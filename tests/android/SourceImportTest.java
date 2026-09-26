package io.github.russianranger.lsb;

import io.github.russianranger.lsb.core.FilesEx;
import java.io.*;
import java.nio.file.Files;
import java.util.zip.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class SourceImportTest {
    private byte[] archive(boolean ambiguous)throws Exception{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(bytes)){
            entry(zip,"README.txt","Server backup and notes");
            String[] roots=ambiguous?new String[]{"wrapper/server/","other/"}:new String[]{"wrapper/server/"};
            for(String root:roots){
                entry(zip,root+"CMakeLists.txt","project(server)");entry(zip,root+"src/main.cpp","source");entry(zip,root+"sql/accounts.sql","schema");
                entry(zip,root+"settings/default/login.lua","CLIENT_VER = '30260904_1',");
                entry(zip,root+"settings/login.lua","CLIENT_VER = '30251204_1',");
                entry(zip,root+"build/xi_connect","retained compiled input");
            }
        }return bytes.toByteArray();
    }
    private void entry(ZipOutputStream zip,String name,String text)throws Exception{zip.putNextEntry(new ZipEntry(name));zip.write(text.getBytes("UTF-8"));zip.closeEntry();}
    @Test public void wrappedServerWithSiblingNotesKeepsRevisionAndBuildBinaries()throws Exception{
        File home=Files.createTempDirectory("server-import").toFile();
        String report=SourceImport.stage(home,new ByteArrayInputStream(archive(false)),"User-selected server ZIP",s->{});
        assertTrue(report.contains("Expected client: 30251204_1"));assertTrue(report.contains("Server-root binaries: 0/4"));
        assertTrue(new File(home,"current/wrapper/server/build/xi_connect").isFile());
        assertEquals("CLIENT_VER = '30251204_1',",FilesEx.read(new File(home,"current/wrapper/server/settings/login.lua"),4096));
        assertFalse(new File(home,"incoming").exists());
    }
    @Test public void ambiguousServerZipPreservesSelectedSnapshot()throws Exception{
        File home=Files.createTempDirectory("server-ambiguous").toFile();
        SourceImport.stage(home,new ByteArrayInputStream(archive(false)),"known matching snapshot",s->{});
        byte[] before=Files.readAllBytes(new File(home,"current/source-report.txt").toPath());
        try{SourceImport.stage(home,new ByteArrayInputStream(archive(true)),"ambiguous",s->{});fail("Two server roots must be rejected");}
        catch(IOException expected){assertTrue(expected.getMessage().contains("exactly one"));}
        assertArrayEquals(before,Files.readAllBytes(new File(home,"current/source-report.txt").toPath()));assertFalse(new File(home,"incoming").exists());
    }
}
