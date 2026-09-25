"""Official PlayOnline UI on a staged mount. No headless patcher or login payload."""
import hashlib
import json
from pathlib import Path
import re

from client_setup import client_path, imports, windows_path

SESSION = Path('/session')
LOGS = Path('/logs')


def validate_manifest(data):
    fields = {'format', 'generation', 'region', 'pol', 'game', 'executable', 'sha256'}
    if set(data) != fields or data['format'] != 1 or data['region'] not in ('US', 'EU', 'JP'):
        raise ValueError('Invalid PlayOnline update manifest')
    if not isinstance(data['generation'], str) or not re.fullmatch(r'[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}', data['generation']):
        raise ValueError('Invalid update generation')
    pol, game, executable = (client_path(data[key]) for key in ('pol', 'game', 'executable'))
    if pol == game or not pol.is_dir() or not game.is_dir() or executable.parent != pol or executable.name.lower() != 'pol.exe':
        raise ValueError('PlayOnline executable does not match the staged viewer')
    if not isinstance(data['sha256'], str) or not re.fullmatch(r'[0-9a-f]{64}', data['sha256']):
        raise ValueError('Invalid PlayOnline checksum')
    digest = hashlib.sha256()
    with executable.open('rb') as file:
        for chunk in iter(lambda: file.read(1024 * 1024), b''):
            digest.update(chunk)
    if digest.hexdigest() != data['sha256']:
        raise ValueError('Staged PlayOnline changed before launch; reopen the updater')
    imports(executable)  # Verify bounded x86 PE32 headers before execution.
    return pol, executable


def run(supervisor):
    from supervisor import atomic
    manifest = json.loads((SESSION / 'client-update-manifest.json').read_text())
    pol, executable = validate_manifest(manifest)
    report = {'format': 1, 'generation': manifest['generation'], 'session_id': supervisor.req['session_id'],
              'status': 'opening', 'region': manifest['region'], 'official_repair_confirmed': False,
              'activation_performed': False, 'credentials_forwarded': False}

    def record():
        atomic(LOGS / 'client-update.json', report)
        supervisor.status(client_update=report)

    record()
    try:
        # The existing 32-bit helper writes and reads back the regional install
        # paths. No new registry guesses and no game DLL registration mid-repair.
        result = SESSION / 'client-step.json'
        result.unlink(missing_ok=True)
        worker = supervisor.spawn(supervisor.wine_command(r'P:\client-init.exe', 'registry', manifest['region'],
                                  windows_path(manifest['pol']), windows_path(manifest['game'])), 'update-registry.log')
        supervisor.wait(worker, 90, 'Staged PlayOnline registration')
        receipt = json.loads(result.read_text()) if result.is_file() else {}
        if receipt.get('operation') != 'registry' or receipt.get('ok') is not True or receipt.get('bits') != 32:
            raise RuntimeError('Missing successful 32-bit staged PlayOnline registry receipt')
        validate_manifest(manifest)
        # Users operate the official viewer themselves. Never persist arbitrary
        # POL output, which could include a retail account entered in its UI.
        supervisor.private_output = True
        environment = dict(supervisor.env, WINEDEBUG='-all', BOX64_LOG='0')
        report['status'] = 'viewer_open'; record()
        supervisor.status('playonline_update', message='In PlayOnline: Check Files → FINAL FANTASY XI → Check Files → File Repair. Exit the viewer after repair completes.')
        viewer = supervisor.spawn(supervisor.wine_command(windows_path(manifest['executable'])), 'playonline.log', env=environment, cwd=pol)
        supervisor.wait(viewer, 7200, 'PlayOnline viewer')
        # PlayOnline may replace itself and restart. Waiting for the entire
        # prefix prevents cleanup from killing detached updater/viewer children.
        supervisor.status('playonline_update', message='Waiting for PlayOnline and any restarted updater to close. Finish File Repair in the display, then Exit Viewer.')
        waiter = supervisor.spawn(supervisor.server_command('-w'), 'playonline-wait.log', env=environment)
        supervisor.wait(waiter, 7200, 'PlayOnline updater and restarted viewer')
        report['status'] = 'verification_pending'; record()
        supervisor.status('completed', message='PlayOnline closed. Confirm File Repair completed, then verify the staged update. The active client has not changed.')
    except BaseException as error:
        report['status'] = 'interrupted'; report['error'] = str(error) or type(error).__name__; record()
        raise
