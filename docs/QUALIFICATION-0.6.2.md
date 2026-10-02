# Server acceleration qualification and APK delivery

The existing 0.6.1 implementation is retained on `codex/beta-061-improvements`, draft PR #2. Server acceleration was added in `671fa3565053a72242904fee50b74c0da4e3e414`. Subsequent commits through `cf49b516fc127e81682eb37a1e02dc917ee3bd1e` correct only the new host test fixture; app and packaged runtime/server code are identical. Main remains verified Beta 0.6 at `f3abab7ab3c1de6fb1bccea02a1d0cc7ff7fc630`.

## Behavior and scope

- Default-on **Server runtime acceleration** is separate from client settings. Only the `start` operation can enable it.
- The credential-free preflight tests file IO/mmap, file locking/fsync, Unix and ephemeral loopback TCP sockets, threads/fork and harmless native execution, including `mariadbd --no-defaults --version`. It never opens a database generation.
- Both preflight and actual launch require the exact activation marker from the pinned, patched PRoot. A closed stdin gate prevents `manager.py` from running until the Android parent confirms the actual launch marker. Failed checks fall back before database access; cleanup failure blocks that launch. Probe/gate cleanup matches a unique environment token and the app UID, avoiding other server/client processes.
- After gate release, existing readiness, database and shutdown paths run unchanged. There is no automatic server restart after database access begins. The switch offers compatibility mode for subsequent troubleshooting.
- Builds, backups, checkpoint create/restore, retention and recovery retain compatibility mode. No server C/C++ source, script cache, deferred zones, mesh-validation cache or database format changes are included.
- Support ZIPs include `server/proot-acceleration.json`, separately from client acceleration receipts. It records requested mode, preflight duration, observed launch mode and fixed fallback reasons without raw probe output or credentials.

## Checks

- Local core/session recovery, 198 runtime unit tests and 138 server unit tests pass.
- API 35 app compilation and all 145 Android tests pass locally and in the latest [Verify baseline 36946972226](https://github.com/Russianranger/lsb-android/actions/runs/36946972226), verify job `110652835160`. Tests cover preflight and launch fallback before releasing stdin, cancellation, maintenance isolation, exact marker parsing, the independent Server switch and retained client preferences.
- The real ARM64 fixture compiles the exact pinned PRoot with the app's acceleration and SysV IPC patches. Its host build reads current glibc signal declarations before an old compatibility macro; the packaged Android PRoot is unchanged.
- Native filtering, credential-free preflight and the unreleased gate preserve a stopped physical database byte-for-byte. Real MariaDB and four native jemalloc/BFD-linked server services reach timed readiness and shut down cleanly under observed filtering, as logged in job `110650240582` of [36946665698](https://github.com/Russianranger/lsb-android/actions/runs/36946665698). That run then stopped at the new test's leftover stop request before its post-shutdown query. The test clears that request in `cf49b516`; it does not change application shutdown or database code.
- The complete latest [ARM64 server-deployment job 110651210399](https://github.com/Russianranger/lsb-android/actions/runs/36946972226/job/110651210399) passes at `cf49b516`. It verifies retained account/character counts and password hashes after accelerated startup, alongside real checkpoint restore/retention, full-session recovery and the existing deployment/profile-service checks. The wider unchanged client qualification jobs are still running; no claim of a complete green fresh workflow is made.
- Full server-source/profile compilation also passed automatically in [36946034183](https://github.com/Russianranger/lsb-android/actions/runs/36946034183), [36946291041](https://github.com/Russianranger/lsb-android/actions/runs/36946291041) and [36946665466](https://github.com/Russianranger/lsb-android/actions/runs/36946665466). No additional source-build rerun was requested.
- The original 0.6.1 failed Windows heartbeat and Box64 readback jobs both passed unchanged on retry; [36905467621](https://github.com/Russianranger/lsb-android/actions/runs/36905467621) is successful. Earlier full qualification remains documented in [QUALIFICATION-0.6.1.md](QUALIFICATION-0.6.1.md).

## Original-signed device APK

- `LSB-Android-0.6.2.apk`: package `io.github.russianranger.lsb`, version 0.6.2, versionCode 73, 18,585,304 bytes.
- Delivered APK SHA-256: `156aa8f4612390a6c7bcca07081c24941eb5e8db26589cfa2d5fdff986bf75f5`.
- Original certificate SHA-256: `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`, matching the actual published Beta 0.6 and delivered 0.6.1. The private key stays outside GitHub.
- Source build: latest run `36946972226` at `cf49b516`, `runtime-device-build` artifact `11203265136`. Artifact ZIP SHA-256: `46568fc8120ca95d5d27a0eba74c7cb8b2f26ffde99aefbd58c89aaa811ffacd`. CI APK SHA-256: `d5c296c6a72b02980c7c182c6f000861d818c1f782a79809c92a730248748593`.
- All 74 non-signature payload entries exactly match the latest tested CI APK and the original implementation artifact `11201739809`; test-fixture fixes do not alter the app payload. Packaged supervisor, preflight and launch-gate scripts match the current branch. APK v2/v3 signatures, ZIP integrity, 16 KiB alignment and package/version checks pass.

Install the delivered original-signed APK over the existing standard Beta 0.6/0.6.1 app without uninstalling or clearing storage. Raw CI APKs have disposable signing and cannot update that app. No server rebuild, database import or redeployment is needed. Main and the original release remain untouched.

The full 300-zone Thor startup speedup is not established by synthetic host fixtures. Use the [focused OFF/ON timing and existing-character login test](TESTING-0.6.2.md). Keep PR #2 draft until those device results are available; the other proposed boot optimizations remain postponed.
