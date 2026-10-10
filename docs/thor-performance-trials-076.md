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

Corrected functional source `ea16761d9f3af582c467d34c308d1f850b64aeef`, tree
`4dd7fe2257fb01abc2238d6e1c81b9d1a9f3a6f2`, is published in stacked draft
[PR #6](https://github.com/Russianranger/lsb-android/pull/6). Push run
`38025981142` / verify `114136881175` and PR run `38025983219` / verify
`114136897314` pass **217 runtime, 138 server and 187 Android tests**.
Core/import, strict native Surface/pacing, a concrete late-phase C regression
and real timing waits also pass; the existing integration assertion is unchanged.
Wide J and narrow L launcher previews were inspected successfully.

The replacement original-signed `LSB-Android-0.7.6.apk` is versionCode 80,
18,605,784 bytes, SHA-256
`a3b9ec5a2b7f55786820d37469fe8a5c75b56d292dcd6cd67bfb81325505f055`.
It retains certificate SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
APK v2/v3 signatures, 16 KiB native alignment and ZIP CRCs pass. All **74
non-signature payload entries** match the CI APK, SHA-256
`01eb55ef5d6831c8a93cfd1e309bed8662b0941afe35487eb062188a464c62ab`,
artifact `11659173993` from push run `38025981142`.
Packaged runtime Python matches source. The ARM64 helper SHA-256
`8c7ba96455b5ddc50a9376f6044e9c15379abc39a7d795dddac1dbea3dc5e8f6`
matches its presentation manifest; uploader hashes also verify.

Exactly five payload entries differ from 0.7.5: `AndroidManifest.xml`,
`classes.dex`, `assets/runtime/supervisor.py`,
`assets/runtime/x11-frame-bridge` and `assets/runtime/presentation-bundle.json`.
The other 69 entries are identical. The private signing key stays outside Git.
Relative to the superseded 0.7.6 APK, only the native helper and its
presentation manifest change; DEX is identical and the inspected launcher
previews remain applicable.

The earlier `2fc979d` source/APK is **superseded for delivery**. Its focused
push passed, but PR job `114135830180` in run `38025471152` failed K's existing
five-idle-response timing assertion (requiring at least `4/60` seconds) after
six earlier arms, including J, pass. Native ownership and independent review
confirm a late-request defect: an initialized late request can return
immediately, then another request can arrive near the following phase deadline.
The corrected source keeps immediate service only for a fresh connection;
later late requests wait for the first future phase deadline. Replacement
source and APK checks pass as recorded above. No timing assertion is weakened
and no unchanged rerun substitutes for a fix.

Corrected focused ARM64 qualification is **mixed**. PR job `114137528058` in
run `38025983219` passes all seven actual-filtered native arms and five fresh
Baseline/Baseline/J/K/L sessions, including 720 child pixels, 7,560 draws, 50
native requests, privacy checks and clean exits. Its independently checked
evidence ZIP SHA-256 is
`424e66a03fccdb8d7437e9adda00fb04aff0115709389e466f6b021a1336128a`.
Push job `114137396082` in run `38025981142` passes all seven native arms but
blocks the first Baseline launch. DXVK 2.7.1 consumes the two-worker setting;
warmup Presents 0 and 1 return, then Present 2 stalls. The unchanged 45-second
production wait correctly rejects launch; the runtime ends about 66 seconds
after launch starts. The push supplies no gameplay/trial-cycle evidence.
Its Baseline relative-60-Hz
consumer has already completed ten requests in 0.184 seconds (one capture,
nine unchanged replies), and K is inactive. Independent review finds no
source-level link from the new choices to the hang; the cause remains
unestablished. Do not label it harmless or CI-only.

Fresh corrected PR broad FEX job `114137528126` in run `38025983219` fails
the modern-Mesa, unfiltered DXVK 2.7.1 startup trace's observed-D3D8 check in
the second Turnip cycle. The normal 128-pixel startup check and first cycle
pass, and the dedicated actual-filtered software gate passes. The later
compatibility stage does not run. At 05:18 UTC, five corrected-head broad jobs
remain pending: push Box64/FEX/PlayOnline and PR Box64/PlayOnline. These
results stay separate from the focused filtered cycles; older-source passes
do not qualify the pending jobs.

The replacement APK is an **opt-in device test build**, with Baseline default.
The passing PR proves the recorded synthetic cycles, not repeatable launches
across both runs, complete-workflow acceptance, or an FPS/stability benefit.
No deadline/check is weakened and no unchanged rerun hides this mixed result.

Native CI requires MIT-SHM on its known fixture path to prove that case;
production's logged `XGetImage`
readback fallback remains supported. Historical 0.7.5 checks do not substitute
for the changed bridge or L profile. Synthetic pixel, rate/schedule, ownership,
lifecycle and filter checks establish correctness, not real FFXI performance
on Adreno.

Prior 0.7.5 follow-through is separate: push run `38019029060` completes all
required jobs successfully, including the broad runtime/FEX/PlayOnline gates.
PR run `38019032575` retains a later modern-Mesa Docker FEX/DXVK 2.7.1
cancellation-probe failure after both Turnip cycles passed. Its existing
60-second D3D8 check times out, then `integration.py:146` reports
`Cancellation fixture did not start`. The PR PlayOnline Box64 filtered
viewer result lacks reviewable evidence; the other PR jobs pass. These results
do not substitute for the new 0.7.6 helper, display-failure or L qualification.
