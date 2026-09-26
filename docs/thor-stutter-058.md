# Periodic launcher scan correction: 0.5.8

## Accepted device result

The user confirms 0.5.8 stops the consistent stutters. Bundle
`lsb-support (3)(4).zip`, SHA-256
`24514d946fd0fe055b49206d0ff8a2198e662b08c04e1009258b28a57b11d05d`,
records session `f234fccc-5b9a-45ad-a84a-47da1879bf13`. Its observer completes
after 30 samples at 13,740 ms and stays idle through normal exit at 261,588 ms.
The final startup snapshot alone takes 743 ms, supporting the reason to keep
this work out of gameplay. All 5,927 posted frames use MIT-SHM; no reporting
windows are dropped. The pinned hardware renderer and original loader remain.

For both retained sessions, select the 28 five-second windows starting between
70 and 210 seconds with more than 30 posted frames. The previous 0.5.7 run has
172 post gaps over 100 ms; 0.5.8 has 19, without the repeated clusters. Surface
throughput is 22.44 versus 23.92 posts/s. These are not game-FPS measurements
or a controlled route comparison. 0.5.8 capture averages 3.79 ms, Surface lock
0.228 ms, pixel conversion/copy 0.426 ms and posting 1.094 ms. Audio finishes
with eight underruns. The user still reports game FPS in the 20s with dips into
the teens; that separate performance issue remains open.

## Thor evidence from 0.5.7

The user still observes recurring gameplay pauses. The screenshot shows Turnip
Adreno 740 / Mesa 26.0.0 / Box64 0.4.4, 27.6 FPS and a frame-time graph with
repeated spikes reaching 203 ms. One screenshot does not establish shader
compilation as their cause; no compiler activity is visible in that instant.

Attached `lsb-support (2)(3).zip` has SHA-256
`e91350800e475f7c09b0b9f165860d11282f7422ea664cfbf88590e40400caf4`.
The two sessions are both 0.5.7, at 1280×720 with Native Surface/MIT-SHM and
startup tracing off:

| Session | HUD | Posted frames / SHM frames | Gameplay sample |
| --- | --- | --- | --- |
| `16c272c5-0ae3-4bc5-8de9-730fe9847588` (previous) | FPS | 1,916 / 1,916 | Windows starting at 70.27–115.43 s |
| `89a7912c-7677-4183-bb5f-d82c48f6f63b` (current) | Detailed | 4,058 / 4,058 | Windows starting at 70.27–210.83 s |

In those gameplay windows, capture averages 2.80 / 3.40 ms, pixel copying
0.426 / 0.426 ms and Surface posting 1.87 / 1.14 ms. Worker loop gaps peak at
0.086 / 0.270 ms and diagnostic enqueue time at 0.063 / 0.078 ms. Completed
background reports peak at 1.70 / 2.91 ms, with zero dropped windows. These
measurements reject frame-worker reporting as the remaining large pause.

There are 38 / 176 post gaps above 100 ms; every one includes unchanged
responses between posts. Capture continues receiving unchanged-image responses
while game presentation pauses. The previous run has clusters approximately
3.7 seconds apart; much of the current run is approximately 4.1 seconds apart.
This points upstream of Android copying, without by itself distinguishing game,
Wine, GPU work and presentation into Xvnc. Surface posts/s are not game FPS, and
the two uncontrolled routes/durations cannot establish the HUD's performance cost.

Audio underruns finish at 8 / 29 respectively; the sessions differ in length.
Both client/bridge exits are zero. Final native delivery failure follows normal
client shutdown and is not evidence of a gameplay display crash. The original
client generation, loader hash and Turnip hash are preserved.

## Concrete remaining periodic work

`windows/client-launch.c` continued calling
`CreateToolhelp32Snapshot(TH32CS_SNAPMODULE, child_pid)` and `EnumWindows` after
the FFXI window was observed. It waited three seconds between these scans and
rewrote its receipt every time. Disabling the separate startup-trace option did
not disable this observer. The receipts record 55 observations over 110,666 ms
and 78 over 205,819 ms; the actual scan durations were not instrumented.

