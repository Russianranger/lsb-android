"""Credential-free FEX crash check; retain raw diagnostics from this owned PE only."""
import os,sys,uuid
from pathlib import Path
sys.path.insert(0,'/opt/lsb')
from supervisor import Supervisor
Path('/session/stop').unlink(missing_ok=True)
s=Supervisor(dict(format=1,engine='fex',session_id=str(uuid.uuid4()),renderer='software',audio=False,action='check-launcher'))
try:
    # integration.py has initialized and verified this native prefix. This
    # console-only fixture needs no loader manifest or display server.
    env=dict(s.env,WINEDEBUG='-all,+timestamp,+pid,err+all,trace+seh,trace+loaddll,trace+process')
    env['WINEDLLOVERRIDES']+=';winedbg='
    for label,args in [('suppressed',[]),('default',['--default-error-mode'])]:
        p=s.spawn(s.wine_command(r'Z:\fixtures\fex-crash.exe',*args),'fex-crash-'+label+'.log',env=env,fixed_output=True)
        s.wait(p,25,'FEX second-chance exception fixture '+label,accepted=(0x94,))
        print('PASS: FEX reports the owned unhandled exception and exits without a debugger hang:',label,flush=True)
finally:s.stop()
