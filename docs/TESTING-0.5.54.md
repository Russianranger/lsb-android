# 0.5.54: Quick login and first-zone-entry evidence

1. Update the ordinary installation with `LSB-Android-0.5.54.apk` (or update the
   separate test installation with the Restore Test variant). Keep existing data.
2. Stop the client and managed server, then start with Play. The new trace is
   installed automatically into the deployed generation at server startup. No
   source fetch, server compilation or database replacement is required.
3. Enter the account and password, select **Remember account and password for
   quick login**, and play. After logout, Play should offer **Quick login** with
   the account filled and password masked. Relaunch the app and repeat.
4. Retry the newly created character shown in the logs as **Leidanar** in Bastok
   Mines. Do not recreate/delete the character. If it fails, note the displayed
   error and time, close the client, and immediately export a support ZIP.
   `server/zone-entry.log` records entry, new-character initialization and
   cutscene callbacks. Installation status also appears in the server log.
5. Compare with Tarrin if useful. The logs alone have not established the cause
   of the first-entry failure, and 0.5.54 does not claim to repair it.
6. **Forget saved login** clears the stored account/password. Changing the server
   address clears the visible saved credentials unless it matches their server.
   With Remember off, a launch uses one-use credentials as before.
7. Complete session backups include opted-in saved logins and restore them across
   the ordinary/test installations. Support ZIPs do not include saved logins.

Automated checks cover save/forget, server binding, masked UI, invalid login
validation, export exclusion, cross-package restore, and diagnostic module
installation/forwarding. Actual Bastok entry remains a device verification.
