# Display smoothness: 0.5.3

## Thor evidence

Input: `lsb-support (6).zip`, SHA-256 `52e3460f0b0394d3456bc483682a58db92791a2d087a65c4e8d1fbb3708d4479`, from the user's accepted 0.5.2 run on AYN Thor, Android 13/API 33.

- Launches `30f28413-2299-4c83-aa7b-febf7d77a3a5` and `cf3ec7e8-27dd-475e-9734-d586ac1b9d99` both complete one clean dependency check, reach the server and FFXI window, then exit cleanly. GameMain/GameStart return zero.
- Controller session `27f756e0-11fa-42b7-8e35-777d9c793aa2` reports seven axes, X/Y/Z/Rz sticks, sixteen buttons and one hat. The user confirms the camera axis and relaunch work.
- Actual renderer is hardware Turnip/Mesa 26 on Adreno 740 with DXVK 2.5.3; 1280×720 overlay/background and RGB565 transport. The old `runtime-profile.json` is a historical GameHub reference, not evidence of the active Wine/Box64 configuration.
- Audio reports three initial underruns, then one additional underrun late in playback, with no subsequent increase before Stop. This supports the user's improved-audio report; audio is not changed.
- `display-performance.json` retains only the last five-second sample: about 0.2 display updates/second near shutdown, 1.88 ms bitmap apply and 0.05 ms Android draw submission. This is not game FPS and cannot describe earlier gameplay. The previous performance file belongs to controller setup, so it is not a gameplay A/B comparison.

There are no continuous DXVK frame-time, CPU/GPU utilization or gameplay display timing records sufficient to prove the remaining bottleneck. Wine exception notifications alone do not establish a fatal error or cause of stutters. Startup capture was enabled; normal-play testing should disable it, without claiming its overhead caused these drops.

## Targeted change

`RuntimeActivity.Screen` formerly retained three bitmaps keyed by exact rectangle dimensions. Cycling among four large shapes evicts and allocates on every update while holding the framebuffer lock. This is a concrete avoidable allocation pattern, not proof of its frequency in the supplied gameplay.

`Rgb565Staging` now lazily allocates one full-frame RGB565 software bitmap and reconfigures its logical size for each rectangle without changing its allocation. It is used only as a source of a completed synchronous software Canvas copy. The backing ARGB framebuffer submitted by the View is never reconfigured or recycled by this path. Input packing honors row stride, with reusable padding storage where required. The existing RGB565/32-bit fallback choice, filtering, scaling, RFB requests and renderer settings remain intact.

The implementation follows Android's [Bitmap reconfiguration constraints](https://developer.android.com/reference/android/graphics/Bitmap#reconfigure(int,int,android.graphics.Bitmap.Config)): reconfiguration must not affect a bitmap still in use by a View or hardware draw. The helper rejects hardware target canvases.

Native Skia/API 33 tests cycle 120 rectangles among 1280×720, 1260×700, 1240×680 and 1220×660, alternating colors. Before: **120 allocations, 207,120,000 cumulative bytes**, reuse assertion fails. After: **one allocation, 1,843,200 bytes**, exact pixels pass. Additional checks cover all width/height combinations from 1×1 to 13×7 with offset copies, changing shape, odd widths and untouched surrounding pixels. This measures allocation reduction in a synthetic workload; it does not measure Thor game FPS or GPU speed.

## Diagnostics

`display-performance.json` format 2 includes the session ID, the latest sample and up to 180 time windows (about 15 minutes at a five-second cadence). Each connection is separately identified and has relative elapsed times and cumulative counts. Completed gameplay windows survive an idle/shutdown sample. Display close records the partial final window.

Each window records update/draw rates and counts, raw pixels/bytes, receive/decode/conversion/apply/Android draw costs, maximum decode/draw time, maximum inter-update gap and counts above 50/100 ms, last-update age, display size/mode, the display-cap preference at connection time, and staging bitmap allocation totals. The cap preference is not a measured frame rate and can differ from the running process if settings were changed after launch. Bytes account for 16-bit versus 32-bit transport. Extra Android invalidations do not count as unique frames. Long update gaps can mean an unchanged scene; Android draw time measures submission/lock wait, not GPU presentation or game frame time. Connection cumulative counters remain cumulative across sampled windows; staging totals describe the current framebuffer allocation lifetime.

Serialization and atomic file replacement run on a single background thread with at most one queued write. If the writer falls behind, a newer queued snapshot replaces an older one; diagnostics cannot build an unbounded backlog on the UI thread. Per-connection totals continue across omitted windows. Log rotation and stale-session checks share the writer's lock, so a closing previous session cannot contaminate the next session's diagnostics. Tests cover idle retention, unique draws/timing, byte counts, stale queued writes and bounded history below the 1 MiB export limit.

No Wine/Box64, gamepad helper, audio, native Windows helper, client data, loader or server code changes are included. Full CI and final signed APK identity are recorded in [validation](validation.md).

## Focused Thor comparison

1. Install 0.5.3 over 0.5.2; keep app data and the accepted preparation. Start the existing matching Termux LandSandBoat server. Keep client `30251204_1` and the original loader/server source; no managed-server migration yet.
2. Retain Turnip 26, the existing 1280×720 profile and display cap, with Fast display enabled. Turn off **Capture FFXI startup result** for normal play and keep the DXVK FPS HUD enabled. Keep the same Thor power mode for comparisons.
3. Play in a repeatable area for 3–5 minutes: stand still, move, rotate both camera axes, then repeat the route. Note HUD FPS range, whether drops settle after the first pass, and whether audio/input remain clean.
4. Export Diagnostics immediately after the run, before another controller/setup/launch session. Include the approximate elapsed time of the worst stutter and whether it occurred during movement, zone loading or a stationary scene. Check Stop/relaunch as a separate regression check.
5. Only after the same-settings run, optionally repeat at 960×540 in the same area and export a separate bundle. A clear improvement helps narrow rendering/transfer pressure; no improvement points toward other work. Do not change multiple settings at once.

The next decision should use the retained timing windows and the actual DXVK HUD observations. Host tests establish pixel correctness/allocation reuse and CI protects the baseline; smoother Thor gameplay remains a device acceptance criterion.
