# Thor 0.5.4 compression comparison

Input: `lsb-support (9).zip`, SHA-256 `1bd5c866bc345718d559226f22838028d50414be0e335255b3d2cedd63187dd5`. The user reports approximately 6–28 FPS with Fast display and compression enabled, similar to the earlier build, and much worse behavior with compression disabled. **0.5.4 is not accepted as a smoothness fix.** Keep compression enabled based on this comparison.

## Sessions and comparison method

- Compression on: previous session `7ab817ea-25ae-44cf-badd-7ce4965e8d0d`, 65 timing windows over about 317 seconds.
- Compression off: current session `bd89f493-4df6-4442-a5e3-2c102bebb1a8`, 35 timing windows over about 169 seconds.
- Both use 1280×720, Fast display, a 30 Hz display cap, verified hardware Turnip 26/Adreno 740 and DXVK 2.5.3. Both complete with child exit 0.
- Both retain prepared generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505` and original loader SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`.

The table selects every timing window whose `window_start_seconds` is at least 75 and below 160: 17 windows and about 85.07 seconds per session. This excludes the login/initial idle intervals and gives equal-duration portions. It does not establish identical scenes, motion, thermal state or guest rendering load. Average receive/decode/apply timings are weighted by update count; Android draw timing is weighted by unique draws. Rates use summed counts/bytes divided by summed window duration. Compression savings compare the encoded payload with the raw-equivalent pixels within the compressed session, not unlike frames in the other session.

| Measurement | Compression on | Compression off |
| --- | ---: | ---: |
| Display updates | 952 | 893 |
| Display updates/s | 11.19 | 10.50 |
| Encoded payload MB/s (decimal) | 6.82 | 15.87 |
| Raw-equivalent pixel MB/s | 19.62 | 15.87 |
| Payload saved versus its own raw equivalent | 65.22% | 0% |
| Receive including decode, ms/update | 53.83 | 62.20 |
| Decode + bitmap apply, ms/update | 9.80 | 4.48 |
| ZRLE decode, ms/update | 8.24 | 0 |
| Bitmap apply, ms/update | 1.56 | 4.48 |
| Android draw submission, ms/draw | 0.079 | 0.082 |
| Inter-update gaps above 100 ms | 222 | 391 |
| Longest inter-update gap, ms | 283.83 | 399.94 |
| Pixel rectangles | 17,127 ZRLE | 222,976 Raw |
| RGB565 staging allocations | 1 | 1 |

The uncompressed session has many more small rectangles and more long gaps in this portion. Its later 115–160-second portion falls to 8.26 display updates/s with 91.70 ms/update receiving and 7.78 ms/update applying pixels. The compressed session also degrades later: the 265–310-second portion averages 7.95 display updates/s. The full runs have different lengths; comparing only their overall averages would obscure the timing changes.

## What this establishes

ZRLE is negotiated and saves about two-thirds of the pixel payload on the actual game. Every pixel rectangle in the compressed comparison portion uses ZRLE, while the other session uses Raw. The reusable bitmap remains at one allocation throughout both runs. Compression is useful here, but neither change has produced accepted stable gameplay.

These counters measure display delivery and Android draw submission, not DXVK/game FPS or actual GPU presentation. `receive_ms` already includes decoder time, so do not add `decode_ms` to it. It starts after the first message byte and can include waiting for the server to finish producing/sending the update. The logs do not distinguish guest rendering, display-server encoding, PRoot scheduling, socket wait, CPU contention or thermal effects. The measurements therefore do not justify replacing Wine/Box64, changing drivers or claiming a specific game-thread/GPU bottleneck.

Both launch receipts still contain `startup_trace.policy = temporary_import_copy` and observer/import-hook records. **Capture FFXI startup result was enabled in both runs.** Its ongoing cost is not measured here, and it is not established as the stutter cause. The setting is sampled by **Launch FFXI**; reopening an existing client view does not change that running launch. A clean comparison needs **Stop client**, uncheck capture, then **Launch FFXI**.

## Next device action using the existing APK

1. Keep 0.5.4, the existing working Termux server, client `30251204_1`, original xiloader, accepted preparation and matching server source. Keep Fast display and compression on, Turnip selected and the current 30 Hz display cap. Leave the FPS HUD on.
2. Stop the client. Open **Graphics and launch options**, uncheck **Capture FFXI startup result**, keep **Windowed 1280×720**, and press **Launch FFXI**. Repeat the same short route for about one minute and note whether the 6–28 FPS swings remain.
3. If they remain, stop the client, select **Windowed 960×540 (lighter)**, retain capture off and both performance options on, then launch again. Repeat the route for 3–5 minutes and export Diagnostics immediately. This profile has 518,400 pixels versus 921,600 at 720p: 43.75% fewer pixels, which is a workload reduction, not a promised FPS increase.
4. Report the HUD range and pauses for both resolutions. If 540p helps, investigate pixel/render/transfer costs next. If it barely changes the drops, prioritize direct guest CPU/frame-pacing and scheduling measurements before another display optimization. Resolution alone cannot distinguish GPU rendering from display-server/transfer work.

No new APK or production code change accompanies this evidence update. Existing CI results still describe the shipped 0.5.4 implementation. Do not update client/xiloader/server source toward `30260904_1`, re-import/re-prepare, or begin managed-server migration for this test.
