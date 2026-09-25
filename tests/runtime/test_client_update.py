import hashlib
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime'))
import client_setup
import client_update
import supervisor


def pe():
    data = bytearray(248)
    data[:2] = b'MZ'; struct.pack_into('<I', data, 60, 64)
    data[64:70] = b'PE\0\0\x4c\x01'; struct.pack_into('<H', data, 84, 160)
    data[88:90] = b'\x0b\x01'
    return bytes(data)


class FakeSupervisor:
    def __init__(self, session, cancel_wait=False, registry_ok=True):
        self.req = {'session_id': '12345678-1234-1234-1234-123456789abc'}
        self.env = {'WINEPREFIX': '/prefix'}
        self.private_output = False
        self.calls = []; self.waits = []; self.states = []
        self.session = session; self.cancel_wait = cancel_wait; self.registry_ok = registry_ok

    def status(self, phase=None, **fields):
        self.states.append(dict(fields, phase=phase))

    def wine_command(self, *args):
        return ['wine', *args]

    def server_command(self, *args):
        return ['wineserver', *args]

    def spawn(self, args, name, **kwargs):
        self.calls.append((args, name, kwargs, self.private_output))
        return name

    def wait(self, proc, timeout, label):
        self.waits.append(proc)
        if proc == 'update-registry.log':
            (self.session / 'client-step.json').write_text(json.dumps({'operation': 'registry', 'ok': self.registry_ok, 'bits': 32}))
        if proc == 'playonline-wait.log' and self.cancel_wait:
            raise supervisor.Stopped()


class UpdateContracts(unittest.TestCase):
    def fixture(self, root):
        client = root / 'client'; client.mkdir()
        (client / 'Viewer').mkdir(); (client / 'Game').mkdir()
        (client / 'Viewer/POL.EXE').write_bytes(pe())
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
                self.assertEqual(s.waits, ['update-registry.log', 'playonline.log', 'playonline-wait.log'])
                viewer = s.calls[1]
                self.assertEqual(viewer[0], ['wine', r'D:\Viewer\POL.EXE'])
                self.assertEqual(viewer[2]['cwd'], client / 'Viewer')
                self.assertNotIn('pipe_input', viewer[2])
                self.assertEqual(viewer[2]['env']['WINEDEBUG'], '-all')
                self.assertTrue(viewer[3])
                self.assertEqual(s.calls[-1][0], ['wineserver', '-w'])
                report = json.loads((logs / 'client-update.json').read_text())
                self.assertEqual(report['status'], 'verification_pending')
                self.assertFalse(report['activation_performed'])
                self.assertFalse(report['official_repair_confirmed'])
                self.assertFalse(report['credentials_forwarded'])

    def test_interrupted_detached_viewer_is_not_reported_complete(self):
        with tempfile.TemporaryDirectory() as tmp:
            client, session, logs, manifest = self.fixture(Path(tmp))
            with patch.object(client_setup, 'CLIENT', client), patch.object(client_update, 'SESSION', session), patch.object(client_update, 'LOGS', logs):
                s = FakeSupervisor(session, cancel_wait=True)
                with self.assertRaises(supervisor.Stopped): client_update.run(s)
                self.assertEqual(json.loads((logs / 'client-update.json').read_text())['status'], 'interrupted')
                self.assertFalse(any(state['phase'] == 'completed' for state in s.states))
                self.assertTrue((client / 'Viewer/POL.EXE').exists())

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
