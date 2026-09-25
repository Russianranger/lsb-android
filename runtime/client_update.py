"""Official PlayOnline UI on a staged mount. No headless patcher or login payload."""
import hashlib
import json
from pathlib import Path
import re
import time

from client_setup import client_path, imports, windows_path
from client_launch import retry_network_initialization, valid_check_rows

SESSION = Path('/session')
LOGS = Path('/logs')


def private_environment(supervisor):
    # The pipe parser emits fixed categories/codes only. DXVK must use that
    # pipe too, rather than retaining an unfiltered application-named log.
    environment = dict(supervisor.env,
        WINEDEBUG='-all,+timestamp,+pid,err+all,warn+module,warn+seh,trace+seh,trace+loaddll,fixme+ole',
        BOX64_LOG='0', BOX64_NOBANNER='1', DXVK_LOG_LEVEL='info', DXVK_LOG_PATH='none')
    # Wine bug 56462 / upstream c0779ad: ir50_32's delayed winegstreamer
    # import can abort POL startup when the optional GStreamer bridge is
    # disabled. Make this unavailable codec explicit only for the updater;
    # do not change the copied registry, shared environment or game launch.
    environment['WINEDLLOVERRIDES'] = supervisor.env.get('WINEDLLOVERRIDES', '') + ';ir50_32='
    return environment


def diagnostics(writer, drain=False):
    if drain:
        writer.thread.join(2)
    return writer.events.diagnostics.snapshot()


def process_receipt():
    path = SESSION / 'playonline-process.json'
    try:
        value = json.loads(path.read_text()) if path.stat().st_size <= 4096 else {}
    except (OSError, ValueError):
        return None
    fields = {'format', 'bits', 'phase', 'win32_error', 'child_exit', 'child_pid',
              'window_error', 'visible_window_seen', 'elapsed_ms'}
    if (not isinstance(value, dict) or set(value) != fields or value['format'] != 1 or value['bits'] != 32 or
            value['phase'] not in ('running', 'exited', 'create_failed', 'wait_failed') or
            type(value['visible_window_seen']) is not bool or
            any(type(value[key]) is not int or not 0 <= value[key] <= 0xffffffff
                for key in ('win32_error', 'child_exit', 'child_pid', 'window_error')) or
            type(value['elapsed_ms']) is not int or not 0 <= value['elapsed_ms'] <= 0xffffffffffffffff):
        return None
    return value


