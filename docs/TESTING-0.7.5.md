# 0.7.5: three filtered gameplay performance trials

The 0.7.4 Thor run passed the combined graphics startup check, entered a world
zone and exited cleanly with actual PRoot syscall filtering and two shader
compiler workers retained. The remaining report is frequent game FPS below 30.
Version 0.7.5/code 79 adds three independent opt-in trials for that report;
none is an accepted performance improvement yet.

## Install and retain the working profile

1. Stop the client and managed server. Install the delivered original-signed
   **LSB-Android-0.7.5.apk** over the standard LSB app. The existing package and
   signing certificate are retained. No runtime reinstall, reimport, client
   preparation, loader replacement, server compilation or redeployment is
   required.
2. Keep FEX, Turnip 26, DXVK 2.7.1, **Runtime syscall filtering ON** and **Two
   shader compiler workers ON**. Leave Turnip system-memory rendering and staged
   geometry uploads OFF. Keep the native Surface/shared-memory display and the
   existing 1280×720 display profile.
3. Keep the same border setting, camera/view, game frame cap, HUD, device
   refresh rate, fan/power mode and shader cache for every run. The latest run
   had border removal enabled; these trials do not require changing it. Do not
   clear caches between trials.

## Compare one trial at a time

Use the Optimization trials selector in Graphics and compatibility settings.
Only one choice is active; the three new choices preserve filtering and the
two-worker baseline. Old A/D/E choices reset to Baseline with a notice; their historical results
remain documented and they are not part of this comparison.

| Choice | Single experimental change | Watch for |
| --- | --- | --- |
| Baseline | Accepted filtered profile | Reference FPS and recurring pauses |
| G: non-tiler scheduling | `dxvk.tilerMode = False` | Smoother motion versus increased GPU work or heat |
| H: smaller FEX translation units | `FEX_MULTIBLOCK = 0` | Shorter first-traversal pauses versus lower sustained FPS |
| I: immediate presentation | `d3d9.presentInterval = 0` | Better pacing versus tearing or increased power use; possible no change |

1. Run Baseline, then G, H and I independently. Stop the client before changing
   the selection and relaunch the same character each time. Keep the server
   state and route comparable.
2. For each run, spend 3–5 minutes on the same route and camera pans. Compare
   the first traversal with a second traversal during that same run, preserving
   the warmed shader cache. Include a repeat startup so cold versus warm startup
   behavior can be reported without resetting the runtime or caches.
3. Record the **game HUD FPS**, where the dips occur, first versus repeated pan
   smoothness, and any visual, audio, input or stability change. Surface timing
   counters in Diagnostics are display submissions and are not game FPS.
4. Stop and export a separate support ZIP after each run, labeled
   Baseline/G/H/I. If a trial fails its finite startup check, record the reason;
   do not treat that launch as a performance sample. Return to Baseline after a
   new freeze, rendering problem or consistently worse performance.

No trial changes resolution, texture quality, the game frame cap, FEX arithmetic
precision or the GPU driver. I changes DXVK synchronization only; it does not
remove a frame cap imposed by FFXI itself. G/H/I startup checks must confirm the
requested options and two workers before accepting the experiment, without
disabling the host syscall filter.

## Validation status

Local test, CI and final APK identity results are pending and will be recorded
with the delivered candidate. Synthetic correctness checks do not establish a
Thor FPS gain. See [device evidence and trial rationale](thor-performance-trials-075.md).

Raw GitHub Actions APKs use disposable signing. Use the separately delivered
original-signed APK for an in-place update.
