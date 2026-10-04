package io.github.russianranger.lsb;

import io.github.russianranger.lsb.core.*;
import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Validates the active installation and update ancestry before removing only the import. */
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
    private static Map<String,String> keys(JSONObject inventory,boolean manifest,String loader)throws Exception {
        Map<String,String> keys=new TreeMap<>();
        if(manifest){JSONObject values=inventory.getJSONObject("key_files");Iterator<String> names=values.keys();while(names.hasNext()){String path=names.next();if(!path.equals(loader))keys.put(path,values.getString(path));}}
        else {JSONArray values=inventory.getJSONArray("keyFiles");for(int i=0;i<values.length();i++){JSONObject key=values.getJSONObject(i);String path=key.getString("path");if(!new File(path).getName().equalsIgnoreCase("xiloader.exe")&&keys.put(path,key.getString("sha256"))!=null)throw new IOException("Duplicate client inventory key");}}
        if(keys.size()<3)throw new IOException("Incomplete client inventory");return keys;
    }
    private boolean updateAncestorMatches(File gen,JSONObject source)throws Exception {
        Set<String> visited=new HashSet<>();Map<String,String> original=keys(source,false,"");
        for(int depth=0;depth<32&&gen!=null;depth++){
            SafeZip.checkCancelled();if(!visited.add(gen.getName()))throw new IOException("Cyclic update ancestry");
            File before=new File(gen,"update-source-inventory.json");
            if(Files.exists(before.toPath(),LinkOption.NOFOLLOW_LINKS)&&original.equals(keys(new JSONObject(FilesEx.read(regular(gen,"update-source-inventory.json"),262144)),false,"")))return true;
            Properties meta=prepared.metadata(gen);String parent=meta.getProperty("repairOf",meta.getProperty("updateOf",""));
            if(parent.isEmpty())break;gen=prepared.generation(parent);
            if(!Files.isDirectory(gen.toPath(),LinkOption.NOFOLLOW_LINKS))break;
        }return false;
    }
    private static boolean updateReceipt(JSONObject receipt,JSONObject initialized,File gen,JSONObject manifest,String loader)throws Exception {
        return "passed".equals(receipt.optString("status"))&&gen.getName().equals(receipt.optString("generation"))&&!receipt.optString("session_id").isEmpty()&&receipt.optString("session_id").equals(initialized.optString("session_id"))&&keys(receipt,true,loader).equals(keys(manifest,true,loader));
    }
    private boolean verifiedUpdate(File gen,Properties meta,JSONObject source,JSONObject manifest)throws Exception {
        if(!"verified".equals(meta.getProperty("updatePhase"))||meta.getProperty("updateOf","").isEmpty())return false;
        if(gen.equals(prepared.generation(meta.getProperty("updateOf")))||!updateAncestorMatches(gen,source))return false;
        JSONObject initialized=new JSONObject(FilesEx.read(regular(gen,"initialization-passed.json"),262144));String loader=meta.getProperty("loader");
        File receipt=new File(gen,"update-verified.json");
        if(Files.exists(receipt.toPath(),LinkOption.NOFOLLOW_LINKS))return updateReceipt(new JSONObject(FilesEx.read(regular(gen,"update-verified.json"),262144)),initialized,gen,manifest,loader);
        // Loader replacement archives the prior verification receipt. It still proves
        // the unchanged updated DLLs/prefix; the current loader is independently hashed.
        File history=new File(gen,"loader-updates");if(!Files.exists(history.toPath(),LinkOption.NOFOLLOW_LINKS))return false;
        inside(gen,"loader-updates");if(!history.isDirectory())throw new IOException("Invalid loader-update history");
        int count=0;for(File transaction:FilesEx.children(history)){
            SafeZip.checkCancelled();if(++count>512)throw new IOException("Too many loader-update records");
            if(!transaction.getName().matches("[0-9a-f-]{36}"))continue;inside(history,transaction.getName());
            regular(transaction,"committed");JSONObject journal=new JSONObject(FilesEx.read(regular(transaction,"journal.json"),262144));
            if(!gen.getName().equals(journal.optString("generation"))||!transaction.getName().equals(journal.optString("id")))throw new IOException("Loader-update history generation mismatch");
            JSONArray entries=journal.getJSONArray("files");if(entries.length()>8)throw new IOException("Invalid loader-update history");
            for(int i=0;i<entries.length();i++){
                JSONObject entry=entries.getJSONObject(i);if(!"update-verified.json".equals(entry.optString("path"))||"missing".equals(entry.optString("old")))continue;
                File old=regular(transaction,i+".old");if(!FilesEx.hash(old).equals(entry.getString("old")))throw new IOException("Archived update verification changed");
                if(updateReceipt(new JSONObject(FilesEx.read(old,262144)),initialized,gen,manifest,loader))return true;
            }
        }return false;
    }
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
        JSONObject manifest=ClientLaunchValidation.manifest(gen,meta,progress);
        if(manifest==null)throw new IOException("Active client validation is incomplete; imported files retained");
        progress.update("Comparing original imported files with the prepared client…");
        ClientInspector.Snapshot source=ClientInspector.inspect(imported.client(),imported.config().polCore,progress);
        if(!source.warning().isEmpty()||source.loader==null)throw new IOException("Original import inspection failed; imported files retained");
        boolean updated=verifiedUpdate(gen,meta,new JSONObject(source.inventory),manifest);
        if(updated){
            File rom=ClientInspector.child(active.game,"ROM"),zero=rom==null?null:ClientInspector.child(rom,"0"),dat=zero==null?null:ClientInspector.child(zero,"0.dat");
            if(dat==null||regular(working,FilesEx.relative(working,dat)).length()==0)throw new IOException("Updated client is missing ROM/0/0.dat; imported files retained");
        }
        if(!updated&&!new JSONObject(source.inventory).getJSONArray("keyFiles").toString().equals(stored.getJSONArray("keyFiles").toString()))throw new IOException("The import differs and its verified PlayOnline update ancestry is unavailable. Imported files retained.");
        final Path original=imported.client().toPath();
        final String user=FilesEx.relative(imported.client(),source.game)+"/USER",usr=FilesEx.relative(imported.client(),source.pol)+"/usr";
        final long[] checked={0,0},reported={0};
        final byte[] a=new byte[65536],b=new byte[65536];
        if(updated)progress.update("Verified PlayOnline update history; the older import may differ from the active client…");
        else Files.walkFileTree(original,EnumSet.noneOf(FileVisitOption.class),64,new SimpleFileVisitor<Path>() {
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
        progress.update("Retaining connection settings and the active prepared loader…");
        PreparedLoaderUpdate.retainImportedLoader(imported,active.loader);
        JSONObject retained=new JSONObject(source.inventory);String selected=FilesEx.relative(working,active.loader),hash=FilesEx.hash(active.loader);
        JSONArray retainedKeys=retained.getJSONArray("keyFiles");boolean changed=false;
        for(int i=0;i<retainedKeys.length();i++){JSONObject key=retainedKeys.getJSONObject(i);if(key.getString("path").equals(FilesEx.relative(imported.client(),source.loader))){changed=!selected.equals(key.getString("path"))||!hash.equals(key.getString("sha256"));key.put("path",selected).put("sha256",hash);}}
        if(changed){retained.put("bytes",Math.addExact(source.bytes,active.loader.length()-source.loader.length()));StorageBackups.atomic(new File(imported.current(),"inventory.json").toPath(),retained.toString(2));}
        JSONObject receipt=new JSONObject().put("format",1).put("generation",gen.getName()).put("verification",updated?"verified_playonline_update":"identical_import").put("verified_at",System.currentTimeMillis()).put("compared_files",checked[0]).put("compared_bytes",checked[1]).put("import_bytes",source.bytes).put("prepared_inventory_sha256",meta.getProperty("inventorySha256"));
        StorageBackups.atomic(new File(imported.current(),"import-removal.json").toPath(),receipt.toString(2));
    }
    private static IOException different(String name) { return new IOException("Imported file differs from or is missing in the prepared client: "+name+". Imported files retained."); }
}
