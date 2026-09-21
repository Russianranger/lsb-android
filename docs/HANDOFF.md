# Active milestone 3: restore the missing version registry value (0.4.8)

The new `patch_pol.zip` matches the selected polcore.dll. Its unchanged patch.ver decodes as `30251204_1` with Interface string `"0"`. The exact original callback returns -1 for a missing registry value and 0 for `"0"` in isolated x86 emulation; input bytes are preserved. The app had created InstallFolder/language keys but omitted Interface. See [evidence, implementation and limits](version-repair-048.md).

0.4.8 validates the complete supported version record before restoring only a missing selected-region, 32-bit `Interface\\0001` REG_SZ value. Existing values/types are preserved; readback failure removes the new value. `version_config` in the native launch receipt reports the repair. Client files, preparation, runtime and loader remain unchanged. This is a targeted fix for the reproduced version-reader failure; whole-game startup remains unverified. Full CI/build validation is pending at implementation commit time.

Next device action after the verified APK is delivered: install in place, keep the accepted preparation and current settings, start Termux server, launch once with startup capture enabled, and export Diagnostics if it stops. No re-import or re-preparation. Do not replace version files or suppress callback failures. If a registry value already exists, preserve it and use the receipt to guide the next investigation.

---

# Previous milestone 3 step: inspect version-data processing (0.4.7 device result)

The new `lsb-support(6).zip` captures successful root FTABLE/VTABLE opens and first reads, plus a successful complete 288-byte patch.ver read. The working directory matches FFXiMain's parent. All ten API hooks are installed; there are no dropped records. GameMain returns `0x88770000` after 1,227 ms, before any Windows-version, DirectPlay, window or D3D-creation event. Outer GameStart still returns S_OK. See [the device evidence, remaining branches and exact next inputs](version-startup-047.md).

The matching DLL puts a PlayOnline common-function-table callback (offset `0x124c`, index 1171) immediately after its version-file read. A negative callback result or file-manager completion failure can produce this exit. Successful ReadFile alone does not distinguish them. Next obtain `FINAL FANTASY XI/patch.ver` and the selected `PlayOnlineViewer/viewer/com/polcore.dll` from the same import/backup, verify the recorded DLL hash, and inspect that implementation locally. Do not use the patch-cache DLL. These bytes are absent from both the support ZIP and the earlier two-DLL upload.

No code, APK or runtime change is made for this analysis; 0.4.7 remains installed. Do not ask for another identical launch, re-import or preparation. Preserve the accepted generation and original files. Do not fabricate version data, bypass validation or change graphics/dependencies without a concrete failing operation. Startup remains unresolved.

---

# Previous milestone 3 step: pinpoint the pre-window operation (0.4.7)

The supplied `ffxi_lsb.zip` matches both recorded DLL hashes. Offline inspection confirms that `0x88770000` is a shared early-startup failure result, not a specific Direct3D/Windows error. GameMain checks its file manager (including FTABLE.DAT, VTABLE.DAT and patch.ver), Windows version, DirectPlay and window creation before its main loop. The pinned runtime contains dpnhpast.dll. The old unknown-version label does not establish that patch.ver is missing. See [static findings, RVAs and trace interpretation](startup-files-047.md).

0.4.7 retains the prepared client and adds fixed, numeric observation of the actual FFXiMain file/platform/window API results, plus a metadata-only inventory of three startup files. No proprietary DLL is bundled or committed. No game instruction, data file, registry setting or renderer is changed. The existing opt-in temporary-loader mode is retained. Reporting includes the earlier inner-HRESULT correction.

