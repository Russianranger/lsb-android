# 0.7.5 filtered performance candidate qualification

Version 0.7.5/code 79 adds three independently selected experiments while
retaining the accepted syscall-filter and two-worker profile. G changes DXVK
tiler hints, H disables FEX multiblock translation, and I requests immediate
D3D9 presentation. Each qualifies the requested environment before login.
No Adreno FPS gain or real FFXI compatibility result is claimed before the
next Thor pass.

## Source and review scope

- Branch: `codex/filtered-performance-075`, stacked on the published 0.7.4
  startup fix `dcadcd653f57b2270430b446877f9b144e69e38e` and the complete
  0.7.3 storage/update-cleanup continuation. Main and existing releases remain
  unchanged. Draft [PR #5](https://github.com/Russianranger/lsb-android/pull/5)
  stacks draft [PR #4](https://github.com/Russianranger/lsb-android/pull/4).
- Android-qualified source:
  `cc58aa42ebf3a81d0cd11127275fa0e4b5ca7d16`.
- Current functional source:
  `d9f587f7a07ae8e48d0e91a565429a2368d7646e`. The only change after the
  qualified source is a synthetic short-JIT child-receipt regex in
  `tests/runtime/proot_integration.py`; APK inputs are unchanged.
- The user explicitly authorized this and future GitHub pushes for
  `Russianranger/lsb-android`. That authorization does not request merging the
  review branches or publishing an official release.

## Build and correctness checks

[Verify run 38018524834](https://github.com/Russianranger/lsb-android/actions/runs/38018524834),
job `114114224212`, passed at the Android-qualified source. It includes
**213 runtime contracts, 180 Android tests and 138 server tests**, plus core
import, preparation, archive and interrupted-operation recovery checks and
APK compilation. Independent review found no blocker.
Wide G and narrow I launcher previews were inspected for readable text and
clipping.

The new tests cover isolated G/H/I selection, retired-selector normalization,
real settings navigation, exact current-process version/config/worker/ordered
frame and pixel receipts, inherited FEX options, failure before login,
cleanup/cancellation and preserved baseline behavior. These are correctness
and wiring checks, not device performance measurements.

For H, proof combines the pinned FEX WoW64 environment-loader source,
inherited `FEX_MULTIBLOCK=0` receipts in the graphics and synthetic loader child,
and the pre-login parent/child environment-hash check. This is not an internal
FEX configuration readback or proof of shorter real-game compilation pauses.

The focused ARM64 filtered gameplay job retains two fresh baseline launches
and adds a fresh launch for each trial. It checks protected host activation
markers, actual FEX execution, synthetic child inheritance and texture pixels,
and clean child/launcher exit. At the current functional source, push job
`114116210327` in [run 38019029060](https://github.com/Russianranger/lsb-android/actions/runs/38019029060)
**passes all five independently host-filtered launches**: Baseline twice, then
G, H and I. The host preflight passes at 03:04:48 UTC; launch completion receipts
are at 03:05:18, :26, :35, :43 and :51 UTC on October 10. All strict DXVK 2.7.1,
two-worker, consumed G/H/I configuration, inherited child-setting/output, pixel
and shutdown assertions pass. The five child runs check **720 independent
texture pixels**. Evidence artifact `11657825320` has ZIP SHA-256
`941c647b3602f0c5c5268deca185d3b0d85908457357bc1bc4f02831bb50795f`.
The parallel focused PR job `114116238611` in
[run 38019032575](https://github.com/Russianranger/lsb-android/actions/runs/38019032575)
also passes. Independent inspection of the push evidence confirms five distinct
session UUIDs and host PIDs, actual `syscall_filter` for each, DXVK 2.7.1 and
two workers in each child, 144 pixels and 1,512 draws per child (720 pixels and
7,560 draws total), and completed state with child exit zero and clean shutdown.
G/H/I each pass 128 software/hardware vertex-processing startup pixels (384
total) and three two-worker receipts. Their child receipts confirm G's
`tilerMode=False`, H's `multiblock=0` and I's `presentInterval=0`. The artifact
digest and ZIP CRCs pass.
The passing PR's separate focused evidence artifact is `11658205127`, ZIP
SHA-256 `6c406c60024ae11bb676016a097b7a650fd5faa0a9fe12b123abbcd0c8c78110`;
its logs also record all five actual host-filtered cycles and 720 child pixels.

Verify and native Windows jobs also pass in both latest runs. Latest push
server-deployment job `114115681868` passes. Broad FEX, Box64 and PlayOnline
jobs are still running at this qualification checkpoint; no complete-workflow
pass is claimed.
At the earlier Android-qualified source, focused jobs `114114756034` (PR) and
`114114804351` (push) completed baseline cycles 1/2 and G cycle 3. H cycle 4
passed its production 128-pixel check, synthetic child 144-pixel check and
multiblock-zero receipt, then a capture-free regex returned the wrong Python
match shape in the CI assertion. I cycle 5 was not reached. Commit `d9f587f`
adds the regex capture; it changes no production code or pixel/version/worker
assertion. CI uses lavapipe, not Adreno; this complete focused synthetic pass
does not establish a real-client FPS improvement.

Broad legacy trace qualification results are separate from this focused
filtered gate:

| Run / job | Recorded failure |
| --- | --- |
| 0.7.4 PR `38017531043` / FEX `114111582163` | `trace_probe.py:33`: observed D3D8 trace fixture exceeded its existing 60-second deadline |
| 0.7.4 push `38017529183` / FEX `114111611430` | `trace_probe.py:65`: compressed-texture UP trace fixture exceeded 60 seconds after the observed D3D8 trace passed |
| 0.7.5 PR `38018524834` / Box64 `114114756005` | Unfiltered action-probe/DXVK 2.5.3 observed D3D8 trace fixture exceeded 60 seconds; baseline 128 pixels, native SHM and cursor checks had passed |

These failures do not show a filtered G/H/I launch failure, and a focused pass
would not erase them. Do not describe the full workflow as green while a
required broad job has failed or remains pending. No test deadline, render
assertion or filter activation requirement is relaxed to conceal a failure.

## Original-signed APK identity

- Source artifact: `11657201950` (`runtime-device-build`) from verify run
  `38018524834` at `cc58aa42ebf3a81d0cd11127275fa0e4b5ca7d16`.
- Downloaded artifact ZIP SHA-256:
  `1079b58fbe423db233afbb669b76e34f5a9d90aff14ae3725024d901ff6fab1f`.
- Source CI APK SHA-256:
  `8fc17cec24dd63dff695a514b876d6216b2cc4542563b23e8469561f8e50aca4`.
- Delivered file: `LSB-Android-0.7.5.apk`, **18,601,688 bytes**.
- Package: `io.github.russianranger.lsb`, version **0.7.5**, versionCode **79**.
- APK SHA-256:
  `416cd45b484c89b121a0f844c6862a05cb74f0c5383727387449e9aadfaf5073`.
- Original signing certificate SHA-256:
  `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.

All **74 non-signature payload entries** are byte-identical to the qualified
CI APK. APK v2/v3 signatures, original certificate, package/version identity,
16 KiB native alignment and ZIP integrity pass. Compared with the original-signed
0.7.4 APK, exactly six payload entries change:

| Changed entry | Purpose |
| --- | --- |
| `AndroidManifest.xml` | Version 0.7.5/code 79 |
| `classes.dex` | Baseline/G/H/I selection and settings propagation |
| `assets/runtime/supervisor.py` | Filter-preserving isolated profile qualification |
| `assets/runtime/fex_runtime.py` | Include multiblock setting in environment proof |
| `assets/runtime/fex-check.exe` | Check inherited multiblock environment |
| `assets/runtime/graphics-check.exe` | Finite combined warmup and pixel validation |

The other 68 payload entries, including driver/runtime/presentation binaries,
remain identical. The private original signing checkpoint stays outside Git.
Raw CI artifacts use a disposable certificate and cannot update the installed
standard app. Install the separately delivered original-signed APK in place;
no runtime reinstall, client preparation, server rebuild or data replacement
is required.

## Device evidence and remaining limits

The supplied 0.7.4 Thor session confirms actual filtering, two compiler workers,
the combined startup check, zone 100 entry and clean exit. In 14 nonterminal
windows beginning at **90 ≤ t < 160 seconds**, Surface delivery averages
**26.157 posts/s** over 70.152 seconds, with 69 gaps over 100 ms and none over
250 ms. Median long-gap duration is 150.687 ms with about 1.019-second
interarrival and eight responsive unchanged-image replies.

This suggests that new-image availability upstream of the final Android copy
needs investigation. It does not identify a specific CPU, server, shader or
thermal cause. Surface posts are not HUD game FPS, historical routes are
unmatched, and the archive does not establish a causal 0.7.4 regression. No
thermal or frequency telemetry proves throttling. Actual SHM uploader copy and
slot-wait counters do not justify another upload-ring change.

The next device comparison is Baseline, then G, H and I separately over the
same 3–5 minute route, with filtering and two workers ON, the same 1280×720
profile/borders/cap/view/power mode and retained caches. Stop and export each
run. See [focused testing](TESTING-0.7.5.md) and
[diagnosis and primary-source rationale](thor-performance-trials-075.md).
