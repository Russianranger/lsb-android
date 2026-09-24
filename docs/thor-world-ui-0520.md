# 0.5.20: accepted FEX world play, fantasy tiles and hidden-cursor polling

## Accepted device evidence

User confirms 0.5.19 renders both menus and the world correctly, with the best
world-play performance so far, generally 20–30 game FPS. This is the first
accepted FEX world run. The previous “world entry unverified” handoffs are
historical. No automatic world-entry detector is claimed.

Export `lsb-support (1)(3).zip`, SHA-256 `d9533c0660baad22bec925a27154590049bbf3aa147f6fba99a11d843e152f94`.
Session `500896eb-f2c4-4f4a-9ebd-c552e6e01fb0`: FEX runtime v3 archive
`030f38066af7b786c142e199cb84adec037840b0f029338849e43f99203a93e1`,
strict64, DXVK 2.7.1, Turnip 26/Adreno 740, 1280×720, Native Surface,
60 Hz, SHM upload active. System-memory and two-compiler experiments are off.
Startup/graphics capture and detailed HUD were enabled. Client exits 0.
All 8,090 submitted frames used SHM; no socket pixel payload. The final
native-delivery EOF follows client exit; it is not evidence of a gameplay crash.

The retained 48-window history begins around 195 seconds; it does not cover
the whole launch. Windows beginning 236–421 seconds average 27.99 Surface
posts/s, range 25.77–29.35. These are display submissions, not game FPS.
Capture averages about 1–3 ms/frame, Android lock about 0.2–0.3 ms, and copy
about 0.4–0.7 ms. The 952 ms and 1,444 ms gaps contain 55 and 84 unchanged
responses respectively; the next capture takes about 2 and 1 ms. This does not
identify CPU, shaders, GPU/readback, loading, or intentional static scenes as
the cause. There is no basis to change the proven translator/GPU stack again.
Audio records four underruns, with the count stable through the end of play.

## Focused optimization

The bridge still queries pointer position on every 60 Hz request even when the
cached cursor is entirely transparent. Existing metadata queries cost roughly
1–3 ms/request in this run. The new path drains XFixes/geometry/damage events,
then skips XQueryPointer only when the cached image has zero alpha everywhere
and no cursor-change notification is pending. Visible/unknown/changed cursors
retain live queries. Reappearance queries the current position before drawing.
Transparent motion no longer dirties the frame. Reconnect, resize, heartbeat,
exact-pixel comparison, ownership and frame caps remain intact.

`hidden_pointer_skips` proves whether this path helps on the next device run;
the old log has no visibility counter, so its benefit is not established yet.
The explicit `--poll-metadata` diagnostic still performs every query. Synthetic
checks exercise hidden motion, visibility restoration at the new position,
shape/hotspot/clipping, resize, both capture paths, both rates, and reconnect.

Protocol basis: [Xlib event queue documentation](https://xorg.freedesktop.org/releases/current/doc/libX11/libX11/libX11.html)
and [XFixes cursor notifications](https://xorg.freedesktop.org/archive/current/doc/fixesproto/fixesproto.txt).
No game files, rendering math, FEX binary, DXVK/driver or audio/input path changes.

## Launcher

Gold-bordered indigo tiles, serif headings/buttons, two columns on narrow
screens and three on wide screens. Large text can reduce the column count.
The selected section expands full-width below its row; opening another replaces
that panel without reconstructing its fields. Per-tab expansion survives
Activity recreation, while login secrets retain the existing clearing policy.
Client login stays above the tile grid. Existing artwork is retained.

Proven fixes contains the accepted FEX v3 path, faster x87, 60 Hz and DXVK;
verified SHM/capture infrastructure is labeled without claiming isolated FPS
benefits. Past experiments contains system-memory and two-worker controls with
no established improvement, plus historical unsuccessful corruption attempts.
CPU-feature correctness remains included in v3 even though it was insufficient
alone. New optimization and Capture & diagnostics remain separate. No saved
runtime choices are changed by reorganizing the screen.

## Validation and next device run

Local core/import/recovery/login/display-codec checks and all 63 runtime Python
contracts pass. Android layout/real-view image checks and existing ARM64/runtime
qualification are pending at this checkpoint.

Install the 0.5.20 APK update in place. Runtime v3 is unchanged, so no runtime
reinstall, client reset, reimport or preparation is needed. Keep the accepted
settings, turn off Capture FFXI startup and graphics, and repeat a familiar
world route for several minutes. Check menu/cursor movement and expand the new
Proven fixes / Past experiments panels. Stop and export one support ZIP.
Do not repeat standalone graphics or the old precision/DXVK/Box64 matrix.
