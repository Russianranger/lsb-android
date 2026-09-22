# Android display startup crash: 0.5.1

The first 0.5.0 Thor test reports that both **Launch FFXI** and **Open FFXI gamepad setup** close the Android app immediately. The supplied support ZIP (`7f3097f8f9658d76fc035294f9bf8cd98d8c3dbf1bc4fb45a7907a75198212c9`) identifies 0.5.0 on Android 13 / API 33. It contains the preceding successful runtime session, with no fresh Wine session or Android exception stack. Those old logs cannot establish a new Wine, graphics, audio or server failure.

## Reproduced failure

Both buttons open `RuntimeActivity`. Its new 20 ms controller timer starts while `ClientRuntime` is still preparing the runtime. `sessionId` initially contains null. The first timer tick copies null into `padSession`; the second tick invokes `padSession.equals(...)`, throwing an uncaught `NullPointerException` on the Android main thread. Runtime preparation may scan and stop owned processes before publishing a session, so the display cannot assume a session exists when it resumes.

The actual unmodified 0.5.0 Activity was executed with Robolectric's Android 13 framework and a deliberately delayed runtime session. Two of three tests failed with this stack:

```text
java.lang.NullPointerException: Cannot invoke "String.equals(Object)" ... is null
    at io.github.russianranger.lsb.RuntimeActivity$1.run(RuntimeActivity.java:27)
    at android.os.Handler.handleCallback(Handler.java:942)
    at android.os.Handler.dispatchMessage(Handler.java:99)
```

This is a reproduced application defect consistent with the device symptom, not a stack captured from the Thor. The previous Wine/Box64 CI suite exercised the native joystick but never ran this Android Activity.

## Fix and regression coverage

0.5.1 reads the worker's session ID once per tick, compares it safely when null, and waits for a session before mapping controller state. The session field is volatile to publish it between the worker and UI threads. Losing window focus closes the existing mapping after publishing neutral controls, without opening a stale run file. Resuming input reopens the current mapping.

With JDK 17, Maven 3 and Android SDK 35 installed, `python3 scripts/test-android.py --android-jar /path/to/android-35/android.jar` compiles the actual app classes and runs three Android 13 tests:

- Open the display before a session exists, advance repeated timer ticks, then publish a session and verify button press/release in the real mapped state file.
- Hold a button, lose/regain focus, replace the session, and pause/resume the Activity; each transition releases held input and the new session receives input.
- Open and change focus with no session while a previous run's state file exists; the previous file remains untouched.

The pre-fix code fails the first and third tests; the fixed code passes all three. The `verify` CI job now requires these tests before packaging the APK. Test libraries are confined to `out/android-tests` and are not packaged in the app. These JVM framework tests do not execute Wine or establish physical Thor controller compatibility.

Wine/Box64, the native gamepad helper, RGB565 display implementation, audio, version-registry repair, client preparation, xiloader and all server sources are unchanged from the completed 0.5.0 CI recovery. [Full CI run 35671938279](https://github.com/Russianranger/lsb-android/actions/runs/35671938279) passes all five gates, including the Android lifecycle regression and both native runtime environments. See [validation](validation.md) for the complete result and signed APK identity.

## Focused Thor retry

1. Install **LSB-Android-0.5.1.apk** over the existing app. Keep app data, the accepted preparation and current runtime.
2. Start the **existing working Termux server**. Under Client, keep the accepted renderer, resolution, server address and startup-capture setting. Launch FFXI once; confirm the display stays open, login still succeeds and audio is clean. Then stop normally.
3. Open **Controller → Open FFXI gamepad setup**. Check that it stays open and receives buttons, both sticks and the D-pad. Release the controls and check they return to neutral. Hold a control while opening the app menu or switching away, then return and confirm it is released.
4. Stop and launch once more. If either screen still closes or input fails, reopen the app and export fresh Diagnostics, noting the button pressed and how long the display remained visible.

Keep client `30251204_1`, the original matching xiloader and existing server source. Do not update toward `30260904_1` or start the managed-server migration during this retry. Managed-server acceptance follows recovery of the working client baseline and controller checks.
