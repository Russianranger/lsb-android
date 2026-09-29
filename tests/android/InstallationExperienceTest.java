package io.github.russianranger.lsb;

import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.lang.reflect.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w920dp-h520dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class InstallationExperienceTest {
    private Context context;private ClientRuntime client;private ServerRuntime server;
    private static final String A="11111111-1111-4111-8111-111111111111",B="22222222-2222-4222-8222-222222222222";
    private static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static TextView text(View v,String expected){
        if(v instanceof TextView&&expected.equals(((TextView)v).getText().toString()))return (TextView)v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView found=text(((ViewGroup)v).getChildAt(i),expected);if(found!=null)return found;}return null;
    }
    @Before public void before()throws Exception{
        context=RuntimeEnvironment.getApplication();ClientRuntime.resetAfterRestore();ServerRuntime.resetAfterRestore();OperationProgress.reset();WorkService.busy=false;SessionBackup.active=false;SessionBackup.recoveryError="";
        for(String name:new String[]{"runtime","setup","quick_login"})context.getSharedPreferences(name,0).edit().clear().commit();
        client=ClientRuntime.get(context);server=ServerRuntime.get(context);FilesEx.delete(client.home);FilesEx.delete(server.home);FilesEx.delete(new File(MainActivity.storage(context),"session"));
        FilesEx.delete(new File(context.getFilesDir(),"progress"));FilesEx.delete(new File(context.getFilesDir(),"working-combination.json"));
        FilesEx.text(new File(client.root,"lsb-runtime.sha256"),ClientRuntime.RUNTIME_SHA);
        FilesEx.text(new File(client.home,"clients/state.properties"),"current="+A+"\ncandidate="+B+"\n");
        FilesEx.text(new File(client.home,"clients/"+A+"/copy-complete"),"1");
        FilesEx.text(new File(client.home,"clients/"+A+"/metadata.properties"),"region=US\nclientVersion=30260904_1\n");
        FilesEx.text(new File(client.home,"clients/"+A+"/source-inventory.json"),new JSONObject().put("keyFiles",new JSONArray().put(new JSONObject().put("path","game/xiloader.exe").put("sha256",HASH))).toString());
        FilesEx.text(new File(client.home,"clients/"+B+"/metadata.properties"),"region=US\nclientVersion=NEW\nupdateOf="+A+"\nupdatePhase=verified\n");
        FilesEx.text(new File(server.state,"active.json"),"{\"current\":\""+A+"\"}");
        FilesEx.text(new File(server.state,"generations/"+A+"/deployment.json"),new JSONObject().put("generation",A).put("build_id","working-build").put("database","xidb").put("expected_client","30260904_1").toString());
        context.getSharedPreferences("setup",0).edit().putString("mode","local").commit();
    }
    @After public void after()throws Exception{WorkService.busy=false;OperationProgress.reset();FilesEx.delete(client.home);FilesEx.delete(server.home);FilesEx.delete(new File(MainActivity.storage(context),"session"));FilesEx.delete(new File(context.getFilesDir(),"progress"));FilesEx.delete(new File(context.getFilesDir(),"working-combination.json"));ClientRuntime.resetAfterRestore();ServerRuntime.resetAfterRestore();}
    @Test public void activeSummaryUsesActivatedIdentitiesWhileCandidateIsSeparate()throws Exception{
        JSONObject summary=InstallationSummary.snapshot(context);assertEquals(A,summary.getJSONObject("client").getString("generation"));assertEquals(HASH,summary.getJSONObject("client").getString("loader_sha256"));
        assertEquals("30260904_1",summary.getJSONObject("client").getString("version"));assertEquals(A,summary.getJSONObject("server").getString("generation"));
        assertFalse(InstallationSummary.describe(summary).contains("NEW"));assertEquals(B,client.preparationState().getJSONObject("candidate").getString("generation"));
        context.getSharedPreferences("setup",0).edit().putString("mode","external").commit();JSONObject external=InstallationSummary.snapshot(context);assertFalse(external.has("server"));assertTrue(InstallationSummary.describe(external).contains("Managed outside this app"));
    }
    @Test public void clientNavigationHidesExperimentsAndMoreKeepsAdvancedReachable()throws Exception{
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class,new Intent(context,MainActivity.class).putExtra("tab","Client")).setup();
        try{
            View content=(View)field(c.get(),"content");assertNotNull(text(content,"Go to Play"));assertNotNull(text(content,"◇  Currently playing with"));assertNull(text(content,"◇  Optimization trials"));assertNull(text(content,"◇  Past experiments"));
            text(c.get().getWindow().getDecorView(),"More").performClick();content=(View)field(c.get(),"content");assertNotNull(text(content,"Guided setup"));assertNotNull(text(content,"Diagnostics and history"));
            text(content,"Advanced settings and repairs").performClick();content=(View)field(c.get(),"content");assertNotNull(text(content,"◇  Optimization trials"));assertNotNull(text(content,"Historical GameHub reference"));
        }finally{c.pause().stop().destroy();}
    }
    @Test public void cleanSessionSupersedesOldFailureAndProgressIsBoundedAndRestored()throws Exception{
        OperationProgress.work.begin("work","Old import");OperationProgress.work.finish(context,"Failed","Old failure");
        OperationProgress.client.begin("client","Play");for(int i=0;i<50;i++)OperationProgress.client.update("Step "+i);
        assertEquals(OperationProgress.client,OperationProgress.current());assertTrue(OperationProgress.current().summary().contains("Elapsed"));assertEquals(12,OperationProgress.client.lines.size());
        OperationProgress.client.finish(context,"Completed","Session completed · client closed normally.");OperationProgress.work.ended=1;
        assertFalse(OperationProgress.current().summary().contains("Old failure"));assertTrue(OperationProgress.current().summary().contains("Completed"));
        OperationProgress.reset();OperationProgress.load(context);assertTrue(OperationProgress.client.summary().contains("client closed normally"));assertFalse(OperationProgress.client.running);
        assertEquals("1h 01m 01s",OperationProgress.duration(3661));
    }
    @Test public void savingCombinationUsesCompleteBackupPickerAndKeepsCurrentPointers()throws Exception{
        org.robolectric.android.controller.ActivityController<MainActivity> c=Robolectric.buildActivity(MainActivity.class).setup();
        try{
            View content=(View)field(c.get(),"content");text(content,"◇  Currently playing with").performClick();
            String before=FilesEx.read(new File(server.state,"active.json"),4096);
            text(content,"Save working combination").performClick();Intent pick=Shadows.shadowOf(c.get()).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,pick.getAction());assertEquals("lsb-working-combination.zip",pick.getStringExtra(Intent.EXTRA_TITLE));assertEquals(before,FilesEx.read(new File(server.state,"active.json"),4096));assertEquals(A,client.prepared().selected("current").getName());
            JSONObject snapshot=InstallationSummary.snapshot(context);InstallationSummary.recordSaved(context,snapshot,"fixture.zip");assertEquals(HASH,InstallationSummary.saved(context).getJSONObject("combination").getJSONObject("client").getString("loader_sha256"));
            content.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));content.layout(0,0,400,content.getMeasuredHeight());
            Bitmap bitmap=Bitmap.createBitmap(400,content.getMeasuredHeight(),Bitmap.Config.ARGB_8888);content.draw(new Canvas(bitmap));File out=new File("out/ui-previews/working-combination-narrow.png");out.getParentFile().mkdirs();try(FileOutputStream stream=new FileOutputStream(out)){bitmap.compress(Bitmap.CompressFormat.PNG,100,stream);}bitmap.recycle();
        }finally{c.pause().stop().destroy();}
    }
}
