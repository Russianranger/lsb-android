package io.github.russianranger.lsb;

import android.content.Context;
import android.os.*;
import io.github.russianranger.lsb.core.FilesEx;
import java.io.File;
import java.time.Duration;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class OperationProgressTest {
    private Context context;
    @Before public void before()throws Exception{context=RuntimeEnvironment.getApplication();OperationProgress.reset();FilesEx.delete(new File(context.getFilesDir(),"progress"));}
    @After public void after()throws Exception{OperationProgress.reset();FilesEx.delete(new File(context.getFilesDir(),"progress"));}
    private JSONObject saved()throws Exception{return new JSONObject(FilesEx.read(new File(context.getFilesDir(),"progress/work.json"),65536));}
    @Test public void heartbeatSavesLastStepAndRecoverableElapsedWithoutNewOutput()throws Exception{
        OperationProgress.work.begin(context,"work","Compile server");OperationProgress.work.update("Compiling xi_map");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15));
        JSONObject receipt=saved();assertEquals("Compiling xi_map",receipt.getString("step"));assertTrue(receipt.getLong("elapsed_millis")>=15000);assertTrue(receipt.getBoolean("running"));
        OperationProgress.reset();OperationProgress.load(context);
        assertFalse(OperationProgress.work.running);assertEquals("Interrupted",OperationProgress.work.outcome);assertEquals("Compiling xi_map",OperationProgress.work.step);
        assertTrue(OperationProgress.work.summary().contains("0m 15s"));assertTrue(OperationProgress.work.details().contains("before completion"));assertFalse(saved().getBoolean("running"));
        OperationProgress.reset();OperationProgress.load(context);assertEquals("Interrupted",OperationProgress.work.outcome);
    }
    @Test public void updatesAreThrottledAndRecentLinesStayBounded()throws Exception{
        OperationProgress.work.begin(context,"work","Import");for(int i=0;i<50;i++)OperationProgress.work.update("File "+i);
        assertEquals("Import",saved().getString("step"));assertEquals(12,OperationProgress.work.lines.size());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));assertEquals("File 49",saved().getString("step"));assertEquals(12,saved().getJSONArray("lines").length());
        OperationProgress.work.finish(context,"Completed","Imported");long elapsed=saved().getLong("elapsed_millis");
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));assertEquals(elapsed,saved().getLong("elapsed_millis"));
        OperationProgress.reset();OperationProgress.load(context);assertEquals("Completed",OperationProgress.work.outcome);assertEquals("Imported",OperationProgress.work.step);
    }
    @Test public void etaRequiresKnownCopyTotalAndMeasuredProgress()throws Exception{
        OperationProgress.work.begin(context,"work","Prepare client");OperationProgress.work.update("Copying working installation · 10 / 100 MiB");assertFalse(OperationProgress.work.summary().contains("remaining"));
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(10));OperationProgress.work.update("Copying working installation · 20 / 100 MiB");assertTrue(OperationProgress.work.summary().contains("about 1m 20s remaining"));
        OperationProgress.work.update("Compiling four server programs with jemalloc");assertFalse(OperationProgress.work.summary().contains("remaining"));
        OperationProgress.work.update("Imported 300 files / 100 MiB");assertFalse(OperationProgress.work.summary().contains("remaining"));
    }
}
