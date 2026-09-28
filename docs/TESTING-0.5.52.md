# 0.5.52: profile service required by current xiloader

The latest 0.5.51 support export confirms the imported loader is active and the
updated game DLLs are preserved. Login now progresses through the accepted-login
message and connection to the data service, then fails before the game window.
Current xiloader also needs the newer `xi_profile` service; previous app versions
built and started only `xi_world`, `xi_search`, `xi_map`, and `xi_connect`.

## Recover the existing deployment

1. Install **LSB-Android-Restore-Test-0.5.52.apk** over Restore Test, retaining its
   data. Use the ordinary APK only for the separate ordinary app.
2. Stop the client and managed server. On **Build**, select the desired worker
   count. The repair uses this same worker preference.
3. On **Server**, tap **Repair missing profile service**. Wait for completion;
   live progress and compiler output appear in **Diagnostics → Server operation
   log**. This requires the build dependencies/source downloads used by the
   normal source build, and compiles only `xi_profile` with jemalloc.
4. Start the managed server and wait for Ready. The newer source now requires
   five ready programs and the profile listeners on ports 51220 and 51240.
5. Launch FFXI with the same prepared client, imported loader, and account. If
   another failure occurs, export Diagnostics after the attempt.

Do not refetch/rebuild the four existing programs or prepare/replace the database
for this repair. The deployed source is used, so fetching a different revision
cannot silently change the repair target. The original successful build receipt,
updated client files, server generation and player data are preserved. The profile
service receives its own build/repair receipt.

New source builds include and verify `xi_profile` when the selected source defines
it. Older source without a profile service retains its four-program behavior.
A specific profile-connection error replaces the previous generic connection
failure; fixed classifications are recorded without retaining private loader
output.

Real-device authentication/world entry after this repair remains to be confirmed
by the next phone test.