def check_dependencies(supervisor, manifest, executable, environment, report, record):
    expected = imports(executable)
    if not expected or len(expected) > 128 or any(len(name) > 128 for name in expected):
        raise ValueError('PlayOnline has an unsupported dependency inventory')
    receipt_path = SESSION / 'loader-check.json'
    report['dependency_attempts'] = []
    for attempt in range(2):
        supervisor.stopped()
        receipt_path.unlink(missing_ok=True)
        supervisor.status('checking_playonline_dependencies', message='Checking PlayOnline dependencies in the staged copy')
        process = supervisor.spawn(supervisor.wine_command(r'P:\client-launch.exe', 'check',
            windows_path(manifest['executable']), *expected),
            'playonline-dependencies' + ('-retry' if attempt else '') + '.log', env=environment)
        writer = supervisor.logs[-1]
        result = {'exit_code': None, 'receipt_valid': False}
        report['dependency_attempts'].append(result)
        try:
            supervisor.wait(process, 90, 'PlayOnline dependency check', accepted=(0, 1))
        finally:
            result['exit_code'] = process.poll()
            result['startup_diagnostics'] = diagnostics(writer, drain=True)
            record()
        # A stale, truncated or malformed receipt never authorizes the viewer.
        try:
            checked = json.loads(receipt_path.read_text()) if receipt_path.stat().st_size <= 128 * 1024 else {}
        except (OSError, ValueError):
            checked = {}
        valid = valid_check_rows(checked, expected)
        rows = checked.get('dependencies', []) if valid else []
        result['receipt_valid'] = valid
        result['dependencies'] = [{key: row[key] for key in ('name', 'ok', 'win32_error')} for row in rows]
        record()
        if attempt == 0 and retry_network_initialization(checked, expected, process.returncode):
            supervisor.status('retrying_playonline_network', message='Retrying Windows networking initialization before PlayOnline opens')
            continue
        if not valid:
            raise RuntimeError('PlayOnline dependency checker did not return a valid receipt; export Diagnostics')
        failed = [row for row in rows if not row['ok']]
        if process.returncode == 1 and failed:
            detail = ', '.join(row['name'] + ' (Windows error ' + str(row['win32_error']) + ')' for row in failed[:3])
            raise RuntimeError('PlayOnline dependency failed: ' + detail + '. The active client is unchanged; export Diagnostics')
        if (process.returncode != 0 or checked.get('ok') is not True or failed or
                any(row['win32_error'] != 0 or not isinstance(row.get('loaded_path'), str) or not row['loaded_path'] for row in rows)):
            raise RuntimeError('PlayOnline dependencies did not pass; export Diagnostics')
        return


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
    writer = None
    viewer = None
    viewer_started = None
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
        environment = private_environment(supervisor)
        check_dependencies(supervisor, manifest, executable, environment, report, record)
        report['status'] = 'viewer_open'; record()
        supervisor.status('playonline_update', message='In PlayOnline: Check Files → FINAL FANTASY XI → Check Files → File Repair. Exit the viewer after repair completes.')
        (SESSION / 'playonline-process.json').unlink(missing_ok=True)
        viewer_started = time.monotonic()
        viewer = supervisor.spawn(supervisor.wine_command(r'P:\playonline-run.exe', windows_path(manifest['executable'])),
                                  'playonline.log', env=environment, cwd=pol)
        writer = supervisor.logs[-1]
        # A self-updater may exit its first process with a nonzero result while
        # a detached replacement is still running. Preserve the result, but do
        # not let supervisor cleanup terminate that replacement prematurely.
        supervisor.wait(viewer, 7200, 'PlayOnline viewer', accepted=range(-255, 256))
        report['viewer_exit_code'] = viewer.returncode
        report['viewer_elapsed_ms'] = max(0, int((time.monotonic() - viewer_started) * 1000))
        report['process'] = process_receipt()
        report['startup_diagnostics'] = diagnostics(writer)
        report['status'] = 'waiting_for_viewer_exit'; record()
        # PlayOnline may replace itself and restart. Waiting for the entire
        # prefix prevents cleanup from killing detached updater/viewer children.
        supervisor.status('playonline_update', message='Waiting for PlayOnline and any restarted updater to close. Finish File Repair in the display, then Exit Viewer.')
        waiter = supervisor.spawn(supervisor.server_command('-w'), 'playonline-wait.log', env=environment)
        wait_writer = supervisor.logs[-1]
        try:
            supervisor.wait(waiter, 7200, 'PlayOnline updater and restarted viewer')
        finally:
            report['prefix_wait_exit_code'] = waiter.poll()
            report['prefix_wait_diagnostics'] = diagnostics(wait_writer, drain=True)
            record()
        process = process_receipt()
        report['process'] = process
        if not process or process['phase'] != 'exited' or process['win32_error'] != 0 or process['child_exit'] != 0 or viewer.returncode != 0:
            if process:
                detail = 'Windows exit 0x%08X, error %d' % (process['child_exit'], process['win32_error'])
            else:
                detail = 'missing Windows process receipt; runner exit ' + str(viewer.returncode)
            raise RuntimeError('PlayOnline did not close cleanly (' + detail +
                '). The staged copy is retained. Export Diagnostics for its startup result')
        report['status'] = 'verification_pending'; record()
        supervisor.status('completed', message='PlayOnline closed. Confirm File Repair completed, then verify the staged update. The active client has not changed.')
    except BaseException as error:
        report['status'] = 'interrupted'; report['error'] = str(error) or type(error).__name__; record()
        raise
    finally:
        if writer is not None:
            report['startup_diagnostics'] = diagnostics(writer, drain=True)
            report['viewer_exit_code'] = viewer.poll()
            report['process'] = process_receipt()
            report['viewer_elapsed_ms'] = report.get('viewer_elapsed_ms', max(0, int((time.monotonic() - viewer_started) * 1000)))
            record()
