"""Real staged PlayOnline supervision with a synthetic PE32 viewer.

Runs after initialization.py in the Box64 software and patched PRoot jobs.
No retail viewer, credentials, downloads, server, or game are involved. The
production COM registration, dependency checker and process runner execute
under the same Wine prefix as the other fixtures; none is mocked.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

sys.path.insert(0, '/opt/lsb')
from client_setup import imports

CLIENT = Path('/client')
SESSION = Path('/session')
LOGS = Path('/logs')
STAGED = CLIENT / 'Updater fixture'
PRIVATE = (b'PRIVATE-POL-STDOUT-SENTINEL', b'PRIVATE-POL-STDERR-SENTINEL',
           b'PRIVATE-ACCOUNT-TITLE-MUST-NOT-BE-RECORDED')


def configure_filter(request):
    """CI receipt from host-observed PRoot markers, never an assumed fast mode."""
    filtered = os.environ.get('LSB_TEST_FILTERED') == '1'
    request['proot_acceleration'] = filtered
    if not filtered:
        return
    deadline = time.monotonic() + 10
    marker = Path('/ci-filter/active')
    while not marker.exists() and time.monotonic() < deadline:
        time.sleep(.02)
    assert marker.read_text() == 'TRASC PRoot: seccomp acceleration observed\n', 'Actual parent filter marker absent'
    preflight = Path('/ci-filter/preflight')
    assert preflight.read_text() == 'LSB_PROOT_PREFLIGHT_V1 PASS\n', 'Filtered production preflight absent'
    # The observer owns this separate binding as host root. The traced tree
    # runs as the ordinary CI user, even though PRoot reports guest uid 0.
    for proof in (marker, preflight):
        try:
            with proof.open('ab'):
                pass
        except PermissionError:
            continue
        raise AssertionError('Guest can rewrite host filter activation proof')
    receipt = {'format': 1, 'session_id': request['session_id'], 'requested': 'syscall_filter',
               'preflight_passed': True, 'launch_observed': True, 'mode': 'syscall_filter',
               'reason': 'ci_host_marker_observed', 'preflight_ms': 0}
    (LOGS / 'proot-acceleration.json').write_text(json.dumps(receipt))


def assert_filter(state):
    acceleration = state.get('runtime_acceleration', {})
    if os.environ.get('LSB_TEST_FILTERED') == '1':
        assert acceleration.get('active') == 'syscall_filter', acceleration
        assert acceleration.get('host_preflight') == 'passed' and acceleration.get('host_launch_observed') is True, acceleration
    else:
        assert acceleration == {'requested': False, 'active': 'none'}, acceleration


def configure_updater_runtime(request):
    configure_filter(request)
    # Only the paired renderer matrix opts in. The existing software COM and
    # online-network cases keep their established requests and preparation.
    backend = os.environ.get('LSB_TEST_UPDATER_HARDWARE_GRAPHICS')
    if backend is not None:
        assert backend in ('0', '1'), 'Unknown updater DirectDraw matrix arm'
        request['updater_hardware_graphics'] = backend == '1'


def retain_glxinfo_failure_evidence():
    """Bounded existing glxinfo output from disposable CI fixtures only."""
    if (os.environ.get('LSB_TEST_MATRIX_MODE') != 'filtered'
            or os.environ.get('LSB_TEST_UPDATER_HARDWARE_GRAPHICS') != '1'):
        return
    import updater_gl
    original_probe = updater_gl.run_probe

    def probe(instance, environment):
        original_wait = instance.wait
        captured = {}
        result = None

        def wait(process, *args, **kwargs):
            try:
                return original_wait(process, *args, **kwargs)
            finally:
                if process.args == ['/usr/bin/glxinfo', '-B']:
                    instance.logs[-1].thread.join(2)
                    path = updater_gl.LOGS / 'updater-gl-check.log'
                    if path.is_file():
                        with path.open('rb') as stream:
                            data = stream.read(16385)
                        captured.update(output=data[:16384].decode('utf-8', errors='replace'),
                                        truncated=len(data) > 16384, exit_code=process.poll())

        instance.wait = wait
        try:
            result = original_probe(instance, environment)
            return result
        finally:
            instance.wait = original_wait
            if captured and (result is None or result.get('state') != 'completed'
                             or result.get('renderer') != 'zink' or result.get('direct') is not True):
                evidence = {'format': 1, 'scope': 'fixed glxinfo -B in a disposable CI fixture only',
                            'engine': os.environ.get('LSB_TEST_ENGINE'), **captured}
                (LOGS / 'ci-glxinfo-failure.json').write_text(json.dumps(evidence, indent=2))

    updater_gl.run_probe = probe


def assert_directdraw(report, implementation):
    """Require the fixed PE32 pixel workload and the observed Wine backend."""
    assert isinstance(report, dict), 'Production DirectDraw report is absent'
    assert implementation in ('compatibility', 'zink')
    assert report.get('requested') == ('hardware' if implementation == 'zink' else 'compatibility'), report
    assert report.get('active') == implementation, report
    assert report.get('state') == 'verified', report
    if implementation == 'zink':
        opengl = report.get('opengl', {})
        assert opengl.get('state') == 'verified' and opengl.get('active') == 'zink', opengl
        assert opengl.get('directdraw_verified') is True, opengl
        native = opengl.get('probe', {})
        assert native.get('state') == 'completed' and native.get('exit_code') == 0, native
        assert native.get('renderer') == 'zink' and native.get('direct') is True, native
        for name in ('accelerated', 'cpu_renderer'):
            assert type(native.get(name)) is bool, native
        for name in ('vendor_id', 'device_id'):
            assert type(native.get(name)) is int and 0 <= native[name] <= 0xffffffff, native
    probe = report.get('probe', {})
    assert probe.get('backend') == 'opengl' and probe.get('exit_code') == 0, probe
    assert probe.get('format') == 1 and probe.get('bits') == 32 and probe.get('passed') is True, probe
    assert probe.get('stage') == 'completed' and probe.get('hresult') == 0, probe
    for key in ('frames', 'expected_frames', 'colorfills', 'uploads', 'blits', 'ffp_frames', 'presents'):
        assert probe.get(key) == 24, (key, probe)
    assert probe.get('readback_samples') == probe.get('presentation_samples') == 192, probe
    assert type(probe.get('adapter_vendor_id')) is int and 0 < probe['adapter_vendor_id'] <= 0xffffffff, probe
    assert type(probe.get('adapter_device_id')) is int and 0 <= probe['adapter_device_id'] <= 0xffffffff, probe
    for key in ('elapsed_ms', 'render_ms', 'first_frame_ms', 'max_frame_ms', 'elapsed_wall_ms'):
        assert type(probe.get(key)) is int and 0 <= probe[key] <= 180000, (key, probe)
    assert probe['first_frame_ms'] <= probe['max_frame_ms'] <= probe['render_ms'] <= probe['elapsed_ms'], probe


def inventory(root, excluded=None):
    """Include new/deleted files and links, not only a known sentinel's bytes."""
    result = {}
    for path in sorted(root.rglob('*')):
        if excluded is not None and (path == excluded or excluded in path.parents):
            continue
        relative = str(path.relative_to(root))
        if path.is_symlink():
            result[relative] = ('link', os.readlink(path))
        elif path.is_file():
            result[relative] = ('file', hashlib.sha256(path.read_bytes()).hexdigest())
        elif path.is_dir():
            result[relative] = ('directory',)
        else:
            result[relative] = ('special',)
    return result


