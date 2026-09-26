# Thor 0.5.16 actual-game D3D8 capture received

## Evidence and verified setup

The user supplied `lsb-support (8)(1).zip` and screenshots
`Screenshot_20260923-104315.png` and `Screenshot_20260923-104336.png`.
ZIP SHA-256:
`093cd2751ac13d35dc759fb10e1a238c30aa9a233dfab512669a8ab6edbdf3d8`.
Persistent file ID: `libfile_976aca2a90d8819196c6272c2c0c5038`.
Read the supplied local attachment when available. Current-session evidence
is separate from the historical `.previous` entries in the export.

The export correctly reports app **0.5.16**. Current session
`b67f5ea4-380c-40f8-9b2a-245bfafa2f2a` runs for 79.305 seconds and ends
`stopped`. FEX strict64, DXVK 2.7.1, Turnip 26 / Adreno 740, Native Surface
and SHM are selected and verified; sysmem and two-compiler tuning are off.
The final Windows child verifies strict64 again: 831 microseconds for 100,000
iterations, 10 MHz QPC, 20.153 ms Sleep, and a verified environment hash.
Preflight arithmetic takes 818 microseconds.

The observer DLL SHA-256 is
`0d70cd3124cfce84ed1928004b752e463c18951f4f727667c6ede07e021a3b05`,
matching the delivered 0.5.16 APK. Runtime, driver, DXVK DLLs, prepared generation
`768f3a6d-8dfb-462f-8b9d-46cdd7101505` and original xiloader identities
match the preceding verified configuration. No re-import or setup repair is
indicated by this export.

The 10:43:15 screenshot has missing menu/background elements with intact
agreement text and 12.6 FPS. At 10:43:36 character selection has dense white/red
streaks and stretched imagery, with intact HUD text and 10.7 FPS.
Diagnostic-session FPS is not a controlled performance comparison.

## Actual game submissions

The first D3D8 device uses hardware vertex processing (`behavior_flags=64`)
at 1280 x 720. There are 16 retained rows: one device, one coverage, five
distinct states, eight frame rows and one completion. No records were dropped.

| Capture result | Value |
| --- | ---: |
| Draws observed | 65,536 |
| UP draws | 65,536 |
| Buffered draws | 0 |
| Failed draws / first HRESULT | 0 / 0 |
| Sampled vertices | 224 |
| Non-finite position components | 0 |
| Extreme screen-coordinate vertices | 0 |
| Invalid RHW vertices | 0 |
| Unavailable samples / other-thread draws | 0 / 0 |
| Final completed presents | 216 |
| Completion reason | 3: draw limit |
| Hook restoration | 1: successful |

Draws increase by 12,096 every 32 presents between frames 64 and 192:
378 draws per present in those intervals. These are all UP submissions;
this does not describe the unobserved remainder of play.

Coverage `reason=2` means the observer's 32 tracked vertex-buffer slots filled.
It is not a D3D creation failure. No buffered draws occurred before capture
ended, and all selected UP position samples were available.

