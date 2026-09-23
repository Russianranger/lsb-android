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

Local MinGW compilation, native-host numeric checks, Python syntax and
patch application checks pass. Real ARM64 build/regression qualification
is pending. Runtime v2 and APK integration must wait for these results and
the regular compatibility gates. No graphics fix is claimed before a
subsequent FEX device run renders the previously broken screens correctly.

The earlier arithmetic, DXVK and paired game captures remain complete.
Do not request the DLLs or those comparisons again. Preserve client
`30251204_1`, original xiloader, Box64 fallback and the working Termux server.
