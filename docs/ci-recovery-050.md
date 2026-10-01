# 0.5.0 CI recovery from 95c94beb

This continues `95c94beb4f272814ffcde471584ecad253b5dd82` on `codex/client-baseline`. The 0.5.0 feature implementation is retained. No Wine, Box64, Turnip, DXVK, audio, proprietary client, xiloader, preparation or version-repair code is changed by this recovery.

## Server toolchain

The Ubuntu 26.04 ARM64 bootstrap installed `gcc-15` and `g++-15`, but Python's extension configuration invokes the unversioned `aarch64-linux-gnu-gcc`. The versioned compiler package alone did not supply that command. Installing `build-essential` supplies the native default compiler and development tools; a bootstrap check now resolves Python's actual configured compiler before marking the runtime ready. The same bootstrap runs in CI and on the device. No compiler symlink, cross-architecture package, prebuilt Python wheel or skipped database update is used.

The real MariaDB integration test exercises import/export, rejection of bad SQL without replacing the active deployment, a cloned source/database update with account/character preservation, rollback in both directions, and managed start/stop. Its server processes are synthetic ARM64 executables; this does not claim a full LandSandBoat build or managed-server gameplay.

## Controller library investigation

- Packaging: `assets/runtime/liblsb-gamepad.so` is included in the APK and copied by `ClientRuntime.assets()` to the backend directory bound at `/opt/lsb`. CI mounts the helper at the same guest path. The downloaded CI APK's helper bytes match its uploaded native artifact.
- Architecture: the helper is ELF64, little endian, AArch64. It belongs to the Linux/glibc host running Box64, not to the x86 Wine guest or Android's Bionic loader. Rebuilding it as an x86 library would change the wrong boundary.
- Dependencies: the intentionally small `-nostdlib` helper has no `DT_NEEDED` entries. Its undefined functions resolve from the glibc host, and it locates SDL dynamically after Wine initializes the joystick subsystem. The runtime test now checks ELF identity and uses `ctypes.CDLL(..., RTLD_NOW)` in the actual ARM64 rootfs to prove every host symbol resolves.
- Preload: the host loader consumes `LD_PRELOAD` before Box64 starts. [Box64 0.4.4](https://github.com/ptitSeb/box64/blob/v0.4.4/src/core.c) also copies `LD_PRELOAD` into its emulated preload list, where this custom native library cannot be resolved as a guest library. Its `cannot pre-load` warning is therefore not evidence of native-load failure. Removing `LD_PRELOAD` would remove the working native injection. The preload mechanism remains unchanged.
- Runtime evidence: the diagnostic run recorded successful native symbol resolution, a fresh bridge attachment, and DirectInput enumeration of `Virtual Joystick` with 4 axes, 16 buttons and 1 hat. Its old stage-0 assertion received a neutral state (`button0=0`, `x=32767`, `hat=4294967295`), rather than failing library loading or enumeration.

## Test synchronization

The old writer held its first input before asynchronous Wine/SDL device creation and before DirectInput acquisition. The fixture now begins neutral, then signals a press only after acquiring the device. It checks press, release, another confirmed press, and neutralization after the writer actually stops refreshing its heartbeat. The previous stale check followed a neutral state and could not prove release of held input.

Stage files are replaced atomically: an empty/partial stage must never be interpreted as an instruction to publish neutral input. Failure logs retain DirectInput HRESULT, button, axis and hat values. The actual bridge, input thresholds, enumeration and assertions remain in use; no test is skipped and the Wine/Box64 client baseline is preserved.

See [validation](validation.md) for the completed CI run and signed device artifact, and [device sequence](milestone-050.md) for the first Thor test.
