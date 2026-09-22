"""Real PE32 COM/D3D8, RFB pixels/input and PCM through ARM64 Wine/Box64."""
import json,os,subprocess,socket,struct,time,sys,uuid,mmap
from pathlib import Path
from collections import Counter
from audio_receiver import Receiver
sys.path.insert(0,'/opt/lsb')
import supervisor

def wait(check, label, timeout=260):
    deadline=time.monotonic()+timeout
    while time.monotonic()<deadline:
        value=check()
        if value:return value
        time.sleep(.2)
    raise AssertionError(label)

def recv(s,size):
    out=b''
    while len(out)<size:
        part=s.recv(size-len(out))
        if not part:raise EOFError('Display closed')
        out+=part
    return out

def display():
    s=socket.socket(socket.AF_UNIX);s.settimeout(10);s.connect('/session/display.sock')
    assert recv(s,12)==b'RFB 003.008\n';s.sendall(b'RFB 003.008\n')
    n=recv(s,1)[0];assert 1 in recv(s,n);s.sendall(b'\x01');assert recv(s,4)==bytes(4);s.sendall(b'\x01')
    w,h=struct.unpack('>HH',recv(s,4));assert (w,h)==(1280,720);recv(s,16);recv(s,struct.unpack('>I',recv(s,4))[0])
    s.sendall(bytes(4)+bytes([32,24,0,1,0,255,0,255,0,255,16,8,0,0,0,0]))
    s.sendall(struct.pack('>BBHi',2,0,1,0))
    return s

def key(s):
    s.sendall(struct.pack('>BBHH',5,1,20,20)+struct.pack('>BBHH',5,0,20,20))
    s.sendall(struct.pack('>BBHI',4,1,0,ord('a'))+struct.pack('>BBHI',4,0,0,ord('a')))

def pixels(s):
    s.sendall(struct.pack('>BBHHHH',3,0,0,0,1280,720))
    while True:
        kind=recv(s,1)[0]
        if kind==2:continue
        if kind==3:recv(s,3);recv(s,struct.unpack('>I',recv(s,4))[0]);continue
        assert kind==0,kind
        recv(s,1);n=struct.unpack('>H',recv(s,2))[0];colors=Counter()
        for _ in range(n):
            x,y,w,h,encoding=struct.unpack('>HHHHi',recv(s,12));assert encoding==0
            raw=recv(s,w*h*4);colors.update(raw[i:i+3] for i in range(0,len(raw),4))
        return colors[bytes([72,48,24])]>50000 and len(colors)>1000

def native_pixels():
    with socket.socket(socket.AF_UNIX) as s,open('/session/framebuffer.bin','rb') as backing,mmap.mmap(backing.fileno(),0,access=mmap.ACCESS_READ) as mapped:
        s.settimeout(5);s.connect('/session/native-display.sock');s.sendall(b'\x01')
        h=struct.unpack('>8I',recv(s,32))
        assert h[:4]==(0x4c534631,1280,720,5120) and h[6]==1280*720*4 and h[7]==1,h
        colors=Counter(mapped[i:i+3] for i in range(0,len(mapped),4))
        return colors[bytes([72,48,24])]>50000 and len(colors)>1000

