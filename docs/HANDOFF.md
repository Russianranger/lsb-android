# Completed milestone: selectable FEX runtime (0.5.13)

The latest 0.5.12 Thor result shows no meaningful improvement from sysmem or
two DXVK workers. Both were actually applied; neither is an accepted FPS fix.
The full log audit, including startup RPCSS/missing-library warnings, is in
[fex-runtime-0513.md](fex-runtime-0513.md). Hardware Turnip, DXVK 2.7.1,
shared-memory presentation, 60 Hz and the startup-only observer were active.
Both game sessions exited cleanly. Preserve the accepted DXVK 2.7.1 improvement,
60 Hz and the 0.5.8 periodic-stutter fix.

The user explicitly requested FEXCore implementation. Work continues from the
existing branch; native ARM64 Wine 10 + FEX 2510 is implemented as a separate
runtime with isolated copied prefixes and an explicit Box64 rollback selector.
No client, server or xiloader source update, re-import, re-preparation or managed
server migration is authorized for this comparison. Keep the working Termux
server and client `30251204_1`.

The native source build passed in run `35811359812`, artifact `10729768931`.
Its complete PE import/export audit includes the upstream Wine thread API
backport `d53a9ba0cd5ee46852b00e4a106e2eb679b5aa3d` (upstream's semi-stub).
Pinned runtime SHA-256 `99c270eefe20e32d942ba6a77ad1ea0d69097ed31b9830879fa749630ce15c89`, 318,095,255 bytes.

The **complete FEX runtime gate passes** for `e60b1b64e5567f7fe4cca2d70cca418cd4fa9edd`
in run `35814095537`, job `107032319489`: actual PE32 FEX execution, copied-prefix
migration, software and DXVK 2.5.3/2.7.1 pixels, actual shared-memory uploads,
60 Hz Native Surface, PCM, input, controller axes/buttons/disconnect, launch,
relaunch, startup-only observer idle/Stop/crash, and patched PRoot all pass.
Every baseline prefix file/link remains unchanged after both container and
PRoot runs. CI Vulkan is lavapipe; Thor FPS and real-client compatibility still
require device testing.

Qualification fixed a missing Wine export, the isolated CI prefix's ownership,
Wine-internal dependency-audit paths, and the FEX debugger hang. Wine matches
`winedbg.exe=` exactly; `winedbg=` does not disable the executable. Correcting
that override **only for FEX** makes the real-window crash and both owned
exception fixtures return `0xc0000094` without hanging. No crash assertion,
privacy filter or startup-only observer policy was weakened.

**Signed device build:** `LSB-Android-0.5.13.apk`, versionCode 29,
18,260,032 bytes, SHA-256
`2c22d4d0e86c64a3b92ec7a7719332901df192532182860630e9ac38ee7668e1`.
Original signer SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`;
v2/v3 signatures, alignment, ZIP integrity, package/version and CI payload
identity verify. Device artifact `10730898789`, CI APK SHA-256
`7e2be16ff4cd28d62cf29bc27848b97d16dda81590f04a2cd84130d388435926`.
Every pre-existing native display, upload, graphics, audio, controller,
launcher, PRoot and Wine/Box64 asset and the purple icon remains byte-identical
to 0.5.12. The FEX-only debugger environment is the final runtime correction.

**All seven CI gates pass** for the final implementation commit
`e60b1b64e5567f7fe4cca2d70cca418cd4fa9edd` in
[push run 35814095537](https://github.com/Russianranger/lsb-android/actions/runs/35814095537):
`presentation`, `verify`, `windows-launcher`, `runtime`, `fex-runtime`,
`server-deployment` and `runtime-release`.
[PR run 35814098449](https://github.com/Russianranger/lsb-android/actions/runs/35814098449)
passes all six applicable gates; its publication job correctly skips.
No implementation test or release gate is pending.

Both complete Box64 suites retain the accepted DXVK 2.7.1 behavior and 2.5.3
fallback, real shared-memory uploads, audio, controller and startup-only observer
idle/Stop/crash checks. The 54 runtime contracts, five server contracts,
15 Android tests and existing core/native Windows checks also pass. The real
MariaDB deployment, failed-update preservation, database export, update,
rollback and four-process lifecycle gate passes. These server fixtures do not
update the user's Termux installation.

Box64 evidence artifact `10731606754`, SHA-256
`ddc513c11c51608dca59f6370c7fd5def507e652d281a6f80cd994ef9c5f16a9`.
FEX evidence artifact `10731625312`, SHA-256
`a9125d7cde5da7465637a2f414fc6611b753504d3c0f1bcbbeddd7bcee75b5ed`.

The immutable [FEX runtime release](https://github.com/Russianranger/lsb-android/releases/tag/runtime-fex-v1)
is published. The runtime asset's size and SHA-256 match the APK pin. Its exact
corresponding source archive is 83,387,434 bytes, SHA-256
`3e98ee0068ef45fad5bc1db8897854f2de2076f36e248230e1cfc9363de8c950`.
The prior source artifact lacking `RtlWow64SuspendThread` was rejected and
never published. During qualification, an earlier Box64 attempt had one
modern-Mesa wineboot exit `-9`; unchanged later complete suites, including
both final runs, pass. No new Box64 fix or cause is claimed for that transient.

**First Thor test:** install the signed update in place, keep the working
Termux server, `30251204_1`, original xiloader and current preparation. Runtime:
install FEX (about 303 MiB; keep 3 GiB free), select it, then run Windows checks.
Use 60 Hz, Native Surface, shared-memory upload and DXVK 2.7.1; keep sysmem and
two-worker experiments off. Repeat a warmed-up route, check audio/right-stick
axes, exit and relaunch, then export Diagnostics. Switch FEX off for the Box64
comparison. Full steps and the log audit are in [fex-runtime-0513.md](fex-runtime-0513.md).
FEX is an opt-in hardware experiment; no Thor FPS improvement is claimed yet.

---

# Completed milestone: Turnip rendering and DXVK compiler scheduling (0.5.12)

The user reports that DXVK 2.7.1 improved the experience, but FPS slowdowns remain.
`lsb-support (8).zip` confirms hardware Turnip 26 / Adreno 740, DXVK 2.7.1,
60 Hz and actual game shared-memory uploads (5,439, no fallback/attach failure).
The ring wait averages 0.0049 ms, so another upload-ring optimization is not
supported by this evidence. The prior 2.5.3 run used the same upload path.

0.5.12 adds **two separate opt-in experiments**, both default off: Turnip 26
system-memory rendering (`TU_DEBUG=sysmem`) and two DXVK compiler workers
(`dxvk.numCompilerThreads=2`). Each applies to the next launch. Their combined
requested environment must pass eight app-owned D3D8 draw/present frames before
login; the worker option also requires DXVK's actual two-worker confirmation.
A failure restores both environment values and retains the already selected
DXVK pair. Requested/active settings and fallback reason appear in Diagnostics.
No client/config/source files are changed. This is a qualified experiment, not
an established fix for the remaining FPS loss. See [evidence and test](graphics-tuning-0512.md).

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

Preserve the existing Termux server, client `30251204_1`, xiloader, preparation,
accepted startup-only observer, 60 Hz, audio, controller and relaunch behavior.
Keep DXVK 2.7.1 and shared-memory upload enabled for the focused Thor comparisons;
there is no need to retest 30 Hz or update/migrate any source.

---

# Completed milestone: Vulkan presentation and DXVK comparison (0.5.11)

The user authorized GPU presentation and DXVK work after reporting no noticeable
improvement from 0.5.10. GameHub's reported 60 FPS came from its overlay; the user
confirms smoother gameplay. This clarification is settled. Do not ask it again.

0.5.11 adds an independently reversible shared-memory Vulkan upload path and
upstream DXVK 2.7.1 comparison. The upload path bypasses eligible full-frame X11
socket payloads, but still performs GPU readback; it is not direct GPU-to-Android
presentation. Both features have startup checks and baseline fallback. DXVK
2.7.1 defaults off, while the new upload path defaults on. Actual selected
versions, checksums, fallback reasons and numeric upload counters are exported.
No recurring process/module scans or timer-driven telemetry flushes are added.

**All six CI gates pass** for `cf5bd4675a05d8133e51b019ab7a9094dd78119d`
in [push run 35798263910](https://github.com/Russianranger/lsb-android/actions/runs/35798263910):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. [PR run 35798267691](https://github.com/Russianranger/lsb-android/actions/runs/35798267691)
passes all five applicable gates and correctly skips release. No CI tests or
failures remain pending.

The actual Wine/DXVK process performs 300 SHM uploads with both DXVK 2.5.3 and
2.7.1. The modern Mesa 25.0.7 fixture selects 2.7.1 and passes all eight preflight
frames plus the main triangle/pixels/input/PCM/exit tests. The baseline Mesa
22.3.6 fixture rejects incompatible 2.7.1, restores both 2.5.3 DLLs and completes
the same gameplay-independent checks. CI never claims Adreno hardware.

Native upload tests pass in Linux and PRoot: 58 calls, 40 SHM uploads, 18 row
fallbacks, zero attach failures and two connections. Forced socket mode passes
all exact pixels with zero SHM uploads. Ring reuse, caller-buffer ownership,
resize, checked errors and reconnect pass. PRoot exercises Android-compatible
memfd emulation. The existing ten native-display cases also pass.

Controller axes/buttons/hat/release/stale-input tests pass with both host
preloads enabled. All 62 launch cases and six startup-observer idle/Stop/crash
checks pass, retaining the accepted stutter fix. Also passed: 116 core checks,
RGB565 and 90 ZRLE checks, native frame bounds, 44 runtime contracts, five server
contracts, 15 Android tests and 36 native Windows checks. Real MariaDB
deployment, failed-update preservation, update/rollback and process lifecycle
pass. Runtime evidence artifact `10725910618`, ZIP SHA-256
`e04f025f4995d437f18cba87bf1284609477f696a39489f845ae2ca38ac90173`.

CI harness corrections restored executable permissions on the downloaded test
helper, selected Trixie's actual `lvp_icd.json` filename, and made container-owned
binary evidence readable by the artifact uploader. The modern Mesa environment
is CI-only; the distributed Wine/Box64 rootfs remains unchanged.

**Signed install-in-place APK:** `LSB-Android-0.5.11.apk`, versionCode 27,
18,214,515 bytes, SHA-256
`7f2c0d63b4a41251af8dc38ead280084720749dc842a5fa6e3f1a0019064ef84`.
Original signer SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`;
v2/v3 signatures, alignment, ZIP integrity, package/version and payload identity
against CI artifact `10724269877` verify. CI APK SHA-256
`a79a2e86364a54c02529b0593d5d67f63099fe28912cf6a454cc72a94820b0dc`.
The driver, baseline DXVK pair, launcher/startup observer, audio, controller,
PRoot, native capture/Android Surface and purple icon remain byte-identical to
0.5.10. Added/changed payload is limited to version/DEX, supervisor, component
manifests, the new upload library/checker, finite graphics checker, candidate
DXVK pair and upstream license. Both graphics options off retain the previous
binaries, renderer environment and transfer path.

**Next Thor test:** install over the existing app. Use the working Termux server,
FFXI client `30251204_1`, original xiloader and accepted preparation. No source
update, import, re-preparation or managed-server migration. Keep 60 Hz, Native
Surface, the same resolution, normal FPS HUD, startup capture off and other
settings unchanged. First test shared-memory Vulkan presentation on / DXVK
2.7.1 off; then enable only DXVK 2.7.1 and repeat the warmed-up route. Stop and
export Diagnostics after each run. Check audio, right-stick camera and relaunch.
Both options off provide graphics rollback. See [full mechanism and test](gpu-presentation-0511.md).

Superseded by the 0.5.12 follow-up above: the user reports improved performance
with DXVK 2.7.1, but remaining slowdowns. The new log confirms it was active. Retain the user's accepted
0.5.8 periodic-stutter fix and 60 Hz preference; do not treat CI pixel checks as
a game FPS benchmark or repeat 30 Hz/compression tests as the main experiment.

---

# Latest Thor result: 0.5.10 has no noticeable gameplay improvement

The user reports no improvement and says the earlier GameHub Lite / Proton 10
setup reached 60 FPS. The new bundle confirms the metadata cache works (one
geometry query and three cursor-image fetches across 13,195 polls), but this is
not an accepted gameplay speedup. The 0.5.8 periodic-stutter fix and 60 Hz remain
accepted. [Device evidence, runtime comparison and next direction](thor-runtime-gap-0510.md).

Prioritize the unresolved rendering/runtime gap: the app forces CPU X11 Vulkan
presentation, and uses Wine WoW64 + Box64 + DXVK 2.5.3. The saved GameHub profile
uses Proton ARM64X + FEX + DXVK 2.7.1 async with a different Turnip build label.
Hardware Turnip rendering does not rule out presentation, translation or driver
bottlenecks. Add effective graphics/present metadata and a reversible DXVK
comparison, then qualify an accelerated presentation / ARM64X-FEX candidate
separately from the working runtime. Do not repeat display-only tuning as though
the main bottleneck were established, blindly remove the WSI software flag, or
claim generic Proton/FEX packages reproduce the recorded GameHub setup.

That earlier 0.5.10 follow-up changed documentation only; it is superseded by
the completed 0.5.11 milestone above. Preserve the matching Termux server/client `30251204_1`, original
xiloader, preparation, login/audio/controller behavior and rollback baseline.

---

# Completed milestone: reduce repeated X11 metadata queries (0.5.10)

The user confirms 60 Hz improves the Thor experience and reduces drops into the
teens; 30 Hz restores the degraded experience. Keep 60 Hz as the recommended
Thor test setting. Overall game FPS in the 20s and occasional hitches remain.
The recurring module-scan stutter fixed in 0.5.8 remains resolved.

The new comparison verifies 60 Hz in both monitor modes, 30 Hz in the middle
run, MIT-SHM throughout, and the accepted startup-only observer in all three.
At 60 Hz, over half of the display polls return unchanged images but each still
queries screen geometry. Cursor images are also downloaded on every capture.
0.5.10 caches geometry/cursor metadata, invalidates it on X11 events, and retains
live pointer polling. In the real X11 fixture, the same 88 requests / 52 captures
use 6 geometry queries instead of 88 and 14 cursor-image queries instead of 52.
All 88 live pointer queries remain. Exact pixels, cursor movement/shape/hotspot,
transparency, clipping, real resizing and reconnect pass in Linux and PRoot.
Query counts and timings are included in exported producer logs.

**All six CI gates pass** for `9612125345448fa4778484664a02167bd5629028`
in [run 35790527598](https://github.com/Russianranger/lsb-android/actions/runs/35790527598):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35790531672)
passes all five applicable gates and correctly skips release.
Runtime evidence artifact `10722148392` confirms all ten
native test combinations (eight cached paths and two polling controls),
Android-compatible memfd/MIT-SHM, 62 client-launch scenarios, six idle/Stop/crash
observer checks, controller preload/input/disconnect, PCM, DXVK HUD and three
supervised 60 Hz Wine captures. All 15 Android tests and real MariaDB deployment,
update and rollback pass. No CI failures remain pending.

**Signed install-in-place APK:** `LSB-Android-0.5.10.apk`, versionCode 26,
15,940,768 bytes, SHA-256
`611620c7ecac7eb28182001c6847e01351cad231b76e6fe7e25651d217b5f73f`.
Original signer, v2/v3 signatures, alignment and ZIP integrity verify; payload
matches CI artifact `10721413053`. Launcher/runtime/graphics/audio/controller
binaries and icon match 0.5.9. Only version metadata, DEX, the display producer
and its checksum manifest change semantically; the runtime manifest differs
only in JSON key order.

[Evidence, validation and focused Thor test](display-metadata-0510.md).
The query reduction is verified; the subsequent Thor test reports no noticeable
FPS improvement. The runtime/presentation follow-up above replaces the original
test request. Another 30 Hz comparison is not needed.
Preserve the same Termux server/client `30251204_1`, original loader, accepted
preparation, pinned Wine/Box64/Turnip/DXVK, audio and controller behavior.
Do not update source, re-import, re-prepare or start managed-server migration.

---

# Accepted Thor result: periodic stutter fixed in 0.5.8

The user confirms the consistent stutters have stopped. The new 0.5.8 receipt
shows startup observation complete at 13,740 ms, with no further scans through
exit at 261,588 ms. In matched 70–210-second log windows, gaps over 100 ms
fall from 172 to 19 and the recurring clusters disappear. These are display
delivery measurements, not game FPS; the routes were not controlled benchmarks.
The remaining issue is game FPS in the 20s with drops into the teens.

0.5.9 implements the supported 60 Hz display cap, retains 30 Hz as the existing
default, and removes redundant native frame copying. The accepted startup-only
observer and all working login, audio, controller and source paths are retained.
This display option does not unlock the game's own FPS limit.
[Implementation, evidence and Thor sequence](display-refresh-059.md).

**All six 0.5.9 CI gates passed** for implementation
`735e5c114d1cc22bc15df1bd65c257fe5510517e` in
[run 35784458788](https://github.com/Russianranger/lsb-android/actions/runs/35784458788):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35784465163)
passes all five applicable gates; release is correctly skipped there.
All 62 launch scenarios and 15 Android tests pass. Both 30/60 Hz capture modes
pass with MIT-SHM and XGetImage in Linux and PRoot, including exact/duplicate
pixels, ownership, idle frames and reconnect. Three supervised Wine captures
run at 60 Hz with audio/input; HUD, memfd, controller/preload, startup-only
observer, Stop/crash handling and real MariaDB deployment/update/rollback pass.
No CI failures remain pending.

**Signed install-in-place APK:** `LSB-Android-0.5.9.apk`, versionCode 25,
15,940,768 bytes, SHA-256
`583842b3a8b0185b3c7e08511496912ac678ca9661a5a9552cf69ee850cf7ba5`.
Original certificate, APK v2/v3 signatures, alignment and ZIP integrity verify;
every non-signature entry matches CI artifact `10718983323`. Only the manifest,
DEX, native capture helper and its checksum manifest change from 0.5.8; the
stutter-fixed launcher, runtime/graphics/audio/controller paths and icon match.
[Validation and artifact evidence](validation.md).

Keep the existing Termux server/client `30251204_1`, original xiloader and accepted
preparation. No source updates, re-import, re-preparation or managed migration.
The paired Thor comparison is complete: the user accepts 60 Hz as better.
Keep Native Surface on, Fast/compression/startup capture off and normal FPS HUD
on for the next comparison. Another 30 Hz run is not needed. Periodic-stutter
acceptance remains recorded; remaining FPS drops and the 0.5.10 follow-up are above.

---

# Completed implementation: stop intrusive gameplay module scans (0.5.8)

The 0.5.7 Thor result still stutters. New timing evidence shows capture and the
Android frame worker remain responsive during repeated game-image pauses; the
asynchronous reporter has no dropped windows and only millisecond write costs.
The launcher still scans the child's complete DLL list every three seconds after
startup, through Wine cross-process reads that can suspend a game thread.

0.5.8 stops those scans once the FFXI window is observed and then waits on the
child process handle. Exit/crash detection, Stop and retained startup evidence
remain. New integration scenarios verify that the observer stays idle while a
healthy window remains open and that normal exit, Stop and late crash still work.
[Evidence, source audit, correction and Thor sequence](thor-stutter-058.md).

**All six CI gates passed** in [run 35780131808](https://github.com/Russianranger/lsb-android/actions/runs/35780131808)
for `c5b3ac3760c846ec139a1fe624113a09df4ecb61` (implementation `7795ddec15d77af7c395257cfd029128f567efe5`
plus the test synchronization correction): `presentation`, `verify`,
`windows-launcher`, `runtime`, `server-deployment` and `runtime-release`.
The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35780138540)
passes all five applicable gates; its release job is correctly skipped.
All 62 launch scenarios pass. The new idle/normal-exit, Stop and late-crash
checks pass separately in ARM64 Wine/Box64 and PRoot. All 14 Android tests,
three supervised native captures, detailed HUD, memfd/MIT-SHM, controller/preload,
audio and real MariaDB deployment/update/rollback checks pass. No CI failures
remain pending.

The APK is built with the original signer and matches the final CI payload. Artifact:
`LSB-Android-0.5.8.apk`, versionCode 24, 15,940,768 bytes, SHA-256
`0e7548c3e5fd39e19aefb6c28daabaa5cf19bb9c01937ebe25ab2a7326f40b01`. Keep the existing matching Termux server/client `30251204_1`, original
xiloader, accepted preparation and pinned runtime/driver/audio/controller path.
No source updates or managed-server migration. The periodic-stutter correction
is accepted on Thor; remaining game FPS drops are still unresolved.

---

# Previous implementation: remove reporting from the display worker (0.5.7)

The authorized next step is implemented: Native Surface transfers bounded numeric
samples to a separate diagnostics writer, preserving session isolation and the
final report. New measurements record individual post gaps with idle context,
stage maxima, loop delay and writer duration. An opt-in stutter HUD adds frame
times, shader compiler and DXVK worker activity. Normal FPS HUD behavior and
all pinned runtime/graphics/audio/controller binaries are retained.
See [implementation, validation and Thor sequence](display-worker-057.md).

**All six CI gates passed** for implementation
`8c75aa70c785bf9824b53fbd13f60ffdf7b0cee2` in
[run 35775283832](https://github.com/Russianranger/lsb-android/actions/runs/35775283832):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35775289943)
passes all five applicable gates; its release job is correctly skipped.
All 14 Android tests, 56 launch scenarios, three supervised Wine/native captures,
the detailed DXVK HUD, Android-compatible PRoot memfd capture, controller/preload,
audio/Stop checks and real MariaDB deployment/update/rollback pass.
No CI failures remain pending.

The original-signer APK is built and verified: `LSB-Android-0.5.7.apk`,
versionCode 23, 15,940,768 bytes, SHA-256
`374e1c17bee9f5cab76c6f5567462446d1f2b43416103ffe26668f4d03c6b2b8`.
The subsequent Thor comparison confirms stutters remain with both HUD modes.
The 0.5.8 investigation and test sequence above supersede this initial device check.
Keep the existing matching Termux server/client `30251204_1`, original xiloader,
accepted preparation, 720p/30 Hz, Native Surface on and Fast display/compression
off. No runtime/driver replacement, client/source update or managed migration.

---

# Previous device result: remaining periodic stutter after 0.5.6

The latest Thor result confirms MIT-SHM is working: all 6,565 posted frames use
shared memory and comparable capture time falls from 25.18 to 4.00 ms. The user
reports no noticeable gameplay improvement and stutters every 3–5 seconds.
Surface throughput remains about 22 posts/s; this is not game FPS. **0.5.6 is
confirmed to correct capture; gameplay smoothness remains unresolved.**
See [device comparison, driver-variable audit and next target](thor-performance-056-result.md).

Turnip 26/Adreno 740 and native DXVK DLLs are verified by the available checks.
The app's `MESA_VK_WSI_DEBUG=sw` still selects CPU-based presentation into Xvnc;
removing it needs a compatible accelerated display path. There is no evidence
that a speculative Turnip debug flag will fix the reported periodic pauses.
The concrete next target is `NativePresentation.report()`: it serializes/writes
the retained diagnostics on the frame worker every five seconds. Move reporting
off that path and add bounded frame-gap/report-duration measurements. Its actual
contribution is not yet measured. Shader activity, CPU scheduling and increasing
audio underruns also need observation; five-second averages cannot isolate them.

That investigation preceded the 0.5.7 implementation and validated APK above.
Keep Native Surface on, Fast display/compression
off and the existing matching Termux server/client `30251204_1`, original xiloader
and accepted preparation. No runtime/driver replacements, client/source updates
or managed-server migration.

---

# Completed implementation: enable Android shared-memory capture (0.5.6)

The 0.5.5 Thor test improves subjectively in the second run but still stutters.
Both sessions actually use Native Surface, with no RFB pixel traffic. The concrete
remaining fault is X11 capture falling back to XGetImage: `shmget` returns errno
38 because the app omitted PRoot's `--sysvipc` option. The packaged runtime already
supports memfd-backed emulation. Capture averages about 25–27 ms/frame while the
Android pixel copy is about 0.43 ms. [Device evidence and correction](thor-native-capture-056.md).

0.5.6 enables that option only for Native Surface launches. The CI display fixture
now forces emulation and requires its memfd marker so native Linux SysV IPC cannot
mask the omission again.
The earlier 0.5.5 Linux MIT-SHM result is not proof of Android emulated capture.

**All six CI gates passed** for implementation
`b3aec4c3b910c0755cbe1bfe626a2a46b6368911` in [run 35769148142](https://github.com/Russianranger/lsb-android/actions/runs/35769148142):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35769152473) passes all five applicable gates;
its release job is correctly skipped. Saved PRoot evidence confirms both the
memfd-emulation marker and successful MIT-SHM capture with exact pixels and no
X errors. All 56 launch scenarios, three supervised Wine/native captures,
controller/preload/audio/Stop checks and real MariaDB deployment/update/rollback
pass. No CI failures remain pending.

**Signed install-in-place APK:** `LSB-Android-0.5.6.apk`, versionCode 22,
15,936,672 bytes, SHA-256 `a88af15a75fb447d5dd894583b65f477c7c2ff616aa36f6fc44fe4e637a86d11`.
Original certificate, APK v2/v3 signatures, alignment and ZIP integrity verify;
every non-signature entry matches the CI build. All native/runtime binaries,
graphics, audio/controller/loader components and purple icon match 0.5.5.
[Artifact evidence and device limits](validation.md).

Preserve the existing Termux server/client `30251204_1`, original xiloader,
accepted preparation and all pinned runtime/graphics/audio/controller binaries.
First next-device check keeps the better second-run settings: 720p, Native Surface
on, Fast display/compression off, startup capture off and DXVK HUD on. Stop and
launch afresh, repeat the same route, check audio/axes and Stop/relaunch, then
export Diagnostics. Require actual MIT-SHM capture/nonzero `shm_frames` before
assessing the FPS change. Thor improvement is unverified; no source update or
managed migration. Native Surface off restores the prior display/PRoot invocation.

---

# Historical 0.5.5 handoff (capture fault superseded above)

# Active milestone: shared-memory Native Surface trial (0.5.5)

The latest Thor comparison confirms startup capture off at both resolutions, yet
960×540 gains only about 2 game FPS and stutters remain. 0.5.4 is not accepted as
a smoothness fix. 0.5.5 removes RFB pixel compression/transport/Java Bitmap updates
from the active display path via an optional shared-file Native Surface, with
automatic fallback. X11 readback remains; actual Thor gains are unverified.
See [evidence, implementation and focused Thor test](native-surface-055.md).

**All six CI gates passed** for implementation
`a70eac356cb1bccb6b2fc23014ead33e0c7655fb` in [run 35759197659](https://github.com/Russianranger/lsb-android/actions/runs/35759197659):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35759204024) passes all five applicable gates; its release
job is correctly skipped. Validation includes 116 core/preparation/login checks,
RGB565 and 90 ZRLE checks, 39 runtime/5 server contracts, 10 Android API 33 tests,
36 native Windows checks and all 56 ARM64/PRoot launch scenarios. Real native
capture tests pass with MIT-SHM and XGetImage in both environments; supervised
Wine/D3D8 pixels reach the shared framebuffer alongside PCM and RFB input.
Existing DirectInput axes/release, preload isolation, audio/Stop checks and real
MariaDB deployment/update/rollback pass. No CI failures remain pending.

**Signed install-in-place APK:** `LSB-Android-0.5.5.apk`, versionCode 21,
15,932,576 bytes, SHA-256 `02b3fa438f10459737bebf9cc9814b9bb3f277bbf5bce27fba1cb328e0783a7e`.
Original signing certificate, APK v2/v3 signatures, alignment and ZIP integrity
verify. Every non-signature entry matches CI; all previous runtime/native binaries,
audio/controller/loader components and purple icon match 0.5.4 byte-for-byte.
[Full evidence and limitations](validation.md). Actual Thor FPS improvement is
unverified; do not call this an accepted smoothness fix before the device test.

Preserve the working matching Termux server/client `30251204_1`, original xiloader,
accepted preparation, Wine/Box64, graphics/audio/controller baseline and purple
icon. No source updates or managed-server migration. First device test: native
on, capture off, 720p, same 30 Hz cap, compression retained for fallback.

---

# Historical 0.5.4 handoff (superseded by 0.5.5 above)

# Historical milestone 4: lossless display transfer and purple icon (0.5.4)

**Latest Thor result:** 0.5.4 still jumps around 6–28 game FPS with Fast display and compression on; compression off is reported far worse. The new bundle verifies about 65% payload savings with compression, stable single-bitmap allocation, and more long delivery gaps in the uncompressed comparison. Smoothness remains unresolved. Both launches still used startup capture. See [measured comparison and next test](thor-performance-054-result.md). Keep both performance options on; the next action uses existing 0.5.4 settings, with no runtime/driver replacement or new APK.

The 0.5.3 Thor test still stutters, with the user reporting 5–28 FPS. Its new timing history confirms the reusable bitmap stays at one allocation, but steady display updates average 13.16 Hz: receipt takes 45.73 ms/update, bitmap work 1.58 ms and Android draw submission 0.085 ms. This supports testing the display transfer path next; it does not establish that all game FPS drops are caused there. See [device evidence, implementation and focused comparison](display-transfer-054.md).

0.5.4 adds lossless ZRLE transport with low server compression effort, preserves the native RGB565 copy path, and provides **Compress display transfer · lossless** to compare against the prior Raw path. Timing history now records encoded bytes and decode costs. A purple adaptive-icon background replaces Android's white legacy surround while preserving the crystal artwork.

Implementation `b3502c75c08859f2d328900efa456edd8525b152` passes all four applicable gates in [PR run 35728160635](https://github.com/Russianranger/lsb-android/actions/runs/35728160635): build/Android, native Windows, ARM64 runtime and real MariaDB deployment/update/rollback. Real TigerVNC tests pass directly and through PRoot, with matching Raw/ZRLE pixels in RGB565 and RGB888. Synthetic RGB565 transfer falls by 90.9%; this is not a Thor FPS measurement. All 56 launch cases, native controller/audio/render checks and nine Android tests pass. The earlier server toolchain and DirectInput failures remain resolved. The first push attempt had an isolated synthetic relaunch exit 97; see [validation](validation.md) for its report and retry evidence.

[Push run 35728155096, attempt 2](https://github.com/Russianranger/lsb-android/actions/runs/35728155096) is also fully green: `verify`, `windows-launcher`, `runtime`, `server-deployment` and `runtime-release`. The unchanged runtime suite passes on retry, including all 56 launch cases and both real display-wire environments. No assertions were weakened or additional runtime changes made for that retry.

Signed `LSB-Android-0.5.4.apk` is versionCode 20, 15,915,863 bytes, SHA-256 `f27385cad9e7544206133546d8ef0aeb9555693fd0f91b834d19f66cb741f4c3`, using the original certificate for an install-in-place update. Every non-signature entry matches CI. Native/runtime binaries, original icon PNG and bundle manifest remain byte-identical to 0.5.3; changed entries are the manifest, Android DEX/resources and the supervisor's low-effort compression setting, plus adaptive-icon XML. Actual Thor smoothness remains the device acceptance criterion.

Next Thor test: existing Termux server, accepted preparation and client **30251204_1**, same Turnip/display cap, Fast display and compression on, DXVK HUD on. **Stop client**, open **Graphics and launch options**, uncheck **Capture FFXI startup result**, then use **Launch FFXI** for a brief 1280×720 baseline; reopening a running view does not disable its startup observer. If stutters persist, stop and relaunch using **Windowed 960×540 (lighter)** for a 3–5 minute route and export immediately. Keep other settings fixed. The compression-off comparison is complete and is no longer the recommended next test. Check audio, camera axes and Stop/relaunch.

Preserve the accepted generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505` and original nested loader SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`. Wine/Box64, graphics drivers, audio and controller code are preserved. Do not update client/xiloader/server source toward **30260904_1**, re-import/re-prepare, or begin managed-server migration during this comparison. Older device sequences below are historical.

---

# Previous milestone 4 checkpoint: display smoothness (0.5.3)

The user accepts 0.5.2 boot, both controller axes, relaunch and improved audio. The new Thor diagnostics corroborate two successful launches, clean dependency checks and seven-axis DirectInput; audio underruns stay nearly flat after startup. Preserve that working baseline. The outstanding device issue is stutters/FPS drops, not launch recovery.

0.5.3 replaces the three-shape RGB565 bitmap cache with a single reusable software staging allocation. An actual Android/Skia regression workload reduces 120 bitmap allocations (207,120,000 cumulative bytes) to one (1,843,200 bytes), with matching pixels including odd rectangle widths. Timing diagnostics retain up to 180 windows, identify sessions/connections, record update gaps and decode/draw costs, flush on display close and serialize/write on a bounded background worker. The previous final-only sample cannot establish gameplay FPS or its limiting stage. See [evidence, scope and Thor comparison](display-smoothness-053.md).

Implementation `24569520699dd5566fe1b99caf797418be48bdc8` passes [full CI run 35723063302](https://github.com/Russianranger/lsb-android/actions/runs/35723063302): `verify`, `windows-launcher`, `runtime`, `server-deployment` and `runtime-release`. All nine Android tests, 56 launch cases across ARM64 Wine/Box64 and PRoot, native input/audio/render checks and real MariaDB deployment/update/rollback pass. The corresponding [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35723068434) passes its four applicable gates. The earlier server toolchain and DirectInput failures remain resolved.

Signed `LSB-Android-0.5.3.apk` is versionCode 19, 15,911,683 bytes, SHA-256 `cf85f0263ffe43cbfe2ef1ae013475351404774541b6f7535e9320955423f1c7`, using the original certificate for an install-in-place update. Every non-signature entry matches CI. Compared with 0.5.2, only the Android manifest/DEX and JSON key ordering in `runtime/bundle.json` differ; all runtime hashes and binaries are unchanged. See [validation and artifact identity](validation.md). Allocation reuse and pixel correctness are verified; actual Thor FPS improvement remains to be measured.

First Thor comparison: install in place, use the **existing working Termux server** and the same accepted 1280×720/Turnip settings with Fast display enabled. Disable Capture FFXI startup result for normal gameplay, keep the DXVK FPS HUD enabled, move and rotate the camera in a repeatable area for 3–5 minutes, then export Diagnostics immediately. Report the HUD FPS range and whether drops occur during motion, zone entry or steady play. Confirm audio and Stop/relaunch still work. A 960×540 comparison is optional only after this same-settings run.

Preserve accepted generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, original nested Ashita xiloader SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`, client **30251204_1**, current Wine/Box64 runtime and matching server source. Do not update client/xiloader/server source toward **30260904_1**, re-import/re-prepare the client, or begin managed-server migration during this test. The older retry sequences below are historical.

---

# Previous milestone 4 checkpoint: loader initialization and camera axis (0.5.2)

The 0.5.1 Thor retry reaches the runtime and completes controller setup; the Android null-session crash is no longer observed. The failed client attempt stops before xiloader: exactly `WS2_32.dll` fails initialization with Windows error 1114, while the other 19 imports pass. The following 32-bit controller-setup process successfully loads that DLL. The underlying initialization cause is not proven. See [device evidence, repair boundaries and focused retry](loader-controller-052.md).

Implementation `3ea8a01a2886b43ed3ac43885a39adf3b88ce21b` (following `aa33cf57dbff428408aac3bb5e1311c68b5b8c8b`) confines the gamepad worker to Wine's HID host, adds one fresh full dependency check only for that exact complete WS2_32 failure, preserves both receipts and adds checker symbol diagnostics. All imports must pass with a clean exit before xiloader can start. The right-stick vertical defect is concrete: four SDL slots exposed X/Y/Z/Rx, so Rz was absent. The helper now exposes seven slots with X/Y/Z/Rz input and neutral Rx/Ry/Slider; the extra neutral slider prevents Wine’s six-axis Xbox heuristic from remapping controls. Android and real DirectInput tests cover both signs on all four stick axes.

[Full CI run 35718338735](https://github.com/Russianranger/lsb-android/actions/runs/35718338735) passes every gate: `verify`, `windows-launcher`, `runtime`, `server-deployment` and `runtime-release`. All 56 launch cases (28 per environment), seven-axis DirectInput checks, controller-preloaded Thor imports, native helper isolation, audio/render regressions and real MariaDB deployment/update/rollback pass. The corresponding PR run also passes its four applicable gates. The earlier server toolchain and DirectInput CI failures remain resolved.

Signed `LSB-Android-0.5.2.apk` is versionCode 18, 15,903,491 bytes, SHA-256 `f6585a47626e9f6ebdc0c18ebf6e69891725014039eb395148becb68d76de6be`, using the original certificate for an install-in-place update. Every non-signature entry matches the CI artifact. Compared with 0.5.1, only `AndroidManifest.xml`, `classes.dex`, `runtime/client_launch.py` and `liblsb-gamepad.so` differ. See [validation](validation.md) for exact artifact checks and limitations.

First Thor test: install in place, start the **existing working Termux server**, launch the accepted client and confirm login/world entry plus clean audio. Then open FFXI gamepad setup, reselect the virtual joystick if necessary, assign camera **Z/Rz**, and check both right-stick directions plus release/Stop/relaunch. Export Diagnostics immediately if the dependency check still stops. This retry is now complete: the user confirms successful boot, recognized camera axis, clean relaunch and much better audio on 0.5.2. The next task is display smoothness; use the current instructions above.

Preserve user-confirmed 0.4.8 behavior, accepted generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, original nested Ashita xiloader SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`, client **30251204_1**, current runtime and matching server source. Do not update client/xiloader/server source toward **30260904_1**, re-import/re-prepare the client, or begin managed-server migration during this retry. The 0.5.1 and 0.5.0 retry instructions below are historical and superseded by the linked 0.5.2 sequence.

---

# Previous milestone 4 checkpoint: Android display startup recovery (0.5.1)

The first 0.5.0 Thor attempt closed the Android app immediately when opening either the client or controller setup. A new Android 13 lifecycle regression reproduces a null-session `NullPointerException` in the actual `RuntimeActivity` controller timer before the worker creates a session. Two tests fail before the fix; all three pass afterward. Implementation `4a28a0d1508c59d1c26d6645a7f42efc47e44d2e` waits for a valid session, publishes its ID safely across threads and releases mapped input on focus loss. The new Activity tests are required in CI. See [diagnosis and focused retry](android-startup-051.md).

Preserve user-confirmed 0.4.8 login and clean audio, accepted client generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, original nested Ashita xiloader, client `30251204_1` and its existing working Termux server. This fix does not change the Wine/Box64 runtime, native helpers, audio, rendering implementation, version repair, client files or server sources. Do not update toward `30260904_1` or perform the managed-server migration during the crash-recovery retry.

The earlier server-toolchain and DirectInput CI failures are resolved. [Full CI run 35671938279](https://github.com/Russianranger/lsb-android/actions/runs/35671938279) passes every gate: `verify` (including the three Android 13 lifecycle tests), `windows-launcher`, `runtime`, `server-deployment` and `runtime-release`. Both ARM64 Wine/Box64 and PRoot pass the native gamepad, audio/render and all 28 launch scenarios; the real MariaDB deployment/update/rollback test passes.

Signed `LSB-Android-0.5.1.apk` is versionCode 17, 15,907,587 bytes, SHA-256 `fee9e5dce1d8bdb23bebb7840f3034dd9e872970882a77a0528cf6921596602f`, with the original certificate for an install-in-place update. Every non-signature entry matches the passing CI artifact; only the Android DEX and version manifest differ from 0.5.0. See [validation](validation.md) for exact evidence and device limits. The next device action is an in-place 0.5.1 update, followed by existing-server login/audio, controller setup and stop/relaunch checks. No re-import or re-preparation is needed. The historical 0.5.0 device instructions below are superseded by this recovery.

---

# Previous milestone 4 checkpoint: controller, performance and existing-server migration (0.5.0)

The user confirmed 0.4.8 works: login succeeds and audio is clean. Preserve accepted generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, the original nested Ashita xiloader (SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`), runtime and version repair. Client patch.ver is `30251204_1`; current upstream source expects `30260904_1`, so test the existing matching server before any source update.

0.5.0 implements a virtual SDL/DirectInput gamepad bridge, configurable Android button mappings, RGB565 bitmap transfer, optional 960×540, reduced steady-state launcher polling, separate server/database generations and crystal artwork. New device behavior and actual FFXI performance remain unverified. See [implementation, test boundaries and device sequence](milestone-050.md).

The continuation fixes the server Python compiler dependency and the DirectInput test's startup timing. [Full CI run 35660037932](https://github.com/Russianranger/lsb-android/actions/runs/35660037932) passes Android packaging, native Windows checks, ARM64 Wine/Box64 and PRoot input/audio/render/launch regressions, real MariaDB deployment/update/rollback, and the runtime release gate. The controller helper loads natively; Box64's duplicate emulated-preload warning was not the failure. See [diagnosis](ci-recovery-050.md) and [validation/artifact identity](validation.md).

Signed `LSB-Android-0.5.0.apk` is versionCode 16, 15,907,587 bytes, SHA-256 `f949f3d5b4e00ab170511106693412ea13634425e19ea7fbefb475c9ee113a5e`, using the original certificate for an install-in-place update. First Thor action: use the existing Termux server and matching `30251204_1` client/loader, verify login and clean audio at the accepted renderer/resolution, then test controller setup and release behavior. Do not update client, xiloader or server source. Full actual LSB compilation, runtime installation on Android and world entry on the managed server remain device work; CI uses synthetic server executables.

---

# Active milestone 3: restore the missing version registry value (0.4.8)

The new `patch_pol.zip` matches the selected polcore.dll. Its unchanged patch.ver decodes as `30251204_1` with Interface string `"0"`. The exact original callback returns -1 for a missing registry value and 0 for `"0"` in isolated x86 emulation; input bytes are preserved. The app had created InstallFolder/language keys but omitted Interface. See [evidence, implementation and limits](version-repair-048.md).

0.4.8 validates the complete supported version record before restoring only a missing selected-region, 32-bit `Interface\\0001` REG_SZ value. Existing values/types are preserved; readback failure removes the new value. `version_config` in the native launch receipt reports the repair. Client files, preparation, runtime and loader remain unchanged. This is a targeted fix for the reproduced version-reader failure; whole-game startup remains unverified. Implementation `0f6c31bdac679a04c59aa6e05440d77a3dc9db22` passes [all three CI gates](https://github.com/Russianranger/lsb-android/actions/runs/35639901043): 116 JVM checks, 34 Python contracts, 36 native Windows checks, the version-registry fixture and 28 launch cases in each of Wine/Box64 and PRoot. The first-repair and subsequent-preservation cases pass in both environments. Signed `LSB-Android-0.4.8.apk` is 7,751,854 bytes, SHA-256 `facac9d63ed16c0f7e68559331350d43a25ad42d7798b2c95c698a22b58ee609`, using the original certificate. See [validation](validation.md) for its identity and verification limits.

Next device action: install in place, keep the accepted preparation and current settings, start Termux server, launch once with startup capture enabled, and export Diagnostics if it stops. No re-import or re-preparation. Do not replace version files or suppress callback failures. If a registry value already exists, preserve it and use the receipt to guide the next investigation.

---

# Previous milestone 3 step: inspect version-data processing (0.4.7 device result)

The new `lsb-support(6).zip` captures successful root FTABLE/VTABLE opens and first reads, plus a successful complete 288-byte patch.ver read. The working directory matches FFXiMain's parent. All ten API hooks are installed; there are no dropped records. GameMain returns `0x88770000` after 1,227 ms, before any Windows-version, DirectPlay, window or D3D-creation event. Outer GameStart still returns S_OK. See [the device evidence, remaining branches and exact next inputs](version-startup-047.md).

The matching DLL puts a PlayOnline common-function-table callback (offset `0x124c`, index 1171) immediately after its version-file read. A negative callback result or file-manager completion failure can produce this exit. Successful ReadFile alone does not distinguish them. Next obtain `FINAL FANTASY XI/patch.ver` and the selected `PlayOnlineViewer/viewer/com/polcore.dll` from the same import/backup, verify the recorded DLL hash, and inspect that implementation locally. Do not use the patch-cache DLL. These bytes are absent from both the support ZIP and the earlier two-DLL upload.

No code, APK or runtime change is made for this analysis; 0.4.7 remains installed. Do not ask for another identical launch, re-import or preparation. Preserve the accepted generation and original files. Do not fabricate version data, bypass validation or change graphics/dependencies without a concrete failing operation. Startup remains unresolved.

---

# Previous milestone 3 step: pinpoint the pre-window operation (0.4.7)

The supplied `ffxi_lsb.zip` matches both recorded DLL hashes. Offline inspection confirms that `0x88770000` is a shared early-startup failure result, not a specific Direct3D/Windows error. GameMain checks its file manager (including FTABLE.DAT, VTABLE.DAT and patch.ver), Windows version, DirectPlay and window creation before its main loop. The pinned runtime contains dpnhpast.dll. The old unknown-version label does not establish that patch.ver is missing. See [static findings, RVAs and trace interpretation](startup-files-047.md).

0.4.7 retains the prepared client and adds fixed, numeric observation of the actual FFXiMain file/platform/window API results, plus a metadata-only inventory of three startup files. No proprietary DLL is bundled or committed. No game instruction, data file, registry setting or renderer is changed. The existing opt-in temporary-loader mode is retained. Reporting includes the earlier inner-HRESULT correction.

Implementation `8ef8234edb9f1c64eabfd9b1bef4a5a2cf53535e` passes [all gates](https://github.com/Russianranger/lsb-android/actions/runs/35634762962): 116 JVM checks, 33 Python contracts, 36 native Windows checks and 26 launch cases in each of Wine/Box64 and PRoot. The signed device APK identity is in [validation](validation.md).

Next device action: install in place, leave **Capture FFXI startup result** checked, start the existing Termux server, launch once and export Diagnostics. No re-import, preparation or additional DLL upload is requested. This is targeted diagnosis, not a confirmed startup fix. Use the next receipt to identify a concrete failing operation before repairing client files or changing runtime components. In particular, do not fabricate patch.ver or claim DirectPlay is absent based only on its dynamic load name.

# Active milestone 3: identify the inner GameMain failure (0.4.6 device result)

The newest report `lsb-support (3)(2).zip` records successful FFXI and GameMain COM creation, followed by **GameMain HRESULT `0x88770000` after 1,441 ms**. Outer GameStart returns S_OK after 1,983 ms, and the loader exits 0 without an observed game window. This is the captured failing boundary; its underlying cause is not yet known. See [exact evidence, reporting correction and next input](game-main-failure-046.md).

Source reporting now surfaces the inner failure. Thirty-one Python contracts and replay of the actual device receipt pass. No new APK is delivered for this reporting-only correction; the existing 0.4.6 already captures the needed boundary. Do not ask for another identical launch. Next obtain `FINAL FANTASY XI/FFXiMain.dll` and `FFXi.dll` from the same import/backup, check the hashes documented above, and statically inspect the return path. The support ZIP contains hashes, not these binaries. Preserve the accepted generation, original loader, imported files and runtime. Neither a renderer change nor a dependency sweep is justified yet.

---

# Active milestone 3: observe the actual GameStart result (0.4.6)

The new `lsb-support (2)(2).zip`, SHA-256 `f8b8c13088bd2ea888195f2fe27c32e4e6e19d4f48399a2b808ad264051378f2`, records 0.4.5 session `a0898d38-3a79-4290-9d76-8c1dbd237c8f`. Loader PID 284 exits 0 after 15,688 ms, with all five expected modules and no sampled game window/dialog. Windowed settings and all 20 dependency loads pass. Missing-DLL 0xc0000135/RPC 1722 belong to PID 292. Loader 0x80000026 is first-chance; no fatal game exception or DXVK initialization message was captured. Do not treat these warnings as the proven cause or prescribe another dependency/renderer change.

0.4.6 adds **Capture FFXI startup result** (initially checked; unchecking restores direct original-loader execution). It runs an exclusive temporary same-directory copy with an added diagnostic DLL import, observes the known IFFXiEntry GameStart and optional IGameMain boundary, and retains fixed stage/PID/HRESULT/duration metadata. Imported code, loader file, accepted generation, registry registration and runtime are not replaced. The copy has a different filename, image layout and checksum, so this is an explicitly identified diagnostic execution mode. It is removed after normal/failure/Stop paths; force-kill can leave an unselected small copy. See [design, evidence and limits](game-start-trace-046.md).

The next device test is an in-place 0.4.6 install, same settings/server/preparation, trace checked, one launch and fresh Diagnostics. The actual game remains unresolved. Correlate `startup` records to the receipt child PID: loader hook count, FFXI COM result, GameStart enter/return (unsigned HRESULT and elapsed milliseconds), and optional nested GameMain result. A missing return with abnormal process exit differs from an ordinary returned HRESULT. A zero return without a game window is not verified startup; zero hook count means that boundary was not instrumented. Preserve source loader SHA `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8` and generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`.

Implementation `3003880bd3f2cbb3be4f608274a8e86b4dce1b38` passes every gate in [run 35629179765](https://github.com/Russianranger/lsb-android/actions/runs/35629179765): 116 JVM checks, 27 Python contracts, 36 native Windows checks and all 25 launch scenarios separately under ARM64 Wine/Box64 and PRoot. S_OK/S_FALSE/E_FAIL/nested E_OUTOFMEMORY, unhandled exception, Stop in GameStart, trace-off, private virtual-slot preservation, original hashes and copy cleanup pass. See [validation](validation.md) for the corrected test-session race and exact boundaries. Delivered `LSB-Android-0.4.6.apk` is 7,747,758 bytes, SHA-256 `293a337e63c970986baf768198dd31ecb66a4784e8b441e24b81d19770227f85`, using the original signing certificate; observer DLL SHA-256 `afe0e602262413e656c6cdb8566a80f475ea8c2afa74c5c3fdad361025ce8b3b`. Actual GameStart outcome and game display on the Thor still require the next device report.

---

# Active milestone 3: capture the silent startup failure (0.4.5)

The latest `lsb-support (1)(1).zip` (SHA-256 `3bdcbd1030a7201edcd75312736bc7432df8abf1a4fec72850916e717f3fea95`) records 0.4.4 session `aa460280-6a87-4994-a0cb-aeac44e1b07c`. The five windowed values were applied and read back successfully; original settings were saved. The loader nevertheless closed with child/bridge exit 0 after 15,111 ms, with POL/FFXI/FFXiMain/D3D8/D3D9 observed and no game window or standard dialog observed in 35 samples. This rejects windowed mode as a sufficient fix. Do not repeat a settings-only update or infer a proven exception from the user's word “crashing.”

0.4.5 fixes a diagnostic blind spot: the launch environment previously set `WINEDEBUG=-all` and `DXVK_LOG_LEVEL=none`. It now captures bounded, filtered Wine DLL/COM/exception/error metadata and DXVK initialization/error categories through the existing private pipe. DXVK file output is explicitly disabled. Numeric Wine process/thread IDs can be correlated with the native receipt's child PID; unhandled process failure still depends on the real exit, not a first-chance exception or a recoverable warning. Unknown text, paths, GUIDs, register/stack contents and credentials are discarded. This is a diagnostic update, not a demonstrated fix for the proprietary GameStart return.

Keep prepared generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, imported xiloader SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`, the source import, saved display originals and Wine/Box64/Turnip/DXVK runtime. No prerequisite installation, new preparation, GameHub transfer or renderer change is supported by this report. Next device action: install 0.4.5 in place, launch once with the existing settings/server, then export fresh Diagnostics. The next report must establish whether a captured error/exception explains the early return. If it is still silent, the imported loader does not expose GameStart's return/HRESULT; direct source-level loader instrumentation may be required. Never claim DLL loading or Vulkan probe frames establish game rendering.

Implementation `6563b1e351ef2a9debd32e2eb1684005a6dbd42f` passes all gates in [run 35624389711](https://github.com/Russianranger/lsb-android/actions/runs/35624389711): 116 JVM checks, 21 Python contracts, 36 native Windows checks and all 18 launch scenarios separately under ARM64 Wine/Box64 and PRoot. The real missing-DLL and unhandled divide-by-zero cases retain safe metadata and the correct child PID; recoverable warnings do not terminate a healthy fixture. Immediate Stop retains its diagnostic snapshot. Delivered `LSB-Android-0.4.5.apk` is 7,723,027 bytes, SHA-256 `7893b550d952e70ee7e975b5cd59319a6661bebe1ae51a6266c55c4759844413`, using the original signing certificate. See [startup diagnostics](startup-diagnostics-045.md) and [validation](validation.md) for exact evidence and limits. Actual game startup remains unresolved pending the fresh device report.

---

# Active milestone 3: explicit windowed display profile (0.4.4)

The new `lsb-support(5).zip` device report (SHA-256 `871b057ff6b647abbea1f31d45857bb226d9321d0126cebf869e564d4e6f79e3`) records 0.4.3 session `be51e556-b2a6-45a9-959c-4353bb3f8113`. Login succeeds; POL, FFXI, FFXiMain, D3D8 and D3D9 are observed, but no game window/dialog is observed before exit 0. All five display values already existed: 640×480 overlay, 512×512 background, fullscreen mode 0. The 0.4.3 missing-only policy made no changes. Do not repeat that test or claim it exercised 1280×720 windowed mode.

0.4.4 adds an explicit Client display selector, defaulting to Windowed 1280×720. It saves original display values in the same prepared prefix before overriding the five known DWORDs, with Keep current and Restore saved original choices. This is a targeted candidate fix for the observed fullscreen configuration, not proof that fullscreen caused the exit. Retain generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, source import, xiloader, prefix and runtime. Implementation `b40c7e6885ca2cb34a0045793f722a2495516842` passes all gates in [run 35618686567](https://github.com/Russianranger/lsb-android/actions/runs/35618686567), including 16 launch cases separately under ARM64 Wine/Box64 and PRoot and the regional registry recovery fixtures. Delivered `LSB-Android-0.4.4.apk` has SHA-256 `f6b03de94325cf438b7137a7e5d24541e20a19e7b13dda8be0bd77e9eb837113` and the original signing certificate. Next device test: install in place, select Windowed 1280×720, start the existing Termux server and launch; send new Diagnostics if no game appears. See [validation.md](validation.md) for evidence, exact build identity and limits.

---

# Active milestone 3: post-login exit investigation (0.4.3)

The 0.4.2 device report `lsb-support (3)(1).zip` (SHA-256 `9011837bb7be404ce1e60e4bfbe927034c82d6c57a1d135c957d7ba93345e364`) records session `56ca8e82-a27b-4107-8971-c9b9989672f7`: `login_message_seen`, `server_connected`, native child exit 0 and bridge exit 0 after about 35.5 seconds. The user confirms successful login followed by exit. This establishes authentication progress, not world entry; no captured exception or initialization failure establishes the cause. Preserve generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, imported loader and current runtime.

0.4.3 fills missing FFXI display settings, records safe post-login observations, and stops describing an unobserved game startup as a normal close. Implementation `483c69087d0e23570fc55827e60564a08b9162ae` passes all gates in [run 35591567783](https://github.com/Russianranger/lsb-android/actions/runs/35591567783), including all 14 launch cases in ARM64 Wine/Box64 and PRoot and the US/EU/JP display-settings fixture. Delivered `LSB-Android-0.4.3.apk` uses the original certificate and has SHA-256 `b7a96fc46560b710412dc484470551ed62af618f04650ccc1a2bfde62cabb2ea`. See the current section of [validation.md](validation.md) for source evidence, exact build identity and limits. Do not assert the actual game is fixed until the device retry. No re-import, preparation copy, runtime replacement or GameHub test is needed.

---

# Active milestone 3: login rejection feedback (0.4.2)

The 0.4.1 device test accepts the dependency-checker fix: all 20 DLLs loaded, exit 0, and xiloader started. The new blocker is a `login_rejected` event while the loader stayed alive and the screen remained black. The old log does not distinguish wrong credentials, account state or version rejection. 0.4.2 adds specific safe event classification, live failure handling/automatic shutdown, accurate waiting-for-login status and an Android error dialog returning to the Client form. Implementation `90f812a836b0da8a185440b6c25a2eada1cdf762` passes all gates in [run 35551248809](https://github.com/Russianranger/lsb-android/actions/runs/35551248809), including all five new rejection/automatic-stop cases under ARM64 Wine/Box64 and PRoot. Delivered `LSB-Android-0.4.2.apk` has SHA-256 `da5e0e65eb3d639a5a10a9899fab6a04d8f89644705dd892f9b949972fce57ae` and the original certificate. Read [the current validation section](validation.md) before requesting another test. Preserve current generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, source import, loader hash and Turnip 26. No blanket graphics or prerequisite changes are supported by this report.

---

# Active milestone 3: fix the 0.4.0 pre-launch exit

The 2026-09-21 Thor support report confirms both launch attempts stopped in the dependency checker, after all 20 import DLL loads passed, with process signal 11. xiloader never started. 0.4.1 changes only the checker lifetime, receipt validation and version labels; the accepted client/prefix, pinned runtime and login flow remain in use. See [diagnosis and validation](validation.md). Implementation `07f6923a56d02c582d179f63ce1fdf8d00229a16` passes all three gates in [run 35547759081](https://github.com/Russianranger/lsb-android/actions/runs/35547759081), including the exact 20-import list twice and unload/exit/missing/initialization-crash regressions under ARM64 Wine/Box64 and PRoot. The original-certificate 0.4.1 APK has SHA-256 `8aa64a92e8a94cb3c40325d5e21e6395e72e6a45d778e1ee8b479e3effd91236` (7,710,656 bytes). Device retry will use the same preparation after installing the update. Do not prescribe a full import/preparation or Visual C++ install based on this report. Precise Wine/Box64 crash attribution remains unproven without a stack.

---

# Active milestone 3: login and world entry 0.4.0 (2026-09-20)

The user authorized this step after proprietary registration/COM passed. The implementation reuses the activated client/prefix, adds Android server/account/password fields, checks the exact imported loader's CLI markers and normal-import DLL loading, and starts it through a native x86 worker with pipe-only credential transport. Launch output persists fixed event names rather than arbitrary credential-bearing child text. The worker records full Windows process failure/exit codes. Prerequisite repair clones the active working client and prefix, retaining current user files and the accepted generation until all checks pass.

Implementation `54f50948af52b3fd286d316d7cba3a73cea74bce` passes all three gates in [run 35537087441](https://github.com/Russianranger/lsb-android/actions/runs/35537087441). Existing 90 core checks, 18 preparation/recovery checks, eight credential-transport checks and ten Python contracts pass. Launch/relaunch, literal credential arguments and working directory, full Windows failure status, explicit Stop, missing-dependency rejection and check-only behavior all pass under ARM64 Wine/Box64 and PRoot. Logs/session reports exclude the deliberately echoed test credentials. The first ARM64 attempt caught a synthetic-fixture encoding mismatch; the corrected fixture passes the same inspection and real launch path. The new fixtures use synthetic loaders, not user credentials or a game server. Launcher repair requires its loader-check receipt before activation, and login discards the PRoot wrapper's raw output as well as unfiltered child output. The original-certificate device APK identity and precise limits are recorded in [validation.md](validation.md).

**Historical 0.4.0 device action (superseded by 0.4.1 above):** install 0.4.0 in place, start the existing Termux server, enter the server account under **Client → Play FINAL FANTASY XI**, and choose **Launch FFXI**. Keep `127.0.0.1`, Turnip 26, and the activated preparation. No further import, preparation copy, open probe or GameHub test is requested. After the attempt, stop/exit and export diagnostics; report the last visible stage and any character/world entry. [Detailed procedure and limits](client-launch-040.md).

**Acceptance still pending:** exact imported-loader parser and dependency compatibility at runtime, server authentication, character/world entry, actual game rendering/audio/input and stability. Static CLI-marker inspection is not a complete parser proof; loadable DLLs are not proof that all exports/behavior satisfy the loader. Process alive/exit 0 never marks login or world entry verified. Existing accounts with ASCII input are supported; account creation, OTP, credential persistence, full controller mapping and server integration remain later work.

---

# Milestone 2 complete: client registration and COM initialization 0.3.0 (2026-09-20)

The user authorized the next step after the Thor completed milestone 1. The 0.3.0 implementation adds a separate full working client and cloned prefix, real registration and client COM checks, per-step reports, prerequisite EXE execution, retry without full recopy, and atomic activation/rollback of matching client/prefix generations. See [client-initialization-030.md](client-initialization-030.md) for device procedure, boundaries and provenance. The selected import survives and remains the source of truth; no GameHub test, fresh prefix or import is requested.

Implementation checkpoint `d0f5acb62727cb82fbe3eb406e3f1c800a78a367` passed all three verification gates in [run 35532787286](https://github.com/Russianranger/lsb-android/actions/runs/35532787286), including synthetic US/EU/JP initialization and failure/prerequisite recovery under ARM64 Wine and PRoot. Local checks pass: existing 90 import/recovery checks, 15 preparation/cancellation/rollback checks and six backend contracts. Following Android-only changes remove the old one-minute display wait during large preparation copies and record a fresh diagnostic state for early copy failures; these compile in the delivered signed APK, whose native/backend bytes are identical to the CI checkpoint. Final APK identity and precise verification boundaries are recorded in [validation.md](validation.md).

**Device acceptance:** `lsb-support (2).zip` confirms all six initialization steps passed with the user's actual US installation on the Thor. The separate working client and matching prefix were activated as generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`; session `93e77044-aa73-457c-934e-501daa5f0812` completed normally. Registration, actual in-process POL/FFXI COM construction and the first preparation/activation are now device-verified. See [validation.md](validation.md) for exact evidence and remaining limits.

**Next implementation milestone:** login and world entry using the activated preparation. Add Android server/account fields and launch the existing imported xiloader with argument-array execution, its correct working directory and credentials excluded from logs, support exports and ordinary preferences. Verify the exact imported loader's CLI behavior and its Visual C++ runtime dependencies before login; its PE imports include `MSVCP140.dll`, `VCRUNTIME140.dll` and UCRT API DLLs, but it has not been executed. The saved server remains `127.0.0.1` for the existing Termux server. Preserve the imported loader, activated client/prefix, source import, backup and Turnip 26. No repeated preparation, open-probe test, re-import, GameHub transfer or blanket dependency install is needed.

**Carry forward:** Wine reports RpcSs startup errors and Box64 cannot load `libXcomposite.so.1`; these did not block the completed registration/COM operations. Keep them visible during launch testing rather than treating the run as warning-free or proof of full Wine service compatibility. The native D3D8 DLL loaded during FFXiMain registration; actual FFXI rendering, login, audio and controls are not yet exercised. Device prerequisite execution and failure/rollback paths remain untested because this preparation passed without a prerequisite installer.

---

# Milestone 1 complete: in-app runtime probe 0.2.0 (2026-09-20)

The user confirms **both imported FFXI data and a backup survive**, and authorized the first runtime milestone. Do not ask for them again or request a GameHub test. The current work implements a fresh, isolated runtime with an embedded display/audio/input path and an open x86 registry/COM/D3D8 probe. See [runtime-milestone-020.md](runtime-milestone-020.md) for setup, candidate choice, ownership boundaries and test interpretation.

The first candidate is explicitly Wine 10 WoW64 / Box64 0.4.4, reused from the source-tracked TRASC runtime. This is not a claim that it recreates the captured Proton/FEX environment. FFXI registration, prerequisites and xiloader launch are not part of this probe build. The existing app import and backup are retained and not mounted by the runtime.

Implementation is on the existing `codex/client-baseline` branch / draft PR #1. Implementation commit `e07f29c32a99cec2d26a5f39e3866ccc9369a24b` passed the APK, native Windows and ARM64 runtime gates in [run 35530166981](https://github.com/Russianranger/lsb-android/actions/runs/35530166981). The original-certificate 0.2.0 APK and exact evidence/limitations are recorded in [validation.md](validation.md). The pinned runtime release is published. Thor hardware, registry/COM, input, audible output, explicit Stop, normal probe exit and relaunch are now confirmed. Milestone 1 is complete; proceed to transactional client initialization. Preserve the existing LSB application ID and signing key. The runtime release mirrors only pinned open components/source archives after verification; it is separate from user client data.


## Initial Thor device results (2026-09-20)

Reviewed the supplied `lsb-support(3).zip`, screenshot `Screenshot_20260920-140524.png`, and the user's report that the triangle and sound worked. The retained session is 0.2.0 on Android 13 / AYN Thor, from 19:01:07 to 19:05:57 UTC.

- **Confirmed:** actual Turnip Adreno 740, Mesa 26.0.0, hardware verification true, DXVK 2.5.3 in D3D8 compatibility mode; 2,000 presented frames in the final retained probe sample. The screenshot independently shows the colored triangle.
- **Confirmed:** 32-bit registry round-trip and native test-DLL COM activation; `hresult=0`.
- **Confirmed:** 9 key-down events and 8 pointer/button events reached the Windows probe. This proves key delivery, not complete text entry or gameplay/controller coverage.
- **Confirmed:** four PCM streams with nonzero audio samples and the user's audible-tone report. Active tone portions have zero reported underruns; some short silent tail streams report one. Do not generalize this short test to sustained game audio stability.
- **Confirmed:** requested Stop ended the supervisor with exit 0, `phase=stopped`, `alive=false`; display and audio closed. This initial bundle was not a completed-probe receipt: `probe_exit` and `automatic_checks_passed` were absent. The subsequent lifecycle report below closes that gap.
- **Confirmed:** `game_files_mounted=false`; current inventory still selects the installed `PlayOnlineViewer/viewer/com/polcore.dll`, with 66,322 imported files and a 32-bit xiloader. Inventory does not establish proprietary runtime compatibility.

The probe intentionally uses a 40 ms draw timer (about 25 FPS). The screenshot's 25 FPS is not a measured FFXI performance ceiling. Its overlapping counter text comes from transparent DrawText on a non-erased invalidated area in `windows/runtime-probe.c`; clear the header rectangle before drawing in the next code build. This cosmetic issue does not invalidate the recorded input counters.

Wine first-prefix setup logged OLE/RpcSs, driver setup and hostname warnings, but subsequently completed and the test COM activation succeeded. No fatal crash or failed HRESULT is shown in the retained probe. The display broken-pipe message coincides with shutdown, not an observed rendering failure. Keep these observations scoped to the open probe; no proprietary registration has been tested.

## Lifecycle acceptance: milestone 1 closed (2026-09-20)

The user reports “exit and restart worked” and supplied `lsb-support (1).zip`. Both the previous and current runtime state records independently report `phase=completed`, `probe_exit=0`, `automatic_checks_passed=true`, verified hardware rendering, passing 32-bit registry/COM checks and HRESULT 0:

| Session (UTC) | D3D8 frames | Result |
| --- | ---: | --- |
| 19:12:35–19:14:37 | 116 | Normal probe exit, checks passed |
| 19:14:52–19:15:28 | 106 | Relaunch and normal probe exit, checks passed |

The final Android state is `alive=false`, `starting=false`; the display and audio close after the probe completes. Both runs use the same Turnip 26 / Adreno 740 candidate. Game files remain unmounted. Input counts in these short lifecycle-only runs are zero; the earlier 9 key and 8 pointer/button events already establish input delivery and need not be retested.

The second run logs `err:ole:start_rpcss Failed to start RpcSs service` despite passing the in-process test COM activation. Preserve this observation for real POL/FFXI initialization; the synthetic class does not test out-of-process COM or RpcSs behavior. Neither run reports a failed probe HRESULT or abnormal probe exit.

**Accepted milestone:** independent Windows runtime, fresh-prefix execution, real hardware D3D8, registry, in-process COM, audible sound, input, explicit Stop and normal exit/relaunch. No more open-probe repetitions are required. Foreground/background resume, sustained gameplay stability and full controller/text input remain later device acceptance work, not completed claims.

**Next implementation milestone:** transactional POL/FFXI initialization against the surviving managed import, preserving its original payload and the tested runtime. Revalidate the selected installed US POL core, create a separate working installation/staged prefix, record registration/prerequisite results and actual proprietary COM activation, and preserve rollback on failure. No FFXI launch compatibility is established yet. Retain the existing prefix and renderer; do not request a reinstall, fresh prefix, re-import or GameHub test.

---

# Historical scope/audit before 0.2.0: fresh in-app FFXI client (2026-09-20)

The following is the pre-implementation audit. Current progress and surviving-data confirmation above supersede its pending-state statements.

The user reports that file transfer into GameHub Lite corrupted the container. There is no working external client for testing. The LandSandBoat server remains available in Termux. Continue toward a client that starts inside LSB Android and connects to that existing server. Server integration/compiler work remains later in the original project scope.

This handoff records the changed premise and source audit. It does not introduce a runtime, new APK or claim successful FFXI execution. Released 0.1.3 remains the import/recovery/export baseline at `ce17df84be6dafebc756d5ea8a01fcb20ad56a3e`; draft PR #1 contains that work. No new GameHub transfer, CMD or EXE test is requested. The cause of the reported corruption is unknown.

## Recoverable state and missing evidence

- LSB Android imports live in its own storage, selected by `MainActivity.storage()`, under `lsb/session/current/client`; they are separate from GameHub's prefix. A corrupted GameHub container does not establish that these files were lost. Check the existing app before requesting a new full import. Do not uninstall or clear app data.
- The latest supplied 0.1.2 support report previously confirmed an imported US client, the installed `PlayOnlineViewer/viewer/com/polcore.dll`, `PlayOnlineViewer/pol.exe`, and `FINAL FANTASY XI/ashitav4/bootloader/xiloader.exe`, targeting `127.0.0.1`. It recorded successful import and backup/restore operations. This is historical inventory, not a new check of surviving device files or proof of launch compatibility.
- An existing LSB session backup or complete client ZIP can supply game data without an existing Windows registry. Whether those bytes still survive has not been confirmed after the corruption. Diagnostic ZIPs contain inventory and logs, not the game payload. There are no proprietary game files in this repository or development workspace.
- The recovered successful setup ran official installers before replacing updated payloads. Support both rebuilding registration from an imported installation and running user-supplied official installers inside the fresh prefix if registration alone is insufficient. Never label a registry recipe as equivalent to the complete installer state without testing.
- xiloader 2.0 autologin used server/username/password arguments because interactive console entry was unreliable. The app needs native credential fields. Keep the existing loader version identifiable; do not silently upgrade it along with the app or claim the current upstream parser verifies the imported executable.

## Runtime reuse audit

Reference repository: [Russianranger/trasc-server-android at 95475c747be1fbac1ac3f54197e962ce5ffc1f42](https://github.com/Russianranger/trasc-server-android/tree/95475c747be1fbac1ac3f54197e962ce5ffc1f42). It was inspected read-only; no changes to the working EQ app are needed for this project.

| Area | Reusable foundation | FFXI-specific work |
| --- | --- | --- |
| Install and process supervision | `ClientRuntime.java`, `RuntimeManager.java`, `TarExtractor.java`, packaged PRoot, staged rootfs installation, process shutdown | Remove server-manager coupling; give LSB its own rootfs, prefix, sockets, caches and journal; enforce stopped-runtime updates |
| Display and input | `ClientActivity.java`, `RfbConnection.java`, `DisplayInput.java`, native surface and keyboard/controller code | Separate EQ game behavior and controller defaults; verify Wine desktop, dialogs and FFXI input |
| Audio | Android audio bridge and `backend/client_audio.py` | Verify PlayOnline and FFXI output in the selected runtime |
| Graphics | Source-built glibc/KGSL Turnip 24.3.4 and 26.0.0, hardware/presentation probe, DXVK 2.5.3 | Existing bundle only installs x86 `d3d9.dll`. Add and verify x86 `d3d8.dll` plus `d3d9.dll` for the D3D8 route; detect existing wrappers before applying overrides |
| Windows compatibility | Reproducible Wine 10.0 amd64 WoW64 + Box64 0.4.4 build | This is not the captured Proton 10 arm64x + FEX environment. Do not copy it and claim FFXI compatibility. Evaluate a source-buildable matching Wine/FEX combination first; treat any alternate stack as a distinct candidate |
| Diagnostics/recovery | Bounded logs, runtime probes, process exit records, stopped session snapshots | Add prefix-generation IDs, exact component/loader hashes, 32-bit registry and COM probe results, redaction and FFXI bootstrap stage reporting |

The existing TRASC Wine/Box64 package is not a drop-in host for an ARM64EC FEX DLL. Wine architecture, translation backend, host libraries and graphics bridges must be packaged and qualified together. The screenshots' component names and version labels do not provide those binaries or establish individual necessity. Copying every opaque GameHub component is not a reproducible installation recipe.

[DXVK 2.5.3's installation table](https://github.com/doitsujin/dxvk/blob/v2.5.3/README.md) explicitly requires both `d3d8.dll` and `d3d9.dll` for D3D8. TRASC's `scripts/build-vulkan.sh` and `backend/client_vulkan.py` package only D3D9. Do not equate a passing EQ/D3D9 test with a verified FFXI rendering path. Check the actual imported client's wrapper/DLL load chain during the proprietary test.

## Implementation sequence and acceptance

1. **Independent runtime proof.** Package the selected, pinned Wine/translation stack and in-app display, input and audio. Create a fresh app-private prefix from scratch. Run an open 32-bit Windows probe for execution, registry view, test-DLL COM activation, D3D8 rendering through the chosen GPU path, keyboard/mouse and clean exit/relaunch. Record architecture, hashes and actual renderer. This stage can proceed without FFXI files. Do not require a GameHub export or an external app to execute commands.
2. **Transactional client initialization.** Revalidate any surviving managed client or import its backup. Preserve the original payload while creating a writable working installation and staged prefix. Apply prerequisites as individually recorded steps. Use the selected installed POL core, correct installation root and region; reject patch-cache selections. Invoke installer/registration APIs from the app supervisor, capture exit/HRESULT details, and test real POL/FFXI COM activation. Offer official installer execution in the same in-app display when needed. A failed step must leave the prior usable generation available.
3. **Login and world entry.** Add Android account/server fields and a launch action that invokes the established xiloader 2.0 with correct working directory and arguments, without shell interpolation or interactive-console dependence. Passwords must not enter normal preferences, launcher exports, support bundles or command logs; optional persistence requires protected storage. Check and redact child-process output too. Start the existing Termux server and test the configured host (historically `127.0.0.1`). TCP reachability alone does not prove authentication, map/UDP connectivity or world readiness.
4. **Recovery and updates.** With the runtime fully stopped, snapshot the prefix, mutable client settings and component manifest; stage installation/repair/update changes; only promote a validated generation. Keep a rollback path and enough-space checks. Separate large client content from runtime binaries and generated prefix state, but preserve a coherent matching snapshot across updates. Expose failures and allow retry without re-importing all game data.
5. **Device acceptance.** Character selection, world entry, zoning, audio, controller/keyboard input, logout/relaunch, app switching and recovery after an interrupted preparation step. Evaluate performance after correct startup and rendering. Only then resume the standalone server runtime/compiler work.

Next engineering checkpoint is the independent in-app runtime probe, not another GameHub transfer. Runtime choice remains to be qualified; there is no source-verified equivalent of the opaque captured GameHub package in this repository yet. Do not promise a playable APK before that checkpoint and the real client test pass.

## Project boundary

The original goal remains one app for client setup/update/play and eventually server management/builds. For this recovery phase, the working Termux server is the connection target. No server migration, database rewrite or external container repair is necessary to develop the client. Fresh initialization must not read or mutate GameHub's files. An app update must retain the existing LSB application ID/signing certificate and imported files. Existing session exports do not contain a Wine prefix; extend backup format/version explicitly when runtime state becomes available.
