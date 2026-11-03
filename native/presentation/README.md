# Native Surface presentation

Adapted from Russianranger/trasc-server-android, commit
`bbad628eb106b9660349ed2c6bb4179873e9adc9` (native frame capture/Surface conversion
and Android Surface lifecycle). This version retains LSB's pinned Xtigervnc,
Wine/Box64, Turnip/DXVK, ALSA and controller implementations. It does not import
the other application's runtime or input stack.

Pixels are published in a fixed-size owner-only shared file. A 32-byte network-order
header is the entire socket response. One outstanding request grants the producer
write ownership. With overlap enabled, Android requests again after copying all
pixels into its private Surface buffer and before posting it. The serial fallback
requests after posting. An unchanged response can be followed immediately by the
next request. Neither path permits two outstanding requests or reads shared pixels
after returning ownership. Release/acquire fences bracket publication/consumption. Bounds,
format and file size are checked before the native copy. Neither passwords nor
game data paths occur in bridge logs. No frame images are saved in diagnostics.

XShmGetImage (or XGetImage fallback) still reads back X11 pixels. Android copies
BGRA to RGBA into ANativeWindow and posts it. This removes RFB compression, socket
pixel payload, Java decompression and Bitmap updates; it is not GPU zero-copy.
XDamage/cursor events avoid idle captures, with a two-second periodic check and
exact comparison before publication. The existing RFB connection retains input
and can resume full framebuffer requests on native failure.

Build the guest helper on ARM64 with `python3 scripts/build-presentation.py guest`.
Build JNI using NDK 27.2.12479018 with the `android` target. The guest executable
links libXdamage statically and the pinned guest's X11/Xext/Xfixes libraries
dynamically; its copyright notice is packaged alongside its checksum manifest.
The Android library targets API 26 and 16 KiB ELF alignment.

Tests cover protocol bounds/colour conversion, actual X11 pixels under ARM64
Docker and PRoot, MIT-SHM and XGetImage, changed/idle frames, reconnect, cap,
private-file permissions, input-only RFB and fallback. Android API 33 tests cover
stale/paused fallback and existing input/bitmap lifecycle. Physical Surface
presentation, game FPS, audio and thermal behavior still require Thor testing.

Request byte 1 retains periodic capture polling. Request byte 2 selects XDamage
waiting while one request is pending and the cursor is known transparent. Xlib's
queued events are drained before polling the X connection; damage, resize or
cursor-shape notifications wake the reader. An unchanged reply is returned after
at most about 100 ms, and the two-second forced readback remains. Visible/unknown
cursors, missing XDamage and metadata diagnostics keep cap-rate polling. Capture
attempts (including identical repaint checks) use absolute monotonic deadlines;
a whole missed tick rebases the deadline rather than bursting to catch up.

`capture_request_policy`, event wait/wake counts and idle heartbeats distinguish
the requested producer policy from active event waits. The Android setting can
select byte 1 for an in-place comparison. The 32-byte response and shared-pixel
ownership are unchanged. Run `python3 scripts/test-capture-pacing.py` for deadline
and socketpair/EINTR/disconnect regressions. `scripts/test-capture-events.py`
compiles the actual producer and exercises request1/2, XDamage wake, cursor, cap,
readback, reconnect and ownership against a disposable host Xvfb (needs X11 dev
libraries and Xvfb). ARM64 Docker/PRoot fixtures additionally cover RandR resizing.
