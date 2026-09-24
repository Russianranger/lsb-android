# 0.5.22: game window border and camera-pan experiment

## Device evidence

The 0.5.21 export `lsb-support (3)(6).zip` has SHA-256
`cc71180b8977be18e92131ca38bd985567d465376972c535c8efe0dd5fab7cf1`, session
`e5d2591f-c99e-4ea6-ae5c-1b877353826f`. User reports remaining camera-pan
slowdowns as buildings/NPCs appear. FEX v3, strict64, DXVK 2.7.1, Turnip 26,
1280×720 and 60 Hz SHM capture remain active. Both older tuning switches are
off. Client exits normally; the final native EOF follows shutdown.

All 9,627 connection frames use SHM, with zero socket pixel bytes. Retained
windows from about 140–370 seconds mostly submit 29.1–29.4 frames/s, dipping
to about 25.9–28.8 between 245–295 seconds. These are Surface submissions,
not measured game FPS. Capture averages about 1.3–2.9 ms; Surface post time
is generally lower during the dips, and the Java loop has no long pause.
Across the retained active-play windows, frame-weighted capture is 1.871 ms,
Surface lock 0.199 ms, copy 0.438 ms and post 4.727 ms. During the dip, copy
remains 0.443 ms and post falls to 3.125 ms.
The screenshot shows eight CS synchronizations totaling 7.2 ms. This makes
buffer synchronization worth testing, but does not establish the cause of
all loading stalls. Shader caches already persist; no cache wipe is needed.

The gray border and asymmetric offset are about 6 screen pixels left and
45 top at 1920×1080, consistent with a 4×30 guest-window offset at 1280×720.
Capture reads the entire X root window. Android immersive mode cannot remove
a Windows frame embedded in those pixels. This is a source-based diagnosis;
the phone must confirm the resulting window geometry and appearance.

## Changes

* **Remove game window borders**, enabled by default in Graphics & display.
  Once the owned, visible `FFXiClass` window appears, strip Windows frame
  styles and place the client area at (0,0), preserving its measured width
  and height. Applies only when the FFXI registry reports windowed mode,
  independently of Android fullscreen. No cropping, stretching, DLL injection
  or recurring window scans. Disable and relaunch to restore ordinary behavior.
  Failed partial adjustments restore the original style and outer geometry.
  Fixed numeric before/after geometry and error codes enter the launch receipt;
  no window titles, arbitrary strings or handles are exported.
* **Staged geometry uploads**, off by default under New optimization. Sets
  DXVK 2.7.1 `d3d9.allowDirectBufferMapping = False`. D3D8 forwards buffer
  operations into D3D9. Its staged path can skip CPU/GPU waits for writes when
  no readback is needed, at the cost of extra copying. This is an experiment,
  not a demonstrated FFXI speedup. Older DXVK/software paths leave it inactive.
  Startup verifies DXVK acknowledges the setting and checks 128 rendered pixels
  across software/hardware vertex processing, textures, indexed geometry,
  render targets and alpha. Any failure restores the previous tuning environment.
* Pixel fixtures now cover ordinary buffer locks as well as DISCARD and
  NOOVERWRITE, to exercise the synchronization path being tested.
* The quieter overlay joins Past experiments after no noticeable improvement.
  Existing FEX precision/flags, driver versions, capture, audio, input, client
  files, prefixes and server behavior are retained.

Implementation references: DXVK v2.7.1
[buffer map-mode selection](https://github.com/doitsujin/dxvk/blob/v2.7.1/src/d3d9/d3d9_common_buffer.cpp),
[LockBuffer and upload synchronization](https://github.com/doitsujin/dxvk/blob/v2.7.1/src/d3d9/d3d9_device.cpp),
[configuration](https://github.com/doitsujin/dxvk/blob/v2.7.1/src/d3d9/d3d9_options.cpp),
and Win32 [SetWindowPos](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setwindowpos).

## Validation and device comparison

Local Windows helper compilation, core checks and 65 runtime contracts pass.
CI additionally checks real native-Windows and Wine window geometry at 720p
and 540p, owned-window filtering, one-time behavior, opt-out/fullscreen and
partial-failure rollback; cross-process launch receipts; Android preference
wiring; and staged-buffer pixels in the existing FEX/Box64 hardware-renderer
fixtures. These use synthetic assets, not FFXI or an Adreno GPU.
CI/APK delivery results will be appended once available.

1. Update the APK over the existing app. Keep runtime v3 and the accepted
   settings; no runtime reinstall, client preparation or reimport.
2. Leave Remove game window borders on. Launch with staged uploads off.
   Check the top/left offset, all four image edges, menus and touch alignment
   in both Android fullscreen and windowed display.
3. Walk the same busy route and pan the camera twice: once to encounter the
   area, then again after it has loaded. Note FPS and CS syncs at the same spots.
   Stop and export support ZIP (baseline).
4. Enable only Staged geometry uploads, relaunch, repeat that route and both
   pans, then Stop and export a second ZIP. Leave startup capture off and keep
   stutter diagnostics the same in both runs. Check NPCs, shadows, menus and
   textures. If it is slower or incorrect, turn it off and relaunch.

A fresh area and a repeat pan distinguish first-encounter loading from a
persistent rendering cost. This does not assume either is already diagnosed.

## 0.5.21 CI follow-through

Primary run 35945502024 Box64 and independent PR run 35945505132 FEX/Box64
all completed successfully. Primary FEX's 40-second trace-probe shutdown
timeout remains a recorded failure in the 0.5.21 report; no timeout or assertion
was changed and the exact cause remains unresolved. Do not label that primary
run all green or erase its retained failure evidence.


## APK and validation checkpoint

Signed 0.5.22 (versionCode 38), 18,301,076 bytes, SHA-256
`8a1dfe49bee5e348d4b263da1f1c2492ae0ed898511c478af2585221ff395c93`.
Original signer
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Package/version, ZIP integrity, alignment, v2/v3 signatures, runtime-manifest
hashes and exact CI payload identity pass. APK source 58e550f, run 35961892890,
artifact 10792217570; archive SHA-256
`6656162454ba7d1c876f36b7e272b9340bd6e3c0cb20651fd05ad870959239bb`.
The later cf503a7 changes a synthetic fixture only, not production code.

19 Android tests pass. Native UI preview for the new option was reviewed.
PR run 35962087529 passes real native-Windows geometry and launcher tests,
including exact 720p/540p client size at origin zero, no repeat adjustment,
opt-out/fullscreen, unrelated-window filtering, and partial-failure rollback.
Initial native-Windows assertions caught creation being constrained by the
smaller hosted desktop. The synthetic window now supplies larger maximum
tracking dimensions and asserts the requested size before and after. No
production check, assertion, or timeout was removed to obtain a pass.
ARM64 validation is still running at this checkpoint.
