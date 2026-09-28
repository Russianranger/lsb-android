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
    interface ProcessStarter { Process start(ProcessBuilder builder)throws IOException; }
    private final ProcessStarter processStarter;
    final File home,root,state,run,logs,backend,tmp;
    private volatile Process process;
    private volatile boolean active;
    private Operation operation;
    private long logEpoch;
    private boolean logsReady=true;
    static final class LogSnapshot {
        final long epoch;final boolean running;final String text,latest;
        LogSnapshot(long epoch,boolean running,String text,String latest){this.epoch=epoch;this.running=running;this.text=text;this.latest=latest;}
    }
    volatile String status="Import your existing server and SQL backup to begin.";
    private static final String BASE="https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/ubuntu-base-26.04.1-base-arm64.tar.gz";
    private static final String BASE_SHA="5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd";
    private ServerRuntime(Context c){this(c,ProcessBuilder::start);}
    ServerRuntime(Context c,ProcessStarter starter){context=c;processStarter=starter;home=new File(c.getFilesDir(),"server-runtime");root=new File(home,"rootfs");state=new File(home,"state");run=new File(home,"run");logs=new File(home,"logs");backend=new File(home,"backend");tmp=new File(home,"tmp");}
    boolean alive(){Process child=process;return active||(child!=null&&child.isAlive());}
    boolean ready(){try{Process child=process;return child!=null&&child.isAlive()&&!new File(run,"stop").exists()&&"running".equals(new JSONObject(FilesEx.read(new File(run,"status.json"),65536)).optString("phase"));}catch(Exception e){return false;}}
    String startupLog(){try{
        JSONObject startup=new JSONObject(FilesEx.read(new File(run,"status.json"),65536)).optJSONObject("startup");
        JSONArray lines=startup==null?null:startup.optJSONArray("recent_lines");StringBuilder out=new StringBuilder();
        if(lines!=null)for(int i=0;i<lines.length();i++)out.append(lines.getString(i)).append('\n');
        return out.toString().trim();
    }catch(Exception e){return "";}}
    boolean installed(){return new File(root,"lsb-server-ready").isFile();}
    boolean toolsCurrent(){return new File(root,"lsb-server-tools-v4").isFile();}
    boolean hasDatabaseImport(){return new File(state,"import.sql").isFile();}
    JSONObject deployment()throws Exception {
        File pointer=new File(state,"active.json");if(!pointer.isFile())return new JSONObject();
        JSONObject selected=new JSONObject(FilesEx.read(pointer,4096));String id=selected.getString("current");
        if(!id.matches("[a-f0-9-]{36}"))throw new IOException("Invalid server generation");
        return new JSONObject(FilesEx.read(new File(state,"generations/"+id+"/deployment.json"),32768));
    }
    private static JSONObject receipt(File file)throws Exception {
        if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return new JSONObject();
        if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid server receipt: "+file.getName());
        try{return new JSONObject(FilesEx.read(file,1048576));}
        catch(FileNotFoundException error){if(!Files.exists(file.toPath(),LinkOption.NOFOLLOW_LINKS))return new JSONObject();throw error;}
    }
    /** Small persisted receipts; source/binary/SQL hashing runs in the server worker. */
    JSONObject buildState()throws Exception {
        File sql=new File(state,"import.sql");JSONObject database=new JSONObject().put("available",sql.isFile()).put("bytes",sql.isFile()?sql.length():0).put("label","Imported SQL backup");
        JSONObject imported=receipt(new File(state,"import-info.json"));
        if(sql.isFile()&&imported.optLong("bytes",-1)==sql.length()&&imported.optLong("modified_millis",-1)==sql.lastModified()){
            database.put("sha256",imported.optString("sha256")).put("imported_at_millis",imported.optLong("imported_at_millis"));
        }
        JSONObject deployed=deployment(),staged=receipt(new File(state,"staged.json"));
        if(!deployed.optString("generation").isEmpty()&&deployed.optString("generation").equals(staged.optString("generation")))staged.put("state","deployed");
        return new JSONObject().put("selected_source",SourceImport.identity(new File(MainActivity.storage(context),"server")))
                .put("build",receipt(new File(state,"source-build/server/android-build.json")))
                .put("staged",staged).put("deployed",deployed).put("database_import",database);
    }
    private static void selectedId(String value,String label)throws IOException {
        if(value==null||!value.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))throw new IOException("Select a completed "+label+" first");
    }
    String performBuild(String action,String buildId,String generation,String databaseMode,SafeZip.Progress progress)throws Exception {
        if(!Arrays.asList("build-source","adopt-build","stage-build","check-staged","deploy-staged").contains(action))throw new IOException("Unknown build action");
        JSONObject selection=new JSONObject();
        if(Arrays.asList("stage-build","check-staged","deploy-staged").contains(action)){
            selectedId(buildId,"build");selection.put("build_id",buildId);
        }
        if(action.equals("check-staged")||action.equals("deploy-staged")){
            selectedId(generation,"staged deployment");selection.put("generation",generation);
        }
        if(action.equals("stage-build")){
            if(!Arrays.asList("fresh","import","copy-current").contains(databaseMode))throw new IOException("Choose how to prepare the staged database");
            selection.put("database_mode",databaseMode);
        }
        try(Operation reserved=beginOperation()){return performReserved(action,action.equals("build-source"),progress,null,selection);}
    }
    String repairProfile(String generation,SafeZip.Progress progress)throws Exception {
        selectedId(generation,"server deployment");
        if(ClientRuntime.get(context).alive())throw new IOException("Stop the client before repairing the profile service");
        try(Operation reserved=beginOperation()){
            return performReserved("repair-profile",false,progress,null,new JSONObject().put("generation",generation));
        }
    }
    private void idle()throws IOException {if(alive())throw new IOException("Stop the managed server before changing its deployment");}
    private final class Operation implements AutoCloseable {
        @Override public void close(){synchronized(ServerRuntime.this){if(operation==this){operation=null;active=false;}}}
    }
    private synchronized Operation beginOperation()throws IOException {
        idle();Operation reserved=new Operation();operation=reserved;active=true;logEpoch++;logsReady=false;return reserved;
    }
    synchronized long logEpoch(){return logEpoch;}
    synchronized boolean hasLogOperation(){return active;}
    /** Called off the UI thread. Never return raw output if redaction fails. */
    LogSnapshot liveLog(){
        final long epoch;final boolean running,ready;
        synchronized(this){epoch=logEpoch;running=active;ready=logsReady;}
        if(!ready)return new LogSnapshot(epoch,running,running?"Waiting for server output…":"No output from the latest server operation.","");
        String text,latest;
        try{
            StringBuilder all=new StringBuilder();String operationText="",supervisorText="";
            for(String name:new String[]{"dependencies.log","database.log","xi_connect.log","xi_map.log","xi_world.log","xi_search.log","xi_profile.log","supervisor.log","operation.log"}){
                String tail=ServerLogTail.read(new File(logs,name));
                if(name.equals("operation.log"))operationText=tail;
                if(name.equals("supervisor.log"))supervisorText=tail;
                if(!tail.isEmpty())all.append(name).append('\n').append(tail).append('\n');
            }
            // Redact before selecting or shortening any displayed line.
            text=redactCredentials(all.toString());
            latest=ServerLogTail.lastLine(redactCredentials(operationText.isEmpty()?supervisorText:operationText));
            if(text.isEmpty())text=running?"Waiting for server output…":"No server output yet.";
        }catch(Exception error){text="Server output is temporarily unavailable; retrying safely…";latest="";}
        synchronized(this){
            if(epoch!=logEpoch)return new LogSnapshot(logEpoch,active,"Waiting for server output…","");
            return new LogSnapshot(epoch,active,text,latest);
        }
    }
    // Hold the same reservation as deploy/start for the entire filesystem snapshot.
    AutoCloseable reserveSession(boolean export,SafeZip.Progress progress)throws Exception {
        Operation reserved=beginOperation();
        try {
            if(export&&new File(state,"active.json").isFile()) {
                progress.update("Saving the database and waiting for a clean shutdown…");
                performReserved("backup",false,progress,null);
            }
            return reserved;
        } catch(Exception error){reserved.close();throw error;}
    }
    static synchronized void resetAfterRestore(){instance=null;}
    private void assets()throws Exception {
        for(File f:new File[]{home,state,run,logs,backend,tmp})FilesEx.mkdir(f);
        for(String name:context.getAssets().list("server"))try(InputStream in=context.getAssets().open("server/"+name);OutputStream out=new FileOutputStream(new File(backend,name))){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)out.write(b,0,n);}
    }
    String importDatabase(InputStream input,SafeZip.Progress progress)throws Exception {
        try(Operation reserved=beginOperation()){return importDatabaseReserved(input,progress);}
    }
    private String importDatabaseReserved(InputStream input,SafeZip.Progress progress)throws Exception {
        assets();File temp=new File(state,"import.sql.new");long count=0;MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try{
            PushbackInputStream peek=new PushbackInputStream(new BufferedInputStream(input),2);byte[] magic=new byte[2];int n=peek.read(magic);if(n>0)peek.unread(magic,0,n);
            if(n==2&&magic[0]=='P'&&magic[1]=='K')throw new IOException("Select the SQL dump or SQL.gz inside the ZIP");
            InputStream in=n==2&&(magic[0]&255)==31&&(magic[1]&255)==139?new GZIPInputStream(peek):peek;
            try(OutputStream out=new FileOutputStream(temp)){
                byte[] b=new byte[1024*1024];long next=0;
                while((n=in.read(b))!=-1){SafeZip.checkCancelled();count+=n;if(count>16L*1073741824||home.getUsableSpace()<256L*1048576)throw new IOException("Not enough space for the database import");out.write(b,0,n);digest.update(b,0,n);if(count>next){progress.update("Importing database: "+count/1048576+" MiB");next=count+32L*1048576;}}
            }
            if(count<16)throw new IOException("The selected database dump is empty");
            File imported=new File(state,"import.sql");
            Files.move(temp.toPath(),imported.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            StringBuilder sha=new StringBuilder();for(byte b:digest.digest())sha.append(String.format(Locale.ROOT,"%02x",b&255));
            File info=new File(state,"import-info.json.new");
            FilesEx.text(info,new JSONObject().put("bytes",count).put("sha256",sha.toString()).put("modified_millis",imported.lastModified()).put("imported_at_millis",System.currentTimeMillis()).toString());
            Files.move(info.toPath(),new File(state,"import-info.json").toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
            return "SQL backup imported ("+count/1048576+" MiB). The active database is unchanged until deployment.";
        }finally{temp.delete();}
    }
    String install(SafeZip.Progress progress)throws Exception {
        try(Operation reserved=beginOperation()){return installReserved(progress);}
    }
    private String installReserved(SafeZip.Progress progress)throws Exception {
        assets();if(installed()&&toolsCurrent())return "Server runtime is installed.";
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
        new File(run,"status.json").delete();status="Installing server dependencies…";
        progress.update(installed()?"Updating server dependencies and build tools; imported files and databases are retained…":"Installing MariaDB and the server compiler. This download can take several minutes…");
        execute(Arrays.asList("/bin/bash","/opt/lsb-server/bootstrap.sh"),progress);
        if(!installed()||!toolsCurrent())throw new IOException("Server setup did not finish; retry Install or update server tools to resume");
        return "Ubuntu server runtime, MariaDB and build tools installed. Your Termux server is unchanged.";
    }
    // Ubuntu Base leaves /etc/hosts empty. Bind private loopback names for every
    // operation, including existing installs, without changing Android host files.
    private List<String> command(List<String> guest)throws Exception {
        File nativeDir=new File(context.getApplicationInfo().nativeLibraryDir);
        for(String name:new String[]{"state","server-run","server-logs","input","opt/lsb-server","tmp","dev","proc","sys"})new File(root,name).mkdirs();
        File source=new File(MainActivity.storage(context),"server/current");source.mkdirs();
        List<String> cmd=new ArrayList<>(Arrays.asList(new File(nativeDir,"libproot.so").getPath(),"--link2symlink","--kill-on-exit","-0","-r",root.getPath(),"-b","/dev","-b","/proc","-b","/sys","-b",new File(backend,"hosts").getPath()+":/etc/hosts","-b",state.getPath()+":/state","-b",run.getPath()+":/server-run","-b",logs.getPath()+":/server-logs","-b",source.getPath()+":/input","-b",backend.getPath()+":/opt/lsb-server","-b",tmp.getPath()+":/tmp","-w","/state","/usr/bin/env","-i","HOME=/root","USER=root","PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin","LANG=C.UTF-8","DEBIAN_FRONTEND=noninteractive","TMPDIR=/tmp","PYTHONUNBUFFERED=1","LSB_SERVER_OWNER="+home.getPath()));cmd.addAll(guest);return cmd;
    }
    private void execute(List<String> guest,SafeZip.Progress progress)throws Exception {
        execute(guest,progress,null);
    }
    private void execute(List<String> guest,SafeZip.Progress progress,ServerAccountRequest account)throws Exception {
        try{
            // Start a clean view for every child, including dependency installation.
            // Keep the latest build evidence available after inspect/start/backup.
            for(File file:Optional.ofNullable(logs.listFiles()).orElse(new File[0]))if(file.isFile()&&!file.getName().endsWith(".previous")&&!file.getName().equals("build-report.json"))LogRetention.rotate(file);
            synchronized(this){logsReady=true;}
            new File(run,"stop").delete();File nativeDir=new File(context.getApplicationInfo().nativeLibraryDir);
            ProcessBuilder pb=new ProcessBuilder(command(guest));pb.environment().put("PROOT_LOADER",new File(nativeDir,"libproot-loader.so").getPath());pb.environment().put("PROOT_TMP_DIR",tmp.getPath());pb.environment().put("PROOT_NO_SECCOMP","1");pb.environment().put("LSB_SERVER_OWNER",home.getPath());pb.redirectErrorStream(true);pb.redirectOutput(new File(logs,"supervisor.log"));
            process=processStarter.start(pb);
            try(OutputStream input=process.getOutputStream()){if(account!=null)account.send(input);}
            while(!process.waitFor(1,TimeUnit.SECONDS)){
                File file=new File(run,"status.json");if(file.isFile())try{status=new JSONObject(FilesEx.read(file,65536)).optString("message",status);progress.update(status);}catch(Exception ignored){}
            }
            File file=new File(run,"status.json");if(file.isFile())status=new JSONObject(FilesEx.read(file,65536)).optString("message",status);
            if(process.exitValue()!=0)throw new IOException(status+" (see Server operation log)");
        }catch(InterruptedException e){
            FilesEx.text(new File(run,"stop"),"stop\n");Thread.interrupted();
            try{if(process!=null)process.waitFor(35,TimeUnit.SECONDS);}catch(InterruptedException again){Thread.currentThread().interrupt();}
            throw new InterruptedIOException("Server operation interrupted; check status before retrying");
        }finally{
            Process child=process;
            try{if(child!=null&&child.isAlive()){
                child.destroy();
                try{if(!child.waitFor(10,TimeUnit.SECONDS))child.destroyForcibly();}
                catch(InterruptedException e){child.destroyForcibly();Thread.currentThread().interrupt();}
            }}finally{
                // Keep a still-live child visible to idle() until it actually exits.
                if(child==null||!child.isAlive())process=null;
            }
        }
    }
    String perform(String action,boolean build,SafeZip.Progress progress)throws Exception {
        try(Operation reserved=beginOperation()){return performReserved(action,build,progress,null);}
    }
    String createAccount(ServerAccountRequest account,SafeZip.Progress progress)throws Exception {
        if(account==null)throw new IllegalArgumentException("Enter account details first");
        try(Operation reserved=beginOperation()){
            if(!toolsCurrent())throw new IOException("Install or update server tools before creating an account");
            return performReserved("create-account",false,progress,account);
        }finally{account.close();}
    }
    private String performReserved(String action,boolean build,SafeZip.Progress progress,ServerAccountRequest account)throws Exception {
        return performReserved(action,build,progress,account,new JSONObject());
    }
    private String performReserved(String action,boolean build,SafeZip.Progress progress,ServerAccountRequest account,JSONObject selection)throws Exception {
        assets();if(!installed())throw new IOException("Install the server runtime first");
        if(Arrays.asList("deploy","update","build-source","adopt-build","stage-build","check-staged","deploy-staged","repair-profile").contains(action)&&!toolsCurrent())throw new IOException("Update server runtime and build tools before deploying or rebuilding the server");
        int jobs=context.getSharedPreferences("server",0).getInt("jobs",2);
        if(jobs<1||jobs>16)throw new IOException("Choose 1 to 16 build workers");
        JSONObject request=new JSONObject().put("action",action).put("build",build).put("jobs",jobs).put("database",context.getSharedPreferences("server",0).getString("database","xidb")).put("local_zones",context.getSharedPreferences("server",0).getBoolean("local_zones",true));
        if(Arrays.asList("build-source","deploy","update").contains(action))request.put("source_identity",SourceImport.identity(new File(MainActivity.storage(context),"server")));
        for(Iterator<String> keys=selection.keys();keys.hasNext();){String key=keys.next();request.put(key,selection.get(key));}
        if(Arrays.asList("deploy","update","stage-build","deploy-staged").contains(action))try{request.put("client_pair",ClientRuntime.get(context).compatibilitySnapshot());}catch(Exception e){request.put("client_pair",new JSONObject().put("status","client_not_prepared"));}
        FilesEx.text(new File(run,"request.json"),request.toString());new File(run,"status.json").delete();
        execute(Arrays.asList("/usr/bin/python3","/opt/lsb-server/manager.py"),progress,account);return status;
    }
    void stop()throws IOException{FilesEx.mkdir(run);FilesEx.text(new File(run,"stop"),"stop\n");status="Stopping managed server…";}
    void exportDatabase(OutputStream out,SafeZip.Progress progress)throws Exception {
        try(Operation reserved=beginOperation()){
        performReserved("backup",false,progress,null);
        try(InputStream in=new FileInputStream(new File(state,"export.sql"));GZIPOutputStream gzip=new GZIPOutputStream(out)){byte[] b=new byte[1024*1024];int n;while((n=in.read(b))!=-1){SafeZip.checkCancelled();gzip.write(b,0,n);}}
        new File(state,"export.sql").delete();
        }
    }
    String operationLog()throws Exception {
        StringBuilder text=new StringBuilder();for(String name:new String[]{"build-report.json","profile-repair-report.json","operation.log","dependencies.log","database.log","xi_connect.log","xi_map.log","xi_world.log","xi_search.log","xi_profile.log","supervisor.log"}){
            File file=new File(logs,name);if(!file.isFile())continue;
            try(RandomAccessFile f=new RandomAccessFile(file,"r")){int size=(int)Math.min(12000,f.length());f.seek(f.length()-size);byte[] b=new byte[size];f.readFully(b);text.append(name).append("\n").append(new String(b,java.nio.charset.StandardCharsets.UTF_8)).append("\n");}
        }
        return redactCredentials(text.toString());
    }
    private String redactCredentials(String result)throws Exception {
        // Credentials may appear in upstream command failures. Redact every
        // generated credential, including a failed candidate's credentials.
        File directory=new File(state,"generations");File[] generations=directory.listFiles();
        if(directory.exists()&&generations==null)throw new IOException("Cannot check server credentials");
        if(generations!=null)for(File dir:generations){File secrets=new File(dir,"database-credentials.json");if(secrets.isFile()){JSONObject values=new JSONObject(FilesEx.read(secrets,4096));Iterator<String> keys=values.keys();while(keys.hasNext()){String value=values.getString(keys.next());if(!value.isEmpty())result=result.replace(value,"[redacted]");}}}
        return result;
    }
    void exportLogs(ZipOutputStream zip)throws Exception {
        SafeZip.entry(zip,"server/deployment.json",deployment().toString(2));
        File s=new File(run,"status.json");if(s.isFile())SafeZip.entry(zip,"server/status.json",FilesEx.read(s,65536));
        File build=new File(logs,"build-report.json");if(build.isFile())SafeZip.entry(zip,"server/build-report.json",redactCredentials(FilesEx.read(build,1048576)));
        File profile=new File(logs,"profile-repair-report.json");if(profile.isFile())SafeZip.entry(zip,"server/profile-repair-report.json",redactCredentials(FilesEx.read(profile,1048576)));
        SafeZip.entry(zip,"server/operation.log",operationLog());
        SafeZip.entry(zip,"server/build-state.json",redactCredentials(buildState().toString(2)));
    }
}
