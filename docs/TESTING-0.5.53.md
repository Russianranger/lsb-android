# 0.5.53: Play, guided setup and runtime presets

## Restore Test → ordinary app

1. In the working Restore Test installation, stop the client and server and export
   a **complete session backup** to Downloads or another location outside app data.
2. Install `LSB-Android-0.5.53.apk` in the ordinary app installation. On Play, choose
   **Continue setup → Restore a complete backup**, then choose that session ZIP.
3. Wait for restore completion. The guide should recognize the client, installed
   runtimes and deployed server. Existing runtime preferences are preserved.
4. Open Play. Confirm the managed server is selected and the gameplay preset
   reflects your saved settings. Enter your account/password and tap **Play**.
5. The page should show server startup progress until all services are ready,
   then open the client display automatically. Login, zone, fight and logout.
6. The server remains running after logout. Stop it on Play or Server. Export a
   support ZIP after the test.

The Restore Test APK uses its existing separate package and can update the test
installation independently. Keep the working test installation until the ordinary
installation and restored player data are verified.

## Source build and client update

- **Play → Updates and backups → Build or update server source** opens the existing
  source/build/database/check/deploy workspace. Jemalloc, build worker selection,
  receipts, staging checks and database rollback remain in place.
- **Play → Updates and backups → Update client with PlayOnline** opens the staged
  updater. On **Gameplay and updater presets**, select Box64 for PlayOnline and
  apply it. Gameplay may remain on the Thor FEX preset independently.
- Complete PlayOnline repair, close it, verify and activate the staged update.
  Follow the source/client/loader compatibility requirements before playing.
- Test Play again after deployment/update. Confirm the expected server/client pair
  is active, gameplay uses FEX, and the updater used Box64.

## Fresh setup and cancellation

- A fresh setup offers **Connect to an existing server**, **Create a server on this
  device**, or **Restore a complete backup**. External servers skip server builds.
- The local path resumes from installed runtime, client import, loader, region,
  preparation, server tools, source build, database, check/deploy and account.
- The Thor preset uses the previously accepted FEX / Turnip 26 / DXVK 2.7.1,
  720p, native/shared-memory display, 60 Hz display cap, two shader workers and
  syscall filtering. Applying it is explicit for existing/restored installations.
- Cancel Play during server startup. The client must not launch, login credentials
  must be discarded, and file operations must become available again. The server
  may remain running and can be stopped on Play/Server.
- Change tabs or rotate during startup; waiting must continue under the foreground
  service and the Play page must recover its live state.

Automated checks cover setup decisions, independent preset settings, complete
cross-package restore, readiness/failure/timeout/cancellation and UI previews.
Real Android document-provider transfers and actual gameplay remain device checks.
