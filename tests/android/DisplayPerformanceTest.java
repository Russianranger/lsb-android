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
public class DisplayPerformanceTest {
    private JSONObject waitFor(File file,long windows)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do{
            if(file.isFile()){JSONObject data=new JSONObject(new String(Files.readAllBytes(file.toPath()),"UTF-8"));if(data.getLong("windows_recorded")==windows)return data;}
            Thread.sleep(5);
        }while(System.nanoTime()<deadline);
        throw new AssertionError("Performance write did not finish");
    }
    private void record(DisplayPerformance log,ClientFrameStats.Sample sample){log.record("run",sample,1280,720,true,30,1,1843200);}

    @Test public void activeWindowsSurviveIdleAndWritesStayOffCaller()throws Exception {
        File file=new File(Files.createTempDirectory("display-stats").toFile(),"performance.json");
        DisplayPerformance log=new DisplayPerformance(file);log.reset("run");
        try{
            ClientFrameStats stats=new ClientFrameStats(1_000_000_000L);
            stats.stages(1_000_000,2_000_000);
            stats.received(1_100_000_000L,4_000_000,3_000_000,100);stats.drawn(2_000_000);
            stats.drawn(90_000_000); // A focus redraw must not inflate timing/counts.
            stats.received(1_250_000_000L,6_000_000,5_000_000,200);stats.drawn(4_000_000);
            synchronized(log){record(log,stats.sample(6_000_000_000L));assertFalse("Caller must not serialize/write",file.exists());}
            waitFor(file,1);record(log,stats.sample(11_000_000_000L));JSONObject report=waitFor(file,2);
            JSONArray windows=report.getJSONArray("windows");JSONObject active=windows.getJSONObject(0),idle=windows.getJSONObject(1);
            assertEquals("run",report.getString("session_id"));assertEquals(2,windows.length());
            assertEquals(.4,active.getDouble("display_updates_per_second"),.00001);
            assertEquals(2,active.getInt("unique_draws"));assertEquals(3,active.getDouble("draw_ms"),.00001);
            assertEquals(4,active.getDouble("max_draw_ms"),.00001);assertEquals(5,active.getDouble("max_decode_ms"),.00001);
            assertEquals(150,active.getDouble("max_update_gap_ms"),.00001);
            assertEquals(1,active.getInt("update_gaps_over_100ms"));assertEquals(120,active.getDouble("raw_bytes_per_second"),.00001);
            assertEquals(0,idle.getInt("updates"));assertEquals(2,idle.getInt("connection_updates"));assertEquals(2,idle.getInt("connection_unique_draws"));
            assertEquals(300,idle.getInt("connection_pixels"));assertEquals(9.75,idle.getDouble("last_update_age_seconds"),.00001);
            // A new arrival reports an idle gap, explicitly not a measured game stall.
            stats.received(11_100_000_000L,0,0,1);record(log,stats.sample(12_000_000_000L));
            assertEquals(9850,waitFor(file,3).getJSONObject("latest").getDouble("max_update_gap_ms"),.00001);
        }finally{log.writer.shutdownNow();}
    }

    @Test public void queuedOldSessionCannotOverwriteRelaunch()throws Exception {
        File file=new File(Files.createTempDirectory("display-stale").toFile(),"performance.json");
        DisplayPerformance log=new DisplayPerformance(file);log.reset("run");
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try{
            log.writer.execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));record(log,new ClientFrameStats(1).sample(5_000_000_001L));
            synchronized(log){log.reset("new-run");}release.countDown();log.writer.shutdown();
            assertTrue(log.writer.awaitTermination(5,TimeUnit.SECONDS));assertFalse(file.exists());
        }finally{release.countDown();log.writer.shutdownNow();}
    }

    @Test public void historyAndWriterQueueStayBounded()throws Exception {
        File file=new File(Files.createTempDirectory("display-bounded").toFile(),"performance.json");
        DisplayPerformance log=new DisplayPerformance(file);log.reset("run");
        ClientFrameStats stats=new ClientFrameStats(1);
        try{
            for(int i=1;i<=DisplayPerformance.LIMIT+3;i++){
                record(log,stats.sample(i*5_000_000_000L+1));waitFor(file,i);
            }
            JSONObject report=waitFor(file,DisplayPerformance.LIMIT+3);
            assertEquals(DisplayPerformance.LIMIT,report.getJSONArray("windows").length());
            assertEquals(15,report.getJSONArray("windows").getJSONObject(0).getDouble("window_start_seconds"),.00001);
            assertTrue("Fits diagnostic export limit",file.length()<1_048_576);
            assertEquals(1,log.writer.getQueue().remainingCapacity());
        }finally{log.writer.shutdownNow();}
    }
}
