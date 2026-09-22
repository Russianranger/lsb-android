# Periodic launcher scan correction: 0.5.8

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
exact causality and the improvement still require the Thor comparison.

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
purple icon remain unchanged.

Three additional real Wine/PRoot scenarios hold a visible FFXI-class fixture
window open, prove the full receipt remains unchanged across more than two old
scan intervals, and verify normal exit, Stop and a later unhandled exception
remain supervised. Existing launch/error/privacy/render/controller/audio/server
checks are retained. Local core checks and 40 runtime / 5 server contracts pass.
The local cross compiler is unavailable; native builds and full integration are
being verified in CI. No Thor smoothness result is claimed yet.

## Thor comparison

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
