# 0.7.7: presentation pacing on AYN Thor

This preview contains Beta 0.7 plus the presentation changes in `c52c21d`:
cached Surface buffer geometry, a display-priority reader, and a refresh-rate
vote for the configured 30/60 Hz display cap. It targets presentation jitter;
no improvement in gameplay FPS or sustained pacing has been measured on Thor.

## Install over the existing standard app

Stop the client and managed server, then install **LSB-Android-0.7.7.apk** from
the [original-signed prerelease](https://github.com/Russianranger/lsb-android/releases/tag/v0.7.7-presentation-pacing)
over the existing LSB app. Package `io.github.russianranger.lsb` and the original
`lsb-preview` signing certificate are retained. VersionCode **81** advances
Beta 0.7/code 74 and the separate previews through 0.7.6/code 80.

Do not uninstall, clear storage, reimport or prepare the client, reinstall the
runtime, replace the database, or rebuild/redeploy the server. Retain your
existing backup. Confirm your imported client, active deployment, saved login
and settings remain selected. This is based on Beta 0.7, not the separate
filtered-performance experiment branches; their additional controls are absent.
No runtime, driver, DXVK, FEX/Box64, server, client-data or loader change is
included. Raw Actions APKs use disposable signing and cannot update this app.

## Focused gameplay check

1. If still on Beta 0.7, record one baseline run before updating. Keep the same
   route, character, camera, scene, fan/power mode and similar device temperature
   for each run. Do not uninstall or force a downgrade to obtain a baseline.
2. Use the existing preparation and server. Start normally, wait for Ready and
   enter the existing character. Keep 1280x720, **Native Surface**, the **60 Hz
   display cap**, startup capture off, and the same game cap/graphics settings.
3. Play the same route for 3–5 minutes. Note visible stutter, input response,
   flicker, brightness shifts or black frames. Export Diagnostics immediately
   after the run; compare Surface-post gaps over 50/100 ms with the baseline.
4. Background and return once, then stop and relaunch normally. Confirm the
   Surface recovers with correct size/colours and working controls/audio. Check
   your character's inventory and location remain intact.
5. Optionally repeat with the existing 30 Hz display cap, relaunching for that
   setting. The refresh vote is advisory and may be ignored by Android; it does
   not change the game's own FPS cap or prove the panel actually switched mode.

If a display issue occurs, stop the client and export Diagnostics with the cap,
route, last visible screen and whether it happened after returning to the app.
The existing standard-display fallback remains available. No new runtime
installation or data reset is part of this test. See [validation](validation.md)
for the signing, payload comparisons and automated qualification boundaries.
