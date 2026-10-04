package io.github.russianranger.lsb;

import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class AppStorageTest {
    private File home,data,external;private List<AppStorage.Root> roots;
    private static final SafeZip.Progress QUIET=s->{};
    private static final AppStorage.Stats STATS=(p,a)->new AppStorage.Stat(a.fileKey(),a.isRegularFile()?512:0);
    @Before public void before()throws Exception {
        home=Files.createTempDirectory("app-accounting-").toFile();data=new File(home,"data");external=new File(home,"external");data.mkdirs();external.mkdirs();
        roots=Arrays.asList(new AppStorage.Root("internal","Internal data",data),new AppStorage.Root("external","External files",external));
    }
    @After public void after()throws Exception {
        Files.walkFileTree(home.toPath(),new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path p,java.nio.file.attribute.BasicFileAttributes a)throws IOException {Files.delete(p);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path p,IOException e)throws IOException {Files.delete(p);return FileVisitResult.CONTINUE;}
        });
    }
    private void text(File root,String path,String value)throws Exception {FilesEx.text(new File(root,path),value);}
    @Test public void allFoldersCountIncludingBuildCacheCheckpointsRestoreStagingAndUnknownFiles()throws Exception {
        text(data,"files/rt/clients/current/client/game","game");
        text(data,"files/server-runtime/state/compiler-cache/object","cache");
        text(data,"files/server-runtime/state/checkpoints/kept.sql","checkpoint");
        text(data,"no_backup/session-transfer/old-files/game","recovery");
        text(data,"cache/new-format/cache","cached");text(data,"code_cache/dex","dex");text(data,"shared_prefs/settings.xml","prefs");text(data,"databases/extra.db","db");text(data,"unknown/new-folder/data","other");
        text(external,"lsb/session/current/client/game","import");text(external,"outside-lsb/leftover.zip","zip");
        AppStorage.Report report=AppStorage.scan(roots,QUIET,STATS);
        assertEquals(4+5+10+8+6+3+5+2+5+6+3,report.bytes);assertEquals(11*512,report.allocated);assertEquals(0,report.errors);
        assertTrue(report.entries.stream().anyMatch(e->e.label.endsWith(" / no_backup")));
        assertTrue(report.entries.stream().anyMatch(e->e.label.endsWith(" / outside-lsb")));
    }
    @Test public void nestedRootsAndHardLinksDoNotDoubleCountAndSparseAllocationIsSeparate()throws Exception {
        text(data,"files/game","payload");Files.createLink(new File(external,"same-game").toPath(),new File(data,"files/game").toPath());
        List<AppStorage.Root> nested=new ArrayList<>(roots);nested.add(new AppStorage.Root("nested","Nested",new File(data,"files")));
        AppStorage.Report report=AppStorage.scan(nested,QUIET,STATS);assertEquals(7,report.bytes);assertEquals(512,report.allocated);
        try(RandomAccessFile sparse=new RandomAccessFile(new File(external,"sparse"),"rw")){sparse.setLength(2*1024*1024);}
        report=AppStorage.scan(nested,QUIET,STATS);assertEquals(7+2*1024*1024,report.bytes);assertEquals(1024,report.allocated);
    }
    @Test public void appBrowserShowsFolderSizesWithoutOpeningLinksOrTraversal()throws Exception {
        text(data,"files/unknown/nested/data","hidden");text(external,"secret","do not count link targets");
        Files.createSymbolicLink(new File(data,"files/escape").toPath(),external.toPath());
        AppStorage.Report report=AppStorage.scan(Collections.singletonList(roots.get(0)),QUIET,STATS);assertEquals(6,report.bytes);
        List<StorageBackups.Node> children=AppStorage.children(roots,"internal:files","",QUIET,STATS);
        assertEquals(6,children.stream().filter(n->n.name.equals("unknown")).findFirst().get().bytes);
        assertTrue(children.stream().anyMatch(n->n.name.equals("escape")&&n.link));
        for(String path:new String[]{"escape","../","/tmp"})try{AppStorage.children(roots,"internal:files",path,QUIET,STATS);fail("Unsafe browse allowed");}catch(IOException expected){}
        StorageBackups store=new StorageBackups(new File(data,"files"),external,new File(data,"no_backup/session-transfer"));
        try{store.delete("internal:files",QUIET);fail("Read-only browse entry became a removal target");}catch(IOException expected){}
        assertEquals("hidden",FilesEx.read(new File(data,"files/unknown/nested/data"),100));
    }
    @Test public void changedFilesMakeScanPartialInsteadOfReportingACompleteSmallTotal()throws Exception {
        text(data,"files/moved-away","large");text(external,"keep","kept");
        AppStorage.Report report=AppStorage.scan(roots,message->{if(message.contains("Internal data / files"))try{FilesEx.delete(new File(data,"files"));}catch(IOException e){throw new RuntimeException(e);}},STATS);
        assertEquals(4,report.bytes);assertTrue(report.errors>0);
    }
    @Test public void cancellationStopsFullScanAndLeavesDataIntact()throws Exception {
        text(data,"files/keep","keep");Thread.currentThread().interrupt();
        try{AppStorage.scan(roots,QUIET,STATS);fail("Cancelled scan completed");}catch(IOException expected){}finally{Thread.interrupted();}
        assertTrue(new File(data,"files/keep").isFile());
    }
}
