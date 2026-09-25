# Complete session backup and isolated restore test

The phone owner confirmed successful in-app world connection on 0.5.33 on
2026-09-25. That source (`e50eb95f5f63231560c2a7027bfe5c695e2f7247`, documented
at `1fcebbbc62e7b0ad94b1594038ee0c6a7e634a85`) is the accepted working baseline.
Keep its client/server revision, compiler defaults and graphics settings.

## First phone test

1. Install **LSB Android 0.5.35** over the working app. Do not uninstall it.
2. Stop the client and managed server. Open **Client → Backup and recovery →
   Export complete session backup**. Save outside either app's managed storage,
   for example Downloads or an external drive, and wait for export to complete.
3. Install **LSB Restore Test 0.5.35**. It has a separate launcher name, package
   ID, Android UID and storage. Open **Client → Backup and recovery → Restore
   complete session backup** and select the exported archive.
4. Wait for verification and restoration to finish. Check that the selected
   renderer/FEX options, controller mapping, prepared client and server
   deployment match the working app. There should be no need to download or
   initialize the runtimes again.
5. Keep the working app's server stopped. In **LSB Restore Test**, start the
   restored server and wait for **Server ready — all scripts loaded**. Launch
   the client with address **127.0.0.1** and verify characters, inventory and
   macros. The test client requires its own ready server so it cannot use the
   other app's server accidentally.
6. Stop both test runtimes. The working app retains its original data. Reopen
   it to verify normal play; the test app can remain installed or be removed.

Both apps use the same local network ports, so their managed servers cannot
run simultaneously. Restore needs space for the full unpacked installation
alongside existing destination data; exporting to the same device also needs
space for the archive. The backup contains private server credentials, account
data and game files. It is not encrypted; keep it private.

## Coverage and consistency

The archive includes all managed imported/current/previous client copies,
prepared generations and their prefixes, Box64 and FEX runtime trees and
prefixes, source imports, server runtime and compiler/dependency installation,
all server/database generations and credentials, deployment pointers, imported
SQL, a fresh logical SQL dump, operation history and typed Android settings.
Client macros, configuration and user files are preserved as ordinary files.

Both runtimes must be stopped. Export refuses owned orphan processes, reserves
server operations, performs a logical database backup and waits for database
shutdown before reading raw database files. Only private process run/tmp trees
and the internal restore-settings marker are excluded. These contain sockets,
PIDs and other temporary process state that must be recreated on launch.

The streaming ZIP64 container stores bounded binary records with SHA-256 for
each regular file and a required SHA-256 completion record for the header and
payload. It preserves executable/private modes, empty directories, timestamps,
symbolic links and hardlinks. Literal Linux filenames, including backslashes,
are retained without Windows path normalization. Use .35 or newer in both apps.
Symlinks are created only after the entire payload
passes verification; extraction never writes through a link. Exact host-root
link targets relocate to the receiving app; Linux guest absolute targets retain
their guest meaning. Registry hives, SQL, sources and executables are not edited.

Restore extracts to separate staging roots. A durable journal protects the
internal/external folder swaps. Before commit, interruption restores the
original destination data; after commit, startup replays typed settings before
discarding old folders. Settings use SharedPreferences APIs so in-process caches
match disk. Existing client-only archives retain a separate legacy restore action.
The archive is an LSB complete-session format, not an ordinary client import ZIP.

## Ordered next milestones

Do not update the working client or server until the owner verifies the isolated
restore test. Next, investigate a staged PlayOnline repair/update workflow in the
test app, including the exact official version-check/repair behavior, progress,
interruption recovery and preservation of the current prepared client. Do not
delete a guessed client marker file or weaken inventory validation.

After the client update is verified, test fetching a pinned new LandSandBoat
source revision, compiling it in the test app, using that revision's database
tool to create/update a separate database generation, and connecting the matching
client. Preserve the accepted source/database pair and provide rollback throughout.
