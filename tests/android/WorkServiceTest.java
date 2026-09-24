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
        set(ServerRuntime.class,runtime,"active",false);new File(runtime.root,"lsb-server-tools-v2").delete();Job oldTools=new Job();
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
        FilesEx.text(new File(runtime.root,"lsb-server-ready"),"ready");FilesEx.text(new File(runtime.root,"lsb-server-tools-v2"),"ready");
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
}
