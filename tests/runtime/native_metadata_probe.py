"""Real cursor changes and RandR resizes; no game files or mocked X11 events."""
import ctypes,struct


def verify_metadata(x,d,window,frame,mapped,background):
    P=ctypes.c_void_p;L=ctypes.c_ulong;I=ctypes.c_int;U=ctypes.c_uint
    class Color(ctypes.Structure):
        _fields_=[('pixel',L),('red',ctypes.c_ushort),('green',ctypes.c_ushort),('blue',ctypes.c_ushort),('flags',ctypes.c_char),('pad',ctypes.c_char)]
    def function(name,result,args):
        f=getattr(x,name);f.restype=result;f.argtypes=args;return f
    function('XCreateBitmapFromData',L,[P,L,ctypes.c_char_p,U,U])
    function('XCreatePixmapCursor',L,[P,L,L,ctypes.POINTER(Color),ctypes.POINTER(Color),U,U])
    function('XDefineCursor',I,[P,L,L]);function('XUndefineCursor',I,[P,L])
    function('XFreeCursor',I,[P,L]);function('XFreePixmap',I,[P,L])
    def make_cursor(rgb,hot,visible=True):
        source=x.XCreateBitmapFromData(d,window,b'\xff'*8,8,8)
        mask=x.XCreateBitmapFromData(d,window,(b'\xff' if visible else b'\x00')*8,8,8)
        assert source and mask
        fg=Color(0,*rgb,b'\x07',b'\x00');bg=Color(0,0,0,0,b'\x07',b'\x00')
        cursor=x.XCreatePixmapCursor(d,source,mask,ctypes.byref(fg),ctypes.byref(bg),*hot)
        x.XFreePixmap(d,source);x.XFreePixmap(d,mask);assert cursor;return cursor
    def move(px,py):x.XWarpPointer(d,0,window,0,0,0,0,px,py);x.XSync(d,0)
    def pixel(px,py,width=1280):return struct.unpack_from('<I',mapped,(py*width+px)*4)[0]&0xffffff
    def patch(px,py,colour,width=8,height=8):
        for yy in range(py,py+height):
            for xx in range(px,px+width):assert pixel(xx,yy)==colour,(xx,yy,hex(pixel(xx,yy)),hex(colour))
    def changed(size=(1280,720)):
        h=frame(size);assert not h[7]&2,'Changed cursor/geometry must deliver pixels';return h

    cursors=[make_cursor((65535,0,0),(2,3)),make_cursor((0,65535,0),(5,1)),make_cursor((0,0,0),(0,0),False)]
    try:
        x.XDefineCursor(d,window,cursors[0]);move(60,40);changed();patch(58,37,0xff0000)
        # Reuse the same image at a different pointer position and restore the
        # pixels underneath its old position; cached x/y would fail this check.
        move(100,60);changed();patch(98,57,0xff0000);patch(58,37,background)
        # Shape and hotspot change without pointer motion or game repainting.
        x.XDefineCursor(d,window,cursors[1]);x.XSync(d,0);changed();patch(95,59,0x00ff00)
        assert pixel(98,57)==background,'Old cursor hotspot left stale pixels'
        x.XDefineCursor(d,window,cursors[2]);x.XSync(d,0);changed();patch(95,59,background)
        assert frame()[7]&2,'Transparent cursor should leave an idle image'
        x.XDefineCursor(d,window,cursors[0]);move(0,0);changed();patch(0,0,0xff0000,6,5)
        x.XDefineCursor(d,window,cursors[2]);x.XSync(d,0);changed();patch(0,0,background,6,5)

        # Use the actual RandR mode change so the root sends ConfigureNotify.
        # A window-only resize would not exercise cached root geometry.
        randr=ctypes.CDLL('libXrandr.so.2')
        class Size(ctypes.Structure):
            _fields_=[('width',I),('height',I),('mm_width',I),('mm_height',I)]
        randr.XRRGetScreenInfo.argtypes=[P,L];randr.XRRGetScreenInfo.restype=P
        randr.XRRConfigSizes.argtypes=[P,ctypes.POINTER(I)];randr.XRRConfigSizes.restype=ctypes.POINTER(Size)
        randr.XRRSetScreenConfig.argtypes=[P,P,L,I,ctypes.c_ushort,L];randr.XRRSetScreenConfig.restype=I
        randr.XRRFreeScreenConfigInfo.argtypes=[P]
        root=x.XDefaultRootWindow(d)
        def resize(width,height):
            config=randr.XRRGetScreenInfo(d,root);assert config
            try:
                count=I();sizes=randr.XRRConfigSizes(config,ctypes.byref(count))
                modes=[(sizes[i].width,sizes[i].height) for i in range(count.value)]
                assert (width,height) in modes,modes
                assert randr.XRRSetScreenConfig(d,config,root,modes.index((width,height)),1,0)==0
                x.XSync(d,0);changed((width,height))
                assert pixel(10,10,width)==background,'Resize changed source pixels'
            finally:randr.XRRFreeScreenConfigInfo(config)
        resize(800,600);resize(1280,720)
    finally:
        x.XUndefineCursor(d,window);move(1279,719)
        for cursor in cursors:x.XFreeCursor(d,cursor)
    changed()
