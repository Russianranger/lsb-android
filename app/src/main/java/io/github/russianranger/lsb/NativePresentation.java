package io.github.russianranger.lsb;

import android.content.Context;
import android.net.*;
import android.os.ParcelFileDescriptor;
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
    NativePresentation(Context context,File path,File pixelsPath,NativePerformance.Stream reports,Events events){
        super(context);this.path=path;this.pixelsPath=pixelsPath;this.reports=reports;this.events=events;
        getHolder().addCallback(this);
    }
    private static native int frame(Surface surface,int fd,ByteBuffer pixels,long[] measures);
    @Override public void surfaceCreated(SurfaceHolder holder){start(holder.getSurface());}
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int w,int h){}
    @Override public void surfaceDestroyed(SurfaceHolder holder){stopWorker();}
    private synchronized void start(Surface surface){
        if(closed)return;
        Thread old=lastWorker;
        Thread next=new Thread(()->{
            // Never run two frame readers when Android recreates the Surface.
            if(old!=null)try{old.join(5500);}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            if(worker!=Thread.currentThread()||closed)return;
            if(old!=null&&old.isAlive()){Thread waiting=Thread.currentThread();post(()->{if(worker==waiting&&!closed)events.failed("Previous Surface reader did not stop");});return;}
            String failureReason=null;stats.resume();
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
                        ByteBuffer pixels=backing.getChannel().map(FileChannel.MapMode.READ_ONLY,0,CAPACITY);long[] times=new long[9];
                        long lastReport=System.nanoTime();
                        while(worker==Thread.currentThread()&&!closed&&events.current()&&surface.isValid()){
                            long begin=System.nanoTime();
                            int result=frame(surface,fd.getFd(),pixels,times);
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
