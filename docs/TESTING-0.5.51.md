# 0.5.51: apply the imported xiloader to the prepared client

The 0.5.50 phone support export confirms all four managed server processes reached
Ready, including map startup with 603 installed map files. The login attempt
reached the server, but the active prepared client still launched the older
xiloader. Previous imports changed only the original imported installation.

## Retry this installation

1. Install **LSB-Android-Restore-Test-0.5.51.apk** over the existing Restore Test
   app. Keep its data. The ordinary APK updates the separate ordinary app.
2. Stop the client/runtime. The managed server may remain running.
3. On **Client → Active xiloader**, compare the checksums. For the reported
   installation, the imported loader starts `aec6c0686cf3`; the older prepared
   loader starts `78fe8ab1dee5`.
4. Tap **Use imported xiloader in prepared client**. Wait for completion. Both
   checksums should now start `aec6c0686cf3`.
5. With the managed server Ready, use **Launch FFXI** and the existing account.
   If login fails, export Diagnostics and report the displayed reason.

Do not repeat the full client preparation, server build, staging, or database
replacement for this fix. The completed PlayOnline update, Windows environment,
server build, and player data stay in place. New imports through **Import and
apply xiloader.exe** also apply the chosen loader to the active prepared client.
A staged client update must be finished or discarded before applying a loader.

## What is checked

The operation verifies the selected imported SHA-256, x86 PE header, current
prepared inventory, and unchanged selected game binaries. It changes only the
prepared xiloader and its inventory/validation receipts. Recovery copies and
previous launch proof are retained with the loader-update history. Interrupted
changes are recovered before another update or runtime launch. Successful login
and world entry with the replacement remain device checks, not assumed results.

Login diagnostics now recognize the upstream server's specific unsupported
xiloader-version reply. Only fixed error classifications are saved; raw server
reply text, account names, and passwords are not added to diagnostics.
