# 0.5.47: explicit source, build and database workflow

The Build tab separates source acquisition, compilation, database preparation,
staging checks and activation. Each stage displays its own identity. A changed
fetch selection never silently substitutes another source into an existing build
or staged deployment.

1. Stop the managed server and client, then open **Build**.
2. Under **1 · Fetch or import source**, enter the repository and branch/tag/commit,
   then fetch. Check the displayed exact commit. An imported ZIP is labelled
   unverified unless its acquisition recorded a commit.
3. Under **2 · Build this source with jemalloc**, choose j1–j16 (default j2) and
   build. jemalloc is mandatory; all four ARM64 binaries are checked. Read
   **Live build progress** on the same page. Scrolling upward pauses following;
   return to the bottom to follow output. The completed build displays its own ID.
4. If upgrading from a successful older build, **Verify and reuse previous
   successful build** validates the retained binaries and assigns an ID without
   compiling again. An older receipt cannot prove its original repository:
   the page labels that provenance as unrecorded instead of borrowing the latest
   fetched revision.
5. Under **3 · Prepare database and stage build**, select one of:
   - **Keep current player data**: back up and migrate a separate copy.
   - **Use imported SQL**: import an SQL/SQL.gz backup, then migrate it for the build.
   - **Create fresh database**: initialize from this build's SQL. Current player
     progress is not copied; a custom source may supply its own seed data.
   Verify the displayed build ID and prepare the pair. This does not activate it.
6. Under **4 · Check staged build and database**, run the check. It validates
   payload fingerprints, the exact four binaries and jemalloc linkage, expected
   client metadata, database tables/counts and MariaDB table checks. Read any
   failure reason and confirm the displayed source, database choice and counts.
   These are staging checks; they do not prove gameplay or complete mesh assets.
7. Under **5 · Deploy the checked pair**, review the replacement confirmation.
   Deployment checks the same staged pair again and activates it without rebuilding
   or reading a newly fetched source. The previous server/database pair is retained
   for rollback.
8. Start the server from **Server**, wait for readiness, then connect with a
   compatible client. Server now contains operating controls, account creation,
   database export and rollback.
9. If the active server is started or an account is created after copying its
   database, that staged copy becomes stale. Prepare the database again to retain
   newer player data before deployment.

For a prebuilt server archive, the separately labelled advanced section on Build
retains imported ARM64 binaries plus imported SQL deployment. It explicitly uses
the imported snapshot, not a completed jemalloc build.

Device verification: confirm the source/build IDs remain distinct after fetching
another revision; check live output while controls are locked; verify worker
selection persists; and inspect a staged replacement before confirming deployment.
Export Diagnostics if any stage fails. A failed/cancelled preparation or check
must leave the active server and database in place.
