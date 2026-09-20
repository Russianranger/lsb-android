# LSB Android

Client-first Android companion for a future self-contained LandSandBoat / Final Fantasy XI application.

**0.3.0 adds in-app PlayOnline/FFXI preparation.** It makes an independent working installation and cloned Windows prefix, registers the selected client, tests its real COM interface identifiers, and activates the pair only after successful checks. Failed preparations remain retryable; the original import and previous validated preparation are preserved. Login/gameplay and server integration remain later milestones.

Install the update over the existing app and use **Client → Prepare imported client**. The 0.2.0 runtime milestone has passed on the Thor; no repeated triangle test, runtime download or client import is needed. Keep Turnip 26. Allow room for another full client plus a Windows prefix (roughly 18 GiB for the current reported import; exact space checked in-app). See [0.3.0 preparation and test instructions](docs/client-initialization-030.md) and [current handoff](docs/HANDOFF.md).

The accepted foundation is Wine 10 / Box64 0.4.4 with an x86 DXVK D3D8+D3D9 pair and embedded display/audio/input. It is distinct from the historical Proton/FEX setup; actual proprietary client initialization still needs device testing. Individual user-supplied x86 prerequisite installers can run in a failed staged preparation. Existing session backups retain the original imported client, not prepared Windows generations.

## Available in 0.1.3

- Full client ZIP import with nested-folder discovery, x86 PE-header validation, selected-file SHA-256 inventory, progress, cancellation and foreground transfers.
- PlayOnline version/folder selector when an archive contains multiple `polcore.dll` / `polcoreeu.dll` files. Extraction pauses for your choice and survives app restart; finish or discard it without re-extracting. The saved choice follows validation, repair, backup/restore and rollback.
- PlayOnline installation-root detection for `PlayOnlineViewer/viewer/com/polcore*.dll`, distinct from its COM DLL path. Patch-cache entries are labelled and sorted last; repair/export refuses them while imports and backups stay available.
- Native 32-bit `LSB-FFXI.exe` export for GameHub, with Repair registration, Launch FFXI and PlayOnline updater buttons. The helper uses Windows APIs and child processes directly; no CMD support, .NET or added Visual C++ runtime is required. A small launcher-update ZIP can update an existing prepared package without copying game data.
- Separate import of the user's x86 `xiloader.exe`.
- Exact reference profile transcribed from the user's four GameHub screenshots, including all nine component labels. Reference settings do not pretend that binaries are installed in this app.
- Region-aware repair/diagnostic/launch-script generation, including the 32-bit registry view and COM registration. Export includes the imported client payload; paths are relative to the new export folder.
- Full client update imports preserve existing FFXI `USER/` and PlayOnline `usr/` data by default. Only complete installations are accepted; delta patches are not supported.
- Validated session backup/restore, previous-client rollback, and recovery from interrupted activation renames.
- Support ZIP export with app/device state, key-file hashes, source/probe reports and operation logs. Game payloads, account passwords and Wine registry hives are excluded.
- Server-source staging from a GitHub repository/ref (resolved to a commit before download), or offline ZIP. Missing mesh submodule contents are reported.
- TCP reachability checks of the saved existing server. These do not prove successful authentication or map/UDP readiness.

## Historical 0.1.3 preparation procedure

The GameHub-dependent steps in this section are superseded by the [fresh in-app client milestone](docs/HANDOFF.md). Import, inspection and session backup remain useful if the app-owned files or original ZIP survive. Do not uninstall or clear LSB Android to start this migration.

1. Install `LSB-Android-0.1.3.apk`. No root or Termux permission is requested.
2. Check **Profile** against the working GameHub settings.
3. On a PC or file manager, ZIP the complete directory containing both `PlayOnlineViewer/` and `FINAL FANTASY XI/`. Include their contents, especially `polcore.dll` (or `polcoreeu.dll`), `FFXi.dll`, `FFXiMain.dll`, and all ROM data. A common `PlayOnline/SquareEnix/` wrapper is supported. Import the installed game, not the multi-part installer EXEs or an entire Wine prefix.
4. Choose **Client → Import client ZIP**. If more than one PlayOnline DLL is found, use **Choose PlayOnline version** at the top of the Client tab, select its region, and tap **Use selected version and finish import**. Choose a **Client files** option outside `patchfiles`; **Patch cache** entries are kept in backups but are not suitable for repair/launch packages. Each option includes the full relative path; `polcoreeu.dll` identifies EU, while `polcore.dll` can be US or JP. Keep sufficient space for the extracted client while retaining your existing GameHub installation. A later update also retains the currently imported client.
5. If the archive did not contain your working `xiloader.exe`, use **Import xiloader.exe**. Use the same loader you currently run; 0.1.3 does not silently download a newer version.
6. Save the server address, region and PlayOnline version under **Connection and repair**. You can change the saved version here later. For your local server, start with `127.0.0.1` and the correct US/EU/JP installation region.
7. Choose **Validate client and preview repair script**. This checks files and generates a recipe; it does not execute Windows DLLs on Android.
8. Export a session backup and test restoring it. The backup includes imported client files, USER/usr data, saved connection settings and the inventory; it excludes GameHub's separate prefix and runtime binaries.
9. Export a support ZIP from **Diagnostics** and attach it to the next test report.

