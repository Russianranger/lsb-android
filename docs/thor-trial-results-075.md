# 0.7.5 Thor results: retain Baseline

The four supplied exports confirm successful filtered startup and world entry
for Baseline, G, H and I. None shows a meaningful steady display-delivery gain
over Baseline. H's small numerical lead is insufficient to promote it; G and I
do not improve this comparison. **Baseline remains the default**, with syscall
filtering and two compiler workers retained. No new proven FPS fix is claimed.

## Archive identity and deduplication

The user identifies `lsb-support (3)(9).zip` as Baseline; its actual receipt
confirms no trial active. Read the current top-level session in each archive,
rather than counting repeated historical sessions as additional trials.

| Run | Uploaded archive | Current session |
| --- | --- | --- |
| Baseline | `lsb-support (3)(9).zip` | `37f5f07f-22f3-4389-9a61-271098b9bc00` |
| G: non-tiler scheduling | `lsb-support(20261010-042643).zip` | `9d8adca4-7c08-4f0a-8afb-8fc292a0b9d8` |
| H: smaller FEX translation units | `lsb-support (2)(20261010-042643).zip` | `7549f6ca-d6d2-4ff0-96ff-5805e4c5f618` |
| I: immediate presentation | `lsb-support (1)(20261010-042643).zip` | `32a188c5-5f1f-47ff-abf5-299f0e764973` |

Archive SHA-256 values:

- Baseline: `90475619505e12812391178da20ae48625350e6a0570a8a90f1f9f47d5e0f779`.
- G: `86b6df3abb8beef1a9960018cfeaeb7cc65e9038dc81852eaa411b535c8399c7`.
- H: `3e7c53c019bb404460b87c382a1d9dab6230bd993f3ce9f596b2279cb7150dd8`.
- I: `f45f9f57b500caa4fe6ce5d051aa878179eb27ed76fc7a8f2c6a72e4219c8140`.

There are 24 archived-session instances across the four ZIPs but only nine
unique session IDs. All 15 repeated instances are byte-identical to their
matching archive; there are no conflicting same-session archives. Only the
four current sessions above enter this comparison. The other five sessions
are historical, including the prior startup failure and 0.7.4 result.

## Actual settings and successful lifecycle

All four current runs use app 0.7.5, FEX v3/strict64, Turnip 26 on Adreno 740,
DXVK 2.7.1, two compiler workers, native Surface and shared-memory uploads.
The windowed profile retains 1280×720 interface/background values; border
removal is requested and applied, with a 1280×694 game client area and
1280×720 backing capture. System-memory and staged-buffer experiments are off.

Each session has a matching independent host receipt with
`preflight_passed=true`, `launch_observed=true` and `mode=syscall_filter`, plus
guest confirmation that the filter and two-worker baseline are active.
Preflight takes 201 ms for Baseline and I, 202 ms for H, and 301 ms for G.
There is no compatibility-mode substitution in these samples.

The current graphics logs independently confirm consumed options and finite
startup checks:

| Run | Confirmed option | Startup evidence |
| --- | --- | --- |
| Baseline | `dxvk.numCompilerThreads = 2` | Eight warmup Presents and one two-worker receipt |
| G | `dxvk.tilerMode = False` | Eight warmup Presents, 64 software and 64 hardware vertex-processing pixels, three two-worker receipts |
| H | `LSB_GRAPHICS_CONFIG multiblock=0` | The same 128 pixel samples and three two-worker receipts, plus a passing pre-login FEX parent/child environment-hash check |
| I | `d3d9.presentInterval = 0` | The same 128 pixel samples and three two-worker receipts |

H's proof uses the pinned FEX WoW64 environment-loader source, inherited
graphics-process value and pre-login environment checks. It is not internal
FEX configuration readback or a direct measurement of JIT compilation time.

Every current runtime finishes `completed`, the client finishes `exited`, and
both child and bridge exit codes are zero. Actual game uploader counters are
4,903 SHM calls for Baseline, 5,403 for G, 4,032 for H and 5,807 for I, with
zero fallback or attach failure in each. Their average slot waits are only
about 0.007–0.008 ms; these results do not support changing upload-ring depth.

## Steady delivery comparison

