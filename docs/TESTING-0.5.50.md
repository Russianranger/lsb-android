# 0.5.50: start the deployed server with its map files

1. Install **LSB-Android-Restore-Test-0.5.50.apk** over the existing Restore-Test app.
   The normal APK updates the separate normal app instead.
2. Keep the deployed generation and completed jemalloc build. **Do not fetch,
   rebuild, restage, or replace the database for this fix.**
3. Open **Server** and tap **Start managed server** while connected to the internet.
   The first start downloads the missing map submodules, validates their file
   headers and installs them into this server generation. Progress appears on
   the Server page and in the live operation log. These map sets contain about
   733 MiB of extracted data; a reusable copy is also cached for later staging.
4. Wait for **Server ready — all scripts loaded**. The supervisor accepts both
   older readiness messages and the new timed messages. It still waits for all
   four processes, including the map server's complete script load.
5. Try connecting with the client/loader matching **30260904_1**. Export support
   logs after startup, whether it succeeds or fails, so we can verify the actual
   device result and investigate any next issue.

The 0.5.49 phone log confirms the earlier database fix succeeded: the new checked
build/database pair deployed with two accounts and three characters. The new
failure is a missing `ximeshes` directory. Database preparation is not repeated
by this startup repair, and the build's source receipt and binaries are retained.

For future builds, missing declared map submodules are prepared before the staged
payload is fingerprinted; **Check staged build and database** validates the map
headers as well as the existing binary and database checks. GitHub fetches use
that source commit's mesh gitlinks. Older adopted builds with no recorded source
commit use a fixed compatibility catalog only when their map loader files match;
the original unknown source identity is not rewritten. Existing valid map files
are retained. A network, archive, header or compatibility failure leaves the
server stopped and does not select a different database.

Host tests and real ARM64/MariaDB tests cover these paths; world navigation and
client connection still need the device test above. Qualification details are
recorded in HANDOFF.md after CI and APK signature verification.
