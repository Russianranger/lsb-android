# FEX arithmetic comparison and graphics-test parity (0.5.14)

## What the Thor result establishes

The 0.5.13 FEX game session was mostly black at character selection and about
5 FPS. Its successful standalone graphics test did not establish FFXI
compatibility. The support bundle confirms native FEX execution, hardware
Turnip 26 on Adreno 740, DXVK 2.7.1, Native Surface at 60 Hz, and 365 actual
game shared-memory uploads. No software-renderer fallback, socket-upload
fallback, or shared-memory attachment failure was recorded.

Game upload copies averaged 0.736 ms and ring waits 0.0038 ms. Late Surface
delivery was 5.04 posts per second, with unchanged scenes between posts.
Those observations place the slow frame producer upstream of Android
presentation; they do not identify the underlying FFXI rendering defect.
The startup observer completed after 8.194 seconds and stayed idle. Audio
continued after three early underruns. The recorded first-chance exceptions
and existing COM warnings do not establish a new exception storm.

## Changes

The standalone Windows check now receives the same selected DXVK version,
display rate, Native Surface, upload, HUD, and graphics-tuning preferences as
the client. Previously it silently used DXVK 2.5.3 and omitted selected
presentation settings. The game's own separate DXVK 2.7.1 preflight already
passed in the failed session, so this parity fix alone is not an FPS fix.

**FEX faster x87 arithmetic · experimental** is a separate, default-off
option. Off explicitly selects full 80-bit emulation; on requests strict
64-bit arithmetic using the existing FEX binary. This tests whether x87
translation cost contributes to the slowdown. Reduced precision can change
game calculations or rendering. No improvement to the black menu or game FPS
has yet been demonstrated on the Thor.

Before starting xiloader, an app-owned PE32 program and its Windows child
check seven fixed environment controls by hash, verify the actual translated
arithmetic precision with `(2^53 + 1) - 2^53`, check exact arithmetic, and record
a finite 100,000-iteration timing sample plus QPC frequency and a short sleep
measurement. The arithmetic sample is not a game benchmark. Diagnostics keeps
the selected mode and these receipts without dumping environment values or
adding recurring gameplay polling. A failed check blocks that FEX launch.

The first CI attempt caught a missing `<wchar.h>` declaration that truncated
the helper's expected 64-bit hash and falsely reported an environment
mismatch. The correction adds that header and makes implicit function
declarations fatal during Windows helper compilation. Synthetic launch and
relaunch tests also assert full80 and strict64 launch-time receipts.

The FEX download, Wine runtime, Turnip drivers, DXVK pairs, display/audio/input
binaries and original Box64 prefix remain unchanged. Existing FEX users do
not need to download the runtime again. No client, original xiloader or server
update, re-import, re-preparation or server migration is part of this test.

## Arithmetic qualification evidence

Push run `35850446810` passes all seven CI gates, and PR run `35850452933`
passes all six applicable gates for implementation
`c861f78836fff89e2bcda80329db1c5fe07a6d86`. The saved FEX receipts verify both
full80 and strict64 arithmetic and Windows-child environment inheritance in
the ARM64 container and PRoot. Synthetic launch/relaunch assertions verify
both modes again with the final launch environment. Graphics, PCM, input,
controller disconnect, crash/Stop behavior and original-prefix preservation
also pass.

The PR run's fixed 100,000-iteration arithmetic sample recorded:

| CI environment | Full80 sample | Strict64 sample |
| --- | ---: | ---: |
| Native Wine in ARM64 container | 7.677 ms | 0.454 ms |
| Native Wine through PRoot | 7.721 ms | 0.460 ms |

These are CPU microtest timings on CI hardware, not FFXI FPS or an Adreno
benchmark. Both modes report QPC frequency 10 MHz and approximately 20 ms for
the 20 ms sleep. Actual precision checks and environment hashes pass.

Evidence artifact: `10746400029`, SHA-256
`bc1c7600ac49ea406477502059afb4ca350649737d760317a85808d4440d91d7`.

## Focused Thor test

1. Install the signed 0.5.14 APK over the existing app. Keep the working Termux
   server, client `30251204_1`, original xiloader and prepared client.
2. Keep FEX selected, Turnip 26, DXVK 2.7.1, 60 Hz, Native Surface and
   shared-memory upload enabled. Keep the same resolution, normal FPS HUD,
   startup capture off, and the sysmem/two-worker experiments off.
3. Enable **FEX faster x87 arithmetic · experimental** and run Windows checks
   once from Runtime. After they pass, launch FFXI and check whether character
   selection draws correctly. If usable, enter the world, repeat the same
   warmed-up route, check audio and controller camera, then exit/relaunch.
   Stop and export Diagnostics.
4. Switch only faster x87 off and repeat a short comparison at the same scene.
   A long repeat of the known 5 FPS failure is unnecessary. Stop and export
   Diagnostics separately. If a Windows check fails, export that result instead
   of repeatedly relaunching.
5. Report menu visibility, approximate FPS/stutters, any rendering changes,
   and which mode each bundle used. Turning FEX off returns to Box64.

Hardware performance and real-client compatibility require this device test;
CI uses synthetic fixtures and lavapipe rather than Adreno hardware.
