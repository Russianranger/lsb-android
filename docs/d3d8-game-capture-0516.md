# 0.5.16: bounded observation of the failing game's D3D8 submissions

## DXVK comparison received

The requested 2.5.3 comparison is complete. Support ZIP `lsb-support (7)(1).zip`
has SHA-256 `56019a26469b79b91bf7e38ce9b6b5dd99e4c67d774529d5d699f36feb397b71`.
Current game session `a8cf101c-324c-45b2-9c6e-c0bd6866d308` verifies DXVK 2.5.3
requested and selected, including D3D8 hash
`8e9e06fcdfc3bd27017fd9f674119be2c850bd220a9b717c981aef9203f7f862`
and D3D9 hash
`3d6b529dc4f7f55639aad580e81b2939ab1489cdbb9f1550dce74e41656bbf5b`.
FEX strict64 is verified in preflight and the final launch child. Turnip 26,
Native Surface, SHM, prepared generation, original xiloader and graphics tuning
match the preceding 2.7.1 run.

The 09:46:22 screenshot shows the same missing menu/background with intact
agreement text and 13.4 FPS. The 09:46:45 character-selection screenshot shows
severe white/red streaks and stretched imagery, with an intact HUD at 12.7 FPS.
The actual game uploader records 500 calls, all 500 using SHM, zero fallback
and zero attachment failure. Average copy is 0.765 ms; average fence wait is
0.00434 ms. Late Surface windows deliver about 12.4–13.2 posts/s. The bounded
startup observer finishes at 7.906 s. Two early audio underruns stay stable
through play, followed by one at shutdown. Stop occurs after about 69 seconds.

There are 104 exception messages, 58 Wine diagnostics, five selected module
loads, four DXVK initialization messages and one generic D3D9 warning, with
zero dropped records. The 2.5.3 warning is not the same retained fixed reason
as the 2.7.1 D3D8 line-pattern warning. Do not reconstruct text discarded by the
privacy parser or assign it a cause from its category alone.

This makes a 2.7.1-specific regression less likely. It does not exclude shared
DXVK code. Both prior 128-pixel on/off checks and this actual-game DXVK version
comparison are complete; do not request them again.

## Diagnostic implementation

The existing opt-in startup observer now attaches to the first D3D8 device
created by FFXiMain. The original imported loader remains unchanged. Capture
records fixed numeric metadata only:

- Device creation flags and dimensions; cumulative UP and buffered draw counts,
  failed draws and first HRESULT.
- Sampled FVF positions: non-finite components, extremely large screen
  coordinates, nonpositive/non-finite RHW, and explicit unavailable coverage.
- Distinct fill, cull, depth, alpha/blend and color-write states, x87/MXCSR
  controls, projection non-finite count, stage-zero texture operations, binding,
  dimensions and format.

It samples at most eight draws every 32 frames and eight vertices per draw.
For vertex buffers, it copies at most the first 32 KiB of an existing application
write before Unlock, across at most 32 tracked buffers (1 MiB fixed storage).
It never locks a buffer or reads a render target to inspect the game. Existing
UP data and copied buffer bytes are analyzed in-process; none of those bytes,
texture contents, pointers, shader handles, account text or paths are exported.

The capture ends on the first of 1,024 presents, 60 seconds checked at Present,
65,536 draws on the creation thread, or device destruction. It then attempts
to restore only its own vtable slots. There is no new worker/timer or recurring
process/module scan. If the game stops making calls, capture does no work.
With the option off, the observer DLL is not loaded into the game.

Limitations are explicit: only the first device and creation-thread draws are
sampled; other-thread draws are counted separately. Shader-based layouts,
unobserved writes and ranges beyond the bounded copy are unavailable, not
reported as good vertices. Indexed draws sample their declared vertex range,
not decoded index references. The thresholds are diagnostic clues, not a claim
that every flagged coordinate is invalid. Diagnostic FPS is not a benchmark.

The numeric graphics report is retained separately from the 64-row startup
record buffer, so frame receipts cannot evict startup call boundaries. It has
its own 64-row limit, strict field counts and no free-form input. Reports land
under `startup_diagnostics.graphics` in the existing client-launch export.

Version labels now read Android package metadata, fixing 0.5.15's stale
`app=0.5.14` text. Version 0.5.16 is a diagnostic build, not a claimed rendering
fix. Wine, FEX, DXVK, Turnip, display/audio/input binaries and prepared client
are not replaced.

## Qualification

The runtime parser contracts cover fragmented input, exact numeric schemas,
private-suffix rejection, absent reports with capture off, bounded retention
and preservation of startup records. A CI-only harness also reruns the actual
128-pixel fixture with the observer attached under both FEX modes and the
Box64 path. It requires unchanged pixels, 32 actual draws, eight buffered draws,
eight sampled buffer vertices, alpha/state-block observation and device-release
detachment. This exercises the real ABI and dynamic buffer writes; no app
request field or production environment override enables the fixture.

Qualification status and delivered APK identity are recorded in HANDOFF and
validation after CI completes. Do not deliver an unqualified APK.

## Focused device test after qualification

Install 0.5.16 over the existing app; no runtime download or client preparation.
Restore DXVK 2.7.1 and keep FEX faster x87 on, Turnip 26, 60 Hz, Native Surface
and SHM. Keep sysmem/two-compiler tuning off.

Enable **Capture FFXI startup and graphics** in Client. Launch directly to the
broken menu/character selection, take a screenshot, Stop and export Diagnostics.
Then turn capture off. No standalone Windows check or world-entry attempt is
needed. If launch fails or hangs, Stop/export instead of repeated relaunches.

Preserve client `30251204_1`, original xiloader and the working Termux server.
Repository changes/pushes remain explicitly authorized. This does not authorize
client updates, re-import/preparation or server migration.