def main():
    for folder in ('/prefix','/session','/logs'):Path(folder).mkdir(exist_ok=True)
    renderer=os.environ.get('LSB_TEST_RENDERER','software')
    for cycle in range(2):
        for n in ('stop','status.json','probe.json','display.sock'):Path('/session',n).unlink(missing_ok=True)
        req={'format':1,'renderer':renderer,'audio':True,'session_id':str(uuid.uuid4()),'native_surface':cycle==1,'display_fps':60 if cycle==1 else 30,'dxvk_diagnostics':cycle==1 and renderer!='software','shm_upload':cycle==1,'dxvk_version':'2.7.1' if cycle==1 else '2.5.3'}
        Path('/session/request.json').write_text(json.dumps(req));receiver=Receiver('/session/audio.sock')
        p=subprocess.Popen(['python3','/opt/lsb/supervisor.py'],env=dict(os.environ,LSB_TEST_AUTOCLOSE='1'))
        try:
            def ready():
                if p.poll() is not None:raise AssertionError(Path('/session/status.json').read_text())
                try:return json.loads(Path('/session/probe.json').read_text()).get('d3d8_frames',0)>=25
                except (OSError,ValueError):return False
            wait(ready,'32-bit probe did not render')
            # Verify the actual live X server received the selected rate. This
            # also covers the standard-display fallback in the same session.
            x_commands=[]
            for f in Path('/proc').glob('[0-9]*/cmdline'):
                try:c=f.read_bytes().split(b'\0')
                except OSError:continue # Unrelated processes may exit during enumeration.
                if any(Path(os.fsdecode(a)).name=='Xtigervnc' for a in c) and b':7' in c and b'/session/display.sock' in c:x_commands.append(c)
            assert any(b'-FrameRate' in c and c[c.index(b'-FrameRate')+1]==str(req['display_fps']).encode() for c in x_commands), 'X server rate differs from request'
            if cycle==1:
                wait(native_pixels,'Wine triangle did not reach shared native framebuffer',6)
                assert 'cap=60' in Path('/logs/native-display.log').read_text()
                print('PASS: supervised Native Surface captures real Wine D3D8 pixels at 60 Hz alongside audio and RFB input',flush=True)
            with display() as s:
                key(s);wait(lambda:pixels(s),'Triangle did not reach the display',6)
            wait(lambda:sum(r['nonzero_samples'] for r in receiver.reports)>100,'Wine did not produce PCM',20)
            p.wait(timeout=40);assert p.returncode==0
            result=json.loads(Path('/session/status.json').read_text());probe=result['probe']
            assert result['phase']=='completed' and result['automatic_checks_passed'],result
            if cycle==1:assert result.get('native_surface_requested') and 'native_surface_fallback' not in result,result
            assert probe['key_events']>0 and probe['pointer_events']>0,probe
            assert probe['bits']==32 and probe['registry32'] and probe['com'] and probe['audio_submitted'],probe
            assert Path('/prefix/lsb-prefix-ready.json').is_file()
            if renderer!='software':
                assert result['hardware_verified'] is False,'CI must not claim Adreno hardware'
                assert result['graphics_hud']==('devinfo,fps,frametimes,compiler,cs' if cycle==1 else 'devinfo,fps'),result
                if cycle==1:print('PASS: detailed DXVK HUD runs with native capture, PCM and input',flush=True)
                log=Path('/logs/wine-probe.log').read_text(errors='replace')
                assert 'd3d8.dll' in log and 'd3d9.dll' in log and ': native' in log
                expected=os.environ.get('LSB_TEST_DXVK','2.5.3') if cycle==1 else '2.5.3'
                assert result['dxvk_selected']==expected,result
                assert any('DXVK: v'+expected in f.read_text(errors='replace') for f in Path('/logs').glob('*d3d*.log'))
                if cycle==1:
                    assert result['shm_upload_active'],result
                    if expected=='2.5.3':assert 'dxvk_fallback' in result,result
                    else:assert 'dxvk_fallback' not in result,result
                    # Exclude preflight's exactly 40 uploads; the real Wine/DXVK
                    # process must use the native-host interposer as well.
                    counters=[struct.unpack('<12Q',f.read_bytes()) for f in Path('/logs').glob('wsi-upload-*.bin')]
                    assert any(c[2]>=250 and c[7]==0 for c in counters),counters
                    print('PASS: actual Wine/DXVK uses shared-memory XCB presentation;',expected,'selected',flush=True)
            assert not receiver.errors,receiver.errors
            Path('/logs/acceptance-'+str(cycle)+'.json').write_text(json.dumps({'runtime':result,'audio':receiver.snapshot()},indent=2))
            print('PASS:',renderer,'cycle',cycle+1,'PE32, registry, COM, D3D8 pixels, keyboard/mouse, PCM and clean exit',flush=True)
        finally:
            if p.poll() is None:
                Path('/session/stop').write_text('stop');
                try:p.wait(timeout=20)
                except subprocess.TimeoutExpired:p.kill();p.wait()
            receiver.close()
    # Explicit cancellation after startup must stop the private display and Wine.
    for n in ('stop','status.json','probe.json'):Path('/session',n).unlink(missing_ok=True)
    req['audio']=False;req['session_id']=str(uuid.uuid4());Path('/session/request.json').write_text(json.dumps(req))
    p=subprocess.Popen(['python3','/opt/lsb/supervisor.py'])
    try:
        wait(lambda:Path('/session/probe.json').is_file(),'Cancellation fixture did not start')
        Path('/session/stop').write_text('stop');p.wait(timeout=30)
        result=json.loads(Path('/session/status.json').read_text());assert result['phase']=='stopped' and not result['display_ready'],result
        assert not Path('/session/display.sock').exists()
        print('PASS: explicit stop closes Wine and its private display',flush=True)
    finally:
        if p.poll() is None:p.kill();p.wait()

if __name__=='__main__':main()
