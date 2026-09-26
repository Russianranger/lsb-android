package io.github.russianranger.lsb;

import org.json.*;

/** Single frame-worker counters. No JSON, file I/O or per-frame allocations. */
final class NativeFrameStats {
    static final int EVENTS=32, EVENT_FIELDS=9;
    private final long start;
    private long since,lastPost,lastReturn,idleSincePost,totalFrames,totalShm;
    // frames, unchanged, capture/read/lock/copy/post ns, bytes, SHM frames,
    // max posted gap, gaps >50/100/250ms, max loop gap, max native call,
    // max capture/read/lock/copy/post, max reporting enqueue time.
    private long[] counters=new long[22],events=new long[EVENTS*EVENT_FIELDS];
    private int eventCount,eventDropped,width,height;
    NativeFrameStats(long now){start=since=now;}
    void resume(){lastPost=lastReturn=idleSincePost=0;}
    void frame(long begin,long end,int result,long[] times){
        long loop=lastReturn==0?0:Math.max(0,begin-lastReturn);lastReturn=end;
        counters[13]=Math.max(counters[13],loop);counters[14]=Math.max(counters[14],end-begin);
        if(result==1){counters[1]++;idleSincePost++;return;}
        if(result!=0)return;
        counters[0]++;totalFrames++;width=(int)times[0];height=(int)times[1];
        for(int i=0;i<5;i++){counters[2+i]+=times[2+i];counters[15+i]=Math.max(counters[15+i],times[2+i]);}
        counters[7]+=times[7];counters[8]+=times[8]&1;totalShm+=times[8]&1;
        if(lastPost!=0){
            long gap=Math.max(0,end-lastPost);counters[9]=Math.max(counters[9],gap);
            if(gap>50_000_000L)counters[10]++;
            if(gap>100_000_000L){
                counters[11]++;
                int slot=eventCount%EVENTS,at=slot*EVENT_FIELDS;
                events[at]=end-start;events[at+1]=gap;events[at+2]=idleSincePost;
                for(int i=0;i<5;i++)events[at+3+i]=times[2+i];events[at+8]=loop;
                if(eventCount>=EVENTS)eventDropped++;eventCount++;
            }
            if(gap>250_000_000L)counters[12]++;
        }
        lastPost=end;idleSincePost=0;
    }
    void enqueued(long elapsed){counters[20]=Math.max(counters[20],elapsed);}
    Sample sample(long now,String failure,boolean terminal){
        long[] captured=counters,recorded=events;
        Sample out=new Sample(start,since,now,lastPost,totalFrames,totalShm,width,height,captured,recorded,eventCount,eventDropped,failure,terminal);
        counters=new long[22];events=new long[EVENTS*EVENT_FIELDS];since=now;eventCount=eventDropped=0;return out;
    }
    static final class Sample {
        final long start,since,end,lastPost,totalFrames,totalShm;final int width,height,count,dropped;
        final long[] c,events;final String failure;final boolean terminal;
        Sample(long start,long since,long end,long lastPost,long totalFrames,long totalShm,int width,int height,long[] c,long[] events,int count,int dropped,String failure,boolean terminal){
            this.start=start;this.since=since;this.end=end;this.lastPost=lastPost;this.totalFrames=totalFrames;this.totalShm=totalShm;this.width=width;this.height=height;this.c=c;this.events=events;this.count=count;this.dropped=dropped;this.failure=failure;this.terminal=terminal;
        }
        JSONObject json()throws JSONException {
            double seconds=Math.max(1,end-since)/1e9;long frames=c[0];
            JSONObject out=new JSONObject().put("window_seconds",seconds).put("window_start_seconds",(since-start)/1e9)
                .put("frames",frames).put("unchanged_responses",c[1]).put("shm_frames",c[8])
                .put("connection_frames",totalFrames).put("connection_shm_frames",totalShm)
                .put("surface_posts_per_second",frames/seconds).put("mapped_pixel_bytes_per_second",c[7]/seconds).put("socket_pixel_bytes",0)
                .put("frame_width",width).put("frame_height",height).put("last_update_age_seconds",lastPost==0?-1:(end-lastPost)/1e9)
                .put("max_post_gap_ms",c[9]/1e6).put("post_gaps_over_50ms",c[10]).put("post_gaps_over_100ms",c[11]).put("post_gaps_over_250ms",c[12])
                .put("max_loop_gap_ms",c[13]/1e6).put("max_native_call_ms",c[14]/1e6).put("max_report_enqueue_ms",c[20]/1e6)
                .put("terminal",terminal).put("gap_events_dropped",dropped);
            String[] names={"capture","request_receive","surface_lock","native_copy","surface_post"};
            for(int i=0;i<names.length;i++)out.put(names[i]+"_ms_per_frame",frames==0?0:c[i+2]/1e6/frames).put("max_"+names[i]+"_ms",c[i+15]/1e6);
            JSONArray gaps=new JSONArray();
            for(int n=Math.max(0,count-EVENTS);n<count;n++){
                int at=(n%EVENTS)*EVENT_FIELDS;
                JSONObject gap=new JSONObject().put("posted_at_seconds",events[at]/1e9).put("gap_ms",events[at+1]/1e6)
                    .put("unchanged_between_posts",events[at+2]).put("loop_gap_ms",events[at+8]/1e6);
                for(int i=0;i<names.length;i++)gap.put(names[i]+"_ms",events[at+3+i]/1e6);gaps.put(gap);
            }
            out.put("gap_events",gaps);if(failure!=null)out.put("failure",failure);return out;
        }
    }
}
