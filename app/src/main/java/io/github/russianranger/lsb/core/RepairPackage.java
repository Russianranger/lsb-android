package io.github.russianranger.lsb.core;

import java.io.*;
import java.util.*;

/** A reviewable Windows/Wine repair recipe. Generating it never marks a prefix repaired. */
public final class RepairPackage {
    public static Map<String, String> generate(ClientInspector.Snapshot s, LaunchConfig cfg, String profile) throws IOException {
        if (!s.warning().isEmpty()) throw new IOException(s.warning());
        if (cfg.region.equals("EU") != s.polDll.equalsIgnoreCase("polcoreeu.dll")) throw new IOException("Selected region does not match the imported PlayOnline DLL");
        String pol = winPath(s.root, s.pol), game = winPath(s.root, s.game), core = winPath(s.root, new File(s.root, s.polCore));
        String prefix = "@echo off\r\nsetlocal DisableDelayedExpansion\r\ncd /d \"%~dp0\"\r\n";
        String vars = "set \"POL=%~dp0" + pol + "\"\r\nset \"FFXI=%~dp0" + game + "\"\r\nset \"REG32=%SystemRoot%\\System32\\reg.exe\"\r\nset \"REGSVR=%SystemRoot%\\System32\\regsvr32.exe\"\r\nif exist \"%SystemRoot%\\SysWOW64\\regsvr32.exe\" set \"REGSVR=%SystemRoot%\\SysWOW64\\regsvr32.exe\"\r\n";
        vars += "set \"POLCORE=%~dp0" + core + "\"\r\n";
        String branch = "HKLM\\" + cfg.registry();
        String repair = prefix + vars + "echo This registers the imported installation in this Windows or Wine environment.\r\necho It does not install the GameHub components or update game files.\r\necho Export your Wine prefix first if you want to restore all COM registration.\r\npause\r\n" +
            "if exist \"%SystemRoot%\\SysWOW64\\reg.exe\" set \"REG32=%SystemRoot%\\SysWOW64\\reg.exe\"\r\nif not exist \"registry-before.reg\" \"%REG32%\" export \"" + branch + "\" \"registry-before.reg\" /y >nul 2>&1\r\n" +
            "\"%REG32%\" add \"" + branch + "\\InstallFolder\" /v 1000 /t REG_SZ /d \"%POL%\" /f /reg:32 > repair.log 2>&1\r\nif errorlevel 1 goto failed\r\n" +
            "\"%REG32%\" add \"" + branch + "\\InstallFolder\" /v 0001 /t REG_SZ /d \"%FFXI%\" /f /reg:32 >> repair.log 2>&1\r\nif errorlevel 1 goto failed\r\n" +
            "\"%REG32%\" add \"" + branch + "\\" + (cfg.region.equals("JP") ? "Square" : "SquareEnix") + "\\PlayOnlineViewer\\Settings\" /v Language /t REG_DWORD /d " + cfg.language() + " /f /reg:32 >> repair.log 2>&1\r\nif errorlevel 1 goto failed\r\n";
        for (String dll : Arrays.asList("%POLCORE%", "%FFXI%\\FFXi.dll", "%FFXI%\\FFXiMain.dll")) {
            repair += "echo Registering \"" + dll + "\" >> repair.log\r\n\"%REGSVR%\" /s \"" + dll + "\" >> repair.log 2>&1\r\nif errorlevel 1 goto failed\r\n";
        }
        repair += "echo Registration commands succeeded. Test launch separately. >> repair.log\r\ntype repair.log\r\npause\r\nexit /b 0\r\n:failed\r\necho Registration failed. No game launch was attempted. >> repair.log\r\ntype repair.log\r\npause\r\nexit /b 1\r\n";
        Map<String, String> files = new LinkedHashMap<>(); files.put("repair.cmd", repair);
        files.put("diagnose.cmd", prefix + vars + "\"%REG32%\" query \"" + branch + "\\InstallFolder\" /reg:32 > diagnosis.log 2>&1\r\n" +
            "echo Imported POL: \"%POL%\" >> diagnosis.log\r\necho Imported FFXI: \"%FFXI%\" >> diagnosis.log\r\ndir \"%POLCORE%\" >> diagnosis.log 2>&1\r\ndir \"%FFXI%\\*.dll\" >> diagnosis.log 2>&1\r\ntype diagnosis.log\r\npause\r\n");
        if (s.loader != null) {
            String loader = winPath(s.root, s.loader);
            files.put("launch.cmd", prefix + "echo Connecting to " + cfg.host + ". Enter credentials in xiloader.\r\npushd \"%~dp0" + pol + "\"\r\n\"%~dp0" + loader + "\" --server " + cfg.host + " --lang " + cfg.region + "\r\nset \"LSB_EXIT=%ERRORLEVEL%\"\r\npopd\r\necho xiloader exited with code %LSB_EXIT%.\r\npause\r\nexit /b %LSB_EXIT%\r\n");
        }
        File polExe = s.polExecutable;
        if (polExe != null) files.put("update-via-playonline.cmd", prefix + "pushd \"%~dp0" + pol + "\"\r\n\"%~dp0" + winPath(s.root, polExe) + "\"\r\npopd\r\n");
        files.put("lsb-launcher.ini", "[lsb]\r\nformat=1\r\npol=" + pol + "\r\ngame=" + game + "\r\ncore=" + core + "\r\nloader=" + (s.loader == null ? "" : winPath(s.root, s.loader)) + "\r\nhost=" + cfg.host + "\r\nregion=" + cfg.region + "\r\n");
        files.put("runtime-profile.json", profile);
        files.put("inventory.json", s.inventory);
        files.put("README.txt", "LSB Android 0.1.3 - GameHub executable launcher\r\n\r\nFULL EXPORT: Extract the complete ZIP. Keep LSB-FFXI.exe and lsb-launcher.ini beside the client folder.\r\nLAUNCHER UPDATE ONLY: Extract into the root of your existing prepared package, beside its client folder. This ZIP contains no game files.\r\n\r\n1. In GameHub, select LSB-FFXI.exe as the game executable and match your known-working container settings. No CMD file or launch arguments are needed.\r\n2. Back up or clone the working GameHub container before testing registration.\r\n3. Start LSB-FFXI.exe and click Repair registration. It writes 32-bit installation keys and calls DllRegisterServer in separate 32-bit workers. It does not open cmd.exe or regsvr32.exe.\r\n4. If registration succeeds, click Launch FFXI. Enter credentials in xiloader. The helper remains running while the child process is active.\r\n5. If it fails, send lsb-launcher.log from beside the EXE. DLL-load errors and registration HRESULTs are recorded; account credentials are not.\r\n\r\nThe helper does not install Wine/FEX, GameHub components, or the server. Wine execution still needs testing on the Thor.\r\nregistry-before.txt records the three prior installation/language values for diagnosis. It is not an automatic restore file or a backup of COM registration; back up the container itself.\r\nThe optional Open PlayOnline updater button starts pol.exe interactively; retail initialization may still be needed.\r\nThe CMD files remain available for environments that support them, but GameHub can use LSB-FFXI.exe directly.\r\n");
        return files;
    }
    private static String winPath(File root, File f) throws IOException {
        if (root.equals(f)) return "client";
        String path = FilesEx.relative(root, f);
        if (!path.matches("[A-Za-z0-9 _./()\\-]+")) throw new IOException("Repair scripts need ASCII folder paths without shell characters. Re-zip beneath a simple folder such as PlayOnline.");
        return "client\\" + path.replace('/', '\\');
    }
}
