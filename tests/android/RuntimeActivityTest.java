package io.github.russianranger.lsb;

import android.os.Looper;
import android.view.InputDevice;
import android.view.KeyEvent;
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
}
