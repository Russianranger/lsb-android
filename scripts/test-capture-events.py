#!/usr/bin/env python3
"""Compile the real producer and exercise request1/2 on a disposable Xvfb server.

Needs cc, X11/Xext/Xfixes/Xdamage development libraries and Xvfb; no game/runtime.
"""
from pathlib import Path
import argparse,ctypes,json,mmap,os,select,socket,struct,subprocess,sys,tempfile,time
root=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(root/'tests/runtime'))
from native_event_probe import verify_event_wait
from native_metadata_probe import verify_metadata
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--helper',type=Path);args=p.parse_args()
out=root/'out/tests/capture-events';out.mkdir(parents=True,exist_ok=True)
helper=args.helper or out/'x11-frame-bridge'
if not args.helper:
    subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',str(root/'native/presentation/x11-frame-bridge.c'),'-lXdamage','-lX11','-lXext','-lXfixes','-o',str(helper)],check=True)
with tempfile.TemporaryDirectory(prefix='lsb-capture-') as name:
    folder=Path(name);readfd,writefd=os.pipe()
    with (out/'xvfb.log').open('w') as output:
        server=subprocess.Popen(['Xvfb','-displayfd',str(writefd),'-screen','0','1280x720x24','-ac','-nolisten','tcp'],pass_fds=[writefd],stdout=output,stderr=subprocess.STDOUT)
    os.close(writefd)
    try:
        assert select.select([readfd],[],[],10)[0],(out/'xvfb.log').read_text()
        number=os.read(readfd,100).decode().strip();assert number,(out/'xvfb.log').read_text()
        display=':'+number
        x=ctypes.CDLL('libX11.so.6');P=ctypes.c_void_p;L=ctypes.c_ulong;I=ctypes.c_int;U=ctypes.c_uint
        def fn(n,result,params):
            f=getattr(x,n);f.restype=result;f.argtypes=params;return f
        fn('XOpenDisplay',P,[ctypes.c_char_p]);fn('XDefaultRootWindow',L,[P]);fn('XCreateSimpleWindow',L,[P,L,I,I,U,U,U,L,L])
        fn('XMapWindow',I,[P,L]);fn('XCreateGC',P,[P,L,L,P]);fn('XSetForeground',I,[P,P,L]);fn('XFillRectangle',I,[P,L,P,I,I,U,U])
        fn('XSync',I,[P,I]);fn('XCloseDisplay',I,[P]);fn('XWarpPointer',I,[P,L,L,I,I,U,U,I,I])
        d=x.XOpenDisplay(display.encode());assert d
        window=x.XCreateSimpleWindow(d,x.XDefaultRootWindow(d),0,0,1280,720,0,0,0);x.XMapWindow(d,window)
        gc=x.XCreateGC(d,window,0,None);x.XWarpPointer(d,0,window,0,0,0,0,400,300);x.XSync(d,0)
        try:
            for fps,no_shm,byte in ((30,False,1),(60,False,1),(30,False,2),(60,False,2),(60,True,2)):
                path=folder/'bridge.sock';pixels=folder/'pixels';path.unlink(missing_ok=True)
                log=out/f'producer-{fps}-{no_shm}-{byte}.log'
                with log.open('w') as output:
                    process=subprocess.Popen([str(helper),str(path),str(pixels),str(fps)]+(['--no-shm'] if no_shm else []),env=dict(os.environ,DISPLAY=display),stdout=output,stderr=subprocess.STDOUT)
                try:
                    until=time.monotonic()+5
                    while not path.exists():
                        assert process.poll() is None,log.read_text()
                        assert time.monotonic()<until,'Producer did not bind'
                        time.sleep(.01)
                    with pixels.open('rb') as backing,mmap.mmap(backing.fileno(),0,access=mmap.ACCESS_READ) as mapped:
                        for reconnect in range(2):
                            with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as client:
                                client.settimeout(5);client.connect(str(path))
                                def frame(size=(1280,720),pending=False):
                                    if not pending:client.sendall(bytes([byte]))
                                    data=b''
                                    while len(data)<32:
                                        part=client.recv(32-len(data));assert part,'Producer disconnected';data+=part
                                    h=struct.unpack('!8I',data)
                                    assert h[:4]==(0x4c534631,*size,size[0]*4),h
                                    assert bool(h[7]&1)==(not no_shm),log.read_text()
                                    return h
                                colour=0x123456+reconnect
                                x.XSetForeground(d,gc,colour);x.XFillRectangle(d,window,gc,0,0,1280,720);x.XSync(d,0)
                                assert not frame()[7]&2,'Reconnect did not publish full image'
                                assert struct.unpack_from('<I',mapped,0)[0]&0xffffff==colour
                                snapshot=mapped[:]
                                x.XSetForeground(d,gc,colour+1);x.XFillRectangle(d,window,gc,0,0,200,100);x.XSync(d,0);time.sleep(.04)
                                assert mapped[:]==snapshot,'Pixels overwritten without returned ownership'
                                assert not frame()[7]&2
                                assert struct.unpack_from('<I',mapped,0)[0]&0xffffff==colour+1
                                verify_metadata(x,d,window,gc,frame,mapped,colour+1,resize=False)
                                if byte==2:verify_event_wait(x,d,window,gc,client,frame,mapped,fps,colour+1)
                    until=time.monotonic()+3
                    while 'capture_request_policy' not in log.read_text() and time.monotonic()<until:time.sleep(.01)
                    records=[json.loads(line) for line in log.read_text().splitlines() if line.startswith('{')]
                    assert records,log.read_text()
                    assert all(r['capture_request_policy']==('xdamage_wait' if byte==2 else 'poll_ticks') for r in records)
                    if byte==2:
                        assert sum(r['event_waits'] for r in records)>0
                        assert sum(r['event_wakes'] for r in records)>0
                        assert sum(r['idle_heartbeats'] for r in records)>0
                    print(f'PASS: real X11 request{byte}, {fps}Hz, '+('XGetImage' if no_shm else 'MIT-SHM')+', cursor, ownership and reconnect',flush=True)
                finally:
                    process.terminate();process.wait(timeout=5)
        finally:x.XCloseDisplay(d)
    finally:
        os.close(readfd);server.terminate();server.wait(timeout=5)