Implementation `8ef8234edb9f1c64eabfd9b1bef4a5a2cf53535e` passes [all gates](https://github.com/Russianranger/lsb-android/actions/runs/35634762962): 116 JVM checks, 33 Python contracts, 36 native Windows checks and 26 launch cases in each of Wine/Box64 and PRoot. The signed device APK identity is in [validation](validation.md).

Next device action: install in place, leave **Capture FFXI startup result** checked, start the existing Termux server, launch once and export Diagnostics. No re-import, preparation or additional DLL upload is requested. This is targeted diagnosis, not a confirmed startup fix. Use the next receipt to identify a concrete failing operation before repairing client files or changing runtime components. In particular, do not fabricate patch.ver or claim DirectPlay is absent based only on its dynamic load name.

# Active milestone 3: identify the inner GameMain failure (0.4.6 device result)

The newest report `lsb-support (3)(2).zip` records successful FFXI and GameMain COM creation, followed by **GameMain HRESULT `0x88770000` after 1,441 ms**. Outer GameStart returns S_OK after 1,983 ms, and the loader exits 0 without an observed game window. This is the captured failing boundary; its underlying cause is not yet known. See [exact evidence, reporting correction and next input](game-main-failure-046.md).

Source reporting now surfaces the inner failure. Thirty-one Python contracts and replay of the actual device receipt pass. No new APK is delivered for this reporting-only correction; the existing 0.4.6 already captures the needed boundary. Do not ask for another identical launch. Next obtain `FINAL FANTASY XI/FFXiMain.dll` and `FFXi.dll` from the same import/backup, check the hashes documented above, and statically inspect the return path. The support ZIP contains hashes, not these binaries. Preserve the accepted generation, original loader, imported files and runtime. Neither a renderer change nor a dependency sweep is justified yet.

---

# Active milestone 3: observe the actual GameStart result (0.4.6)

The new `lsb-support (2)(2).zip`, SHA-256 `f8b8c13088bd2ea888195f2fe27c32e4e6e19d4f48399a2b808ad264051378f2`, records 0.4.5 session `a0898d38-3a79-4290-9d76-8c1dbd237c8f`. Loader PID 284 exits 0 after 15,688 ms, with all five expected modules and no sampled game window/dialog. Windowed settings and all 20 dependency loads pass. Missing-DLL 0xc0000135/RPC 1722 belong to PID 292. Loader 0x80000026 is first-chance; no fatal game exception or DXVK initialization message was captured. Do not treat these warnings as the proven cause or prescribe another dependency/renderer change.

0.4.6 adds **Capture FFXI startup result** (initially checked; unchecking restores direct original-loader execution). It runs an exclusive temporary same-directory copy with an added diagnostic DLL import, observes the known IFFXiEntry GameStart and optional IGameMain boundary, and retains fixed stage/PID/HRESULT/duration metadata. Imported code, loader file, accepted generation, registry registration and runtime are not replaced. The copy has a different filename, image layout and checksum, so this is an explicitly identified diagnostic execution mode. It is removed after normal/failure/Stop paths; force-kill can leave an unselected small copy. See [design, evidence and limits](game-start-trace-046.md).

The next device test is an in-place 0.4.6 install, same settings/server/preparation, trace checked, one launch and fresh Diagnostics. The actual game remains unresolved. Correlate `startup` records to the receipt child PID: loader hook count, FFXI COM result, GameStart enter/return (unsigned HRESULT and elapsed milliseconds), and optional nested GameMain result. A missing return with abnormal process exit differs from an ordinary returned HRESULT. A zero return without a game window is not verified startup; zero hook count means that boundary was not instrumented. Preserve source loader SHA `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8` and generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`.

Implementation `3003880bd3f2cbb3be4f608274a8e86b4dce1b38` passes every gate in [run 35629179765](https://github.com/Russianranger/lsb-android/actions/runs/35629179765): 116 JVM checks, 27 Python contracts, 36 native Windows checks and all 25 launch scenarios separately under ARM64 Wine/Box64 and PRoot. S_OK/S_FALSE/E_FAIL/nested E_OUTOFMEMORY, unhandled exception, Stop in GameStart, trace-off, private virtual-slot preservation, original hashes and copy cleanup pass. See [validation](validation.md) for the corrected test-session race and exact boundaries. Delivered `LSB-Android-0.4.6.apk` is 7,747,758 bytes, SHA-256 `293a337e63c970986baf768198dd31ecb66a4784e8b441e24b81d19770227f85`, using the original signing certificate; observer DLL SHA-256 `afe0e602262413e656c6cdb8566a80f475ea8c2afa74c5c3fdad361025ce8b3b`. Actual GameStart outcome and game display on the Thor still require the next device report.

---

# Active milestone 3: capture the silent startup failure (0.4.5)

The latest `lsb-support (1)(1).zip` (SHA-256 `3bdcbd1030a7201edcd75312736bc7432df8abf1a4fec72850916e717f3fea95`) records 0.4.4 session `aa460280-6a87-4994-a0cb-aeac44e1b07c`. The five windowed values were applied and read back successfully; original settings were saved. The loader nevertheless closed with child/bridge exit 0 after 15,111 ms, with POL/FFXI/FFXiMain/D3D8/D3D9 observed and no game window or standard dialog observed in 35 samples. This rejects windowed mode as a sufficient fix. Do not repeat a settings-only update or infer a proven exception from the user's word “crashing.”

0.4.5 fixes a diagnostic blind spot: the launch environment previously set `WINEDEBUG=-all` and `DXVK_LOG_LEVEL=none`. It now captures bounded, filtered Wine DLL/COM/exception/error metadata and DXVK initialization/error categories through the existing private pipe. DXVK file output is explicitly disabled. Numeric Wine process/thread IDs can be correlated with the native receipt's child PID; unhandled process failure still depends on the real exit, not a first-chance exception or a recoverable warning. Unknown text, paths, GUIDs, register/stack contents and credentials are discarded. This is a diagnostic update, not a demonstrated fix for the proprietary GameStart return.

Keep prepared generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, imported xiloader SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`, the source import, saved display originals and Wine/Box64/Turnip/DXVK runtime. No prerequisite installation, new preparation, GameHub transfer or renderer change is supported by this report. Next device action: install 0.4.5 in place, launch once with the existing settings/server, then export fresh Diagnostics. The next report must establish whether a captured error/exception explains the early return. If it is still silent, the imported loader does not expose GameStart's return/HRESULT; direct source-level loader instrumentation may be required. Never claim DLL loading or Vulkan probe frames establish game rendering.

Implementation `6563b1e351ef2a9debd32e2eb1684005a6dbd42f` passes all gates in [run 35624389711](https://github.com/Russianranger/lsb-android/actions/runs/35624389711): 116 JVM checks, 21 Python contracts, 36 native Windows checks and all 18 launch scenarios separately under ARM64 Wine/Box64 and PRoot. The real missing-DLL and unhandled divide-by-zero cases retain safe metadata and the correct child PID; recoverable warnings do not terminate a healthy fixture. Immediate Stop retains its diagnostic snapshot. Delivered `LSB-Android-0.4.5.apk` is 7,723,027 bytes, SHA-256 `7893b550d952e70ee7e975b5cd59319a6661bebe1ae51a6266c55c4759844413`, using the original signing certificate. See [startup diagnostics](startup-diagnostics-045.md) and [validation](validation.md) for exact evidence and limits. Actual game startup remains unresolved pending the fresh device report.

---

# Active milestone 3: explicit windowed display profile (0.4.4)

The new `lsb-support(5).zip` device report (SHA-256 `871b057ff6b647abbea1f31d45857bb226d9321d0126cebf869e564d4e6f79e3`) records 0.4.3 session `be51e556-b2a6-45a9-959c-4353bb3f8113`. Login succeeds; POL, FFXI, FFXiMain, D3D8 and D3D9 are observed, but no game window/dialog is observed before exit 0. All five display values already existed: 640×480 overlay, 512×512 background, fullscreen mode 0. The 0.4.3 missing-only policy made no changes. Do not repeat that test or claim it exercised 1280×720 windowed mode.

0.4.4 adds an explicit Client display selector, defaulting to Windowed 1280×720. It saves original display values in the same prepared prefix before overriding the five known DWORDs, with Keep current and Restore saved original choices. This is a targeted candidate fix for the observed fullscreen configuration, not proof that fullscreen caused the exit. Retain generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, source import, xiloader, prefix and runtime. Implementation `b40c7e6885ca2cb34a0045793f722a2495516842` passes all gates in [run 35618686567](https://github.com/Russianranger/lsb-android/actions/runs/35618686567), including 16 launch cases separately under ARM64 Wine/Box64 and PRoot and the regional registry recovery fixtures. Delivered `LSB-Android-0.4.4.apk` has SHA-256 `f6b03de94325cf438b7137a7e5d24541e20a19e7b13dda8be0bd77e9eb837113` and the original signing certificate. Next device test: install in place, select Windowed 1280×720, start the existing Termux server and launch; send new Diagnostics if no game appears. See [validation.md](validation.md) for evidence, exact build identity and limits.

---

# Active milestone 3: post-login exit investigation (0.4.3)

The 0.4.2 device report `lsb-support (3)(1).zip` (SHA-256 `9011837bb7be404ce1e60e4bfbe927034c82d6c57a1d135c957d7ba93345e364`) records session `56ca8e82-a27b-4107-8971-c9b9989672f7`: `login_message_seen`, `server_connected`, native child exit 0 and bridge exit 0 after about 35.5 seconds. The user confirms successful login followed by exit. This establishes authentication progress, not world entry; no captured exception or initialization failure establishes the cause. Preserve generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, imported loader and current runtime.

0.4.3 fills missing FFXI display settings, records safe post-login observations, and stops describing an unobserved game startup as a normal close. Implementation `483c69087d0e23570fc55827e60564a08b9162ae` passes all gates in [run 35591567783](https://github.com/Russianranger/lsb-android/actions/runs/35591567783), including all 14 launch cases in ARM64 Wine/Box64 and PRoot and the US/EU/JP display-settings fixture. Delivered `LSB-Android-0.4.3.apk` uses the original certificate and has SHA-256 `b7a96fc46560b710412dc484470551ed62af618f04650ccc1a2bfde62cabb2ea`. See the current section of [validation.md](validation.md) for source evidence, exact build identity and limits. Do not assert the actual game is fixed until the device retry. No re-import, preparation copy, runtime replacement or GameHub test is needed.

---

# Active milestone 3: login rejection feedback (0.4.2)

The 0.4.1 device test accepts the dependency-checker fix: all 20 DLLs loaded, exit 0, and xiloader started. The new blocker is a `login_rejected` event while the loader stayed alive and the screen remained black. The old log does not distinguish wrong credentials, account state or version rejection. 0.4.2 adds specific safe event classification, live failure handling/automatic shutdown, accurate waiting-for-login status and an Android error dialog returning to the Client form. Implementation `90f812a836b0da8a185440b6c25a2eada1cdf762` passes all gates in [run 35551248809](https://github.com/Russianranger/lsb-android/actions/runs/35551248809), including all five new rejection/automatic-stop cases under ARM64 Wine/Box64 and PRoot. Delivered `LSB-Android-0.4.2.apk` has SHA-256 `da5e0e65eb3d639a5a10a9899fab6a04d8f89644705dd892f9b949972fce57ae` and the original certificate. Read [the current validation section](validation.md) before requesting another test. Preserve current generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, source import, loader hash and Turnip 26. No blanket graphics or prerequisite changes are supported by this report.

---

# Active milestone 3: fix the 0.4.0 pre-launch exit

The 2026-09-21 Thor support report confirms both launch attempts stopped in the dependency checker, after all 20 import DLL loads passed, with process signal 11. xiloader never started. 0.4.1 changes only the checker lifetime, receipt validation and version labels; the accepted client/prefix, pinned runtime and login flow remain in use. See [diagnosis and validation](validation.md). Implementation `07f6923a56d02c582d179f63ce1fdf8d00229a16` passes all three gates in [run 35547759081](https://github.com/Russianranger/lsb-android/actions/runs/35547759081), including the exact 20-import list twice and unload/exit/missing/initialization-crash regressions under ARM64 Wine/Box64 and PRoot. The original-certificate 0.4.1 APK has SHA-256 `8aa64a92e8a94cb3c40325d5e21e6395e72e6a45d778e1ee8b479e3effd91236` (7,710,656 bytes). Device retry will use the same preparation after installing the update. Do not prescribe a full import/preparation or Visual C++ install based on this report. Precise Wine/Box64 crash attribution remains unproven without a stack.

---

# Active milestone 3: login and world entry 0.4.0 (2026-09-20)

The user authorized this step after proprietary registration/COM passed. The implementation reuses the activated client/prefix, adds Android server/account/password fields, checks the exact imported loader's CLI markers and normal-import DLL loading, and starts it through a native x86 worker with pipe-only credential transport. Launch output persists fixed event names rather than arbitrary credential-bearing child text. The worker records full Windows process failure/exit codes. Prerequisite repair clones the active working client and prefix, retaining current user files and the accepted generation until all checks pass.

Implementation `54f50948af52b3fd286d316d7cba3a73cea74bce` passes all three gates in [run 35537087441](https://github.com/Russianranger/lsb-android/actions/runs/35537087441). Existing 90 core checks, 18 preparation/recovery checks, eight credential-transport checks and ten Python contracts pass. Launch/relaunch, literal credential arguments and working directory, full Windows failure status, explicit Stop, missing-dependency rejection and check-only behavior all pass under ARM64 Wine/Box64 and PRoot. Logs/session reports exclude the deliberately echoed test credentials. The first ARM64 attempt caught a synthetic-fixture encoding mismatch; the corrected fixture passes the same inspection and real launch path. The new fixtures use synthetic loaders, not user credentials or a game server. Launcher repair requires its loader-check receipt before activation, and login discards the PRoot wrapper's raw output as well as unfiltered child output. The original-certificate device APK identity and precise limits are recorded in [validation.md](validation.md).

**Historical 0.4.0 device action (superseded by 0.4.1 above):** install 0.4.0 in place, start the existing Termux server, enter the server account under **Client → Play FINAL FANTASY XI**, and choose **Launch FFXI**. Keep `127.0.0.1`, Turnip 26, and the activated preparation. No further import, preparation copy, open probe or GameHub test is requested. After the attempt, stop/exit and export diagnostics; report the last visible stage and any character/world entry. [Detailed procedure and limits](client-launch-040.md).

**Acceptance still pending:** exact imported-loader parser and dependency compatibility at runtime, server authentication, character/world entry, actual game rendering/audio/input and stability. Static CLI-marker inspection is not a complete parser proof; loadable DLLs are not proof that all exports/behavior satisfy the loader. Process alive/exit 0 never marks login or world entry verified. Existing accounts with ASCII input are supported; account creation, OTP, credential persistence, full controller mapping and server integration remain later work.

---

# Milestone 2 complete: client registration and COM initialization 0.3.0 (2026-09-20)

The user authorized the next step after the Thor completed milestone 1. The 0.3.0 implementation adds a separate full working client and cloned prefix, real registration and client COM checks, per-step reports, prerequisite EXE execution, retry without full recopy, and atomic activation/rollback of matching client/prefix generations. See [client-initialization-030.md](client-initialization-030.md) for device procedure, boundaries and provenance. The selected import survives and remains the source of truth; no GameHub test, fresh prefix or import is requested.

Implementation checkpoint `d0f5acb62727cb82fbe3eb406e3f1c800a78a367` passed all three verification gates in [run 35532787286](https://github.com/Russianranger/lsb-android/actions/runs/35532787286), including synthetic US/EU/JP initialization and failure/prerequisite recovery under ARM64 Wine and PRoot. Local checks pass: existing 90 import/recovery checks, 15 preparation/cancellation/rollback checks and six backend contracts. Following Android-only changes remove the old one-minute display wait during large preparation copies and record a fresh diagnostic state for early copy failures; these compile in the delivered signed APK, whose native/backend bytes are identical to the CI checkpoint. Final APK identity and precise verification boundaries are recorded in [validation.md](validation.md).

**Device acceptance:** `lsb-support (2).zip` confirms all six initialization steps passed with the user's actual US installation on the Thor. The separate working client and matching prefix were activated as generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`; session `93e77044-aa73-457c-934e-501daa5f0812` completed normally. Registration, actual in-process POL/FFXI COM construction and the first preparation/activation are now device-verified. See [validation.md](validation.md) for exact evidence and remaining limits.

**Next implementation milestone:** login and world entry using the activated preparation. Add Android server/account fields and launch the existing imported xiloader with argument-array execution, its correct working directory and credentials excluded from logs, support exports and ordinary preferences. Verify the exact imported loader's CLI behavior and its Visual C++ runtime dependencies before login; its PE imports include `MSVCP140.dll`, `VCRUNTIME140.dll` and UCRT API DLLs, but it has not been executed. The saved server remains `127.0.0.1` for the existing Termux server. Preserve the imported loader, activated client/prefix, source import, backup and Turnip 26. No repeated preparation, open-probe test, re-import, GameHub transfer or blanket dependency install is needed.

**Carry forward:** Wine reports RpcSs startup errors and Box64 cannot load `libXcomposite.so.1`; these did not block the completed registration/COM operations. Keep them visible during launch testing rather than treating the run as warning-free or proof of full Wine service compatibility. The native D3D8 DLL loaded during FFXiMain registration; actual FFXI rendering, login, audio and controls are not yet exercised. Device prerequisite execution and failure/rollback paths remain untested because this preparation passed without a prerequisite installer.

---

# Milestone 1 complete: in-app runtime probe 0.2.0 (2026-09-20)

The user confirms **both imported FFXI data and a backup survive**, and authorized the first runtime milestone. Do not ask for them again or request a GameHub test. The current work implements a fresh, isolated runtime with an embedded display/audio/input path and an open x86 registry/COM/D3D8 probe. See [runtime-milestone-020.md](runtime-milestone-020.md) for setup, candidate choice, ownership boundaries and test interpretation.

The first candidate is explicitly Wine 10 WoW64 / Box64 0.4.4, reused from the source-tracked TRASC runtime. This is not a claim that it recreates the captured Proton/FEX environment. FFXI registration, prerequisites and xiloader launch are not part of this probe build. The existing app import and backup are retained and not mounted by the runtime.

Implementation is on the existing `codex/client-baseline` branch / draft PR #1. Implementation commit `e07f29c32a99cec2d26a5f39e3866ccc9369a24b` passed the APK, native Windows and ARM64 runtime gates in [run 35530166981](https://github.com/Russianranger/lsb-android/actions/runs/35530166981). The original-certificate 0.2.0 APK and exact evidence/limitations are recorded in [validation.md](validation.md). The pinned runtime release is published. Thor hardware, registry/COM, input, audible output, explicit Stop, normal probe exit and relaunch are now confirmed. Milestone 1 is complete; proceed to transactional client initialization. Preserve the existing LSB application ID and signing key. The runtime release mirrors only pinned open components/source archives after verification; it is separate from user client data.


## Initial Thor device results (2026-09-20)

Reviewed the supplied `lsb-support(3).zip`, screenshot `Screenshot_20260920-140524.png`, and the user's report that the triangle and sound worked. The retained session is 0.2.0 on Android 13 / AYN Thor, from 19:01:07 to 19:05:57 UTC.

- **Confirmed:** actual Turnip Adreno 740, Mesa 26.0.0, hardware verification true, DXVK 2.5.3 in D3D8 compatibility mode; 2,000 presented frames in the final retained probe sample. The screenshot independently shows the colored triangle.
- **Confirmed:** 32-bit registry round-trip and native test-DLL COM activation; `hresult=0`.
- **Confirmed:** 9 key-down events and 8 pointer/button events reached the Windows probe. This proves key delivery, not complete text entry or gameplay/controller coverage.
- **Confirmed:** four PCM streams with nonzero audio samples and the user's audible-tone report. Active tone portions have zero reported underruns; some short silent tail streams report one. Do not generalize this short test to sustained game audio stability.
- **Confirmed:** requested Stop ended the supervisor with exit 0, `phase=stopped`, `alive=false`; display and audio closed. This initial bundle was not a completed-probe receipt: `probe_exit` and `automatic_checks_passed` were absent. The subsequent lifecycle report below closes that gap.
- **Confirmed:** `game_files_mounted=false`; current inventory still selects the installed `PlayOnlineViewer/viewer/com/polcore.dll`, with 66,322 imported files and a 32-bit xiloader. Inventory does not establish proprietary runtime compatibility.

The probe intentionally uses a 40 ms draw timer (about 25 FPS). The screenshot's 25 FPS is not a measured FFXI performance ceiling. Its overlapping counter text comes from transparent DrawText on a non-erased invalidated area in `windows/runtime-probe.c`; clear the header rectangle before drawing in the next code build. This cosmetic issue does not invalidate the recorded input counters.

Wine first-prefix setup logged OLE/RpcSs, driver setup and hostname warnings, but subsequently completed and the test COM activation succeeded. No fatal crash or failed HRESULT is shown in the retained probe. The display broken-pipe message coincides with shutdown, not an observed rendering failure. Keep these observations scoped to the open probe; no proprietary registration has been tested.

## Lifecycle acceptance: milestone 1 closed (2026-09-20)

The user reports “exit and restart worked” and supplied `lsb-support (1).zip`. Both the previous and current runtime state records independently report `phase=completed`, `probe_exit=0`, `automatic_checks_passed=true`, verified hardware rendering, passing 32-bit registry/COM checks and HRESULT 0:

| Session (UTC) | D3D8 frames | Result |
| --- | ---: | --- |
| 19:12:35–19:14:37 | 116 | Normal probe exit, checks passed |
| 19:14:52–19:15:28 | 106 | Relaunch and normal probe exit, checks passed |

The final Android state is `alive=false`, `starting=false`; the display and audio close after the probe completes. Both runs use the same Turnip 26 / Adreno 740 candidate. Game files remain unmounted. Input counts in these short lifecycle-only runs are zero; the earlier 9 key and 8 pointer/button events already establish input delivery and need not be retested.

The second run logs `err:ole:start_rpcss Failed to start RpcSs service` despite passing the in-process test COM activation. Preserve this observation for real POL/FFXI initialization; the synthetic class does not test out-of-process COM or RpcSs behavior. Neither run reports a failed probe HRESULT or abnormal probe exit.

**Accepted milestone:** independent Windows runtime, fresh-prefix execution, real hardware D3D8, registry, in-process COM, audible sound, input, explicit Stop and normal exit/relaunch. No more open-probe repetitions are required. Foreground/background resume, sustained gameplay stability and full controller/text input remain later device acceptance work, not completed claims.

**Next implementation milestone:** transactional POL/FFXI initialization against the surviving managed import, preserving its original payload and the tested runtime. Revalidate the selected installed US POL core, create a separate working installation/staged prefix, record registration/prerequisite results and actual proprietary COM activation, and preserve rollback on failure. No FFXI launch compatibility is established yet. Retain the existing prefix and renderer; do not request a reinstall, fresh prefix, re-import or GameHub test.

---

# Historical scope/audit before 0.2.0: fresh in-app FFXI client (2026-09-20)

The following is the pre-implementation audit. Current progress and surviving-data confirmation above supersede its pending-state statements.

The user reports that file transfer into GameHub Lite corrupted the container. There is no working external client for testing. The LandSandBoat server remains available in Termux. Continue toward a client that starts inside LSB Android and connects to that existing server. Server integration/compiler work remains later in the original project scope.

This handoff records the changed premise and source audit. It does not introduce a runtime, new APK or claim successful FFXI execution. Released 0.1.3 remains the import/recovery/export baseline at `ce17df84be6dafebc756d5ea8a01fcb20ad56a3e`; draft PR #1 contains that work. No new GameHub transfer, CMD or EXE test is requested. The cause of the reported corruption is unknown.

## Recoverable state and missing evidence

- LSB Android imports live in its own storage, selected by `MainActivity.storage()`, under `lsb/session/current/client`; they are separate from GameHub's prefix. A corrupted GameHub container does not establish that these files were lost. Check the existing app before requesting a new full import. Do not uninstall or clear app data.
- The latest supplied 0.1.2 support report previously confirmed an imported US client, the installed `PlayOnlineViewer/viewer/com/polcore.dll`, `PlayOnlineViewer/pol.exe`, and `FINAL FANTASY XI/ashitav4/bootloader/xiloader.exe`, targeting `127.0.0.1`. It recorded successful import and backup/restore operations. This is historical inventory, not a new check of surviving device files or proof of launch compatibility.
- An existing LSB session backup or complete client ZIP can supply game data without an existing Windows registry. Whether those bytes still survive has not been confirmed after the corruption. Diagnostic ZIPs contain inventory and logs, not the game payload. There are no proprietary game files in this repository or development workspace.
- The recovered successful setup ran official installers before replacing updated payloads. Support both rebuilding registration from an imported installation and running user-supplied official installers inside the fresh prefix if registration alone is insufficient. Never label a registry recipe as equivalent to the complete installer state without testing.
- xiloader 2.0 autologin used server/username/password arguments because interactive console entry was unreliable. The app needs native credential fields. Keep the existing loader version identifiable; do not silently upgrade it along with the app or claim the current upstream parser verifies the imported executable.

## Runtime reuse audit

Reference repository: [Russianranger/trasc-server-android at 95475c747be1fbac1ac3f54197e962ce5ffc1f42](https://github.com/Russianranger/trasc-server-android/tree/95475c747be1fbac1ac3f54197e962ce5ffc1f42). It was inspected read-only; no changes to the working EQ app are needed for this project.

| Area | Reusable foundation | FFXI-specific work |
| --- | --- | --- |
| Install and process supervision | `ClientRuntime.java`, `RuntimeManager.java`, `TarExtractor.java`, packaged PRoot, staged rootfs installation, process shutdown | Remove server-manager coupling; give LSB its own rootfs, prefix, sockets, caches and journal; enforce stopped-runtime updates |
| Display and input | `ClientActivity.java`, `RfbConnection.java`, `DisplayInput.java`, native surface and keyboard/controller code | Separate EQ game behavior and controller defaults; verify Wine desktop, dialogs and FFXI input |
| Audio | Android audio bridge and `backend/client_audio.py` | Verify PlayOnline and FFXI output in the selected runtime |
| Graphics | Source-built glibc/KGSL Turnip 24.3.4 and 26.0.0, hardware/presentation probe, DXVK 2.5.3 | Existing bundle only installs x86 `d3d9.dll`. Add and verify x86 `d3d8.dll` plus `d3d9.dll` for the D3D8 route; detect existing wrappers before applying overrides |
| Windows compatibility | Reproducible Wine 10.0 amd64 WoW64 + Box64 0.4.4 build | This is not the captured Proton 10 arm64x + FEX environment. Do not copy it and claim FFXI compatibility. Evaluate a source-buildable matching Wine/FEX combination first; treat any alternate stack as a distinct candidate |
| Diagnostics/recovery | Bounded logs, runtime probes, process exit records, stopped session snapshots | Add prefix-generation IDs, exact component/loader hashes, 32-bit registry and COM probe results, redaction and FFXI bootstrap stage reporting |

The existing TRASC Wine/Box64 package is not a drop-in host for an ARM64EC FEX DLL. Wine architecture, translation backend, host libraries and graphics bridges must be packaged and qualified together. The screenshots' component names and version labels do not provide those binaries or establish individual necessity. Copying every opaque GameHub component is not a reproducible installation recipe.

[DXVK 2.5.3's installation table](https://github.com/doitsujin/dxvk/blob/v2.5.3/README.md) explicitly requires both `d3d8.dll` and `d3d9.dll` for D3D8. TRASC's `scripts/build-vulkan.sh` and `backend/client_vulkan.py` package only D3D9. Do not equate a passing EQ/D3D9 test with a verified FFXI rendering path. Check the actual imported client's wrapper/DLL load chain during the proprietary test.

## Implementation sequence and acceptance

1. **Independent runtime proof.** Package the selected, pinned Wine/translation stack and in-app display, input and audio. Create a fresh app-private prefix from scratch. Run an open 32-bit Windows probe for execution, registry view, test-DLL COM activation, D3D8 rendering through the chosen GPU path, keyboard/mouse and clean exit/relaunch. Record architecture, hashes and actual renderer. This stage can proceed without FFXI files. Do not require a GameHub export or an external app to execute commands.
2. **Transactional client initialization.** Revalidate any surviving managed client or import its backup. Preserve the original payload while creating a writable working installation and staged prefix. Apply prerequisites as individually recorded steps. Use the selected installed POL core, correct installation root and region; reject patch-cache selections. Invoke installer/registration APIs from the app supervisor, capture exit/HRESULT details, and test real POL/FFXI COM activation. Offer official installer execution in the same in-app display when needed. A failed step must leave the prior usable generation available.
3. **Login and world entry.** Add Android account/server fields and a launch action that invokes the established xiloader 2.0 with correct working directory and arguments, without shell interpolation or interactive-console dependence. Passwords must not enter normal preferences, launcher exports, support bundles or command logs; optional persistence requires protected storage. Check and redact child-process output too. Start the existing Termux server and test the configured host (historically `127.0.0.1`). TCP reachability alone does not prove authentication, map/UDP connectivity or world readiness.
4. **Recovery and updates.** With the runtime fully stopped, snapshot the prefix, mutable client settings and component manifest; stage installation/repair/update changes; only promote a validated generation. Keep a rollback path and enough-space checks. Separate large client content from runtime binaries and generated prefix state, but preserve a coherent matching snapshot across updates. Expose failures and allow retry without re-importing all game data.
5. **Device acceptance.** Character selection, world entry, zoning, audio, controller/keyboard input, logout/relaunch, app switching and recovery after an interrupted preparation step. Evaluate performance after correct startup and rendering. Only then resume the standalone server runtime/compiler work.

Next engineering checkpoint is the independent in-app runtime probe, not another GameHub transfer. Runtime choice remains to be qualified; there is no source-verified equivalent of the opaque captured GameHub package in this repository yet. Do not promise a playable APK before that checkpoint and the real client test pass.

## Project boundary

The original goal remains one app for client setup/update/play and eventually server management/builds. For this recovery phase, the working Termux server is the connection target. No server migration, database rewrite or external container repair is necessary to develop the client. Fresh initialization must not read or mutate GameHub's files. An app update must retain the existing LSB application ID/signing certificate and imported files. Existing session exports do not contain a Wine prefix; extend backup format/version explicitly when runtime state becomes available.
