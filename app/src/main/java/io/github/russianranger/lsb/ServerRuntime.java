package io.github.russianranger.lsb;

import android.content.*;
import android.system.Os;
import io.github.russianranger.lsb.core.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/** Dedicated Linux root and database storage, separate from the accepted client. */
final class ServerRuntime {
    private static ServerRuntime instance;
    static synchronized ServerRuntime get(Context c){if(instance==null)instance=new ServerRuntime(c.getApplicationContext());return instance;}
    private final Context context;
    final File home,root,state,run,logs,backend,tmp;
    private volatile Process process;
    private volatile boolean active;
    volatile String status="Import your existing server and SQL backup to begin.";
    private static final String BASE="https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz";
    private static final String BASE_SHA="5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd";
    private ServerRuntime(Context c){context=c;home=new File(c.getFilesDir(),"server-runtime");root=new File(home,"rootfs");state=new File(home,"state");run=new File(home,"run");logs=new File(home,"logs");backend=new File(home,"backend");tmp=new File(home,"tmp");}
    boolean alive(){return active;}
    boolean installed(){return new File(root,"lsb-server-ready").isFile();}
    boolean hasDatabaseImport(){return new File(state,"import.sql").isFile();}
    JSONObject deployment()throws Exception {
        File pointer=new File(state,"active.json");if(!pointer.isFile())return new JSONObject();
        JSONObject selected=new JSONObject(FilesEx.read(pointer,4096));String id=selected.getString("current");
        if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Invalid server generation");
        return new JSONObject(FilesEx.read(new File(state,"generations/"+id+"/deployment.json"),32768));
    }
    private void idle()throws IOException {if(active)throw new IOException("Stop the managed server before changing its deployment");}
    private void assets()throws Exception {
        for(File f:new File[]{home,state,run,logs,backend,tmp})FilesEx.mkdir(f);
        for(String name:context.getAssets().list("server"))try(InputStream in=context.getAssets().open("server/"+name);OutputStream out=new FileOutputStream(new File(backend,name))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    }
    String importDatabase(InputStream input,SafeZip.Progress progress)throws Exception {
        idle();assets();File temp=new File(state,"import.sql.new");long count=0;
        try{
            PushbackInputStream peek=new PushbackInputStream(new BufferedInputStream(input),2);byte[] magic=new byte[2];int n=peek.read(magic);if(n>0)peek.unread(magic,0,n);
            if(n==2&&magic[0]=='P'&&magic[1]=='K')throw new IOException("Select the SQL dump or SQL.gz inside the ZIP");
            InputStream in=n==2&&(magic[0]&255)==31&&(magic[1]&255)==139?new GZIPInputStream(peek):peek;
            try(OutputStream out=new FileOutputStream(temp)){
                byte[] b=new byte[1024*1024];long next=0;
                while((n=in.read(b))!=-1){SafeZip.checkCancelled();count+=n;if(count>16L*1073741824||home.getUsableSpace()<256L*1048576)throw new IOException("Not enough space for the database import");out.write(b,0,n);if(count>next){progress.update("Importing database: "+count/1048576+" MiB");next=count+32L*1048576;}}
            }
            if(count<16)throw new IOException("The selected database dump is empty");
            Files.move(temp.toPath(),new File(state,"import.sql").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            return "SQL backup imported ("+count/1048576+" MiB). The active database is unchanged until deployment.";
        }finally{temp.delete();}
    }
    String install(SafeZip.Progress progress)throws Exception {
        idle();assets();if(installed())return "Server runtime is installed.";
        File archive=new File(home,"ubuntu-base.tar.gz"),incoming=new File(home,"rootfs.incoming");
        if(!new File(root,"usr/bin/bash").isFile()){
            progress.update("Downloading Ubuntu ARM64 base (33 MiB)…");
            HttpURLConnection c=(HttpURLConnection)new URL(BASE).openConnection();c.setConnectTimeout(20000);c.setReadTimeout(30000);
            MessageDigest hash=MessageDigest.getInstance("SHA-256");long count=0;
            try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(archive)){
                byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){SafeZip.checkCancelled();if((count+=n)>100L*1048576)throw new IOException("Unexpected server base size");hash.update(b,0,n);out.write(b,0,n);}
            }finally{c.disconnect();}
            StringBuilder actual=new StringBuilder();for(byte b:hash.digest())actual.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!BASE_SHA.equals(actual.toString()))throw new IOException("Server base checksum mismatch");
            TarExtractor.remove(incoming);FilesEx.mkdir(incoming);TarExtractor.extract(archive,incoming,n->progress.update("Extracting server base: "+n+" entries"));
            if(!new File(incoming,"usr/bin/bash").isFile())throw new IOException("Server base does not contain bash");
            TarExtractor.remove(root);FilesEx.move(incoming,root);archive.delete();
        }
        Files.deleteIfExists(new File(root,"etc/resolv.conf").toPath());FilesEx.text(new File(root,"etc/resolv.conf"),"nameserver 1.1.1.1\nnameserver 8.8.8.8\n");
        FilesEx.text(new File(root,"usr/sbin/policy-rc.d"),"#!/bin/sh\nexit 101\n");Os.chmod(new File(root,"usr/sbin/policy-rc.d").getPath(),0755);
        progress.update("Installing MariaDB and the server compiler. This download can take several minutes…");
        execute(Arrays.asList("/bin/bash","/opt/lsb-server/bootstrap.sh"),progress);
        if(!installed())throw new IOException("Server setup did not finish; retry Install runtime to resume");
        return "Ubuntu server runtime, MariaDB and build tools installed. Your Termux server is unchanged.";
    }
    private List<String> command(List<String> guest)throws Exception {
        File nativeDir=new File(context.getApplicationInfo().nativeLibraryDir);
        for(String name:new String[]{"state","server-run","server-logs","input","opt/lsb-server","tmp","dev","proc","sys"})new File(root,name).mkdirs();
        File source=new File(MainActivity.storage(context),"server/current");source.mkdirs();
        List<String> cmd=new ArrayList<>(Arrays.asList(new File(nativeDir,"libproot.so").getPath(),"--link2symlink","--kill-on-exit","-0","-r",root.getPath(),"-b","/dev","-b","/proc","-b","/sys","-b",state.getPath()+":/state","-b",run.getPath()+":/server-run","-b",logs.getPath()+":/server-logs","-b",source.getPath()+":/input","-b",backend.getPath()+":/opt/lsb-server","-b",tmp.getPath()+":/tmp","-w","/state","/usr/bin/env","-i","HOME=/root","USER=root","PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin","LANG=C.UTF-8","DEBIAN_FRONTEND=noninteractive","TMPDIR=/tmp","PYTHONUNBUFFERED=1","LSB_SERVER_OWNER="+home.getPath()));cmd.addAll(guest);return cmd;
    }
    private void execute(List<String> guest,SafeZip.Progress progress)throws Exception {
        synchronized(this){idle();active=true;}
        try{
            new File(run,"stop").delete();File nativeDir=new File(context.getApplicationInfo().nativeLibraryDir);
            ProcessBuilder pb=new ProcessBuilder(command(guest));pb.environment().put("PROOT_LOADER",new File(nativeDir,"libproot-loader.so").getPath());pb.environment().put("PROOT_TMP_DIR",tmp.getPath());pb.environment().put("PROOT_NO_SECCOMP","1");pb.environment().put("LSB_SERVER_OWNER",home.getPath());pb.redirectErrorStream(true);pb.redirectOutput(new File(logs,"supervisor.log"));
            process=pb.start();process.getOutputStream().close();
            while(!process.waitFor(1,TimeUnit.SECONDS)){
                File file=new File(run,"status.json");if(file.isFile())try{status=new JSONObject(FilesEx.read(file,65536)).optString("message",status);progress.update(status);}catch(Exception ignored){}
            }
            File file=new File(run,"status.json");if(file.isFile())status=new JSONObject(FilesEx.read(file,65536)).optString("message",status);
            if(process.exitValue()!=0)throw new IOException(status+" (see Server operation log)");
        }catch(InterruptedException e){FilesEx.text(new File(run,"stop"),"stop\n");Thread.interrupted();if(process!=null)process.waitFor(35,TimeUnit.SECONDS);throw new InterruptedIOException("Server operation cancelled; previous deployment retained");}
        finally{if(process!=null&&process.isAlive()){process.destroy();if(!process.waitFor(10,TimeUnit.SECONDS))process.destroyForcibly();}process=null;active=false;}
    }
    String perform(String action,boolean build,SafeZip.Progress progress)throws Exception {
        idle();assets();if(!installed())throw new IOException("Install the server runtime first");
        JSONObject request=new JSONObject().put("action",action).put("build",build).put("jobs",context.getSharedPreferences("server",0).getInt("jobs",2)).put("database",context.getSharedPreferences("server",0).getString("database","xidb")).put("local_zones",context.getSharedPreferences("server",0).getBoolean("local_zones",true));
        if(action.equals("deploy")||action.equals("update"))try{request.put("client_pair",ClientRuntime.get(context).compatibilitySnapshot());}catch(Exception e){request.put("client_pair",new JSONObject().put("status","client_not_prepared"));}
        FilesEx.text(new File(run,"request.json"),request.toString());new File(run,"status.json").delete();
        for(File file:Optional.ofNullable(logs.listFiles()).orElse(new File[0]))if(file.isFile()&&!file.getName().endsWith(".previous"))LogRetention.rotate(file);
        execute(Arrays.asList("/usr/bin/python3","/opt/lsb-server/manager.py"),progress);return status;
    }
    void stop()throws IOException{FilesEx.mkdir(run);FilesEx.text(new File(run,"stop"),"stop\n");status="Stopping managed server…";}
    void exportDatabase(OutputStream out,SafeZip.Progress progress)throws Exception {
        perform("backup",false,progress);
        try(InputStream in=new FileInputStream(new File(state,"export.sql"));GZIPOutputStream gzip=new GZIPOutputStream(out)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){SafeZip.checkCancelled();gzip.write(b,0,n);}}
        new File(state,"export.sql").delete();
    }
    String operationLog()throws Exception {
        StringBuilder text=new StringBuilder();for(String name:new String[]{"operation.log","database.log","xi_connect.log","xi_map.log","xi_world.log","xi_search.log","supervisor.log"}){
            File file=new File(logs,name);if(!file.isFile())continue;
            try(RandomAccessFile f=new RandomAccessFile(file,"r")){int size=(int)Math.min(12000,f.length());f.seek(f.length()-size);byte[] b=new byte[size];f.readFully(b);text.append(name).append("\n").append(new String(b,java.nio.charset.StandardCharsets.UTF_8)).append("\n");}
        }
        // Credentials may appear in upstream command failures. Redact every
        // generated credential, including a failed candidate's credentials.
        File[] generations=new File(state,"generations").listFiles();String result=text.toString();
        if(generations!=null)for(File dir:generations){File secrets=new File(dir,"database-credentials.json");if(secrets.isFile()){JSONObject values=new JSONObject(FilesEx.read(secrets,4096));Iterator<String> keys=values.keys();while(keys.hasNext())result=result.replace(values.getString(keys.next()),"[redacted]");}}
        return result;
    }
    void exportLogs(ZipOutputStream zip)throws Exception {
        SafeZip.entry(zip,"server/deployment.json",deployment().toString(2));
        File s=new File(run,"status.json");if(s.isFile())SafeZip.entry(zip,"server/status.json",FilesEx.read(s,65536));
        SafeZip.entry(zip,"server/operation.log",operationLog());
    }
}
