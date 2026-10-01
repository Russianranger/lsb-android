package io.github.russianranger.lsb;

import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class ClientLaunchValidationTest {
    private File home,generation;private PreparedClientStore store;
    private static final SafeZip.Progress QUIET=s->{};
    private static byte[] pe(){byte[] b=new byte[256];b[0]='M';b[1]='Z';b[0x3c]=0x40;b[0x40]='P';b[0x41]='E';b[0x44]=0x4c;b[0x45]=1;b[0x58]=0x0b;b[0x59]=1;return b;}
    @Before public void before()throws Exception{
        home=Files.createTempDirectory("launch-validation-").toFile();ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            for(String path:new String[]{"POL/polcore.dll","POL/pol.exe","FFXI/FFXi.dll","FFXI/FFXiMain.dll","FFXI/xiloader.exe"}){zip.putNextEntry(new ZipEntry(path));zip.write(pe());zip.closeEntry();}
            zip.putNextEntry(new ZipEntry("FFXI/ROM/0/0.DAT"));zip.write(new byte[]{1,2,3});zip.closeEntry();
        }
        ClientStore imported=new ClientStore(new File(home,"imported"));imported.importClient(new ByteArrayInputStream(bytes.toByteArray()),false,true,QUIET);
        File prefix=new File(home,"prefix");FilesEx.text(new File(prefix,"lsb-prefix-ready.json"),"{}");
        store=new PreparedClientStore(new File(home,"prepared"));generation=store.prepare(imported,prefix,QUIET);
        FilesEx.text(new File(generation,"initialization-passed.json"),new JSONObject().put("status","passed").put("generation",generation.getName()).toString());store.promote(generation);
    }
    @After public void after()throws Exception{FilesEx.delete(home);}
    private JSONObject manifest()throws Exception{return ClientLaunchValidation.manifest(generation,store.metadata(generation),QUIET);}
    @Test public void fastManifestMatchesFullInspectionCriticalFiles()throws Exception{
        JSONObject quick=manifest();assertNotNull(quick);ClientInspector.Snapshot full=ClientInspector.inspect(new File(generation,"client"),store.metadata(generation).getProperty("core"),QUIET);
        JSONArray keys=new JSONObject(full.inventory).getJSONArray("keyFiles");for(int i=0;i<keys.length();i++){JSONObject key=keys.getJSONObject(i);assertEquals(key.getString("sha256"),quick.getJSONObject("key_files").getString(key.getString("path")));}
        assertEquals(FilesEx.hash(new File(generation,"source-inventory.json")),quick.getString("inventory_sha256"));
    }
    @Test public void changedDllCannotHideBehindMatchingSizeAndTimestamp()throws Exception{
        File dll=new File(generation,"client/FFXI/FFXiMain.dll");FileTime time=Files.getLastModifiedTime(dll.toPath());byte[] data=Files.readAllBytes(dll.toPath());data[200]=42;Files.write(dll.toPath(),data);Files.setLastModifiedTime(dll.toPath(),time);
        try{manifest();fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("binary changed"));}
    }
    @Test public void repeatLaunchSkipsRomWalkButExplicitInspectionStillChecksIt()throws Exception{
        Path link=new File(generation,"client/FFXI/ROM/0/link.DAT").toPath();Files.createSymbolicLink(link,new File(home,"missing").toPath());
        assertNotNull(manifest());
        try{ClientInspector.inspect(new File(generation,"client"),store.metadata(generation).getProperty("core"),QUIET);fail();}catch(IOException expected){}
    }
    @Test public void criticalAncestorSymlinksAndModifiedInventoryAreRejected()throws Exception{
        File game=new File(generation,"client/FFXI"),other=new File(generation,"client/saved-game");Files.move(game.toPath(),other.toPath());Files.createSymbolicLink(game.toPath(),other.toPath());
        try{manifest();fail();}catch(IOException expected){}
        Files.delete(game.toPath());Files.move(other.toPath(),game.toPath());
        FilesEx.text(new File(generation,"source-inventory.json"),"{}");try{manifest();fail();}catch(IOException expected){assertTrue(expected.getMessage().contains("inventory changed"));}
    }
    @Test public void legacyOrUninitializedClientUsesFullInspection()throws Exception{
        Properties meta=store.metadata(generation);meta.remove("inventorySha256");assertNull(ClientLaunchValidation.manifest(generation,meta,QUIET));
        FilesEx.text(new File(generation,"initialization-passed.json"),"{\"status\":\"failed\"}");assertNull(manifest());
    }
}
