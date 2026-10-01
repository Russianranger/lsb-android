# Working Box64 versus failing FEX: 0.5.16 comparison

## Received evidence

The requested Box64 comparison is complete. ZIP `lsb-support (9)(1).zip`
has SHA-256 `d1a8a42d06fd1237e56bfe331c51d19e12abcafe13b39420563b4e43e2423dfa`
and persistent file ID `libfile_ba1bdde326c8819196a66effedc0ed46`.
Current Box64 session is `e06adc18-9430-44e7-aa31-74ed84a3e743`.
The same agreement and character-selection screens render correctly in the
10:59:01 and 10:59:20 screenshots, at HUD readings of 29.0 and 29.2 FPS.

Compare against the [failed FEX capture](thor-game-capture-0516.md), session
`b67f5ea4-380c-40f8-9b2a-245bfafa2f2a`. Both use app 0.5.16, the same
observer DLL, original xiloader, prepared generation, DXVK 2.7.1 DLL hashes,
Turnip 26 driver hash, 1280 x 720 profile, Native Surface and SHM.
Sysmem/two-compiler tuning are off in both. The runtime stacks and their
Wine environments differ; this is not a pure CPU-emulator comparison.

## Comparison

| Observed result | Working Box64 | Failing FEX |
| --- | --- | --- |
| Device flags / size | 64 / 1280 x 720 | 64 / 1280 x 720 |
| Draws before capture limit | 65,536 UP | 65,536 UP |
| Final completed presents | 213 | 216 |
| Sampled vertices | 224 | 224 |
| Flagged sample values / failed draws | 0 / 0 | 0 / 0 |
| Hooks restored | Yes | Yes |
| x87 control / MXCSR | 0x003f / 0x1f80 | 0x003f / 0x1f80 |
| Retained distinct states | 6 | 5 |
| D3D8 line-pattern warning | Present | Present |
| Game SHM uploads / failures | 1,167 / 0 | 569 / 0 |

All five FEX state records also occur unchanged in Box64. Box64 additionally
captures a 128 x 128 DXT1 texture with additive blending. Sampling and scene
timing differ, so that extra retained state does not establish a missing draw.
Solid fill, culling/depth state, color-write mask, FVF, floating-point controls,
texture dimensions/formats and alpha combinations retained by FEX all have
working counterparts.

Both captures fill the observer's 32 vertex-buffer tracking slots, yet report
no buffered draws before detaching. Both hit the draw cap and detach normally.
The warning and tracking-limit receipt therefore also occur with correct
rendering. Neither distinguishes this failure.

Position sampling still checks only the first eight draws every 32 presents.
Neither export contains complete coordinates, UV values, texture contents or
the entire later character-selection draw stream. Matching retained metadata
cannot clear game-generated data, CPU execution or all Wine/Vulkan behavior.

## Transport and lifecycle

Box64's actual game uploader `wsi-upload-10143.bin` records 1,167 SHM uploads,
zero fallbacks/attachment failures, and 4,146,677,760 copied bytes. Average copy
is 0.751 ms and average fence wait 0.00560 ms, close to FEX's 0.744/0.00377 ms.
Later full Surface windows record about 25.5–29.3 posts/s; the final nearly
idle window follows game exit. Screenshots and delivery data support Box64
remaining the usable rendering baseline. They do not establish world FPS or
a new optimization relative to older Box64 builds.

The session lasts 95.805 seconds; the client exits with code zero. Its bounded
startup observer's last sample is at 13.461 seconds. `elapsed_ms=64027` is the
later receipt at process exit, not evidence that module scans ran for 64 seconds.
Audio has four underruns total, with two counted during the early active period;
the count stays at four through normal stream stop/release.

Box64 diagnostics retain 145 exception messages, 62 Wine diagnostics, five
selected module loads, three DXVK initialization messages and the same one
D3D8 warning. A higher exception-message count with correct rendering is not
evidence that those messages caused FEX corruption.

## Automated investigation

The next check is CI-only; no device APK or production runtime is changed.
`tests/windows/texture-submission.c` builds a standalone synthetic D3D8
fixture that exercises a gap identified by both actual-game captures:

