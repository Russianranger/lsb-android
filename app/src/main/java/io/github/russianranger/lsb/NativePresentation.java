package io.github.russianranger.lsb;

import android.content.Context;
import android.net.*;
import android.os.ParcelFileDescriptor;
import android.hardware.display.DisplayManager;
import android.view.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Optional X11 readback -> native Android Surface. No RFB pixel decoder or Bitmap. */
final class NativePresentation extends SurfaceView implements SurfaceHolder.Callback {
    interface Events {void failed(String reason);void size(int width,int height);boolean current();}
    private static final int CAPACITY=1280*720*4;
    private final File path, pixelsPath;
    private final NativePerformance.Stream reports;
    private final NativeFrameStats stats=new NativeFrameStats(System.nanoTime());
    private final Events events;
    private volatile LocalSocket socket;
    private volatile Thread worker;
    private Thread lastWorker;
    private volatile boolean closed;
    private volatile int frameRateHint;
    private volatile boolean overlap=true;
    private volatile DisplayInfo displayInfo=new DisplayInfo(0,0);
    private DisplayManager displayManager;
    private final DisplayManager.DisplayListener displayListener=new DisplayManager.DisplayListener(){
        public void onDisplayAdded(int id){updateDisplay();}
        public void onDisplayRemoved(int id){updateDisplay();}
        public void onDisplayChanged(int id){updateDisplay();}
    };
    private static final class DisplayInfo {
        final int mode;final float hz;
        DisplayInfo(int mode,float hz){this.mode=mode;this.hz=hz;}
    }
    NativePresentation(Context context,File path,File pixelsPath,NativePerformance.Stream reports,Events events){
        super(context);this.path=path;this.pixelsPath=pixelsPath;this.reports=reports;this.events=events;
        getHolder().addCallback(this);
    }
    private static native int frame(Surface surface,int fd,ByteBuffer pixels,long[] measures,boolean overlap);
    void overlap(boolean enabled){overlap=enabled;}
    private void updateDisplay(){
        Display display=getDisplay();
        displayInfo=display==null?new DisplayInfo(0,0):new DisplayInfo(display.getMode().getModeId(),display.getRefreshRate());
    }
    @Override protected void onAttachedToWindow(){
        super.onAttachedToWindow();displayManager=(DisplayManager)getContext().getSystemService(Context.DISPLAY_SERVICE);
        if(displayManager!=null)displayManager.registerDisplayListener(displayListener,new android.os.Handler(android.os.Looper.getMainLooper()));
        updateDisplay();
    }
    @Override protected void onDetachedFromWindow(){
        if(displayManager!=null){displayManager.unregisterDisplayListener(displayListener);displayManager=null;}
        super.onDetachedFromWindow();
    }
    /** Display refresh vote in frames per second (the configured cap); 0 leaves SurfaceFlinger's own choice. Call before the Surface exists. */
    void frameRate(int fps){frameRateHint=fps;}
    // Game content arrives at an irregular 15-60 posts/s. Without a vote, SurfaceFlinger's
    // layer-history heuristic may move a variable-refresh panel to a lower mode mid-play.
    // Only a seamless switch is allowed, so the vote never blanks the screen.
    private static void voteFrameRate(Surface surface,int fps){
        if(fps<=0||android.os.Build.VERSION.SDK_INT<30||!surface.isValid())return;
        try{
            if(android.os.Build.VERSION.SDK_INT>=31)surface.setFrameRate(fps,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS);
            else surface.setFrameRate(fps,Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
        }catch(RuntimeException ignored){} // A hint only; presentation never depends on it.
    }
    @Override public void surfaceCreated(SurfaceHolder holder){voteFrameRate(holder.getSurface(),frameRateHint);updateDisplay();start(holder.getSurface());}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){}
    @Override public void surfaceDestroyed(SurfaceHolder holder){stopWorker();}
    private synchronized void start(Surface surface){
        if(closed)return;
        Thread old=lastWorker;
        final boolean overlapRequests=overlap;
        Thread next=new Thread(()->{
            // Never run two frame readers when Android recreates the Surface.
            if(old!=null)try{old.join(5500);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            if(worker!=Thread.currentThread()||closed)return;
            if(old!=null&&old.isAlive()){Thread waiting=Thread.currentThread();post(()->{if(worker==waiting&&!closed)events.failed("Previous Surface reader did not stop");});return;}
            String failureReason=null;stats.resume();
            // The worker does about 2 ms of work per frame but competes with the translated
            // game's busy threads for cores; a late wake-up delays every later frame.
            try{android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);}catch(RuntimeException ignored){}
            try{
                System.loadLibrary("lsb-presentation");
                for(int retry=0;(!path.exists()||!pixelsPath.exists())&&retry<100;retry++){
                    if(worker!=Thread.currentThread()||closed||!events.current())return;Thread.sleep(100);
                }
                try(LocalSocket local=new LocalSocket()){
                    socket=local;if(worker!=Thread.currentThread()||closed)return;
                    local.connect(new LocalSocketAddress(path.getPath(),LocalSocketAddress.Namespace.FILESYSTEM));local.setSoTimeout(5000);
                    if(local.getPeerCredentials().getUid()!=android.os.Process.myUid())throw new IOException("Native display owner mismatch");
                    try(ParcelFileDescriptor fd=ParcelFileDescriptor.dup(local.getFileDescriptor());FileInputStream backing=new FileInputStream(pixelsPath)){
                        if(backing.getChannel().size()!=CAPACITY)throw new IOException("Invalid native display buffer size");
                        ByteBuffer pixels=backing.getChannel().map(FileChannel.MapMode.READ_ONLY,0,CAPACITY);
                        // Results, geometry and one outstanding request belong to this socket/Surface only.
                        long[] times=new long[15];
                        long lastReport=System.nanoTime();
                        while(worker==Thread.currentThread()&&!closed&&events.current()&&surface.isValid()){
                            long begin=System.nanoTime();
                            DisplayInfo display=displayInfo;stats.display(display.mode,display.hz,frameRateHint,overlapRequests);
                            int result=frame(surface,fd.getFd(),pixels,times,overlapRequests);
                            long end=System.nanoTime();stats.frame(begin,end,result,times);
                            if(result==1){if(end-lastReport>5_000_000_000L){report(null,false);lastReport=System.nanoTime();}continue;}
                            if(result!=0)throw new IOException("Native frame delivery failed ("+result+")");
                            if(System.nanoTime()-lastReport>5_000_000_000L){report(null,false);lastReport=System.nanoTime();}
                            final int w=(int)times[0],h=(int)times[1];if(w!=reportedWidth||h!=reportedHeight){reportedWidth=w;reportedHeight=h;post(()->events.size(w,h));}
                        }
                    }
                }
            }catch(Exception|UnsatisfiedLinkError failure){failureReason=failure.getMessage();if(worker==Thread.currentThread()&&!closed){Thread failed=Thread.currentThread();post(()->{if(worker==failed&&!closed)events.failed(failure.getMessage());});}}finally{report(failureReason,true);}
        },"LSB native Surface");worker=next;lastWorker=next;next.start();
    }
    // Only a bounded numeric snapshot crosses threads; the writer owns JSON and I/O.
    private void report(String failure,boolean terminal){
        long begin=System.nanoTime();reports.record(stats.sample(begin,failure,terminal));
        stats.enqueued(System.nanoTime()-begin);
    }
    private int reportedWidth,reportedHeight;
    private synchronized void stopWorker(){worker=null;LocalSocket local=socket;socket=null;if(local!=null)try{local.shutdownInput();local.shutdownOutput();local.close();}catch(IOException ignored){}}
    void close(){closed=true;stopWorker();}
}
