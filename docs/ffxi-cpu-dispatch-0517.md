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

Local MinGW compilation, native-host numeric checks, Python checks and patch application checks pass. No graphics fix is claimed before a subsequent FEX device run renders the previously broken screens correctly.

## Integration qualification and candidate APK

Implementation commit: `451f1290369d01244f0afb5278c1555df7e6780c`.
The preceding integration commit's fixture build needed `-Dmain=wmain` to match the launcher's existing `-municode` flags; the standalone Docker regression still uses its normal `main`. This changes the test entry point only.

Push workflow [35910276196](https://github.com/Russianranger/lsb-android/actions/runs/35910276196), original FEX job `107348983932`, passed its complete suite with 132 PASS records. All six acceptance receipts verify matching CPU features in both parent and child: full80/strict64 in migrated Wine, Vulkan/DXVK and PRoot. Both graphics cycles pass the observed geometry/state/detach and compressed-texture fixtures. Controller, exception, registration, launch and prefix-preservation checks also pass. This is ARM64 CI with software Vulkan, not Thor/Adreno gameplay.

FEX passing evidence artifact `10772943456` is 400,003 bytes, SHA-256 `535b95b8b55a7b529529f76fcd741fee685b4d4a6abcdcd6d4ab8ed313c2766c`.

Independent PR workflow [35910281267](https://github.com/Russianranger/lsb-android/actions/runs/35910281267), Box64 job `107349231288`, passed its full suite with 151 PASS records. All four Vulkan graphics cycles also pass the new CPU/math fixture, observed pixels and compressed textures. Its evidence artifact `10773479146` is 375,075 bytes, SHA-256 `4db9cc5512790c532b0c18f99ee0b451e34fea998540f071f46256c60aa22e1d`. Both runtimes therefore have complete passing runs at the exact implementation commit. The release workflow's targeted Box64 retry also passed, as recorded below.

Retained CI failures: the push Box64 job `107348983829` and independent PR FEX job `107349231405` timed out in the unchanged **Observed D3D8 pixel fixture** after the new CPU fixture passed. This matches the earlier baseline's documented intermittent fixture timeout; the FEX job above passes the same assertions. No pixel assertion or timeout was weakened. One targeted retry of the failed push Box64 job passed with all 151 PASS records (`107355581748`); the first API request had been refused because the parent workflow was still running and did not start a retry. Workflow attempt 2 completed successfully, including the runtime-release job `107363845189`. Passing retry evidence artifact `10775065976` is 373,839 bytes, SHA-256 `769e8ad8dab13c6082466ce097708dd254521571f0084dc462b18a9c6b8656dc`. The original failed Box64 artifact `10773515593` remains available; no failure evidence was removed.

The delivered signed 0.5.17 candidate (versionCode 33) is 18,292,884 bytes, SHA-256 `f85f9f3775d813c7c31b98728e39b453dfc72f9e38ae6b9fa66e2d51af6b7148`, with the existing signer `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`. The source APK is from artifact `10773365539` at the implementation commit; artifact ZIP SHA-256 `0f3c6f6b583e9d256d3607b1acb59ed1851207993d0bb05949e393f2b6455114`, CI APK SHA-256 `a3d20a4712bb90221455082a346cf74467796d0a85dd2d04c0e267f474967e68`. ZIP, original v2/v3 signer, alignment, package/version and payload identity after signing pass. Changed entries from 0.5.16 are the Android manifest, FEX manifest, FEX Python preflight and FEX Windows check; the Box64 manifest serialization changes but its parsed values are identical.

The earlier arithmetic, DXVK and paired game captures remain complete.
Do not request the DLLs or those comparisons again. Preserve client
`30251204_1`, original xiloader, Box64 fallback and the working Termux server.

## Published release and next device check

[Runtime v2](https://github.com/Russianranger/lsb-android/releases/tag/runtime-fex-v2) was published at 2026-09-23 20:17:48 UTC. Release metadata matches all three tested assets: runtime hash above; `fex-bundle.json` SHA-256 `f5e6c1838478d29ebe99610e71d417b58e6a0ad5a6b4804362d3193acdc3644f` (753 bytes); exact source archive SHA-256 `d70e13ef06602ae3ea7cb06afe0c46a4d5f5004e76bdb5abddfd6170e50471a2` (83,389,305 bytes). Runtime v1 is retained.

`LSB-Android-0.5.17.apk` is saved as Library ID `libfile_f1b1eee0d580819196f494b031d7f63d`, file ID `file_00000000545c81fb8ef9351af714612b`, at `/LSB-Android-0.5.17.apk`. The local deliverable is `/workspace/scratch/ee8c6f15b83e/LSB-Android-0.5.17.apk`.

The user should install the update, then use **Runtime → Install FEX runtime** to obtain the newly pinned build. Enable FEX and keep faster x87 enabled with the existing DXVK 2.7.1 / Turnip 26 / Native Surface shared-memory settings. One normal FEX run of the agreement and character-selection screens, two screenshots and one support export will check the correction on Thor. The launch preflight automatically records the new runtime identity and parent/child feature agreement. No repeat of the earlier precision/DXVK matrix or bounded graphics capture is requested.

Actual FEX gameplay rendering and world entry remain unverified until the phone result arrives. If rendering is still wrong, first verify the runtime hash and `fex_launch_cpu_features` in that export; do not assume the feature correction alone fixed every rendering issue. Preserve prepared client `30251204_1`, original xiloader, Box64 fallback and the working Termux server. No reimport, preparation reset, client update or server migration is needed.
