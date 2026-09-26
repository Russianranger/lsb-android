"""Prepared-client launch; credentials are consumed from pipes, never files/logs."""
import hashlib
import json
from pathlib import Path
import re
import sys
import time

from client_setup import client_path, validate_manifest, windows_path

FAILURES={
    'login_invalid_credentials':'The launcher reports an invalid account or password. Return to Client and re-enter your server login.',
    'login_already_active':'The launcher reports this account is already logged in. Close the other session or wait for the server to release it, then retry.',
    'login_version_mismatch':'The server rejected the xiloader version. Use the loader required by this server; export Diagnostics before changing files.',
    'login_additional_authentication':'The launcher requests additional authentication. This login form does not yet support OTP; export Diagnostics.',
    'login_invalid_reply':'The launcher received an invalid server reply. Check that the server and xiloader versions are compatible; export Diagnostics.',
    'login_server_error':'The server returned a login error. Check the Termux server log for its reason; export Diagnostics.',
    'login_rejected':'The launcher reported a login failure. Check your server account, existing sessions and loader/server version, then retry from Client.',
    'connection_failed':'The launcher could not connect. Start your Termux server and check the server address, then retry from Client.',
    'connection_timeout':'The server did not reply to the launcher in time. Check the Termux server log and retry from Client.',
    'authentication_handshake_failed':'The authentication connection failed. Check server and loader compatibility; export Diagnostics.',
    'listen_failed':'The launcher could not open its local listener. Stop any other loader session and retry.',
    'polcore_initialization_failed':'Login progressed, but the loader could not initialize PlayOnline. Export Diagnostics; your imported data is retained.',
    'ffxi_initialization_failed':'Login progressed, but the loader could not initialize FFXI. Export Diagnostics; your imported data is retained.',
    'polcore_patch_failed':'The loader could not locate a required PlayOnline function. Export Diagnostics to check loader/client compatibility.',
    'com_initialization_failed':'The loader could not initialize Windows COM. Export Diagnostics.',
    'loader_hook_failed':'The loader could not install its required network hooks. Export Diagnostics.',
    'windows_exception':'The launcher reported a Windows exception. Export Diagnostics.',
    'dll_import_error':'The launcher reported a DLL import error. Export Diagnostics.'}


def progress(events, elapsed, process=None):
    for event,message in FAILURES.items():
        if event in events:return 'launch_failed',message,event
    observation=(process or {}).get('observation',{})
    if observation.get('ffxi_window_seen') is True:
        return 'game_window_observed','An FFXI window opened. Character selection and world entry still need checking.',''
    if 'FFXiMain.dll' in observation.get('modules_seen',[]):
        return 'game_module_loaded','FFXI loaded its main module. Waiting for the game window.',''
    if 'game_launch_message_seen' in events:
        return 'game_start_requested','The launcher requested game startup. Waiting for the game display.',''
    if 'login_message_seen' in events:
        return 'login_message_received','The launcher reports login accepted. Waiting for the game display.',''
    if elapsed>=60:
        return 'waiting_for_login','Still waiting for a login result. Stop and export Diagnostics if no game appears.',''
    return 'waiting_for_login','Launcher started. Waiting for a login result.',''


def exit_problem(process, events, diagnostics=None):
    if process.get('phase')=='version_configuration_failed':
        return ('version_configuration_failed','The missing FFXI version registry value could not be restored (Windows error '+str(process.get('win32_error','unavailable'))+'). Your client files were kept. Export Diagnostics.')
    if process.get('phase')=='configuration_failed':
        if process.get('win32_error')==1168:
            return ('display_configuration_failed','No saved original display settings are available. Choose Windowed 1280×720 or Keep current display settings and retry.')
        return ('display_configuration_failed','The selected FFXI display setting could not be applied (Windows error '+str(process.get('win32_error','unavailable'))+'). Export Diagnostics.')
    if (process.get('phase')=='exited' and process.get('child_exit')==0 and
        'login_message_seen' in events and not process.get('observation',{}).get('ffxi_window_seen')):
        pid=process.get('observation',{}).get('child_pid')
        rows=[row for row in (diagnostics or {}).get('records',[])
              if pid is not None and row.get('source')=='startup' and row.get('process_id')==pid]
        # FFXI can return a failed inner HRESULT and then mask it with S_OK
        # from GameStart. Use only the latest call, not an earlier retry.
        boundary=next((i for i in range(len(rows)-1,-1,-1) if rows[i]['event']=='game_start_enter'),0)
        rows=rows[boundary:]
        inner=next((row for row in reversed(rows) if row['event']=='game_main_return'),None)
        if inner is not None and inner['code'] & 0x80000000:
            return ('game_main_failed','FFXI GameMain failed with 0x%08X after %d ms before a game window was observed. Export Diagnostics.'%(inner['code'],inner['detail']))
        for row in reversed(rows):
            if row['event']=='game_start_return':
                return ('game_start_returned_without_window','FFXI GameStart returned 0x%08X after %d ms before a game window was observed. Export Diagnostics.'%(row['code'],row['detail']))
        return ('closed_before_game_window','The loader closed after login before an FFXI window was observed. Export Diagnostics to identify the remaining startup failure.')
    return None


