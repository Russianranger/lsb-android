package io.github.russianranger.lsb.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class ClientStore {
    private final File home;
    public ClientStore(File home) throws IOException { this.home = home; FilesEx.mkdir(home); }
    public File current() { return new File(home, "current"); }
    public File previous() { return new File(home, "previous"); }
    public File client() { return new File(current(), "client"); }
    public boolean hasClient() { return client().isDirectory(); }
    public boolean hasPrevious() { return new File(previous(), "client").isDirectory(); }
    public LaunchConfig config() throws IOException { return LaunchConfig.load(new File(current(), "session.properties")); }
    public void saveConfig(LaunchConfig config) throws IOException { FilesEx.mkdir(current()); FilesEx.text(new File(current(), "session.properties"), config.properties()); }
    public String summary() throws IOException {
        File f = new File(current(), "summary.txt");
        return hasClient() && f.exists() ? FilesEx.read(f, 8192) : "Import a ZIP containing both PlayOnline and FINAL FANTASY XI. Nested folders are detected automatically.";
    }
    public String inventory() throws IOException {
        File f = new File(current(), "inventory.json"); return f.exists() ? FilesEx.read(f, 32768) : "{\"imported\": false}";
    }
    public void recover() throws IOException {
        File swap = new File(home, "swap");
        // A process may die between any two directory renames during import or rollback.
        if (!current().exists()) {
            if (previous().exists()) FilesEx.move(previous(), current());
            else if (swap.exists()) FilesEx.move(swap, current());
        }
        if (swap.exists() && !previous().exists()) FilesEx.move(swap, previous());
    }
    public void importClient(InputStream in, boolean restore, boolean preserveUser, SafeZip.Progress progress) throws Exception {
        recover();
        File incoming = new File(home, "incoming"); FilesEx.delete(incoming); FilesEx.mkdir(incoming);
        boolean promoted = false;
        try {
            File unpack = new File(incoming, "unpack");
            SafeZip.extract(in, unpack, progress);
            LaunchConfig cfg = config();
            File payload = unpack;
            if (restore) {
                File marker = new File(unpack, "backup-format.txt");
                if (!marker.isFile() || !FilesEx.read(marker, 128).trim().equals("lsb-android-session-v1")) throw new IOException("Not an LSB Android session backup");
                payload = new File(unpack, "client");
                if (!payload.isDirectory()) throw new IOException("Backup has no client folder");
                cfg = LaunchConfig.load(new File(unpack, "session.properties"));
            }
            ClientInspector.Snapshot fresh = ClientInspector.inspect(payload, progress);
            if (!restore && preserveUser && hasClient()) {
                ClientInspector.Snapshot old = ClientInspector.inspect(client(), progress);
                preserveDirectory(old.game, fresh.game, "USER", progress);
                preserveDirectory(old.pol, fresh.pol, "usr", progress);
                fresh = ClientInspector.inspect(payload, progress);
            }
            File staged = new File(incoming, "ready"); FilesEx.mkdir(staged);
            FilesEx.move(payload, new File(staged, "client"));
            FilesEx.text(new File(staged, "session.properties"), cfg.properties());
            FilesEx.text(new File(staged, "inventory.json"), fresh.inventory);
            FilesEx.text(new File(staged, "summary.txt"), fresh.summary());
            FilesEx.text(new File(staged, "backup-format.txt"), "lsb-android-session-v1\n");
            SafeZip.checkCancelled();
            promote(staged); promoted = true;
        } finally { try { FilesEx.delete(incoming); } catch (IOException e) { if (!promoted) progress.update("Staging cleanup will retry on the next import"); } }
    }
    private void promote(File staged) throws IOException {
        // Existing validated current remains active until extraction and inspection succeed.
        FilesEx.delete(previous());
        if (current().exists()) FilesEx.move(current(), previous());
        try { FilesEx.move(staged, current()); }
        catch (IOException e) { if (!current().exists() && previous().exists()) FilesEx.move(previous(), current()); throw e; }
    }
    public void rollback(SafeZip.Progress progress) throws Exception {
        if (!hasPrevious() || !new File(previous(), "client").isDirectory()) throw new IOException("No previous client is available");
        ClientInspector.inspect(new File(previous(), "client"), progress);
        File swap = new File(home, "swap");
        if (swap.exists()) throw new IOException("An interrupted rollback needs recovery");
        if (current().exists()) FilesEx.move(current(), swap);
        try { FilesEx.move(previous(), current()); if (swap.exists()) FilesEx.move(swap, previous()); }
        catch (IOException e) { recover(); throw e; }
    }
    public ClientInspector.Snapshot validate(SafeZip.Progress progress) throws Exception {
        if (!hasClient()) throw new IOException("Import a client first");
        ClientInspector.Snapshot s = ClientInspector.inspect(client(), progress);
        FilesEx.text(new File(current(), "summary.txt"), s.summary());
        FilesEx.text(new File(current(), "inventory.json"), s.inventory); return s;
    }
    public void importLoader(InputStream input, SafeZip.Progress progress) throws Exception {
        if (!hasClient()) throw new IOException("Import the client first");
        File temp = new File(home, "xiloader-import.tmp");
        try {
            try (InputStream in = input; OutputStream out = new FileOutputStream(temp)) {
                byte[] buf = new byte[65536]; int n; long size = 0;
                while ((n = in.read(buf)) != -1) { SafeZip.checkCancelled(); size += n; if (size > 64 * 1048576) throw new IOException("xiloader exceeds 64 MiB"); out.write(buf, 0, n); }
            }
            ClientInspector.requireX86(temp);
            ClientInspector.Snapshot s = validate(progress);
            File target = s.loader == null ? new File(s.pol, "xiloader.exe") : s.loader;
            File backup = new File(home, "previous-xiloader.exe");
            if (target.exists()) Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            validate(progress);
        } finally { if (temp.exists()) FilesEx.delete(temp); }
    }
    public void exportBackup(OutputStream out, SafeZip.Progress progress) throws Exception {
        validate(progress);
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out))) { zip.setLevel(1); SafeZip.writeTree(zip, current(), "", progress); }
    }
    public void exportPrepared(OutputStream out, String profile, SafeZip.Progress progress) throws Exception {
        ClientInspector.Snapshot s = validate(progress);
        Map<String, String> scripts = RepairPackage.generate(s, config(), profile);
        if (s.loader == null) throw new IOException("Import xiloader.exe before exporting a launch package");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out))) {
            zip.setLevel(1); for (Map.Entry<String, String> e : scripts.entrySet()) SafeZip.entry(zip, e.getKey(), e.getValue());
            SafeZip.writeTree(zip, client(), "client/", progress);
        }
    }
    public String previewRepair(String profile, SafeZip.Progress progress) throws Exception {
        ClientInspector.Snapshot s = validate(progress);
        return RepairPackage.generate(s, config(), profile).get("repair.cmd");
    }
    private static void preserveDirectory(File oldRoot, File newRoot, String name, SafeZip.Progress progress) throws IOException {
        File from = ClientInspector.child(oldRoot, name);
        if (from == null || !from.isDirectory()) return;
        File to = ClientInspector.child(newRoot, name); if (to == null) to = new File(newRoot, from.getName());
        progress.update("Preserving " + name + " settings"); copyTree(from, to);
    }
    private static void copyTree(File from, File to) throws IOException {
        SafeZip.checkCancelled();
        if (java.nio.file.Files.isSymbolicLink(from.toPath())) throw new IOException("Unexpected link in personal settings");
        if (from.isDirectory()) {
            FilesEx.mkdir(to);
            for (File f : FilesEx.children(from)) { File existing = ClientInspector.child(to, f.getName()); copyTree(f, existing == null ? new File(to, f.getName()) : existing); }
        } else {
            FilesEx.mkdir(to.getParentFile());
            if (to.getParentFile().getUsableSpace() < from.length() + SafeZip.RESERVE_BYTES) throw new IOException("Not enough room to preserve personal settings");
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
