# X11 metadata caching: 0.5.10

**Subsequent Thor result:** the user reports no noticeable improvement. The cache
works on the device, but this is not an accepted gameplay speedup. See the
[runtime/presentation investigation](thor-runtime-gap-0510.md) for the new log,
the GameHub Proton/FEX/DXVK comparison and the next implementation direction.

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
unchanged-frame, duplicate-image and pacing checks.

All six gates pass for `9612125345448fa4778484664a02167bd5629028` in
[run 35790527598](https://github.com/Russianranger/lsb-android/actions/runs/35790527598).
The ten native cases all pass: four cached mode/rate combinations plus one
60 Hz polling control on each of Linux and PRoot. Each sequence makes 88
requests and 52 captures, with two connections and four real mode changes.

| Query count per sequence | Cached | Polling control |
| --- | ---: | ---: |
| Root geometry | 6 | 88 |
| Cursor image | 14 | 52 |
| Live pointer | 88 | 88 |

The fixture initially assumed TigerVNC preserved pixels through a mode change;
it now redraws after checking the resize header and checks the new exact pixels.
Both backends pass the corrected test. Runtime evidence artifact `10722148392`
also confirms memfd/MIT-SHM, 62 launch scenarios, six startup-only observer
idle/Stop/crash checks, controller preload/input/disconnect, PCM, detailed HUD
and three supervised 60 Hz Wine captures. All 15 Android tests, native Windows
checks and real MariaDB deployment/update/rollback pass. No CI failure remains
pending. These are correctness and query-count results, not a Thor FPS benchmark.

## Signed artifact

`LSB-Android-0.5.10.apk`, versionCode 26, 15,940,768 bytes.
SHA-256: `611620c7ecac7eb28182001c6847e01351cad231b76e6fe7e25651d217b5f73f`.
Original signer: `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
APK v2/v3 signatures, alignment, ZIP integrity and package/version checks pass.
All non-signature entries match CI build artifact `10721413053`. Against 0.5.9,
only the Android manifest, DEX, native display producer and its checksum manifest
change semantically. `assets/runtime/bundle.json` also differs in JSON key order;
its parsed contents and every referenced runtime binary are identical. The
stutter-fixed launcher helper, graphics/native JNI/audio/controller components
and purple icon are byte-identical.

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
