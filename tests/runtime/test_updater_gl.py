import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime'))
import updater_gl as graphics
from supervisor import Stopped


def output(renderer='zink (Turnip Adreno (TM) 740)', accelerated='yes', vendor='5143'):
    return (f'name of display: private-display\n'
            f'direct rendering: Yes\n'
            f'Extended renderer info (GLX_MESA_query_renderer):\n'
            f'    Vendor: Unknown (vendor-id: 0x{vendor}) (0x{vendor})\n'
            f'    Device: {renderer} (0x740)\n'
            f'    Version: 22.3.6\n'
            f'    Accelerated: {accelerated}\n'
            f'OpenGL renderer string: {renderer}\n').encode()


class ZinkContracts(unittest.TestCase):
    def instance(self, engine='box64'):
        return SimpleNamespace(req=dict(action='update-client', renderer='turnip26'),
                               state=dict(dxvk_selected='2.5.3', hardware_verified=True),
                               engine=engine, status=Mock())

    def environment(self):
        return dict(WINE_D3D_CONFIG='csmt=1', LIBGL_ALWAYS_SOFTWARE='1',
                    GALLIUM_DRIVER='llvmpipe', LP_NUM_THREADS='4',
                    VK_ICD_FILENAMES='/session/turnip-icd.json',
                    VK_DRIVER_FILES='/session/turnip-icd.json', MESA_VK_WSI_DEBUG='sw',
                    WINEDEBUG='private-output-policy', WINEDLLOVERRIDES='d3d9=n',
                    MESA_SHADER_CACHE_DIR='/prefix/cache', LC_ALL='fr_FR.UTF-8')

    def receipt(self, **changes):
        return dict(graphics.parse(output()), state='completed', exit_code=0,
                    elapsed_wall_ms=50) | changes

    def test_candidate_is_a_copy_and_keeps_icd_wsi_wine_policy(self):
        original = self.environment()
        before = dict(original)
        candidate = graphics.candidate_environment(original)
        self.assertEqual(original, before)
        expected = {k: v for k, v in before.items()
                    if k not in ('LIBGL_ALWAYS_SOFTWARE', 'GALLIUM_DRIVER', 'LP_NUM_THREADS')}
        expected.update(WINE_D3D_CONFIG='csmt=1,renderer=gl', MESA_LOADER_DRIVER_OVERRIDE='zink')
        self.assertEqual(candidate, expected)
        self.assertNotIn('LIBGL_KOPPER_DISABLE', candidate)

    def test_parser_retains_only_fixed_flags_and_numeric_device_ids(self):
        result = graphics.parse(output())
        self.assertEqual(result, dict(format=1, renderer='zink', direct=True, accelerated=True,
                                      cpu_renderer=False, vendor_id=0x5143, device_id=0x740))
        serialized = json.dumps(result)
        for private in ('private-display', 'Turnip', 'Adreno', 'Unknown', '22.3.6'):
            self.assertNotIn(private, serialized)
        cpu = graphics.parse(output('zink (llvmpipe (LLVM 15.0.6, 128 bits))', 'no', '10005'))
        self.assertEqual(cpu['renderer'], 'zink')
        self.assertTrue(cpu['cpu_renderer'])
        self.assertFalse(cpu['accelerated'])
        self.assertEqual(graphics.parse(output('llvmpipe (LLVM 15.0.6)'))['renderer'], 'other')

    def test_parser_rejects_missing_duplicate_malformed_or_unbounded_fields(self):
        good = output()
        for bad in (b'', good * 2, good + b'direct rendering: No\n',
                    good.replace(b'Accelerated: yes', b'Accelerated: maybe'),
                    good.replace(b' (0x5143)\n', b' (0x100000000)\n'),
                    good.replace(b' (0x740)\n', b' (740)\n'),
                    good.replace(b'OpenGL renderer string:', b'Unknown renderer string:'),
                    good + b'\0', good + b'x' * 1025, b'\n' * 16385):
            with self.subTest(size=len(bad)), self.assertRaises(ValueError):
                graphics.parse(bad)

    def test_phone_qualifies_each_engine_but_does_not_claim_directdraw(self):
        for engine in ('box64', 'fex'):
            original = self.environment()
            with self.subTest(engine=engine), patch.dict(os.environ, {}, clear=True), \
                    patch.object(graphics, 'run_probe', return_value=self.receipt()) as probe:
                candidate, report = graphics.qualify(self.instance(engine), original)
            self.assertEqual(report['active'], 'zink')
            self.assertEqual(report['state'], 'verified')
            self.assertFalse(report['directdraw_verified'])
            self.assertEqual(probe.call_args.args[1], candidate)
            self.assertEqual(original, self.environment())

    def test_phone_rejects_software_ambiguous_backend_wrong_vendor_or_failed_probe(self):
        cases = ({'accelerated': False}, {'cpu_renderer': True}, {'renderer': 'other'},
                 {'direct': False}, {'vendor_id': 0x10005}, {'state': 'failed'}, {'exit_code': 1})
        for change in cases:
            original = self.environment()
            with self.subTest(change=change), patch.dict(os.environ, {}, clear=True), \
                    patch.object(graphics, 'run_probe', return_value=self.receipt(**change)):
                candidate, report = graphics.qualify(self.instance(), original)
            self.assertEqual(candidate, original)
            self.assertEqual(report['active'], 'compatibility')
        instance = self.instance(); instance.state['hardware_verified'] = False
        with patch.dict(os.environ, {}, clear=True), patch.object(graphics, 'run_probe', return_value=self.receipt()):
            candidate, report = graphics.qualify(instance, self.environment())
        self.assertEqual(report['state'], 'fallback')

    def test_software_ci_requires_explicit_matching_icd_in_both_loader_variables(self):
        for test_path, second_path, expected in (('', '/ci/lvp.json', 'fallback'),
                                                ('/ci/other.json', '/ci/lvp.json', 'fallback'),
                                                ('/ci/lvp.json', '/wrong.json', 'fallback'),
                                                ('/ci/lvp.json', '/ci/lvp.json', 'verified')):
            original = self.environment() | {'VK_ICD_FILENAMES': '/ci/lvp.json',
                                             'VK_DRIVER_FILES': second_path}
            instance = self.instance(); instance.state['hardware_verified'] = False
            with self.subTest(test_path=test_path, second_path=second_path), \
                    patch.dict(os.environ, {'LSB_TEST_VULKAN_ICD': test_path}, clear=True), \
                    patch.object(graphics, 'run_probe', return_value=self.receipt(
                        accelerated=False, cpu_renderer=True, vendor_id=0x10005)):
                candidate, report = graphics.qualify(instance, original)
            self.assertEqual(report['state'], expected)

    def test_unrelated_profiles_are_untouched(self):
        for field, value in (('action', 'launch'), ('renderer', 'software'),
                             ('engine', 'other'), ('dxvk_selected', '2.7.1')):
            instance = self.instance()
            if field == 'engine': instance.engine = value
            elif field == 'dxvk_selected': instance.state[field] = value
            else: instance.req[field] = value
            original = self.environment()
            with self.subTest(field=field), patch.object(graphics, 'run_probe') as probe:
                candidate, report = graphics.qualify(instance, original)
            probe.assert_not_called()
            self.assertEqual(candidate, original)
            self.assertEqual(report['reason'], 'unsupported_profile')

    def test_probe_is_finite_locale_fixed_and_cleans_raw_logs_on_each_outcome(self):
        for outcome in ('success', 'exit_failure', 'malformed', 'oversized', 'timeout', 'stop', 'spawn_failure'):
            with self.subTest(outcome=outcome), tempfile.TemporaryDirectory() as tmp:
                path = Path(tmp) / 'updater-gl-check.log'
                previous = path.with_suffix('.previous.log')
                path.write_bytes(b'stale'); previous.write_bytes(b'stale rotated')
                process = Mock(pid=12345, returncode=0)
                process.poll.return_value = 0
                instance = self.instance(); instance.logs = [Mock()]

                def spawn(args, name, **kwargs):
                    self.assertFalse(path.exists()); self.assertFalse(previous.exists())
                    if outcome == 'spawn_failure': raise OSError('private path detail')
                    data = b'private diagnostic' if outcome == 'malformed' else output()
                    if outcome == 'oversized': data += b'\n' * 16385
                    path.write_bytes(data); previous.write_bytes(b'private rotated detail')
                    return process

                instance.spawn = Mock(side_effect=spawn); instance.wait = Mock()
                if outcome == 'exit_failure': process.returncode = 1; process.poll.return_value = 1
                if outcome in ('timeout', 'stop'):
                    process.returncode = None; process.poll.return_value = None
                    instance.wait.side_effect = Stopped() if outcome == 'stop' else RuntimeError('private timeout detail')
                with patch.object(graphics, 'LOGS', Path(tmp)), patch.object(graphics.os, 'killpg') as kill:
                    if outcome == 'stop':
                        with self.assertRaises(Stopped): graphics.run_probe(instance, self.environment())
                    else:
                        result = graphics.run_probe(instance, self.environment())
                        self.assertEqual(result['state'], 'completed' if outcome == 'success' else 'failed')
                        self.assertNotIn('private', json.dumps(result))
                    if outcome in ('timeout', 'stop'):
                        kill.assert_called_once_with(process.pid, graphics.signal.SIGKILL)
                        process.wait.assert_called_once_with(timeout=2)
                    else: kill.assert_not_called()
                self.assertFalse(path.exists()); self.assertFalse(previous.exists())
                self.assertEqual(instance.spawn.call_args.args[0], ['/usr/bin/glxinfo', '-B'])
                self.assertTrue(instance.spawn.call_args.kwargs['fixed_output'])
                self.assertEqual(instance.spawn.call_args.kwargs['env']['LC_ALL'], 'C')
                self.assertEqual(instance.spawn.call_args.kwargs['env']['WINEDEBUG'], 'private-output-policy')
                if outcome != 'spawn_failure': self.assertEqual(instance.wait.call_args.args[1], 30)


if __name__ == '__main__':
    unittest.main()
