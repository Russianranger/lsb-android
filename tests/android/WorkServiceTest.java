package io.github.russianranger.lsb;

import android.content.*;
import android.os.Looper;
import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class WorkServiceTest {
    private Context context;
    private ServiceController<WorkService> service;
    private static void set(Class<?> type,Object object,String name,Object value)throws Exception {
        Field field=type.getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    @Before public void prepare()throws Exception {
        context=RuntimeEnvironment.getApplication();
        set(ClientRuntime.class,null,"instance",null);set(ServerRuntime.class,null,"instance",null);
        set(WorkService.class,null,"pending",null);set(WorkService.class,null,"runningThread",null);set(WorkService.class,null,"cancellationRequested",false);
        WorkService.busy=false;
    }
    @After public void cleanup()throws Exception {
        if(service!=null)service.destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        set(WorkService.class,null,"pending",null);WorkService.busy=false;
        set(ClientRuntime.class,null,"instance",null);set(ServerRuntime.class,null,"instance",null);
    }
    private Context submissionContext(boolean reject){
        return new ContextWrapper(context){@Override public ComponentName startForegroundService(Intent intent){
            if(reject)throw new IllegalStateException("foreground start denied");
            return new ComponentName(context,WorkService.class);
        }};
    }
    private void finished()throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(WorkService.busy&&System.nanoTime()<deadline){Shadows.shadowOf(Looper.getMainLooper()).idle();Thread.sleep(5);}
        assertFalse("Worker completion must release the service",WorkService.busy);
    }
    private static class Job implements WorkService.Job {
        final ServerAccountRequest account=new ServerAccountRequest("test account","test password");
        final AtomicInteger runs=new AtomicInteger(),closes=new AtomicInteger();
        final CountDownLatch closed=new CountDownLatch(1);
        boolean fail;
        public String run(Context context,SafeZip.Progress progress)throws Exception {
            runs.incrementAndGet();if(fail)throw new IOException("fixture failure");return "Created account";
        }
        public void close(){account.close();closes.incrementAndGet();closed.countDown();}
        void verifyClosed()throws Exception {
            assertTrue(closed.await(5,TimeUnit.SECONDS));assertEquals(1,closes.get());
            try{account.send(new ByteArrayOutputStream());fail("Credentials must be expired");}catch(IOException expected){}
        }
    }
    @Test public void rejectedSubmissionAndForegroundStartFailureCloseAccount()throws Exception {
        Job busy=new Job();WorkService.busy=true;
        assertFalse(WorkService.submit(submissionContext(false),"Create account",busy));busy.verifyClosed();assertEquals(0,busy.runs.get());
        WorkService.busy=false;ClientRuntime.get(context).starting=true;Job client=new Job();
        assertFalse(WorkService.submit(submissionContext(false),"Create account",client));client.verifyClosed();assertEquals(0,client.runs.get());
        ClientRuntime.get(context).starting=false;Job rejected=new Job();
        assertFalse(WorkService.submit(submissionContext(true),"Create account",rejected));rejected.verifyClosed();assertFalse(WorkService.busy);
    }
    @Test public void cancellationBeforeWorkerInvocationStillClosesAccount()throws Exception {
        Job job=new Job();assertTrue(WorkService.submit(submissionContext(false),"Create account",job));WorkService.cancel();
        service=Robolectric.buildService(WorkService.class).create();service.startCommand(0,1);
        job.verifyClosed();assertEquals(0,job.runs.get());
        finished();assertEquals("Cancelled",WorkService.message);
    }
    @Test public void completedAndFailedWorkersCloseAccount()throws Exception {
        for(boolean fail:new boolean[]{false,true}){
            Job job=new Job();job.fail=fail;assertTrue(WorkService.submit(submissionContext(false),"Create account",job));
            service=Robolectric.buildService(WorkService.class).create();service.startCommand(0,1);
            job.verifyClosed();assertEquals(1,job.runs.get());
            finished();
            assertEquals(fail?"Failed":"Completed",WorkService.message);
            service.destroy();service=null;assertEquals(1,job.closes.get());
        }
    }
    @Test public void stoppedServerAndToolsChecksConsumeRejectedAccount()throws Exception {
        ServerRuntime runtime=ServerRuntime.get(context);Job running=new Job();set(ServerRuntime.class,runtime,"active",true);
        try{runtime.createAccount(running.account,s->{});fail("Running server must reject account changes");}catch(IOException expected){assertTrue(expected.getMessage().contains("Stop"));}
        try{running.account.send(new ByteArrayOutputStream());fail("Rejected details must expire");}catch(IOException expected){}
        set(ServerRuntime.class,runtime,"active",false);new File(runtime.root,"lsb-server-tools-v4").delete();Job oldTools=new Job();
        try{runtime.createAccount(oldTools.account,s->{});fail("Old tools must be updated");}catch(IOException expected){assertTrue(expected.getMessage().contains("update server tools"));}
        try{oldTools.account.send(new ByteArrayOutputStream());fail("Rejected details must expire");}catch(IOException expected){}
    }
    private Context nativeContext(){
        return new ContextWrapper(context){@Override public android.content.pm.ApplicationInfo getApplicationInfo(){
            android.content.pm.ApplicationInfo info=new android.content.pm.ApplicationInfo(super.getApplicationInfo());
            info.nativeLibraryDir=getFilesDir().getPath();return info;
        }};
    }
    private static class Child extends Process {
        final ByteArrayOutputStream input=new ByteArrayOutputStream();
        boolean alive,interruptWait,forced;
        Child(boolean interrupt){alive=interruptWait=interrupt;}
        public OutputStream getOutputStream(){return input;}
        public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        public int waitFor()throws InterruptedException{if(interruptWait)throw new InterruptedException("fixture interruption");return 0;}
        public boolean waitFor(long timeout,TimeUnit unit)throws InterruptedException{waitFor();return !alive;}
        public int exitValue(){if(alive)throw new IllegalThreadStateException();return 0;}
        public boolean isAlive(){return alive;}
        public void destroy(){}
        public Process destroyForcibly(){forced=true;alive=false;return this;}
    }
    @Test public void dependencyUpgradePreservesImportsAndDatabaseAndClearsStaleError()throws Exception {
        File home=new File(context.getFilesDir(),"server-runtime");AtomicInteger bootstraps=new AtomicInteger();
        ServerRuntime runtime=new ServerRuntime(nativeContext(),builder->{
            assertTrue(builder.command().contains("/opt/lsb-server/bootstrap.sh"));
            assertTrue(builder.command().contains(new File(home,"backend/hosts").getPath()+":/etc/hosts"));
            assertFalse("Previous deployment failure must not override bootstrap status",new File(home,"run/status.json").exists());
            assertFalse(builder.environment().containsKey("LD_PRELOAD"));
            FilesEx.text(new File(home,"rootfs/lsb-server-tools-v4"),"BFD compatibility installed");
            bootstraps.incrementAndGet();return new Child(false);
        });
        File source=new File(MainActivity.storage(context),"server/current/source-report.txt");
        try{
            FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");
            FilesEx.text(new File(runtime.root,"lsb-server-tools-v3"),"old tools");
            FilesEx.text(new File(runtime.root,"usr/bin/bash"),"existing rootfs sentinel");
            FilesEx.text(new File(runtime.state,"import.sql"),"staged SQL retained");
            FilesEx.text(new File(runtime.state,"generations/fixture/mysql/sentinel"),"database retained");
            FilesEx.text(source,"matching imported server retained");
            FilesEx.text(new File(runtime.run,"status.json"),"{\"phase\":\"error\",\"message\":\"old missing library error\"}");
            assertTrue(runtime.installed());assertFalse(runtime.toolsCurrent());
            try{runtime.perform("deploy",false,s->{});fail("Old dependencies must be updated first");}
            catch(IOException expected){assertTrue(expected.getMessage().contains("Update server runtime"));}
            assertEquals(0,bootstraps.get());
            assertTrue(runtime.install(s->{}).contains("Ubuntu server runtime"));
            assertEquals(1,bootstraps.get());assertTrue(runtime.toolsCurrent());assertFalse(runtime.alive());
            assertEquals("existing rootfs sentinel",FilesEx.read(new File(runtime.root,"usr/bin/bash"),1024));
            assertEquals("staged SQL retained",FilesEx.read(new File(runtime.state,"import.sql"),1024));
            assertEquals("database retained",FilesEx.read(new File(runtime.state,"generations/fixture/mysql/sentinel"),1024));
            assertEquals("matching imported server retained",FilesEx.read(source,1024));
        }finally{FilesEx.delete(runtime.home);source.delete();}
    }
    @Test public void existingRuntimeBindsPrivateHostsWithoutAnotherDependencyInstall()throws Exception {
        File home=new File(context.getFilesDir(),"server-runtime");AtomicInteger calls=new AtomicInteger();
        ServerRuntime runtime=new ServerRuntime(nativeContext(),builder->{
            java.util.List<String> command=builder.command();
            int binding=command.indexOf(new File(home,"backend/hosts").getPath()+":/etc/hosts");
            assertTrue(binding>0);assertEquals("-b",command.get(binding-1));
            assertTrue(command.contains("/opt/lsb-server/manager.py"));
            assertFalse(command.contains("/opt/lsb-server/bootstrap.sh"));
            String hosts=FilesEx.read(new File(home,"backend/hosts"),4096);
            assertTrue(hosts.contains("127.0.0.1 localhost localhost.localdomain"));
            assertTrue(hosts.contains("::1 localhost ip6-localhost ip6-loopback"));
            calls.incrementAndGet();return new Child(false);
        });
        try{
            FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");
            FilesEx.text(new File(runtime.root,"lsb-server-tools-v4"),"current dependencies");
            FilesEx.text(new File(runtime.root,"etc/hosts"),"");
            FilesEx.text(new File(runtime.state,"import.sql"),"staged SQL retained");
            // Config.NONE does not load APK assets; seed the same production asset.
            FilesEx.text(new File(runtime.backend,"hosts"),FilesEx.read(new File("server/hosts"),4096));
            context.getSharedPreferences("server",0).edit().putBoolean("proot_acceleration",false).commit();
            runtime.perform("deploy",false,s->{});runtime.perform("start",false,s->{});
            assertEquals(2,calls.get());assertTrue(runtime.toolsCurrent());
            assertEquals("",FilesEx.read(new File(runtime.root,"etc/hosts"),4096));
            assertEquals("staged SQL retained",FilesEx.read(new File(runtime.state,"import.sql"),4096));
        }finally{FilesEx.delete(runtime.home);context.getSharedPreferences("server",0).edit().remove("proot_acceleration").commit();}
    }
    @Test public void dependencyDetailsReachSupportExport()throws Exception {
        ServerRuntime runtime=new ServerRuntime(context,builder->{throw new AssertionError("No process needed for support export");});
        try{
            FilesEx.text(new File(runtime.logs,"dependencies.log"),"xi_world\nlibjemalloc.so.2 => not found\n");
            FilesEx.text(new File(runtime.logs,"build-report.json"),"{\"allocator\":\"jemalloc\",\"failure\":\"fixture-secret\"}");
            FilesEx.text(new File(runtime.state,"generations/fixture/database-credentials.json"),"{\"password\":\"fixture-secret\"}");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)){runtime.exportLogs(zip);}
            boolean found=false,buildFound=false;
            try(java.util.zip.ZipInputStream zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(bytes.toByteArray()))){
                java.util.zip.ZipEntry entry;while((entry=zip.getNextEntry())!=null){
                    ByteArrayOutputStream content=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int n;
                    while((n=zip.read(buffer))!=-1)content.write(buffer,0,n);
                    String value=content.toString("UTF-8");assertFalse(value.contains("fixture-secret"));
                    if(entry.getName().equals("server/operation.log")){assertTrue(value.contains("libjemalloc.so.2 => not found"));assertTrue(value.contains("build-report.json"));found=true;}
                    if(entry.getName().equals("server/build-report.json")){assertEquals("jemalloc",new org.json.JSONObject(value).getString("allocator"));assertTrue(value.contains("[redacted]"));buildFound=true;}
                }
            }
            assertTrue(found);assertTrue(buildFound);
        }finally{FilesEx.delete(runtime.home);}
    }
    @Test public void sourceBuildRoutesWithoutDatabaseOrClientCompatibilityAndRetainsEvidence()throws Exception {
        File home=new File(context.getFilesDir(),"server-runtime");AtomicInteger calls=new AtomicInteger();
        ServerRuntime runtime=new ServerRuntime(nativeContext(),builder->{
            try{
            assertTrue(builder.command().contains("/opt/lsb-server/manager.py"));
            org.json.JSONObject request=new org.json.JSONObject(FilesEx.read(new File(home,"run/request.json"),4096));
            String action=request.getString("action");
            assertEquals(calls.get()==0?"build-source":"inspect",action);
            assertFalse(request.has("client_pair"));
            assertFalse(new File(home,"state/import.sql").exists());assertFalse(new File(home,"state/active.json").exists());
            if(action.equals("build-source")){
                assertTrue(request.getBoolean("build"));
                FilesEx.text(new File(home,"logs/build-report.json"),"{\"allocator\":\"jemalloc\",\"status\":\"passed\"}");
            }else assertTrue(new File(home,"logs/build-report.json").isFile());
            FilesEx.text(new File(home,"run/status.json"),"{\"message\":\"Source build verified with jemalloc\"}");
            calls.incrementAndGet();return new Child(false);
            }catch(org.json.JSONException error){throw new IOException(error);}
        });
        try{
            FilesEx.delete(runtime.home);FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");
            try{runtime.perform("build-source",true,s->{});fail("Source builds require current compiler dependencies");}
            catch(IOException expected){assertTrue(expected.getMessage().contains("Update server runtime"));}
            assertEquals(0,calls.get());assertFalse(runtime.alive());
            FilesEx.text(new File(runtime.root,"lsb-server-tools-v4"),"current dependencies");
            assertEquals("Source build verified with jemalloc",runtime.perform("build-source",true,s->{}));
            runtime.perform("inspect",false,s->{});
            assertEquals(2,calls.get());assertFalse(runtime.alive());
            assertEquals("jemalloc",new org.json.JSONObject(FilesEx.read(new File(runtime.logs,"build-report.json"),4096)).getString("allocator"));
        }finally{FilesEx.delete(runtime.home);}
    }
    @Test public void concurrentPreparationCannotOverwriteRequestOrReleaseImportReservation()throws Exception {
        ServerRuntime runtime=new ServerRuntime(context,builder->{throw new AssertionError("Rejected operation must not start");});
        FilesEx.text(new File(runtime.run,"request.json"),"existing request");
        CountDownLatch reading=new CountDownLatch(1),release=new CountDownLatch(1);
        InputStream source=new ByteArrayInputStream("CREATE TABLE fixture(id INT);\n".getBytes("US-ASCII")){
            @Override public synchronized int read(byte[] b,int off,int length){
                reading.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Import release timeout");}
                catch(InterruptedException e){throw new AssertionError(e);}return super.read(b,off,length);
            }
        };
        FutureTask<String> task=new FutureTask<>(()->runtime.importDatabase(source,s->{}));Thread thread=new Thread(task,"server-import-fixture");thread.start();
        try{
            assertTrue(reading.await(5,TimeUnit.SECONDS));assertTrue(runtime.alive());
            try{runtime.perform("start",false,s->{});fail("Concurrent start must fail before preparing its request");}
            catch(IOException expected){assertTrue(expected.getMessage().contains("Stop"));}
            ServerAccountRequest account=new ServerAccountRequest("account","password");
            try{runtime.createAccount(account,s->{});fail("Concurrent account change must be rejected");}catch(IOException expected){}
            try{account.send(new ByteArrayOutputStream());fail("Rejected credentials must expire");}catch(IOException expected){}
            assertTrue("Rejected operation must not release the import reservation",runtime.alive());
            assertEquals("existing request",FilesEx.read(new File(runtime.run,"request.json"),1024));
        }finally{release.countDown();thread.join(5000);}
        assertFalse(thread.isAlive());assertTrue(task.get(1,TimeUnit.SECONDS).contains("SQL backup imported"));assertFalse(runtime.alive());
    }
    @Test public void repeatedInterruptionForcesChildCleanupAndReleasesOnlyFinishedOperation()throws Exception {
        Child child=new Child(true);ServerRuntime runtime=new ServerRuntime(nativeContext(),builder->child);
        FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");FilesEx.text(new File(runtime.root,"lsb-server-tools-v4"),"ready");
        ServerAccountRequest account=new ServerAccountRequest("fixture account","private password");
        try{runtime.createAccount(account,s->{});fail("Interrupted manager must fail");}
        catch(InterruptedIOException expected){assertTrue(expected.getMessage().contains("check status before retrying"));}
        finally{Thread.interrupted();}
        assertTrue(child.forced);assertFalse(child.alive);assertFalse(runtime.alive());
        String request=FilesEx.read(new File(runtime.run,"request.json"),4096);
        assertFalse(request.contains("fixture account"));assertFalse(request.contains("private password"));
        assertEquals("LSBACCOUNT1\nfixture account\nprivate password\n",child.input.toString("US-ASCII"));
        try{account.send(new ByteArrayOutputStream());fail("Interrupted account details must expire");}catch(IOException expected){}
        assertEquals("Server runtime is installed.",runtime.install(s->{}));
    }
    @Test public void databaseExportRetainsReservationUntilCompressionAndDeletionFinish()throws Exception {
        File home=new File(context.getFilesDir(),"server-runtime");
        ServerRuntime runtime=new ServerRuntime(nativeContext(),builder->{
            FilesEx.text(new File(home,"state/export.sql"),"CREATE TABLE exported(id INT);\n");return new Child(false);
        });
        FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");
        CountDownLatch writing=new CountDownLatch(1),release=new CountDownLatch(1);
        OutputStream output=new ByteArrayOutputStream(){@Override public synchronized void write(byte[] b,int off,int length){
            writing.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("Export release timeout");}
            catch(InterruptedException e){throw new AssertionError(e);}super.write(b,off,length);
        }};
        FutureTask<Void> task=new FutureTask<>(()->{runtime.exportDatabase(output,s->{});return null;});Thread thread=new Thread(task,"server-export-fixture");thread.start();
        try{
            assertTrue(writing.await(5,TimeUnit.SECONDS));assertTrue(runtime.alive());
            try{runtime.perform("inspect",false,s->{});fail("Export must retain its reservation after the database child exits");}catch(IOException expected){}
            assertEquals("backup",new org.json.JSONObject(FilesEx.read(new File(runtime.run,"request.json"),4096)).getString("action"));
            assertTrue(new File(runtime.state,"export.sql").isFile());assertTrue(runtime.alive());
        }finally{release.countDown();thread.join(5000);}
        assertFalse(thread.isAlive());task.get(1,TimeUnit.SECONDS);assertFalse(runtime.alive());assertFalse(new File(runtime.state,"export.sql").exists());
    }
    @Test public void liveLogTailHandlesGrowthRotationTruncationAndIncompleteLines()throws Exception {
        ServerRuntime runtime=new ServerRuntime(context,builder->{throw new AssertionError("No process needed");});
        File log=new File(runtime.logs,"operation.log");
        try{
            assertEquals("",ServerLogTail.read(log));
            FilesEx.text(log,"[ 10%] first\n");assertEquals("[ 10%] first",runtime.liveLog().latest);
            try(FileWriter out=new FileWriter(log,true)){out.write("\u001b[32m[ 20%] second\u001b[0m\n\nunfinished secret");}
            assertEquals("[ 20%] second",runtime.liveLog().latest);assertFalse(runtime.liveLog().text.contains("unfinished"));
            LogRetention.rotate(log);assertEquals("",runtime.liveLog().latest);
            FilesEx.text(log,"replacement\n");assertEquals("replacement",runtime.liveLog().latest);
            FilesEx.text(log,"short\n");assertEquals("short",runtime.liveLog().latest);
            StringBuilder large=new StringBuilder("old output\n");for(int i=0;i<ServerLogTail.MAX_BYTES*3;i++)large.append('x');
            large.append("truncated-secret-suffix\nlast complete line\n");FilesEx.text(log,large.toString());
            String tail=ServerLogTail.read(log);assertEquals("last complete line\n",tail);assertTrue(tail.length()<=ServerLogTail.MAX_BYTES);
            FilesEx.text(log,"\u001b]0;hidden title\u0007visible\u0001\n");assertEquals("visible",runtime.liveLog().latest);
            log.delete();assertEquals("",runtime.liveLog().latest);
        }finally{FilesEx.delete(runtime.home);}
    }
    @Test public void liveLogRedactsBeforeDisplayAndFailsClosedWithoutReadingOtherFiles()throws Exception {
        ServerRuntime runtime=new ServerRuntime(context,builder->{throw new AssertionError("No process needed");});
        File credentials=new File(runtime.state,"generations/fixture/database-credentials.json"),log=new File(runtime.logs,"operation.log");
        try{
            FilesEx.text(credentials,"{\"password\":\"fixture-secret\"}");
            FilesEx.text(log,"compiler fixture-secret\n");
            FilesEx.text(new File(runtime.logs,"import.sql"),"private SQL contents\n");
            FilesEx.text(new File(runtime.logs,"database-credentials.json"),"private credential contents\n");
            ServerRuntime.LogSnapshot snapshot=runtime.liveLog();assertEquals("compiler [redacted]",snapshot.latest);assertFalse(snapshot.text.contains("fixture-secret"));assertFalse(snapshot.text.contains("private"));
            FilesEx.text(credentials,"{invalid");snapshot=runtime.liveLog();assertEquals("",snapshot.latest);assertTrue(snapshot.text.contains("unavailable"));assertFalse(snapshot.text.contains("compiler"));
            FilesEx.text(credentials,"{\"password\":\"fixture-secret\"}");log.delete();
            java.nio.file.Files.createSymbolicLink(log.toPath(),credentials.toPath());assertEquals("",runtime.liveLog().latest);assertFalse(runtime.liveLog().text.contains("fixture-secret"));
        }finally{java.nio.file.Files.deleteIfExists(log.toPath());FilesEx.delete(runtime.home);}
    }
    @Test public void liveLogClearsAtOperationStartAndKeepsFinalOutputAfterExit()throws Exception {
        File home=new File(context.getFilesDir(),"server-runtime");AtomicInteger runs=new AtomicInteger();
        ServerRuntime runtime=new ServerRuntime(nativeContext(),builder->{
            File operation=new File(home,"logs/operation.log");assertFalse("Prior output must rotate before every child",operation.exists());
            FilesEx.text(operation,"[100%] build "+runs.incrementAndGet()+"\n");return new Child(false);
        });
        try{
            FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");FilesEx.text(new File(runtime.root,"lsb-server-tools-v4"),"ready");
            FilesEx.text(new File(runtime.logs,"operation.log"),"old compiler output\n");
            AutoCloseable reserved=runtime.reserveSession(false,s->{});
            assertEquals("",runtime.liveLog().latest);assertFalse(runtime.liveLog().text.contains("old compiler"));reserved.close();
            long epoch=runtime.logEpoch();runtime.perform("build-source",true,s->{});
            ServerRuntime.LogSnapshot result=runtime.liveLog();assertFalse(result.running);assertEquals("[100%] build 1",result.latest);assertTrue(result.epoch>epoch);
            runtime.perform("inspect",false,s->{});assertEquals("[100%] build 2",runtime.liveLog().latest);assertFalse(runtime.liveLog().text.contains("build 1"));
            // Dependency upgrades use the same rotation path as builds.
            new File(runtime.root,"lsb-server-tools-v4").delete();FilesEx.text(new File(runtime.root,"usr/bin/bash"),"bash");
            try{runtime.install(s->{});fail("Fixture does not install the tools sentinel");}catch(IOException expected){}
            assertEquals("[100%] build 3",runtime.liveLog().latest);
        }finally{FilesEx.delete(runtime.home);}
    }
    @Test public void buildRequestsBindDisplayedIdsAndSelectedWorkers()throws Exception {
        File home=new File(context.getFilesDir(),"server-runtime");java.util.List<org.json.JSONObject> requests=new java.util.ArrayList<>();
        ServerRuntime runtime=new ServerRuntime(nativeContext(),builder->{
            try{requests.add(new org.json.JSONObject(FilesEx.read(new File(home,"run/request.json"),32768)));}
            catch(org.json.JSONException error){throw new IOException(error);}return new Child(false);
        });
        File identity=new File(MainActivity.storage(context),"server/current/source-identity.json");
        String build="11111111-2222-4333-8444-555555555555",generation="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        try{
            FilesEx.delete(runtime.home);FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");FilesEx.text(new File(runtime.root,"lsb-server-tools-v4"),"ready");
            FilesEx.text(identity,"{\"origin\":\"Selected source A\",\"snapshot_id\":\"source-a\",\"commit\":\"16281a81de58acfb315b639d9b79aaacd52a64f2\"}");
            context.getSharedPreferences("server",0).edit().putInt("jobs",3).commit();
            runtime.performBuild("build-source","","","",s->{});
            org.json.JSONObject request=requests.get(0);
            assertEquals(3,request.getInt("jobs"));assertTrue(request.getBoolean("build"));assertEquals("source-a",request.getJSONObject("source_identity").getString("snapshot_id"));
            // Fetch selection can change; later operations must keep the explicit build ID.
            FilesEx.text(identity,"{\"origin\":\"Selected source B\",\"snapshot_id\":\"source-b\"}");
            runtime.performBuild("stage-build",build,"","copy-current",s->{});
            runtime.performBuild("check-staged",build,generation,"",s->{});
            runtime.performBuild("deploy-staged",build,generation,"",s->{});
            assertEquals(4,requests.size());
            for(int i=1;i<4;i++){assertEquals(build,requests.get(i).getString("build_id"));assertFalse(requests.get(i).getBoolean("build"));assertFalse(requests.get(i).has("source_identity"));}
            assertEquals("copy-current",requests.get(1).getString("database_mode"));
            assertEquals(generation,requests.get(2).getString("generation"));assertEquals(generation,requests.get(3).getString("generation"));
            String before=FilesEx.read(new File(runtime.run,"request.json"),32768);
            try{runtime.performBuild("deploy-staged","",generation,"",s->{});fail("No implied latest build");}catch(IOException expected){}
            try{runtime.performBuild("stage-build",build,"","erase",s->{});fail("Unknown database mode");}catch(IOException expected){}
            assertEquals(before,FilesEx.read(new File(runtime.run,"request.json"),32768));assertEquals(4,requests.size());assertFalse(runtime.alive());
            context.getSharedPreferences("server",0).edit().putInt("jobs",17).commit();
            try{runtime.performBuild("build-source","","","",s->{});fail("Invalid jobs must not launch a process");}catch(IOException expected){assertTrue(expected.getMessage().contains("1 to 16"));}
            assertEquals(4,requests.size());assertFalse(runtime.alive());
        }finally{context.getSharedPreferences("server",0).edit().remove("jobs").commit();identity.delete();FilesEx.delete(runtime.home);}
    }
    @Test public void buildStateSeparatesFetchedBuiltStagedAndActiveReceipts()throws Exception {
        ServerRuntime runtime=new ServerRuntime(context,builder->{throw new AssertionError("Reading receipts must not start a process");});
        File identity=new File(MainActivity.storage(context),"server/current/source-identity.json");
        String id="aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
        try{
            FilesEx.delete(runtime.home);
            FilesEx.text(identity,"{\"origin\":\"Fetched B\",\"snapshot_id\":\"b\"}");
            FilesEx.text(new File(runtime.state,"source-build/server/android-build.json"),"{\"state\":\"passed\",\"build_id\":\"11111111-2222-4333-8444-555555555555\",\"selected_source\":{\"origin\":\"Built A\"}}");
            FilesEx.text(new File(runtime.state,"staged.json"),"{\"generation\":\""+id+"\",\"state\":\"checked\",\"database_mode\":\"fresh\"}");
            FilesEx.text(new File(runtime.state,"active.json"),"{\"current\":\""+id+"\"}");
            FilesEx.text(new File(runtime.state,"generations/"+id+"/deployment.json"),"{\"generation\":\""+id+"\",\"expected_client\":\"old-client\"}");
            org.json.JSONObject state=runtime.buildState();
            assertEquals("Fetched B",state.getJSONObject("selected_source").getString("origin"));
            assertEquals("Built A",state.getJSONObject("build").getJSONObject("selected_source").getString("origin"));
            assertEquals("fresh",state.getJSONObject("staged").getString("database_mode"));
            assertEquals("deployed",state.getJSONObject("staged").getString("state"));
            assertEquals("old-client",state.getJSONObject("deployed").getString("expected_client"));
            assertFalse(state.getJSONObject("database_import").getBoolean("available"));assertFalse(runtime.alive());
        }finally{identity.delete();FilesEx.delete(runtime.home);}
    }
    @Test public void importedDatabaseIdentityTracksExactUncompressedBackup()throws Exception {
        ServerRuntime runtime=new ServerRuntime(context,builder->{throw new AssertionError("Import must not deploy or start database");});
        byte[] bytes="CREATE TABLE fixture(id INT);\n".getBytes("UTF-8");
        try{
            FilesEx.delete(runtime.home);runtime.importDatabase(new ByteArrayInputStream(bytes),s->{});
            org.json.JSONObject imported=runtime.buildState().getJSONObject("database_import");
            assertTrue(imported.getBoolean("available"));assertEquals(bytes.length,imported.getLong("bytes"));
            StringBuilder hash=new StringBuilder();for(byte b:java.security.MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            assertEquals(hash.toString(),imported.getString("sha256"));assertFalse(new File(runtime.state,"active.json").exists());
            FilesEx.text(new File(runtime.state,"import.sql"),"A different, longer backup is now selected\n");
            assertFalse(runtime.buildState().getJSONObject("database_import").has("sha256"));
        }finally{FilesEx.delete(runtime.home);}
    }

}
