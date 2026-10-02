package io.github.russianranger.lsb;

import android.content.*;
import io.github.russianranger.lsb.core.FilesEx;
import java.io.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class ServerAccelerationTest {
    private static class Child extends Process {
        final ByteArrayOutputStream input=new ByteArrayOutputStream();final String output;boolean alive;
        Child(String output,boolean alive){this.output=output;this.alive=alive;}
        public OutputStream getOutputStream(){return input;}
        public InputStream getInputStream(){return new ByteArrayInputStream(output.getBytes(java.nio.charset.StandardCharsets.US_ASCII));}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        public boolean isAlive(){return alive;}
        public int exitValue(){if(alive)throw new IllegalThreadStateException();return 0;}
        public int waitFor(){alive=false;return 0;}
        public boolean waitFor(long timeout,TimeUnit unit){
            if(alive)assertEquals("LSB_SERVER_FILTER_GO_V1\n",input.toString());
            alive=false;return true;
        }
        public void destroy(){alive=false;}
        public Process destroyForcibly(){destroy();return this;}
    }
    private Context context(){
        Context base=RuntimeEnvironment.getApplication();
        return new ContextWrapper(base){@Override public android.content.pm.ApplicationInfo getApplicationInfo(){
            android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo(super.getApplicationInfo());info.nativeLibraryDir=getFilesDir().getPath();return info;
        }};
    }
    @Test public void startsOnlyAfterBothChecksOrFallsBackBeforeDatabaseRelease()throws Exception{
        for(int scenario=0;scenario<4;scenario++){
            final boolean enabled=scenario!=0,probePass=scenario!=1,launchPass=scenario==3;
            Context context=context();File home=new File(context.getFilesDir(),"server-runtime");FilesEx.delete(home);
            context.getSharedPreferences("server",0).edit().putBoolean("proot_acceleration",enabled).commit();
            int[] probes={0},gates={0},ordinary={0};Child[] gate={null};
            ServerRuntime runtime=new ServerRuntime(context,builder->{
                assertEquals("keep player data",FilesEx.read(new File(home,"state/sentinel"),1024));
                if(builder.command().contains("/opt/lsb-server/proot_preflight.py")){
                    probes[0]++;assertFalse(builder.environment().containsKey("PROOT_NO_SECCOMP"));
                    assertTrue(builder.command().contains("--server"));assertTrue(builder.environment().containsKey("LSB_SERVER_FILTER_OWNER"));
                    return new Child((probePass?ProotAcceleration.MARKER+"\n":"")+ProotAcceleration.CHECK+"\n",false);
                }
                if(builder.command().contains("/opt/lsb-server/accelerated_start.py")){
                    gates[0]++;assertFalse(builder.environment().containsKey("PROOT_NO_SECCOMP"));
                    assertEquals("1",builder.environment().get("TRASC_PROOT_REPORT"));
                    if(launchPass)FilesEx.text(new File(home,"logs/supervisor.log"),ProotAcceleration.MARKER+"\n");
                    return gate[0]=new Child("",launchPass);
                }
                ordinary[0]++;assertTrue(builder.command().contains("/opt/lsb-server/manager.py"));
                assertEquals("1",builder.environment().get("PROOT_NO_SECCOMP"));assertFalse(builder.environment().containsKey("TRASC_PROOT_REPORT"));
                assertFalse(builder.environment().containsKey("LSB_SERVER_FILTER_OWNER"));return new Child("",false);
            });
            try{
                FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");FilesEx.text(new File(runtime.state,"sentinel"),"keep player data");
                runtime.perform("start",false,value->{});
                assertEquals(enabled?1:0,probes[0]);assertEquals(enabled&&probePass?1:0,gates[0]);assertEquals(launchPass?0:1,ordinary[0]);
                if(gate[0]!=null)assertEquals(launchPass?"LSB_SERVER_FILTER_GO_V1\n":"",gate[0].input.toString());
                JSONObject receipt=new JSONObject(FilesEx.read(new File(runtime.logs,"proot-acceleration.json"),65536));
                assertEquals(launchPass,receipt.getBoolean("launch_observed"));assertEquals(launchPass?"syscall_filter":"compatibility",receipt.getString("mode"));
                assertEquals("keep player data",FilesEx.read(new File(runtime.state,"sentinel"),1024));assertFalse(runtime.alive());
                if(scenario==2)assertEquals("launch_filter_not_observed",receipt.getString("reason"));
            }finally{FilesEx.delete(home);context.getSharedPreferences("server",0).edit().clear().commit();}
        }
    }
    @Test public void stopDuringProbeBlocksEveryServerLaunch()throws Exception{
        Context context=context();File home=new File(context.getFilesDir(),"server-runtime");FilesEx.delete(home);int[] probes={0};
        ServerRuntime runtime=new ServerRuntime(context,builder->{
            assertTrue(builder.command().contains("/opt/lsb-server/proot_preflight.py"));probes[0]++;
            FilesEx.text(new File(home,"run/stop"),"stop");return new Child(ProotAcceleration.MARKER+"\n"+ProotAcceleration.CHECK+"\n",false);
        });
        try{
            FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");
            try{runtime.perform("start",false,value->{});fail("Cancelled probe must not launch the server");}catch(InterruptedIOException expected){}
            assertEquals(1,probes[0]);assertFalse(runtime.alive());
            assertFalse(new File(runtime.run,"status.json").exists());
        }finally{Thread.interrupted();FilesEx.delete(home);context.getSharedPreferences("server",0).edit().clear().commit();}
    }
    @Test public void maintenanceNeverProbesEvenWhenAccelerationIsEnabled()throws Exception{
        Context context=context();context.getSharedPreferences("server",0).edit().putBoolean("proot_acceleration",true).commit();int[] calls={0};
        ServerRuntime runtime=new ServerRuntime(context,builder->{
            assertTrue(builder.command().contains("/opt/lsb-server/manager.py"));assertEquals("1",builder.environment().get("PROOT_NO_SECCOMP"));
            assertFalse(builder.environment().containsKey("TRASC_PROOT_REPORT"));calls[0]++;return new Child("",false);
        });
        try{
            FilesEx.delete(runtime.home);FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");FilesEx.text(new File(runtime.root,"lsb-server-tools-v4"),"ready");
            for(String action:new String[]{"backup","inspect","rollback","install-build-cache"})runtime.perform(action,false,value->{});
            runtime.performCheckpoint("create-checkpoint",null,5,value->{});assertEquals(5,calls[0]);
        }finally{FilesEx.delete(runtime.home);context.getSharedPreferences("server",0).edit().clear().commit();}
    }
}
