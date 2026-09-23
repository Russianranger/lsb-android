"""Credential-free FEX crash check; retain raw diagnostics from this owned PE only."""
import os,sys,uuid
sys.path.insert(0,'/opt/lsb')
from supervisor import Supervisor
s=Supervisor(dict(format=1,engine='fex',session_id=str(uuid.uuid4()),renderer='software',audio=False,action='check-launcher'))
try:
    # integration.py has initialized and verified this native prefix. This
    # console-only fixture needs no loader manifest or display server.
    env=dict(s.env,WINEDEBUG='-all,+timestamp,+pid,err+all,trace+seh,trace+loaddll,trace+process')
    env['WINEDLLOVERRIDES']+=';winedbg='
    p=s.spawn(s.wine_command(r'Z:\fixtures\fex-crash.exe'),'fex-crash.log',env=env,fixed_output=True)
    s.wait(p,25,'FEX second-chance exception fixture',accepted=(0x94,))
    print('PASS: FEX reports the owned unhandled exception and exits without a debugger hang',flush=True)
finally:s.stop()
