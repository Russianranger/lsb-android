package io.github.russianranger.lsb;

import android.app.usage.StorageStats;
import android.app.usage.StorageStatsManager;
import android.content.Context;
import android.os.storage.StorageManager;
import android.system.Os;
import android.system.StructStat;
import io.github.russianranger.lsb.core.SafeZip;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Complete, read-only app-data accounting. Never contributes deletion targets. */
final class AppStorage {
    private static final LinkOption[] NOFOLLOW={LinkOption.NOFOLLOW_LINKS};
    static final class Root {
        final String id,label; final Path path;
        Root(String id,String label,File path)throws IOException {this.id=id;this.label=label;this.path=path.getCanonicalFile().toPath();}
    }
    static final class Entry {
        final String id,label; final Path root,path; final boolean directory,link;
        long bytes,allocated,files,links,errors; boolean allocationKnown=true;
        Entry(Root root,Path path)throws IOException {
            this.root=root.path;this.path=path;id=root.id+":"+root.path.relativize(path);
            label=root.path.equals(path)?root.label:root.label+" / "+path.getFileName();
            directory=Files.isDirectory(path,NOFOLLOW);link=Files.isSymbolicLink(path);
        }
    }
    static final class Report {
        final List<Entry> entries=new ArrayList<>();
        long bytes,allocated,errors,androidData=-1,androidCache=-1,androidCode=-1;
        boolean allocationKnown=true;
    }
    static final class Stat {
        final Object identity; final long allocated;
        Stat(Object identity,long allocated){this.identity=identity;this.allocated=allocated;}
    }
    interface Stats {Stat read(Path path,BasicFileAttributes attrs);}
    private static final Stats DISK=(path,attrs)->{
        try {
            StructStat s=Os.lstat(path.toString());
            if(s!=null&&s.st_ino!=0)return new Stat(s.st_dev+":"+s.st_ino,Math.multiplyExact(s.st_blocks,512L));
        }catch(Exception ignored){}
        return new Stat(attrs.fileKey(),-1);
    };
    private static void guard()throws IOException {
        if(WorkService.busy||SessionBackup.active||!SessionBackup.recoveryError.isEmpty())throw new IOException("Wait for maintenance and complete session recovery before browsing app storage");
    }
    static List<Root> roots(Context c)throws IOException {
        guard();List<Root> roots=new ArrayList<>();
        roots.add(new Root("internal","Internal data",new File(c.getApplicationInfo().dataDir)));
        add(roots,"device","Device-protected data",new File(c.createDeviceProtectedStorageContext().getApplicationInfo().dataDir));
        addAll(roots,"external-files","App external files",c.getExternalFilesDirs(null));
        addAll(roots,"external-cache","App external cache",c.getExternalCacheDirs());
        addAll(roots,"media","App media",c.getExternalMediaDirs());
        addAll(roots,"obb","App expansion files",c.getObbDirs());
        return roots;
    }
    private static void addAll(List<Root> roots,String id,String label,File[] folders)throws IOException {
        if(folders!=null)for(int i=0;i<folders.length;i++)if(folders[i]!=null)add(roots,id+"-"+i,label+(i==0?"":" (volume "+(i+1)+")"),folders[i]);
    }
    private static void add(List<Root> roots,String id,String label,File path)throws IOException {
        Root added=new Root(id,label,path);
        for(Root root:roots)if(added.path.startsWith(root.path))return;
        roots.removeIf(root->root.path.startsWith(added.path));roots.add(added);
    }
    static Report scan(Context c,SafeZip.Progress progress)throws Exception {
        Report report=scan(roots(c),progress,DISK);
        try {
            StorageStatsManager manager=c.getSystemService(StorageStatsManager.class);
            StorageManager volumes=c.getSystemService(StorageManager.class);
            StorageStats stats=manager.queryStatsForUid(volumes.getUuidForPath(new File(c.getApplicationInfo().dataDir)),android.os.Process.myUid());
            report.androidData=stats.getDataBytes();report.androidCache=stats.getCacheBytes();report.androidCode=stats.getAppBytes();
        }catch(Exception ignored){} // Own-UID statistics are optional; never ask for usage access.
        return report;
    }
    static Report scan(List<Root> roots,SafeZip.Progress progress,Stats stats)throws IOException {
        Report report=new Report();Set<Object> seen=new HashSet<>();List<Path> covered=new ArrayList<>();
        List<Root> sorted=new ArrayList<>(roots);sorted.sort(Comparator.comparingInt(root->root.path.getNameCount()));
        for(Root root:sorted) {
            SafeZip.checkCancelled();boolean nested=false;for(Path path:covered)if(root.path.startsWith(path)){nested=true;break;}if(nested)continue;
            covered.add(root.path);if(!Files.exists(root.path,NOFOLLOW))continue;
            // Show top-level folders individually; this includes files, cache, code_cache,
            // no_backup (restore staging), databases and shared_prefs without an allowlist.
            if(Files.isDirectory(root.path,NOFOLLOW)) {
                Entry overhead=new Entry(root,root.path);account(root.path,Files.readAttributes(root.path,BasicFileAttributes.class,NOFOLLOW),overhead,seen,stats);
                report.allocated+=overhead.allocated;report.allocationKnown&=overhead.allocationKnown;
                try(DirectoryStream<Path> children=Files.newDirectoryStream(root.path)) {
                    for(Path path:children) {
                        SafeZip.checkCancelled();Entry entry=new Entry(root,path);progress.update("Measuring "+entry.label+"…");measure(entry,seen,stats,progress);report.entries.add(entry);
                    }
                }catch(IOException error){report.errors++;}
            }else {Entry entry=new Entry(root,root.path);measure(entry,seen,stats,progress);report.entries.add(entry);}
        }
        for(Entry entry:report.entries){report.bytes+=entry.bytes;report.allocated+=entry.allocated;report.errors+=entry.errors;report.allocationKnown&=entry.allocationKnown;}
        report.entries.sort((a,b)->Long.compare(b.bytes,a.bytes));return report;
    }
    private static void measure(Entry entry,Set<Object> seen,Stats stats,SafeZip.Progress progress)throws IOException {
        final long[] count={0};
        Files.walkFileTree(entry.path,EnumSet.noneOf(FileVisitOption.class),256,new SimpleFileVisitor<Path>() {
            private void visit(Path path,BasicFileAttributes attrs)throws IOException {
                SafeZip.checkCancelled();account(path,attrs,entry,seen,stats);
                if(++count[0]%2000==0)progress.update("Measuring "+entry.label+" · "+count[0]+" entries…");
            }
            @Override public FileVisitResult preVisitDirectory(Path path,BasicFileAttributes attrs)throws IOException {visit(path,attrs);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFile(Path path,BasicFileAttributes attrs)throws IOException {visit(path,attrs);return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult visitFileFailed(Path path,IOException error)throws IOException {SafeZip.checkCancelled();entry.errors++;return FileVisitResult.CONTINUE;}
            @Override public FileVisitResult postVisitDirectory(Path path,IOException error)throws IOException {SafeZip.checkCancelled();if(error!=null)entry.errors++;return FileVisitResult.CONTINUE;}
        });
    }
    private static void account(Path path,BasicFileAttributes attrs,Entry entry,Set<Object> seen,Stats stats) {
        Stat stat=stats.read(path,attrs);
        if(attrs.isSymbolicLink())entry.links++;else if(attrs.isRegularFile())entry.files++;
        if(stat.identity!=null&&!seen.add(stat.identity))return; // Count hard-linked data once, across all roots.
        if(attrs.isRegularFile())entry.bytes+=attrs.size();
        if(stat.allocated>=0)entry.allocated+=stat.allocated;else entry.allocationKnown=false;
    }
    static List<StorageBackups.Node> children(Context c,String id,String relative,SafeZip.Progress progress)throws Exception {
        return children(roots(c),id,relative,progress,DISK);
    }
    static List<StorageBackups.Node> children(List<Root> roots,String id,String relative,SafeZip.Progress progress,Stats stats)throws Exception {
        Root selected=null;String base="";
        for(Root root:roots)if(id.startsWith(root.id+":")){selected=root;base=id.substring(root.id.length()+1);break;}
        if(selected==null)throw new IOException("Unknown app storage location");
        Path target=resolve(selected.path,base),path=resolve(target,relative);realParents(selected.path,path);
        if(!Files.isDirectory(path,NOFOLLOW))throw new IOException("Choose a real directory; symbolic links are not opened");
        List<StorageBackups.Node> nodes=new ArrayList<>();
        try(DirectoryStream<Path> children=Files.newDirectoryStream(path)) {
            for(Path child:children) {
                SafeZip.checkCancelled();StorageBackups.Node node=new StorageBackups.Node(target,child);
                if(node.directory&&!node.link){Entry entry=new Entry(selected,child);measure(entry,new HashSet<>(),stats,progress);node.bytes=entry.bytes;node.sizeIncomplete=entry.errors!=0;}
                nodes.add(node);
            }
        }
        nodes.sort((a,b)->a.directory!=b.directory?(a.directory?-1:1):Long.compare(b.bytes,a.bytes));return nodes;
    }
    private static Path resolve(Path root,String relative)throws IOException {
        if(relative==null)throw new IOException("Missing browser path");
        Path part=Paths.get(relative);if(part.isAbsolute())throw new IOException("Invalid browser path");
        for(Path name:part)if(name.toString().equals("..")||name.toString().equals("."))throw new IOException("Invalid browser path");
        Path path=root.resolve(part).normalize();if(!path.startsWith(root))throw new IOException("Invalid browser path");return path;
    }
    private static void realParents(Path root,Path path)throws IOException {
        if(!Files.isDirectory(root,NOFOLLOW))throw new IOException("App storage root is not a real directory");
        Path cursor=root;for(Path part:root.relativize(path)){cursor=cursor.resolve(part);if(Files.isSymbolicLink(cursor))throw new IOException("Symbolic links are not opened");}
    }
}
