# 0.5.49: stage older account databases with current server SQL

The 0.5.48 result confirms build recovery succeeded. Database staging later failed
because the existing accounts.id is a signed integer while the new accounts_files
foreign key requires an unsigned integer. The compatibility adjustment operates
only on the isolated staged database and preserves existing accounts and characters.

1. Install **LSB-Android-Restore-Test-0.5.49.apk** over the existing Restore-Test app.
2. Stop the client and managed server, then open **Build**.
3. Keep the completed build already shown in step 2. There is no need to fetch,
   compile, or verify/adopt it again.
4. Under step 3, select **Keep current player data**, then **Prepare database and
   stage this build**. Preparation creates a new copy from the active database.
   The live log reports the account-ID compatibility adjustment when needed.
5. Run **Check staged build and database**. Confirm the saved player counts (the
   previous deployment records two accounts and three characters), the build ID,
   and the expected client version before choosing **Deploy the checked pair**.
6. Start the managed server from **Server** after deployment.

The completed build expects client version `30260904_1`; the previous deployment
records `30251204_1`. Match the actual client and loader to the built server.
A successful database check does not prove gameplay or complete mesh assets.

If preparation fails, export Diagnostics immediately. Keep the current deployment
and completed build; another compilation is not needed to investigate database SQL.
