package io.github.russianranger.lsb;

import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StorageBackupsTest {
    private File home,files,managed,work;private StorageBackups store;
    private static final String A="11111111-1111-1111-1111-111111111111",B="22222222-2222-2222-2222-222222222222",C="33333333-3333-3333-3333-333333333333",D="44444444-4444-4444-4444-444444444444";
    private static final String HASH=String.join("",Collections.nCopies(64,"a"));
    private static final SafeZip.Progress QUIET=s->{};
    @Before public void setUp()throws Exception {
        home=Files.createTempDirectory("backup-browser-").toFile();files=new File(home,"files");managed=new File(home,"managed");work=new File(home,"no_backup/session-transfer");
        files.mkdirs();managed.mkdirs();work.mkdirs();store=new StorageBackups(files,managed,work);
        prepared(A,B,C);server(A,B);
        for(String id:new String[]{A,B,C,D}){text(files,"rt/clients/"+id+"/client/test",id);text(files,"rt/clients/"+id+"/copy-complete","1");text(files,"rt/clients/"+id+"/initialization-passed.json","{}");text(files,"server-runtime/state/generations/"+id+"/database/data",id);text(files,"server-runtime/state/generations/"+id+"/deployment.json","{}");}
    }
    @After public void clean()throws Exception {
        Files.walkFileTree(home.toPath(),new SimpleFileVisitor<Path>(){
            @Override public FileVisitResult preVisitDirectory(Path p,java.nio.file.attribute.BasicFileAttributes a){p.toFile().setWritable(true,true);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path p,java.nio.file.attribute.BasicFileAttributes a)throws IOException {Files.delete(p);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path p,IOException e)throws IOException {Files.delete(p);return FileVisitResult.CONTINUE;}
        });
    }
    private void prepared(String current,String previous,String candidate)throws Exception {Properties p=new Properties();if(current!=null)p.setProperty("current",current);if(previous!=null)p.setProperty("previous",previous);if(candidate!=null)p.setProperty("candidate",candidate);StringWriter w=new StringWriter();p.store(w,null);text(files,"rt/clients/state.properties",w.toString());}
    private void server(String current,String previous)throws Exception {text(files,"server-runtime/state/active.json",new JSONObject().put("current",current).put("previous",previous).toString());}
    private static void text(File root,String path,String value)throws IOException {FilesEx.text(new File(root,path),value);}
    private StorageBackups.Item item(String id)throws Exception {for(StorageBackups.Item item:store.inventory(QUIET))if(item.id.equals(id))return item;throw new AssertionError("Missing item "+id);}
    private interface Failing {void run()throws Exception;}
    private static void fails(Failing body)throws Exception {try{body.run();fail("Expected failure");}catch(IOException expected){}}
    @Test public void currentAndCandidateProtectedWhilePreviousAndOrphansListed()throws Exception {
        assertFalse(item("client:"+A).deletable);assertFalse(item("client:"+C).deletable);assertTrue(item("client:"+B).deletable);assertTrue(item("client:"+D).deletable);
        assertFalse(item("server:"+A).deletable);assertTrue(item("server:"+B).deletable);assertTrue(item("server:"+D).deletable);
        fails(()->store.delete("client:"+A,QUIET));fails(()->store.delete("client:"+C,QUIET));fails(()->store.delete("server:"+A,QUIET));
    }
    @Test public void removalClearsOnlyPreviousPointerAndRetainsActiveFiles()throws Exception {
        store.delete("client:"+B,QUIET);Properties p=new PreparedClientStore(new File(files,"rt/clients")).state();assertEquals(A,p.getProperty("current"));assertEquals(C,p.getProperty("candidate"));assertFalse(p.containsKey("previous"));
        assertFalse(new File(files,"rt/clients/"+B).exists());assertEquals(A,FilesEx.read(new File(files,"rt/clients/"+A+"/client/test"),100));
        store.delete("server:"+B,QUIET);JSONObject server=new JSONObject(FilesEx.read(new File(files,"server-runtime/state/active.json"),16384));assertEquals(A,server.getString("current"));assertFalse(server.has("previous"));assertTrue(new File(files,"server-runtime/state/generations/"+A+"/database/data").isFile());
    }
    @Test public void staleSelectionCannotRemoveNewlyActivatedCopy()throws Exception {
        assertTrue(item("client:"+B).deletable);prepared(B,A,C);fails(()->store.delete("client:"+B,QUIET));assertTrue(new File(files,"rt/clients/"+B).isDirectory());
        assertTrue(item("server:"+B).deletable);server(B,A);fails(()->store.delete("server:"+B,QUIET));
    }
    @Test public void linksAreCountedButNeverFollowedForSizeBrowseOrDelete()throws Exception {
        File external=new File(home,"outside");text(external,"secret","keep me");Path link=new File(files,"rt/clients/"+B+"/client/escape").toPath();Files.createSymbolicLink(link,external.toPath());
        StorageBackups.Item backup=item("client:"+B);assertEquals(1,backup.links);assertEquals(39,backup.bytes); // UUID + copy marker + {} receipt
        assertTrue(store.children("client:"+B,"client").stream().anyMatch(n->n.link&&!n.directory));fails(()->store.children("client:"+B,"client/escape"));
        store.delete("client:"+B,QUIET);assertEquals("keep me",FilesEx.read(new File(external,"secret"),100));
    }
    @Test public void traversalAndUnlistedPathsCannotBeDeletedOrBrowsed()throws Exception {
        text(files,"private.txt","keep");fails(()->store.children("client:"+A,"../"));fails(()->store.children("client:"+A,home.getPath()));fails(()->store.delete("../../private.txt",QUIET));assertTrue(new File(files,"private.txt").exists());
        text(files,"rt/clients/"+B+"/client/literal\\filename","ok");assertTrue(store.children("client:"+B,"client").stream().anyMatch(n->n.name.equals("literal\\filename")));store.delete("client:"+B,QUIET);
    }
    @Test public void generationLinkAndParentLinkStayProtected()throws Exception {
        Path generation=new File(files,"rt/clients/"+D).toPath();Path original=generation.resolveSibling("saved");Files.move(generation,original);Files.createSymbolicLink(generation,original);
        assertFalse(item("client:"+D).deletable);fails(()->store.delete("client:"+D,QUIET));assertTrue(Files.isDirectory(original));
        Path clients=new File(files,"rt/clients").toPath();Path moved=home.toPath().resolve("elsewhere");Files.move(clients,moved);Files.createSymbolicLink(clients,moved);fails(()->store.inventory(QUIET));
    }
    @Test public void pairedFexPrefixesRemovedOnlyForSelectedInactiveClient()throws Exception {
        for(String id:new String[]{A,B,C})text(files,"rt/fex/prefixes/"+HASH+"/"+id+"/user.reg",id);
        assertTrue(item("client:"+B).detail.contains("FEX"));assertEquals(75,item("client:"+B).bytes);
        store.delete("client:"+B,QUIET);assertFalse(new File(files,"rt/fex/prefixes/"+HASH+"/"+B).exists());assertTrue(new File(files,"rt/fex/prefixes/"+HASH+"/"+A+"/user.reg").isFile());assertTrue(new File(files,"rt/fex/prefixes/"+HASH+"/"+C+"/user.reg").isFile());
    }
    @Test public void interruptedRemovalLeavesDeselectedCopyForRetry()throws Exception {
        for(int i=0;i<1010;i++)text(files,"rt/clients/"+B+"/many/"+i,"x");
        try{store.delete("client:"+B,message->{if(message.contains("entries"))throw new RuntimeException("simulate interruption");});fail("Expected simulated interruption");}catch(RuntimeException expected){}
        assertFalse(new PreparedClientStore(new File(files,"rt/clients")).state().containsKey("previous"));String cleanup=null;for(StorageBackups.Item item:store.inventory(QUIET))if(item.id.startsWith("cleanup:"))cleanup=item.id;assertNotNull(cleanup);store.delete(cleanup,QUIET);
        assertEquals(A,new PreparedClientStore(new File(files,"rt/clients")).state().getProperty("current"));
    }
    @Test public void readOnlyNestedFoldersCanBeRemovedWithoutChangingOutsideModes()throws Exception {
        Path folder=new File(files,"rt/clients/"+B+"/client").toPath();Files.setPosixFilePermissions(folder,EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_EXECUTE));store.delete("client:"+B,QUIET);assertFalse(Files.exists(folder));
    }
    @Test public void journalsAndPendingSettingsBlockRemoval()throws Exception {
        text(work,"transaction.properties","phase=COMMITTED");fails(()->store.delete("client:"+B,QUIET));new File(work,"transaction.properties").delete();text(files,"session-restore-settings.json","{}");fails(()->store.delete("client:"+B,QUIET));assertTrue(new File(files,"rt/clients/"+B).exists());
    }
    @Test public void missingSelectionMetadataProtectsGenerations()throws Exception {
        new File(files,"rt/clients/state.properties").delete();new File(files,"server-runtime/state/active.json").delete();assertFalse(item("client:"+B).deletable);assertFalse(item("server:"+B).deletable);
    }
    @Test public void incompleteCurrentImportAndRuntimeKeepRecoveryCopies()throws Exception {
        text(managed,"session/previous/client/file","old");text(files,"rt/root.previous/file","old");new File(managed,"session/current").mkdirs();new File(files,"rt/root").mkdirs();assertFalse(item("client-import-previous").deletable);assertFalse(item("client-runtime-previous").deletable);
        text(managed,"session/current/client/file","current");text(files,"rt/root/lsb-runtime.sha256","hash");assertTrue(item("client-import-previous").deletable);assertTrue(item("client-runtime-previous").deletable);store.delete("client-import-previous",QUIET);assertTrue(new File(managed,"session/current/client/file").isFile());
    }
    @Test public void firstServerDeploymentAllowsNullOrEmptyPrevious()throws Exception {
        server(A,"");assertFalse(item("server:"+A).deletable);assertTrue(item("server:"+B).deletable);
        text(files,"server-runtime/state/active.json",new JSONObject().put("current",A).put("previous",JSONObject.NULL).toString());assertTrue(item("server:"+B).deletable);
    }
    @Test public void missingCurrentReceiptKeepsAllGenerationsProtected()throws Exception {
        new File(files,"rt/clients/"+A+"/initialization-passed.json").delete();assertFalse(item("client:"+B).deletable);
        new File(files,"server-runtime/state/generations/"+A+"/deployment.json").delete();assertFalse(item("server:"+B).deletable);
    }
    @Test public void importedAndExportedSqlCanBeRemovedWithoutDatabase()throws Exception {
        text(files,"server-runtime/state/import.sql","import");text(files,"server-runtime/state/export.sql","export");store.delete("sql-import",QUIET);store.delete("sql-export",QUIET);assertTrue(new File(files,"server-runtime/state/generations/"+A+"/database/data").isFile());
    }
}
