# 0.5.25: three independent optimization trials

The user confirms that 0.5.24 improved performance and requests at least three
separate variables for the remaining slowdowns. Two compiler workers remains
the accepted baseline. No claimed device improvement for these new trials yet.

## Latest device evidence

Local supplied `lsb-support (7)(2).zip`, SHA-256
`5dcb912b2fe09ca612a6f5e1a7386d9eda752d2e8d6746d72a1e032ad555896d`,
session `8dcce1d4-ba9f-4360-9013-2fe48a874362`: app 0.5.24, FEX v3/strict64,
Turnip 26 / Adreno 740, DXVK 2.7.1, two workers confirmed, sysmem and staged
uploads off, SHM active, border adjustment disabled. Startup observation ends
at 8928 ms; no recurring scans. Session ends with user Stop, not a natural exit.

Surface histories include roughly 25–29 posts/s through much of the run, with
some retained five-second windows around 22–26/s. Busy-period capture is about
2.1–3.6 ms/frame and Android copy about 0.44–0.54 ms/frame. These are Surface
submissions, not measured game FPS; there are no matched scene markers. They
do not establish a dominant Android copy bottleneck or a shader-only cause.

## Controlled variables

`Client → Optimization trials` contains a single selection; trials cannot
accidentally be stacked. All default off. Existing preferences are preserved.
Use the tested shader settings button to return to the two-worker baseline.
Trials require confirmed two workers, hardware DXVK 2.7.1 and both past rendering
experiments off. Failed/unsupported trials record a reason and preserve the
validated baseline environment. They never change FEX precision or memory order.

| Test | Only change relative to baseline | Hypothesis / tradeoff |
| --- | --- | --- |
| A: One compiler worker | `dxvk.numCompilerThreads = 1` | Less CPU competition while moving; shader queues may take longer |
| B: Retain shader pipelines | `dxvk.trackPipelineLifetime = False` | Avoid discarding/recreating pipeline libraries during revisits; increases in-session memory and address-space use |
| C: Lighter 3D scene | FFXI 0003/0004 = 960/540; keep interface 0001/0002 = 1280/720 | 43.75% fewer background pixels; softer 3D image, no guaranteed FPS gain |

A/B run an independent startup pixel check (128 samples, both SWVP/HWVP), verify
configuration acknowledgment and actual worker count. B also requires reported
GPL support. A failure restores the exact pre-trial config, including two
workers. The accepted baseline receipt stays in `graphics_tuning`; the override
and its effective worker count are in `performance_trial`.

C requires Launch with Windowed 1280×720 selected. It uses internal profile
`windowed720lite`; other display profiles/probes receive an explicit skip note.
`performance_trial.active` selects that profile; the launch helper then verifies
all five registry values before starting the game. Use the launch report's
`process.display_config` as proof of actual application. Original settings
remain backed up. Returning to Baseline with windowed720 restores 720p 3D.
Display/capture resolution stays 1280×720; C does not change Android copy size.

Pinned primary source inspected locally: DXVK tag v2.7.1, `dxvk.conf`,
`src/dxvk/dxvk_pipemanager.cpp`, `src/dxvk/dxvk_device.cpp`,
`src/util/config/config.cpp`. One worker is supported; last duplicate option
wins. Lifetime tracking defaults on for this 32-bit non-RADV GPL path. Newest
Thor logs confirm GPL enabled. D3D8 batching was considered and rejected for
this pass because its specialized geometry implementation and documented
artifact risk are a poor fit after the earlier corruption. Cached dynamic
buffers likewise have no evidence of the CPU-readback workload they target.

## Phone comparison

Install over 0.5.24; do not clear data, caches, reimport or reinstall the runtime.
Use tested shader settings; keep FEX v3/strict64, Turnip 26, DXVK 2.7.1, SHM,
60 Hz, Windowed 1280×720. Keep border adjustment and startup capture off.
Keep the same HUD, fan/power mode and route for every run.

Run Baseline, then A alone, B alone, C alone. Stop and relaunch between choices.
For each, walk/pan through the same buildings/NPCs, then repeat the route for
3–5 minutes total. Export immediately after Stop and label the ZIP Baseline/A/B/C.
Record first-pan and repeat-pan smoothness, any longer load, missing graphics,
or new crash. A/B/C are not proven fixes until this device comparison. If one
fails, return to Baseline; no additional switches are needed. A final baseline
repeat is useful only if heat or different scene conditions make results unclear.

## Validation and CI follow-through

Local: core import/recovery/display tests and 68 runtime contracts pass.
Native Windows helpers compile. CI adds isolated A/B runs through real Wine
and pinned DXVK, checking 256 pixels total. The JP/US/EU registry fixture checks
C's independent background dimensions, reversion and original backup retention.
Android tests cover trial selection/request propagation and baseline reset.
These establish compatibility and wiring, not performance on Adreno or in FFXI.
New CI results and APK identity will be appended after packaging.

0.5.24 follow-through: PR run 36042830832's FEX and Box64 gates passed. Push run
36042824628's Box64 passed; FEX job 107780538255 failed because its second-cycle
DXVK 2.7.1 draw/present preflight timed out after 45 s and selected 2.5.3. Later
pixels/input/audio completed, but the exact-version acceptance assertion failed.
That failure remains unresolved and recorded; no unchanged retry or weakened
assertion. The runtime-release job was skipped. Do not describe all CI as green.
