package io.github.russianranger.lsb;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class ProotAccelerationTest {
    private static final String ID="00000000-0000-0000-0000-000000000001";
    private static class Child extends Process {
        final InputStream output;boolean alive,destroyed;int exit;
        Child(String text,boolean alive)throws Exception{output=new ByteArrayInputStream(text.getBytes("UTF-8"));this.alive=alive;}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){return output;}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        public int waitFor()throws InterruptedException{while(alive)Thread.sleep(5);return exit;}
        public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{if(alive)Thread.sleep(5);return !alive;}
        public int exitValue(){if(alive)throw new IllegalThreadStateException();return exit;}
        public boolean isAlive(){return alive;}
        public void destroy(){alive=false;destroyed=true;exit=143;}
        public Process destroyForcibly(){destroy();return this;}
    }
    private JSONObject request()throws Exception{return new JSONObject().put("performance_trial","none").put("proot_acceleration",true).put("action","launch")
        .put("engine","fex").put("renderer","turnip26").put("dxvk_version","2.7.1").put("dxvk_two_compilers",true);}
    private ProcessBuilder command(){return new ProcessBuilder("proot","--sysvipc","--kill-on-exit","/usr/bin/python3","/opt/lsb/supervisor.py");}
    private ProotAcceleration helper(Child child,File dir,java.util.List<ProcessBuilder> launched)throws Exception{
        return new ProotAcceleration(ID,dir,new File(dir,"logs"),builder->{launched.add(builder);return child;},5);
    }
    @Test public void recognizesOnlyCompleteExactMarkersWithBoundedNoise()throws Exception{
        String noise="password=secret "+ProotAcceleration.MARKER+"\n"+String.join("",Collections.nCopies(10000,"x"))+ProotAcceleration.MARKER+"\n";
        ProotAcceleration.MarkerStream bad=new ProotAcceleration.MarkerStream(new ByteArrayInputStream((noise+ProotAcceleration.MARKER).getBytes("UTF-8")));
        bad.start();bad.join(1000);assertFalse(bad.marker);assertFalse(bad.check);
        byte[] bytes=(noise+ProotAcceleration.MARKER+"\n"+ProotAcceleration.CHECK+"\n").getBytes("UTF-8");
        InputStream fragmented=new ByteArrayInputStream(bytes){public synchronized int read(byte[] b,int off,int n){return super.read(b,off,Math.min(n,3));}};
        ProotAcceleration.MarkerStream good=new ProotAcceleration.MarkerStream(fragmented);good.start();good.join(1000);
        assertTrue(good.marker);assertTrue(good.check);assertFalse(good.failed);
    }
    @Test public void successfulProbeCleansUpBeforeEnablingAndObservesRealLaunch()throws Exception{
        File dir=Files.createTempDirectory("proot-acceleration").toFile();Child probe=new Child(ProotAcceleration.MARKER+"\n"+ProotAcceleration.CHECK+"\n",false);
        List<ProcessBuilder> launched=new ArrayList<>();ProotAcceleration helper=helper(probe,dir,launched);ProcessBuilder main=command();int[] cleaned={0};
        assertTrue(helper.prepare(main,request(),()->{},()->{assertFalse(probe.isAlive());cleaned[0]++;}));
        assertEquals(1,cleaned[0]);assertEquals(1,launched.size());assertFalse(main.environment().containsKey("PROOT_NO_SECCOMP"));
        assertEquals("1",main.environment().get("TRASC_PROOT_REPORT"));assertFalse(launched.get(0).command().contains("/opt/lsb/supervisor.py"));
        assertEquals("--sysvipc",launched.get(0).command().get(launched.get(0).command().size()-1));
        assertFalse(helper.receipt().getBoolean("launch_observed"));
        Child runtime=new Child("secret login output discarded\n"+ProotAcceleration.MARKER+"\n",true);helper.observeLaunch(runtime,()->{});
        JSONObject saved=new JSONObject(new String(Files.readAllBytes(new File(dir,"proot-acceleration.json").toPath()),"UTF-8"));
        assertEquals(ID,saved.getString("session_id"));assertEquals("syscall_filter",saved.getString("mode"));assertTrue(saved.getBoolean("launch_observed"));
        assertFalse(saved.toString().contains("secret"));helper.close();
        JSONObject legacy=request().put("performance_trial","syscall_filter");legacy.remove("proot_acceleration");
        helper=helper(new Child(ProotAcceleration.MARKER+"\n"+ProotAcceleration.CHECK+"\n",false),Files.createTempDirectory("proot-legacy").toFile(),new ArrayList<>());
        assertTrue(helper.prepare(command(),legacy,()->{},()->{}));assertEquals("syscall_filter",helper.receipt().getString("requested"));
    }
    @Test public void missingActivationFailureAndTimeoutKeepCompatibility()throws Exception{
        for(int mode=0;mode<3;mode++){
            Child probe=new Child(mode==0?ProotAcceleration.CHECK+"\n":ProotAcceleration.MARKER+"\n"+ProotAcceleration.CHECK+"\n",mode==2);
            if(mode==1)probe.exit=1;
            ProotAcceleration helper=helper(probe,Files.createTempDirectory("proot-fallback").toFile(),new ArrayList<>());ProcessBuilder main=command();int[] cleaned={0};
            assertFalse(helper.prepare(main,request(),()->{},()->{assertFalse(probe.isAlive());cleaned[0]++;}));
            assertEquals(1,cleaned[0]);assertEquals("1",main.environment().get("PROOT_NO_SECCOMP"));assertFalse(main.environment().containsKey("TRASC_PROOT_REPORT"));
            assertFalse(helper.receipt().getBoolean("preflight_passed"));assertFalse(helper.receipt().getBoolean("launch_observed"));
            if(mode==2)assertTrue(probe.destroyed);
        }
    }
    @Test public void stopAndCleanupFailureBlockContinuation()throws Exception{
        Child probe=new Child("",true);ProotAcceleration helper=helper(probe,Files.createTempDirectory("proot-stop").toFile(),new ArrayList<>());int[] checks={0},cleaned={0};
        try{
            helper.prepare(command(),request(),()->{if(++checks[0]>1)throw new InterruptedIOException("stop");},()->{assertFalse(probe.isAlive());cleaned[0]++;});fail("Stop must abort");
        }catch(InterruptedIOException expected){}finally{Thread.interrupted();}
        assertTrue(probe.destroyed);assertEquals(1,cleaned[0]);assertEquals("cancelled",helper.receipt().getString("reason"));
        Child complete=new Child(ProotAcceleration.MARKER+"\n"+ProotAcceleration.CHECK+"\n",false);
        helper=helper(complete,Files.createTempDirectory("proot-cleanup").toFile(),new ArrayList<>());
        try{helper.prepare(command(),request(),()->{},()->{throw new IOException("remaining descendants");});fail("Cleanup failure must abort");}
        catch(IOException expected){}assertFalse(helper.receipt().getBoolean("preflight_passed"));assertEquals("cleanup_failed",helper.receipt().getString("reason"));
    }
    @Test public void unsupportedOrUnselectedTrialNeverSpawnsProbe()throws Exception{
        List<ProcessBuilder> launched=new ArrayList<>();Child child=new Child("",false);
        for(String trial:new String[]{"none","syscall_filter","one_compiler","cached_dynamic","gpl_fast"}){
            ProotAcceleration helper=helper(child,Files.createTempDirectory("proot-declined").toFile(),launched);ProcessBuilder main=command();
            boolean experiment=!"none".equals(trial)&&!"syscall_filter".equals(trial);
            JSONObject request=request().put("performance_trial",trial).put("proot_acceleration",experiment);
            assertFalse(helper.prepare(main,request,()->{},()->{fail("No probe exists");}));assertEquals("1",main.environment().get("PROOT_NO_SECCOMP"));
            assertEquals(experiment?"syscall_filter":"none",helper.receipt().getString("requested"));
        }
        ProotAcceleration helper=helper(child,Files.createTempDirectory("proot-past-experiment").toFile(),launched);
        assertFalse(helper.prepare(command(),request().put("turnip_sysmem",true),()->{},()->{fail("No probe exists");}));
        assertTrue(launched.isEmpty());
    }
    @Test public void descendantsAreReapedBeforeClosingBlockedOutput()throws Exception{
        java.util.concurrent.atomic.AtomicBoolean reaped=new java.util.concurrent.atomic.AtomicBoolean(),earlyClose=new java.util.concurrent.atomic.AtomicBoolean();
        InputStream pipe=new InputStream(){
            public int read()throws IOException{while(!reaped.get())try{Thread.sleep(1);}catch(InterruptedException e){throw new InterruptedIOException();}return -1;}
            public void close(){if(!reaped.get())earlyClose.set(true);}
        };
        Child child=new Child("",true){public InputStream getInputStream(){return pipe;}};
        ProotAcceleration helper=helper(child,Files.createTempDirectory("proot-descendants").toFile(),new ArrayList<>());
        assertFalse(helper.prepare(command(),request(),()->{},()->{assertFalse(child.isAlive());reaped.set(true);}));
        assertTrue(reaped.get());assertFalse(earlyClose.get());assertEquals("preflight_timeout",helper.receipt().getString("reason"));
    }
    @Test public void mainLaunchMustIndependentlyConfirmFilterAndHonorsStop()throws Exception{
        for(boolean stopped:new boolean[]{false,true}){
            Child probe=new Child(ProotAcceleration.MARKER+"\n"+ProotAcceleration.CHECK+"\n",false);
            ProotAcceleration helper=helper(probe,Files.createTempDirectory("proot-main-marker").toFile(),new ArrayList<>());
            assertTrue(helper.prepare(command(),request(),()->{},()->{}));
            Child runtime=new Child("credential-like noise is never evidence\n",stopped);
            try{helper.observeLaunch(runtime,()->{if(stopped)throw new InterruptedIOException("stop");});fail("Must not proceed to credentials");}
            catch(IOException expected){}finally{runtime.destroy();helper.close();}
            assertFalse(helper.receipt().getBoolean("launch_observed"));
            assertEquals(stopped?"cancelled":"launch_filter_not_observed",helper.receipt().getString("reason"));
        }
    }
    @Test public void wrapperTerminationFailureStillReapsAndPreservesBothErrors()throws Exception{
        for(boolean reapFails:new boolean[]{false,true}){
            Child child=new Child("",true){public void destroy(){destroyed=true;}};
            ProotAcceleration helper=helper(child,Files.createTempDirectory("proot-failed-termination").toFile(),new ArrayList<>());
            int[] reaped={0};
            try{
                helper.prepare(command(),request(),()->{},()->{reaped[0]++;child.alive=false;if(reapFails)throw new IOException("reap failed");});
                fail("A failed termination must block continuation even after reaping");
            }catch(IOException expected){
                assertEquals("Syscall-filter check did not stop",expected.getMessage());
                assertEquals(reapFails?1:0,expected.getSuppressed().length);
            }
            assertEquals(1,reaped[0]);assertFalse(child.isAlive());assertFalse(helper.receipt().getBoolean("preflight_passed"));
            assertEquals("cleanup_failed",helper.receipt().getString("reason"));
        }
    }
}
