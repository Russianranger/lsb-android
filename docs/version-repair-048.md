# Missing version registry value repair (0.4.8)

## Evidence

The uploaded `patch_pol.zip` has SHA-256
`67a48a2fa818b9b3f91df18256c00fa377cba7d94f5c058829e9541e5f77f1c0`.
Its 548,352-byte polcore.dll matches the active client's recorded SHA-256
`73b1864bf522d3aa6ec28e9f4bb226fcee956f7f4848a238c9c2d756dc703b06`.
The 288-byte patch.ver has SHA-256
`ceae6867bef1cfbf9f2f387f3ae1586e963e9c4c79f7eb62e8424a9ceb92e072`.

Offline inspection of the matching DLL locates GetCommonFunctionTable at RVA
`0x690e`, its table at `0x6fbe8`, and entry 1171 at `0x4a0e0`. This is the
callback called by the supplied FFXiMain immediately after reading patch.ver.
It obtains the selected region's `Interface\\0001` string, derives a decoding
seed, checks the record and copies the version text. A missing registry value
uses a zero accumulator. That is different from the ASCII string `"0"`, whose
accumulator is 0x30. With content ID 1 the two seeds are 1 and 0x31 respectively.

The exact supplied decoder instructions were run locally in isolated x86
emulation. Only the registry-string lookup was substituted; buffer decoding,
checksum, bounds check and text copy remained the original instructions:

| Registry lookup result | Original callback result | Version |
| --- | --- | --- |
| Missing | -1 | No output |
| REG_SZ `"0"` | 0 | `30251204_1` |
| REG_SZ `"0"`, deliberately damaged checksum data | -1 | No output |

Every run preserved the input bytes. This reproduces a specific failure and
its repair at the version callback. It does not prove the whole game starts,
or capture the device's existing Interface value. The app's preparation code
creates InstallFolder and language entries but has not created Interface.
The 0.4.7 device report stops at precisely this read/processing boundary.

The registry dependency is also described by the maintainers of
[XiPackets](https://github.com/atom0s/XiPackets/blob/ebe2216a991ff88254ad600cc5bae8e3d9e80306/patch/packets/0x0007/README.md)
and [Ashita Sandbox](https://github.com/AshitaXI/Ashita-v4beta/blob/4171c74c8ddb2ca2a31654f199e6c1cee40d7256/docs/sandbox/README.md#check-files-showing-unknown-versions).
The [OpenLobby format investigation](https://github.com/PrettyOpenLobby/OpenLobby/blob/a1b4c82458deffcb1f9303e219209f658bbfaa37/tools/patchver.py)
helped identify the supported key offline. Its source is not bundled or
incorporated. Our small read-only C verifier is derived from the inspected DLL
operations and checked against the original decoder. It validates the final
length/checksum block as well as the version payload.

## Repair behavior

Immediately before launching xiloader, the native helper checks the selected
region's 32-bit `Software\\PlayOnline[US|EU]\\Interface` key in the app-owned
Wine prefix. JP uses `PlayOnline`, US `PlayOnlineUS`, EU `PlayOnlineEU`.

- If `0001` exists, its value and type are preserved without reading its contents
  into diagnostics. No other installation's key is guessed or replaced.
- If it is missing, read only the supplied game's patch.ver. Require exactly
  288 bytes, the `"0"` decoding format, length 280, the format checksum, zero
  padding and a bounded `eight digits + underscore + alphanumeric suffix`.
- Only after that verification, create the missing REG_SZ value `"0"`, then
  read it back. If readback fails, remove the newly written value and report
  the error and rollback result. A failed registry operation stops launch.
- Missing, unreadable, other-key or unrecognized version data is left alone;
  the regular launch path and existing startup diagnostics remain available.

The receipt adds `version_config` with a fixed state, validated version text,
Windows error and rollback error. `restored_missing` identifies an applied
repair; `existing_preserved` identifies subsequent launches or existing setups.
No game bytes, original loader, renderer, dependencies or accepted generation
are replaced. No command prompt, whole-client copy, re-registration or game
version override is involved. The helper gets the manifest's validated game
directory explicitly, separately from the loader's bootloader directory.

## Verification

The local read-only verifier decodes the supplied file as `30251204_1`, matching
the original DLL. Its separate synthetic `20260921_1` record is accepted;
single high-bit changes at all 288 positions are rejected. Native regression
coverage checks JP/US/EU, missing-only repair, idempotence, existing string/type
preservation, truncated/corrupt input, denied write, failed-readback rollback
and byte-for-byte source preservation. The synthetic record contains no user
or game data. The launch suite adds first repair and subsequent preservation
cases, executed under both ARM64 Wine/Box64 and PRoot.

Local compilation and 34 Python contracts pass. Full CI and APK identity are
recorded in validation after the build completes. No actual game rendering is
claimed by the synthetic tests or offline decoder checks.

## Device test

Install 0.4.8 over the current app. Keep the accepted preparation, runtime and
display settings; leave **Capture FFXI startup result** enabled. Start the
existing Termux server and launch once. If startup stops again, export fresh
Diagnostics; check `version_config` first, then the later startup events.
No re-import or new preparation is needed. If this repair is applied, the next
question is whether the client reaches Windows/DirectPlay checks and opens its
game window.
