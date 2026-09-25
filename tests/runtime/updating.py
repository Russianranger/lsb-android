"""Real staged PlayOnline supervision with a synthetic PE32 viewer.

Runs after initialization.py in the Box64 software and patched PRoot jobs.
No retail viewer, credentials, downloads, server, or game are involved. The
production dependency checker and process runner execute under the same Wine
prefix as the other runtime fixtures; neither helper is mocked.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid

sys.path.insert(0, '/opt/lsb')
from client_setup import imports

CLIENT = Path('/client')
SESSION = Path('/session')
LOGS = Path('/logs')
STAGED = CLIENT / 'Updater fixture'
PRIVATE = (b'PRIVATE-POL-STDOUT-SENTINEL', b'PRIVATE-POL-STDERR-SENTINEL',
           b'PRIVATE-ACCOUNT-TITLE-MUST-NOT-BE-RECORDED')


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
    (game / 'unrelated-client-data.dat').write_bytes(b'preserve staged game data\x00\xff')
    (STAGED / 'update-original-0.dat').write_bytes(b'preserve removed repair trigger')
    executable = viewer / 'pol.exe'

    # The high DWORD deliberately has a zero low byte. Direct Wine process
    # exits can truncate this to success; the Windows runner must preserve it.
    cases = [('visible-clean-exit', 0, True),
             ('no-window-high-exit', 0x80004000, False),
             ('missing-dependency', None, False)]
    try:
        for label, exit_code, window in cases:
            for name in ('stop', 'status.json', 'playonline-process.json', 'loader-check.json'):
                (SESSION / name).unlink(missing_ok=True)
            source = 'login-missing.exe' if exit_code is None else 'playonline-stub.exe'
            shutil.copyfile('/fixtures/' + source, executable)
            # Fixture-only control files live beside the stub. Production
            # requests never accept an arbitrary command or environment.
            (viewer / 'playonline-fixture.txt').write_text('%d\n%d\n%d\n' %
                                                         (exit_code or 0, int(window), 1600 if window else 0))
            manifest = {'format': 1, 'generation': str(uuid.uuid4()), 'region': 'US',
                        'pol': str(viewer.relative_to(CLIENT)), 'game': str(game.relative_to(CLIENT)),
                        'executable': str(executable.relative_to(CLIENT)),
                        'sha256': hashlib.sha256(executable.read_bytes()).hexdigest()}
            request = {'format': 1, 'engine': 'box64', 'session_id': str(uuid.uuid4()),
                       'renderer': 'software', 'audio': False, 'action': 'update-client'}
            (SESSION / 'client-update-manifest.json').write_text(json.dumps(manifest))
            (SESSION / 'request.json').write_text(json.dumps(request))
            expected_imports = imports(executable)
            assert expected_imports and 'kernel32.dll' in [name.lower() for name in expected_imports], expected_imports
            staged_before = inventory(STAGED)
            process = subprocess.run(['python3', '/opt/lsb/supervisor.py'], timeout=480)
            state = json.loads((SESSION / 'status.json').read_text())
            report = json.loads((LOGS / 'client-update.json').read_text())
            assert report['generation'] == manifest['generation'] and report['session_id'] == request['session_id'], report
            assert report['activation_performed'] is False and report['official_repair_confirmed'] is False, report
            assert report['credentials_forwarded'] is False, report
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
                assert attempts[0]['exit_code'] == 0 and all(row['ok'] and row['win32_error'] == 0 for row in dependencies), attempts
                native = report['process']
                assert native['format'] == 1 and native['bits'] == 32 and native['phase'] == 'exited', native
                assert native['child_exit'] == exit_code and native['child_pid'] > 0, native
                assert native['win32_error'] == 0 and native['window_error'] == 0, native
                assert native['visible_window_seen'] is window and native['elapsed_ms'] >= 0, native
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
            print('PASS: PlayOnline update', label, 'real dependency checks, full Windows exit, private diagnostics and isolated stage', flush=True)
    finally:
        shutil.rmtree(STAGED)


if __name__ == '__main__':
    main()
