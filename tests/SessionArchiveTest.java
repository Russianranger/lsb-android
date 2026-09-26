import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

public final class SessionArchiveTest {
    private static final SafeZip.Progress QUIET=s->{};
    private static final String BASE="lsb-complete-session-v1/";
    private static int checks;
    private interface Action {void run()throws Exception;}
    private static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;System.out.println("PASS "+label);}
    private static void fails(Action task,String label)throws Exception{try{task.run();}catch(IOException e){checks++;System.out.println("PASS "+label);return;}throw new AssertionError("Expected rejection: "+label);}
    private static String failure(Action task)throws Exception{try{task.run();}catch(IOException e){return e.getMessage();}throw new AssertionError("Expected IOException");}
    private static Map<String,File> roots(Path directory)throws IOException{Map<String,File> roots=new LinkedHashMap<>();for(String scope:new String[]{"files","managed"})roots.put(scope,Files.createDirectories(directory.resolve(scope)).toFile());return roots;}
    private static void put(Path file,String value)throws IOException{Files.createDirectories(file.getParent());Files.write(file,value.getBytes(StandardCharsets.UTF_8));}
    private static String read(Path file)throws IOException{return new String(Files.readAllBytes(file),StandardCharsets.UTF_8);}
    private static byte[] write(Map<String,File> roots,byte[] metadata)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();SessionArchive.write(out,roots,metadata,(scope,path)->path.equals("tmp"),QUIET);return out.toByteArray();}
    private static Map<String,byte[]> unzip(byte[] archive)throws IOException{Map<String,byte[]> entries=new LinkedHashMap<>();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(archive))){ZipEntry entry;byte[] buf=new byte[1024];while((entry=z.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=z.read(buf))!=-1)out.write(buf,0,n);entries.put(entry.getName(),out.toByteArray());}}return entries;}
    private static byte[] zip(Map<String,byte[]> entries)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){for(Map.Entry<String,byte[]> e:entries.entrySet()){z.putNextEntry(new ZipEntry(e.getKey()));z.write(e.getValue());z.closeEntry();}}return out.toByteArray();}
    private static void updateFooter(Map<String,byte[]> entries)throws Exception{
        DataInputStream old=new DataInputStream(new ByteArrayInputStream(entries.get(BASE+"complete.bin")));int magic=old.readInt(),version=old.readInt();long count=old.readLong(),bytes=old.readLong();ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream footer=new DataOutputStream(out);footer.writeInt(magic);footer.writeInt(version);footer.writeLong(count);footer.writeLong(bytes);footer.write(MessageDigest.getInstance("SHA-256").digest(entries.get(BASE+"header.bin")));footer.write(MessageDigest.getInstance("SHA-256").digest(entries.get(BASE+"files.bin")));entries.put(BASE+"complete.bin",out.toByteArray());
    }
    private static void replaceEqual(byte[] bytes,String from,String to){byte[] a=from.getBytes(StandardCharsets.UTF_8),b=to.getBytes(StandardCharsets.UTF_8);if(a.length!=b.length)throw new AssertionError("replacement length");outer:for(int i=0;i+a.length<=bytes.length;i++){for(int j=0;j<a.length;j++)if(bytes[i+j]!=a[j])continue outer;System.arraycopy(b,0,bytes,i,b.length);return;}throw new AssertionError("missing replacement "+from);}
    private static void string(DataOutputStream out,String value)throws IOException{byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);}
    private static void node(DataOutputStream out,int type,String name,String content)throws Exception{
        out.writeByte(type);string(out,name);out.writeInt(0700);out.writeLong(1600000000000L);
        if(type==2){byte[] value=content.getBytes(StandardCharsets.UTF_8);out.writeLong(value.length);out.write(value);out.write(MessageDigest.getInstance("SHA-256").digest(value));}
        else if(type==3||type==4)string(out,content);
    }
    private static byte[] custom(byte[] archive,byte[] payload,long count,long bytes)throws Exception{
        Map<String,byte[]> records=unzip(archive);records.put(BASE+"files.bin",payload);
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();DataOutputStream footer=new DataOutputStream(buffer);footer.writeInt(0x4c534253);footer.writeInt(1);footer.writeLong(count);footer.writeLong(bytes);footer.write(new byte[64]);records.put(BASE+"complete.bin",buffer.toByteArray());updateFooter(records);return zip(records);
    }
    private static SessionArchive.Result restore(byte[] archive,Map<String,File> stage,Map<String,File> target)throws IOException{return SessionArchive.read(new ByteArrayInputStream(archive),stage,target,QUIET,1024*1024,0,10000);}
    private static boolean empty(Map<String,File> roots){for(File f:roots.values())if(f.list().length!=0)return false;return true;}
    private static void remove(Path path)throws IOException{if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)){Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rwx------"));try(DirectoryStream<Path> children=Files.newDirectoryStream(path)){for(Path child:children)remove(child);}}Files.deleteIfExists(path);}
    private static void posixNames(Path base)throws Exception{
        Map<String,File> source=roots(base.resolve("posix-source")),stage=roots(base.resolve("posix-stage")),destination=roots(base.resolve("posix-destination"));
        Path files=source.get("files").toPath(), managed=source.get("managed").toPath();
        String wineFolder="rt/fex/prefixes/runtime/generation/drive_c/users/root/AppData/Local/Temp";
        // Synthetic Windows-style text stored as a POSIX basename: the support log did not record its actual failing path.
        String wineName="C:\\windows\\temp\\runtime.log";
        String[] names={wineFolder+"/"+wineName,"rt/wine\\cache/..\\literal.dat","rt/z-control\tline\n\u001b[31m\u007f.dat","rt/trailing . ","rt/settings-\u65e5\u672c\u8a9e-\ud83c\udfae.dat"};
        for(int i=0;i<names.length;i++)put(files.resolve(names[i]),"content-"+i);
        put(managed.resolve("session/current/client/USER/literal\\macro.dat"),"managed macros");
        String hardlink=wineFolder+"/C:\\windows\\temp\\runtime-copy.log",symlink=wineFolder+"/relative\\alias";
        Files.createLink(files.resolve(hardlink),files.resolve(names[0]));Files.createSymbolicLink(files.resolve(symlink),Paths.get(wineName));
        byte[] archive=write(source,"{}".getBytes(StandardCharsets.UTF_8));SessionArchive.Result restored=restore(archive,stage,destination);
        Path extracted=stage.get("files").toPath();
        for(int i=0;i<names.length;i++)check(read(extracted.resolve(names[i])).equals("content-"+i),"exact POSIX basename survives complete backup case "+(i+1));
        check(read(stage.get("managed").toPath().resolve("session/current/client/USER/literal\\macro.dat")).equals("managed macros"),"managed scope also preserves literal backslash names");
        check(Files.isSameFile(extracted.resolve(names[0]),extracted.resolve(hardlink))&&restored.summary.hardlinks==1,"hardlink identity survives names containing literal backslashes");
        check(Files.readSymbolicLink(extracted.resolve(symlink)).toString().equals(wineName)&&read(extracted.resolve(symlink)).equals("content-0"),"relative symlink target retains literal backslashes and resolves correctly");
        check(!Files.exists(extracted.resolve(wineFolder+"/C:"))&&!Files.exists(extracted.resolve("rt/wine/cache")),"backslashes never become directory separators during restore");
        check(read(files.resolve(names[0])).equals("content-0")&&Files.isSameFile(files.resolve(names[0]),files.resolve(hardlink)),"filename compatibility fix leaves original runtime files and aliases intact");
        Map<String,File> badStage=roots(base.resolve("posix-bad-stage"));
        Map<String,byte[]> damaged=unzip(archive);replaceEqual(damaged.get(BASE+"files.bin"),"content-2","damaged-2");updateFooter(damaged);byte[] corruptName=zip(damaged);
        String checksumError=failure(()->restore(corruptName,badStage,destination));
        check(checksumError.contains("\\u0009")&&checksumError.contains("\\u000a")&&checksumError.contains("\\u001b")&&checksumError.contains("\\u007f")&&!checksumError.contains("\n")&&!checksumError.contains("\t"),"checksum diagnostic quotes and escapes control characters instead of injecting log lines");
        for(String invalid:new String[]{"files/../escape","files/./escape","files//escape","/files/escape","files/nul\0name","outside/file","files/","files\\escape","files/..\\/../escape"}){
            ByteArrayOutputStream payload=new ByteArrayOutputStream();DataOutputStream records=new DataOutputStream(payload);node(records,1,"files",null);node(records,1,"managed",null);node(records,2,invalid,"bad");records.writeByte(0);
            byte[] malformed=custom(archive,payload.toByteArray(),3,3);String error=failure(()->restore(malformed,badStage,destination));check(error.contains("session"),"actual separator traversal, NUL or invalid scope stays rejected");check(empty(badStage),"malformed POSIX path leaves no staged data");
            if(invalid.indexOf('\0')>=0)check(error.contains("\\u0000")&&error.indexOf('\0')<0,"rejected NUL path is identified with an escaped diagnostic");
        }
    }
    public static void main(String[] args)throws Exception{
        Path base=Files.createTempDirectory("lsb-session-archive-");
        try{
            Map<String,File> source=roots(base.resolve("source"));Path files=source.get("files").toPath(),managed=source.get("managed").toPath();
            put(files.resolve("rt/root/usr/bin/tool"),"ELF-test-runtime");Files.setPosixFilePermissions(files.resolve("rt/root/usr/bin/tool"),PosixFilePermissions.fromString("rwxr-x---"));
            put(files.resolve("server-runtime/state/generation/database-secret"),"private-database-state");Files.setPosixFilePermissions(files.resolve("server-runtime/state/generation/database-secret"),PosixFilePermissions.fromString("rw-------"));
            put(files.resolve("rt/root/.l2s.hidden"),"proot-hardlink-storage");Files.createLink(files.resolve("rt/root/hardlink"),files.resolve("rt/root/.l2s.hidden"));
            Files.createSymbolicLink(files.resolve("rt/root/guest-link"),Paths.get("/usr/bin/tool"));
            Files.createSymbolicLink(files.resolve("rt/root/host-link"),files.resolve("rt/root/.l2s.hidden"));
            Files.createSymbolicLink(files.resolve("rt/root/relative-link"),Paths.get("../root/.l2s.hidden"));
            Files.createDirectories(files.resolve("rt/clients/empty"));Files.createDirectories(files.resolve("tmp"));put(files.resolve("tmp/do-not-copy"),"transient");
            put(managed.resolve("session/current/client/USER/macros.dat"),"player macros");put(managed.resolve("server/current/source"),"source revision");put(managed.resolve("case/A"),"upper");put(managed.resolve("case/a"),"lower");
            long savedTime=1600000000123L;Files.setLastModifiedTime(files.resolve("rt/root/usr/bin/tool"),FileTime.fromMillis(savedTime));
            byte[] metadata="{\"preferences\":{\"controller\":{\"enabled\":true}}}".getBytes(StandardCharsets.UTF_8),archive=write(source,metadata);
            Map<String,File> stage=roots(base.resolve("stage")),destination=roots(base.resolve("different-app-id"));SessionArchive.Result result=restore(archive,stage,destination);
            Path sf=stage.get("files").toPath(),sm=stage.get("managed").toPath();
            check(Arrays.equals(result.metadata,metadata)&&result.sourceRoots.get("files").equals(files.toString()),"opaque settings and source root identity survive full archive");
            check(read(sf.resolve("server-runtime/state/generation/database-secret")).equals("private-database-state")&&read(sm.resolve("session/current/client/USER/macros.dat")).equals("player macros"),"database state and client macros restored across both storage roots");
            check(Files.getPosixFilePermissions(sf.resolve("rt/root/usr/bin/tool")).equals(PosixFilePermissions.fromString("rwxr-x---"))&&Files.getPosixFilePermissions(sf.resolve("server-runtime/state/generation/database-secret")).equals(PosixFilePermissions.fromString("rw-------")),"executable and private credential modes preserved");
            check(Files.getLastModifiedTime(sf.resolve("rt/root/usr/bin/tool")).toMillis()==savedTime&&Files.isDirectory(sf.resolve("rt/clients/empty")),"mtime and empty directories preserved");
            check(Files.isSameFile(sf.resolve("rt/root/.l2s.hidden"),sf.resolve("rt/root/hardlink"))&&result.summary.hardlinks==1,"real hardlink identity and hidden PRoot storage preserved");
            check(Files.readSymbolicLink(sf.resolve("rt/root/guest-link")).toString().equals("/usr/bin/tool")&&Files.readSymbolicLink(sf.resolve("rt/root/relative-link")).toString().equals("../root/.l2s.hidden"),"guest absolute and relative symlinks retain their targets");
            check(Files.readSymbolicLink(sf.resolve("rt/root/host-link")).equals(destination.get("files").toPath().resolve("rt/root/.l2s.hidden")),"host symlink relocates to final independent app root");
            check(!Files.exists(sf.resolve("tmp"))&&read(sm.resolve("case/A")).equals("upper")&&read(sm.resolve("case/a")).equals("lower"),"explicit transients excluded without changing Linux filename casing");
            fails(()->restore(archive,stage,destination),"restore rejects occupied staging roots");
            Map<String,File> badStage=roots(base.resolve("bad-stage"));
            Map<String,byte[]> altered=unzip(archive);replaceEqual(altered.get(BASE+"files.bin"),"ELF-test-runtime","ELF-test-corrupt");updateFooter(altered);
            byte[] alteredArchive=zip(altered);fails(()->restore(alteredArchive,badStage,destination),"per-file SHA256 catches corruption even with recomputed ZIP CRC and footer");check(empty(badStage),"corrupt file cleans partial staging and creates no links");
            altered=unzip(archive);altered.remove(BASE+"complete.bin");byte[] missingFooter=zip(altered);fails(()->restore(missingFooter,badStage,destination),"late missing completion footer rejects whole session");check(empty(badStage),"late archive failure removes all staged files without touching source");
            altered=unzip(archive);replaceEqual(altered.get(BASE+"files.bin"),"files/rt/root/usr/bin/tool","files/../root/usr/bin/tool");updateFooter(altered);byte[] traversal=zip(altered);fails(()->restore(traversal,badStage,destination),"parent traversal rejected even with valid checksums");
            altered=unzip(archive);altered.get(BASE+"header.bin")[altered.get(BASE+"header.bin").length-2]^=1;byte[] badMetadata=zip(altered);fails(()->restore(badMetadata,badStage,destination),"settings metadata is covered by required footer checksum");
            altered=unzip(archive);altered.put("unexpected",new byte[]{1});byte[] trailing=zip(altered);fails(()->restore(trailing,badStage,destination),"unexpected trailing ZIP records rejected");
            fails(()->SessionArchive.read(new ByteArrayInputStream(archive),badStage,destination,QUIET,8,0,10000),"expanded size limit enforced before oversized write");
            fails(()->SessionArchive.read(new ByteArrayInputStream(archive),badStage,destination,QUIET,1024*1024,0,3),"entry count limit enforced");
            fails(()->SessionArchive.read(new ByteArrayInputStream(archive),badStage,destination,QUIET,1024*1024,Long.MAX_VALUE/2,10000),"available disk space reserve enforced");
            Thread.currentThread().interrupt();fails(()->restore(archive,badStage,destination),"restore cancellation rejected before activation");Thread.interrupted();check(empty(badStage),"cancelled restore leaves empty staging");
            Files.createSymbolicLink(files.resolve("rt/root/unmapped"),Paths.get("/data/user/0/another.app/files/private"));byte[] unmapped=write(source,metadata);fails(()->restore(unmapped,badStage,destination),"unmapped Android host symlink cannot target another app");Files.delete(files.resolve("rt/root/unmapped"));
            Files.createSymbolicLink(files.resolve("rt/root/escape"),Paths.get(files.toString()+"/../outside"));byte[] escape=write(source,metadata);fails(()->restore(escape,badStage,destination),"source-root host symlink cannot escape through parent traversal");Files.delete(files.resolve("rt/root/escape"));
            Files.createSymbolicLink(files.resolve("relative-escape"),Paths.get("../../../outside"));byte[] relativeEscape=write(source,metadata);fails(()->restore(relativeEscape,badStage,destination),"relative link escaping both logical roots rejected");Files.delete(files.resolve("relative-escape"));
            Files.createSymbolicLink(files.resolve("relative-cross-scope"),Paths.get("../managed/server/current/source"));byte[] crossScope=write(source,metadata);Map<String,File> crossStage=roots(base.resolve("cross-stage"));restore(crossScope,crossStage,destination);
            check(Files.readSymbolicLink(crossStage.get("files").toPath().resolve("relative-cross-scope")).equals(destination.get("managed").toPath().resolve("server/current/source")),"relative link across original storage roots relocates to independent destination");Files.delete(files.resolve("relative-cross-scope"));
            Path outside=base.resolve("outside");put(outside.resolve("sentinel"),"working data");ByteArrayOutputStream payload=new ByteArrayOutputStream();DataOutputStream records=new DataOutputStream(payload);
            node(records,1,"files",null);node(records,1,"managed",null);node(records,3,"files/out",outside.toString());node(records,2,"files/out/sentinel","overwrite");records.writeByte(0);byte[] aliasAttack=custom(archive,payload.toByteArray(),4,9);
            fails(()->restore(aliasAttack,badStage,destination),"symlink cannot become parent of later payload writes");check(read(outside.resolve("sentinel")).equals("working data")&&empty(badStage),"parent symlink attack leaves external files and staging untouched");
            payload=new ByteArrayOutputStream();records=new DataOutputStream(payload);node(records,1,"files",null);node(records,1,"managed",null);node(records,2,"files/repeated","one");node(records,2,"files/repeated","two");records.writeByte(0);byte[] repeated=custom(archive,payload.toByteArray(),4,6);fails(()->restore(repeated,badStage,destination),"duplicate records cannot overwrite earlier verified content");
            payload=new ByteArrayOutputStream();records=new DataOutputStream(payload);node(records,1,"files",null);node(records,1,"managed",null);node(records,4,"files/invalid-hardlink","files/missing");records.writeByte(0);byte[] missingLink=custom(archive,payload.toByteArray(),3,0);fails(()->restore(missingLink,badStage,destination),"hardlinks only reference preceding verified regular files");
            Path nested=base.resolve("nested-source");Map<String,File> nestedRoots=new LinkedHashMap<>();nestedRoots.put("files",Files.createDirectories(nested.resolve("files")).toFile());nestedRoots.put("managed",Files.createDirectories(nested.resolve("files/lsb")).toFile());put(nested.resolve("files/lsb/data"),"managed");Files.createSymbolicLink(nested.resolve("files/alias"),nested.resolve("files/lsb/data"));
            ByteArrayOutputStream nestedOut=new ByteArrayOutputStream();SessionArchive.write(nestedOut,nestedRoots,metadata,(scope,path)->scope.equals("files")&&path.equals("lsb"),QUIET);restore(nestedOut.toByteArray(),badStage,destination);
            check(Files.readSymbolicLink(badStage.get("files").toPath().resolve("alias")).equals(destination.get("managed").toPath().resolve("data")),"longest original root alias relocates nested managed storage correctly");
            check(!Files.exists(badStage.get("files").toPath().resolve("lsb"))&&read(badStage.get("managed").toPath().resolve("data")).equals("managed"),"internal fallback storage backed up once through managed scope");
            check(read(files.resolve("rt/root/usr/bin/tool")).equals("ELF-test-runtime")&&read(managed.resolve("session/current/client/USER/macros.dat")).equals("player macros"),"all restore trials leave working source untouched");
            posixNames(base);
            System.out.println("Session archive checks passed: "+checks);
        }finally{Thread.interrupted();remove(base);}
    }
}
