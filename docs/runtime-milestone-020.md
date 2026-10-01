# In-app runtime probe · 0.2.0

The user confirmed that the imported FFXI data and a backup survive. This milestone establishes a fresh app-owned Windows environment. It does **not** launch FFXI, install POL prerequisites, modify imported client files, or manage the working Termux server.

## Device test

1. Install 0.2.0 over the existing LSB app. The application ID and preview signing certificate are retained. Do not uninstall or re-import the client.
2. Open **Runtime → Install runtime**. The verified download is 353,710,639 bytes (about 338 MiB). Keep at least 3 GiB free for extraction and prefix initialization. Downloads and extraction run in the existing foreground transfer service and can be cancelled.
3. Leave **Turnip 26 / DXVK (Thor)** selected with sound enabled. Tap **Start Windows checks**. The in-app display opens while a fresh Windows prefix is created; first startup can take several minutes.
4. The probe should show a colored triangle, **Registry: PASS**, **COM: PASS** and an increasing D3D8 frame counter. Tap outside the triangle; use **Keyboard → Send + Enter** and check the key/click counts. **Play tone** should produce a short tone. The tone also plays once on entry. Actual audio playback is a device check, distinct from the API accepting the sound.
5. Use **Exit probe**, return to Runtime, and start the checks a second time. Then test **Stop**. Returning with Android Back leaves the runtime alive; **Open runtime display** reconnects. The foreground notification has a Stop action.
6. Export **Diagnostics → Export support ZIP**. Include whether the triangle, tone, touch and keyboard worked and whether relaunch/stop succeeded. Runtime state and bounded logs are included automatically; a separate Windows log transfer is unnecessary.

If Turnip startup fails, export that attempt's logs first. A deliberate **Software diagnostic** selection can distinguish basic Windows/display startup from GPU failure; it is not a gameplay performance target. Turnip mode rejects unverified hardware instead of silently selecting a software renderer. No GameHub or Termux commands are part of this test.

**Create fresh Windows prefix** preserves the old prefix under the runtime's prefix-backups directory before initializing another. This is a diagnostic reset, not yet the full runtime backup/restore UI. Existing session backup ZIPs still contain the imported client and its configuration, not the experimental runtime. No failed runtime setup deletes the imported client.

## Candidate and provenance

The initial candidate is Debian ARM64, Wine 10.0 amd64 WoW64 and Box64 0.4.4 (commit `2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a`), with the existing TRASC PRoot, display and audio foundations. The Wine/Box64 combination already has integration and device history in that project. Reusing it lets this milestone test the Android host and D3D8 path independently of FFXI.

It is **not** the historical GameHub Proton 10 arm64x/FEX environment. No FFXI compatibility, equivalent component versions, or FEX substitution is claimed. FEX/Wine packaging and real FFXI qualification remain subsequent runtime decisions. The checkpoint must retain this distinction when describing results.

`scripts/prepare-runtime.py` pins every source archive/rootfs/component APK by SHA-256. It verifies the TRASC component APK before extracting only the PRoot executable/loader, Turnip libraries, Vulkan probe, ALSA plugin and patched Wine server used here. It obtains the matching x86 DXVK 2.5.3 **D3D8 and D3D9** pair from the verified upstream archive. Original source packages are mirrored alongside the pinned runtime at the LSB `runtime-probe-v1` release. The mirror is populated only after build, native Windows and ARM64 integration checks pass; it does not contain game data or private signing material. Existing mirrored assets are not overwritten.

Source references: [TRASC runtime recipe](https://github.com/Russianranger/trasc-server-android/blob/95475c747be1fbac1ac3f54197e962ce5ffc1f42/client-runtime/Dockerfile), [native runtime patches/build](https://github.com/Russianranger/trasc-server-android/blob/95475c747be1fbac1ac3f54197e962ce5ffc1f42/scripts/build-proot.sh), [audio bridge](https://github.com/Russianranger/trasc-server-android/blob/95475c747be1fbac1ac3f54197e962ce5ffc1f42/native/pcm_trasc.c), [DXVK 2.5.3](https://github.com/doitsujin/dxvk/tree/v2.5.3). Upstream license notices and corresponding sources are in those archives. Reused Java/audio protocol code is adapted from the user's TRASC repository; FFXI-specific orchestration and the open COM/D3D8 probe are in this repository.

## Isolation and lifecycle

`ClientRuntime` owns only `files/rt`: rootfs, Windows prefix, prior prefixes, runtime assets, probe, sockets, caches and logs. Android packages PRoot as extracted ARM64 native executables. The child process mounts those directories plus the required system device/proc/sys views. It never mounts LSB's imported client, GameHub storage or Termux data. This is operational separation, not a security sandbox for arbitrary untrusted programs; this milestone launches only bundled open test code.

The display is TigerVNC bound to an app-private filesystem Unix socket, without a TCP listener. Android verifies the peer uid. X clients use a generated Xauthority cookie. Audio uses the existing private Unix PCM protocol and Android AudioTrack; there is no microphone capture. Runtime supervision uses a dedicated foreground service, finite startup/stop timeouts and an ownership tag to clean up only the app's own prior processes. PRoot compatibility mode is used initially; acceleration/performance tuning is outside this milestone.

The probe's COM class and registry keys are artificial test fixtures. It calls DllRegisterServer and CoCreateInstance inside 32-bit Wine, then actually presents D3D8 frames through the selected stack. It does not register or load POL/FFXI DLLs. A pass establishes those primitive operations with the fixture, not the proprietary client's behavior. Initial prefix readiness is recorded only after a successful probe exit.

The Android UI exposes touch/mouse, physical keyboard, an on-screen text entry dialog, Enter/Esc, and basic D-pad/A/B key forwarding. Editable controller/gameplay mappings and relative mouse capture are later work. The display is a correctness-first RFB bitmap surface, not the later optimized Native Surface path.

## Verification boundaries

The workflow builds/signature-checks a disposable CI APK, retains the 90 client import/recovery checks and native Windows launcher checks, and adds open runtime contract tests. An ARM64 integration job executes the actual x86 test EXE/DLL through Wine/Box64, checks RFB-rendered triangle pixels and injected keyboard/mouse events, observes nonzero PCM through the audio protocol, then checks clean exit, relaunch and explicit stop. The Vulkan CI route uses an explicitly marked Lavapipe device because the runner has no Adreno hardware. The APK cannot enable that CI-only override. The same initial software probe is also exercised through the patched PRoot path and the APK's actual tar extractor.

The delivered APK reuses the private preview key locally; that key is never committed or sent to Actions. Device acceptance of Android execution policy, the document provider, physical sound/input and Adreno presentation still belongs to the Thor test. Passing CI is not sufficient to mark the real device milestone complete.
