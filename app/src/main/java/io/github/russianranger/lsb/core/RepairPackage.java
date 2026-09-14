package io.github.russianranger.lsb.core;

import java.io.*;
import java.util.*;

/** A reviewable Windows/Wine repair recipe. Generating it never marks a prefix repaired. */
public final class RepairPackage {
    public static Map<String, String> generate(ClientInspector.Snapshot s, LaunchConfig cfg, String profile) throws IOException {
        if (cfg.region.equals("EU") != s.polDll.equalsIgnoreCase("polcoreeu.dll")) throw new IOException("Selected region does not match the imported PlayOnline DLL");
        String pol = winPath(s.root, s.pol), game = winPath(s.root, s.game);
        String prefix = "@echo off\r\nsetlocal DisableDelayedExpansion\r\ncd /d \"%~dp0\"\r\n";
        String vars = "set \"POL=%~dp0" + pol + "\"\r\nset \"FFXI=%~dp0" + game + "\"\r\nset \"REG32=%SystemRoot%\\System32\\reg.exe\"\r\nset \"REGSVR=%SystemRoot%\\System32\\regsvr32.exe\"\r\nif exist \"%SystemRoot%\\SysWOW64\\regsvr32.exe\" set \"REGSVR=%SystemRoot%\\SysWOW64\\regsvr32.exe\"\r\n";
        String branch = "HKLM\\" + cfg.registry();
        String repair = prefix + vars + "echo This registers the imported installation in this Windows or Wine environment.\r\necho It does not install the GameHub components or update game files.\r\necho Export your Wine prefix first if you want to restore all COM registration.\r\npause\r\n" +
            "if exist \"%SystemRoot%\\SysWOW64\\reg.exe\" set \"REG32=%SystemRoot%\\SysWOW64\\reg.exe\"\r\nif not exist \"registry-before.reg\" \"%REG32%\" export \"" + branch + "\" \"registry-before.reg\" /y >nul 2>&1\r\n" +
            "\"%REG32%\" add \"" + branch + "\\InstallFolder\" /v 1000 /t REG_SZ /d \"%POL%\" /f /reg:32 > repair.log 2>&1\r\nif errorlevel 1 goto failed\r\n" +
            "\"%REG32%\" add \"" + branch + "\\InstallFolder\" /v 0001 /t REG_SZ /d \"%FFXI%\" /f /reg:32 >> repair.log 2>&1\r\nif errorlevel 1 goto failed\r\n" +
            "\"%REG32%\" add \"" + branch + "\\" + (cfg.region.equals("JP") ? "Square" : "SquareEnix") + "\\PlayOnlineViewer\\Settings\" /v Language /t REG_DWORD /d " + cfg.language() + " /f /reg:32 >> repair.log 2>&1\r\nif errorlevel 1 goto failed\r\n";
        for (String dll : Arrays.asList("%POL%\\" + s.polDll, "%FFXI%\\FFXi.dll", "%FFXI%\\FFXiMain.dll")) {
            repair += "echo Registering \"" + dll + "\" >> repair.log\r\n\"%REGSVR%\" /s \"" + dll + "\" >> repair.log 2>&1\r\nif errorlevel 1 goto failed\r\n";
        }
        repair += "echo Registration commands succeeded. Test launch separately. >> repair.log\r\ntype repair.log\r\npause\r\nexit /b 0\r\n:failed\r\necho Registration failed. No game launch was attempted. >> repair.log\r\ntype repair.log\r\npause\r\nexit /b 1\r\n";
        Map<String, String> files = new LinkedHashMap<>(); files.put("repair.cmd", repair);
        files.put("diagnose.cmd", prefix + vars + "\"%REG32%\" query \"" + branch + "\\InstallFolder\" /reg:32 > diagnosis.log 2>&1\r\n" +
            "echo Imported POL: \"%POL%\" >> diagnosis.log\r\necho Imported FFXI: \"%FFXI%\" >> diagnosis.log\r\ndir \"%POL%\\*.dll\" >> diagnosis.log 2>&1\r\ndir \"%FFXI%\\*.dll\" >> diagnosis.log 2>&1\r\ntype diagnosis.log\r\npause\r\n");
        if (s.loader != null) {
            String loader = winPath(s.root, s.loader);
            files.put("launch.cmd", prefix + "echo Connecting to " + cfg.host + ". Enter credentials in xiloader.\r\npushd \"%~dp0" + pol + "\"\r\n\"%~dp0" + loader + "\" --server " + cfg.host + " --lang " + cfg.region + "\r\nset \"LSB_EXIT=%ERRORLEVEL%\"\r\npopd\r\necho xiloader exited with code %LSB_EXIT%.\r\npause\r\nexit /b %LSB_EXIT%\r\n");
        }
        File polExe = ClientInspector.child(s.pol, "pol.exe");
        if (polExe != null) files.put("update-via-playonline.cmd", prefix + "pushd \"%~dp0" + pol + "\"\r\n\"%~dp0" + winPath(s.root, polExe) + "\"\r\npopd\r\n");
        files.put("runtime-profile.json", profile);
        files.put("inventory.json", s.inventory);
        files.put("README.txt", "LSB Android 0.1.0 client preparation package\r\n\r\nExtract this entire ZIP into a NEW folder accessible to your working GameHub container.\r\nPreserve your existing container and installation. This package contains your imported game files.\r\n\r\n1. Match runtime-profile.json against the working container settings. These are reference settings; this package does not install them.\r\n2. Run repair.cmd INSIDE that Windows/Wine environment. Review repair.log.\r\n3. Run launch.cmd, if present, to connect to " + cfg.host + " using xiloader. Credentials are entered interactively and are not stored here.\r\n4. diagnose.cmd can record the paths and DLL inventory.\r\n\r\nThe repair recipe is source-derived and requires a Thor test; it is not the recovered historical command sequence.\r\nIt repairs 32-bit installation keys and COM registration, not missing runtimes, DirectPlay, or loader signature compatibility.\r\nGameHub component labels such as 1.0.0 are package labels, not verified upstream DLL versions.\r\nThe app does not yet embed Wine/FEX/graphics or run the server.\r\n\r\nOfficial PlayOnline updating remains interactive and may require prior retail initialization.\r\nDo not treat a successful TCP probe or DLL header check as proof of gameplay.\r\n");
        return files;
    }
    private static String winPath(File root, File f) throws IOException {
        String path = FilesEx.relative(root, f);
        if (!path.matches("[A-Za-z0-9 _./()\\-]+")) throw new IOException("Repair scripts need ASCII folder paths without shell characters. Re-zip beneath a simple folder such as PlayOnline.");
        return "client\\" + path.replace('/', '\\');
    }
}
