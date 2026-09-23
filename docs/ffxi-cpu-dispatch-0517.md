# Exact-client CPU dispatch investigation

The requested DLLs arrived in `ffxi_lsb(1).zip` (Library ID
`libfile_12c7019768f08191a95645ec9d11ae30`), archive SHA-256
`3637cd9e34e28403a2c0e4f411be49d18841b43a52be79031bc10d9907f050e3`.
Both exactly match the working Box64 and failing FEX exports:

- `FFXiMain.dll`: `d528142a9bfb7767b4d4574dde531ea87a673670c81892f1eab37563b82c0235`.
- `FFXi.dll`: `d58b652ff3bb2de0b4f7dd4bb51eccc80f39d26de4bfb01217d7a7eb5faaa57f`.

The DLLs use POL1 compression for their code sections. Private inspection
decoded exactly the declared code sizes (3,298,350 and 67,992 bytes). No
client binary, unpacked code or disassembly is included in this repository.

## Concrete selection mismatch

The main DLL's linked D3DX dispatcher at preferred VA `0x102e5490` first
installs scalar x87 routines, then queries Windows processor feature 7
(3DNow). A positive API response sends it to the 3DNow initializer and
prevents the later SSE2 choice. That initializer independently checks
CPUID and returns without installing 3DNow if the CPUID bit is clear.
The dispatcher nevertheless records mode 1, retaining the x87 routines.

The pinned FEX 2510 Windows build disables 3DNow in CPUID but unconditionally
sets `CPU_FEATURE_3DNOW` in its Windows feature report. The WoW64 exported
`BTCpuIsProcessorFeaturePresent` uses that report. This is a concrete
contract inconsistency affecting the uploaded client's selection logic.

Local replay of the actual private selection routine in an x86 emulator,
with explicit mocked Windows NT version/registry responses and CPUID
exposing MMX/SSE/SSE2 but no 3DNow, produces:

| Windows 3DNow API | CPUID 3DNow | Recorded mode | Vec2 / Vec3 / matrix multiply |
| --- | --- | --- | --- |
| True | False | 1 | `0x102f210b` / `0x102f21a9` / `0x102f243b` (x87) |
| False | False | 2 | `0x102f6930` / `0x102f8640` / `0x102f88c0` (SSE2) |

These are preferred-image addresses for this exact hash. Replay establishes
the branch behavior under the specified responses; it is not a live Thor
game trace. The dispatcher does **not** execute its 3DNow routines when the
CPUID check rejects them. Therefore merely finding PFACC instructions in
the DLL does not justify applying the unrelated upstream PFACC correction.

## Candidate correction and regression

`native/fex/cpu-features.patch` derives the Windows 3DNow report from the
guest's extended CPUID bit. It preserves disabled 3DNow and permits legacy
dispatchers to reach SSE2. The change is in FEX's feature reporting, with
the same Wine 10 and FEX 2510 upstream identities and existing Wine backport.

The build retains the original translator only for a before/after check.
The independent `tests/windows/cpu-dispatch.c` fixture compares the real
Windows API and CPUID, models the legacy choice without client code, and
checks 72 exactly expected affine-transform results through SSE and a full
eight-entry x87 stack using ST(4) pop operations. Both full80 and strict64
are checked. The original must reproduce the mismatch; the corrected
translator must agree and select SSE2. Both numeric paths must pass in all
four runs. An unexpected result fails the build rather than being ignored.

The real ARM64 build [35909118469](https://github.com/Russianranger/lsb-android/actions/runs/35909118469) passed at source commit `5cccf6c8ca6272d2ee765f5e0fe3aba2f80ea5af`. Its four recorded runs reproduce the expected result in both precision modes:

| Translator | Windows / CPUID 3DNow | Selection | Exit | Math |
| --- | --- | --- | --- | --- |
| Original, full80 and strict64 | 1 / 0 | x87 fallback | 10 (expected mismatch) | 72/72 each |
| Corrected, full80 and strict64 | 0 / 0 | SSE2 | 0 | 72/72 each |

Original translator SHA-256: `c8df803f1aaabd9b13d35c5930aeae48460295394bd384ff9743691cb1867921`.
Corrected translator SHA-256: `70c6c7f6fb0948b60a691a0d29075ac7ef571453d9f3eda1945d4858f7f73373`.
The downloadable artifact (`10771564577`, 401,488,337 bytes) SHA-256 is `56b070cd6b9ad05ae1fa0f5bf47f30fea87981304b0e828c0bf6fc78b03a5da0`. Its `cpu-evidence/` holds all four raw logs and `cpu-dispatch.json`.

APK 0.5.17 pins the new immutable runtime-v2 archive: `3534f21b23272940e94901d1c9e1f9c57b9e3b1c645bc3cdbd101f0fd30fa8fb`, 318,095,586 bytes; inventory `f397fdd90b96cd38d11ba3632680af94f750b32ed7be7db42e142b4d66dbebeb`. Runtime-v1 stays intact. Both runtime installation checks and the automatic game-launch preflight verify matching CPU features in the Windows parent and child, recording `fex_cpu_features` / `fex_launch_cpu_features` in the status evidence.

Local MinGW compilation, native-host numeric checks, Python checks and patch application checks pass. Full compatibility qualification and signed APK delivery are pending. No graphics fix is claimed before a subsequent FEX device run renders the previously broken screens correctly.

The earlier arithmetic, DXVK and paired game captures remain complete.
Do not request the DLLs or those comparisons again. Preserve client
`30251204_1`, original xiloader, Box64 fallback and the working Termux server.
