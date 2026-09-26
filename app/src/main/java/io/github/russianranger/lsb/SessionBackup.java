package io.github.russianranger.lsb;

import android.content.*;
import android.system.Os;
import io.github.russianranger.lsb.core.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A stopped, complete installation snapshot, independent of the Android package ID. */
final class SessionBackup {
    static volatile boolean active;
    static volatile String recoveryError="";
    private static final String SETTINGS="session-restore-settings.json";
    private static final String FORMAT="lsb-complete-session-v1";
    private static final String[] KNOWN_PREFS={"runtime","controller","compatibility","server","preparation"};
    private static SessionTransaction transaction(Context c)throws IOException {
        // getFilesDir()/storage() may recreate a root missing halfway through a swap.
        // Recovery must see that absence before normal app initialization creates it.
        File files=new File(c.getApplicationInfo().dataDir,"files"),external=c.getExternalFilesDir(null);
        return new SessionTransaction(files,new File(external==null?files:external,"lsb"),new File(c.getNoBackupFilesDir(),"session-transfer"));
    }
    private static Map<String,File> roots(File files,File managed){
        Map<String,File> roots=new LinkedHashMap<>();roots.put("files",files);roots.put("managed",managed);return roots;
    }
    static void recover(Context c)throws Exception {
        try {
            SessionTransaction transaction=transaction(c);
            if(transaction.recover()){
                applyPendingSettings(c);transaction.finish();new File(c.getFilesDir(),SETTINGS).delete();
                ClientRuntime.resetAfterRestore();ServerRuntime.resetAfterRestore();
            }
            recoveryError="";
        }catch(Exception error){recoveryError="Session recovery needs attention: "+error.getMessage();throw error;}
    }
    private static void idle(Context c)throws Exception {
        if(ClientRuntime.get(c).alive()||ServerRuntime.get(c).alive())throw new IOException("Stop the client and managed server before a complete backup or restore");
        // A dead Android service is not proof that its guest database stopped.
        File[] processes=new File("/proc").listFiles();
        if(processes!=null)for(File process:processes)try{
            int pid=Integer.parseInt(process.getName());
            if(pid==android.os.Process.myPid()||Os.stat(process.getPath()).st_uid!=android.os.Process.myUid())continue;
            byte[] bytes=Files.readAllBytes(new File(process,"environ").toPath());
            String environment=new String(bytes,StandardCharsets.UTF_8);
            if(environment.contains("LSB_RUNTIME_OWNER="+new File(c.getFilesDir(),"rt").getPath()+"\0")||environment.contains("LSB_SERVER_OWNER="+new File(c.getFilesDir(),"server-runtime").getPath()+"\0"))
                throw new OwnedProcessException();
        }catch(OwnedProcessException error){throw new IOException("A previous runtime process is still active. Stop both runtimes or restart LSB before backing up or restoring.");}
        catch(Exception ignored){}
    }
    private static final class OwnedProcessException extends Exception {}
    static void export(Context c,OutputStream out,SafeZip.Progress progress)throws Exception {
        active=true;
        try {
            idle(c);recover(c);
            try(AutoCloseable reserved=ServerRuntime.get(c).reserveSession(true,progress)) {
                File files=c.getFilesDir(),managed=MainActivity.storage(c);
                String nested=managed.toPath().startsWith(files.toPath())?files.toPath().relativize(managed.toPath()).toString().replace(File.separatorChar,'/'):null;
                JSONObject metadata=new JSONObject().put("format",FORMAT).put("package",c.getPackageName())
                    .put("created",System.currentTimeMillis()).put("preferences",captureSettings(c));
                SessionArchive.write(out,roots(files,managed),metadata.toString().getBytes(StandardCharsets.UTF_8),(root,path)->{
                    if(!root.equals("files"))return false;
                    if(nested!=null&&(path.equals(nested)||path.startsWith(nested+"/")))return true;
                    if(path.equals(SETTINGS))return true;
                    return path.equals("rt/run")||path.startsWith("rt/run/")||path.equals("rt/tmp")||path.startsWith("rt/tmp/")
                        ||path.equals("server-runtime/run")||path.startsWith("server-runtime/run/")||path.equals("server-runtime/tmp")||path.startsWith("server-runtime/tmp/");
                },progress);
            }
        }finally{active=false;}
    }
    static String restore(Context c,InputStream in,SafeZip.Progress progress)throws Exception {
        active=true;
        try {
            idle(c);recover(c);
            // Create normal destination roots before the transaction records
            // whether they exist, never as a side effect while decoding.
            Map<String,File> destinations=roots(c.getFilesDir(),MainActivity.storage(c));
            SessionTransaction transaction=transaction(c);boolean committed=false;
            try(AutoCloseable reserved=ServerRuntime.get(c).reserveSession(false,progress)) {
                try {
                    SessionTransaction.Stage stage=transaction.begin();
                    SessionArchive.Result result=SessionArchive.read(in,roots(stage.files(),stage.storage()),destinations,progress);
                    JSONObject metadata=new JSONObject(new String(result.metadata,StandardCharsets.UTF_8));
                    if(!FORMAT.equals(metadata.optString("format")))throw new IOException("Select a complete session backup. Older client-only backups use Restore legacy client backup.");
                    JSONObject prefs=metadata.getJSONObject("preferences");validateSettings(prefs);
                    File marker=new File(stage.files(),SETTINGS);
                    if(Files.exists(marker.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Backup contains a reserved restore marker");
                    FilesEx.text(marker,prefs.toString());
                    SafeZip.checkCancelled();progress.update("Backup verified. Activating the restored installation…");
                    // No cancellation between committing the folders and committing settings.
                    transaction.activate();committed=true;
                    applyPendingSettings(c);transaction.finish();new File(c.getFilesDir(),SETTINGS).delete();
                    ClientRuntime.resetAfterRestore();
                }catch(Exception error){if(committed)recoveryError="Restore activation needs recovery: "+error.getMessage();throw error;}
                finally{if(!committed)try{transaction.abort();}catch(Exception error){recoveryError="Session recovery needs attention: "+error.getMessage();throw error;}}
            }
            ServerRuntime.resetAfterRestore();
            return "Complete session restored and verified. Settings, prepared clients, runtimes, server and databases are ready. Stop the other app’s server before testing this copy.";
        }finally{active=false;}
    }
    private static Set<String> preferenceNames(Context c){
        Set<String> names=new TreeSet<>(Arrays.asList(KNOWN_PREFS));
        File[] files=new File(c.getApplicationInfo().dataDir,"shared_prefs").listFiles();
        if(files!=null)for(File file:files)if(file.getName().endsWith(".xml"))names.add(file.getName().substring(0,file.getName().length()-4));
        return names;
    }
    static JSONObject captureSettings(Context c)throws Exception {
        JSONObject all=new JSONObject();
        for(String name:preferenceNames(c)) {
            JSONObject values=new JSONObject();
            for(Map.Entry<String,?> item:c.getSharedPreferences(name,0).getAll().entrySet()) {
                Object value=item.getValue();String type;
                if(value instanceof String)type="string";
                else if(value instanceof Boolean)type="boolean";
                else if(value instanceof Integer)type="int";
                else if(value instanceof Long)type="long";
                else if(value instanceof Float)type="float";
                else if(value instanceof Set){type="set";value=new JSONArray(new TreeSet<>((Set<String>)value));}
                else throw new IOException("Unsupported setting type");
                values.put(item.getKey(),new JSONObject().put("type",type).put("value",value));
            }
            all.put(name,values);
        }
        validateSettings(all);return all;
    }
    private static void validateSettings(JSONObject all)throws Exception {
        if(all.length()>128)throw new IOException("Too many setting groups");
        Iterator<String> names=all.keys();while(names.hasNext()) {
            String name=names.next();if(!name.matches("[A-Za-z0-9_.-]{1,128}"))throw new IOException("Invalid setting group");
            JSONObject values=all.getJSONObject(name);if(values.length()>10000)throw new IOException("Too many settings");
            Iterator<String> keys=values.keys();while(keys.hasNext()) {
                JSONObject item=values.getJSONObject(keys.next());String type=item.getString("type");
                switch(type){
                    case "string":item.getString("value");break;
                    case "boolean":item.getBoolean("value");break;
                    case "int":Integer.parseInt(item.get("value").toString());break;
                    case "long":Long.parseLong(item.get("value").toString());break;
                    case "float":if(!Float.isFinite(Float.parseFloat(item.get("value").toString())))throw new IOException("Invalid float setting");break;
                    case "set":JSONArray set=item.getJSONArray("value");for(int i=0;i<set.length();i++)set.getString(i);break;
                    default:throw new IOException("Invalid setting type");
                }
            }
        }
    }
    private static void applyPendingSettings(Context c)throws Exception {
        File marker=new File(c.getFilesDir(),SETTINGS);
        if(!marker.isFile()||Files.isSymbolicLink(marker.toPath()))throw new IOException("Restored settings are missing; restart LSB to retry recovery");
        JSONObject all=new JSONObject(FilesEx.read(marker,1048576));validateSettings(all);
        Set<String> names=preferenceNames(c);Iterator<String> restored=all.keys();while(restored.hasNext())names.add(restored.next());
        for(String name:names) {
            SharedPreferences.Editor edit=c.getSharedPreferences(name,0).edit().clear();JSONObject values=all.optJSONObject(name);
            if(values!=null){Iterator<String> keys=values.keys();while(keys.hasNext()){
                String key=keys.next();JSONObject item=values.getJSONObject(key);
                switch(item.getString("type")){
                    case "string":edit.putString(key,item.getString("value"));break;
                    case "boolean":edit.putBoolean(key,item.getBoolean("value"));break;
                    case "int":edit.putInt(key,Integer.parseInt(item.get("value").toString()));break;
                    case "long":edit.putLong(key,Long.parseLong(item.get("value").toString()));break;
                    case "float":edit.putFloat(key,Float.parseFloat(item.get("value").toString()));break;
                    case "set":Set<String> set=new HashSet<>();JSONArray array=item.getJSONArray("value");for(int i=0;i<array.length();i++)set.add(array.getString(i));edit.putStringSet(key,set);break;
                }
            }}
            if(!edit.commit())throw new IOException("Could not restore app settings; restart LSB to retry recovery");
        }
        // Keep the marker until the transaction is finished, so recovery is idempotent.
    }
}
