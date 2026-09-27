"""Qualify Wine's DirectDraw backend on the isolated updater environment.

DXVK's D3D9 device check does not exercise Wine's builtin DirectDraw renderer.
Only fixed synthetic pixels and numeric adapter metadata are retained here.
"""
import json
import os
from pathlib import Path
import re
import signal
import subprocess
import time

from startup_diagnostics import StartupDiagnostics

LOGS = Path('/logs')
FIELDS = {'format', 'bits', 'passed', 'stage', 'hresult', 'adapter_vendor_id',
          'adapter_device_id', 'frames', 'expected_frames', 'colorfills', 'uploads',
          'blits', 'ffp_frames', 'presents', 'readback_samples', 'presentation_samples',
          'mismatch_sample', 'expected_rgb', 'actual_rgb',
          'elapsed_ms', 'render_ms', 'first_frame_ms', 'max_frame_ms'}
STAGES = {'arguments', 'window', 'create', 'cooperative', 'identifier', 'primary',
          'clipper', 'offscreen', 'device', 'state', 'colorfill', 'upload', 'blit',
          'draw', 'readback', 'present', 'present_readback', 'budget', 'completed'}


def shader_diagnostics(lines):
    """Exact Wine 10 synthetic-probe compiler failures; no shader text."""
    result = dict(hlsl_count=0, hlsl_code=0, spirv_count=0, spirv_code=0)
    patterns = {b'compile_hlsl_shader': ('hlsl', rb'failed to compile hlsl, ret (-[0-9]{1,10})\.'),
                b'shader_spirv_compile_shader': ('spirv', rb'failed to compile shader, ret (-[0-9]{1,10})\.')}
    for line in lines:
        wine = StartupDiagnostics.WINE.fullmatch(line.lower())
        if not wine:
            continue
        _, level, channel, function, message = wine.groups()
        if level != b'err' or channel != b'd3d_shader' or function not in patterns:
            continue
        label, pattern = patterns[function]
        match = re.fullmatch(pattern, message)
        if match and -0x80000000 <= int(match[1]) < 0:
            result[label + '_count'] = min(1000000, result[label + '_count'] + 1)
            result[label + '_code'] = int(match[1])
    return result


def validate(value):
    if (not isinstance(value, dict) or set(value) != FIELDS
            or type(value['passed']) is not bool
            or not isinstance(value['stage'], str) or value['stage'] not in STAGES
            or any(type(value[k]) is not int or not 0 <= value[k] <= 0xffffffff
                   for k in FIELDS - {'passed', 'stage'})
            or value['format'] != 1 or value['bits'] != 32
            or value['expected_frames'] != 24
            or any(value[k] > 24 for k in ('frames', 'colorfills', 'uploads', 'blits', 'ffp_frames', 'presents'))
            or value['readback_samples'] > 192 or value['presentation_samples'] > 192):
        raise ValueError('Invalid DirectDraw check receipt')
    mismatch = value['mismatch_sample']
    if (mismatch not in (*range(8), 0xffffffff)
            or value['expected_rgb'] > 0xffffff or value['actual_rgb'] > 0xffffff
            or (mismatch == 0xffffffff and (value['expected_rgb'] or value['actual_rgb']))
            or (mismatch != 0xffffffff and (value['passed'] or not value['hresult']
                or value['stage'] not in ('readback', 'present_readback')
                or value['expected_rgb'] == value['actual_rgb']))):
        raise ValueError('Invalid DirectDraw mismatch metadata')
    if value['passed'] and (value['stage'] != 'completed' or value['hresult']
            or any(value[k] != 24 for k in ('frames', 'colorfills', 'uploads', 'blits', 'ffp_frames', 'presents'))
            or value['readback_samples'] != 192 or value['presentation_samples'] != 192):
        raise ValueError('Incomplete DirectDraw pixel check')
    return dict(value)


