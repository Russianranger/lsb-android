# LSB Android 0.5.44: PlayOnline repair test

Version 0.5.44 / code 60 removes the launcher's two-hour limit on the
interactive PlayOnline viewer, including a viewer that restarts during an
update. Preparation checks still have time limits, and **Stop updater** remains
available.

**Hardware PlayOnline graphics** adds an updater-only Zink/OpenGL path through
Turnip 26. Before using it, the app checks the physical GPU, native OpenGL and
actual 32-bit DirectDraw rendering. If those checks fail, it continues with
compatibility graphics. Diagnostics records which path was selected and adds
bounded viewer-thread CPU measurements.

The Box64 and FEX PlayOnline CI checks exercise real rendering, startup,
restart and Stop, but use a software Vulkan device. They do not establish
repair speed on the AYN Thor or prove that an online repair has completed.

## Install the matching APK

- `LSB-Android-0.5.44.apk` updates the regular LSB Android app.
- `LSB-Android-Restore-Test-0.5.44.apk` updates the separate LSB Restore Test app.

Both retain the existing signing certificate. Install over the matching app;
keep its data. Use the app that already contains your staged update. For a
first update test, use Restore Test with your restored session.

## Short first comparison

1. Stop the client/updater and managed server. Install the matching APK and
   open **Client → Client update · PlayOnline**.
2. Keep the same **Updater runtime** as your last repair attempt. Leave
   **Updater runtime acceleration** and **Hardware PlayOnline graphics** on.
3. Tap **Open or resume PlayOnline update** to reuse the staged copy. Complete
   any viewer update, then use **Check Files → FINAL FANTASY XI → Check Files →
   File Repair** as appropriate for the stage shown by PlayOnline.
4. Record the file count or percentage when checking/repair begins and again
   after five minutes. Note whether the menus remain responsive. If progress
   is still very slow, stop and export a support ZIP from **Diagnostics** at
   this point; another hour-long run is unnecessary for the first measurement.
5. If repair is progressing well, allow it to finish. After PlayOnline reports
   completion, choose **Exit Viewer**, then **Verify completed update**.

Keep the staged copy when sending diagnostics. The launcher no longer ends a
healthy session at two hours. A successful repair does not automatically
activate the updated client: activation remains a separate action, and the
updated client still needs a matching server and xiloader.

For a graphics comparison, stop the updater, turn **Hardware PlayOnline
graphics** off, and reopen it with the same engine. Compare similar repair
stages where possible; downloaded files and filesystem caching can affect a
second run. Export a separate support ZIP for each attempt.
