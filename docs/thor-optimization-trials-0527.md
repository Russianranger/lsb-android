# 0.5.27: three independent optimization candidates

No default graphics profile changes and no claimed phone performance gain.
The user's two-worker improvement remains the baseline; A (one worker) remains
available but unproven. B (pipeline lifetime disabled) and C (540p background)
stay retired after the reported freeze and severe panning regression.

## Evidence and selection

The fresh Baseline/A exports reviewed in [0.5.26](thor-trial-results-0526.md)
do not establish a scene-matched FPS gain. Native Surface timing is not game FPS.
Their actual-game shared-memory upload counters show:

| Counter | Baseline | A |
| --- | ---: | ---: |
| Uploads | 3,418 | 5,190 |
| Average copy | 1.112 ms | 1.053 ms |
| Average slot wait | about 0.005 ms | about 0.0055 ms |
| Maximum slot wait | 0.271 ms | 0.708 ms |
| Fallbacks / failures | 0 / 0 | 0 / 0 |

These do not support changing ring depth, synchronization or the working Android
copy path. Camera-pan stalls could still involve geometry access, shader work,
asset loading or CPU translation. D/E/F target different parts of that path.
Each replaces the selected trial and keeps two compiler workers; they do not stack.

## Trials

| Trial | Exact change | Potential benefit | Tradeoff |
| --- | --- | --- | --- |
| D: cached geometry | `d3d9.cachedDynamicBuffers = True` | CPU-cached dynamic/WRITEONLY vertex/index buffers may reduce CPU access cost | GPU access may be slower; already-cached software-only buffers are unchanged |
| E: fewer shader optimization jobs | `dxvk.enableGraphicsPipelineLibrary = True` | Skip background compilation of optimized variants after fast GPL linking | Less optimized GPU pipelines and retention of more base pipelines |
| F: runtime syscall filter | Remove `PROOT_NO_SECCOMP=1` only for the checked launch | Avoid ptrace stops for permitted syscalls | Kernel/runtime compatibility is device dependent |

D preserves direct mapping and existing lock semantics. It is not the retired
staged-buffer experiment. E requires confirmed GPL support before its probe and
again in the probe output. It does not set B's `trackPipelineLifetime=False`,
but its own retention behavior can still increase memory use. F does not bypass
GPU ioctls: path-sensitive calls and extension-relevant operations remain trapped.

Implementation checked against the exact local pinned sources: DXVK 2.7.1 commit
`c3dd74be6baec53786d4e064a572185b70347a17` (`d3d9_common_buffer.cpp`,
`d3d9_options.cpp`, `dxvk_graphics.cpp`) and PRoot commit
`7266fb3e8516535682f5a9c8f3a7e70f6506eddb` plus the existing bundled patches.
No driver, DXVK DLL, FEX precision, native presentation library or PRoot binary
upgrade is part of this build.

## Checks and rollback

D/E require the confirmed DXVK 2.7.1/two-worker profile with past sysmem/staged
experiments off. Each verifies effective options, worker count and 128 pixels
across software/hardware vertex processing, textures, dynamic geometry, alpha
and state blocks. A failed check restores the exact prior environment. These
synthetic checks do not establish real FFXI correctness or smoothness.

F additionally requires FEX and Turnip 26. Before the login tree starts, a separate
credential-free tree checks file/mmap/socket/thread/fork and SysV shared memory.
An exact PRoot activation marker and successful exit are mandatory. Unsupported
or failed preflight retains compatibility mode. Cleanup failure stops launch.
The actual login tree must emit its own activation marker before credentials are
sent. Only fixed, bounded markers are recognized; raw wrapper output is discarded.
The guest checks a bounded, regular, session-matched activation receipt.
Filtering cannot be disabled inside an already running PRoot tree: inconsistent
activation or a failed effective graphics baseline stops launch and asks for
Baseline. There is no automatic credential replay or false in-process rollback.

Support history now includes matching `proot-acceleration.json` receipts in each
of its six session archives. No per-frame history writing is added. Selecting
Baseline removes the trial; **Use tested shader settings** also restores the
accepted two-worker profile.

## Phone test

1. Install 0.5.27 over the existing app. No runtime reinstall, reimport or cache reset.
2. Choose **Use tested shader settings**, FEX/Turnip 26/DXVK 2.7.1 and the same
   1280×720 profile. Keep past experiments and startup capture off. Keep border
   adjustment and fullscreen state identical across comparisons.
3. Run Baseline, then D, E and F individually over the same 3–5 minute route.
   Repeat the same camera pans. Note initial traversal versus a second traversal,
   missing graphics, freezes and sustained FPS dips; do not judge only standing FPS.
4. Stop between runs and export support. Exporting after each is safest; one final
   ZIP also retains up to six session summaries. Return to Baseline immediately
   if a trial hangs or renders incorrectly. A remains available for normal play.

The F receipt distinguishes activated filtering from a preflight decline; a
declined F run is compatibility-mode evidence, not a performance test of filtering.

## Verification record

Local core checks, 77 runtime contracts, eight PRoot helper JUnit tests and the
real host file/thread/fork/socket/SysV preflight pass. Windows fixture compilation,
shell syntax and whitespace checks pass. The helper JUnit run used an equivalent
host file sink; full Android/Robolectric validation remains a separate CI gate.
New Windows stage markers are confined to the finite check executable, with
unchanged pixel assertions and timeouts. CI exercises A/D/E independently and a
separate enabled-filter real FEX run; all prior compatibility gates remain mandatory.

Prior 0.5.26 follow-through: earlier push FEX and PR Box64 passed their complete
gates; latest push FEX also passed. Other jobs intermittently timed out in existing
pixel/cancellation fixtures. The exact blocked call remains unresolved. No
unchanged retries or relaxed acceptance criteria. Final 0.5.27 CI and APK details
will be added after the build.
