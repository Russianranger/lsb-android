# Presentation pacing: Surface geometry, thread priority and refresh vote

No measured Thor result yet. These changes are on the Android presentation side
only; earlier bundles already show it costs about 2 ms per frame, so none of
them can raise the game's own FPS. They target delivery jitter and avoidable
per-frame work, and each needs a same-route Thor comparison before acceptance.

## Changes

1. **Buffer geometry is applied once, not queried per frame.**
   `android-surface.c` called `ANativeWindow_getWidth/getHeight/getFormat` before
   every posted frame. The width and height queries are answered by the
   BufferQueue producer over Binder and report its default size, which follows the
   view, so a scaled or fullscreen view could also make the old check fail on every
   frame and re-apply the geometry each time. The applied size now lives in
   `times[9..10]` of the per-Surface array, so a recreated Surface is configured
   again. This time was in neither the lock nor the copy counters, so existing
   diagnostics cannot show the saving directly.
2. **The Surface reader thread runs at display priority.** It previously ran at
   default priority alongside the translated game's busy threads.
3. **The panel gets a refresh vote.** `Surface.setFrameRate(cap, DEFAULT,
   ONLY_IF_SEAMLESS)` uses the configured 30/60 cap (API 30+, seamless-only on 31+).
   Irregular 15-60 posts/s content otherwise leaves the panel's refresh choice to
   SurfaceFlinger's heuristic. This is motivated by the 0.5.9 result that 60 Hz
   sampling helped, not by a recorded panel mode change; no panel-mode logging exists.

## Not changed, and why

- The game's 20-27 FPS is bound by CPU translation, DXVK and Mesa on the guest
  side; the retained captures show the display path faithfully following it.
- `Xtigervnc` is started without `-CompareFB 0`. It may do framebuffer comparison
  for an input-only RFB client in Native Surface mode, but an unsupported option
  would stop display startup, so test it as a trial rather than a default.
- `framebuffer.bin` is a disk-backed shared mapping dirtied every posted frame.
  A memfd handed over the socket would avoid dirty-page writeback, but that changes
  the guest/Android protocol.

## Thor check

Same route and settings as [0.5.8](thor-stutter-058.md): 1280x720, 60 Hz, Native
Surface, startup capture off. Compare Surface-post gaps above 50/100 ms and note any
visible brightness or refresh shifts. Export Diagnostics straight after each run.
