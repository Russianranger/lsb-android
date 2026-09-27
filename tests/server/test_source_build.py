"""Safety and linkage contracts for compiling an imported server revision."""

import contextlib
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


spec = importlib.util.spec_from_file_location(
    'source_build_manager', Path(__file__).resolve().parents[2] / 'server/manager.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


def arm64_elf(name):
    header = bytearray(64)
    header[:6] = b'\x7fELF\x02\x01'
    header[18:20] = (183).to_bytes(2, 'little')
    return bytes(header) + name.encode()


def needed_output(libraries):
    return ''.join(' 0x0000000000000001 (NEEDED) Shared library: [' + lib + ']\n'
                   for lib in libraries)


class SourceBuildTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.staging = self.root / 'staging'
        self.staging.mkdir()
        patch = mock.patch.multiple(m, STATE=self.root / 'state', RUN=self.root / 'run',
                                    LOGS=self.root / 'logs', INPUT=self.root / 'input')
        patch.start()
        self.addCleanup(patch.stop)
        for path in (m.STATE, m.RUN, m.LOGS, m.INPUT):
            path.mkdir()

    def make_source(self, root):
        for name in ('src', 'sql', 'settings/default', 'tools', 'data'):
            (root / name).mkdir(parents=True, exist_ok=True)
        (root / 'CMakeLists.txt').write_text('project(server C CXX)\n')
        (root / 'tools/requirements.txt').write_text('jinja2\njsonschema\nruamel.yaml\n')
        (root / 'settings/default/login.lua').write_text("CLIENT_VER = '30260904_1',\n")
        (root / 'data/custom.bin').write_bytes(b'keep imported server assets')
        return root

    def make_binaries(self, root):
        for name in m.PROCESSES:
            (root / name).write_bytes(arm64_elf(name))

    def loader(self, args, **kwargs):
        if args[0] == 'ldd':
            return subprocess.CompletedProcess(args, 0,
                'libjemalloc.so.2 => /usr/lib/aarch64-linux-gnu/libjemalloc.so.2 (0x1)\n'
                'libc.so.6 => /lib/aarch64-linux-gnu/libc.so.6 (0x2)\n', '')
        if args[0] == 'readelf':
            return subprocess.CompletedProcess(args, 0,
                needed_output(['libjemalloc.so.2', 'libstdc++.so.6', 'libc.so.6']), '')
        self.fail('Unexpected subprocess: ' + str(args))

    def fake_compile(self, args, **kwargs):
        if args[:2] == ['cmake', '--build']:
            self.make_binaries(Path(args[2]).parent)

    def test_allocator_evidence_records_all_four_exact_binaries(self):
        self.make_binaries(self.staging)
        with mock.patch.object(m.subprocess, 'run', side_effect=self.loader) as inspect:
            report = m.validate_jemalloc(self.staging)
        self.assertEqual(set(report), set(m.PROCESSES))
        for name, evidence in report.items():
            payload = (self.staging / name).read_bytes()
            self.assertEqual(evidence['sha256'], hashlib.sha256(payload).hexdigest())
            self.assertEqual(evidence['bytes'], len(payload))
            self.assertEqual(evidence['allocator'], 'libjemalloc.so.2')
            self.assertLess(evidence['needed'].index('libjemalloc.so.2'),
                            evidence['needed'].index('libc.so.6'))
        self.assertEqual(inspect.call_count, 4)

    def test_allocator_missing_wrong_order_or_inspection_failure_rejects_build(self):
        self.make_binaries(self.staging)
        cases = (
            ('missing', subprocess.CompletedProcess([], 0, needed_output(['libc.so.6']), ''),
             'without a shared jemalloc'),
            ('wrong-order', subprocess.CompletedProcess([], 0,
             needed_output(['libc.so.6', 'libjemalloc.so.2']), ''), 'libc before jemalloc'),
            ('readelf-error', subprocess.CompletedProcess([], 1,
             needed_output(['libjemalloc.so.2', 'libc.so.6']), 'bad ELF'), 'Cannot inspect'),
        )
        for name, outcome, message in cases:
            with self.subTest(name=name), mock.patch.object(m.subprocess, 'run', return_value=outcome):
                with self.assertRaisesRegex(RuntimeError, message):
                    m.validate_jemalloc(self.staging)
        with mock.patch.object(m.subprocess, 'run', side_effect=subprocess.TimeoutExpired('readelf', 30)):
            with self.assertRaises(subprocess.TimeoutExpired):
                m.validate_jemalloc(self.staging)

    def test_missing_allocator_in_last_binary_cannot_pass_partial_validation(self):
        self.make_binaries(self.staging)
        def loader(args, **kwargs):
            if Path(args[-1]).name == m.PROCESSES[-1]:
                return subprocess.CompletedProcess(args, 0, needed_output(['libc.so.6']), '')
            return self.loader(args, **kwargs)
        with mock.patch.object(m.subprocess, 'run', side_effect=loader):
            with self.assertRaisesRegex(RuntimeError, m.PROCESSES[-1]):
                m.validate_jemalloc(self.staging)

    def test_build_uses_dependency_venv_low_memory_options_and_four_targets(self):
        self.make_source(self.staging)
        with mock.patch.object(m, 'command', side_effect=self.fake_compile) as command, \
             mock.patch.object(m.subprocess, 'run', side_effect=self.loader):
            report = m.build(self.staging, 2)
        configure = next(call.args[0] for call in command.call_args_list
                         if call.args[0][:2] == ['cmake', '-S'])
        self.assertIn('-DPython_EXECUTABLE=' + str(self.staging / '.venv/bin/python'), configure)
        self.assertIn('-DENABLE_IPO=OFF', configure)
        self.assertIn('-DPCH_ENABLE=OFF', configure)
        self.assertIn('-DCMAKE_C_COMPILER=gcc-15', configure)
        self.assertIn('-DCMAKE_CXX_COMPILER=g++-15', configure)
        self.assertTrue(any(option.startswith('-DCMAKE_PROJECT_TOP_LEVEL_INCLUDES=')
                            for option in configure if isinstance(option, str)))
        compile_call = next(call for call in command.call_args_list
                            if call.args[0][:2] == ['cmake', '--build'])
        args = compile_call.args[0]
        self.assertEqual(args[args.index('--target') + 1:], list(m.PROCESSES))
        self.assertEqual(args[args.index('--parallel') + 1], '2')
        self.assertIn(mock.call([self.staging / '.venv/bin/pip', 'install', '-r',
                                self.staging / 'tools/requirements.txt'], cwd=self.staging,
                               timeout=1800), command.call_args_list)
        self.assertEqual(report['state'], 'passed')
        self.assertEqual(report['source']['expected_client'], '30260904_1')
        self.assertEqual(set(report['binaries']), set(m.PROCESSES))
        self.assertEqual(json.loads((m.LOGS / 'build-report.json').read_text()), report)
        self.assertEqual(json.loads((self.staging / 'android-build.json').read_text()), report)

    def test_linkage_failure_or_cancellation_never_publishes_success_report(self):
        self.make_source(self.staging)
        failures = (RuntimeError('xi_map built without jemalloc'), InterruptedError('stopped'))
        for error in failures:
            with self.subTest(error=type(error).__name__), \
                 mock.patch.object(m, 'command', side_effect=self.fake_compile), \
                 mock.patch.object(m, 'validate_binaries'), \
                 mock.patch.object(m, 'validate_jemalloc', side_effect=error):
                with self.assertRaises(type(error)):
                    m.build(self.staging, 2)
            report = json.loads((m.LOGS / 'build-report.json').read_text())
            self.assertEqual(report['state'], 'stopped' if isinstance(error, InterruptedError) else 'failed')
            self.assertIn(str(error), report['error'])
            self.assertFalse((self.staging / 'android-build.json').exists())

    def test_missing_one_build_output_is_rejected_before_linkage_validation(self):
        self.make_source(self.staging)
        def compile(args, **kwargs):
            self.fake_compile(args, **kwargs)
            if args[:2] == ['cmake', '--build']:
                (self.staging / 'xi_map').unlink()
        with mock.patch.object(m, 'command', side_effect=compile), \
             mock.patch.object(m, 'validate_binaries') as validate:
            with self.assertRaisesRegex(RuntimeError, 'Build did not produce xi_map'):
                m.build(self.staging, 2)
            validate.assert_not_called()
        self.assertEqual(json.loads((m.LOGS / 'build-report.json').read_text())['state'], 'failed')

    def test_compile_only_success_and_failure_preserve_database_active_pair_and_input(self):
        source = self.make_source(m.INPUT / 'server')
        # A previous binary/report in the import must not count as a fresh build.
        for name in m.PROCESSES:
            (source / name).write_bytes(b'old imported binary')
        generation = m.STATE / 'generations/existing'
        (generation / 'database').mkdir(parents=True)
        (generation / 'database/player-data').write_bytes(b'valuable database bytes')
        (generation / 'deployment.json').write_text('{"database":"xidb"}')
        (m.STATE / 'active.json').write_text('{"current":"existing","previous":null}')
        (m.STATE / 'import.sql').write_text('keep this pending database import')
        (m.RUN / 'request.json').write_text('{"action":"build-source","jobs":2}')
        before_input = {p.relative_to(m.INPUT): p.read_bytes()
                        for p in m.INPUT.rglob('*') if p.is_file()}
        before_state = {p.relative_to(m.STATE): p.read_bytes()
                        for p in m.STATE.rglob('*') if p.is_file()}
        cases = (None, RuntimeError('compiler stopped'), InterruptedError('user stopped'))
        for failure in cases:
            with self.subTest(failure=str(failure)), contextlib.ExitStack() as stack:
                for name in ('start_database', 'stop_database', 'import_database', 'sql',
                             'deploy', 'write_network', 'credentials', 'current'):
                    stack.enter_context(mock.patch.object(m, name,
                        side_effect=AssertionError('Compile must not access a deployment/database: ' + name)))
                def command(args, **kwargs):
                    if args[:2] == ['cmake', '--build'] and failure:
                        raise failure
                    self.fake_compile(args, **kwargs)
                stack.enter_context(mock.patch.object(m, 'command', side_effect=command))
                stack.enter_context(mock.patch.object(m.subprocess, 'run', side_effect=self.loader))
                atomic = stack.enter_context(mock.patch.object(m, 'atomic', wraps=m.atomic))
                if failure:
                    with self.assertRaises(type(failure)):
                        m.main()
                else:
                    m.main()
                    status = json.loads((m.RUN / 'status.json').read_text())
                    self.assertEqual(status['phase'], 'build_ready')
                    self.assertEqual(status['build']['state'], 'passed')
                self.assertFalse(any(call.args[0] == m.STATE / 'active.json'
                                     for call in atomic.call_args_list))
            self.assertEqual({p.relative_to(m.INPUT): p.read_bytes()
                              for p in m.INPUT.rglob('*') if p.is_file()}, before_input)
            self.assertEqual({p.relative_to(m.STATE): p.read_bytes()
                              for p in m.STATE.rglob('*') if p.is_file()
                              and 'source-build' not in p.relative_to(m.STATE).parts}, before_state)


if __name__ == '__main__':
    unittest.main()
