# GameStart boundary trace (0.4.6)

The 0.4.5 device report `lsb-support (2)(2).zip`, SHA-256
`f8b8c13088bd2ea888195f2fe27c32e4e6e19d4f48399a2b808ad264051378f2`,
records session `a0898d38-3a79-4290-9d76-8c1dbd237c8f`. Loader PID 284
closed with exit 0 after 15,688 ms. All 20 dependency loads passed; windowed
settings were already correct. POL, FFXI, FFXiMain, D3D8 and D3D9 were observed,
but no game window or standard dialog was sampled and no DXVK initialization
message was captured. Absence of a message is not proof that an API was never called.

The missing-module status `0xc0000135`, exception 1722 and RpcSs error belong to
PID 292, not loader PID 284. The loader's `0x80000026` is a first-chance exception;
it is not a captured fatal game exception. These warning patterns also occurred
in successful synthetic runs. Installing unrelated runtimes or replacing the
renderer is not justified by this evidence. Keep the accepted generation
`768f3a6d-8dfb-462f-8b9d-46cdd7101505` and source-loader SHA-256
`78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`.

## Why a direct trace

[xiloader v2.0.0 main.cpp](https://github.com/LandSandBoat/xiloader/blob/9679ac443f755f9e11cb672e901aee17c936b453/src/main.cpp)
ignores `IFFXiEntry::GameStart`'s HRESULT and returns success after cleanup.
[ffxi.h](https://github.com/LandSandBoat/xiloader/blob/9679ac443f755f9e11cb672e901aee17c936b453/src/ffxi.h)
defines the six-slot IFFXiEntry interface; [ffximain.h](https://github.com/LandSandBoat/xiloader/blob/9679ac443f755f9e11cb672e901aee17c936b453/src/ffximain.h)
defines seven-slot IGameMain. This establishes an instrumentation target, not
the exact source provenance of the user's imported binary.

## Behavior and recovery

Client has **Capture FFXI startup result**, initially checked and persisted as a
boolean preference. Unchecking launches the exact original executable directly.
Tracing verifies the existing loader hash, creates an exclusive temporary PE32
copy beside it, and adds an import of the app's `P:\startup-trace.dll` in a new
section. Original code sections, entry point, RVAs and imports remain in place.
Unsupported layouts fail before launch and explain how to use the original.
The diagnostic copy is unsigned and receives a different filename and checksum;
it is not a byte-identical execution mode. No original loader is overwritten.

The helper runs the temporary image using the existing working directory and
pipe-only credential transport. The observer hooks the executable's normal
CoCreateInstance import, matches the known FFXI class and interface GUIDs, and
wraps only that object's GameStart slot. Its FFXI DLL import is observed similarly
for IGameMain. Other interface slots, object identity, arguments, return values
and returned LastError are retained. Exceptions propagate. No account/network,
display, runtime, COM registration or game-content changes are added.

Only fixed stage labels, Windows PID/TID, HRESULT/Win32 code and a numeric detail
are written to the private pipe. `*_com_return` detail is object-present (0/1),
`*_import_hooks` detail is patched-import count, and `*_return` for game calls
uses elapsed milliseconds. Raw addresses, pointers, strings, COM objects and
credentials are never serialized. These records take priority over indirect
Wine warnings within the existing 64-row bound. Source, observer and temporary
image SHA-256 values identify the experiment in the launch receipt.

An early exit after login now includes GameStart's HRESULT and elapsed time when
the record belongs to the observed child PID. S_OK and S_FALSE without a window
are still incomplete startup, not verified gameplay. Failed HRESULTs remain
evidence; this build does not change them or claim to fix their cause.

The temporary copy is unlinked on completion, failure and requested Stop. A
device power loss/app force-kill can leave a small uniquely named copy in the
prepared working loader directory; it cannot overwrite the original and is not
selected on the next launch. Automatic deletion of unknown leftover files is
deliberately avoided. Imports/backups and the matching prepared prefix survive.

Instrumentation changes process layout and adds a DLL, so it can affect behavior.
Zero import-hook count, missing hook-ready or missing return records explicitly
limit conclusions. Delay-loaded/dynamically resolved COM APIs and other game
interfaces are not intercepted. Visible windows still do not establish frames,
character selection, world entry or stability.

## Device procedure

Install 0.4.6 over the current app, keep the prepared client and display settings,
leave **Capture FFXI startup result** checked, start the Termux server and launch
once. Export fresh Diagnostics afterward. No re-import, full preparation,
GameHub transfer, console command or new rootfs download is needed. If this
diagnostic mode itself cannot start, its error can be compared with an ordinary
launch by unchecking the option, without changing the imported loader.

## Verification

Unit contracts cover PE layout rejection, preservation of original sections and
files, exclusive output, source-hash mismatch, boolean request validation,
privacy/bounds and child-PID correlation. Real Wine/Box64 and PRoot fixtures add
GameStart S_OK, S_FALSE, E_FAIL, nested IGameMain E_OUTOFMEMORY, unhandled exception,
Stop while inside GameStart and tracing disabled. They check unchanged HRESULT,
LastError, object identity/arguments, exception exit, copy cleanup and original
hash. Existing launch, preparation and graphics/input/audio regressions remain.
The actual proprietary FFXI startup result requires the next device report.
