package io.github.russianranger.lsb;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import io.github.russianranger.lsb.core.FilesEx;
import java.io.*;
import java.lang.reflect.Field;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w920dp-h520dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ServerPageTest {
    private static TextView text(View v,String prefix){
        if(v instanceof TextView&&((TextView)v).getText().toString().startsWith(prefix))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=text(((ViewGroup)v).getChildAt(i),prefix);if(found!=null)return found;}return null;
    }
    private static EditText field(View v,String description){
        if(v instanceof EditText&&description.contentEquals(v.getContentDescription()))return (EditText)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){EditText found=field(((ViewGroup)v).getChildAt(i),description);if(found!=null)return found;}return null;
    }
    private static void capture(View v,int width,String name)throws Exception{
        v.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));v.layout(0,0,width,v.getMeasuredHeight());
        Bitmap b=Bitmap.createBitmap(width,v.getHeight(),Bitmap.Config.ARGB_8888);Canvas c=new Canvas(b);c.drawColor(0xff0c141f);v.draw(c);
        File target=new File("out/ui-previews",name);target.getParentFile().mkdirs();try(OutputStream out=new FileOutputStream(target)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}b.recycle();
    }
    @Test public void serverControlsSeparateAccountsRestoreAndSourceUpdatesAndClearSecrets()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime sr=ServerRuntime.get(ctx);String id="11111111-1111-1111-1111-111111111111";
        FilesEx.text(new File(sr.root,"lsb-server-ready"),"ready");FilesEx.text(new File(sr.root,"lsb-server-tools-v4"),"ready");
        FilesEx.text(new File(sr.state,"active.json"),"{\"current\":\""+id+"\"}");
        FilesEx.text(new File(sr.state,"generations/"+id+"/deployment.json"),"{\"generation\":\""+id+"\",\"accounts\":1,\"characters\":1,\"expected_client\":\"30251204_1\"}");
        FilesEx.text(new File(sr.state,"import.sql"),"a complete staged fixture dump");
        SharedPreferences prefs=ctx.getSharedPreferences("runtime",0);prefs.edit().putBoolean("proot_acceleration",true).putBoolean("dxvk_two_compilers",true).commit();Map<String,?> before=prefs.getAll();
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(ctx,MainActivity.class).putExtra("tab","Server")).setup();
        try{
            Field tileField=MainActivity.class.getDeclaredField("tiles");tileField.setAccessible(true);FantasyTiles tiles=(FantasyTiles)tileField.get(controller.get());
            assertNotNull(text(tiles,"◇  Import your working server"));assertNotNull(text(tiles,"◇  Create account"));assertNotNull(text(tiles,"◇  Database backup & restore"));assertNotNull(text(tiles,"◇  Source builds & updates"));
            capture(tiles,920,"server-tiles-wide.png");
            text(tiles,"◇  Create account").performClick();
            assertTrue(text(tiles,"Create player account").isEnabled());
            EditText user=field(tiles,"New server account name"),password=field(tiles,"New server account password"),confirm=field(tiles,"Confirm server account password");
            assertNotNull(user);assertNotNull(password);assertNotNull(confirm);assertFalse(password.isSaveEnabled());assertFalse(confirm.isSaveEnabled());
            capture(tiles,920,"server-accounts-wide.png");capture(tiles,400,"server-accounts-narrow.png");
            user.setText("test account");password.setText("private-password");confirm.setText("different");text(tiles,"Create player account").performClick();
            AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(((TextView)dialog.findViewById(android.R.id.message)).getText().toString().contains("do not match"));dialog.dismiss();assertFalse(WorkService.busy);
            controller.pause();assertEquals("",password.getText().toString());assertEquals("",confirm.getText().toString());controller.resume();
            text(tiles,"◇  Database backup & restore").performClick();capture(tiles,920,"server-database-wide.png");capture(tiles,400,"server-database-narrow.png");
            text(tiles,"Restore imported database").performClick();dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
            String message=((TextView)dialog.findViewById(android.R.id.message)).getText().toString();assertTrue(message.contains("currently deployed server"));assertTrue(message.contains("does not fetch source"));dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(WorkService.busy);
            assertEquals(before,prefs.getAll());
        }finally{controller.pause().stop().destroy();FilesEx.delete(sr.home);prefs.edit().clear().commit();singleton.set(null,null);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void installedRuntimeOffersDependencyUpgradeBeforeDeployment()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime sr=ServerRuntime.get(ctx);FilesEx.delete(sr.home);
        FilesEx.text(new File(sr.root,"lsb-server-ready"),"ready");FilesEx.text(new File(sr.root,"lsb-server-tools-v3"),"old tools");
        FilesEx.text(new File(sr.state,"import.sql"),"staged SQL fixture");
        File report=new File(MainActivity.storage(ctx),"server/current/source-report.txt");FilesEx.text(report,"matching server fixture");
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(ctx,MainActivity.class).putExtra("tab","Server")).setup();
        try{
            Field tileField=MainActivity.class.getDeclaredField("tiles");tileField.setAccessible(true);FantasyTiles tiles=(FantasyTiles)tileField.get(controller.get());
            text(tiles,"◇  Import your working server").performClick();
            assertTrue(text(tiles,"Update server runtime and build tools").isEnabled());
            assertFalse(text(tiles,"Deploy matching server + database").isEnabled());
            assertFalse(text(tiles,"Build imported revision and deploy").isEnabled());
            assertNotNull(text(tiles,"Update the server runtime to add libraries required by your imported server."));
            capture(tiles,920,"server-runtime-update-wide.png");capture(tiles,400,"server-runtime-update-narrow.png");
            text(tiles,"◇  Source builds & updates").performClick();
            assertFalse(text(tiles,"Build selected source with jemalloc").isEnabled());
        }finally{controller.pause().stop().destroy();FilesEx.delete(sr.home);report.delete();singleton.set(null,null);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void sourceBuildNeedsNoDatabaseOrDeploymentAndDisablesWhileBusy()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime sr=ServerRuntime.get(ctx);FilesEx.delete(sr.home);
        FilesEx.text(new File(sr.root,"lsb-server-ready"),"ready");FilesEx.text(new File(sr.root,"lsb-server-tools-v4"),"ready");
        File report=new File(MainActivity.storage(ctx),"server/current/source-report.txt");FilesEx.text(report,"selected source fixture");
        try{
            assertFalse(sr.hasDatabaseImport());assertFalse(sr.deployment().has("generation"));
            for(boolean busy:new boolean[]{false,true}){
                WorkService.busy=busy;
                org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(ctx,MainActivity.class).putExtra("tab","Server")).setup();
                try{
                    Field tileField=MainActivity.class.getDeclaredField("tiles");tileField.setAccessible(true);FantasyTiles tiles=(FantasyTiles)tileField.get(controller.get());
                    text(tiles,"◇  Source builds & updates").performClick();
                    assertEquals(!busy,text(tiles,"Build selected source with jemalloc").isEnabled());
                    assertFalse(text(tiles,"Build and apply source + database update").isEnabled());
                    if(!busy){capture(tiles,920,"server-source-build-wide.png");capture(tiles,400,"server-source-build-narrow.png");}
                }finally{controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
            }
        }finally{WorkService.busy=false;FilesEx.delete(sr.home);report.delete();singleton.set(null,null);}
    }
    private static Object member(Object instance,String name)throws Exception{
        Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(instance);
    }
    private static void setMember(Object instance,String name,Object value)throws Exception{
        Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);field.set(instance,value);
    }
    private static void awaitText(TextView view,String expected)throws Exception{
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!view.getText().toString().contains(expected)&&System.nanoTime()<deadline){
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));Thread.sleep(5);
        }
        assertTrue("Expected "+expected+" in "+view.getText(),view.getText().toString().contains(expected));
    }
    @Test public void compilerOutputRefreshesWhileBusyAndDiagnosticsSurvivesPauseAndCompletion()throws Exception{
        Context context=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime runtime=ServerRuntime.get(context);FilesEx.delete(runtime.home);
        FilesEx.text(new File(runtime.logs,"operation.log"),"[ 10%] Compiling first.cpp\n");setMember(runtime,"active",true);
        WorkService.busy=true;WorkService.message="Compiling the four server programs with jemalloc…";
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(context,MainActivity.class).putExtra("tab","Diagnostics")).setup();
        try{
            MainActivity activity=controller.get();TextView latest=(TextView)member(activity,"latestServerOutput"),body=(TextView)member(activity,"serverLogBody");
            awaitText(latest,"[ 10%]");awaitText(body,"first.cpp");assertEquals(View.VISIBLE,latest.getVisibility());
            FilesEx.text(new File(runtime.logs,"operation.log"),"[ 20%] Compiling second.cpp\n");awaitText(latest,"[ 20%]");awaitText(body,"second.cpp");
            controller.pause();String paused=body.getText().toString();FilesEx.text(new File(runtime.logs,"operation.log"),"[ 30%] Compiling third.cpp\n");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3));assertEquals(paused,body.getText().toString());
            controller.resume();awaitText(body,"third.cpp");
            // Completion retains the final tail and clears the active headline.
            FilesEx.text(new File(runtime.logs,"operation.log"),"[100%] Built target xi_map\n");setMember(runtime,"active",false);WorkService.busy=false;
            awaitText(body,"Built target xi_map");assertEquals(View.GONE,latest.getVisibility());
            capture((View)member(activity,"content"),400,"server-live-log-narrow.png");
            // A new server operation must not display the prior final line.
            setMember(runtime,"active",true);setMember(runtime,"logEpoch",runtime.logEpoch()+1);setMember(runtime,"logsReady",false);WorkService.busy=true;
            awaitText(body,"Waiting for server output");assertFalse(body.getText().toString().contains("xi_map"));assertEquals(View.GONE,latest.getVisibility());
        }finally{WorkService.busy=false;setMember(runtime,"active",false);controller.pause().stop().destroy();FilesEx.delete(runtime.home);singleton.set(null,null);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void serverLogButtonRemainsUsableDuringWorkAndDialogFollowsWithoutJumpingHistory()throws Exception{
        Context context=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime runtime=ServerRuntime.get(context);FilesEx.delete(runtime.home);
        StringBuilder lines=new StringBuilder();for(int i=0;i<150;i++)lines.append("compiler line ").append(i).append('\n');
        FilesEx.text(new File(runtime.logs,"operation.log"),lines.toString());setMember(runtime,"active",true);WorkService.busy=true;
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(context,MainActivity.class).putExtra("tab","Server")).setup();
        try{
            MainActivity activity=controller.get();FantasyTiles tiles=(FantasyTiles)member(activity,"tiles");text(tiles,"◇  Server logs").performClick();
            TextView button=text(tiles,"View server operation log");assertTrue(button.isEnabled());button.performClick();
            AlertDialog dialog=(AlertDialog)member(activity,"serverLogDialog");assertTrue(dialog.isShowing());
            TextView body=(TextView)member(activity,"serverLogDialogBody");ScrollView scroll=(ScrollView)member(activity,"serverLogDialogScroll");awaitText(body,"compiler line 149");
            // Finish initial layout/follow before the user starts reading history.
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));
            long now=android.os.SystemClock.uptimeMillis();MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,20,20,0),cancel=MotionEvent.obtain(now,now+1,MotionEvent.ACTION_CANCEL,20,20,0);
            scroll.dispatchTouchEvent(down);scroll.dispatchTouchEvent(cancel);down.recycle();cancel.recycle();scroll.scrollTo(0,0);
            assertTrue(scroll.canScrollVertically(1));
            lines.append("[ 99%] Linking\n");FilesEx.text(new File(runtime.logs,"operation.log"),lines.toString());awaitText(body,"[ 99%]");assertEquals("Reading older output must preserve scroll position",0,scroll.getScrollY());
            scroll.scrollTo(0,body.getBottom());assertFalse(scroll.canScrollVertically(1));
            lines.append("[100%] Built target xi_map\n");FilesEx.text(new File(runtime.logs,"operation.log"),lines.toString());awaitText(body,"Built target xi_map");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));assertFalse("An append follows when already at the bottom",scroll.canScrollVertically(1));
            // A touch after an append must also invalidate its queued follow.
            scroll.scrollTo(0,body.getBottom());assertFalse(scroll.canScrollVertically(1));
            java.lang.reflect.Method update=MainActivity.class.getDeclaredMethod("updateServerLogView",TextView.class,ScrollView.class,String.class);update.setAccessible(true);
            update.invoke(activity,body,scroll,body.getText()+"new output queued before touch\n");
            down=MotionEvent.obtain(now+2,now+2,MotionEvent.ACTION_DOWN,20,20,0);cancel=MotionEvent.obtain(now+2,now+3,MotionEvent.ACTION_CANCEL,20,20,0);
            body.dispatchTouchEvent(down);body.dispatchTouchEvent(cancel);down.recycle();cancel.recycle();scroll.scrollTo(0,0);
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600));assertEquals("A stale follow must not override a later gesture",0,scroll.getScrollY());
            dialog.dismiss();Shadows.shadowOf(Looper.getMainLooper()).idle();assertNull(member(activity,"serverLogDialogBody"));
        }finally{WorkService.busy=false;setMember(runtime,"active",false);controller.pause().stop().destroy();FilesEx.delete(runtime.home);singleton.set(null,null);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
}
