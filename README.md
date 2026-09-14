# LSB Android

Client-first Android companion for a future self-contained LandSandBoat / Final Fantasy XI application.

**0.1.0 is an installable preparation and recovery baseline. It does not yet run FFXI inside the app or compile/start LandSandBoat.** The exported Windows scripts are intended for the user's already-working GameHub environment. Their successful execution on the Thor has not yet been verified.

## Available in 0.1.0

- Full client ZIP import with nested-folder discovery, x86 PE-header validation, selected-file SHA-256 inventory, progress, cancellation and foreground transfers.
- Separate import of the user's x86 `xiloader.exe`.
- Exact reference profile transcribed from the user's four GameHub screenshots, including all nine component labels. Reference settings do not pretend that binaries are installed in this app.
- Region-aware repair/diagnostic/launch-script generation, including the 32-bit registry view and COM registration. Export includes the imported client payload; paths are relative to the new export folder.
- Full client update imports preserve existing FFXI `USER/` and PlayOnline `usr/` data by default. Only complete installations are accepted; delta patches are not supported.
- Validated session backup/restore, previous-client rollback, and recovery from interrupted activation renames.
- Support ZIP export with app/device state, key-file hashes, source/probe reports and operation logs. Game payloads, account passwords and Wine registry hives are excluded.
- Server-source staging from a GitHub repository/ref (resolved to a commit before download), or offline ZIP. Missing mesh submodule contents are reported.
- TCP reachability checks of the saved existing server. These do not prove successful authentication or map/UDP readiness.

## First Thor test

1. Install `LSB-Android-0.1.0.apk`. No root or Termux permission is requested.
2. Check **Profile** against the working GameHub settings.
3. On a PC or file manager, ZIP the complete directory containing both `PlayOnlineViewer/` and `FINAL FANTASY XI/`. Include their contents, especially `polcore.dll` (or `polcoreeu.dll`), `FFXi.dll`, `FFXiMain.dll`, and all ROM data. A common `PlayOnline/SquareEnix/` wrapper is supported. Import the installed game, not the multi-part installer EXEs or an entire Wine prefix.
4. Choose **Client → Import client ZIP**. Keep sufficient space for the extracted client while retaining your existing GameHub installation. A later update also retains the currently imported client.
5. If the archive did not contain your working `xiloader.exe`, use **Import xiloader.exe**. Use the same loader you currently run; 0.1.0 does not silently download a newer version.
6. Save the server address and region. For your local server, start with `127.0.0.1` and the correct US/EU/JP installation region.
7. Choose **Validate client and preview repair script**. This checks files and generates a recipe; it does not execute Windows DLLs on Android.
8. Export a session backup and test restoring it. The backup includes imported client files, USER/usr data, saved connection settings and the inventory; it excludes GameHub's separate prefix and runtime binaries.
9. Export a support ZIP from **Diagnostics** and attach it to the next test report.

For the subsequent repair/launch test, **Export prepared client + launch scripts**, extract it into a **new** folder accessible to a backed-up working Wine container, and run `repair.cmd` inside that environment. Inspect `repair.log`, then run `launch.cmd`. Login remains interactive. `registry-before.reg` is a limited install-key snapshot, not a complete undo for COM registration; back up the Wine prefix before applying the recipe. `update-via-playonline.cmd`, when present, opens the official updater interactively. Preparation is source-derived and has not been established as the user's exact historical fix.

Do not uninstall/clear app data before exporting any managed data you want to keep. Android removes app-owned storage on uninstall. Full exports can take a long time because they contain the whole installation. Failed/cancelled exports are closed and the app attempts to delete the incomplete destination.

## Builds and checks

JDK 17, Python 3, Android SDK platform 35 and Android SDK build-tools 35.0.0 are sufficient. No third-party app libraries are needed.

```sh
python3 scripts/test.py
python3 scripts/build-apk.py \
  --android-jar "$ANDROID_HOME/platforms/android-35/android.jar" \
  --build-tools "$ANDROID_HOME/build-tools/35.0.0" \
  --keystore /private/path/lsb-android-preview.jks
```

The direct SDK build is the verified build route. The included Gradle project can also be opened in Android Studio (AGP 8.7.3, compatible Gradle/JDK); no Gradle wrapper is bundled. `scripts/build-apk.py` creates a preview keystore only when absent. Reuse the existing key for updates. The preview alias is `lsb-preview`, the keystore password is `android`. **The key itself is private and is never committed.** Future development should recover the saved `LSB-Android-preview-signing.zip` checkpoint when necessary. The certificate fingerprint for the delivered build is recorded in `docs/validation.md`.

Host tests use synthetic PE fixtures, not proprietary game files. They cover archive traversal/case conflicts/size limits, nested client detection, wrong-architecture rejection, preservation of a valid client after failed imports, USER macro preservation, backup round trips, rollback, interrupted activation recovery, region selection, script arguments, and missing-loader checks. Android code is compiled/dexed and the APK signature verified. Device UI, document-provider behavior and actual Wine execution still need the Thor test.

## Next milestones

1. Qualify this import/preparation/recovery baseline against the real installation and collect the missing historical initialization details.
2. Select a source-complete runtime foundation that can reproduce `proton10.0-arm64x-2` + `Fex-20251029`; prototype Wine initialization, x86 COM probing and the display/input/audio bridge in-app.
3. Reach character selection, world entry, zoning, logout/relaunch and controller operation using the existing server.
4. Add a packaged ARM64 server environment, MariaDB import/migrations, mesh import, foreground process supervision and controlled shutdown.
5. Add the isolated server compiler/toolchain and coordinated updates/rollback. Source, loader, runtime, client and database revisions must remain separately identifiable.

See [working profile and historical evidence](docs/working-setup.md) and [architecture](docs/architecture.md).
