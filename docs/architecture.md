# Baseline architecture

`MainActivity` provides Client/Profile/Server/Diagnostics. `WorkService` runs one transfer/validation task with a foreground notification, bounded wake lock, progress and cancellation. Jobs capture application state, not an Activity. No credentials are stored.

`core/` is plain Java and is exercised by the JVM test harness. `SafeZip` streams ZIP/ZIP64 archives rather than loading game data into memory. Extraction rejects traversal, absolute/drive paths, duplicate files, conflicting Windows path casing, excessive nesting, more than 500,000 entries and more than 80 GiB expanded content. It reserves 512 MiB free space. Archive symlink records are extracted as ordinary files and cannot create filesystem links; existing filesystem links are rejected during inspection/export.

`ClientInspector` detects one installation by `FFXi.dll`, `FFXiMain.dll` and `polcore.dll` / `polcoreeu.dll`, requires ROM data, checks PE32/x86 headers and hashes only the key executables. It does not certify completeness of every DAT, runtime dependencies, COM registration or server compatibility.

`ClientStore` maintains `current/`, `previous/`, and `incoming/`. A full extraction and inspection occurs before directory promotion. No game executable is run during import. Full update imports optionally preserve the old FFXI USER and PlayOnline usr trees into staging. Restores use their backed-up settings without overlaying the old personal files. Previous-client rollback is a directory swap with recovery for interrupted renames. Staging leftovers are removed on the next import. A rollback keeps only one previous full client.

`RepairPackage` emits a reviewable CMD recipe for the selected region. It resolves paths relative to an exported package, uses the 32-bit registry view and registration tool, stops on registration errors and preserves the first available install-key snapshot. It does not mark registration successful within the Android app. COM undo requires a Wine-prefix backup. Paths containing unsupported CMD-sensitive characters are rejected at generation. The source-derived DLL registration sequence requires on-device qualification.

`SourceImport` stages an LSB source ZIP. Online mode accepts GitHub owner/repository and branch/tag/SHA, obtains a full commit SHA, then downloads the archive at that SHA. HTTPS redirects are restricted to GitHub API/archive hosts. Offline source ZIPs cannot prove their revision. Missing mesh contents are surfaced; source acquisition is not a compiler or runtime installation.

## Intentionally absent runtime boundary

The captured Proton arm64x/FEX environment cannot be truthfully represented by bundling unrelated generic Wine/Box64 binaries. Version strings alone do not prove binary equivalence. This baseline records that boundary explicitly. A future runtime implementation must provide install/verify, create-prefix, x86 Windows process launch, COM-probe results, display/input/audio, process supervision, and exit/crash reporting. It must pass real client startup and world-entry tests before the app enables an in-app Play flow.

The standalone server backend needs an execution strategy compliant with Android's target-SDK restrictions, an ARM64 Linux or Android-native dependency bundle, MariaDB, all LSB processes, backup-aware migration, mesh import, and local-address/port configuration. An isolated toolchain can then compile selected source revisions. No interface in 0.1.1 claims these capabilities exist.

## Multiple PlayOnline versions (0.1.1)

Inspection lists every `polcore.dll` / `polcoreeu.dll` relative path. Without a saved choice, multiple candidates create a pending import under `session/incoming`; the active client remains in place. A small metadata file supplies UI choices without rescanning the full installation on the main thread. Finishing validates the explicitly selected DLL before activation. A failed selection or cancelled finish keeps the extracted payload available; discarding it removes only staging.

`session.properties` adds an optional `polCore` relative path. Older settings remain readable. New imports resolve or request a choice, then persist it with inventory and candidate metadata. Validation, backup/restore, rollback, repair and launch exports use that same path. Choosing an EU DLL selects EU; US versus JP remains explicit because both use `polcore.dll`. Unselected DLLs are retained. If several loaders are present, the one beside the selected viewer is used; otherwise a separate loader import may be needed.

## PlayOnline installation root (0.1.2)

The DLL's parent directory is not necessarily the viewer installation root. For the standard `PlayOnlineViewer/viewer/com/polcore*.dll` layout, keep `PlayOnlineViewer` as the installation root and retain the complete selected DLL path separately. The registry `InstallFolder/1000`, launch working directory, updater executable and preserved `usr` directory use the root. COM registration uses the precise DLL path. A `pol.exe` file must exist at the resolved root before a repair package can be generated.

This root/COM distinction matches [xiloader's registry folder lookup](https://github.com/LandSandBoat/xiloader/blob/main/src/functions.cpp) and [its separate COM initialization and install-folder callback](https://github.com/LandSandBoat/xiloader/blob/main/src/main.cpp). Wine execution remains unverified.

A path with a `patchfiles` component is labelled as an update cache, sorted after other candidates and rejected by repair/launch export. It remains importable/backuppable; a saved cache selection is not silently replaced. The user can select a live copy without re-extraction. Support reports now include all candidate paths, selected root, `pol.exe` presence and an actionable warning. Only the known cache name `patchfiles` is classified; this is not a universal backup-folder detector.

## GameHub executable entry point (0.1.3)

`windows/launcher.c` builds as a native i386 GUI PE with MinGW-w64 and static GCC support. Only Windows system DLLs are imported. Its INI contains package-relative paths and the validated host/region; no credentials. Android can export a full prepared package or a small launcher-only update.

Registration writes the 32-bit HKLM installation/language values. Each selected client DLL registers in a separate 32-bit helper process via `LoadLibraryExW` and `DllRegisterServer`, with the DLL folder as the working directory. A load error, failed HRESULT, worker crash, or 60-second registration timeout stops the sequence. Existing writes may remain; launch is always a separate button. A first-value diagnostic snapshot is retained but does not claim to restore COM state.

The launcher uses explicit executable paths with `CreateProcessW`, never a command shell. xiloader gets a new console for interactive credentials; console input/output is not copied into the helper log. The GUI stays responsive and alive while waiting for the client process. The updater button similarly starts `pol.exe`.

Windows CI uses only test DLLs and process-local registry overrides, following Microsoft's [RegOverridePredefKey](https://learn.microsoft.com/en-us/windows/win32/api/winreg/nf-winreg-regoverridepredefkey) behavior. Production compilation omits the test override and command-line test actions. See also [DllRegisterServer](https://learn.microsoft.com/en-us/windows/win32/api/olectl/nf-olectl-dllregisterserver) and [CreateProcessW](https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw).
