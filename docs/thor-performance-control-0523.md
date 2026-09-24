# 0.5.23 baseline control: four-way 0.5.22 performance report

The user reports a small degradation with every combination: borders on /
geometry off, both on, borders off / geometry on, then both off. This does not
demonstrate a successful optimization or establish which component regressed.
Staged uploads have no demonstrated benefit and should remain off. Do not ask
the user to repeat the four-way matrix or add another tuning variable yet.

## Export identity and effective settings

Read the supplied local copies. The current and previous session slots retain
all four runs, plus the accepted 0.5.21 session. Deduplicate by session ID rather
than counting repeated exports as new runs.

| ZIP | SHA-256 | Current / previous session |
| --- | --- | --- |
| `lsb-support(10).zip` | `d7e0e23a8e618d33022cd8885cdf5b92927fbcb6362836b0dfade99cc75156c7` | `905d980f` / `e5d2591f` (0.5.21) |
| `lsb-support (1)(4).zip` | `e4da5c0893eb3224e57f3c918b11f9575dc27c7da55fae25feb4cf2e4812f386` | `c599ce14` / `905d980f` |
| `lsb-support (2)(6).zip` | `f5ca703b732232751cd77a2b831a02ffe04e93c8d8012bb568366ffdd5eaf834` | `fbcacd9a` / `357685be` |

| Order | Session ID | Border receipt | Staged uploads active |
| --- | --- | --- | --- |
| 1 | `905d980f-0ee8-44a1-aebf-bbb14f52cccf` | applied | false |
| 2 | `c599ce14-1d19-4047-85a9-1f19bacd99f6` | applied | true, 128-pixel preflight passed |
| 3 | `357685be-8992-4ecf-90a2-33144a41a46a` | disabled | true, 128-pixel preflight passed |
| 4 | `fbcacd9a-9f87-4408-a5d8-80450684f117` | disabled | false |

FEX v3 (`030f3806…`), strict64, DXVK 2.7.1, Turnip 26, 1280×720 root capture,
60 Hz display cap, SHM and detailed HUD remain in use. Sysmem and two-compiler
experiments are off. Session 3 ends through Stop; the other three have normal
client exit zero. There is no evidence that geometry tuning persisted into
the final off run. The loader's last startup scan ends at 8.1–9.3 seconds;
the recurring window-scanning regression did not return.

## Timing evidence and its limits

These are Surface submissions, not game FPS. Different run lengths, scene
timing and retained windows prevent a controlled numerical comparison.
For an explicit descriptive slice, select complete five-second windows whose
start is at least 60 seconds and submission rate at least 20/s. This excludes
many menu/loading/idle intervals but also excludes severe slowdowns; it must
not be presented as a whole-run benchmark or a percentage regression.

| Run | Retained selected seconds | Posts/s | Capture ms/frame | Android copy ms/frame | Surface post ms/frame |
| --- | ---: | ---: | ---: | ---: | ---: |
| Prior 0.5.21 | 230.47 | 28.68 | 1.871 | 0.438 | 4.727 |
| Borders on, geometry off | 135.25 | 26.81 | 2.605 | 0.461 | 3.031 |
| Both on | 120.23 | 26.49 | 2.673 | 0.475 | 3.343 |
| Borders off, geometry on | 175.35 | 26.63 | 2.204 | 0.483 | 3.262 |
| Both off | 100.22 | 25.98 | 2.649 | 0.490 | 2.937 |

All four use SHM for every delivered frame. Selected-window Surface lock stays
0.205–0.208 ms; maximum Java-loop gaps stay below 0.21 ms. Android copy cost
increases only about 0.02–0.05 ms relative to the older export, while capture
increases by roughly 0.3–0.8 ms and Surface post time falls. This does not
support Android copying as the dominant new stall. Longer gaps commonly show
repeated unchanged responses, locating them before fresh pixels reach Android;
menus/loading can also produce those responses. Do not label every gap a
rendering stall, shader compilation or thermal throttling.

All combinations return to roughly 29 submissions/s in parts of the run;
slower stretches are commonly in the low-to-mid 20s. There are no scene
markers, device thermal history, CPU/GPU clocks or game-frame timestamps to
separate workload, power/thermal conditions and a build regression conclusively.

