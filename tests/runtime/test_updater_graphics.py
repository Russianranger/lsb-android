import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime'))
import updater_graphics as graphics
from supervisor import Stopped, validate_request


def receipt():
    return dict(format=1, bits=32, passed=True, stage='completed', hresult=0,
                adapter_vendor_id=0x10de, adapter_device_id=1, frames=24, expected_frames=24,
                colorfills=24, uploads=24, blits=24, ffp_frames=24, presents=24,
                readback_samples=192, presentation_samples=192, elapsed_ms=800,
                render_ms=700, first_frame_ms=30, max_frame_ms=50)


class DirectDrawContracts(unittest.TestCase):
    def instance(self):
        return SimpleNamespace(req=dict(action='update-client', renderer='turnip26', updater_vulkan_ddraw=True),
                               state=dict(dxvk_selected='2.5.3', hardware_verified=True),
                               engine='box64', status=Mock())

    def test_strict_complete_pixel_receipt(self):
        self.assertEqual(graphics.validate(receipt()), receipt())
        for change in ({'private': 'account'}, {'passed': 1}, {'stage': 'private'}, {'hresult': 1},
                       {'frames': 23}, {'readback_samples': 191}, {'presentation_samples': 0},
                       {'uploads': True}, {'adapter_vendor_id': -1}):
            with self.subTest(change=change), self.assertRaises(ValueError):
                graphics.validate(dict(receipt(), **change))

    def test_verified_backend_changes_only_updater_copy(self):
        original = dict(WINE_D3D_CONFIG='csmt=1', WINEDLLOVERRIDES='d3d9=n', LIBGL_ALWAYS_SOFTWARE='1')
        for engine in ('box64', 'fex'):
            instance = self.instance(); instance.engine = engine
            with patch.object(graphics, 'run_probe', return_value=dict(receipt(), backend='vulkan', exit_code=0)) as probe:
                environment, report = graphics.configure(instance, original)
            self.assertEqual(report['state'], 'verified')
            self.assertEqual(environment['WINE_D3D_CONFIG'], 'csmt=1,renderer=vulkan')
            self.assertEqual(environment['WINEDLLOVERRIDES'], 'd3d9=n;ddraw=b')
            self.assertEqual(original['WINE_D3D_CONFIG'], 'csmt=1')
            self.assertEqual(probe.call_args.args[1], environment)

    def test_failure_and_unverified_hardware_preserve_compatibility(self):
        original = {'WINE_D3D_CONFIG': 'csmt=1'}
        for change in ({'passed': False}, {'backend': 'opengl'}, {'exit_code': 1}, {'probe_error': True, 'passed': False}):
            instance = self.instance()
            with patch.object(graphics, 'run_probe', return_value=dict(receipt(), backend='vulkan', exit_code=0) | change):
                environment, report = graphics.configure(instance, original)
            self.assertEqual(environment, original); self.assertEqual(report['state'], 'fallback')
        instance = self.instance(); instance.state['hardware_verified'] = False
        with patch.dict(os.environ, {}, clear=True), patch.object(graphics, 'run_probe', return_value=dict(receipt(), backend='vulkan', exit_code=0)):
            environment, report = graphics.configure(instance, original)
        self.assertEqual(environment, original); self.assertEqual(report['state'], 'fallback')

    def test_disabled_and_unsupported_profiles_do_not_probe(self):
        for change in ({'updater_vulkan_ddraw': False}, {'action': 'launch'}, {'renderer': 'software'}):
            instance = self.instance(); instance.req.update(change)
            with patch.dict(os.environ, {}, clear=True), patch.object(graphics, 'run_probe') as probe:
                environment, report = graphics.configure(instance, {'safe': 'unchanged'})
            probe.assert_not_called(); self.assertEqual(environment, {'safe': 'unchanged'})
            self.assertEqual(report['active'], 'compatibility')

    def test_probe_is_bounded_private_and_stop_propagates(self):
        for outcome in ('success', 'timeout', 'stop', 'duplicate', 'malformed', 'unknown_backend'):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / 'updater-ddraw-check.log'
                proc = Mock(pid=12345, returncode=0); proc.poll.return_value = 0
                instance = self.instance(); instance.logs = [Mock()]
                instance.wine_command = lambda arg: ['wine', arg]
                def spawn(args, name, **kwargs):
                    data = b'0010:err:winediag:wined3d_dll_init Using the Vulkan renderer.\n'
                    if outcome == 'unknown_backend': data = b'private_account\n'
                    data += json.dumps(receipt()).encode() + b'\n'
                    if outcome == 'duplicate': data += json.dumps(receipt()).encode() + b'\n'
                    if outcome == 'malformed': data += b'{private_password}\n'
                    path.write_bytes(data); path.with_suffix('.previous.log').write_bytes(b'synthetic')
                    return proc
                instance.spawn = Mock(side_effect=spawn); instance.wait = Mock()
                if outcome in ('timeout', 'stop'):
                    instance.wait.side_effect = RuntimeError('budget') if outcome == 'timeout' else Stopped()
                    proc.returncode = None; proc.poll.return_value = None
                with patch.object(graphics, 'LOGS', Path(tmp)), patch.object(graphics.os, 'killpg') as kill:
                    if outcome == 'stop':
                        with self.assertRaises(Stopped): graphics.run_probe(instance, {})
                    else:
                        result = graphics.run_probe(instance, {})
                        if outcome == 'success':
                            self.assertTrue(result['passed']); self.assertEqual(result['backend'], 'vulkan')
                        elif outcome == 'unknown_backend': self.assertEqual(result['backend'], 'unconfirmed')
                        else: self.assertTrue(result['probe_error'])
                        self.assertNotIn('private', json.dumps(result))
                    if outcome in ('timeout', 'stop'): kill.assert_called_once()
                self.assertEqual(instance.wait.call_args.args[1], 45)
                self.assertTrue(instance.spawn.call_args.kwargs['fixed_output'])
                self.assertEqual(instance.spawn.call_args.args[0][-1], r'P:\ddraw-check.exe')
                self.assertFalse(path.exists()); self.assertFalse(path.with_suffix('.previous.log').exists())

    def test_request_is_boolean_and_updater_only(self):
        base = dict(format=1, action='update-client', renderer='turnip26', audio=True, session_id=str(uuid.uuid4()))
        for value in (True, False): self.assertEqual(validate_request(dict(base, updater_vulkan_ddraw=value))['updater_vulkan_ddraw'], value)
        for value in (1, 'vulkan', None, {}):
            with self.assertRaises(ValueError): validate_request(dict(base, updater_vulkan_ddraw=value))
        for action in ('launch', 'probe', 'verify-client-update'):
            with self.assertRaises(ValueError): validate_request(dict(base, action=action, updater_vulkan_ddraw=True))
