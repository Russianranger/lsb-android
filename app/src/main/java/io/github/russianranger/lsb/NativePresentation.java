package io.github.russianranger.lsb;

import android.content.Context;
import android.net.*;
import android.os.ParcelFileDescriptor;
import android.view.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import org.json.JSONArray;

/** Optional X11 readback -> native Android Surface. No RFB pixel decoder or Bitmap. */
final class NativePresentation extends SurfaceView implements SurfaceHolder.Callback {
    interface Events {void failed(String reason);void size(int width,int height);boolean current();}
    private static final int CAPACITY=1280*720*4;
    private final File path, pixelsPath, reportFile;
    private final String sessionId;
    private final java.util.ArrayDeque<JSONObject> history=new java.util.ArrayDeque<>();
    private final long connectionStart=System.nanoTime();
    private final Events events;
    private volatile LocalSocket socket;
    private volatile Thread worker;
    private Thread lastWorker;
    private volatile boolean closed;
    private long since=System.nanoTime(),frames,unchanged,readNs,captureNs,lockNs,copyNs,postNs,bytes,shmFrames,lastFrame;
    private int width,height;
    NativePresentation(Context context,File path,File pixelsPath,File reportFile,String sessionId,Events events){
        super(context);this.path=path;this.pixelsPath=pixelsPath;this.reportFile=reportFile;this.sessionId=sessionId;this.events=events;
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
                            int result=frame(surface,fd.getFd(),pixels,times);
                            if(result==1){synchronized(this){unchanged++;}if(System.nanoTime()-lastReport>5_000_000_000L){report(null);lastReport=System.nanoTime();}continue;}
                            if(result!=0)throw new IOException("Native frame delivery failed ("+result+")");
                            synchronized(this){frames++;width=(int)times[0];height=(int)times[1];captureNs+=times[2];readNs+=times[3];lockNs+=times[4];copyNs+=times[5];postNs+=times[6];bytes+=times[7];shmFrames+=(times[8]&1);lastFrame=System.nanoTime();}
                            if(System.nanoTime()-lastReport>5_000_000_000L){report(null);lastReport=System.nanoTime();}
                            final int w=(int)times[0],h=(int)times[1];if(w!=reportedWidth||h!=reportedHeight){reportedWidth=w;reportedHeight=h;post(()->events.size(w,h));}
                        }
                    }
                }
            }catch(Exception|UnsatisfiedLinkError failure){report(failure.getMessage());if(worker==Thread.currentThread()&&!closed){Thread failed=Thread.currentThread();post(()->{if(worker==failed&&!closed)events.failed(failure.getMessage());});}}finally{report(null);}
        },"LSB native Surface");worker=next;lastWorker=next;next.start();
    }
    // Called only by the Surface worker: serialization and I/O stay off the UI.
    private void report(String failure){
        if(!events.current())return;
        try{
            JSONObject window=sample(System.nanoTime());if(failure!=null)window.put("failure",failure);
            history.addLast(window);while(history.size()>180)history.removeFirst();
            JSONObject result=new JSONObject().put("format",1).put("session_id",sessionId)
                .put("presentation","shared_native_surface").put("measurement","Capture and Surface submission, not game FPS or GPU presentation")
                .put("latest",window).put("windows",new JSONArray(history));
            File temporary=new File(reportFile.getPath()+".new");
            try(FileOutputStream out=new FileOutputStream(temporary)){out.write(result.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
            if(events.current())java.nio.file.Files.move(temporary.toPath(),reportFile.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception ignored){}
    }
    private int reportedWidth,reportedHeight;
    private synchronized void stopWorker(){worker=null;LocalSocket local=socket;socket=null;if(local!=null)try{local.shutdownInput();local.shutdownOutput();local.close();}catch(IOException ignored){}}
    void close(){closed=true;stopWorker();}
    private synchronized JSONObject sample(long now)throws org.json.JSONException {
        double seconds=(now-since)/1e9;if(seconds<=0)return new JSONObject();
        JSONObject result=new JSONObject().put("window_seconds",seconds).put("surface_posts_per_second",frames/seconds)
            .put("capture_ms_per_frame",frames==0?0:captureNs/1e6/frames).put("request_receive_ms_per_frame",frames==0?0:readNs/1e6/frames)
            .put("surface_lock_ms_per_frame",frames==0?0:lockNs/1e6/frames).put("native_copy_ms_per_frame",frames==0?0:copyNs/1e6/frames)
            .put("surface_post_ms_per_frame",frames==0?0:postNs/1e6/frames).put("mapped_pixel_bytes_per_second",bytes/seconds).put("socket_pixel_bytes",0).put("window_start_seconds",(since-connectionStart)/1e9)
            .put("unchanged_responses",unchanged).put("shm_frames",shmFrames).put("frames",frames).put("frame_width",width).put("frame_height",height)
            .put("last_update_age_seconds",lastFrame==0?-1:Math.max(0,now-lastFrame)/1e9);
        since=now;frames=unchanged=readNs=captureNs=lockNs=copyNs=postNs=bytes=shmFrames=0;return result;
    }
}
