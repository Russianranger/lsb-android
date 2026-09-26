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


def elapsed_ms(started, ended=None):
    return max(0, int(((time.monotonic() if ended is None else ended) - started) * 1000))


def viewer_version_config(receipt):
    """Allow only fixed repair metadata, never registry strings or paths."""
    data = receipt.get('viewer_version_config') if isinstance(receipt, dict) else None
    states = {'not_checked', 'existing_preserved', 'registry_unavailable', 'file_unavailable',
              'format_not_matched', 'invalid_path', 'restore_failed', 'restored_missing'}
    fields = {'state', 'version', 'candidate', 'win32_error', 'rollback_error', 'content_id'}
    if (not isinstance(data, dict) or set(data) != fields or not isinstance(data['state'], str) or data['state'] not in states or
            not isinstance(data['candidate'], str) or data['candidate'] not in ('none', 'zero', 'official_installer') or
            type(data['content_id']) is not int or data['content_id'] != 1000 or
            any(type(data[key]) is not int or not 0 <= data[key] <= 0xffffffff
                for key in ('win32_error', 'rollback_error')) or
            not isinstance(data['version'], str) or
            (data['version'] and not re.fullmatch(r'[0-9]{8}_[A-Za-z0-9]{1,8}', data['version']))):
        raise RuntimeError('Invalid PlayOnline version-metadata receipt; export Diagnostics')
    if data['state'] == 'restored_missing' and (not data['version'] or data['candidate'] == 'none' or
                                              data['win32_error'] or data['rollback_error']):
        raise RuntimeError('Inconsistent PlayOnline version repair receipt; export Diagnostics')
    return dict(data)


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
        result = {'exit_code': None, 'receipt_valid': False}
        report['dependency_attempts'].append(result)
        started = time.monotonic()
        process = writer = None
        try:
            process = supervisor.spawn(supervisor.wine_command(r'P:\client-launch.exe', 'check',
                windows_path(manifest['executable']), *expected),
                'playonline-dependencies' + ('-retry' if attempt else '') + '.log', env=environment)
            writer = supervisor.logs[-1]
            supervisor.wait(process, 90, 'PlayOnline dependency check', accepted=(0, 1))
        finally:
            result['exit_code'] = process.poll() if process is not None else None
            if writer is not None:
                result['startup_diagnostics'] = diagnostics(writer, drain=True)
            result['elapsed_ms'] = elapsed_ms(started)
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


def viewer_component(manifest, relative):
    """Resolve one installer-defined live path, preserving imported filename case."""
    current = client_path(manifest['pol'])
    for name in relative.split('/'):
        if not current.is_dir():
            raise RuntimeError('Staged PlayOnline component is missing: ' + relative)
        matches = [entry for entry in current.iterdir() if entry.name.casefold() == name.casefold()]
        if len(matches) != 1:
            raise RuntimeError('Staged PlayOnline component is missing or ambiguous: ' + relative)
        current = client_path(matches[0].relative_to(client_path('.')).as_posix())
    if not current.is_file():
        raise RuntimeError('Staged PlayOnline component is not a file: ' + relative)
    imports(current)  # Reject non-PE32 or malformed images before registration.
    return current


