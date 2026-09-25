# PlayOnline COM startup investigation (0.5.38 in progress)

The owner's .37 report (`lsb-support (2)(8).zip`) reaches the official viewer
splash and reports `0x80040154`. Its eleven direct DLL imports pass. The viewer
loads polcore.dll before reporting an OLE class failure; the previous sanitizer
retained the failure category but discarded the class identity. The active
client and retained update candidate remain intact.

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

This initial diagnostic commit is not an APK delivery or a claimed COM fix.
Next inspect the exact missing class and official installer registrations,
implement only the required updater setup, and retest. Preserve both package
identities and the original signing certificate for delivery.
