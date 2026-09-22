"""Actual Wine/Box64 DirectInput enumeration and shared input transport."""
import ctypes, mmap, os
from pathlib import Path
import struct, subprocess, sys, threading, time, uuid
sys.path.insert(0,'/opt/lsb')
from supervisor import Supervisor
library=Path('/opt/lsb/liblsb-gamepad.so')
header=library.read_bytes()[:20]
assert header[:6]==b'\x7fELF\x02\x01' and struct.unpack_from('<H',header,18)[0]==183, 'Gamepad bridge must be ELF64 AArch64'
# This is a native glibc host preload, not an x86 Wine/Box64 guest library.
# Resolve every undefined symbol now to distinguish dependency errors from input errors.
ctypes.CDLL(str(library),mode=os.RTLD_NOW)
print('PASS: packaged ARM64 gamepad bridge loads with all host symbols resolved',flush=True)
session=Path('/session');(session/'stop').unlink(missing_ok=True)
(session/'gamepad-stage').unlink(missing_ok=True)
(session/'gamepad-bridge.json').unlink(missing_ok=True)
(session/'gamepad.bin').write_bytes(bytes(64))
# A valid input file must not start a polling thread in unrelated processes.
# Wine's winedevice.exe host below must still attach and publish all four axes.
subprocess.run([sys.executable,'-c',
    'from pathlib import Path; import time; time.sleep(.1); '
    'assert len(list(Path("/proc/self/task").iterdir()))==1, "gamepad worker started outside Wine HID host"'],
    env=dict(os.environ,LD_PRELOAD=str(library),LSB_GAMEPAD_STATE=str(session/'gamepad.bin')),check=True)
print('PASS: gamepad preload is idle outside Wine HID host',flush=True)
done=False
def write_pad():
    with (session/'gamepad.bin').open('r+b') as f,mmap.mmap(f.fileno(),64) as m:
        count=0
        while not done:
            try:stage=int((session/'gamepad-stage').read_text())
            except (OSError,ValueError):stage=-1
            # Start neutral. Wine's HID report is created asynchronously after
            # SDL attachment; a button held before enumeration has no new edge.
            # Stage 3 stops the heartbeat after stage 2 proves a fresh press.
            if stage==3:
                time.sleep(.02);continue
            count+=2;stamp=int(time.monotonic()*1000)
            m[4:8]=struct.pack('<I',count-1)
            buttons,hat=(1,3) if stage in (0,2) else (0,0)
            axes=(20000,-18000,12000,-16000) if stage==0 else (-20000,18000,-12000,16000) if stage==2 else (0,0,0,0)
            struct.pack_into('<I',m,0,0x4c534247);struct.pack_into('<IIhhhh',m,8,buttons,hat,*axes)
            struct.pack_into('<Q',m,32,stamp)
            m[4:8]=struct.pack('<I',count);time.sleep(.02)
worker=threading.Thread(target=write_pad,daemon=True);worker.start()
os.environ['LSB_TEST_AUTOCLOSE']='1'
s=Supervisor(dict(format=1,session_id=str(uuid.uuid4()),renderer='software',audio=False,action='probe',gamepad=True))
try:
    s.start()
    s.wait(s.spawn(['/usr/local/bin/box64','/opt/wine/bin/wine',r'Z:\fixtures\gamepad-check.exe'],'gamepad-check.log'),100,'DirectInput controller checks')
    print('PASS: real Wine virtual controller input and disconnect handling')
finally:
    done=True;worker.join(2);print((session/'gamepad-bridge.json').read_text() if (session/'gamepad-bridge.json').exists() else 'No gamepad bridge receipt',flush=True);s.stop();os.environ.pop('LSB_TEST_AUTOCLOSE',None)
