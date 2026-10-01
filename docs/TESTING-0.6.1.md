# LSB Android 0.6.1 improvement pass

Beta 0.6 on Main preserves the phone-tested 0.5.55 production payload and its
original Android signature. The published APK's internal Android version remains
0.5.55. This branch is the next pass, version 0.6.1 / code 72.

## Changes to exercise

- Launch the activated client twice. Repeat launches reuse its initialized
  inventory and hash the selected critical binaries again, without walking the
  ROM tree. Prepare/initialize/update verification still inspect the full tree.
  Advanced → Inspect all activated client files runs the full inspection on
  demand. Changed DLL contents must still block launch even with unchanged size
  and modification time.
- Build → Install compiler cache upgrades an existing build runtime with ccache.
  New installations include it. Rebuild the fetched source with jemalloc, then
  repeat: source builds use independent staging directories and retain only
  validated outputs. The persistent cache is limited to 2 GiB and isolated by
  compiler contents, compiler options, headers, build hook and source patch
  rules. Build reports include before/after ccache counters. Compiler cache
  contents are reproducible and omitted from full session backups.
- Server → Database checkpoints saves dated compressed SQL including accounts,
  characters, world tables, routines, triggers and events. Choose retention of
  2, 3, 5 or 10; pruning occurs after a successful new save. Restore checks the
  compressed checksum, size and matching deployed build/schema, then imports
  into an independent generation. The current deployment becomes the rollback
  target. The imported SQL selection remains unchanged. A checkpoint is local
  app data; use the full working-combination export before uninstalling or
  moving to another server build. Complete backups include checkpoints.
- Long operations save their latest step, last 12 updates and elapsed time
  periodically. An app-process interruption retains this evidence on reopening,
  with an Interrupted outcome. It does not automatically restart a transfer or
  compile. Estimated remaining time is shown only for a measured client copy
  with a known total. Builds retain elapsed time and live logs.

## Delivery

Only the standard `io.github.russianranger.lsb` APK is built for this pass. The
workflow no longer produces or publishes an LSB Android Restore Test APK.

The original private signing checkpoint was recovered on October 1. Its
certificate matches the published Beta 0.6 APK:
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
The delivered `LSB-Android-0.6.1.apk` uses this original key, the same package
ID and versionCode 72. Install it over the standard Beta 0.6 app; do not
uninstall, clear data, re-import the client or replace the database.

The signed APK is 18,581,050 bytes, SHA-256
`917f78066f9df4c3a8e98afa415206dbbee55de6ddc263388889b4b0c53f1bc8`.
All 72 non-signature entries match CI artifact `11183876093` at implementation
commit `c3aac1e8ba4de6663ea1d7b0e5df7c93c99a2595`. APK v2/v3 signatures,
ZIP integrity, 16 KiB native-library alignment, package/version identity and
packaged runtime/server source bytes were verified. CI still uses disposable
signing: its raw artifact APK is not an update for Beta 0.6. No replacement
key was created, and the Beta 0.6 release and Main were left unchanged.

## Focused AYN Thor test

1. Before updating, stop the client and managed server and use **Save working
   combination** to export a full ZIP to Downloads/SD. Keep the ZIP outside
   the app. Install the original-signed 0.6.1 APK over the standard app.
2. Confirm the active client/server identities, saved login, controller
   settings and characters remain. Start the existing managed server; avoid
   importing, deploying or fetching replacements for this check.
3. Launch normally, enter an existing character and log out. Stop the client,
   then launch the same client again. Record time from Play/Quick login to
   the game window on each launch. Look for brief critical-file checks instead
   of a complete ROM-tree scan. This reduces validation work; it does not
   promise a specific total boot-time improvement.
4. Confirm **Quick login** still uses the saved account and reaches that
   character without re-entering credentials. Keep the working FEX/display
   settings. Stop client and server after the check.
5. In **Server → Database checkpoints**, keep the default five and select
   **Save database checkpoint**. Record checkpoint A's date, counts and size.
6. Start the server/client, make a harmless, recognizable saved change (for
   example, move to another location and log out), stop both, and save B.
   Restore A using **Restore selected database checkpoint**, then start and
   confirm the earlier state. Stop both and restore B to recover the newer
   state. The current server/database pair is retained as the previous
   deployment on each successful restore. Checkpoint restore requires the
   matching server build/schema; it does not migrate unrelated databases.
7. Select **Keep newest 2** and save a third checkpoint. Confirm two remain,
   including the newest save. Changing the dropdown alone does not prune.
8. If source is already fetched, use **Build → Install compiler cache** when
   offered. Build that same source twice with the same worker count and
   jemalloc settings. Check the build report/log for successful output and
   increased ccache hits on the second build. Do not deploy a new build for
   this test. The cache is bounded to 2 GiB; changing compiler/flags/headers
   correctly invalidates affected objects. This check is optional if two
   source compiles would take too long.
9. Export a new full working-combination ZIP; it should include the retained
   checkpoints and omit the reproducible compiler cache. Restore this ZIP
   while both runtimes are stopped, then check login, characters and the
   checkpoint list. To exercise an older Beta 0.6 backup too, keep the new
   0.6.1 ZIP first, restore the older ZIP, verify its saved state, and restore
   the new ZIP afterward. Each restore returns progress to its saved date.
10. During an export/build, background and reopen the app and inspect elapsed
    time/recent progress. Do not force-stop a database restore. Export a
    support ZIP afterward and report launch timings, checkpoint results and
    any cache counters. **Advanced → Inspect all activated client files** is
    available on demand; a full inspection is not required on each launch.

## Automated verification

Local core import/session recovery checks, 197 runtime unit tests and 136 server
unit tests pass, and the production Android Java sources compile against API 35.
CI also exercises Android 13 lifecycle/UI/progress/critical-file checks, real
ccache reuse and invalidation, and real MariaDB checkpoint restoration and
retention. Native runtime qualification remains separate from phone play testing.
