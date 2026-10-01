# Thor 0.5.6 result: capture fixed, gameplay stutter remains

The user reports no noticeable performance improvement and a recurring stutter
every 3–5 seconds. This is not an accepted smoothness fix. The attachment is
`lsb-support(7).zip`, SHA-256
`97afb7d1107deda1165fe4fbeae534990eb48451361fec59bf5adcb9c47be122`.
It contains one new 0.5.6 session, `abc4e29a-bbe4-490e-acf9-7255b09559f4`, and
the retained 0.5.5 session `86e97193-ad9f-48a0-87b8-cbdc7e3d8208` in `.previous`
files. Do not mistake the old `shmget` failure for a new 0.5.6 failure.

## Confirmed device result

The current capture log reports MIT-SHM attached, errno 0 and X error 0.
All 6,565 posted frames carry the SHM flag. Native Surface stays active with
zero RFB pixel updates, payload bytes or bitmap allocations. Current settings
are 1280×720, 30 Hz, Fast display off and startup capture off.

Frame-weighted comparison of windows beginning between 75 and 265 seconds:

| Metric | Previous 0.5.5 | Current 0.5.6 |
| --- | ---: | ---: |
| Measured duration | 190.97 s | 190.74 s |
| Surface posts/s | 22.97 | 21.91 |
| X11 capture per posted frame | 25.18 ms | 4.00 ms |
| Request/receive per posted frame | 40.23 ms | 34.70 ms |
| Android pixel copy | 0.428 ms | 0.433 ms |
| Surface lock | 0.226 ms | 0.230 ms |
| Surface post | 0.954 ms | 0.895 ms |

These are capture/presentation counters, not game FPS. The scenes and device
temperature are not controlled. Faster capture is proven, but the user's report
and similar image throughput do not establish a gameplay improvement. Retain
MIT-SHM and investigate the remaining path rather than repeating compression or
resolution comparisons.

The Vulkan probe verifies Turnip Mesa 26.0.0 on Adreno 740, software=false, with
the expected driver hash. Launch diagnostics record native d3d8/d3d9 plus DXVK
initialization. Launch completes with exit 0; the native-reader failure at the
end follows game exit/display shutdown. Audio underruns increase during play:
the closest samples before elapsed 75/265 seconds show 9/38, versus 6/11 in the
previous session. This supports checking scheduling and stalls beyond pixel
copying, but does not prove their cause or establish a new audio-code defect.

## Driver and environment audit

The app sets both Vulkan ICD variables to its pinned Turnip library. Its clean
guest environment does not inherit arbitrary Termux driver variables. The app
does not set TU_DEBUG, IR3_SHADER_DEBUG, DXVK_ASYNC, cache-disable flags or a custom
DXVK thread count. Mesa and DXVK cache directories point into the persistent
accepted Wine prefix. The bundle does not inventory actual shader cache hits or
all possible imported DXVK configuration files, so neither is verified here.

The notable setting is `MESA_VK_WSI_DEBUG=sw`. In the exact Mesa 26.0.0 source,
`wsi_common.c` sets the WSI software flag; `wsi_common_x11.c` selects CPU images
and its software X11 presentation function when the shared-pixmap route is not
available. This is CPU-based presentation, not proof of software game rendering.
It remains in use before the X11-to-Android capture stage that 0.5.6 improved.
The pinned driver bundle explicitly calls this `x11-cpu-copy`; its build uses
X11 and KGSL. Removing the setting is not a verified drop-in optimization:
the alternative requires a compatible accelerated presentation backend.

Source checked against the [official Mesa 26 archive](https://archive.mesa3d.org/mesa-26.0.0.tar.xz),
SHA-256 `2a44e98e64d5c36cec64633de2d0ec7eff64703ee25b35364ba8fcaa84f33f72`,
matching the pinned driver build recipe. Relevant files are
`src/vulkan/wsi/wsi_common.c`, `wsi_common_x11.c` and
`src/freedreno/vulkan/tu_wsi.cc`.

`LIBGL_ALWAYS_SOFTWARE=1`, `GALLIUM_DRIVER=llvmpipe` and `LP_NUM_THREADS=4` also
remain in the base environment. These select the OpenGL diagnostic path; they
do not demonstrate that the selected Vulkan Turnip driver is rendering in
software. Their scope should be separated in a future environment cleanup,
with both graphics paths validated, rather than treating their names as proof
of the current stutter. See [Mesa environment documentation](https://docs.mesa3d.org/envvars.html).

## Concrete next target and evidence gap

`NativePresentation.start()` calls `report()` every five seconds on the same
worker that receives and posts frames. That method serializes the retained JSON
history, writes a temporary file and renames it before the worker can request
another frame. It is off the UI thread but still on the display's critical path.
This is a confirmed blocking design issue whose cadence matches the reported
hitch. The size/duration of the actual device pause is not recorded, so it is a
candidate contributor, not a proven explanation for every stutter or low game
FPS. Existing RFB statistics already use a separate bounded writer.

Next implementation should move Native Surface reporting onto a bounded writer,
preserve session isolation and final flush, and measure maximum/inter-frame gaps
plus report duration using bounded data. Check the audio worker's separate
five-second progress logging too, without changing accepted PCM behavior. Current
five-second averages cannot establish temporal correlation with individual hitches.

Then expose focused DXVK `frametimes`, `compiler` and worker statistics for a
controlled comparison. These are supported by the pinned
[DXVK 2.5.3 documentation](https://github.com/doitsujin/dxvk/blob/v2.5.3/README.md#hud).
Shader compilation and CPU/PRoot scheduling remain possible; the supplied bundle
does not identify which is responsible. Do not add speculative Turnip flags or
replace the runtime as though the cause were established.

This investigation changes documentation only. No new APK, driver setting or
runtime change has been implemented in this pass. Preserve the existing Termux
server/client `30251204_1`, original xiloader, accepted preparation, working
controller/audio behavior and purple icon. No source updates or managed migration.
