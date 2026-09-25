package io.github.russianranger.lsb;

import android.content.Context;
import android.system.Os;
import io.github.russianranger.lsb.core.*;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.util.*;

/** Read-only browser plus explicit removal of app-owned, inactive retained copies. */
final class StorageBackups {
    private static final LinkOption[] NOFOLLOW={LinkOption.NOFOLLOW_LINKS};
    private static final String UUID_PATTERN="[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String TRASH=".backup-delete-";
    static final class Item {
        final String id,label,path;
        String detail;
        final boolean deletable,directory;
        long bytes,files,directories,links,modified;
        private final Path root,target;
        private final String pointerKind,pointerValue;
        private final List<Path> related=new ArrayList<>();
        Item(String id,String label,String detail,Path root,Path target,boolean deletable,String pointerKind,String pointerValue)throws IOException {
            this.id=id;this.label=label;this.root=root;this.target=target;this.path=root.getFileName()+"/"+root.relativize(target);
            this.directory=Files.isDirectory(target,NOFOLLOW);
            boolean linked=Files.isSymbolicLink(target);
            this.deletable=deletable&&!linked;
            this.detail=linked?"Protected: this location is a symbolic link; its target is never browsed or deleted.":detail;
            this.pointerKind=pointerKind;this.pointerValue=pointerValue;
            modified=Files.getLastModifiedTime(target,NOFOLLOW).toMillis();
        }
    }
    static final class Node {
        final String name,path;
        final boolean directory,link;
        final long bytes,modified;
        Node(Path root,Path target)throws IOException {
            BasicFileAttributes a=Files.readAttributes(target,BasicFileAttributes.class,NOFOLLOW);
            name=target.getFileName().toString();path=root.relativize(target).toString();directory=a.isDirectory();link=a.isSymbolicLink();
            bytes=a.isRegularFile()?a.size():0;modified=a.lastModifiedTime().toMillis();
        }
    }
    private final Path files,managed,work;
    StorageBackups(File files,File managed,File work)throws IOException {
        this.files=files.getCanonicalFile().toPath();this.managed=managed.getCanonicalFile().toPath();this.work=work.getCanonicalFile().toPath();
    }
    private static StorageBackups at(Context c)throws IOException {
        return new StorageBackups(c.getFilesDir(),MainActivity.storage(c),new File(c.getNoBackupFilesDir(),"session-transfer"));
    }
    static List<Item> inventory(Context c,SafeZip.Progress progress)throws Exception {return at(c).inventory(progress);}
    static List<Node> children(Context c,String id,String relative)throws Exception {return at(c).children(id,relative);}
    static String delete(Context c,String id,SafeZip.Progress progress)throws Exception {
        // WorkService serializes this with import/restore and prevents a new client launch.
        synchronized(WorkService.class){if(!WorkService.busy)throw new IOException("Start backup removal as a maintenance operation");}
        idle(c);
        try(AutoCloseable reserved=ServerRuntime.get(c).reserveSession(false,progress)) {
            StorageBackups store=at(c);store.checkRecovery();return store.delete(id,progress);
        }
    }
    private static void idle(Context c)throws Exception {
        if(SessionBackup.active||!SessionBackup.recoveryError.isEmpty()||ClientRuntime.get(c).alive()||ServerRuntime.get(c).alive())
            throw new IOException("Stop the client and server and finish session recovery before removing backups");
        File[] processes=new File("/proc").listFiles();
        if(processes!=null)for(File process:processes)try {
            int pid=Integer.parseInt(process.getName());
            if(pid==android.os.Process.myPid()||Os.stat(process.getPath()).st_uid!=android.os.Process.myUid())continue;
            String env=new String(Files.readAllBytes(new File(process,"environ").toPath()),StandardCharsets.UTF_8);
            if(env.contains("LSB_RUNTIME_OWNER="+new File(c.getFilesDir(),"rt").getPath()+"\0")||env.contains("LSB_SERVER_OWNER="+new File(c.getFilesDir(),"server-runtime").getPath()+"\0"))throw new OwnedProcess();
        }catch(OwnedProcess e){throw new IOException("An earlier runtime process is still active. Stop both runtimes or restart LSB before removing backups.");}
        catch(Exception ignored){}
    }
    private static final class OwnedProcess extends Exception {}
    private void checkRecovery()throws IOException {
        if(exists(work.resolve("transaction.properties"))||exists(work.resolve("transaction.properties.tmp"))||exists(files.resolve("session-restore-settings.json")))
            throw new IOException("A complete session restore needs recovery before removing backups");
    }
    List<Item> inventory(SafeZip.Progress progress)throws Exception {
        List<Item> items=catalog();
        for(Item item:items){SafeZip.checkCancelled();progress.update("Measuring "+item.label+"…");measure(item);}
        return items;
    }
    /** Catalog policy is deliberately independent of an old UI inventory snapshot. */
    private List<Item> catalog()throws Exception {
        SafeZip.checkCancelled();checkRecovery();List<Item> items=new ArrayList<>();
        Path clients=files.resolve("rt/clients");requireParents(files,clients.resolve("state.properties"));Properties prepared=properties(clients.resolve("state.properties"));
        boolean selectedPrepared=exists(clients.resolve("state.properties"));validateIds(prepared,"current","previous","candidate");
        String activePrepared=prepared.getProperty("current","");
        if(!activePrepared.isEmpty())selectedPrepared&=regular(files,"rt/clients/"+activePrepared+"/copy-complete")&&regular(files,"rt/clients/"+activePrepared+"/initialization-passed.json");
        for(Path generation:childrenOf(files,clients)) {
            String id=generation.getFileName().toString();if(!id.matches(UUID_PATTERN))continue;
            boolean current=id.equals(prepared.getProperty("current")),candidate=id.equals(prepared.getProperty("candidate")),previous=id.equals(prepared.getProperty("previous"));
            String detail=current?"Protected: your active prepared client and its Windows environment.":candidate?"Protected: a staged client operation. Finish or discard it from the Client tab.":previous?"Recovery copy of the prepared client. Deleting it removes Restore previous preparation for this copy.":"Unused prepared client generation retained by an earlier operation.";
            if(!selectedPrepared)detail="Protected: client selection metadata is missing or incomplete; repair the selection before deleting generations.";
            add(items,"client:"+id,current?"Active prepared client":candidate?"Staged prepared client":previous?"Client recovery copy":"Older client copy "+shortId(id),detail,files,generation,selectedPrepared&&!current&&!candidate,previous?"prepared":"",id);
        }
        Path fex=files.resolve("rt/fex/prefixes");
        for(Path runtime:childrenOf(files,fex))if(runtime.getFileName().toString().matches("[0-9a-f]{64}"))for(Path prefix:childrenOf(files,runtime)) {
            String id=prefix.getFileName().toString();if(!id.matches(UUID_PATTERN+"|probe"))continue;
            Item paired=null;for(Item item:items)if(item.id.equals("client:"+id)&&item.deletable){paired=item;break;}
            if(paired!=null){paired.related.add(prefix);paired.detail+=" Includes the matching FEX Windows environment ("+shortId(runtime.getFileName().toString())+").";continue;}
            boolean retained=id.equals("probe")||!selectedPrepared||id.equals(prepared.getProperty("current"))||id.equals(prepared.getProperty("candidate"))||id.equals(prepared.getProperty("previous"));
            add(items,"fex:"+runtime.getFileName()+":"+id,retained?"Retained FEX Windows environment":"Unused FEX Windows environment "+shortId(id),retained?"Protected: used by Windows checks or a current, staged or recovery client. Matching FEX environments are included when deleting their client recovery copy.":"Windows environment for a client generation that is no longer selected. Removing it keeps the installed FEX runtime.",files,prefix,!retained,"","");
        }
        Path state=files.resolve("server-runtime/state"),pointer=state.resolve("active.json");
        requireParents(files,pointer);JSONObject server=json(pointer);String current=serverId(server,"current"),previous=serverId(server,"previous");
        boolean selectedServer=exists(pointer)&&!current.isEmpty()&&regular(files,"server-runtime/state/generations/"+current+"/deployment.json");
        for(Path generation:childrenOf(files,state.resolve("generations"))) {
            String id=generation.getFileName().toString();if(!id.matches(UUID_PATTERN))continue;
            boolean active=id.equals(current),rollback=id.equals(previous);
            String detail=active?"Protected: your active server and database.":rollback?"Recovery server and database pair. Deleting it removes rollback to this deployment and its saved character progress.":"Unused server/database generation from an earlier deployment or failed staging operation.";
            if(!selectedServer)detail="Protected: server selection metadata is missing or incomplete; complete deployment before removing generations.";
            add(items,"server:"+id,active?"Active server and database":rollback?"Server and database recovery copy":"Older server/database copy "+shortId(id),detail,files,generation,selectedServer&&!active,rollback?"server":"",id);
        }
        fixed(items,"client-import","Current imported client","Protected: source used to prepare clients and export launch packages.",managed,"session/current",false);
        boolean importHealthy=directory(managed,"session/current/client")&&!exists(managed.resolve("session/swap"));
        fixed(items,"client-import-previous","Previous imported client",importHealthy?"Previous imported source. Deleting it removes Switch to previous client for this import.":"Protected: the current import or an interrupted swap needs recovery.",managed,"session/previous",importHealthy);
        fixed(items,"server-source","Current imported server source","Protected: selected source used for deployment and compilation.",managed,"server/current",false);
        boolean sourceHealthy=regular(managed,"server/current/source-report.txt");
        fixed(items,"server-source-previous","Previous server source",sourceHealthy?"Earlier imported source snapshot. The selected source and active server/database stay in place.":"Protected: the selected server source needs recovery.",managed,"server/previous",sourceHealthy);
        fixed(items,"loader-previous","Previous xiloader","Saved loader from a prior loader import. Keep an external backup if you may need this version.",managed,"session/previous-xiloader.exe",true);
        fixed(items,"sql-import","Imported database dump","The staged SQL import. Deleting it keeps deployed databases; importing or deploying from this dump again requires selecting the original file.",files,"server-runtime/state/import.sql",true);
        fixed(items,"sql-export","Local database backup dump","SQL copy from a completed backup. Your deployed database is separate; the next export creates a fresh dump.",files,"server-runtime/state/export.sql",true);
        for(Path prefix:childrenOf(files,files.resolve("rt/prefix-backups")))if(prefix.getFileName().toString().matches("prefix-[0-9]+"))
            add(items,"prefix:"+prefix.getFileName(),"Saved Windows test environment", "A prior test prefix saved by Create fresh Box64 test prefix. Deleting it keeps prepared clients and the current test environment.",files,prefix,true,"","");
        fixed(items,"client-runtime","Installed client runtime","Protected: required for client launch.",files,"rt/root",false);
        fixed(items,"server-runtime","Installed server runtime","Protected: required for the managed server.",files,"server-runtime/rootfs",false);
        fixed(items,"client-runtime-previous","Previous client runtime",regular(files,"rt/root/lsb-runtime.sha256")?"Previous runtime retained after installation. The installed client runtime is kept.":"Protected: the current runtime needs recovery.",files,"rt/root.previous",regular(files,"rt/root/lsb-runtime.sha256"));
        fixed(items,"client-archive","Client runtime download cache","Downloaded install archive. Installed runtimes are separate; a future install can download it again.",files,"rt/runtime.tar.gz",true);
        fixed(items,"fex-archive","FEX download cache","Downloaded FEX archive. The installed runtime is separate.",files,"rt/fex/download.tar.gz",true);
        fixed(items,"server-archive","Server runtime download cache","Downloaded Ubuntu archive. The installed server runtime is separate.",files,"server-runtime/ubuntu-base.tar.gz",true);
        // An interruption after quarantine leaves only disposable names; never recovery roots/journals.
        for(Path parent:Arrays.asList(clients,state.resolve("generations"),managed.resolve("session"),managed.resolve("server"),files.resolve("rt"),files.resolve("rt/fex"),files.resolve("server-runtime"),state,files.resolve("rt/prefix-backups")))
            trash(items,parent.startsWith(managed)?managed:files,parent);
        for(Path runtime:childrenOf(files,fex))if(runtime.getFileName().toString().matches("[0-9a-f]{64}"))trash(items,files,runtime);
        return items;
    }
    private void trash(List<Item> items,Path root,Path parent)throws IOException {
        for(Path path:childrenOf(root,parent))if(path.getFileName().toString().matches("\\.backup-delete-"+UUID_PATTERN))
            add(items,"cleanup:"+root.getFileName()+":"+root.relativize(path),"Interrupted backup removal","An already deselected backup left by interrupted removal. Removing it completes cleanup.",root,path,true,"","");
    }
    private static void fixed(List<Item> items,String id,String label,String detail,Path root,String relative,boolean deletable)throws IOException {add(items,id,label,detail,root,root.resolve(relative),deletable,"","");}
    private static void add(List<Item> items,String id,String label,String detail,Path root,Path target,boolean deletable,String pointer,String value)throws IOException {
        requireParents(root,target);if(exists(target))items.add(new Item(id,label,detail,root,target,deletable,pointer,value));
    }
    private static boolean regular(Path root,String relative)throws IOException {Path path=root.resolve(relative);requireParents(root,path);return Files.isRegularFile(path,NOFOLLOW);}
    private static boolean directory(Path root,String relative)throws IOException {Path path=root.resolve(relative);requireParents(root,path);return Files.isDirectory(path,NOFOLLOW);}
    private static List<Path> childrenOf(Path root,Path path)throws IOException {
        SafeZip.checkCancelled();requireParents(root,path);if(!exists(path)||Files.isSymbolicLink(path))return Collections.emptyList();
        if(!Files.isDirectory(path,NOFOLLOW))throw new IOException("Expected a backup directory: "+path.getFileName());
        List<Path> result=new ArrayList<>();try(DirectoryStream<Path> entries=Files.newDirectoryStream(path)){for(Path entry:entries){SafeZip.checkCancelled();result.add(entry);}}
        Collections.sort(result);return result;
    }
    private static void measure(Item item)throws IOException {
        requireParents(item.root,item.target);
        List<Path> targets=new ArrayList<>();targets.add(item.target);targets.addAll(item.related);
        for(Path target:targets){requireParents(item.root,target);Files.walkFileTree(target,EnumSet.noneOf(FileVisitOption.class),256,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs)throws IOException {SafeZip.checkCancelled();item.directories++;return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws IOException {
                SafeZip.checkCancelled();if(attrs.isSymbolicLink())item.links++;else if(attrs.isRegularFile()){item.files++;item.bytes=Math.addExact(item.bytes,attrs.size());}return FileVisitResult.CONTINUE;
            }
        });}
    }
    List<Node> children(String id,String relative)throws Exception {
        Item item=find(id);Path path=item.target;
        if(relative==null)throw new IOException("Missing browser path");
        if(!relative.isEmpty()) {
            Path r=Paths.get(relative);if(r.isAbsolute())throw new IOException("Invalid browser path");
            for(Path part:r)if(part.toString().equals("..")||part.toString().equals("."))throw new IOException("Invalid browser path");
            path=path.resolve(r).normalize();
        }
        if(!path.startsWith(item.target))throw new IOException("Invalid browser path");
        requireParents(item.root,path);if(Files.isSymbolicLink(path)||!Files.isDirectory(path,NOFOLLOW))throw new IOException("Choose a real directory; symbolic links are not opened");
        List<Node> nodes=new ArrayList<>();try(DirectoryStream<Path> entries=Files.newDirectoryStream(path)){for(Path entry:entries){SafeZip.checkCancelled();nodes.add(new Node(item.target,entry));}}
        Collections.sort(nodes,(a,b)->a.directory!=b.directory?(a.directory?-1:1):a.name.compareToIgnoreCase(b.name));return nodes;
    }
    private Item find(String id)throws Exception {
        for(Item item:catalog())if(item.id.equals(id))return item;
        throw new IOException("This retained copy is no longer available. Refresh the backup browser.");
    }
    String delete(String id,SafeZip.Progress progress)throws Exception {
        checkRecovery();Item item=find(id);if(!item.deletable)throw new IOException("This item is protected: "+item.label);
        requireParents(item.root,item.target);if(Files.isSymbolicLink(item.target))throw new IOException("Backup location became a symbolic link; refresh the browser");
        SafeZip.checkCancelled();
        // First remove rollback selection atomically. A crash can leave an unused full copy,
        // never a pointer selecting a partially removed client or database.
        if(item.pointerKind.equals("prepared")) {
            Path state=files.resolve("rt/clients/state.properties");Properties p=properties(state);
            if(!item.pointerValue.equals(p.getProperty("previous"))||item.pointerValue.equals(p.getProperty("current"))||item.pointerValue.equals(p.getProperty("candidate")))throw new IOException("Client selection changed. Refresh the browser.");
            p.remove("previous");StringWriter out=new StringWriter();p.store(out,"Retained client selection");atomic(state,out.toString());
        }else if(item.pointerKind.equals("server")) {
            Path state=files.resolve("server-runtime/state/active.json");JSONObject p=json(state);
            if(!item.pointerValue.equals(serverId(p,"previous"))||item.pointerValue.equals(serverId(p,"current")))throw new IOException("Server selection changed. Refresh the browser.");
            p.remove("previous");atomic(state,p.toString());
        }
        List<Path> targets=new ArrayList<>();targets.add(item.target);targets.addAll(item.related);
        final long[] count={0};progress.update("Removing "+item.label+"…");
        for(Path original:targets) {
        Path target=original;requireParents(item.root,target);
        if(!target.getFileName().toString().startsWith(TRASH)) {
            Path quarantine=target.resolveSibling(TRASH+java.util.UUID.randomUUID());
            Files.move(target,quarantine,StandardCopyOption.ATOMIC_MOVE);syncDirectory(target.getParent());target=quarantine;
        }
        Files.walkFileTree(target,EnumSet.noneOf(FileVisitOption.class),256,new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs)throws IOException {SafeZip.checkCancelled();requireParents(item.root,path);makeWritable(path);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws IOException {SafeZip.checkCancelled();requireParents(item.root,path);Files.delete(path);report();return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path path,IOException error)throws IOException {if(error!=null)throw error;SafeZip.checkCancelled();requireParents(item.root,path);Files.delete(path);report();return FileVisitResult.CONTINUE;}
            private void report(){if(++count[0]%1000==0)progress.update("Removing "+item.label+" · "+count[0]+" entries");}
        });}
        return item.label+" deleted. Active clients, runtimes and deployed database retained.";
    }
    private static void makeWritable(Path path)throws IOException {
        // A copied package may preserve read-only modes. Never chmod a symlink target.
        if(Files.isSymbolicLink(path))throw new IOException("Directory changed during removal");
        try {
            Set<PosixFilePermission> mode=new HashSet<>(Files.getPosixFilePermissions(path,NOFOLLOW));
            mode.add(PosixFilePermission.OWNER_READ);mode.add(PosixFilePermission.OWNER_WRITE);mode.add(PosixFilePermission.OWNER_EXECUTE);Files.setPosixFilePermissions(path,mode);
        } catch(IOException|UnsupportedOperationException unsupported) {
            File directory=path.toFile();if(!directory.canRead())directory.setReadable(true,true);if(!directory.canWrite())directory.setWritable(true,true);if(!directory.canExecute())directory.setExecutable(true,true);
        }
    }
    private static Properties properties(Path path)throws IOException {
        Properties p=new Properties();if(!exists(path))return p;
        requireRegular(path);try(Reader reader=new StringReader(FilesEx.read(path.toFile(),131072))){p.load(reader);}return p;
    }
    private static JSONObject json(Path path)throws Exception {if(!exists(path))return new JSONObject();requireRegular(path);return new JSONObject(FilesEx.read(path.toFile(),16384));}
    private static void validateIds(Properties p,String... keys)throws IOException {for(String key:keys){String id=p.getProperty(key,"");if(!id.isEmpty()&&!id.matches(UUID_PATTERN))throw new IOException("Invalid client selection metadata");}}
    private static String serverId(JSONObject p,String key)throws Exception {if(!p.has(key)||p.isNull(key))return "";Object value=p.get(key);if(!(value instanceof String)||(!((String)value).matches(UUID_PATTERN)&&!(key.equals("previous")&&value.equals(""))))throw new IOException("Invalid server selection metadata");return (String)value;}
    private static void requireRegular(Path path)throws IOException {if(!Files.isRegularFile(path,NOFOLLOW))throw new IOException("Invalid backup selection metadata");}
    private static void atomic(Path path,String value)throws IOException {
        Path tmp=path.resolveSibling(path.getFileName()+".backup-"+java.util.UUID.randomUUID());
        try {
            try(OutputStream out=Files.newOutputStream(tmp,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)){out.write(value.getBytes(StandardCharsets.UTF_8));}
            try(RandomAccessFile out=new RandomAccessFile(tmp.toFile(),"rw")){out.getFD().sync();}
            Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);syncDirectory(path.getParent());
        }finally{Files.deleteIfExists(tmp);}
    }
    private static void syncDirectory(Path path) {
        try(FileChannel channel=FileChannel.open(path,StandardOpenOption.READ)){channel.force(true);}
        catch(IOException|UnsupportedOperationException ignored){}
    }
    private static void requireParents(Path root,Path target)throws IOException {
        Path normalized=target.toAbsolutePath().normalize();if(!normalized.startsWith(root)||normalized.equals(root))throw new IOException("Backup path is outside its app storage");
        Path parent=normalized.getParent(),cursor=root;
        if(exists(root)&&!Files.isDirectory(root,NOFOLLOW))throw new IOException("App storage root is not a directory");
        for(Path part:root.relativize(parent)){cursor=cursor.resolve(part);if(exists(cursor)&&!Files.isDirectory(cursor,NOFOLLOW))throw new IOException("Backup path crosses a symbolic link or non-directory");}
    }
    private static String shortId(String id){return id.substring(0,Math.min(8,id.length()));}
    private static boolean exists(Path path){return Files.exists(path,NOFOLLOW);}
}
