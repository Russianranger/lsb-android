package io.github.russianranger.lsb;

import android.content.*;
import android.graphics.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import io.github.russianranger.lsb.core.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w920dp-h520dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PlaySetupTest {
    private Context context;private ClientRuntime client;private ServerRuntime server;
    private static final String ID="11111111-1111-4111-8111-111111111111";
    static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    static void set(Object target,String name,Object value)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);f.set(target,value);}
    static TextView text(View root,String label){
        if(root instanceof TextView&&label.equals(((TextView)root).getText().toString()))return (TextView)root;
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++){TextView v=text(((ViewGroup)root).getChildAt(i),label);if(v!=null)return v;}
        return null;
    }
    @Before public void before()throws Exception{
        context=RuntimeEnvironment.getApplication();WorkService.busy=false;SessionBackup.active=false;SessionBackup.recoveryError="";RuntimeService.clearLogin();
        ClientRuntime.resetAfterRestore();ServerRuntime.resetAfterRestore();
        for(String name:new String[]{"runtime","setup","server","quick_login"})context.getSharedPreferences(name,0).edit().clear().commit();
        client=ClientRuntime.get(context);server=ServerRuntime.get(context);
        FilesEx.delete(client.home);FilesEx.delete(server.home);FilesEx.delete(new File(MainActivity.storage(context),"session"));
    }
    @After public void after()throws Exception{
        Thread.interrupted();client.releasePlay();RuntimeService.clearLogin();WorkService.busy=false;
        FilesEx.delete(client.home);FilesEx.delete(server.home);FilesEx.delete(new File(MainActivity.storage(context),"session"));
        for(String name:new String[]{"runtime","setup","server","quick_login"})context.getSharedPreferences(name,0).edit().clear().commit();
        ClientRuntime.resetAfterRestore();ServerRuntime.resetAfterRestore();
    }
    private void readyClient()throws Exception{
        FilesEx.text(new File(client.root,"lsb-runtime.sha256"),ClientRuntime.RUNTIME_SHA);
        FilesEx.text(new File(client.home,"clients/state.properties"),"current="+ID+"\n");
        FilesEx.text(new File(client.home,"clients/"+ID+"/copy-complete"),"1\n");
    }
    @Test public void invalidSavedLoginCannotReplaceWorkingCredentialsOrLeakThroughRuntimeExports()throws Exception{
        SavedLogin.save(context,"LOCALHOST","offline-account","offline-password");assertTrue(SavedLogin.matches(context,"localhost"));
        assertFalse(SavedLogin.matches(context,"other.example"));assertEquals("",SavedLogin.password(context,"other.example"));
        try{SavedLogin.save(context,"localhost","offline-account","");fail("invalid login accepted");}catch(IllegalArgumentException expected){}
        assertEquals("offline-password",SavedLogin.password(context,"localhost"));
        FilesEx.text(new File(server.logs,"zone-entry.log"),"[LSB zone entry] char=Fixture id=4 zone=234 charCreate.begin\n");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)){client.exportLogs(zip);server.exportLogs(zip);}
        boolean trace=false;
        try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))){
            java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                ByteArrayOutputStream content=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
                while((n=zip.read(buffer))!=-1)content.write(buffer,0,n);
                String value=content.toString("UTF-8");assertFalse(value.contains("offline-password"));assertFalse(value.contains("offline-account"));
                if(entry.getName().equals("server/zone-entry.log")){trace=true;assertTrue(value.contains("charCreate.begin"));}
            }
        }
        assertTrue(trace);SavedLogin.forget(context);assertFalse(SavedLogin.matches(context,"localhost"));
    }
    @Test public void rememberedLoginIsMaskedAndCanBeForgottenFromPlay()throws Exception{
        readyClient();readyServer();SetupGuide.restored(context);SavedLogin.save(context,"127.0.0.1","offline-account","offline-password");
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        try{
            View content=(View)field(c.get(),"content");assertNotNull(text(content,"Quick login"));
            assertTrue(((CheckBox)text(content,"Remember account and password for quick login")).isChecked());
            EditText password=(EditText)text(content,"offline-password");assertNotNull(password);assertFalse(password.isSaveEnabled());
            assertTrue(password.getTransformationMethod() instanceof android.text.method.PasswordTransformationMethod);
            capture(content,400,"play-quick-login-narrow.png");
            text(content,"Forget saved login").performClick();
            assertFalse(SavedLogin.matches(context,"127.0.0.1"));assertNull(text((View)field(c.get(),"content"),"offline-password"));
        }finally{c.pause().stop().destroy();}
    }
    @Test public void editingServerAddressCannotCarrySavedCredentialsToAnotherHost()throws Exception{
        readyClient();context.getSharedPreferences("setup",0).edit().putString("mode","external").commit();SavedLogin.save(context,"127.0.0.1","offline-account","offline-password");
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        try{
            View content=(View)field(c.get(),"content");EditText address=(EditText)text(content,"127.0.0.1");
            address.setText("");address.setText("other.example");
            assertNull(text(content,"offline-password"));assertNull(text(content,"offline-account"));assertNotNull(text(content,"Play"));
            assertFalse(((CheckBox)text(content,"Remember account and password for quick login")).isChecked());
            assertTrue(SavedLogin.matches(context,"127.0.0.1"));
            address.setText("127.0.0.1");assertNotNull(text(content,"offline-password"));assertNotNull(text(content,"Quick login"));
        }finally{c.pause().stop().destroy();}
    }
    private void readyServer()throws Exception{
        FilesEx.text(new File(server.root,"lsb-server-ready"),"ready");FilesEx.text(new File(server.root,"lsb-server-tools-v4"),"ready");
        FilesEx.text(new File(server.state,"active.json"),"{\"current\":\""+ID+"\"}");
        FilesEx.text(new File(server.state,"generations/"+ID+"/deployment.json"),new JSONObject().put("generation",ID).put("accounts",3).toString());
    }
    private static void capture(View view,int width,String name)throws Exception{
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        view.layout(0,0,width,view.getMeasuredHeight());Bitmap b=Bitmap.createBitmap(width,Math.max(1,view.getMeasuredHeight()),Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));
        File out=new File("out/ui-previews",name);out.getParentFile().mkdirs();try(FileOutputStream stream=new FileOutputStream(out)){b.compress(Bitmap.CompressFormat.PNG,100,stream);}b.recycle();
    }
    @Test public void firstRunStartsOnPlayAndOffersThreeResumablePaths()throws Exception{
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        try{
            assertEquals("Play",field(c.get(),"tab"));View content=(View)field(c.get(),"content");assertNotNull(text(content,"Continue setup"));text(content,"Continue setup").performClick();
            content=(View)field(c.get(),"content");assertNotNull(text(content,"Connect to an existing server"));assertNotNull(text(content,"Create a server on this device"));assertNotNull(text(content,"Restore a complete backup"));
            capture(content,920,"setup-first-run-wide.png");capture(content,400,"setup-first-run-narrow.png");
            text(content,"Connect to an existing server").performClick();assertEquals("external",context.getSharedPreferences("setup",0).getString("mode",""));
            assertEquals("runtime",SetupGuide.next(context).id);assertFalse(SetupGuide.local(context));
            assertNotNull(text((View)field(c.get(),"content"),"Install client runtime"));
        }finally{c.pause().stop().destroy();}
        assertEquals("runtime",SetupGuide.next(context).id);
    }
    @Test public void restoredInstallationSkipsDownloadsAndRetainsEveryRuntimeSetting()throws Exception{
        readyClient();readyServer();SharedPreferences p=context.getSharedPreferences("runtime",0);
        p.edit().putBoolean("fex",false).putString("updater_engine","box64").putInt("display_fps",30).putBoolean("proot_acceleration",false).commit();
        Map<String,?> before=p.getAll();SetupGuide.restored(context);RuntimePresets.initializeNewSetup(context);
        assertTrue(SetupGuide.next(context).ready());assertTrue(SetupGuide.local(context));assertEquals(before,p.getAll());
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        try{
            View content=(View)field(c.get(),"content");assertNotNull(text(content,"Play"));assertNull(text(content,"Continue setup"));
            assertNotNull(text(content,"Ready to play"));assertNotNull(text(content,"Server stopped · Play will start it"));
            ((Runnable)field(c.get(),"poll")).run();
            assertNotNull(text(content,"Ready to play"));assertNull(text(content,"Install the runtime, then start the Windows checks."));assertNull(text(content,"Import your existing server and SQL backup to begin."));
            capture(content,920,"play-restored-wide.png");capture(content,400,"play-restored-narrow.png");assertEquals(before,p.getAll());
        }
        finally{c.pause().stop().destroy();}
    }
    @Test public void gameplayAndUpdaterChoicesAreIndependentAndNeverRewritePreparedFiles()throws Exception{
        readyClient();File sentinel=new File(client.home,"clients/"+ID+"/client/updated.dat");FilesEx.text(sentinel,"updated client");
        RuntimePresets.updater(context,"box64");RuntimePresets.gameplay(context,"thor");
        SharedPreferences p=context.getSharedPreferences("runtime",0);assertTrue(p.getBoolean("fex",false));assertEquals("box64",p.getString("updater_engine",""));
        assertTrue(RuntimePresets.gameplayLabel(context).startsWith("Thor tested"));assertEquals("fex",SetupGuide.next(context).id);
        RuntimePresets.updater(context,"fex");RuntimePresets.gameplay(context,"box64");assertEquals("fex",p.getString("updater_engine",""));assertFalse(p.getBoolean("fex",true));assertTrue(p.getBoolean("dxvk_271",false));
        assertEquals("updated client",FilesEx.read(sentinel,128));
    }
    @Test public void localGuideResumesEachBuildReceiptAndExternalSkipsServer()throws Exception{
        readyClient();context.getSharedPreferences("setup",0).edit().putString("mode","local").commit();assertEquals("server_runtime",SetupGuide.next(context).id);
        readyServer();new File(server.state,"active.json").delete();assertEquals("source",SetupGuide.next(context).id);
        File source=new File(MainActivity.storage(context),"server/current/source-identity.json");FilesEx.text(source,"{\"snapshot_id\":\"selected-source\"}");
        try{
            File receipt=new File(server.state,"source-build/server/android-build.json");
            JSONObject compiled=new JSONObject().put("state","passed").put("build_id",ID).put("allocator","jemalloc").put("selected_source",new JSONObject().put("snapshot_id","older-source"));
            FilesEx.text(receipt,compiled.toString());assertEquals("build",SetupGuide.next(context).id);
            compiled.getJSONObject("selected_source").put("snapshot_id","selected-source");FilesEx.text(receipt,compiled.toString());assertEquals("database",SetupGuide.next(context).id);
            JSONObject staged=new JSONObject().put("generation",ID).put("build_id","older-build").put("state","checked");File stage=new File(server.state,"staged.json");
            FilesEx.text(stage,staged.toString());assertEquals("database",SetupGuide.next(context).id);
            staged.put("build_id",ID).put("state","staged");FilesEx.text(stage,staged.toString());assertEquals("check",SetupGuide.next(context).id);
            staged.put("state","checked");FilesEx.text(stage,staged.toString());assertEquals("deploy",SetupGuide.next(context).id);
            staged.put("stale_database",true);FilesEx.text(stage,staged.toString());assertEquals("database",SetupGuide.next(context).id);
            context.getSharedPreferences("setup",0).edit().putString("mode","external").commit();assertTrue(SetupGuide.next(context).ready());
        }finally{FilesEx.delete(new File(MainActivity.storage(context),"server"));}
    }
    private static class Fake implements PlayFlow.Server,PlayFlow.Wait {
        long time,readyAt=93000,dieAt=Long.MAX_VALUE;int starts;boolean running;String progress;
        public boolean alive(){return running&&time<dieAt;}
        public boolean ready(){return alive()&&time>=readyAt;}
        public String status(){return "Loading map scripts";}
        public void start(){starts++;running=true;}
        public long now(){return time;}
        public void pause(){time+=250;}
        public void progress(String text){progress=text;}
    }
    @Test public void playWaitsThroughLongMapStartupAndReusesReadyServer()throws Exception{
        Fake server=new Fake();PlayFlow.await(server,server,1800000);assertEquals(93000,server.time);assertEquals(1,server.starts);assertTrue(server.progress.contains("Loading map scripts"));
        PlayFlow.await(server,server,1800000);assertEquals(1,server.starts);
    }
    @Test public void serverExitTimeoutAndCancellationNeverReportReady()throws Exception{
        Fake server=new Fake();server.dieAt=5000;
        try{PlayFlow.await(server,server,1800000);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("did not become ready"));}assertEquals(5000,server.time);
        Fake timeout=new Fake();try{PlayFlow.await(timeout,timeout,2000);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("still loading"));}
        Fake cancelled=new Fake();Thread.currentThread().interrupt();try{PlayFlow.await(cancelled,cancelled,2000);fail();}catch(InterruptedIOException expected){assertEquals(0,cancelled.starts);}finally{Thread.interrupted();}
    }
    @Test public void waitingForServerOwnsClientAndBlocksRestoreOrOtherTransfers()throws Exception{
        client.reservePlay();assertTrue(client.alive());final boolean[] closed={false};
        assertFalse(WorkService.submit(context,"restore",new WorkService.Job(){public String run(Context c,SafeZip.Progress p){throw new AssertionError("Restore started during Play");}public void close(){closed[0]=true;}}));
        assertTrue(closed[0]);client.releasePlay();assertFalse(client.alive());
    }
    @Test public void consumedLoginSurvivesServerWaitWithoutPersistingCredentials()throws Exception{
        LoginRequest request=new LoginRequest("127.0.0.1","fixture-user","fixture-secret");String ticket=RuntimeService.queueLogin(request);
        java.lang.reflect.Method take=RuntimeService.class.getDeclaredMethod("takeLogin",String.class);take.setAccessible(true);LoginRequest owned=(LoginRequest)take.invoke(null,ticket);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(94,TimeUnit.SECONDS);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();owned.send(bytes);assertTrue(bytes.size()>0);
        try{owned.send(new ByteArrayOutputStream());fail("One-use credentials reused");}catch(IOException expected){}
        assertTrue(context.getSharedPreferences("runtime",0).getAll().isEmpty());
    }
    @Test public void cancelledServiceClearsLoginAndReleasesReservation()throws Exception{
        readyClient();readyServer();set(server,"active",true);
        LoginRequest request=new LoginRequest("127.0.0.1","fixture-user","fixture-secret");String ticket=RuntimeService.queueLogin(request);
        org.robolectric.android.controller.ServiceController<RuntimeService> service=Robolectric.buildService(RuntimeService.class).create();
        try{
            service.get().onStartCommand(new Intent(context,RuntimeService.class).putExtra("operation","launch").putExtra("play_flow",true).putExtra("managed_server",true).putExtra("login_ticket",ticket),0,1);
            long deadline=System.currentTimeMillis()+3000;while(!client.alive()&&System.currentTimeMillis()<deadline)Thread.sleep(10);assertTrue(client.alive());
            service.get().onStartCommand(new Intent(context,RuntimeService.class).setAction("stop"),0,2);
            Thread worker=(Thread)field(service.get(),"worker");if(worker!=null)worker.join(3000);assertFalse(client.alive());
            try{request.send(new ByteArrayOutputStream());fail("Credentials retained after cancel");}catch(IOException expected){}
            Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(WorkService.busy);
        }finally{
            set(server,"active",false);
            Thread worker=(Thread)field(service.get(),"worker");if(worker!=null){worker.interrupt();worker.join(3000);assertFalse("Runtime worker did not stop",worker.isAlive());}
            Shadows.shadowOf(Looper.getMainLooper()).idle();service.destroy();
            // Stop requests finish on a separate thread; join it before deleting the fixture.
            for(Thread thread:Thread.getAllStackTraces().keySet())if(thread.getName().equals("lsb-runtime-stop")||thread.getName().equals("lsb-runtime-cleanup")){
                thread.join(3000);assertFalse("Runtime cleanup did not stop",thread.isAlive());
            }
        }
    }
}
