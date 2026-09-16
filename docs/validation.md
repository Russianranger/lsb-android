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
