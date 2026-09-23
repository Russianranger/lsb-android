# 0.5.17 Thor result and 0.5.18 diagnostic

## Device result: the CPU correction did not fix rendering

The latest Thor export is `lsb-support (10)(1).zip`, SHA-256
`fac8ee504d2735b26fdd6546de58976f28ce3912ab49206a804ed31c50f54221`.
It identifies app 0.5.17 and runtime v2
`3534f21b23272940e94901d1c9e1f9c57b9e3b1c645bc3cdbd101f0fd30fa8fb`.
The installed and launch parent/child CPU checks agree: 3DNow false, SSE2 true.
The corrected CPU-reporting patch reached the device. Strict64 arithmetic
passes both checks. DXVK 2.7.1, Turnip 26/Adreno 740, 1280x720 and native SHM
remain selected. The screenshots still show the missing agreement background,
logo/buttons and severe character-selection corruption, at about 13 FPS.

The capture was still enabled. It again reaches 65,536 successful UP draws,
224 sampled vertices without flags, and restores its hooks at frame 216.
Its five recorded states match the earlier failing capture. Native copies
remain roughly 0.5 ms; the final delivery disconnect follows Stop. Neither
the finite-position sample nor the corrected preflight proves correct game
arithmetic. Do not interpret the CPU correction as a graphics fix.

## What the next diagnostic adds

Private inspection finds 57 direct calls to the linked math dispatcher, all
passing its enable flag. Initialization is lazy through math entry points;
a check in another process does not establish the game's selected function
table. The next capture observes that table after the first successful
Present, with a second opportunity at frame 32 if the routines were unknown.
At capture completion it records the table again without invoking routines.

Only this exact FFXiMain identity is supported:
`d528142a9bfb7767b4d4574dde531ea87a673670c81892f1eab37563b82c0235`.
The observer checks the file size/SHA-256 and PE identity, then validates each
selected routine's RVA, executable image mapping and complete routine SHA-256.
Unknown images, unsupported pointers and modified routines are never called.
The only supported bodies are the privately inspected pure Vec2 transform,
Vec3 transform and matrix multiply routines (x87 or SSE2 selection).
No client code, bytes, assets or disassembly are included in the repository.

Once, on the existing rendering thread, the observer supplies synthetic local
buffers to those three **already selected** functions. An independent integer
oracle checks 192 exactly representable results: positive/negative vector
inputs, aligned/unaligned matrices, separate matrix output, left/right aliasing
and squaring in place. Both signs of zero are accepted. The probe saves/restores
the floating-point state and isolates its temporary x87 stack. It does not
initialize the dispatcher or change pointers, registry, files, rendering inputs,
runtime settings or game data. Normal capture limits and hook restoration remain.

The first-frame work is bounded, but this remains a diagnostic launch, not an
FPS benchmark. Tests cover a small set of exact inputs; passing them does not
validate all FFXI arithmetic, texture decoding, submitted data or GPU behavior.
Existing game draws have not yet been shown to execute any particular kernel.

## Receipts in `startup_diagnostics.records`

All rows retain the existing fixed-label/numeric schema and game process/thread
IDs. No caller addresses or game memory values are exported. Failure bits are
results of synthetic inputs only.

| Event | `code` | `detail` |
| --- | --- | --- |
| `main_math_profile` | 0 supported; 1 unsupported | profile 1 |
| `main_math_dispatch`, `main_math_final` | mode 0 scalar, 1 3DNow choice, 2 SSE2, 3 SSE, 65535 uninitialized, 65534 unknown | three nibbles: Vec2, Vec3, matrix; each 0 unknown, 1 x87, 2 SSE2 |
| `main_math_cpu` | 0 | bits 0/1 = CPUID/API 3DNow; bits 2/3 = CPUID/API SSE2 |
| `main_math_begin` | 0 | 192 planned samples |
| `main_math_result` | failed components | checked components (192 complete) |
| `main_math_returns` | incorrect output-pointer returns | 0 |
| `main_math_case` | first failing synthetic case × 16 + component | 0 |
| `main_math_expected`, `main_math_actual` | synthetic result IEEE float bits | 0 |
| `main_math_unavailable`, `main_math_skipped` | 1 | 0 or attempt number |

For corrected FEX, `main_math_cpu.detail=12` is the expected feature report.
`main_math_dispatch(code=2, detail=546)` means mode SSE2 with all three kernel
hashes recognized as SSE2 (`0x222`). This would establish actual selection;
it is not asserted before receiving the phone result. `main_math_begin` without
`main_math_result` identifies an interrupted probe, not a pass.

## Verification and next device run

Locally, the exact six private routines pass the same 192 cases per path under
Unicorn, including the stdcall ABI and output pointer return. This validates
the diagnostic's function signatures/reference expectations, not FEX behavior.
The host oracle passes result/error-detection and floating-state checks. The
Windows fixture additionally rejects unreadable/unsupported images, checks
last-error preservation and ensures unsupported profiles never run arithmetic.
It runs on native Windows and both ARM64 runtime stacks in existing CI gates.
Qualification and APK delivery receipts will be appended when complete.

Initial implementation `6c480ac663062be39c8674566fca80caa96d295c` passes native
Windows and Android packaging. Both Box64 jobs stop in the new floating-state
fixture (PR job `107381167920`, push job `107381050870`), before graphics checks.
The arithmetic itself passes. Inspection of Box64 `2f130fab1`'s
`fpu_fxsave32`/`fpu_fxrstor32` finds that they copy the 8-byte internal register
representation into each x87 slot; bytes 8–9 are untouched. The fixture compared
those bytes in uninitialized buffers. Both snapshots now start zeroed, retaining
the same control, status, tag, MXCSR and register comparison assertions; failures
also print the differing synthetic state bytes. This is a fixture correction,
with no production/runtime change or retry of the unchanged failing test.
Source: [Box64 x87 helper](https://github.com/ptitSeb/box64/blob/2f130fab1/src/emu/x87emu_private.c).

After qualification, install 0.5.18 over the app. No runtime download is needed.
Retain FEX/faster x87, DXVK 2.7.1 and existing display settings. Enable
**Capture FFXI startup and graphics**, launch once to the broken screens,
then Stop/export Diagnostics and disable capture. No standalone runtime check,
driver comparison, Box64 recapture, client reimport or server change is needed.
Box64 remains the usable rendering baseline. Do not repeat previous matrices.
