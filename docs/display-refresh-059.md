# Display refresh and frame-copy optimization: 0.5.9

## Accepted baseline and remaining limitation

The user accepts the 0.5.8 periodic-stutter fix. The new Thor bundle and comparison
are recorded in [the 0.5.8 result](thor-stutter-058.md#accepted-device-result).
Launcher observation stops at 13.74 seconds and remains idle for the rest of
the session. Overall game FPS still sits in the 20s with drops into the teens.
Preserve the startup-only observer, working login, audio and both controller axes.

The latest 720p/30 Hz run verifies Turnip 26 on Adreno 740, native DXVK 2.5.3,
MIT-SHM for all 5,927 posted frames, and no dropped reporting windows. In the
28 selected gameplay windows, capture averages 3.79 ms, Surface lock 0.228 ms,
Android conversion/copy 0.426 ms and posting 1.094 ms. The frame worker's maximum
loop delay is 0.116 ms. These timings do not establish Android posting as the
remaining game-FPS bottleneck. Occasional long gaps still include unchanged
images, so more display updates cannot guarantee more rendered game frames.

The same windows contain 3,359 posts and 763 unchanged responses, about 29.35
requests/sec. Of those posts, 765 follow gaps over 50 ms. A 30 Hz polling interval
can make a slightly late game image wait another roughly 33 ms; the 60 Hz option
halves that scheduled sampling interval. This can improve delivery cadence even
when game FPS stays the same, but the extra polling/capture work can also compete
with rendering. The paired device test is necessary to decide which rate helps.

## Changes

- Client → Graphics and launch options exposes **60 Hz display refresh**.
  Off retains the existing 30 Hz default; on selects up to 60 updates/sec for
  both Native Surface capture and the standard-display fallback. Stop and launch
  again to apply. This changes display sampling, not FFXI's own frame limit or
  the physical panel mode. Higher sampling can reduce the wait for a fresh game
  image, but also consumes more CPU and power and needs a Thor comparison.
- A reopened viewer reports the active session's rate from its launch request,
  rather than a changed preference intended for the next launch.
- The native producer compares captured pixels directly with the last published
  shared mapping. Android maps it read-only, and the next request returns write
  ownership after posting. This removes one 3.52 MiB buffer per connection and
  one 3,686,400-byte copy per changed frame at 720p. Exact duplicate detection,
  unchanged responses, first-frame delivery and resize/reconnect handling remain.
  At 24 posts/sec, the removed copy carried about 88 MB/sec of pixel payload;
  this is a structural reduction, not a measured FPS improvement.

No Turnip debug flags, shader settings, cache policies, Box64 tuning, Wine/DXVK
binaries or game files change. The existing Xvnc software WSI presentation path
still requires X11 readback. A zero-copy GPU display path would be a separate,
larger implementation, requiring compatible buffers and synchronization; merely
removing `MESA_VK_WSI_DEBUG=sw` does not implement one. Current evidence does not
justify replacing the accepted renderer or changing synchronization blindly.

## Verification

All six gates pass in [run 35784458788](https://github.com/Russianranger/lsb-android/actions/runs/35784458788)
for `735e5c114d1cc22bc15df1bd65c257fe5510517e`; the
[PR run](https://github.com/Russianranger/lsb-android/actions/runs/35784465163) passes all five applicable gates
and correctly skips release. All 62 launch scenarios, 15 Android tests,
116 core/preparation/login, RGB565, 90 ZRLE, 41 runtime and 5 server checks pass.
Native integration passes all eight combinations of 30/60 Hz, MIT-SHM/XGetImage
and Linux/PRoot (retained in evidence artifact `10721015088`). It verifies exact pixels, changed and
identically repainted images, buffer ownership between requests, idle retention,
reconnect, permissions and pacing. Supervised Wine/D3D8 captures use 60 Hz with
audio, input and the detailed HUD; the fixture checks the actual X server's
arguments as well as the native producer's reported cap. Android checks cover
active-versus-next-launch rate reporting. Existing launch, controller, audio,
Stop/relaunch and real MariaDB deployment/update/rollback gates all pass. The original-signer APK and exact payload comparison are
recorded in [validation](validation.md). No CI failures remain pending.

## Focused Thor comparison

Install in place. Keep the existing Termux server, client `30251204_1`, original
xiloader and accepted preparation. Do not update, re-import, re-prepare or migrate.

1. Keep 1280×720, Native Surface on, compression/Fast display off, startup capture
   off and normal DXVK FPS on. Leave detailed stutter diagnostics off for both
   performance runs so the graph does not vary between them.
2. Launch with **60 Hz display refresh off**. Repeat a familiar route for three
   minutes, checking game FPS, smoothness, sound and camera axes. Stop normally.
3. Enable **60 Hz display refresh**, launch again and repeat the same route and
   camera movement for three minutes. Stop and export Diagnostics immediately;
   the bundle retains both sessions. Report whether typical FPS, dips and input
   feel improve, remain the same or worsen. The setting stays at your choice.
4. If 60 Hz regresses performance, turn it off and launch again. No preparation
   or runtime reinstall is needed. Further rendering optimizations depend on
   the comparison; 60 game FPS and a sustained 30 FPS gain are not yet verified.
