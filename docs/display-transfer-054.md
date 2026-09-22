# Lossless display transfer and launcher background: 0.5.4

## Device evidence

Input: `lsb-support (7).zip`, SHA-256 `437d8cbcc6f5665c43e7f843f57a702091fd05af530aabf2635a1473ab6e197b`, 0.5.3 on AYN Thor/API 33. The user reports continued stutters and a DXVK HUD range of 5–28 FPS. Do not describe 0.5.3 as an accepted smoothness fix.

Session `42bc108d-0ff4-4b55-ab63-3e6f32ec3aea` retains 61 display windows covering about 298 seconds. The RGB565 staging allocation remains exactly one, 1,843,200 bytes. The earlier allocation fix works on the device, but does not resolve the reported instability.

For the 170-second interval beginning about 75 seconds after display connection:

| Observation | Measured value |
| --- | ---: |
| Display updates / unique Android draws | 2,240 / 2,240 |
| Average display update rate | 13.16 Hz |
| Average update receive + decode | 45.73 ms |
| Average bitmap apply/decode | 1.58 ms |
| Average Android draw submission | 0.085 ms |
| Inter-update gaps above 100 ms | 314 |

Most steady windows move roughly 20–24 MB/s of uncompressed RGB565 data; the faster interval reaches about 36 MB/s. Late windows fall to about 5–9 display updates/s, with update receipt averaging roughly 84–114 ms and bitmap work about 7–10 ms. These are display transport/submission measurements, not a replacement for the user's actual DXVK HUD observation. Receipt time includes waiting while the server produces/sends an update; these logs alone cannot separate server work, PRoot scheduling and socket transfer cost. Android GPU presentation time and game-thread CPU/GPU timings are absent.

The existing Turnip 26/Adreno 740 and DXVK 2.5.3 hardware path is verified. The accepted generation, original loader and 1280×720 registry values remain selected. The dependency check and launch complete successfully. Startup capture is still enabled in this bundle; turn it off for the next normal-play comparison. Audio underruns reach 25 over about 260 seconds of playback; no audio code change is made in this iteration.

## Targeted implementation

The next target is the amount of data crossing the display connection. 0.5.4 advertises lossless ZRLE before Raw, retaining Raw/CopyRect/resize support. `ZrleDecoder` supports every defined tile family for the app's RGB565 and RGB888 formats, carries one inflater across rectangles and resizes, bounds compressed and expanded sizes, validates palette/run lengths and reuses buffers. High-colour RGB565 tiles use bulk row copies into the existing native bitmap path. The decoded colors are identical to the same negotiated uncompressed format; RGB565 itself retains its existing color-depth tradeoff.

The pinned TigerVNC 1.12 implementation supports ZRLE but its ZRLE encoder uses the server's `ZlibLevel`, rather than the client compression-level hint. The server now receives `-ZlibLevel 1` to keep compression effort low. This does not replace the display server or change Wine/Box64, DXVK/Turnip, audio, controller, client, loader or server source. See the [RFB encoding specification](https://github.com/rfbproto/rfbproto/blob/master/rfbproto.rst#769-zrle-encoding) and [pinned TigerVNC encoder](https://github.com/TigerVNC/tigervnc/blob/v1.12.0/common/rfb/ZRLEEncoder.cxx).

The Client settings add **Compress display transfer · lossless**, enabled by default. Turning it off and reopening the client view restores the previous uncompressed transfer path. There is no automatic renderer/runtime change or lossy JPEG mode. Resize handling requests a full refresh because the Android framebuffer is reallocated. Inflater resources are released when the display connection closes.

The existing bounded timing history now records actual encoded payload bytes, ZRLE/raw rectangle counts and ZRLE decode time. `raw_bytes_per_second` remains the equivalent uncompressed pixel volume for comparison; `encoded_bytes_per_second` is the transferred rectangle payload, excluding ordinary rectangle/message headers. A lower payload count is evidence of compression, not proof of higher game FPS.

## Icon

The screenshot shows Android's white surround around the old legacy drawable. The app now declares an adaptive icon for both regular and round launcher icons, with background `#50306D` and the unchanged crystal/LSB artwork inset by 25%. Android controls the launcher mask, including the Thor's circle. The background is a native resource layer; the source PNG is unchanged. Resource compilation and a native Android drawable preview validate the result. See [Android adaptive icon documentation](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive).

## Validation boundaries

Local checks cover the original core/transport tests, 84 new ZRLE checks (all tile families in both pixel formats, odd/edge tiles, 720p data, persistent streams, raw fallback, resize, invalid palettes/runs/expansion and corrupt/truncated input), Python contracts and nine Android lifecycle/bitmap/history tests. Adaptive resources compile/link and the rendered preview preserves the lettering and crystal.

`check-display-wire.py` runs the production protocol decoder against a real TigerVNC server over a Unix socket, comparing three full frames per mode for Raw versus ZRLE at both RGB565 and RGB888. CI runs it with the pinned ARM64 server directly and through PRoot, alongside all existing Wine/Box64/input/audio/render/launch and MariaDB gates. The local workspace prohibits Unix socket creation, so no local real-server result is claimed. CI results and the signed artifact identity are recorded in [validation](validation.md) after completion.

Synthetic image byte savings demonstrate transport behavior and exact pixel agreement. They are not a Thor gameplay benchmark. Compression can trade CPU work for transfer savings; the device comparison determines whether this improves the remaining stutters.

## Thor test

1. Install in place, retaining the prepared client and existing working Termux server. Keep client `30251204_1`, the original xiloader and matching server source; no update toward `30260904_1` and no managed-server migration yet.
2. Keep Turnip 26, 1280×720, the same display cap and Fast display on. Leave **Compress display transfer · lossless** on. Disable startup capture and enable the DXVK FPS HUD.
3. Walk and rotate the camera along the same route for 3–5 minutes. Note the HUD FPS range, pauses, image correctness and audio; export Diagnostics immediately.
4. If stutters remain, turn only **Compress display transfer** off, return to the running client view (or relaunch), repeat the same route and export a second bundle. Keep resolution, power mode and other settings fixed. This compares the new and previous transfer paths within one APK.
5. Check that the launcher icon's surround is purple, both camera axes work and Stop/relaunch remains reliable.
