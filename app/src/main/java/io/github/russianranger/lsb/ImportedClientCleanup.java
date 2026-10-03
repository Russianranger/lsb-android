package io.github.russianranger.lsb;

import io.github.russianranger.lsb.core.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** One-time comparison before removing the import; normal launches never run this scan. */
final class ImportedClientCleanup {
    private final PreparedClientStore prepared;
    private final ClientStore imported;
    ImportedClientCleanup(PreparedClientStore prepared, ClientStore imported) { this.prepared=prepared; this.imported=imported; }
    private static File regular(File root,String path)throws IOException {
        File file=inside(root,path);
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing verification file: "+path);
        return file;
    }
    private static File inside(File root,String path)throws IOException {
        if(path.isEmpty()||path.startsWith("/")||path.indexOf('\\')>=0)throw new IOException("Invalid client verification path");
        if(!Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid client verification directory");
        File file=root;String[] parts=path.split("/",-1);
        for(int i=0;i<parts.length;i++){
            String part=parts[i];if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("Invalid client verification path");
            file=new File(file,part);
            if(Files.isSymbolicLink(file.toPath()))throw new IOException("Client verification crosses a symbolic link");
            if(i+1<parts.length&&!Files.isDirectory(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing client verification directory");
        }return file;
    }
    private File generation()throws Exception {
        if(!imported.hasClient()||imported.hasPendingImport()||new File(imported.current().getParentFile(),"swap").exists())throw new IOException("Finish client import recovery before removing imported files");
        if(prepared.selected("candidate")!=null)throw new IOException("Finish or discard the staged preparation or update first");
        File gen=prepared.selected("current");
        if(!prepared.complete(gen)||Files.isSymbolicLink(gen.toPath())||Files.isSymbolicLink(gen.getParentFile().toPath()))throw new IOException("A complete active preparation is required");
        regular(gen,"copy-complete");regular(gen,"metadata.properties");regular(gen,"prefix/lsb-prefix-ready.json");
        JSONObject receipt=new JSONObject(FilesEx.read(regular(gen,"initialization-passed.json"),262144));
        if(!"passed".equals(receipt.optString("status"))||!gen.getName().equals(receipt.optString("generation")))throw new IOException("Active preparation has no matching passed initialization receipt");
        Properties meta=prepared.metadata(gen);
        if(!gen.getName().equals(meta.getProperty("generation"))||PreparedLoaderUpdate.pending(gen))throw new IOException("Finish prepared-loader recovery before removing imported files");
        return gen;
    }
    boolean available() { try { generation(); return true; } catch(Exception unavailable) { return false; } }
    void verifyAndRetain(SafeZip.Progress progress)throws Exception {
        File gen=generation();Properties meta=prepared.metadata(gen);
        File inventoryFile=regular(gen,"source-inventory.json");
        if(!FilesEx.hash(inventoryFile).equals(meta.getProperty("inventorySha256")))throw new IOException("Prepared inventory changed; keep the imported files");
        progress.update("Inspecting the active prepared client before import removal…");
        File working=inside(gen,"client");
        ClientInspector.Snapshot active=ClientInspector.inspect(working,meta.getProperty("core"),progress);
        JSONObject stored=new JSONObject(FilesEx.read(inventoryFile,262144));
        if(!active.warning().isEmpty()||active.loader==null||!stored.getJSONArray("keyFiles").toString().equals(new JSONObject(active.inventory).getJSONArray("keyFiles").toString()))throw new IOException("Prepared client verification failed; imported files retained");
        for(String path:new String[]{"pol","game","loader"}){
            File selected=path.equals("pol")?active.pol:path.equals("game")?active.game:active.loader;
            if(!FilesEx.relative(working,selected).equals(meta.getProperty(path)))throw new IOException("Prepared client paths changed; imported files retained");
        }
        ClientInspector.requireX86(active.polExecutable);
        progress.update("Comparing original imported files with the prepared client…");
        ClientInspector.Snapshot source=ClientInspector.inspect(imported.client(),imported.config().polCore,progress);
        if(!source.warning().isEmpty()||source.loader==null||!new JSONObject(source.inventory).getJSONArray("keyFiles").toString().equals(stored.getJSONArray("keyFiles").toString()))throw new IOException("The import differs from the active preparation. Keep it or prepare this import before removing its files.");
        final Path original=imported.client().toPath();
        final String user=FilesEx.relative(imported.client(),source.game)+"/USER",usr=FilesEx.relative(imported.client(),source.pol)+"/usr";
        final long[] checked={0,0},reported={0};
        final byte[] a=new byte[65536],b=new byte[65536];
        Files.walkFileTree(original,EnumSet.noneOf(FileVisitOption.class),64,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs)throws IOException {
                SafeZip.checkCancelled();String name=original.relativize(path).toString().replace(File.separatorChar,'/');
                if(name.equalsIgnoreCase(user)||name.equalsIgnoreCase(usr))return FileVisitResult.SKIP_SUBTREE;
                if(!name.isEmpty()&&!Files.isDirectory(inside(working,name).toPath(),LinkOption.NOFOLLOW_LINKS))throw different(name);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws IOException {
                SafeZip.checkCancelled();String name=original.relativize(path).toString().replace(File.separatorChar,'/');
                if(!attrs.isRegularFile())throw different(name);
                File target=regular(working,name);
                if(attrs.size()!=target.length())throw different(name);
                try(InputStream left=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS);InputStream right=Files.newInputStream(target.toPath(),LinkOption.NOFOLLOW_LINKS)){
                    int n;while((n=left.read(a))!=-1){
                        SafeZip.checkCancelled();int count=0,m;
                        while(count<n&&(m=right.read(b,count,n-count))!=-1)count+=m;
                        if(count!=n)throw different(name);
                        for(int i=0;i<n;i++)if(a[i]!=b[i])throw different(name);
                        checked[1]+=n;
                        if(checked[1]-reported[0]>=64L*1048576){reported[0]=checked[1];progress.update("Verifying imported files · "+checked[0]+" files · "+checked[1]/1048576+" / "+source.bytes/1048576+" MiB");}
                    }if(right.read()!=-1)throw different(name);
                }
                if(++checked[0]%1000==0)progress.update("Verifying imported files · "+checked[0]+" files · "+checked[1]/1048576+" MiB");
                return FileVisitResult.CONTINUE;
            }
        });
        // Recheck selection after the expensive comparison, before any payload is detached.
        if(!gen.equals(generation()))throw new IOException("Active client selection changed; imported files retained");
        SafeZip.checkCancelled();
        progress.update("Retaining connection settings and the selected imported loader…");
        PreparedLoaderUpdate.retainImportedLoader(imported,source.loader);
        JSONObject receipt=new JSONObject().put("format",1).put("generation",gen.getName()).put("verified_at",System.currentTimeMillis()).put("compared_files",checked[0]).put("compared_bytes",checked[1]).put("import_bytes",source.bytes).put("prepared_inventory_sha256",meta.getProperty("inventorySha256"));
        StorageBackups.atomic(new File(imported.current(),"import-removal.json").toPath(),receipt.toString(2));
    }
    private static IOException different(String name) { return new IOException("Imported file differs from or is missing in the prepared client: "+name+". Imported files retained."); }
}
