"""Interactive repair may outlive two hours; finite setup still has deadlines."""
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import test_client_update as fixtures

client_setup = fixtures.client_setup
client_update = fixtures.client_update
supervisor = fixtures.supervisor


class VirtualSupervisor(fixtures.FakeSupervisor):
    """Use the production wait loop with a clock instead of spending hours."""
    INTERACTIVE = ('playonline.log', 'playonline-wait.log')

    def __init__(self, session, clock, stop_phase=None, waiter_exit=0, **kwargs):
        super().__init__(session, **kwargs)
        self.clock = clock
        self.stop_phase = stop_phase
        self.waiter_exit = waiter_exit
        self.budgets = {}
        self.durations = {}
        self.active_phase = None
        self.phase_started = 0

    def stopped(self):
        if self.active_phase == self.stop_phase and self.clock[0] - self.phase_started > 7200:
            raise supervisor.Stopped()

    def wait(self, proc, timeout, label, accepted=(0,)):
        self.budgets[proc.name] = timeout
        if proc.name not in self.INTERACTIVE:
            return super().wait(proc, timeout, label, accepted)
        self.active_phase = proc.name
        self.phase_started = self.clock[0]
        # Both the original viewer and detached restart stay alive well beyond
        # the former independent 7,200-second limits before exiting normally.
        exit_at = self.phase_started + 18000
        result = self.waiter_exit if proc.name == 'playonline-wait.log' else int(bool(self.child_exit))

        def poll():
            if self.clock[0] >= exit_at:
                proc.returncode = result
            return proc.returncode

        proc.poll = poll
        try:
            supervisor.Supervisor.wait(self, proc, timeout, label, accepted)
        finally:
            self.durations[proc.name] = self.clock[0] - self.phase_started
        # Fixture emits the real-shaped Windows receipt after process exit.
        return super().wait(proc, timeout, label, accepted)


class UpdateLifetime(unittest.TestCase):
    def run_update(self, root, **options):
        client, session, logs, _ = fixtures.UpdateContracts().fixture(root)
        clock = [100.0]
        instance = VirtualSupervisor(session, clock, **options)

        def advance(_seconds):
            clock[0] += 4000

        with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), \
                patch.object(client_update, 'LOGS', logs), \
                patch.object(supervisor.time, 'monotonic', side_effect=lambda: clock[0]), \
                patch.object(supervisor.time, 'sleep', side_effect=advance):
            error = None
            try:
                client_update.run(instance)
            except (RuntimeError, supervisor.Stopped) as caught:
                error = caught
        return instance, json.loads((logs / 'client-update.json').read_text()), error

    def test_original_and_restarted_viewer_finish_after_two_hours_without_activation(self):
        with tempfile.TemporaryDirectory() as tmp:
            instance, report, error = self.run_update(Path(tmp))
        self.assertIsNone(error)
        self.assertEqual(instance.durations, {'playonline.log': 20000, 'playonline-wait.log': 20000})
        self.assertEqual(report['viewer_elapsed_ms'], 20000000)
        self.assertEqual(report['status'], 'verification_pending')
        self.assertFalse(report['activation_performed'])
        self.assertFalse(report['official_repair_confirmed'])
        self.assertEqual(instance.budgets, {'update-registry.log': 90, 'playonline-components.log': 180,
                                         'playonline-dependencies.log': 90,
                                         'playonline.log': None, 'playonline-wait.log': None})

    def test_stop_interrupts_original_and_restarted_viewer_after_two_hours(self):
        for phase in VirtualSupervisor.INTERACTIVE:
            with self.subTest(phase=phase), tempfile.TemporaryDirectory() as tmp:
                instance, report, error = self.run_update(Path(tmp), stop_phase=phase)
            self.assertIsInstance(error, supervisor.Stopped)
            self.assertEqual(instance.durations[phase], 8000)
            self.assertEqual(report['status'], 'interrupted')
            self.assertFalse(any(state['phase'] == 'completed' for state in instance.states))
            if phase == 'playonline.log':
                self.assertNotIn('playonline-wait.log', instance.budgets)
            else:
                self.assertIsNone(report['prefix_wait_exit_code'])

    def test_dead_viewer_still_waits_for_restart_and_reports_failed_windows_exit(self):
        with tempfile.TemporaryDirectory() as tmp:
            instance, report, error = self.run_update(Path(tmp), child_exit=0xc0000005)
        self.assertIsInstance(error, RuntimeError)
        self.assertIn('Windows exit 0xC0000005', str(error))
        self.assertEqual(instance.durations['playonline-wait.log'], 20000)
        self.assertEqual(report['status'], 'interrupted')
        self.assertFalse(any(state['phase'] == 'completed' for state in instance.states))

    def test_failed_restart_wait_is_not_reported_as_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            instance, report, error = self.run_update(Path(tmp), waiter_exit=5)
        self.assertIsInstance(error, RuntimeError)
        self.assertIn('restarted viewer exited with code 5', str(error))
        self.assertEqual(report['prefix_wait_exit_code'], 5)
        self.assertEqual(report['status'], 'interrupted')
        self.assertFalse(any(state['phase'] == 'completed' for state in instance.states))

    def test_bounded_setup_wait_still_times_out(self):
        instance = object.__new__(supervisor.Supervisor)
        instance.stopped = lambda: None
        process = SimpleNamespace(poll=lambda: None, returncode=None)
        with patch.object(supervisor.time, 'monotonic', side_effect=[100, 100, 191]), \
                patch.object(supervisor.time, 'sleep') as sleep:
            with self.assertRaisesRegex(RuntimeError, 'Dependency check timed out'):
                instance.wait(process, 90, 'Dependency check')
        sleep.assert_called_once_with(.15)

    def test_stop_file_remains_responsive_during_unlimited_wait(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = Path(tmp)
            instance = object.__new__(supervisor.Supervisor)
            process = SimpleNamespace(poll=lambda: None, returncode=None)
            with patch.object(supervisor, 'SESSION', session), patch.object(supervisor, 'STOP', False), \
                    patch.object(supervisor.time, 'sleep', side_effect=lambda _: (session / 'stop').touch()) as sleep:
                with self.assertRaises(supervisor.Stopped):
                    instance.wait(process, None, 'PlayOnline viewer')
            sleep.assert_called_once_with(.15)


if __name__ == '__main__':
    unittest.main()
