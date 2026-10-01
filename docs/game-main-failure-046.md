# 0.4.6 device result: inner GameMain failure

The report `lsb-support (3)(2).zip` has SHA-256
`02067e49bcadbfe0de1a1d9ba003b50728ca437151cb136e2a840bcf18019bdd`.
Session `4a918d7f-9ae7-4c8e-a0c6-c3cb27549b9f` uses the existing prepared
generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`. The original loader and
accepted runtime remain unchanged.

## Captured boundaries

All these records belong to loader PID 284, thread 288:

| Boundary | HRESULT | Detail |
| --- | --- | --- |
| FFXI COM creation | `0x00000000` | Object present |
| GameMain COM creation | `0x00000000` | Object present |
| `IGameMain::FFXiGameMain` return | `0x88770000` | 1,441 ms |
| `IFFXiEntry::GameStart` return | `0x00000000` | 1,983 ms |
| Loader process exit | 0 | 16,668 ms total |

Both COM activations succeed. The inner call returns a failing HRESULT; the
outer call masks that result with S_OK. This is a returned initialization
failure, not a captured unhandled crash. No game window or dialog was sampled.
All 20 launcher dependency loads pass and the five windowed display values
remain correct. No observer failure or dropped diagnostic record is reported.
The missing-DLL/RPC warnings belong to PID 292. Their presence does not explain
this inner HRESULT. Loaded graphics DLLs and the separate Vulkan probe do not
establish game rendering.

## Reporting correction

The source now reports `game_main_failed` with the inner HRESULT and duration
instead of displaying outer S_OK. It requires the observed child PID, selects
the latest inner result within the latest recorded GameStart attempt, and does
not replace abnormal process-exit handling or classify a sampled game window
as absent. Successful inner results and other processes' failures cannot supply
this error. Startup boundaries retain repeated calls in chronological order;
the 64-record bound discards indirect warnings first, then the oldest boundary
when all records are direct startup events. Wine warning deduplication remains.
The existing real-runtime nested-call fixture expects the new reason.

Thirty-one Python contracts pass, including the device's inner-failure/outer-success
sequence, PID correlation, retry handling, normal/abnormal exit distinction,
privacy and existing import/launch contracts. Replaying the actual uploaded
receipt through `exit_problem` produces `game_main_failed` and
`0x88770000 after 1441 ms`. These checks validate reporting, not a game fix.
No new device APK is delivered for this reporting-only change. The installed
0.4.6 remains sufficient to capture the failure; earlier 0.4.6 CI results apply
to the previously delivered build, not this new source revision.

## Next evidence needed

Exact-code searches and the public xiloader/Fenestra sources have not yielded a
reliable definition or originating instruction for `0x88770000`. Do not label
it as a Direct3D, registry, memory or missing-runtime error without evidence.
The support ZIP contains file identities and runtime receipts, not client DLL
bytes. Obtain these two files from the same imported installation or its backup
for static inspection of the return path, ideally together in a ZIP:

| File under `FINAL FANTASY XI` | Expected SHA-256 |
| --- | --- |
| `FFXiMain.dll` | `d528142a9bfb7767b4d4574dde531ea87a673670c81892f1eab37563b82c0235` |
| `FFXi.dll` | `d58b652ff3bb2de0b4f7dd4bb51eccc80f39d26de4bfb01217d7a7eb5faaa57f` |

Inspect these files locally; do not commit proprietary binaries to the public
repository. Confirm their hashes before attributing code paths to this report.
The next change should follow that inspection. Keep the imported client,
prepared prefix and original loader. No re-import, preparation, GameHub transfer,
dependency sweep or repeat of the identical device test is needed at this stage.
