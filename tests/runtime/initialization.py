"""Actual x86 registration and client COM identities, with synthetic DLLs only."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid


def main():
    client = Path('/client'); client.mkdir(exist_ok=True)
    pol = client/'PlayOnlineViewer'; game = client/'FINAL FANTASY XI'
    (pol/'viewer/com').mkdir(parents=True,exist_ok=True);game.mkdir(exist_ok=True)
    for dest in [pol/'viewer/com/polcore.dll', pol/'viewer/com/polcoreeu.dll', game/'FFXi.dll', game/'FFXiMain.dll']:
        shutil.copyfile('/fixtures/client-com-stub.dll',dest)
    for dest in [pol/'pol.exe', game/'xiloader.exe']:
        shutil.copyfile('/fixtures/loader-stub.exe',dest)
    for label, region, failure in [('us','US',''),('eu','EU',''),('jp','JP',''),('registration-failure','US','fail-registration'),('com-failure','US','fail-com'),('prerequisite-retry','US','')]:
        for p in ['/session/stop','/session/status.json','/session/client-step.json','/client/fail-registration','/client/fail-com','/client/region-jp']:
            if label!='prerequisite-retry' or p!='/client/fail-com':Path(p).unlink(missing_ok=True)
        if failure:(client/failure).touch()
        if region=='JP':(client/'region-jp').touch()
        core=pol/'viewer/com'/('polcoreeu.dll' if region=='EU' else 'polcore.dll')
        key_files={str(p.relative_to(client)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [core,pol/'pol.exe',game/'FFXi.dll',game/'FFXiMain.dll',game/'xiloader.exe']}
        manifest={'format':1,'generation':str(uuid.uuid4()),'region':region,'pol':str(pol.relative_to(client)),
                  'game':str(game.relative_to(client)),'core':str(core.relative_to(client)),
                  'loader':str((game/'xiloader.exe').relative_to(client)),'key_files':key_files,'inventory_sha256':'0'*64}
        req={'format':1,'engine':os.environ.get('LSB_TEST_ENGINE','box64'),'session_id':str(uuid.uuid4()),'renderer':'software','audio':False,'action':'initialize'}
        if label=='prerequisite-retry':
            req['action']='installer';shutil.copyfile('/fixtures/prerequisite-stub.exe','/session/prerequisite.exe')
        Path('/session/client-manifest.json').write_text(json.dumps(manifest));Path('/session/request.json').write_text(json.dumps(req))
        p=subprocess.run(['python3','/opt/lsb/supervisor.py'],timeout=480)
        state=json.loads(Path('/session/status.json').read_text());report=json.loads(Path('/logs/client-initialization.json').read_text())
        assert report['generation']==manifest['generation'] and report['session_id']==req['session_id']
        assert not report['game_started']
        if failure:
            assert p.returncode!=0 and state['phase']=='error' and report['status']=='failed',report
            assert report['steps'][-1]['status']=='failed' and report['steps'][-1]['result']['ok'] is False,report
            assert report['steps'][-1]['name']==('register_core' if failure=='fail-registration' else 'activate_pol')
        else:
            assert p.returncode==0 and state['phase']=='completed' and state['initialization_passed'],state
            steps=report['steps']
            if label=='prerequisite-retry':
                assert steps[0]['status']=='completed' and steps[0]['result']['child_exit']==3010,steps
                steps=steps[1:]
            assert len(steps)==6 and all(s['status']=='passed' and s['result']['bits']==32 for s in steps),report
            assert all(s['result']['loaded_path'].lower().startswith('d:\\') for s in steps[1:])
        assert not Path('/session/display.sock').exists()
        Path('/logs/initialization-'+label+'.json').write_text(json.dumps(report,indent=2))
        print('PASS: native client initialization',label,'expected outcome, receipt and clean shutdown',flush=True)


if __name__=='__main__':main()
