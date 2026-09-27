"""Actual PRoot updater qualification; fixed synthetic data and public viewer only.

Each engine has an unfiltered preparation/benchmark and a filtered full viewer
and lifecycle pass. This does not claim Android GPU or online repair throughput.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import uuid

from updating import assert_filter, configure_filter, inventory


def seed():
    env = dict(os.environ, WINEPREFIX='/prefix', WINEARCH='win64', WINEDEBUG='-all',
               WINEDLLOVERRIDES='winemenubuilder,mscoree,mshtml,winegstreamer=',
               BOX64_LOG='0', BOX64_NOBANNER='1', BOX64_MAXCPU='0',
               BOX64_PATH='/opt/wine/bin',
               BOX64_LD_LIBRARY_PATH='/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu:/opt/wine/lib/wine/x86_64-unix')
    try:
        subprocess.run(['/usr/local/bin/box64', '/opt/wine/bin/wine', 'wineboot', '-u'], env=env,
                       check=True, timeout=180, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        subprocess.run(['/usr/local/bin/box64', '/opt/wine/bin/wineserver', '-k'], env=env,
                       timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['/usr/local/bin/box64', '/opt/wine/bin/wineserver', '-w'], env=env,
                       timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    Path('/prefix/lsb-matrix-preserved.txt').write_text('unchanged stopped Box64 seed\n')
    assert Path('/prefix/system.reg').is_file(), 'Box64 seed prefix was not initialized'
    print('PASS: stopped Box64 seed prefix prepared for both engine copies', flush=True)


def benchmark_worker():
    import client_update
    import supervisor
    original = client_update.repair_io_check

    def measure_then_stop(instance, environment):
        # Keep production path/COM/dependency warmup identical, then stop before
        # the viewer. No mocking of Windows I/O, preparation or supervision.
        result = original(instance, environment)
        Path('/logs/benchmark-only.json').write_text(json.dumps(result, indent=2))
        raise supervisor.Stopped()

    client_update.repair_io_check = measure_then_stop
    supervisor.main()


def baseline(engine):
    from playonline_smoke import POL_SHA
    prefix = Path('/prefix')
    shutil.copytree('/baseline-prefix', prefix, symlinks=True, dirs_exist_ok=True)
    if engine == 'fex':
        bundle = json.loads(Path('/opt/lsb/fex-bundle.json').read_text())
        (prefix / 'lsb-runtime-engine.json').write_text(json.dumps({'engine': 'fex', 'runtime': bundle['sha256']}))
        (prefix / 'lsb-prefix-ready.json').unlink(missing_ok=True)
    shutil.copytree('/official', '/client/Viewer')
    Path('/client/Game').mkdir()
    request = {'format': 1, 'engine': engine, 'session_id': str(uuid.uuid4()),
               'renderer': 'turnip26', 'audio': False, 'action': 'update-client',
               'dxvk_version': '2.5.3', 'repair_diagnostics': True}
    configure_filter(request)
    manifest = {'format': 1, 'generation': str(uuid.uuid4()), 'region': 'US', 'pol': 'Viewer',
                'game': 'Game', 'executable': 'Viewer/pol.exe', 'sha256': POL_SHA}
    Path('/session/request.json').write_text(json.dumps(request))
    Path('/session/client-update-manifest.json').write_text(json.dumps(manifest))
    subprocess.run([sys.executable, __file__, '--benchmark-worker'], check=True, timeout=780)
    state = json.loads(Path('/logs/runtime-state.json').read_text())
    report = json.loads(Path('/logs/client-update.json').read_text())
    assert state['session_id'] == report['session_id'] == request['session_id'], 'Stale baseline receipt'
    assert state['phase'] == 'stopped', state
    assert report['generation'] == manifest['generation'], 'Stale baseline generation'
    batch = report['component_batch']
    assert batch['policy'] == 'live_six_steps_one_process' and batch['exit_code'] == 0 and batch['receipt_valid'], batch
    attempts = report['dependency_attempts']
    assert len(attempts) == 1 and attempts[0]['receipt_valid'] and attempts[0]['exit_code'] == 0, attempts
    assert not Path('/session/playonline-process.json').exists(), 'Short baseline launched the viewer'
    return state, json.loads(Path('/logs/benchmark-only.json').read_text())


def fex_initialization(state):
    if state['runtime_engine'] != 'fex':
        return None
    report = state.get('prefix_initialization', {})
    assert (report.get('policy') == 'single_fex_updater_initialization' and
            report.get('state') == 'completed' and report.get('exit_code') == 0), report
    log = Path('/logs/wine-setup.log').read_text(errors='replace')
    loads = [line for line in log.splitlines() if ':trace:loaddll:build_module Loaded L"' in line
             and 'rundll32.exe"' in line.lower()]
    host = sum(bool(re.search(r' at [0-9a-fA-F]{16}:', line)) for line in loads)
    wow64 = sum(bool(re.search(r' at [0-9a-fA-F]{8}:', line)) for line in loads)
    assert (len(loads), host, wow64) == (3, 2, 1), ('Duplicate or missing FEX initialization passes', len(loads), host, wow64)
    return {'policy': report['policy'], 'state': report['state'], 'elapsed_ms': report['elapsed_ms'],
            'host_registration_loads': host, 'wow64_registration_loads': wow64,
            'total_registration_loads': len(loads)}


def main():
    if sys.argv[1:] == ['seed']:
        seed(); return
    if sys.argv[1:] == ['--benchmark-worker']:
        benchmark_worker(); return
    assert not sys.argv[1:]
    engine = os.environ['LSB_TEST_ENGINE']
    mode = os.environ['LSB_TEST_MATRIX_MODE']
    assert engine in ('box64', 'fex') and mode in ('baseline', 'filtered')
    assert (os.environ.get('LSB_TEST_FILTERED') == '1') == (mode == 'filtered')
    protected = inventory(Path('/baseline-prefix'))
    official = inventory(Path('/official'))
    source_hash = hashlib.sha256(json.dumps(protected, sort_keys=True).encode()).hexdigest()
    if mode == 'baseline':
        state, benchmark = baseline(engine)
    else:
        import playonline_smoke
        playonline_smoke.main()
        production = json.loads(Path('/logs/official-playonline-smoke.json').read_text())['production']
        state, benchmark = production['runtime'], production.get('repair_io')
    assert state['runtime_engine'] == engine and state['dxvk_selected'] == '2.5.3', state
    assert_filter(state)
    if engine == 'fex':
        assert state['fex_execution_verified'] is True, state
    initialization = fex_initialization(state)
    assert isinstance(benchmark, dict), 'Production updater did not retain its file-I/O comparison'
    assert benchmark['status'] == 'completed', 'Synthetic file-I/O comparison did not complete'
    assert benchmark['native']['state'] == benchmark['windows']['state'] == 'completed', benchmark
    lifecycle = []
    if mode == 'filtered':
        # Preserve full actual production component/dependency/high-exit and
        # restarted-viewer supervision without repeating it in the short arm.
        import updating
        updating.main()
        if engine == 'fex':
            final_state = json.loads(Path('/logs/runtime-state.json').read_text())
            assert 'prefix_initialization' not in final_state, 'Ready FEX updater unnecessarily migrated again'
        lifecycle = ['visible-clean-exit', 'no-window-high-exit', 'missing-dependency', 'restarted-visible-clean-exit']
    assert inventory(Path('/baseline-prefix')) == protected, 'Engine changed the protected Box64 seed'
    assert inventory(Path('/official')) == official, 'Engine changed the pinned official viewer input'
    result = {'format': 2, 'engine': engine, 'mode': mode, 'transport': 'patched_proot', 'network': 'disabled',
              'renderer': 'DXVK 2.5.3 / CI lavapipe', 'filtering': mode == 'filtered',
              'runtime_acceleration': state['runtime_acceleration'],
              'baseline_prefix_sha256': source_hash, 'baseline_prefix_preserved': True,
              'official_input_preserved': True, 'fex_execution_verified': state.get('fex_execution_verified'),
              'prefix_initialization': initialization, 'component_registration_verified': True,
              'lifecycle_cases': lifecycle, 'repair_io': benchmark, 'online_repair_verified': False,
              'interpretation': 'One paired synthetic sample, independent copied prefixes, identical production preparation. No phone repair throughput or speedup threshold is claimed.'}
    Path('/logs/playonline-engine-matrix.json').write_text(json.dumps(result, indent=2))
    print('PASS:', engine, mode, 'actual PRoot, copied prefix and bounded Windows workload; source inputs unchanged', flush=True)


if __name__ == '__main__':
    main()
