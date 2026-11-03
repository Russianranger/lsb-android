"""Real XDamage wakeups while Android owns one pending request; no game files."""
import ctypes,select,struct,time


def verify_event_wait(x,d,window,gc,client,frame,mapped,fps,background):
    P=ctypes.c_void_p;L=ctypes.c_ulong;I=ctypes.c_int;U=ctypes.c_uint
    class Color(ctypes.Structure):
        _fields_=[('pixel',L),('red',ctypes.c_ushort),('green',ctypes.c_ushort),('blue',ctypes.c_ushort),('flags',ctypes.c_char),('pad',ctypes.c_char)]
    x.XCreateBitmapFromData.argtypes=[P,L,ctypes.c_char_p,U,U];x.XCreateBitmapFromData.restype=L
    x.XCreatePixmapCursor.argtypes=[P,L,L,ctypes.POINTER(Color),ctypes.POINTER(Color),U,U];x.XCreatePixmapCursor.restype=L
    x.XDefineCursor.argtypes=[P,L,L];x.XUndefineCursor.argtypes=[P,L]
    x.XFreeCursor.argtypes=[P,L];x.XFreePixmap.argtypes=[P,L]
    source=x.XCreateBitmapFromData(d,window,b'\x00',1,1)
    mask=x.XCreateBitmapFromData(d,window,b'\x00',1,1)
    black=Color();cursor=x.XCreatePixmapCursor(d,source,mask,ctypes.byref(black),ctypes.byref(black),0,0)
    x.XFreePixmap(d,source);x.XFreePixmap(d,mask);assert cursor
    def paint(colour):
        x.XSetForeground(d,gc,colour);x.XFillRectangle(d,window,gc,0,0,200,100);x.XSync(d,0)
    try:
        x.XDefineCursor(d,window,cursor);x.XSync(d,0);frame()
        for _ in range(2):assert frame()[7]&2
        snapshot=mapped[:]
        client.sendall(b'\x02')
        assert not select.select([client],[],[],.04)[0], 'Idle event request returned on a polling tick'
        colour=background^0x0055aa;paint(colour)
        h=frame(pending=True);assert not h[7]&2,'Pending request did not wake for real XDamage'
        assert struct.unpack_from('<I',mapped,0)[0]&0xffffff==colour
        assert mapped[:]!=snapshot
        start=time.monotonic()
        for n in range(12):
            paint(colour+n+1);assert not frame()[7]&2
        assert time.monotonic()-start>=10/fps,'Changed capture requests ignored cap'
        snapshot=mapped[:];start=time.monotonic()
        while time.monotonic()-start<2.15:
            assert frame()[7]&2
            assert mapped[:]==snapshot
        paint(background);assert not frame()[7]&2
    finally:
        x.XUndefineCursor(d,window);x.XSync(d,0);x.XFreeCursor(d,cursor)
    frame()
    print('PASS: XDamage pending-request wake,100ms idle heartbeat, capture cap and bounded unchanged readback',flush=True)
