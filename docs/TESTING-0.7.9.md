# 0.7.9: wake capture when the image changes

Install **LSB-Android-0.7.9.apk**, code **83**, from the
[original-signed prerelease](https://github.com/Russianranger/lsb-android/releases/tag/v0.7.9-damage-capture).
Stop the client and managed server first, then install over the existing standard
`io.github.russianranger.lsb` app. Do not uninstall, clear storage, reimport or
reprepare the client, replace the runtime/database or redeploy the server.

This preview changes the presentation capture helper. Instead of discovering
image changes only at fixed polling ticks, it can wake on X11 damage events.
It retains the capture cap, exact pixel comparison and private Surface copy.
Hidden-cursor idle scenes use a bounded heartbeat; visible cursors and systems
without damage events keep periodic checks. Runtime engines, drivers, DXVK,
FEX/Box64, server, client data and loader are unchanged. A locked 30 FPS is not
promised; the goal is less capture-phase delay and less idle work.

## One useful run

1. Start your existing server and wait for Ready. Leave the client stopped.
2. Open **More → Advanced settings and repairs**, then open these section tiles:

   | Tile | Setting | Value |
   | --- | --- | --- |
   | Graphics & display | FFXI display setting | Windowed 1280×720 |
   | Proven fixes | Native Surface display · shared memory | Checked |
   | Proven fixes | 60 Hz display refresh | Checked |
   | Proven fixes | Overlap native capture and presentation | Checked |
   | Proven fixes | Wake native capture on image changes | Checked (new default) |
   | Capture & diagnostics | Capture FFXI startup and graphics | Unchecked |

3. Keep the game FPS cap, graphics settings and Thor fan/power mode unchanged.
   If changing the resolution dropdown, launch through **Quick login** or
   **Launch FFXI** at the top of this same Advanced page so the selection saves.
4. Play the same route for **3–5 minutes**, including camera movement. Note
   smoother/unchanged/worse delivery and any flicker, corruption, input or audio
   issue. Export Diagnostics immediately afterward, before another launch.
5. Background and return once, then stop/relaunch. Confirm the image, controls,
   saved character and settings still work.

For a useful optional comparison, stop the client, uncheck only **Wake native
capture on image changes**, then relaunch and repeat the same route. Export a
second ZIP labelled **image-change wake off**. That restores the 0.7.8 polling
path in this same APK. If delivery stalls or the picture becomes corrupt, stop,
export Diagnostics, and use that switch off for the next launch.

## Reading the results

Android reports include `capture_wake_policy`; producer records identify the
capture policy and event/heartbeat counts. Idle requests and unchanged replies
should fall with event waiting. Their counts are not directly comparable to
0.7.8's fixed 60 Hz checks. Compare changed-frame post intervals, response/wake
timings and visible pacing instead. A 100 ms idle heartbeat is intentional and
does not mean the game rendered a frame late. Request age includes waiting for
an image change; it is not game-frame age. Surface submission counts do not
prove display scanout or game FPS. The November 2 run is an observational
reference, not a controlled benchmark.
