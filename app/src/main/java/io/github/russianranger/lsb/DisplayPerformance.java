package io.github.russianranger.lsb;

import java.io.File;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import org.json.*;

/** Bounded, session-scoped diagnostics. Serialization and disk I/O stay off the UI thread. */
final class DisplayPerformance {
    static final int LIMIT=180; // About 15 minutes at the usual five-second cadence.
    final ThreadPoolExecutor writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"lsb-display-stats");t.setDaemon(true);return t;},
        new ThreadPoolExecutor.DiscardOldestPolicy());
    private final File path;
    private String session;
    private final ArrayDeque<JSONObject> history=new ArrayDeque<>();
    private long windows;
    DisplayPerformance(File path){this.path=path;}
    // Caller holds this same monitor while rotating logs and publishing the new session.
    synchronized void reset(String id){session=id;history.clear();windows=0;}
    void record(String id,ClientFrameStats.Sample sample,int width,int height,boolean fast,int cap,long allocations,long allocatedBytes){
        if(id==null||sample==null)return;
        writer.execute(()->append(id,sample,width,height,fast,cap,allocations,allocatedBytes));
    }
    private synchronized void append(String id,ClientFrameStats.Sample sample,int width,int height,boolean fast,int cap,long allocations,long allocatedBytes){
        if(!id.equals(session))return; // A closing display must not overwrite a new launch.
        try{
            double[] s=sample.values;
            JSONObject data=new JSONObject().put("connection_id",Long.toString(sample.connection))
                .put("window_start_seconds",s[10]).put("window_seconds",s[0])
                .put("display_updates_per_second",s[1]).put("unique_draws_per_second",s[2])
                .put("receive_ms",s[3]).put("decode_ms",s[4]).put("draw_ms",s[5])
                .put("pixels_per_second",s[6]).put("raw_bytes_per_second",s[6]*(fast?2:4))
                .put("last_update_age_seconds",s[7]).put("conversion_ms",s[8]).put("bitmap_apply_ms",s[9])
                .put("updates",s[11]).put("unique_draws",s[12]).put("max_update_gap_ms",s[13])
                .put("max_decode_ms",s[14]).put("max_draw_ms",s[15])
                .put("update_gaps_over_50ms",s[16]).put("update_gaps_over_100ms",s[17])
                .put("connection_updates",s[18]).put("connection_unique_draws",s[19]).put("connection_pixels",s[20])
                .put("width",width).put("height",height).put("fast_display",fast).put("display_cap",cap)
                .put("staging_bitmap_allocations",allocations).put("staging_allocated_bytes",allocatedBytes);
            if(history.size()==LIMIT)history.removeFirst();history.addLast(data);windows++;
            JSONObject report=new JSONObject().put("format",2).put("session_id",session)
                .put("measurement","Display transport and Android draw submission; not game FPS or GPU presentation. Idle scenes can have long update gaps.")
                .put("window_limit",LIMIT).put("windows_recorded",windows).put("latest",data)
                .put("windows",new JSONArray(history));
            ClientRuntime.write(path,report.toString());
        }catch(Exception ignored){}
    }
}
