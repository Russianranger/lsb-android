import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.nio.file.*;

public final class PreparedClientTest {
    public static void main(String[] args)throws Exception {
        File home=Files.createTempDirectory("lsb-prepared-").toFile();
        ClientStore imported=new ClientStore(new File(home,"imported"));
        CoreTest.importZip(imported,CoreTest.fixture("30260904_1",false));
        File seed=new File(home,"seed");FilesEx.text(new File(seed,"lsb-prefix-ready.json"),"{}");
        FilesEx.text(new File(seed,"user.reg"),"original hive");FilesEx.mkdir(new File(seed,"dosdevices"));
        Files.createSymbolicLink(new File(seed,"dosdevices/z:").toPath(),Paths.get("/"));
        File isolated=new File(home,"fex-prefix");
        PreparedClientStore.copyRuntimePrefix(seed,isolated,CoreTest.QUIET);
        FilesEx.text(new File(isolated,"user.reg"),"FEX hive");
        CoreTest.ok(FilesEx.read(new File(seed,"user.reg"),100).equals("original hive"),"FEX prefix edits preserve original registry bytes");
        CoreTest.ok(Files.readSymbolicLink(new File(isolated,"dosdevices/z:").toPath()).equals(Paths.get("/")),"FEX copy preserves guest links without traversal");
        CoreTest.fails(()->PreparedClientStore.copyRuntimePrefix(seed,seed,CoreTest.QUIET),"reject in-place prefix conversion");
        CoreTest.fails(()->PreparedClientStore.copyRuntimePrefix(seed,isolated,CoreTest.QUIET),"refuse overwriting an existing runtime prefix");
        PreparedClientStore store=new PreparedClientStore(new File(home,"prepared"));
        File a=store.prepare(imported,seed,CoreTest.QUIET);
        CoreTest.ok(store.complete(a)&&store.selected("current")==null,"copy alone never activates client");
        CoreTest.ok(Files.isSymbolicLink(new File(a,"prefix/dosdevices/z:").toPath()),"prefix guest links copied without traversal");
        CoreTest.fails(()->store.promote(a),"refuse unvalidated activation");
        CoreTest.ok(store.prepare(imported,seed,CoreTest.QUIET).equals(a),"retry retains completed working copy and prefix");
        ClientInspector.Snapshot original=imported.validate(CoreTest.QUIET),working=ClientInspector.inspect(new File(a,"client"),store.metadata(a).getProperty("core"),CoreTest.QUIET);
        FilesEx.text(new File(working.game,"USER/settings"),"working settings");
        FilesEx.text(new File(a,"prefix/user.reg"),"working hive");
        CoreTest.ok(!new File(original.game,"USER/settings").exists()&&FilesEx.read(new File(seed,"user.reg"),100).equals("original hive"),"working edits cannot change import or tested prefix");
        FilesEx.text(new File(a,"initialization-passed.json"),"{}");store.promote(a);
        CoreTest.ok(store.selected("current").equals(a)&&store.selected("candidate")==null,"atomically activate matching client and prefix");
        File b=store.prepare(imported,new File(a,"prefix"),CoreTest.QUIET);
        CoreTest.ok(store.selected("current").equals(a),"next preparation preserves active generation");
        FilesEx.text(new File(b,"initialization-passed.json"),"{}");store.promote(b);store.rollback();
        CoreTest.ok(store.selected("current").equals(a)&&store.selected("previous").equals(b),"rollback restores matching client and prefix pair");
        CoreTest.fails(()->store.prepare(imported,seed,text->{if(text.startsWith("Copying"))throw new RuntimeException("copy interrupted");}),"simulate interrupted full copy");
        CoreTest.ok(!store.complete(store.selected("candidate"))&&store.selected("current").equals(a),"interrupted copy cannot displace active client");
        PreparedClientStore reopened=new PreparedClientStore(new File(home,"prepared"));
        File retried=reopened.prepare(imported,seed,CoreTest.QUIET);
        CoreTest.ok(reopened.complete(retried)&&reopened.selected("current").equals(a),"recover interrupted copy after store recreation");
        reopened.discard();CoreTest.ok(reopened.selected("candidate")==null&&reopened.selected("current").equals(a)&&imported.hasClient(),"discard only candidate, following no prefix links");
        File repair=reopened.stageRepair(CoreTest.QUIET);
        CoreTest.ok(FilesEx.read(new File(repair,"client/"+PreparedClientStore.relative(working.root,working.game)+"/USER/settings"),100).equals("working settings"),"prerequisite repair retains current user settings rather than recopying import");
        CoreTest.ok(reopened.stageRepair(CoreTest.QUIET).equals(repair)&&reopened.selected("current").equals(a),"retry repair reuses candidate and preserves accepted generation");
        FilesEx.text(new File(repair,"prefix/user.reg"),"installer changes");
        CoreTest.ok(FilesEx.read(new File(a,"prefix/user.reg"),100).equals("working hive"),"prerequisite repair cannot modify active prefix");
        reopened.discard();
        CoreTest.fails(()->reopened.generation("../imported"),"reject generation traversal");
        File external=new File(home,"external");FilesEx.text(external,"keep");
        Files.createSymbolicLink(new File(imported.client(),"escape").toPath(),external.toPath());
        CoreTest.fails(()->reopened.prepare(imported,seed,CoreTest.QUIET),"reject linked imported files before copy");
        CoreTest.ok(FilesEx.read(external,100).equals("keep"),"outside files remain intact");
        Files.walk(home.toPath()).sorted(java.util.Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(IOException e){throw new RuntimeException(e);}});
        System.out.println("Completed "+CoreTest.checks+" preparation recovery checks.");
    }
}
