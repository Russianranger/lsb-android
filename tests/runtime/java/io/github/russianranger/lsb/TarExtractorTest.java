package io.github.russianranger.lsb;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Dependency-free host regressions exercising the same parser shipped in the APK. */
public final class TarExtractorTest {
    interface Check {void run(File root)throws Exception;}
    private static int checks;
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void contents(File root,String name,String expected)throws Exception {
        require(Arrays.equals(Files.readAllBytes(new File(root,name).toPath()),bytes(expected)),"Unexpected file contents: "+name);
    }
    private static byte[] pax(String... pairs)throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        for(int i=0;i<pairs.length;i+=2) {
            byte[] tail=bytes(" "+pairs[i]+"="+pairs[i+1]+"\n");
            int length=tail.length+1;
            while(length!=tail.length+String.valueOf(length).length())length=tail.length+String.valueOf(length).length();
            out.write(bytes(String.valueOf(length)));out.write(tail);
        }
        return out.toByteArray();
    }
    private static final class Tar {
        final ByteArrayOutputStream out=new ByteArrayOutputStream();
        Tar member(String name,char type,String link,byte[] data)throws Exception {return member(name,"",type,link,data,data.length,0644);}
        Tar member(String name,String prefix,char type,String link,byte[] data,long declaredSize,int mode)throws Exception {
            byte[] header=new byte[512];
            field(header,0,100,name);field(header,100,8,String.format(Locale.ROOT,"%07o",mode));
            field(header,124,12,String.format(Locale.ROOT,"%011o",declaredSize));
            header[156]=(byte)type;field(header,157,100,link);field(header,257,6,"ustar");field(header,263,2,"00");field(header,345,155,prefix);
            long checksum=0;for(int i=0;i<header.length;i++)checksum+=i>=148&&i<156?32:header[i]&255;
            field(header,148,8,String.format(Locale.ROOT,"%06o",checksum));header[155]=' ';
            out.write(header);out.write(data);out.write(new byte[(512-data.length%512)%512]);return this;
        }
        Tar extended(char type,String... pairs)throws Exception {return member("PaxHeaders/member",type,"",pax(pairs));}
        Tar file(String name,String contents)throws Exception {return member(name,'0',"",bytes(contents));}
        Tar link(String name,char type,String target)throws Exception {return member(name,type,target,new byte[0]);}
        static void field(byte[] header,int start,int length,String value) {
            byte[] data=bytes(value);if(data.length>length)throw new IllegalArgumentException("Fixture header field is too long");
            System.arraycopy(data,0,header,start,data.length);
        }
    }
    private static void run(String label,Tar tar,boolean success,Check check)throws Exception {
        File work=Files.createTempDirectory("lsb-tar-test-").toFile(), root=new File(work,"root");root.mkdirs();
        File archive=new File(work,"archive.tar.gz");
        try {
            try(OutputStream out=new GZIPOutputStream(new FileOutputStream(archive))) {tar.out.writeTo(out);out.write(new byte[1024]);}
            IOException failure=null;
            try{TarExtractor.extract(archive,root,n->{});}catch(IOException e){failure=e;}
            if(success&&failure!=null)throw new AssertionError(label+": "+failure.getMessage(),failure);
            if(!success&&failure==null)throw new AssertionError(label+": unsafe/malformed archive accepted");
            if(check!=null)check.run(root);
            checks++;
        } finally {TarExtractor.remove(work);}
    }
    private static void reject(String label,Tar tar)throws Exception {run(label,tar,false,null);}
    public static void main(String[] args)throws Exception {
        run("USTAR files, directory, prefix, mode and links",new Tar()
            .member("bin",'5',"",new byte[0])
            .member("tool","bin",'0',"",bytes("executable"),10,0755)
            .member("plain",'\0',"",bytes("text"))
            .link("hard",'1',"bin/tool").link("relative",'2',"bin/tool").link("absolute",'2',"/usr/bin/tool"),true,root->{
                contents(root,"bin/tool","executable");contents(root,"plain","text");
                require(new File(root,"bin/tool").canExecute(),"Executable bit lost");
                require(Files.isSameFile(new File(root,"hard").toPath(),new File(root,"bin/tool").toPath()),"Hard link lost");
                require(Files.readSymbolicLink(new File(root,"absolute").toPath()).toString().equals("/usr/bin/tool"),"Guest absolute symlink changed");
            });
        final String longPath="directory/"+String.join("",Collections.nCopies(12,"long-name-"))+"file";
        run("GNU long names and links",new Tar().member("././@LongLink",'L',"",bytes(longPath+"\0"))
            .file("placeholder","gnu").member("././@LongLink",'K',"",bytes(longPath+"\0")).link("gnu-link",'2',"placeholder"),true,root->{
                contents(root,longPath,"gnu");contents(root,"gnu-link","gnu");
            });
        run("Ubuntu timestamp PAX headers",new Tar().extended('x',"atime","1775623567.123456789","ctime","1775623567.987654321","mtime","1775623567.5")
            .file("usr/bin/bash","bash").extended('x',"atime","0","ctime","0").link("bin",'2',"usr/bin"),true,root->{
                contents(root,"bin/bash","bash");require(!new File(root,"PaxHeaders").exists(),"PAX metadata extracted as file");
            });
        final String unicodePath="share/é漢字/"+String.join("",Collections.nCopies(12,"long-name-"))+"λ";
        run("PAX UTF-8 paths and link targets",new Tar().extended('x',"path",unicodePath).file("placeholder","utf8")
            .extended('x',"path","hard-pax","linkpath",unicodePath).link("placeholder",'1',"wrong")
            .extended('x',"linkpath",unicodePath).link("sym-pax",'2',"wrong").file("after","plain"),true,root->{
                contents(root,unicodePath,"utf8");contents(root,"hard-pax","utf8");contents(root,"sym-pax","utf8");contents(root,"after","plain");
            });
        final String large=String.join("",Collections.nCopies(513,"x"));
        run("Effective PAX size controls reads and padding",new Tar().extended('x',"size","513")
            .member("payload","",'0',"",bytes(large),0,0644).file("after","aligned"),true,root->{contents(root,"payload",large);contents(root,"after","aligned");});
        run("Repeated PAX keys and ignored metadata",new Tar().extended('x',"path","unused","path","chosen","comment","hello\nother=value","SCHILY.xattr.user.note","metadata")
            .file("placeholder","chosen").file("next","next"),true,root->{contents(root,"chosen","chosen");contents(root,"next","next");require(!new File(root,"unused").exists(),"First duplicate key won");});
        run("Global and local scope, deletion and reset",new Tar().extended('g',"size","3","mtime","0")
            .member("global-a","",'0',"",bytes("aaa"),0,0644)
            .extended('x',"size","1").member("local","",'0',"",bytes("b"),0,0644)
            .member("global-b","",'0',"",bytes("ccc"),0,0644)
            .extended('x',"size","").file("suppressed","dd")
            .member("global-c","",'0',"",bytes("eee"),0,0644)
            .extended('g',"size","").file("deleted","ff"),true,root->{
                contents(root,"global-a","aaa");contents(root,"local","b");contents(root,"global-b","ccc");contents(root,"suppressed","dd");contents(root,"global-c","eee");contents(root,"deleted","ff");
            });
        run("Adjacent local headers retain overrides",new Tar().extended('x',"path","kept").extended('x',"size","3")
            .member("placeholder","",'0',"",bytes("abc"),0,0644),true,root->contents(root,"kept","abc"));
        for(String name:new String[]{"../outside","/outside","nested/../../outside","nested\\outside","bad\0name"})
            reject("Unsafe PAX path "+name,new Tar().extended('x',"path",name).file("safe","bad"));
        reject("Global path traversal",new Tar().extended('g',"path","../outside").file("safe","bad"));
        reject("PAX hardlink traversal",new Tar().file("safe","ok").extended('x',"linkpath","../outside").link("hard",'1',"safe"));
        run("Deferred symlinks cannot redirect subsequent writes",new Tar().link("escape",'2',"..").extended('x',"path","escape/outside").file("safe","inside"),false,root->{
            require(!new File(root.getParentFile(),"outside").exists(),"Symlink traversal wrote outside root");
        });
        reject("Long PAX path bound",new Tar().extended('x',"path",String.join("",Collections.nCopies(16385,"x"))).file("safe","bad"));
        reject("PAX metadata allocation bound",new Tar().member("pax","",'x',"",new byte[0],1024*1024+1,0644));
        for(String size:new String[]{"8589934593","99999999999999999999999999999","-1","+1"," 1","1x"})
            reject("Invalid or oversized PAX size "+size,new Tar().extended('x',"size",size).file("safe",""));
        for(String data:new String[]{"0 path=x\n","999 path=x\n","999999999999999999999999 path=x\n","x path=x\n","8 =abcd\n","12 path=abc!","11 path:xx\n","12 path=x\n"})
            reject("Malformed PAX record",new Tar().member("pax",'x',"",bytes(data)).file("safe","bad"));
        byte[] badUtf8=pax("path","x");badUtf8[badUtf8.length-2]=(byte)0xff;
        reject("Malformed UTF-8 path",new Tar().member("pax",'x',"",badUtf8).file("safe","bad"));
        reject("Missing file after local PAX header",new Tar().extended('x',"mtime","0"));
        reject("Binary PAX path encoding",new Tar().extended('x',"hdrcharset","BINARY").file("safe","bad"));
        for(String key:new String[]{"GNU.sparse.major","GNU.sparse.map","SCHILY.realsize","SCHILY.filetype","GNU.volume.size"})
            reject("Unsupported payload layout "+key,new Tar().extended('x',key,"1").file("safe","bad"));
        reject("PAX cannot turn a link into a payload",new Tar().extended('x',"size","1").link("link",'2',"target"));
        Tar truncated=new Tar().member("pax","",'x',"",new byte[0],2048,0644);
        reject("Truncated PAX body",truncated);
        Tar corrupt=new Tar().file("safe","bad");byte[] corruptBytes=corrupt.out.toByteArray();corruptBytes[0]^=1;corrupt.out.reset();corrupt.out.write(corruptBytes);
        reject("Checksum still enforced",corrupt);
        System.out.println("PASS: "+checks+" runtime tar extraction regressions");
    }
}
