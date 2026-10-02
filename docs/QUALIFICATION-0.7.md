# Beta 0.7 release qualification

The user confirmed improved server startup on AYN Thor and explicitly authorized making acceleration the default, moving the control into settings, merging the existing continuation into Main and publishing Beta 0.7. The existing PR #2 and branch `codex/beta-061-improvements` are retained. The archived Beta 0.6 tag/APK at `f3abab7` stays unchanged and remains an ancestor of the continuation.

## Final behavior

Server startup acceleration defaults ON. Its control and last-start receipt now live under **More → Advanced settings and repairs → Server runtime settings**; the normal Server page no longer shows them. A saved explicit OFF preference is preserved. The switch is disabled while the server or a maintenance operation is active, and client acceleration preferences remain independent.

The qualified preflight, actual launch-marker check, stdin gate, compatibility fallback and existing readiness/shutdown behavior are unchanged. Builds, SQL checkpoints, database recovery and backups retain compatibility mode. No server-source, script cache, zone loading, database format or native client changes accompany this release step. Other proposed boot optimizations remain postponed. AndroidManifest and Gradle both report 0.7.0/code 74; Gradle no longer defines the obsolete Restore Test flavor. No Restore Test APK is released.

## Thor acceptance and retained qualification

The accepted October 2 support log records server filter activation (`preflight_passed` and `launch_observed` true, 505 ms preflight) and map readiness at **95.96 seconds**. The existing deployment retains three accounts and three characters. The user confirmed improved startup. An earlier map timing came from a different deployment, so this is not an isolated percentage benchmark.

The full [Verify baseline PR run 36946975980](https://github.com/Russianranger/lsb-android/actions/runs/36946975980) passed at `cf49b516`: presentation, Android/core verification, real ARM64 server deployment, native Windows launcher, PlayOnline, Box64 runtime and FEX runtime. Server-source compilation also passed. A parallel push FEX job hit an observed D3D8 pixel-fixture timeout; the identical-head complete PR run passed that check. No runtime change or deadline extension was necessary. Earlier 0.6.1 Windows heartbeat and Box64 readback failures also passed unchanged on retry; see [0.6.1 evidence](QUALIFICATION-0.6.1.md).

## Beta 0.7 checks and APK provenance

[Verify baseline 37053403849](https://github.com/Russianranger/lsb-android/actions/runs/37053403849), implementation head `1095f8329c380d17223a1ce40b4a5e640647f5db`, passes API 35 compilation/dex/package verification, **146 Android tests**, **198 runtime unit tests**, **138 server unit tests**, native Windows launcher checks and real ARM64 server deployment/recovery. The new Android test verifies default-on settings, persistence of an OFF choice, server/busy locking and unchanged client preferences; the Server-page test verifies the control is absent. UI previews were inspected. Automatic [server-source compilation 37053403854](https://github.com/Russianranger/lsb-android/actions/runs/37053403854) passed. Native runtime/server payloads are unchanged from the previously fully qualified acceleration implementation.

The delivered original-signed `LSB-Android-Beta-0.7.apk` is **18581208 bytes**, package `io.github.russianranger.lsb`, Android version **0.7.0**, versionCode **74**.

- APK SHA-256: `8ab58ee5dd504a5cb63c106bab86a3841b33cb4821ef2faa4cf50c591101dbaa`.
- Original certificate SHA-256: `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`, matching actual published Beta 0.6 and the delivered 0.6.1/0.6.2 previews.
- Source artifact: `runtime-device-build` **11247303695** from the above PR run. Artifact ZIP SHA-256: `1f501a8f6db0e741be55eccba3578b1a6e0d735311810e607a675914c32761e8`.
- CI APK SHA-256: `9b48e75ce8aca730bd19cc76f83886e8998082b6150ac7804495158317ca6d71`. All **74 non-signature payload entries** exactly match the delivered APK. Packaged runtime/server scripts match the continuation source.
- APK v2/v3 signatures, original signer, ZIP integrity, package/version and **16 KiB alignment** pass. The private key remains outside GitHub; raw Actions APKs use disposable signing.

The final metadata/documentation commit does not change packaged Java, assets, manifest or native payloads. Install the published APK over the existing standard app without uninstalling or clearing storage. No client import, server rebuild, database preparation or redeployment is required. See [focused Thor upgrade checks](TESTING-0.7.md).
