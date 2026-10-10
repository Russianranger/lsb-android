# 0.7.6: sampling and queue trials with filtering retained

The completed 0.7.5 Baseline/G/H/I comparison shows no meaningful steady
delivery winner. Keep Baseline as the accepted profile and retire those three
selector choices rather than promote a tiny difference. The new J/K/L batch
changes three separate variables while preserving actual syscall filtering,
two compiler workers and rendering quality. No new Thor FPS gain is claimed.

## Measured starting point

Use the current Baseline in `lsb-support (3)(9).zip`, SHA-256
`90475619505e12812391178da20ae48625350e6a0570a8a90f1f9f47d5e0f779`,
session `37f5f07f-22f3-4389-9a61-271098b9bc00`. It is app 0.7.5, FEX/strict64,
Turnip 26/DXVK 2.7.1, two workers, actual host filtering, native Surface/SHM,
1280×720 backing and the existing border-adjusted 1280×694 game area. It enters
zone 100 and exits cleanly. See the exact archive mapping and deduplication in
[the results report](thor-trial-results-075.md).

For every nonterminal native timing window starting at **60 ≤ t < 150
seconds**, the Baseline contributes 90.186 seconds:

| Counter | Baseline |
| --- | ---: |
| Posted frames | 2,553 |
| Unchanged-image replies | 2,697 |
| Total completed replies/s | 58.213 |
| Surface posts/s | 28.308 |
| Unchanged-image replies/s | 29.905 |
| Posted gaps over 100 ms | 7 |
| Posted gaps over 250 ms | 0 |
| Maximum recorded Surface-post call | 31.698 ms |
| Unchanged replies within each recorded long gap | 5–6 |

The total completed reply rate counts posted plus unchanged responses. It is
not a GPU frame counter. A 60 Hz requested sampling rate produces roughly
58.2 completed replies/s here, and many requests find no changed image.
Occasional Surface-post calls exceed one 60 Hz sampling period. This motivates
testing sampling phase/cadence and queue overlap; it does not establish that
those stages cause the remaining game FPS dips.

All seven recorded long gaps contain responsive unchanged replies. Their
missing new image may originate in static content, the game/translator,
rendering, asset work or scheduling before the final Surface copy. Faster
sampling cannot create a frame that the game has not produced. The previous
once-per-second long-gap pattern is absent in this Baseline, so the new trials
do not claim to fix that old pattern. No thermal/frequency trace identifies a
cooling or clock cause.

## Three isolated changes

| Trial | Exact change | Hypothesis and tradeoff |
| --- | --- | --- |
| J Faster frame sampling | Native capture bridge polls at 90 Hz instead of 60 Hz | More sampling opportunities may reduce the delay before discovering a new image; more unchanged requests and CPU work may raise power/heat without improving game FPS |
| K Steady sampling schedule | Absolute monotonic 60 Hz deadlines; skip missed ticks and never issue catch-up bursts | Avoid sampling cadence drifting with request/copy/post time; it cannot help an upstream render stall and may show no device benefit |
| L Shorter render queue | `d3d9.maxFrameLatency = 1` | Reduce queued render work and possible input/display delay; less CPU/GPU overlap can reduce throughput or worsen stutters |

J changes capture polling only. The Android display preference remains 60 Hz
and the game's existing cap remains unchanged. K retains the 60 Hz sampling
rate and changes only its deadline schedule, with missed-tick skipping and no
request bursts. L retains the default bridge rate/schedule and changes only
the DXVK queue cap. The choices cannot be stacked.

L uses the pinned DXVK 2.7.1 commit
`c3dd74be6baec53786d4e064a572185b70347a17`. Its
[D3D9 swapchain](https://github.com/doitsujin/dxvk/blob/v2.7.1/src/d3d9/d3d9_swapchain.cpp)
calculates effective latency from the device limit, explicit configured cap and
backbuffer count plus one. An explicit cap of one limits overlap; it does not
prove lower real-game frame time or guarantee a higher rate. Configuration
acknowledgment is required instead of assuming a saved checkbox took effect.

## Activation and preserved baseline

Each trial requires the exact eligible filtered FEX/Turnip 26/DXVK 2.7.1
two-worker profile, with system-memory/staged-buffer experiments off. J and K
also require native Surface, shared-memory upload and the 60 Hz display
preference. This means the accepted Vulkan SHM uploader plus the native
display's mapped-file transport. It does not require MIT-SHM X11 readback to
succeed: the helper's existing `XGetImage` readback fallback remains supported
and logged. No first viewer capture is demanded before login, because the
viewer may open later.

The running owned helper must provide current rate/schedule proof; stale log
or requested values do not qualify activation. This receipt proves active
capture/pacing configuration, not physical Android Surface/GPU performance.
L must prove its
consumed DXVK setting. Every trial must pass the combined finite eight-frame
warmup and 128 software/hardware vertex-processing pixel samples, with current
worker/version evidence and independent host filter proof before login.

A failed or unsupported selected trial cannot silently disable filtering or
authorize an unverified launch. Baseline remains the accepted fallback choice
for the next stopped/relaunched run. Existing driver/translator precision,
memory ordering, game files, resolution, quality, borders, server/database and
cache policy are preserved. G/H/I selections normalize to Baseline with a
visible notice, and their no-winner result remains in the historical report.
For active J/K, an actual JNI/socket/Surface frame error that would otherwise
fall back to the alternate RFB viewer stops the current trial instead. A run
cannot claim the native sampling experiment while using a different display
path. Normal Surface pause/resume, rotation, clean game exit and Stop remain
supported. Expected helper closure during exit/Stop is not a transport failure
and must not produce a false trial-failure report.

## Device comparison

Install 0.7.6 in place using the original certificate. No runtime reinstall,
reimport, preparation, server rebuild or cache reset is required. Run Baseline,
J, K and L separately over the same 3–5 minute route, keeping the same
1280×720 profile, borders/view/cap/HUD, retained shader cache and fan/power mode.
Stop and export after each run. Record first versus repeated traversal,
game HUD FPS, visible hitches, input delay and any regression.

Do not equate native reply or Surface submission rates with game FPS. The
next logs must first establish actual trial activation, then corroborate
timing against the user's visible/HUD observations. No default promotion or
combined J/K/L mode is justified before the device results. See
[focused testing](TESTING-0.7.6.md).

## Qualification status

Local/unit/Android/native and real ARM64 results, exact source/artifact identity
and original-signed APK qualification are pending. Add those results when
verified; the historical 0.7.5 checks do not substitute for the changed bridge
or L profile. Synthetic pixel, rate/schedule, ownership, lifecycle and filter
checks establish correctness, not real FFXI performance on Adreno.

Prior 0.7.5 follow-through is separate: push run `38019029060` completes all
required jobs successfully, including the broad runtime/FEX/PlayOnline gates.
PR run `38019032575` retains a later modern-Mesa Docker FEX/DXVK 2.7.1
cancellation-probe failure after both Turnip cycles passed. Its existing
60-second D3D8 check times out, then `integration.py:146` reports
`Cancellation fixture did not start`. The PR PlayOnline Box64 filtered
viewer result lacks reviewable evidence; the other PR jobs pass. These results
do not substitute for the new 0.7.6 helper, display-failure or L qualification.
