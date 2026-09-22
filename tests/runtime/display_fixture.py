"""Owned TigerVNC/X11 fixture with a deterministic, high-colour 720p image."""
import array,ctypes,os,signal,subprocess,sys,time
from pathlib import Path

folder=Path(sys.argv[1]);folder.mkdir(exist_ok=True)
# The host-owned parent is mode 0700; allow the host CI user to read a
# socket created by the container's root, without exposing it outside that folder.
display=':97';sock=folder/'display.sock'
server=subprocess.Popen(['Xtigervnc',display,'-geometry','1280x720','-depth','24',
    '-rfbport','-1','-rfbunixpath',str(sock),'-rfbunixmode','0666','-SecurityTypes','None',
    '-AlwaysShared','-nolisten','tcp','-ac','-ZlibLevel','1','-FrameRate','30'])
def stop(*unused):raise SystemExit(0)
signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
try:
    deadline=time.monotonic()+20
    while not sock.exists():
        if server.poll() is not None:raise RuntimeError('X server stopped')
        if time.monotonic()>deadline:raise TimeoutError('X server socket')
        time.sleep(.05)
    x=ctypes.CDLL('libX11.so.6');P=ctypes.c_void_p;I=ctypes.c_int;U=ctypes.c_uint;L=ctypes.c_ulong
    def fn(name,result,args):
        f=getattr(x,name);f.restype=result;f.argtypes=args;return f
    fn('XOpenDisplay',P,[ctypes.c_char_p]);fn('XDefaultRootWindow',L,[P]);fn('XDefaultVisual',P,[P,I])
    fn('XCreateSimpleWindow',L,[P,L,I,I,U,U,U,L,L]);fn('XMapWindow',I,[P,L]);fn('XCreateGC',P,[P,L,L,P])
    fn('XCreateImage',P,[P,P,U,I,I,P,U,U,I,I]);fn('XPutImage',I,[P,L,P,P,I,I,I,I,U,U])
    fn('XSync',I,[P,I]);fn('XDestroyImage',I,[P]);fn('XCloseDisplay',I,[P])
    d=x.XOpenDisplay(display.encode());assert d
    root=x.XDefaultRootWindow(d);window=x.XCreateSimpleWindow(d,root,0,0,1280,720,0,0,0)
    x.XMapWindow(d,window);gc=x.XCreateGC(d,window,0,None)
    # Quantized components retain exact RGB565 source values; colours and tiles
    # vary spatially. This is a protocol fixture, not a Thor gameplay benchmark.
    pixels=array.array('I',((((px//4+py//8)&31)<<19)|(((px//8+py//4)&63)<<10)|((((px^py)//4)&31)<<3) for py in range(720) for px in range(1280)))
    libc=ctypes.CDLL(None);libc.malloc.argtypes=[ctypes.c_size_t];libc.malloc.restype=P
    data=libc.malloc(len(pixels)*4);assert data;ctypes.memmove(data,pixels.buffer_info()[0],len(pixels)*4)
    picture=x.XCreateImage(d,x.XDefaultVisual(d,0),24,2,0,data,1280,720,32,0);assert picture
    # Paint again after Expose has settled, before publishing readiness.
    for _ in range(3):x.XPutImage(d,window,gc,picture,0,0,0,0,1280,720);x.XSync(d,0);time.sleep(.1)
    x.XDestroyImage(picture)
    from native_surface_probe import verify_native_surface
    verify_native_surface(folder,display,x,d,window,gc)
    # Restore the original image after the native transport's changed-frame check.
    data=libc.malloc(len(pixels)*4);assert data;ctypes.memmove(data,pixels.buffer_info()[0],len(pixels)*4)
    picture=x.XCreateImage(d,x.XDefaultVisual(d,0),24,2,0,data,1280,720,32,0)
    x.XPutImage(d,window,gc,picture,0,0,0,0,1280,720);x.XSync(d,0);x.XDestroyImage(picture)
    (folder/'ready').write_text('ready')
    while not (folder/'stop').exists():
        if server.poll() is not None:raise RuntimeError('X server stopped during transfer')
        time.sleep(.1)
    x.XCloseDisplay(d)
finally:
    if server.poll() is None:
        server.terminate()
        try:server.wait(timeout=5)
        except subprocess.TimeoutExpired:server.kill();server.wait()
