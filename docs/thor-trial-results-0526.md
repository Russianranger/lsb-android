# 0.5.26: retain A, retire B/C, preserve session comparisons

User's last four runs: Baseline unchanged, A a small perceived improvement,
B froze before the menu, C worst performance with camera-pan FPS in low single
digits. The two earlier runs had staged geometry enabled and must not be pooled
with this comparison. No claim that A is now proven or a measured percent faster.

**Follow-up:** the user subsequently supplied two fresh Baseline/A runs from
0.5.25 in exports (9)(2) and (10)(2). These independently verify A's effective
one-worker setting. They do not recover the original overwritten Baseline/A runs.
The new comparison is documented below; another repeat is no longer needed just
to establish those settings.

## What this export actually retains

`lsb-support (8)(2).zip`, SHA-256
`4a9f161222843e47d2dbebac3f2bd23e8187146944771d0f5813ed06a9b977d1`.
This app version retains only current and previous log slots. Those are C and B.
Baseline, A and the earlier geometry-enabled sessions have been overwritten.
One stale `native-display-performance.json.new.previous` is from unrelated
session `8b769cc2-55ee-4e37-8955-5f92b6e4affb`; it is not usable as A/B evidence.
Do not claim to have independently checked four runs from this ZIP. User reports
remain useful evidence, explicitly separated from retained settings/timings.

| Run | User observation | Retained evidence |
| --- | --- | --- |
| Baseline | as before | none from this four-run set |
| A | small improvement | none; actual one-worker application cannot be audited here |
| B | froze before menu | `a8ccfc42-4d4c-4d33-9b15-ec471a501a13`, 62.6 seconds |
| C | severe pan slowdown | `78eadd67-d19a-4159-8ce7-bb82e53f0301`, 204.2 seconds |

## Fresh Baseline/A comparison supplied before delivery

* `lsb-support (9)(2).zip`: SHA-256
  `22be4822eeb1f2935ff2079cb68bff87ea5949c620eb0f1aacf2c0ce4634c7bc`.
  Current Baseline `7f8d465e-0b07-475c-acbf-cb20cea9677d`, 169.3 seconds;
  previous slot is the already-reviewed C session.
* `lsb-support (10)(2).zip`: SHA-256
  `ea1613e20bbe73aa2cf57c20c908ef7e749570863df076ec9786a51c055c4eec`.
  Current A `921258d1-f3bc-4b0b-adde-84b248eddddd`, 229.9 seconds;
  previous Baseline's runtime, launch and native timing receipts are byte-identical
  to export 9's current receipts. Do not count that duplicate as another run.

Both retain FEX v3/strict64, DXVK 2.7.1, Turnip 26, SHM and full 1280×720 interface
and background. Geometry/sysmem/border adjustment are off. Baseline's registry
receipt explicitly restores background 960×540 → 1280×720 after C. A stays at
1280×720. The compiler count is the isolated requested variable: Baseline has
2; A's trial receipt and DXVK effective-config/worker lines confirm 1, with all
128 startup pixels passing. A's `graphics_tuning.compiler_threads=2` records its
starting profile; `performance_trial.compiler_threads=1` is the effective override.

For a transparent coarse summary, select native timing windows starting at or
after 60 seconds and exclude the terminal window. This avoids startup/Stop but
does **not** align routes or guarantee active panning. No windows were dropped.

| Measurement | Baseline | A |
| --- | ---: | ---: |
| Included native window time | 60.12–160.38 s | 60.15–220.55 s |
| Weighted Surface posts/s | 27.25 | 27.45 |
| Post gaps >100 ms | 8 in 100.25 s | 10 in 160.40 s |
| Post gaps >250 ms | 0 | 0 |
| Weighted capture ms/frame | 2.36 | 2.27 |
| Weighted copy ms/frame | 0.48 | 0.51 |
| Weighted Surface-post ms/frame | 3.92 | 4.25 |

