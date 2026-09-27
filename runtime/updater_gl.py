"""Finite native Zink qualification; callers must also check PE32 DirectDraw.

The pinned Mesa 22.3.6 GLX loader selects its Kopper path when the driver
override is zink. Do not disable Kopper: that version then selects swrast.
No raw glxinfo text or renderer/device strings leave this module.
"""
import os
from pathlib import Path
import re
import signal
import subprocess
import time

LOGS = Path('/logs')
MAX_OUTPUT = 16384


def candidate_environment(environment):
    candidate = dict(environment, WINE_D3D_CONFIG='csmt=1,renderer=gl',
                     MESA_LOADER_DRIVER_OVERRIDE='zink')
    # Zink itself honors LIBGL_ALWAYS_SOFTWARE by selecting a CPU Vulkan
    # device. The caller's qualified physical ICD and X11-copy WSI stay intact.
    for name in ('LIBGL_ALWAYS_SOFTWARE', 'GALLIUM_DRIVER', 'LP_NUM_THREADS'):
        candidate.pop(name, None)
    return candidate


def parse(data):
    if not isinstance(data, bytes) or len(data) > MAX_OUTPUT or b'\0' in data:
        raise ValueError('Invalid OpenGL check output')
    lines = data.splitlines()
    if any(len(line) > 1024 for line in lines):
        raise ValueError('Oversized OpenGL check line')

    def one(pattern):
        values = [match[1] for line in lines if (match := re.fullmatch(pattern, line))]
        if len(values) != 1:
            raise ValueError('Missing or ambiguous OpenGL check field')
        return values[0]

    direct = one(rb'direct rendering: (Yes|No)') == b'Yes'
    renderer = one(rb'OpenGL renderer string: ([\x20-\x7e]{1,1000})')
    accelerated = one(rb'\s+Accelerated: (yes|no)') == b'yes'
    vendor = one(rb'\s+Vendor: [\x20-\x7e]{1,1000} \(0x([0-9a-fA-F]{1,8})\)')
    device = one(rb'\s+Device: [\x20-\x7e]{1,1000} \(0x([0-9a-fA-F]{1,8})\)')
    # This is the pinned driver's zink_get_name() format. Classifying only
    # fixed tokens avoids exporting the actual GL/Vulkan device-name strings.
    zink = re.fullmatch(rb'zink \([\x20-\x7e]+\)', renderer) is not None
    cpu = re.search(rb'(?<![a-z0-9])(?:llvmpipe|softpipe|lavapipe|swrast|software)(?![a-z0-9])',
                    renderer.lower()) is not None
    return {'format': 1, 'renderer': 'zink' if zink else 'other',
            'direct': direct, 'accelerated': accelerated, 'cpu_renderer': cpu,
            'vendor_id': int(vendor, 16), 'device_id': int(device, 16)}


def run_probe(supervisor, environment):
    path = LOGS / 'updater-gl-check.log'
    previous = path.with_suffix('.previous.log')
    path.unlink(missing_ok=True)
    previous.unlink(missing_ok=True)
    process = None
    started = time.monotonic()
    result = {'format': 1, 'state': 'failed', 'exit_code': None, 'elapsed_wall_ms': 0}
    try:
        # Native fixed command, no client/account/file argument. Locale is
        # fixed only for this probe so glxinfo's known labels can be checked.
        probe_environment = dict(environment, LC_ALL='C', LANG='C')
        process = supervisor.spawn(['/usr/bin/glxinfo', '-B'], path.name,
                                   env=probe_environment, fixed_output=True)
        supervisor.wait(process, 30, 'PlayOnline OpenGL graphics check', accepted=(0, 1))
        result['exit_code'] = process.returncode
        supervisor.logs[-1].thread.join(2)
        with path.open('rb') as stream:
            output = stream.read(MAX_OUTPUT + 1)
        result.update(parse(output))
        if process.returncode == 0:
            result['state'] = 'completed'
    except (OSError, RuntimeError, ValueError):
        result['probe_error'] = True
        if process is not None:
            result['exit_code'] = process.poll()
    finally:
        # Stop is deliberately not caught. A timed-out native helper cannot
        # remain alongside the later compatibility or candidate viewer.
        if process is not None and process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=2)
            except (OSError, subprocess.TimeoutExpired):
                pass
        path.unlink(missing_ok=True)
        previous.unlink(missing_ok=True)
        result['elapsed_wall_ms'] = max(0, int((time.monotonic() - started) * 1000))
    return result


def qualify(supervisor, environment):
    """Return a qualified GL candidate, not proof that DirectDraw passed."""
    report = {'format': 1, 'active': 'compatibility', 'state': 'fallback',
              'reason': 'unsupported_profile', 'directdraw_verified': False}
    if (supervisor.req.get('action') != 'update-client'
            or supervisor.req.get('renderer') != 'turnip26'
            or supervisor.state.get('dxvk_selected') != '2.5.3'
            or supervisor.engine not in ('box64', 'fex')):
        return dict(environment), report
    candidate = candidate_environment(environment)
    # CI may explicitly bind its software ICD. It must be the exact injected
    # path used by both Vulkan loader variables. Android launches with env -i
    # and never exposes this test-only switch.
    test_icd = os.environ.get('LSB_TEST_VULKAN_ICD')
    ci = bool(test_icd and candidate.get('VK_ICD_FILENAMES') == test_icd
              and candidate.get('VK_DRIVER_FILES') == test_icd)
    # Mesa 22 Zink rejects a CPU device unless CPU selection is explicit.
    # This opt-in is confined to the exact disposable CI ICD binding.
    if ci:
        candidate['LIBGL_ALWAYS_SOFTWARE'] = '1'
    supervisor.status('checking_updater_graphics', message='Checking OpenGL rendering for PlayOnline')
    report['probe'] = probe = run_probe(supervisor, candidate)
    device_ok = ci or (supervisor.state.get('hardware_verified') is True
                       and probe.get('accelerated') is True
                       and probe.get('cpu_renderer') is False
                       and probe.get('vendor_id') == 0x5143)
    if (probe.get('state') == 'completed' and probe.get('exit_code') == 0
            and probe.get('renderer') == 'zink' and probe.get('direct') is True and device_ok):
        report.update(active='zink', state='verified', reason='zink_opengl_verified')
        return candidate, report
    report['reason'] = 'zink_opengl_check_failed'
    return dict(environment), report
