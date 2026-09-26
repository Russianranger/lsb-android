# Thor loader dependency failure and camera axis: 0.5.2

## Device evidence

The 0.5.1 report `lsb-support (4).zip` (SHA-256 `28d5cdf379804ffe5d6e90a4b31867acaf99341dee731e4f4b06300f9ece3bdf`) records a stopped dependency check, not an xiloader/game crash. Session `fcc6de18-3295-42f4-a56c-da80b650041f` exits the checker with code 1: **WS2_32.dll fails initialization with Windows error 1114; all other 19 imports pass**. The screenshot matches that boundary. No login or game startup occurred in this attempt.

The next controller-configuration session, `68802c94-0d29-4fde-9abc-55652a97611f`, runs for approximately 136 seconds and completes. Its 32-bit FFXiPadConfig process loads WS2_32 successfully, and the native helper reports attachment with 4 axes, 16 buttons and 1 hat. The user reports working controller input except right-stick vertical movement. The previous Android null-session startup failure is no longer observed in these sessions.

The original loader hash remains `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`, with accepted generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`. A missing client DLL, changed loader, rejected credentials or client/server version mismatch is not established by this report.

## Loader handling

[Wine 10 WS2_32 initialization](https://github.com/wine-mirror/wine/blob/wine-10.0/dlls/ws2_32/socket.c) returns the result of `__wine_init_unix_call()`. The failed device log reaches the emulated `x86_64-unix/ws2_32.so` load, then unloads the Windows DLL. It lacks the detailed symbol-resolution/loader warning needed to explain the failed initialization. The successful later load shows the DLL is present and usable in the same prepared prefix. The underlying device-specific failure is **not conclusively reproduced or diagnosed**.

The 0.5.0 helper previously started its SDL polling/dlopen thread in every process inheriting `LSB_GAMEPAD_STATE`, including the checker and xiloader. 0.5.2 limits that worker to the `winedevice.exe` process that hosts Wine's SDL HID bus. Other processes still inherit the native preload, but its constructor does no input work there. This removes unnecessary concurrency from DLL initialization; it is not proof that the thread caused WS2_32's failure. The ARM64 library, its `/opt/lsb` copy location and host `LD_PRELOAD` mechanism remain the same design. The Box64 duplicate guest-preload warning is explained in [the earlier investigation](ci-recovery-050.md).

Before starting xiloader, the app permits one fresh checker process only when a complete, correctly ordered 32-bit receipt reports exactly the observed failure: WS2_32 error 1114, all other imports loaded, and exit code 1. The new process checks **every import again**. A repeated error, missing DLL, malformed/partial receipt, different failed DLL, abnormal exit or Stop still blocks launch. This is bounded recovery for an initialization failure, not permission to ignore it. Both attempts and their exit codes remain in `client-launch.json`; the retry has its own bounded log. Checker-only Wine module warnings and Box64 symbol errors aid the next diagnosis. The checker receives no credentials. Game-process output retains its existing privacy filter.

The UI now reports the failed DLL and Windows error instead of only “checker exited with code 1.” All-success receipts still require a clean checker exit. The existing disposable checker's load-only DLL lifetime policy is retained.

## Right-stick vertical axis

[Wine 10's SDL bus](https://github.com/wine-mirror/wine/blob/wine-10.0/dlls/winebus.sys/bus_sdl.c) assigns joystick slots to **X, Y, Z, Rx, Ry, Rz** in that order. The four-axis helper published Android X/Y/Z/RZ into slots 0–3, exposing the last value as **Rx**, while the app instructed users to assign camera axes Z/Rz. Rz was absent. This is a concrete mapping defect.

0.5.2 exposes seven SDL slots. Android right-stick vertical now goes into slot 5, producing DirectInput Rz; Rx, Ry and the unused slider remain centered. X, Y, Z, buttons, hat and stale-input release keep their existing meaning. The attachment receipt identifies `axes: 7` and `stick_axes: X,Y,Z,Rz`. FFXI's existing controller preferences are preserved; users may need to reselect the virtual controller and assign Z/Rz in its setup after this descriptor change.

The first six-slot candidate was rejected by [CI run 35717522922](https://github.com/Russianranger/lsb-android/actions/runs/35717522922): the helper attached six axes, but DirectInput reported five axes and ten buttons, so the descriptor assertion failed. Wine's `sdl_add_device()` classifies exactly six axes with at least fourteen buttons as a gamepad, triggering its Xbox mapping. Adding the neutral seventh slider retains the generic descriptor without altering Wine, reducing buttons or relaxing input assertions. The real test requires all seven axes, sixteen buttons, both signs of X/Y/Z/Rz and a centered unused slider. The rejected candidate was not delivered.

## Verification

Implementation `3ea8a01a2886b43ed3ac43885a39adf3b88ce21b` (following `aa33cf57dbff428408aac3bb5e1311c68b5b8c8b`) adds:

- Android 13 Activity tests for both signs and neutral values on all four stick axes, alongside the three startup/focus/session regressions.
- Native ARM64 preload symbol resolution and an unrelated-process test proving no controller worker starts there.
- Actual Wine/Box64 and PRoot DirectInput checks for X/Y/Z/Rz in both directions, neutral Rx/Ry/Slider, button/hat release and heartbeat expiry.
- The actual 20-import Thor list checked twice with the controller preload enabled, in addition to baseline DLL lifetime, missing-DLL and attach-crash checks.
- Replay of the device's dependency receipt to verify one successful retry, persistent failure, cancellation and rejection of unrelated or incomplete failures.

See [validation](validation.md) for the final full CI result and signed APK identity. Native integration uses synthetic Windows/server fixtures; it cannot establish FFXI login, audio or physical camera behavior on Thor.

## Focused Thor retry

1. Install **LSB-Android-0.5.2.apk** over the existing app. Keep app data, current runtime, accepted preparation and display/audio settings.
2. Start the **existing working Termux server**, then launch the current client once. Confirm login/world entry and clean audio. If launch stops, export Diagnostics immediately so the checker attempts and symbol log are retained.
3. Open **Controller → Open FFXI gamepad setup**. Select the virtual joystick if needed and assign the camera to **Z/Rz**. Check left/right and up/down on the right stick, then release and verify neutral. Check movement stick, buttons and D-pad.
4. Return to the game and check camera movement in both directions. Stop and relaunch once; confirm held input releases when opening the app menu or switching away.

Keep client **30251204_1**, the original matching xiloader and existing server source. Do not update toward **30260904_1**, re-import/re-prepare the client or begin managed-server migration during this retry. The pinned Wine/Box64/graphics/audio components and version repair are preserved.