def credentials(stream):
    data = stream.read(1025)
    try:
        lines = data.decode('ascii').split('\n')
        if len(lines) != 5 or lines[0] != 'LSBLOGIN1' or lines[-1] != '': raise ValueError()
        host, user, password = lines[1:4]
        if not re.fullmatch(r'[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?', host): raise ValueError()
        if any(not 1 <= len(v) <= 128 or any(ord(c) < 32 or ord(c) > 126 for c in v) for v in (user,password)): raise ValueError()
        return data, host
    except (ValueError, UnicodeError):
        raise ValueError('Enter a hostname and nonempty ASCII account/password (up to 128 characters); no line breaks') from None


def cli_flags(path):
    if path.stat().st_size > 64*1024*1024: raise ValueError('Loader exceeds inspection limit')
    data=path.read_bytes()
    def pick(options):
        for flag in options:
            if flag.encode('ascii')+b'\0' in data: return flag
        raise ValueError('Imported loader does not expose the expected xiloader CLI; export Diagnostics')
    return [pick(['--server']),pick(['--username','--user']),pick(['--password','--pass']),pick(['--lang'])]


def record(s, report):
    from supervisor import atomic
    atomic(Path('/logs/client-launch.json'),report)
    s.status(client_launch=report)


def validate_check(report, expected, exit_code):
    # A success-looking receipt never overrides an abnormal process exit.
    if exit_code != 0:
        if exit_code==1 and valid_check_rows(report,expected):
            failed=[row for row in report['dependencies'] if row['ok'] is False]
            if failed:
                detail=', '.join(row['name']+' (Windows error '+str(row['win32_error'])+')' for row in failed[:3])
                raise RuntimeError('Loader dependency failed: '+detail+'. xiloader has not started; export Diagnostics.')
        raise RuntimeError('Loader dependency checker exited with code '+str(exit_code)+'. xiloader has not started; export Diagnostics.')
    rows=report.get('dependencies',[])
    if (not expected or report.get('format')!=1 or report.get('bits')!=32 or
        report.get('check_policy')!='load_only' or report.get('ok') is not True or
        not isinstance(rows,list) or len(rows)!=len(expected) or
        any(not isinstance(row,dict) or row.get('name')!=name or row.get('ok') is not True or
            row.get('win32_error')!=0 or not row.get('loaded_path') for row,name in zip(rows,expected))):
        raise RuntimeError('Loader dependencies did not pass. View launch results for missing DLLs.')


def valid_check_rows(report,expected):
    """Only accept the exact, complete check receipt for failure classification."""
    if not isinstance(report,dict):return False
    rows=report.get('dependencies')
    return (bool(expected) and report.get('format')==1 and report.get('bits')==32 and
            report.get('check_policy')=='load_only' and isinstance(rows,list) and len(rows)==len(expected) and
            all(isinstance(row,dict) and row.get('name')==name and
                re.fullmatch(r'[A-Za-z0-9_.-]{1,128}',name) and type(row.get('ok')) is bool and
                type(row.get('win32_error')) is int and 0<=row['win32_error']<=0xffffffff
                for row,name in zip(rows,expected)))


