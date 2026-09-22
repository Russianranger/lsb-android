package io.github.russianranger.lsb;

import java.io.File;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.*;

/** One bounded writer per runtime; neither sampling nor submission waits for disk. */
final class NativePerformance {
    // Detailed event windows are larger than the old averages; cap export size.
    static final int LIMIT=48;
    interface Sink {void write(File file,String data)throws Exception;}
    private final File file;private final Sink sink;
    private final AtomicLong connection=new AtomicLong(),dropped=new AtomicLong(),submitted=new AtomicLong(),completed=new AtomicLong();
    private final Object submissions=new Object();
    private volatile String session;
    final ThreadPoolExecutor writer;
    NativePerformance(File file){this(file,ClientRuntime::write);}
    NativePerformance(File file,Sink sink){
        this.file=file;this.sink=sink;
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"lsb-native-stats");t.setDaemon(true);return t;},
            (r,e)->{if(!e.isShutdown()){if(e.getQueue().poll()!=null)dropped.incrementAndGet();e.execute(r);}});
    }
    // Runtime launch holds this monitor while rotating reports and changing sessions.
    synchronized void reset(String id){synchronized(submissions){session=id;connection.incrementAndGet();writer.getQueue().clear();completed.set(submitted.get());dropped.set(0);}}
    Stream open(String id){synchronized(submissions){return new Stream(id,java.util.Objects.equals(id,session)?connection.incrementAndGet():-1);}}
    final class Stream {
        final String id;final long token;final ArrayDeque<JSONObject> history=new ArrayDeque<>();
        long windows;double lastWriteMs,maxWriteMs;
        Stream(String id,long token){this.id=id;this.token=token;}
        void record(NativeFrameStats.Sample sample){synchronized(submissions){
            if(token!=connection.get())return;long queued=System.nanoTime(),ticket=submitted.incrementAndGet();
            writer.execute(()->{try{append(this,sample,queued);}finally{completed.accumulateAndGet(ticket,Math::max);}});
        }}
    }
    private synchronized void append(Stream stream,NativeFrameStats.Sample sample,long queued){
        if(!stream.id.equals(session)||stream.token!=connection.get())return;
        long begin=System.nanoTime();
        JSONObject window=null;
        try{
            window=sample.json().put("writer_queue_wait_ms",(begin-queued)/1e6)
                .put("report_started_at_seconds",(begin-sample.start)/1e9);
            stream.history.addLast(window);while(stream.history.size()>LIMIT)stream.history.removeFirst();stream.windows++;
            JSONObject report=new JSONObject().put("format",2).put("session_id",stream.id).put("connection_id",stream.token)
                .put("presentation","shared_native_surface")
                .put("measurement","Capture and Surface submission, not game FPS. Posted gaps may include an unchanged scene; inspect unchanged_between_posts.")
                .put("window_limit",LIMIT).put("windows_recorded",stream.windows).put("dropped_windows",dropped.get())
                .put("previous_report_write_ms",stream.lastWriteMs).put("max_completed_report_write_ms",stream.maxWriteMs)
                .put("latest",window).put("windows",new JSONArray(stream.history));
            // A replacement viewer can invalidate this stream without waiting for I/O.
            if(stream.token==connection.get())sink.write(file,report.toString());
        }catch(Exception ignored){}finally{
            stream.lastWriteMs=(System.nanoTime()-begin)/1e6;stream.maxWriteMs=Math.max(stream.maxWriteMs,stream.lastWriteMs);
            // Completed duration appears in the next persisted snapshot, never as
            // a claim to have timed a write before that write has finished.
            if(window!=null)try{window.put("completed_report_write_ms",stream.lastWriteMs);}catch(JSONException ignored){}
        }
    }
    boolean flush(long milliseconds)throws InterruptedException {
        // Never enqueue a barrier: that could evict the pending terminal sample.
        long target=submitted.get(),until=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(milliseconds);
        while(completed.get()<target){
            if(System.nanoTime()>=until)return false;Thread.sleep(5);
        }
        return true;
    }
}
