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

Once, on a disposable worker thread, the observer supplies synthetic local
buffers to those three **already selected** functions. An independent integer
oracle checks 192 exactly representable results: positive/negative vector
inputs, aligned/unaligned matrices, separate matrix output, left/right aliasing
and squaring in place. Both signs of zero are accepted. The worker uses the
render thread's captured x87 control word and MXCSR settings, starts with an
empty x87 stack, and records its observed controls. It uses no FXRSTOR on the
game thread. The loaded module remains referenced for the worker's lifetime.
Collection at frame 32/completion is nonblocking; a still-running worker is
reported as pending, not passed. One static job bounds storage and prevents a
timeout/use-after-free race. The probe does not
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
| `main_math_controls` | observed worker x87 control word | observed worker MXCSR |
| `main_math_result` | failed components | checked components (192 complete) |
| `main_math_returns` | incorrect output-pointer returns | 0 |
| `main_math_case` | first failing synthetic case × 16 + component | 0 |
| `main_math_expected`, `main_math_actual` | synthetic result IEEE float bits | 0 |
| `main_math_unavailable`, `main_math_skipped` | read/worker/creation error 1/2/3, or unsupported 1 | 0 or attempt number |
| `main_math_pending` | 1 | 0; worker not complete before capture ended |

For corrected FEX, `main_math_cpu.detail=12` is the expected feature report.
`main_math_dispatch(code=2, detail=546)` means mode SSE2 with all three kernel
hashes recognized as SSE2 (`0x222`). This would establish actual selection;
it is not asserted before receiving the phone result. `main_math_begin` without
`main_math_result` identifies an incomplete probe, not a pass. The worker is
not claimed to reproduce the game thread's incoming x87 stack, flags, TLS or
real inputs; the inspected pure routines use none of those process-specific inputs.

## Verification and next device run

Locally, the exact six private routines pass the same 192 cases per path under
Unicorn, including the stdcall ABI and output pointer return. This validates
the diagnostic's function signatures/reference expectations, not FEX behavior.
The initial host oracle passed result/error-detection and floating-state checks. The
Windows fixture additionally rejects unreadable/unsupported images, checks
last-error preservation and ensures unsupported profiles never run arithmetic.
It runs on native Windows and both ARM64 runtime stacks in existing CI gates.
Qualification and APK delivery receipts are recorded below.

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

The initial FEX job `107381051057` also fails the snapshot-restoration fixture
after its other launch checks. FEX 2510's `SaveX87State` rotates saved slots by
TOP and converts strict64 values to extended format, while `RestoreX87State`
loads those slots directly into physical registers. The diagnostic must not
depend on this pair preserving its caller's state. Synthetic calls now use the
isolated worker described above. The fixture retains its parent-state equality
assertions and checks that the worker receives the captured control settings.
There is no runtime patch and no claim that this FEX defect caused the original
graphics corruption. [Exact FEX source](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/FEXCore/Source/Interface/Core/OpcodeDispatcher/Vector.cpp).

With initialized snapshots, FEX job `107384204999` confirms the live values
move from saved ST0/ST1 to ST2/ST3 after the round trip, matching the TOP-rotation
problem in the source. Box64's corrected oracle passes in job `107384170347`;
that job later times out in the existing observed pixel fixture.

## Worker implementation and package identities

Implementation: `f8c3083e1471cde332230213e0b8b7d57cec1dfd`.
The revised observer compiles locally and all 62 runtime unit tests pass.
PR run `35921519475` passes native Windows (36 process/registry checks plus
the 192-sample worker oracle, four guards, injected-error detection and parent
floating-state preservation). The same worker oracle passes under Box64 job
`107387172562` and FEX job `107387172573`. The Box64 job later times out in
the observed pixel fixture; the FEX job later exits -11 in the unchanged,
unobserved pixel fixture, before loading the new observer. These failures are
retained, not relabeled as passes. Independent push run `35921514817` completes
all seven jobs successfully with the same implementation. FEX job `107387741358`
has 134 PASS records, including two worker-oracle runs; Box64 job `107387741316`
has 155, including four worker-oracle runs. Both complete their normal graphics,
audio, input, launch/recovery and PRoot gates. No timeout or assertion was relaxed,
and no unchanged job was rerun for this final implementation.

| Evidence | Artifact | ZIP SHA-256 |
| --- | --- | --- |
| FEX qualification | `10777777967` | `ff3e838e56d052ed7996fe326d57469859a00813a07dba13fca5a2141e1322ea` |
| Box64 qualification | `10778790869` | `6a6221e8b7c48e2413afef4a6dd455f81546d3942ba77edc0d76ed67e8ea8afa` |
| Qualified push build | `10777611552` | `40bec2c3936711ebb67ced7042b0c3ddefc21591f244d803e5eb4a78b4533f65` |

[Passing workflow](https://github.com/Russianranger/lsb-android/actions/runs/35921514817).
The qualified push APK and delivery PR APK have identical complete ZIP entry
payloads after excluding CI signing metadata, with no serialization differences.

The packaged APK is from PR artifact `10776719660` (28,557,231-byte ZIP,
SHA-256 `cf8dc215eb167b97146247c05be322ff2fc96a53d0349dd605a92158001d3452`).
After original-certificate signing, `LSB-Android-0.5.18.apk` is 18,292,884 bytes,
versionCode 34, SHA-256
`093f668754b2d542b1ad6a0a1129809f10b84de599ab9183eb5871a80776653d`.
Signer: `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Observer DLL: `b7e3893f00dba83bd4b4d67d644fe69db70742db421365de47cf9505b403b7ab`.
The CI APK SHA is `b3c59c2c657fe0b2c96401d4462362f499b93b4f169f5002c288209d30846466`.
ZIP integrity, v2/v3 signatures, alignment, package/version, CI payload identity,
observer artifact identity and Python source identity pass. Compared with
0.5.17, only the manifest, startup observer, diagnostic parser and serialization
of the unchanged Box64 manifest differ. FEX, Box64, DXVK, Turnip, presentation,
audio and input assets are unchanged. No test executable is packaged.
Earlier on-thread 0.5.18 candidates were not delivered; use the identity above.
Delivered file: Library ID `libfile_c52db2ae710481919a70c8569638841e`, version 0,
content ID `file_00000000dc8c81f99bea7cb6d78e7b7d`. Local identity/xattrs are saved.

## Focused device check

Install the delivered 0.5.18 over the app. No runtime download is needed.
Retain FEX/faster x87, DXVK 2.7.1 and existing display settings. Enable
**Capture FFXI startup and graphics**, launch once to the broken screens,
then Stop/export Diagnostics and disable capture. No standalone runtime check,
driver comparison, Box64 recapture, client reimport or server change is needed.
Box64 remains the usable rendering baseline. Do not repeat previous matrices.
