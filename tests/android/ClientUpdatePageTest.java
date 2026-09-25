package io.github.russianranger.lsb;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

/** UI contracts for isolated PlayOnline repair; no Windows process or download is started. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w920dp-h520dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ClientUpdatePageTest {
    private static final SafeZip.Progress QUIET=message->{};
    private android.app.Application context;
    private MainActivity activity;
    private ClientRuntime runtime;
    private ServerRuntime server;
    private ClientStore imported;
    private PreparedClientStore prepared;
    private File current;
    private Map<String,?> runtimeSettings,serverSettings;

    private static void set(Object target,String name,Object value)throws Exception {
        Class<?> type=target instanceof Class?(Class<?>)target:target.getClass();
        Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(target instanceof Class?null:target,value);
    }
    private static Object field(Object target,String name)throws Exception {
        Class<?> type=target instanceof Class?(Class<?>)target:target.getClass();
        Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(target instanceof Class?null:target);
    }
    private static TextView text(View view,String value) {
        if(view instanceof TextView&&value.equals(((TextView)view).getText().toString()))return (TextView)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){
            TextView found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;
        }
        return null;
    }
    private static boolean contains(View view,String value) {
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(value))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(contains(((ViewGroup)view).getChildAt(i),value))return true;
        return false;
    }
    private static TextView button(View view,String value) {
        TextView found=text(view,value);assertNotNull("Missing button: "+value,found);assertTrue(found instanceof Button);return found;
    }
    private static byte[] pe() {
        byte[] bytes=new byte[256];bytes[0]='M';bytes[1]='Z';bytes[0x3c]=0x40;bytes[0x40]='P';bytes[0x41]='E';
        bytes[0x44]=0x4c;bytes[0x45]=1;bytes[0x58]=0x0b;bytes[0x59]=1;return bytes;
    }
    private static byte[] clientZip()throws Exception {
        Map<String,byte[]> files=new LinkedHashMap<>();
        for(String name:new String[]{"PlayOnlineViewer/polcore.dll","PlayOnlineViewer/pol.exe","PlayOnlineViewer/xiloader.exe","FINAL FANTASY XI/FFXi.dll","FINAL FANTASY XI/FFXiMain.dll"})files.put(name,pe());
        files.put("FINAL FANTASY XI/ROM/0/0.DAT",new byte[]{1,2,3});
        files.put("FINAL FANTASY XI/patch.ver","30260904_1".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){for(Map.Entry<String,byte[]> entry:files.entrySet()){
            zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue());zip.closeEntry();
        }}
        return bytes.toByteArray();
    }
    private static void reset()throws Exception {
        WorkService.Job job=(WorkService.Job)field(WorkService.class,"pending");if(job!=null)job.close();set(WorkService.class,"pending",null);
        WorkService.busy=false;WorkService.message="Ready";WorkService.result="";
        SessionBackup.active=false;SessionBackup.recoveryError="";
        ClientRuntime.resetAfterRestore();ServerRuntime.resetAfterRestore();
    }
    @Before public void before()throws Exception {
        reset();context=RuntimeEnvironment.getApplication();
        FilesEx.delete(new File(context.getFilesDir(),"rt"));FilesEx.delete(new File(context.getFilesDir(),"server-runtime"));
        FilesEx.delete(new File(MainActivity.storage(context),"session"));
        context.getSharedPreferences("runtime",0).edit().clear().putBoolean("fex",true).putString("renderer","turnip26")
            .putBoolean("dxvk_two_compilers",true).putBoolean("proot_acceleration",true).putInt("display_fps",60).commit();
        context.getSharedPreferences("server",0).edit().clear().putString("repository","accepted-source").putString("revision","accepted-revision").commit();
        runtimeSettings=new HashMap<>(context.getSharedPreferences("runtime",0).getAll());serverSettings=new HashMap<>(context.getSharedPreferences("server",0).getAll());
        runtime=ClientRuntime.get(context);server=ServerRuntime.get(context);imported=MainActivity.store(context);prepared=runtime.prepared();
        FilesEx.text(new File(server.state,"active.json"),"{\"current\":\"accepted-server-generation\"}");
        // Build an attached Activity but invoke only the requested cards, avoiding startup migrations and unrelated tabs.
        activity=Robolectric.buildActivity(MainActivity.class,new Intent(context,MainActivity.class)).get();
    }
    @After public void after()throws Exception {
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null)dialog.dismiss();
        reset();FilesEx.delete(runtime.home);FilesEx.delete(server.home);FilesEx.delete(new File(MainActivity.storage(context),"session"));
        context.getSharedPreferences("runtime",0).edit().clear().commit();context.getSharedPreferences("server",0).edit().clear().commit();
    }
    private File activeClient()throws Exception {
        FilesEx.text(new File(runtime.root,"lsb-runtime.sha256"),ClientRuntime.RUNTIME_SHA);
        FilesEx.text(new File(runtime.prefix,"lsb-prefix-ready.json"),"{}");FilesEx.text(new File(runtime.prefix,"user.reg"),"accepted registry");
        imported.importClient(new ByteArrayInputStream(clientZip()),false,true,QUIET);
        current=prepared.prepare(imported,runtime.prefix,QUIET);FilesEx.text(new File(current,"initialization-passed.json"),"{}");prepared.promote(current);
        return current;
    }
    private File updateCandidate()throws Exception {if(current==null)activeClient();return prepared.stageUpdate(QUIET);}
    private FantasyTiles card(String method,String selected)throws Exception {
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        FantasyTiles tiles=new FantasyTiles(activity,selected,value->{});set(activity,"content",content);set(activity,"tiles",tiles);
        Method draw=MainActivity.class.getDeclaredMethod(method);draw.setAccessible(true);draw.invoke(activity);
        content.addView(tiles);activity.setContentView(content);layout(tiles,920);return tiles;
    }
    private FantasyTiles preparationCard(String method,String selected)throws Exception {
        LinearLayout content=new LinearLayout(activity);content.setOrientation(LinearLayout.VERTICAL);
        FantasyTiles tiles=new FantasyTiles(activity,selected,value->{});set(activity,"content",content);set(activity,"tiles",tiles);
        Method draw=MainActivity.class.getDeclaredMethod(method,ClientStore.class);draw.setAccessible(true);draw.invoke(activity,imported);
        content.addView(tiles);activity.setContentView(content);layout(tiles,920);return tiles;
    }
    private static void layout(View view,int width) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        view.layout(0,0,width,view.getMeasuredHeight());
    }
    private static void capture(View view,int width,String name)throws Exception {
        layout(view,width);Bitmap bitmap=Bitmap.createBitmap(width,view.getHeight(),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);canvas.drawColor(0xff0c141f);view.draw(canvas);
        File target=new File("out/ui-previews",name);target.getParentFile().mkdirs();
        try(OutputStream out=new FileOutputStream(target)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
    private static void respond(AlertDialog dialog,int button) {
        dialog.getButton(button).performClick();Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }
    private AlertDialog confirmation(View view,String label,String message)throws Exception {
        button(view,label).performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertTrue(dialog.isShowing());
        assertTrue(((TextView)dialog.findViewById(android.R.id.message)).getText().toString().contains(message));
        assertNull(Shadows.shadowOf(context).getNextStartedService());assertFalse(WorkService.busy);assertUnchanged();return dialog;
    }
    private void assertUnchanged()throws Exception {
        assertEquals(runtimeSettings,context.getSharedPreferences("runtime",0).getAll());assertEquals(serverSettings,context.getSharedPreferences("server",0).getAll());
        assertEquals("{\"current\":\"accepted-server-generation\"}",FilesEx.read(new File(server.state,"active.json"),4096));
        if(current!=null){assertEquals(current,prepared.selected("current"));assertEquals("accepted registry",FilesEx.read(new File(current,"prefix/user.reg"),4096));}
    }
    private void assertRuntimeAction(String operation)throws Exception {
        Intent service=Shadows.shadowOf(context).getNextStartedService();assertNotNull(service);
        assertEquals(RuntimeService.class.getName(),service.getComponent().getClassName());assertEquals(operation,service.getStringExtra("operation"));
        assertEquals("turnip26",service.getStringExtra("renderer"));assertTrue(service.getBooleanExtra("audio",false));
        Intent display=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(display);assertEquals(RuntimeActivity.class.getName(),display.getComponent().getClassName());
        assertUnchanged();
    }
    private File repairedCandidate()throws Exception {
        File candidate=updateCandidate();prepared.prepareUpdateRepair();
        try(OutputStream out=new FileOutputStream(new File(candidate,"client/FINAL FANTASY XI/ROM/0/0.DAT"))){out.write(new byte[]{1,2,3});}
        prepared.requireRepairCompleted();prepared.updatePhase("verified");return candidate;
    }
    private JSONObject verification(File candidate)throws Exception {
        Method manifest=ClientRuntime.class.getDeclaredMethod("clientManifest",File.class);manifest.setAccessible(true);
        JSONObject inspected=(JSONObject)manifest.invoke(runtime,candidate);
        return new JSONObject().put("format",1).put("generation",candidate.getName()).put("session_id","ui-fixture-verification")
            .put("status","passed").put("key_files",inspected.getJSONObject("key_files"));
    }
    private void receipts(File candidate,JSONObject receipt)throws Exception {
        FilesEx.text(new File(candidate,"update-verified.json"),receipt.toString());
        FilesEx.text(new File(candidate,"initialization-passed.json"),new JSONObject().put("status","passed").put("generation",candidate.getName())
            .put("session_id","ui-fixture-verification").toString());
    }
    private void rejectedActivation(File candidate)throws Exception {
        String before=FilesEx.read(new File(runtime.home,"clients/state.properties"),4096);
        try{runtime.activateClientUpdate();fail("Unverified or busy update must not activate");}catch(IOException expected){}
        assertEquals(before,FilesEx.read(new File(runtime.home,"clients/state.properties"),4096));assertEquals(candidate,prepared.selected("candidate"));assertUnchanged();
    }

    @Test public void missingPreparedClientDisablesUpdateAndBackupBrowserRemainsDiscoverable()throws Exception {
        View update=card("clientUpdateCard","Client update · PlayOnline");
        assertFalse(button(update,"Prepare update and open PlayOnline").isEnabled());assertFalse(button(update,"Verify completed update").isEnabled());assertFalse(button(update,"Activate verified update").isEnabled());
        View backup=card("clientPage","Backup and recovery");button(backup,"Manage backups and storage").performClick();
        Intent launch=Shadows.shadowOf(activity).getNextStartedActivity();assertNotNull(launch);assertEquals(BackupBrowserActivity.class.getName(),launch.getComponent().getClassName());assertUnchanged();
    }
    @Test public void startRequiresExplicitCopyConfirmationAndDispatchesUpdaterWithoutChangingSettings()throws Exception {
        activeClient();View update=card("clientUpdateCard","Client update · PlayOnline");
        assertTrue(button(update,"Prepare update and open PlayOnline").isEnabled());assertFalse(button(update,"Activate verified update").isEnabled());
        assertTrue(contains(update,"Use LSB Restore Test for the first update test"));assertTrue(contains(update,"Check Files → FINAL FANTASY XI"));
        AlertDialog dialog=confirmation(update,"Prepare update and open PlayOnline","Your active client and its Windows environment are retained");
        respond(dialog,AlertDialog.BUTTON_NEGATIVE);assertNull(Shadows.shadowOf(context).getNextStartedService());assertNull(prepared.selected("candidate"));
        dialog=confirmation(update,"Prepare update and open PlayOnline","ROM/0/0.dat");respond(dialog,AlertDialog.BUTTON_POSITIVE);assertRuntimeAction("update-client");
    }
    @Test public void stagedUpdateCannotUseGenericPreparationOrPrerequisiteInstaller()throws Exception {
        File candidate=updateCandidate();FilesEx.text(new File(runtime.home,"prerequisite.exe"),"selected fixture installer");
        View initialization=preparationCard("initializationCard","Prepare PlayOnline and FFXI");
        assertFalse(button(initialization,"Retry client initialization").isEnabled());assertNull(text(initialization,"Select prerequisite installer (.exe)"));assertNull(text(initialization,"Run prerequisite and retry checks"));
        assertFalse(button(initialization,"Discard staged preparation").isEnabled());assertTrue(contains(initialization,"Continue it from Client update · PlayOnline"));
        View diagnostics=preparationCard("loginCard","Capture & diagnostics");
        assertFalse(button(diagnostics,"Select launcher prerequisite (.exe)").isEnabled());assertFalse(button(diagnostics,"Repair launcher prerequisites").isEnabled());
        View update=card("clientUpdateCard","Client update · PlayOnline");
        assertTrue(button(update,"Open or resume PlayOnline update").isEnabled());assertTrue(button(update,"Verify completed update").isEnabled());assertFalse(button(update,"Activate verified update").isEnabled());
        assertEquals(candidate,prepared.selected("candidate"));assertUnchanged();
    }
    @Test public void resumeAndVerificationAreDistinctConfirmedRuntimeActions()throws Exception {
        updateCandidate();View update=card("clientUpdateCard","Client update · PlayOnline");
        AlertDialog dialog=confirmation(update,"Open or resume PlayOnline update","requires verification again");respond(dialog,AlertDialog.BUTTON_POSITIVE);assertRuntimeAction("update-client");
        update=card("clientUpdateCard","Client update · PlayOnline");dialog=confirmation(update,"Verify completed update","file repair complete");
        respond(dialog,AlertDialog.BUTTON_POSITIVE);assertRuntimeAction("verify-client-update");assertFalse(button(update,"Activate verified update").isEnabled());
    }
    @Test public void verifiedActivationIsExplicitAndQueuesOnlyTheActivationJob()throws Exception {
        File candidate=updateCandidate();prepared.updatePhase("verified");View update=card("clientUpdateCard","Client update · PlayOnline");
        assertTrue(button(update,"Activate verified update").isEnabled());assertTrue(contains(update,"verified and ready for activation"));
        capture(update,920,"client-update-verified-wide.png");capture(update,400,"client-update-verified-narrow.png");
        AlertDialog dialog=confirmation(update,"Activate verified update","matching server revision and xiloader");
        respond(dialog,AlertDialog.BUTTON_NEGATIVE);assertEquals(candidate,prepared.selected("candidate"));assertUnchanged();
        dialog=confirmation(update,"Activate verified update","previous prepared client remains available for rollback");respond(dialog,AlertDialog.BUTTON_POSITIVE);
        assertTrue(WorkService.busy);assertEquals("Activating client update",WorkService.message);assertNotNull(field(WorkService.class,"pending"));
        Intent work=Shadows.shadowOf(context).getNextStartedService();assertNotNull(work);assertEquals(WorkService.class.getName(),work.getComponent().getClassName());
        assertEquals(candidate,prepared.selected("candidate"));assertUnchanged();
    }
    @Test public void discardRequiresConfirmationAndLeavesCurrentSelectionUntilWorkRuns()throws Exception {
        File candidate=updateCandidate();View update=card("clientUpdateCard","Client update · PlayOnline");
        AlertDialog dialog=confirmation(update,"Discard staged update","Downloaded changes in that copy will be lost");
        respond(dialog,AlertDialog.BUTTON_NEGATIVE);assertEquals(candidate,prepared.selected("candidate"));assertTrue(candidate.isDirectory());
        dialog=confirmation(update,"Discard staged update","Your active prepared client is retained");respond(dialog,AlertDialog.BUTTON_POSITIVE);
        assertTrue(WorkService.busy);assertEquals("Discarding client update",WorkService.message);assertNotNull(field(WorkService.class,"pending"));assertUnchanged();
    }
    @Test public void runtimeAndManagedServerBlockMaintenanceUntilStopped()throws Exception {
        updateCandidate();prepared.updatePhase("verified");set(server,"active",true);
        View update=card("clientUpdateCard","Client update · PlayOnline");
        for(String label:new String[]{"Open or resume PlayOnline update","Verify completed update","Activate verified update","Discard staged update"})assertFalse(label,button(update,label).isEnabled());
        set(server,"active",false);runtime.starting=true;update=card("clientUpdateCard","Client update · PlayOnline");
        for(String label:new String[]{"Open or resume PlayOnline update","Verify completed update","Activate verified update","Discard staged update"})assertFalse(label,button(update,label).isEnabled());
        assertTrue(button(update,"Open updater display").isEnabled());assertTrue(button(update,"Stop updater").isEnabled());runtime.starting=false;assertUnchanged();
    }
    @Test
    public void testInstallationExplainsIsolationAndShowsStagedWorkflowAtBothWidths()throws Exception {
        // Config.NONE has no APK package metadata; supply the test variant's package through its base context.
        Context base=activity.getBaseContext();Field baseField=ContextWrapper.class.getDeclaredField("mBase");baseField.setAccessible(true);
        baseField.set(activity,new ContextWrapper(base){@Override public String getPackageName(){return "io.github.russianranger.lsb.restoretest";}});
        updateCandidate();View update=card("clientUpdateCard","Client update · PlayOnline");
        assertEquals("io.github.russianranger.lsb.restoretest",activity.getPackageName());assertTrue(contains(update,"Test client updates here while your regular LSB installation stays separate"));
        capture(update,920,"client-update-staged-wide.png");capture(update,400,"client-update-staged-narrow.png");
        assertFalse(button(update,"Activate verified update").isEnabled());assertUnchanged();
    }
    @Test public void activationRejectsMissingRetargetedFailedAndChangedVerificationReceipts()throws Exception {
        File candidate=repairedCandidate();rejectedActivation(candidate);
        JSONObject valid=verification(candidate);
        receipts(candidate,new JSONObject(valid.toString()).put("generation",current.getName()));rejectedActivation(candidate);
        receipts(candidate,new JSONObject(valid.toString()).put("status","failed"));rejectedActivation(candidate);
        receipts(candidate,new JSONObject(valid.toString()).put("session_id","different-verification"));rejectedActivation(candidate);
        JSONObject changed=new JSONObject(valid.toString());String key=changed.getJSONObject("key_files").keys().next();
        changed.getJSONObject("key_files").put(key,"incorrect-hash");receipts(candidate,changed);rejectedActivation(candidate);
        receipts(candidate,valid);prepared.updatePhase("repair_pending");rejectedActivation(candidate);prepared.updatePhase("verified");
        byte[] altered=pe();altered[altered.length-1]=1;
        try(OutputStream out=new FileOutputStream(new File(candidate,"client/PlayOnlineViewer/pol.exe"))){out.write(altered);}
        rejectedActivation(candidate);
    }
    @Test public void verifiedActivationRequiresStoppedRuntimesAndPromotesMatchingClientPrefixPair()throws Exception {
        File candidate=repairedCandidate();receipts(candidate,verification(candidate));
        FilesEx.text(new File(candidate,"prefix/user.reg"),"updated registry");
        set(server,"active",true);rejectedActivation(candidate);set(server,"active",false);
        runtime.starting=true;rejectedActivation(candidate);runtime.starting=false;
        assertTrue(runtime.activateClientUpdate().contains("activated"));assertEquals(candidate,prepared.selected("current"));assertEquals(current,prepared.selected("previous"));assertNull(prepared.selected("candidate"));
        assertEquals("updated registry",FilesEx.read(new File(prepared.selected("current"),"prefix/user.reg"),4096));
        assertEquals("accepted registry",FilesEx.read(new File(prepared.selected("previous"),"prefix/user.reg"),4096));
        assertEquals(runtimeSettings,context.getSharedPreferences("runtime",0).getAll());assertEquals(serverSettings,context.getSharedPreferences("server",0).getAll());
        assertTrue(runtime.rollbackPreparation().contains("restored"));assertEquals(candidate,prepared.selected("previous"));assertUnchanged();
    }
}
