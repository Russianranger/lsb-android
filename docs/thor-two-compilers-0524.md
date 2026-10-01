# 0.5.24: promote the confirmed two-compiler profile

User reports the 0.5.23 baseline control felt worse with both options off.
Enabling both two shader compiler workers and Turnip system-memory rendering
improved performance. Two workers alone retained decent performance; Turnip
system-memory rendering alone was worse. All four effective states are verified.
This is sufficient to recommend two workers for this Thor setup. It does not
establish a precise FPS gain or prove every remaining pause is shader compilation.

The control did not restore the earlier smoothness by reverting the app.
That weakens the hypothesis of a 0.5.22-specific regression; it does not rule
one out under every workload. No further rollback or four-way repeat is needed
for the next check. Use two workers, with the other rendering experiments off.

## Evidence

All current exports identify app 0.5.23. Read the supplied local copies, match
current/previous runtime-state and native-performance session IDs, and deduplicate.

| ZIP | SHA-256 | Current / previous session |
| --- | --- | --- |
| `lsb-support (3)(7).zip` | `3662877c8422bbc8c23e14e20afda2f5168f2b76c1032008e370203b17eb68f0` | `5f7f549e` / `fbcacd9a` (older 0.5.22 both-off run) |
| `lsb-support (4)(3).zip` | `8b2b17d7ce269797a2b479b40e89af9a7bff03244fd8c7a13e2f37ebfd4c61ad` | `367f9f0e` / `5f7f549e` |
| `lsb-support (6)(2).zip` | `43429a229f6bc634214581d568c59edb5ff83d0df1e2b795a8e88f3207d3807f` | `c4565f9e` / `f09a8668` |

| Order | Session ID | Two workers active | Sysmem active | User observation |
| --- | --- | --- | --- | --- |
| 1 | `5f7f549e-7e8f-4735-9955-b0c632efb450` | false | false | worse |
| 2 | `367f9f0e-53fc-4637-9a22-2f17161477f6` | true | true | improved noticeably |
| 3 | `f09a8668-b9de-45fe-960d-397b7ec14a07` | true | false | improvement maintained |
| 4 | `c4565f9e-8cea-4662-b301-01d0bec553c3` | false | true | worse |

Both improved runs have `graphics-tuning.log` acknowledgment of
`dxvk.numCompilerThreads = 2` and `DXVK: Using 2 compiler threads`, plus a passed
draw/presentation preflight. The Turnip-only run's tuning preflight reports
**8** compiler threads. Compatibility preflights run before tuning and report
8 even in the improved sessions; do not mistake them for the final environment.
Old rotated tuning logs in the first export refer to earlier experiments;
use matching runtime-state active fields, not filenames alone.

Every run retains FEX v3 (`030f3806…`), strict64, DXVK 2.7.1, Turnip 26 / Adreno
740, windowed720, detailed HUD, 60 Hz capture and SHM. Every native frame uses
SHM. Turning off **Turnip system-memory rendering** does not turn off Turnip:
it retains the same GPU driver and returns its rendering mode to the default.
The 0.5.23 control contains neither the border adjustment nor staged uploads.
The two naturally completed runs exit zero; the other two end via Stop.

Timing histories agree directionally with the user's observation, but are
Surface submissions rather than measured game FPS. Durations and scene timing
differ; they are not matched benchmarks. Using the same descriptive slice as
the previous report (complete five-second windows starting >=60s with >=20
submissions/s):

| Settings | Selected seconds | Posts/s | Capture ms/frame | Android copy ms/frame |
| --- | ---: | ---: | ---: | ---: |
| Both off | 130.34 | 25.97 | 2.746 | 0.490 |
| Both on | 215.42 | 27.62 | 2.338 | 0.443 |
| Two workers only | 85.20 | 27.82 | 2.174 | 0.443 |
| Sysmem only | 50.11 | 26.68 | 2.765 | 0.491 |

This slice excludes severe slowdowns and cannot quantify total improvement.
In the complete timelines, both-off and sysmem-only also have multiple windows
in the teens. Two workers alone has no such windows after 60 seconds until
shutdown; both-on stays about 25–29/s over that period. Different scene phases
still preclude a causal percentage. Fewer workers may reduce CPU competition,
but compilation activity and CPU scheduling are not measured here. The data
does not establish sysmem as independently harmful or a cure for loading stalls.

## App changes

Implementation `c6d1eba232cc17685d22a77f3c168c645c47deb4`, version 0.5.24/code 40.
The reserved code 39 remains the 0.5.23 control.

* Move **Two shader compiler workers** into **Proven fixes**, describing this
  Thor result rather than the older inconclusive tests.
