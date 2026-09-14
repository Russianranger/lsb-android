import io.github.russianranger.lsb.core.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class CoreTest {
    static int checks;
    static final SafeZip.Progress QUIET = s -> {};
    interface Checked { void run() throws Exception; }
    static void ok(boolean test, String name) { if (!test) throw new AssertionError(name); checks++; System.out.println("PASS " + name); }
    static void fails(Checked action, String name) throws Exception { try { action.run(); } catch (Exception e) { checks++; System.out.println("PASS " + name + " (" + e.getMessage() + ")"); return; } throw new AssertionError("Expected rejection: " + name); }
    static byte[] pe(int machine) {
        byte[] b = new byte[256]; b[0] = 'M'; b[1] = 'Z'; b[0x3c] = 0x40; b[0x40] = 'P'; b[0x41] = 'E'; b[0x44] = (byte)machine; b[0x45] = (byte)(machine >> 8); b[0x58] = 0x0b; b[0x59] = 1; return b;
    }
    static byte[] zip(Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) { for (Map.Entry<String, byte[]> e : files.entrySet()) { z.putNextEntry(new ZipEntry(e.getKey())); z.write(e.getValue()); z.closeEntry(); } }
        return out.toByteArray();
    }
    static Map<String, byte[]> fixture(String version, boolean eu) {
        Map<String, byte[]> files = new LinkedHashMap<>(); String pol = "wrapper/PlayOnline/SquareEnix/PlayOnlineViewer/", game = "wrapper/PlayOnline/SquareEnix/FINAL FANTASY XI/";
        files.put(pol + (eu ? "polcoreeu.dll" : "polcore.dll"), pe(0x14c)); files.put(pol + "pol.exe", pe(0x14c)); files.put(pol + "xiloader.exe", pe(0x14c));
        files.put(game + "FFXi.dll", pe(0x14c)); files.put(game + "FFXiMain.dll", pe(0x14c)); files.put(game + "ROM/0/0.DAT", new byte[]{1, 2, 3}); files.put(game + "patch.ver", version.getBytes(java.nio.charset.StandardCharsets.UTF_8)); return files;
    }
    static void importZip(ClientStore store, Map<String, byte[]> files) throws Exception { store.importClient(new ByteArrayInputStream(zip(files)), false, true, QUIET); }
    public static void main(String[] args) throws Exception {
        File temp = Files.createTempDirectory("lsb-core-").toFile();
        try {
            for (String path : new String[]{"../outside", "/root/file", "C:/game", "a/../../escape", "a\\..\\escape", "a//b", "a/./b", "foo./bar", "x /b"}) fails(() -> SafeZip.safeName(path), "reject ZIP path " + path);
            ok(SafeZip.safeName("PlayOnline\\FINAL FANTASY XI\\ROM/0/").equals("PlayOnline/FINAL FANTASY XI/ROM/0"), "normalize Windows archive separators");
            File unsafe = new File(temp, "unsafe");
            fails(() -> SafeZip.extract(new ByteArrayInputStream(zip(Collections.singletonMap("../escaped", new byte[]{1}))), unsafe, QUIET, 1024, 0), "reject traversal during extraction");
            ok(!new File(temp, "escaped").exists(), "traversal did not create an external file");
            Map<String, byte[]> collide = new LinkedHashMap<>(); collide.put("a/Foo", new byte[]{1}); collide.put("A/bar", new byte[]{2});
            fails(() -> SafeZip.extract(new ByteArrayInputStream(zip(collide)), new File(temp, "case"), QUIET, 1024, 0), "reject implicit parent case collision");
            fails(() -> SafeZip.extract(new ByteArrayInputStream(zip(Collections.singletonMap("big", new byte[5000]))), new File(temp, "budget"), QUIET, 100, 0), "enforce expanded archive budget");
            fails(() -> SafeZip.extract(new ByteArrayInputStream(new byte[10]), new File(temp, "invalid"), QUIET, 100, 0), "reject non-ZIP");
            ClientStore s = new ClientStore(new File(temp, "session"));
            importZip(s, fixture("30260904_1", false));
            ClientInspector.Snapshot first = s.validate(QUIET);
            ok(first.version.equals("30260904_1") && first.loader != null && first.files == 7, "detect nested client, version, loader and file inventory");
            String oldInventory = s.inventory();
            Map<String, byte[]> broken = fixture("30261001_1", false); broken.remove("wrapper/PlayOnline/SquareEnix/FINAL FANTASY XI/FFXi.dll");
            fails(() -> importZip(s, broken), "reject incomplete client update");
            ok(s.inventory().equals(oldInventory) && !s.hasPrevious(), "failed import preserves active client and backup state");
            Map<String, byte[]> x64 = fixture("30261001_1", false); x64.put("wrapper/PlayOnline/SquareEnix/PlayOnlineViewer/xiloader.exe", pe(0x8664));
            fails(() -> importZip(s, x64), "reject x64 xiloader");
            FilesEx.text(new File(first.game, "USER/character/macros.dat"), "my macros");
            importZip(s, fixture("30261001_1", false));
            ClientInspector.Snapshot newer = s.validate(QUIET);
            ok(FilesEx.read(new File(newer.game, "USER/character/macros.dat"), 100).equals("my macros"), "preserve user macros on full update");
            ok(s.hasPrevious() && newer.version.equals("30261001_1"), "successful update retains previous client");
            s.rollback(QUIET); ok(s.validate(QUIET).version.equals("30260904_1"), "rollback restores original client");
            s.rollback(QUIET); ok(s.validate(QUIET).version.equals("30261001_1"), "rollback can switch back");
            s.saveConfig(new LaunchConfig("192.168.1.50", "US"));
            ByteArrayOutputStream backup = new ByteArrayOutputStream(); s.exportBackup(backup, QUIET);
            ClientStore restored = new ClientStore(new File(temp, "restored")); restored.importClient(new ByteArrayInputStream(backup.toByteArray()), true, false, QUIET);
            ok(restored.config().host.equals("192.168.1.50") && restored.validate(QUIET).version.equals("30261001_1"), "backup restore preserves settings and version");
            ok(FilesEx.read(new File(restored.validate(QUIET).game, "USER/character/macros.dat"), 100).equals("my macros"), "backup restore preserves macros");
            fails(() -> restored.importClient(new ByteArrayInputStream(zip(fixture("30260904_1", false))), true, false, QUIET), "reject client ZIP passed as backup");
            for (String host : new String[]{"localhost & calc", "127.0.0.1 --pass x", "a%PATH%", "x\r\ny", "-bad", "http://server"}) fails(() -> new LaunchConfig(host, "US"), "reject command injection/invalid host " + host.replace('\n', ' '));
            Map<String, String> packageFiles = RepairPackage.generate(restored.validate(QUIET), restored.config(), "{}");
            String cmd = packageFiles.get("launch.cmd");
            ok(cmd.contains("--server 192.168.1.50 --lang US") && cmd.contains("pushd") && !cmd.contains("--pass"), "launch recipe selects server and region without storing credentials");
            ok(packageFiles.get("repair.cmd").contains("/reg:32") && packageFiles.get("repair.cmd").contains("SysWOW64") && packageFiles.get("repair.cmd").contains("if errorlevel 1 goto failed"), "repair uses 32-bit registry and stops on registration failure");
            fails(() -> RepairPackage.generate(restored.validate(QUIET), new LaunchConfig("localhost", "EU"), "{}"), "reject client region mismatch");
            ByteArrayOutputStream prepared = new ByteArrayOutputStream(); restored.exportPrepared(prepared, "{}", QUIET);
            File exported = new File(temp, "prepared"); SafeZip.extract(new ByteArrayInputStream(prepared.toByteArray()), exported, QUIET);
            ok(new File(exported, "repair.cmd").isFile() && new File(exported, "launch.cmd").isFile() && new File(exported, "client").isDirectory(), "prepared export contains runnable recipe and client payload");
            ClientInspector.inspect(new File(exported, "client"), QUIET); checks++;
            ClientStore noLoader = new ClientStore(new File(temp, "no-loader")); Map<String, byte[]> without = fixture("30260904_1", false); without.remove("wrapper/PlayOnline/SquareEnix/PlayOnlineViewer/xiloader.exe"); importZip(noLoader, without);
            fails(() -> noLoader.exportPrepared(new ByteArrayOutputStream(), "{}", QUIET), "refuse launch package without xiloader");
            noLoader.importLoader(new ByteArrayInputStream(pe(0x14c)), QUIET); ok(noLoader.validate(QUIET).loader != null, "separate x86 bootloader import");
            fails(() -> noLoader.importLoader(new ByteArrayInputStream(pe(0x8664)), QUIET), "reject incompatible replacement bootloader");
            ok(noLoader.validate(QUIET).loader != null, "bad loader import retains working loader");
            ClientStore eu = new ClientStore(new File(temp, "eu")); importZip(eu, fixture("30260904_1", true));
            ok(RepairPackage.generate(eu.validate(QUIET), new LaunchConfig("localhost", "EU"), "{}").get("repair.cmd").contains("PlayOnlineEU"), "EU registration profile");
            File current = s.current(), previous = s.previous(); FilesEx.delete(previous); FilesEx.move(current, previous); s.recover(); ok(s.hasClient(), "recover interrupted import directory switch");
            FilesEx.move(s.current(), new File(current.getParentFile(), "swap")); s.recover(); ok(s.hasClient(), "recover interrupted first rollback rename");
            Thread.currentThread().interrupt(); fails(() -> SafeZip.checkCancelled(), "recognize cancellation"); Thread.interrupted();
            System.out.println("Completed " + checks + " checks.");
        } finally { Thread.interrupted(); FilesEx.delete(temp); }
    }
}
