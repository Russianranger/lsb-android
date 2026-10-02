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
import org.json.JSONObject;
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
    private static View described(View view,String description){
        if(description.equals(view.getContentDescription()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=described(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}return null;
    }
    private static JSONObject source(String snapshot,String commit)throws Exception{return new JSONObject().put("snapshot_id",snapshot).put("repository","Russianranger/LSB-server").put("ref","test").put("commit",commit).put("origin","Russianranger/LSB-server @ "+commit).put("expected_client","30260904_1");}
    private static void writeSource(Context context,JSONObject identity)throws Exception{
        File directory=new File(MainActivity.storage(context),"server/current");FilesEx.text(new File(directory,"source-report.txt"),"Source: "+identity.optString("origin")+"\nExpected client: 30260904_1\n");
        FilesEx.text(new File(directory,"source-identity.json"),identity.toString());
    }
    @Test public void serverTabKeepsAccountsAndRecoveryAndLinksToBuild()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime sr=ServerRuntime.get(ctx);String id="11111111-1111-1111-1111-111111111111";
        FilesEx.text(new File(sr.root,"lsb-server-ready"),"ready");FilesEx.text(new File(sr.root,"lsb-server-tools-v4"),"ready");
        FilesEx.text(new File(sr.state,"active.json"),"{\"current\":\""+id+"\"}");
        FilesEx.text(new File(sr.state,"generations/"+id+"/deployment.json"),"{\"generation\":\""+id+"\",\"accounts\":1,\"characters\":1,\"expected_client\":\"30251204_1\"}");
        SharedPreferences prefs=ctx.getSharedPreferences("runtime",0);prefs.edit().putBoolean("proot_acceleration",true).putBoolean("dxvk_two_compilers",true).commit();Map<String,?> before=prefs.getAll();
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(ctx,MainActivity.class).putExtra("tab","Server")).setup();
        try{
            FantasyTiles tiles=(FantasyTiles)member(controller.get(),"tiles");View content=(View)member(controller.get(),"content");
            CheckBox acceleration=(CheckBox)text(content,"Server runtime acceleration");assertNotNull(acceleration);assertTrue(acceleration.isChecked());
            acceleration.performClick();assertFalse(ctx.getSharedPreferences("server",0).getBoolean("proot_acceleration",true));assertEquals(before,prefs.getAll());
            acceleration.performClick();assertTrue(ctx.getSharedPreferences("server",0).getBoolean("proot_acceleration",false));
            assertNull(text(tiles,"◇  Import your working server"));assertNull(text(tiles,"◇  Source builds & updates"));
            assertNotNull(text(tiles,"◇  Create account"));assertNotNull(text(tiles,"◇  Database backup & restore"));assertNotNull(text(content,"Open Build tab"));
            capture(tiles,920,"server-tiles-wide.png");text(tiles,"◇  Create account").performClick();
            assertTrue(text(tiles,"Create player account").isEnabled());
            EditText user=field(tiles,"New server account name"),password=field(tiles,"New server account password"),confirm=field(tiles,"Confirm server account password");
            assertNotNull(user);assertNotNull(password);assertNotNull(confirm);assertFalse(password.isSaveEnabled());assertFalse(confirm.isSaveEnabled());
            capture(tiles,920,"server-accounts-wide.png");capture(tiles,400,"server-accounts-narrow.png");
            user.setText("test account");password.setText("private-password");confirm.setText("different");text(tiles,"Create player account").performClick();
            AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(((TextView)dialog.findViewById(android.R.id.message)).getText().toString().contains("do not match"));dialog.dismiss();assertFalse(WorkService.busy);
            controller.pause();assertEquals("",password.getText().toString());assertEquals("",confirm.getText().toString());controller.resume();
            text(tiles,"◇  Database backup & restore").performClick();assertNull(text(tiles,"Restore imported database"));assertNotNull(text(tiles,"Restore previous server + database"));
            text(tiles,"◇  Database checkpoints").performClick();assertTrue(text(tiles,"Save database checkpoint").isEnabled());assertFalse(text(tiles,"Restore selected database checkpoint").isEnabled());
            Spinner retention=(Spinner)described(tiles,"Database checkpoint retention");assertEquals(4,retention.getCount());assertEquals(2,retention.getSelectedItemPosition());
            capture(tiles,920,"database-checkpoints-wide.png");capture(tiles,400,"database-checkpoints-narrow.png");
            text(tiles,"◇  Database backup & restore").performClick();text(tiles,"Prepare a replacement database on Build").performClick();assertEquals("Build",member(controller.get(),"tab"));assertNotNull(text((View)member(controller.get(),"content"),"Build workspace"));
            assertEquals(before,prefs.getAll());
        }finally{controller.pause().stop().destroy();FilesEx.delete(sr.home);prefs.edit().clear().commit();ctx.getSharedPreferences("server",0).edit().clear().commit();singleton.set(null,null);Shadows.shadowOf(Looper.getMainLooper()).idle();}
    }
    @Test public void buildTabRequiresCurrentToolsAndOffersWorkersWithoutDatabase()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime sr=ServerRuntime.get(ctx);FilesEx.delete(sr.home);ctx.getSharedPreferences("server",0).edit().clear().commit();
        FilesEx.text(new File(sr.root,"lsb-server-ready"),"ready");FilesEx.text(new File(sr.root,"lsb-server-tools-v3"),"old tools");
        writeSource(ctx,source("snapshot-a","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"));
        try{
            for(boolean toolsCurrent:new boolean[]{false,true})for(boolean busy:new boolean[]{false,true}){
                if(toolsCurrent)FilesEx.text(new File(sr.root,"lsb-server-tools-v4"),"ready");
                WorkService.busy=busy;
                org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(ctx,MainActivity.class).putExtra("tab","Build")).setup();
                try{
                    View content=(View)member(controller.get(),"content");
                    assertNotNull(text(content,"FETCHED SOURCE"));assertNotNull(text(content,"Live build progress"));
                    assertEquals(toolsCurrent&&!busy,text(content,"Build fetched source with jemalloc").isEnabled());
                    assertFalse(text(content,"Prepare database and stage this build").isEnabled());assertFalse(text(content,"Check staged build and database").isEnabled());assertFalse(text(content,"Deploy checked build and database").isEnabled());
                    Spinner workers=(Spinner)described(content,"Build worker count");assertEquals(16,workers.getCount());assertEquals(!busy,workers.isEnabled());
                    if(!busy){workers.setSelection(15);Shadows.shadowOf(Looper.getMainLooper()).idle();assertEquals(16,ctx.getSharedPreferences("server",0).getInt("jobs",0));}
                    if(!toolsCurrent&&!busy){assertTrue(text(content,"Update server runtime and build tools").isEnabled());capture(content,400,"build-tools-upgrade-narrow.png");}
                    assertFalse(sr.hasDatabaseImport());assertFalse(sr.deployment().has("generation"));
                }finally{controller.pause().stop().destroy();Shadows.shadowOf(Looper.getMainLooper()).idle();}
            }
        }finally{WorkService.busy=false;FilesEx.delete(sr.home);FilesEx.delete(new File(MainActivity.storage(ctx),"server"));ctx.getSharedPreferences("server",0).edit().clear().commit();singleton.set(null,null);}
    }
    @Test public void buildTabNamesDifferentFetchedBuiltAndStagedSourcesAndConfirmsReplacement()throws Exception{
        Context ctx=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime sr=ServerRuntime.get(ctx);FilesEx.delete(sr.home);ctx.getSharedPreferences("server",0).edit().clear().commit();
        FilesEx.text(new File(sr.root,"lsb-server-ready"),"ready");FilesEx.text(new File(sr.root,"lsb-server-tools-v4"),"ready");
        JSONObject selected=source("snapshot-new","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),built=source("snapshot-built","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),stagedSource=source("snapshot-staged","cccccccccccccccccccccccccccccccccccccccc");
        writeSource(ctx,selected);
        String buildId="11111111-1111-4111-8111-111111111111",stagedBuildId="22222222-2222-4222-8222-222222222222",generation="33333333-3333-4333-8333-333333333333";
        FilesEx.text(new File(sr.state,"source-build/server/android-build.json"),new JSONObject().put("state","passed").put("build_id",buildId).put("allocator","jemalloc").put("jobs",2).put("selected_source",built).toString());
        JSONObject stage=new JSONObject().put("state","staged").put("generation",generation).put("build_id",stagedBuildId).put("database_mode","fresh").put("selected_source",stagedSource).put("accounts",0).put("characters",0).put("expected_client","30260904_1");
        FilesEx.text(new File(sr.state,"staged.json"),stage.toString());
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(ctx,MainActivity.class).putExtra("tab","Build")).setup();
        try{
            MainActivity activity=controller.get();View content=(View)member(activity,"content");
            assertTrue(text(content,"FETCHED SOURCE").getText().toString().contains(selected.getString("commit")));
            assertTrue(text(content,"COMPLETED BUILD").getText().toString().contains(built.getString("commit")));assertTrue(text(content,"COMPLETED BUILD").getText().toString().contains(buildId));
            assertNotNull(text(content,"This completed build is from a different"));assertNotNull(text(content,"The staged pair uses an earlier build"));
            assertTrue(text(content,"STAGED PAIR").getText().toString().contains(stagedSource.getString("commit")));
            assertTrue(text(content,"Check staged build and database").isEnabled());assertFalse(text(content,"Deploy checked build and database").isEnabled());
            Spinner mode=(Spinner)described(content,"Staged database source");mode.setSelection(0);Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(text(content,"Prepare database and stage this build").isEnabled());
            mode.setSelection(1);Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(text(content,"Prepare database and stage this build").isEnabled());
            mode.setSelection(2);Shadows.shadowOf(Looper.getMainLooper()).idle();assertTrue(text(content,"Prepare database and stage this build").isEnabled());
            text(content,"Prepare database and stage this build").performClick();
            AlertDialog dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();String message=((TextView)dialog.findViewById(android.R.id.message)).getText().toString();
            assertTrue(message.contains(buildId));assertTrue(message.contains(built.getString("commit")));assertTrue(message.contains("Existing progress is not copied."));dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(WorkService.busy);
            stage.put("state","checked");FilesEx.text(new File(sr.state,"staged.json"),stage.toString());
            java.lang.reflect.Method draw=MainActivity.class.getDeclaredMethod("draw");draw.setAccessible(true);draw.invoke(activity);content=(View)member(activity,"content");
            assertTrue(text(content,"Deploy checked build and database").isEnabled());text(content,"Deploy checked build and database").performClick();dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();message=((TextView)dialog.findViewById(android.R.id.message)).getText().toString();
            assertTrue(message.contains(stagedBuildId));assertTrue(message.contains(generation));assertTrue(message.contains(stagedSource.getString("commit")));assertFalse(message.contains(selected.getString("commit")));assertTrue(message.contains("never rebuilds"));dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(WorkService.busy);
            capture(content,920,"build-workspace-wide.png");capture(content,400,"build-workspace-narrow.png");
            stage.put("stale_database",true).put("check_error","Current player data changed");FilesEx.text(new File(sr.state,"staged.json"),stage.toString());draw.invoke(activity);content=(View)member(activity,"content");
            assertFalse(text(content,"Deploy checked build and database").isEnabled());assertNotNull(text(content,"Current player data changed after staging"));assertNotNull(text(content,"Check needs attention:"));

        }finally{controller.pause().stop().destroy();FilesEx.delete(sr.home);FilesEx.delete(new File(MainActivity.storage(ctx),"server"));ctx.getSharedPreferences("server",0).edit().clear().commit();singleton.set(null,null);Shadows.shadowOf(Looper.getMainLooper()).idle();}
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
    @Test public void repeatedUnverifiedZipNamesDoNotImplyTheSameSource()throws Exception{
        java.lang.reflect.Method same=MainActivity.class.getDeclaredMethod("sameSource",JSONObject.class,JSONObject.class);same.setAccessible(true);
        JSONObject first=new JSONObject().put("origin","User-selected ZIP (revision unverified)"),second=new JSONObject(first.toString());
        assertEquals(false,same.invoke(null,first,second));
        first.put("snapshot_id","a");second.put("snapshot_id","b");assertEquals(false,same.invoke(null,first,second));
        second.put("snapshot_id","a");assertEquals(true,same.invoke(null,first,second));
    }
    @Test public void buildPageShowsLiveOutputWhileBuildControlsAreLocked()throws Exception{
        Context context=RuntimeEnvironment.getApplication();Field singleton=ServerRuntime.class.getDeclaredField("instance");singleton.setAccessible(true);singleton.set(null,null);
        ServerRuntime runtime=ServerRuntime.get(context);FilesEx.delete(runtime.home);
        FilesEx.text(new File(runtime.logs,"operation.log"),"[ 41%] Compiling build-page.cpp\n");setMember(runtime,"active",true);WorkService.busy=true;
        org.robolectric.android.controller.ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class,new Intent(context,MainActivity.class).putExtra("tab","Build")).setup();
        try{
            MainActivity activity=controller.get();TextView body=(TextView)member(activity,"serverLogBody");View content=(View)member(activity,"content");
            assertNotNull(text(content,"Live build progress"));assertFalse(text(content,"Fetch this source").isEnabled());assertFalse(text(content,"Build fetched source with jemalloc").isEnabled());
            awaitText(body,"[ 41%]");FilesEx.text(new File(runtime.logs,"operation.log"),"[ 42%] Linking xi_map\n");awaitText(body,"[ 42%]");
            controller.pause();String paused=body.getText().toString();FilesEx.text(new File(runtime.logs,"operation.log"),"[100%] Build complete\n");
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));assertEquals(paused,body.getText().toString());
            controller.resume();awaitText(body,"Build complete");
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
