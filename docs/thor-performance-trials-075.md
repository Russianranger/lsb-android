# 0.7.5: three new performance trials with syscall filtering retained

The user reports regular FFXI FPS below 30 and requests three optimizations at a
time. The accepted syscall-filter and two-worker profile stays active for these
three new independent trials. No default trial, visual-quality reduction or
claimed device FPS improvement is introduced.

## Latest device evidence

Supplied `lsb-support (1)(20261010-023150).zip`, SHA-256
`f5538e6a2092a1e09af425f362b8cf0bec820150b12ab2c36cabbd661da6a957`,
contains one new 0.7.4 session:
`5b834747-553b-4df2-9911-669638f3aacc`. The other five session archives are
historical, including the earlier failed startup, and are not additional
0.7.4 comparisons.

The new session runs from 02:08:42 to 02:11:41 UTC on October 10, 2026
(October 9 evening in Chicago). It confirms FEX v3/strict64, Wine 10/FEX 2510,
Turnip 26.0.0 on Adreno 740, DXVK 2.7.1, two compiler workers, the 1280×720
windowed profile, native Surface and shared-memory upload. System-memory and
staged-buffer experiments are off, with no performance trial active. Border
removal is requested and applied; the game client area remains 1280×694 while
the backing capture is 1280×720. Preserve that setting during comparison.

Both independent host receipt fields `preflight_passed` and `launch_observed`
are true, the mode is `syscall_filter`, and preflight takes 604 ms. Guest status
confirms the same actual filter and two-worker baseline. The new combined
eight-frame DXVK/tuning check is verified. Client and bridge exit codes are
zero. This archive supports a successful 0.7.4 startup without the earlier
misleading disable-filter error; it does not establish the exact cause of that
earlier intermittent graphics hang.

Server zone-entry events place the character in zone 100 at 02:10:07 and
02:10:11 UTC, around 85–89 seconds into the runtime. The large 80–85 second
delivery slowdown overlaps world entry, so it is not counted as steady world
play. The startup-only process observer finishes its last sample at 9.62
seconds; it does not keep scanning the game throughout the measured route.

## Timing and limits

Use every nonterminal native timing window with start time **90 ≤ t < 160
seconds**. These 14 windows cover 70.152 seconds and 1,835 Surface submissions.
They are an elapsed-time selection after world entry, not a controlled camera
route or an independent game FPS measurement.

| Counter | Latest 0.7.4 run |
| --- | ---: |
| Weighted Surface posts/s | 26.157 |
| Five-second window range | 25.185–26.772 posts/s |
| Posted gaps over 100 ms | 69 |
| Posted gaps over 250 ms | 0 |
| Median recorded long gap | 150.687 ms |
| Median long-gap interarrival | 1.019 s |
| Median unchanged replies within a long gap | 8 |
| Capture time per posted frame | 2.403 ms |
| Android native copy per posted frame | 0.582 ms |
| Surface lock / post per posted frame | 0.223 / 1.234 ms |

Long gaps contain repeated responsive unchanged-image replies with small
Android loop gaps. This places the missing new image upstream of the final
Surface copy in those intervals; static scenes, game logic, translation,
rendering, asset work and scheduling remain possible explanations. The nearly
one-second cadence is a useful CPU/scheduling investigation lead, **not proof**
of a server tick, shader compilation or a specific thread causing the pauses.
The roughly 20 ms request/receive value includes paced requests and response
work; it is not an isolated game-render time.

Actual game uploader `wsi-upload-15573.bin` records 3,422 calls, all through
SHM, with zero socket fallback or attach failure. Average uploader copy is
0.926 ms (maximum 11.469 ms); average slot wait is 0.00546 ms (maximum 0.748
ms). These counters do not justify another ring-depth or socket-transfer
optimization. Android report writing remains bounded and off the frame worker.
Audio underruns settle at three and rise to four once, rather than increasing
continuously.

Earlier archived sessions with the same requested runtime profile have higher
delivery averages in some windows, but their scene, route and thermal state
are unmatched. No temperature or frequency telemetry establishes throttling
in this archive, and the steady 90–160 second delivery remains approximately
flat. Do not claim that 0.7.4 caused a performance regression, that cooling is
the cause, or that Surface posts equal the user's HUD FPS.

## Three independent candidates

| Trial | Exact experimental option | Hypothesis and tradeoff |
| --- | --- | --- |
| G: non-tiler scheduling | `dxvk.tilerMode = False` | Test desktop-style render-pass/submission heuristics instead of the Turnip default; they may suit this workload better or worsen tiled-GPU bandwidth and power |
| H: smaller FEX translation units | `FEX_MULTIBLOCK = 0` | Avoid combining multiple guest basic blocks into one translation unit; first-use translation may cost less, while extra dispatches can reduce sustained throughput |
| I: immediate presentation | `d3d9.presentInterval = 0` | Remove DXVK's requested vertical synchronization interval; useful if double pacing is involved, but may be ineffective if the game already requests immediate presentation and can increase tearing/power |

