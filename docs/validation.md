# Silent startup diagnostics: 0.4.5 (2026-09-21)

**Device evidence.** The attached `lsb-support (1)(1).zip`, SHA-256 `3bdcbd1030a7201edcd75312736bc7432df8abf1a4fec72850916e717f3fea95`, records session `aa460280-6a87-4994-a0cb-aeac44e1b07c` on 0.4.4. Windowed 1280×720 applied successfully; original display backup is ready. Login/server events progressed, all 20 dependencies loaded, and POL/FFXI/FFXiMain/D3D8/D3D9 were observed. Child and bridge exit 0 followed after 15,111 ms; no game window or standard dialog was observed in 35 samples. No actual exception or GameStart HRESULT was captured. This confirms the display-only fix was insufficient, not that a specific graphics component caused the failure.

**Implementation.** `6563b1e351ef2a9debd32e2eb1684005a6dbd42f` closes the launch logging gap with the bounded metadata parser described in [startup-diagnostics-045.md](startup-diagnostics-045.md). It enables Wine and DXVK diagnostic output only on the private launch pipe, disables raw DXVK log files, correlates numeric Wine IDs with the child's PID, and keeps recoverable warnings/first-chance exceptions separate from fatal exit classification. The saved client generation, loader, display policy, runtime and credential transport remain in use. This build is diagnostic; proprietary game startup is not claimed fixed.

