import json
from pathlib import Path
import sys
import tempfile
import unittest
import uuid
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime'))
import fex_runtime
import supervisor


class Clock:
    def __init__(self): self.now = 0.0
    def monotonic(self): return self.now
    def sleep(self, seconds): self.now += seconds


class Process:
    def __init__(self, clock, duration, exit_code=0):
        self.clock, self.duration, self.exit_code = clock, duration, exit_code
        self.returncode = None
    def poll(self):
        if self.clock.now >= self.duration: self.returncode = self.exit_code
        return self.returncode


class PrefixInitialization(unittest.TestCase):
    def instance(self, engine='fex', action='update-client'):
        return supervisor.Supervisor(dict(format=1, renderer='software', audio=False,
            session_id=str(uuid.uuid4()), engine=engine, action=action))

    def run_cold(self, folder, duration, exit_code=0, stop_at=None, log=''):
        clock = Clock(); proc = Process(clock, duration, exit_code)
        s = self.instance(); s.status = Mock(); s.spawn = Mock(return_value=proc)
        s.logs = [Mock()]; (folder / 'wine-setup.log').write_text(log)
        s.stopped = Mock()
        if stop_at is not None:
            def stopped():
                if clock.now >= stop_at: raise supervisor.Stopped()
            s.stopped.side_effect = stopped
        error = None
        with patch.object(supervisor, 'PREFIX', folder), \
             patch.object(supervisor, 'LOGS', folder), \
             patch.object(supervisor.time, 'monotonic', clock.monotonic), \
             patch.object(supervisor.time, 'sleep', clock.sleep), \
             patch.object(fex_runtime, 'refresh_host_builtins', return_value=['host.dll']):
            try: s.prepare_prefix(False)
            except (supervisor.Stopped, RuntimeError) as caught: error = caught
        return s, clock, error

    def test_cold_updater_updates_once_and_preserves_copied_settings(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t); prefix = root / 'candidate'; prefix.mkdir()
            accepted = root / 'accepted'; accepted.mkdir()
            for folder in (prefix, accepted):
                (folder / '.update-timestamp').write_text('1234\n')
                (folder / 'user.reg').write_text('preserved settings')
            s, clock, error = self.run_cold(prefix, 181.4)
            self.assertIsNone(error)
            self.assertEqual(s.spawn.call_args.args,
                (['/opt/wine/bin/wine', 'wineboot', '-i'], 'wine-setup.log'))
            self.assertFalse((prefix / '.update-timestamp').exists())
            self.assertEqual((accepted / '.update-timestamp').read_text(), '1234\n')
            for folder in (prefix, accepted):
                self.assertEqual((folder / 'user.reg').read_text(), 'preserved settings')
            self.assertFalse((prefix / 'lsb-prefix-ready.json').exists())
            reports = [call.kwargs['prefix_initialization'] for call in s.status.call_args_list]
            self.assertEqual(reports[-1]['state'], 'completed')
            self.assertEqual(reports[-1]['exit_code'], 0)
            self.assertGreaterEqual(reports[-1]['elapsed_ms'], 181400)
            self.assertGreater(len(reports), 30)
            self.assertTrue(all(b['elapsed_ms'] - a['elapsed_ms'] <= 5200
                for a, b in zip(reports, reports[1:])))

    def test_slow_conversion_still_finishes_but_hang_is_bounded_and_stop_works(self):
        with tempfile.TemporaryDirectory() as t:
            prefix = Path(t)
            for duration, code, stop, expected in [(300, 0, None, 'completed'),
                    (900, 0, None, 'failed'), (900, 0, 11, 'stopped'),
                    (3, 5, None, 'failed')]:
                with self.subTest(duration=duration, code=code, stop=stop):
                    (prefix / '.update-timestamp').write_text('interrupted conversion')
                    s, clock, error = self.run_cold(prefix, duration, code, stop)
                    report = s.status.call_args.kwargs['prefix_initialization']
                    self.assertEqual(report['state'], expected)
                    self.assertEqual(report['timeout_seconds'], 600)
                    self.assertLess(clock.now, 601)
                    self.assertFalse((prefix / 'lsb-prefix-ready.json').exists())
                    self.assertFalse((prefix / '.update-timestamp').exists())
                    if expected == 'completed': self.assertIsNone(error)
                    elif expected == 'stopped': self.assertIsInstance(error, supervisor.Stopped)
                    else: self.assertIsInstance(error, RuntimeError)

    def test_wine_internal_bootstrap_timeout_is_not_success(self):
        with tempfile.TemporaryDirectory() as t:
            prefix = Path(t)
            s, clock, error = self.run_cold(prefix, 301, log='err:environ:run_wineboot boot event wait timed out\n')
            self.assertIsInstance(error, RuntimeError)
            self.assertEqual(s.status.call_args.kwargs['prefix_initialization']['state'], 'failed')
            self.assertEqual(s.status.call_args.kwargs['prefix_initialization']['exit_code'], 0)
            self.assertFalse((prefix / 'lsb-prefix-ready.json').exists())

    def test_existing_ready_prefix_gameplay_and_box64_behavior_is_unchanged(self):
        with tempfile.TemporaryDirectory() as t:
            prefix = Path(t); stamp = prefix / '.update-timestamp'
            for engine, action, ready in [('fex', 'update-client', True),
                    ('fex', 'launch', False), ('fex', 'probe', False),
                    ('box64', 'update-client', False), ('box64', 'update-client', True)]:
                with self.subTest(engine=engine, action=action, ready=ready), \
                     patch.object(supervisor, 'PREFIX', prefix), \
                     patch.object(fex_runtime, 'refresh_host_builtins', return_value=[]) as refresh:
                    stamp.write_text('preserved')
                    s = self.instance(engine, action); s.wine = Mock(); s.spawn = Mock()
                    s.prepare_prefix(ready)
                    s.wine.assert_called_once_with(['wineboot', '-i' if ready else '-u'],
                        240, 'Fresh Windows prefix initialization')
                    self.assertEqual(stamp.read_text(), 'preserved')
                    self.assertEqual(refresh.call_count, int(engine == 'fex' and not ready))
                    s.spawn.assert_not_called()

    def test_ready_marker_waits_for_pe32_files_and_actual_fex_execution(self):
        class AfterPrefix(Exception): pass
        with tempfile.TemporaryDirectory() as t:
            root = Path(t)
            for name in ('session', 'logs', 'prefix'): (root / name).mkdir()
            prefix = root / 'prefix'; session = root / 'session'
            # start() checks existence after starting X; no actual socket used.
            (session / 'display.sock').touch()
            signature = {'format': 1, 'runtime': '1' * 64}
            marker = prefix / 'lsb-prefix-ready.json'
            for pe_ok, fex_ok in [(False, False), (True, False), (True, True)]:
                with self.subTest(pe_ok=pe_ok, fex_ok=fex_ok), \
                     patch.object(supervisor, 'PREFIX', prefix), \
                     patch.object(supervisor, 'SESSION', session), \
                     patch.object(supervisor, 'LOGS', root / 'logs'), \
                     patch.object(supervisor, 'verify_bundle', return_value={}), \
                     patch.object(supervisor, 'pe32', return_value=pe_ok), \
                     patch.object(fex_runtime, 'verify', return_value={'sha256': '1' * 64}), \
                     patch.object(fex_runtime, 'select_translator'), \
                     patch.object(fex_runtime, 'check', side_effect=None if fex_ok else RuntimeError('execution failed')) as proof:
                    marker.unlink(missing_ok=True)
                    s = self.instance(); s.prepare_prefix = Mock(); s.wait = Mock()
                    s.start_native_surface = Mock(); s.status = Mock(); s.stopped = Mock()
                    s.spawn = Mock(return_value=Mock(poll=Mock(return_value=None)))
                    s.graphics = Mock(side_effect=AfterPrefix)
                    with self.assertRaises(AfterPrefix if pe_ok and fex_ok else RuntimeError): s.start()
                    s.prepare_prefix.assert_called_once_with(False)
                    self.assertEqual(proof.call_count, int(pe_ok))
                    if pe_ok and fex_ok: self.assertEqual(json.loads(marker.read_text()), signature)
                    else: self.assertFalse(marker.exists())


if __name__ == '__main__': unittest.main()
