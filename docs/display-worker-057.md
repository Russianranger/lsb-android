# Display-worker diagnostics correction: 0.5.7

## Why this change

The 0.5.6 Thor report confirms MIT-SHM and capture around 4 ms instead of 25 ms,
but the user still sees stutters every 3–5 seconds. Native Surface's frame worker
was serializing and writing its growing diagnostics history every five seconds
before requesting another frame. The cadence matches the report; the previous
logs cannot measure how much of the stutter that write caused.

## Implementation

The frame worker now keeps primitive counters and a fixed 32-event buffer. Every
five seconds, it transfers ownership of a numeric snapshot to a separate writer.
JSON creation, history serialization and file publication happen on that writer.
The writer has one active task and at most one pending task; if storage falls
behind, the newest pending sample replaces the older one. Dropped windows are
counted, and cumulative frame/SHM totals survive dropped windows. No per-frame
event objects or unbounded queue are introduced.

The writer is owned by the runtime, with explicit session and viewer identities.
It shares the launch-time log-rotation lock, and rejects reports from old sessions
or replaced viewers. Closing the frame worker queues one terminal sample, keeping
its failure reason if present. Export waits up to two seconds for submitted
reports without putting a barrier in the bounded queue or displacing that final
sample. Frame delivery and the UI do not wait for this export flush.

Diagnostics retain 48 windows (about four minutes) with posted-frame gap counts
above 50/100/250 ms, per-stage maxima, loop gaps, native-call time and up to 32
events above 100 ms per window. Events include their relative timestamp, current
stage costs and the number of unchanged responses between posts. Idle scenes can
produce large post gaps: these are not automatically game stalls. Surface
recreation resets gap tracking so lifecycle downtime is not counted as a hitch.

Queue wait, report start and completed write duration are recorded. A completed
write duration becomes visible in the following snapshot; the final sample does
not pretend to measure its own unfinished write. History/event/queue caps keep
the full JSON within the existing 1 MiB diagnostics export limit.

An opt-in **Show stutter diagnostics · frame graph and shader activity** checkbox
adds the pinned DXVK 2.5.3 `frametimes`, `compiler` and `cs` counters to the FPS
overlay on the next launch. The existing FPS checkbox retains its previous
behavior when this option is off. The effective HUD selection is included in
runtime state. This changes the overlay only, not shader compilation policy,
driver variables, CPU settings or caches.

The driver/runtime/native binaries, MIT-SHM setting, original loader, controller,
PCM/audio buffering, source compatibility and purple icon remain unchanged.
Audio's existing progress logging is not retuned in this pass. A physical Thor
comparison is still needed; this is not a claim of stable 30 FPS.

## Verification

Four new Android tests exercise a blocked file writer while the frame producer
continues, bounded backlog with terminal/failure retention, old session/viewer
rejection, snapshot immutability, gap/idle/reset semantics and a worst-case report
under the export limit. They pass locally. The 116 core/preparation/login checks,
RGB565 and 90 ZRLE checks, 40 runtime and 5 server contracts also pass.
The local full Android run compiled all sources and passed 12/14 tests; the two
unchanged native bitmap tests could not load Skia because the local downloaded
`nativeruntime-dist-compat` JAR is truncated (not a valid ZIP). Their assertions
remain required in CI. The real ARM64 DXVK fixture now exercises the detailed HUD
alongside native capture, PCM and input. Full CI and device signing are pending.

## Thor check

Stop the client and install 0.5.7 in place. Keep the existing Termux server,
client `30251204_1`, original xiloader and accepted preparation. No updates,
re-import, re-preparation or managed-server migration.

Use 1280×720, the same 30 Hz cap, Native Surface on, Fast display/compression off,
startup capture off and FPS HUD on. First repeat the same 3–5 minute route with
the new stutter-diagnostics checkbox off, to compare only the worker change.
If periodic hitches remain, stop and relaunch with the new checkbox on. Watch
whether shader compilation activity coincides with frame-time spikes; capture
a short clip if practical. Check audio, both camera axes and Stop/relaunch, then
export Diagnostics immediately. The bundle contains current and previous runs.
