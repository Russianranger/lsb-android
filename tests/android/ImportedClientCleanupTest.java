package io.github.russianranger.lsb;

import android.content.Context;
import io.github.russianranger.lsb.core.*;
import java.io.*;
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
public class ImportedClientCleanupTest {
    private static final SafeZip.Progress QUIET=message->{};
    private File home,files,managed,gen;
    private ClientStore source;
    private PreparedClientStore prepared;
    private StorageBackups backups;
    private Map<String,String> activeBefore;
    private static byte[] pe(int marker){byte[] b=new byte[256];b[0]='M';b[1]='Z';b[0x3c]=0x40;b[0x40]='P';b[0x41]='E';b[0x44]=0x4c;b[0x45]=1;b[0x58]=0x0b;b[0x59]=1;b[200]=(byte)marker;return b;}
    static byte[] clientZip()throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            for(String path:new String[]{"POL/polcore.dll","POL/pol.exe","POL/xiloader.exe","FFXI/FFXi.dll","FFXI/FFXiMain.dll"}){zip.putNextEntry(new ZipEntry(path));zip.write(pe(1));zip.closeEntry();}
            for(String path:new String[]{"FFXI/ROM/0/0.DAT","FFXI/ROM/0/1.DAT","FFXI/USER/settings","POL/usr/settings"}){zip.putNextEntry(new ZipEntry(path));zip.write(new byte[]{1,2,3});zip.closeEntry();}
        }return bytes.toByteArray();
    }
    static File prepare(ClientStore source,PreparedClientStore prepared,File prefix)throws Exception {
        FilesEx.text(new File(prefix,"lsb-prefix-ready.json"),"{}");FilesEx.text(new File(prefix,"user.reg"),"registry");
        File gen=prepared.prepare(source,prefix,QUIET);
        FilesEx.text(new File(gen,"initialization-passed.json"),new JSONObject().put("status","passed").put("generation",gen.getName()).toString());prepared.promote(gen);return gen;
    }
    private static Map<String,String> snapshot(File root)throws Exception {
        Map<String,String> result=new TreeMap<>();try(java.util.stream.Stream<Path> walk=Files.walk(root.toPath())){
            for(Path path:(Iterable<Path>)walk::iterator)if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))result.put(root.toPath().relativize(path).toString(),FilesEx.hash(path.toFile()));
        }return result;
    }
    @Before public void before()throws Exception {
        home=Files.createTempDirectory("lsb-import-cleanup-").toFile();files=new File(home,"files");managed=new File(home,"managed");files.mkdirs();managed.mkdirs();
        source=new ClientStore(new File(managed,"session"));source.importClient(new ByteArrayInputStream(clientZip()),false,true,QUIET);
        source.saveConfig(new LaunchConfig("127.0.0.1","US",source.config().polCore));
        prepared=new PreparedClientStore(new File(files,"rt/clients"));gen=prepare(source,prepared,new File(home,"seed"));
        FilesEx.text(new File(gen,"client/FFXI/USER/settings"),"played settings");FilesEx.text(new File(gen,"client/POL/usr/settings"),"current login settings");
        FilesEx.text(new File(files,"server-runtime/state/database/character"),"keep character");activeBefore=snapshot(gen);
        backups=new StorageBackups(files,managed,new File(home,"no_backup/session-transfer"));
    }
    @After public void after()throws Exception {Thread.interrupted();TarExtractor.remove(home);}
    private interface Action{void run()throws Exception;}
    private void fails(Action action)throws Exception {try{action.run();fail("Unsafe removal accepted");}catch(IOException expected){}assertTrue(source.hasClient());assertFalse(source.releasedImport());}
    private StorageBackups.Item original()throws Exception {for(StorageBackups.Item item:backups.inventory(QUIET))if(item.id.equals("client-import"))return item;throw new AssertionError("Original not listed");}
    @Test public void removesOnlyOriginalPayloadAndPreservesLaunchLoaderConfigurationAndCharacters()throws Exception {
        assertTrue(original().deletable);String inventory=source.inventory(),loaderHash=FilesEx.hash(new File(source.client(),"POL/xiloader.exe"));
        assertTrue(backups.delete("client-import",QUIET).contains("Original imported client files removed"));
        assertFalse(source.hasClient());assertTrue(source.releasedImport());assertEquals(inventory,source.inventory());assertEquals("127.0.0.1",source.config().host);
        assertEquals(loaderHash,FilesEx.hash(source.retainedLoader()));assertEquals(activeBefore,snapshot(gen));
        assertEquals("keep character",FilesEx.read(new File(files,"server-runtime/state/database/character"),100));
        assertNotNull(ClientLaunchValidation.manifest(gen,prepared.metadata(gen),QUIET));assertFalse(new PreparedLoaderUpdate(prepared).selection(source).getBoolean("differs"));
        assertTrue(source.summary().contains("removed"));source.recover();assertFalse(source.hasClient());assertEquals(gen,prepared.selected("current"));
    }
    @Test public void preparedPersonalSettingsArePreservedWhenReimportingAfterRemoval()throws Exception {
        backups.delete("client-import",QUIET);
        ClientStore reimport=new ClientStore(source.current().getParentFile(),new File(gen,"client"),prepared.metadata(gen).getProperty("core"));
        reimport.importClient(new ByteArrayInputStream(clientZip()),false,true,QUIET);
        assertTrue(reimport.hasClient());assertFalse(reimport.releasedImport());
        assertEquals("played settings",FilesEx.read(new File(reimport.client(),"FFXI/USER/settings"),100));assertEquals("current login settings",FilesEx.read(new File(reimport.client(),"POL/usr/settings"),100));assertEquals(activeBefore,snapshot(gen));
    }
    @Test public void loaderImportAndApplyStillWorkWithoutTheLargeOriginalCopy()throws Exception {
        backups.delete("client-import",QUIET);PreparedLoaderUpdate loader=new PreparedLoaderUpdate(prepared);
        loader.importLoader(source,new ByteArrayInputStream(pe(9)),QUIET);String hash=loader.selection(source).getString("imported_sha256");
        assertTrue(loader.apply(source,hash,QUIET).contains("applied"));assertEquals(hash,FilesEx.hash(new File(gen,"client/POL/xiloader.exe")));assertFalse(source.hasClient());
        assertEquals(activeBefore.get("client/FFXI/FFXiMain.dll"),FilesEx.hash(new File(gen,"client/FFXI/FFXiMain.dll")));assertEquals("played settings",FilesEx.read(new File(gen,"client/FFXI/USER/settings"),100));
        assertNotNull(ClientLaunchValidation.manifest(gen,prepared.metadata(gen),QUIET));
    }
    @Test public void stagedRepairAndPlayOnlineUpdateStillUsePreparedFiles()throws Exception {
        backups.delete("client-import",QUIET);File repair=prepared.stageRepair(QUIET);
        assertEquals("played settings",FilesEx.read(new File(repair,"client/FFXI/USER/settings"),100));prepared.discard();
        File update=prepared.stageUpdate(QUIET);assertEquals("current login settings",FilesEx.read(new File(update,"client/POL/usr/settings"),100));assertEquals(activeBefore,snapshot(gen));
    }
    @Test public void changedOrMissingRomAndUniqueUnpreparedImportFilesPreventRemoval()throws Exception {
        File rom=new File(gen,"client/FFXI/ROM/0/1.DAT");Files.write(rom.toPath(),new byte[]{3,2,1});fails(()->backups.delete("client-import",QUIET));
        Files.write(rom.toPath(),new byte[]{1,2,3});Files.delete(rom.toPath());fails(()->backups.delete("client-import",QUIET));
        Files.write(rom.toPath(),new byte[]{1,2,3});FilesEx.text(new File(source.client(),"original-only.dat"),"keep unique import");fails(()->backups.delete("client-import",QUIET));
    }
    @Test public void incompleteReceiptChangedBinaryCandidateAndLinksPreventRemoval()throws Exception {
        File receipt=new File(gen,"initialization-passed.json");String passed=FilesEx.read(receipt,4096);FilesEx.text(receipt,"{}");assertFalse(original().deletable);fails(()->backups.delete("client-import",QUIET));FilesEx.text(receipt,passed);
        File candidate=prepared.stageRepair(QUIET);assertFalse(original().deletable);fails(()->backups.delete("client-import",QUIET));prepared.discard();
        Path dll=new File(gen,"client/FFXI/FFXiMain.dll").toPath();Files.write(dll,pe(9));fails(()->backups.delete("client-import",QUIET));Files.write(dll,pe(1));
        Path link=new File(source.client(),"FFXI/ROM/0/escape").toPath();Files.createSymbolicLink(link,new File(gen,"client/FFXI/ROM/0/0.DAT").toPath());fails(()->backups.delete("client-import",QUIET));
    }
    @Test public void cancellationBeforeQuarantineKeepsOriginalAndActiveClient()throws Exception {
        try{backups.delete("client-import",message->{if(message.startsWith("Comparing original"))Thread.currentThread().interrupt();});fail();}catch(IOException expected){}finally{Thread.interrupted();}
        assertTrue(source.hasClient());assertFalse(source.releasedImport());assertEquals(activeBefore,snapshot(gen));
    }
    @Test public void interruptedRemovalCanBeCompletedWithoutRestoringOrTouchingPreparedClient()throws Exception {
        try{backups.delete("client-import",message->{if(message.startsWith("Removing Original"))Thread.currentThread().interrupt();});fail();}catch(IOException expected){}finally{Thread.interrupted();}
        assertTrue(source.releasedImport());assertEquals(activeBefore,snapshot(gen));source.recover();assertFalse(source.hasClient());
        String cleanup=null;for(StorageBackups.Item item:backups.inventory(QUIET))if(item.id.startsWith("cleanup:"))cleanup=item.id;
        assertNotNull(cleanup);backups.delete(cleanup,QUIET);assertEquals(activeBefore,snapshot(gen));assertNotNull(ClientLaunchValidation.manifest(gen,prepared.metadata(gen),QUIET));
    }
    @Test public void metadataCannotBeDeletedAndPreservationFailsClosedWithoutPreparedClient()throws Exception {
        backups.delete("client-import",QUIET);
        try{backups.delete("client-import-settings",QUIET);fail();}catch(IOException expected){}
        try{source.importClient(new ByteArrayInputStream(clientZip()),false,true,QUIET);fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("preservation"));}
        assertTrue(source.releasedImport());assertEquals(activeBefore,snapshot(gen));
    }
    @Test public void pendingImportSwapLoaderJournalAndSessionRestoreBlockCleanup()throws Exception {
        for(File marker:new File[]{new File(source.current().getParentFile(),"incoming/pending.properties"),new File(source.current().getParentFile(),"swap"),new File(gen,"loader-update-pending"),new File(files,"session-restore-settings.json")}){
            FilesEx.text(marker,"{}");fails(()->backups.delete("client-import",QUIET));FilesEx.delete(marker);assertEquals(activeBefore,snapshot(gen));
        }
    }
    @Test public void invalidStandaloneLoaderDoesNotChangeRetainedLoaderOrWorkingFiles()throws Exception {
        backups.delete("client-import",QUIET);String hash=FilesEx.hash(source.retainedLoader()),inventory=source.inventory();
        try{new PreparedLoaderUpdate(prepared).importLoader(source,new ByteArrayInputStream(new byte[100]),QUIET);fail();}catch(IOException expected){}
        assertEquals(hash,FilesEx.hash(source.retainedLoader()));assertEquals(inventory,source.inventory());assertEquals(activeBefore,snapshot(gen));
    }
}
