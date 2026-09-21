# Startup diagnostics 0.4.5

## Device finding

The 0.4.4 report confirms US display values 0001/0002/0003/0004 = 1280/720/1280/720 and 0034 = 1. All changed from the original 640/480/512/512/0, with backup ready and no rollback error. Dependency check: 20 loads passed, check exit 0. Events: autologin_started, login_message_seen, server_connected, loader_closing. Native process exit and bridge exit are 0. No FFXiClass window was observed. Neither a failing instruction nor a GameStart HRESULT was captured.

## Change and boundaries

The launch-only environment enables Wine errors, module warnings, SEH trace, DLL load trace and COM failure messages. DXVK logs at info level to the private pipe, with `DXVK_LOG_PATH=none` preventing unfiltered sidecar files. The existing launcher message allowlist continues separately. The private parser normalizes complete, bounded lines in memory and records at most 64 distinct metadata rows, bounded aggregate counters and a dropped-row counter under `startup_diagnostics` in the existing launch/state reports. It never persists the original trace text. The launch finalizer retains metadata even when Stop arrives before the first progress poll.

Recognized DLL basenames are limited to polcore.dll, polcoreeu.dll, FFXi.dll, FFXiMain.dll, d3d8.dll and d3d9.dll, with native/builtin origin. Wine errors use fixed channel/function categories, severity, and trailing numeric HRESULT/NTSTATUS when recognized. SEH captures the raised exception code without registers, addresses, stack data or exception arguments. Wine IDs are numeric and correlate with `process.observation.child_pid`; records from helper/other Wine processes are not automatically attributed to xiloader. DXVK records initialization-message presence and bounded error/warning categories, not rendered frames or a successful device. Unknown messages retain only fixed category counts; complete messages, paths, arbitrary names and GUIDs are discarded.

Warnings and first-chance exceptions do not automatically stop the client. Existing fatal launcher messages and full Windows exit status still determine failure. This avoids treating recoverable DLL probes or handled exceptions as crashes. Output from multiple processes may interleave, formats outside the allowlist may be omitted, and more than 64 unique rows are counted as dropped. This is scoped evidence, not a complete debugger or full GameStart instrumentation.

The update changes no loader/game bytes, account transport, display policy, runtime components or prepared-generation selection. Launching still uses the selected existing display policy. Install in place and retry once; export Diagnostics after the failure. No import, initialization copy or runtime download is needed.

## Source grounding

Patterns and no-file logging behavior were checked against these primary sources:

- [Wine 10 COM implementation](https://github.com/wine-mirror/wine/blob/wine-10.0/dlls/combase/combase.c): CoCreateInstanceEx failure HRESULT and class-object errors.
- [Wine 10 loader](https://github.com/wine-mirror/wine/blob/wine-10.0/dlls/ntdll/loader.c): module import/load/initialization warnings and status codes.
- [Wine 10 exception handling](https://github.com/wine-mirror/wine/blob/wine-10.0/dlls/ntdll/exception.c): first-chance exception trace precedes handlers, so a trace alone is not fatal.
- [DXVK 2.5.3 logger](https://github.com/doitsujin/dxvk/blob/v2.5.3/src/util/log/log.cpp): `none` path disables file creation while Wine debug output/stderr remains available.
- [xiloader startup](https://github.com/LandSandBoat/xiloader/blob/9679ac443f755f9e11cb672e901aee17c936b453/src/main.cpp): GameStart return is not reported. This reference source is not proof that the imported binary exactly matches that commit.

## Verification scope

Python tests cover real source-format samples, 3-byte chunking, ANSI and UTF-16, EOF, overlong lines, deduplication/caps, unknown labels, and credential exclusion. ARM64 Wine/Box64 and PRoot launch fixtures add a recoverable missing-DLL diagnostic plus a synthetic DXVK error line (must still exit normally), and a real unhandled divide-by-zero exception (must retain full exit status and matching child PID). These fixtures are synthetic, not the FFXI client; actual device diagnosis is still pending.

Final implementation `6563b1e351ef2a9debd32e2eb1684005a6dbd42f` passes [all CI gates](https://github.com/Russianranger/lsb-android/actions/runs/35624389711), including all 18 launch cases in each Wine/Box64 and PRoot environment, 21 Python contracts and the early-Stop regression. See [validation.md](validation.md) for APK identity and corrections found during verification.
