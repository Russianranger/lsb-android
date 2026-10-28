# 0.7.8: overlap capture and Android presentation

Install **LSB-Android-0.7.8.apk** (versionCode **82**) from the
[original-signed prerelease](https://github.com/Russianranger/lsb-android/releases/tag/v0.7.8-presentation-overlap)
over the standard `io.github.russianranger.lsb` app. Stop the client and managed
server first. Do not uninstall, clear storage, reimport/reprepare the client,
reinstall the runtime, replace the database or rebuild/redeploy the server.
Your existing imported client, deployment, login and settings should remain.

This preview builds on 0.7.7. It requests the next capture after the pixels have
been copied into Android's private Surface buffer, before Android finishes
submitting it. The capture can overlap a submission wait, with at most one
request outstanding. The guest protocol and all runtime/driver/Windows/server
components remain unchanged. A locked 30 FPS or measured speedup is not claimed.

## One useful test

1. Start your existing server and wait for Ready. Leave the client stopped.
2. Open **More → Advanced settings and repairs**. Tap the section tiles marked
   **Open +** and check these exact controls:

   | Tile | Control | Value |
   | --- | --- | --- |
   | Graphics & display | FFXI display setting | Windowed 1280×720 |
   | Proven fixes | Native Surface display · shared memory | Checked |
   | Proven fixes | 60 Hz display refresh | Checked |
   | Proven fixes | Overlap native capture and presentation | Checked (default) |
   | Capture & diagnostics | Capture FFXI startup and graphics | Unchecked |

3. Keep the other game/graphics settings and Thor fan/power mode unchanged.
   If you changed the resolution dropdown, launch using **Quick login** or
   **Launch FFXI** in **Play FINAL FANTASY XI** at the top of this same Advanced
   page: this build saves the dropdown when launching there. Returning to the
   main Play tab first can discard that dropdown selection.
4. Play the same route as your 0.7.7 run for **3–5 minutes**, including camera
   movement. Note smoother/unchanged/worse pacing, approximate FPS and any
   flicker, wrong colours, tearing, control or audio issue. Export Diagnostics
   immediately after this run, before another launch.
5. Background and return once, then stop/relaunch normally. Confirm display,
   controls, audio, saved character and settings still work.

One run is enough for the next analysis. The October 28 support ZIP provides
an observational 0.7.7 reference, not a controlled A/B result. If an optional
same-build comparison is useful, stop the client, uncheck **Overlap native
capture and presentation**, relaunch and repeat the same route. Export a second
ZIP and label it **overlap off**. This restores serial presentation without an
APK downgrade or a data reset. Stop/relaunch is required after switching it.

If the picture becomes corrupt or delivery stalls, stop the client, export
Diagnostics and use overlap off on the next launch. The existing standard
display fallback also remains available.

## What the added diagnostics establish

`native-display-performance.json` format 3 records the active request policy,
actual Android display mode/refresh rate separately from the requested 60/30 Hz
cap, request wait/age/overlap timings including unchanged responses, post-gap
buckets and detailed events above 50 ms. Only bounded numeric records are added;
no frame images, game files or credentials are captured.

Request age includes producer pacing and any response waiting while Android
posts the previous image. It is not the age of the game frame. Post-gap timing
measures Surface submission, not confirmed screen presentation or game FPS.
The 60 Hz vote still does not force a panel-mode switch. Vsync scheduling is
not changed in this preview; actual refresh observations and overlap results
will inform a separate experiment. Longer unchanged-image pauses still require
distinguishing game rendering from capture/change notification.
