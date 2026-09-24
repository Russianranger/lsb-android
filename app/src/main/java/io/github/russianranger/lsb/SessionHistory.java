package io.github.russianranger.lsb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipOutputStream;
import org.json.*;
import io.github.russianranger.lsb.core.SafeZip;

/** Bounded, session-identified receipts. Called only before launch or on export,
 * never by the frame worker or a recurring gameplay timer. */
final class SessionHistory {
    static final int LIMIT=6, INPUT_LIMIT=1_048_576, ARCHIVE_LIMIT=4_194_304;
    private static final String ID="[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}";
    private static final String[] SOURCES={"runtime-state.json","client-launch.json","native-display-performance.json","display-performance.json"};
    private static final String[] KEYS={"runtime","client_launch","native_display","display"};
    private static JSONObject read(File file,int limit)throws Exception {
        if(Files.isSymbolicLink(file.toPath())||!file.isFile()||file.length()>limit)throw new IOException("Missing or oversized session receipt");
        return new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
    }
    private static File[] archives(File folder){
        File[] files=folder.listFiles(f->f.isFile()&&!Files.isSymbolicLink(f.toPath())&&f.getName().matches("[0-9]{13}-"+ID+"\\.json"));
        if(files==null)return new File[0];Arrays.sort(files,Comparator.comparing(File::getName));return files;
    }
    static synchronized void capture(File logs)throws Exception {
        File folder=new File(logs,"sessions");
        if(Files.isSymbolicLink(folder.toPath()))throw new IOException("Invalid history directory");
        if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("Cannot create session history");
        File[] stages=folder.listFiles(f->f.getName().matches("[0-9]{13}-"+ID+"\\.json\\.new"));
        if(stages!=null)for(File stage:stages)Files.deleteIfExists(stage.toPath());
        // Seed both retained slots on upgrade. Each receipt must match its own
        // session; stale rotated files must never be attached to a newer run.
        for(String suffix:new String[]{".previous",""}){
            File state=new File(logs,SOURCES[0]+suffix);if(!state.isFile())continue;
            JSONObject runtime=read(state,INPUT_LIMIT);String id=runtime.optString("session_id","");
            if(!id.matches(ID))continue;
            long started=(long)(runtime.optDouble("started_at",0)*1000);
            if(started<=0||started>9_999_999_999_999L)started=Math.min(state.lastModified(),9_999_999_999_999L);
            File dest=new File(folder,String.format(Locale.ROOT,"%013d-%s.json",started,id));
            for(File existing:archives(folder))if(existing.getName().endsWith("-"+id+".json")){dest=existing;break;}
            JSONObject archive=new JSONObject().put("format",1).put("session_id",id).put("runtime",runtime);
            JSONArray omitted=new JSONArray();
            for(int i=1;i<SOURCES.length;i++){
                try{
                    JSONObject receipt=read(new File(logs,SOURCES[i]+suffix),INPUT_LIMIT);
                    if(id.equals(receipt.optString("session_id")))archive.put(KEYS[i],receipt);
                    else omitted.put(SOURCES[i]+": different session");
                }catch(Exception error){omitted.put(SOURCES[i]+": unavailable");}
            }
            archive.put("omitted",omitted);
            byte[] bytes=archive.toString().getBytes(StandardCharsets.UTF_8);
            if(bytes.length>ARCHIVE_LIMIT)throw new IOException("Session history record exceeds limit");
            File stage=new File(folder,dest.getName()+".new");Files.write(stage.toPath(),bytes);
            Files.move(stage.toPath(),dest.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }
        File[] files=archives(folder);for(int i=0;i<files.length-LIMIT;i++)Files.delete(files[i].toPath());
    }
    static synchronized void export(File logs,ZipOutputStream zip)throws Exception {
        File folder=new File(logs,"sessions");if(Files.isSymbolicLink(folder.toPath()))return;
        JSONArray index=new JSONArray();
        File[] files=archives(folder);
        for(int i=Math.max(0,files.length-LIMIT);i<files.length;i++){
            File f=files[i];JSONObject record=read(f,ARCHIVE_LIMIT),runtime=record.getJSONObject("runtime");
            String path="runtime/sessions/"+f.getName();
            index.put(new JSONObject().put("session_id",record.getString("session_id"))
                .put("file",path).put("started_at",runtime.optDouble("started_at",0))
                .put("action",runtime.optString("action")).put("phase",runtime.optString("phase"))
                .put("graphics_tuning",runtime.optJSONObject("graphics_tuning"))
                .put("performance_trial",runtime.optJSONObject("performance_trial")));
            SafeZip.entry(zip,path,Files.readAllBytes(f.toPath()));
        }
        SafeZip.entry(zip,"runtime/sessions/index.json",new JSONObject().put("format",1).put("limit",LIMIT)
            .put("order","oldest to newest").put("sessions",index)
            .put("note","Archives begin with this update; overwritten older runs cannot be recovered.").toString(2));
    }
}
