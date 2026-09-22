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
