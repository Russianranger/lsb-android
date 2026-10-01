# 0.6.1 continuation qualification and signing

Implementation is preserved at `c3aac1e8ba4de6663ea1d7b0e5df7c93c99a2595`
on `codex/beta-061-improvements`, draft PR #2. Main remains the verified
Beta 0.6 commit `f3abab7ab3c1de6fb1bccea02a1d0cc7ff7fc630`.

## CI failure assessment

- Push run [36905461856](https://github.com/Russianranger/lsb-android/actions/runs/36905461856)
  initially failed only Windows job `110516445273`, at
  `tests/windows/playonline.py:127`: the receipt observer did not satisfy
  `process.returncode == 0`, final phase `exited`, and at least two observed
  running heartbeat samples. Prior receipt, visible-window and CPU checks
  passed. The parallel PR Windows job `110516859580` passed at the same
  implementation commit. Retrying the failed job without code changes passed
  as `110550904089`; the entire push run is now successful (attempt 2).
  This is intermittent qualification behavior, not evidence of a 0.6.1
  launcher regression. The failed assertion does not log which predicate
  failed, so its precise scheduler/observer cause is not established.
- PR run [36905467621](https://github.com/Russianranger/lsb-android/actions/runs/36905467621)
  initially failed only Box64 job `110516859707`, inside the existing
  60-second D3D8 pixel preflight under DXVK 2.7.1 and CI lavapipe. The last
  logged stage was software vertex processing, frame 3, before `pixel_lock`;
  earlier readback frames and math/CPU/native-display checks passed. The
  parallel push Box64 job `110516445264` passed the complete suite, including
  those DXVK pixels, software rendering, native capture, client initialization,
  launch/relaunch, input, updater lifetime and PRoot. A retry of the failed
  PR job was started as `110550713725`; its final result should be checked
  before claiming that both workflow runs are green. The same-code full
  passing run is conclusive qualification for the delivered payload; a CI
  lavapipe readback timeout is not a new Android runtime regression diagnosis.
- `git diff f3abab7..c3aac1e8 -- windows runtime scripts/check-runtime.sh
  tests/windows tests/runtime/integration.py` is empty. No production native
  launcher/runtime changes or arbitrary timeout increases were made to hide
  either failure.

The full successful push run also includes standard build/core/Android tests,
real compiler-cache reuse and invalidation, ARM64 MariaDB checkpoint recovery
and retention, FEX and official PlayOnline startup. Server-source compilation
passed separately in [36905467847](https://github.com/Russianranger/lsb-android/actions/runs/36905467847).
The implementation's reported 141 Android, 197 runtime and 136 server unit
tests are retained; no previously completed milestone was restarted.

## Device APK

The private `LSB-Android-preview-signing.zip` checkpoint was recovered from the
user's saved files. Its private-key certificate matches the actual published
Beta 0.6 APK, not just an old documentation record:
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Keep the private archive outside GitHub. Future sessions should recover this
saved checkpoint instead of generating a replacement signer.

- Source artifact `11183876093`, ZIP SHA-256
  `a2bffb6f4c84b02da0658a99121050c18b2115a62a5cb6ff9be20a6cf7185022`.
- CI APK SHA-256
  `80c230f7923dc15c9834a86794df970b92aa01b91f187d2ec45ba5854354a28c`.
- Delivered original-signed `LSB-Android-0.6.1.apk`, 18,581,050 bytes,
  SHA-256 `917f78066f9df4c3a8e98afa415206dbbee55de6ddc263388889b4b0c53f1bc8`.
- All 72 non-signature ZIP entries exactly match the CI APK. Packaged
  runtime/server source matches the continuation branch. APK v2/v3 signatures,
  ZIP integrity and 16 KiB native-library alignment pass. Package remains
  `io.github.russianranger.lsb`, version 0.6.1 / versionCode 72.
- Actual Beta 0.6 release APK SHA-256 was checked against
  `c438c4c0e29d918ff5cd94f663bcaf6d523c1df8c8194c9c2eb0a80b8c3d9b3c`.
  That APK, its release/tag and Main were not changed.

Install the delivered APK over standard Beta 0.6. No uninstall, data clear,
replacement signing key or backup/reinstall migration is needed. Raw CI APKs
remain disposable-signed and must not be presented as device updates.

PR #2 stays draft pending the focused [Thor test](TESTING-0.6.1.md), especially
existing data preservation and real gameplay/checkpoint restore. There is no
claim of phone testing from container qualification. Only documentation changes
follow the qualified implementation commit; these do not change APK payload.
