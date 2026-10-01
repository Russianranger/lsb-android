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
    public void saveConfig(LaunchConfig config) throws IOException { requireRegion(config); FilesEx.mkdir(current()); FilesEx.text(new File(current(), "session.properties"), config.properties()); }
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
    private File incoming() { return new File(home, "incoming"); }
    public boolean hasPendingImport() { return new File(incoming(), "pending.properties").isFile(); }
    public LaunchConfig pendingConfig() throws IOException { return LaunchConfig.load(new File(incoming(), "session.properties")); }
    public List<String> pendingChoices() throws IOException { return readChoices(new File(incoming(), "pending.properties")); }
    public List<String> playOnlineChoices() throws IOException { return readChoices(new File(current(), "playonline-options.properties")); }
    private static List<String> readChoices(File file) throws IOException {
        List<String> result = new ArrayList<>();
        if (!file.isFile()) return result;
        Properties p = new Properties(); try (Reader r = new FileReader(file)) { p.load(r); }
        int count = Integer.parseInt(p.getProperty("count", "0"));
        if (count < 0 || count > 500000) throw new IOException("Invalid PlayOnline option count");
        for (int i = 0; i < count; i++) result.add(p.getProperty("choice." + i));
        ClientInspector.sortPlayOnlineChoices(result);
        return result;
    }
    private static void writeChoices(File file, List<String> choices, boolean restore, boolean preserveUser) throws IOException {
        Properties p = new Properties(); p.setProperty("count", Integer.toString(choices.size()));
        p.setProperty("restore", Boolean.toString(restore)); p.setProperty("preserveUser", Boolean.toString(preserveUser));
        for (int i = 0; i < choices.size(); i++) p.setProperty("choice." + i, choices.get(i));
        StringWriter out = new StringWriter(); p.store(out, null); FilesEx.text(file, out.toString());
    }
    public void discardPendingImport() throws IOException { FilesEx.delete(incoming()); }
    public void importClient(InputStream in, boolean restore, boolean preserveUser, SafeZip.Progress progress) throws Exception {
        recover();
        if (hasPendingImport()) throw new IOException("Finish or discard the extracted import before importing another ZIP.");
        File incoming = incoming(); FilesEx.delete(incoming); FilesEx.mkdir(incoming);
        boolean retain = false;
        try {
            File unpack = new File(incoming, "unpack");
            SafeZip.extract(in, unpack, progress);
            LaunchConfig active = config();
            // A new archive can use different paths. Ask again if it contains multiple versions.
            LaunchConfig cfg = new LaunchConfig(active.host, active.region);
            File payload = unpack;
            if (restore) {
                File marker = new File(unpack, "backup-format.txt");
                if (!marker.isFile() || !FilesEx.read(marker, 128).trim().equals("lsb-android-session-v1")) throw new IOException("Not an LSB Android session backup");
                payload = new File(unpack, "client");
                if (!payload.isDirectory()) throw new IOException("Backup has no client folder");
                cfg = LaunchConfig.load(new File(unpack, "session.properties"));
            }
            try { activateImport(payload, cfg, !restore && preserveUser, progress); }
            catch (ClientInspector.PlayOnlineChoiceRequired choice) {
                FilesEx.text(new File(incoming, "session.properties"), cfg.properties());
                writeChoices(new File(incoming, "pending.properties"), choice.choices, restore, !restore && preserveUser);
                retain = true;
                progress.update("Choose PlayOnline version to finish import");
            }
        } finally { if (!retain) cleanupImport(progress); }
    }
    public void finishPendingImport(String selectedCore, String region, SafeZip.Progress progress) throws Exception {
        if (!hasPendingImport()) throw new IOException("No extracted import is waiting for a selection");
        if (!pendingChoices().contains(selectedCore)) throw new IOException("Choose one of the detected PlayOnline versions");
        Properties state = new Properties();
        try (Reader r = new FileReader(new File(incoming(), "pending.properties"))) { state.load(r); }
        LaunchConfig old = pendingConfig();
        LaunchConfig cfg = new LaunchConfig(old.host, region, selectedCore);
        requireRegion(cfg);
        File payload = new File(incoming(), Boolean.parseBoolean(state.getProperty("restore")) ? "unpack/client" : "unpack");
        activateImport(payload, cfg, Boolean.parseBoolean(state.getProperty("preserveUser")), progress);
        cleanupImport(progress);
    }
    private void activateImport(File payload, LaunchConfig cfg, boolean preserveUser, SafeZip.Progress progress) throws Exception {
        ClientInspector.Snapshot fresh = ClientInspector.inspect(payload, cfg.polCore, progress);
        if (preserveUser && hasClient()) {
            ClientInspector.Snapshot old = ClientInspector.inspect(client(), config().polCore, progress);
            preserveDirectory(old.game, fresh.game, "USER", progress);
            preserveDirectory(old.pol, fresh.pol, "usr", progress);
            fresh = ClientInspector.inspect(payload, fresh.polCore, progress);
        }
        // EU is identifiable by the DLL. US and JP share the other filename.
        String region = fresh.polDll.equalsIgnoreCase("polcoreeu.dll") ? "EU" : cfg.region.equals("EU") ? "US" : cfg.region;
        cfg = new LaunchConfig(cfg.host, region, fresh.polCore);
        File staged = new File(incoming(), "ready"); FilesEx.delete(staged); FilesEx.mkdir(staged);
        FilesEx.text(new File(staged, "session.properties"), cfg.properties());
        writeSnapshot(staged, fresh);
        FilesEx.text(new File(staged, "backup-format.txt"), "lsb-android-session-v1\n");
        SafeZip.checkCancelled();
        // Move the large payload only after validation and metadata are complete.
        File stagedClient = new File(staged, "client");
        FilesEx.move(payload, stagedClient);
        try { promote(staged); }
        catch (IOException e) { if (stagedClient.exists()) FilesEx.move(stagedClient, payload); throw e; }
    }
    private void cleanupImport(SafeZip.Progress progress) {
        try { FilesEx.delete(incoming()); }
        catch (IOException e) { progress.update("Staging cleanup will retry on the next import"); }
    }
    private static void writeSnapshot(File directory, ClientInspector.Snapshot snapshot) throws IOException {
        FilesEx.text(new File(directory, "inventory.json"), snapshot.inventory);
        FilesEx.text(new File(directory, "summary.txt"), snapshot.summary());
        writeChoices(new File(directory, "playonline-options.properties"), snapshot.polChoices, false, false);
    }
    private static void requireRegion(LaunchConfig cfg) throws IOException {
        if (!cfg.polCore.isEmpty() && cfg.region.equals("EU") != new File(cfg.polCore).getName().equalsIgnoreCase("polcoreeu.dll"))
            throw new IOException("Choose EU for polcoreeu.dll, or US / JP for polcore.dll");
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
        ClientInspector.inspect(new File(previous(), "client"), LaunchConfig.load(new File(previous(), "session.properties")).polCore, progress);
        File swap = new File(home, "swap");
        if (swap.exists()) throw new IOException("An interrupted rollback needs recovery");
        if (current().exists()) FilesEx.move(current(), swap);
        try { FilesEx.move(previous(), current()); if (swap.exists()) FilesEx.move(swap, previous()); }
        catch (IOException e) { recover(); throw e; }
    }
    public ClientInspector.Snapshot validate(SafeZip.Progress progress) throws Exception {
        if (!hasClient()) throw new IOException("Import a client first");
        ClientInspector.Snapshot s = ClientInspector.inspect(client(), config().polCore, progress);
        writeSnapshot(current(), s); return s;
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
    public void exportPrepared(OutputStream out, String profile, byte[] launcher, boolean includeClient, SafeZip.Progress progress) throws Exception {
        ClientInspector.Snapshot s = validate(progress);
        Map<String, String> scripts = RepairPackage.generate(s, config(), profile);
        if (launcher == null || launcher.length < 88 || launcher.length > 2 * 1048576 || launcher[0] != 'M' || launcher[1] != 'Z') throw new IOException("The Windows launcher asset is missing or invalid; reinstall the updated APK.");
        if (s.loader == null) throw new IOException("Import xiloader.exe before exporting a launch package");
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out))) {
            zip.setLevel(1); for (Map.Entry<String, String> e : scripts.entrySet()) SafeZip.entry(zip, e.getKey(), e.getValue());
            SafeZip.entry(zip, "LSB-FFXI.exe", launcher);
            if (includeClient) SafeZip.writeTree(zip, client(), "client/", progress);
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