def prepare_playonline_components(supervisor, manifest, environment, report, record):
    # The official installer registers the viewer application and regional
    # contents separately from the POL core used by xiloader. Only these known
    # live modules are selected; patchfiles and arbitrary DLLs are never scanned.
    international = manifest['region'] != 'JP'
    definitions = [
        ('core', 'viewer/com/' + ('polcoreeu.dll' if manifest['region'] == 'EU' else 'polcore.dll'), None),
        ('app', 'viewer/com/app.dll', 'pol-app'),
        ('contents', 'viewer/contents/' + ('polcontentsINT.dll' if international else 'PolContents.dll'),
         'pol-contents-int' if international else 'pol-contents')]
    components = []
    report['component_registration'] = []
    for name, relative, class_name in definitions:
        path = viewer_component(manifest, relative)
        digest = hashlib.sha256()
        with path.open('rb') as source:
            for chunk in iter(lambda: source.read(1024 * 1024), b''):
                digest.update(chunk)
        entry = {'component': name, 'dll': path.name, 'sha256': digest.hexdigest(), 'steps': []}
        report['component_registration'].append(entry)
        components.append((path, class_name, entry))
    record()
    step_number = 0
    for path, class_name, entry in components:
        windows = windows_path(path.relative_to(client_path('.')).as_posix())
        verify = ('class', [class_name, windows]) if class_name else ('com', [manifest['region'], 'pol', windows])
        for operation, arguments in [('register', [windows]), verify]:
            supervisor.stopped()
            step_number += 1
            label = {'core': 'core', 'app': 'application', 'contents': 'contents'}[entry['component']]
            action = 'registering' if operation == 'register' else 'checking'
            supervisor.status('registering_playonline_components',
                              message='Preparing PlayOnline components (%d/6): %s %s' %
                                      (step_number, action, label))
            receipt_path = SESSION / 'client-step.json'
            receipt_path.unlink(missing_ok=True)
            step = {'operation': operation, 'exit_code': None}
            entry['steps'].append(step)
            started = time.monotonic()
            process = writer = None
            try:
                process = supervisor.spawn(supervisor.wine_command(r'P:\client-init.exe', operation, *arguments),
                    'playonline-' + entry['component'] + '-' + operation + '.log', env=environment)
                writer = supervisor.logs[-1]
                supervisor.wait(process, 90, 'PlayOnline ' + entry['component'] + ' ' + operation, accepted=(0, 1))
            finally:
                try:
                    receipt = json.loads(receipt_path.read_text()) if receipt_path.stat().st_size <= 32 * 1024 else {}
                except (OSError, ValueError):
                    receipt = {}
                if not isinstance(receipt, dict):
                    receipt = {}
                # Do not copy the helper's free-form strings into private POL logs.
                safe = {key: receipt[key] for key in ('format', 'bits', 'hresult', 'win32_error')
                        if type(receipt.get(key)) is int and 0 <= receipt[key] <= 0xffffffff}
                if receipt.get('operation') in ('register', 'com', 'class'):
                    safe['operation'] = receipt['operation']
                if type(receipt.get('ok')) is bool:
                    safe['ok'] = receipt['ok']
                step.update(exit_code=process.poll() if process is not None else None, result=safe)
                if writer is not None:
                    step['startup_diagnostics'] = diagnostics(writer, drain=True)
                step['elapsed_ms'] = elapsed_ms(started)
                record()
            valid = (safe.get('format') == 1 and safe.get('bits') == 32 and
                     safe.get('operation') == operation and safe.get('ok') is True and
                     type(safe.get('hresult')) is int and safe['hresult'] < 0x80000000 and
                     safe.get('win32_error') == 0 and isinstance(receipt.get('loaded_path'), str) and
                     receipt['loaded_path'].casefold() == windows.casefold())
            if process.returncode != 0 or not valid:
                code = safe.get('hresult')
                detail = (' (HRESULT 0x%08X)' % code) if code is not None else ' (invalid component receipt)'
                raise RuntimeError('PlayOnline ' + entry['dll'] + ' ' + operation + ' failed' + detail +
                                   '. The staged copy is retained; export Diagnostics')


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
    preparation_started = time.monotonic()
    viewer_started = None
    manifest = json.loads((SESSION / 'client-update-manifest.json').read_text())
    pol, executable = validate_manifest(manifest)
    report = {'format': 1, 'generation': manifest['generation'], 'session_id': supervisor.req['session_id'],
              'status': 'opening', 'region': manifest['region'], 'official_repair_confirmed': False,
              'activation_performed': False, 'credentials_forwarded': False}

    def record():
        # This covers updater preparation only; prefix/display initialization
        # happens before run(). Freeze it once the viewer process starts.
        report['preparation_elapsed_ms'] = elapsed_ms(preparation_started, viewer_started)
        atomic(LOGS / 'client-update.json', report)
        supervisor.status(client_update=report)

    record()
    writer = None
    viewer = None
    try:
        # The existing 32-bit helper writes and reads back the regional install
        # paths; viewer-only registrations below stay in the staged prefix.
        result = SESSION / 'client-step.json'
        result.unlink(missing_ok=True)
        supervisor.status('registering_playonline_paths', message='Checking PlayOnline version metadata and installation paths')
        registry_started = time.monotonic()
        worker = None
        receipt = {}
        try:
            worker = supervisor.spawn(supervisor.wine_command(r'P:\client-init.exe', 'update-registry', manifest['region'],
                                      windows_path(manifest['pol']), windows_path(manifest['game'])), 'update-registry.log')
            supervisor.wait(worker, 90, 'Staged PlayOnline registration', accepted=(0, 1))
            try:
                receipt = json.loads(result.read_text()) if result.stat().st_size <= 32768 else {}
            except (OSError, ValueError):
                receipt = {}
            report['viewer_version_config'] = viewer_version_config(receipt)
            if (worker.returncode != 0 or receipt.get('operation') != 'update-registry' or
                    receipt.get('ok') is not True or receipt.get('bits') != 32):
                raise RuntimeError('Missing successful 32-bit staged PlayOnline registry receipt')
        finally:
            report['initial_registry'] = {'exit_code': worker.poll() if worker is not None else None,
                                          'elapsed_ms': elapsed_ms(registry_started)}
            record()
        validate_manifest(manifest)
        # Users operate the official viewer themselves. Never persist arbitrary
        # POL output, which could include a retail account entered in its UI.
        supervisor.private_output = True
        environment = private_environment(supervisor)
        prepare_playonline_components(supervisor, manifest, environment, report, record)
        check_dependencies(supervisor, manifest, executable, environment, report, record)
        report['status'] = 'viewer_open'; record()
        supervisor.status('playonline_update', message='In PlayOnline: Check Files → FINAL FANTASY XI → Check Files → File Repair. Exit the viewer after repair completes.')
        (SESSION / 'playonline-process.json').unlink(missing_ok=True)
        spawn_started = time.monotonic()
        viewer = supervisor.spawn(supervisor.wine_command(r'P:\playonline-run.exe', windows_path(manifest['executable'])),
                                  'playonline.log', env=environment, cwd=pol)
        viewer_started = spawn_started
        report['viewer_start_elapsed_ms'] = elapsed_ms(preparation_started, viewer_started)
        writer = supervisor.logs[-1]
        record()
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
