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
}