These are display submissions, **not game FPS**. Neither run proves a consistent
win; A is compatible with the user's small perceived improvement, and remains
promising rather than proven. A has a roughly 21 posts/s window around 130 seconds
alongside many 25–29 posts/s windows; Baseline also fluctuates around 25–29 after
its initial transitions. A's later long 29/s stretches do not establish that it
renders an identical scene faster. Order/cache/route effects are not controlled.

Before 60 seconds both have long gaps: after 35 seconds the largest recorded gaps
are Baseline 1.93 s with 113 unchanged replies and A 0.97 s with 56 unchanged
replies. Scene transitions/idle intervals cannot be separated from stalls without
route markers. The responsive capture loop, few-ms capture/copy and sub-ms report
enqueue/loop gaps do not support blaming a blocked Android copy/log writer for
those long waits. They also do not distinguish game asset work, shader work or
renderer scheduling. Keep that distinction open for the next targeted profiling.

No repeat of the same Baseline/A test is needed merely to recover missing logs.
Use A for normal play if it feels better, keep full 720p and past experiments off,
and export after a noticeable pause. Two workers remains the proven baseline;
returning to Baseline is available if A feels worse.

## Original B/C evidence

B and C both use FEX v3/strict64, Turnip 26, hardware DXVK 2.7.1, confirmed
2-worker baseline, SHM and 1280×720 output. Staged buffers and sysmem are off;
border adjustment is disabled. Startup observation finishes at about 8 seconds.
Both end with user Stop; no natural client-exit result is available.

B confirms `dxvk.trackPipelineLifetime = False`, GPL support and 128 passing
startup pixels. It then produces only 29 captured frames in ~55.6 seconds. The
last ~20.7 seconds contain no new frames, while capture requests continue at
about 58/s with unchanged replies. This agrees with the reported freeze and
shows a responsive display service. It does not prove deadlock, memory exhaustion
or GPU failure; there is no recorded fault establishing the exact hang cause.
A successful synthetic preflight does not establish real FFXI compatibility.

C's registry receipt verifies 0001/0002 stay 1280/720 and only background
0003/0004 become 960/540, with no write/rollback error. It is the intended trial,
not accidentally full-resolution or an old staged-buffer combination. Between
roughly 35–195 seconds, timing windows fluctuate widely, including 3 and 8.4
Surface posts/s, many 0.4–1.7-second gaps, and stretches near 29 posts/s. Capture
is usually ~1–4 ms/frame and copy ~0.5–0.8 ms, somewhat higher in sparse windows.
Unchanged replies continue between posts. These are Surface measurements, not
DXVK FPS, and there are no route markers. They support a severe intermittent
regression but do not diagnose why lower background resolution hurts this path.
Do not assume an Android copy bottleneck or claim a specific GPU scaling bug.

## Changes

* B/C move into Past experiments as retired, unavailable choices. Runtime rejects
  their activation even if an old saved request reaches it, recording the reason
  and keeping the validated baseline. The 540p internal native profile is retained
  only for historical tests; the app/runtime no longer selects it. Returning to
  Windowed 1280×720 restores both background and interface dimensions.
* A remains a promising, unproven trial. Two workers remains the accepted baseline.
  No FEX precision, driver, shader DLL, display worker or per-frame algorithm change.
* New support history retains six session snapshots, identified by session UUID,
  containing runtime settings, launch receipts and matching native/fallback timing
  histories. It seeds the two old slots on upgrade, refreshes a session on export,
  preserves chronological order and bounds files and count. Mismatched or missing
  component receipts are explicitly omitted; partial `.new` files are excluded.
  Earlier overwritten runs cannot be recovered. Captures occur only before a new
  launch and on explicit export, never in the gameplay frame loop. Existing live
  diagnostic logs stay in the ZIP alongside `runtime/sessions/index.json`.