- 1024 x 1024 managed A8R8G8B8, DXT1 and DXT3 textures, updated each frame.
- Hardware vertex processing, pretransformed position/diffuse/UV data.
- MODULATE2X color/alpha with the observed x87 precision/rounding/masks.
- Opaque, alpha-test/ordinary alpha blend, and additive blend panels.
- 378 small UP tiles per frame, four frames, 1,512 draws and 144 independently
  expected color samples, including transparent and fractional-alpha texels.

The CI harness runs this after the existing observed fixture in the same
supervised process environment, under Box64 and FEX full80/strict64 cycles.
It checks exit status and the exact completion receipt and preserves a
version-specific JSON report. The fixture is distributed only with CI assets;
it is not added to the APK or production preflight.

Local MinGW compilation and Python syntax checks pass. The PR's real ARM64
FEX job `107270094967` passes with 130 PASS receipts. Both new fixture runs
pass: DXVK 2.5.3/full80 and DXVK 2.7.1/strict64, each with 1,512 draws,
144 expected pixel samples and x87 control `0x003f`. The full FEX migration,
launch, controller, audio and PRoot checks also pass. CI uses lavapipe;
this does not reproduce FFXI or qualify FEX on Adreno.

The downloaded `fex-runtime-evidence` artifact `10763253880` is verified at
SHA-256 `cf4f973bd8386ab8ecede4ea48ef8016198a72f82214ff9f43ce66578cf54140`.
Its two texture JSON reports agree with their exact completion receipts.
Box64 job `107270095091` passes with 147 PASS receipts, including all four
new fixture runs. The modern-Mesa cycles select DXVK 2.5.3 and 2.7.1; both
older-Mesa cycles select 2.5.3 (including the requested-2.7.1 fallback).
Each verifies 1,512 draws, 144 expected pixels and x87 control `0x003f`.
The `runtime-evidence` artifact `10764323108` is verified at SHA-256
`a4b11799536d195d9fbd6470222abe2baf79495a66b3f38608a45ef734057646`.
All four JSON reports match their completion receipts. The synthetic
operations pass under both stacks; no game-specific CPU or texture fix has
been identified.

Implementation is `eda376514a403c6bf0957fb02f337d7aab078b3d`.
The independent [push run 35886842062](https://github.com/Russianranger/lsb-android/actions/runs/35886842062)
passes verify, Windows launcher, server and presentation jobs, but both ARM64
jobs stop before the new fixture: Box64 job `107270112859` times out in the
existing observed pixel fixture; FEX job `107270112869` times out in the
ordinary unobserved pixel check when entering the modern-DXVK cycle. These
are retained failures of unknown cause, not texture-test passes or failures.
No retry or timeout relaxation was used. Matching
[PR run 35886847711](https://github.com/Russianranger/lsb-android/actions/runs/35886847711)
passes all six applicable jobs; runtime publication correctly skips.

The arithmetic, DXVK-version, FEX game and matching Box64 comparisons are now
complete. Do not ask the user to repeat them. Preserve Box64 as the working
baseline, the prepared `30251204_1` client, original loader and Termux server.
Changes and pushes remain authorized.

## Next source evidence

The exports include the hash of `FINAL FANTASY XI/FFXiMain.dll`, but not that
binary. It is also absent from the available workspace. Obtain that one file
from the same client import for static inspection of CPU-feature dispatch,
math routines and rendering call sites. The expected SHA-256 is
`d528142a9bfb7767b4d4574dde531ea87a673670c81892f1eab37563b82c0235`.
Do not publish the client binary in this repository. Static inspection can
guide an independent reproducer; it cannot establish which path executed
during the captured game session by itself.

Upstream FEX fixes are leads, not an established FFXI fix. For example,
[FEX #5806](https://github.com/FEX-Emu/FEX/pull/5806) repairs non-SVE packed
double-to-integer precision loss. The pinned
[Windows CPU-feature report](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/Source/Windows/Common/CPUFeatures.cpp)
advertises 3DNow through `IsProcessorFeaturePresent`, while
[host-feature selection](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/Source/Common/HostFeatures.cpp)
disables its CPUID exposure on Windows. Neither capture identifies the
relevant instruction or query in FFXI. Do not backport a guess or replace the
qualified Wine/FEX bundle merely
because a newer upstream release exists. First tie the affected operation to
this client's code and make a standalone correctness test.
