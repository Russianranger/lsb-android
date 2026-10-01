package io.github.russianranger.lsb;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativePerformanceTest {
    private static final long[] TIMES={1280,720,4_000_000,30_000_000,200_000,400_000,600_000,3686400,1};
    private File file()throws Exception{return new File(Files.createTempDirectory("native-performance").toFile(),"report.json");}
    private JSONObject read(File file)throws Exception{return new JSONObject(new String(Files.readAllBytes(file.toPath()),"UTF-8"));}

    @Test public void blockedDiskCannotBlockFramesAndPendingFinalReportSurvives()throws Exception {
        File file=file();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        NativePerformance log=new NativePerformance(file,(f,s)->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));ClientRuntime.write(f,s);});
        ExecutorService frameWorker=Executors.newSingleThreadExecutor();log.reset("run");NativePerformance.Stream stream=log.open("run");
        NativeFrameStats stats=new NativeFrameStats(1);
        try{
            stream.record(stats.sample(5_000_000_001L,null,false));assertTrue(entered.await(5,TimeUnit.SECONDS));
            // This executes the producer path while the actual sink is blocked.
            frameWorker.submit(()->{
                for(int i=1;i<=100;i++){
                    long end=5_000_000_001L+i*200_000_000L;
                    stats.frame(end-30_000_000,end,0,TIMES);
                    stream.record(stats.sample(end,i==100?"fixture failure":null,i==100));
                }
            }).get(1,TimeUnit.SECONDS);
            assertEquals(1,log.writer.getQueue().size());assertFalse(log.flush(10));
            release.countDown();assertTrue(log.flush(5000));
            JSONObject report=read(file),last=report.getJSONObject("latest");
            assertEquals(100,last.getLong("connection_frames"));assertEquals(100,last.getLong("connection_shm_frames"));
            assertTrue(last.getBoolean("terminal"));assertEquals("fixture failure",last.getString("failure"));
            assertTrue(report.getLong("dropped_windows")>0);assertEquals(2,report.getJSONArray("windows").length());
            assertTrue(report.getDouble("previous_report_write_ms")>0);
        }finally{release.countDown();frameWorker.shutdownNow();log.writer.shutdownNow();}
    }

    @Test public void oldSessionAndReplacedViewerCannotOverwriteCurrentReport()throws Exception {
        File file=file();NativePerformance log=new NativePerformance(file);log.reset("old");NativePerformance.Stream old=log.open("old");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            log.writer.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));NativeFrameStats stats=new NativeFrameStats(1);
            old.record(stats.sample(2,null,true));log.reset("new");NativePerformance.Stream first=log.open("new"),current=log.open("new");
            current.record(stats.sample(3,"current",true));
            old.record(stats.sample(4,"stale session",true));first.record(stats.sample(5,"stale viewer",true));
            log.open("old").record(stats.sample(6,"late old open",true));
            release.countDown();assertTrue(log.flush(5000));
            assertEquals("new",read(file).getString("session_id"));assertEquals("current",read(file).getJSONObject("latest").getString("failure"));
        }finally{release.countDown();log.writer.shutdownNow();}
    }

    @Test public void frameGapsKeepIdleContextAndSnapshotsStayImmutable()throws Exception {
        NativeFrameStats stats=new NativeFrameStats(1_000_000_000L);
        stats.frame(1_010_000_000L,1_040_000_000L,0,TIMES);
        stats.frame(1_050_000_000L,1_070_000_000L,1,TIMES);
        stats.frame(1_170_000_000L,1_200_000_000L,0,TIMES);
        stats.enqueued(900_000);
        NativeFrameStats.Sample saved=stats.sample(1_210_000_000L,null,false);
        for(int i=0;i<NativeFrameStats.EVENTS+4;i++){
            long end=1_400_000_000L+i*200_000_000L;stats.frame(end-30_000_000L,end,0,TIMES);
        }
        JSONObject first=saved.json(),next=stats.sample(10_000_000_000L,null,false).json();
        assertEquals(2,first.getInt("frames"));assertEquals(2,first.getInt("shm_frames"));
        assertEquals(160,first.getDouble("max_post_gap_ms"),.0001);assertEquals(100,first.getDouble("max_loop_gap_ms"),.0001);
        assertEquals(.9,first.getDouble("max_report_enqueue_ms"),.0001);
        JSONObject gap=first.getJSONArray("gap_events").getJSONObject(0);
        assertEquals(1,gap.getInt("unchanged_between_posts"));assertEquals(.2,gap.getDouble("posted_at_seconds"),.0001);
        assertEquals(4,gap.getDouble("capture_ms"),.0001);assertEquals(30,gap.getDouble("request_receive_ms"),.0001);
        assertEquals(NativeFrameStats.EVENTS,next.getJSONArray("gap_events").length());assertEquals(4,next.getInt("gap_events_dropped"));
        stats.resume();stats.frame(20_000_000_000L,20_030_000_000L,0,TIMES);
        JSONObject resumed=stats.sample(20_040_000_000L,null,true).json();
        assertEquals(0,resumed.getDouble("max_post_gap_ms"),0);assertEquals(0,resumed.getDouble("max_loop_gap_ms"),0);
        assertEquals(2,saved.json().getInt("frames"));
    }

    @Test public void worstCaseEventHistoryFitsDiagnosticExportAndKeepsFinalSample()throws Exception {
        File file=file();NativePerformance log=new NativePerformance(file);log.reset("run");NativePerformance.Stream stream=log.open("run");
        NativeFrameStats stats=new NativeFrameStats(1);long clock=1;
        try{
            for(int i=0;i<NativePerformance.LIMIT+2;i++){
                for(int n=0;n<NativeFrameStats.EVENTS+1;n++){clock+=200_000_000;stats.frame(clock-30_000_000,clock,0,TIMES);}
                stream.record(stats.sample(clock,null,i==NativePerformance.LIMIT+1));assertTrue(log.flush(5000));
            }
            JSONObject report=read(file);assertEquals(NativePerformance.LIMIT,report.getJSONArray("windows").length());
            assertTrue(report.getJSONObject("latest").getBoolean("terminal"));assertTrue("Export must retain complete JSON",file.length()<1_048_576);
        }finally{log.writer.shutdownNow();}
    }
}