These are performance hypotheses. The supplied archive does not identify the
current game's selected tiler heuristic or presentation interval, and it does
not prove that translation-unit compilation causes the periodic pauses. Trials
must record their consumed settings rather than infer activation from a saved
checkbox. The finite graphics fixture's immediate presentation setting is not
proof that FFXI itself uses the same presentation interval.

The exact pinned primary sources establish the options' behavior:

- DXVK 2.7.1, commit `c3dd74be6baec53786d4e064a572185b70347a17`:
  [configuration](https://github.com/doitsujin/dxvk/blob/v2.7.1/dxvk.conf) and
  [device hints](https://github.com/doitsujin/dxvk/blob/v2.7.1/src/dxvk/dxvk_device.cpp).
  Automatic tiler mode selects the Turnip render-pass hint. G overrides that
  hint; it does not enable the old `TU_DEBUG=sysmem` experiment or replace the
  GPU driver.
- FEX commit `320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab`:
  [Multiblock configuration](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/FEXCore/Source/Interface/Config/Config.json.in),
  [environment mapping](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/Source/Common/Config.cpp)
  and [WoW64 configuration loading](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/Source/Windows/WOW64/Module.cpp).
  Multiblock defaults on; H overrides it through the supported environment
  configuration. It does not change x87 precision or memory ordering.
- DXVK's [D3D9 swapchain](https://github.com/doitsujin/dxvk/blob/v2.7.1/src/d3d9/d3d9_swapchain.cpp)
  applies the configured present interval after the game's interval. I therefore
  tests synchronization without changing the Android Surface's 60 Hz capture
  preference or the game's own cap.

The accepted FEX precision and CPU-feature behavior, DXVK 2.7.1, Turnip driver,
two compiler workers, host filter receipts, shared-memory transport, resolution,
game frame cap and shader caches are preserved. G/H/I are mutually exclusive.
Old A/D/E selector choices reset to Baseline with a notice; their historical
runtime paths remain readable and are not combined with these new trials.
Retired B/C and the unsuccessful past system-memory/staged-buffer experiments
remain retired.

Startup validation uses the requested profile from the beginning, including
the filter and two workers, and requires finite draw/presentation and pixel
checks before a new trial can qualify. A failed check cannot authorize an
unverified game launch or silently turn off the accepted stability setting.

## Phone comparison

Install version 0.7.5/code 79 over the existing app with the original certificate.
No runtime reinstall, preparation, reimport, server rebuild or cache reset is
required. Run Baseline, G, H and I separately over the same 3–5 minute route,
with the same camera/view, border state, 1280×720 profile, cap, HUD and fan/power
mode. Compare first and repeat traversal, and cold versus warm startup without
deleting caches. Stop and export one clearly labeled support ZIP per run.
Measure game HUD FPS and visible pauses; the display receipts corroborate
delivery and activation but cannot replace those observations.

Choose a trial for normal play only after it improves the user's route without
graphics, input, audio or stability problems. See
[focused instructions](TESTING-0.7.5.md). No trial removes FFXI's own frame cap,
and these candidates do not lower texture quality or rendering resolution.

## Validation status

The Android verify/build passes 213 runtime contracts, 180 Android tests and
138 server tests at `cc58aa42ebf3a81d0cd11127275fa0e4b5ca7d16`, run
`38018524834`, job `114114224212`. The original-signed 0.7.5/code 79 APK matches
all 74 qualified CI payload entries. Current functional source
`d9f587f7a07ae8e48d0e91a565429a2368d7646e` changes only a synthetic short-JIT
child-receipt regex after that Android build; the APK inputs remain unchanged.
The focused ARM64 push gate passes all five independent host-filtered launches
at that functional source: Baseline twice, then G/H/I, with 720 independent
synthetic child texture pixels and all strict setting/worker/version/shutdown
assertions. Push run `38019029060`, job `114116210327`, produces evidence
artifact `11657825320`, ZIP SHA-256
`941c647b3602f0c5c5268deca185d3b0d85908457357bc1bc4f02831bb50795f`.
The parallel focused PR gate also passes: run `38019032575`, job
`114116238611`. Independent evidence inspection confirms five distinct filtered
sessions, all 720 child pixels and 7,560 draws, the three trials' 384 startup
probe pixels, exact inherited trial settings and clean exit/shutdown. Broader
FEX/Box64/PlayOnline jobs remain pending; the latest push server-deployment job
`114115681868` passes. Historical legacy trace
failures are recorded separately. See
[exact qualification and APK identity](QUALIFICATION-0.7.5.md). No synthetic
test establishes an Adreno or real-client FPS improvement.
