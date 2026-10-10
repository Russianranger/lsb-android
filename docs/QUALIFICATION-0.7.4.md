# 0.7.4 filtered-startup candidate qualification

The user requires preserving syscall filtering because it makes the Thor client
stable. The fix combines the selected DXVK 2.7.1 and two-worker graphics checks
into one verified process. It does not establish the exact underlying driver
or thread cause of the intermittent hang.

## Source and checks

- Base: `2846bbf8c77d08ef6b46580e3a40dd676d575062`, the complete 0.7.3
  storage/verified-update-cleanup continuation. Main and existing draft PR #3
  are preserved.
- Implementation local commit: `1b066b828bfda2e81772a761447c8da924aa738d` on
  `codex/filtered-startup-074`. Later changes add reproducible packaging,
  qualification evidence and stronger synthetic test privacy assertions.
- All 204 runtime unit tests pass, including 43 supervisor tests. These include
  exact profile eligibility, real version/config/worker/frame proof, rejection
  of incomplete or changed receipts, unchanged ordinary fallback, and bounded
  cleanup/cancellation.
- Core import, prepared-client, archive and transaction recovery checks pass.
  Python compilation, focused shell syntax and whitespace checks pass.
- A separate ARM64 CI job is implemented to test two actual production gameplay
  launches under observed filtered PRoot/FEX, with inherited DXVK 2.7.1/two-worker
  configuration and 144 independent synthetic texture pixels per launch.
  **It has not run.** Automatic approval review rejected GitHub publication for
  missing explicit authorization. No remote write was retried.
- No fresh phone gameplay success is claimed. Follow [Thor tests](TESTING-0.7.4.md).

## Reproducible original-signed APK

Java/resources/native code are unchanged. The local candidate is built with
`scripts/build-filtered-startup.py` from the exact previously passed Android
verify/build artifact, replacing only the runtime supervisor and manifest
version metadata. This avoids any new compiler/runtime dependencies and
preserves all 72 other payload entries byte-for-byte.

- Source workflow run: `37168398703`, Android verify/build job `111336169685`.
  That job passed; the complete historical run had an unrelated Box64 runtime
  qualification failure, so it is not described as all-green qualification.
- Source artifact `11290746491` (`runtime-device-build`), ZIP SHA-256:
  `cb92db1a49293a0b427430024400ba426a8bc1d8088ec5a92bfb71980e4da8af`.
- Source CI APK SHA-256:
  `42cbdb8687dedfe8dd560b3ea2ce3ea13ba689667b5f2f9527215531a2430940`.
- Packaged supervisor SHA-256:
  `829da5057c5013931569df1da34d5ef18d0c6f9eb846c78b3f1d7a7f91d00970`.
- Candidate: `LSB-Android-0.7.4.apk`, 18,597,592 bytes,
  `io.github.russianranger.lsb`, versionCode 78.
- Candidate SHA-256:
  `7c3f947f7e207d8ef263dfeb39ec6f8b79c048b407970c698da0c9afa0d41e34`.
- Original certificate SHA-256:
  `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.

Verified APK v2/v3 signatures, package/version identity, 16 KiB native alignment,
ZIP CRCs, native bundle hashes, exact packaged supervisor bytes and manifest
tree equality apart from the two version fields. Every remaining non-signature
entry matches the pinned CI APK. Android version display reads the manifest;
runtime scripts refresh from APK assets on each launch. No new key, uninstall,
runtime reinstall, client preparation or server/database replacement is needed.

The signing checkpoint stays private and outside Git. Raw CI APKs have a
disposable certificate and cannot update the installed standard app.
