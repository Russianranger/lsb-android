# Thor 0.5.15: all three requested exports received

The user completed the two Windows checks and a short launch to the broken
menu. Both checks pass on the actual Adreno 740. FEX game rendering remains
unaccepted. Do not repeat the 0.5.15 on/off pixel procedure or present its
success as an FFXI fix.

## Evidence and session identity

| Export | Current action | FEX arithmetic | Actual DXVK | Result |
| --- | --- | --- | --- | --- |
| `lsb-support (3)(5).zip` | Windows checks | strict64 | 2.7.1 | 128/128 pixels, both vertex-processing modes; interactive check completed |
| `lsb-support (4)(2).zip` | Windows checks | full80 | 2.7.1 | 128/128 pixels, both vertex-processing modes; interactive check completed |
| `lsb-support (5)(1).zip` | FFXI launch | strict64 | 2.7.1 | User reports broken menu; stopped after about 54 seconds |

Archive SHA-256, in that order:

```
01ba3dfd2969946ba27cab01004f9aec71ec7342660068f56ebcac63c677a657
b13c7432a9f5ebba46ee9978adaeeface4a996d339ba7407e765903f074c138f
0e7c233ab20646e73985a0f270b6a6aa39c37179a9647ba419f705f7eaf32419
```

Current sessions, in the same order:

```
c4c503d0-6ae4-4cf6-a8e6-34c7e8f2290c
03c80fd8-5e6d-493e-9b56-196376d588f1
c26ba381-16fd-4eb4-8664-b95090977b0d
```

The game archive's previous runtime session is the full80 probe. Its previous
client-launch session is the older 0.5.14 game
`9c612832-2206-4d98-8fb1-a7afa1e7c414`. These independently rotated files do not
establish another full80 game test.

`device.txt` still says `app=0.5.14`: MainActivity's export string and home-page
label were left hardcoded at that version in 0.5.15. The 128-pixel receipts and
new fixed warning reason establish that the new diagnostics actually ran.
This is a reporting defect to correct in the next APK, not grounds to ask the
user to reinstall or repeat the completed tests. Prefer package version metadata
over another manually maintained version literal.

## What passed on the device

Both probes contain distinct `mode=swvp` and `mode=hwvp` PASS receipts, each
with four frames and 64 samples. They cover the managed texture, dynamic
indexed buffer, transforms, render target, alpha and state-block fixture.
The later interactive checks complete with 787 and 413 frames respectively,
two key events and seven pointer events each, submitted audio and HRESULT 0.
Different interactive durations mean those frame totals are not an FPS A/B.

The fixed 100,000-iteration arithmetic sample takes 843 microseconds in
strict64 and 11,108 microseconds in full80. Both verify the Windows child's
selected environment, 10 MHz QPC and approximately 20 ms sleep. This confirms
a large difference in this arithmetic workload, not a 13-fold gameplay gain.

All three exports use the same verified runtime, driver and DXVK binaries:

- FEX runtime: `99c270eefe20e32d942ba6a77ad1ea0d69097ed31b9830879fa749630ce15c89`.
- Turnip 26: `20ff681a7f37f228bcbad1910ed81174b299785620f528dc56d9be05730894f1`.
- D3D8: `901d4f234cad5b9c6c9f0323aba123bfa8c096dcf6f7bfbe24d3ec3c3798d348`.
- D3D9: `00ecd422b3b12e9d3b309785f3f19431405389d80eb4a37018ebc63e2d87d900`.

Turnip reports Adreno 740, Mesa 26.0.0, `software=false`. Native Surface and
SHM are active. Sysmem and two-compiler tuning are both requested off and
confirmed inactive. This is the controlled 2.7.1 arithmetic comparison that
the earlier CI fixtures, using different DXVK versions, could not provide.

## Broken-menu launch

The final launch child verifies strict64 again (818 microseconds), with its
own checked environment hash. Prepared generation
`768f3a6d-8dfb-462f-8b9d-46cdd7101505`, original xiloader hash
`78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`
and windowed 1280x720 profile match the previous setup. The prepared client's
patch/version-table files are readable.

The actual game uploader (`wsi-upload-30924.bin`) records 186 calls and 186
SHM uploads, zero socket fallbacks and zero attachment failures. Average copy
time is 0.834 ms; average fence wait is 0.00449 ms. Late Native Surface windows
deliver 13.18, 13.38 and 13.02 posts/s. These transport measurements agree with
the earlier approximate 13 FPS report; they are not direct GPU/game FPS.
The final delivery error is at shutdown. Audio's three early underruns remain
stable during play, followed by one at shutdown; audible-sample data arrives.

The game's diagnostic counts are 76 exception messages, 44 Wine diagnostics,
five selected module loads, three DXVK initialization messages and one DXVK
warning, with zero dropped records. All retained exception codes are
`0x80000026`; their presence does not establish a crash or its cause.

The newly classified warning is **`line_pattern_unsupported`**. In the exact
[DXVK 2.7.1 implementation](https://github.com/doitsujin/dxvk/blob/v2.7.1/src/d3d8/d3d8_device.cpp),
setting `D3DRS_LINEPATTERN` logs once, stores the supplied value and returns
`D3D_OK`. The warning is unconditional on the requested pattern's value; it
does not prove that visible stippled lines were requested. There is no retained
base-vertex, state-block or FPU-setup warning. Implementing line stippling is
not justified as a fix for the missing menu and broad geometry corruption.

## Interpretation and next comparison

The tested D3D8 paths render correctly on this Thor under both arithmetic
modes. The actual game still fails. This narrows the investigation to behavior
the fixture does not exercise: game-generated data/math, other rendering
states or formats, and runtime/driver interactions. Passing samples do not
clear every CPU instruction, Wine bridge or DXVK/Turnip operation.

Upstream research found a concrete [PFACC arithmetic fix](https://github.com/FEX-Emu/FEX/pull/5863)
and [reported 3DNow-related rendering failures](https://github.com/FEX-Emu/FEX/pull/5935).
However, our pinned FEX source already contains the [Windows CPUID workaround](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/Source/Common/HostFeatures.cpp)
that disables 3DNow by default. Its Windows CPUFeatures class still advertises
the separate OS feature bit unconditionally. That inconsistency is a possible
future diagnostic target, but these exports show neither the game's feature
query nor execution of PFACC. Do not backport a speculative fix or claim this
as the FFXI root cause.

The next device comparison requires **no new APK**:

1. Stop the client. Leave FEX faster x87 on and keep the existing settings.
2. In Client, turn off **DXVK 2.7.1 · experimental**. This selects bundled 2.5.3.
3. Launch once to the same broken menu, take a screenshot, Stop and export
   Diagnostics. If launch fails or hangs, Stop/export there. No standalone
   Windows check or world-entry route is needed.
4. Restore the 2.7.1 checkbox after the comparison. Box64 remains available as
   the previously working rendering path.

This isolates the DXVK version on the actual failing game while preserving
FEX, arithmetic, Turnip and the prepared client. A clean menu would implicate
the 2.7.1 interaction; unchanged corruption would make a 2.7.1-specific issue
less likely, without excluding shared DXVK code. This exact real-game FEX
comparison is not present in the three exports or prior recorded tests.

Keep the working Termux server, client `30251204_1`, prepared files and original
xiloader. No client update, re-import, re-preparation or server migration is
part of this investigation. Repository changes and pushes remain explicitly
authorized. This checkpoint changes documentation only and supplies no new APK
or claimed rendering fix.