[Wine 10 toolhelp implementation](https://github.com/wine-mirror/wine/blob/wine-10.0/dlls/kernel32/toolhelp.c)
reads the remote loader list and both module names using repeated
`ReadProcessMemory` calls. [Wine 10 memory reading](https://github.com/wine-mirror/wine/blob/wine-10.0/dlls/ntdll/unix/virtual.c)
sends remote reads to the wineserver;
[its ptrace implementation](https://github.com/wine-mirror/wine/blob/wine-10.0/server/ptrace.c)
suspends a target thread while reading, then resumes it. The pinned wineserver's
existing patch only adjusts translated-process exit grace. A whole module walk
can therefore disturb the child even when no useful startup evidence changes.
The three-second wait plus scan work is consistent with the observed cadence;
the Thor comparison was needed to confirm the improvement, and its accepted
result is now recorded above.

## Change and verification

0.5.8 observes the game-class window before its final startup module snapshot.
Once that window has appeared, the helper retains the evidence and waits on the
child process handle without further scans or periodic receipt rewrites. It still
collects the child's exit code and publishes the final receipt. The Python
supervisor still detects explicit launch failures, display termination, Stop and
child exit. Observing a window never marks authentication or world entry verified.

Fixed numeric fields record the startup-only policy, completion flag, last sample
time, and last/max/total sample durations. This makes it possible to verify on
Thor that sampling ends long before gameplay ends. No proprietary file is
modified. Runtime, driver, native display, controller, audio, source versions and
purple icon remain unchanged. `ClientRuntime.assets()` copies the packaged helper
into the app-owned probe directory on each fresh launch, before mounting the
accepted client generation. An install-in-place update therefore uses the new
helper without re-import, runtime installation or client re-preparation.

Three additional real Wine/PRoot scenarios hold a visible FFXI-class fixture
window open, prove the full receipt remains unchanged across more than two old
scan intervals, and verify normal exit, Stop and a later unhandled exception
remain supervised. Existing launch/error/privacy/render/controller/audio/server
checks are retained. Local core checks and 40 runtime / 5 server contracts pass.
All six gates pass in [run 35780131808](https://github.com/Russianranger/lsb-android/actions/runs/35780131808)
for `c5b3ac3760c846ec139a1fe624113a09df4ecb61`, including implementation
`7795ddec15d77af7c395257cfd029128f567efe5` and the fixture synchronization below.
The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35780138540)
passes all five applicable gates, with release correctly skipped.
CI passes all 14 Android tests, 36 native Windows checks plus 288 corruption
checks, 62 launch cases across Wine/Box64 and PRoot, three supervised native
captures, detailed DXVK HUD, memfd/MIT-SHM, controller/preload/audio checks and
real MariaDB deployment/update/rollback. Runtime job `106924157036` explicitly
records each new idle/normal-exit, Stop and late-crash check passing in both
runtime environments. No CI failures remain pending. The subsequent device
result above accepts the periodic-stutter fix, while game FPS remains unresolved.

The first ARM64 run passed the new seven-second idle/normal-exit scenario, then
exposed a fixture race in `window-stop`: Stop arrived after native receipt
publication but before the supervisor's 300 ms poll consumed it. The retained
report correctly represented its earlier state (`complete=false`, 13 samples),
so comparing it with the newer native receipt was invalid. Test-only follow-up
`c5b3ac3760c846ec139a1fe624113a09df4ecb61` waits for the matching session and
completed receipt in the supervisor's published report before issuing Stop.
All invariants remain checked, and the existing immediate-Stop scenario remains.
No application code or test assertion was weakened for this correction.

The APK uses versionCode 24 and the original signing certificate. It is
15,940,768 bytes, SHA-256
`0e7548c3e5fd39e19aefb6c28daabaa5cf19bb9c01937ebe25ab2a7326f40b01`.
APK v2/v3 signatures, alignment and ZIP integrity verify, and every non-signature
entry matches CI artifact `10717629448`. Against 0.5.7, exactly three entries
change: `AndroidManifest.xml`, `classes.dex` (version strings only) and
`assets/runtime/client-launch.exe`. All other entries are byte-identical.

## Original Thor comparison (completed; result above)

Stop the client and install 0.5.8 in place. Keep the existing Termux server,
client `30251204_1`, original xiloader and accepted preparation. Do not update,
re-import, re-prepare or migrate the server.

Keep 1280×720, 30 Hz, Native Surface on, Fast display/compression off and startup
capture off. Enable the detailed stutter HUD for a fresh 3–5 minute run along
the same route shown in the screenshot. Watch whether the regular spike clusters
disappear. Check audio, both camera axes and Stop/relaunch, then export Diagnostics
immediately. The final receipt should show `policy=startup_only`, `complete=true`
and a last sample time near startup. Any remaining irregular stalls need their
own diagnosis; this build does not promise a stable 30 FPS.
