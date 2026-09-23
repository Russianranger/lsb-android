# Thor evidence and FEX runtime candidate (0.5.13)

The latest `lsb-support(8).zip` (no space before the parenthesis), SHA-256
`b0f766f7b43a0dcfc9443a3aaf6e00817efb47df15cf0a4f9da0cd7d077c8569`, contains two
0.5.12 sessions. The user reports no meaningful improvement from the two
graphics experiments. Neither is an accepted FPS fix.

| Session | Turnip sysmem | DXVK workers | Surface posts/s, 70–140 s | Gaps >100 ms |
| --- | --- | --- | --- | --- |
| Previous `aa4808e1…` | Active | Automatic (8) | 27.46 | 7 |
| Current `6fff3ee1…` | Active | 2, confirmed | 25.98 | 7 |

Surface posts measure delivered changed images, not an independent game FPS
benchmark. Both sessions used hardware Turnip 26 / Adreno 740 and DXVK 2.7.1,
with no graphics-version or tuning fallback. Actual game processes performed
5,498 and 5,411 shared-memory uploads respectively: zero socket fallback or
attach failure, average ring wait about 0.005 ms, maximum below 0.44 ms. Average
CPU copy was 0.79–0.81 ms. Startup observation ended after 13.2 / 12.2 seconds
and did not resume during gameplay. Both clients exited with code zero.

The audit also found startup `libXcomposite.so.1` resolution warnings and Wine
RPCSS startup failures. Private startup metadata retains missing-module,
COM/RPC and exception categories, including RPC status 1722; it intentionally
does not retain arbitrary DLL paths or raw login output. These warnings must
not be described as absent. They do not establish the cause of the later FPS
drops. Audio underrun totals reached 3 / 5, then stayed constant through the
last minute. Native capture retains live pointer-query cost, and Vulkan still
uses CPU readback. Neither has been proved the dominant remaining bottleneck.

## Implementation

The candidate uses upstream Wine 10.0, compiled for `aarch64,i386`, and the
FEX-2510 `libwow64fex.dll` Windows translation module. Native ARM64 Wine handles
the Unix side; FEX translates the 32-bit Windows client/DXVK code. It does not
run the old x86-64 Wine executable through FEXLoader and requires no x86 Linux
rootfs or binfmt registration. It does not claim to reproduce GameHub's
Proton 10 ARM64X or async-DXVK fork.

- Wine commit: `b073859675060c9211fcbccfd90e4e87520dc2c2`.
- FEX commit: `320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab`.
- LLVM-MinGW 20250910 ARM64 toolchain: SHA-256
  `175f344e3db2e11b3d53f4f828ae7b5f0073a4d239b9600fa023ef4b90eb5a7e`.

Built on Bookworm to match the existing glibc runtime. Wine's bounded eight
second Unix process-exit grace is retained. Wine upstream commit
`d53a9ba0cd5ee46852b00e4a106e2eb679b5aa3d` is backported to provide
`RtlWow64SuspendThread`, an import required by FEX but absent in stock Wine
10.0. It uses upstream's `NtSuspendThread` implementation. The build checks
every FEX import against the actual packaged Wine exports. No weaker FEX memory-ordering
settings, recurring observer scans, CPU affinity or speculative driver flags
are introduced.

Only FEX disables the exact `winedbg.exe` module name. Wine removes `.dll`,
but not `.exe`, when matching `WINEDLLOVERRIDES`; the existing `winedbg=`
setting does not disable that executable. The candidate must propagate a
crashed child's Windows exit code without waiting in the automatic debugger.
The credential-free exception fixture exercises both normal error mode and
explicitly suppressed crash dialogs, alongside the full real-window crash test.

