# 0.6.2: server runtime acceleration on AYN Thor

This adds only server PRoot syscall filtering to the existing 0.6.1 improvement branch. It does not change server source, script loading, zones, database formats, checkpoints or the compiler cache. Main and the published Beta 0.6 remain unchanged.

## Install and preserve data

Stop the client and managed server. Install the delivered **LSB-Android-0.6.2.apk** over your existing standard LSB app. Keep your installation and data: do not uninstall or clear storage. The delivered APK uses the original Beta 0.6/0.6.1 certificate, the same package and versionCode 73. A raw GitHub Actions APK uses a disposable certificate and cannot update your installed original-signed app.

Your existing client, server deployment, characters, SQL checkpoints and settings should still be selected. No server rebuild, database preparation or redeployment is required. Retain your existing session backup.

## Compare server boot time

1. Open **Server**, turn **Server runtime acceleration OFF**, then **Start managed server**. Time from pressing Start until all services are Ready. Record the map-server's own `ready to work after … seconds` line as well. The login port opening is earlier than full readiness.
2. Export a support ZIP after readiness and name it `server-off.zip`. Stop the server and wait for it to finish.
3. Turn **Server runtime acceleration ON** and start the same deployment. Record both times again. Reopen Server after readiness to see **Last server start: acceleration confirmed** or **compatibility mode**. Export `server-on.zip` before running maintenance operations.
4. Stop cleanly. For a more reliable comparison, repeat OFF then ON once using the same power/performance setting and similar device temperature. Keep the client closed during timing. Avoid comparing a first mesh download with an already prepared launch.
5. With acceleration ON and all services Ready, use your usual quick login and enter your existing character. Confirm its name, inventory and location. Close the client and stop the server normally.

The support ZIP contains `server/proot-acceleration.json` with the requested mode, preflight time, native activation confirmation and fallback reason, separately from the client receipt. Existing startup/status logs contain service readiness and elapsed times.

If the check cannot confirm filtering, the app automatically starts in compatibility mode **before opening the database**. A compatibility result is safe but cannot measure acceleration's benefit. If a later server startup error appears, turn the switch OFF for the next start and retain the support ZIP. The app does not automatically restart a server after database access begins.

## Scope of this test

Checkpoint creation/restore, retention, backups, database recovery and source builds continue using compatibility mode. Their existing 0.6.1 qualification and instructions remain applicable. This phone test does not require repeating those milestones. Script caching, deferred zones and other optimizations are postponed.

Host CI confirms actual ARM64 PRoot filter activation with real MariaDB and native jemalloc server fixtures, readiness, clean shutdown and retained account/character data. It does not predict a particular speedup for the full 300-zone server on Thor; the OFF/ON timings establish that.
