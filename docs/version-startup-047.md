# 0.4.7 device result: failure after reading version data

The new `lsb-support(6).zip` has SHA-256
`8e764064c870abfc1b53ae50d94a53e14905a9dc33618e2c819ea64e05e8f707`.
Session `3f71802c-68ec-4bbb-af86-32b7670f243a` uses the unchanged prepared
generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`. The observer hash matches
the delivered 0.4.7 APK. No application or runtime change accompanies this
analysis; the game startup failure remains unresolved.

## Observed results

All startup records below belong to loader PID 284, thread 288. Ten import
hooks were installed, with no observer failure or dropped diagnostic record.

| Operation | Result |
| --- | --- |
| FFXI and GameMain COM creation | Both S_OK, objects present |
| Working directory | Matches loaded FFXiMain.dll parent |
| Root FTABLE.DAT | Open succeeds; first read succeeds, 217,088 bytes; file size 219,402 |
| Root VTABLE.DAT | Open succeeds; first read succeeds, 106,496 bytes; file size 109,701 |
| patch.ver | Open succeeds; first read succeeds, all 288 bytes; file size 288 |
| Windows version / DirectPlay / window / D3D creation | No corresponding API record |
| GameMain return | `0x88770000` after 1,227 ms |
| Outer GameStart return | S_OK after 1,810 ms |
| Loader exit | 0; no sampled FFXI window or dialog |

The FTABLE/VTABLE counts are **first reads**, not total bytes read. Their
smaller counts do not establish truncation or a failed read. File presence and
successful reads do not validate content. The earlier inventory text
`Unknown (no readable patch.ver)` does not mean this file is absent or cannot
be opened: the new device trace directly demonstrates the contrary.

All 20 dependency loads pass; the five windowed settings remain correct.
Missing-DLL/RPC warnings belong to PID 292, not the loader. This is another
returned initialization failure, not a captured unhandled crash. Loaded D3D
modules do not establish game rendering.

## Narrowed code path and remaining uncertainty

The matching supplied FFXiMain.dll's file-manager initializer at RVA `0x1c28f0`
reads 0x120 bytes from patch.ver through `0x1bdc10`. At `0x1c2966` it tests the
file-manager result for a negative value. If successful, it copies the buffer
and invokes the PlayOnline common-function-table entry at byte offset `0x124c`
(index 1171), through thunk `0x30f2a2`. The caller supplies four cdecl arguments,
including size `0x100` and mode `1`; a negative callback result at `0x1c2999`
also takes the failure return at `0x1c29c2`.

The read dispatch (`0x19dd20` -> `0x19e110` -> `0x19e790`) can still return a
negative result after ReadFile succeeds: its CRT read/error handling, file
close and semaphore release are not fully represented by the current trace.
Consequently the report does **not** prove that the PlayOnline callback ran or
failed. Version-data processing is the leading next boundary to inspect, not
a confirmed corrupt-file or incompatible-DLL diagnosis.

On the inspected normal path, successful file-manager initialization is
followed by an unconditional GetVersionExA import call in the platform check
at `0x15de1`, then the DirectPlay check. Both imports are instrumented. Their
absence, together with the recorded file reads and inner failure, strongly
localizes this run to file-manager completion/version processing before the
platform and graphics stages. This inference is scoped to the matching DLL
and functioning observer; the API trace is not an instruction trace.

## Next inputs

Obtain these two files from the same imported installation or its backup:

| Path | Identity check |
| --- | --- |
| `FINAL FANTASY XI/patch.ver` | Active file is 288 bytes; no content hash was captured |
| `PlayOnlineViewer/viewer/com/polcore.dll` | SHA-256 `73b1864bf522d3aa6ec28e9f4bb226fcee956f7f4848a238c9c2d756dc703b06` |

Use the selected viewer DLL, not the second copy under `patchfiles`. The
support ZIP contains metadata, not either file's bytes. The prior binary ZIP
contains only FFXi.dll and FFXiMain.dll. Inspect new files locally and verify
the DLL identity before following its common-function-table implementation;
do not put proprietary binaries or disassembly into the repository or APK.

Keep the existing import, accepted preparation, backup, original loader and
runtime. Another identical launch or diagnostics-only APK would not resolve
the missing file content. Do not replace or fabricate patch.ver, bypass the
callback, assume an absent DirectPlay component, or switch renderers based on
this report. If static inspection is inconclusive, observe the remaining
return values transparently before choosing a repair.
