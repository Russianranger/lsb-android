# Native Surface trial: 0.5.5

## Why another settings change is insufficient

The user reports roughly the same 6–28 FPS swings with startup capture off at
1280×720 and 960×540; 540p improves the game HUD by only about 2 FPS. The latest
local `lsb-support (10).zip` (SHA-256
`b07fddd2ab0ac061240f81d527374d5a99746d9abdded6d804cca0b1718d61b1`)
confirms capture disabled in both sessions, successful exits, the accepted
preparation, original loader and hardware Turnip. Single bitmap allocation remains
stable. The previous compression-off test was far worse, so retain compression
for the fallback path.

The 75–140 second portions show 9.76 and 18.09 display updates/s respectively,
with receive/decode times of 67.82/13.64 ms at 720p and 25.96/5.33 ms at 540p.
Receive includes decoding and waiting; do not add those counters. Different scene
and slowdown timing makes this an uncontrolled comparison. Display update rates
are not game FPS. These logs do not isolate the game CPU, GPU readback, encoding,
scheduling or temperature. No massive FPS gain is claimed from these figures.

## Change

0.5.5 defaults fresh FFXI launches to **Native Surface display · shared memory**.
A separate ARM64 X11 capture helper publishes pixels into an app-private mapped
file; only a 32-byte control header crosses its socket. JNI copies directly into
an Android Surface. This removes compression/decompression, socket pixel transfer
and Java Bitmap updates from the active display path. X11/GPU readback and a
native pixel copy remain. [Implementation/provenance](../native/presentation/README.md).

The same RFB connection carries mouse/keyboard input and resumes the existing
compressed display automatically if native presentation fails. Existing Fast
display/compression options then apply. The Native Surface checkbox is sampled
on a new launch; changing it requires Stop client and Launch FFXI.

The pinned Wine/Box64, Turnip, DXVK, audio, gamepad, loader/preparation and matching
Termux server remain unchanged. Controller setup uses the established display.
The purple icon remains. No client/xiloader/server-source update or preparation
migration is included.

Diagnostics add `native-display-performance.json` (bounded 180 windows, written
off the UI thread) and `native-display.log` (capture mode, fixed numeric counters).
Metrics distinguish Surface posts, capture, request wait, native copy, lock/post,
unchanged frames and zero socket pixel bytes. They are not game-FPS measurements.
The running viewer identifies Native Surface or the reason for fallback.

## First Thor test

1. Install 0.5.5 over the existing app. Keep the working Termux server, client
   `30251204_1`, original xiloader and accepted preparation. Do not uninstall,
   re-import, re-prepare or update source toward `30260904_1`.
2. Stop the client. Choose Windowed 1280×720, the accepted Turnip driver, 30 Hz
   display cap, Native Surface on, startup capture off and DXVK HUD on. Leave
   Fast display and compression enabled for fallback.
3. Launch FFXI and check the viewer says **Native Surface · shared memory**.
   If it says **Using previous display**, export Diagnostics and report that
   message; the new path was not active.
4. Repeat the same 3–5 minute route/camera movements. Note game-HUD FPS range,
   longest pauses, audio and both camera axes. Stop, relaunch once, then export
   Diagnostics immediately. Physical game performance is still unverified.
5. If Native Surface regresses, stop, turn only Native Surface off and launch
   again to restore the existing compressed display. Compare the same route.
