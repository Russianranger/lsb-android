# X11 metadata caching: 0.5.10

## Accepted Thor evidence

The user confirms 60 Hz reduces drops into the teens compared with 30 Hz.
Game FPS remains mostly in the 20s and occasional hitches remain. The periodic
stutter removed in 0.5.8 remains fixed. Keep 60 Hz for subsequent Thor testing.

The two 0.5.9 exports were read locally:

| Export | SHA-256 | Sessions used |
| --- | --- | --- |
| `lsb-support (4)(1).zip` | `42a225e6bac1077ed8ec76cb6fd41dc1fa14dd7107c7b94d06938e51300d0a17` | Current: 60 Hz with detailed monitor |
| `lsb-support (5).zip` | `609366b3f1ac2012d6f3285e2f6019b403ee46bc7d7f4ce1c2fd65742a306a94` | Previous: 30 Hz with monitor; current: 60 Hz with normal FPS HUD |

Comparison uses fourteen five-second reporting windows beginning at 70–140
seconds in each session. All use 1280×720, native MIT-SHM capture, the same
Turnip/DXVK versions and the completed startup-only observer.

| Measurement | 60 Hz, monitor | 30 Hz, monitor | 60 Hz, normal HUD |
| --- | ---: | ---: | ---: |
| Surface posts/second | 25.39 | 22.10 | 26.03 |
| Display gaps over 50 ms | 337 | 428 | 281 |
| Display gaps over 100 ms | 1 | 53 | 7 |
| Display gaps over 250 ms | 0 | 0 | 0 |
| Mean X11 capture, ms | 3.26 | 4.94 | 3.52 |
| Mean Surface post, ms | 3.04 | 1.24 | 3.19 |
| Producer requests/second | 55.17 | 28.94 | 54.31 |
| Unchanged producer responses | 2088 | 479 | 1982 |
| Dropped diagnostic windows | 0 | 0 | 0 |

Surface posts are display delivery measurements, not game FPS. Different routes,
session lengths and warm-up conditions prevent a controlled speedup claim.
The monitor comparison does not establish a meaningful monitor penalty.
The logs do not establish Turnip environment variables as the remaining cause.

## Focused change

Previously every request synchronously fetched root window attributes, even
when the frame was unchanged, and every capture fetched and allocated a cursor
image. Over half the 60 Hz requests in these windows were unchanged.

Cache root geometry until a root `ConfigureNotify`, and cache cursor pixels and
hotspot until XFixes reports a different cursor serial. Query pointer position
on every poll, including idle polls. This live query also receives preceding
events without adding an extra synchronization round trip. Start both caches
fresh on every viewer connection and free cursor storage on disconnect.

The protocol basis is the official [RandR event specification](https://xorg.freedesktop.org/archive/current/doc/randrproto/randrproto.txt),
which permits root ConfigureNotify for screen dimensions, and the
[XFixes cursor protocol](https://xorg.freedesktop.org/archive/current/doc/fixesproto/fixesproto.txt),
which provides cursor-change notifications and serial identifiers. Pointer
position must remain live even when the cursor image is reused.

The existing five-second producer report now includes metadata query timing,
maximum capture duration and geometry/pointer/cursor query counts. Cursor fetch
time is included in capture time, not a separate additive cost. A diagnostic
`--poll-metadata` switch reproduces repeated queries for CI comparison; the
app uses caching automatically. There is no new user setting.

No changes to the frame protocol, Android renderer, pacing, accepted startup-only
observer, Wine/Box64, Turnip/DXVK, audio, controller, client files or xiloader.
This removes avoidable work; device FPS improvement still requires measurement.

## Verification

The native integration test compares cached and polling modes using a real X11
server. It checks live cursor movement, shape and hotspot changes, transparency,
edge clipping, real RandR resize and reconnect, with exact pixel assertions and
query counts. Both 30/60 Hz and MIT-SHM/XGetImage paths retain ownership,
unchanged-frame, duplicate-image and pacing checks. Full ARM64 CI is required
before delivering the signed APK; results will be recorded here and in HANDOFF.

## Focused Thor test

1. Install 0.5.10 over the existing app. Keep the working Termux server, client
   `30251204_1`, original xiloader and accepted preparation. Do not update source,
   re-import, re-prepare or start managed-server migration.
2. Use native Surface at **60 Hz**, compression off, fast display off, and the
   normal FPS HUD. Keep the same resolution and game settings as the previous
   60 Hz run. The detailed monitor is optional; another 30 Hz comparison is not
   needed.
3. Spend 3–5 minutes on the same route, including a short stationary segment,
   camera rotation and menus. Check cursor behavior, both camera axes and audio.
4. Stop and relaunch once, then export Diagnostics. Report typical FPS, lowest
   FPS and whether hitches are less frequent. New producer counters distinguish
   metadata waiting from capture and Surface costs.
