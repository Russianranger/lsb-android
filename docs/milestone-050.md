# 0.5.0: controls, display and managed server

The first 0.5.0 device attempt exposed an Android display startup race, fixed in 0.5.1. The following device result reaches controller setup but exposes a dependency initialization failure and camera-axis defect. Use the [0.5.2 focused retry](loader-controller-052.md) before continuing the tests below.

## First device test

Install in place; keep the existing prepared client, original xiloader, runtime and Termux server. First launch against that existing server with the accepted renderer and resolution, and confirm login and clean audio. Keep client `30251204_1` and its matching server; current upstream expects `30260904_1`. Do not update client files, xiloader or server source during this test, and do not re-import or re-prepare the accepted client.

After this baseline check, stop the client. Under Controller, save mappings and use Open FFXI gamepad setup. The app maps physical Android controls to 16 joystick buttons, four axes and one D-pad; assign those native inputs to actions in FFXI's configuration utility. The app does not know your preferred game actions. Opening configuration requires the current game session to stop first. Check both sticks, D-pad, buttons and triggers; release them and verify that nothing remains held. Return to the launcher while holding a control, then reopen the display and check that it released. Relaunch against the same Termux server.

Launch with the existing server and accepted renderer. Fast display transfer defaults on and halves raw framebuffer bytes using RGB565; 24-bit color remains available by disabling it. Graphics options include 960×540 and 1280×720 windowed profiles plus restoration of the original registry settings. The renderer's FPS overlay measures game rendering; exported display timing measures the Android display path. Compare the same area/camera before judging the improvement. Audio configuration is unchanged. The small in-game menu replaces the large toolbar.

Keep the accepted resolution for the first 5–10 minute gameplay/audio check. Only then optionally compare 960×540 in the same area. Export Diagnostics after the test and report controller detection, stuck inputs, audio distortion, loading time and perceived smoothness. Managed-server migration is a separate next test using a copy of the existing matching server and logical SQL dump.

## Import the existing server before updating

1. Server → Export existing-server helper. In the Linux environment where the current Termux server runs, execute `python3 lsb-export-existing.py /path/to/server --output /path/to/shared/folder`. Stop game server processes during export; leave MariaDB available. Enter its existing database connection details when prompted. The helper reads the source/binaries and creates a logical database dump; it does not update the server or database. Existing source ZIP plus a complete SQL or SQL.gz dump can also be imported manually. Do not import the raw MariaDB data directory.
2. Import the server ZIP and SQL dump in LSB Android. Set the game database name. Keep local-zone routing enabled for one local map process. Install server runtime (a separate pinned Ubuntu base, followed by toolchain/MariaDB packages).
3. Deploy existing ARM64 binaries, or build and deploy the **same imported revision** if the binaries cannot run against the new environment. Keep navmeshes/ximeshes and scripts in the exported folder. Missing runtime dependencies are reported instead of activating an unusable candidate. Building source may take substantial time and storage.
4. Stop the Termux server before starting the managed server: its game ports must be free. The app's database listens only on loopback port 13306; it does not use the Termux database. Start managed server, then launch the existing client against 127.0.0.1. Readiness means four processes remain alive and the login port answers; only a successful game session verifies world entry.
5. Export Diagnostics if startup fails. Deployment metadata records the source's expected client version, client generation/loader identity and account/character counts. Keep the original Termux server and export files until this test succeeds.

## Source and database updates

Fetch a repository/ref (Russianranger/LSB-server or LandSandBoat/server), or import another source ZIP. Inspect selected source to review CLIENT_VER and mesh presence. Fetching only changes the staged source. Explicitly applying a source/database update takes a full dump, copies current settings, builds the selected source, imports a new database and runs that revision's dbtool migrations/full update with automatic client updates disabled. Failed candidates never replace the active pointer. Rollback selects the previous server and database together; later progress in the newer database does not move into the older snapshot. Export a database backup before deciding to roll back live progress.

No automatic client, patch.ver, PlayOnline or xiloader update is implemented. Current upstream may require a different client; preserving a version number does not establish compatibility. Keep using the matching imported server for the first deployment test.

## Implementation and verification boundaries

The controller helper is an app-owned ARM64 LD_PRELOAD library attached to Wine's native SDL joystick host. It consumes a 64-byte shared state file; no kernel input permission, game-file replacement or keyboard-to-controller workaround is used. Actual Wine/Box64 and PRoot tests enumerate a 32-bit DirectInput joystick and exercise press/release/stale state. Android hardware mapping and FFXI configuration still require device testing.

The server is isolated from the client under server-runtime/. Each generation holds a copied server folder, independent MariaDB data and credentials, plus deployment metadata. SQL import uses a restricted game account and local-only staging socket; original source and dump stay intact. SQL dumps should come from the existing server. Source compilation and dbtool execute imported project code within the managed environment. Failed and previous generations are retained and currently consume storage until a later cleanup feature; preserve several full copies' worth of free space.

Server CI uses real ARM64 MariaDB with synthetic server executables to verify orchestration/import/export/rollback. It does not compile a full LandSandBoat checkout or prove gameplay. The exact user's existing executable dependencies, complete meshes and database migrations need the device test. Runtime installation still needs an on-device check of Android/PRoot behavior.

Artwork generation prompts and original paths: [artwork-050.md](artwork-050.md).
