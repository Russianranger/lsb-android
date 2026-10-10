package io.github.russianranger.lsb;

import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Exercises the shared display used by both Launch FFXI and controller setup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class RuntimeActivityTest {
    private ActivityController<RuntimeActivity> activity;
    private ClientRuntime runtime;

    @Before public void prepare() throws Exception {
        field(ClientRuntime.class, "instance").set(null, null);
        runtime=ClientRuntime.get(RuntimeEnvironment.getApplication());
        // Hold preparation before it publishes a session. No Wine process or game data.
        runtime.starting=true;
    }
    @After public void cleanup() throws Exception {
        if(activity!=null)activity.pause().stop().destroy();
        runtime.starting=false;
        RuntimeEnvironment.getApplication().getSharedPreferences("runtime",0).edit().remove("fullscreen").commit();
        field(ClientRuntime.class, "instance").set(null, null);
    }
    private static Field field(Class<?> type,String name)throws Exception {
        Field f=type.getDeclaredField(name);f.setAccessible(true);return f;
    }
    private void open() {
        activity=Robolectric.buildActivity(RuntimeActivity.class).setup().visible();
        activity.windowFocusChanged(true);
    }
    private static class OwnedProcess extends Process {
        boolean alive=true,destroyed;
        public java.io.OutputStream getOutputStream(){return new java.io.ByteArrayOutputStream();}
        public java.io.InputStream getInputStream(){return new java.io.ByteArrayInputStream(new byte[0]);}
        public java.io.InputStream getErrorStream(){return new java.io.ByteArrayInputStream(new byte[0]);}
        public int waitFor(){return 0;}
        public boolean waitFor(long timeout,TimeUnit unit){return !alive;}
        public int exitValue(){if(alive)throw new IllegalThreadStateException();return 0;}
        public boolean isAlive(){return alive;}
        public void destroy(){alive=false;destroyed=true;}
        public Process destroyForcibly(){destroy();return this;}
    }
    private RfbConnection nativeViewer(String id,String trial,java.io.ByteArrayOutputStream sent)throws Exception{
        session(id);ClientRuntime.write(new File(runtime.run,"request.json"),new org.json.JSONObject().put("session_id",id).put("action","launch").put("performance_trial",trial).toString());
        Files.deleteIfExists(new File(runtime.logs,"native-trial-display.json").toPath());open();RuntimeActivity viewer=activity.get();
        Object screen=field(RuntimeActivity.class,"screen").get(viewer);field(screen.getClass(),"nativeMode").setBoolean(screen,true);
        RfbConnection c=new RfbConnection(new java.io.ByteArrayInputStream(new byte[0]),sent,(RfbConnection.Screen)screen,true,true);c.width=1280;c.height=720;((RfbConnection.Screen)screen).resize(c.width,c.height);
        field(RuntimeActivity.class,"connection").set(viewer,c);
        Class<?> type=Class.forName("io.github.russianranger.lsb.RuntimeActivity$DisplaySession");
        java.lang.reflect.Constructor<?> constructor=type.getDeclaredConstructor(RfbConnection.class,String.class,boolean.class,int.class,String.class);constructor.setAccessible(true);
        field(RuntimeActivity.class,"displaySession").set(viewer,constructor.newInstance(c,id,true,60,runtime.captureTrialForSession(id)));
        return c;
    }
    private void nativeFailure(RfbConnection c,String id,int generation)throws Exception{
        java.lang.reflect.Method method=RuntimeActivity.class.getDeclaredMethod("fallbackNative",RfbConnection.class,String.class,int.class,String.class);method.setAccessible(true);
        method.invoke(activity.get(),c,id,generation,"fixture delivery failure");
    }
    private ExecutorService input()throws Exception{return (ExecutorService)field(RuntimeActivity.class,"input").get(activity.get());}
    private boolean nativeStop()throws Exception{return ((Future<Boolean>)field(RuntimeActivity.class,"nativeStop").get(activity.get())).get(5,TimeUnit.SECONDS);}
    private void tick(){shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));}
    private void menuItem(int index)throws Exception{
        android.widget.FrameLayout root=(android.widget.FrameLayout)field(RuntimeActivity.class,"layout").get(activity.get());
        for(int i=0;i<root.getChildCount();i++)if("Game menu".contentEquals(root.getChildAt(i).getContentDescription()==null?"":root.getChildAt(i).getContentDescription()))root.getChildAt(i).performClick();
        android.app.AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);android.widget.ListView list=dialog.getListView();assertNotNull(list);list.performItemClick(null,index,index);shadowOf(Looper.getMainLooper()).idle();
    }
    @Test public void fullscreenMenuPersistsWithoutReconnectingAndOverlayUpdatesOnlyWhenChanged()throws Exception{
        android.content.SharedPreferences prefs=RuntimeEnvironment.getApplication().getSharedPreferences("runtime",0);
        prefs.edit().putBoolean("fullscreen",false).putString("display_profile","windowed720").commit();open();
        RuntimeActivity viewer=activity.get();android.widget.TextView status=(android.widget.TextView)field(RuntimeActivity.class,"status").get(viewer);
        int generation=field(RuntimeActivity.class,"connectionGeneration").getInt(viewer);int[] writes={0};
        status.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){writes[0]++;}public void afterTextChanged(android.text.Editable e){}});
        Runnable refresh=(Runnable)field(RuntimeActivity.class,"refresh").get(viewer);
        refresh.run();refresh.run();assertEquals(0,writes[0]);
        runtime.status="Changed status";refresh.run();assertEquals(1,writes[0]);
        menuItem(4);assertTrue(Fullscreen.enabled(viewer));assertEquals(android.view.View.GONE,status.getVisibility());
        assertEquals(generation,field(RuntimeActivity.class,"connectionGeneration").getInt(viewer));
        assertEquals("windowed720",prefs.getString("display_profile",""));
        runtime.status="Latest hidden status";refresh.run();assertEquals(1,writes[0]);
        activity.windowFocusChanged(false);activity.windowFocusChanged(true);assertEquals(android.view.View.GONE,status.getVisibility());
        menuItem(5);android.widget.TextView message=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().findViewById(android.R.id.message);assertNotNull(message);assertEquals("Latest hidden status",message.getText().toString());
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();
        activity.pause().stop().destroy();activity=null;open();
        assertTrue(Fullscreen.enabled(activity.get()));status=(android.widget.TextView)field(RuntimeActivity.class,"status").get(activity.get());assertEquals(android.view.View.GONE,status.getVisibility());
        menuItem(4);assertFalse(Fullscreen.enabled(activity.get()));assertEquals(android.view.View.VISIBLE,status.getVisibility());assertEquals("Latest hidden status",status.getText().toString());
    }
    private File session(String id)throws Exception {
        File pad=runtime.gamepadState();
        Files.deleteIfExists(pad.toPath());
        try(RandomAccessFile file=new RandomAccessFile(pad,"rw")){file.setLength(64);}
        field(ClientRuntime.class,"sessionId").set(runtime,id);
        return pad;
    }
    private ByteBuffer state(File path)throws Exception {
        return ByteBuffer.wrap(Files.readAllBytes(path.toPath())).order(ByteOrder.LITTLE_ENDIAN);
    }
    private void button(int action) {
        assertTrue(activity.get().dispatchKeyEvent(new KeyEvent(0,0,action,KeyEvent.KEYCODE_BUTTON_A,0,0,1,0,0,InputDevice.SOURCE_GAMEPAD)));
    }
    private void sticks(float x,float y,float z,float rz) {
        MotionEvent.PointerProperties property=new MotionEvent.PointerProperties();property.id=0;
        MotionEvent.PointerCoords axes=new MotionEvent.PointerCoords();
        axes.setAxisValue(MotionEvent.AXIS_X,x);axes.setAxisValue(MotionEvent.AXIS_Y,y);
        axes.setAxisValue(MotionEvent.AXIS_Z,z);axes.setAxisValue(MotionEvent.AXIS_RZ,rz);
        MotionEvent event=MotionEvent.obtain(0,0,MotionEvent.ACTION_MOVE,1,new MotionEvent.PointerProperties[]{property},
            new MotionEvent.PointerCoords[]{axes},0,0,1,1,999,0,InputDevice.SOURCE_JOYSTICK,0);
        try{assertTrue(activity.get().dispatchGenericMotionEvent(event));}finally{event.recycle();}
    }

    @Test public void graphicsProbeReceivesClientGraphicsPreferences()throws Exception {
        android.content.SharedPreferences preferences=RuntimeEnvironment.getApplication().getSharedPreferences("runtime",0);
        preferences.edit().putInt("display_fps",60).putBoolean("native_surface",true)
            .putBoolean("shm_upload",true).putBoolean("dxvk_271",true)
            .putBoolean("turnip_sysmem",true).putBoolean("dxvk_two_compilers",true).putBoolean("dxvk_staged_buffers",true).putBoolean("borderless",false).apply();
        try {
            org.json.JSONObject probe=new org.json.JSONObject().put("action","probe");
            org.json.JSONObject launch=new org.json.JSONObject().put("action","launch");
            runtime.applyGraphicsSettings(probe);runtime.applyGraphicsSettings(launch);
            for(String key:new String[]{"display_fps","native_surface","shm_upload","dxvk_version","turnip_sysmem","dxvk_two_compilers","dxvk_staged_buffers","borderless","dxvk_hud","dxvk_diagnostics"})
                assertEquals(key,launch.get(key),probe.get(key));
            assertEquals(60,probe.getInt("display_fps"));assertEquals("2.7.1",probe.getString("dxvk_version"));
            assertTrue(probe.getBoolean("shm_upload"));assertTrue(probe.getBoolean("native_surface"));
            assertTrue(probe.getBoolean("dxvk_staged_buffers"));assertFalse(probe.getBoolean("borderless"));
        } finally {preferences.edit().clear().commit();}
    }

    @Test public void displayRateTracksActiveLaunchInsteadOfNextLaunchPreference()throws Exception {
        File request=new File(runtime.run,"request.json");Files.deleteIfExists(request.toPath());
        assertEquals(30,runtime.activeDisplayFps());
        ClientRuntime.write(request,"{\"display_fps\":30}");
        runtime.context.getSharedPreferences("runtime",0).edit().putInt("display_fps",60).commit();
        assertEquals(30,runtime.activeDisplayFps());
        ClientRuntime.write(request,"{\"display_fps\":60}");
        runtime.context.getSharedPreferences("runtime",0).edit().putInt("display_fps",30).commit();
        assertEquals(60,runtime.activeDisplayFps());
        ClientRuntime.write(request,"{}");assertEquals(30,runtime.activeDisplayFps());
    }

    @Test public void bothStickDirectionsAndRightVerticalReachMappedState()throws Exception {
        RuntimeEnvironment.getApplication().getSharedPreferences("controller",0).edit().putInt("deadzone",0).commit();
        File pad=session("axes");open();tick();
        sticks(1,-1,.5f,-.5f);tick();ByteBuffer first=state(pad);
        assertEquals(32767,first.getShort(16));assertEquals(-32767,first.getShort(18));
        assertEquals(16384,first.getShort(20));assertEquals(-16383,first.getShort(22));
        sticks(-1,1,-.5f,.5f);tick();ByteBuffer second=state(pad);
        assertEquals(-32767,second.getShort(16));assertEquals(32767,second.getShort(18));
        assertEquals(-16383,second.getShort(20));assertEquals(16384,second.getShort(22));
        sticks(0,0,0,0);tick();for(int i=0;i<4;i++)assertEquals(0,state(pad).getShort(16+i*2));
    }

    @Test public void displayWaitsThroughRepeatedTicksBeforeFirstSession()throws Exception {
        assertNull(runtime.sessionKey());
        open();tick();tick();
        assertFalse(runtime.gamepadState().exists());
        File pad=session("first");tick();
        assertEquals(0x4c534247,state(pad).getInt(0));
        assertTrue(state(pad).getInt(4)>0);
        button(KeyEvent.ACTION_DOWN);tick();assertEquals(1,state(pad).getInt(8));
        button(KeyEvent.ACTION_UP);tick();assertEquals(0,state(pad).getInt(8));
    }

    @Test public void sessionChangeAndFocusLossReleaseHeldControls()throws Exception {
        File pad=session("first");open();tick();button(KeyEvent.ACTION_DOWN);tick();
        assertEquals(1,state(pad).getInt(8));
        activity.windowFocusChanged(false);tick();assertEquals(0,state(pad).getInt(8));
        activity.windowFocusChanged(true);button(KeyEvent.ACTION_DOWN);tick();
        assertEquals(1,state(pad).getInt(8));
        pad=session("second");tick();assertEquals(0,state(pad).getInt(8));
        button(KeyEvent.ACTION_DOWN);tick();assertEquals(1,state(pad).getInt(8));
        activity.pause();assertEquals(0,state(pad).getInt(8));
        activity.resume();tick();assertEquals(0,state(pad).getInt(8));
    }

    @Test public void focusChangesBeforeSessionDoNotMapPreviousRunFile()throws Exception {
        File pad=session(null);
        byte[] before=Files.readAllBytes(pad.toPath());
        open();tick();activity.windowFocusChanged(false);tick();
        assertArrayEquals(before,Files.readAllBytes(pad.toPath()));
    }

    @Test public void nativeFailureRestoresBitmapAndFullRfbRefreshOnlyForCurrentViewer()throws Exception {
        open();RuntimeActivity viewer=activity.get();
        Object screen=field(RuntimeActivity.class,"screen").get(viewer);
        Field nativeMode=field(screen.getClass(),"nativeMode");nativeMode.setBoolean(screen,true);
        java.io.ByteArrayOutputStream sent=new java.io.ByteArrayOutputStream();
        RfbConnection c=new RfbConnection(new java.io.ByteArrayInputStream(new byte[0]),sent,(RfbConnection.Screen)screen,true,true);
        c.width=1280;c.height=720;((RfbConnection.Screen)screen).resize(c.width,c.height);
        assertNull(field(screen.getClass(),"bitmap").get(screen));
        field(RuntimeActivity.class,"connection").set(viewer,c);
        int generation=field(RuntimeActivity.class,"connectionGeneration").getInt(viewer);
        java.lang.reflect.Method fallback=RuntimeActivity.class.getDeclaredMethod("fallbackNative",RfbConnection.class,int.class,String.class);fallback.setAccessible(true);
        fallback.invoke(viewer,c,generation-1,"stale worker");assertTrue(nativeMode.getBoolean(screen));assertEquals(0,sent.size());
        fallback.invoke(viewer,c,generation,"fixture error");
        ((java.util.concurrent.ExecutorService)field(RuntimeActivity.class,"input").get(viewer)).submit(()->{}).get(5,java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(nativeMode.getBoolean(screen));assertNotNull(field(screen.getClass(),"bitmap").get(screen));
        assertEquals(10,sent.size());assertEquals(3,sent.toByteArray()[0]);assertEquals(0,sent.toByteArray()[1]);
        field(RuntimeActivity.class,"viewing").setBoolean(viewer,false);sent.reset();
        fallback.invoke(viewer,c,generation,"paused worker");assertEquals(0,sent.size());
    }
    @Test public void captureTrialsStopOwnedClientInsteadOfFallingBackDespiteChangedNextLaunchChoices()throws Exception{
        for(String trial:new String[]{"capture90","capture_deadline"}){
            String id=java.util.UUID.randomUUID().toString();java.io.ByteArrayOutputStream sent=new java.io.ByteArrayOutputStream();RfbConnection c=nativeViewer(id,trial,sent);
            OwnedProcess process=new OwnedProcess();field(ClientRuntime.class,"process").set(runtime,process);field(ClientRuntime.class,"stopRequested").setBoolean(runtime,false);
            runtime.context.getSharedPreferences("runtime",0).edit().putString("performance_trial","none").commit();
            ClientRuntime.write(new File(runtime.run,"request.json"),new org.json.JSONObject().put("session_id",id).put("action","launch").put("performance_trial","none").toString());
            RuntimeActivity viewer=activity.get();Object screen=field(RuntimeActivity.class,"screen").get(viewer);int generation=field(RuntimeActivity.class,"connectionGeneration").getInt(viewer);
            nativeFailure(c,id,generation-1);assertNull(field(RuntimeActivity.class,"nativeStop").get(viewer));assertFalse(new File(runtime.logs,"native-trial-display.json").exists());
            ClientRuntime.write(new File(runtime.run,"status.json"),new org.json.JSONObject().put("session_id",java.util.UUID.randomUUID().toString()).put("phase","completed").toString());
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            input().execute(()->{entered.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});assertTrue(entered.await(5,TimeUnit.SECONDS));
            try{nativeFailure(c,id,generation);assertTrue("Stopping cannot wait behind blocked RFB input",nativeStop());assertTrue(process.destroyed);}
            finally{release.countDown();}
            input().submit(()->{}).get(5,TimeUnit.SECONDS);
            assertFalse(field(RuntimeActivity.class,"viewing").getBoolean(viewer));assertTrue(field(screen.getClass(),"nativeMode").getBoolean(screen));assertNull(field(screen.getClass(),"bitmap").get(screen));
            assertTrue(process.destroyed);assertFalse(process.isAlive());assertTrue(field(ClientRuntime.class,"stopRequested").getBoolean(runtime));assertTrue(runtime.operationFailed);
            assertEquals(ClientRuntime.captureTrialFailure(trial),runtime.launchError);assertEquals(runtime.launchError,runtime.status);
            // Input cleanup still sends a neutral pointer; no full RFB frame request is sent.
            assertEquals(6,sent.size());assertEquals(5,sent.toByteArray()[0]);
            org.json.JSONObject receipt=new org.json.JSONObject(ClientRuntime.read(new File(runtime.logs,"native-trial-display.json"),4096));
            assertEquals(id,receipt.getString("session_id"));assertEquals(trial,receipt.getString("performance_trial"));assertFalse(receipt.getBoolean("fallback_allowed"));assertEquals("android_native_presentation",receipt.getString("stage"));
            org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();activity.pause().stop().destroy();activity=null;field(ClientRuntime.class,"process").set(runtime,null);
        }
    }
    @Test public void baselineAndRenderQueueTrialKeepNativeFallbackAfterPreferencesChange()throws Exception{
        for(String trial:new String[]{"none","frame_latency"}){
            String id=java.util.UUID.randomUUID().toString();java.io.ByteArrayOutputStream sent=new java.io.ByteArrayOutputStream();RfbConnection c=nativeViewer(id,trial,sent);
            field(ClientRuntime.class,"stopRequested").setBoolean(runtime,false);runtime.context.getSharedPreferences("runtime",0).edit().putString("performance_trial","capture90").commit();
            int generation=field(RuntimeActivity.class,"connectionGeneration").getInt(activity.get());nativeFailure(c,id,generation);input().submit(()->{}).get(5,TimeUnit.SECONDS);
            Object screen=field(RuntimeActivity.class,"screen").get(activity.get());assertFalse(field(screen.getClass(),"nativeMode").getBoolean(screen));assertNotNull(field(screen.getClass(),"bitmap").get(screen));
            assertEquals(10,sent.size());assertEquals(3,sent.toByteArray()[0]);assertFalse(field(ClientRuntime.class,"stopRequested").getBoolean(runtime));assertFalse(new File(runtime.logs,"native-trial-display.json").exists());
            activity.pause().stop().destroy();activity=null;
        }
    }
    @Test public void staleCaptureViewerAndQueuedStopCannotAffectReplacementSession()throws Exception{
        String id=java.util.UUID.randomUUID().toString(),next=java.util.UUID.randomUUID().toString();java.io.ByteArrayOutputStream sent=new java.io.ByteArrayOutputStream();RfbConnection c=nativeViewer(id,"capture90",sent);
        RuntimeActivity viewer=activity.get();int generation=field(RuntimeActivity.class,"connectionGeneration").getInt(viewer);field(ClientRuntime.class,"sessionId").set(runtime,next);
        nativeFailure(c,id,generation);assertTrue(field(RuntimeActivity.class,"viewing").getBoolean(viewer));assertEquals(0,sent.size());assertFalse(new File(runtime.logs,"native-trial-display.json").exists());
        field(ClientRuntime.class,"sessionId").set(runtime,id);
        try{
            OwnedProcess replacement=new OwnedProcess();
            synchronized(runtime){
                nativeFailure(c,id,generation);field(ClientRuntime.class,"sessionId").set(runtime,next);runtime.launchError="replacement session";
                field(ClientRuntime.class,"stopRequested").setBoolean(runtime,false);field(ClientRuntime.class,"process").set(runtime,replacement);
            }
            assertFalse(nativeStop());input().submit(()->{}).get(5,TimeUnit.SECONDS);
            assertTrue(replacement.isAlive());assertFalse(replacement.destroyed);assertFalse(field(ClientRuntime.class,"stopRequested").getBoolean(runtime));assertEquals("replacement session",runtime.launchError);
            assertFalse(new File(runtime.logs,"native-trial-display.json").exists());field(ClientRuntime.class,"process").set(runtime,null);
        }finally{org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();}
    }
    @Test public void normalClientExitAndExplicitStopDoNotMislabelCaptureTrialsAsNativeFailures()throws Exception{
        for(String phase:new String[]{"completed","stopped","cancelled","error","active-stop"}){
            String id=java.util.UUID.randomUUID().toString();java.io.ByteArrayOutputStream sent=new java.io.ByteArrayOutputStream();RfbConnection c=nativeViewer(id,"capture90",sent);
            OwnedProcess process=new OwnedProcess();field(ClientRuntime.class,"process").set(runtime,process);field(ClientRuntime.class,"stopRequested").setBoolean(runtime,"active-stop".equals(phase));
            String original="error".equals(phase)?"Original loader failure":"";runtime.launchError=original;
            ClientRuntime.write(new File(runtime.run,"status.json"),new org.json.JSONObject().put("session_id",id).put("phase",phase).toString());
            RuntimeActivity viewer=activity.get();int generation=field(RuntimeActivity.class,"connectionGeneration").getInt(viewer);nativeFailure(c,id,generation);
            Object display=field(RuntimeActivity.class,"displaySession").get(viewer);
            java.lang.reflect.Method failed=RuntimeActivity.class.getDeclaredMethod("displayConnectionFailed",display.getClass(),int.class,String.class);failed.setAccessible(true);
            failed.invoke(viewer,display,generation,"expected shutdown EOF");assertEquals("",field(RuntimeActivity.class,"displayError").get(viewer));
            assertTrue(field(RuntimeActivity.class,"viewing").getBoolean(viewer));assertNull(field(RuntimeActivity.class,"nativeStop").get(viewer));
            assertTrue(process.isAlive());assertFalse(process.destroyed);assertEquals(original,runtime.launchError);assertEquals(0,sent.size());assertFalse(new File(runtime.logs,"native-trial-display.json").exists());
            activity.pause().stop().destroy();activity=null;field(ClientRuntime.class,"process").set(runtime,null);
        }
    }
    @Test public void activeCaptureControlConnectionLossStillStopsAfterReaderClearsItsFields()throws Exception{
        String id=java.util.UUID.randomUUID().toString();java.io.ByteArrayOutputStream sent=new java.io.ByteArrayOutputStream();nativeViewer(id,"capture_deadline",sent);
        OwnedProcess process=new OwnedProcess();field(ClientRuntime.class,"process").set(runtime,process);field(ClientRuntime.class,"stopRequested").setBoolean(runtime,false);
        ClientRuntime.write(new File(runtime.run,"status.json"),new org.json.JSONObject().put("session_id",id).put("phase","client_running").toString());
        RuntimeActivity viewer=activity.get();Object display=field(RuntimeActivity.class,"displaySession").get(viewer);int generation=field(RuntimeActivity.class,"connectionGeneration").getInt(viewer);
        field(RuntimeActivity.class,"connection").set(viewer,null);field(RuntimeActivity.class,"displaySession").set(viewer,null);
        java.lang.reflect.Method failed=RuntimeActivity.class.getDeclaredMethod("displayConnectionFailed",display.getClass(),int.class,String.class);failed.setAccessible(true);
        failed.invoke(viewer,display,generation,"fixture RFB control EOF");assertTrue(nativeStop());
        assertTrue(process.destroyed);assertEquals(ClientRuntime.captureTrialFailure("capture_deadline"),runtime.launchError);assertFalse(field(RuntimeActivity.class,"viewing").getBoolean(viewer));
        org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog().dismiss();field(ClientRuntime.class,"process").set(runtime,null);
    }
    @Test public void pausedCaptureViewerDoesNotStopTheClientOnAnOldReaderCallback()throws Exception{
        String id=java.util.UUID.randomUUID().toString();java.io.ByteArrayOutputStream sent=new java.io.ByteArrayOutputStream();RfbConnection c=nativeViewer(id,"capture90",sent);
        OwnedProcess process=new OwnedProcess();field(ClientRuntime.class,"process").set(runtime,process);runtime.launchError="";
        RuntimeActivity viewer=activity.get();int generation=field(RuntimeActivity.class,"connectionGeneration").getInt(viewer);activity.pause();nativeFailure(c,id,generation);
        assertNull(field(RuntimeActivity.class,"nativeStop").get(viewer));assertTrue(process.isAlive());assertEquals("",runtime.launchError);assertFalse(new File(runtime.logs,"native-trial-display.json").exists());
        field(ClientRuntime.class,"process").set(runtime,null);
    }
}