def run_probe(supervisor, environment):
    path = LOGS / 'updater-ddraw-check.log'
    path.unlink(missing_ok=True)
    path.with_suffix('.previous.log').unlink(missing_ok=True)
    proc = None
    started = time.monotonic()
    result = {'backend': 'unconfirmed', 'exit_code': None, 'elapsed_wall_ms': 0}
    try:
        # This executable takes no file or account input. Retain only fixed
        # backend/compiler metadata and discard the bounded raw log afterward.
        probe_env = dict(environment, WINEDEBUG='-all,+timestamp,+pid,err+all',
                         DXVK_LOG_LEVEL='none', DXVK_LOG_PATH='none')
        probe_env['WINEDLLOVERRIDES'] = environment.get('WINEDLLOVERRIDES', '') + ';ddraw=b'
        proc = supervisor.spawn(supervisor.wine_command(r'P:\ddraw-check.exe'),
                                path.name, env=probe_env, fixed_output=True)
        supervisor.wait(proc, 45, 'PlayOnline DirectDraw graphics check', accepted=(0, 1))
        result['exit_code'] = proc.returncode
        supervisor.logs[-1].thread.join(2)
        with path.open('rb') as stream:
            data = stream.read(65537)
        if len(data) > 65536:
            raise ValueError('Oversized DirectDraw check output')
        result['shader_diagnostics'] = shader_diagnostics(data.splitlines())
        rows = []
        diagnostics = StartupDiagnostics()
        for line in data.splitlines():
            diagnostics.line(line.lower())
            if line.startswith(b'{'):
                rows.append(validate(json.loads(line)))
        if len(rows) != 1:
            raise ValueError('Missing DirectDraw check receipt')
        result.update(rows[0])
        backends = {row['backend'] for row in diagnostics.snapshot()['records']
                    if row['event'] == 'wined3d_renderer'}
        if len(backends) == 1:
            result['backend'] = backends.pop()
    except (OSError, ValueError, RuntimeError):
        result['probe_error'] = True
        if proc is not None:
            result['exit_code'] = proc.poll()
    finally:
        # Cancellation propagates, while a failed finite probe cannot leave a
        # renderer/window running beside the compatibility viewer.
        if proc is not None and proc.poll() is None:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=2)
            except (OSError, subprocess.TimeoutExpired):
                pass
        path.unlink(missing_ok=True)
        path.with_suffix('.previous.log').unlink(missing_ok=True)
        result['elapsed_wall_ms'] = max(0, int((time.monotonic() - started) * 1000))
    return result


def configure(supervisor, environment):
    requested = supervisor.req.get('updater_vulkan_ddraw', False)
    report = {'format': 1, 'requested': 'vulkan' if requested else 'compatibility',
              'active': 'compatibility', 'state': 'disabled', 'reason': 'not_requested'}
    if not requested:
        # Fixed CI-only comparison. Android's cleared environment does not
        # expose this switch. The actual compatibility environment is retained.
        if os.environ.get('LSB_TEST_DDRAW_PROBE') == '1':
            probe_env = dict(environment, WINE_D3D_CONFIG='csmt=1,renderer=gl')
            report['probe'] = run_probe(supervisor, probe_env)
            report.update(state='verified' if report['probe'].get('passed') and
                          report['probe'].get('exit_code') == 0 and
                          report['probe']['backend'] == 'opengl' else 'fallback',
                          reason='compatibility_probe')
        return dict(environment), report
    state = supervisor.state
    if (supervisor.req.get('action') != 'update-client'
            or supervisor.req.get('renderer') != 'turnip26'
            or state.get('dxvk_selected') != '2.5.3'
            or supervisor.engine not in ('box64', 'fex')):
        report.update(state='fallback', reason='unsupported_profile')
        return dict(environment), report
    candidate = dict(environment, WINE_D3D_CONFIG='csmt=1,renderer=vulkan')
    candidate['WINEDLLOVERRIDES'] = environment.get('WINEDLLOVERRIDES', '') + ';ddraw=b'
    supervisor.status('checking_updater_graphics', message='Checking hardware rendering for PlayOnline')
    report['probe'] = probe = run_probe(supervisor, candidate)
    # DDraw may report a compatibility PCI vendor rather than Qualcomm's
    # physical identity. The same verified ICD binding is authoritative for
    # hardware; the actual DDraw backend and all pixel operations must pass.
    device_ok = state.get('hardware_verified') is True or bool(os.environ.get('LSB_TEST_VULKAN_ICD'))
    if (probe.get('passed') is True and probe.get('exit_code') == 0
            and probe.get('backend') == 'vulkan' and device_ok):
        report.update(active='vulkan', state='verified', reason='directdraw_pixels_verified')
        return candidate, report
    report.update(state='fallback', reason='directdraw_check_failed')
    return dict(environment), report
