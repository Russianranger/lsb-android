"""Prepared-client launch; credentials are consumed from pipes, never files/logs."""
import json
from pathlib import Path
import re
import sys
import time

from client_setup import client_path, validate_manifest, windows_path


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


def check(s):
    manifest=json.loads(Path('/session/client-manifest.json').read_text())
    imports=validate_manifest(manifest)
    if not manifest['loader']: raise ValueError('Prepared client has no xiloader; import a loader and prepare that installation')
    loader=client_path(manifest['loader']);flags=cli_flags(loader)
    report={'format':1,'session_id':s.req['session_id'],'generation':manifest['generation'],
            'status':'checking','loader':manifest['loader'],'loader_sha256':manifest['key_files'][manifest['loader']],
            'region':manifest['region'],'cli_flags':flags,'authentication_verified':False,'world_entry_verified':False}
    record(s,report);s.status('checking_loader_dependencies')
    result=Path('/session/loader-check.json');result.unlink(missing_ok=True)
    dependencies=imports[manifest['loader']]
    if not dependencies: raise ValueError('Loader has no normal imports; unsupported loader image')
    p=s.spawn(['/usr/local/bin/box64','/opt/wine/bin/wine',r'P:\client-launch.exe','check',windows_path(manifest['loader']),*dependencies],'loader-check.log')
    try:
        s.wait(p,90,'Loader dependency check')
    finally:
        if result.is_file(): report['dependencies']=json.loads(result.read_text())
        report['check_exit']=p.poll();record(s,report)
    if report.get('dependencies',{}).get('ok') is not True or report['dependencies'].get('bits')!=32:
        raise RuntimeError('Loader dependencies did not pass. View launch results for missing DLLs.')
    report['status']='ready';record(s,report)
    return manifest,report,flags


def run(s, display):
    payload=None
    try:
        # Android closes the writer immediately; bounded input cannot enter session files.
        payload,host=credentials(sys.stdin.buffer)
        manifest,report,flags=check(s)
        report.update(server=host,status='starting');record(s,report)
        s.stopped();s.private_output=True
        result=Path('/session/loader-process.json');result.unlink(missing_ok=True)
        env=dict(s.env,WINEDEBUG='-all',BOX64_LOG='0',BOX64_NOBANNER='1',DXVK_LOG_LEVEL='none')
        env['WINEDLLOVERRIDES']+=';winedbg='
        # No credentials in argv/environment. Native helper supplies the Windows CLI.
        p=s.spawn(['/usr/local/bin/box64','/opt/wine/bin/wine',r'P:\client-launch.exe','launch',windows_path(manifest['loader']),*flags[:3],str({'JP':0,'US':1,'EU':2}[manifest['region']])],
                  'loader-events.log',env=env,pipe_input=True)
        try:p.stdin.write(payload);p.stdin.close()
        except BrokenPipeError:raise RuntimeError('Loader bridge closed before accepting login; export Diagnostics') from None
        payload=None;s.status('starting_loader');deadline=time.monotonic()+90;last=None
        while p.poll() is None:
            s.stopped()
            if display.poll() is not None: raise RuntimeError('Display closed during client launch')
            if result.is_file():
                current=json.loads(result.read_text())
                if current!=last:
                    report.update(process=current,status='running' if current.get('phase')=='running' else 'closing')
                    record(s,report);s.status('client_running');last=current
            elif time.monotonic()>deadline: raise RuntimeError('Windows loader did not start within 90 seconds; export Diagnostics')
            time.sleep(.3)
        if result.is_file():report['process']=json.loads(result.read_text())
        report['bridge_exit']=p.returncode;process=report.get('process',{})
        if p.returncode!=0 or process.get('phase')!='exited' or process.get('child_exit')!=0:
            report['status']='failed';record(s,report)
            raise RuntimeError('Client launch failed (Windows exit '+str(process.get('child_exit','unavailable'))+', error '+str(process.get('win32_error','unavailable'))+'). Export Diagnostics.')
        report['status']='exited';record(s,report);s.status('completed',client_exit=0)
    finally:
        payload=None


def finish_report(s,phase):
    """Do not turn a stopped/failed attempt into a stale ready/running receipt."""
    report=s.state.get('client_launch')
    if report and phase in ('error','stopped'):
        report['status']='stopped' if phase=='stopped' else 'failed'
        record(s,report)