def retry_network_initialization(report,expected,exit_code):
    if exit_code!=1 or not valid_check_rows(report,expected) or report.get('ok') is not False:return False
    failed=[row for row in report['dependencies'] if row['ok'] is False]
    return (len(failed)==1 and failed[0]['name'].lower()=='ws2_32.dll' and failed[0]['win32_error']==1114 and
            all(row['ok'] is False or (row['win32_error']==0 and row.get('loaded_path')) for row in report['dependencies']))


def check_dependencies(s,report,loader,dependencies):
    result=Path('/session/loader-check.json')
    report['check_attempts']=[]
    for attempt in range(2):
        s.stopped()
        result.unlink(missing_ok=True)
        # This process receives no login payload. Retain symbol/loader failures
        # here; arbitrary game-process output still uses the private filter.
        env=dict(s.env,WINEDEBUG='-all,+timestamp,+pid,err+all,warn+module,trace+loaddll',BOX64_DLSYM_ERROR='1')
        name='loader-check.log' if attempt==0 else 'loader-check-retry.log'
        p=s.spawn(s.wine_command(r'P:\client-launch.exe','check',windows_path(loader),*dependencies),name,env=env)
        try:
            s.wait(p,90,'Loader dependency check (xiloader has not started)',accepted=(0,1))
        finally:
            checked=json.loads(result.read_text()) if result.is_file() else {}
            code=p.poll();report['dependencies']=checked;report['check_exit']=code
            report['check_attempts'].append({'exit':code,'dependencies':checked});record(s,report)
        if attempt==0 and retry_network_initialization(checked,dependencies,code):
            s.status('retrying_network_initialization',message='Retrying Windows networking initialization before xiloader starts')
            s.stopped();continue
        validate_check(checked,dependencies,code)
        return


def data_inventory(game):
    """Small metadata-only inventory; encrypted patch.ver need not contain text."""
    result={}
    for name in ('patch.ver','FTABLE.DAT','VTABLE.DAT'):
        try:
            matches=[p for p in game.iterdir() if p.name.lower()==name.lower()]
            if not matches:result[name]={'state':'missing'};continue
            if len(matches)!=1:result[name]={'state':'ambiguous_case'};continue
            path=matches[0]
            if path.is_symlink() or not path.is_file():result[name]={'state':'not_regular'};continue
            size=path.stat().st_size
            with path.open('rb') as f:f.read(1)
            result[name]={'state':'readable','bytes':size}
        except OSError as error:result[name]={'state':'unreadable','errno':error.errno}
    return result


def check(s):
    manifest=json.loads(Path('/session/client-manifest.json').read_text())
    imports=validate_manifest(manifest)
    if not manifest['loader']: raise ValueError('Prepared client has no xiloader; import a loader and prepare that installation')
    loader=client_path(manifest['loader'])
    report={'format':1,'session_id':s.req['session_id'],'generation':manifest['generation'],
            'status':'checking','loader':manifest['loader'],'loader_sha256':manifest['key_files'][manifest['loader']],
            'region':manifest['region'],'cli_flags':[],'authentication_verified':False,'world_entry_verified':False,
            'client_data':data_inventory(client_path(manifest['game']))}
    record(s,report)
    flags=cli_flags(loader);report['cli_flags']=flags
    record(s,report);s.status('checking_loader_dependencies')
    dependencies=imports[manifest['loader']]
    if not dependencies: raise ValueError('Loader has no normal imports; unsupported loader image')
    check_dependencies(s,report,manifest['loader'],dependencies)
    report['status']='ready';record(s,report)
    return manifest,report,flags


