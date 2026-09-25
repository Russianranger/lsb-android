# Staged client updates and storage management

The owner confirmed that 0.5.35 full-session restore works. Keep the regular app
and LSB Restore Test installed separately. Version 0.5.38 updates each in place;
never uninstall the regular app to install an update.

## Manage copies stored in the app

Open **Client → Backup and recovery → Manage backups and storage**. The browser
lists app-held installations and retained copies, their sizes, and whether they
can be deleted. Browse names and sizes before selecting an eligible copy for
deletion. The confirmation identifies the selected copy; deleting a retained
previous generation removes that rollback option. Active installations and
unfinished restore transactions are protected. Stop both runtimes before deletion.

Complete-session exports stream directly to the destination selected in Android's
file picker. They are not duplicated as an internal ZIP. Exports in Downloads or
on an external drive stay in that folder; manage those files through Android's
Files app. The in-app browser manages retained app data rather than scanning
arbitrary device folders.

## First PlayOnline update test

1. Install both 0.5.38 APK updates and use **LSB Restore Test** for this test.
   Keep the original app's server stopped. Stop the test app's client/server too.
2. Open **Client → Client update · PlayOnline → Prepare update and open
   PlayOnline**. Confirm creation of a separate full client and Windows copy.
   Keep enough free space for the copy plus the update download.
3. In PlayOnline, select **Check Files → FINAL FANTASY XI → Check Files**.
   When it finds the deliberately missing repair trigger, choose **File Repair →
   Yes**. Wait until PlayOnline reports completion. Download progress is shown in
   PlayOnline's display. If PlayOnline updates itself and closes, reopen it using
   **Open or resume PlayOnline update**.
4. Choose **Exit Viewer** after repair completes. In LSB, select **Verify completed
   update**. The staged files, registration and loader dependencies are checked.
   Closing PlayOnline by itself does not activate the copy or prove an update
   completed. Stopping or interrupting an update leaves it available to resume.
5. Use **Activate verified update** only when you are ready to switch the test
   app. The old prepared client and matching Windows environment remain available
   through **Restore previous prepared client**. The original app stays separate.
6. Record the updated client version and export a support ZIP. A newer client
   may reject the old server revision or require a matching xiloader. The next
   milestone is testing a pinned latest server build and its database tool in
   Restore Test after the update workflow is confirmed. No server update is
   automatic here.

The updater moves only the staged copy's `ROM/0/0.dat` aside; the original stays
in the active installation and another saved copy stays outside the staged game
folder. Resuming does not remove a newly downloaded replacement. Verification
requires the replacement file to exist. Data and settings in the active copy
remain unchanged until explicit activation.

PlayOnline uses the Box64 environment used for client preparation. The saved
FEX/gameplay renderer selection is retained. Retail credentials are entered only
in PlayOnline if required; LSB's private-server account fields are not passed to
it. The installation must already qualify for PlayOnline's official file-check
workflow. If FINAL FANTASY XI is absent from its dropdown, complete the official
retail setup/login requirement first; this feature does not fabricate that state.

## Primary references

- [LandSandBoat client setup, Later Updates](https://github.com/LandSandBoat/lsb-wiki/blob/main/Client-Setup-Windows.md#3-later-updates)
  documents `ROM/0/0.dat`, PlayOnline Check Files and prior retail connection.
- [Square Enix Windows installation](https://www.playonline.com/ff11us/download/media/install_win.html)
  provides the official PlayOnline/FFXI installer.
- [Wine wineserver manual source](https://github.com/wine-mirror/wine/blob/master/server/wineserver.man.in)
  documents prefix-scoped waiting for Windows process completion.

Automated fixtures verify staging, recovery protection, repair-trigger isolation,
resumption and explicit activation gates. Actual Square Enix download completion
and PlayOnline interaction require this phone test with the owner's installation.

## Retrying a viewer that showed black and exited

Version 0.5.37 keeps the existing update candidate. In Restore Test, use **Open or
resume PlayOnline update**; do not discard the candidate or restore the session
again. The update process disables Wine's optional Indeo video codec when using
this runtime's disabled GStreamer support. This follows Wine bug 56462's failure
mechanism: the codec used a delayed call into an unavailable decoder. The
workaround applies only to the update process, without changing the active
client, Windows registry, gameplay renderer, or server.

Before opening the viewer, the updater loads its normal imported DLLs in a
separate process and records their Windows error codes. A dedicated viewer runner
records the full Windows exit code and whether a visible window appeared, without
reading window titles. Wine/DXVK output is reduced to fixed diagnostic categories;
account text and raw process output are discarded. A visible window or a clean
exit still does not prove File Repair completed. Detached viewer/updater processes
are allowed to finish before cleanup, including after an abnormal first exit.

If it still exits, export a fresh support ZIP. The update report now includes the
DLL check, full process result and startup diagnostics instead of just exit 1.

- [Wine bug 56462 and its upstream resolution](https://list.winehq.org/hyperkitty/list/wine-bugs@list.winehq.org/thread/YEO7G4Z53QPOJWXWFWNIBJHHNTHGHI7G/)
- [Upstream codec import fix](https://github.com/wine-mirror/wine/commit/c0779ad492b2b0f6f4b0bdb1a7d20dc5674dcf97)

## Retrying Unknown error 0x80040154

Version 0.5.38 registers PlayOnline's application and regional contents modules
in the staged Windows environment before opening the viewer. These are separate
from the core component already used by the game launcher. Their DLL registration
and class factories are checked, and any failure is recorded with a component
name and numeric error code.

Install the matching APK update without uninstalling. In **LSB Restore Test**,
select **Client → Client update · PlayOnline → Open or resume PlayOnline update**.
The retained client copy is reused; another restore, import or runtime download
is not needed. After the viewer opens, continue the official Check Files/File
Repair steps above. If another error appears, export a fresh support ZIP.

See [component registration evidence](playonline-com-0538.md) for the official
installer mapping, reproduced failure and verification limits.
