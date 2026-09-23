# Thor 0.5.18 result and broader draw coverage

## Received device evidence

`lsb-support(9).zip` (Library ID `libfile_603b5a6d876c81918c0ec9a59478e6a8`)
has SHA-256 `44393b0f180db2d532348922e5af2740944c451402e8650e87f5ae7957de2280`.
It identifies app 0.5.18 and the delivered observer
`b7e3893f00dba83bd4b4d67d644fe69db70742db421365de47cf9505b403b7ab`.
The 17:42:07 and 17:43:24 screenshots still show missing agreement graphics
and the same severe character-selection corruption, both around 13.5 FPS.

The exact image is recognized (`main_math_profile=0/1`). On the first Present
the dispatcher is uninitialized (`65535/0`) and the probe correctly skips it.
At the second opportunity the actual game selects SSE2 for all three routines
(`main_math_dispatch=2/546`, or `0x222`). CPU bits are 12. The worker reports
x87 control 63 and MXCSR 8064, then **0 failures out of 192 results and 0 bad
output-pointer returns**. The final table remains SSE2. This is a completed
phone result, not a preflight inference. It covers the documented synthetic
inputs and worker context; it does not validate the game's actual inputs,
other arithmetic, texture decoding, or GPU behavior.

Runtime v2, strict64, parent/child CPU-feature agreement, DXVK 2.7.1 and
untuned Turnip remain confirmed. The capture still stops at 65,536 UP draws,
frame 216, with 224 first-eight-draw position samples and no flags. Its five
retained states match the earlier failing capture. That tiny sample does not
cover all menu draws, UV/diffuse-alpha values, or the later character scene.
Do not re-run this math check or the earlier runtime/precision/DXVK matrices.

## 0.5.19 diagnostic change

Rendering behavior is unchanged. The opt-in observer now additionally groups
up to 1,024 successful UP draws every 32 frames, sampling up to 64 vertices
distributed across each draw, including the last one. Indexed UP draws decode
16/32-bit submitted indices and check the declared range before reading a
vertex. Buffered draws retain the previous limited capture only.

Groups include FVF, stride, primitive/indexed mode, stage-zero texture
type/size/format, alpha test/blend, color/alpha operations and arguments,
texture-coordinate index and transform flags. Up to 24 groups are retained
in each of four elapsed-time windows: 0–15, 15–30, 30–60 and 60–90 seconds.
Descriptors and cumulative summaries replace prior snapshots for the same
group, bounding the parser at 288 batch records plus four limit receipts.
The original 64-row record budget remains separate.

Summaries count nonfinite positions/UVs, nonpositive/nonfinite RHW, UVs beyond
±16, zero UV pairs, and zero/opaque diffuse alpha. They retain finite min/max
float bits for x/y/z/RHW/u/v, with an explicit validity mask. Integer-only
classification/order comparisons avoid changing the caller's floating-point
state. Zero alpha and large UVs can be legitimate; none of these flags alone
proves a rendering defect. Unsupported FVF/shader handles are reported without
exporting handle values. No texture content, vertex arrays, addresses, game
code, credentials or private paths are exported. No GPU readback is added.

The observer detaches at 90 seconds, 4,096 frames or 1,048,576 draws, whichever
comes first, or device release. Broader collection has diagnostic overhead;
do not use a capture launch as a performance benchmark. Its purpose is to
find which class of actual submissions diverges from expected screen geometry.
It does not replace the renderer or claim to fix the corruption.

## Verification

Local integer-helper checks inject negative/zero RHW, nonfinite/large UV,
transparent/opaque colors, signed zero, and insufficient/unsupported layouts.
They verify finite bounds and sampling across a large draw. All 63 runtime
unit tests pass; observer and synthetic texture fixture cross-compile with
warnings treated as errors.

Existing pixel checks remain. The CI texture fixture now optionally attaches
the observer and uses both 16- and 32-bit indexed UP draws with a nonzero
minimum vertex index and invalid prefix sentinels, alongside ordinary UP.
All 144 expected pixels must still pass. The harness requires all 378 draws
from the sampled frame and all 1,512 submitted vertices in the new summaries,
including UV/alpha checks, valid indices, no coverage loss and hook restoration.
Real ARM64 and package qualification receipts will be appended after completion.

## Next device check

Only after qualification, install 0.5.19 over the existing app. Keep the current
FEX/faster-x87/DXVK 2.7.1/display settings; no runtime download or client reset.
Enable **Capture FFXI startup and graphics**, launch through agreement to
character selection, stay there about 60 seconds, then Stop/export Diagnostics
and disable capture. This supplies different evidence from the completed math
test. One FEX launch is sufficient; no Box64 recapture or standalone checks.
Preserve the prepared `30251204_1` client, original xiloader and Termux server.
