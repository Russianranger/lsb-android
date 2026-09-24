package io.github.russianranger.lsb;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class SessionHistoryTest {
    private String id(int n){return String.format(Locale.ROOT,"00000000-0000-0000-0000-%012d",n);}
    private File logs()throws Exception{return Files.createTempDirectory("session-history").toFile();}
    private void write(File dir,String name,JSONObject data)throws Exception{Files.write(new File(dir,name).toPath(),data.toString().getBytes("UTF-8"));}
    private JSONObject receipt(int n)throws Exception{return new JSONObject().put("session_id",id(n)).put("started_at",1700000000+n).put("action","launch").put("phase","stopped");}
    private Map<String,JSONObject> exported(File logs)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(bytes)){SessionHistory.export(logs,z);}
        Map<String,JSONObject> entries=new HashMap<>();try(ZipInputStream z=new ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))){
            ZipEntry e;byte[] buffer=new byte[8192];while((e=z.getNextEntry())!=null){ByteArrayOutputStream out=new ByteArrayOutputStream();int n;while((n=z.read(buffer))!=-1)out.write(buffer,0,n);entries.put(e.getName(),new JSONObject(new String(out.toByteArray(),"UTF-8")));}
        }return entries;
    }
    @Test public void lastSixSessionsSurviveRotationInChronologicalOrder()throws Exception{
        File logs=logs();
        for(int n=1;n<=8;n++){
            if(n>1){LogRetention.rotate(new File(logs,"runtime-state.json"));LogRetention.rotate(new File(logs,"native-display-performance.json"));}
            write(logs,"runtime-state.json",receipt(n).put("graphics_tuning",new JSONObject().put("active",new JSONObject().put("dxvk_staged_buffers",n<3)))
                .put("performance_trial",new JSONObject().put("active",n==6?"one_compiler":"none")));
            write(logs,"native-display-performance.json",receipt(n).put("windows",new JSONArray().put(n)));
            SessionHistory.capture(logs);
        }
        Map<String,JSONObject> out=exported(logs);JSONArray index=out.get("runtime/sessions/index.json").getJSONArray("sessions");
        assertEquals(6,index.length());assertEquals(7,out.size());
        for(int i=0;i<6;i++){
            JSONObject row=index.getJSONObject(i),archive=out.get(row.getString("file"));assertEquals(id(i+3),row.getString("session_id"));
            assertEquals(id(i+3),archive.getJSONObject("runtime").getString("session_id"));assertEquals(i+3,archive.getJSONObject("native_display").getJSONArray("windows").getInt(0));
        }
        assertEquals("one_compiler",index.getJSONObject(3).getJSONObject("performance_trial").getString("active"));
    }
    @Test public void upgradeSeedsTwoSlotsAndRefreshesWithoutMixingStaleReports()throws Exception{
        File logs=logs();write(logs,"runtime-state.json.previous",receipt(1));write(logs,"runtime-state.json",receipt(2).put("phase","running"));
        write(logs,"native-display-performance.json.previous",receipt(1));write(logs,"native-display-performance.json",receipt(99));
        write(logs,"native-display-performance.json.new.previous",receipt(98));
        write(logs,"client-launch.json",receipt(2));
        write(logs,"proot-acceleration.json",receipt(2).put("mode","syscall_filter").put("launch_observed",true));SessionHistory.capture(logs);
        write(logs,"runtime-state.json",receipt(2));write(logs,"native-display-performance.json",receipt(2).put("terminal",true));SessionHistory.capture(logs);
        Map<String,JSONObject> out=exported(logs);JSONArray index=out.get("runtime/sessions/index.json").getJSONArray("sessions");assertEquals(2,index.length());
        JSONObject latest=out.get(index.getJSONObject(1).getString("file"));assertEquals("stopped",latest.getJSONObject("runtime").getString("phase"));
        assertTrue(latest.getJSONObject("proot_acceleration").getBoolean("launch_observed"));
        assertEquals("syscall_filter",index.getJSONObject(1).getJSONObject("proot_acceleration").getString("mode"));
        assertTrue(latest.getJSONObject("native_display").getBoolean("terminal"));assertFalse(out.toString().contains(id(99)));assertFalse(out.toString().contains(id(98)));
        write(logs,"native-display-performance.json",receipt(99));write(logs,"proot-acceleration.json",receipt(99));SessionHistory.capture(logs);out=exported(logs);
        latest=out.get(index.getJSONObject(1).getString("file"));assertFalse(latest.has("native_display"));assertFalse(latest.has("proot_acceleration"));assertTrue(latest.getJSONArray("omitted").toString().contains("different session"));
    }
    @Test public void malformedIdsOversizedFilesAndUnfinishedWritesAreExcluded()throws Exception{
        File logs=logs();write(logs,"runtime-state.json",receipt(1));SessionHistory.capture(logs);
        write(logs,"runtime-state.json",receipt(2).put("session_id","../escape"));SessionHistory.capture(logs);
        File pending=new File(logs,"sessions/1700000000000-"+id(3)+".json.new");Files.write(pending.toPath(),"partial".getBytes("UTF-8"));
        assertEquals(2,exported(logs).size());
        write(logs,"runtime-state.json",receipt(2));File large=new File(logs,"native-display-performance.json");try(RandomAccessFile file=new RandomAccessFile(large,"rw")){file.setLength(SessionHistory.INPUT_LIMIT+1);}
        SessionHistory.capture(logs);Map<String,JSONObject> out=exported(logs);JSONArray index=out.get("runtime/sessions/index.json").getJSONArray("sessions");assertEquals(2,index.length());
        JSONObject latest=out.get(index.getJSONObject(1).getString("file"));assertFalse(latest.has("native_display"));assertTrue(latest.getJSONArray("omitted").toString().contains("unavailable"));
    }
}
