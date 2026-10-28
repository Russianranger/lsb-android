# Presentation pacing: Surface geometry, thread priority and refresh vote

## October 28 follow-up: capture overlap in 0.7.8

The user reports 0.7.7 is acceptable but not locked to 30 FPS. Support ZIP
`lsb-support(20261028-231709).zip`, SHA-256
`f1ae12225ce6b76befb2e3fa8f76349482fb7db0e465816632534b2d14a789aa`,
contains current session `67bba01e-7c2d-4f21-9250-d6bf37d58734`. The 33 complete
native-report windows starting at 45.085 through 205.319 seconds span 165.237 s:
4,657 posts / 28.184 posts per second, with five-second rates 27.50–28.72.
Capture averages 0.906 ms, Android lock 0.359 ms, conversion/copy 0.561 ms,
and submission 4.914 ms (maximum 31.722 ms). There are 345 gaps above 50 ms,
21 above 100 ms and none above 250 ms in that selected interval. Every >100 ms
event includes 5–7 unchanged responses. No comparison with an equivalent older
build was performed; these numbers are not an improvement claim or game FPS.

The new Android-only pipeline returns shared-buffer ownership immediately after
copying every pixel into the private ANativeWindow buffer, before unlock/post.
It remembers a single outstanding request per socket/Surface and consumes that
response on the next call. Unchanged responses require no pixel read. Invalid
headers, failed locks and size/format failures do not release unread pixels.
Failed sends still unlock/release the private window; reconnects use fresh state.
The guest byte protocol and packaged guest helper remain unchanged.

**Overlap native capture and presentation** under Advanced → Proven fixes is
on by default and takes effect on relaunch. Off restores after-post requests.
Host tests compile the actual JNI C implementation against a test window and a
real socket peer. Posting waits for the next request while the peer overwrites
the shared pixels: the test verifies the private image remains correct. The
suite also checks serial mode, unchanged responses, resize/cache reuse, fragmented
headers, malformed input, disconnects and window cleanup on failure.

Report format 3 retains five-second bounded asynchronous history. It adds actual
display mode/refresh, requested cap, request policy, request age and overlap,
unchanged-response wait, exclusive post-gap buckets and bounded >50 ms events.
The measured Surface-post duration excludes the early request send. Request age
includes producer pacing and response waiting, not game-frame age. An actual
refresh observation is not proof of frame scanout. Vsync-driven posting and
changes to the guest render stack are deliberately separate future experiments.

The older original-change description follows. Its "No measured Thor result"
qualification refers to controlled before/after improvement, which remains
unmeasured. Follow [TESTING-0.7.8.md](TESTING-0.7.8.md) for the next device run.

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