**Validation passed.** Final implementation `6563b1e351ef2a9debd32e2eb1684005a6dbd42f` passes all three gates in [run 35624389711](https://github.com/Russianranger/lsb-android/actions/runs/35624389711): APK build, 116 JVM checks, 21 Python contracts, 36 native Windows checks, and the ARM64 runtime suite. All 18 launch scenarios pass separately under Wine/Box64 and PRoot. The new scenarios capture a real missing-DLL warning with status 0xc0000135 and the correct child PID, preserve a synthetic DXVK diagnostic without turning a recoverable warning into a fatal exit, and capture a real unhandled Windows divide-by-zero with exception/child-exit 0xc0000094. Immediate Stop retains metadata. Existing login rejection, early exit, literal credential arguments, diagnostic privacy, display preservation/restoration, registration/recovery, dependency lifetime and open rendering gates pass. The rendering gate uses software/Lavapipe in CI; actual FFXI rendering and the cause of its early return remain device work.

**Validation corrections.** Initial runs caught three issues before delivery: a strict test rejected ERROR_NO_MORE_FILES (18) from a final module snapshot after successful window observation and exit; Wine's internal `load_dll` warning needed a specific category; and immediate Stop could precede the first metadata snapshot. The test now permits the observed teardown result while retaining it in the receipt, the parser recognizes the source-grounded function name, and the launch finalizer preserves metadata on cancellation. A deterministic early-Stop regression accompanies the final fix. The final run above passes all cases.

**Device APK.** `LSB-Android-0.4.5.apk`, 7,723,027 bytes; SHA-256 `7893b550d952e70ee7e975b5cd59319a6661bebe1ae51a6266c55c4759844413`. Application ID `io.github.russianranger.lsb`, version code 12, min API 26, target/compile API 35, arm64. Original signing certificate SHA-256 `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`. Packaged `client-launch.exe` SHA-256 `906de596c0a670708900c604f9cee574fe1b2ff6768d3cd154420e2c8959912a`; `client_launch.py` `fc11f09964a859ba368ca494cc985ff34232c94df0fbd5e4b54f83e057ddcb9f`; `supervisor.py` `11dff2342b2d2a855ce1d2585bab9d3b33cbcb5cc5caee78c9314f8012d3a01c`; `startup_diagnostics.py` `7c9f422a0721bb7efe1c887d952746af728c917cfab20dbf07de7f0167cf576c`.

**Next device action.** Install in place, keep the current settings, start the existing Termux server and launch once. Export a fresh support ZIP from Diagnostics afterward. No re-import, prerequisite installation, new preparation, runtime download or GameHub transfer is required. The new report may identify a DLL/COM/SEH/graphics failure; if GameStart still returns without any diagnostic, source-level loader instrumentation remains the next investigation.

---

# Explicit windowed launch: 0.4.4 (2026-09-21)

**Device evidence.** `lsb-support(5).zip`, SHA-256 `871b057ff6b647abbea1f31d45857bb226d9321d0126cebf869e564d4e6f79e3`, records 0.4.3 session `be51e556-b2a6-45a9-959c-4353bb3f8113` on the Thor. All 20 import loads pass. Login succeeds and the loader closes normally after 15,881 ms; 36 observations record `polcore.dll`, `FFXi.dll`, `FFXiMain.dll`, `d3d8.dll` and `d3d9.dll`. Window/module observation error codes are 0. No visible FFXI window or standard dialog is observed, and no POL/FFXI COM failure is reported. Loaded modules do not prove a D3D device, game frame or world entry; the separate Vulkan probe's three presented frames are not game frames.

The effective display settings are:

| Value | Meaning | Device value |
| --- | --- | ---: |
| 0001 | Overlay width | 640 |
| 0002 | Overlay height | 480 |
| 0003 | Background width | 512 |
| 0004 | Background height | 512 |
| 0034 | Display mode | 0 (fullscreen) |

Every `added` flag is false. The prior candidate did not test windowed mode: registration or another earlier step already populated these defaults, and 0.4.3 preserved them. The exact failing game operation remains unknown; no crash or `GameStart` HRESULT was captured. [The fixed registry mappings](https://github.com/Windower/Fenestra/blob/e5bfb6442f49bdb4d31859bff6218bbeb1bea620/core/src/hooks/advapi32.cpp) support a controlled windowed test.

**Changes.** The Client card now offers Windowed 1280×720 (default), Keep current display settings, and Restore saved original display settings. The selection is carried as a bounded non-secret launch option; credentials retain their existing one-use pipe transport. Windowed mode explicitly sets only the five values above to 1280/720/1280/720/1, even when defaults exist. Keep current makes no value changes, including absent values. Restore puts back the values or absence saved before the first windowed application in this prefix/region.

Before the first override, the helper reads and validates all five original DWORDs, saves them under its regional `Software\LSBAndroid\DisplayBackup` key in the same prepared prefix, verifies them, and flushes values and a completion marker before changing the game settings. A later launch never overwrites a completed original backup. Incomplete initial saves are retried; unexpected types or a malformed completion marker fail instead of overwriting data. A detected write/readback failure rolls back changed values and records any rollback error. If the app is interrupted partway through application, the completed original backup remains available for the Restore selection. This does not claim power-loss durability beyond Wine/Android storage guarantees.

The launch receipt records selected policy, backup readiness, prior/current values and presence, change flags and rollback error. Imported files, loader, active generation and renderer are unchanged. No fresh import, preparation copy, dependency installation, GameHub transfer or external CMD is needed. The current prepared prefix intentionally receives the selected display settings and its small registry backup.

**Validation passed.** Implementation `b40c7e6885ca2cb34a0045793f722a2495516842` passes all three gates in [run 35618686567](https://github.com/Russianranger/lsb-android/actions/runs/35618686567): APK build, 116 JVM checks, 16 Python contracts, 36 native Windows checks and the ARM64 runtime suite. All 16 launch scenarios pass separately under Wine/Box64 and PRoot, including explicit windowed override of the seeded 640×480 fullscreen values, preserved custom values, reapplication without replacing the original backup, and restored originals. The JP/US/EU registry fixtures pass forced windowing, preservation, restoration, missing-value restoration, interrupted-backup retry, failed-write rollback and partial-application recovery. Invalid registry string bytes are excluded from numeric diagnostics. Existing startup, privacy, failure, dependency and rendering checks also pass. These are synthetic tests; actual FFXI rendering remains a device gate.

**Device APK.** `LSB-Android-0.4.4.apk`, 7,718,848 bytes, SHA-256 `f6b03de94325cf438b7137a7e5d24541e20a19e7b13dda8be0bd77e9eb837113`; application ID `io.github.russianranger.lsb`, version code 11, min API 26, target/compile API 35. The final complete ZIP and v2/v3 signatures were verified using original certificate `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`. Packaged helper SHA-256 `9a08f0a828a8c18fc965178a869585169f81198e1d723dc48c60e7b1916b79af`; `client_launch.py` SHA-256 `6a922a1258d6b2896e314017f87dcb438291a5105330b6380ef90e0f4cbf8e8e`; `supervisor.py` SHA-256 `83350bb1802e6b9220512cb5862ba4b8ff69548b4221fba66916be7090ec5857`.

**Device procedure.** Install 0.4.4 over the current app. In Client → Play FINAL FANTASY XI, leave **FFXI display setting → Windowed 1280×720 (recommended)** selected. Start the existing Termux server and launch with the same account/server. Report the first visible game screen, or send the new Diagnostics ZIP if it exits again. To undo this profile later, choose Restore saved original display settings and launch; the original values are applied before the loader starts.

---

# Post-login exit: 0.4.3 (2026-09-21)

**Device evidence.** The latest 0.4.2 report `lsb-support (3)(1).zip`, SHA-256 `9011837bb7be404ce1e60e4bfbe927034c82d6c57a1d135c957d7ba93345e364`, records session `56ca8e82-a27b-4107-8971-c9b9989672f7` on AYN Thor. All 20 dependency loads pass with checker exit 0. Events record autologin, successful login and server connection. The loader and bridge then return 0 after approximately 35.5 seconds; no game-start/window observation or crash stack is available. User confirmation closes the earlier authentication hurdle, but character selection/world entry are still unverified.

**Source findings.** [xiloader v2.0 main.cpp](https://github.com/LandSandBoat/xiloader/blob/9679ac443f755f9e11cb672e901aee17c936b453/src/main.cpp) invokes FFXI through in-process COM and `GameStart`; it can report POL/FFXI COM creation failure and still return 0. It does not print the game-start message the old parser expected. There is no evidence of a separate game process being killed on a successful handoff. The exact imported loader has not been rebuilt or replaced; upstream source explains possible paths, not the actual failing instruction.

**Changes.**

- Before launching, initialize only absent 32-bit HKLM regional FFXI display values: `0001/0002` (overlay width/height) and `0003/0004` (background width/height) to 1280/720; `0034` (mode) to 1, windowed. Existing DWORDs remain intact. All reads precede writes; incompatible types fail without changing values, and a write/readback failure rolls back values added by that attempt. Report every final value and whether it was added. No imported file, client DLL, loader, renderer or source prefix is replaced. This closes a preparation omission but is a **candidate fix**, not a proven cause of the reported exit.
- The display key and numeric mappings are corroborated by [Windower's registry implementation](https://github.com/Windower/Fenestra/blob/e5bfb6442f49bdb4d31859bff6218bbeb1bea620/core/src/hooks/advapi32.cpp). No other registry keys are copied from a working container.
- Observe the directly launched process every 250 ms for six fixed DLL names, visible `FFXiClass` windows, and standard dialogs. Record cumulative booleans, sample count, elapsed time and Windows observation error codes. The class name is corroborated by [Windower's window hook](https://github.com/Windower/Fenestra/blob/e5bfb6442f49bdb4d31859bff6218bbeb1bea620/core/src/hooks/user32.cpp). Polling can miss a transient module/window; absence means **not observed**, not proof it never existed. A visible window does not establish rendered frames, character selection or world entry.
- Recognize fixed POL/FFXI COM, loader-hook and profile-port initialization failures without retaining arbitrary child output. A reported login followed by exit 0 without an observed FFXI window now produces an actionable Android error. Module/window status helps identify the remaining stage instead of leaving a stale login-wait message.
- Passwords remain pipe-only. No window title, arbitrary module name/path, command line or registry string is logged. Tests deliberately put a password in a window title to check the boundary.

**Validation passed.** Implementation `483c69087d0e23570fc55827e60564a08b9162ae` passes all three gates in [run 35591567783](https://github.com/Russianranger/lsb-android/actions/runs/35591567783): Android build, 116 JVM checks, 15 Python contracts, 36 native Windows checks, and the ARM64 runtime suite. All 14 launch scenarios pass separately in Wine/Box64 and PRoot, including observed game window/main module, normal exit/relaunch, prior failure/Stop paths, POL/FFXI initialization failures after login, and an otherwise silent post-login exit 0. The registry fixture passes defaults, user-value preservation, invalid-type handling and regional paths for US/EU/JP. Existing dependency-lifetime, registration and rendering gates also pass. The device APK contains the tested implementation and is signed with the original certificate. Actual proprietary `GameStart`, a settings correction on the Thor and world entry require the device retry; synthetic tests cannot prove them.

**Device APK.** `LSB-Android-0.4.3.apk`, 7,718,848 bytes, SHA-256 `b7a96fc46560b710412dc484470551ed62af618f04650ccc1a2bfde62cabb2ea`; application ID `io.github.russianranger.lsb`, version code 10, minimum API 26, target/compile API 35. Verified v2/v3 signatures use the original certificate `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`. Packaged helper SHA-256 `75c416503524fd5275a63412dae7b583437710016376d8ee106bd483bcafcce5`; backend `client_launch.py` SHA-256 `aae135e59242f0abe23d311f552e2bae98f8fe3ce349e59c8c46f2d2a01510d3` and `supervisor.py` SHA-256 `888da90809bf8d488631d2f455076a8eabe6f7a0485849ff3056e6cbff009d02`.

**Device procedure.** Install the original-certificate 0.4.3 APK over the current app, start the existing Termux server, and use Client → Launch FFXI with the same account/server. Existing preparation remains active. Report whether a title/character screen appears; if it closes again, export the new Diagnostics ZIP. Do not uninstall, import or prepare again.

---

# 0.4.2: expose login failure instead of a running black screen (2026-09-21)

The 0.4.1 Thor report `lsb-support (2)(1).zip` (SHA-256 `7a187ac4046decfc92a6a64e5b22b3342de21d8181f6e24c06d7703584197e04`) and black-screen screenshot show the next boundary. Session `4613eb66-0fc3-47e6-b55e-fe97003ec8a5` ran **01:17:55–01:24:04 UTC**, ending by Stop. The corrected dependency checker loaded all 20 DLLs and exited **0** with `check_policy=load_only`. Windows process creation succeeded. Thus the 0.4.1 preflight fix is device-accepted.

`loader-events.log` contains only **login_rejected**. The old filter collapsed multiple xiloader failure messages into that one event, and the launch loop never consumed it. The app displayed `client running` solely because the Windows loader remained alive. There is no successful login/game-start event or game-rendering evidence in this attempt. The precise rejection reason cannot be recovered from this filtered bundle; do not assert that the password was wrong or that this was a graphics failure. The source's interactive retry loop explains a possible live-but-idle loader, but its exact menu state is not captured.

0.4.2 consumes credential-safe events while the loader runs and after exit. Recognized failures stop the owned Wine session and show a native Android error dialog with a Back to Client action. Invalid credentials, an existing login, version mismatch, connection failures and invalid/server-error replies have distinct fixed messages and diagnostic codes. Unknown rejection remains explicitly generic. A reported failure plus Windows exit 0 still fails. The app reports waiting for login or a received login/game-start message instead of equating process existence with game readiness. After 60 seconds with no recognized result it asks the user to Stop/export; it does not kill potentially slow or unrecognized successful launches on a speculative timeout.

Filtering now operates on bounded complete lines (up to 4096 bytes), strips ANSI sequences and ASCII UTF-16 NULs, recognizes the source's timestamp prefix, and emits only allowlisted constant event names. Specific rejection reasons cannot be lost when the generic prefix and detail arrive in separate pipe chunks. Echoed account/password substrings and oversized unknown lines cannot become events. `already logged in` is no longer mistaken for successful login. No raw console text, account names, passwords or arbitrary server error strings are persisted. Unknown server errors require the Termux server log for detail.

The historical v2.0.0 timestamp and rejection wording were also checked against [source at 9679ac4](https://github.com/LandSandBoat/xiloader/tree/9679ac443f755f9e11cb672e901aee17c936b453). Message vocabulary and the retry loop were checked against the read-only [xiloader source at 370a1a1](https://github.com/LandSandBoat/xiloader/blob/370a1a11e4d3c5b58b793bd83096de34d5942ed6/src/command_handler.h) and `network.cpp`/`main.cpp`. This newer source is not proof of every message in the user's imported binary, which retains hash `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`.

Fourteen Python contracts pass locally. ARM64/PRoot regression cases add hung-loader failures for invalid credentials, existing login, version mismatch and connection failure, plus rejection followed by exit 0. They check automatic shutdown, specific reason retention and credential-free logs. All three gates pass for implementation `90f812a836b0da8a185440b6c25a2eada1cdf762` in [run 35551248809](https://github.com/Russianranger/lsb-android/actions/runs/35551248809). Each of the five new rejection cases passes under both ARM64 Wine/Box64 and PRoot: invalid credentials, account already active, version mismatch (generic prefix/detail split across writes), connection failure, and reported rejection followed by Windows exit 0. Every case preserves the specific reason, stops the owned display/session and excludes echoed credentials. The six prior launch cases, seven dependency lifetime cases, initialization and render/input/audio checks also pass. CI verifies 116 JVM checks and 14 Python contracts. Fixtures do not prove the actual server account/protocol or Android dialog interaction; those remain device observations.

**Device APK:** `LSB-Android-0.4.2.apk`, versionCode 9, **7,710,656 bytes**, SHA-256 `da5e0e65eb3d639a5a10a9899fab6a04d8f89644705dd892f9b949972fce57ae`. V2/v3 signatures verify with the original certificate `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`. The packaged `client_launch.py` and `supervisor.py` match source/build bytes. The native client-launch helper retains its tested 0.4.1 hash `f333e1a21301b08056a0e9962a9b5ed35baad56e13b27fd360e142d7613c6031`. Test-only loaders/DLLs are excluded.

**Device retry:** install 0.4.2 in place, keep Turnip 26 and the accepted preparation, start the existing Termux server, and launch with the existing server account. Read the new failure dialog if one appears and export Diagnostics. This build makes the login blockage visible and recoverable; it does not change server credentials, accounts, protocol requirements or the imported loader. Actual authentication and world entry remain pending. No client re-import or preparation is requested.

---

# 0.4.1: dependency checker lifetime fix (2026-09-21)

The user reported immediate exit and supplied `lsb-support(4).zip` (SHA-256 `e42eba5c0ed242258fc664b5b0038751c3669e79e48048d43e94b76690420cfa`). Both retained 0.4.0 attempts fail **before xiloader starts**:

| Session | UTC interval | Result |
| --- | --- | --- |
| `01a99a45-4c54-4bba-8957-7aabc988877c` | 00:15:48–00:16:16 | Load checker exit -11 |
| `ed7720c6-4bc8-438e-b073-2feaf8b7da33` | 00:17:53–00:18:10 | Load checker exit -11 |

All 20 DLL rows in both completed native receipts have `ok=true`, Win32 error 0 and resolved system paths, including MSVCP140, VCRUNTIME140 and UCRT. Python sees SIGSEGV (-11) after the checker wrote its receipt. No loader process receipt or authentication event exists. Turnip 26 / Adreno 740 presentation succeeds first. The accepted client/prefix generation remains `768f3a6d-8dfb-462f-8b9d-46cdd7101505`; registration is not rerun.

**Diagnosis and scope:** the receipt establishes that DLL loading and result publication completed; the crash follows in helper shutdown. The old helper repeatedly called FreeLibrary and then normal CRT/process exit. Its unload/reload and detach lifetime is a likely trigger in Wine/Box64. The report has no native stack identifying a particular DLL or proving the precise engine defect. GnuTLS missing-wrapper warnings alone are not evidence of a missing loader import.

**Fix:** keep all loaded import modules referenced, close and atomically publish the load result, then use TerminateProcess on the disposable checker itself. This avoids CRT/DLL detach callbacks in a process that owns no game session. The real loader, game and installer retain their existing supervision/lifetime. The supervisor still requires exit 0, the new load-only policy and a complete ordered list of successful DLL results. A success-looking receipt never overrides a signal or other nonzero exit. This is a loadability check, not DLL export/API compatibility, login or world-entry proof.

The isolated worker policy follows Microsoft's documented [process termination behavior](https://learn.microsoft.com/en-us/windows/win32/procthread/terminating-a-process) and [DLL lifetime constraints](https://learn.microsoft.com/en-us/windows/win32/dlls/dynamic-link-library-best-practices). Only the disposable checker skips DLL cleanup; client state is not saved this way.

**Regression coverage:** reproduce the device's exact 20-import order twice, prove a test DLL fails on both explicit unload and ordinary process exit, verify that the corrected checker loads it without invoking detach, keep missing DLL error 126, and keep initialization crashes as failed/no-completed-receipt outcomes. The runtime suite exercises these under ARM64 Wine/Box64 and PRoot before the existing launch/credential/Stop tests. Tests use open synthetic DLLs, not proprietary game files. All seven cases pass on both routes in [verification run 35547759081](https://github.com/Russianranger/lsb-android/actions/runs/35547759081), implementation `07f6923a56d02c582d179f63ce1fdf8d00229a16`. The two negative controls invoke DLL detach and fail as designed; the new checker passes both runs of the exact 20 imports and the detach-sensitive DLL, returns 1 for a missing DLL (error 126), and retains a nonzero initialization-crash exit without a completed receipt. The existing six launch/credential/Stop cases and registration/render/input/audio gates also pass. CI Vulkan uses Lavapipe; no new Thor/gameplay result is implied.

**Build verification:** 11 Python contracts pass, including rejecting the reported all-DLLs-success/exit-minus-11 combination. All three CI gates pass: 116 JVM checks, 11 Python contracts, Android packaging, existing Windows native tests and ARM64 runtime execution. The device APK is `LSB-Android-0.4.1.apk`, versionCode 8, **7,710,656 bytes**, SHA-256 `8aa64a92e8a94cb3c40325d5e21e6395e72e6a45d778e1ee8b479e3effd91236`. APK v2/v3 signatures verify with the original certificate `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`. Packaged helper/backend bytes match the built sources; synthetic regression DLLs are excluded.

**Next device step:** install 0.4.1 over the existing app; start the Termux server and use Client → Launch FFXI with the same host/account and Turnip 26. Keep the current preparation; no reinstall, re-import, prerequisite repair or full copy. If it exits, export the new support ZIP and report the last visible screen. Actual xiloader startup/login/world entry remain pending.

---

# 0.4.0 prepared-client launch verification (2026-09-20)

The prior Thor runtime and actual client registration/COM acceptance remain valid. The new native launcher and Android account/server flow are intended to exercise login and world entry with that accepted preparation. Neither is yet device-verified.

- Local JVM checks: 90 existing import/recovery, 18 preparation/recovery (including a prerequisite-repair clone retaining active user files without modifying the active prefix), and eight one-use credential-transport/input/error checks.
- Ten Python contracts pass, including ASCII CLI markers/aliases, bounded credential input, rejection without secret-bearing errors and private output handling of split, long, ANSI and UTF-16 secret text. During login, only fixed event names can be persisted from arbitrary child output.
- The original-certificate APK compiles, dexes, aligns and verifies with v2/v3 signatures. Application ID `io.github.russianranger.lsb`, version `0.4.0` / code 7, minSdk 26, target/compileSdk 35; certificate SHA-256 `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
- APK: `LSB-Android-0.4.0.apk`, **7,710,656 bytes**, SHA-256 `a6dac5d6844293d6df722603c36dd5f980cfdcad568dc3267782cac9a8bd5a2d`. Packaged `client-launch.exe`, `client-init.exe`, `client_launch.py`, `client_setup.py` and `supervisor.py` match their source/build outputs byte-for-byte. No synthetic loader/DLL fixture is packaged.
- Implementation checkpoint `54f50948af52b3fd286d316d7cba3a73cea74bce` passed all three gates in [verification run 35537087441](https://github.com/Russianranger/lsb-android/actions/runs/35537087441): APK/core, existing native Windows tests, and ARM64 runtime execution. Each new case passes in both ARM64 Wine/Box64 in Docker and the actual extracted rootfs through PRoot: launch, relaunch, deliberate full Windows exit `0xc0000135`, explicit Stop, missing import DLL (error 126, loader not executed), and dependency-check-only (loader not executed). The successful fixture confirms the exact username/password arguments and space-containing working directory; quotes, shell metacharacters and trailing backslashes survive literally. Persisted logs/session reports contain neither account nor secret echoes, including the fixture's long and UTF-16 output.
- The existing software/DXVK D3D8 render/input/audio/lifecycle gates and US/EU/JP registration, registration/COM failure and prerequisite-retry gates continue to pass. CI's Vulkan adapter is Lavapipe; the earlier Thor result provides the hardware-rendering evidence. This is not a fresh device/gameplay result.
- The initial [run 35536746662](https://github.com/Russianranger/lsb-android/actions/runs/35536746662) passed core/APK and existing Windows checks but stopped at CLI inspection of the synthetic fixture: it encoded its option strings as UTF-16, unlike the ASCII parser strings in the inspected xiloader source. The corrected fixture now uses the expected string encoding and passes the same static inspection locally. This did not execute the user's loader or alter device files.

**Device boundary:** literal argument passing and native process supervision are tested with an open fixture. The actual imported xiloader remains identified by the prior report's hash `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`; its complete parser behavior, Visual C++ compatibility, server protocol/authentication and actual FFXI world entry require the Thor test. A process running or exiting with zero is not recorded as authenticated/world-ready. See [the device procedure](client-launch-040.md). No repeated import, preparation or open probe is requested.

---

# 0.3.0 staged client initialization verification (2026-09-20)

The existing Thor runtime acceptance remains valid. Real-client registration and COM initialization have now also passed on the device, as recorded below. No proprietary DLLs are present in the development environment.

- Existing 90 JVM import/recovery checks and 15 new preparation checks pass. The new checks exercise separate client/prefix copies, preservation of original files/hives, non-traversed prefix links, no activation without a receipt, atomic selection/rollback, interrupted copying/store recreation, retry and candidate-only discard.
- Six Python contracts pass, covering request isolation, bounded logs, component inventory, working-copy path boundaries, linked-path rejection and malformed/wrong-architecture PE images.
- Android SDK 35 compilation, DEX packaging, zip alignment and APK v2/v3 signature verification pass locally. Application ID `io.github.russianranger.lsb`, version `0.3.0` / code 6, original signing certificate retained.
- Packaged `client-init.exe`, `client_setup.py` and `supervisor.py` match the built source/assets exactly. No synthetic client DLL or prerequisite fixture is packaged.
- Implementation checkpoint `d0f5acb62727cb82fbe3eb406e3f1c800a78a367`: [verification run 35532787286](https://github.com/Russianranger/lsb-android/actions/runs/35532787286) passed APK/core, native Windows helper and ARM64 runtime gates. The real x86 worker passes US/EU/JP synthetic COM initialization, deliberate registration failure, deliberate COM failure and prerequisite/retry with a full Windows exit code of 3010, under both ARM64 Wine/Box64 in Docker and the extracted rootfs through PRoot. Each case checks its receipt and clean shutdown. The existing render/input/audio/exit and Stop gates also pass; CI Vulkan uses Lavapipe, while the prior Thor acceptance supplies hardware-rendering evidence.
- Follow-up Android-only changes keep the display waiting through a long full copy and write an accurate fresh failure/cancellation state before Windows starts. These are compiled into the delivered APK; the native worker and Python backend remain the CI checkpoint's exact bytes.
- Delivered APK: `LSB-Android-0.3.0.apk`, **7,677,733 bytes**, SHA-256 `eab9c76be74a5f2d1c02d22822c90e1687ed3a00c5c04fde096395981894b3fd`. The update retains certificate `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e` and needs no uninstall or runtime download.

## Thor acceptance: milestone 2 complete

Reviewed `lsb-support (2).zip`, SHA-256 `4e6ec2603829e0add41e36368996abc82559431b1efd261331ec89182cf9d8b8`. It reports app 0.3.0 on AYN Thor / Android 13, using the installed US `PlayOnlineViewer/viewer/com/polcore.dll`, outside `patchfiles`.

- Session `93e77044-aa73-457c-934e-501daa5f0812` ran from **20:21:34 to 20:22:46 UTC** on 2026-09-20, about 72.6 seconds after the full-copy stage. This is not the total copy/preparation duration.
- All six steps passed with exit code 0, HRESULT 0 and Win32 error 0: 32-bit registry write/readback, registration of `polcore.dll`, `FFXi.dll` and `FFXiMain.dll`, followed by actual region-specific POL and FFXI COM construction.
- Native DLL-load traces independently confirm the selected proprietary files loaded from the working `D:\\` installation. FFXiMain registration also loaded native `C:\\windows\\system32\\d3d8.dll` and Wine's built-in DINPUT8. No game-rendering performance or full input compatibility is inferred from DLL loading.
- Current preparation generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505` reports `copy_complete=true`, 66,322 files and `status=passed`. Its saved receipt exactly matches the supervisor and current-preparation results. Source inventory matches the managed import inventory byte-for-byte, and its SHA-256 matches the receipt (`c51540586e4a8039455e3976572f3edb6185468a95c56fa67fbc300ea604bcbc`). The original inventory still reports 15,176,011,583 bytes.
- Final state is `phase=completed`, `automatic_checks_passed=true`, `initialization_passed=true`, `alive=false`, `starting=false`; display and audio close. `game_files_mounted=true` describes this completed session's working-client mount, not a currently running process. The audio bridge received zero streams because this stage did not play sound; prior runtime audio acceptance remains valid.
- Hardware preflight confirms Turnip Adreno 740 / Mesa 26.0.0 with DXVK 2.5.3. Its three Vulkan presentation frames are preflight evidence, not FFXI frames. `game_started=false` is expected.
- No prerequisite was selected or executed. RpcSs, hostname/menu-builder/Bluetooth/Eventlog startup messages and Box64's missing `libXcomposite.so.1` remain in the logs, but none caused a failed initialization step. Preserve those observations for game startup; do not assume all Wine services work.

**Remaining device boundary:** actual xiloader execution, its imported Visual C++ dependencies, authentication, character/world entry, game rendering/audio/controls, vendor prerequisite installers and failure/rollback behavior still need testing. The legacy source inventory's `runtimeTested=false` is not an initialization failure: the separate preparation receipt records the successful checks, and gameplay remains untested. Client version is still unknown because no readable `patch.ver` was found. No repeat of preparation or the open runtime probe is requested. See [the next milestone](HANDOFF.md).

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
