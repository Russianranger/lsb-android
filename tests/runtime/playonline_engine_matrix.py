"""Focused engine qualification inside actual PRoot, with synthetic data only.

The official viewer is the pinned public installer copy, offline. This does not
claim completion of the owner's repair or reproduce Android GPU performance.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

from updating import inventory


def seed():
    env = dict(os.environ, WINEPREFIX='/prefix', WINEARCH='win64', WINEDEBUG='-all',
               WINEDLLOVERRIDES='winemenubuilder,mscoree,mshtml,winegstreamer=',
               BOX64_LOG='0', BOX64_NOBANNER='1', BOX64_MAXCPU='0',
               BOX64_PATH='/opt/wine/bin',
               BOX64_LD_LIBRARY_PATH='/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu:/opt/wine/lib/wine/x86_64-unix')
    command = ['/usr/local/bin/box64', '/opt/wine/bin/wine', 'wineboot', '-u']
    try:
        subprocess.run(command, env=env, check=True, timeout=180, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    finally:
        subprocess.run(['/usr/local/bin/box64', '/opt/wine/bin/wineserver', '-k'], env=env,
                       timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        subprocess.run(['/usr/local/bin/box64', '/opt/wine/bin/wineserver', '-w'], env=env,
                       timeout=30, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    Path('/prefix/lsb-matrix-preserved.txt').write_text('unchanged stopped Box64 seed\n')
    assert Path('/prefix/system.reg').is_file(), 'Box64 seed prefix was not initialized'
    print('PASS: stopped Box64 seed prefix prepared for both engine copies', flush=True)


def main():
    if sys.argv[1:] == ['seed']:
        seed()
        return
    assert not sys.argv[1:]
    engine = os.environ['LSB_TEST_ENGINE']
    assert engine in ('box64', 'fex')
    protected = inventory(Path('/baseline-prefix'))
    official = inventory(Path('/official'))
    source_hash = hashlib.sha256(json.dumps(protected, sort_keys=True).encode()).hexdigest()
    import playonline_smoke
    playonline_smoke.main()
    report = json.loads(Path('/logs/official-playonline-smoke.json').read_text())
    production = report['production']
    state = production['runtime']
    assert state['runtime_engine'] == engine and state['dxvk_selected'] == '2.5.3', state
    assert state['runtime_acceleration'] == {'requested': False, 'active': 'none'}, state
    if engine == 'fex': assert state['fex_execution_verified'] is True, state
    benchmark = production.get('repair_io')
    assert isinstance(benchmark, dict), 'Production updater did not retain its file-I/O comparison'
    print('Engine repair I/O:', engine, json.dumps(benchmark, sort_keys=True), flush=True)
    assert benchmark['status'] == 'completed', 'Synthetic file-I/O comparison did not complete'
    assert benchmark['native']['state'] == benchmark['windows']['state'] == 'completed', benchmark
    # Reuse the same independent engine prefix for all actual production
    # component/dependency/high-exit/restarted-viewer supervision fixtures.
    import updating
    updating.main()
    assert inventory(Path('/baseline-prefix')) == protected, 'Engine changed the protected Box64 seed'
    assert inventory(Path('/official')) == official, 'Engine changed the pinned official viewer input'
    result = {'format': 1, 'engine': engine, 'transport': 'patched_proot', 'network': 'disabled',
              'renderer': 'DXVK 2.5.3 / CI lavapipe', 'filtering': False,
              'baseline_prefix_sha256': source_hash, 'baseline_prefix_preserved': True,
              'official_input_preserved': True, 'fex_execution_verified': engine != 'fex' or state['fex_execution_verified'],
              'component_registration_verified': production['component_registration_verified'],
              'lifecycle_cases': ['visible-clean-exit', 'no-window-high-exit', 'missing-dependency', 'restarted-visible-clean-exit'],
              'repair_io': benchmark, 'online_repair_verified': False,
              'interpretation': 'Synthetic workload and offline viewer qualify engine compatibility; phone repair throughput still needs measurement.'}
    Path('/logs/playonline-engine-matrix.json').write_text(json.dumps(result, indent=2))
    print('PASS:', engine, 'actual PRoot, copied prefix, official viewer, file workload and restarted viewer; source inputs unchanged', flush=True)


if __name__ == '__main__':
    main()
