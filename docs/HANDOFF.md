# Active implementation: in-app runtime probe 0.2.0 (2026-09-20)

The user confirms **both imported FFXI data and a backup survive**, and authorized the first runtime milestone. Do not ask for them again or request a GameHub test. The current work implements a fresh, isolated runtime with an embedded display/audio/input path and an open x86 registry/COM/D3D8 probe. See [runtime-milestone-020.md](runtime-milestone-020.md) for setup, candidate choice, ownership boundaries and test interpretation.

The first candidate is explicitly Wine 10 WoW64 / Box64 0.4.4, reused from the source-tracked TRASC runtime. This is not a claim that it recreates the captured Proton/FEX environment. FFXI registration, prerequisites and xiloader launch are not part of this probe build. The existing app import and backup are retained and not mounted by the runtime.

Implementation is on the existing `codex/client-baseline` branch / draft PR #1. Implementation commit `e07f29c32a99cec2d26a5f39e3866ccc9369a24b` passed the APK, native Windows and ARM64 runtime gates in [run 35530166981](https://github.com/Russianranger/lsb-android/actions/runs/35530166981). The original-certificate 0.2.0 APK and exact evidence/limitations are recorded in [validation.md](validation.md). Runtime publication follows the passing gates. Thor acceptance is still pending. Preserve the existing LSB application ID and signing key. The runtime release mirrors only pinned open components/source archives after verification; it is separate from user client data.

---

# Current scope: fresh in-app FFXI client (2026-09-20)

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
