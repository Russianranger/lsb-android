# 0.3.0 staged client initialization verification (2026-09-20)

The existing Thor runtime acceptance remains valid. This build adds real-client initialization, which still needs the user's proprietary files on the device; no proprietary DLLs are present in the development environment.

- Existing 90 JVM import/recovery checks and 15 new preparation checks pass. The new checks exercise separate client/prefix copies, preservation of original files/hives, non-traversed prefix links, no activation without a receipt, atomic selection/rollback, interrupted copying/store recreation, retry and candidate-only discard.
- Six Python contracts pass, covering request isolation, bounded logs, component inventory, working-copy path boundaries, linked-path rejection and malformed/wrong-architecture PE images.
- Android SDK 35 compilation, DEX packaging, zip alignment and APK v2/v3 signature verification pass locally. Application ID `io.github.russianranger.lsb`, version `0.3.0` / code 6, original signing certificate retained.
- Packaged `client-init.exe`, `client_setup.py` and `supervisor.py` match the built source/assets exactly. No synthetic client DLL or prerequisite fixture is packaged.
- Implementation checkpoint `d0f5acb62727cb82fbe3eb406e3f1c800a78a367`: [verification run 35532787286](https://github.com/Russianranger/lsb-android/actions/runs/35532787286) passed APK/core, native Windows helper and ARM64 runtime gates. The real x86 worker passes US/EU/JP synthetic COM initialization, deliberate registration failure, deliberate COM failure and prerequisite/retry with a full Windows exit code of 3010, under both ARM64 Wine/Box64 in Docker and the extracted rootfs through PRoot. Each case checks its receipt and clean shutdown. The existing render/input/audio/exit and Stop gates also pass; CI Vulkan uses Lavapipe, while the prior Thor acceptance supplies hardware-rendering evidence.
- Follow-up Android-only changes keep the display waiting through a long full copy and write an accurate fresh failure/cancellation state before Windows starts. These are compiled into the delivered APK; the native worker and Python backend remain the CI checkpoint's exact bytes.
- Delivered APK: `LSB-Android-0.3.0.apk`, **7,677,733 bytes**, SHA-256 `eab9c76be74a5f2d1c02d22822c90e1687ed3a00c5c04fde096395981894b3fd`. The update retains certificate `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e` and needs no uninstall or runtime download.

**Device boundary:** registration and real POL/FFXI COM construction, prerequisite installer behavior with actual vendor installers, original/full working-copy storage behavior and later login/world entry remain unverified until the Thor test. The native tests use synthetic DLLs with the corresponding interface identifiers, not the user's game. See [the preparation procedure](client-initialization-030.md).

---

# 0.2.0 runtime probe verification (2026-09-20)

Implementation commit `e07f29c32a99cec2d26a5f39e3866ccc9369a24b`. [Push verification run 35530166981](https://github.com/Russianranger/lsb-android/actions/runs/35530166981) passed APK/core checks, native Windows tests and the ARM64 runtime job. The release publication job follows those gates.

- 90 JVM import/recovery checks pass; three runtime contract tests check request isolation, bounded logging and missing component inventory rejection.
- The existing native Windows helper checks pass with synthetic DLLs.
- Real x86 Windows probe runs under ARM64 Wine/Box64: registry round-trip, in-process COM registration/activation, rendered D3D8 triangle pixels, RFB keyboard and mouse input, nonzero audio PCM and a clean exit. Both fresh and reused prefixes pass.
- Software rendering and DXVK 2.5.3 D3D8+D3D9 pass. CI's Vulkan adapter is explicitly Lavapipe; this is not evidence of a physical Adreno/Turnip run.
- The actual APK tar extractor installs the pinned rootfs on the ARM64 host, and the patched PRoot route passes the software probe twice plus explicit stop. The later Thor report below supplies physical Android/Adreno evidence.
- An initial DXVK failure showed black pixels despite successful Present calls. Changing the test surface from a child STATIC to an owned top-level window fixed the presentation path. The pixel assertion remains mandatory.
- Device APK: `LSB-Android-0.2.0.apk`, **7,636,621 bytes**, SHA-256 `cb2528fa59813fe7730b245f446af0b2b42d22ba583239b467acee334d47f0b9`. Application ID `io.github.russianranger.lsb`, version code 5, minSdk 26, target/compileSdk 35. apksigner verifies v2/v3 with the original certificate `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
- APK inspection verifies ARM64 PRoot executables, x86 probe/COM/DXVK DLLs, the packaged supervisor source and component digests. Runtime files remain separate from managed client data.

## Thor result supplied after delivery

`lsb-support(3).zip` and `Screenshot_20260920-140524.png` confirm 0.2.0 execution on AYN Thor / Android 13. The retained run reports actual Turnip Adreno 740 / Mesa 26.0.0 hardware, DXVK 2.5.3 D3D8 mode, 2,000 presented frames, passing 32-bit registry and COM checks, HRESULT 0, 9 keyboard events and 8 pointer/button events. The user saw the triangle and heard sound; audio logs contain four nonzero PCM streams. The app's imported game files were not mounted.

Requested Stop completed (`phase=stopped`, app exit 0, `alive=false`, display/audio closed). That initial bundle did not establish normal probe exit; the later lifecycle bundle below provides both normal-exit receipts. See [HANDOFF.md](HANDOFF.md) for evidence and the next implementation milestone.

The visible counter overlap is a probe paint defect: transparent drawing does not erase previous values. The 25 FPS screenshot matches the deliberate 40 ms probe timer and is not an FFXI performance result. First-prefix Wine warnings did not block this probe's registry/COM/render/audio results; they do not establish that proprietary initialization will succeed.

## Normal exit/relaunch: milestone 1 accepted

The follow-up `lsb-support (1).zip` retains two distinct, consecutive completed sessions (2026-09-20 19:12:35–19:14:37 and 19:14:52–19:15:28 UTC). Both have `probe_exit=0`, `automatic_checks_passed=true`, registry/COM true and HRESULT 0; D3D8 frame totals are 116 and 106. Both verify Adreno 740 / Turnip 26 hardware. The final app state is stopped, with audio/display closed. The user explicitly confirms exit and restart worked. Together with the earlier input/audio/rendering/Stop results, this completes the independent runtime milestone on the Thor.

The second Wine run has an RpcSs service startup error, but the test's in-process COM activation and normal exit still pass. Carry that warning into proprietary COM/registration testing rather than claiming all COM service behavior is verified. Zero input counts in these lifecycle-only sessions do not supersede the earlier successful input test.

**Pending subsequent work:** proprietary POL/FFXI DLL registration, initialization and game execution; foreground/background behavior, sustained stability and gameplay controls. Preserve the tested prefix, imported data and backup. No repeated open-probe test, GameHub transfer or repeated import is needed.

---

# GameHub executable entry point 0.1.3 validation

- Adds a Windows GUI executable because GameHub could not open the exported CMD recipes in the user's setup.
- 90 JVM checks cover imports, selections, backups and both full/launcher-only export shapes, including exact native INI root/core/loader fields and missing-helper rejection.
- The x86 Windows GUI helper is cross-compiled from source. PE import inspection shows only ADVAPI32, GDI32, KERNEL32, msvcrt, ole32, SHELL32 and USER32.
- Android build 0.1.3 / code 4 packages the compiled helper as an asset and retains the original device certificate.
- A separate Windows CI test uses stub DLLs and an isolated HKCU registry subtree. This checks native registration and process behavior without proprietary game files. Consult the corresponding workflow result for its execution status.
- Actual GameHub GUI use, proprietary DLL registration, xiloader login and game rendering remain unverified. The launcher is a compatibility test entry point, not an embedded Android Wine runtime.

# PlayOnline layout fix 0.1.2 validation

The user's 0.1.1 Thor report confirms import of 66,322 files (15,176,011,583 bytes), xiloader import, backup export and backup restore. There are no new failed operations in that run. It also shows the selected DLL at `PlayOnlineViewer/patchfiles/PlayOnlineViewer/viewer/com/polcore.dll`; the old preview incorrectly used that `com` directory for registry InstallFolder 1000. `patch.ver` was unreadable/absent, so no client version is asserted.

- 85 JVM checks pass, including a synthetic reproduction of the reported patch-cache and viewer/com layout, switching an existing selection without re-import, correct registry root vs COM DLL paths, updater and working directory, `usr` preservation, backup restore, missing `pol.exe`, and an archive-root installation.
- Android SDK 35 compilation, DEX packaging, zip alignment and APK signature verification pass for 0.1.2 / version code 3.
- The APK retains the original application ID and certificate below for an in-place update.
- The host fixture models the reported path; it is not the user's full game archive. The alternative installed DLL path was not present in the 0.1.1 support report. Updated support exports include all candidates.
- Device testing of 0.1.2 and Wine registration/launch are still required. Baseline import/backup/restore have now been observed in the user's report; no integrated gameplay is claimed.

# Import selection fix 0.1.1 validation

- User screenshot and support log report `Expected one PlayOnline polcore.dll or polcoreeu.dll; found 2` after extraction in 0.1.0. The report does not contain the actual candidate paths, so both regional DLLs in one folder and duplicate DLLs in separate folders are covered.
- 69 dependency-free JVM checks pass. The new cases cover pending selections, store recreation/recovery, cancellation/retry without extraction, invalid selection/header rejection, region matching, selection-aware repair/export, backup/restore, rollback, personal settings preservation, alternate viewer loaders and legacy configuration loading.
- SDK 35 Java compilation and DEX packaging completed. APK version 0.1.1 / code 2 retains application ID `io.github.russianranger.lsb`, minSdk 26 and targetSdk 35.
- The signed APK uses the original device certificate below, permitting an in-place update from the delivered 0.1.0 APK.
- No 0.1.1 installation, Android UI/document-provider test, or real-client import was possible here. Tests use synthetic PE fixtures; the attached support ZIP does not include the game files.

# Baseline 0.1.0 validation

## Completed locally

- 45 dependency-free JVM checks in `tests/CoreTest.java` passed, including a reproduced/fixed interrupted-rollback recovery case.
- All Android Java sources compiled against the Android SDK platform JAR with Java 8 bytecode targeting minSdk 26.
- D8 generated the actual APK's DEX; aapt2 linked the manifest and resources.
- zipalign completed and apksigner verified APK signature schemes v2 and v3.
- APK metadata was checked for application ID `io.github.russianranger.lsb`, version `0.1.0` / code 1, minSdk 26, targetSdk 35, launchable Activity and foreground dataSync service.
- No proprietary FFXI, PlayOnline, Microsoft runtime, GameHub, Wine or FEX binaries are packaged.

Device APK signing certificate SHA-256:

```text
f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e
```

Keep this certificate for subsequent device builds. The private signing checkpoint is saved separately as `LSB-Android-preview-signing.zip`; it is excluded from git. CI uses a disposable certificate for compilation checks and does not publish that APK as a device update.

## Not yet validated

- Installation and Activity layout on the physical Thor.
- Real Android document-provider imports/exports and background/cancellation behavior.
- A full FFXI installation and a full-size backup/restore on the device; host fixtures are intentionally small synthetic PE files.
- Repair CMD execution, COM initialization, xiloader login and official PlayOnline updater behavior in Wine.
- In-app FFXI rendering/input/audio or standalone server compilation/execution. Those runtimes are not part of this milestone.

Do not describe this build as a working integrated game client or server. File preparation and a TCP-open result are separate from actual runtime readiness.
