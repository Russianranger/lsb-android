"""Exercise the shipped ARM64 producer under both Linux and Android-style PRoot.

No game assets. Compare real X11 readback with a deterministic image; the Android
Surface itself still needs device acceptance. Socket messages contain no pixels.
"""
import ctypes,mmap,os,socket,stat,struct,subprocess,time
from pathlib import Path

def verify_native_surface(folder,display,x,d,window,gc):
    helper=Path('/presentation/x11-frame-bridge')
    if not helper.is_file():
        helper=Path(__file__).resolve().parents[2]/'out/presentation/x11-frame-bridge'
    assert helper.is_file(), 'Missing native presentation artifact'
    # Move the cursor out of the compared patch; retain cursor compositing.
    x.XWarpPointer.argtypes=[ctypes.c_void_p,ctypes.c_ulong,ctypes.c_ulong,ctypes.c_int,ctypes.c_int,ctypes.c_uint,ctypes.c_uint,ctypes.c_int,ctypes.c_int]
    x.XWarpPointer(d,0,window,0,0,0,0,1279,719);x.XSync(d,0)
    x.XSetForeground.argtypes=[ctypes.c_void_p,ctypes.c_void_p,ctypes.c_ulong]
    x.XFillRectangle.argtypes=[ctypes.c_void_p,ctypes.c_ulong,ctypes.c_void_p,ctypes.c_int,ctypes.c_int,ctypes.c_uint,ctypes.c_uint]
    # XGetImage fallback is tested separately from the required MIT-SHM path.
    for fps,no_shm in ((30,False),(30,True),(60,False),(60,True)):
        sock=folder/'native.sock';pixels=folder/'framebuffer.bin'
        sock.unlink(missing_ok=True)
        log=folder/(('native-xgetimage' if no_shm else 'native-shm')+('-60' if fps==60 else '')+'.log')
        with log.open('w') as output:
            process=subprocess.Popen([str(helper),str(sock),str(pixels),str(fps)]+(['--no-shm'] if no_shm else []),env=dict(os.environ,DISPLAY=display),stdout=output,stderr=subprocess.STDOUT)
            try:
                deadline=time.monotonic()+10
                while not sock.exists():
                    assert process.poll() is None,log.read_text()
                    assert time.monotonic()<deadline,'Native socket timeout'
                    time.sleep(.02)
                assert stat.S_IMODE(sock.stat().st_mode)==0o600
                assert stat.S_IMODE(pixels.stat().st_mode)==0o600
                assert pixels.stat().st_size==1280*720*4
                with pixels.open('rb') as backing,mmap.mmap(backing.fileno(),0,access=mmap.ACCESS_READ) as mapped:
                    for reconnect in range(2):
                        with socket.socket(socket.AF_UNIX,socket.SOCK_STREAM) as client:
                            client.settimeout(5);client.connect(str(sock))
                            def frame():
                                client.sendall(b'\x01');data=b''
                                while len(data)<32:
                                    part=client.recv(32-len(data));assert part,'Producer disconnected';data+=part
                                h=struct.unpack('!8I',data)
                                assert h[:4]==(0x4c534631,1280,720,5120),h
                                assert h[7]&1==int(not no_shm),log.read_text()
                                assert h[6]==(0 if h[7]&2 else 1280*720*4),h
                                client.setblocking(False)
                                try:extra=client.recv(1);assert not extra,'Pixels leaked onto socket'
                                except BlockingIOError:pass
                                finally:client.settimeout(5)
                                return h
                            h=frame();assert not h[7]&2,'Reconnect must deliver a complete first frame'
                            if fps==30 and not no_shm and not reconnect:
                                for py in range(0,600,7):
                                    for px in range(0,1100,11):
                                        expected=(((px//4+py//8)&31)<<19)|(((px//8+py//4)&63)<<10)|((((px^py)//4)&31)<<3)
                                        assert struct.unpack_from('<I',mapped,(py*1280+px)*4)[0]&0xffffff==expected,(px,py)
                            # A changed X window must trigger a new mapped frame.
                            before=mapped[:]
                            colour=0x123456+reconnect+int(no_shm)*2+fps
                            x.XSetForeground(d,gc,colour);x.XFillRectangle(d,window,gc,0,0,200,100);x.XSync(d,0)
                            time.sleep(.04)
                            assert mapped[:]==before,'Producer changed pixels before ownership returned'
                            h=frame();assert not h[7]&2,'Changed frame was skipped'
                            for py in range(100):
                                for px in range(200):assert struct.unpack_from('<I',mapped,(py*1280+px)*4)[0]&0xffffff==colour
                            snapshot=mapped[:];start=time.monotonic()
                            for _ in range(5):assert frame()[7]&2,'Idle image should retain the Surface'
                            assert time.monotonic()-start>=4/fps,'Producer ignored frame cap'
                            assert mapped[:]==snapshot,'Unchanged response modified pixels'
                            # XDamage fires even when the same colour is repainted.
                            # Exercise full-image comparison against the mapping,
                            # rather than only the no-damage idle fast path.
                            x.XFillRectangle(d,window,gc,0,0,200,100);x.XSync(d,0)
                            assert frame()[7]&2,'Identical repaint must not be published again'
                            assert mapped[:]==snapshot,'Duplicate comparison changed the published image'
                assert 'cap='+str(fps) in log.read_text()
                print('PASS: native shared-file exact pixels, changed/idle/duplicate frames, ownership, reconnect, permissions and '+str(fps)+' Hz cap; '+('XGetImage' if no_shm else 'MIT-SHM'),flush=True)
            finally:
                process.terminate()
                try:process.wait(timeout=5)
                except subprocess.TimeoutExpired:process.kill();process.wait()
                print(log.read_text(),flush=True)
