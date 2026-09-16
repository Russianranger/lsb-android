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
            ByteArrayOutputStream prepared = new ByteArrayOutputStream(); restored.exportPrepared(prepared, "{}", pe(0x14c), true, QUIET);
            File exported = new File(temp, "prepared"); SafeZip.extract(new ByteArrayInputStream(prepared.toByteArray()), exported, QUIET);
            ok(new File(exported, "repair.cmd").isFile() && new File(exported, "launch.cmd").isFile() && new File(exported, "client").isDirectory(), "prepared export contains runnable recipe and client payload");
            ClientInspector.inspect(new File(exported, "client"), QUIET); checks++;
            ClientStore noLoader = new ClientStore(new File(temp, "no-loader")); Map<String, byte[]> without = fixture("30260904_1", false); without.remove("wrapper/PlayOnline/SquareEnix/PlayOnlineViewer/xiloader.exe"); importZip(noLoader, without);
            fails(() -> noLoader.exportPrepared(new ByteArrayOutputStream(), "{}", pe(0x14c), true, QUIET), "refuse launch package without xiloader");
            noLoader.importLoader(new ByteArrayInputStream(pe(0x14c)), QUIET); ok(noLoader.validate(QUIET).loader != null, "separate x86 bootloader import");
            fails(() -> noLoader.importLoader(new ByteArrayInputStream(pe(0x8664)), QUIET), "reject incompatible replacement bootloader");
            ok(noLoader.validate(QUIET).loader != null, "bad loader import retains working loader");
            ClientStore eu = new ClientStore(new File(temp, "eu")); importZip(eu, fixture("30260904_1", true));
            ok(RepairPackage.generate(eu.validate(QUIET), new LaunchConfig("localhost", "EU"), "{}").get("repair.cmd").contains("PlayOnlineEU"), "EU registration profile");
            File current = s.current(), previous = s.previous(); FilesEx.delete(previous); FilesEx.move(current, previous); s.recover(); ok(s.hasClient(), "recover interrupted import directory switch");
            FilesEx.move(s.current(), new File(current.getParentFile(), "swap")); s.recover(); ok(s.hasClient(), "recover interrupted first rollback rename");
            String polPath = "wrapper/PlayOnline/SquareEnix/PlayOnlineViewer/";
            Map<String, byte[]> mixed = fixture("30261010_1", false);
            mixed.put(polPath + "polcoreeu.dll", pe(0x14c));
            ClientStore dual = new ClientStore(new File(temp, "dual"));
            importZip(dual, mixed);
            ok(dual.hasPendingImport() && !dual.hasClient() && dual.pendingChoices().size() == 2, "two POL DLLs pause for selection without failing import");
            ClientStore resumed = new ClientStore(new File(temp, "dual")); resumed.recover();
            ok(resumed.pendingChoices().contains(polPath + "polcoreeu.dll"), "extracted choices survive store recreation and recovery");
            fails(() -> importZip(resumed, fixture("30261011_1", false)), "do not replace an import waiting for selection");
            fails(() -> resumed.finishPendingImport("../polcore.dll", "US", QUIET), "reject a selection outside discovered candidates");
            fails(() -> resumed.finishPendingImport(polPath + "polcoreeu.dll", "US", QUIET), "require matching region for selected EU DLL");
            Thread.currentThread().interrupt();
            fails(() -> resumed.finishPendingImport(polPath + "polcoreeu.dll", "EU", QUIET), "cancel finishing a pending import"); Thread.interrupted();
            ok(resumed.hasPendingImport(), "cancelled selection keeps extracted files available for retry");
            resumed.finishPendingImport(polPath + "polcoreeu.dll", "EU", QUIET);
            ok(!resumed.hasPendingImport() && resumed.config().region.equals("EU") && resumed.validate(QUIET).polDll.equals("polcoreeu.dll"), "selected EU version activates and validates with both DLLs retained");
            ok(resumed.playOnlineChoices().size() == 2 && new File(resumed.client(), polPath + "polcore.dll").isFile(), "keep alternate version selectable after activation");
            String euRepair = resumed.previewRepair("{}", QUIET);
            ok(euRepair.contains("PlayOnlineEU") && euRepair.contains("POLCORE=%~dp0client\\" + (polPath + "polcoreeu.dll").replace('/', '\\')) && euRepair.contains("/s \"%POLCORE%\""), "repair registers the selected EU DLL");
            ByteArrayOutputStream dualBackup = new ByteArrayOutputStream(); resumed.exportBackup(dualBackup, QUIET);
            ClientStore dualRestored = new ClientStore(new File(temp, "dual-restored"));
            dualRestored.importClient(new ByteArrayInputStream(dualBackup.toByteArray()), true, false, QUIET);
            ok(!dualRestored.hasPendingImport() && dualRestored.config().polCore.equals(polPath + "polcoreeu.dll") && dualRestored.validate(QUIET).polDll.equals("polcoreeu.dll"), "backup restores saved selection without asking or rejecting two versions");
            resumed.saveConfig(new LaunchConfig("localhost", "JP", polPath + "polcore.dll"));
            ok(resumed.validate(QUIET).polDll.equals("polcore.dll") && resumed.previewRepair("{}", QUIET).contains("HKLM\\SOFTWARE\\PlayOnline\\InstallFolder"), "switch existing selection to JP and repair the chosen version");
            ByteArrayOutputStream mixedExport = new ByteArrayOutputStream(); resumed.exportPrepared(mixedExport, "{}", pe(0x14c), true, QUIET);
            File mixedExportDir = new File(temp, "mixed-export"); SafeZip.extract(new ByteArrayInputStream(mixedExport.toByteArray()), mixedExportDir, QUIET);
            ok(FilesEx.read(new File(mixedExportDir, "launch.cmd"), 10000).contains("--lang JP"), "prepared launch uses selected region with both DLLs present");
            importZip(resumed, fixture("30261101_1", false)); resumed.rollback(QUIET);
            ok(resumed.config().region.equals("JP") && resumed.validate(QUIET).polDll.equals("polcore.dll"), "rollback restores the chosen version from mixed client");
            String beforePending = resumed.inventory(); importZip(resumed, mixed);
            ok(resumed.hasPendingImport() && resumed.inventory().equals(beforePending), "pending update keeps active client intact");
            resumed.discardPendingImport();
            ok(!resumed.hasPendingImport() && resumed.inventory().equals(beforePending), "discard removes only pending extraction");
            Map<String, byte[]> duplicates = fixture("30261012_1", false);
            duplicates.put("OtherViewer/polcore.dll", pe(0x14c)); duplicates.put("OtherViewer/xiloader.exe", pe(0x14c));
            importZip(resumed, duplicates); resumed.finishPendingImport("OtherViewer/polcore.dll", "US", QUIET);
            ClientInspector.Snapshot duplicateSnapshot = resumed.validate(QUIET);
            ok(duplicateSnapshot.pol.getName().equals("OtherViewer") && duplicateSnapshot.loader.getParentFile().equals(duplicateSnapshot.pol), "select exact folder with duplicate same-region DLLs and loaders");
            FilesEx.text(new File(duplicateSnapshot.pol, "usr/preferences.ini"), "personal");
            importZip(resumed, duplicates); resumed.finishPendingImport("OtherViewer/polcore.dll", "US", QUIET);
            ok(FilesEx.read(new File(resumed.validate(QUIET).pol, "usr/preferences.ini"), 100).equals("personal"), "preserve settings from selected viewer when finishing a mixed update");
            Map<String, byte[]> invalidChoice = fixture("30261013_1", false); invalidChoice.put(polPath + "polcoreeu.dll", pe(0x8664));
            importZip(resumed, invalidChoice);
            fails(() -> resumed.finishPendingImport(polPath + "polcoreeu.dll", "EU", QUIET), "still reject invalid selected DLL architecture");
            ok(resumed.hasPendingImport() && resumed.validate(QUIET).pol.getName().equals("OtherViewer"), "bad selection keeps pending files and active client");
            resumed.finishPendingImport(polPath + "polcore.dll", "US", QUIET);
            ok(resumed.validate(QUIET).polDll.equals("polcore.dll"), "retry another valid version without extracting again");
            resumed.saveConfig(new LaunchConfig("localhost", "US", "missing/polcore.dll"));
            fails(() -> resumed.validate(QUIET), "never silently switch away from a missing saved version");
            File oldConfig = new File(temp, "legacy.properties"); FilesEx.text(oldConfig, "schema=1\nhost=localhost\nregion=JP\n");
            ok(LaunchConfig.load(oldConfig).polCore.isEmpty(), "read 0.1.0 connection settings without a selected DLL");
            File escaped = new File(temp, "escaped.properties");
            String unusualPath = " プレイ = Online/polcore.dll"; FilesEx.text(escaped, new LaunchConfig("localhost", "JP", unusualPath).properties());
            ok(LaunchConfig.load(escaped).polCore.equals(unusualPath), "round-trip spaces and Unicode in a selected folder path");
            // Reproduce the device layout: a real viewer/com DLL plus a patch-cache copy.
            String liveCore = "PlayOnlineViewer/viewer/com/polcore.dll";
            String cacheCore = "PlayOnlineViewer/patchfiles/PlayOnlineViewer/viewer/com/polcore.dll";
            Map<String, byte[]> realLayout = new LinkedHashMap<>();
            realLayout.put("FINAL FANTASY XI/FFXi.dll", pe(0x14c));
            realLayout.put("FINAL FANTASY XI/FFXiMain.dll", pe(0x14c));
            realLayout.put("FINAL FANTASY XI/ROM/0/0.DAT", new byte[]{1,2,3});
            realLayout.put("FINAL FANTASY XI/ashitav4/bootloader/xiloader.exe", pe(0x14c));
            realLayout.put("PlayOnlineViewer/pol.exe", pe(0x14c));
            realLayout.put(liveCore, pe(0x14c)); realLayout.put(cacheCore, pe(0x14c));
            ClientStore layout = new ClientStore(new File(temp, "device-layout")); importZip(layout, realLayout);
            ok(layout.pendingChoices().get(0).equals(liveCore), "installed candidate appears before patch-cache copy");
            layout.finishPendingImport(cacheCore, "US", QUIET);
            ClientInspector.Snapshot cached = layout.validate(QUIET);
            ok(cached.polPatchCache && cached.warning().contains("patch-cache") && layout.config().polCore.equals(cacheCore), "existing patch-cache selection remains readable and gets an actionable warning");
            fails(() -> layout.previewRepair("{}", QUIET), "do not generate registry repair for a patch-cache copy");
            fails(() -> layout.exportPrepared(new ByteArrayOutputStream(), "{}", pe(0x14c), true, QUIET), "do not export a misleading launch package for a patch cache");
            ByteArrayOutputStream cacheBackup = new ByteArrayOutputStream(); layout.exportBackup(cacheBackup, QUIET);
            ClientStore recoveredLayout = new ClientStore(new File(temp, "device-restored"));
            recoveredLayout.importClient(new ByteArrayInputStream(cacheBackup.toByteArray()), true, false, QUIET);
            ok(recoveredLayout.config().polCore.equals(cacheCore) && recoveredLayout.playOnlineChoices().get(0).equals(liveCore), "restore backup made with cache selection and retain usable live choice");
            recoveredLayout.saveConfig(new LaunchConfig("127.0.0.1", "US", liveCore));
            ClientInspector.Snapshot installed = recoveredLayout.validate(QUIET);
            ok(installed.pol.equals(new File(recoveredLayout.client(), "PlayOnlineViewer")) && installed.warning().isEmpty(), "resolve viewer/com DLL to PlayOnlineViewer installation root without reimport");
            ok(installed.version.startsWith("Unknown") && installed.loader.getPath().contains("ashitav4"), "missing patch.ver and a nested Ashita loader do not block file preparation");
            Map<String,String> layoutPackage = RepairPackage.generate(installed, recoveredLayout.config(), "{}");
            String correctRepair = layoutPackage.get("repair.cmd");
            ok(correctRepair.contains("set \"POL=%~dp0client\\PlayOnlineViewer\"\r\n") && correctRepair.contains("/v 1000 /t REG_SZ /d \"%POL%\""), "InstallFolder 1000 points to viewer root rather than viewer/com");
            ok(correctRepair.contains("set \"POLCORE=%~dp0client\\PlayOnlineViewer\\viewer\\com\\polcore.dll\"") && correctRepair.contains("/s \"%POLCORE%\""), "COM registration points to the exact selected nested DLL");
            ok(layoutPackage.get("update-via-playonline.cmd").contains("client\\PlayOnlineViewer\\pol.exe") && layoutPackage.get("launch.cmd").contains("pushd \"%~dp0client\\PlayOnlineViewer\"\r\n"), "updater and launch working directory use the viewer root");
            ok(installed.inventory.contains("playOnlineCandidates") && installed.inventory.contains(cacheCore) && installed.inventory.contains("playOnlineRoot"), "support inventory reports both choices and resolved root");
            FilesEx.text(new File(installed.pol, "usr/preferences.ini"), "viewer preferences");
            importZip(recoveredLayout, realLayout); recoveredLayout.finishPendingImport(liveCore, "US", QUIET);
            ok(FilesEx.read(new File(recoveredLayout.validate(QUIET).pol, "usr/preferences.ini"), 100).equals("viewer preferences"), "preserve usr from viewer root with nested COM DLL");
            Map<String,byte[]> absentExe = new LinkedHashMap<>(realLayout); absentExe.remove("PlayOnlineViewer/pol.exe"); absentExe.remove(cacheCore);
            importZip(layout, absentExe);
            ok(layout.validate(QUIET).warning().contains("No pol.exe"), "incomplete viewer keeps imported data and identifies missing executable");
            fails(() -> layout.previewRepair("{}", QUIET), "missing pol.exe cannot produce a success-looking repair package");
            ok(ClientInspector.isPatchCache("POL/PATCHFILES/polcore.dll") && !ClientInspector.isPatchCache("My-patchfiles-archive/POL/polcore.dll"), "cache detection matches a path component case-insensitively");
            Map<String,byte[]> rootLayout = new LinkedHashMap<>();
            for (Map.Entry<String,byte[]> e : realLayout.entrySet()) if (!e.getKey().equals(cacheCore)) rootLayout.put(e.getKey().replace("PlayOnlineViewer/", ""), e.getValue());
            ClientStore atRoot = new ClientStore(new File(temp, "viewer-at-root")); importZip(atRoot, rootLayout);
            ok(atRoot.previewRepair("{}", QUIET).contains("set \"POL=%~dp0client\"\r\n"), "support viewer files at archive root without escaping payload");
            ByteArrayOutputStream launcherOnly = new ByteArrayOutputStream();
            recoveredLayout.exportPrepared(launcherOnly, "{}", pe(0x14c), false, QUIET);
            File launcherDir = new File(temp, "launcher-only"); SafeZip.extract(new ByteArrayInputStream(launcherOnly.toByteArray()), launcherDir, QUIET);
            ok(new File(launcherDir, "LSB-FFXI.exe").isFile() && !new File(launcherDir, "client").exists(), "small launcher update includes EXE without recopying game data");
            String ini = FilesEx.read(new File(launcherDir, "lsb-launcher.ini"), 10000);
            ok(ini.contains("pol=client\\PlayOnlineViewer\r\n") && ini.contains("core=client\\PlayOnlineViewer\\viewer\\com\\polcore.dll\r\n"), "native launcher config separates install root and COM DLL");
            ok(ini.contains("loader=client\\FINAL FANTASY XI\\ashitav4\\bootloader\\xiloader.exe") && ini.contains("host=127.0.0.1") && ini.contains("region=US"), "native launcher config retains nested bootloader and connection");
            ok(new File(exported, "LSB-FFXI.exe").isFile() && new File(exported, "lsb-launcher.ini").isFile() && new File(exported, "client").isDirectory(), "full prepared export includes EXE, config and client");
            fails(() -> recoveredLayout.exportPrepared(new ByteArrayOutputStream(), "{}", new byte[0], false, QUIET), "refuse launcher export with a missing packaged helper");
            Thread.currentThread().interrupt(); fails(() -> SafeZip.checkCancelled(), "recognize cancellation"); Thread.interrupted();
            System.out.println("Completed " + checks + " checks.");
        } finally { Thread.interrupted(); FilesEx.delete(temp); }
    }
}
