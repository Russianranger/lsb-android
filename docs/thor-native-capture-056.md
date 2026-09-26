# Thor native-capture correction: 0.5.6

## Observed 0.5.5 result

The user reports higher game FPS with Fast display/compression off in the second
session, but regular stutters and performance below the desired 30 FPS remain.
The screenshot shows Native Surface and the DXVK HUD enabled, with both fallback
options off. The local `lsb-support (11).zip` SHA-256 is
`4f72e6cd053939b2cee78b960860b67d7215d5de392a6e460cd989b3b0c9a0a7`.

Both sessions used Native Surface at 1280×720 with the 30 Hz cap and hardware
Turnip 26/Adreno 740. Both have zero RFB updates, zero encoded pixel bytes and zero
staging bitmap allocations. The first RFB handshake selects RGB565 and the second
RGB888, matching the Fast display choice, but neither connection transports
pixels. Compression state in the first session is user-reported; no pixel
encoding is exercised in either run. No active RFB fallback is recorded. The
current native-reader closure occurs after the normal game exit; it does not
establish an in-game failure.

| 75–135 second window range | First session, options on | Second session, options off |
| --- | ---: | ---: |
| Measured duration | 60.34 s | 60.38 s |
| Surface posts/s | 20.75 | 22.10 |
| X11 capture/frame | 27.04 ms | 27.05 ms |
| Native pixel copy/frame | 0.427 ms | 0.427 ms |
| Surface lock/frame | 0.237 ms | 0.228 ms |
| Surface post/frame | 2.046 ms | 0.682 ms |
| MIT-SHM frames | 0 | 0 |

The longer 75–265 second second-session portion averages 22.97 Surface posts/s,
25.19 ms capture and 0.428 ms pixel copy. These are changed-image Surface posts,
not the game HUD, and the runs are not a controlled scene/cache/temperature
benchmark. The reported improvement is retained; the logs do not establish that
disabling the fallback switches caused it. No further on/off comparison is needed
before fixing the concrete capture fault.

Both launches use the accepted generation
`768f3a6d-8dfb-462f-8b9d-46cdd7101505`, original loader and matching client. Startup
capture is off; dependency checks pass. The first run is explicitly stopped; the
second exits normally with child/bridge code 0. Audio reports 12/11 final
underruns respectively, largely during the early portion; neither establishes
zero glitches, but no audio implementation change is indicated by this report.

## Concrete fault and correction

Both capture logs say:

```
Capture mode: XGetImage; 1280x720; reason=shmget failed; errno=38; xerror=0
```

Native Surface's shared-file handoff to Android works. The earlier X11 capture
stage cannot allocate SysV shared memory on this Android environment and falls
back to XGetImage. The app omitted PRoot's `--sysvipc` option. The exact packaged
PRoot binary already contains that option and the memfd-backed SysV emulation
patch; the pinned Termux PRoot source initializes the emulation only when the
option is selected. No replacement runtime is needed.

0.5.6 adds `--sysvipc` to the PRoot invocation only when the launch requests Native
Surface. Xvnc and the capture helper run in that same emulated IPC environment.
Standard display and controller setup keep their existing invocation. Wine,
Box64, Turnip, DXVK, native Surface/audio/controller binaries, client/loader files
and server source remain unchanged.

The prior CI claim must be narrowed: MIT-SHM succeeded through PRoot on Linux,
but that invocation also omitted `--sysvipc`, so the host kernel could supply
native SysV IPC and mask the Android fault. The corrected real display fixture
forces `--sysvipc` and requires the bundled patch's memfd-allocation marker, in
addition to exact pixels, MIT-SHM flags, reconnect/idle/cap and XGetImage fallback
checks. The full Wine/PRoot regression run also uses emulated IPC.

All six gates pass for `b3aec4c3b910c0755cbe1bfe626a2a46b6368911` in
[CI run 35769148142](https://github.com/Russianranger/lsb-android/actions/runs/35769148142).
The saved PRoot log confirms the memfd marker and successful MIT-SHM attachment
with zero X errors; all 56 launch cases and controller/audio regressions pass.
The original-signer APK is verified for an in-place update. See
[full validation and artifact identity](validation.md).

## First Thor check

Install 0.5.6 in place. Use the existing Termux server, client `30251204_1`, original
xiloader and accepted preparation. Do not update sources toward `30260904_1`,
re-import or re-prepare. Keep the second session's settings: 1280×720, Native
Surface on, startup capture off, DXVK HUD on, Fast display/compression off. Leave
the display cap unchanged. Stop and launch afresh so the PRoot option takes effect.

Repeat the same 3–5 minute route and camera movements, check audio/controller,
then stop/relaunch and export Diagnostics. Acceptance first requires
`Capture mode: MIT-SHM` and nonzero `shm_frames`; then compare game-HUD FPS and
stutters. This correction removes a demonstrated fallback but does not establish
a specific FPS gain before the device test. Disabling Native Surface and
relaunching restores the previous PRoot invocation and display path.
