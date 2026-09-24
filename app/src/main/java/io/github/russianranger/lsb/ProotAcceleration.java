package io.github.russianranger.lsb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/** Validated syscall filtering. Raw wrapper output is discarded, including on failure. */
final class ProotAcceleration {
    static final String MARKER="TRASC PRoot: seccomp acceleration observed";
    static final String CHECK="LSB_PROOT_PREFLIGHT_V1 PASS";
    interface Check {void check()throws Exception;}
    interface Cleanup {void run()throws Exception;}
    interface Starter {Process start(ProcessBuilder builder)throws IOException;}
    private final String session;
    private final File run,logs;
    private final Starter starter;
    private final long timeoutMs;
    private boolean requested,preflight,observed;
    private long elapsed;
    private String reason="not_requested";
    private MarkerStream launch;

    ProotAcceleration(String session,File run,File logs){this(session,run,logs,ProcessBuilder::start,15000);}
    ProotAcceleration(String session,File run,File logs,Starter starter,long timeoutMs){
        this.session=session;this.run=run;this.logs=logs;this.starter=starter;this.timeoutMs=timeoutMs;
    }
    static boolean eligible(JSONObject r){
        String trial=r.optString("performance_trial","none");
        return ("none".equals(trial)||"syscall_filter".equals(trial))
            &&"launch".equals(r.optString("action"))&&"fex".equals(r.optString("engine"))
            &&"turnip26".equals(r.optString("renderer"))&&"2.7.1".equals(r.optString("dxvk_version"))
            &&r.optBoolean("dxvk_two_compilers")&&!r.optBoolean("turnip_sysmem")&&!r.optBoolean("dxvk_staged_buffers");
    }
    JSONObject receipt()throws Exception{
        return new JSONObject().put("format",1).put("session_id",session).put("requested",requested?"syscall_filter":"none")
            .put("preflight_passed",preflight).put("launch_observed",observed)
            .put("mode",observed?"syscall_filter":"compatibility").put("reason",reason).put("preflight_ms",elapsed);
    }
    private void save()throws Exception{
        String data=receipt().toString();ClientRuntime.write(new File(run,"proot-acceleration.json"),data);ClientRuntime.write(new File(logs,"proot-acceleration.json"),data);
    }
    static void configure(ProcessBuilder builder,boolean enabled){
        if(enabled){builder.environment().remove("PROOT_NO_SECCOMP");builder.environment().put("TRASC_PROOT_REPORT","1");}
        else{builder.environment().put("PROOT_NO_SECCOMP","1");builder.environment().remove("TRASC_PROOT_REPORT");}
    }
    boolean prepare(ProcessBuilder main,JSONObject request,Check stop,Cleanup cleanup)throws Exception{
        configure(main,false);requested=request.optBoolean("proot_acceleration","syscall_filter".equals(request.optString("performance_trial")));
        if(!requested)return false;
        if(!eligible(request)){reason="requires_tested_fex_turnip26_two_worker_profile";save();return false;}
        reason="checking";save();stop.check();
        List<String> command=new ArrayList<>(main.command());int script=command.indexOf("/opt/lsb/supervisor.py");
        if(script<0||script!=command.size()-1)throw new IOException("Cannot isolate the syscall-filter check");
        command.set(script,"/opt/lsb/proot_preflight.py");if(command.contains("--sysvipc"))command.add("--sysvipc");
        ProcessBuilder probe=new ProcessBuilder(command);probe.environment().clear();probe.environment().putAll(main.environment());
        probe.directory(main.directory());probe.redirectErrorStream(true);configure(probe,true);
        Process child=null;MarkerStream reader=null;long begin=System.nanoTime();boolean interrupted=false;
        try{
            child=starter.start(probe);child.getOutputStream().close();reader=new MarkerStream(child.getInputStream());reader.start();
            long deadline=begin+TimeUnit.MILLISECONDS.toNanos(timeoutMs);
            while(!child.waitFor(100,TimeUnit.MILLISECONDS)){
                stop.check();if(System.nanoTime()>=deadline){reason="preflight_timeout";break;}
            }
            stop.check();
            if(!child.isAlive()){
                reader.join(1000);
                if(child.exitValue()!=0)reason="preflight_failed";
                else if(!reader.marker)reason="filter_not_observed";
                else if(!reader.check||reader.failed||reader.isAlive())reason="preflight_incomplete";
                else{preflight=true;reason="preflight_passed";}
            }
        }catch(InterruptedException|InterruptedIOException e){interrupted=true;reason="cancelled";throw e;}
        catch(IOException e){reason="preflight_unavailable";}
        finally{
            elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-begin);
            boolean restore=Thread.interrupted()||interrupted;
            try{
                Exception cleanupFailure=null;
                try{terminate(child);}catch(Exception e){cleanupFailure=e;if(e instanceof InterruptedException)restore=true;}
                // Only called before the main tree exists. Failure blocks login.
                // Reap descendants before touching their stdout pipe: a close
                // can otherwise wait behind a blocked ProcessInputStream read.
                // Reaping must still run if terminating the wrapper itself fails.
                try{cleanup.run();}catch(Exception e){
                    if(e instanceof InterruptedException)restore=true;
                    if(cleanupFailure==null)cleanupFailure=e;else cleanupFailure.addSuppressed(e);
                }
                if(cleanupFailure!=null)throw cleanupFailure;
                if(reader!=null){reader.join(1000);if(reader.isAlive())throw new IOException("Syscall-filter output did not close");reader.close();}
            }catch(Exception e){preflight=false;reason="cleanup_failed";throw e;}
            finally{save();if(restore)Thread.currentThread().interrupt();}
        }
        stop.check();configure(main,preflight);return preflight;
    }
    void observeLaunch(Process child,Check stop)throws Exception{
        if(!preflight)throw new IOException("Syscall-filter preflight is missing");
        reason="awaiting_launch_marker";save();launch=new MarkerStream(child.getInputStream());launch.start();
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        try{
            while(!launch.marker){
                stop.check();
                if(!child.isAlive()||launch.failed||System.nanoTime()>=deadline){reason="launch_filter_not_observed";save();throw new IOException("Syscall filtering was not confirmed; turn off Runtime syscall filtering in Proven fixes and export support");}
                Thread.sleep(10);
            }
            stop.check();
        }catch(InterruptedException|InterruptedIOException e){reason="cancelled";save();throw e;}
        observed=true;reason="launch_filter_observed";save();
    }
    void close(){
        if(launch==null)return;
        try{launch.join(1000);}catch(InterruptedException e){Thread.currentThread().interrupt();}
        // A failed reap may leave a pipe writer alive. Never synchronously close
        // underneath its blocked read; the daemon reader closes itself at EOF.
        if(!launch.isAlive())launch.close();
    }
    static void terminate(Process child)throws Exception{
        if(child==null||!child.isAlive())return;child.destroy();
        if(!child.waitFor(2,TimeUnit.SECONDS)){child.destroyForcibly();if(!child.waitFor(2,TimeUnit.SECONDS))throw new IOException("Syscall-filter check did not stop");}
    }
    /** Exact fixed lines only; long, malformed, embedded or arbitrary output is never retained. */
    static final class MarkerStream extends Thread {
        private final InputStream input;
        volatile boolean marker,check,failed;
        MarkerStream(InputStream input){super("lsb-proot-markers");this.input=input;setDaemon(true);}
        public void run(){
            byte[] chunk=new byte[4096],line=new byte[128];int used=0;boolean discard=false;
            try{int n;while((n=input.read(chunk))!=-1)for(int i=0;i<n;i++){
                int b=chunk[i]&255;
                if(b==10){
                    if(!discard){String value=new String(line,0,used,StandardCharsets.US_ASCII);if(MARKER.equals(value))marker=true;if(CHECK.equals(value))check=true;}
                    used=0;discard=false;
                }else if(!discard){if(b<32||b>126||used==line.length){discard=true;used=0;}else line[used++]=(byte)b;}
            }}catch(IOException e){failed=true;}finally{close();}
        }
        void close(){try{input.close();}catch(IOException ignored){}}
    }
}
