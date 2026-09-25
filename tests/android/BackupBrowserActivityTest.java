package io.github.russianranger.lsb;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;
import io.github.russianranger.lsb.core.FilesEx;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE, qualifiers="w920dp-h520dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BackupBrowserActivityTest {
    private Context context;
    private File session;
    private ActivityController<BackupBrowserActivity> controller;
    private ServiceController<WorkService> service;

    @Before public void fixture() throws Exception {
        context = RuntimeEnvironment.getApplication();
        ClientRuntime.resetAfterRestore(); ServerRuntime.resetAfterRestore();
        WorkService.busy = false; WorkService.message = "Ready"; WorkService.result = "";
        SessionBackup.active = false; SessionBackup.recoveryError = "";
        set(WorkService.class, null, "pending", null);
        set(WorkService.class, null, "runningThread", null);
        set(WorkService.class, null, "cancellationRequested", false);
        session = new File(MainActivity.storage(context), "session");
        FilesEx.text(new File(session, "current/client/active.dat"), "private-active-file-content");
        FilesEx.text(new File(session, "previous/client/recovery.dat"), "private-recovery-file-content");
        controller = Robolectric.buildActivity(BackupBrowserActivity.class).setup();
        ready();
    }

    @After public void cleanup() throws Exception {
        if (controller != null) controller.pause().stop().destroy();
        if (service != null) service.destroy();
        WorkService.busy = false; set(WorkService.class, null, "pending", null);
        ClientRuntime.resetAfterRestore(); ServerRuntime.resetAfterRestore();
        FilesEx.delete(session);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static void set(Class<?> type, Object object, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static Object get(Class<?> type, Object object, String name) throws Exception {
        Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private View content() { return ((ViewGroup)controller.get().findViewById(android.R.id.content)).getChildAt(0); }
    private static TextView text(View view, String prefix) {
        if (view instanceof TextView && ((TextView)view).getText().toString().startsWith(prefix)) return (TextView)view;
        if (view instanceof ViewGroup) for (int n=0; n<((ViewGroup)view).getChildCount(); n++) {
            TextView found=text(((ViewGroup)view).getChildAt(n),prefix); if(found!=null)return found;
        }
        return null;
    }
    private static ListView list(View view) {
        if (view instanceof ListView) return (ListView)view;
        if (view instanceof ViewGroup) for (int n=0; n<((ViewGroup)view).getChildCount(); n++) {
            ListView found=list(((ViewGroup)view).getChildAt(n)); if(found!=null)return found;
        }
        return null;
    }
    private void choose(String label) {
        ListView list=list(content());
        for(int n=0;n<list.getAdapter().getCount();n++) {
            View row=list.getAdapter().getView(n,null,list);
            if(text(row,label)!=null){list.performItemClick(row,n,n);return;}
        }
        fail("Missing storage row: "+label);
    }
    private void ready() throws Exception {
        waitFor(()-> { try { return !(Boolean)get(BackupBrowserActivity.class,controller.get(),"scanning"); } catch(Exception e){throw new RuntimeException(e);} });
        assertNull(text(content(),"Could not read storage:"));
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        do {Shadows.shadowOf(Looper.getMainLooper()).idle();if(condition.getAsBoolean())return;Thread.sleep(5);}while(System.nanoTime()<until);
        assertTrue("Timed out waiting for UI worker",condition.getAsBoolean());
    }
    private static String dialogMessage(AlertDialog dialog) {return ((TextView)dialog.findViewById(android.R.id.message)).getText().toString();}
    private static void clickDialog(int which) {
        ShadowAlertDialog.getLatestAlertDialog().getButton(which).performClick();
        // AlertDialog dispatches its button listener through the main Handler.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private void capture(int width,int height,String filename) throws Exception {
        View view=content();view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));view.layout(0,0,width,height);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));
        File output=new File("out/ui-previews",filename);output.getParentFile().mkdirs();
        try(OutputStream out=new FileOutputStream(output)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}bitmap.recycle();
    }

    @Test public void protectedDataCannotBeDeletedAndRecoveryDeletionRequiresExplicitConfirmation() throws Exception {
        capture(920,520,"backup-browser-wide.png");capture(400,800,"backup-browser-narrow.png");
        choose("Current imported client");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialogMessage(dialog).contains("Protected"));
        assertEquals(View.GONE,dialog.getButton(AlertDialog.BUTTON_POSITIVE).getVisibility());
        assertFalse(dialogMessage(dialog).contains("private-active-file-content"));dialog.dismiss();
        choose("Previous imported client");dialog=ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialogMessage(dialog).contains(new File(session,"previous/client/recovery.dat").length()+" B"));
        clickDialog(AlertDialog.BUTTON_POSITIVE);dialog=ShadowAlertDialog.getLatestAlertDialog();
        String confirmation=dialogMessage(dialog);
        assertTrue(confirmation.contains("Previous imported client"));
        assertTrue(confirmation.contains("permanently removes this copy"));
        assertTrue(confirmation.contains("rollback"));
        assertFalse(WorkService.busy);
        clickDialog(AlertDialog.BUTTON_NEGATIVE);
        assertTrue(new File(session,"previous/client/recovery.dat").isFile());assertFalse(WorkService.busy);
        ClientRuntime.get(context).starting=true;
        choose("Previous imported client");dialog=ShadowAlertDialog.getLatestAlertDialog();
        assertFalse("A running client must prevent removal",dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        ClientRuntime.get(context).starting=false;
    }

    @Test public void acceptedDeletionRunsThroughForegroundServiceAndRescansWithoutTouchingCurrent() throws Exception {
        choose("Previous imported client");clickDialog(AlertDialog.BUTTON_POSITIVE);
        clickDialog(AlertDialog.BUTTON_POSITIVE);
        assertTrue(WorkService.busy);assertTrue(new File(session,"previous").exists());
        service=Robolectric.buildService(WorkService.class).create();service.startCommand(0,1);
        waitFor(()->!WorkService.busy);
        // Advance the scheduled status poll so completion invalidates the old inventory.
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));ready();
        assertFalse(new File(session,"previous").exists());
        assertEquals("private-active-file-content",FilesEx.read(new File(session,"current/client/active.dat"),1024));
        ListView list=list(content());assertEquals(1,list.getAdapter().getCount());
        assertNotNull(text(content(),"Completed: Previous imported client deleted"));
    }

    @Test public void cancellationDropsQueuedScanAndRotationRestoresReadonlyFolderLocation() throws Exception {
        ExecutorService worker=(ExecutorService)get(BackupBrowserActivity.class,controller.get(),"worker");
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
        worker.submit(()->{blocked.countDown();try{release.await();}catch(InterruptedException ignored){Thread.currentThread().interrupt();}});
        assertTrue(blocked.await(5,TimeUnit.SECONDS));
        text(content(),"Refresh").performClick();text(content(),"Cancel scan").performClick();
        assertNotNull(text(content(),"Scan cancelled."));
        release.countDown();worker.submit(()->{}).get(5,TimeUnit.SECONDS);Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNotNull("A cancelled callback must not replace the status",text(content(),"Scan cancelled."));
        text(content(),"Refresh").performClick();ready();
        choose("Previous imported client");clickDialog(AlertDialog.BUTTON_NEUTRAL);ready();
        choose("client");ready();
        assertNotNull(text(content(),"Previous imported client / client"));
        ListView list=list(content());View file=list.getAdapter().getView(0,null,list);
        assertNotNull(text(file,"recovery.dat"));assertNull(text(file,"private-recovery-file-content"));
        Bundle state=new Bundle();controller.saveInstanceState(state).pause().stop().destroy();
        controller=Robolectric.buildActivity(BackupBrowserActivity.class).create(state).start().resume().visible();ready();
        assertNotNull(text(content(),"Previous imported client / client"));
        text(content(),"Parent folder").performClick();ready();
        assertNotNull(text(content(),"Previous imported client"));
    }
}
