package io.github.russianranger.lsb.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.security.*;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.zip.*;

/** Complete stopped-session transport. No links exist in staging until all data is verified. */
public final class SessionArchive {
    private static final String HEADER="lsb-complete-session-v1/header.bin", DATA="lsb-complete-session-v1/files.bin", FOOTER="lsb-complete-session-v1/complete.bin";
    private static final int MAGIC=0x4c534253, VERSION=1, MAX_TEXT=16384, MAX_METADATA=1048576;
    private static final int DIRECTORY=1, REGULAR=2, SYMLINK=3, HARDLINK=4;
    public static final long MAX_BYTES=1024L*1024*1024*1024, RESERVE_BYTES=512L*1024*1024;
    public static final int MAX_ENTRIES=1000000;
    private SessionArchive(){}

    public static final class Summary {
        public long entries, files, directories, symlinks, hardlinks, bytes;
        public String toString(){return files+" files, "+symlinks+" symbolic links, "+hardlinks+" hardlinks, "+bytes/1048576+" MiB";}
    }
    public static final class Result {
        public final byte[] metadata;
        public final Summary summary;
        public final Map<String,String> sourceRoots;
        Result(byte[] metadata,Summary summary,Map<String,String> sourceRoots){this.metadata=metadata;this.summary=summary;this.sourceRoots=Collections.unmodifiableMap(sourceRoots);}
    }
    private static final class Header {
        byte[] metadata;
        final LinkedHashMap<String,String> roots=new LinkedHashMap<>(), canonical=new LinkedHashMap<>();
    }
    private static final class Node {
        int type,mode;long mtime,size;String name,link;Path path;
    }
    private static MessageDigest digest(){try{return MessageDigest.getInstance("SHA-256");}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
    private static void text(DataOutputStream out,String value)throws IOException{byte[] b=value.getBytes(StandardCharsets.UTF_8);if(b.length>MAX_TEXT)throw new IOException("Session path is too long");out.writeInt(b.length);out.write(b);}
    private static String text(DataInputStream in)throws IOException{
        int count=in.readInt();if(count<0||count>MAX_TEXT)throw new IOException("Invalid session text length");byte[] bytes=new byte[count];in.readFully(bytes);
        String value=new String(bytes,StandardCharsets.UTF_8);if(!Arrays.equals(bytes,value.getBytes(StandardCharsets.UTF_8)))throw new IOException("Invalid UTF-8 in session path");return value;
    }
    private static Path absolute(File file){return file.toPath().toAbsolutePath().normalize();}
    private static void roots(Map<String,File> roots,boolean empty)throws IOException{
        if(roots.size()!=2||!roots.containsKey("files")||!roots.containsKey("managed"))throw new IOException("Session requires files and managed roots");
        Path a=absolute(roots.get("files")), b=absolute(roots.get("managed"));
        if(empty&&(a.startsWith(b)||b.startsWith(a)))throw new IOException("Session staging roots overlap");
        for(File root:roots.values()){
            if(Files.isSymbolicLink(root.toPath())||!Files.isDirectory(root.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Session root must be a real directory");
            if(empty&&FilesEx.children(root).length!=0)throw new IOException("Session staging directory is not empty");
        }
    }
    private static byte[] header(Map<String,File> roots,byte[] metadata)throws IOException{
        if(metadata==null||metadata.length>MAX_METADATA)throw new IOException("Session metadata exceeds limit");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.writeInt(MAGIC);out.writeInt(VERSION);out.writeInt(2);
        for(String scope:new String[]{"files","managed"}){text(out,scope);text(out,absolute(roots.get(scope)).toString());text(out,roots.get(scope).getCanonicalPath());}
        out.writeInt(metadata.length);out.write(metadata);out.flush();return bytes.toByteArray();
    }
    private static Header header(byte[] bytes)throws IOException{
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes));Header result=new Header();
        if(in.readInt()!=MAGIC||in.readInt()!=VERSION||in.readInt()!=2)throw new IOException("Unsupported complete session backup");
        for(int i=0;i<2;i++){
            String name=text(in),path=text(in),canonical=text(in);
            if((!name.equals("files")&&!name.equals("managed"))||result.roots.containsKey(name))throw new IOException("Invalid session root");
            for(String value:new String[]{path,canonical})if(!value.startsWith("/")||value.equals("/")||value.indexOf('\0')>=0||!Paths.get(value).normalize().toString().equals(value))throw new IOException("Invalid source root path");
            result.roots.put(name,path);result.canonical.put(name,canonical);
        }
        int length=in.readInt();if(length<0||length>MAX_METADATA)throw new IOException("Invalid session settings size");result.metadata=new byte[length];in.readFully(result.metadata);
        if(in.read()!=-1)throw new IOException("Unexpected session header data");return result;
    }
    private static void entry(ZipOutputStream zip,String name,byte[] data)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(data);zip.closeEntry();}
    private static byte[] entry(ZipInputStream zip,String expected,int max)throws IOException{
        ZipEntry e=zip.getNextEntry();if(e==null||!expected.equals(e.getName())||e.isDirectory())throw new IOException("Missing complete session record: "+expected);
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=zip.read(buffer))!=-1){SafeZip.checkCancelled();if(out.size()+n>max)throw new IOException("Session metadata exceeds limit");out.write(buffer,0,n);}zip.closeEntry();return out.toByteArray();
    }
    public static Summary write(OutputStream output,Map<String,File> roots,byte[] metadata,BiPredicate<String,String> exclude,SafeZip.Progress progress)throws IOException{
        roots(roots,false);byte[] header=header(roots,metadata);Summary stats=new Summary();
        try(ZipOutputStream zip=new ZipOutputStream(new BufferedOutputStream(output,262144))){
            zip.setLevel(1);entry(zip,HEADER,header);zip.putNextEntry(new ZipEntry(DATA));
            MessageDigest manifest=digest();DataOutputStream out=new DataOutputStream(new DigestOutputStream(zip,manifest));
            Map<String,String> hardlinks=new HashMap<>();byte[] buffer=new byte[262144];long[] report={0};
            for(String scope:new String[]{"files","managed"})walk(out,roots.get(scope).toPath(),scope,"",exclude,stats,hardlinks,buffer,report,progress,0);
            out.writeByte(0);out.flush();byte[] payloadHash=manifest.digest();zip.closeEntry();
            ByteArrayOutputStream done=new ByteArrayOutputStream();DataOutputStream footer=new DataOutputStream(done);
            footer.writeInt(MAGIC);footer.writeInt(VERSION);footer.writeLong(stats.entries);footer.writeLong(stats.bytes);footer.write(digest().digest(header));footer.write(payloadHash);footer.flush();entry(zip,FOOTER,done.toByteArray());
            progress.update("Complete session exported · "+stats);return stats;
        }
    }
    private static int mode(Path path,boolean directory,boolean managed)throws IOException{
        try{
            Set<PosixFilePermission> p=Files.getPosixFilePermissions(path,LinkOption.NOFOLLOW_LINKS);int result=0;
            for(PosixFilePermission bit:p)result|=1<<(8-bit.ordinal());return result;
        }catch(UnsupportedOperationException e){return directory?0755:(path.toFile().canExecute()?0755:0644);}
        catch(IOException e){if(!managed)throw e;return directory?0755:0644;}
    }
    private static void permissions(Path path,int value)throws IOException{
        Set<PosixFilePermission> bits=EnumSet.noneOf(PosixFilePermission.class);
        for(PosixFilePermission bit:PosixFilePermission.values())if((value&(1<<(8-bit.ordinal())))!=0)bits.add(bit);
        try{Files.setPosixFilePermissions(path,bits);}catch(UnsupportedOperationException e){
            // App-specific external storage has no POSIX modes. Executable runtimes live in files.
            if((value&0111)!=0&&!path.toFile().canExecute()&&!path.toFile().setExecutable(true,true))throw new IOException("Cannot restore executable permission");
        }
    }
    private static BasicFileAttributes attributes(Path path)throws IOException{return Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);}
    private static boolean same(BasicFileAttributes a,BasicFileAttributes b){return a.size()==b.size()&&a.lastModifiedTime().equals(b.lastModifiedTime())&&Objects.equals(a.fileKey(),b.fileKey())&&a.isDirectory()==b.isDirectory()&&a.isRegularFile()==b.isRegularFile()&&a.isSymbolicLink()==b.isSymbolicLink();}
    private static void walk(DataOutputStream out,Path path,String scope,String relative,BiPredicate<String,String> exclude,Summary stats,Map<String,String> hardlinks,byte[] buffer,long[] report,SafeZip.Progress progress,int depth)throws IOException{
        SafeZip.checkCancelled();if(depth>128)throw new IOException("Session directory nesting limit");
        if(!relative.isEmpty()&&exclude!=null&&exclude.test(scope,relative))return;
        BasicFileAttributes before=attributes(path);String name=scope+(relative.isEmpty()?"":"/"+relative);safe(name);
        if(++stats.entries>MAX_ENTRIES)throw new IOException("Session exceeds entry limit");
        int kind;String link=null,key=null;
        if(before.isSymbolicLink()){kind=SYMLINK;link=Files.readSymbolicLink(path).toString();validateLink(link);stats.symlinks++;}
        else if(before.isDirectory()){kind=DIRECTORY;stats.directories++;}
        else if(before.isRegularFile()){
            key=before.fileKey()==null?null:before.fileKey().toString();
            // Most files have one name. Avoid retaining hundreds of thousands of inode keys.
            try{if(((Number)Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS)).longValue()<2)key=null;}
            catch(UnsupportedOperationException|IllegalArgumentException|IOException ignored){}
            if(key!=null&&hardlinks.containsKey(key)){kind=HARDLINK;link=hardlinks.get(key);stats.hardlinks++;}
            else{kind=REGULAR;if(key!=null)hardlinks.put(key,name);stats.files++;}
        }else throw new IOException("Stop all runtimes before backup; unsupported filesystem object: "+name);
        out.writeByte(kind);text(out,name);out.writeInt(kind==SYMLINK?0777:mode(path,before.isDirectory(),scope.equals("managed")));out.writeLong(before.lastModifiedTime().toMillis());
        if(kind==REGULAR){
            long expected=before.size();if(expected<0||expected>MAX_BYTES-stats.bytes)throw new IOException("Session exceeds size limit");out.writeLong(expected);MessageDigest hash=digest();long copied=0;
            try(InputStream in=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){int n;while((n=in.read(buffer))!=-1){SafeZip.checkCancelled();copied+=n;if(copied>expected)throw new IOException("Session changed while exporting "+name);out.write(buffer,0,n);hash.update(buffer,0,n);stats.bytes+=n;report(stats,report,progress,"Exporting complete session");}}
            if(copied!=expected)throw new IOException("Session changed while exporting "+name);out.write(hash.digest());
        }else if(kind==SYMLINK||kind==HARDLINK)text(out,link);
        else for(File child:FilesEx.children(path.toFile()))walk(out,child.toPath(),scope,relative.isEmpty()?child.getName():relative+"/"+child.getName(),exclude,stats,hardlinks,buffer,report,progress,depth+1);
        if(!same(before,attributes(path)))throw new IOException("Session changed while exporting "+name);
    }
    private static void report(Summary stats,long[] previous,SafeZip.Progress progress,String action){
        long now=System.currentTimeMillis();if(now-previous[0]>500){previous[0]=now;progress.update(action+" · "+stats.entries+" entries / "+stats.bytes/1048576+" MiB");}
    }
    private static String safe(String path)throws IOException{
        if(path.isEmpty()||path.startsWith("/")||path.indexOf('\0')>=0||path.indexOf('\\')>=0||path.getBytes(StandardCharsets.UTF_8).length>MAX_TEXT)throw new IOException("Unsafe session path");
        String[] parts=path.split("/",-1);if(parts.length>130||(!parts[0].equals("files")&&!parts[0].equals("managed")))throw new IOException("Invalid session scope");
        for(String p:parts){if(p.isEmpty()||p.equals(".")||p.equals(".."))throw new IOException("Unsafe session path");for(char c:p.toCharArray())if(c<32||c==127)throw new IOException("Control character in session path");}return path;
    }
    private static void validateLink(String link)throws IOException{if(link.isEmpty()||link.indexOf('\0')>=0||link.getBytes(StandardCharsets.UTF_8).length>MAX_TEXT)throw new IOException("Invalid session link target");}
    private static Path target(String name,Map<String,File> roots)throws IOException{
        safe(name);int slash=name.indexOf('/');String scope=slash<0?name:name.substring(0,slash);Path root=absolute(roots.get(scope));return slash<0?root:root.resolve(name.substring(slash+1));
    }
    private static String relocate(String name,String link,Header header,Map<String,File> roots)throws IOException{
        validateLink(link);
        if(!link.startsWith("/")){
            int slash=name.indexOf('/');String scope=name.substring(0,slash);
            Path source=Paths.get(header.roots.get(scope)), resolved=source.resolve(name.substring(slash+1)).getParent().resolve(link).normalize();
            if(resolved.startsWith(source))return link;
            // A relative link crossing logical roots must be rebased to its destination root.
            for(String other:header.roots.keySet())for(String alias:new String[]{header.roots.get(other),header.canonical.get(other)}){
                Path old=Paths.get(alias);if(resolved.startsWith(old))return absolute(roots.get(other)).resolve(old.relativize(resolved)).toString();
            }
            throw new IOException("Relative session link escapes its source roots");
        }
        // Longest aliases first: managed may originally be files/lsb on devices without external storage.
        Map<String,String> replacements=new TreeMap<>((a,b)->{int diff=Integer.compare(b.length(),a.length());return diff!=0?diff:a.compareTo(b);});
        for(String scope:header.roots.keySet()){replacements.put(header.roots.get(scope),absolute(roots.get(scope)).toString());replacements.put(header.canonical.get(scope),absolute(roots.get(scope)).toString());}
        for(Map.Entry<String,String> value:replacements.entrySet())if(link.equals(value.getKey())||link.startsWith(value.getKey()+"/")){
            Path old=Paths.get(value.getKey()), normalized=Paths.get(link).normalize();if(!normalized.startsWith(old))throw new IOException("Host session link escapes its root");
            return Paths.get(value.getValue()).resolve(old.relativize(normalized)).toString();
        }
        String normalized=Paths.get(link).normalize().toString();
        for(String host:new String[]{"/data","/storage","/sdcard","/mnt","/workspace"})if(normalized.equals(host)||normalized.startsWith(host+"/"))throw new IOException("Unmapped host path in session link");
        return link; // PRoot guest absolute /usr, /prefix, /client, /state, etc. retain guest semantics.
    }
    public static Result read(InputStream source,Map<String,File> stagingRoots,Map<String,File> finalRoots,SafeZip.Progress progress)throws IOException{return read(source,stagingRoots,finalRoots,progress,MAX_BYTES,RESERVE_BYTES,MAX_ENTRIES);}
    public static Result read(InputStream source,Map<String,File> stagingRoots,Map<String,File> finalRoots,SafeZip.Progress progress,long maxBytes,long reserve,int maxEntries)throws IOException{
        roots(stagingRoots,true);if(finalRoots.size()!=2||!finalRoots.containsKey("files")||!finalRoots.containsKey("managed"))throw new IOException("Invalid restore destinations");
        if(maxBytes<0||maxBytes>MAX_BYTES||reserve<0||maxEntries<2)throw new IOException("Invalid session extraction limits");
        boolean success=false;Summary stats=new Summary();List<Node> directories=new ArrayList<>(),links=new ArrayList<>();Map<String,Integer> paths=new HashMap<>();byte[] buffer=new byte[262144];long[] report={0};
        try(ZipInputStream zip=new ZipInputStream(new BufferedInputStream(source,262144))){
            byte[] rawHeader=entry(zip,HEADER,MAX_METADATA+4*MAX_TEXT+128);Header header=header(rawHeader);
            ZipEntry data=zip.getNextEntry();if(data==null||!DATA.equals(data.getName())||data.isDirectory())throw new IOException("Missing complete session payload");
            MessageDigest manifest=digest();DataInputStream in=new DataInputStream(new DigestInputStream(zip,manifest));
            while(true){
                SafeZip.checkCancelled();int type=in.readUnsignedByte();if(type==0)break;
                if(type<DIRECTORY||type>HARDLINK)throw new IOException("Unsupported session object");
                Node node=new Node();node.type=type;node.name=safe(text(in));node.mode=in.readInt();node.mtime=in.readLong();node.path=target(node.name,stagingRoots);
                if(node.mode<0||node.mode>0777||++stats.entries>maxEntries||paths.put(node.name,type)!=null)throw new IOException("Duplicate or invalid session object");
                int slash=node.name.lastIndexOf('/');if(slash<0){if(type!=DIRECTORY)throw new IOException("Session root is not a directory");}
                else if(!Integer.valueOf(DIRECTORY).equals(paths.get(node.name.substring(0,slash))))throw new IOException("Session parent is not a directory");
                if(type==DIRECTORY){if(slash>=0)Files.createDirectory(node.path);directories.add(node);stats.directories++;}
                else if(type==REGULAR){
                    node.size=in.readLong();if(node.size<0||node.size>maxBytes-stats.bytes)throw new IOException("Session exceeds restored size limit");
                    if(node.path.getParent().toFile().getUsableSpace()-reserve<node.size)throw new IOException("Not enough storage to restore this session");
                    MessageDigest hash=digest();long remaining=node.size;
                    try(OutputStream out=Files.newOutputStream(node.path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){while(remaining>0){SafeZip.checkCancelled();int n=in.read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new EOFException("Truncated session file");if(node.path.getParent().toFile().getUsableSpace()-reserve<n)throw new IOException("Not enough free storage during restore");out.write(buffer,0,n);hash.update(buffer,0,n);remaining-=n;stats.bytes+=n;report(stats,report,progress,"Restoring complete session");}}
                    byte[] expected=new byte[32];in.readFully(expected);if(!MessageDigest.isEqual(hash.digest(),expected))throw new IOException("Session file checksum failed: "+node.name);
                    restoreAttributes(node);stats.files++;
                }else{
                    node.link=text(in);if(type==HARDLINK){safe(node.link);if(!Integer.valueOf(REGULAR).equals(paths.get(node.link)))throw new IOException("Invalid session hardlink");stats.hardlinks++;}
                    else{node.link=relocate(node.name,node.link,header,finalRoots);stats.symlinks++;}links.add(node);
                }
            }
            if(in.read()!=-1)throw new IOException("Unexpected data after session payload");byte[] payloadHash=manifest.digest();zip.closeEntry();
            byte[] complete=entry(zip,FOOTER,128);DataInputStream footer=new DataInputStream(new ByteArrayInputStream(complete));
            if(footer.readInt()!=MAGIC||footer.readInt()!=VERSION||footer.readLong()!=stats.entries||footer.readLong()!=stats.bytes)throw new IOException("Session completion record mismatch");
            byte[] expectedHeader=new byte[32],expectedPayload=new byte[32];footer.readFully(expectedHeader);footer.readFully(expectedPayload);
            if(footer.read()!=-1||!MessageDigest.isEqual(expectedHeader,digest().digest(rawHeader))||!MessageDigest.isEqual(expectedPayload,payloadHash))throw new IOException("Session manifest checksum failed");
            if(zip.getNextEntry()!=null||!Integer.valueOf(DIRECTORY).equals(paths.get("files"))||!Integer.valueOf(DIRECTORY).equals(paths.get("managed")))throw new IOException("Incomplete or unexpected session records");
            // Every parent is an actual directory created above. Hardlinks can only reference verified regular files.
            for(Node node:links)if(node.type==HARDLINK){SafeZip.checkCancelled();Files.createLink(node.path,target(node.link,stagingRoots));}
            for(Node node:links)if(node.type==SYMLINK){SafeZip.checkCancelled();Files.createSymbolicLink(node.path,Paths.get(node.link));}
            for(int i=directories.size()-1;i>=0;i--){Node node=directories.get(i);restoreAttributes(node);}
            progress.update("Complete session verified · "+stats);success=true;return new Result(header.metadata,stats,header.roots);
        }finally{if(!success)for(File root:stagingRoots.values())try{clear(root.toPath());}catch(IOException ignored){}}
    }
    private static void restoreAttributes(Node node)throws IOException{
        boolean managed=node.name.equals("managed")||node.name.startsWith("managed/");
        try{permissions(node.path,node.mode);}catch(IOException|UnsupportedOperationException e){if(!managed)throw e;}
        try{Files.setLastModifiedTime(node.path,FileTime.fromMillis(node.mtime));}catch(IOException|UnsupportedOperationException e){if(!managed)throw e;}
    }
    private static void clear(Path root)throws IOException{
        permissions(root,0700);for(File child:FilesEx.children(root.toFile()))remove(child.toPath());
    }
    private static void remove(Path path)throws IOException{
        if(Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)){permissions(path,0700);for(File child:FilesEx.children(path.toFile()))remove(child.toPath());}Files.deleteIfExists(path);
    }
}
