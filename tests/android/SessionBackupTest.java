package io.github.russianranger.lsb;

import android.content.*;
import android.content.pm.ApplicationInfo;
import io.github.russianranger.lsb.core.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercises the Android orchestration with independent app directories/settings. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class SessionBackupTest {
    private final List<Installation> installations=new ArrayList<>();
    private static final String GENERATION="11111111-1111-1111-1111-111111111111";
    private static final String SETTINGS="session-restore-settings.json";
    private static final SafeZip.Progress QUIET=message->{};

    private static final class Installation extends ContextWrapper {
        final File home,files,external,noBackup;
        final String packageName,prefNamespace=UUID.randomUUID().toString();
        final Map<String,SharedPreferences> preferences=new HashMap<>();
        String failCommit;
        Installation(boolean nested,String packageName)throws Exception {
            super(RuntimeEnvironment.getApplication());this.packageName=packageName;
            home=Files.createTempDirectory("session-app-").toFile();files=new File(home,"files");external=nested?null:new File(home,"external");noBackup=new File(home,"no_backup");
            files.mkdirs();noBackup.mkdirs();if(external!=null)external.mkdirs();new File(home,"shared_prefs").mkdirs();
        }
        @Override public Context getApplicationContext(){return this;}
        @Override public File getFilesDir(){return files;}
        @Override public File getExternalFilesDir(String type){return external;}
        @Override public File getNoBackupFilesDir(){return noBackup;}
        @Override public String getPackageName(){return packageName;}
        @Override public ApplicationInfo getApplicationInfo(){ApplicationInfo info=new ApplicationInfo(super.getApplicationInfo());info.dataDir=home.getPath();info.nativeLibraryDir=new File(home,"native").getPath();return info;}
        @Override public SharedPreferences getSharedPreferences(String name,int mode){
            SharedPreferences original=preferences.get(name);
            if(original==null){
                original=super.getSharedPreferences(prefNamespace+"_"+name,mode);preferences.put(name,original);
                try{new File(home,"shared_prefs/"+name+".xml").createNewFile();}catch(IOException error){throw new RuntimeException(error);}
            }
            final SharedPreferences delegate=original;
            return (SharedPreferences)Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),new Class[]{SharedPreferences.class},(proxy,method,args)->{
                if(!method.getName().equals("edit"))return method.invoke(delegate,args);
                SharedPreferences.Editor editor=delegate.edit();
                return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),new Class[]{SharedPreferences.Editor.class},(editProxy,editMethod,editArgs)->{
                    if(editMethod.getName().equals("commit")&&name.equals(failCommit)){failCommit=null;return false;}
                    Object result=editMethod.invoke(editor,editArgs);return result==editor?editProxy:result;
                });
            });
        }
    }
    private static final class Child extends Process {
        boolean alive;
        Child(boolean alive){this.alive=alive;}
        public OutputStream getOutputStream(){return new ByteArrayOutputStream();}
        public InputStream getInputStream(){return new ByteArrayInputStream(new byte[0]);}
        public InputStream getErrorStream(){return new ByteArrayInputStream(new byte[0]);}
        public int waitFor(){alive=false;return 0;}
        public boolean waitFor(long timeout,TimeUnit unit){return !alive;}
        public int exitValue(){if(alive)throw new IllegalThreadStateException();return 0;}
        public boolean isAlive(){return alive;}
        public void destroy(){alive=false;}
    }
    private Installation installation(boolean nested,boolean test)throws Exception {
        Installation c=new Installation(nested,"io.github.russianranger.lsb"+(test?".restoretest":""));installations.add(c);return c;
    }
    private static void reset(){ClientRuntime.resetAfterRestore();ServerRuntime.resetAfterRestore();SessionBackup.active=false;SessionBackup.recoveryError="";WorkService.busy=false;}
    private static void set(Object instance,String name,Object value)throws Exception {Field field=instance.getClass().getDeclaredField(name);field.setAccessible(true);field.set(instance,value);}
    private static void select(ServerRuntime runtime)throws Exception {Field field=ServerRuntime.class.getDeclaredField("instance");field.setAccessible(true);field.set(null,runtime);}
    private static String read(File root,String relative)throws Exception{return FilesEx.read(new File(root,relative),1048576);}
    private static void write(File root,String relative,String value)throws Exception{FilesEx.text(new File(root,relative),value);}
    private byte[] export(Installation c)throws Exception {reset();ByteArrayOutputStream bytes=new ByteArrayOutputStream();SessionBackup.export(c,bytes,QUIET);assertFalse(SessionBackup.active);return bytes.toByteArray();}
    private static Map<String,File> roots(Installation c)throws Exception{Map<String,File> roots=new LinkedHashMap<>();roots.put("files",c.files);roots.put("managed",MainActivity.storage(c));return roots;}
    private static void settings(Installation c)throws Exception {
        assertTrue(c.getSharedPreferences("runtime",0).edit().putString("renderer","turnip26").putBoolean("sound",true).putInt("display_fps",60)
            .putLong("large_value",9223372036854775707L).putFloat("sensitivity",1.25f).putStringSet("options",new HashSet<>(Arrays.asList("one","two"))).commit());
        assertTrue(c.getSharedPreferences("controller",0).edit().putString("profile","Thor").commit());
        assertTrue(c.getSharedPreferences("custom_future_settings",0).edit().putString("future","retained").commit());
    }
    private static void sameSettings(Installation a,Installation b){
        for(String name:Arrays.asList("runtime","controller","custom_future_settings"))assertEquals(name,a.getSharedPreferences(name,0).getAll(),b.getSharedPreferences(name,0).getAll());
    }
    @Before public void before(){reset();}
    @After public void after()throws Exception {
        reset();Thread.interrupted();
        for(Installation c:installations){for(SharedPreferences prefs:c.preferences.values())prefs.edit().clear().commit();TarExtractor.remove(c.home);}
    }

    @Test public void completeExportRestoresBothRuntimesClientsDatabaseAndEverySettingIntoIsolatedApp()throws Exception {
        Installation source=installation(false,false),target=installation(false,true);settings(source);
        File managed=MainActivity.storage(source);
        Map<String,String> files=new LinkedHashMap<>();
        files.put("rt/root/lsb-runtime.sha256","runtime fixture");files.put("rt/root/bin/wine","executable runtime fixture");
        files.put("rt/prefix/user.reg","Wine settings fixture");files.put("rt/prefix/drive_c/windows/filename\\with\\backslashes.log","literal Linux filename fixture");files.put("rt/fex/wine-fixture/bin/wine","FEX Wine fixture");
        files.put("rt/clients/state.properties","current="+GENERATION+"\n");
        files.put("rt/clients/"+GENERATION+"/prefix/system.reg","prepared Windows registry");
        files.put("rt/clients/"+GENERATION+"/client/ROM/0/0.DAT","prepared client fixture");
        files.put("rt/clients/"+GENERATION+"/copy-complete","1\n");
        files.put("server-runtime/rootfs/lsb-server-ready","installed");
        files.put("server-runtime/state/active.json","{\"current\":\""+GENERATION+"\"}");
        files.put("server-runtime/state/generations/"+GENERATION+"/database/ibdata1","raw stopped database fixture");
        files.put("server-runtime/state/generations/"+GENERATION+"/database-credentials.json","{\"game\":\"fixture-private-secret\"}");
        files.put("server-runtime/state/generations/"+GENERATION+"/server/xi_map","server binary fixture");
        for(Map.Entry<String,String> item:files.entrySet())write(source.files,item.getKey(),item.getValue());
        write(managed,"session/current/client/ROM/0/0.DAT","original client fixture");write(managed,"session/current/session.properties","host=127.0.0.1\nregion=US\n");
        write(managed,"server/current/scripts/mob.lua","custom server script");
        write(source.files,"rt/run/credentials.pipe","transient");write(source.files,"rt/tmp/socket","transient");
        write(source.files,"server-runtime/run/status.json","{\"phase\":\"running\"}");write(source.files,"server-runtime/tmp/socket","transient");
        Path wine=new File(source.files,"rt/root/bin/wine").toPath();Files.setPosixFilePermissions(wine,EnumSet.of(PosixFilePermission.OWNER_READ,PosixFilePermission.OWNER_WRITE,PosixFilePermission.OWNER_EXECUTE));
        Path links=new File(source.files,"rt/prefix/dosdevices").toPath();Files.createDirectories(links);
        Files.createSymbolicLink(links.resolve("d:"),new File(managed,"session/current/client").toPath());
        Files.createLink(new File(source.files,"rt/root/bin/wine-hardlink").toPath(),wine);
        final int[] dumps={0};ServerRuntime server=new ServerRuntime(source,builder->{
            assertTrue(builder.command().contains("/opt/lsb-server/manager.py"));
            try{assertEquals("backup",new JSONObject(read(source.files,"server-runtime/run/request.json")).getString("action"));
                write(source.files,"server-runtime/state/export.sql","complete logical database fixture");write(source.files,"server-runtime/run/status.json","{\"phase\":\"ready\",\"message\":\"Database copied and stopped\"}");
            }catch(Exception error){throw new IOException(error);}dumps[0]++;return new Child(false);
        });select(server);
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();SessionBackup.export(source,bytes,QUIET);
        assertEquals(1,dumps[0]);assertFalse(server.alive());assertFalse(SessionBackup.active);
        write(target.files,"obsolete","target-only old data");target.getSharedPreferences("runtime",0).edit().putString("obsolete","remove").commit();target.getSharedPreferences("target_only",0).edit().putBoolean("obsolete",true).commit();
        reset();String result=SessionBackup.restore(target,new ByteArrayInputStream(bytes.toByteArray()),QUIET);
        assertTrue(result.contains("restored"));assertFalse(new File(target.files,"obsolete").exists());assertTrue(target.getSharedPreferences("target_only",0).getAll().isEmpty());sameSettings(source,target);
        for(Map.Entry<String,String> item:files.entrySet()){assertEquals(item.getValue(),read(target.files,item.getKey()));assertEquals(item.getValue(),read(source.files,item.getKey()));}
        assertEquals("complete logical database fixture",read(target.files,"server-runtime/state/export.sql"));
        assertEquals("original client fixture",read(MainActivity.storage(target),"session/current/client/ROM/0/0.DAT"));
        assertEquals("custom server script",read(MainActivity.storage(target),"server/current/scripts/mob.lua"));
        assertEquals("127.0.0.1",MainActivity.store(target).config().host);assertEquals("US",MainActivity.store(target).config().region);
        assertEquals(GENERATION,ClientRuntime.get(target).prepared().selected("current").getName());
        assertEquals(new File(MainActivity.storage(target),"session/current/client").toPath(),Files.readSymbolicLink(new File(target.files,"rt/prefix/dosdevices/d:").toPath()));
        assertTrue(Files.isSameFile(new File(target.files,"rt/root/bin/wine").toPath(),new File(target.files,"rt/root/bin/wine-hardlink").toPath()));
        assertEquals(Files.getPosixFilePermissions(wine),Files.getPosixFilePermissions(new File(target.files,"rt/root/bin/wine").toPath()));
        for(String path:Arrays.asList("rt/run/credentials.pipe","rt/tmp/socket","server-runtime/run/status.json","server-runtime/tmp/socket"))assertFalse(path,new File(target.files,path).exists());
        assertFalse(new File(target.files,SETTINGS).exists());assertFalse(SessionBackup.active);
    }

    @Test public void fallbackAndExternalStorageCanRestoreAcrossBothLayouts()throws Exception {
        for(boolean nested:new boolean[]{true,false}){
            Installation source=installation(nested,false),target=installation(!nested,true);
            write(MainActivity.storage(source),"session/client.bin","client");write(source.files,"rt/root/runtime","runtime");
            File link=new File(source.files,"client-link");Files.createSymbolicLink(link.toPath(),new File(MainActivity.storage(source),"session/client.bin").toPath());
            byte[] bytes=export(source);reset();SessionBackup.restore(target,new ByteArrayInputStream(bytes),QUIET);
            assertEquals("client",read(MainActivity.storage(target),"session/client.bin"));assertEquals("runtime",read(target.files,"rt/root/runtime"));
            assertEquals(new File(MainActivity.storage(target),"session/client.bin").toPath(),Files.readSymbolicLink(new File(target.files,"client-link").toPath()));
            assertEquals("client",read(MainActivity.storage(source),"session/client.bin"));
        }
    }

    @Test public void corruptArchiveNeverActivatesOrChangesSettings()throws Exception {
        Installation source=installation(false,false),target=installation(false,true);settings(source);write(source.files,"restored","new payload");byte[] bytes=export(source);
        write(target.files,"sentinel","working runtime");write(MainActivity.storage(target),"sentinel","working client");target.getSharedPreferences("runtime",0).edit().putInt("display_fps",30).commit();
        reset();try{SessionBackup.restore(target,new ByteArrayInputStream(Arrays.copyOf(bytes,bytes.length/2)),QUIET);fail("Truncated archive must fail");}catch(IOException expected){}
        assertEquals("working runtime",read(target.files,"sentinel"));assertEquals("working client",read(MainActivity.storage(target),"sentinel"));
        assertEquals(30,target.getSharedPreferences("runtime",0).getInt("display_fps",0));assertFalse(new File(target.files,"restored").exists());assertFalse(SessionBackup.active);
        SessionBackup.recover(target);assertEquals("working runtime",read(target.files,"sentinel"));
    }

    @Test public void invalidSettingGroupsAreRejectedBeforeActivation()throws Exception {
        Installation source=installation(false,false),target=installation(false,true);write(source.files,"restored","new payload");write(target.files,"sentinel","working runtime");
        JSONObject metadata=new JSONObject().put("format","lsb-complete-session-v1").put("preferences",new JSONObject().put("../escape",new JSONObject()));
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();SessionArchive.write(bytes,roots(source),metadata.toString().getBytes(StandardCharsets.UTF_8),(root,path)->false,QUIET);
        reset();try{SessionBackup.restore(target,new ByteArrayInputStream(bytes.toByteArray()),QUIET);fail("Invalid group must fail");}catch(IOException expected){assertTrue(expected.getMessage().contains("setting group"));}
        assertEquals("working runtime",read(target.files,"sentinel"));assertFalse(new File(target.files,"restored").exists());assertFalse(SessionBackup.active);
    }

    @Test public void interruptedSettingsCommitIsReplayedAfterFolderActivation()throws Exception {
        Installation source=installation(false,false),target=installation(false,true);settings(source);write(source.files,"payload","restored runtime");byte[] bytes=export(source);
        write(target.files,"payload","old runtime");target.getSharedPreferences("runtime",0).edit().putInt("display_fps",30).commit();target.failCommit="runtime";
        reset();try{SessionBackup.restore(target,new ByteArrayInputStream(bytes),QUIET);fail("Failed settings commit must remain recoverable");}catch(IOException expected){assertTrue(expected.getMessage().contains("restore app settings"));}
        assertEquals("restored runtime",read(target.files,"payload"));assertEquals(30,target.getSharedPreferences("runtime",0).getInt("display_fps",0));
        assertTrue(new File(target.files,SETTINGS).isFile());assertFalse(SessionBackup.active);
        SessionBackup.recover(target);sameSettings(source,target);assertFalse(new File(target.files,SETTINGS).exists());
        SessionBackup.recover(target);sameSettings(source,target);assertEquals("restored runtime",read(target.files,"payload"));
    }

    @Test public void liveRuntimesBlockSnapshotsBeforeAnyArchiveBytes()throws Exception {
        Installation c=installation(false,false);
        for(boolean client:new boolean[]{true,false}){
            reset();Object runtime=client?ClientRuntime.get(c):ServerRuntime.get(c);set(runtime,"active",true);ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try{SessionBackup.export(c,bytes,QUIET);fail("Live runtime must block backup");}catch(IOException expected){assertTrue(expected.getMessage().contains("Stop the client"));}
            assertEquals(0,bytes.size());assertFalse(SessionBackup.active);set(runtime,"active",false);
        }
    }

    @Test public void serverReadinessRequiresLiveProcessAndFreshReadyStatus()throws Exception {
        Installation c=installation(false,true);ServerRuntime server=ServerRuntime.get(c);
        write(server.run,"status.json","{\"phase\":\"running\",\"startup\":{\"recent_lines\":[\"map: Loading Mob scripts\",\"map: Ready\"]}}");
        assertFalse(server.ready());set(server,"active",true);assertFalse("A reservation is not a running server",server.ready());set(server,"active",false);
        Child child=new Child(true);set(server,"process",child);assertTrue(server.ready());assertEquals("map: Loading Mob scripts\nmap: Ready",server.startupLog());
        write(server.run,"stop","stop\n");assertFalse("Stopping server cannot authorize a test client launch",server.ready());new File(server.run,"stop").delete();
        write(server.run,"status.json","{\"phase\":\"starting\"}");assertFalse(server.ready());child.destroy();
    }

    @Test public void restoreTestClientCannotConnectBeforeOwnServerOrToAnotherHost()throws Exception {
        Installation c=installation(false,true);ClientRuntime client=ClientRuntime.get(c);ServerRuntime server=ServerRuntime.get(c);
        try{client.run("turnip26",false,"launch",new LoginRequest("127.0.0.1","fixture","private-password"),"windowed720",false);fail("Clone must wait for its own server");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("Restore Test"));assertTrue(expected.getMessage().contains("wait"));}
        Child child=new Child(true);set(server,"process",child);write(server.run,"status.json","{\"phase\":\"running\"}");
        try{client.run("turnip26",false,"launch",new LoginRequest("192.0.2.1","fixture","private-password"),"windowed720",false);fail("Login destination must be checked independently of saved local config");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("127.0.0.1"));}
        try{client.run("turnip26",false,"launch",new LoginRequest("127.0.0.1","fixture","private-password"),"windowed720",false);fail("Fixture has no actual runtime");}
        catch(IOException expected){assertTrue("Local destination with own ready server may proceed to runtime validation",expected.getMessage().contains("pinned runtime"));}
        child.destroy();assertFalse(client.alive());
    }
}
