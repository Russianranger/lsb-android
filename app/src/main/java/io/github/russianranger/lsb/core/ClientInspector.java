package io.github.russianranger.lsb.core;

import java.io.*;
import java.util.*;

public final class ClientInspector {
    public static final class PlayOnlineChoiceRequired extends IOException {
        public final List<String> choices;
        PlayOnlineChoiceRequired(List<String> choices) { super("Choose the PlayOnline version to use. The extracted files are ready."); this.choices = choices; }
    }
    public static final class Snapshot {
        public File root, game, pol, loader;
        public long bytes, files;
        public String polDll, polCore, version = "Unknown (no readable patch.ver)", inventory;
        public List<String> polChoices;
        public String summary() { return files + " files · " + bytes / 1048576 + " MiB\nClient version: " + version + "\nPlayOnline: " + polCore + "\n" + (loader == null ? "Import xiloader.exe to enable launch-package export." : "32-bit xiloader present."); }
    }
    public static Snapshot inspect(File root, SafeZip.Progress progress) throws Exception {
        return inspect(root, "", progress);
    }
    public static Snapshot inspect(File root, String selectedPolCore, SafeZip.Progress progress) throws Exception {
        Snapshot s = new Snapshot(); s.root = root;
        Map<String, List<File>> candidates = new HashMap<>();
        scan(root, s, candidates, progress, 0);
        File main = unique(candidates, "ffximain.dll", true);
        File entry = unique(candidates, "ffxi.dll", true);
        if (!main.getParentFile().equals(entry.getParentFile())) throw new IOException("FFXi.dll and FFXiMain.dll must be in the same game folder");
        s.game = main.getParentFile();
        List<File> cores = new ArrayList<>();
        cores.addAll(candidates.getOrDefault("polcore.dll", Collections.emptyList()));
        cores.addAll(candidates.getOrDefault("polcoreeu.dll", Collections.emptyList()));
        File rom = child(s.game, "ROM");
        if (rom == null || !rom.isDirectory() || FilesEx.children(rom).length == 0) throw new IOException("Missing FFXI ROM data. Import the complete PlayOnline and FFXI installation.");
        requireX86(main); requireX86(entry);
        if (cores.isEmpty()) throw new IOException("Missing PlayOnline polcore.dll or polcoreeu.dll");
        s.polChoices = new ArrayList<>();
        for (File f : cores) s.polChoices.add(FilesEx.relative(root, f));
        File core;
        if (selectedPolCore != null && !selectedPolCore.isEmpty()) {
            int index = s.polChoices.indexOf(selectedPolCore);
            if (index < 0) throw new IOException("The selected PlayOnline DLL is missing. Choose an available version.");
            core = cores.get(index);
        } else if (cores.size() == 1) core = cores.get(0);
        else throw new PlayOnlineChoiceRequired(s.polChoices);
        s.pol = core.getParentFile(); s.polDll = core.getName(); s.polCore = FilesEx.relative(root, core);
        if (s.game.equals(s.pol)) throw new IOException("PlayOnline and FFXI must have separate folders");
        requireX86(core);
        List<File> loaders = candidates.getOrDefault("xiloader.exe", Collections.emptyList());
        // Prefer the loader alongside the selected viewer when each viewer has its own copy.
        s.loader = loaders.size() > 1 ? child(s.pol, "xiloader.exe") : unique(candidates, "xiloader.exe", false);
        if (s.loader != null && !s.loader.isFile()) s.loader = null;
        if (s.loader != null) requireX86(s.loader);
        File version = child(s.game, "patch.ver");
        if (version != null && version.isFile() && version.length() < 4096) {
            String text = FilesEx.read(version, 4096);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("[0-9]{8}_[0-9]+").matcher(text);
            if (m.find()) s.version = m.group();
        }
        StringBuilder json = new StringBuilder("{\n  \"schema\": 1,\n  \"files\": " + s.files + ",\n  \"bytes\": " + s.bytes + ",\n  \"clientVersion\": " + FilesEx.json(s.version) + ",\n  \"selectedPlayOnline\": " + FilesEx.json(s.polCore) + ",\n  \"runtimeTested\": false,\n  \"keyFiles\": [\n");
        List<File> keys = new ArrayList<>(Arrays.asList(core, entry, main)); if (s.loader != null) keys.add(s.loader);
        for (int i = 0; i < keys.size(); i++) {
            File f = keys.get(i); progress.update("Hashing " + f.getName());
            json.append("    {\"path\": ").append(FilesEx.json(FilesEx.relative(root, f))).append(", \"machine\": \"x86\", \"sha256\": ").append(FilesEx.json(FilesEx.hash(f))).append("}").append(i + 1 == keys.size() ? "\n" : ",\n");
        }
        s.inventory = json.append("  ]\n}\n").toString(); return s;
    }
    private static File unique(Map<String, List<File>> map, String key, boolean required) throws IOException {
        List<File> list = map.getOrDefault(key, Collections.emptyList());
        if (list.isEmpty() && !required) return null;
        if (list.size() != 1) throw new IOException("Expected one " + key + "; found " + list.size() + ". Include one complete installation.");
        return list.get(0);
    }
    private static void scan(File dir, Snapshot s, Map<String, List<File>> candidates, SafeZip.Progress progress, int depth) throws IOException {
        if (depth > 64) throw new IOException("Client folder nesting is too deep");
        for (File f : FilesEx.children(dir)) {
            SafeZip.checkCancelled();
            if (java.nio.file.Files.isSymbolicLink(f.toPath())) throw new IOException("Client contains a symbolic link");
            if (f.isDirectory()) { scan(f, s, candidates, progress, depth + 1); continue; }
            s.files++; s.bytes += f.length();
            if (s.files > 500000 || s.bytes > SafeZip.MAX_BYTES) throw new IOException("Client exceeds supported import limits");
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (Arrays.asList("ffxi.dll", "ffximain.dll", "polcore.dll", "polcoreeu.dll", "xiloader.exe").contains(name)) candidates.computeIfAbsent(name, k -> new ArrayList<>()).add(f);
            if (s.files % 1000 == 0) progress.update("Checking " + s.files + " client files");
        }
    }
    public static File child(File dir, String name) throws IOException {
        for (File f : FilesEx.children(dir)) if (f.getName().equalsIgnoreCase(name)) return f;
        return null;
    }
    public static void requireX86(File file) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            if (in.length() < 88 || in.readUnsignedByte() != 'M' || in.readUnsignedByte() != 'Z') throw new IOException("Missing Windows MZ header");
            in.seek(0x3c); long offset = Integer.toUnsignedLong(Integer.reverseBytes(in.readInt()));
            if (offset < 64 || offset > in.length() - 26) throw new IOException("Invalid PE header offset");
            in.seek(offset);
            if (in.readInt() != 0x50450000 || Short.reverseBytes(in.readShort()) != 0x14c) throw new IOException("Expected 32-bit x86 PE machine");
            in.seek(offset + 24);
            if (Short.reverseBytes(in.readShort()) != 0x10b) throw new IOException("Expected PE32 optional header");
        } catch (IOException e) { throw new IOException(file.getName() + ": " + e.getMessage()); }
    }
}
