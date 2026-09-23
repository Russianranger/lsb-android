# Thor rendering failure and pixel diagnostics (0.5.15)

## Device result: performance improves, rendering fails

The user tested signed 0.5.14 on the Thor and reports about 13 FPS, up from
about 5 FPS, with missing menu elements and severe graphics corruption.
The 06:16:13 screenshot has readable agreement text but missing background/menu
content. The 06:16:41 character-selection screenshot has dense white streaks
and distorted geometry. The DXVK HUD remains readable in both.

Support archive SHA-256:
`dd3a23835b5f5c2411268a31a00a7c0e0e32e3f4278975a8bc28cced02dc3079`.
The archive and client assets are not committed.

Current game session: `9c612832-2206-4d98-8fb1-a7afa1e7c414`.
Its preflight and final launch child both verify **strict64** arithmetic and
inherited environment controls. The previous runtime state is the preceding
strict64 standalone probe, `fdecf724-8e34-4602-ac0e-c9c641f3d6ae`.
`client-launch.json.previous` is the older 0.5.13 full80 FEX game session
`f1565fe7-80be-48eb-a70a-3236450689e4`, not a second 0.5.14 on/off test.
Do not infer two new game comparisons from the rotated filenames.

Confirmed configuration: native Wine 10/FEX 2510, Turnip 26 on Adreno 740,
DXVK 2.7.1, 60 Hz, Native Surface and SHM upload. Detailed HUD was enabled;
sysmem and two compiler workers were disabled. Runtime/driver/DXVK hashes,
prepared client generation, original xiloader, client version and 1280x720
settings match the existing setup. No software renderer was substituted.

The actual game uploader records 690 SHM uploads, zero socket fallbacks and
zero attachment failures. Average copy time is 0.721 ms and average fence
wait about 0.0043 ms. Late Surface delivery is approximately 13 posts/s.
The final Surface delivery error coincides with Stop. Audio has two early
underruns, remains stable during play and records one more at shutdown.
Startup observation again finishes at 8.194 s; it does not poll throughout
play. Both old and current FEX game diagnostics count 104 first-chance
exceptions. That count does not establish an exception storm.

The intact HUD and working final upload path suggest a fault upstream in game
rendering or CPU translation. They do **not** identify a particular FEX opcode,
Wine bridge, DXVK operation or driver defect. Faster x87 is a measured speed
improvement, not an accepted graphics fix. Full80 already had missing content;
this bundle alone cannot assign every new artifact to reduced precision.

## Diagnostic change

The original graphics preflight renders eight untextured, pretransformed
triangles and checks API return values. It cannot detect incorrect texture,
transform, buffer or render-target pixels. It stays in the game launch path.

Standalone **Windows checks** now additionally run a finite pixel contract
using the selected DXVK, FEX arithmetic and graphics settings. It runs after
any compatibility fallback and graphics tuning, so the report names the
actual selected DXVK version. Each software/hardware vertex-processing mode
renders four changing frames, verifying 64 sample locations (128 total):

- Pretransformed colored panels through DrawPrimitiveUP.
- Managed ARGB textures, uploaded using the returned row pitch.
- Fixed-function world/view/projection matrices, indexed dynamic vertex and
  index buffers using DISCARD and NOOVERWRITE, including nonzero base vertex
  and start index; geometry is rendered into a texture then sampled onscreen.
- Alpha blending, alpha rejection and state-block restoration between frames.

Readback occurs before Present so the selected HUD cannot contaminate the
samples. RGB tolerance is two channel levels; X8 backbuffer alpha is ignored.
A failed API records a fixed stage and HRESULT; a pixel mismatch records the
mode, frame, panel, sample and expected/actual RGB. Both completion receipts
are required, so an old triangle-only helper cannot produce a false pass.
`graphics-pixels.log` and `graphics_pixels` state hold the evidence.

The game launch retains its previous preflight and receives no additional
readbacks or recurring polling. Its existing bounded diagnostic parser now
recognizes exact, fixed DXVK reasons for unsupported line/patch states,
out-of-range base vertices, invalid state-block tokens, missing bridge and
unsupported FPU/texture operations. Unknown text stays redacted. This may
identify the currently generic D3D8 warning; the existing bundle cannot recover
that discarded text, and a warning alone is not proof of the root cause.

No runtime, Wine, FEX, driver, DXVK, presentation or client binary replacement
is proposed. 0.5.15 is a diagnostic build, **not a claimed rendering fix**.

## Qualification and next device test

Local runtime contracts pass (58 tests). ARM64 FEX/Box64 and Android CI
qualification is pending; do not deliver an unqualified APK.

After qualification, install over the current app. Keep the working Termux
server, existing prepared client and original xiloader. Keep FEX, Turnip 26,
DXVK 2.7.1, 60 Hz, Native Surface and SHM selected.

1. Leave faster x87 on, run Windows checks once, then export Diagnostics.
2. Turn only faster x87 off, run Windows checks again, and export separately.
3. If both pixel checks pass, a short game launch in the faster mode can collect
   the newly classified D3D8 warning. Stop at the broken menu and export; no
   long performance route or world-entry attempt is necessary.

These outcomes can narrow arithmetic versus generic D3D8/driver compatibility,
but a passing fixture still does not establish real FFXI compatibility. Box64
remains the previously working rendering baseline. There is no request to
update/re-import/re-prepare the client, replace xiloader or migrate the server.
