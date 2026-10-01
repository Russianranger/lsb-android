import copy
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime'))
import repair_io


class RepairIoTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.session = Path(self.temporary.name)
        self.root = self.session / 'repair-io'
        self.stop = lambda: None

    def valid(self):
        return {'format': 1, 'bits': 32, 'state': 'completed', 'qpc_frequency': 10000000,
                'elapsed_ms': 200, 'phases': [dict(name=name, operations=count, bytes=size,
                    qpc_us=100, tick_ms=1, cpu_user_us=20, cpu_kernel_us=5, cpu_error=0, error=0, timed_out=False)
                    for name, count, size in zip(repair_io.NAMES, repair_io.COUNTS, repair_io.BYTES)]}

    def test_native_fixture_work_and_no_client_file_access(self):
        private = self.session / 'client-secret'; private.write_text('account password')
        repair_io.fixtures(self.root, self.stop)
        result = repair_io.native(self.root, self.stop)
        self.assertEqual(result['state'], 'completed')
        self.assertEqual([x['operations'] for x in result['phases']], list(repair_io.COUNTS))
        self.assertEqual([x['bytes'] for x in result['phases']], list(repair_io.BYTES))
        self.assertGreaterEqual(result['phases'][5]['elapsed_us'], 60000)
        self.assertEqual(private.read_text(), 'account password')
        self.assertNotIn('secret', json.dumps(result))
        self.assertEqual(len(list((self.root / 'case').iterdir())), 256)

    def test_missing_or_corrupt_fixture_and_deadline(self):
        repair_io.fixtures(self.root, self.stop)
        (self.root / 'f00.bin').write_bytes(bytes(4096))
        bad = repair_io.native(self.root, self.stop)
        self.assertEqual(bad['state'], 'failed')
        self.assertEqual(bad['phases'][-1]['name'], 'small_reads')
        self.assertNotEqual(bad['phases'][-1]['error'], 0)
        timed = repair_io.native(self.root, self.stop, budget=0)
        self.assertEqual(timed['state'], 'timed_out')
        self.assertEqual(len(timed['phases']), 1)
        self.assertTrue(timed['phases'][0]['timed_out'])

    def test_existing_fixture_is_never_merged_or_followed(self):
        outside = self.session / 'outside'; outside.mkdir()
        (outside / 'preserve').write_text('keep')
        self.root.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(FileExistsError):
            repair_io.fixtures(self.root, self.stop)
        self.assertEqual(list(outside.iterdir()), [outside / 'preserve'])

    def test_cancellation_propagates(self):
        class Cancelled(Exception): pass
        def stop(): raise Cancelled()
        with self.assertRaises(Cancelled):
            repair_io.fixtures(self.root, stop)

    def test_strict_receipt_types_order_partial_and_private_fields(self):
        good = self.valid(); self.assertEqual(repair_io.validate(good), good)
        changes = [lambda x: x.update(format=True), lambda x: x.update(bits=32.0),
                   lambda x: x.update(qpc_frequency=False), lambda x: x.update(private_path='secret'),
                   lambda x: x['phases'][0].update(name='private'),
                   lambda x: x['phases'][0].update(operations=999999),
                   lambda x: x['phases'][0].update(qpc_us=-1),
                   lambda x: x['phases'][0].update(error=True),
                   lambda x: x['phases'][0].update(timed_out=1),
                   lambda x: x['phases'].pop(),
                   lambda x: x.update(state='timed_out')]
        for change in changes:
            bad = copy.deepcopy(good); change(bad)
            with self.assertRaises(ValueError): repair_io.validate(bad)
        partial = copy.deepcopy(good); partial['phases'] = partial['phases'][:2]
        partial['state'] = 'failed'; partial['phases'][-1].update(error=2, operations=31)
        self.assertEqual(repair_io.validate(partial), partial)
        partial['state'] = 'timed_out'; partial['phases'][-1].update(error=0, timed_out=True)
        self.assertEqual(repair_io.validate(partial), partial)

    def test_receipt_rejects_symlink_fifo_oversize_and_invalid(self):
        path = self.session / 'repair-io-result.json'
        with patch.object(repair_io, 'SESSION', self.session):
            path.write_text(json.dumps(self.valid()))
            self.assertIsNotNone(repair_io.receipt())
            path.write_text(' ' * 8193); self.assertIsNone(repair_io.receipt())
            path.unlink(); path.symlink_to(self.session / 'outside'); self.assertIsNone(repair_io.receipt())
            path.unlink(); os.mkfifo(path); self.assertIsNone(repair_io.receipt())

    def test_runner_uses_fixed_command_and_reports_real_parent_time(self):
        valid = self.valid()
        class Process:
            returncode = 0
            def poll(self): return 0
        class Supervisor:
            stopped = staticmethod(lambda: None)
            def wine_command(self, *args): return ['wine', *args]
            def spawn(this, args, name, env):
                self.assertEqual(args, ['wine', r'P:\repair-io.exe'])
                self.assertEqual(env['WINEDEBUG'], '-all')
                (self.session / 'repair-io-result.json').write_text(json.dumps(valid))
                return Process()
        with patch.object(repair_io, 'SESSION', self.session):
            result = repair_io.run(Supervisor(), {'WINEDEBUG': '+all'})
        self.assertEqual(result['status'], 'completed')
        self.assertEqual(result['windows'], valid)
        self.assertGreaterEqual(result['elapsed_ms'], 60)
        self.assertNotIn(str(self.session), json.dumps(result))

    def test_parent_deadline_kills_owned_group_and_retains_partial_receipt(self):
        clock = [0.0]
        partial = self.valid(); partial['state'] = 'running'; partial['phases'] = partial['phases'][:2]
        class Process:
            pid = 2345
            alive = True
            def poll(self): clock[0] = 21.0; return None if self.alive else -9
            def wait(self, timeout): self.alive = False; return -9
        process = Process()
        class Supervisor:
            stopped = staticmethod(lambda: None)
            wine_command = staticmethod(lambda *args: ['wine', *args])
            spawn = staticmethod(lambda *args, **kwargs: process)
        with patch.object(repair_io, 'SESSION', self.session), patch.object(repair_io, 'fixtures'), \
                patch.object(repair_io, 'native', return_value={'state': 'completed'}), \
                patch.object(repair_io, 'receipt', return_value=partial), \
                patch.object(repair_io.time, 'monotonic', side_effect=lambda: clock[0]), \
                patch.object(repair_io.os, 'killpg') as kill:
            result = repair_io.run(Supervisor(), {})
        self.assertEqual(result['status'], 'timed_out')
        self.assertEqual(result['windows'], partial)
        self.assertFalse(process.alive)
        kill.assert_called_once_with(process.pid, repair_io.signal.SIGKILL)

    def test_cancellation_after_spawn_kills_owned_helper_and_propagates(self):
        class Cancelled(Exception): pass
        class Process:
            pid = 2346
            alive = True
            def poll(self): return None if self.alive else -9
            def wait(self, timeout): self.alive = False; return -9
        process = Process()
        class Supervisor:
            calls = 0
            def stopped(self):
                self.calls += 1
                if self.calls == 2: raise Cancelled()
            wine_command = staticmethod(lambda *args: ['wine', *args])
            spawn = staticmethod(lambda *args, **kwargs: process)
        with patch.object(repair_io, 'SESSION', self.session), patch.object(repair_io, 'fixtures'), \
                patch.object(repair_io, 'native', return_value={'state': 'completed'}), \
                patch.object(repair_io.os, 'killpg') as kill:
            with self.assertRaises(Cancelled): repair_io.run(Supervisor(), {})
        self.assertFalse(process.alive)
        kill.assert_called_once_with(process.pid, repair_io.signal.SIGKILL)


if __name__ == '__main__':
    unittest.main()
