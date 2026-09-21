"""Real Wine/PRoot launch supervision with synthetic PE32 loader; no server/game."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

sys.path.insert(0,'/opt/lsb')
from supervisor import Supervisor

SESSION=Path('/session');LOGS=Path('/logs');CLIENT=Path('/client')
PAYLOAD=b'LSBLOGIN1\n127.0.0.1\nLSB_private_account\nLSB "quoted" & % ! \\ tail\\\n'
REJECTIONS={'reject-credentials':('1','login_invalid_credentials'),
            'reject-active':('2','login_already_active'),
            'reject-version':('3','login_version_mismatch'),
            'connect-failure':('4','connection_failed'),
            'reject-zero-exit':('0','login_rejected')}
POST_LOGIN={'post-login-pol':('p','polcore_initialization_failed'),
            'post-login-ffxi':('f','ffxi_initialization_failed'),
            'post-login-early-exit':('0','closed_before_game_window')}


def main():
    (SESSION/'stop').unlink(missing_ok=True)
    setup=Supervisor({'format':1,'session_id':str(uuid.uuid4()),'renderer':'software','audio':False,'action':'check-launcher'})
    try:
        setup.wait(setup.spawn(['/usr/local/bin/box64','/opt/wine/bin/wine',r'Z:\fixtures\display-config.exe'],'display-config.log'),90,'Synthetic display configuration checks')
    finally:setup.stop()
    manifest=json.loads((SESSION/'client-manifest.json').read_text())
    manifest['key_files'].pop(manifest['loader'])
    manifest['loader']='FINAL FANTASY XI/boot loader/xiloader.exe'
    loader=CLIENT/manifest['loader'];loader.parent.mkdir(exist_ok=True)
    for case in ['launch','relaunch','exit-failure','stop','missing-dependency','check-only',*REJECTIONS,*POST_LOGIN]:
        for path in [SESSION/'stop',SESSION/'status.json',SESSION/'loader-process.json',SESSION/'loader-check.json',SESSION/'login-fixture.json',CLIENT/'launch-fail',CLIENT/'launch-hang',CLIENT/'login-reject',CLIENT/'post-login']:
            path.unlink(missing_ok=True)
        shutil.copyfile('/fixtures/login-missing.exe' if case=='missing-dependency' else '/fixtures/login-stub.exe',loader)
        manifest['key_files'][manifest['loader']]=hashlib.sha256(loader.read_bytes()).hexdigest()
        (SESSION/'client-manifest.json').write_text(json.dumps(manifest))
        request={'format':1,'session_id':str(uuid.uuid4()),'renderer':'software','audio':False,'action':'check-launcher' if case=='check-only' else 'launch'}
        (SESSION/'request.json').write_text(json.dumps(request))
        if case=='exit-failure':(CLIENT/'launch-fail').touch()
        if case=='stop':(CLIENT/'launch-hang').touch()
        if case in REJECTIONS:(CLIENT/'login-reject').write_text(REJECTIONS[case][0])
        if case in POST_LOGIN:(CLIENT/'post-login').write_text(POST_LOGIN[case][0])
        p=subprocess.Popen(['python3','/opt/lsb/supervisor.py'],stdin=subprocess.PIPE)
        p.stdin.write(PAYLOAD if case!='check-only' else b'');p.stdin.close()
        if case=='stop':
            deadline=time.monotonic()+180
            while p.poll() is None and time.monotonic()<deadline:
                try:
                    if json.loads((SESSION/'loader-process.json').read_text()).get('phase')=='running':break
                except (OSError,ValueError):pass
                time.sleep(.2)
            assert p.poll() is None and (SESSION/'loader-process.json').exists(),'loader did not start before Stop'
            (SESSION/'stop').touch()
        p.wait(timeout=90 if case in REJECTIONS else 300)
        state=json.loads((SESSION/'status.json').read_text());report=json.loads((LOGS/'client-launch.json').read_text())
        assert report['session_id']==request['session_id'] and report['generation']==manifest['generation'],report
        assert report['authentication_verified'] is False and report['world_entry_verified'] is False
        if case in ('launch','relaunch'):
            assert p.returncode==0 and state['phase']=='completed' and report['status']=='exited',state
            assert report['process']['child_exit']==0
            observed=report['process']['observation']
            assert observed['ffxi_window_seen'] and 'FFXiMain.dll' in observed['modules_seen'],observed
            assert observed['samples']>0 and observed['module_error']==observed['window_error']==0,observed
            config=report['process']['display_config'];assert config['ok'] and config['policy']=='missing_only',config
            assert config['values']['0001']['value']==(1280 if case=='launch' else 1024),config
            assert all(v['added']==(case=='launch') for v in config['values'].values()),config
        elif case=='exit-failure':
            assert p.returncode!=0 and state['phase']=='error' and report['process']['child_exit']==0xc0000135,report
        elif case=='stop':
            assert state['phase']=='stopped' and report['status']=='stopped',report
        elif case=='missing-dependency':
            assert p.returncode!=0 and state['phase']=='error' and report['status']=='failed',report
            missing=[d for d in report['dependencies']['dependencies'] if not d['ok']]
            assert any(d['name'].lower()=='lsb-missing-fixture.dll' and d['win32_error']==126 for d in missing),missing
            assert not (SESSION/'login-fixture.json').exists(),'loader executed before dependency check passed'
        elif case in REJECTIONS:
            assert p.returncode!=0 and state['phase']=='error' and report['status']=='failed',report
            assert report['failure_reason']==REJECTIONS[case][1],report
            assert report['termination_reason']=='launcher_reported_failure',report
            assert state['error']==report['message'] and report['launch_stage']=='launch_failed',report
            assert report['events']==[REJECTIONS[case][1]],report
            if case=='reject-zero-exit':assert report['process']['child_exit']==0,report
        elif case in POST_LOGIN:
            assert p.returncode!=0 and state['phase']=='error' and report['status']=='failed',report
            assert report['failure_reason']==POST_LOGIN[case][1],report
            assert 'login_message_seen' in report['events'],report
            assert report['process']['child_exit']==0,report
            assert not report['process']['observation']['ffxi_window_seen'],report
        else:
            assert p.returncode==0 and report['status']=='ready' and not (SESSION/'login-fixture.json').exists(),report
        if case in ('launch','relaunch','exit-failure'):
            assert json.loads((SESSION/'login-fixture.json').read_text())['arguments_and_cwd_match']
        assert not (SESSION/'display.sock').exists()
        for path in [*LOGS.glob('*'),*SESSION.glob('*.json')]:
            if path.is_file():
                data=path.read_bytes()
                assert b'LSB_private_account' not in data and b'quoted' not in data and 'quoted'.encode('utf-16le') not in data,path
        (LOGS/('launch-'+case+'.json')).write_text(json.dumps(report,indent=2))
        print('PASS: client launch',case,'literal argv/cwd, private diagnostics, receipt and shutdown',flush=True)


if __name__=='__main__':main()
