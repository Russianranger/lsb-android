# 0.5.21: fullscreen, navigation and support export

## Device result from 0.5.20

User reports performance about the same, with remaining slowdowns. Support ZIP
`lsb-support (2)(5).zip` SHA-256
`6dcba8ce8062116386c7230b5bff42f8b69d520a1d3cf1bca7328315448a6d0f`,
session `3ad720be-a4d8-4568-ba02-18f4e2d3a824`, confirms FEX v3, strict64,
DXVK 2.7.1, Turnip 26, 1280×720, 60 Hz and shared-memory Native Surface.
Client exit is zero; the terminal capture EOF follows shutdown. Audio ends
with four underruns, stable through the retained playback records.

The hidden-cursor change is active: 10,459 of 12,289 requests avoid pointer
queries (85.1%). It reduces round trips but has no noticeable gameplay gain
according to the user. This result is now listed under Past experiments;
the correctness-tested reduction remains enabled. Do not present it as a
proven FPS improvement or repeat the same comparison.

All 4,943 submitted frames use shared memory, with no socket pixel payload.
Windows starting 65–205 seconds average 27.91 Surface submissions/s over
145.31 seconds; this is not a game-FPS measurement. Weighted capture averages
2.42 ms, Android lock 0.23 ms, copy 0.44 ms, and post 5.02 ms. Longer gaps
mostly carry unchanged responses; retained late-run >100 ms gaps at about
94, 117 and 136 seconds carry five unchanged responses each. The evidence does
not identify shader compilation, translated CPU work, GPU work, loading or
static scenes as the cause. No unsupported translator/driver switch is made.

## Changes

* Saved **Fullscreen app and game** option in Client → Graphics & display.
  The game menu also offers Enter/Exit fullscreen. Android status/navigation
  bars use transient edge-swipe controls; the in-game bottom status strip is
  hidden, while the game menu stays accessible. Client status remains available
  in that menu and launch-failure dialogs remain active. The preference applies
  to launcher and display, survives activity recreation, and never changes the
  Windows resolution, aspect fit or runtime selection.
* Client/Controller/Server/Runtime/Profile/Diagnostics navigation uses the same
  ornamental gold borders and ripple feedback as the section tiles. Selected
  state is distinct and the existing wide/narrow row layout is retained.
* **Export support ZIP** is a dedicated direct-action tile on Client and
  Diagnostics, opening the existing document picker immediately. Export keeps
  the current expanded panel and respects the existing operation-busy guard.
  It is also available before client preparation.
* The gameplay status overlay only replaces text when its content changes and
  skips text work while hidden. This avoids periodic redundant redraws; it is
  a small UI overhead reduction, not a claimed fix for the observed slowdowns.

Fullscreen follows the platform's
[immersive-mode guidance](https://developer.android.com/develop/ui/views/layout/immersive).
API 30+ uses WindowInsetsController; the supported older versions use immersive
sticky flags. No FEX, Wine, DXVK, Turnip, capture protocol, audio, controller,
client files or server behavior is changed.

## Qualification and next device check

Local core checks and all 63 runtime Python contracts pass. Android tests now
exercise real menu toggles, status visibility/update behavior, saved fullscreen
on reopening, no connection generation change during toggles, unchanged Windows
resolution selection, and the export tile's actual document intent. Native UI
previews cover the updated tiles and navigation and were visually reviewed.
All 19 Android checks pass, along with the build, native Windows, presentation
and server-deployment jobs. Primary FEX failed at probe shutdown; Box64 and
the independent PR runtime runs are still running. Current results are in
[run 35945502024](https://github.com/Russianranger/lsb-android/actions/runs/35945502024)
on implementation `d52a7542750fe48f45e3fca1b52abab8a91ef3c4`. Their results are
not claimed as passing. This Android-only device-test update is delivered on
the passing Android checks and exact native-asset identity with the previously
qualified 0.5.20 APK. The unchanged-runtime reruns continue independently;
check and record their eventual outcome on the next follow-up.

Signed APK: 0.5.21, versionCode 37, 18,305,172 bytes. SHA-256
`312267378bd71d55c5adc38c195a2cf6d65a05f8da2c4b7e0086dac7ec7f66df`.
Original signer
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Package/version, ZIP integrity, alignment, v2/v3 signatures and exact CI payload
identity pass. Only AndroidManifest.xml, classes.dex and JSON key ordering in
the runtime manifest differ from 0.5.20. All native runtime assets, graphics
binaries, game launcher/observer, audio/input helpers and artwork are identical.

Install the signed APK over 0.5.20; no runtime reinstall or client preparation.
Keep the accepted settings and startup capture off. Enable fullscreen in
Graphics & display or the game menu. Test edge-swipe Android controls, exit and
re-enter fullscreen, then reopen the display and check persistence. Verify
controller and touch alignment, including near the edges. Play the familiar
route for 3–5 minutes, stop, and use the Export support ZIP tile. Send the ZIP
with any fullscreen/UI issues and remaining slowdown observations.


## Failed primary FEX run retained

Primary FEX job `107463031167` fails in integration.py's unchanged
`p.wait(timeout=40)` for trace_probe.py, in the strict64/DXVK 2.7.1 cycle.
The full evidence archive is artifact `10787106028`, SHA-256
`36d660c79c3f3c64611417564aee14aac8eabcaf886650163fc44875560a74ba`.

Before timeout, CPU/math and x87 flag checks, observed UP/indexed/texture pixels,
and live 60 Hz Native Surface checks pass. Retained probe state has 300 D3D8
frames, one keyboard event, one pointer event, submitted audio and HRESULT zero.
Graphics pixel state passes all 128 samples. Wine's last log reports an
out-of-date swapchain/recreation. The exact shutdown stall is unresolved;
this log alone does not establish its cause. The final stopped phase comes
from failure cleanup, not successful automatic exit.

No deadline/assertion was relaxed and no unchanged retry was requested. The
Android changes do not run in this Linux/ARM64 test; shipped native payloads
are byte-identical to the fully qualified 0.5.20 build. This limits the evidence
for a regression from this UI update, but does not turn the failed job into a
pass. Delivery is a device-test APK with the CI limitation disclosed. Inspect
the still-running PR FEX and both Box64 results at the next follow-up.