def run(s, display):
    payload=None;writer=None;traced_loader=None
    try:
        # Android closes the writer immediately; bounded input cannot enter session files.
        payload,host=credentials(sys.stdin.buffer)
        manifest,report,flags=check(s)
        loader=manifest['loader']
        if s.req.get('startup_trace',False):
            from startup_image import prepare
            traced_loader,trace=prepare(client_path(loader),manifest['key_files'][loader])
            loader=traced_loader.relative_to('/client').as_posix()
            trace['observer_sha256']=hashlib.sha256(Path('/probe/startup-trace.dll').read_bytes()).hexdigest()
            report['startup_trace']=trace
        profile=s.req.get('display_profile','preserve')
        report.update(server=host,status='starting',display_profile=profile);record(s,report)
        s.stopped();s.private_output=True
        result=Path('/session/loader-process.json');result.unlink(missing_ok=True)
        # All output stays on the existing private pipe. DXVK must not create its
        # own unfiltered file; error/COM/SEH evidence is normalized in memory.
        env=dict(s.env,WINEDEBUG='-all,+timestamp,+pid,err+all,warn+module,warn+seh,trace+seh,trace+loaddll,fixme+ole',
                 BOX64_LOG='0',BOX64_NOBANNER='1',DXVK_LOG_LEVEL='info',DXVK_LOG_PATH='none')
        env['WINEDLLOVERRIDES']+=';winedbg='
        if getattr(s,'engine','box64')=='fex':
            env={k:v for k,v in env.items() if not k.startswith('BOX64_')}
            from fex_runtime import check as check_fex
            check_fex(s,env=env,launch=True)
        # No credentials in argv/environment. Native helper supplies the Windows CLI.
        p=s.spawn(s.wine_command(r'P:\client-launch.exe','launch',windows_path(loader),*flags[:3],str({'JP':0,'US':1,'EU':2}[manifest['region']]),profile,windows_path(manifest['game']),'1' if s.req.get('borderless',True) else '0'),
                  'loader-events.log',env=env,pipe_input=True)
        writer=s.logs[-1]
        try:p.stdin.write(payload);p.stdin.close()
        except BrokenPipeError:raise RuntimeError('Loader bridge closed before accepting login; export Diagnostics') from None
        payload=None;s.status('starting_loader');started=time.monotonic();deadline=started+90;last=None;last_progress=None
        def update_progress():
            nonlocal last_progress
            events=writer.events.snapshot();state=progress(events,time.monotonic()-started,report.get('process'))
            phase,message,failure=state
            diagnostics=writer.events.diagnostics.snapshot()
            if (state,events,diagnostics)!=last_progress:
                report.update(events=events,launch_stage=phase,message=message,startup_diagnostics=diagnostics)
                if failure:report.update(status='failed',failure_reason=failure,termination_reason='launcher_reported_failure')
                record(s,report);s.status(phase,message=message);last_progress=(state,events,diagnostics)
            if failure:raise RuntimeError(message)
        while p.poll() is None:
            s.stopped()
            if display.poll() is not None: raise RuntimeError('Display closed during client launch')
            if result.is_file():
                current=json.loads(result.read_text())
                if current!=last:
                    report.update(process=current,status='running' if current.get('phase')=='running' else 'closing')
                    record(s,report);last=current
            elif time.monotonic()>deadline: raise RuntimeError('Windows loader did not start within 90 seconds; export Diagnostics')
            if last is not None:update_progress()
            time.sleep(.3)
        # Drain final output before accepting an exit, including rejection+exit 0.
        if result.is_file():report['process']=json.loads(result.read_text())
        report['bridge_exit']=p.returncode
        writer.thread.join(2);update_progress();process=report.get('process',{})
        problem=exit_problem(process,writer.events.snapshot(),writer.events.diagnostics.snapshot())
        if problem:
            reason,message=problem
            report.update(status='failed',failure_reason=reason,termination_reason='loader_exited',launch_stage=reason,message=message)
            record(s,report);raise RuntimeError(message)
        if p.returncode!=0 or process.get('phase')!='exited' or process.get('child_exit')!=0:
            report['status']='failed';record(s,report)
            raise RuntimeError('Client launch failed (Windows exit '+str(process.get('child_exit','unavailable'))+', error '+str(process.get('win32_error','unavailable'))+'). Export Diagnostics.')
        report['status']='exited';record(s,report);s.status('completed',client_exit=0)
    finally:
        payload=None
        if traced_loader is not None:traced_loader.unlink(missing_ok=True)
        # Stop can arrive before the first process receipt/progress poll. Keep a
        # bounded snapshot on every path after spawning, including cancellation
        # and a broken input pipe, without retaining raw stream data.
        if writer is not None:
            report['startup_diagnostics']=writer.events.diagnostics.snapshot()
            record(s,report)


def finish_report(s,phase):
    """Do not turn a stopped/failed attempt into a stale ready/running receipt."""
    report=s.state.get('client_launch')
    if report and phase in ('error','stopped'):
        report['status']='stopped' if phase=='stopped' else 'failed'
        record(s,report)
