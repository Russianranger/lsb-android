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
previews cover the updated tiles and navigation. CI/APK qualification pending.

Install the signed APK over 0.5.20; no runtime reinstall or client preparation.
Keep the accepted settings and startup capture off. Enable fullscreen in
Graphics & display or the game menu. Test edge-swipe Android controls, exit and
re-enter fullscreen, then reopen the display and check persistence. Verify
controller and touch alignment, including near the edges. Play the familiar
route for 3–5 minutes, stop, and use the Export support ZIP tile. Send the ZIP
with any fullscreen/UI issues and remaining slowdown observations.
