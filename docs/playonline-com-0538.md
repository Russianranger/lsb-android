# PlayOnline component registration repair (0.5.38)

The owner's .37 report (`lsb-support (2)(8).zip`) reaches the official viewer
splash and reports `0x80040154`. Its eleven direct DLL imports pass. The viewer
loads polcore.dll before reporting an OLE class failure; the previous sanitizer
retained the failure category but discarded the class identity. The active
client and retained update candidate remain intact. Archive SHA256:
efa924f03a3246964e50605cbf2ce2ccc60a283db30e9eec1d560fbe968758bc.

The runtime diagnostics now preserve canonical numeric COM CLSID/IID and
HRESULT only from exact Wine 10 component failure messages. No raw output,
window titles, account strings or registry values are retained. New contracts
cover message grammar and rejection of additional private text.

A separate, required PlayOnline CI job publishes the official offline fixture's
screenshots and diagnostic metadata promptly. It compares install-path-only
setup with a registered/activated viewer core. Visible windows and colored
pixels do not establish usable UI: a splash with an error dialog also qualifies.
The fixture explicitly leaves usable UI and online repair unverified pending
evidence inspection. No proprietary executables or DLLs are uploaded.

Diagnostic commit aed459cabce9f7e94fcd92ade7d5e14bc4dc716f reproduced the class
failure after successful official core registration and activation:
`{40555aae-53ad-4abc-ae65-8441755e7d69}` was not registered. This is the viewer
application, distinct from the existing core used for gameplay. Push workflow
36192314902 artifact 10888453146 contains the diagnostic comparison.

The exact [official viewer installer](https://www.playonline.com/ff11us/download/media/install_win.html)
MSI SHA256 is 08a6d3ad218db466ec6d126215131d24c38cc7972dbf7127676e9d0799e7e032.
Its Registry, Component, File and Directory tables identify these live modules:

| Component | Relative path | Class |
| --- | --- | --- |
| Viewer application | viewer/com/app.dll | 40555AAE-53AD-4ABC-AE65-8441755E7D69 |
| International contents | viewer/contents/polcontentsINT.dll | 3FC1EF9A-F346-413C-BB47-ED6F9A4BD52F |
| Japanese contents | viewer/contents/PolContents.dll | 62021866-976B-49A3-A18B-7A44869008A2 |

The official executable embeds the application and international contents class
IDs. Both modules export DllRegisterServer and DllGetClassObject. The installer
also contains duplicate registrations pointing into patchfiles; the updater
selects only the live paths above.

Before opening the staged viewer, .38 validates the regional core, application
and regional contents modules, calls their own DllRegisterServer, then verifies
their 32-bit registration paths and COM availability. The new application and
contents checks request and release IClassFactory without constructing an
application object or invoking login. No movie filters or unrelated Windows
prerequisites are installed. Registration results retain fixed names, hashes and
numeric status only. Missing, ambiguous, linked, malformed or unsuccessfully
registered modules stop startup and retain the stage for retry.

The original working prefix/client/server are unchanged. Registration takes
place in the copied update prefix and is safe to repeat after interruption or
viewer self-update. The original signing certificate and both package identities
are retained.

## Verified result

Production commit 27560c7b2c4102374779f1efeb98e273b1f226a1 was validated with
test-only correction 6ba9720d06996ee2fe9ca2731172af935692684b. The correction
reads native Windows receipts as UTF-8 and captures the viewer at its negotiated
display size instead of assuming 1280×720. Packaged production contents are
byte-identical between these commits.

Push workflow 36194023305 and PR 36194026874 both pass the focused official
viewer test. All six component operations return HRESULT 0/Win32 error 0, with no
known missing-class messages or dropped diagnostic records. The reviewed
640×480 production screenshot shows **Setup → Version Update**, **Update to the
Latest Version**, and the **Network / Next / Cancel** controls. The unknown-error
modal is gone. The baseline core-registered case still reproduces 0x80040154.

Push evidence artifact 10889034624 has archive SHA256
b29ba479d74c999ba75e040f0c787b38fec68ad8061fb5c00d145471e6207178;
production.png SHA256
bba30c7edb9b20cd915ac896713fe52c3bb441f90f1111cea17a0ca34215bd50.
The test is offline and does not click Next, download updates or run File Repair.
Generic Wine warnings remain, so this is not a claim of a completely clean log.

Both native Windows jobs pass 143 checks, Android packaging/server checks pass,
and 102 local runtime unit test methods pass. Broader renderer CI failures and
pending runs are recorded separately in HANDOFF.md; they do not constitute a
successful full graphics regression run. Signed regular and Restore Test 0.5.38
APKs are saved with the retained certificate; see the handoff for identities.
