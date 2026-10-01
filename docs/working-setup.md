# Working setup evidence

User: AYN Thor Max, Android 13, Snapdragon 8 Gen 2 / Adreno 740, 16 GiB RAM.
The four screenshot filenames contain 2026-09-13 21:28. The conversation environment date is 2026-09-14 UTC.

## Directly visible in screenshots

| Field | Exact value |
| --- | --- |
| Compatibility Layer | proton10.0-arm64x-2 |
| Translation Params | Game Presets |
| DInput Library | Prefer Native |
| Skip Audio/Video Decode | Off |
| GPU Driver | turnip_v26.0.0_R2 |
| Audio Driver | Pulse |
| DXVK Version | dxvk-v2.7.1-1-async |
| VKD3D Version | vkd3d-proton-2.14.1 |
| CPU Translator | Fex-20251029 |
| CPU Core Limit | 6 Core |

| Component | GameHub package version |
| --- | --- |
| cjkfonts | 1.0.0 |
| gdiplus | 1.0.0 |
| dx8vb | 1.0.0 |
| dotnet462 | 1.0.0 |
| dotnetcoredesktop8 | 1.0.0 |
| win7 | 1.0.0 |
| mono | 1.0.0 |
| vcredist2022 | 1.0.0 |
| base | 1.0.1 |

These package labels do not identify the exact Microsoft/Wine DLL builds. A listed component is not evidence that it was individually necessary. Do not replace the captured environment with a newer generic Wine/Box64 preset, or substitute a generic Winetricks command for an opaque GameHub package and label it equivalent.

## History actually available

- The user reports installing PlayOnline and FFXI to initialize registry state, then replacing both installed directories with updated contents.
- Bootloader initialization required additional work.
- Prior user context identifies GameHub Lite 5.3.3 and xiloader v2.0 CLI autologin.
- The initial personal-context search returned the recent feasibility assessment, not the original successful setup transcript.
- A follow-up search on 2026-09-20 recovered a prior user report dated 2026-01-05: install Gecko, VC++ 2015-2022 and Mono; run the official PlayOnline/FFXI/DirectX installers inside the container to create installation state; replace the PlayOnlineViewer and FFXI payloads with a fully PlayOnline-updated backup. This is recovered conversation evidence, not a fresh device trace or a complete verified component order.
- That report identifies xiloader 2.0 and the working argument shape `--server 127.0.0.1 --username <account> --password <secret>` because interactive terminal login was unreliable. No credential values were retrieved or stored. The future in-app UI must support account entry without requiring a terminal. The exact successful executable hash and complete invocation/environment remain unverified.
- On 2026-09-20 the user reported that transferring files corrupted the GameHub Lite container. The external client is unavailable; Termux's LandSandBoat server survives. There are no corruption logs establishing the cause. Do not require the user to reconstruct GameHub before continuing.

Still needed: translator preset internals, complete component installation order, exact bootloader binary/environment, DLL overrides not visible in the screenshots, runtime DLL hashes and a successful in-app launch log. Support bundles identified imported game/loader hashes, but do not prove those files launched. The current repair recipe is a new source-derived proposal, not a claim to reproduce a recovered command. See [current handoff](HANDOFF.md) for the fresh-container path.

## Source findings used for the baseline

- [xiloader registry handling](https://github.com/LandSandBoat/xiloader/blob/main/src/functions.cpp): US/EU/JP registry branches, 32-bit registry access, install-folder value `1000`, and region-specific language handling.
- [xiloader initialization](https://github.com/LandSandBoat/xiloader/blob/main/src/main.cpp): PlayOnline and FFXI COM activation, PlayOnline DLL pattern lookup, and `--server` / `--lang` arguments. A registry-only repair cannot guarantee these later stages succeed.
- [xiloader build](https://github.com/LandSandBoat/xiloader/blob/main/CMakeLists.txt): x86-only build. [README](https://github.com/LandSandBoat/xiloader) documents VC2022 x86 / `vcrun2022` for current builds.
- [LSB client guide](https://github.com/LandSandBoat/lsb-wiki/blob/main/Client-Setup-Windows.md): installed client plus official PlayOnline initialization/updating. The baseline does not automate first-time retail login or download proprietary game files.
- [Server `.gitmodules`](https://github.com/Russianranger/LSB-server/blob/base/.gitmodules): `navmeshes` and `ximeshes` are separate sources.
- [Server login configuration](https://github.com/Russianranger/LSB-server/blob/base/settings/default/login.lua): server-admitted client version is distinct from the actual imported client version. Never "update" the game by only changing CLIENT_VER.

The earlier server review was at commit `d8ace891901730bb79c5bf170e976cb4d0400e47`. App source downloads resolve the selected ref afresh and record that commit. ZIP imports report their revision as unverified.
