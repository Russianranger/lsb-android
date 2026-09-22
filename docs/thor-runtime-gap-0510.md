# Thor 0.5.10 result and the GameHub runtime gap

The user reports no noticeable improvement with 0.5.10 and reports that the
earlier GameHub Lite / Proton 10 setup reached 60 FPS. Do not treat metadata
caching as an accepted gameplay speedup, or assume DXVK/Turnip/CPU translation
has been ruled out. The 0.5.8 periodic-stutter fix and the 60 Hz preference remain
accepted. This investigation changes documentation only; no new APK is built.

## Current device evidence

Local attachment: `lsb-support (6)(1).zip`, SHA-256
`55020deeefe24c8917d07b8315276ce3564772aff1a740068b8d5d6e7684d7f6`.
Current session `759bd3b1-07c9-4674-8d9c-c1b7f512b468` runs 0.5.10 at 1280×720,
native Surface / MIT-SHM / 60 Hz. Hardware probe reports Turnip Mesa 26.0.0 on
Adreno 740, software=false, driver SHA-256
`20ff681a7f37f228bcbad1910ed81174b299785620f528dc56d9be05730894f1`.
Native d3d8/d3d9 and DXVK initialization are recorded. Startup observation stops
at 13,031 ms and stays complete until exit at 213,118 ms; child/bridge exit 0.

The producer makes 13,195 pointer queries, one geometry query and three cursor
image queries over 230.459 seconds. Caching is active on the real device. In the
fourteen reporting windows beginning from 70 to 140 seconds, both metadata
caches require zero refreshes. The remaining live pointer/event round trip
averages 3.395 ms/request and peaks at 39.249 ms in those producer windows.
This is time observed waiting on X11, not attribution of that wait to a driver,
CPU scheduler, GPU load or PRoot by itself.

| Measurement, selected 70–140 s windows | Retained 0.5.9 | Current 0.5.10 |
| --- | ---: | ---: |
| Native Surface posts/s | 26.03 | 22.66 |
| X11 capture per posted frame | 3.516 ms | 2.641 ms |
| Android copy per frame | 0.428 ms | 0.431 ms |
| Surface post per frame | 3.189 ms | 1.784 ms |
| Display gaps >100 ms | 7 | 18 |
| Display gaps >250 ms | 0 | 0 |

These are delivered-image counters, not game FPS. The current run has the
detailed HUD (`devinfo,fps,frametimes,compiler,cs`), while the retained run uses
the normal HUD. Routes, temperatures and warm-up are not controlled. The table
does not prove a regression caused by caching; it does establish that faster
capture did not yield a demonstrated gameplay improvement. Do not prescribe
another compression, 30 Hz or resolution comparison as the next main experiment.

## Material differences from the recorded working GameHub profile

The profile is already preserved in `app/src/main/assets/working-profile.json`
and [working-setup.md](working-setup.md); do not ask the user to rebuild the lost
GameHub container merely to recover these known labels.

| Component | Current app | Earlier GameHub profile |
| --- | --- | --- |
| Windows runtime | Wine 10 WoW64 | `proton10.0-arm64x-2` |
| CPU translator | Box64 0.4.4 | `Fex-20251029` |
| D3D translation | DXVK 2.5.3 | `dxvk-v2.7.1-1-async` |
| Driver | pinned Mesa Turnip 26.0.0, KGSL/X11 build | `turnip_v26.0.0_R2` |
| CPU visibility | Box64 MAXCPU=0, actual core count | 6 Core setting |
| Presentation | forced CPU X11 WSI then capture to Android | implementation not recovered |

These GameHub package labels do not prove binary hashes, build flags, translation
preset internals, GPU presentation method, synchronization settings or native vs
translated module coverage. The 60 FPS report establishes a valuable comparison
target, not proof that changing only the Proton version reproduces it.

## Source/configuration audit

`runtime/supervisor.py` explicitly sets `MESA_VK_WSI_DEBUG=sw` on the Vulkan path.
The exact Mesa 26.0.0 source sets `wsi->sw` from this flag and selects
`WSI_IMAGE_TYPE_CPU` for X11 swapchain images. Depending on supported X11 shared
pixmaps, presentation follows its shared-image or software PutImage path. This
stage is before the X11-to-Android capture that recent APKs optimized. Hardware
Turnip rendering and a CPU presentation bottleneck can coexist. "Native Surface"
does not establish direct GPU-to-Android presentation.

