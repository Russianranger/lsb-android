# 0.5.26: retain A, retire B/C, preserve session comparisons

User's last four runs: Baseline unchanged, A a small perceived improvement,
B froze before the menu, C worst performance with camera-pan FPS in low single
digits. The two earlier runs had staged geometry enabled and must not be pooled
with this comparison. No claim that A is now proven or a measured percent faster.

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

Next phone check: install in place, Use tested shader settings, Windowed 1280×720,
then select A. Keep staged geometry, sysmem, border adjustment and startup capture
off. Repeat the usual route/first and repeat pans for 3–5 minutes, Stop, export.
No repeat of B/C and no runtime reinstall, cache reset or reimport. Check A's
`performance_trial` effective worker count; `graphics_tuning` describes its
2-worker starting profile, not the one-worker override. New archive will make
future multi-run comparisons auditable from one ZIP.

## Prior CI follow-through

0.5.25 PR FEX 107794277708 passed the complete gate including A/B pixels and
registry checks; this did not predict B's device hang. PR Box64 107794277654
failed on the existing observed D3D8 pixel fixture's 60-second timeout under
DXVK 2.5.3, before reaching the new trials. Push FEX 107794714838 failed at that
same earlier observed fixture; its expected negative-test exceptions are not
the cause of the failed job. Push Box64 107794714596 was still running when
checked. These remain unresolved; no unchanged retry or relaxed assertion.

0.5.26 local core checks and 69 runtime contracts pass. New Android tests cover
six-session pruning/order, upgrade seeding, final refresh, mismatched session
rejection, partial-file exclusion and bounded source size. Android/CI and APK
identity will be appended after packaging. This pass is recovery and evidence
retention, not a claimed FPS increase over A.
