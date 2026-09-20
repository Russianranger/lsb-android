package io.github.russianranger.lsb;

import android.content.Context;
import android.os.Build;
import android.system.Os;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipOutputStream;
import io.github.russianranger.lsb.core.SafeZip;

/** Owns only files/rt. It never opens or binds the managed client or Termux/GameHub. */
final class ClientRuntime {
    static final String RUNTIME_SHA="08c639c26506dc6fbd15464bec475337087bb23cb7c0c5ace2db5240ee36424f";
    static final String URL="https://github.com/Russianranger/lsb-android/releases/download/runtime-probe-v1/runtime-arm64.tar.gz";
    static final long ARCHIVE_BYTES=353710639L;
    private static ClientRuntime instance;
    static synchronized ClientRuntime get(Context c){if(instance==null)instance=new ClientRuntime(c.getApplicationContext());return instance;}
    final Context context;
    final File home,root,prefix,run,tmp,logs,backend,probes;
    volatile String status="Install the runtime, then start the Windows checks.";
    volatile boolean starting;
    private volatile Process process;
    private volatile boolean stopRequested;
    private AudioBridge audio;
    private String sessionId;
    private ClientRuntime(Context c){
        context=c;home=new File(c.getFilesDir(),"rt");root=new File(home,"root");prefix=new File(home,"prefix");
        run=new File(home,"run");tmp=new File(home,"tmp");logs=new File(home,"logs");backend=new File(home,"backend");probes=new File(home,"probe");
        for(File f:new File[]{home,run,tmp,logs})f.mkdirs();
    }
    boolean installed(){return new File(root,"lsb-runtime.sha256").isFile();}
    boolean alive(){Process p=process;return starting||(p!=null&&p.isAlive());}
    File displaySocket(){return new File(run,"display.sock");}
    static String read(File p,int max)throws IOException {
        if(p.length()>max)throw new IOException("Metadata exceeds limits");return new String(Files.readAllBytes(p.toPath()),StandardCharsets.UTF_8);
    }
    static void write(File p,String s)throws IOException {
        p.getParentFile().mkdirs();File t=new File(p.getParentFile(),p.getName()+".new");
        Files.write(t.toPath(),s.getBytes(StandardCharsets.UTF_8));Files.move(t.toPath(),p.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
    }
    static String sha(File file)throws Exception {
        MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] bytes=new byte[1024*1024];
        try(InputStream in=new FileInputStream(file)){int n;while((n=in.read(bytes))!=-1){interrupted();md.update(bytes,0,n);}}
        StringBuilder s=new StringBuilder();for(byte b:md.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    static void interrupted()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Runtime operation cancelled");}
    JSONObject state()throws Exception {
        JSONObject info=new JSONObject().put("installed",installed()).put("alive",alive()).put("starting",starting).put("status",status).put("game_files_mounted",false);
        File f=new File(run,"status.json");
        if(f.isFile())try{JSONObject state=new JSONObject(read(f,131072));info.put("launch",state);}catch(Exception ignored){}
        return info;
    }
    private List<Integer> ownedProcesses(){
        List<Integer> found=new ArrayList<>();File[] proc=new File("/proc").listFiles();if(proc==null)return found;
        byte[] needle=("LSB_RUNTIME_OWNER="+home.getPath()+"\0").getBytes(StandardCharsets.UTF_8);
        for(File p:proc)try{
            int pid=Integer.parseInt(p.getName());if(pid==android.os.Process.myPid())continue;
            // Own uid only; never identify a process by a common executable name.
            if(Os.stat(p.getPath()).st_uid!=android.os.Process.myUid())continue;
            byte[] env=Files.readAllBytes(new File(p,"environ").toPath());
            outer:for(int i=0;i+needle.length<=env.length;i++){for(int j=0;j<needle.length;j++)if(env[i+j]!=needle[j])continue outer;found.add(pid);break;}
        }catch(Exception ignored){}
        return found;
    }
    private void reapOrphans()throws Exception {
        for(int pid:ownedProcesses())try{Os.kill(pid,15);}catch(Exception ignored){}
        for(int n=0;n<20&&!ownedProcesses().isEmpty();n++)Thread.sleep(100);
        for(int pid:ownedProcesses())try{Os.kill(pid,9);}catch(Exception ignored){}
        for(int n=0;n<20&&!ownedProcesses().isEmpty();n++)Thread.sleep(100);
        if(!ownedProcesses().isEmpty())throw new IOException("Previous runtime processes have not stopped. Restart LSB before changing the environment.");
    }
    synchronized String install(SafeZip.Progress progress)throws Exception {
        if(alive())throw new IOException("Stop the runtime first");reapOrphans();
        if(!Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"))throw new IOException("This runtime requires an ARM64 Android device");
        if(installed()){status="Runtime already installed. Start the Windows checks.";return status;}
        if(home.getUsableSpace()<3L*1024*1024*1024)throw new IOException("Keep at least 3 GiB free for the runtime and fresh Windows prefix");
        File stage=new File(home,"install"),archive=new File(home,"runtime.tar.gz"),previous=new File(home,"root.previous");
        if(!root.exists()&&previous.exists()&&!previous.renameTo(root))throw new IOException("Could not recover the runtime");
        TarExtractor.remove(stage);stage.mkdirs();
        try {
            if(!archive.isFile()||archive.length()!=ARCHIVE_BYTES||!sha(archive).equals(RUNTIME_SHA))download(archive,progress);
            progress.update("Verifying runtime archive");if(!sha(archive).equals(RUNTIME_SHA))throw new IOException("Runtime checksum mismatch; imported client files were not changed");
            TarExtractor.extract(archive,stage,n->{if(Thread.currentThread().isInterrupted())throw new RuntimeException("Runtime operation cancelled");progress.update("Unpacking runtime · "+n+" files");});
            JSONObject m=new JSONObject(read(new File(stage,"etc/trasc-client-runtime.json"),16384));
            if(m.getInt("format")!=1||!m.getString("architecture").equals("arm64")||!m.getString("runtime").equals("client-1.0"))throw new IOException("Runtime identity mismatch");
            for(String name:new String[]{"usr/bin/python3.11","usr/bin/Xtigervnc","usr/local/bin/box64","opt/wine/bin/wine","opt/wine/bin/wineserver"})if(!new File(stage,name).isFile())throw new IOException("Incomplete runtime: "+name);
            for(String path:new String[]{"opt/lsb","probe","prefix","session","logs","tmp"})new File(stage,path).mkdirs();
            write(new File(stage,"lsb-runtime.sha256"),RUNTIME_SHA);interrupted();
            TarExtractor.remove(previous);
            if(root.exists()&&!root.renameTo(previous))throw new IOException("Could not preserve previous runtime");
            if(!stage.renameTo(root)){if(previous.exists())previous.renameTo(root);throw new IOException("Could not activate runtime");}
            status="Runtime installed. Start the Windows checks.";return status;
        }finally{TarExtractor.remove(stage);archive.delete();}
    }
    private void download(File target,SafeZip.Progress progress)throws Exception {
        java.net.URL address=new java.net.URL(URL);
        for(int redirects=0;redirects<8;redirects++){
            if(!address.getProtocol().equals("https"))throw new IOException("Runtime download requires HTTPS");
            HttpURLConnection c=(HttpURLConnection)address.openConnection();c.setInstanceFollowRedirects(false);c.setConnectTimeout(20000);c.setReadTimeout(30000);c.setRequestProperty("User-Agent","LSB-Android/0.2.0");
            try{
                int code=c.getResponseCode();
                if(code>=300&&code<400){String location=c.getHeaderField("Location");if(location==null)throw new IOException("Invalid download redirect");address=new java.net.URL(address,location);continue;}
                if(code!=200)throw new IOException("Runtime download HTTP "+code+". Retry when connected; imported files are unchanged.");
                long count=0,next=0;byte[] b=new byte[1024*1024];
                try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(target)){
                    int n;while((n=in.read(b))!=-1){interrupted();count+=n;if(count>ARCHIVE_BYTES)throw new IOException("Runtime download exceeds pinned size");out.write(b,0,n);if(count>=next){progress.update("Downloading runtime · "+(count/1048576)+" / "+(ARCHIVE_BYTES/1048576)+" MiB");next=count+4*1048576;}}
                }
                if(count!=ARCHIVE_BYTES)throw new IOException("Incomplete runtime download");return;
            }finally{c.disconnect();}
        }throw new IOException("Too many runtime download redirects");
    }
    synchronized String freshPrefix()throws Exception {
        if(alive())throw new IOException("Stop the runtime first");reapOrphans();
        if(prefix.exists()){
            File saved=new File(home,"prefix-backups/prefix-"+System.currentTimeMillis());saved.getParentFile().mkdirs();
            if(!prefix.renameTo(saved))throw new IOException("Could not preserve the previous Windows prefix");
        }
        prefix.mkdirs();new File(run,"status.json").delete();status="Previous prefix preserved. Start the checks to initialize a fresh one.";return status;
    }
    private void assets()throws Exception {
        backend.mkdirs();probes.mkdirs();
        for(String name:context.getAssets().list("runtime")){
            File dest=new File(name.equals("runtime-probe.exe")||name.equals("probe-com.dll")?probes:backend,name);
            try(InputStream in=context.getAssets().open("runtime/"+name);OutputStream out=new FileOutputStream(dest)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
            if(name.equals("vulkan-probe")||name.equals("wineserver"))Os.chmod(dest.getPath(),0700);
        }
    }
    void run(String renderer,boolean sound)throws Exception {
        synchronized(this){if(alive())throw new IOException("Runtime is already running");starting=true;stopRequested=false;}
        try{
            if(!installed()||!read(new File(root,"lsb-runtime.sha256"),128).equals(RUNTIME_SHA))throw new IOException("Install the pinned runtime first");
            if(!Arrays.asList("turnip26","turnip24","software").contains(renderer))throw new IOException("Unsupported renderer");
            reapOrphans();TarExtractor.remove(run);TarExtractor.remove(tmp);run.mkdirs();tmp.mkdirs();prefix.mkdirs();assets();
            File[] old=logs.listFiles();if(old!=null)for(File f:old)if(f.isFile()&&!f.getName().endsWith(".previous"))LogRetention.rotate(f);
            sessionId=UUID.randomUUID().toString();JSONObject request=new JSONObject().put("format",1).put("session_id",sessionId).put("renderer",renderer).put("audio",sound);
            write(new File(run,"request.json"),request.toString());
            if(sound)audio=AudioBridge.start(context,new File(run,"audio.sock"),new File(logs,"audio.log"));
            File nativeDir=new File(context.getApplicationInfo().nativeLibraryDir);
            List<String> command=new ArrayList<>(Arrays.asList(new File(nativeDir,"libproot.so").getPath(),"--link2symlink","--kill-on-exit","-0","-r",root.getPath(),
                "-b","/dev","-b","/proc","-b","/sys","-b",prefix.getPath()+":/prefix","-b",run.getPath()+":/session","-b",logs.getPath()+":/logs",
                "-b",backend.getPath()+":/opt/lsb","-b",probes.getPath()+":/probe","-b",tmp.getPath()+":/tmp",
                "-b",new File(backend,"wineserver").getPath()+":/opt/wine/bin/wineserver","-w","/probe",
                "/usr/bin/env","-i","HOME=/root","USER=root","PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin","LANG=C.UTF-8","TMPDIR=/tmp","PYTHONUNBUFFERED=1",
                "LSB_RUNTIME_OWNER="+home.getPath(),"/usr/bin/python3","/opt/lsb/supervisor.py"));
            ProcessBuilder pb=new ProcessBuilder(command);pb.environment().put("PROOT_LOADER",new File(nativeDir,"libproot-loader.so").getPath());
            pb.environment().put("PROOT_TMP_DIR",tmp.getPath());pb.environment().put("PROOT_NO_SECCOMP","1");pb.environment().put("LSB_RUNTIME_OWNER",home.getPath());
            pb.redirectErrorStream(true);pb.redirectOutput(new File(logs,"proot.log"));
            process=pb.start();starting=false;
            if(stopRequested)write(new File(run,"stop"),"stop\n");status="Starting Windows checks. Open the display to follow progress.";
            while(!process.waitFor(1,TimeUnit.SECONDS)){
                if(new File(run,"status.json").isFile())try{JSONObject s=new JSONObject(read(new File(run,"status.json"),131072));status=s.optString("error",s.optString("phase",status)).replace('_',' ');}catch(Exception ignored){}
            }
            JSONObject finalState=state().optJSONObject("launch");
            if(finalState!=null&&finalState.optString("phase").equals("completed"))status="Automatic checks passed. Confirm picture, sound and input, then relaunch.";
            else if(finalState!=null&&finalState.has("error"))status=finalState.getString("error");
            else status="Runtime stopped (exit "+process.exitValue()+"). Export Diagnostics if unexpected.";
        }finally{
            starting=false;
            if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(5,TimeUnit.SECONDS)){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}}
            try{reapOrphans();}finally{if(audio!=null){audio.close();audio=null;}process=null;}
        }
    }
    void requestStop()throws Exception {
        stopRequested=true;
        write(new File(run,"stop"),"stop\n");status="Stopping Windows checks…";
        Process active=process;if(active!=null&&!active.waitFor(30,TimeUnit.SECONDS)){active.destroy();if(!active.waitFor(5,TimeUnit.SECONDS))active.destroyForcibly();}
    }
    void exportLogs(ZipOutputStream zip)throws Exception {
        SafeZip.entry(zip,"runtime/state.json",state().toString(2));
        File[] files=logs.listFiles();if(files==null)return;
        Arrays.sort(files,Comparator.comparing(File::getName));
        for(File file:files){
            if(!file.isFile()||Files.isSymbolicLink(file.toPath()))continue;
            try(RandomAccessFile in=new RandomAccessFile(file,"r")){
                int size=(int)Math.min(in.length(),1024*1024);in.seek(Math.max(0,in.length()-size));byte[] bytes=new byte[size];in.readFully(bytes);SafeZip.entry(zip,"runtime/"+file.getName(),bytes);
            }
        }
    }
}
