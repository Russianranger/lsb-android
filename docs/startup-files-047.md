# Startup file and platform checks (0.4.7)

## Evidence from the supplied DLLs

`ffxi_lsb.zip` contains exactly the two files requested after the 0.4.6 report.
Their hashes match the active generation's recorded files:

| File | SHA-256 |
| --- | --- |
| FFXiMain.dll | `d528142a9bfb7767b4d4574dde531ea87a673670c81892f1eab37563b82c0235` |
| FFXi.dll | `d58b652ff3bb2de0b4f7dd4bb51eccc80f39d26de4bfb01217d7a7eb5faaa57f` |

Both have a packed POL1 section. Offline inspection reproduced the DLL's own
literal/back-reference unpacking routine and checked output lengths against the
PE text-section virtual sizes (3,298,350 and 67,992 bytes). Only analysis copies
were unpacked; no proprietary binaries, disassembly dumps or unpacked client
files are included in the repository, APK or CI.

The GameMain interface table at RVA `0x327ba0` points slot 3 to `0x155b0`.
That wrapper calls `0x155e0`. The only literal `0x88770000` in the reconstructed
FFXiMain image initializes the default failure result at RVA `0x155fd`. Several
failed checks converge on cleanup at `0x1582d`, returning that default value.
It therefore does not identify one Windows error, DLL or graphics failure.

| Before/at the first game-window creation | Call site RVA | Relevant behavior |
| --- | --- | --- |
| Argument-string setup | `0x15614` | Allocation/copy can fail |
| Configuration-object setup | `0x1566d` | Allocation/initialization |
| File-manager setup | `0x156be` | Initial file tables and version data |
| Windows/DirectPlay checks | `0x156cb` | OS version, then dynamic DLL load |
| Window setup | `0x15704` | Window class and window creation |

The normal file-manager path opens the root `FTABLE.DAT` and `VTABLE.DAT`.
Version initialization reads `patch.ver` into a 0x120-byte buffer and invokes
a PlayOnline common-function-table callback through RVA `0x30f2a2`; a failed
read or negative callback result returns failure. This callback is not defined
by the supplied DLL pair, so its precise validation rules are not established.

The platform function at `0x15dc0` calls `GetVersionExA`. Its next check at
`0x15eb0` loads `dpnhpast.dll`, tests the handle and frees it. The exact pinned
runtime archive (SHA-256 `08c639c26506dc6fbd15464bec475337087bb23cb7c0c5ace2db5240ee36424f`)
contains both i386 and x86_64 Wine copies of that DLL. Presence in an archive
alone does not prove it loads on this device, but it rules out a claim that this
runtime simply omits DirectPlay. No runtime replacement is justified by that
hypothesis. Wine's implementation is available in the
[Wine 10 source](https://github.com/wine-mirror/wine/tree/wine-10.0/dlls/dpnhpast).

FFXi.dll itself sets the working directory before calling GameMain. Its
GameStart call site at RVA `0x4eab` does not preserve the inner HRESULT as its
own return, consistent with the 0.4.6 device trace. There is no basis yet to
change the loader's working directory, recreate registry keys or fabricate a
version file.

## What the next build observes

The same opt-in **Capture FFXI startup result** mode now observes these normal
imports of the loaded FFXiMain module: CreateFileA, ReadFile, GetFileSize,
CloseHandle, LoadLibraryA, GetVersionExA, RegisterClassA, CreateWindowExA and
Direct3DCreate8. The API wrappers forward the original arguments, result and
returned LastError. No game instruction or imported client file is patched;
process-local import slots are redirected only in this diagnostic run, as the
existing COM observer already does. Tracing off still executes the original
loader directly.

File tracking covers only basenames `patch.ver`, `FTABLE.DAT` and `VTABLE.DAT`,
with 16 bounded handle slots. It records opens, size queries and the first read
per tracked handle; successful CloseHandle clears tracking. No bytes read from
the files, arbitrary paths, titles, pointers or credentials enter diagnostics.
The existing 64-record bound and numeric/fixed-label parser remain. These are
API observations, not proof of file correctness or successful gameplay.

Before GameMain, `main_directory` compares the current directory with the
loaded FFXiMain module's parent without serializing either path. Code is a
Windows error when directory retrieval fails; detail 1 means equal, 2 different,
0 comparison unavailable. Other records are:

| Event | code | detail |
| --- | --- | --- |
| main_import_hooks | 0 | Number of imports redirected |
| main_file_open | Windows error or 0 | File ID in top byte; low bits: handle present |
| main_file_read | Windows error or 0 | File ID in top byte; low 24 bits: bytes read |
| main_file_size | Windows error or 0 | File ID in top byte; low 24 bits: size, capped |
| main_directplay_load | Windows error or 0 | Handle present |
| main_windows_version | Windows error or 0 | Major/minor in top two bytes; platform in low 16 bits |
| main_window_class | Windows error or 0 | Registration succeeded |
| main_window_create | Windows error or 0 | Handle present |
| main_d3d8_create | 0 (API has no HRESULT) | Object present |

File IDs: 1 patch.ver, 2 FTABLE.DAT, 3 VTABLE.DAT. Zero-byte reads can be valid
EOF. The size field caps at 0xffffff. Absent records are not proof that an
operation never occurred: only normal imports in this module are observed;
other modules, dynamically resolved APIs, wide-character file APIs and later
reads are outside this trace. Directory comparisons also have MAX_PATH limits.

The launch receipt independently inventories these three files from the current
prepared game root. It distinguishes missing, readable (with byte size),
unreadable, non-regular/symlink and ambiguous-case entries. It reads at most one
byte, saves no contents and does not reject launch based on this inventory.
The old `Unknown (no readable patch.ver)` version label only meant its text
pattern was not found, and never proved that the file was absent.

## Device procedure and limits

Install 0.4.7 over the current app, keep the existing preparation and display
profile, leave Capture checked, start the Termux server and launch once. Export
Diagnostics after it exits (or Stop and export if it remains black). No import,
preparation, GameHub copy, new rootfs download or additional DLL upload is needed.
This build identifies the next failing operation; it is not a confirmed game fix.
Do not replace or create client version/data files until the next receipt gives
specific evidence.

## Validation

Local verification: 116 JVM checks, 33 Python contracts, native helper/fixture
compilation, Android packaging and v2/v3 signature verification with the original
certificate. The added runtime fixture reads a known file, observes a missing
file with error 2, checks file size and DirectPlay/OS calls, returns inner
0x88770000 and outer S_OK, and checks that observation preserves behavior.
Full validation [passes](https://github.com/Russianranger/lsb-android/actions/runs/35634762962): 36 native Windows checks and all 26 launch scenarios in each of ARM64 Wine/Box64 and PRoot. The file-observation scenario passes in both. These are synthetic tests; proprietary game startup remains a device gate.

The signed device artifact is `LSB-Android-0.4.7.apk`, versionCode 14,
7,747,758 bytes, SHA-256 `f54de83554832ba7011cef3c797835d2c17a5caeac31b67f5448349af7292c0d`. Observer DLL SHA-256
`d144c864e970e21236bbdf36ddec3446580d6edf6ffc42fbb5fddb1d5d408686`. It uses the original signing certificate
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
