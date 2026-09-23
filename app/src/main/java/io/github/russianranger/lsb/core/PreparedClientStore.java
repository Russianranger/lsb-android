package io.github.russianranger.lsb.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Separate writable generations. One atomic pointer file activates client + prefix together. */
public final class PreparedClientStore {
    private final File home;
    public PreparedClientStore(File home) throws IOException { this.home=home; FilesEx.mkdir(home); }
    private Properties load(File file) throws IOException {
        Properties p=new Properties(); if(file.isFile())try(Reader r=new StringReader(FilesEx.read(file,131072))){p.load(r);} return p;
    }
    private void save(File file,Properties p) throws IOException {
        StringWriter w=new StringWriter();p.store(w,null);
        File tmp=new File(file.getPath()+".new");FilesEx.text(tmp,w.toString());
        Files.move(tmp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    public Properties state() throws IOException { return load(new File(home,"state.properties")); }
    public File generation(String id) throws IOException {
        if(id==null||!id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IOException("Invalid prepared generation");
        return new File(home,id);
    }
    public File selected(String key) throws IOException {
        String id=state().getProperty(key,"");return id.isEmpty()?null:generation(id);
    }
    public Properties metadata(File gen) throws IOException { return load(new File(gen,"metadata.properties")); }
    public boolean complete(File gen) { return gen!=null&&new File(gen,"copy-complete").isFile(); }
    public static String relative(File root,File file) throws IOException { return root.equals(file)?".":FilesEx.relative(root,file); }
    public static long size(File file,boolean links,int depth) throws IOException {
        SafeZip.checkCancelled();if(depth>80)throw new IOException("Directory nesting exceeds preparation limits");
        if(Files.isSymbolicLink(file.toPath())){if(!links)throw new IOException("Client contains a symbolic link");return 0;}
        if(!Files.isDirectory(file.toPath(),LinkOption.NOFOLLOW_LINKS)){
            if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsupported file in preparation");return file.length();
        }
        long total=0;for(File f:FilesEx.children(file))total=Math.addExact(total,size(f,links,depth+1));return total;
    }
    private static final class Copier {
        long done,files,last; final long total; final SafeZip.Progress progress;
        final byte[] buffer=new byte[1024*1024];
        Copier(long total,SafeZip.Progress progress){this.total=total;this.progress=progress;}
        void copy(File from,File to,boolean links,int depth) throws IOException {
            SafeZip.checkCancelled();if(depth>80)throw new IOException("Preparation nesting limit");
            if(Files.isSymbolicLink(from.toPath())){
                if(!links)throw new IOException("Client contains a symbolic link");
                Files.createSymbolicLink(to.toPath(),Files.readSymbolicLink(from.toPath()));return;
            }
            if(Files.isDirectory(from.toPath(),LinkOption.NOFOLLOW_LINKS)){
                FilesEx.mkdir(to);for(File f:FilesEx.children(from))copy(f,new File(to,f.getName()),links,depth+1);return;
            }
            if(!Files.isRegularFile(from.toPath(),LinkOption.NOFOLLOW_LINKS))throw new IOException("Unsupported preparation file");
            long expected=from.length(),count=0;
            if(to.getParentFile().getUsableSpace()<expected+SafeZip.RESERVE_BYTES)throw new IOException("Not enough free space to finish preparation");
            try(InputStream in=Files.newInputStream(from.toPath(),LinkOption.NOFOLLOW_LINKS);OutputStream out=new FileOutputStream(to)){
                int n;while((n=in.read(buffer))!=-1){SafeZip.checkCancelled();out.write(buffer,0,n);count+=n;done+=n;
                    if(System.currentTimeMillis()-last>500){progress.update("Copying working installation · "+done/1048576+" / "+total/1048576+" MiB");last=System.currentTimeMillis();}}
            }
            if(count!=expected||from.length()!=expected)throw new IOException("Source changed while preparing "+from.getName());
            if(from.canExecute())to.setExecutable(true,true);files++;
        }
    }
    public static void copyRuntimePrefix(File source,File destination,SafeZip.Progress progress)throws IOException {
        if(Files.isSymbolicLink(source.toPath())||destination.exists()||destination.getCanonicalFile().toPath().startsWith(source.getCanonicalFile().toPath()))throw new IOException("Runtime copy must use a new directory");
        long bytes=size(source,true,0);
        if(destination.getParentFile().getUsableSpace()<bytes+SafeZip.RESERVE_BYTES)throw new IOException("Not enough space for a separate Windows environment");
        new Copier(bytes,progress).copy(source,destination,true,0);
    }
    public File prepare(ClientStore imported,File seedPrefix,SafeZip.Progress progress) throws Exception {
        File existing=selected("candidate");
        if(complete(existing)){progress.update("Reusing staged client; retrying initialization");return existing;}
        if(existing!=null)discard();
        if(imported.hasPendingImport())throw new IOException("Finish the pending import first");
        ClientInspector.Snapshot source=ClientInspector.inspect(imported.client(),imported.config().polCore,progress);
        if(!source.warning().isEmpty())throw new IOException(source.warning());
        LaunchConfig config=imported.config();
        if(config.region.equals("EU")!=source.polDll.equalsIgnoreCase("polcoreeu.dll"))throw new IOException("PlayOnline region does not match selected DLL");
        if(!new File(seedPrefix,"lsb-prefix-ready.json").isFile())throw new IOException("Complete Windows checks once before preparing the client");
        long bytes=Math.addExact(source.bytes,size(seedPrefix,true,0));
        if(home.getUsableSpace()<bytes+2L*1024*1024*1024)throw new IOException("Preparation needs "+((bytes+2L*1024*1024*1024+1073741823)/1073741824)+" GiB free for a working client and Windows prefix");
        String id=UUID.randomUUID().toString();File gen=generation(id);FilesEx.mkdir(gen);
        Properties state=state();state.setProperty("candidate",id);save(new File(home,"state.properties"),state);
        Properties meta=new Properties();meta.setProperty("format","1");meta.setProperty("generation",id);meta.setProperty("region",config.region);
        meta.setProperty("pol",relative(source.root,source.pol));meta.setProperty("core",source.polCore);meta.setProperty("game",relative(source.root,source.game));
        meta.setProperty("loader",source.loader==null?"":relative(source.root,source.loader));
        meta.setProperty("files",Long.toString(source.files));meta.setProperty("bytes",Long.toString(source.bytes));
        FilesEx.text(new File(gen,"source-inventory.json"),source.inventory);
        meta.setProperty("inventorySha256",FilesEx.hash(new File(gen,"source-inventory.json")));save(new File(gen,"metadata.properties"),meta);
        Copier copier=new Copier(bytes,progress);copier.copy(source.root,new File(gen,"client"),false,0);copier.copy(seedPrefix,new File(gen,"prefix"),true,0);
        ClientInspector.Snapshot copied=ClientInspector.inspect(new File(gen,"client"),source.polCore,progress);
        if(!source.inventory.equals(copied.inventory))throw new IOException("Working copy did not match source inventory");
        FilesEx.text(new File(gen,"copy-complete"),"1\n");return gen;
    }
    /** Repair the activated generation, preserving its user files as well as the prefix. */
    public File stageRepair(SafeZip.Progress progress)throws Exception {
        File existing=selected("candidate");
        if(existing!=null){
            Properties m=metadata(existing);
            File active=selected("current");
            if(active==null||!active.getName().equals(m.getProperty("repairOf")))throw new IOException("Discard the unrelated staged preparation before repairing this launcher");
            if(complete(existing))return existing;
            discard();
        }
        File current=selected("current");
        if(!complete(current)||!new File(current,"initialization-passed.json").isFile())throw new IOException("Prepare the client first");
        long bytes=Math.addExact(size(new File(current,"client"),false,0),size(new File(current,"prefix"),true,0));
        if(home.getUsableSpace()<bytes+2L*1073741824L)throw new IOException("Repair needs space for a full recovery copy ("+((bytes+3L*1073741824L-1)/1073741824L)+" GiB)");
        File gen=generation(UUID.randomUUID().toString());FilesEx.mkdir(gen);
        Properties m=metadata(current);m.setProperty("generation",gen.getName());m.setProperty("repairOf",current.getName());save(new File(gen,"metadata.properties"),m);
        Properties s=state();s.setProperty("candidate",gen.getName());save(new File(home,"state.properties"),s);
        Files.copy(new File(current,"source-inventory.json").toPath(),new File(gen,"source-inventory.json").toPath());
        Copier copier=new Copier(bytes,progress);copier.copy(new File(current,"client"),new File(gen,"client"),false,0);copier.copy(new File(current,"prefix"),new File(gen,"prefix"),true,0);
        FilesEx.text(new File(gen,"copy-complete"),"1\n");return gen;
    }
    public void promote(File gen) throws IOException {
        Properties state=state();String id=gen.getName();
        if(!id.equals(state.getProperty("candidate"))||!complete(gen)||!new File(gen,"initialization-passed.json").isFile())throw new IOException("Candidate is not validated");
        String current=state.getProperty("current","");if(!current.isEmpty())state.setProperty("previous",current);
        state.setProperty("current",id);state.remove("candidate");save(new File(home,"state.properties"),state);
    }
    public void rollback() throws IOException {
        Properties s=state();String previous=s.getProperty("previous","");File gen=generation(previous);
        if(!complete(gen)||!new File(gen,"initialization-passed.json").isFile())throw new IOException("No validated previous preparation");
        String current=s.getProperty("current","");s.setProperty("current",previous);s.setProperty("previous",current);save(new File(home,"state.properties"),s);
    }
    public void discard() throws IOException {
        Properties s=state();String id=s.getProperty("candidate","");if(id.isEmpty())return;
        if(id.equals(s.getProperty("current"))||id.equals(s.getProperty("previous")))throw new IOException("Cannot discard an active generation");
        remove(generation(id));s.remove("candidate");save(new File(home,"state.properties"),s);
    }
    private static void remove(File f) throws IOException {
        if(Files.isDirectory(f.toPath(),LinkOption.NOFOLLOW_LINKS))for(File child:FilesEx.children(f))remove(child);
        Files.deleteIfExists(f.toPath());
    }
}