Initial next-check plan was a focused A run; the fresh exports above now satisfy
the missing-settings check. Install in place, Use tested shader settings, Windowed
1280×720, then select A for normal play if preferred. Keep staged geometry, sysmem,
border adjustment and startup capture off. No repeated test matrix, runtime
reinstall, cache reset or reimport is needed. Check A's
`performance_trial` effective worker count; `graphics_tuning` describes its
2-worker starting profile, not the one-worker override. New archive will make
future multi-run comparisons auditable from one ZIP.

## Prior CI follow-through

0.5.25 PR FEX 107794277708 passed the complete gate including A/B pixels and
registry checks; this did not predict B's device hang. PR Box64 107794277654
failed on the existing observed D3D8 pixel fixture's 60-second timeout under
DXVK 2.5.3, before reaching the new trials. Push FEX 107794714838 failed at that
same earlier observed fixture; its expected negative-test exceptions are not
the cause of the failed job. Push Box64 107794714596 subsequently passed its
complete gate. The two failed jobs remain unresolved; no unchanged retry or
relaxed assertion.

0.5.26 local core checks and 69 runtime contracts pass. New Android tests cover
six-session pruning/order, upgrade seeding, final refresh, mismatched session
rejection, partial-file exclusion and bounded source size. This pass is recovery and evidence
retention, not a claimed FPS increase over A.

## Delivered APK and verification

Recovery implementation `4f60a37b30188139364d4969565814fd1c6c79ee`, final UI wording
`cd290be19d5ec51657202fb1bd043d39794fd660`, version 0.5.26/code 42, pushed to
`codex/client-baseline`; no merge. Final push run 36051583691, device artifact
10830034448, ZIP SHA-256
`597dfc1d3cd758c050fdee6a7476ed769b001a9aa592b76b15a91e71130c8d9e`.

`LSB-Android-0.5.26.apk`: 18,309,268 bytes, SHA-256
`0474b2a67f3f16a8e8dd1e913dd624520ad46c115b50c05ddf9406f66fc09d4a`.
Original signer SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Package/version, ZIP integrity, v2/v3 signatures, 16 KiB alignment, runtime
manifest digests and unchanged payload through re-signing all checked. Compared
with 0.5.25, all runtime/native entries are byte-identical except the two intended
Python sources (`supervisor.py` and `client_launch.py`). No renderer/DLL change.
Library `libfile_0e87e80ecba08191aac36df30902f5af`, version 1, file
`file_0000000048cc822f8cec65553716b828`. This supersedes the preliminary APK
`701022255be4037fdcdfbaf54dd619f642f2a501418ca4b8a97bdf1430a5f8a2`, replaced before
final delivery to include the newly supplied Baseline/A evidence in the UI.

Both final push and PR verify jobs passed, including all 22 Android tests, core
checks, packaging, presentation and server gates. Both native Windows jobs passed.
Wide/narrow optimization and past-experiment screenshots were visually reviewed;
text and controls fit. Full runtime gates remain pending:

* Final push 36051583691: FEX 107809166178, Box64 107809166474.
* Final PR 36051588916: FEX 107809407297, Box64 107809407350.

Earlier 0.5.26 recovery implementation CI has unresolved failures:

* Push 36050716378 Box64 107806214712: existing observed D3D8 pixel fixture's
  60-second timeout on DXVK 2.5.3 before trial activation.
* PR 36050722375 FEX 107806500337: requested 2.7.1 preflight times out, falls back
  to 2.5.3 and completes the probe, then fails the exact-version assertion at
  integration.py:107. Earlier negative-test exceptions in this log are expected.
* Earlier push FEX 107806214742 and PR Box64 107806500417 still require follow-up.

The final UI-only wording update is not a fix for those runtime timeouts; its CI
was triggered by the actual source change, not an unchanged retry. Do not describe
full CI as green or device A performance as proven. Follow through on these jobs
next turn; diagnose failures without unchanged retries or relaxed assertions.
Install this signed APK over the existing app; do not uninstall.