Source: [official Mesa 26.0.0 archive](https://archive.mesa3d.org/mesa-26.0.0.tar.xz),
SHA-256 `2a44e98e64d5c36cec64633de2d0ec7eff64703ee25b35364ba8fcaa84f33f72`,
`src/vulkan/wsi/wsi_common.c` and `wsi_common_x11.c`. The archived files were
rechecked locally. Removing the flag alone is not an implemented accelerated
backend; the current X server/driver must support the alternative presentation
interface. This limitation was identified in the 0.5.6 audit and remains open.

The inherited OpenGL diagnostic variables (`LIBGL_ALWAYS_SOFTWARE`,
`GALLIUM_DRIVER=llvmpipe`, `LP_NUM_THREADS=4`) are still present. Their names do
not override the observed native DXVK/Turnip Vulkan evidence. Scope cleanup is
appropriate, but deleting these is not a proven fix for this performance gap.
No TU_DEBUG or custom DXVK compiler-thread, Vsync or FPS override is set by the
app. Imported DXVK configuration files and the effective game present interval
are not inventoried, and shader cache hits/compilation stalls are not measured.

The app uses `BOX64_DYNAREC_STRONGMEM=1`, `BIGBLOCK=2`, `SAFEFLAGS=1` and
`MAXCPU=0`. [Box64 0.4.4 documentation](https://github.com/ptitSeb/box64/blob/v0.4.4/docs/USAGE.md)
identifies STRONGMEM=1 as adding memory barriers and MAXCPU=0 as exposing actual
cores. These are testable differences, not evidence that unsafe relaxed-memory
settings or CPU affinity changes will be correct or faster. [FEX documentation](https://fex-emu.com/)
confirms a different translator with Wine WoW64/ARM64EC integration and host API
forwarding; it does not promise that FEX is faster for this exact client.

[DXVK 2.7.1](https://github.com/doitsujin/dxvk/releases/tag/v2.7.1) includes D3D9
performance changes that reduce unnecessary render-pass barriers in some games.
That supports a controlled version comparison, not a claim of an FFXI-specific
fix. DXVK 2.7 requires VK_KHR_maintenance5; probe the pinned driver's actual
features before enabling a candidate. The GameHub async fork is distinct from
upstream 2.7.1. Setting DXVK_ASYNC on stock 2.5.3 does not reproduce that fork.
The 2.5.3 and 2.7.1 built-in application configuration tables were checked: neither
has an FFXI/pol/xiloader-specific entry to copy into this launch path.

## Next implementation direction

1. Capture bounded effective graphics metadata: DXVK/config identity, supported
   Vulkan features, present mode/interval, shader compiler activity and GPU/CPU
   timing where supported. Record enough to separate translation, GPU rendering
   and presentation waits without reinstating intrusive gameplay module scans.
2. Provide an app-owned, pinned DXVK 2.7.1 comparison with an explicit 2.5.3
   rollback path, keeping the same runtime, scene and client data. Verify actual
   DLL loading and Vulkan requirements. Do not label upstream as the GameHub
   async fork or promise a 60 FPS result.
3. Pursue the higher-impact runtime candidate: a compatible accelerated GPU
   presentation backend and a separately qualified ARM64X/FEX runtime closer to
   the recorded GameHub architecture. Keep the current Wine/Box64 prefix and
   runtime selectable; validate login, controller, audio, exit/relaunch and
   device performance before promoting the candidate.

The only useful remaining GameHub clarification is whether the reported 60 was
an in-game/DXVK measurement or the launcher overlay, and whether a frame-rate
unlocker was active. FFXI's default divisor is 2 (30 FPS); divisor 1 is 60 FPS in
[Windower's Config documentation](https://docs.windower.net/plugins/config/).
The app's 60 Hz display polling does not change that game setting. This explains
why cap configuration belongs in the comparison; it does not explain drops
into the teens or dismiss the user's performance report.

Preserve the working Termux server, client `30251204_1`, original xiloader and
accepted preparation. No client/server source update or managed migration.