Actual 0.5.21 versus 0.5.22 APK entries differ only in AndroidManifest.xml,
classes.dex, runtime/client_launch.py, runtime/supervisor.py,
runtime/client-launch.exe and runtime/graphics-check.exe. Every other payload
entry, including native display libraries and x11-frame-bridge, is identical.
Both-off source paths bypass the new tuning and border adjustment. This audit
narrows the search but does not prove the newer build cannot regress.

Separate border finding: both applied receipts preserve a measured **1280×694**
client, moving origin **(6,32) → (0,0)** with no Win32 error. The 0.5.22 promise
to preserve measured client size held, but the real game was already height
constrained at observation. Full 720p edge coverage is not established; a bottom
gap is possible. Do not conflate that with the all-off performance report or
change window sizing during the baseline comparison.

## Delivered control APK

`LSB-Android-0.5.23-baseline-control.apk` contains the exact 0.5.21 application
payload from commit `d52a7542750fe48f45e3fca1b52abab8a91ef3c4`. It is a diagnostic
control, **not a claimed performance fix**. Fullscreen, fantasy tiles, gold tabs
and the support-export tile remain. The two 0.5.22 controls and implementations
are absent. Existing saved preferences and prepared client data are retained;
the app copies its bundled matching helpers at the next launch.

Only manifest versionName 0.5.21 → 0.5.23 and versionCode 37 → 39 change before
alignment/signing. Advancing the version permits an ordinary update over 0.5.22
without uninstalling. **Reserve code 39 for this control; the next app must use
at least code 40.** Main branch production source remains 0.5.22 intentionally;
the control is reproduced by `scripts/build-baseline-control.py`, not by the
ordinary current-branch APK build. Do not accidentally deliver CI's 0.5.22 APK
as this control.

Source artifact: run `35945502024`, artifact `10786652295`; archive SHA-256
`69a735b37ef8bfe1e7b0d35b86248bd4152adf06fe8f405be7115568b781ff6e`.
Source CI APK SHA-256
`b0d87d763057680cc56a47ab9f0b94a49ee3daaf64aa68514c021e215c4d57af`.
The script requires this exact source and validates the binary manifest fields
against Android's [resource structure definitions](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/androidfw/include/androidfw/ResourceTypes.h).
Exactly two manifest bytes change. AAPT2's complete decoded manifest differs
only in the two version fields. All other **52 payload entries** match the source
byte for byte, including classes.dex, resources, native libraries, PE helpers
and Python files. ZIP integrity, all runtime bundle hashes, package/version,
16 KiB native alignment and v2/v3 original-certificate signatures pass.
No new game-runtime code was compiled or substituted; existing 0.5.21 runtime
validation applies, with its earlier primary FEX timeout retained in its report.

Control APK: **18,301,076 bytes**, SHA-256
`ad7719b1a075282894b83817b42e17eb7098bc3c9045ae772f263e3376663b99`.
Signer SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Library ID `libfile_4d1156d21418819193893ba7bfd2ea63`, file ID
`file_00000000ca30820e85a62e26fd560764`.

## One focused device test

1. Stop the game and install the control APK over the existing app. Do not
   uninstall, reset, reimport, prepare the client, reinstall FEX or clear caches.
2. Keep the same display/HUD/graphics settings. Startup capture stays off.
   The old top/left border is expected; this comparison restores the old app.
3. With the handheld cooled and the same power/fan/charging settings, walk the
   same busy route for 3–5 minutes. Pan once into the area and once again after
   it has loaded. Note whether the older smoothness returns and FPS at the
   same trouble spot. Stop and export one support ZIP.

If the control restores smoothness, narrow the 0.5.22 code delta with a repeat
comparison before attributing it to either toggle. If not, investigate shared
workload/device conditions next; this does not by itself prove throttling.
No additional optimization is activated during this diagnosis.

## 0.5.22 CI follow-through

Both Box64 jobs completed successfully: `107513222031` (push `35962083617`)
and `107513197834` (PR `35962087529`). Independent PR FEX `107513197849` also
completed successfully. Primary FEX `107513222153` remains failed at the
observed-pixel timeout documented in the 0.5.22 report. Runtime-release is
skipped in both runs. No unchanged retry or weakened assertion was used; do
not describe either whole workflow as an all-green release.
