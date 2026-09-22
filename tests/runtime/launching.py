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
STARTUP={'trace-ok':('s',0),'trace-fail':('f',0x80004005),'trace-sfalse':('n',1),
         'trace-inner':('m',0x8007000e),'trace-data':('d',0),'trace-exception':('x',None),'trace-stop':('h',None),
         'trace-off':('f',0x80004005)}


def main():
    (SESSION/'stop').unlink(missing_ok=True)
    setup=Supervisor({'format':1,'session_id':str(uuid.uuid4()),'renderer':'software','audio':False,'action':'check-launcher'})
    try:
        setup.wait(setup.spawn(['/usr/local/bin/box64','/opt/wine/bin/wine',r'Z:\fixtures\display-config.exe'],'display-config.log'),90,'Synthetic display configuration checks')
        setup.wait(setup.spawn(['/usr/local/bin/box64','/opt/wine/bin/wine',r'Z:\fixtures\version-registry.exe',r'Z:\session\version-fixture.bin'],'version-registry.log'),90,'Synthetic version registry checks')
    finally:setup.stop()
    manifest=json.loads((SESSION/'client-manifest.json').read_text())
    manifest['key_files'].pop(manifest['loader'])
    manifest['loader']='FINAL FANTASY XI/boot loader/xiloader.exe'
    loader=CLIENT/manifest['loader'];loader.parent.mkdir(exist_ok=True)
    for case in ['launch','relaunch','windowed-existing','restore-display','window-idle','window-stop','window-crash','version-repair','version-preserved','startup-diagnostics','startup-exception','exit-failure','stop','missing-dependency','check-only',*REJECTIONS,*POST_LOGIN,*STARTUP]:
        for path in [SESSION/'stop',SESSION/'status.json',SESSION/'loader-process.json',SESSION/'loader-check.json',SESSION/'login-fixture.json',CLIENT/'launch-fail',CLIENT/'launch-hang',CLIENT/'login-reject',CLIENT/'post-login']:
            path.unlink(missing_ok=True)
        for path in [CLIENT/'startup-result',SESSION/'startup-fixture.json']:path.unlink(missing_ok=True)
        for path in [CLIENT/'window-hold',CLIENT/'window-crash']:path.unlink(missing_ok=True)
        shutil.copyfile('/fixtures/login-missing.exe' if case=='missing-dependency' else '/fixtures/login-stub.exe',loader)
        manifest['key_files'][manifest['loader']]=hashlib.sha256(loader.read_bytes()).hexdigest()
        (SESSION/'client-manifest.json').write_text(json.dumps(manifest))
        request={'format':1,'session_id':str(uuid.uuid4()),'renderer':'software','audio':False,'action':'check-launcher' if case=='check-only' else 'launch'}
        if case in ('launch','windowed-existing'):request['display_profile']='windowed720'
        elif case=='restore-display':request['display_profile']='restore'
        if case in STARTUP:
            request['startup_trace']=case!='trace-off'
            (CLIENT/'startup-result').write_text(STARTUP[case][0])
            if case=='trace-data':(CLIENT/'FINAL FANTASY XI'/'patch.ver').write_bytes(b'hello')
        if case=='version-repair':shutil.copyfile(SESSION/'version-fixture.bin',CLIENT/'FINAL FANTASY XI'/'patch.ver')
        (SESSION/'request.json').write_text(json.dumps(request))
        if case=='exit-failure':(CLIENT/'launch-fail').touch()
        if case=='stop':(CLIENT/'launch-hang').touch()
        if case in REJECTIONS:(CLIENT/'login-reject').write_text(REJECTIONS[case][0])
        if case in POST_LOGIN:(CLIENT/'post-login').write_text(POST_LOGIN[case][0])
        if case=='startup-diagnostics':(CLIENT/'post-login').write_text('d')
        if case=='startup-exception':(CLIENT/'post-login').write_text('e')
        if case.startswith('window-'):
            (CLIENT/'window-hold').touch()
            if case=='window-crash':(CLIENT/'window-crash').touch()
        p=subprocess.Popen(['python3','/opt/lsb/supervisor.py'],stdin=subprocess.PIPE)
        p.stdin.write(PAYLOAD if case!='check-only' else b'');p.stdin.close()
        frozen=None
        if case.startswith('window-'):
            deadline=time.monotonic()+180
            while p.poll() is None and time.monotonic()<deadline:
                try:
                    current=json.loads((SESSION/'loader-process.json').read_text())
                    observation=current.get('observation',{})
                    published=json.loads((LOGS/'client-launch.json').read_text())
                    # Stop tests this state after the supervisor has consumed it.
                    # Native receipt publication can precede its 300 ms poll;
                    # stopping in that gap tests the separate early-Stop path.
                    acknowledged=published.get('session_id')==request['session_id'] and published.get('process')==current
                    if current.get('phase')=='running' and observation.get('complete') and acknowledged:
                        assert observation['policy']=='startup_only' and observation['ffxi_window_seen'],current
                        assert 'FFXiMain.dll' in observation['modules_seen'],current
                        frozen=(SESSION/'loader-process.json').read_bytes()
                        break
                except (OSError,ValueError):pass
                time.sleep(.1)
            assert frozen is not None and p.poll() is None,'startup observation did not complete'
            if case=='window-idle':
                # More than two former three-second intervals. Compare the full
                # receipt, not just cumulative booleans, to catch continued work.
                time.sleep(7)
                assert p.poll() is None,'healthy child was terminated after observing its window'
                assert (SESSION/'loader-process.json').read_bytes()==frozen,'launcher kept scanning during gameplay'
            if case=='window-stop':(SESSION/'stop').touch()
            else:(CLIENT/'window-hold').unlink()
        if case in ('stop','trace-stop'):
            deadline=time.monotonic()+180
            while p.poll() is None and time.monotonic()<deadline:
                try:
                    if case=='trace-stop':
                        current=json.loads((LOGS/'client-launch.json').read_text())
                        rows=current.get('startup_diagnostics',{}).get('records',[])
                        if current.get('session_id')==request['session_id'] and any(r.get('event')=='game_start_enter' for r in rows):break
                    elif json.loads((SESSION/'loader-process.json').read_text()).get('phase')=='running':break
                except (OSError,ValueError):pass
                time.sleep(.2)
            assert p.poll() is None and (SESSION/'loader-process.json').exists(),'loader did not start before Stop'
            (SESSION/'stop').touch()
        p.wait(timeout=90 if case in REJECTIONS else 300)
        state=json.loads((SESSION/'status.json').read_text());report=json.loads((LOGS/'client-launch.json').read_text())
        assert report['session_id']==request['session_id'] and report['generation']==manifest['generation'],report
        assert report['authentication_verified'] is False and report['world_entry_verified'] is False
        if case in ('launch','relaunch','windowed-existing','restore-display'):
            assert p.returncode==0 and state['phase']=='completed' and report['status']=='exited',state
            assert report['process']['child_exit']==0
            observed=report['process']['observation']
            assert observed['ffxi_window_seen'] and 'FFXiMain.dll' in observed['modules_seen'],observed
            # The final poll can race normal DLL teardown: Module32First then
            # returns ERROR_NO_MORE_FILES (18) even though earlier samples saw
            # FFXiMain and the game window. Keep that code in the receipt.
            assert observed['samples']>0 and observed['module_error'] in (0,18) and observed['window_error']==0,observed
            config=report['process']['display_config'];assert config['ok'] and config['policy']==request.get('display_profile','preserve'),config
            assert config['rollback_error']==0,config
            expected={'launch':1280,'relaunch':1024,'windowed-existing':1280,'restore-display':640}
            assert config['values']['0001']['value']==expected[case],config
            assert config['values']['0034']['value']==(0 if case=='restore-display' else 1),config
            if case=='launch':
                assert config['values']['0001']['previous_value']==640 and config['values']['0034']['previous_value']==0,config
                assert config['backup_ready'] and all(v['changed'] for v in config['values'].values()),config
        elif case.startswith('window-'):
            observed=report['process']['observation'];before=json.loads(frozen)['observation']
            assert {k:v for k,v in observed.items() if k!='elapsed_ms'}=={k:v for k,v in before.items() if k!='elapsed_ms'},'startup observation changed after completion'
            assert observed['last_sample_elapsed_ms']<=report['process']['observation']['elapsed_ms']
            if case=='window-stop':
                assert state['phase']=='stopped' and report['status']=='stopped',report
            elif case=='window-crash':
                assert p.returncode!=0 and state['phase']=='error' and report['process']['child_exit']==0xc0000094,report
                assert any(r.get('code')==0xc0000094 and r['event']=='exception_raised' and r.get('process_id')==observed['child_pid'] for r in report['startup_diagnostics']['records']),report
            else:
                assert p.returncode==0 and state['phase']=='completed' and report['process']['child_exit']==0,report
                assert observed['elapsed_ms']-observed['last_sample_elapsed_ms']>=7000,observed
            print('PASS: completed startup observer stays idle; supervision '+case,flush=True)
        elif case in ('version-repair','version-preserved'):
            assert p.returncode==0 and state['phase']=='completed' and report['process']['child_exit']==0,report
            result=report['process']['version_config']
            assert result['state']==('restored_missing' if case=='version-repair' else 'existing_preserved'),result
            assert not result['win32_error'] and not result['rollback_error'],result
            if case=='version-repair':assert result['version']=='20260921_1',result
            assert (CLIENT/'FINAL FANTASY XI'/'patch.ver').read_bytes()==(SESSION/'version-fixture.bin').read_bytes()
        elif case=='exit-failure':
            assert p.returncode!=0 and state['phase']=='error' and report['process']['child_exit']==0xc0000135,report
        elif case=='startup-diagnostics':
            assert p.returncode==0 and report['status']=='exited',report
            records=report['startup_diagnostics']['records']
            assert any(r.get('category')=='dll_load' for r in records),records
            assert any(r.get('module')=='FFXiMain.dll' and r.get('process_id')==report['process']['observation']['child_pid'] for r in records),records
            assert any(r['source']=='dxvk' and r.get('category')=='d3d9' for r in records),records
        elif case=='startup-exception':
            assert p.returncode!=0 and report['process']['child_exit']==0xc0000094,report
            assert any(r.get('code')==0xc0000094 and r['event']=='exception_raised' and r.get('process_id')==report['process']['observation']['child_pid'] for r in report['startup_diagnostics']['records']),report
        elif case in ('stop','trace-stop'):
            assert state['phase']=='stopped' and report['status']=='stopped',report
            if case=='trace-stop':assert any(r.get('event')=='game_start_enter' for r in report['startup_diagnostics']['records']),report
        elif case in STARTUP:
            rows=[r for r in report['startup_diagnostics']['records'] if r.get('source')=='startup']
            if case=='trace-off':
                assert not rows and 'startup_trace' not in report,report
                assert report['failure_reason']=='closed_before_game_window',report
            else:
                assert any(r['event']=='loader_import_hooks' and r['detail']>0 for r in rows),report
                assert all(r['process_id']==report['process']['observation']['child_pid'] for r in rows),report
                assert any(r['event']=='ffxi_com_return' and r['code']==0 and r['detail']==1 for r in rows),report
                assert any(r['event']=='game_start_enter' for r in rows),report
                assert not any(r['event']=='observer_failed' for r in rows),report
                if case=='trace-exception':
                    assert report['process']['child_exit']==0xc0000094,report
                    assert not any(r['event']=='game_start_return' for r in rows),report
                else:
                    assert any(r['event']=='game_start_return' and r['code']==STARTUP[case][1] for r in rows),report
                    expected_failure='game_main_failed' if case in ('trace-inner','trace-data') else 'game_start_returned_without_window'
                    assert report['process']['child_exit']==0 and report['failure_reason']==expected_failure,report
            if case!='trace-exception':
                fixture=json.loads((SESSION/'startup-fixture.json').read_text())
                assert fixture['hresult']==STARTUP[case][1],fixture
                if case!='trace-inner':assert fixture['last_error']==1234,fixture
            if case=='trace-data':
                assert any(r['event']=='game_main_return' and r['code']==0x88770000 for r in rows),report
                assert any(r['event']=='main_file_open' and r['code']==2 and r['detail']>>24==2 for r in rows),report
                assert any(r['event']=='main_file_read' and r['code']==0 and r['detail']==(1<<24|5) for r in rows),report
                assert any(r['event']=='main_file_size' and r['code']==0 and r['detail']==(1<<24|5) for r in rows),report
                assert any(r['event']=='main_directplay_load' and r['code']==0 and r['detail']==1 for r in rows),report
                assert any(r['event']=='main_windows_version' and r['code']==0 for r in rows),report
                assert report['client_data']['patch.ver']=={'state':'readable','bytes':5},report
                assert report['client_data']['FTABLE.DAT']['state']=='missing',report
            if case=='trace-inner':assert any(r['event']=='game_main_return' and r['code']==0x8007000e for r in rows),report
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
        assert hashlib.sha256(loader.read_bytes()).hexdigest()==manifest['key_files'][manifest['loader']]
        assert not list(loader.parent.glob('lsb-startup-*.exe')),'temporary diagnostic copy was not removed'
        if case not in ('missing-dependency','check-only'):
            diagnostics=report['startup_diagnostics']
            assert diagnostics['policy']=='fixed_metadata_only' and len(diagnostics['records'])<=64,diagnostics
        for path in [*LOGS.glob('*'),*SESSION.glob('*.json')]:
            if path.is_file():
                data=path.read_bytes()
                assert b'LSB_private_account' not in data and b'quoted' not in data and 'quoted'.encode('utf-16le') not in data,path
        (LOGS/('launch-'+case+'.json')).write_text(json.dumps(report,indent=2))
        print('PASS: client launch',case,'literal argv/cwd, private diagnostics, receipt and shutdown',flush=True)


if __name__=='__main__':main()