The primary selection includes every nonterminal native timing window whose
start satisfies **60 ≤ t < 150 seconds**. Each run supplies 18 windows,
approximately 90.2 seconds, after world entry and before H's exit-adjacent
slowdown. Calculate the weighted rate as total posted frames divided by total
window duration; do not average the per-window rates without weighting.

| Run | Recorded seconds | Posted frames | Surface posts/s | Gaps >100 ms | Gaps >100 ms/min | Gaps >250 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline | 90.186 | 2,553 | 28.308 | 7 | 4.66 | 0 |
| G | 90.245 | 2,548 | 28.234 | 10 | 6.65 | 0 |
| H | 90.220 | 2,559 | 28.364 | 6 | 3.99 | 0 |
| I | 90.240 | 2,546 | 28.214 | 9 | 5.98 | 0 |

For a later sensitivity check, use **90 ≤ t < 150 seconds**. Each run supplies
12 windows, approximately 60.15 seconds:

| Run | Recorded seconds | Posted frames | Surface posts/s | Gaps >100 ms | Gaps >250 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| Baseline | 60.160 | 1,705 | 28.341 | 4 | 0 |
| G | 60.144 | 1,700 | 28.266 | 7 | 0 |
| H | 60.156 | 1,706 | 28.360 | 5 | 0 |
| I | 60.137 | 1,698 | 28.235 | 5 | 0 |

Both selections put all four runs around 28.2–28.4 posts/s. H's primary lead
over Baseline is just 0.056 posts/s and one fewer long gap; the later selection
has one more long gap than Baseline. This is not a robust performance gain.
G's shorter average Surface-post time does not produce a higher delivery rate.
I likewise shows no useful delivery advantage.

These are **Surface submissions, not game HUD FPS**. Unchanged scenes need
not be posted again, and timing windows do not label the route or camera.
The previously observed roughly one-per-second 150 ms gap pattern is absent
from all four steady samples, including Baseline. That improvement cannot be
credited to G, H or I.

## Startup, world timing and comparison limits

The actual chronological order is **G → I → H → Baseline**, even though the
upload filenames share a timestamp. Server receipts place each character in
zone 100, with first zone-entry events and later callbacks as follows:

| Run | Runtime start, UTC October 10 | Duration | First zone-entry event after start | Later zone-entry callbacks after start |
| --- | --- | ---: | ---: | ---: |
| G | 04:10:38.022 | 216.37 s | 35.98 s | 40.98 s |
| I | 04:15:20.561 | 228.50 s | 34.44 s | 39.44 s |
| H | 04:19:45.058 | 167.75 s | 36.94 s | 40.94 s |
| Baseline | 04:23:03.559 | 200.24 s | 37.44 s | 41.44 s |

These receipts confirm server world entry; they do not time the first complete
client world frame. Startup includes unmarked menu/character navigation and
cannot isolate rendering or compilation performance. Native timing windows
use elapsed connection time, while the zone offsets above use runtime start;
the two clocks are not assumed to have an identical origin.

H's short run has a low-delivery window beginning around 155 seconds near its
shutdown. Extending the shared comparison through 160 seconds would include
that window and incorrectly make an exit-adjacent slowdown look like steady
play regression. G, I and Baseline also have slower tail windows near closure;
whole-run averages include startup/static menus/exit and are not the primary
comparison.

There are no matched route markers, repeated randomized trials, game HUD FPS
samples, temperature readings or CPU/GPU frequency traces. Baseline runs last,
so cache and thermal state may differ. The evidence supports retaining
Baseline and does not establish a cooling cause, a shader-only cause or an
accepted G/H/I improvement. No new comparison matrix is required merely to
repeat these completed checks.

## Analysis method

The private scratch comparison JSON records archive digests, the four selected
IDs, deduplication counts, whitelisted settings/lifecycle receipts and both
weighted window selections. It was generated with Python's ZIP/JSON/hash
libraries from the supplied scratch copies, not by copying raw login output
or unrelated user data into this repository. Reproduce the tables by reading
`runtime/native-display-performance.json`, filtering its `windows` by the
stated start bounds and `terminal=false`, then summing `frames`,
`window_seconds`, `post_gaps_over_100ms` and `post_gaps_over_250ms`.

Retain the working filtered Baseline while assessing the next distinct
optimization candidates. See [0.7.5 trial rationale](thor-performance-trials-075.md)
for the original hypotheses; this report supersedes any suggestion that these
four runs establish a preferred trial.
