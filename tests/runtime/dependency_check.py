"""Check actual Wine DLL lifetimes, including the 20 imports in the Thor report.

Run after initialization.py, using its prepared test prefix and drive mappings.
The real xiloader is not included or executed by these tests.
"""
import os
import json
from pathlib import Path
import shutil
import sys
import uuid

sys.path.insert(0,'/opt/lsb')
from supervisor import Supervisor

IMPORTS=['CRYPT32.dll','WS2_32.dll','bcrypt.dll','KERNEL32.dll','USER32.dll',
         'SHELL32.dll','ole32.dll','ADVAPI32.dll','MSVCP140.dll','VCRUNTIME140.dll',
         *['api-ms-win-crt-'+name+'-l1-1-0.dll' for name in
           ['stdio','runtime','string','heap','convert','filesystem','time','locale','math','environment']]]


def main():
    session=Path('/session');result=session/'loader-check.json';detached=session/'dependency-detached'
    (session/'stop').unlink(missing_ok=True)
    root=Path('/client/FINAL FANTASY XI/dependency test');root.mkdir(exist_ok=True)
    for name in ['dependency-detach.dll','dependency-attach.dll']:
        shutil.copyfile('/fixtures/'+name,root/name)
    s=Supervisor({'format':1,'engine':os.environ.get('LSB_TEST_ENGINE','box64'),'session_id':str(uuid.uuid4()),'renderer':'software','audio':False,'action':'check-launcher'})
    # 0.5.1 Thor: WS2_32.dll initialization failed with the controller preload
    # enabled. Exercise the exact import list in that environment as well.
    (session/'gamepad.bin').write_bytes(bytes(64))
    exe=r'D:\FINAL FANTASY XI\dependency test\xiloader.exe'
    def run(label,args,gamepad=False):
        env=dict(s.env,WINEDEBUG='-all,+timestamp,+pid,err+all,warn+module,trace+loaddll',BOX64_DLSYM_ERROR='1')
        if gamepad:env.update(LD_PRELOAD='/opt/lsb/liblsb-gamepad.so',LSB_GAMEPAD_STATE='/session/gamepad.bin')
        p=s.spawn(s.wine_command(*args),'dependency-'+label+'.log',env=env)
        p.wait(timeout=90)
        return p.returncode
    try:
        for mode in ('unload','exit'):
            detached.unlink(missing_ok=True)
            rc=run(mode,[r'Z:\fixtures\dependency-cycle.exe',mode,exe.rsplit('\\',1)[0]+r'\dependency-detach.dll'])
            assert rc!=0 and detached.exists(),('negative control did not exercise DLL detach',mode,rc)
            print('PASS: dependency lifetime negative control',mode,flush=True)
        cases=[('thor-imports',IMPORTS,True),('thor-imports-repeat',IMPORTS,True),
               ('thor-gamepad',IMPORTS,True),('thor-gamepad-repeat',IMPORTS,True),
               ('detach',IMPORTS+['dependency-detach.dll'],True),
               ('missing',['dependency-detach.dll','lsb-does-not-exist.dll'],False),
               ('attach',['dependency-attach.dll'],None)]
        for label,names,expected in cases:
            result.unlink(missing_ok=True);detached.unlink(missing_ok=True)
            rc=run(label,[r'P:\client-launch.exe','check',exe,*names],gamepad=label.startswith('thor-gamepad'))
            assert not detached.exists(),('checker invoked DLL detach',label)
            if expected is None:
                assert rc!=0 and not result.exists(),('attach crash was hidden',rc)
            else:
                report=json.loads(result.read_text())
                assert rc==(0 if expected else 1) and report['ok'] is expected,(label,rc,report)
                assert report['bits']==32 and report['check_policy']=='load_only',report
                assert [d['name'] for d in report['dependencies']]==names,report
                assert all(d['ok'] and d['win32_error']==0 and d['loaded_path'] for d in report['dependencies'][:-1]),report
                if not expected:assert report['dependencies'][-1]['win32_error']==126,report
                Path('/logs/dependency-'+label+'.json').write_text(json.dumps(report,indent=2))
            print('PASS: dependency check',label,'exit',rc,flush=True)
    finally:s.stop()


if __name__=='__main__':main()
