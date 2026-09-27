import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime'))
import repair_performance as perf


class RepairPerformanceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.proc = Path(self.temporary.name)
        self.now = 0.0
        self.process(100, name='python3', ppid=10, start=1, tracer=10)
        self.process(10, name='private-tracer-name', start=2)
        self.monitor = perf.Monitor(root_pid=100, proc_root=self.proc, clock=lambda: self.now)
        self.addCleanup(self.monitor.stop)

    def process(self, pid, name='pol.exe', ppid=1, start=3, ticks=0,
                tracer=10, uid=1234, io=True, amount=0, state='S'):
        folder = self.proc / str(pid)
        folder.mkdir(exist_ok=True)
        fields = [state] + ['0'] * 30
        for index, value in [(1, ppid), (11, ticks), (12, 0), (17, 2), (19, start)]:
            fields[index] = str(value)
        (folder / 'stat').write_text(str(pid) + ' (' + name + ') ' + ' '.join(fields))
        (folder / 'status').write_text('Name:\tprivate-name\nUid:\t%d\t%d\t%d\t%d\nTracerPid:\t%d\n' % (uid, uid, uid, uid, tracer))
        if io:
            (folder / 'io').write_text('\n'.join(k + ': ' + str(amount) for k in perf._IO))
        else:
            (folder / 'io').unlink(missing_ok=True)
        (folder / 'cmdline').write_text('secret-account')
        (folder / 'environ').write_text('secret-password')

    def sample(self, seconds=5):
        self.monitor.sample()
        self.now += seconds
        return self.monitor.snapshot()['samples'][-1]

    def test_restart_reparent_and_deltas_cover_replacement(self):
        self.process(200, ppid=100, ticks=1)
        self.sample()
        self.sample()
        self.process(200, ppid=1, ticks=11, amount=40)
        row = self.sample()['categories']['viewer']
        self.assertEqual(row['cpu_delta_ms'], 10 * 1000 // self.monitor.tick_hz)
        self.assertEqual(row['io_delta']['rchar'], 40)
        shutil.rmtree(self.proc / '200')
        self.process(300, ppid=1, start=4, ticks=200)
        self.sample()  # Discovery at t=15 identifies a detached replacement.
        row = self.sample()['categories']['viewer']
        self.assertIsNone(row['cpu_delta_ms'])
        self.process(300, ppid=1, start=4, ticks=205)
        row = self.sample()['categories']['viewer']
        self.assertEqual(row['cpu_delta_ms'], 5 * 1000 // self.monitor.tick_hz)

    def test_ancestry_fallback_keeps_observed_reparent_not_unrelated(self):
        self.process(100, name='python3', ppid=1, start=1, tracer=0)
        self.process(200, ppid=100, tracer=0)
        self.process(300, ppid=1, tracer=0)
        self.sample()
        self.process(200, ppid=1, tracer=0)
        row = self.sample()
        self.assertEqual(row['scope'], 'observed_ancestry_only')
        self.assertEqual(row['categories']['viewer']['processes'], 1)
        self.assertNotIn(300, self.monitor._known)

    def test_uid_tracer_and_reuse_guard_scope(self):
        self.process(200, ppid=1, uid=999, tracer=10)
        self.process(300, ppid=1, uid=1234, tracer=77)
        self.process(400, ppid=100, start=8)
        self.sample()
        self.sample()
        self.process(400, ppid=1, start=9, tracer=77, ticks=99999)
        row = self.sample()
        self.assertNotIn('viewer', row['categories'])
        self.assertNotIn(200, self.monitor._known)
        self.assertNotIn(300, self.monitor._known)
        self.assertNotIn(400, self.monitor._known)
        self.process(10, name='unrelated', start=999)
        self.process(500, ppid=1, tracer=10)
        row = self.sample()
        self.assertEqual(row['scope'], 'observed_ancestry_only')
        self.assertNotIn(500, self.monitor._known)

    def test_fixed_labels_no_private_names_and_io_missing_is_unavailable(self):
        self.process(200, name='secret-character', ppid=100, io=False)
        self.sample()
        row = self.sample()
        self.assertEqual(row['categories']['other']['io_available_processes'], 0)
        self.assertIsNone(row['categories']['other']['io_delta'])
        text = json.dumps(self.monitor.snapshot())
        for value in ('secret', str(self.proc), 'private-name', 'private-tracer-name'):
            self.assertNotIn(value, text)
        with patch.object(perf, '_read', wraps=perf._read) as read:
            self.sample()
            self.assertTrue(all(call.args[0].name in ('stat', 'status', 'io') for call in read.call_args_list))

    def test_anchor_unavailable_does_not_present_zero_cpu(self):
        (self.proc / '100' / 'status').unlink()
        self.monitor.sample()
        report = self.monitor.snapshot()
        self.assertEqual(report['state'], 'anchor_unavailable')
        self.assertEqual(report['failed_samples'], 1)
        self.assertEqual(report['samples'], [])

    def test_cursor_fairness_and_known_first_under_limits(self):
        for pid in range(1000, 1020):
            self.process(pid, tracer=0, uid=999)
        self.process(99999, ppid=1)
        with patch.object(perf, 'MAX_SCAN_ENTRIES', 3):
            for _ in range(12):
                self.sample(seconds=15)
            self.assertIn(99999, self.monitor._known)
            row = self.sample()
            self.assertEqual(row['categories']['viewer']['processes'], 1)

    def test_process_count_and_rolling_report_are_bounded(self):
        for pid in range(1000, 1100):
            self.process(pid, ppid=100)
        self.sample()
        self.assertLessEqual(len(self.monitor._known), perf.MAX_PROCESSES)
        self.assertEqual(self.monitor.snapshot()['samples'][-1]['discovery']['state'], 'bounded')
        for _ in range(perf.MAX_SAMPLES + 4):
            self.sample()
        report = self.monitor.snapshot()
        self.assertLessEqual(len(report['samples']), perf.MAX_SAMPLES)
        self.assertGreater(report['samples_recorded'], perf.MAX_SAMPLES)
        self.assertLessEqual(len(json.dumps(report, separators=(',', ':')).encode()), perf.MAX_REPORT_BYTES)

    def test_proc_identity_race_never_adds_foreign_counters(self):
        self.process(200, ppid=100)
        self.sample()
        self.sample()
        original = perf._stat
        calls = [0]
        def race(path):
            row = original(path)
            if path.parent.name == '200':
                calls[0] += 1
                if calls[0] == 2:
                    row['start'] += 1
            return row
        with patch.object(perf, '_stat', side_effect=race):
            row = self.sample()
        self.assertNotIn('viewer', row['categories'])

    def test_budget_is_reported_and_anchor_sampling_survives_crowded_proc(self):
        ticks = [0.0]
        def advancing():
            ticks[0] += 0.025
            return ticks[0]
        self.monitor.clock = advancing
        self.monitor.sample()
        row = self.monitor.snapshot()['samples'][-1]
        self.assertEqual(row['discovery']['state'], 'bounded')
        self.assertGreaterEqual(row['sampling_ms'], 100)
        self.assertIn('supervisor', row['categories'])

    def test_slow_discovery_read_enrolls_match_before_budget_exhaustion(self):
        # Cover both a detached same-tracer replacement and a direct child
        # when Android makes TracerPid unavailable (reported as zero).
        for shared_tracer in (True, False):
            with self.subTest(shared_tracer=shared_tracer):
                self.now = 0.0
                tracer = 10 if shared_tracer else 0
                self.process(100, name='python3', ppid=1, start=1, tracer=tracer)
                self.process(200, ppid=1 if shared_tracer else 100, tracer=tracer)
                self.process(201, ppid=1, tracer=tracer)
                monitor = perf.Monitor(root_pid=100, proc_root=self.proc, clock=lambda: self.now)
                self.addCleanup(monitor.stop)
                original = monitor._record
                def slow_record(pid):
                    value = original(pid)
                    if pid == 200:
                        self.now += perf.BUDGET_SECONDS + 0.01
                    return value
                # First eligible record finishes after budget. Retain it even
                # though the next candidate cannot be inspected this cycle.
                class Entries:
                    def __init__(self):
                        self.items = iter([type('Entry', (), {'name': str(pid)})() for pid in (200, 201)])
                    def __next__(self):
                        return next(self.items)
                    def close(self):
                        pass
                with patch.object(perf.os, 'scandir', return_value=Entries()), patch.object(monitor, '_record', side_effect=slow_record):
                    monitor.sample()
                self.assertIn(200, monitor._known)
                self.assertNotIn(201, monitor._known)
                self.assertEqual(monitor.snapshot()['samples'][-1]['discovery']['state'], 'bounded')
                self.now += 5
                monitor.sample()
                row = monitor.snapshot()['samples'][-1]
                self.assertEqual(row['categories']['viewer']['processes'], 1)

    def test_periodic_receipt_is_atomic_bounded_and_has_session(self):
        output = self.proc / 'repair-performance.json'
        monitor = perf.Monitor(root_pid=100, proc_root=self.proc, clock=lambda: self.now,
                               session_id='fixture-identity', output_path=output)
        self.addCleanup(monitor.stop)
        monitor.sample()
        monitor._persist()
        report = json.loads(output.read_text())
        self.assertEqual(report['session_id'], 'fixture-identity')
        self.assertEqual(report['samples_recorded'], 1)
        self.assertLessEqual(output.stat().st_size, perf.MAX_REPORT_BYTES)
        self.assertFalse((self.proc / 'repair-performance.json.new').exists())
        monitor.output_path = self.proc / 'missing' / 'receipt.json'
        monitor._persist()
        self.assertEqual(monitor.snapshot()['write_failures'], 1)


if __name__ == '__main__':
    unittest.main()
