# 0.5.12: Turnip rendering and DXVK compiler scheduling

## Thor evidence

The user reports improvement with DXVK 2.7.1, but remaining FPS slowdowns.
Input: `lsb-support (8).zip`, SHA-256
`fd9f7d7e52c46f7a5a8a9493acce1f24b4a5864941e4bc6bd6bd1e0951e6d2b3`.
Current session `5651d729-f4b8-4886-a8a0-8c4eb86f2e15`; previous
`551237a1-db5d-4454-b7d0-5a509ad5b068`. Both are app 0.5.11, 1280×720,
60 Hz, Native Surface, detailed DXVK HUD and hardware Turnip 26 / Adreno 740.
Both exit cleanly. The startup observer stops sampling after approximately
13/14 seconds; the accepted periodic-stutter fix is intact.

| Measurement | Previous DXVK 2.5.3 | Current DXVK 2.7.1 |
| --- | ---: | ---: |
| Actual game SHM uploads | 3,236 | 5,439 |
| Socket fallbacks / attach failures | 0 / 0 | 0 / 0 |
| Average upload copy | 0.776 ms | 0.811 ms |
| Average ring-slot wait | 0.0049 ms | 0.0049 ms |
| Maximum ring-slot wait | 0.416 ms | 0.647 ms |
| Surface posts/s, 70–140-second windows | 24.44 | 25.16 |
| Post gaps over 100 ms, same windows | 22 | 8 |
| Post gaps over 250 ms, same windows | 0 | 0 |

These are distinct, uncontrolled routes and Surface delivery measurements,
not measured game FPS or a causal benchmark. The current run still has windows
around 20–23 posts/s. The counters establish that the game, not only preflight,
uses SHM uploads. Tiny slot waits do not implicate the upload ring. They do not
measure GPU execution/readback, translated game CPU work or shader compilation.
The native capture path remains MIT-SHM with zero socket pixel payload.
The five largest current-run gaps in that window are 130–149 ms, with six or
seven unchanged responses between posts. Their capture cost is 1.6–6.0 ms and
Surface post cost 0.3–0.8 ms once the next image arrives. This supports looking
upstream of Android submission, while not distinguishing stationary scenes,
game CPU stalls, shader work and GPU/readback delays.

The startup D3D8 compatibility log reports **eight compiler workers** and
immediate Vulkan presentation. This is a preflight property; it is not proof of
the game's present interval or of eight simultaneously busy workers during a
slowdown. It supports testing worker contention, not claiming it as the cause.
The Box64 custom-host-preload warning is not a failure here: actual game upload
counters advance and the controller baseline remains working.

## Changes and boundaries

Two independent Client → Graphics and launch options controls, both default off:

* **Turnip system-memory rendering · experimental** sets only `TU_DEBUG=sysmem`
  for Turnip 26. Mesa's normal tile-memory decision is bypassed. Rendering stays
  on Adreno; this is unrelated to software rasterization and separate from SHM
  frame transfer. It can reduce tile setup/resolve overhead on some workloads,
  but can lose the bandwidth benefit of tile memory on others.
* **DXVK: use two shader compiler workers · experimental** sets only
  `dxvk.numCompilerThreads = 2` through `DXVK_CONFIG`. It preserves other
  existing config keys and files. The default uses available CPU cores.
  Fewer workers can reduce contention while compiling but lengthen warm-up.
  No shader compilation or draw calls are skipped; this is upstream DXVK,
  not an async fork.

The app first establishes the existing renderer and selected DXVK pair.
Requested tuning then runs the finite, owned eight-frame D3D8 checker. Compiler
tuning additionally requires the actual `Using 2 compiler threads` output.
Failure/timeout restores both prior environment values, retaining the selected
DXVK version, controller preload and upload path. Stop remains cancellation.
Software rendering skips both controls; Turnip 24 skips the Turnip experiment.
Diagnostics record requested versus active controls, check and fallback reason
in `runtime-state.json` → `graphics_tuning`, with `graphics-tuning.log` before
login. The report records applied settings and preflight success, not a GPU
profiler's proof of every render pass or a performance improvement.

No driver/DXVK/rootfs upgrade, client/server/xiloader update, cache deletion,
client INI edit, preparation change, recurring process scan or gameplay log
flush is introduced. Existing 0.5.11 graphics settings and binaries remain.
Both new switches off restore 0.5.11 behavior without reinstalling.

Source audit:

