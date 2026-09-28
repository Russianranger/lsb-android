package io.github.russianranger.lsb;

import io.github.russianranger.lsb.core.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Small-file transaction: an imported loader must never replace an updated game installation. */
final class PreparedLoaderUpdate {
    private static final String PENDING="loader-update-pending";
    private static final int META_LIMIT=262144;
    private final PreparedClientStore store;
    PreparedLoaderUpdate(PreparedClientStore store){this.store=store;}
    static boolean pending(File gen){return gen!=null&&Files.exists(new File(gen,PENDING).toPath(),LinkOption.NOFOLLOW_LINKS);}
    private static File safe(File root,String relative,boolean required)throws IOException {
        if(relative.isEmpty()||relative.startsWith("/")||relative.indexOf('\\')>=0)throw new IOException("Invalid loader-update path");
        File f=root;
        if(Files.isSymbolicLink(root.toPath())||!Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid loader-update directory");
        String[] parts=relative.split("/",-1);
        for(int i=0;i<parts.length;i++){
            String part=parts[i];if(part.isEmpty()||part.equals(".")||part.equals(".."))throw new IOException("Invalid loader-update path");
            f=new File(f,part);
            if(Files.isSymbolicLink(f.toPath()))throw new IOException("Loader update contains a symbolic link");
            if(i+1<parts.length&&!Files.isDirectory(f.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing loader-update parent directory");
        }
        if(required&&!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Missing loader-update file: "+relative);
        if(f.exists()&&!Files.isRegularFile(f.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsupported loader-update file");
        return f;
    }
    private static JSONObject json(File root,String name)throws Exception{return new JSONObject(FilesEx.read(safe(root,name,true),META_LIMIT));}
    private static JSONObject loaderKey(JSONObject inventory)throws Exception {
        JSONArray keys=inventory.optJSONArray("keyFiles");JSONObject found=null;if(keys==null)return null;
        Set<String> paths=new HashSet<>();
        for(int i=0;i<keys.length();i++){
            JSONObject key=keys.getJSONObject(i);String path=key.getString("path");
            if(!paths.add(path))throw new IOException("Duplicate client inventory path");
            if(new File(path).getName().equalsIgnoreCase("xiloader.exe")){
                if(found!=null)throw new IOException("Ambiguous selected xiloader");found=key;
            }
        }
        return found;
    }
    /** Persisted hashes only; drawing the launcher never scans the full installation. */
    JSONObject selection(ClientStore imported)throws Exception {
        JSONObject result=new JSONObject().put("prepared_available",false).put("differs",false).put("recovery_pending",false);
        JSONObject source=loaderKey(new JSONObject(imported.inventory()));
        if(source!=null)result.put("imported_sha256",source.getString("sha256")).put("imported_path",source.getString("path"));
        File gen=store.selected("current");if(!store.complete(gen))return result;
        result.put("generation",gen.getName()).put("recovery_pending",pending(gen));
        JSONObject current=loaderKey(json(gen,"source-inventory.json"));
        if(current!=null){
            result.put("prepared_available",true).put("prepared_sha256",current.getString("sha256")).put("prepared_path",current.getString("path"));
            result.put("differs",source!=null&&!source.getString("sha256").equals(current.getString("sha256")));
        }
        result.put("loader_validation",store.metadata(gen).getProperty("loaderValidation","unchanged"));return result;
    }
    private static void durable(File target,byte[] bytes)throws IOException {
        try(FileOutputStream out=new FileOutputStream(target)){out.write(bytes);out.getFD().sync();}
    }
    private static byte[] text(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static byte[] properties(Properties p)throws IOException {StringWriter w=new StringWriter();p.store(w,null);return text(w.toString());}
    private static String hashOrMissing(File f)throws Exception{return Files.exists(f.toPath(),LinkOption.NOFOLLOW_LINKS)?FilesEx.hash(f):"missing";}
    private static void replace(File from,File target,boolean executable)throws Exception {
        File temp=new File(target.getParentFile(),target.getName()+".loader-new");
        if(Files.exists(temp.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(temp.toPath(),LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(temp.toPath()))throw new IOException("Unsafe loader-update temporary file");
            Files.delete(temp.toPath());
        }
        try{
            try(InputStream in=Files.newInputStream(from.toPath(),LinkOption.NOFOLLOW_LINKS);FileOutputStream out=new FileOutputStream(temp)){
                byte[] buffer=new byte[65536];int count;while((count=in.read(buffer))!=-1)out.write(buffer,0,count);out.getFD().sync();
            }
            if(executable&&!temp.setExecutable(true,true))throw new IOException("Cannot retain loader permissions");
            Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp.toPath());}
    }
    private static Set<String> allowed(String loader){return new HashSet<>(Arrays.asList("client/"+loader,"source-inventory.json","metadata.properties","initialization-passed.json","last-result.json","last-launch.json","update-verified.json"));}
    /** The journal is installed before any target changes. Interrupted attempts restore all old files. */
    void recover(SafeZip.Progress progress)throws Exception {
        File gen=store.selected("current");if(!pending(gen))return;
        if(Files.isSymbolicLink(gen.toPath()))throw new IOException("Invalid prepared generation");
        File txn=new File(gen,PENDING);if(Files.isSymbolicLink(txn.toPath())||!txn.isDirectory())throw new IOException("Invalid loader-update journal");
        File journal=new File(txn,"journal.json");
        // No journal means staging never started replacing target files.
        if(!Files.exists(journal.toPath(),LinkOption.NOFOLLOW_LINKS)){remove(txn);return;}
        JSONObject record=json(txn,"journal.json");
        if(!gen.getName().equals(record.getString("generation")))throw new IOException("Loader-update generation mismatch");
        String loader=record.getString("loader");safe(new File(gen,"client"),loader,true);
        if(!new File(loader).getName().equalsIgnoreCase("xiloader.exe"))throw new IOException("Invalid loader-update target");
        JSONArray entries=record.getJSONArray("files");Set<String> remaining=allowed(loader);
        boolean committed=Files.isRegularFile(new File(txn,"committed").toPath(),LinkOption.NOFOLLOW_LINKS);
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.getJSONObject(i);String path=entry.getString("path");
            if(!remaining.remove(path))throw new IOException("Invalid loader-update journal entry");
            File target=safe(gen,path,false);String actual=hashOrMissing(target),old=entry.getString("old"),next=entry.getString("new");
            if(!actual.equals(old)&&!actual.equals(next))throw new IOException("Loader-update recovery found a changed file: "+path);
            if(committed&&!actual.equals(next))throw new IOException("Committed loader-update file changed: "+path);
            if(!old.equals("missing")&&!FilesEx.hash(safe(txn,i+".old",true)).equals(old))throw new IOException("Loader-update recovery copy changed");
        }
        if(entries.length()<4||remaining.contains("client/"+loader)||remaining.contains("source-inventory.json")||remaining.contains("metadata.properties")||remaining.contains("initialization-passed.json"))throw new IOException("Incomplete loader-update journal");
        if(committed){archive(gen,txn,record.getString("id"));return;}
        progress.update("Recovering interrupted xiloader update…");
        for(int i=0;i<entries.length();i++){
            JSONObject entry=entries.getJSONObject(i);File target=safe(gen,entry.getString("path"),false);
            if(entry.getString("old").equals("missing"))Files.deleteIfExists(target.toPath());
            else replace(safe(txn,i+".old",true),target,entry.optBoolean("executable"));
        }
        // Retire the journal atomically before cleanup. A crash while deleting backups
        // must not leave a live journal that can no longer restore its old bytes.
        String id=record.getString("id");
        if(!id.matches("[0-9a-f-]{36}"))throw new IOException("Invalid loader-update receipt ID");
        File discarded=new File(gen,"loader-update-aborted-"+id);
        Files.move(txn.toPath(),discarded.toPath(),StandardCopyOption.ATOMIC_MOVE);
        progress.update("Previous xiloader restored; cleaning transaction backup…");
        remove(discarded);
    }
    private static void archive(File gen,File txn,String id)throws Exception {
        if(!id.matches("[0-9a-f-]{36}"))throw new IOException("Invalid loader-update receipt ID");
        File history=new File(gen,"loader-updates");
        if(Files.isSymbolicLink(history.toPath()))throw new IOException("Invalid loader-update history");
        FilesEx.mkdir(history);
        Files.move(txn.toPath(),new File(history,id).toPath(),StandardCopyOption.ATOMIC_MOVE);
    }
    private static void remove(File f)throws IOException {
        if(Files.isSymbolicLink(f.toPath()))throw new IOException("Unexpected link in loader-update staging");
        if(f.isDirectory())for(File child:FilesEx.children(f))remove(child);
        Files.deleteIfExists(f.toPath());
    }
    String apply(ClientStore imported,String expectedSha256,SafeZip.Progress progress)throws Exception {
        recover(progress);
        if(expectedSha256==null||!expectedSha256.matches("[0-9a-f]{64}"))throw new IOException("Select the imported xiloader again");
        if(imported.hasPendingImport())throw new IOException("Finish the pending client import first");
        if(store.selected("candidate")!=null)throw new IOException("Finish or discard the staged client update before changing xiloader");
        File gen=store.selected("current");if(!store.complete(gen))throw new IOException("Prepare the client first");
        if(Files.isSymbolicLink(gen.toPath()))throw new IOException("Invalid prepared generation");
        File client=new File(gen,"client");Properties meta=store.metadata(gen);
        JSONObject inventory=json(gen,"source-inventory.json"),oldKey=loaderKey(inventory);
        JSONObject sourceKey=loaderKey(new JSONObject(imported.inventory()));
        if(sourceKey==null||oldKey==null)throw new IOException("Import xiloader and prepare a client containing a loader first");
        String loader=oldKey.getString("path"),oldHash=oldKey.getString("sha256");
        if(!loader.equals(meta.getProperty("loader")))throw new IOException("Prepared loader path does not match its inventory");
        File from=safe(imported.client(),sourceKey.getString("path"),true),target=safe(client,loader,true);
        if(from.length()>64L*1048576||!sourceKey.getString("sha256").equals(expectedSha256)||!FilesEx.hash(from).equals(expectedSha256))throw new IOException("Imported xiloader changed; select it again");
        ClientInspector.requireX86(from);
        if(!FilesEx.hash(safe(gen,"source-inventory.json",true)).equals(meta.getProperty("inventorySha256")))throw new IOException("Prepared inventory changed; inspect the prepared client");
        JSONArray keys=inventory.getJSONArray("keyFiles");
        for(int i=0;i<keys.length();i++){
            JSONObject key=keys.getJSONObject(i);File file=safe(client,key.getString("path"),true);
            if(!FilesEx.hash(file).equals(key.getString("sha256")))throw new IOException("Prepared client binary changed: "+key.getString("path"));
            ClientInspector.requireX86(file);
        }
        JSONObject initialized=json(gen,"initialization-passed.json");
        if(!"passed".equals(initialized.optString("status"))||!gen.getName().equals(initialized.optString("generation")))throw new IOException("Prepared client initialization receipt is invalid");
        if(oldHash.equals(expectedSha256))return "The prepared client already uses this imported xiloader.";
        long bytes=Math.addExact(inventory.getLong("bytes"),from.length()-target.length());
        oldKey.put("sha256",expectedSha256);inventory.put("bytes",bytes);
        meta.setProperty("bytes",Long.toString(bytes));meta.setProperty("loaderValidation","pending");meta.setProperty("loaderPreviousSha256",oldHash);
        // Original initialization remains evidence for unchanged DLLs/prefix, never for the replacement loader.
        initialized.put("loader_validation","pending").put("loader_changed_from_sha256",oldHash).put("loader_changed_to_sha256",expectedSha256);
        File txn=new File(gen,PENDING);FilesEx.mkdir(txn);
        try{
            LinkedHashMap<String,byte[]> changes=new LinkedHashMap<>();
            changes.put("client/"+loader,Files.readAllBytes(from.toPath()));changes.put("source-inventory.json",text(inventory.toString(2)));
            durable(new File(txn,"inventory"),changes.get("source-inventory.json"));meta.setProperty("inventorySha256",FilesEx.hash(new File(txn,"inventory")));
            changes.put("metadata.properties",properties(meta));changes.put("initialization-passed.json",text(initialized.toString(2)));
            for(String stale:new String[]{"last-launch.json","last-result.json","update-verified.json"})if(Files.exists(new File(gen,stale).toPath(),LinkOption.NOFOLLOW_LINKS))changes.put(stale,null);
            JSONArray files=new JSONArray();int index=0;
            for(Map.Entry<String,byte[]> change:changes.entrySet()){
                File current=safe(gen,change.getKey(),false);String before=hashOrMissing(current),after="missing";
                if(!before.equals("missing")){durable(new File(txn,index+".old"),Files.readAllBytes(current.toPath()));if(!FilesEx.hash(new File(txn,index+".old")).equals(before))throw new IOException("File changed while saving loader recovery copy");}
                if(change.getValue()!=null){durable(new File(txn,index+".new"),change.getValue());after=FilesEx.hash(new File(txn,index+".new"));}
                files.put(new JSONObject().put("path",change.getKey()).put("old",before).put("new",after).put("executable",current.canExecute()));index++;
            }
            if(!files.getJSONObject(0).getString("old").equals(oldHash)||!files.getJSONObject(0).getString("new").equals(expectedSha256))throw new IOException("Loader changed during update");
            JSONObject journal=new JSONObject().put("format",1).put("id",UUID.randomUUID().toString()).put("generation",gen.getName()).put("loader",loader).put("files",files);
            durable(new File(txn,"journal.new"),text(journal.toString(2)));
            Files.move(new File(txn,"journal.new").toPath(),new File(txn,"journal.json").toPath(),StandardCopyOption.ATOMIC_MOVE);
            progress.update("Replacing only the prepared xiloader…");
            for(int i=0;i<files.length();i++){
                SafeZip.checkCancelled();JSONObject entry=files.getJSONObject(i);File file=safe(gen,entry.getString("path"),false);
                if(!hashOrMissing(file).equals(entry.getString("old")))throw new IOException("Prepared file changed during loader update");
                if(entry.getString("new").equals("missing"))Files.deleteIfExists(file.toPath());else replace(safe(txn,i+".new",true),file,entry.optBoolean("executable"));
                progress.update("Updated xiloader metadata · "+(i+1)+" / "+files.length());
            }
            // Retain recovery material after commit; old successful-launch proof lives only in this history.
            durable(new File(txn,"committed"),text("1\n"));
            progress.update("Xiloader update committed; retaining previous loader…");
            archive(gen,txn,journal.getString("id"));
            return "Imported xiloader applied to the prepared client. Game updates, Windows environment and settings retained. Launch to validate this loader.";
        }catch(Exception failure){
            boolean interrupted=Thread.interrupted();try{recover(message->{});}catch(Exception recovery){failure.addSuppressed(recovery);}finally{if(interrupted)Thread.currentThread().interrupt();}
            throw failure;
        }
    }
}
