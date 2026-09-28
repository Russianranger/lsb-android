package io.github.russianranger.lsb;

import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class PreparedLoaderUpdateTest {
    private static final SafeZip.Progress QUIET=message->{};
    private File home,current,sourceLoader,workingLoader;
    private ClientStore imported;
    private PreparedClientStore store;
    private PreparedLoaderUpdate update;
    private String oldHash,newHash;
    private Map<String,String> baseline;
    private static byte[] pe(int marker){
        byte[] bytes=new byte[256];bytes[0]='M';bytes[1]='Z';bytes[0x3c]=0x40;bytes[0x40]='P';bytes[0x41]='E';
        bytes[0x44]=0x4c;bytes[0x45]=1;bytes[0x58]=0x0b;bytes[0x59]=1;bytes[200]=(byte)marker;return bytes;
    }
    private static byte[] clientZip()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            for(String name:new String[]{"POL/polcore.dll","POL/pol.exe","FFXI/FFXi.dll","FFXI/FFXiMain.dll","FFXI/ashitav4/bootloader/xiloader.exe"}){
                zip.putNextEntry(new ZipEntry(name));zip.write(pe(1));zip.closeEntry();
            }
            zip.putNextEntry(new ZipEntry("FFXI/ROM/0/0.DAT"));zip.write(new byte[]{2,3,4});zip.closeEntry();
        }return bytes.toByteArray();
    }
    private static Map<String,String> snapshot(File base)throws Exception {
        Map<String,String> result=new TreeMap<>();
        try(java.util.stream.Stream<Path> walk=Files.walk(base.toPath())){
            for(Path p:(Iterable<Path>)walk::iterator)if(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))result.put(base.toPath().relativize(p).toString(),FilesEx.hash(p.toFile()));
        }return result;
    }
    @Before public void before()throws Exception {
        home=Files.createTempDirectory("lsb-loader-update-").toFile();imported=new ClientStore(new File(home,"imported"));
        imported.importClient(new ByteArrayInputStream(clientZip()),false,true,QUIET);
        File prefix=new File(home,"seed");FilesEx.text(new File(prefix,"lsb-prefix-ready.json"),"{}");FilesEx.text(new File(prefix,"user.reg"),"personal registry");
        store=new PreparedClientStore(new File(home,"prepared"));current=store.prepare(imported,prefix,QUIET);
        FilesEx.text(new File(current,"initialization-passed.json"),new JSONObject().put("status","passed").put("generation",current.getName()).put("session_id","original-dll-prefix-check").toString());store.promote(current);
        // Device reproduction: activated PlayOnline update has newer DLLs than the imported source.
        Files.write(new File(current,"client/FFXI/FFXi.dll").toPath(),pe(7));Files.write(new File(current,"client/FFXI/FFXiMain.dll").toPath(),pe(8));
        FilesEx.text(new File(current,"client/FFXI/USER/settings"),"keep personal settings");
        ClientInspector.Snapshot inspected=ClientInspector.inspect(new File(current,"client"),store.metadata(current).getProperty("core"),QUIET);
        FilesEx.text(new File(current,"source-inventory.json"),inspected.inventory);
        Properties meta=store.metadata(current);meta.setProperty("inventorySha256",FilesEx.hash(new File(current,"source-inventory.json")));meta.setProperty("bytes",Long.toString(inspected.bytes));
        StringWriter props=new StringWriter();meta.store(props,null);FilesEx.text(new File(current,"metadata.properties"),props.toString());
        for(String name:new String[]{"last-launch.json","last-result.json","update-verified.json"})FilesEx.text(new File(current,name),"{\"status\":\"passed\",\"old_loader\":true}");
        workingLoader=inspected.loader;oldHash=FilesEx.hash(workingLoader);baseline=snapshot(current);
        imported.importLoader(new ByteArrayInputStream(pe(9)),QUIET);sourceLoader=new File(imported.client(),"FFXI/ashitav4/bootloader/xiloader.exe");newHash=FilesEx.hash(sourceLoader);
        update=new PreparedLoaderUpdate(store);
    }
    @After public void after()throws Exception {
        if(home!=null)try(java.util.stream.Stream<Path> walk=Files.walk(home.toPath())){walk.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});}
    }
    private interface Action{void run()throws Exception;}
    private static void fails(Action action)throws Exception {try{action.run();fail("Operation should reject unsafe state");}catch(IOException expected){}}
    private void originalRestored()throws Exception {assertEquals(baseline,snapshot(current));assertEquals(oldHash,FilesEx.hash(workingLoader));assertFalse(PreparedLoaderUpdate.pending(current));}
    @Test public void appliesOnlyLoaderPreservesUpdatedGameAndSettingsAndInvalidatesOldProof()throws Exception {
        JSONObject selection=update.selection(imported);assertTrue(selection.getBoolean("differs"));assertEquals(oldHash,selection.getString("prepared_sha256"));
        assertTrue(update.apply(imported,newHash,QUIET).contains("applied"));assertEquals(newHash,FilesEx.hash(workingLoader));
        for(String path:new String[]{"client/FFXI/FFXi.dll","client/FFXI/FFXiMain.dll","client/FFXI/ROM/0/0.DAT","client/FFXI/USER/settings","prefix/user.reg","copy-complete"})assertEquals(path,baseline.get(path),FilesEx.hash(new File(current,path)));
        assertEquals(current,store.selected("current"));assertNull(store.selected("candidate"));assertFalse(update.selection(imported).getBoolean("differs"));
        assertEquals("pending",store.metadata(current).getProperty("loaderValidation"));
        JSONObject initialized=new JSONObject(FilesEx.read(new File(current,"initialization-passed.json"),16384));assertEquals("passed",initialized.getString("status"));assertEquals("pending",initialized.getString("loader_validation"));
        for(String old:new String[]{"last-launch.json","last-result.json","update-verified.json"})assertFalse(new File(current,old).exists());
        assertEquals(FilesEx.hash(new File(current,"source-inventory.json")),store.metadata(current).getProperty("inventorySha256"));
        JSONObject inventory=new JSONObject(FilesEx.read(new File(current,"source-inventory.json"),131072));
        assertEquals(new JSONObject(ClientInspector.inspect(new File(current,"client"),store.metadata(current).getProperty("core"),QUIET).inventory).getJSONArray("keyFiles").toString(),inventory.getJSONArray("keyFiles").toString());
        File[] history=new File(current,"loader-updates").listFiles();assertNotNull(history);assertEquals(1,history.length);assertEquals(oldHash,FilesEx.hash(new File(history[0],"0.old")));
        Map<String,String> after=snapshot(current);assertTrue(update.apply(imported,newHash,QUIET).contains("already uses"));assertEquals(after,snapshot(current));
    }
    @Test public void ordinaryFailureRestoresEveryTarget()throws Exception {
        try{update.apply(imported,newHash,message->{if(message.startsWith("Updated xiloader metadata · 2"))throw new IllegalStateException("injected I/O failure");});fail();}catch(IllegalStateException expected){}
        originalRestored();
    }
    private static final class SimulatedCrash extends Error{}
    @Test public void restartRecoversEveryPossibleInterruptedReplacement()throws Exception {
        for(int stop=1;stop<=7;stop++){
            final int point=stop;
            try{update.apply(imported,newHash,message->{if(message.startsWith("Updated xiloader metadata · "+point+" /"))throw new SimulatedCrash();});fail();}catch(SimulatedCrash expected){}
            assertTrue(PreparedLoaderUpdate.pending(current));new PreparedLoaderUpdate(new PreparedClientStore(new File(home,"prepared"))).recover(QUIET);originalRestored();
        }
    }
    @Test public void recoveryRefusesToOverwriteUnexpectedChanges()throws Exception {
        try{update.apply(imported,newHash,message->{if(message.startsWith("Updated xiloader metadata · 1"))throw new SimulatedCrash();});fail();}catch(SimulatedCrash expected){}
        Files.write(workingLoader.toPath(),pe(22));fails(()->update.recover(QUIET));
        assertArrayEquals(pe(22),Files.readAllBytes(workingLoader.toPath()));
    }
    @Test public void crashDuringRollbackCleanupCannotLeaveAnUnrecoverableJournal()throws Exception {
        try{update.apply(imported,newHash,message->{if(message.startsWith("Updated xiloader metadata · 1"))throw new SimulatedCrash();});fail();}catch(SimulatedCrash expected){}
        try{update.recover(message->{if(message.startsWith("Previous xiloader restored"))throw new SimulatedCrash();});fail();}catch(SimulatedCrash expected){}
        assertFalse(PreparedLoaderUpdate.pending(current));assertEquals(oldHash,FilesEx.hash(workingLoader));
        File[] leftovers=current.listFiles(file->file.getName().startsWith("loader-update-aborted-"));assertNotNull(leftovers);assertEquals(1,leftovers.length);
        Files.delete(new File(leftovers[0],"0.old").toPath());update.recover(QUIET);assertEquals(oldHash,FilesEx.hash(workingLoader));
    }
    @Test public void crashAfterCommitRetainsNewLoaderAndArchivesOldProof()throws Exception {
        try{update.apply(imported,newHash,message->{if(message.startsWith("Xiloader update committed"))throw new SimulatedCrash();});fail();}catch(SimulatedCrash expected){}
        assertTrue(PreparedLoaderUpdate.pending(current));update.recover(QUIET);assertFalse(PreparedLoaderUpdate.pending(current));
        assertEquals(newHash,FilesEx.hash(workingLoader));assertFalse(new File(current,"last-launch.json").exists());
        File[] history=new File(current,"loader-updates").listFiles();assertNotNull(history);assertEquals(1,history.length);assertEquals(oldHash,FilesEx.hash(new File(history[0],"0.old")));
    }
    @Test public void changedIdentityOrUnrelatedDllCannotBeAccepted()throws Exception {
        fails(()->update.apply(imported,oldHash,QUIET));originalRestored();
        Files.write(new File(current,"client/FFXI/FFXiMain.dll").toPath(),pe(42));fails(()->update.apply(imported,newHash,QUIET));assertEquals(oldHash,FilesEx.hash(workingLoader));
    }
    @Test public void missingOrLinkedLoaderAndStagedUpdateAreRejected()throws Exception {
        File candidate=store.stageUpdate(QUIET);fails(()->update.apply(imported,newHash,QUIET));assertEquals(candidate,store.selected("candidate"));store.discard();
        byte[] original=Files.readAllBytes(sourceLoader.toPath());Files.delete(sourceLoader.toPath());Files.createSymbolicLink(sourceLoader.toPath(),workingLoader.toPath());
        fails(()->update.apply(imported,newHash,QUIET));Files.delete(sourceLoader.toPath());Files.write(sourceLoader.toPath(),original);originalRestored();
        File dir=workingLoader.getParentFile(),saved=new File(dir.getParentFile(),"saved-bootloader");Files.move(dir.toPath(),saved.toPath());Files.createSymbolicLink(dir.toPath(),saved.toPath());
        fails(()->update.apply(imported,newHash,QUIET));Files.delete(dir.toPath());Files.move(saved.toPath(),dir.toPath());originalRestored();
    }
    @Test public void runtimeGuardRejectsChangeWhileClientIsRunning()throws Exception {
        ClientRuntime.resetAfterRestore();ClientRuntime runtime=ClientRuntime.get(RuntimeEnvironment.getApplication());
        Field active=ClientRuntime.class.getDeclaredField("active");active.setAccessible(true);active.set(runtime,true);
        try{fails(()->runtime.applyImportedLoader(newHash,QUIET));}finally{active.set(runtime,false);ClientRuntime.resetAfterRestore();}
        originalRestored();
    }
}
