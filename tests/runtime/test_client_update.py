import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime'))
import client_setup
import client_update
import supervisor


def pe():
    data = bytearray(1024)
    data[:2] = b'MZ'; struct.pack_into('<I', data, 60, 64)
    data[64:70] = b'PE\0\0\x4c\x01'; struct.pack_into('<H', data, 84, 160)
    data[88:90] = b'\x0b\x01'
    struct.pack_into('<H', data, 70, 1)
    struct.pack_into('<I', data, 192, 4096)
    struct.pack_into('<IIII', data, 256, 512, 4096, 512, 512)
    struct.pack_into('<I', data, 524, 4136)
    data[552:564] = b'PolHook.dll\0'
    return bytes(data)


class FakeSupervisor:
    def __init__(self, session, cancel_wait=False, registry_ok=True, child_exit=0, dependency_error=0,
                 viewer_error=None, missing_receipt=False, malformed_check=False, viewer_output=b'',
                 component_failure=None, component_receipt_changes=None, missing_component_receipt=False):
        self.req = {'session_id': '12345678-1234-1234-1234-123456789abc'}
        self.env = {'WINEPREFIX': '/prefix'}
        self.private_output = False
        self.calls = []; self.waits = []; self.states = []
        self.logs = []
        self.session = session; self.cancel_wait = cancel_wait; self.registry_ok = registry_ok
        self.child_exit = child_exit; self.dependency_error = dependency_error
        self.viewer_error = viewer_error; self.missing_receipt = missing_receipt
        self.malformed_check = malformed_check; self.viewer_output = viewer_output
        self.component_failure = component_failure
        self.component_receipt_changes = component_receipt_changes or {}
        self.missing_component_receipt = missing_component_receipt

    def status(self, phase=None, **fields):
        self.states.append(dict(fields, phase=phase))

    def wine_command(self, *args):
        return ['wine', *args]

    def server_command(self, *args):
        return ['wineserver', *args]

    def spawn(self, args, name, **kwargs):
        self.calls.append((args, name, kwargs, self.private_output))
        events = supervisor.PrivateEvents()
        self.logs.append(SimpleNamespace(events=events, thread=SimpleNamespace(join=lambda seconds: None)))
        process = SimpleNamespace(name=name, args=args, returncode=None)
        process.poll = lambda: process.returncode
        return process

    def stopped(self):
        pass

    def wait(self, proc, timeout, label, accepted=(0,)):
        self.waits.append(proc.name)
        proc.returncode = 0
        if proc.name == 'update-registry.log':
            (self.session / 'client-step.json').write_text(json.dumps({'operation': 'registry', 'ok': self.registry_ok, 'bits': 32}))
        if len(proc.args) > 2 and proc.args[1] == r'P:\client-init.exe' and proc.args[2] in ('register', 'com', 'class'):
            operation = proc.args[2]
            dll = proc.args[-1].split('\\')[-1].lower()
            failed = self.component_failure == (dll, operation)
            proc.returncode = int(failed)
            receipt = {'format': 1, 'bits': 32, 'operation': operation, 'ok': not failed,
                       'hresult': 0x80040154 if failed else 0, 'win32_error': 0,
                       'child_exit': 0, 'loaded_path': proc.args[-1],
                       'detail': 'PRIVATE-COMPONENT-DETAIL account=retail-secret'}
            receipt.update(self.component_receipt_changes)
            if not self.missing_component_receipt:
                (self.session / 'client-step.json').write_text(json.dumps(receipt))
        if proc.name.startswith('playonline-dependencies'):
            proc.returncode = 1 if self.dependency_error else 0
            data = {'format': 1, 'bits': 32, 'check_policy': 'load_only', 'ok': not self.dependency_error,
                    'dependencies': [{'name': 'PolHook.dll', 'ok': not self.dependency_error,
                                      'win32_error': self.dependency_error, 'loaded_path': r'D:\Viewer\PolHook.dll' if not self.dependency_error else ''}]}
            if self.malformed_check: data['dependencies'][0]['name'] = 'wrong.dll'
            (self.session / 'loader-check.json').write_text(json.dumps(data))
        if proc.name == 'playonline.log':
            proc.returncode = 1 if self.child_exit else 0
            self.logs[-1].events.feed(self.viewer_output)
            if not self.missing_receipt:
                (self.session / 'playonline-process.json').write_text(json.dumps({
                    'format': 1, 'bits': 32, 'phase': 'exited', 'win32_error': 0,
                    'child_exit': self.child_exit, 'child_pid': 36, 'window_error': 0,
                    'visible_window_seen': False, 'elapsed_ms': 15}))
            if self.viewer_error: raise self.viewer_error
        if proc.name == 'playonline-wait.log' and self.cancel_wait:
            proc.returncode = None
            raise supervisor.Stopped()
        if proc.returncode not in accepted:
            raise RuntimeError(label + ' exited with code ' + str(proc.returncode))