All five retained states use solid fill, no culling, depth disabled and
color-write mask 15. FVF starts at `0x44` (pretransformed position and diffuse
color), then uses `0x144` (also one texture-coordinate set). Checked projection
values are finite. x87 control is `0x003f` and MXCSR `0x1f80`.
Those values do not establish a fault: DXVK's
[SetupFPU implementation](https://github.com/doitsujin/dxvk/blob/v2.7.1/src/d3d9/d3d9_device.cpp)
deliberately selects masked exceptions, 24-bit x87 precision and rounding to
nearest when FPU preservation is not requested.

Texture samples include unbound, 1280 x 720 A8R8G8B8, 1024 x 1024 DXT3,
and 128 x 128 / 32 x 32 DXT1. Textured states use MODULATE2X, with ordinary
alpha and additive blend states represented. These observations do not prove
incorrect state. The fixture does not cover every compressed-texture/state
combination used here.

## Coverage limits

This is **not a clean bill of health for submitted geometry**. The observer
checks only the first eight eligible draws every 32 frames and at most eight
vertices per draw. It does not retain actual coordinates or UVs. Finite
coordinates can still be wrong. Texture bytes, decoded pixels, complete index
references, later draws and later devices are outside this report.

The 65,536-draw cap is reached at frame 216, before the frame/time caps.
Hooks restore successfully; the game then continues without draw observation.
The report has no capture wall-clock timestamps linking frame 216 precisely
to either screenshot. Do not assert that the entire corrupted character
selection was sampled. Extending only the duration of the same first-eight
draw sampling would retain biased coverage. If later evidence justifies an
observer revision, distribute its sample budget across draw positions and
include elapsed-time receipts.

The separate startup process/module observer completes at 8.018 seconds with
18 samples. It is not scanning processes periodically during the rest of play.

## Transport, audio and diagnostic audit

Actual game uploader `wsi-upload-4955.bin` records 569 calls, all 569 SHM,
zero fallback and zero attachment failures, copying 2,021,816,320 bytes.
Mean copy is 0.744 ms; mean fence wait is 0.00377 ms. Native Surface records
577 posts for its entire connection, which includes earlier windows and is
a different scope. Late five-second windows show about 12.56 and 11.77 posts/s,
then 12.05 in the terminal partial window. The final delivery error occurs at
Stop/shutdown; it is not evidence of a prior graphics upload failure.

Four early audio underruns remain stable during play; one more occurs at
stream release. Nonzero audio samples arrive. Fixed diagnostic counts:
104 Wine exception messages, 59 Wine diagnostics, five selected module loads,
three DXVK initialization messages and one D3D8 warning, with zero dropped
records. The warning remains `line_pattern_unsupported`; the previous source
audit explains why it does not establish the cause of the visible streaks.

## Source review and next action

The pinned DXVK D3D8 implementation forwards UP draws to D3D9. Optional D3D8
batching defaults off and is not an established cause for these UP receipts.
The actual game has already failed under both tested DXVK versions.

Upstream PFACC and Windows OS-feature/CPUID research remains unproven for this
game. The pinned Windows
[host-feature policy](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/Source/Common/HostFeatures.cpp)
disables 3DNow CPUID advertisement, while the
[Windows CPU-feature class](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/Source/Windows/Common/CPUFeatures.cpp)
still sets a separate OS feature bit. The report contains neither the game's
feature query nor proof of executing that path. Similar upstream symptoms
are insufficient grounds for a speculative CPU patch.

**Next: one matching Box64 game capture on the existing 0.5.16 APK.**
The earlier working Box64 baseline predates this actual-game observer.

1. Stop. In Client, uncheck **FEX / native ARM64 Wine · experimental**.
   This selects the existing Box64 environment. Faster x87 can remain saved;
   it applies only to FEX.
2. Keep DXVK 2.7.1, Turnip 26, 60 Hz, Native Surface, SHM, windowed 1280 x 720,
   and sysmem/two-compiler tuning off. Enable **Capture FFXI startup and graphics**.
3. Launch to the same agreement/character-selection screens. Screenshot,
   Stop and export Diagnostics. If launch fails or stalls, Stop/export instead
   of repeating it.
4. Turn capture off afterward. Box64 may remain selected as the previously
   working rendering path.

This compares two runtime stacks, including separate Wine environments.
It does not isolate a single CPU instruction. Verify Box64 still renders
correctly, then compare device flags, FVF, texture/state sequence, draw counts
and sample flags. If Box64 also corrupts or fails, investigate that changed
baseline before attributing the difference specifically to FEX.

No standalone check, new APK, runtime download, fresh prefix, client preparation,
loader replacement or server migration is needed. Preserve client `30251204_1`
and the working Termux server. Changes/pushes remain authorized. This checkpoint
changes documentation only; the rendering fault remains unresolved.