def assert_private_logs():
    for path in [*LOGS.rglob('*'), *SESSION.glob('*.json')]:
        if path.is_file():
            data = path.read_bytes()
            assert all(value not in data and value.decode().encode('utf-16le') not in data for value in PRIVATE), path


def main():
    CLIENT.mkdir(exist_ok=True)
    SESSION.mkdir(exist_ok=True)
    LOGS.mkdir(exist_ok=True)
    if STAGED.exists():
        shutil.rmtree(STAGED)
    original_client = inventory(CLIENT)
    # An independent active prefix/database and pointer remain outside the
    # staged client. Runtime tests cannot model Android's package isolation;
    # this verifies the updater's actual filesystem writes stay in its stage.
    protected = SESSION / 'updater-active-copy'
    protected.mkdir(exist_ok=True)
    for name, data in [('active.json', b'{"generation":"working-baseline"}\n'),
                       ('database.bin', b'unchanged active database\x00\x01'),
                       ('prefix.reg', b'unchanged active Wine settings\r\n')]:
        (protected / name).write_bytes(data)
    original_protected = inventory(protected)
    viewer = STAGED / 'Viewer'
    game = STAGED / 'Game'
    viewer.mkdir(parents=True)
    game.mkdir()
    components = [viewer / 'viewer/com/polcore.dll', viewer / 'viewer/com/app.dll',
                  viewer / 'viewer/contents/polcontentsINT.dll']
    for component in components:
        component.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile('/fixtures/client-com-stub.dll', component)
    component_hash = hashlib.sha256(Path('/fixtures/client-com-stub.dll').read_bytes()).hexdigest()
    (game / 'unrelated-client-data.dat').write_bytes(b'preserve staged game data\x00\xff')
    (STAGED / 'update-original-0.dat').write_bytes(b'preserve removed repair trigger')
    executable = viewer / 'pol.exe'

    # The high DWORD deliberately has a zero low byte. Direct Wine process
    # exits can truncate this to success; the Windows runner must preserve it.
    cases = [('visible-clean-exit', 0, True, False),
             ('no-window-high-exit', 0x80004000, False, False),
             ('missing-dependency', None, False, False),
             ('restarted-visible-clean-exit', 0, True, True)]
    try:
        for label, exit_code, window, restart in cases:
            for name in ('stop', 'status.json', 'playonline-process.json', 'loader-check.json', 'playonline-restarted.json'):
                (SESSION / name).unlink(missing_ok=True)
            source = 'login-missing.exe' if exit_code is None else 'playonline-stub.exe'
            shutil.copyfile('/fixtures/' + source, executable)
            # Fixture-only control files live beside the stub. Production
            # requests never accept an arbitrary command or environment.
            (viewer / 'playonline-fixture.txt').write_text(('%d\n%d\n%d\n' %
                (exit_code or 0, int(window), 5000 if restart else 1600 if window else 0)) + ('1\n' if restart else ''))
            manifest = {'format': 1, 'generation': str(uuid.uuid4()), 'region': 'US',
                        'pol': str(viewer.relative_to(CLIENT)), 'game': str(game.relative_to(CLIENT)),
                        'executable': str(executable.relative_to(CLIENT)),
                        'sha256': hashlib.sha256(executable.read_bytes()).hexdigest()}
            request = {'format': 1, 'engine': os.environ.get('LSB_TEST_ENGINE', 'box64'), 'session_id': str(uuid.uuid4()),
                       'renderer': os.environ.get('LSB_TEST_RENDERER', 'software'), 'audio': False, 'action': 'update-client',
                       'dxvk_version': '2.5.3', 'proot_acceleration': False}
            configure_updater_runtime(request)
            (SESSION / 'client-update-manifest.json').write_text(json.dumps(manifest))
            (SESSION / 'request.json').write_text(json.dumps(request))
            expected_imports = imports(executable)
            assert expected_imports and 'kernel32.dll' in [name.lower() for name in expected_imports], expected_imports
            staged_before = inventory(STAGED)
            process = subprocess.run(['python3', '/opt/lsb/supervisor.py'], timeout=480)
            state = json.loads((SESSION / 'status.json').read_text())
            assert_filter(state)
            report = json.loads((LOGS / 'client-update.json').read_text())
            assert report['generation'] == manifest['generation'] and report['session_id'] == request['session_id'], report
            assert report['activation_performed'] is False and report['official_repair_confirmed'] is False, report
            assert report['credentials_forwarded'] is False, report
            registration = report['component_registration']
            batch = report['component_batch']
            assert batch['policy'] == 'live_six_steps_one_process' and batch['exit_code'] == 0 and batch['receipt_valid'], batch
            assert batch['startup_diagnostics']['policy'] == 'fixed_metadata_only', batch
            assert [row['component'] for row in registration] == ['core', 'app', 'contents'], registration
            assert [row['dll'] for row in registration] == [path.name for path in components], registration
            for row, operations in zip(registration, [('register', 'com'), ('register', 'class'), ('register', 'class')]):
                assert row['sha256'] == component_hash, row
                assert [step['operation'] for step in row['steps']] == list(operations), row
                for step in row['steps']:
                    assert step['result'] == {'format': 1, 'bits': 32, 'operation': step['operation'],
                                              'ok': True, 'hresult': 0, 'win32_error': 0}, step
            assert 'loaded_path' not in json.dumps(registration) and 'detail' not in json.dumps(registration), registration
            attempts = report['dependency_attempts']
            assert len(attempts) == 1 and attempts[0]['receipt_valid'] is True, attempts
            dependencies = attempts[0]['dependencies']
            assert [row['name'] for row in dependencies] == expected_imports, dependencies
            assert all(set(row) == {'name', 'ok', 'win32_error'} for row in dependencies), dependencies
            if exit_code is None:
                missing = [row for row in dependencies if not row['ok']]
                assert any(row['name'].lower() == 'lsb-missing-fixture.dll' and row['win32_error'] == 126 for row in missing), missing
                assert attempts[0]['exit_code'] == 1, attempts
                assert process.returncode != 0 and state['phase'] == 'error' and report['status'] == 'interrupted', report
                assert not (SESSION / 'playonline-process.json').exists(), 'viewer started despite a missing dependency'
                assert 'process' not in report and 'viewer_exit_code' not in report, report
            else:
                if os.environ.get('LSB_TEST_UPDATER_HARDWARE_GRAPHICS') == '1':
                    assert_directdraw(report.get('directdraw_graphics'), 'zink')
                assert attempts[0]['exit_code'] == 0 and all(row['ok'] and row['win32_error'] == 0 for row in dependencies), attempts
                native = report['process']
                assert native['format'] == 2 and native['bits'] == 32 and native['phase'] == 'exited', native
                assert native['child_exit'] == exit_code and native['child_pid'] > 0, native
                assert native['win32_error'] == 0 and native['window_error'] == 0, native
                assert native['visible_window_seen'] is (window and not restart) and native['elapsed_ms'] >= 0, native
                if restart:
                    replacement = json.loads((SESSION / 'playonline-restarted.json').read_text())
                    assert replacement['format'] == 1 and replacement['phase'] == 'exited' and replacement['child_exit'] == 0, replacement
                    assert replacement['canonical_image'] is True and replacement['working_directory'] is True, replacement
                    assert replacement['elapsed_ms'] >= 5000 and replacement['child_pid'] != native['child_pid'], replacement
                assert report['prefix_wait_exit_code'] == 0, report
                assert report['startup_diagnostics']['policy'] == 'fixed_metadata_only', report
                if exit_code == 0:
                    assert process.returncode == 0 and state['phase'] == 'completed' and report['status'] == 'verification_pending', report
                    assert report['viewer_exit_code'] == 0, report
                else:
                    assert process.returncode != 0 and state['phase'] == 'error' and report['status'] == 'interrupted', report
                    assert report['viewer_exit_code'] != 0 and '80004000' in report['error'], report
            assert inventory(STAGED) == staged_before, 'the runner changed or discarded the staged client'
            assert inventory(CLIENT, excluded=STAGED) == original_client, 'the active client fixture changed'
            assert inventory(protected) == original_protected, 'the active database, prefix, or pointer changed'
            assert not (SESSION / 'display.sock').exists(), 'updater display was left running'
            assert_private_logs()
            (LOGS / ('update-' + label + '.json')).write_text(json.dumps(report, indent=2))
            print('PASS: PlayOnline update', label, 'real COM registration and dependencies, full Windows exit, private diagnostics and isolated stage', flush=True)
    finally:
        shutil.rmtree(STAGED)


if __name__ == '__main__':
    main()