class UpdateContracts(unittest.TestCase):
    def test_codec_override_is_updater_only_and_preserves_game_environment(self):
        game_environment = {'WINEPREFIX': '/prefix', 'WINEDLLOVERRIDES': 'winegstreamer=;d3d8,d3d9=n',
                            'DXVK_LOG_PATH': '/logs', 'WINEDEBUG': '-all'}
        s = SimpleNamespace(env=dict(game_environment))
        update_environment = client_update.private_environment(s)
        self.assertEqual(update_environment['WINEDLLOVERRIDES'], 'winegstreamer=;d3d8,d3d9=n;ir50_32=')
        self.assertEqual(update_environment['DXVK_LOG_PATH'], 'none')
        self.assertEqual(s.env, game_environment)
        self.assertIsNot(update_environment, s.env)

    def fixture(self, root):
        client = root / 'client'; client.mkdir()
        (client / 'Viewer').mkdir(); (client / 'Game').mkdir()
        (client / 'Viewer/POL.EXE').write_bytes(pe())
        (client / 'Viewer/viewer/com').mkdir(parents=True)
        (client / 'Viewer/viewer/contents').mkdir()
        for relative in ('viewer/com/polcoreeu.dll', 'viewer/com/app.dll', 'viewer/contents/polcontentsINT.dll'):
            (client / 'Viewer' / relative).write_bytes(pe())
        session = root / 'session'; session.mkdir()
        logs = root / 'logs'; logs.mkdir()
        manifest = {'format': 1, 'generation': '12345678-1234-1234-1234-123456789abc', 'region': 'EU',
                    'pol': 'Viewer', 'game': 'Game', 'executable': 'Viewer/POL.EXE', 'sha256': hashlib.sha256(pe()).hexdigest()}
        (session / 'client-update-manifest.json').write_text(json.dumps(manifest))
        return client, session, logs, manifest

    def test_viewer_cwd_no_login_and_detached_restart_wait(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                s = FakeSupervisor(session); client_update.run(s)
                self.assertEqual(s.waits[0], 'update-registry.log')
                self.assertEqual(s.waits[-3:], ['playonline-dependencies.log', 'playonline.log', 'playonline-wait.log'])
                self.assertEqual(len(s.waits), 10)
                viewer = next(call for call in s.calls if call[1] == 'playonline.log')
                self.assertEqual(viewer[0], ['wine', r'P:\playonline-run.exe', r'D:\Viewer\POL.EXE'])
                self.assertEqual(viewer[2]['cwd'], client / 'Viewer')
                self.assertNotIn('pipe_input', viewer[2])
                self.assertIn('err+all', viewer[2]['env']['WINEDEBUG'])
                self.assertEqual(viewer[2]['env']['DXVK_LOG_PATH'], 'none')
                self.assertTrue(viewer[3])
                self.assertEqual(s.calls[-1][0], ['wineserver', '-w'])
                report = json.loads((logs / 'client-update.json').read_text())
                self.assertEqual(report['status'], 'verification_pending')
                self.assertFalse(report['activation_performed'])
                self.assertFalse(report['official_repair_confirmed'])
                self.assertFalse(report['credentials_forwarded'])
                self.assertEqual(report['process']['child_exit'], 0)
                self.assert_component_registration(report, s, manifest)
                self.assertEqual(report['dependency_attempts'][0]['dependencies'], [{'name': 'PolHook.dll', 'ok': True, 'win32_error': 0}])

    def assert_component_registration(self, report, supervisor, manifest):
        registration = report['component_registration']
        self.assertEqual([row['component'] for row in registration], ['core', 'app', 'contents'])
        expected = [
            ('polcoreeu.dll' if manifest['region'] == 'EU' else 'polcore.dll', ['register', 'com']),
            ('app.dll', ['register', 'class']),
            ('polcontents.dll' if manifest['region'] == 'JP' else 'polcontentsint.dll', ['register', 'class'])]
        for row, (dll, operations) in zip(registration, expected):
            self.assertEqual(row['dll'].lower(), dll)
            self.assertEqual(row['sha256'], hashlib.sha256(pe()).hexdigest())
            self.assertEqual([step['operation'] for step in row['steps']], operations)
            for step in row['steps']:
                self.assertEqual(step['exit_code'], 0)
                self.assertEqual(step['result'], {'format': 1, 'bits': 32, 'operation': step['operation'],
                                                  'ok': True, 'hresult': 0, 'win32_error': 0})
                self.assertEqual(step['startup_diagnostics']['policy'], 'fixed_metadata_only')
        component_calls = [call for call in supervisor.calls if call[0][1] == r'P:\client-init.exe' and
                           call[0][2] in ('register', 'com', 'class')]
        self.assertEqual(len(component_calls), 6)
        self.assertEqual([call[0][2] for call in component_calls], ['register', 'com', 'register', 'class', 'register', 'class'])
        self.assertEqual(component_calls[1][0][3:5], [manifest['region'], 'pol'])
        self.assertEqual(component_calls[3][0][3], 'pol-app')
        self.assertEqual(component_calls[5][0][3], 'pol-contents' if manifest['region'] == 'JP' else 'pol-contents-int')
        self.assertTrue(all(call[3] and call[2]['env']['DXVK_LOG_PATH'] == 'none' for call in component_calls))
        encoded = json.dumps(registration)
        for private in ('loaded_path', 'PRIVATE-COMPONENT-DETAIL', 'retail-secret', 'D:\\Viewer'):
            self.assertNotIn(private, encoded)

    def test_required_viewer_components_use_regional_classes_and_case_insensitive_exact_paths(self):
        for region in ('US', 'EU', 'JP'):
            with self.subTest(region=region), tempfile.TemporaryDirectory() as tmp:
                client, session, logs, manifest = self.fixture(Path(tmp))
                viewer = client / 'Viewer'
                if region != 'EU':
                    (viewer / 'viewer/com/polcoreeu.dll').rename(viewer / 'viewer/com/POLCORE.DLL')
                if region == 'JP':
                    (viewer / 'viewer/contents/polcontentsINT.dll').rename(viewer / 'viewer/contents/PolContents.dll')
                (viewer / 'viewer/com/app.dll').rename(viewer / 'viewer/com/APP.DLL')
                (viewer / 'viewer/com').rename(viewer / 'viewer/COM')
                (viewer / 'viewer').rename(viewer / 'ViEwEr')
                manifest['region'] = region
                (session / 'client-update-manifest.json').write_text(json.dumps(manifest))
                with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                    s = FakeSupervisor(session)
                    client_update.run(s)
                    report = json.loads((logs / 'client-update.json').read_text())
                    self.assert_component_registration(report, s, manifest)

    def test_all_required_components_are_validated_before_any_registration_or_viewer(self):
        for invalid in ('missing-core', 'missing-app', 'missing-contents', 'patch-cache-only',
                        'ambiguous-file', 'ambiguous-directory', 'linked-file', 'linked-directory',
                        'not-pe32', 'wrong-architecture'):
            with self.subTest(invalid=invalid), tempfile.TemporaryDirectory() as tmp:
                root = Path(tmp)
                client, session, logs, manifest = self.fixture(root)
                viewer = client / 'Viewer'
                contents = viewer / 'viewer/contents/polcontentsINT.dll'
                if invalid.startswith('missing-'):
                    target = {'core': viewer / 'viewer/com/polcoreeu.dll', 'app': viewer / 'viewer/com/app.dll',
                              'contents': contents}[invalid[8:]]
                    target.unlink()
                elif invalid == 'patch-cache-only':
                    (viewer / 'patchfiles').mkdir()
                    contents.rename(viewer / 'patchfiles/polcontentsINT.dll')
                elif invalid == 'ambiguous-file':
                    contents.with_name('POLCONTENTSINT.DLL').write_bytes(pe())
                elif invalid == 'ambiguous-directory':
                    (viewer / 'VIEWER').mkdir()
                elif invalid == 'linked-file':
                    outside = root / 'private-component.dll'; outside.write_bytes(pe())
                    contents.unlink(); contents.symlink_to(outside)
                elif invalid == 'linked-directory':
                    original = viewer / 'viewer/contents'; renamed = viewer / 'elsewhere'
                    original.rename(renamed); original.symlink_to(renamed, target_is_directory=True)
                elif invalid == 'not-pe32':
                    contents.write_bytes(b'not a DLL')
                elif invalid == 'wrong-architecture':
                    data = bytearray(pe()); data[68:70] = b'\x64\x86'; contents.write_bytes(data)
                with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                    s = FakeSupervisor(session)
                    with self.assertRaises((ValueError, RuntimeError, OSError)):
                        client_update.run(s)
                    self.assertEqual(s.waits, ['update-registry.log'])
                    self.assertFalse(any(state['phase'] == 'completed' for state in s.states))
                    self.assertEqual(json.loads((logs / 'client-update.json').read_text())['status'], 'interrupted')

    def test_registration_and_class_failures_stop_before_dependencies_and_viewer(self):
        for failure in (('polcoreeu.dll', 'register'), ('polcoreeu.dll', 'com'),
                        ('app.dll', 'register'), ('app.dll', 'class'),
                        ('polcontentsint.dll', 'register'), ('polcontentsint.dll', 'class')):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as tmp:
                client, session, logs, manifest = self.fixture(Path(tmp))
                with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                    s = FakeSupervisor(session, component_failure=failure)
                    with self.assertRaises(RuntimeError):
                        client_update.run(s)
                    self.assertNotIn('playonline-dependencies.log', s.waits)
                    self.assertNotIn('playonline.log', s.waits)
                    report = json.loads((logs / 'client-update.json').read_text())
                    failed = next(step for row in report['component_registration'] for step in row['steps'] if step['exit_code'] == 1)
                    self.assertEqual(failed['operation'], failure[1])
                    self.assertFalse(failed['result']['ok'])
                    self.assertEqual(failed['result']['hresult'], 0x80040154)
                    self.assertNotIn('PRIVATE-COMPONENT-DETAIL', json.dumps(report))

    def test_component_receipts_must_be_current_32_bit_success_for_exact_staged_dll(self):
        malformed = ({'format': 2}, {'bits': 64}, {'operation': 'registry'}, {'ok': False},
                     {'hresult': 0x80040154}, {'hresult': -1}, {'hresult': 0x100000000},
                     {'hresult': True}, {'win32_error': 5}, {'win32_error': False},
                     {'loaded_path': r'D:\active\app.dll'}, {'loaded_path': ''})
        for change in malformed:
            with self.subTest(change=change), tempfile.TemporaryDirectory() as tmp:
                client, session, logs, manifest = self.fixture(Path(tmp))
                with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                    s = FakeSupervisor(session, component_receipt_changes=change)
                    with self.assertRaises(RuntimeError):
                        client_update.run(s)
                    self.assertNotIn('playonline-dependencies.log', s.waits)
                    self.assertNotIn('playonline.log', s.waits)
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                s = FakeSupervisor(session, missing_component_receipt=True)
                with self.assertRaises(RuntimeError):
                    client_update.run(s)
                self.assertNotIn('playonline.log', s.waits)
                self.assertFalse((session / 'client-step.json').exists(), 'the prior registry receipt must be removed')

    def test_interrupted_detached_viewer_is_not_reported_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                s = FakeSupervisor(session, cancel_wait=True)
                with self.assertRaises(supervisor.Stopped): client_update.run(s)
                self.assertEqual(json.loads((logs / 'client-update.json').read_text())['status'], 'interrupted')
                self.assertFalse(any(state['phase'] == 'completed' for state in s.states))
                self.assertTrue((client / 'Viewer/POL.EXE').exists())

    def test_nonzero_viewer_waits_for_detached_updater_and_retains_windows_exit(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                s = FakeSupervisor(session, child_exit=0xc0000135)
                with self.assertRaisesRegex(RuntimeError, 'Windows exit 0xC0000135'): client_update.run(s)
                self.assertEqual(s.waits[-1], 'playonline-wait.log')
                report = json.loads((logs / 'client-update.json').read_text())
                self.assertEqual(report['status'], 'interrupted')
                self.assertEqual(report['process']['child_exit'], 0xc0000135)
                self.assertEqual(report['viewer_exit_code'], 1)
                self.assertEqual(report['prefix_wait_exit_code'], 0)
                self.assertFalse(any(state['phase'] == 'completed' for state in s.states))

    def test_dependency_failure_stops_before_viewer_without_arbitrary_paths(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                s = FakeSupervisor(session, dependency_error=126)
                with self.assertRaisesRegex(RuntimeError, r'PolHook.dll \(Windows error 126\)'): client_update.run(s)
                self.assertNotIn('playonline.log', s.waits)
                self.assertTrue(s.calls[-1][3])
                report = json.loads((logs / 'client-update.json').read_text())
                self.assertNotIn('loaded_path', json.dumps(report))
                self.assertEqual(report['dependency_attempts'][0]['dependencies'][0]['win32_error'], 126)

    def test_bad_dependency_receipt_stops_before_viewer(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                s = FakeSupervisor(session, malformed_check=True)
                with self.assertRaisesRegex(RuntimeError, 'valid receipt'): client_update.run(s)
                self.assertNotIn('playonline.log', s.waits)

    def test_missing_windows_receipt_cannot_report_success(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                s = FakeSupervisor(session, missing_receipt=True)
                with self.assertRaisesRegex(RuntimeError, 'missing Windows process receipt'): client_update.run(s)
                self.assertEqual(s.waits[-1], 'playonline-wait.log')
                self.assertFalse(any(state['phase'] == 'completed' for state in s.states))

    def test_windows_receipt_rejects_unbounded_or_arbitrary_fields(self):
        with tempfile.TemporaryDirectory() as tmp:
            session = Path(tmp)
            good = {'format': 1, 'bits': 32, 'phase': 'exited', 'win32_error': 0,
                    'child_exit': 0xc0000135, 'child_pid': 36, 'window_error': 0,
                    'visible_window_seen': False, 'elapsed_ms': 15}
            with patch.object(client_update, 'SESSION', session):
                path = session / 'playonline-process.json'
                path.write_text(json.dumps(good)); self.assertEqual(client_update.process_receipt(), good)
                for change in ({'child_exit': -1}, {'child_exit': 0x100000000}, {'child_exit': True},
                               {'visible_window_seen': 'true'}, {'phase': 'secret'}, {'account': 'secret'}):
                    path.write_text(json.dumps(dict(good, **change)))
                    self.assertIsNone(client_update.process_receipt(), str(change))
                path.write_text(' ' * 4097); self.assertIsNone(client_update.process_receipt())
                path.unlink(); self.assertIsNone(client_update.process_receipt())

    def test_failed_wait_keeps_fixed_diagnostics_without_private_output(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            private = b'account=retail-user password=retail-secret\n'
            output = (private + b'100.1:0024:0028:err:module:import_dll Library private-name.dll (which is needed by retail-secret) not found\n'
                      b'100.2:0024:0028:trace:loaddll:build_module Loaded L"D:\\\\Viewer\\\\PolHook.dll" at 1234: native\n'
                      b'100.3:0024:0028:trace:seh:dispatch_exception code=c0000135 flags=0 addr=1234\n')
            with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                s = FakeSupervisor(session, viewer_error=supervisor.Stopped(), viewer_output=output)
                with self.assertRaises(supervisor.Stopped): client_update.run(s)
                raw = (logs / 'client-update.json').read_text()
                for value in ('retail-user', 'retail-secret', 'private-name', r'D:\\Viewer'):
                    self.assertNotIn(value, raw)
                report = json.loads(raw)
                rows = report['startup_diagnostics']['records']
                self.assertTrue(any(row.get('category') == 'dll_import' for row in rows))
                self.assertTrue(any(row.get('module') == 'PolHook.dll' for row in rows))
                self.assertTrue(any(row.get('code') == 0xc0000135 for row in rows))
                self.assertEqual(report['status'], 'interrupted')
                self.assertGreaterEqual(report['viewer_elapsed_ms'], 0)

    def test_registry_receipt_failure_never_opens_viewer(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                s = FakeSupervisor(session, registry_ok=False)
                with self.assertRaisesRegex(RuntimeError, 'registry receipt'): client_update.run(s)
                self.assertEqual(len(s.calls), 1)

    def test_resume_does_not_require_game_dlls_mid_repair(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client):
                pol, executable = client_update.validate_manifest(manifest)
                self.assertEqual(pol, client / 'Viewer')
                self.assertEqual(executable, client / 'Viewer/POL.EXE')

    def test_manifest_rejects_arbitrary_paths_checksum_and_architecture(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client):
                for change in ({'executable': '../POL.EXE'}, {'executable': 'Viewer/../POL.EXE'}, {'executable': 'Viewer/other.exe'},
                               {'pol': 'Game'}, {'sha256': '0'*64}, {'generation': '../'+'.'*33}, {'command': 'run'}, {'region': 'other'}):
                    with self.assertRaises((ValueError, OSError), msg=str(change)): client_update.validate_manifest(dict(manifest, **change))
                target = client / 'Viewer/POL.EXE'
                target.unlink(); target.symlink_to('/outside/POL.EXE')
                with self.assertRaises(ValueError): client_update.validate_manifest(manifest)
                target.unlink(); bad = bytearray(pe()); bad[68:70] = b'\x64\x86'; target.write_bytes(bad)
                manifest['sha256'] = hashlib.sha256(bad).hexdigest()
                with self.assertRaisesRegex(ValueError, 'x86'): client_update.validate_manifest(manifest)

    def test_update_actions_do_not_accept_credentials_or_commands(self):
        req = {'format': 1, 'session_id': '0'*36, 'renderer': 'turnip26', 'audio': True}
        for action in ('update-client', 'verify-client-update'):
            self.assertEqual(supervisor.validate_request(dict(req, action=action))['action'], action)
            for change in ({'password': 'secret'}, {'command': 'pol.exe'}, {'display_profile': 'preserve'}):
                with self.assertRaises(ValueError): supervisor.validate_request(dict(req, action=action, **change))


if __name__ == '__main__': unittest.main()