For the repair/launch test, **Export prepared client + GameHub launcher**, then extract the entire ZIP into a new folder accessible to a backed-up or cloned working Wine container. In GameHub add **LSB-FFXI.exe** as the game executable, using the known-working runtime settings. No command-line arguments are needed. In its window, click **Repair registration**, then **Launch FFXI** if repair succeeds. Account entry remains in xiloader. Send `lsb-launcher.log` from beside the EXE for troubleshooting; it is separate from the Android support ZIP.

If the prepared client is already extracted, use **Export launcher update only** and extract that small ZIP into the same package folder, beside its existing `client/` directory. It contains the EXE, configuration and recipes but no game files. Keep `LSB-FFXI.exe`, `lsb-launcher.ini` and the `client/` directory together.

The helper records prior install/language registry values in `registry-before.txt` for diagnosis, not automatic rollback. COM registration can be partial if a DLL fails. Back up the Wine container itself before repair. The GUI remains open while xiloader runs so GameHub's entry process stays alive. **Open PlayOnline updater** starts the official updater interactively; prior retail initialization may still be needed. Optional CMD recipes remain included.

Updating from 0.1.1: no re-import is necessary. If your saved selection includes `patchfiles`, choose the **Client files** copy under **Connection and repair → PlayOnline version**, save, and regenerate the repair preview. The installed copy normally has a path such as `PlayOnlineViewer/viewer/com/polcore.dll`; the import root is detected separately at `PlayOnlineViewer/pol.exe`. Export Diagnostics support ZIP after validation. Older repair previews are cleared by this upgrade so a stale registry path is not reused.

Updating from 0.1.0: install the new APK over the existing app. The signing certificate and application ID are unchanged. Retry the ZIP once: 0.1.0 deleted its failed extraction. Version 0.1.1 keeps an ambiguous extraction for selection.

Do not uninstall/clear app data before exporting any managed data you want to keep. Android removes app-owned storage on uninstall. Full exports can take a long time because they contain the whole installation. Failed/cancelled exports are closed and the app attempts to delete the incomplete destination.

## Builds and checks

JDK 17, Python 3, Android SDK platform 35, Android SDK build-tools 35.0.0 and MinGW-w64 i686 GCC are required. On Ubuntu install `gcc-mingw-w64-i686-posix`; `LSB_MINGW_CC` can select an existing compiler. Both direct and Gradle builds compile the helper from `windows/launcher.c` and package it as an Android asset. No third-party Android app libraries are needed.

```sh
python3 scripts/test.py
python3 scripts/build-apk.py \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --keystore /private/path/lsb-android-preview.jks
```

The direct SDK build is the verified build route. The included Gradle project can also be opened in Android Studio (AGP 8.7.3, compatible Gradle/JDK); no Gradle wrapper is bundled. `scripts/build-apk.py` creates a preview keystore only when absent. Reuse the existing key for updates. The preview alias is `lsb-preview`, the keystore password is `android`. **The key itself is private and is never committed.** Future development should recover the saved `LSB-Android-preview-signing.zip` checkpoint when necessary. The certificate fingerprint for the delivered build is recorded in `docs/validation.md`.

Host tests use synthetic PE fixtures, not proprietary game files. They cover archive traversal/case conflicts/size limits, nested client detection, wrong-architecture rejection, preservation of a valid client after failed imports, USER macro preservation, backup round trips, rollback, interrupted activation recovery, region selection, duplicate PlayOnline versions/folders, pending selection restart/retry/cancel, saved selection round trips, script arguments, and missing-loader checks. Android code is compiled/dexed and the APK signature verified. Device UI, document-provider behavior and actual Wine execution still need the Thor test.

## Next milestones

1. Build an app-owned runtime and fresh Windows prefix with in-app display/input/audio. Use open x86 registry/COM, Direct3D 8 and process-lifecycle probes before relying on proprietary client files. Keep the captured Proton/FEX setup as historical evidence; a different runtime is a new compatibility candidate, not an equivalent preset.
2. Validate any surviving app import or client ZIP, then initialize PlayOnline/FFXI registration and dependencies in a staged prefix. Support official installers inside the app when reconstruction requires them. Preserve a stopped, usable environment before activation or repair.
3. Provide native account entry for the established xiloader 2.0 autologin flow, then reach character selection, world entry, zoning, logout/relaunch and controller operation against the existing Termux server. Exclude credentials from diagnostics and normal configuration exports.
4. Add a packaged ARM64 server environment, MariaDB import/migrations, mesh import, foreground process supervision and controlled shutdown.
5. Add the isolated server compiler/toolchain and coordinated updates/rollback. Source, loader, runtime, client and database revisions must remain separately identifiable.

See [working profile and historical evidence](docs/working-setup.md) and [architecture](docs/architecture.md).

Native Windows integration checks compile a separate test-only helper that redirects its HKLM calls to `HKCU/Software/LSBLauncherTests`, plus stub registration DLLs and a stub loader. GitHub Actions checks US/EU/JP keys, real DLL registration calls, failures, working directories, Unicode package paths, argument passing and child exit codes. The test mode is not compiled into the delivered helper. This does not validate the proprietary DLLs or GameHub Wine behavior.
