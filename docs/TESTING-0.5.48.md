# 0.5.48: reuse a completed build after symlink validation failure

The 0.5.47 phone log confirms all four ARM64 binaries compiled with jemalloc.
The error occurred during the later payload fingerprint. Generated CMake cache
files are not server runtime files and must not be included in the staged payload.

1. Install the matching 0.5.48 APK over the existing app. The reported phone uses
   **LSB Restore Test**, so use the Restore-Test APK; do not uninstall or clear data.
2. Stop the client and managed server, then open **Build**.
3. Under step 2, choose **Verify and reuse previous successful build**. This checks
   the saved binaries, jemalloc linkage and source metadata, then assigns a build ID.
   There is no need to fetch again or press Build again.
4. Once a completed build ID appears, choose the database mode in step 3. Use
   **Keep current player data** to migrate a separate copy of the current database.
   The reported deployment has two accounts and three characters.
5. Prepare the database, then run **Check staged build and database**. Review the
   build ID, database mode, player counts and expected client version before Deploy.
   The completed build expects `30260904_1`; the old deployment recorded `30251204_1`.
6. Deploy the checked pair only when ready. The current deployment stays active
   until deployment; the prior pair remains available for rollback.

The recovered 0.5.47 receipt did not finish recording its source acquisition.
It is deliberately labelled as previously built with original repository not
recorded, rather than silently attributing it to whatever source is fetched now.

If reuse fails, export Diagnostics immediately. Do not rebuild merely to obtain
another log. Errors for non-generated symlinks now identify their relative path.
For the complete workflow, see [TESTING-0.5.47.md](TESTING-0.5.47.md).
