# PlayOnline repair performance (0.5.42)

Input: `lsb-support(20260927-005241).zip`, SHA256
`9f6b120f3e06a1bb03dc295dfd00ba2d49f4bc2f5d35d97a4019b4064486602d`.
The owner can now reach File Repair, but reports only about 250 of 61,280 files
checked in over an hour and very slow UI response. That rate is about 0.069
files/second. Checking 61,280 files within two hours requires at least 8.51
files/second before any subsequent download time; no completion-time guarantee
can be inferred from the current logs.

## Established by the phone report

Session `5cde70b9-9f4a-4c8c-9efa-858100c369a4` on 0.5.41 lasts 6,179.84 seconds.
The updater uses Wine 10 WoW64/Box64 0.4.4, DXVK 2.5.3, verified hardware Turnip
Adreno 740, and no PRoot acceleration. The accepted gameplay engine is FEX;
updater code excluded it because the original preparation path used Box64.
There is no documented PlayOnline-specific FEX incompatibility behind that gate.

Preparation improves from 85.997 to 32.227 seconds. This confirms the batching
change helps launch preparation, not the ongoing file-check bottleneck.

The current display connection delivers 2,310 updates and 2,310 unique Android
draws over 5,567.74 seconds. Its clock origin differs from the runtime's, so do
not align those timestamps directly. The retained active tail has 341 updates
over 765.16 seconds (0.446 updates/second). Weighted costs are 4.349 ms receive,
0.192 ms decode and 0.076 ms draw submission. Worst decode is 0.913 ms and draw
0.847 ms. Android keeps up with incoming frames; no protocol failure is logged.
These are damage-update rates, not measured GPU FPS. Hardware Vulkan preflight
does not establish the actual viewer rendering path's performance.

The original Windows viewer (PID 516) exits after 151.358 seconds with 1.34 CPU
seconds; the diagnostics then show replacement PID 728 loading pol.exe and
app.dll. The existing heartbeat's responsive window and CPU counters describe
only the original startup process, not the long file check. No manual display
refresh is recorded in this session.

Several megabytes of fixed viewer files are read/hashed by native guest Python
in 51 ms before and 18 ms after the attempt. The client is on app-private internal
storage, not streamed from SAF or an SD card. This weakens a broad sequential
storage-throughput explanation but does not measure Wine's metadata access,
case-insensitive directory searches or many-small-read overhead. Box64 Dynarec
is enabled. No explicit CPU pinning is configured; a Cortex-A510 startup banner
alone does not establish the CPU on which the repair runs. The retained 977
exception diagnostic events are insufficient evidence of a sustained log storm.

## Controlled change and measurement

Offer the installed FEX game engine to the updater, using the existing separate
per-generation prefix migration. Keep DXVK 2.5.3, display transport and filtering
unchanged to isolate the Wine/CPU engine. The Box64 verification path remains
available and no accepted client/server/database data is modified.

A finite PE32 helper performs fixed synthetic metadata, case lookup, file-read,
timer, event and CPU phases with Windows clocks/counters plus native Python wall
time. A paired native reference runs on the same disposable session files.
Native exact-case lookup is explicitly distinguished from Windows mismatched-case
lookup; CPU algorithms differ, so do not interpret their ratio as pure emulation
cost. Compare the identical Windows workload between engines instead. Inner and
outer budgets retain partial receipts and preserve Stop behavior.

A bounded process sampler records CPU ticks, I/O counters and process state for
the owned runtime, including observed replacement viewers and its PRoot tracer.
Membership uses process identity/ancestry or a validated tracer, not an arbitrary
process name. Discovery and output limits are reported; unreadable or unobserved
processes are not reported as zero load. It reads no command lines, environment,
window titles or client contents.

## Validation boundary

The focused qualification compares Box64 and FEX under actual patched PRoot,
with the same DXVK version and software Vulkan driver on ARM64 CI. It exercises
the official viewer's production component checks, the synthetic I/O workload,
staged source preservation and a self-restarting Windows fixture. CI timing is
not a Thor performance measurement. The phone's proprietary client data and
post-update state are not part of that fixture. Completed checks and artifact
identities are recorded in HANDOFF.md after the release gates finish.

Next phone test: FEX in Restore Test, resume the existing update, record the
checked-file count over five minutes, then export support. No hour-long retry,
client reimport or new restore is required to measure the change.
