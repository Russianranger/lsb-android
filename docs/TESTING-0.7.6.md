# 0.7.6: three new filtered performance trials

The four 0.7.5 phone runs show no meaningful winner among G/H/I. Baseline
remains the working default, with actual syscall filtering and two compiler
workers. Version 0.7.6 tests three different variables: J samples the existing
display faster, K changes its sampling schedule, and L limits the render queue.
This is an opt-in device test build with mixed CI qualification. No FPS or
stability improvement is established for these new trials.

## Install and keep the working settings

1. Stop the client and managed server. Install the delivered original-signed
   **LSB-Android-0.7.6.apk** over the standard LSB app. Keep the existing runtime,
   prepared client, loader, server and database; no reinstall, reimport,
   preparation, server compilation or redeployment is required.
2. Keep FEX, Turnip 26, DXVK 2.7.1, **Runtime syscall filtering ON** and **Two
   shader compiler workers ON**. Leave system-memory and staged-buffer
   experiments OFF. Retain 1280×720, the current borders/view/game frame cap,
   shader cache, HUD and fan/power mode for every comparison.
3. Keep **Native Surface**, **shared-memory upload** and the **60 Hz display
   preference** enabled. J and K require that existing display profile. The
   shared-memory prerequisite covers the accepted Vulkan uploader and mapped
   native display transport; the helper's supported X11 readback fallback is
   retained. J's 90 Hz sampling rate does not change the 60 Hz display
   preference or FFXI's game frame cap.

G/H/I selections retire to Baseline with a visible notice after this update;
their results remain documented. The new selector offers Baseline/J/K/L and
activates one experimental choice at a time.

## Compare Baseline, then each new choice

Open the Optimization trials selector in Graphics and compatibility settings:

| Selection | Single experimental change | Watch for |
| --- | --- | --- |
| Baseline | Accepted filtered profile | Reference HUD FPS and visible pauses |
| J Faster frame sampling | Native bridge polls at 90 Hz | Better delivery timing versus additional CPU work, power or heat |
| K Steady sampling schedule | Absolute monotonic 60 Hz sampling deadlines | More regular motion versus no change when the game is the limiting stage |
| L Shorter render queue | `d3d9.maxFrameLatency = 1` | Lower delay versus lost CPU/GPU overlap, stutters or lower FPS |

1. Run Baseline, then J, K and L independently. Stop the client before changing
   the selector and relaunch the same character. Do not stack the choices or
   change the other settings between runs.
2. Spend 3–5 minutes on the same route and camera pans per run. Compare a first
   traversal with a repeat traversal while retaining caches. Keep the route,
   server, view and fan/power mode comparable.
3. Record **game HUD FPS**, visible pauses, camera/input delay, and any visual,
   audio, stability or temperature change. Report initial and repeat traversal
   behavior separately. Surface submission counters are not game FPS.
4. Stop and export a separate support ZIP after every run, labeled
   Baseline/J/K/L. If a trial's finite startup check fails, export that failure
   and return to Baseline; it is not a performance sample. Return to Baseline
   after a new freeze, rendering fault or consistently worse performance.

Every selected trial must preserve actual host filtering, two workers and the
exact tested graphics profile, pass the combined eight-frame/128-pixel startup
check, and supply a current helper or consumed configuration receipt before
login. The helper receipt confirms the owned process's active capture/pacing
configuration; it does not prove physical Android Surface or GPU performance.
The viewer can open after this check. Saved settings alone cannot establish
trial activation. If an actual native frame/socket/Surface error would switch
J/K to the alternate display path, the selected trial stops instead; export
the failure and return to Baseline. Normal pause, resume, rotation, clean game
exit and Stop remain supported; normal helper closure during exit/Stop is not
reported as a trial failure. The experiments do not reduce resolution or
visual quality, replace the translator/driver,
unlock the game's cap or change FEX precision/memory ordering.

## Qualification status

Push and PR verify/build jobs pass **217 runtime, 138 server and 187 Android
tests** at corrected functional source `ea16761d9f3af582c467d34c308d1f850b64aeef`:
push run `38025981142` and PR run `38025983219`. Core/import, strict native
Surface/pacing and the concrete late-request regression checks also pass. The
original-signed 0.7.6/code 80 APK matches all 74 qualified CI payload entries;
its original certificate, v2/v3 signatures, 16 KiB alignment and ZIP integrity
pass. Wide J and narrow L launcher previews were checked for readability.

Focused ARM64 qualification is **mixed**. Corrected PR job `114137528058` in
run `38025983219` passes all seven filtered native arms and five fresh
Baseline/Baseline/J/K/L sessions: 720 child pixels, 7,560 draws, 50 native
requests, privacy checks and clean exits. Corrected push job `114137396082` in
run `38025981142` passes the seven native arms, but blocks its first Baseline
launch when warmup Present 2 stalls after Presents 0 and 1 return. The unchanged
45-second production wait rejects launch; no gameplay cycles are qualified
by that push. Baseline's relative-60-Hz consumer had already completed its ten
requests and K was inactive. Review finds no source-level link from the new
choices to the hang, but its cause remains unestablished. This is not evidence
of harmlessness, repeatability or a complete green workflow.

Fresh PR broad FEX job `114137528126` fails the modern-Mesa, unfiltered
DXVK 2.7.1 observed-D3D8 startup trace in its second Turnip cycle, after the
normal 128-pixel startup check and first cycle pass. Its dedicated
actual-filtered software gate passes; the later compatibility stage does not
run. At 05:18 UTC, five corrected-head broad jobs remain pending: push
Box64/FEX/PlayOnline and PR Box64/PlayOnline. Those outcomes are not replaced
by earlier-source passes.

The earlier `2fc979d` build is superseded: PR job `114135830180` in run
`38025471152` exposed a real K late-request scheduling defect through its
unchanged idle-response assertion. The corrected source now keeps immediate
service only for fresh connections and waits for the first future phase on
later late requests. Use the verified replacement APK for opt-in device
testing with Baseline default. No deadline or assertion is weakened and no
unchanged rerun is used to conceal either failure.
Neither synthetic correctness checks nor a successful startup can establish
an Adreno or real FFXI FPS gain. See
[trial rationale](thor-performance-trials-076.md) and
[completed 0.7.5 results](thor-trial-results-075.md).

Raw GitHub Actions APKs have disposable signing. Use the separately delivered
original-signed APK for an in-place update.