* [DXVK 2.7.1 compiler configuration](https://github.com/doitsujin/dxvk/blob/v2.7.1/dxvk.conf)
  and [worker implementation](https://github.com/doitsujin/dxvk/blob/v2.7.1/src/dxvk/dxvk_pipemanager.cpp).
* [Mesa 26.0.0 command-buffer mode selection](https://gitlab.freedesktop.org/mesa/mesa/-/blob/mesa-26.0.0/src/freedreno/vulkan/tu_cmd_buffer.cc)
  and [debug option parser](https://gitlab.freedesktop.org/mesa/mesa/-/blob/mesa-26.0.0/src/freedreno/vulkan/tu_util.cc).
  Audited release source archive SHA-256:
  `2a44e98e64d5c36cec64633de2d0ec7eff64703ee25b35364ba8fcaa84f33f72`.

## Validation

Local runtime contracts cover independent switches, disabled-path environment
identity, restoration after failed/unconfirmed preflight, scope, strict input
validation and Stop propagation. CI exercises baseline settings, both enabled
with actual DXVK 2.7.1, both enabled after the 2.5.3 compatibility fallback,
and software-mode skipping. It requires the main Wine rendering process to
confirm the two-worker config while pixels/input/PCM/SHM and clean exit pass.
CI uses lavapipe: it cannot qualify Adreno performance or forced-sysmem pixels
on physical hardware. On-device preflight is required on each tuned launch.

**All six CI gates pass** for `ae984d3ab8e473fcccf650c83aced0d2fc4c98d5`
in [push run 35803742003](https://github.com/Russianranger/lsb-android/actions/runs/35803742003):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. [PR run 35803744607](https://github.com/Russianranger/lsb-android/actions/runs/35803744607)
passes all five applicable gates; release correctly skips. No current CI gate
is pending or failing.

Both full runtime runs confirm two compiler workers in the actual Wine rendering
process with DXVK 2.7.1 and the 2.5.3 compatibility fallback, alongside real
D3D8 pixels, shared-memory uploads, PCM, input, clean exit and cancellation.
Both pass Linux and PRoot controller enumeration, all four axes, buttons/hat,
release and stale-input neutralization. Startup-only observer idle/Stop/crash
checks remain green. The native display/upload fixtures, 116 core checks,
RGB565, 90 ZRLE checks, native bounds, 49 Python runtime contracts, five server
contracts, 15 Android tests and 36 native Windows checks pass. Real MariaDB
import, failed-update preservation, update, rollback and process lifecycle pass.
CI validates compatibility using lavapipe, not Adreno performance.

The first PR run exposed a crash in the unnecessarily upgraded TigerVNC 1.15.
The modern-Mesa fixture now upgrades only Mesa and required dependencies and
verifies the original X server's binary digest. An initial push run separately
failed PRoot controller enumeration despite native attachment; Docker passed.
That failure did not recur in either complete corrected run. Controller code,
assertions and timeouts remain unchanged; no new controller fix is claimed.
Runtime evidence artifact `10727272220`, SHA-256
`1f9b236f71a59d26b9262925d415ebc6cdc878f54d68c3f5aefe46dd15f34a3a`.

**Signed install-in-place APK:** `LSB-Android-0.5.12.apk`, versionCode 28,
18,214,515 bytes, SHA-256
`7203f7967be42bf52a69673b82853ef708be061094c9d22455c5047a48042a49`.
Original signer SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`;
v2/v3 signatures, alignment, ZIP integrity, package/version and CI payload
identity verify. CI device artifact `10727275250`, APK SHA-256
`fe678dac8727a615be3ed574ab1c9e1b27ee706b13eb35fcbb085215f41a329c`.
Only `AndroidManifest.xml`, `classes.dex` and `assets/runtime/supervisor.py`
differ from 0.5.11. Every native graphics/display/audio/controller/launcher
component, both DXVK pairs, Wine/Box64/PRoot components and purple icon remain
byte-identical. Both new switches off preserve 0.5.11 graphics behavior.

The first duplicate PR run failed inside TigerVNC 1.15's Composite redirect
(`compRedirectWindow` / `miValidateTree`), after the tuned eight-frame check
confirmed two workers. The modern-Mesa Docker fixture had unnecessarily
upgraded the entire rootfs from Bookworm to Trixie. It now installs only Mesa
and required dependencies and verifies that the original X server binary's
SHA-256 remains unchanged. All graphics assertions remain; no test is skipped
or automatically retried. The distributed device runtime is unaffected.

The original push run also reached the final PRoot controller fixture and
failed virtual-device enumeration (exit 2), despite a native attachment receipt;
its Docker controller test and all new graphics checks passed. Controller code
and binaries are byte-identical to 0.5.11. This is recorded separately from the
X-server crash: no controller fix, weakened assertion or extended timeout is
claimed. Both complete corrected runs subsequently pass the original controller
checks in Docker and PRoot, without reproducing this enumeration failure.

## Focused Thor test

Install over the existing app. Use the same working Termux server and client
`30251204_1`, original xiloader and accepted preparation. No source updates or
managed-server migration. Keep DXVK 2.7.1, shared-memory Vulkan presentation,
Native Surface and 60 Hz on; keep the same resolution and other settings.

1. Enable **Turnip system-memory rendering** only. Leave the new two-worker
   option off. Warm up for two minutes, repeat the known route with camera
   rotation and menus, then Stop and export Diagnostics. Compare against the
   current improved 0.5.11 experience. Check textures, shadows, audio and camera.
2. With the better Turnip setting retained, enable **two shader compiler workers**
   and repeat the route and export. Note first-pass hitches versus repeated
   traversal; slower shader warm-up is possible.
3. If either test is worse, disable that option and relaunch. If neither helps,
   both off retain the improved DXVK 2.7.1 setup. Check one clean relaunch.

Keep HUD choice identical between comparisons. These tests isolate two rendering
choices; do not rerun 30 Hz/compression/resolution comparisons. Do not promise
60 game FPS or a resolved slowdown until Thor measurements support it.
