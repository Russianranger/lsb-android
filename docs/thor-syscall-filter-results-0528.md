# 0.5.28: make the confirmed syscall optimization automatic

The user reports: “Trial F — that's the one. It's perfect. Integrate that as a
default function.” The new device receipts confirm that F actually activated;
this was not a compatibility-mode fallback. Promote that tested behavior without
stacking the other experimental graphics changes. The user does not need another
comparison matrix before this promotion.

## Device evidence and deduplication

* `lsb-support (11)(1).zip`, 220,258 bytes, SHA-256
  `5140d44227d2e6c3f01898b3ba0b6e0e340ba9fa19c8856fcba624c590c122aa`.
* `lsb-support (12).zip`, 225,143 bytes, SHA-256
  `fdfa2c9fe299fb46c6c2e78c726857b10725532a879b82aed286b5ac331d34c7`.

Export 12 retains six distinct session archives. Its first five archives are
byte-identical to those in export 11; count each session once. The old Baseline
`7f8d465e-0b07-475c-acbf-cb20cea9677d` and A
`921258d1-f3bc-4b0b-adde-84b248eddddd` are historical runs already analyzed in
[0.5.26](thor-trial-results-0526.md), not additional new comparisons.

| New run | Session | Runtime duration |
| --- | --- | ---: |
| Baseline | `b97d0124-3c2a-4678-957f-95093a26adf8` | 150.80 s |
| D, cached dynamic buffers | `4a97de02-642d-4e22-b41f-5189f1af9ef4` | 329.50 s |
| E, GPL base pipelines | `20fc5fd1-ae5a-47ca-b209-b203532974af` | 143.52 s |
| F, syscall filtering | `0b7bf44a-f500-44f4-a6b8-1c85505b79df` | 214.47 s |

All four retain FEX/strict64, Turnip 26 on Adreno 740, DXVK 2.7.1, two compiler
workers, Native Surface/shared-memory upload, full 1280×720 interface/background,
and disabled sysmem, staged geometry and border adjustment. D/E receipts confirm
their intended options and 128 passing startup pixels. F changes the host syscall
path while retaining the graphics baseline.

F's host preflight took **202 ms**. Both `preflight_passed` and `launch_observed`
are true, `mode` is `syscall_filter`, and the guest independently reports active
`syscall_filter` with two compiler workers. The current host receipt and archived
host receipt are JSON-identical. All four new sessions finish with runtime phase
`completed`, client phase `exited`, child exit 0 and bridge exit 0. F therefore has
a clean recorded process exit, not merely a running receipt whose default exit
field happens to be zero. Gameplay correctness is supported by the user's report;
the startup observer itself does not certify world entry.

## Timing evidence and limits

For a common coarse window, include every nonterminal native timing window whose
start satisfies **90 ≤ t < 130 seconds**. Each run contributes eight windows,
about 40.06–40.09 seconds. D's longer run retains only its last 48 of 66 windows,
beginning at 90.18 seconds; earlier windows cannot be reconstructed. These are
elapsed-time windows, not matched route or camera markers.

| Run | Weighted Surface posts/s | Recorded post gaps >100 ms | Capture ms/frame |
| --- | ---: | ---: | ---: |
| Baseline | 26.13 | 12 | 2.55 |
| D | 24.76 | 13 | 2.37 |
| E | 25.22 | 18 | 3.11 |
| F | 29.34 | 0 | 0.97 |

Surface posts are **not game FPS**. Gap counters belong to the reporting window;
a gap can begin before its window starts. Do not derive a percentage game-FPS
gain or claim route-controlled benchmarking from this table. F's 60–170 second
windows are consistently about 29.2–29.4 posts/s, which agrees with the user's
reported smoothness. Later windows remain about 25.9–27.8 posts/s before exit.
Startup/scene transitions still produce gaps. Android copy and Surface-post time
are not uniformly lower, so this does not establish that the prior bottleneck was
the Android copy operation or explain every remaining frame interval.

## Default behavior and next check

Enable the optimization automatically for the tested launch profile: FEX,
Turnip 26, DXVK 2.7.1, confirmed two-worker settings, with other experiments off.
Keep it separate from experimental graphics choices. Preserve the credential-free
preflight, actual-launch activation check, session receipts and cleanup rules.
An unsupported or failed preflight retains compatibility mode; inconsistent
activation or failure to retain the effective graphics baseline must stop before
login rather than claim an impossible in-process rollback.

Provide a normal on/off checkbox under Proven fixes so compatibility mode remains
available. The default is on for eligible launches, and an explicit saved off
choice must persist. Existing Trial F selection migrates to the normal tested
profile. Preserve the user's other explicit settings instead of silently stacking
D/E or past experiments with the newly accepted default.

After installing the update in place, normal play is sufficient. No new baseline
matrix, runtime reinstall, client reimport or cache reset is needed. Export support
if an unexpected slowdown or launch issue occurs.

## Completed 0.5.27 CI follow-through

Both exact-source runs have now passed every validation job:

* [Push run 36054096976](https://github.com/Russianranger/lsb-android/actions/runs/36054096976):
  FEX `107817584316`, Box64 `107817584243`, verify, presentation, server deployment
  and native Windows all succeed; runtime-release `107826383576` also succeeds.
* [PR run 36054103253](https://github.com/Russianranger/lsb-android/actions/runs/36054103253):
  FEX `107817968603`, Box64 `107817968483`, verify, presentation, server deployment
  and native Windows all succeed. Runtime-release is intentionally skipped for PRs.

Both FEX logs explicitly confirm the production syscall-filter preflight marker,
actual enabled filtering, real FEX execution with D3D8 pixels, native capture,
PCM/input and clean stop. They also confirm the independent A/D/E checks with
384 pixel samples. The enabled-filter CI proof uses Linux PRoot/FEX; the Thor
receipt and user observation supply the separate Android-device evidence.
Prior 0.5.26 timeout failures remain historical unresolved findings; no unchanged
retry or relaxed assertion was used to make 0.5.27 pass. This records 0.5.27
results, not acceptance of the subsequent 0.5.28 implementation.
