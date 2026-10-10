# 0.7.4: filtered gameplay startup

This candidate preserves the accepted PRoot syscall-filter acceleration and
two-worker FEX/Turnip 26/DXVK 2.7.1 gameplay profile. It includes the existing
0.7.3 storage and verified-update cleanup changes.

The October 9 Thor support archive shows a passing hardware/translation check
and passing automatic-worker DXVK probe, followed by a timeout at the second
Present in a separate two-worker probe. That timeout reverted the worker setting
and produced the misleading syscall-filter baseline warning. Five previous
archived sessions passed the same requested profile. The exact driver/thread
cause of the intermittent stall remains unproven.

The filtered baseline now verifies DXVK and the two-worker setting together in
one eight-frame startup process. Actual DXVK version, consumed configuration,
two-worker confirmation and all draw/presentation frames must pass within the
existing deadline. A failed check stops before client login and reports the
graphics failure. Filtering and the requested two-worker profile are retained;
failed checks never authorize an unverified launch.

## Install

1. Stop the client and managed server. Install the delivered original-signed
   **LSB-Android-0.7.4.apk** over the standard LSB app.
2. Keep the current runtime, prepared client, loader, server and database. This
   fix requires no reimport, preparation, client update, server compilation or
   redeployment. The package and original signing certificate are preserved.

## Focused Thor check

1. Open Play → Gameplay and updater presets → Graphics and compatibility
   settings → Proven fixes. Confirm **Runtime syscall filtering** and **Two
   shader compiler workers** are ON. Keep FEX, Turnip 26, DXVK 2.7.1 and the
   existing native/shared-memory display. Use Baseline, with Turnip system-memory
   rendering and staged geometry uploads OFF.
2. Launch an existing character. Test camera movement, combat and a zone change,
   then log out normally.
3. Relaunch the same client twice to check repeat startup. Record whether the
   earlier warning recurs and any stability or FPS change. No new performance
   gain is claimed by this startup change.
4. Export a support ZIP after the tests, or immediately after a startup failure.
   Diagnostics should show actual host syscall filtering and confirmed two
   compiler workers for the launched session, with one combined DXVK/tuning
   startup check. A failure must name its graphics stage and retain requested
   filtering rather than advise disabling the accepted stability feature.

Raw GitHub Actions APKs use disposable signing and cannot update your installed
standard app. Use the separately delivered original-signed APK.
