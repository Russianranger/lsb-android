# Native Surface presentation

Adapted from Russianranger/trasc-server-android, commit
`bbad628eb106b9660349ed2c6bb4179873e9adc9` (native frame capture/Surface conversion
and Android Surface lifecycle). This version retains LSB's pinned Xtigervnc,
Wine/Box64, Turnip/DXVK, ALSA and controller implementations. It does not import
the other application's runtime or input stack.

Pixels are published in a fixed-size owner-only shared file. A 32-byte network-order
header is the entire socket response. One outstanding request grants the producer
write ownership; the consumer requests again only after copying/posting the
previous frame. Release/acquire fences bracket publication/consumption. Bounds,
format and file size are checked before the native copy. Neither passwords nor
game data paths occur in bridge logs. No frame images are saved in diagnostics.

XShmGetImage (or XGetImage fallback) still reads back X11 pixels. Android copies
BGRA to RGBA into ANativeWindow and posts it. This removes RFB compression, socket
pixel payload, Java decompression and Bitmap updates; it is not GPU zero-copy.
XDamage/cursor events avoid idle captures, with a two-second periodic check and
exact comparison before publication. The existing RFB connection retains input
and can resume full framebuffer requests on native failure.

Two isolated opt-in capture trials retain the 60 Hz display setting and the
existing ownership and pixel suppression rules. `--capture-rate=90` polls for
changed X11 pixels at up to 90 Hz; it does not change game rendering or post
unchanged Surface buffers. `--deadline-pacing` keeps 60 Hz polling but schedules
absolute monotonic sleeps, skipping obsolete deadlines after a late consumer.
The modes cannot be combined and apply only to the 60 Hz display profile.
Each helper prints a fixed configuration receipt with its own PID and effective
display rate, capture rate and pacing policy; later metrics repeat those fields.

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