The published runtime is a separately downloaded, size/hash-verified overlay (about 303 MiB; keep 3 GiB free for installation and the copied prefix). The APK
pins its component manifest. First use copies only the stopped prepared
Windows prefix into a separate per-client, per-runtime directory; client files
are mounted from the same accepted preparation. Regular files are copied,
never hardlinked to the original, and guest symlinks are preserved without
traversal. Before the first native boot, only Wine-owned AMD64 system builtins
are replaced by their verified ARM64 counterparts in the copied prefix; I386
files and native overrides are preserved. This lets native wineboot start and
complete conversion. The original prefix remains the Box64 selection. There is no silent
engine fallback. Initialization/repair of the accepted client continues using
the baseline runtime.

Before login, the candidate verifies native ARM64 ELF components and the ARM64
FEX DLL, then runs an app-owned PE32 check. It requires `IsWow64Process2` to
report I386 / ARM64, Wine's loaded-FEX-module receipt, and valid native CPU
capability registers (including required CRC32 support). Numeric host registers
are exported for the comparison. Existing finite
graphics, dependency and launch checks remain in effect. Diagnostics records
the requested engine, source identities, runtime hash and execution proof.

## Qualification evidence

The complete FEX gate passes for `e60b1b64e5567f7fe4cca2d70cca418cd4fa9edd`
in push run `35814095537` and PR run `35814098449`. The ARM64 container exercises
actual FEX PE32 execution, copied-prefix conversion, software drawing and both
DXVK 2.5.3 / 2.7.1. DXVK 2.7.1 uses the shared-memory upload path with real
pixels, PCM and input. The existing patched PRoot separately exercises software
D3D8, 60 Hz Native Surface, PCM, input, controller stages and all launch lifecycle
cases. Both routes compare every original prefix file/link against its stopped
snapshot. No original prefix changes occurred.

The owned default-error-mode crash log confirms an attempted `winedbg.exe`
launch is rejected with error 126 after the exact-name override. Both owned
crash fixtures and the full-window crash case then return the expected
`0xc0000094`; no timeout or recurring gameplay observer is used to hide a hang.
The FEX evidence artifact is `10731625312`, SHA-256
`a9125d7cde5da7465637a2f414fc6611b753504d3c0f1bcbbeddd7bcee75b5ed`.
This qualifies the backend for a Thor comparison, not an FPS result.

## Focused Thor comparison

Install the signed 0.5.13 update in place. Preserve the working Termux server,
client `30251204_1`, original xiloader and accepted client preparation. Do not
update, import or re-prepare the client/server for this test.

Keep 60 Hz, Native Surface, shared-memory upload and DXVK 2.7.1 enabled. Keep
the same resolution. Turn the sysmem/two-worker experiments off for both runs;
use the normal FPS HUD and leave startup capture off.

1. Runtime tab: install FEX, select FEX, and run Windows checks. Confirm the
   triangle, registry/COM labels, tone and input. Stop normally.
2. Launch the existing prepared client with FEX selected. Repeat the same
   warmed-up route, checking FPS dips, audio and right-stick camera axes.
   Exit, relaunch once, then export Diagnostics.
3. Switch FEX off and repeat that route with Box64. Export Diagnostics
   separately. Switching off selects the preserved original prefix.

Hardware gameplay performance and real-client compatibility remain device
checks; CI uses synthetic fixtures and lavapipe, never claims Adreno FPS.

## Primary sources

- [FEX 2510 WoW64 implementation](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/Source/Windows/WOW64/Module.cpp)
- [FEX Windows build workflow](https://github.com/FEX-Emu/FEX/blob/320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab/.github/workflows/wine_dll_artifacts.yml)
- [Wine 10 CPU-module registration](https://github.com/wine-mirror/wine/blob/b073859675060c9211fcbccfd90e4e87520dc2c2/loader/wine.inf.in)

- [Wine 10 executable override matching](https://github.com/wine-mirror/wine/blob/b073859675060c9211fcbccfd90e4e87520dc2c2/dlls/ntdll/unix/loadorder.c)
- [Wine thread API backport](https://github.com/wine-mirror/wine/commit/d53a9ba0cd5ee46852b00e4a106e2eb679b5aa3d)