* Add **Use tested shader settings** there. Explicit tap sets only
  `dxvk_two_compilers=true`, `turnip_sysmem=false`, `dxvk_staged_buffers=false`.
  The existing compatibility gate and environment restoration remain intact.
* Move staged geometry into **Past experiments** with Turnip system-memory
  rendering. Remove the now-empty New optimization tile and its repeat-test
  instructions. Neither experiment has demonstrated additional benefit.
* Keep saved choices unchanged when opening/updating the app. The profile
  button updates its checkboxes without rebuilding the form or clearing login
  entries; changes apply on the next launch.
* Correct border-option wording to measured window size. The known 1280×694
  observation and possible bottom gap are not fixed in this pass. Keep border
  removal off for the next performance baseline; sizing is a separate next task.

This is the regular branch APK again, with 0.5.22's optional border/staged
controls available. No runtime, Windows, native display, graphics driver,
compiler algorithm, default preferences or shader-cache code changes from
0.5.22. The working behavior is the setting the user just tested, not a new
claimed speedup. MainActivity, version packaging and its Android UI test change.

## Validation and next device check

Local core checks pass. The existing Android integration test now verifies
correct category membership, unchanged saved choices on opening, a profile
tap turning both experiments off, preservation of other settings and the form,
and the resulting runtime request. All 19 Android tests pass; wide/narrow native-rendered previews were reviewed.
APK verification is recorded below. Native runtime qualification uses exact asset
identity with 0.5.22 plus its previously passing independent FEX/Box64 jobs;
that version's primary FEX timeout remains documented, not erased.

After installing over the existing app: Client → Proven fixes → Use tested
shader settings. Leave border removal and startup capture off, keep Turnip 26 /
DXVK 2.7.1 / FEX v3 / strict64 / Native Surface / 60 Hz. No runtime reinstall,
reimport, client preparation, cache reset or toggle matrix. Walk the familiar
route for 3–5 minutes, pan into the busy area then repeat after it loads, Stop
and export one ZIP. Note remaining pauses on first versus repeat camera pans.
Resolve the remaining stalls using this profile as the performance baseline.


## Verified delivery

Signed `LSB-Android-0.5.24.apk`, versionCode **40**, **18,309,268 bytes**.
SHA-256 `afc4a359e936cf7b2a5c79ff3cc3a140231e008217856e17049c6d1c906ccc48`.
Original signer SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Library ID `libfile_72c33ec2f01081919178b0305b8a0329`; file ID
`file_0000000021cc81f5a24754baa453e689`.

Source CI: PR run **36042830832**, commit `c6d1eba`, device artifact
**10827736387**, archive SHA-256
`2fa47066a91bedc3191bd57fbc2b42fa85dc87a42fdcf7a77cdeb9e4ebb6e6fd`.
Disposable-signed CI APK SHA-256
`54c1e1875e0871dfdb68750976f0f960ff03b422a2c356cf77ca5de1584c73dc`.
UI artifact **10826883728**, SHA-256
`388fdbec73e07e92fd0a1a18cf6a4a7e3954d151831aa0e73e189fa870e9b0a2`.

Verify job **107779115946** passes core checks, 65 runtime contracts, 5 server
contracts and **19 Android tests**, plus APK compilation. Native Windows job
**107780270404** passes. Server deployment and presentation jobs also pass.
FEX **107780270354** and Box64 **107780270519** remain in progress at delivery;
do not report full new ARM64 qualification. Push run **36042824628** also runs
unchanged native checks; follow up on both workflows next turn rather than
manually retrying or waiting indefinitely to deliver this Android UI change.

ZIP integrity, package/version, 16 KiB alignment, runtime file hashes, v2/v3
original-certificate signatures and exact payload identity before/after signing
pass. The initial strict old/new runtime-entry comparison rejected the candidate
because `assets/runtime/bundle.json` has a different dictionary key order.
The difference was inspected: **only that entry differs**, parsed JSON objects
are identical, all referenced content hashes validate, and every runtime binary,
native library and Python script is byte-identical to delivered 0.5.22.
Verification requires that exact single-entry difference and semantic equality;
no executable difference was ignored or replaced. That resolves the packaging
check without rebuilding or changing the candidate's runtime behavior.

Delivery qualification for unchanged runtime code rests on that exact binary
identity and the previously passing 0.5.22 independent FEX/Box64 runs. Its
primary FEX timeout is still retained in the prior report. No assertions or
timeouts were weakened, no unchanged failed job was retried, and no new
performance improvement beyond the user-tested setting is claimed.
