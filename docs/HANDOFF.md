# 0.5.36 delivered: backup browser and staged PlayOnline update

The owner confirmed on 2026-09-25: "Restore functionality works perfectly."
Support lsb-support(20260925-131758).zip records successful full restore and
verification at 07:55:44 CDT on .35. This is the new accepted backup/restore
baseline. Preserve the regular app and provide both regular and isolated Restore
Test APK updates. Next request is reviewing/deleting app-held recovery copies and
backups, then testing client updating before latest server compilation/database
migration. Do not update the working installation automatically.

.36/code52 adds Client → Backup and recovery → Manage backups and storage,
with background size scans, read-only names/sizes browsing, protected current data,
and explicit selection/confirmation for deleting inactive recovery data. Known
previous/orphan client/server generations, paired FEX prefixes, previous imports,
saved test prefixes, SQL dump copies and download caches are supported. No link
traversal or arbitrary path deletion. Deletion rechecks selections, holds server
maintenance reservation, durably removes rollback pointers before quarantine,
and leaves interrupted cleanup discoverable. It does not retain another export
ZIP: exported archives stay in their chosen Android folder (manage via Files).

Client → Client update · PlayOnline copies the current prepared client and Box64
prefix, moves only its ROM/0/0.dat aside outside the mounted client folder, then
opens real pol.exe in that copy. User runs official Check Files / File Repair;
progress appears in the display. Reopening resumes the same candidate and does
not remove downloaded replacements. Prefix-scoped wineserver waiting covers POL
self-restarts. POL output/login data is not forwarded to support logs. No login
credentials are passed from LSB. FFXI must already be eligible in POL's dropdown;
no retail-registration state is fabricated.

Verification is separate, requires restored0.dat, reinspects only this update's
inventory, retains xiloader hash, runs registration and loader dependency checks,
and records generation/session-bound receipts. Explicit activation checks those
receipts and current file hashes, then promotes the client/prefix together with
rollback retained. Ordinary initialization cannot accept an update candidate.
Fixed Box64 update/verification follows existing preparation; gameplay FEX choice
is retained and the new generation obtains its own FEX prefix on launch. Actual
POL UI/network download and newer client/server compatibility need phone testing.

Local checks: 15 storage safety tests, four browser lifecycle/deletion tests,
and ten update UI/activation tests pass. Core checks pass, including 39 prepared
client checks, 62 archive checks and 353 restore transaction cases; all 88 Python
runtime contracts pass. Independent updater review found no blocking issues.
The full final Android suite passes all 82 tests in both push and PR CI.

Implementation source **49160a630ace2ed245cb409569774f648561f054**, tree
**7a30ecb7420629721a30f32cacbe6de86d692950**, version .36/code52. Final review
added explicit browser/scan guards during complete restore/recovery and raw,
non-creating storage paths. The Android test returns to a paused browser while
files is absent and confirms no runtime singleton or root is recreated. The APK
isolation verifier explicitly checks the new private BackupBrowserActivity.

Push **36142309471** and PR **36142315411** Android build jobs
**108094991396 / 108095005268** pass. Both native ARM64 server jobs
**108094856553 / 108094876336** pass all 15 markers, including actual 731 MiB
archive/activation and restored MariaDB boot at a separate app path. Account
hashes, characters, SQL objects and original-state isolation are verified.
Presentation and Windows jobs pass in both runs. Long Box64/FEX jobs remain
running at delivery: push **108096107733 / 108096107907**, PR
**108096033275 / 108096033547**. Follow up next turn; do not claim all CI green.
No unchanged reruns or weakened gates.

Both delivered APKs retain certificate SHA256
f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e:

- **LSB-Android-0.5.36.apk**: 18,379,265 bytes; SHA256
  b0fab3138211b7d63344337ce238d88ec734789c2086310d4000f1bb2561fe0f.
  Library libfile_a59ba8380eb48191b99405001e9fd76e, version0,
  file_00000000f738822f95d9bb329f1cb52b.
- **LSB-Android-Restore-Test-0.5.36.apk**: 18,375,169 bytes; SHA256
  f51af147ab28c0058ba0ccc361aa91fe4989183ea20bcde854d97d6c3a4ecb34.
  Library libfile_596713565080819194d8a485af203851, version0,
  file_00000000301881f5b64828e441c9ebba.

v2/v3 signatures, alignment, package IDs/version52 and ZIP integrity pass. Signed
payloads match final CI artifacts. Both APKs have 56 identical code/resource/
runtime entries outside manifest/resources table. All 36 existing runtime/native
entries except changed supervisor.py remain byte-identical to .35; the one added
runtime file is client_update.py. Seven server assets match source unchanged.
Changes are only Android manifest/classes, supervisor.py, new client_update.py
and signing metadata. CI artifacts **10867682012** (standard) and **10867362324**
(test), ZIP SHA256 b513c902fee2f14467a54086f65016ea8156c719969ebfb66fa7829cf675506e
and 10a9d6563305ce36e26aeab1faaa64bb0e6050b721a0359218e775abb8a5e837.

Install both updates without uninstalling. First review/delete an unwanted
inactive copy under Manage backups and storage, then prepare/resume PlayOnline
repair in Restore Test. After POL reports completion, exit, verify, and explicitly
activate the test copy. The existing server may require a newer matching revision
before the updated client can connect. Retain the regular app as the working
installation. No new runtime download or full restore is required for .36.

See docs/client-update.md and docs/complete-session-backup.md for sequence and
primary repair references. Latest server/database testing follows successful
client updater validation; no server/allocator/source changes in this release.

.35 CI follow-through: all push36132052769 jobs finished successfully, including
Box64108062114928/FEX108062114936 and runtime-release108069012128. PR FEX108062249745
passed; PR Box64108062249766 retains the documented observed pixel timeout. No
unchanged rerun or weakened gate.

---

# 0.5.35 delivered: complete-backup POSIX filename repair

The .34 phone export ran from 06:39:55 to 06:48:24 CDT and failed with
IOException: Unsafe session path. The supplied support ZIP confirms the logical
SQL backup succeeded and MariaDB shut down cleanly by 06:40:00. It does not record
the offending filename. Local reproduction shows the old complete-archive
validator rejects an ordinary Linux filename containing a literal backslash.
The writer constructs only POSIX slash-separated paths from real filesystem
entries; it must preserve such names, not normalize them as Windows paths.

The .35/code51 fix supports literal backslashes and other valid UTF-8 Linux
filename characters while retaining slash traversal, NUL, scope, duplicate,
link-parent and checksum checks. Errors identify a bounded escaped path. Both
standard and Restore Test APKs need the update. No game/runtime/server data is
renamed or skipped; archive format remains v1. Client/runtime/allocator behavior
is unchanged. See docs/complete-session-backup.md for the retry sequence.

Implementation source **44f1b7a7f9be84665343a4d233fba09243082512**, tree
**c45f0c093696553bb0df60ce71de378eb545f977**, version .35/code51. Two independent
local reproductions reproduce the previous error with literal backslash names;
the exact phone filename remains unverified. POSIX host and UTF-8 round-trip
checks prevent interpreting these names differently on another host.

Validation: push **36132052769** and PR **36132058540** Android build jobs
**108061447491 / 108061504271** pass all **53 Android tests**, including the
full-session Wine-prefix backslash filename fixture, **62 archive** and **353
transaction recovery** checks, **43 tar** checks, **82 runtime contracts**,
**39 server units** and existing core/display checks. Native ARM64 server jobs
**108061330879 / 108061350382** pass all 15 markers, including the actual 731 MiB
complete archive/activation round trip with 2,443 files and five symlinks. Restored
MariaDB starts at the new app path, preserves account/password/character data and
SQL objects, and restored-only writes leave original state/source hashes unchanged.
The phone owner's full export/isolated restore still needs a retry.

Both presentation and Windows jobs pass. PR Box64 **108062249766** failed the
observed D3D8 pixel fixture timeout (last logged swvp frame 3 pixel_lock before),
followed by the startup assertion in the cancellation fixture. Push Box64
**108062114928** and push/PR FEX **108062114936 / 108062249745** are still running
at delivery. Runtime payloads are unchanged; no gates were weakened or unchanged
jobs rerun. Do not claim all CI green; follow up on these jobs next turn.

Both delivered APKs use the retained signing certificate SHA256
f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e:

- **LSB-Android-0.5.35.apk**: 18,350,516 bytes; SHA256
  832f80271ea8477b0f88b8eeaacd70b7183c3635f949d0cd33a27d56ee307f86.
  Library libfile_3b5c25071de88191954d6895e95405ff, version0,
  file_00000000db7081f58dcde5b084d739e1.
- **LSB-Android-Restore-Test-0.5.35.apk**: 18,354,612 bytes; SHA256
  c39db351279d851785e54c7479bf3a86c0696428d1a28dd1ec3fb12d55384170.
  Library libfile_475b053568c481918a007a0d1cc4560a, version0,
  file_00000000059481f58c0066505ff09378.

v2/v3 signatures, alignment, package IDs/version51 and ZIP integrity pass. Final
non-signature payloads match CI. The two APKs have 55 identical code/resource/
runtime entries outside manifest/resources table. All 37 client runtime/native
entries remain byte-identical to .34; all seven server assets match source.
Push CI artifacts **10862236104** (standard) and **10862211092** (test), ZIP SHA256
71be7ca4d5679a7d86dd765caf752cca01f037cb1146df567145d407deba49b8 and
4c562dfcadb2799d6a83c3139b89c42eb50f0b1561e8983d9e1804d1437ff392.

Install both .35 updates without uninstalling. Stop client/server, retry Client
→ Backup and recovery → Export complete session backup in the working app, then
restore into LSB Restore Test. Keep the original server stopped while testing
the restored server. No runtime reinstall or source/client/database reimport is
needed. Preserve the accepted .33 working setup; client updating and latest LSB
source/database experiments remain gated on the owner's successful restore test.

.34 CI follow-through: PR FEX108054909744 passed. Push FEX108054625185 failed a
D3D8 texture/geometry/render-target pixel timeout followed by cancellation fixture
not starting. Push/PR Box64 jobs108054625277/108054909695 were still running at
.35 investigation. No unchanged reruns or weakened gates.

---

# 0.5.34 delivered: complete session backup and isolated restore test

The user confirmed successful world connection on .33 on 2026-09-25 and accepted
it as the baseline. The apparent startup problem was early login: world/search/
connect were ready at 10:45:33 UTC, but map only reached markLoaded at 10:47:30
following mob scripts. Latest support archive reports stopped; untimestamped
assertions at the end of xi_map.log remain unconfirmed shutdown-context evidence.

Implementation source **68a2a21ef89000d477ba07125eb86325d00fa233**, tree
**5e3e7ec462bfcb3f3958dedecdba3752982fd05d**, version .34/code50. Initial source
b64973de75dc25cc72df86eb604c6381bfc46725 already passed native restoration, but
Android integration caught fresh managed storage being created after transaction
begin. Final source creates destination roots before recording existence and
also disables readiness as soon as server shutdown starts. No weakened test.

Complete backups now preserve all files/rt and server-runtime persistent state,
managed client/source imports, previous generations, both runtime installations,
all Box64/FEX prefixes and prepared clients, credentials/raw MariaDB files plus a
fresh logical SQL dump, and typed SharedPreferences (including future groups).
Client and server must be stopped; orphan detection and server reservation prevent
snapshotting live database writers. Process run/tmp files are recreated. Archives
stream directly through SAF, preserve modes/hardlinks/symlinks, hash each file and
the complete payload/metadata, and relocate exact host-root links to the receiving
app. Guest paths remain unchanged; source/SQL/registry data is not rewritten.

Restore verifies separate staging roots before activation, uses a durable
cross-storage recovery journal, restores original roots after interrupted
uncommitted swaps, and replays settings after committed interruption. Android-created
empty placeholders and read-only directory cleanup are covered. Recovery failures
block normal UI. Legacy client-only backups keep a separate restore action.
Server startup now shows live loading stages, elapsed time and recent startup lines;
Ready requires fresh markers from all four xi processes and reachable login port.

Two APKs were built from identical code/runtime assets. Standard updates
io.github.russianranger.lsb; **LSB Restore Test** uses
io.github.russianranger.lsb.restoretest with separate UID/storage, explicit private
components and no shared UID/provider. Test client requires its OWN live, ready
server and actual LoginRequest host127.0.0.1. The two apps share network ports;
stop the working app server before starting the test server.

Validation: both push **36129692117** and PR **36129725510** Android build jobs
**108053958301 / 108054073959** pass all **53 Android tests**, **32 archive** and
**353 transaction recovery** checks, **43 tar** checks, **82 runtime contracts**,
**39 server units** and existing core/display checks. Both native ARM64 server jobs
**108053857294 / 108053960884** pass all 15 markers, including actual 731 MiB
complete-session archive/activation with 2,443 files and five symlinks. Restored
MariaDB starts at a new app path; account hashes/passwords, characters, blobs,
views, routines, events and triggers work. Writes to the restored database leave
original source/state hashes unchanged. These are synthetic fixtures; phone-scale
backup/restore of the owner's private installation is the next required test.

Both presentation and Windows jobs pass. Long Box64/FEX jobs are still running at
delivery: push **108054625277 / 108054625185** and PR
**108054909695 / 108054909744**. Check their results next turn; do not claim all CI
green. .33 follow-through: both Box64 and PR FEX108037110708 passed; push
FEX108037283382 failed an observed D3D8 pixel fixture timeout. No unchanged reruns.

Delivered APKs, both signed by retained certificate SHA256
f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e:

- **LSB-Android-0.5.34.apk**: 18,346,420 bytes; SHA256
  f5eef1114149c8af6c3c33ad7e49eff1638f3219afbfbae15f86fb2864aa2078.
  Library libfile_25f8dc7af9f0819187420bf32cc84dd8, version0,
  file_00000000925081f5b4f98316b312cd62.
- **LSB-Android-Restore-Test-0.5.34.apk**: 18,350,516 bytes; SHA256
  d7c9c2bf0ea3ff715088a893cb291c5005ba45ae1c4935d4a600e632b6d8e775.
  Library libfile_0e27a637cc2881918aafe44aa2dba5f2, version0,
  file_000000004b8c81f58c0eaf5331faa736.

v2/v3 signatures, package IDs/version, alignment and ZIP integrity pass. Final
non-signature payload matches CI; APK pair verifier confirms identical 55 code/
resource/runtime entries outside manifest/resources table. All 37 client runtime/
native entries remain byte-identical to .33; all seven server assets match source.
CI source artifacts **10860823245** (standard) and **10861192959** (test), archive
SHA256 053b5e5d84bc175daf41da2f3eeb8c1704ce0bf40a99c6826a79abeaf56d7b8c and
118fc509aeb4e4b305d4e4839c986a08d0752cef7715c2853647c2e0f3b68090.

Next phone test: install standard APK over current app (do not uninstall), stop
client+server, Client → Backup and recovery → Export complete session backup to
Downloads/external storage. Install Restore Test, import that backup, check saved
settings and prepared client/server, start only the test server and wait for
Server ready before connecting. See docs/complete-session-backup.md.

After the owner confirms this restore test, investigate staged in-app PlayOnline
repair/client updating. Only after that, test fetching/compiling a pinned newest
LSB source revision and its database tool in the test installation. Do not update
the accepted working client/server now. No allocator change requested.

---

# 0.5.33: MariaDB localhost resolution in the server guest

Phone support ZIP `lsb-support (3)(8).zip` on .32 proves BFD/SFrame repair works:
all four xi executables pass ldd and resolve both exact libraries from the
compatibility directory. Deployment then fails in mariadb-install-db because
neither the phone hostname `localhost` nor `localhost` resolves. No database
has been activated (`server/deployment.json` is empty). Imports remain staged.

The exact pinned Ubuntu Base tar contains an empty `etc/hosts`; its nsswitch
configuration uses `hosts: files dns`. Docker had supplied localhost entries,
which masked this prerequisite in previous server integration tests. Version
.33/code 49 bundles a private loopback hosts file and binds it to `/etc/hosts`
for every server PRoot invocation, including bootstrap, deploy and start on
existing installs. Host Android/Termux files and rootfs files are not overwritten.
The v4 dependency marker remains current; this repair needs only an APK update
and another Deploy matching server + database, without a runtime reinstall.
No force flags, database reset, allocator changes or client changes are needed.

Source commit **e50eb95f5f63231560c2a7027bfe5c695e2f7247** passes both native
ARM64 server jobs: push **108036468380** / run **36124206700**, and PR
**108036481247** / run **36124211055**. All 13 integration markers pass. The new
check disables DNS lookup, empties hosts, reproduces the exact MariaDB error,
verifies an actual PRoot file binding resolves localhost without overwriting
the underlying hosts file, then deploys real SQL using the packaged loopback
entries while DNS remains disabled. DNS is restored for later pip-based updates.
Existing BFD, import/restore/rollback/account and process start/stop checks pass.

Both Android build jobs **108036585417** and **108036592759** pass 45 Android
checks, 43 tar checks, 82 runtime contracts and 34 server units. The new Android
regression covers deploy/start on a current runtime with retained staged SQL;
the existing upgrade test also checks the bootstrap binding. Both presentation
and Windows jobs pass. The user's private server deployment remains untested here.

Delivered **LSB-Android-0.5.33.apk**, code 49, 18,334,132 bytes, SHA-256
`d01b4116461c811270050cf9c0229e1ee60e6a9a600aa03e077ef2ae66199ba2`.
Library `libfile_d91a58dc0a9c81919c9317cc4348c2f0`, version 0,
file `file_0000000043b081f5be5cb12f4506ee60`. Retained update certificate verifies
with v2/v3 signatures; package/version, alignment and ZIP integrity pass.
Non-signature payload matches CI artifact **10859711591**, ZIP SHA-256
`d9fbce8f65f4098035ab23636ea3c63dec77cda4e067fce6208a89f0c99605c9`.
All 37 client runtime/native entries are byte-identical to .32; all seven server
assets match source. Payload changes are manifest/classes and the new server
hosts file, plus signing metadata. No runtime dependency update is needed.

Full client CI is still running at delivery: push Box64 **108037283340**, FEX
**108037283382**; PR Box64 **108037110774**, FEX **108037110708**. Check these
next turn; do not claim all CI green. No unchanged reruns or relaxed gates.

.32 follow-through: PR FEX job 108031656057 failed the existing optional graphics
trial because baseline GPL was not confirmed (gpl_fast inactive; draw/presentation
check timed out). Earlier software/FEX coverage passed. Push FEX **108031574898**
has completed successfully. Both .32 Box64 jobs **108031574807** / **108031656085**
remain running at .33 delivery. No unchanged rerun or weakened check.

Next phone test after delivery: install .33 over .32, Server → Import your
working server → Deploy matching server + database, then Start managed server.
Keep the already-imported source and SQL, installed server runtime and accepted
client settings. No rebuild/reimport/update from upstream is requested.

---

# 0.5.32: exact BFD 2.45 compatibility for imported server

The 0.5.31 phone support ZIP `lsb-support (2)(7).zip` confirms dependency update
completed, then deployment failed before database activation. All four imported
xi executables report only `libbfd-2.45-system.so => not found`. Their loader lists
do not show dynamic jemalloc linkage. Do not infer static allocation choices or
claim jemalloc caused this failure. Deployment reuses the imported binaries;
rebuilding uses source defaults without forced jemalloc preload/linker flags.

Version .32/code 48 supplies checksum-pinned official Ubuntu ARM64 BFD 2.45 and
its SFrame 2 dependency in a dedicated compatibility directory, enabled through
ldconfig. It does not downgrade system binutils or replace its headers/linker,
alias a newer BFD library, change imported binaries, or change client defaults.
Tools marker v4 prompts the existing in-place runtime update. See
`docs/bfd-compatibility.md` for exact packages, hashes, provenance and checks.

Source commit **145ca88cd6679f0b0dbe38e5bc2bfa8383dc5ae9** passes both native
ARM64 server jobs: push **108030758382** / run **36122418320**, and PR
**108030772547** / run **36122422696**. All 12 integration markers pass, including
reproducing the exact missing BFD SONAME, installing its verified libraries,
calling real BFD object operations, retaining the current toolchain/imported
binary bytes, and starting/stopping all four BFD/jemalloc-linked test processes.
MariaDB deployment, restore, rollback and account checks also pass. The user's
actual private server archive and phone deployment remain untested here.

Both Android build jobs **108030881245** and **108030881386** pass 44 Android
checks, 43 tar checks, 82 runtime contracts and 34 server unit tests. Android
regressions cover the v3-to-v4 upgrade retaining imported source/SQL/databases;
new package checks cover integrity bounds, archive fallback and preserved live
libraries/config on download or staged-loader failure. Wide/narrow update panels
were reviewed. Both presentation and Windows jobs also pass.

Delivered **LSB-Android-0.5.32.apk**, code 48, 18,334,067 bytes, SHA-256
`a5cd7748b0ce87205aa17aac8c9e8b25503b7694f8e35aacfa7ba460ac155152`.
Library `libfile_446925fe8d3881919f0f89e75d6ab2bd`, version 0,
file `file_00000000079481f5bdd0e257dc6f6274`. Original update certificate retained
(`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`).
Version/package, v2/v3 signing, alignment and ZIP integrity verify. Non-signature
payload matches CI artifact **10857348593** (ZIP SHA-256
`97d5906a2e552a64811674aa1f9f2c67fc120272395736f6fc770daf45bfcc42`).
All 37 client runtime/native entries are byte-identical to .31; all six server
assets match source. Payload differences are manifest/classes, bootstrap.sh and
new bfd_compat.py, plus signing metadata. No client reinstall or cache reset.

Full client jobs are still running at delivery: push Box64 **108031574807**, FEX
**108031574898**; PR Box64 **108031656085**, FEX **108031656057**. Check these next
turn; do not claim all CI green. No unchanged retries or weakened gates.

Follow-through on .31 CI: PR run 36087783616 completed successfully, including
Box64 job 107924095368 and FEX job 107924095363. Push run 36087781630 passed FEX
107923961334, but Box64 107923961292 failed its existing D3D8 texture/geometry/
render-target pixel check with exit 12 on llvmpipe. It is separate from the
server dependency error. No unchanged rerun or weakened gate was attempted.

Phone retry after delivery: update APK in place, stop client and old Termux
server, Server → Import your working server → Update server runtime and build
tools, then Deploy matching server + database. Preserve existing imports; do
not fetch upstream or rebuild just to fix this specific missing library.

---

# 0.5.31: imported-server dependencies and jemalloc support

Fresh phone support ZIP `lsb-support (1)(5).zip` confirms .30 installed Ubuntu,
MariaDB and build tools successfully at 21:35:59 CDT. Deployment then failed on
`xi_world` at 21:37:17 before database import. The old validator discarded ldd
output, leaving only a generic missing-library message and empty server logs.
The precise missing SONAME is therefore unknown; do not claim jemalloc is proven
as the cause. User recalls requiring jemalloc for the old Termux/proot server.
Historical context found a proposed `-ljemalloc` build flag but no verified
actual link/preload command. The user's server archive has not been supplied here.

Version .31/code 47 installs libjemalloc2 and libjemalloc-dev and marks server
tools v3. Existing runtime installs offer **Update server runtime and build tools**;
this reruns dependencies without replacing the Linux root, imported source/SQL,
or deployed databases. Stale deployment errors are cleared before bootstrap.
Deployment/rebuild requires updated tools; the imported binaries stay unchanged.
No forced jemalloc preload or linker override is added. Prelinked jemalloc
binaries can resolve their allocator; fresh builds retain source allocator defaults.

Dependency validation now inspects all four binaries, reports exact missing
library names and ABI-version failures, checks loader errors/timeouts, and stores
loader details in `dependencies.log`, included in server log/support exports.
Static executables require independent ELF confirmation when ldd returns an error.
Tests cover error details and preserved binaries, dependency upgrades preserving
imports/databases, support export, and the upgrade UI. ARM64 integration now uses
jemalloc-linked server fixtures and a deliberately missing shared library to
verify failed deployment keeps the active pair and restoring the library permits
validation without recompilation. Local 30 server unit tests pass. Both Android builds and ARM64 integration now
pass for **f8c845c56d2b7f9ac50aedbd21356b528db0e001**. Push run **36087781630**
and PR run **36087783616** pass 44 Android tests (including dependency upgrade,
retained imports/databases, support export and update UI), 43 archive checks,
82 runtime contracts, 30 server unit tests and all 11 ARM64 integration markers.
The real jemalloc-linked synthetic processes start/stop and report their allocator
version. The missing-library fixture fails before import without changing the
active pair; restoring that library permits validation without recompilation.
Actual user's server remains untested. Wide/narrow upgrade panels reviewed.

Delivered **LSB-Android-0.5.31.apk**, code 47, 18,329,898 bytes, SHA-256
`885b44cd25897d57023d0b9d7981f9078dad788bd49328ec3221058fdf10d0da`.
Library `libfile_52c83fd30b008191b830addd0908a927`, version 0,
file `file_00000000904881f99fa8a753fc9b6c05`. Original update certificate retained;
package/version, v2/v3 signatures, alignment and ZIP integrity verify. Complete
non-signature payload matches push artifact **10844945377**. All 37 client
runtime/native entries are byte-identical to .30; all five server assets match
source. Changes are Android manifest/classes, bootstrap.sh, manager.py and signing
metadata only. No client runtime reinstall, cache reset or source update needed.

Both runs' presentation and Windows gates also pass. Full client CI remains
running at delivery: push Box64 **107923961292**, FEX **107923961334**;
PR Box64 **107924095368**, FEX **107924095363**. Check these next turn and do not
claim all CI green. No unchanged rerun or weakened gates.

Prior .30 follow-through: FEX jobs failed existing optional client performance
trials: push 107918076905 timed out trial A's pixel check; PR 107918076921 rejected
trial E because baseline GPL was not confirmed. Both passed earlier FEX/software
and D3D8 coverage. No retry or weakened check. Both .30 Box64 jobs completed successfully. These optional client-trial failures
are recorded separately from the phone's server dependency error.

Next phone test: update APK in place; Server → Import your working server →
Update server runtime and build tools; then Deploy matching server + database.
Do not reimport files, change client settings or fetch newer server source. If
another dependency remains unavailable, the new error/support log names it.

---

# 0.5.30: Ubuntu runtime extraction fix

User reports server runtime installation failed on 0.5.29. Support ZIP
`lsb-support(20260925-020848).zip` shows `Unsupported runtime tar member (type x)`
before bootstrap or database deployment. Server source ZIP (4/4 root binaries,
expected client30251204_1) and20MiB SQL were already imported successfully on
phone. Keep them; install this APK in place and retry Server → Import your working
server → Install server runtime and build tools. Then Deploy matching server +
database. Do not reset app data or reimport client/source/SQL for this error.

The checksum-pinned Ubuntu26.04.1 ARM64 base has6564 PAX per-entry headers with
atime/ctime/mtime metadata. Old Java extractor reproduces the exact exception on
this archive. Fix adds bounded PAX parsing before existing extraction safety checks.
Build gate now extracts the exact pinned download using the production Java parser
and verifies its file contents, normalized executable modes, hardlinks and symlinks.
Synthetic regressions cover metadata scopes, overrides and malformed archives.
Client settings and all native/runtime assets remain unchanged; extraction is shared,
so GNU/USTAR compatibility must also pass. Version 0.5.30/code 46. No physical-device
runtime installation has been claimed; retry on Thor is the next device check.

Continuation recovered the exact pushed fix **e7c52599efeb3c2cd5f306ff171022c34d75a0e7**
after the previous conversation reached its length limit. The saved local files
match that commit. A fresh local check passes all 43 tar regressions and verifies
5,407 files, 755 directories, 287 symlinks and 115 hardlinks against the pinned
Ubuntu archive; all 110,784,659 regular-file bytes and executable modes match.

Delivered **LSB-Android-0.5.30.apk**, 18,329,898 bytes, SHA-256
`828c32ad1bcbb3662195224ba73a1c6d2fc779bede7baf125a7582f87cf1874b`.
Library `libfile_bbe00fa4f2e88191b553be6fdb6eb396`, version 0,
file `file_0000000000f081f9b1cfccb71cc3ecf5`. The original update signer is retained;
package/version, v2/v3 signatures, alignment and ZIP integrity verify. All
non-signature payload entries match CI artifact **10844170202** from push run
**36085869588**. All 37 client runtime/native entries are byte-identical to .29,
and all five server assets match committed source. APK changes are limited to
the Android manifest, classes.dex and signature metadata.

Both push **36085869588** and PR **36085872176** pass the Android build/41 Android
tests, 43 archive checks, 82 runtime contracts, 24 server unit tests, presentation,
Windows launcher and real ARM64 MariaDB deployment/recovery gates. The latter
passes all nine integration markers using synthetic server processes; this does
not establish deployment or gameplay with the user's actual server archive.
At delivery the full client jobs remain running: push Box64 **107918076729**,
FEX **107918076905**; PR Box64 **107918076822**, FEX **107918076921**. Check them
next turn; do not claim all CI green. No unchanged retries or weakened checks.

Phone retry: install .30 over .29 without uninstalling or clearing app data.
Stop the client and old Termux server, then choose Server → Import your working
server → Install server runtime and build tools. Existing source and SQL imports
are retained; do not reimport for this error. Once installation completes, choose
Deploy matching server + database, then Start managed server and use 127.0.0.1 in
the client. If a step fails, export a fresh support ZIP and report the failed step.
No source update, client runtime reinstall, merge or public release was requested.

Prior0.5.29 push36066678276 and PR36066682077 now completed **success**, including
both Box64/FEX runtime gates. The older pending notes below are historical.

Additional received SQL `lsb-database-20260925-015102.sql.gz` statically validates:
4,422,916 compressed bytes,21,073,107 SQL bytes,125 tables,9 triggers. No actual
restore of this private SQL has been run here. Actual accounts.id is signed INT
AUTO_INCREMENT, unlike current reviewed account schema gate. Need review matching
server source before broadening account creation. No source ZIP supplied to this
workspace yet; only phone's support inventory. Do not publish private SQL or rows.

---

# 0.5.29: matching server import, player accounts and database recovery

User has accepted the client and now wants to run their matching server ZIP,
create accounts easily, import/export the database, and later compile official
LandSandBoat source. Repository edits/pushes remain authorized. **No server ZIP
or SQL dump is attached yet.** Finish app delivery, then request those files;
do not claim their actual server has been deployed. The existing Termux server
and accepted client/xiloader remain the reference pair.

Server tab now groups matching-server setup, player accounts, full database
backup/restore, explicit later source updates, logs and its own support tile.
New repository defaults point to LandSandBoat/server base; saved choices persist.
[Exact setup instructions and scope](server-first-0529.md).

Imported ZIPs allow one complete source tree under wrapper folders with sibling
notes. Source reports show the expected client version, preferring local settings.
Prebuilt deployment recovers unique missing xi_* binaries from build/; a requested
rebuild discards old outputs and accepts newly built executables. Ambiguous build
outputs only block prebuilt deployment, not a fresh build of the same source.
Initial deployment and restore preserve server data/ runtime assets.

Accounts require stopped processes, current account tools, the deployed source's
reviewed bcrypt login contract and matching InnoDB schema. Printable ASCII login
1–16/password1–32 bytes, spaces retained, normal status/privilege1, max-ID allocation
at least1000, database-collation duplicate check under a table write lock. Credentials
use a bounded one-use stdin frame and never enter arguments, intents, settings,
request/status/support logs. Interrupted/late failure does not imply rollback of
an account that may already have committed. bootstrap adds python3-bcrypt and a
v2 tools marker; existing installations update tools without replacing databases.

SQL restore clones the currently deployed server files, not selected new source;
verifies binary hashes, preserves client pairing and local-zone policy, imports
into a new database, closes MariaDB, then atomically activates. No source fetch,
compilation or migration on restore. Previous server/database pair is retained.
Database exports use a completed temporary file before replacing the last export.

Local core/credential transport and server unit tests pass. Real ARM64
MariaDB integration covers complete SQL objects, private bcrypt account creation,
duplicates, restore from a mismatched selected source, failure and rollback. It
uses synthetic xi_* server processes: exact LandSandBoat world play remains a
device check requiring the user's archive. Android service lifecycle, source ZIP
and server UI tests are included. Server operation ownership covers preparation
through completion so simultaneous starts cannot overwrite maintenance requests; CI/signing/delivery evidence follows when ready.

First .29 CI at05928c6: both verify jobs pass41 Android tests,29 account
transport checks,82 runtime contracts and21 server unit tests. ARM64 MariaDB
integration exposed restore rejecting an excluded .venv/bin/python symlink after
a build; import/full export/update/rollback passed before that failure. Follow-up
prunes exactly the excluded copy paths, retaining external/recursive link checks
for copied content and build binary recovery.24 server unit tests now pass. No
weakened gates or unchanged retry; real integration reruns on this code fix.

Delivered **LSB-Android-0.5.29.apk**,18,325,802 bytes,SHA-256
`c647d2d224a49c9f289b3efa80c99ab8dcd0441ebb82e35924f8026406999b73`.
Library `libfile_a5b81db72a7881918ac8ab9ebf183f1a`,version0,
file `file_0000000021d0822f9c45d0e9cbdcaadb`. Original update signer retained;
package/code45,v2/v3 signatures,alignment and signing payload identity verified.
All37 client runtime/native entries are byte-identical to .28. Bundled server
assets match committed source. Wide/narrow account and database panels reviewed.

Final implementation **ba179358579e0912ce90c003c97f4ccaacf77b96**. Push
run36066678276,APK artifact10836441930;PR run36066682077. Both verify jobs pass41
Android tests,24 server unit tests,82 runtime contracts and29 account transport
checks. **Both real ARM64 MariaDB integration jobs pass**, including all9 PASS
markers for full SQL objects,cloned restore/failure/rollback,bcrypt accounts,
duplicates,credential-log exclusion and managed fixture process startup/shutdown.
Push presentation and Windows gates pass. Full unchanged runtime CI remains
pending at delivery: push Box64 job107858914937,FEX107858914975;check those and the
matching PR runtime jobs next turn. Do not call all CI green yet.

Install APK in place. Server → Import your working server → install runtime/tools,
import complete matching server ZIP plus full SQL/GZ,deploy prebuilt first or
explicitly rebuild the imported revision. Stop Termux server before starting the
managed one;Client connection127.0.0.1. Stop client/server before account or DB
maintenance. Attach ZIP and SQL dump next so its exact source/client/binary
compatibility can be inspected. No full upstream update until the imported server
works. The detailed guide contains the exact button labels and exporter command.

Prior .28 CI follow-through complete: push36057571648 and PR36057577993 pass all
Box64/FEX/presentation/server/Windows/build gates. Do not change client binaries,
settings or the accepted Trial F default while implementing this server phase.

---

# 0.5.28: Trial F promoted to a proven default

User: “Trial F - thats the one. Its perfect. Integrate that as a default function.”
Fresh exports (11)(1) and (12) confirm actual filtering on device, successful exit
and smoother display delivery. [Evidence](thor-syscall-filter-results-0528.md).
Do not ask for another Baseline/D/E/F matrix; normal play confirms this update.

Runtime syscall filtering now lives in **Proven fixes**, default on for the
validated FEX/Turnip26/DXVK2.7.1/two-worker launch profile, past experiments off.
Saved F selection migrates to Baseline automatically; new explicit off is preserved.
**Use tested shader settings** enables filtering and two workers and resets trials.
A/D/E remain experimental, temporarily decline filtering to avoid new combinations.
B/C stay retired. Other saved renderer/display settings are preserved.

Host preflight, actual-launch activation proof, bounded private marker drain,
receipt validation, fail-stop on inconsistent activation and compatibility fallback
are unchanged. Guest status now separates runtime_acceleration from performance_trial.
Recovery for a filter activation failure is to turn off Runtime syscall filtering
in Proven fixes, not merely choose Baseline (which now enables the proven default).
No driver/renderer/FEX/PRoot/presentation binary change.

Prior .27 full CI now passes in BOTH push36054096976 and PR36054103253: real
PRoot enabled-filter preflight+FEX graphics/audio/input/stop proof and A/D/E384pixels
confirmed. Previous pending statuses below are historical. The .28 delivery record follows.

Delivered **LSB-Android-0.5.28.apk**, 18,313,443 bytes, SHA-256
`fb76ae2c83794b7333b581dab304d1eec6ba3a551e49648f12e2ba54fafc8805`.
Library `libfile_d08af7da6c9881918475e098b3a61723`, version 0,
file `file_000000000eec81f6b5e9b0585d5a4ce6`. Original update signer;
package/code44, v2/v3 signatures, alignment and payload identity after signing
verified. Only supervisor.py changes in runtime/native payload versus .27;
all renderer, FEX, PRoot, preflight, presentation and fixture binaries unchanged.
Wide/narrow Proven fixes panels reviewed.

Source `67e147ce496e74d22a96838f3cc9eb5271d4a945`, push run36057571648,
APK artifact10832759007. Local82 runtime contracts and8 helper tests pass.
Both .28 verify jobs pass31 Android tests and APK builds; presentation/server/
Windows gates pass. Full .28 runtime CI pending at delivery: push Box64
107829449362 and FEX107829449419; PR run36057577993 Box64107829323396 and
FEX107829323430. Follow through next turn; do not substitute the previous .27
full passes for these .28 results. No unchanged retries or weakened gates.

Install in place; previous F selection migrates automatically. Normal play is
sufficient; no new comparison matrix, runtime reinstall, reimport or cache reset.
The feature can be disabled under Proven fixes if troubleshooting is needed.

---

# 0.5.27: separate D/E/F optimization candidates

User asks for more potential optimizations and implementation. Added D CPU-cached
geometry buffers, E GPL fast-link without background optimized variants, F checked
PRoot syscall filtering. All opt-in, independent and retain two compiler workers.
No phone gain claimed; A remains available, B/C retired, existing working renderer
and FEX precision unchanged. [Design, risks and tests](thor-optimization-trials-0527.md).

D/E verify exact settings and 128 startup pixels each; E requires confirmed GPL.
F requires isolated credential-free preflight plus actual launch activation marker,
then guest validation of a session-matched receipt. Preflight declines retain
compatibility; inconsistent real activation/effective graphics baseline stops
before game launch. No in-process false rollback or credential replay. Raw PRoot
output is discarded. History includes matching F receipts, still six sessions.

Delivered **LSB-Android-0.5.27.apk**, 18,313,443 bytes, SHA-256
`42b66980e973fe0fba1560b915ad501f61565d1b27d2af8e42d02c13f82308de`.
Library `libfile_5b4bca94f6d0819181694945c334fa46`, version 0,
file `file_00000000da04822f8da1ebf3655d09d1`. Original signer retained;
package version/code43, v2/v3 signatures, alignment and signing payload identity
verified. Against 0.5.26 only supervisor.py and graphics-check.exe changed in the
runtime/native payload, with new proot_preflight.py. All renderer/Wine/FEX/PRoot/
presentation binaries are byte-identical. Wide/narrow trial previews reviewed.

Source **61e51b5baac1af8e2d4a02c6cdfd378264f4d6e6**. Push run **36054096976**,
APK artifact **10831483400**. Core/77 runtime contracts/eight host helper JUnit
tests/real SysV preflight and Windows fixture compile pass. Both CI verify jobs
pass **30 Android tests** and APK build; server/presentation/Windows gates pass.
Full .27 runtime jobs remain running at delivery: push Box64 **107817584243**,
FEX **107817584316**; PR run **36054103253**, Box64 **107817968483**,
FEX **107817968603**. Follow through next turn: confirm new enabled-filter
preflight/integration and A/D/E 384-pixel result. Do not claim full CI green or
native F proof yet. Finite stage markers aim to locate intermittent CI pixel
stalls; assertions/timeouts unchanged. No retries or relaxed checks.

Prior .26 latest push 36051583691 now passes all runtime gates; latest PR Box64
also passes, but PR FEX retains later cancellation timeout after A/2.7.1 passed.
Earlier .26 push FEX and PR Box64 also passed. These previous passes do not
substitute for .27's new trial gates. No merge/release requested; edits and pushes
explicitly authorized.

Phone test: in-place update; Use tested shader settings, same full 720p route,
Baseline then D/E/F separately, Stop/export after each. No runtime reinstall or
cache reset. Failed F activation: return to Baseline. No repeat B/C, and do not
stack A with the new trials. No phone gain claimed; inspect actual activation
receipts before comparing F. Final response explicitly notes full CI pending.

---

# 0.5.26 follow-up: fresh Baseline/A logs verified

Before APK delivery the user supplied `lsb-support (9)(2).zip` and `(10)(2).zip`
from the previous build. They are a **new** Baseline/A pair, not recovery of the
original overwritten pair. Baseline session `7f8d465e-0b07-475c-acbf-cb20cea9677d`
uses 2 workers; A `921258d1-f3bc-4b0b-adde-84b248eddddd` confirms 1 worker and 128
passing startup pixels. A's graphics_tuning still describes its 2-worker starting
profile; performance_trial and DXVK effective config confirm the override.
Both have full 1280×720 background/interface, FEX v3/strict64, DXVK 2.7.1, SHM,
Turnip 26; geometry/sysmem/border are off. Baseline restores C's 540p background.
Export 10's previous Baseline receipts duplicate export 9 exactly; deduplicate.

After the first minute, excluding terminal windows, weighted Surface posts/s are
27.25 Baseline over 100.25 seconds and 27.45 A over 160.40 seconds. These are not
actual game FPS or scene-matched samples. A remains promising, not proven; no
percentage improvement claim. Both fluctuate and both have early long gaps with
responsive unchanged replies. No evidence the Android copy or log writer explains
those waits; game/asset/shader/render timing remains unresolved. Full measurements,
ZIP identities and selection rule: [report](thor-trial-results-0526.md).

The requested repeat A check is now satisfied. Do not ask for another matrix just
to recover settings. Normal play with A if preferred, 720p, past experiments off;
export after a noticeable pause. Baseline remains 2 workers. B/C stay retired.
Update trial description for these new verified logs; UI-only commit
`cd290be19d5ec51657202fb1bd043d39794fd660` follows recovery implementation
`4f60a37b30188139364d4969565814fd1c6c79ee`.

Final `LSB-Android-0.5.26.apk` is 18,309,268 bytes, SHA-256
`0474b2a67f3f16a8e8dd1e913dd624520ad46c115b50c05ddf9406f66fc09d4a`.
Library `libfile_0e87e80ecba08191aac36df30902f5af`, version 1, file
`file_0000000048cc822f8cec65553716b828`. Original signer; identity, alignment,
signatures and rendering binary identity against 0.5.25 checked. This supersedes
the preliminary 0.5.26 APK listed in the historical entry below. Final push run
36051583691 / artifact 10830034448. Both final verify jobs pass (22 Android tests),
server/presentation/Windows pass; updated wide/narrow UI previews checked. Full
runtime jobs pending: push FEX 107809166178 / Box64 107809166474; PR run
36051588916 FEX 107809407297 / Box64 107809407350. Do not claim full CI green.

Prior implementation CI follow-through: push Box64 107806214712 failed the
existing observed D3D8 pixel fixture's 60-second timeout on DXVK 2.5.3, before
trial activation; this is distinct from the real-device B freeze. Keep the failure
recorded. Earlier PR FEX 107806500337 also failed: 2.7.1 preflight timeout caused
2.5.3 fallback, then the exact-version assertion failed at integration.py:107.
Its earlier negative-test exceptions were expected. No retry or relaxed assertion.
Earlier push FEX 107806214742 and PR Box64 107806500417 need follow-through.
The UI-only wording update does not fix those timeout failures.

---

# 0.5.26: A promising, B/C retired; six-session support history

User reports last four tests: baseline unchanged; A small improvement; B freezes
before menu; C severe camera-pan regression to low single digits. First two
older runs had staged geometry on; exclude those from the intended comparison.
[Full evidence and next test](thor-trial-results-0526.md).

**The uploaded ZIP contains only B and C.** Previous two-slot rotation overwrote
Baseline/A. Do not pretend to verify four sessions or use unrelated stale `.new`
files as missing evidence. B/C effective settings verified: geometry and sysmem
off, 2 workers, SHM, border disabled. B passes preflight then freezes; C applies
960×540 background correctly and has intermittent long gaps. Root causes remain
unresolved. A is user-reported promising, not proven or quantitatively measured.

0.5.26/code 42 disables B/C in UI and runtime, retains A, and adds six-session
settings/launch/timing archives to support ZIPs. Only before-launch/export I/O,
no per-frame changes. Existing old B/C slots are seeded; overwritten earlier runs
cannot be restored. 720p selected profile restores C's previous background size.
Next check: in-place update, Use tested shader settings, Windowed 1280×720, then
select A. Past experiments/border/startup capture off. Same 3–5 minute route,
Stop/export one ZIP. No B/C repeat, reinstall or cache reset.

Prior CI: PR FEX passed full 0.5.25 gate. PR Box64 and push FEX failed the existing
observed-pixel 60-second timeout on 2.5.3 before the new trials. Push Box64
107794714596 subsequently passed. Keep the two failures recorded; no
unchanged reruns or weakened gates. Repo edits/pushes authorized; no merge.

Delivered `LSB-Android-0.5.26.apk`, 18,305,172 bytes, SHA-256
`701022255be4037fdcdfbaf54dd619f642f2a501418ca4b8a97bdf1430a5f8a2`.
Library `libfile_0e87e80ecba08191aac36df30902f5af`, file
`file_000000005f1081f4b0e5ae39a2876bbc`. Original signer retained; signatures,
alignment, package, contents and native binary identity against 0.5.25 checked.
Implementation `4f60a37b30188139364d4969565814fd1c6c79ee`; push run
36050716378 artifact 10829958320. Local core/69 runtime contracts pass. Both CI
verify jobs pass all 22 Android tests and APK build; server/presentation/native
Windows gates pass. Wide/narrow UI previews reviewed. At 19:54 UTC full runtime
jobs remain pending: push FEX 107806214742 / Box64 107806214712; PR run
36050722375 FEX 107806500337 / Box64 107806500417. Follow through next turn;
do not claim full CI green. No unchanged retries or weakened checks.

Earlier entries below are historical.

---

# 0.5.25: three isolated performance trials delivered

User confirms two-worker improvement and requests at least three independent
variables. New **Optimization trials** selector: A one compiler worker,
B retain shader pipelines, C 540p 3D with 720p interface. Defaults to Baseline;
no automatic change of saved settings. Baseline button resets trial selection.
[Evidence, rationale and phone instructions](thor-three-trials-0525.md).

A/B receive real startup pixel/config checks; failure restores the validated
two-worker environment. C changes only background dimensions and uses existing
backup/readback/rollback handling. Two workers remains the accepted profile;
new trials have no phone-performance claim yet. Keep past experiments, border
adjustment and startup capture off. No FEX/runtime reinstall or cache reset.

0.5.24 gates followed up: both Box64 and PR FEX passed; push FEX had a 45-second
2.7.1 preflight timeout, correctly fell back, then failed exact-version acceptance.
Recorded, not retried or relaxed. See new report. Border sizing issue is separate.
Repo edits/pushes authorized, no merge requested. Version 0.5.25/code 41.

Delivered `LSB-Android-0.5.25.apk`, 18,305,172 bytes, SHA-256
`777f8e6bbb12eaac2f4abcecfc339981fe7f4037de6bddf6ca416629d138380e`.
Library `libfile_7545cab12c3481919990247881cb9424`, file
`file_000000007be881fdb1594aca43e32012`. Original signing key retained.
Implementation `57d5871f7ae5554e82de697e44854a54848c8e60`; PR run
36047112262 artifact 10828707828. Core, 68 runtime contracts, 5 server,
19 Android tests, existing native-Windows checks, APK integrity/signatures
and native UI preview checks pass. New A/B Wine pixels and C Wine registry
checks are inside still-running FEX 107794277708 / Box64 107794277654.
Push run 36047105778 also needs final follow-through. Experimental opt-in APK;
do not claim full CI green. Record/diagnose failures next turn, no unchanged
retry or weakened gates. Full identity and limits in the report.

Phone test: install over current app, Use tested shader settings, Windowed
1280×720. Run Baseline then A, B, C independently, relaunching each time.
Same 3–5 minute route and first/repeat camera pans, export after every run.
Keep border/startup capture off, remaining accepted settings unchanged.
C softens 3D only; B uses more memory; A may compile new shaders more slowly.

Earlier entries below are historical.

---

# 0.5.24: two shader compiler workers confirmed on Thor

The new four-way comparison supports **two shader compiler workers on** with
**Turnip system-memory rendering off**. Both-on improved performance; workers
alone retained it; sysmem alone was worse. The older 0.5.23 control also ran
poorly with both off, so an app rollback did not recover performance. Do not
repeat that rollback or the four-way matrix. [Evidence and next check](thor-two-compilers-0524.md).

All effective settings verified in local supplied ZIPs, including the final
ZIP's previous slot for the workers-only run. Improved preflights explicitly
use 2 compiler threads; sysmem-only uses 8. All retain the Turnip 26 driver,
FEX v3/strict64, DXVK 2.7.1, SHM and 60 Hz. Do not confuse the sysmem switch
with disabling/replacing Turnip. Frame histories agree directionally but lack
matched scene markers: no claimed percentage FPS gain or diagnosis of every
remaining pause.

Implementation `c6d1eba232cc17685d22a77f3c168c645c47deb4` is pushed, version
0.5.24/code 40. Two workers moves into Proven fixes; staged geometry joins
sysmem in Past experiments. **Use tested shader settings** sets only workers
on, sysmem off and staged geometry off, synchronizes the checkboxes and retains
the live form. Saved preferences do not change merely on update/open. Other
runtime/display settings and the compatibility fallback remain intact.

This is the regular branch build again, not the manifest-only 0.5.23 control.
No guest runtime, native display or Windows source changes from 0.5.22. Border
adjustment remains available; leave it off in the next performance run. Its
measured 1280×694 sizing caveat remains a separate unresolved display issue.
Do not claim this pass fixes that or adds another performance algorithm.

Next phone test after APK delivery: update in place, Client → Proven fixes →
Use tested shader settings; leave border removal/startup capture off. Keep
Turnip 26, FEX v3/strict64, DXVK 2.7.1, Native Surface/SHM, 60 Hz. Same route
3–5 minutes, first and repeat pans, Stop/export one ZIP. No runtime reinstall,
client preparation, reimport or cache reset. Preserve this profile as the
baseline while investigating remaining stalls.

Delivered `LSB-Android-0.5.24.apk`, 18,309,268 bytes, SHA-256
`afc4a359e936cf7b2a5c79ff3cc3a140231e008217856e17049c6d1c906ccc48`.
Library ID `libfile_72c33ec2f01081919178b0305b8a0329`, file ID
`file_0000000021cc81f5a24754baa453e689`. Original signer retained. Source PR
run 36042830832, artifact 10827736387; identity details in the report.
Core, 65 runtime contracts, 5 server contracts, 19 Android tests, native Windows,
package/signature/alignment and UI preview checks pass. Every runtime binary,
native library and Python asset matches 0.5.22 exactly. The generated bundle
manifest differs only in key ordering; parsed JSON and content hashes match.

New FEX 107780270354 / Box64 107780270519 and push run 36042824628 native gates
remain in progress at delivery. Delivery qualification uses passing Android
checks and the exact previously qualified runtime bytes. Do not call full CI
green or discard any later failures. Follow up on their final outcomes next
turn; no unchanged retries or weakened assertions. The 0.5.22 primary FEX
observed-pixel timeout remains documented.
Repo edits/pushes authorized, no merge requested.

Earlier entries below are historical.

---

# 0.5.23 baseline control: reported slowdown in every 0.5.22 combination

User reports all four border/geometry combinations feel slightly slower.
All four effective states are verified in three new ZIPs (including previous
session slots). No demonstrated staged-upload benefit; keep it off. No retained
geometry option, repeated startup scanning, socket-pixel fallback or dominant
Android copy regression was found. Cause remains unresolved. Full evidence and
one focused next test: [0.5.23 control report](thor-performance-control-0523.md).

Delivered `LSB-Android-0.5.23-baseline-control.apk` is the exact accepted 0.5.21
application payload with only install version metadata advanced to 0.5.23/code
39, then aligned and signed using the original key. **Not a claimed fix.**
All 52 non-manifest payload entries are identical to the pinned 0.5.21 CI APK;
only two manifest bytes change. Complete AAPT2 manifest comparison, runtime
hashes, ZIP, alignment and v2/v3 signature checks pass. Fullscreen and fantasy
UI remain; border removal and staged geometry are absent for this comparison.
Install over current app; preserve data, caches, FEX v3 and prepared client.

SHA-256 `ad7719b1a075282894b83817b42e17eb7098bc3c9045ae772f263e3376663b99`,
18,301,076 bytes. Library ID `libfile_4d1156d21418819193893ba7bfd2ea63`, file ID
`file_00000000ca30820e85a62e26fd560764`. Reproduce with
`scripts/build-baseline-control.py` and the exact source artifact in the report.
**Version code 39 is reserved; next regular APK must be at least code 40.**
Production branch sources intentionally remain 0.5.22; a routine CI build
is not the 0.5.23 control. Do not label that APK as this delivery.

Next: one cooled-device run on the same route/power/fan/charging settings,
3–5 minutes with first/repeat pans; startup capture off. Stop/export one ZIP
and report whether the prior smoothness returned. The older border is expected.
Do not repeat four toggle combinations or add another optimization yet.

Separate finding for later: border receipts moved (6,32) to (0,0), preserving
1280×694, not a full 720-high client. Possible bottom gap needs a sizing fix
once the performance comparison is settled. No causal link to both-off dips.
0.5.22 CI is now finished: both Box64 jobs and independent PR FEX pass; primary
FEX's recorded observed-pixel timeout remains a failure; runtime-release skipped.
No unchanged reruns or relaxed gates. Repo edits/pushes authorized; no merge.

Earlier entries below are historical.

---

# 0.5.22 delivered for device testing: border removal and staged uploads

Latest user reports camera-pan drops when more buildings/NPCs enter view, and
an asymmetric top/left border in both Android display modes. Device evidence,
source diagnosis and next comparison are in [the 0.5.22 report](thor-border-pan-0522.md).
Keep FEX v3 / strict64 / DXVK 2.7.1 / Turnip 26 / SHM / 60 Hz and startup
capture off. No FEX/runtime reinstall, client preparation, reimport or reset.

App changes are implementation `121990d5101fb19b2aba77ecd53c2921cf2c0227`.
Follow-up commits `58e550f2318df4179703b47ba1cdf5bff15acef3` and
`cf503a7fe1e033859ce8e4912760e461cd911223` correct only synthetic tests:
run window geometry with a live display, and allow full 1280×720 on a smaller
Windows CI desktop. No production source differs across these commits.

**Remove game window borders** defaults on. Startup adjusts only the owned,
visible FFXiClass window in registry windowed mode: frame removal, client
origin zero, unchanged measured client dimensions, partial-failure rollback.
It is independent of Android fullscreen and performs no recurring scans.
`process.game_window` receipts record numeric before/after geometry and errors.
Disable this option and relaunch to opt out; fullscreen registry modes are
left intact.

**Staged geometry uploads** defaults off under New optimization. On selected
DXVK 2.7.1 it disables direct vertex/index buffer mapping to test reduced
synchronization waits. Adds copying and is not a demonstrated FFXI speedup.
Startup verifies DXVK acknowledgment plus 128 rendered pixels in both vertex
processing modes, or restores baseline tuning. Existing pixel fixtures now
exercise ordinary locks as well as DISCARD/NOOVERWRITE. Request and active
state are separate in `graphics_tuning`; inspect fallback, not just the toggle.

65 Python runtime tests, core checks, 19 Android tests, UI preview review and
package/signature/alignment/manifest checks pass. Native Windows geometry and launcher tests pass in PR run 35962087529.
Primary FEX 107513222153 passes real Wine/FEX border and launcher checks,
then times out at the 60-second observed graphics fixture after normal staged
128-pixel checks pass. Archive 10792718543 and details are in the report.
Its cause is unresolved; do not claim that run passed. Independent PR FEX
and both Box64 jobs continue; append outcomes before full qualification. Older initial Windows tests
failed because their synthetic decorated window was clamped to the small
CI desktop. Full-size assertions remain; the fixture now supplies larger
WM_GETMINMAXINFO limits and records requested/before/after dimensions.

Signed APK is `/workspace/scratch/ee8c6f15b83e/LSB-Android-0.5.22.apk`, code 38,
18,301,076 bytes, SHA-256
`8a1dfe49bee5e348d4b263da1f1c2492ae0ed898511c478af2585221ff395c93`.
Original signer retained. Library ID `libfile_7739c6e448a08191971ac26e549b4306`,
file ID `file_00000000384881f58534f33f83e6bb64`.
Built from 58e550f run 35961892890 artifact 10792217570, archive SHA-256
`6656162454ba7d1c876f36b7e272b9340bd6e3c0cb20651fd05ad870959239bb`.
The later commit changes tests only. No post-sign payload change.

Delivery is a device-test build qualified by the passing Android suite,
real Windows and Wine/FEX border/launcher checks, staged normal pixel checks
on FEX and Box64, and package verification. It is not an all-green CI release.
The primary FEX diagnostic timeout is retained above; the already-running
independent FEX and both Box64 jobs remain pending at delivery. Inspect their
final outcomes on the next follow-up. Do not rerun unchanged tests or weaken
a timeout/assertion to erase a failure. The new upload mode stays off by
default and restores baseline if its on-device normal pixel preflight fails.

Next device comparison: border fix on, staged uploads off for a baseline;
check all four edges/touch in fullscreen and windowed. Walk the same busy route
and pan twice (first encounter and repeat), Stop and export. Then enable only
staged uploads, relaunch/repeat/export; report correctness, FPS and CS syncs.
Turn the experiment off if slower or incorrect. Preserve both exports.

0.5.21 follow-through: primary Box64 and independent PR FEX/Box64 passed.
Primary FEX timeout remains recorded; never describe that entire run as green.
Repo changes and pushes authorized; no PR merge requested.

Earlier entries below are historical.

---

# 0.5.21 delivered for device testing: fullscreen and export polish

User reports 0.5.20 performance about the same, with remaining slowdowns.
The hidden-cursor optimization is active (85.1% of pointer requests skipped)
but is not a demonstrated FPS improvement. Preserve the accepted FEX v3 /
strict64 / DXVK 2.7.1 / Turnip 26 / SHM / 60 Hz baseline.
[Evidence, implementation and next phone check](thor-fullscreen-0521.md).

This pass adds persistent fullscreen for launcher/game, an in-game toggle,
gold-bordered navigation tabs, and direct support-export tiles on Client and
Diagnostics. Fullscreen hides Android bars and the bottom status strip while
keeping the game menu available. Unchanged/hidden status text no longer causes
redundant periodic updates. Runtime and game resolution are unchanged.

Local core, 63 runtime contracts and all 19 Android checks pass. Native UI
previews are reviewed. Signed 0.5.21 APK package/alignment/original-signature
and CI-payload checks pass. Primary FEX job 107463031167 timed out while
waiting for the trace-probe process to exit, after rendering 300 frames. Its
retained state passes math/pixels/input/audio; the shutdown cause is unresolved.
Evidence artifact 10787106028 and its SHA-256 are recorded in the linked report.
No timeout or assertion changed, and no unchanged retry was requested. Parallel
FEX and both Box64 runs are still running; full CI must not be reported green.
Delivery qualification is the Android suite plus exact
native-asset identity with fully qualified 0.5.20: all changed application code
is on Android; no guest runtime or native capture/input/audio code changed.
The unchanged native-suite reruns continue in GitHub without holding this UI
device-test update. Inspect their eventual results on the next follow-up;
retain any failure evidence rather than silently retrying.

APK SHA-256 `312267378bd71d55c5adc38c195a2cf6d65a05f8da2c4b7e0086dac7ec7f66df`.
VersionCode 37, 18,305,172 bytes. Library ID
`libfile_d4c361e239688191847ab80dc3ed3036`; file ID
`file_00000000ac3481f5ab3019b1553d8713`. Original signer retained; all native
runtime assets match 0.5.20 exactly. Implementation
`d52a7542750fe48f45e3fca1b52abab8a91ef3c4`, primary CI run `35945502024`.
Next phone test after delivery: update APK only, enable fullscreen, check
edge controls, toggling/reopening, touch/controller alignment, familiar world
route, then Stop and use Export support ZIP. Keep startup capture off.
No reinstall, reset, reimport, preparation or old runtime test matrix.
Repo edits and pushes remain authorized; no PR merge requested.

Earlier entries below are historical.

---

# 0.5.20 delivered: accepted world play, fantasy tiles and display optimization

User confirms correct menus/world rendering and best performance so far,
generally 20–30 FPS in world play. Runtime v3 is verified in the new support
export. Preserve this FEX/strict64/DXVK 2.7.1/Turnip 26/SHM/60 Hz baseline.
[Evidence, UI categories, optimization and next test](thor-world-ui-0520.md).

0.5.20 introduces fantasy serif/gold/indigo expandable tiles in two or three
columns, with full-width panels and preserved field contents/settings. Proven
fixes, past experiments, new optimization and diagnostics are separate.
A focused bridge change avoids position round trips for a fully transparent
cursor, resuming live position queries on reappearance. Device benefit is
unverified; the next log includes a skipped-query counter.

Implementation `296b758d97eadc104608fcc91cc1b5427cfab457` passes core checks,
63 Python runtime contracts, 18 Android checks and native Windows checks.
Native-rendered wide/narrow tile previews were reviewed. Both ARM64 gates in
[run 35942316408](https://github.com/Russianranger/lsb-android/actions/runs/35942316408)
pass: FEX has 138 PASS records, Box64 has 163. All seven jobs, including runtime
release identity verification, succeed. A parallel PR run passed all new cursor
checks but timed out in an existing D3D8 pixel fixture; retained evidence is
documented in the linked report. No assertions/deadlines were weakened or
unchanged retries requested.

Signed **LSB-Android-0.5.20.apk**, versionCode 36, 18,305,172 bytes.
SHA-256 `34dd60590aebabedee2468838f3b31ad74875d5f19e603bf84fb42130d7d431d`.
Original signer `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Library ID `libfile_06361dd636288191ac0b0398bde3a65c`;
file ID `file_000000000e5481f9aa09f606ecb9f521`. Payload matches passing
Android CI. The first candidate
was rejected for a banner compile error before any APK was delivered.

Code and pushes are authorized. Next phone action: APK update only; runtime v3
is unchanged. Leave the prepared client, original loader and Termux server
intact. Keep the accepted settings; turn off **Capture FFXI startup and
graphics** in **Capture & diagnostics**. Expand/collapse the new tiles and run
a familiar world route for 3–5 minutes, checking menus, cursor, audio and
controller. Stop and export one support ZIP with FPS/smoothness feedback.
No old test matrices, reset, reimport, preparation or runtime reinstallation.

Earlier entries below are historical.

---

# 0.5.19 delivered: reproduced FEX x87 branch defect corrected

The broader draw test exposed FEX 2510's x87-store NaN helper overwriting
condition flags from a preceding integer comparison. The targeted patch saves
and restores NZCV. Independent synthetic regression: before fix, 8/12 full80
and 4/12 strict64 cases fail; after fix, both modes pass all 12. This is a
confirmed translator defect/fix; actual Thor FFXI rendering remains unverified.
[Diagnosis, qualification and failed-run audit](x87-flags-0519.md).

Implementation `ba052de14f3e10bffe114d209095ade70d56f529` passes all seven jobs
in [run 35933191991](https://github.com/Russianranger/lsb-android/actions/runs/35933191991).
FEX: 138 PASS records; Box64: 163. New branch checks pass in both FEX modes, four Box64 cycles and native Windows;
expanded texture/draw summaries pass on both ARM64 engines.
A parallel PR run hit an existing pixel-fixture timeout without any observer
device or batch records; full evidence and the independent passing push run are documented.
No assertions/timeouts were relaxed or failed jobs retried unchanged.

Signed **LSB-Android-0.5.19.apk**, versionCode 35, 18,301,076 bytes.
SHA-256 `9c04e6dd6e9b4fc694193b51d3ad1b35671427062c5cc694ec0ec58370143d7c`.
Original signer `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Library ID `libfile_f15889612ae08191b5142064fe1b49d4`;
file ID `file_00000000a13481f9895be0cb9a579947`. Payload matches passing CI.
The diagnostic-only candidate of the same version was never delivered.

**Next phone action: APK update AND Install FEX runtime on Runtime.**
The new immutable [runtime v3](https://github.com/Russianranger/lsb-android/releases/tag/runtime-fex-v3)
is published, 318,095,727 bytes (~304 MiB), archive SHA-256
`030f38066af7b786c142e199cb84adec037840b0f029338849e43f99203a93e1`.
Its translator is `6cbf54493c8f9a4ef4ddda6e5e5ebb840988c0094880cb4a038c4e75347bbd8e`;
manifest lists both CPU-feature and x87-flags patches. APK alone cannot apply
this translator correction. It preserves the prepared client and Box64 baseline.

Keep FEX/faster x87/DXVK 2.7.1 and current display settings. Enable **Capture
FFXI startup and graphics**, launch through agreement to character selection,
wait there about 60 seconds, Stop/export Diagnostics, disable capture. One
launch/export; no repeated standalone runtime, precision/DXVK or Box64 matrix.
Preserve prepared client `30251204_1`, original xiloader and Termux server;
no reset/reimport/reprepare/client update. Repo changes/pushes remain authorized.

0.5.18's actual-game SSE2 dispatcher and isolated math worker passed 192 results
while graphics still failed. Do not repeat that diagnostic. 0.5.19 retains it
and adds bounded position/UV/alpha summaries over 180 seconds, up to 1,024 UP
draws per sampled frame. No proprietary code/assets or raw geometry is exported.
World entry remains unverified. Earlier entries below are historical.

---

# 0.5.18 Thor math passes; 0.5.19 broader draw diagnostic in qualification

Latest export `lsb-support(9).zip` (SHA-256
`44393b0f180db2d532348922e5af2740944c451402e8650e87f5ae7957de2280`)
confirms the delivered observer, actual game SSE2 selection (`2/0x222`),
matching CPU bits 12, all 192 synthetic math results and all output-pointer
returns passing. The table remains SSE2 through capture completion. Graphics
still fail in both screenshots at about 13.5 FPS. Do not call this a math or
rendering fix; actual input generation and other rendering paths remain open.

[Latest evidence and 0.5.19 coverage design](draw-coverage-0519.md).
The existing observer stops at frame 216 and samples only the first eight
UP draws every 32 frames. 0.5.19 adds bounded numeric summaries of up to 1,024
UP draws per sampled frame, distributed vertex/decoded-index samples,
position/UV bounds and diffuse-alpha counts, grouped by layout/texture/state
in four time windows. Capture lasts up to 180 seconds. No rendering patch,
texture/vertex dump, runtime update or proprietary code is included.

Local helper checks, 63 runtime tests and warning-free cross-compilation pass.
ARM64 pixel/capture qualification and signed delivery are pending. Do not
present an unqualified APK as ready. Next phone action after qualification:
APK update only, one captured FEX run through agreement to character selection,
wait there about 60 seconds, Stop/export Diagnostics, disable capture.
No repeats of the math check, standalone runtime/precision/DXVK matrix or
Box64 capture. Preserve client `30251204_1`, original xiloader, Box64 and the
Termux server. Repo changes and pushes remain authorized.

---

# 0.5.18 delivered: isolated actual-game math diagnostic

The 0.5.17 Thor export confirms runtime v2 and matching CPU features in both
launch parent/child processes. Missing agreement graphics and severe character
selection corruption remain at about 13 FPS. The CPU-reporting correction
reached the device but did not fix rendering. Actual FEX gameplay/world entry
remain unconfirmed. [Audit, diagnostic design and qualification](game-math-0518.md).

Implementation `f8c3083e1471cde332230213e0b8b7d57cec1dfd` is qualified by
[run 35921514817](https://github.com/Russianranger/lsb-android/actions/runs/35921514817):
all seven jobs pass. FEX has 134 PASS records; Box64 has 155. The new worker
oracle passes twice under FEX, four times under Box64 and on native Windows.
Earlier failed candidates and separate pixel-test failures are retained in the
audit. No assertions/timeouts were weakened or unchanged final jobs rerun.

0.5.18 fingerprints the exact client and selected pure math routines, observes
the live lazy dispatch table, and checks 192 synthetic results on a disposable
worker using captured FP controls. It uses no FXRSTOR on the game thread.
The initial design's checks exposed a FEX context-restoration limitation; the
worker avoids depending on that operation. This is a diagnostic, not a claimed
graphics fix. It ships no proprietary code, disassembly or client data.

Delivered **LSB-Android-0.5.18.apk**, versionCode 34, 18,292,884 bytes.
SHA-256: `093f668754b2d542b1ad6a0a1129809f10b84de599ab9183eb5871a80776653d`.
Original signer: `f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Library ID: `libfile_c52db2ae710481919a70c8569638841e`.
The signed payload matches the passing implementation's build. Only the app
manifest, startup observer and diagnostic parser change (Box64 manifest values
are identical). FEX, Box64, DXVK, Turnip and presentation/audio/input assets stay
identical to 0.5.17. Earlier on-thread 0.5.18 packages were never delivered.

**Next: APK update only, one captured FEX launch and support export.** Keep
faster x87, DXVK 2.7.1, Turnip 26 and Native Surface/SHM. Enable **Capture FFXI
startup and graphics**, launch to character selection, Stop/export Diagnostics,
then disable capture. No runtime download, standalone check or new screenshot
is required for this diagnostic result. Box64 remains the usable baseline.

Audit the next export for observer SHA
`b7e3893f00dba83bd4b4d67d644fe69db70742db421365de47cf9505b403b7ab`.
`main_math_profile.code=0` means the exact image is recognized. Expected feature
bits are `main_math_cpu.detail=12`. Dispatch mode 2 with detail `0x222` would
prove the three selected routines are SSE2; do not assume it before receiving
the result. A complete pass is `main_math_result(code=0, detail=192)` and
`main_math_returns.code=0`. Unsupported, skipped or pending checks are not
passes. `main_math_final` records the later table. Interpret the result within
the documented synthetic-input and worker-thread limits.

Do not request the client DLLs again or repeat the completed precision/DXVK/
Box64 capture matrices. Preserve prepared client `30251204_1`, original
xiloader, working Box64 and the Termux server. No reimport, preparation reset,
client update or server migration. Repo changes/pushes remain authorized.
Earlier entries below are historical.

---

# 0.5.17 delivered: FEX CPU-feature correction qualified; Thor rendering check next

The exact uploaded client DLLs match the working Box64 and broken FEX exports.
Private inspection/replay identified inconsistent FEX CPU reporting: Windows
claimed 3DNow while CPUID disabled it, preventing the linked D3DX dispatcher
from reaching SSE2. A narrow FEX 2510 patch now makes those reports agree.
The actual game's rendering correction still needs the phone result.

Implementation: `451f1290369d01244f0afb5278c1555df7e6780c`.
Isolated ARM64 build `35909118469` reproduced the original mismatch and verified
the corrected path in full80/strict64; all 72 math samples passed in each run.
Full FEX qualification passed with 132 PASS records. Independent Box64 passed
with 151; the release workflow's single targeted Box64 retry also passed.
Workflow `35910276196`, attempt 2, completed all seven jobs successfully.
Earlier graphics-fixture timeouts are retained and documented, with no reduced
assertions. [Detailed evidence and identities](ffxi-cpu-dispatch-0517.md).

[Runtime v2](https://github.com/Russianranger/lsb-android/releases/tag/runtime-fex-v2)
is published and matches the tested archive and sources. Runtime SHA-256:
`3534f21b23272940e94901d1c9e1f9c57b9e3b1c645bc3cdbd101f0fd30fa8fb`.
`LSB-Android-0.5.17.apk` (versionCode 33, 18,292,884 bytes) is signed with the
existing certificate and saved as Library ID `libfile_f1b1eee0d580819196f494b031d7f63d`.
APK SHA-256: `f85f9f3775d813c7c31b98728e39b453dfc72f9e38ae6b9fa66e2d51af6b7148`.

Next: install the APK update, then **Runtime → Install FEX runtime**. Enable
FEX and retain faster x87, DXVK 2.7.1, Turnip 26 and Native Surface/shared-memory
settings. One normal run of the agreement and character-selection screens,
two screenshots and one support export will verify the candidate on Thor.
The automatic launch preflight records `fex_launch_cpu_features` for both
Windows parent/child processes; check this and the new runtime hash first if
rendering still fails. Actual FEX gameplay/world entry are not yet confirmed.

Do not request the DLLs or repeat the completed arithmetic/DXVK/capture matrix.
Preserve client `30251204_1`, original xiloader, working Box64 and Termux server.
No reimport, preparation reset, client update or server migration is needed.
Repo changes and pushes remain authorized. Earlier entries are historical.

---

# Box64 comparison complete: both screens render correctly

On 0.5.16, the same agreement/character-selection screens render correctly
under Box64 at about 29 FPS. The runtime/driver/client checks match the FEX
comparison except for the runtime stacks and separate Wine environments.

All five captured FEX states are also present unchanged in working Box64,
including FVF, texture formats and x87/MXCSR controls. Both captures hit the
65,536 UP-draw limit with 224 unflagged sampled vertices and restore their
hooks. Both contain the line-pattern warning and buffer-tracking limit.
These shared receipts do not identify a cause of the FEX corruption.

[Comparison and automated investigation](box64-fex-comparison-0516.md).
A CI-only synthetic fixture now exercises the captured DXT1/DXT3 formats,
MODULATE2X/alpha/additive combinations, and 378 small UP draws per frame.
Implementation `eda376514a403c6bf0957fb02f337d7aab078b3d` is qualified by
PR run `35886847711`: all six applicable jobs pass; publication skips.
FEX has 130 PASS receipts and Box64 147, including two FEX and four Box64
runs of the new fixture. Each verifies 1,512 draws and 144 expected pixels.
The artifacts and completion receipts are verified. This is a synthetic
lavapipe result, not an FFXI/Adreno fix. Independent push run `35886842062`
times out in existing pixel fixtures before the new test; those failures
remain recorded. No retry or timeout change was used. No production APK or
runtime change is made.

Next source evidence: obtain `FINAL FANTASY XI/FFXiMain.dll` from this same
client import. The support export contains its hash, not the binary, and it
is absent locally. Inspect CPU dispatch/math/rendering call sites to guide
an independent reproducer. Expected SHA-256 is in the comparison audit.
Keep client binaries out of the repository; do not guess a runtime patch.

The requested device comparisons are complete; do not repeat them.
Box64 remains the usable baseline. Keep the prepared client `30251204_1`,
original xiloader and working Termux server. Repo changes/pushes remain
authorized. Earlier entries below are historical.

---

# Thor 0.5.16 game capture received: rendering fault still unresolved

The requested FEX game capture is verified against the delivered APK.
It observed 65,536 UP draws, no failed draws, and 224 sampled vertices with
no non-finite/extreme-position/invalid-RHW flags. It reached its draw cap at
frame 216 and restored its hooks. This covers part of play; it does not
establish that all submitted geometry or the later character-selection scene
is correct. Screenshots still show missing menu elements and severe corruption.

[Current capture audit, limits and next device comparison](thor-game-capture-0516.md).

The game has 569 SHM uploads with zero fallback/attachment failures. Runtime,
DXVK 2.7.1, Turnip 26, strict64, prepared-client and original-loader identities
are correct. Captured states include DXT1/DXT3 textures and pretransformed UP
geometry; no specific CPU, texture or render-state fix is established.
Do not repeat the completed on/off pixel checks, DXVK-version game comparison,
or this exact FEX capture.

**Next uses the existing 0.5.16 APK: one Box64 game capture.** Stop, uncheck
**FEX / native ARM64 Wine · experimental**, retain DXVK 2.7.1 and the other
graphics settings, and enable **Capture FFXI startup and graphics**. Launch
to the same agreement/character-selection screens, screenshot, Stop/export
Diagnostics, then disable capture. This supplies the missing matching capture
from the previously working runtime stack. Its separate Wine environment means
it is not a pure CPU-only comparison.

No binary workaround is justified by this capture alone. Preserve the prepared
client `30251204_1`, original xiloader and working Termux server. No runtime
installation, client preparation, re-import or prefix reset is needed.
Authorization to change and push the repo persists. This checkpoint changes
documentation only. Earlier entries below are historical.

---

# Ready for Thor: bounded D3D8 game capture (0.5.16)

The actual-game DXVK 2.5.3 comparison is complete. Selected DLL hashes confirm
2.5.3; the screenshots show the same missing menu and severe corruption at
about 13 FPS. All 500 game uploads use SHM without failure. A 2.7.1-specific
defect is less likely; the rendering fault remains unresolved. Do not repeat
the completed on/off pixel checks or the actual-game DXVK comparison.

**0.5.16 is a qualified diagnostic build.** Its opt-in capture records the
game's actual D3D8 states and sampled submitted positions, with fixed numeric
metadata, bounded work and no GPU readbacks or recurring process scans.
It is not a claimed graphics fix. [Audit, limits and device procedure](d3d8-game-capture-0516.md).

Implementation `ec8986faa494779712197997a7d04a0389004a8c` is pushed to
`codex/client-baseline`. [PR run 35878718602](https://github.com/Russianranger/lsb-android/actions/runs/35878718602)
passes all six applicable jobs; publication correctly skips. FEX reports
128 PASS receipts and Box64 143, including the real observed pixel fixture.
The independent push FEX job also passes. The initial push Box64 job timed out
in the existing untraced pixel fixture after SWVP passed, before the new
observer loaded. That failure remains recorded; its cause is unknown. No
retry, timeout relaxation or implementation change was used for qualification.
CI uses synthetic clients and lavapipe, not Thor hardware.

Delivered **LSB-Android-0.5.16.apk**, versionCode **32**, **18,288,788 bytes**.
SHA-256: `efeeb880c6c4f8c4efaa32047e8f9dbb4e4ceb2e67e216deef1f7381f80fbcb2`.
Original signer:
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
The APK comes from the passing PR artifact; its payload, v2/v3 signatures,
alignment, package/version and ZIP integrity were verified. Wine/FEX, DXVK,
Turnip and display/audio/input binaries are unchanged. Version text now reads
the installed package metadata instead of the stale hardcoded label.

Install over the existing app. Restore DXVK 2.7.1; keep FEX faster x87 on,
Turnip 26, 60 Hz, Native Surface and SHM, with sysmem/two-compiler tuning off.
Enable **Capture FFXI startup and graphics**, launch once to the broken menu,
take a screenshot, Stop/export Diagnostics, then disable capture. No standalone
Windows check is needed. If launch fails or hangs, Stop/export rather than
repeated relaunches. Diagnostic FPS is not a benchmark.

Preserve prepared client `30251204_1`, the original loader and working Termux
server. No client re-import, preparation or runtime download is required.
Authorization for repo changes and pushes remains active.

Earlier entries below are historical.

---

# Thor 0.5.15 results received: game rendering still unresolved

**All three requested exports have been audited.** Both strict64 and full80
Windows checks pass all 128 pixel samples on Adreno 740 with DXVK 2.7.1 fixed.
Both interactive checks complete. The actual FEX game still has the broken
menu: 186 game SHM uploads, zero fallback/failure, and about 13 Surface posts/s.
Its newly classified DXVK warning is `line_pattern_unsupported`; the exact
DXVK implementation does not establish that warning as the rendering cause.

[Current evidence, source findings and next comparison](thor-pixel-results-0515.md).
Do not repeat the completed on/off Windows checks. On the existing APK, the
next useful test is one launch to the same menu with **DXVK 2.7.1 · experimental**
unchecked (bundled 2.5.3), keeping FEX faster x87 and the other settings fixed.
Capture a screenshot, Stop/export, then restore 2.7.1. This isolates the DXVK
version in the actual failing game; no new build is needed for that comparison.

No rendering fix is claimed. Preserve the prepared client `30251204_1`, original
xiloader and working Termux server. User authorization for repo changes and
pushes remains active. Box64 is the previously working rendering baseline.
The hardcoded `app=0.5.14` export label is stale in 0.5.15; the new pixel and
warning receipts confirm the new diagnostics ran. Correct the version reporting
in the next APK; do not ask the user to reinstall for this label alone.

Earlier qualification and device-test instructions below are historical.

---

# Ready for Thor: graphics diagnostics (0.5.15)

**0.5.14 FEX is not accepted for real FFXI.** Faster x87 improved the user's
reported FPS from about 5 to 13, but menus are missing and character selection
has severe white geometric corruption. The new support bundle verifies
strict64 arithmetic in preflight and the actual launch child, hardware
Turnip 26/Adreno 740, DXVK 2.7.1 and 690 SHM game uploads. Upload copies average
0.721 ms, with zero fallback/attachment failures. An upstream rendering or CPU
translation problem is suspected; no specific rendering fix is established.

**0.5.15 is a qualified diagnostic build.** Standalone Windows checks now verify
128 texture/buffer/transform/render-target/alpha pixel samples in software and
hardware vertex-processing modes. Actual game launches gain fixed DXVK warning
reasons and retain the prior preflight, with no new readbacks or recurring
observer. See [the audit, qualification and device procedure](graphics-corruption-0515.md).

Implementation `66a65375a560ba81a17bf726fba8096acad2e347` is pushed to
`codex/client-baseline`. [Push run 35855473122](https://github.com/Russianranger/lsb-android/actions/runs/35855473122)
passes all seven gates. [PR run 35855479506](https://github.com/Russianranger/lsb-android/actions/runs/35855479506)
passes all six applicable gates on attempt 2; publication correctly skips.
The first PR FEX job passed the new pixels but the later interactive probe
stalled after three uploads. The independent push and targeted retry both
completed 300 frames per mode, audio/input and the complete suite. The initial
hang and its evidence are retained; its cause is unknown. No code or APK change
was made between attempts. CI uses synthetic clients/lavapipe, not Thor hardware.

Delivered **LSB-Android-0.5.15.apk**, versionCode **31**, **18,288,704 bytes**.
SHA-256: `d3be4e0475e8a814564d0cfa3080a36902eb1bb1cf0986d16f2378e45f4af592`.
The original signer is verified:
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
Only the manifest, graphics helper and two diagnostic Python files change in
the APK (bundle.json key ordering also differs). Java, Wine/FEX, DXVK, Turnip,
display/audio/input binaries and prepared client remain unchanged. Install over
the existing app; there is no runtime re-download or fresh-prefix requirement.

## Next device test

1. Keep the current prepared client, original xiloader, client version and
   working Termux server. Keep FEX, Turnip 26, DXVK 2.7.1, 60 Hz, Native Surface
   and SHM selected; keep other graphics preferences fixed.
2. Run Windows checks once with faster x87 on, exit/Stop and export Diagnostics.
   Turn only faster x87 off, repeat Windows checks and export a separate ZIP.
   A failed or hung check should be stopped and exported, not repeatedly relaunched.
3. If both pass, a brief faster-mode game launch to the broken menu can collect
   the newly classified D3D8 warning. Stop/export there; no long route or
   world-entry attempt is required for this diagnostic comparison.

The next task is to use these actual Adreno pixel receipts and specific warning
reasons to localize the rendering failure. Passing this fixture still does not
prove FFXI compatibility. Box64 remains the previously working rendering path.
Do not update/re-import/re-prepare the client, replace xiloader or migrate the
server as part of this comparison. User authorization to change and push the
repository remains explicit and active; no renewed publication approval is needed.

Current 0.5.14 game session: `9c612832-2206-4d98-8fb1-a7afa1e7c414`.
Its previous runtime state is a strict64 probe. Its previous client-launch JSON
is older 0.5.13 FEX session `f1565fe7-80be-48eb-a70a-3236450689e4`, not a second
0.5.14 arithmetic comparison. Audit/version/test artifact details are in the
linked 0.5.15 document. Earlier handoff/validation entries below are historical.

---

# Ready for Thor: FEX arithmetic comparison (0.5.14)

Thor 0.5.13 is **not accepted for real FFXI**: character selection was mostly
black and about 5 FPS. The graphics test passed, but that is not proof of game
compatibility. The current support ZIP has FEX client session
`f1565fe7-80be-48eb-a70a-3236450689e4`; its previous runtime session is a FEX
probe, while `client-launch.json.previous` is the older 0.5.12 Box64 run.
Do not mislabel that older launch as a second FEX game result.

Audit: native FEX execution, Turnip 26/Adreno 740, DXVK 2.7.1, 60 Hz, Native
Surface and 365 actual game SHM uploads are confirmed. No software fallback,
socket-upload fallback or attachment failures. Game upload copy averages
0.736 ms and ring wait 0.0038 ms; late Surface delivery is 5.04 posts/s with
unchanged scenes between posts. The black scene/slow producer is upstream of
Android presentation. 104 normalized first-chance exceptions are fewer than
the old Box64 run's 161, not evidence of an exception storm. Audio runs; its
three early underruns stabilize. Startup observation ends at 8.194 s and does
not resume. Existing COM/module warnings also occurred on the working Box64
path; these receipts do not establish their cause or a new FEX failure.

A concrete test environment bug was found: Android's standalone Windows checks
omitted all client graphics preferences, running DXVK 2.5.3 without SHM, Native
Surface or selected 60 Hz. 0.5.14 shares that request construction with launch.
The client's separate eight-frame 2.7.1 preflight did pass in the failed run;
correcting standalone-test parity alone is **not claimed to fix game FPS**.

FEX's upstream default uses full 80-bit x87 software emulation. 0.5.14 adds an
explicit, default-off **FEX faster x87 arithmetic** comparison using strict
64-bit mode. A PE32 child verifies both inherited controls and actual precision
with `(2^53 + 1) - 2^53`, exact arithmetic, a fixed-work timing sample and QPC.
The same proof runs with the exact launch environment before starting xiloader.
Only a hash of seven fixed controls is retained; no environment dump or new
post-login polling. Box64's launch environment is unchanged. No memory-ordering
relaxation, runtime binary/source update or claimed equivalence to GameHub.
This x87 mode is a hypothesis for translation cost, not a confirmed black-menu
fix. Runtime checking and device acceptance must remain distinct.

## Continuation and release qualification

The user explicitly authorized repository pushes and changes for this
continuation. Correction `c861f78836fff89e2bcda80329db1c5fe07a6d86` is pushed to
`codex/client-baseline`. No further publication confirmation is needed within
this authorized continuation.

The first 0.5.14 CI attempt (`5761a20`, run `35848491912`) exposed a missing
`<wchar.h>` declaration in the new helper. Its expected 64-bit hash was
truncated to a signed 32-bit value, falsely reporting an environment mismatch.
The correction adds the header, makes implicit function declarations fatal
in Windows helper compilation, and exercises full80/strict64 launch receipts
on the synthetic launch/relaunch paths. The failed APK was not delivered.

**All seven CI gates pass** for `c861f78836fff89e2bcda80329db1c5fe07a6d86`
in [push run 35850446810](https://github.com/Russianranger/lsb-android/actions/runs/35850446810):
`presentation`, `verify`, `windows-launcher`, `runtime`, `fex-runtime`,
`server-deployment` and `runtime-release`.
[PR run 35850452933](https://github.com/Russianranger/lsb-android/actions/runs/35850452933)
passes all six applicable gates; its publication job correctly skips.
No build, runtime qualification or release gate is pending.

The FEX suites verify actual full80/strict64 precision and Windows-child
inheritance, including final launch/relaunch environments. Native Wine and
PRoot graphics, PCM, controller, crash/Stop behavior and preservation of every
original Box64 prefix file/link pass. Both full Box64 suites preserve the
accepted display, DXVK 2.7.1 and 2.5.3 fallback, shared-memory upload, audio,
controller and startup-only observer behavior. The 56 runtime contracts,
16 Android tests and existing core, native Windows, display and real MariaDB
deployment/recovery checks pass. CI uses synthetic clients and lavapipe;
Thor game performance and menu correctness remain device acceptance work.

Push evidence artifacts: Box64 `10746177125`, SHA-256
`6df43fd2aaa0d0c7332506221b527c25a095f086ad3cf185ba1c56dbe71fd5a6`;
FEX `10745244740`, SHA-256
`5be4789bd082004c74a2b259a3b87a47216a59e773ede5637305efe68ede4332`.
The PR FEX artifact and observed arithmetic timings are recorded in the
[0.5.14 test document](fex-regression-0514.md).

**Signed device build:** `LSB-Android-0.5.14.apk`, versionCode 30, 18,264,128 bytes,
SHA-256 `6ee3417227f4b98240bd1f775234f32e27632e30da2cf5635d942c53503bb121`.
Original signer SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`.
V2/v3 signatures, alignment, ZIP integrity, package/version, runtime-source
identity and complete non-signature payload identity against CI artifact
`10745144523` verify. CI APK SHA-256
`26312229e8b47184f8759e947a6cc0f53fec207a842f2620e9b720ff2cc0cec7`.

Only the manifest/version, DEX, three runtime Python files and `fex-check.exe`
change from 0.5.13; `bundle.json` has only reordered keys with identical
values. All existing native display, upload, graphics, audio, controller,
launcher, Wine/Box64/PRoot components, FEX runtime and artwork remain
byte-identical. Existing FEX users need no runtime download.

[Evidence, mechanism and focused Thor test](fex-regression-0514.md).
The next device checkpoint is faster x87 on/off at the same scene with FEX,
Turnip 26, DXVK 2.7.1, 60 Hz, Native Surface and shared-memory upload. Keep
sysmem, two-worker tuning and startup capture off. Preserve the working
Termux server, client `30251204_1`, original xiloader, preparation and Box64
rollback. The black-menu/FPS result remains unverified until the Thor test.


---

# Completed milestone: selectable FEX runtime (0.5.13)

The latest 0.5.12 Thor result shows no meaningful improvement from sysmem or
two DXVK workers. Both were actually applied; neither is an accepted FPS fix.
The full log audit, including startup RPCSS/missing-library warnings, is in
[fex-runtime-0513.md](fex-runtime-0513.md). Hardware Turnip, DXVK 2.7.1,
shared-memory presentation, 60 Hz and the startup-only observer were active.
Both game sessions exited cleanly. Preserve the accepted DXVK 2.7.1 improvement,
60 Hz and the 0.5.8 periodic-stutter fix.

The user explicitly requested FEXCore implementation. Work continues from the
existing branch; native ARM64 Wine 10 + FEX 2510 is implemented as a separate
runtime with isolated copied prefixes and an explicit Box64 rollback selector.
No client, server or xiloader source update, re-import, re-preparation or managed
server migration is authorized for this comparison. Keep the working Termux
server and client `30251204_1`.

The native source build passed in run `35811359812`, artifact `10729768931`.
Its complete PE import/export audit includes the upstream Wine thread API
backport `d53a9ba0cd5ee46852b00e4a106e2eb679b5aa3d` (upstream's semi-stub).
Pinned runtime SHA-256 `99c270eefe20e32d942ba6a77ad1ea0d69097ed31b9830879fa749630ce15c89`, 318,095,255 bytes.

The **complete FEX runtime gate passes** for `e60b1b64e5567f7fe4cca2d70cca418cd4fa9edd`
in run `35814095537`, job `107032319489`: actual PE32 FEX execution, copied-prefix
migration, software and DXVK 2.5.3/2.7.1 pixels, actual shared-memory uploads,
60 Hz Native Surface, PCM, input, controller axes/buttons/disconnect, launch,
relaunch, startup-only observer idle/Stop/crash, and patched PRoot all pass.
Every baseline prefix file/link remains unchanged after both container and
PRoot runs. CI Vulkan is lavapipe; Thor FPS and real-client compatibility still
require device testing.

Qualification fixed a missing Wine export, the isolated CI prefix's ownership,
Wine-internal dependency-audit paths, and the FEX debugger hang. Wine matches
`winedbg.exe=` exactly; `winedbg=` does not disable the executable. Correcting
that override **only for FEX** makes the real-window crash and both owned
exception fixtures return `0xc0000094` without hanging. No crash assertion,
privacy filter or startup-only observer policy was weakened.

**Signed device build:** `LSB-Android-0.5.13.apk`, versionCode 29,
18,260,032 bytes, SHA-256
`2c22d4d0e86c64a3b92ec7a7719332901df192532182860630e9ac38ee7668e1`.
Original signer SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`;
v2/v3 signatures, alignment, ZIP integrity, package/version and CI payload
identity verify. Device artifact `10730898789`, CI APK SHA-256
`7e2be16ff4cd28d62cf29bc27848b97d16dda81590f04a2cd84130d388435926`.
Every pre-existing native display, upload, graphics, audio, controller,
launcher, PRoot and Wine/Box64 asset and the purple icon remains byte-identical
to 0.5.12. The FEX-only debugger environment is the final runtime correction.

**All seven CI gates pass** for the final implementation commit
`e60b1b64e5567f7fe4cca2d70cca418cd4fa9edd` in
[push run 35814095537](https://github.com/Russianranger/lsb-android/actions/runs/35814095537):
`presentation`, `verify`, `windows-launcher`, `runtime`, `fex-runtime`,
`server-deployment` and `runtime-release`.
[PR run 35814098449](https://github.com/Russianranger/lsb-android/actions/runs/35814098449)
passes all six applicable gates; its publication job correctly skips.
No implementation test or release gate is pending.

Both complete Box64 suites retain the accepted DXVK 2.7.1 behavior and 2.5.3
fallback, real shared-memory uploads, audio, controller and startup-only observer
idle/Stop/crash checks. The 54 runtime contracts, five server contracts,
15 Android tests and existing core/native Windows checks also pass. The real
MariaDB deployment, failed-update preservation, database export, update,
rollback and four-process lifecycle gate passes. These server fixtures do not
update the user's Termux installation.

Box64 evidence artifact `10731606754`, SHA-256
`ddc513c11c51608dca59f6370c7fd5def507e652d281a6f80cd994ef9c5f16a9`.
FEX evidence artifact `10731625312`, SHA-256
`a9125d7cde5da7465637a2f414fc6611b753504d3c0f1bcbbeddd7bcee75b5ed`.

The immutable [FEX runtime release](https://github.com/Russianranger/lsb-android/releases/tag/runtime-fex-v1)
is published. The runtime asset's size and SHA-256 match the APK pin. Its exact
corresponding source archive is 83,387,434 bytes, SHA-256
`3e98ee0068ef45fad5bc1db8897854f2de2076f36e248230e1cfc9363de8c950`.
The prior source artifact lacking `RtlWow64SuspendThread` was rejected and
never published. During qualification, an earlier Box64 attempt had one
modern-Mesa wineboot exit `-9`; unchanged later complete suites, including
both final runs, pass. No new Box64 fix or cause is claimed for that transient.

**First Thor test:** install the signed update in place, keep the working
Termux server, `30251204_1`, original xiloader and current preparation. Runtime:
install FEX (about 303 MiB; keep 3 GiB free), select it, then run Windows checks.
Use 60 Hz, Native Surface, shared-memory upload and DXVK 2.7.1; keep sysmem and
two-worker experiments off. Repeat a warmed-up route, check audio/right-stick
axes, exit and relaunch, then export Diagnostics. Switch FEX off for the Box64
comparison. Full steps and the log audit are in [fex-runtime-0513.md](fex-runtime-0513.md).
FEX is an opt-in hardware experiment; no Thor FPS improvement is claimed yet.

---

# Completed milestone: Turnip rendering and DXVK compiler scheduling (0.5.12)

The user reports that DXVK 2.7.1 improved the experience, but FPS slowdowns remain.
`lsb-support (8).zip` confirms hardware Turnip 26 / Adreno 740, DXVK 2.7.1,
60 Hz and actual game shared-memory uploads (5,439, no fallback/attach failure).
The ring wait averages 0.0049 ms, so another upload-ring optimization is not
supported by this evidence. The prior 2.5.3 run used the same upload path.

0.5.12 adds **two separate opt-in experiments**, both default off: Turnip 26
system-memory rendering (`TU_DEBUG=sysmem`) and two DXVK compiler workers
(`dxvk.numCompilerThreads=2`). Each applies to the next launch. Their combined
requested environment must pass eight app-owned D3D8 draw/present frames before
login; the worker option also requires DXVK's actual two-worker confirmation.
A failure restores both environment values and retains the already selected
DXVK pair. Requested/active settings and fallback reason appear in Diagnostics.
No client/config/source files are changed. This is a qualified experiment, not
an established fix for the remaining FPS loss. See [evidence and test](graphics-tuning-0512.md).

**All six CI gates pass** for `ae984d3ab8e473fcccf650c83aced0d2fc4c98d5`
in [push run 35803742003](https://github.com/Russianranger/lsb-android/actions/runs/35803742003):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. [PR run 35803744607](https://github.com/Russianranger/lsb-android/actions/runs/35803744607)
passes all five applicable gates; release correctly skips. No current CI gate
is pending or failing.

Both full runtime runs confirm two compiler workers in the actual Wine rendering
process with DXVK 2.7.1 and the 2.5.3 compatibility fallback, alongside real
D3D8 pixels, shared-memory uploads, PCM, input, clean exit and cancellation.
Both pass Linux and PRoot controller enumeration, all four axes, buttons/hat,
release and stale-input neutralization. Startup-only observer idle/Stop/crash
checks remain green. The native display/upload fixtures, 116 core checks,
RGB565, 90 ZRLE checks, native bounds, 49 Python runtime contracts, five server
contracts, 15 Android tests and 36 native Windows checks pass. Real MariaDB
import, failed-update preservation, update, rollback and process lifecycle pass.
CI validates compatibility using lavapipe, not Adreno performance.

The first PR run exposed a crash in the unnecessarily upgraded TigerVNC 1.15.
The modern-Mesa fixture now upgrades only Mesa and required dependencies and
verifies the original X server's binary digest. An initial push run separately
failed PRoot controller enumeration despite native attachment; Docker passed.
That failure did not recur in either complete corrected run. Controller code,
assertions and timeouts remain unchanged; no new controller fix is claimed.
Runtime evidence artifact `10727272220`, SHA-256
`1f9b236f71a59d26b9262925d415ebc6cdc878f54d68c3f5aefe46dd15f34a3a`.

**Signed install-in-place APK:** `LSB-Android-0.5.12.apk`, versionCode 28,
18,214,515 bytes, SHA-256
`7203f7967be42bf52a69673b82853ef708be061094c9d22455c5047a48042a49`.
Original signer SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`;
v2/v3 signatures, alignment, ZIP integrity, package/version and CI payload
identity verify. CI device artifact `10727275250`, APK SHA-256
`fe678dac8727a615be3ed574ab1c9e1b27ee706b13eb35fcbb085215f41a329c`.
Only `AndroidManifest.xml`, `classes.dex` and `assets/runtime/supervisor.py`
differ from 0.5.11. Every native graphics/display/audio/controller/launcher
component, both DXVK pairs, Wine/Box64/PRoot components and purple icon remain
byte-identical. Both new switches off preserve 0.5.11 graphics behavior.

Preserve the existing Termux server, client `30251204_1`, xiloader, preparation,
accepted startup-only observer, 60 Hz, audio, controller and relaunch behavior.
Keep DXVK 2.7.1 and shared-memory upload enabled for the focused Thor comparisons;
there is no need to retest 30 Hz or update/migrate any source.

---

# Completed milestone: Vulkan presentation and DXVK comparison (0.5.11)

The user authorized GPU presentation and DXVK work after reporting no noticeable
improvement from 0.5.10. GameHub's reported 60 FPS came from its overlay; the user
confirms smoother gameplay. This clarification is settled. Do not ask it again.

0.5.11 adds an independently reversible shared-memory Vulkan upload path and
upstream DXVK 2.7.1 comparison. The upload path bypasses eligible full-frame X11
socket payloads, but still performs GPU readback; it is not direct GPU-to-Android
presentation. Both features have startup checks and baseline fallback. DXVK
2.7.1 defaults off, while the new upload path defaults on. Actual selected
versions, checksums, fallback reasons and numeric upload counters are exported.
No recurring process/module scans or timer-driven telemetry flushes are added.

**All six CI gates pass** for `cf5bd4675a05d8133e51b019ab7a9094dd78119d`
in [push run 35798263910](https://github.com/Russianranger/lsb-android/actions/runs/35798263910):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. [PR run 35798267691](https://github.com/Russianranger/lsb-android/actions/runs/35798267691)
passes all five applicable gates and correctly skips release. No CI tests or
failures remain pending.

The actual Wine/DXVK process performs 300 SHM uploads with both DXVK 2.5.3 and
2.7.1. The modern Mesa 25.0.7 fixture selects 2.7.1 and passes all eight preflight
frames plus the main triangle/pixels/input/PCM/exit tests. The baseline Mesa
22.3.6 fixture rejects incompatible 2.7.1, restores both 2.5.3 DLLs and completes
the same gameplay-independent checks. CI never claims Adreno hardware.

Native upload tests pass in Linux and PRoot: 58 calls, 40 SHM uploads, 18 row
fallbacks, zero attach failures and two connections. Forced socket mode passes
all exact pixels with zero SHM uploads. Ring reuse, caller-buffer ownership,
resize, checked errors and reconnect pass. PRoot exercises Android-compatible
memfd emulation. The existing ten native-display cases also pass.

Controller axes/buttons/hat/release/stale-input tests pass with both host
preloads enabled. All 62 launch cases and six startup-observer idle/Stop/crash
checks pass, retaining the accepted stutter fix. Also passed: 116 core checks,
RGB565 and 90 ZRLE checks, native frame bounds, 44 runtime contracts, five server
contracts, 15 Android tests and 36 native Windows checks. Real MariaDB
deployment, failed-update preservation, update/rollback and process lifecycle
pass. Runtime evidence artifact `10725910618`, ZIP SHA-256
`e04f025f4995d437f18cba87bf1284609477f696a39489f845ae2ca38ac90173`.

CI harness corrections restored executable permissions on the downloaded test
helper, selected Trixie's actual `lvp_icd.json` filename, and made container-owned
binary evidence readable by the artifact uploader. The modern Mesa environment
is CI-only; the distributed Wine/Box64 rootfs remains unchanged.

**Signed install-in-place APK:** `LSB-Android-0.5.11.apk`, versionCode 27,
18,214,515 bytes, SHA-256
`7f2c0d63b4a41251af8dc38ead280084720749dc842a5fa6e3f1a0019064ef84`.
Original signer SHA-256
`f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e`;
v2/v3 signatures, alignment, ZIP integrity, package/version and payload identity
against CI artifact `10724269877` verify. CI APK SHA-256
`a79a2e86364a54c02529b0593d5d67f63099fe28912cf6a454cc72a94820b0dc`.
The driver, baseline DXVK pair, launcher/startup observer, audio, controller,
PRoot, native capture/Android Surface and purple icon remain byte-identical to
0.5.10. Added/changed payload is limited to version/DEX, supervisor, component
manifests, the new upload library/checker, finite graphics checker, candidate
DXVK pair and upstream license. Both graphics options off retain the previous
binaries, renderer environment and transfer path.

**Next Thor test:** install over the existing app. Use the working Termux server,
FFXI client `30251204_1`, original xiloader and accepted preparation. No source
update, import, re-preparation or managed-server migration. Keep 60 Hz, Native
Surface, the same resolution, normal FPS HUD, startup capture off and other
settings unchanged. First test shared-memory Vulkan presentation on / DXVK
2.7.1 off; then enable only DXVK 2.7.1 and repeat the warmed-up route. Stop and
export Diagnostics after each run. Check audio, right-stick camera and relaunch.
Both options off provide graphics rollback. See [full mechanism and test](gpu-presentation-0511.md).

Superseded by the 0.5.12 follow-up above: the user reports improved performance
with DXVK 2.7.1, but remaining slowdowns. The new log confirms it was active. Retain the user's accepted
0.5.8 periodic-stutter fix and 60 Hz preference; do not treat CI pixel checks as
a game FPS benchmark or repeat 30 Hz/compression tests as the main experiment.

---

# Latest Thor result: 0.5.10 has no noticeable gameplay improvement

The user reports no improvement and says the earlier GameHub Lite / Proton 10
setup reached 60 FPS. The new bundle confirms the metadata cache works (one
geometry query and three cursor-image fetches across 13,195 polls), but this is
not an accepted gameplay speedup. The 0.5.8 periodic-stutter fix and 60 Hz remain
accepted. [Device evidence, runtime comparison and next direction](thor-runtime-gap-0510.md).

Prioritize the unresolved rendering/runtime gap: the app forces CPU X11 Vulkan
presentation, and uses Wine WoW64 + Box64 + DXVK 2.5.3. The saved GameHub profile
uses Proton ARM64X + FEX + DXVK 2.7.1 async with a different Turnip build label.
Hardware Turnip rendering does not rule out presentation, translation or driver
bottlenecks. Add effective graphics/present metadata and a reversible DXVK
comparison, then qualify an accelerated presentation / ARM64X-FEX candidate
separately from the working runtime. Do not repeat display-only tuning as though
the main bottleneck were established, blindly remove the WSI software flag, or
claim generic Proton/FEX packages reproduce the recorded GameHub setup.

That earlier 0.5.10 follow-up changed documentation only; it is superseded by
the completed 0.5.11 milestone above. Preserve the matching Termux server/client `30251204_1`, original
xiloader, preparation, login/audio/controller behavior and rollback baseline.

---

# Completed milestone: reduce repeated X11 metadata queries (0.5.10)

The user confirms 60 Hz improves the Thor experience and reduces drops into the
teens; 30 Hz restores the degraded experience. Keep 60 Hz as the recommended
Thor test setting. Overall game FPS in the 20s and occasional hitches remain.
The recurring module-scan stutter fixed in 0.5.8 remains resolved.

The new comparison verifies 60 Hz in both monitor modes, 30 Hz in the middle
run, MIT-SHM throughout, and the accepted startup-only observer in all three.
At 60 Hz, over half of the display polls return unchanged images but each still
queries screen geometry. Cursor images are also downloaded on every capture.
0.5.10 caches geometry/cursor metadata, invalidates it on X11 events, and retains
live pointer polling. In the real X11 fixture, the same 88 requests / 52 captures
use 6 geometry queries instead of 88 and 14 cursor-image queries instead of 52.
All 88 live pointer queries remain. Exact pixels, cursor movement/shape/hotspot,
transparency, clipping, real resizing and reconnect pass in Linux and PRoot.
Query counts and timings are included in exported producer logs.

**All six CI gates pass** for `9612125345448fa4778484664a02167bd5629028`
in [run 35790527598](https://github.com/Russianranger/lsb-android/actions/runs/35790527598):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35790531672)
passes all five applicable gates and correctly skips release.
Runtime evidence artifact `10722148392` confirms all ten
native test combinations (eight cached paths and two polling controls),
Android-compatible memfd/MIT-SHM, 62 client-launch scenarios, six idle/Stop/crash
observer checks, controller preload/input/disconnect, PCM, DXVK HUD and three
supervised 60 Hz Wine captures. All 15 Android tests and real MariaDB deployment,
update and rollback pass. No CI failures remain pending.

**Signed install-in-place APK:** `LSB-Android-0.5.10.apk`, versionCode 26,
15,940,768 bytes, SHA-256
`611620c7ecac7eb28182001c6847e01351cad231b76e6fe7e25651d217b5f73f`.
Original signer, v2/v3 signatures, alignment and ZIP integrity verify; payload
matches CI artifact `10721413053`. Launcher/runtime/graphics/audio/controller
binaries and icon match 0.5.9. Only version metadata, DEX, the display producer
and its checksum manifest change semantically; the runtime manifest differs
only in JSON key order.

[Evidence, validation and focused Thor test](display-metadata-0510.md).
The query reduction is verified; the subsequent Thor test reports no noticeable
FPS improvement. The runtime/presentation follow-up above replaces the original
test request. Another 30 Hz comparison is not needed.
Preserve the same Termux server/client `30251204_1`, original loader, accepted
preparation, pinned Wine/Box64/Turnip/DXVK, audio and controller behavior.
Do not update source, re-import, re-prepare or start managed-server migration.

---

# Accepted Thor result: periodic stutter fixed in 0.5.8

The user confirms the consistent stutters have stopped. The new 0.5.8 receipt
shows startup observation complete at 13,740 ms, with no further scans through
exit at 261,588 ms. In matched 70–210-second log windows, gaps over 100 ms
fall from 172 to 19 and the recurring clusters disappear. These are display
delivery measurements, not game FPS; the routes were not controlled benchmarks.
The remaining issue is game FPS in the 20s with drops into the teens.

0.5.9 implements the supported 60 Hz display cap, retains 30 Hz as the existing
default, and removes redundant native frame copying. The accepted startup-only
observer and all working login, audio, controller and source paths are retained.
This display option does not unlock the game's own FPS limit.
[Implementation, evidence and Thor sequence](display-refresh-059.md).

**All six 0.5.9 CI gates passed** for implementation
`735e5c114d1cc22bc15df1bd65c257fe5510517e` in
[run 35784458788](https://github.com/Russianranger/lsb-android/actions/runs/35784458788):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35784465163)
passes all five applicable gates; release is correctly skipped there.
All 62 launch scenarios and 15 Android tests pass. Both 30/60 Hz capture modes
pass with MIT-SHM and XGetImage in Linux and PRoot, including exact/duplicate
pixels, ownership, idle frames and reconnect. Three supervised Wine captures
run at 60 Hz with audio/input; HUD, memfd, controller/preload, startup-only
observer, Stop/crash handling and real MariaDB deployment/update/rollback pass.
No CI failures remain pending.

**Signed install-in-place APK:** `LSB-Android-0.5.9.apk`, versionCode 25,
15,940,768 bytes, SHA-256
`583842b3a8b0185b3c7e08511496912ac678ca9661a5a9552cf69ee850cf7ba5`.
Original certificate, APK v2/v3 signatures, alignment and ZIP integrity verify;
every non-signature entry matches CI artifact `10718983323`. Only the manifest,
DEX, native capture helper and its checksum manifest change from 0.5.8; the
stutter-fixed launcher, runtime/graphics/audio/controller paths and icon match.
[Validation and artifact evidence](validation.md).

Keep the existing Termux server/client `30251204_1`, original xiloader and accepted
preparation. No source updates, re-import, re-preparation or managed migration.
The paired Thor comparison is complete: the user accepts 60 Hz as better.
Keep Native Surface on, Fast/compression/startup capture off and normal FPS HUD
on for the next comparison. Another 30 Hz run is not needed. Periodic-stutter
acceptance remains recorded; remaining FPS drops and the 0.5.10 follow-up are above.

---

# Completed implementation: stop intrusive gameplay module scans (0.5.8)

The 0.5.7 Thor result still stutters. New timing evidence shows capture and the
Android frame worker remain responsive during repeated game-image pauses; the
asynchronous reporter has no dropped windows and only millisecond write costs.
The launcher still scans the child's complete DLL list every three seconds after
startup, through Wine cross-process reads that can suspend a game thread.

0.5.8 stops those scans once the FFXI window is observed and then waits on the
child process handle. Exit/crash detection, Stop and retained startup evidence
remain. New integration scenarios verify that the observer stays idle while a
healthy window remains open and that normal exit, Stop and late crash still work.
[Evidence, source audit, correction and Thor sequence](thor-stutter-058.md).

**All six CI gates passed** in [run 35780131808](https://github.com/Russianranger/lsb-android/actions/runs/35780131808)
for `c5b3ac3760c846ec139a1fe624113a09df4ecb61` (implementation `7795ddec15d77af7c395257cfd029128f567efe5`
plus the test synchronization correction): `presentation`, `verify`,
`windows-launcher`, `runtime`, `server-deployment` and `runtime-release`.
The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35780138540)
passes all five applicable gates; its release job is correctly skipped.
All 62 launch scenarios pass. The new idle/normal-exit, Stop and late-crash
checks pass separately in ARM64 Wine/Box64 and PRoot. All 14 Android tests,
three supervised native captures, detailed HUD, memfd/MIT-SHM, controller/preload,
audio and real MariaDB deployment/update/rollback checks pass. No CI failures
remain pending.

The APK is built with the original signer and matches the final CI payload. Artifact:
`LSB-Android-0.5.8.apk`, versionCode 24, 15,940,768 bytes, SHA-256
`0e7548c3e5fd39e19aefb6c28daabaa5cf19bb9c01937ebe25ab2a7326f40b01`. Keep the existing matching Termux server/client `30251204_1`, original
xiloader, accepted preparation and pinned runtime/driver/audio/controller path.
No source updates or managed-server migration. The periodic-stutter correction
is accepted on Thor; remaining game FPS drops are still unresolved.

---

# Previous implementation: remove reporting from the display worker (0.5.7)

The authorized next step is implemented: Native Surface transfers bounded numeric
samples to a separate diagnostics writer, preserving session isolation and the
final report. New measurements record individual post gaps with idle context,
stage maxima, loop delay and writer duration. An opt-in stutter HUD adds frame
times, shader compiler and DXVK worker activity. Normal FPS HUD behavior and
all pinned runtime/graphics/audio/controller binaries are retained.
See [implementation, validation and Thor sequence](display-worker-057.md).

**All six CI gates passed** for implementation
`8c75aa70c785bf9824b53fbd13f60ffdf7b0cee2` in
[run 35775283832](https://github.com/Russianranger/lsb-android/actions/runs/35775283832):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35775289943)
passes all five applicable gates; its release job is correctly skipped.
All 14 Android tests, 56 launch scenarios, three supervised Wine/native captures,
the detailed DXVK HUD, Android-compatible PRoot memfd capture, controller/preload,
audio/Stop checks and real MariaDB deployment/update/rollback pass.
No CI failures remain pending.

The original-signer APK is built and verified: `LSB-Android-0.5.7.apk`,
versionCode 23, 15,940,768 bytes, SHA-256
`374e1c17bee9f5cab76c6f5567462446d1f2b43416103ffe26668f4d03c6b2b8`.
The subsequent Thor comparison confirms stutters remain with both HUD modes.
The 0.5.8 investigation and test sequence above supersede this initial device check.
Keep the existing matching Termux server/client `30251204_1`, original xiloader,
accepted preparation, 720p/30 Hz, Native Surface on and Fast display/compression
off. No runtime/driver replacement, client/source update or managed migration.

---

# Previous device result: remaining periodic stutter after 0.5.6

The latest Thor result confirms MIT-SHM is working: all 6,565 posted frames use
shared memory and comparable capture time falls from 25.18 to 4.00 ms. The user
reports no noticeable gameplay improvement and stutters every 3–5 seconds.
Surface throughput remains about 22 posts/s; this is not game FPS. **0.5.6 is
confirmed to correct capture; gameplay smoothness remains unresolved.**
See [device comparison, driver-variable audit and next target](thor-performance-056-result.md).

Turnip 26/Adreno 740 and native DXVK DLLs are verified by the available checks.
The app's `MESA_VK_WSI_DEBUG=sw` still selects CPU-based presentation into Xvnc;
removing it needs a compatible accelerated display path. There is no evidence
that a speculative Turnip debug flag will fix the reported periodic pauses.
The concrete next target is `NativePresentation.report()`: it serializes/writes
the retained diagnostics on the frame worker every five seconds. Move reporting
off that path and add bounded frame-gap/report-duration measurements. Its actual
contribution is not yet measured. Shader activity, CPU scheduling and increasing
audio underruns also need observation; five-second averages cannot isolate them.

That investigation preceded the 0.5.7 implementation and validated APK above.
Keep Native Surface on, Fast display/compression
off and the existing matching Termux server/client `30251204_1`, original xiloader
and accepted preparation. No runtime/driver replacements, client/source updates
or managed-server migration.

---

# Completed implementation: enable Android shared-memory capture (0.5.6)

The 0.5.5 Thor test improves subjectively in the second run but still stutters.
Both sessions actually use Native Surface, with no RFB pixel traffic. The concrete
remaining fault is X11 capture falling back to XGetImage: `shmget` returns errno
38 because the app omitted PRoot's `--sysvipc` option. The packaged runtime already
supports memfd-backed emulation. Capture averages about 25–27 ms/frame while the
Android pixel copy is about 0.43 ms. [Device evidence and correction](thor-native-capture-056.md).

0.5.6 enables that option only for Native Surface launches. The CI display fixture
now forces emulation and requires its memfd marker so native Linux SysV IPC cannot
mask the omission again.
The earlier 0.5.5 Linux MIT-SHM result is not proof of Android emulated capture.

**All six CI gates passed** for implementation
`b3aec4c3b910c0755cbe1bfe626a2a46b6368911` in [run 35769148142](https://github.com/Russianranger/lsb-android/actions/runs/35769148142):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. The [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35769152473) passes all five applicable gates;
its release job is correctly skipped. Saved PRoot evidence confirms both the
memfd-emulation marker and successful MIT-SHM capture with exact pixels and no
X errors. All 56 launch scenarios, three supervised Wine/native captures,
controller/preload/audio/Stop checks and real MariaDB deployment/update/rollback
pass. No CI failures remain pending.

**Signed install-in-place APK:** `LSB-Android-0.5.6.apk`, versionCode 22,
15,936,672 bytes, SHA-256 `a88af15a75fb447d5dd894583b65f477c7c2ff616aa36f6fc44fe4e637a86d11`.
Original certificate, APK v2/v3 signatures, alignment and ZIP integrity verify;
every non-signature entry matches the CI build. All native/runtime binaries,
graphics, audio/controller/loader components and purple icon match 0.5.5.
[Artifact evidence and device limits](validation.md).

Preserve the existing Termux server/client `30251204_1`, original xiloader,
accepted preparation and all pinned runtime/graphics/audio/controller binaries.
First next-device check keeps the better second-run settings: 720p, Native Surface
on, Fast display/compression off, startup capture off and DXVK HUD on. Stop and
launch afresh, repeat the same route, check audio/axes and Stop/relaunch, then
export Diagnostics. Require actual MIT-SHM capture/nonzero `shm_frames` before
assessing the FPS change. Thor improvement is unverified; no source update or
managed migration. Native Surface off restores the prior display/PRoot invocation.

---

# Historical 0.5.5 handoff (capture fault superseded above)

# Active milestone: shared-memory Native Surface trial (0.5.5)

The latest Thor comparison confirms startup capture off at both resolutions, yet
960×540 gains only about 2 game FPS and stutters remain. 0.5.4 is not accepted as
a smoothness fix. 0.5.5 removes RFB pixel compression/transport/Java Bitmap updates
from the active display path via an optional shared-file Native Surface, with
automatic fallback. X11 readback remains; actual Thor gains are unverified.
See [evidence, implementation and focused Thor test](native-surface-055.md).

**All six CI gates passed** for implementation
`a70eac356cb1bccb6b2fc23014ead33e0c7655fb` in [run 35759197659](https://github.com/Russianranger/lsb-android/actions/runs/35759197659):
`presentation`, `verify`, `windows-launcher`, `runtime`, `server-deployment` and
`runtime-release`. [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35759204024) passes all five applicable gates; its release
job is correctly skipped. Validation includes 116 core/preparation/login checks,
RGB565 and 90 ZRLE checks, 39 runtime/5 server contracts, 10 Android API 33 tests,
36 native Windows checks and all 56 ARM64/PRoot launch scenarios. Real native
capture tests pass with MIT-SHM and XGetImage in both environments; supervised
Wine/D3D8 pixels reach the shared framebuffer alongside PCM and RFB input.
Existing DirectInput axes/release, preload isolation, audio/Stop checks and real
MariaDB deployment/update/rollback pass. No CI failures remain pending.

**Signed install-in-place APK:** `LSB-Android-0.5.5.apk`, versionCode 21,
15,932,576 bytes, SHA-256 `02b3fa438f10459737bebf9cc9814b9bb3f277bbf5bce27fba1cb328e0783a7e`.
Original signing certificate, APK v2/v3 signatures, alignment and ZIP integrity
verify. Every non-signature entry matches CI; all previous runtime/native binaries,
audio/controller/loader components and purple icon match 0.5.4 byte-for-byte.
[Full evidence and limitations](validation.md). Actual Thor FPS improvement is
unverified; do not call this an accepted smoothness fix before the device test.

Preserve the working matching Termux server/client `30251204_1`, original xiloader,
accepted preparation, Wine/Box64, graphics/audio/controller baseline and purple
icon. No source updates or managed-server migration. First device test: native
on, capture off, 720p, same 30 Hz cap, compression retained for fallback.

---

# Historical 0.5.4 handoff (superseded by 0.5.5 above)

# Historical milestone 4: lossless display transfer and purple icon (0.5.4)

**Latest Thor result:** 0.5.4 still jumps around 6–28 game FPS with Fast display and compression on; compression off is reported far worse. The new bundle verifies about 65% payload savings with compression, stable single-bitmap allocation, and more long delivery gaps in the uncompressed comparison. Smoothness remains unresolved. Both launches still used startup capture. See [measured comparison and next test](thor-performance-054-result.md). Keep both performance options on; the next action uses existing 0.5.4 settings, with no runtime/driver replacement or new APK.

The 0.5.3 Thor test still stutters, with the user reporting 5–28 FPS. Its new timing history confirms the reusable bitmap stays at one allocation, but steady display updates average 13.16 Hz: receipt takes 45.73 ms/update, bitmap work 1.58 ms and Android draw submission 0.085 ms. This supports testing the display transfer path next; it does not establish that all game FPS drops are caused there. See [device evidence, implementation and focused comparison](display-transfer-054.md).

0.5.4 adds lossless ZRLE transport with low server compression effort, preserves the native RGB565 copy path, and provides **Compress display transfer · lossless** to compare against the prior Raw path. Timing history now records encoded bytes and decode costs. A purple adaptive-icon background replaces Android's white legacy surround while preserving the crystal artwork.

Implementation `b3502c75c08859f2d328900efa456edd8525b152` passes all four applicable gates in [PR run 35728160635](https://github.com/Russianranger/lsb-android/actions/runs/35728160635): build/Android, native Windows, ARM64 runtime and real MariaDB deployment/update/rollback. Real TigerVNC tests pass directly and through PRoot, with matching Raw/ZRLE pixels in RGB565 and RGB888. Synthetic RGB565 transfer falls by 90.9%; this is not a Thor FPS measurement. All 56 launch cases, native controller/audio/render checks and nine Android tests pass. The earlier server toolchain and DirectInput failures remain resolved. The first push attempt had an isolated synthetic relaunch exit 97; see [validation](validation.md) for its report and retry evidence.

[Push run 35728155096, attempt 2](https://github.com/Russianranger/lsb-android/actions/runs/35728155096) is also fully green: `verify`, `windows-launcher`, `runtime`, `server-deployment` and `runtime-release`. The unchanged runtime suite passes on retry, including all 56 launch cases and both real display-wire environments. No assertions were weakened or additional runtime changes made for that retry.

Signed `LSB-Android-0.5.4.apk` is versionCode 20, 15,915,863 bytes, SHA-256 `f27385cad9e7544206133546d8ef0aeb9555693fd0f91b834d19f66cb741f4c3`, using the original certificate for an install-in-place update. Every non-signature entry matches CI. Native/runtime binaries, original icon PNG and bundle manifest remain byte-identical to 0.5.3; changed entries are the manifest, Android DEX/resources and the supervisor's low-effort compression setting, plus adaptive-icon XML. Actual Thor smoothness remains the device acceptance criterion.

Next Thor test: existing Termux server, accepted preparation and client **30251204_1**, same Turnip/display cap, Fast display and compression on, DXVK HUD on. **Stop client**, open **Graphics and launch options**, uncheck **Capture FFXI startup result**, then use **Launch FFXI** for a brief 1280×720 baseline; reopening a running view does not disable its startup observer. If stutters persist, stop and relaunch using **Windowed 960×540 (lighter)** for a 3–5 minute route and export immediately. Keep other settings fixed. The compression-off comparison is complete and is no longer the recommended next test. Check audio, camera axes and Stop/relaunch.

Preserve the accepted generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505` and original nested loader SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`. Wine/Box64, graphics drivers, audio and controller code are preserved. Do not update client/xiloader/server source toward **30260904_1**, re-import/re-prepare, or begin managed-server migration during this comparison. Older device sequences below are historical.

---

# Previous milestone 4 checkpoint: display smoothness (0.5.3)

The user accepts 0.5.2 boot, both controller axes, relaunch and improved audio. The new Thor diagnostics corroborate two successful launches, clean dependency checks and seven-axis DirectInput; audio underruns stay nearly flat after startup. Preserve that working baseline. The outstanding device issue is stutters/FPS drops, not launch recovery.

0.5.3 replaces the three-shape RGB565 bitmap cache with a single reusable software staging allocation. An actual Android/Skia regression workload reduces 120 bitmap allocations (207,120,000 cumulative bytes) to one (1,843,200 bytes), with matching pixels including odd rectangle widths. Timing diagnostics retain up to 180 windows, identify sessions/connections, record update gaps and decode/draw costs, flush on display close and serialize/write on a bounded background worker. The previous final-only sample cannot establish gameplay FPS or its limiting stage. See [evidence, scope and Thor comparison](display-smoothness-053.md).

Implementation `24569520699dd5566fe1b99caf797418be48bdc8` passes [full CI run 35723063302](https://github.com/Russianranger/lsb-android/actions/runs/35723063302): `verify`, `windows-launcher`, `runtime`, `server-deployment` and `runtime-release`. All nine Android tests, 56 launch cases across ARM64 Wine/Box64 and PRoot, native input/audio/render checks and real MariaDB deployment/update/rollback pass. The corresponding [PR run](https://github.com/Russianranger/lsb-android/actions/runs/35723068434) passes its four applicable gates. The earlier server toolchain and DirectInput failures remain resolved.

Signed `LSB-Android-0.5.3.apk` is versionCode 19, 15,911,683 bytes, SHA-256 `cf85f0263ffe43cbfe2ef1ae013475351404774541b6f7535e9320955423f1c7`, using the original certificate for an install-in-place update. Every non-signature entry matches CI. Compared with 0.5.2, only the Android manifest/DEX and JSON key ordering in `runtime/bundle.json` differ; all runtime hashes and binaries are unchanged. See [validation and artifact identity](validation.md). Allocation reuse and pixel correctness are verified; actual Thor FPS improvement remains to be measured.

First Thor comparison: install in place, use the **existing working Termux server** and the same accepted 1280×720/Turnip settings with Fast display enabled. Disable Capture FFXI startup result for normal gameplay, keep the DXVK FPS HUD enabled, move and rotate the camera in a repeatable area for 3–5 minutes, then export Diagnostics immediately. Report the HUD FPS range and whether drops occur during motion, zone entry or steady play. Confirm audio and Stop/relaunch still work. A 960×540 comparison is optional only after this same-settings run.

Preserve accepted generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, original nested Ashita xiloader SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`, client **30251204_1**, current Wine/Box64 runtime and matching server source. Do not update client/xiloader/server source toward **30260904_1**, re-import/re-prepare the client, or begin managed-server migration during this test. The older retry sequences below are historical.

---

# Previous milestone 4 checkpoint: loader initialization and camera axis (0.5.2)

The 0.5.1 Thor retry reaches the runtime and completes controller setup; the Android null-session crash is no longer observed. The failed client attempt stops before xiloader: exactly `WS2_32.dll` fails initialization with Windows error 1114, while the other 19 imports pass. The following 32-bit controller-setup process successfully loads that DLL. The underlying initialization cause is not proven. See [device evidence, repair boundaries and focused retry](loader-controller-052.md).

Implementation `3ea8a01a2886b43ed3ac43885a39adf3b88ce21b` (following `aa33cf57dbff428408aac3bb5e1311c68b5b8c8b`) confines the gamepad worker to Wine's HID host, adds one fresh full dependency check only for that exact complete WS2_32 failure, preserves both receipts and adds checker symbol diagnostics. All imports must pass with a clean exit before xiloader can start. The right-stick vertical defect is concrete: four SDL slots exposed X/Y/Z/Rx, so Rz was absent. The helper now exposes seven slots with X/Y/Z/Rz input and neutral Rx/Ry/Slider; the extra neutral slider prevents Wine’s six-axis Xbox heuristic from remapping controls. Android and real DirectInput tests cover both signs on all four stick axes.

[Full CI run 35718338735](https://github.com/Russianranger/lsb-android/actions/runs/35718338735) passes every gate: `verify`, `windows-launcher`, `runtime`, `server-deployment` and `runtime-release`. All 56 launch cases (28 per environment), seven-axis DirectInput checks, controller-preloaded Thor imports, native helper isolation, audio/render regressions and real MariaDB deployment/update/rollback pass. The corresponding PR run also passes its four applicable gates. The earlier server toolchain and DirectInput CI failures remain resolved.

Signed `LSB-Android-0.5.2.apk` is versionCode 18, 15,903,491 bytes, SHA-256 `f6585a47626e9f6ebdc0c18ebf6e69891725014039eb395148becb68d76de6be`, using the original certificate for an install-in-place update. Every non-signature entry matches the CI artifact. Compared with 0.5.1, only `AndroidManifest.xml`, `classes.dex`, `runtime/client_launch.py` and `liblsb-gamepad.so` differ. See [validation](validation.md) for exact artifact checks and limitations.

First Thor test: install in place, start the **existing working Termux server**, launch the accepted client and confirm login/world entry plus clean audio. Then open FFXI gamepad setup, reselect the virtual joystick if necessary, assign camera **Z/Rz**, and check both right-stick directions plus release/Stop/relaunch. Export Diagnostics immediately if the dependency check still stops. This retry is now complete: the user confirms successful boot, recognized camera axis, clean relaunch and much better audio on 0.5.2. The next task is display smoothness; use the current instructions above.

Preserve user-confirmed 0.4.8 behavior, accepted generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, original nested Ashita xiloader SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`, client **30251204_1**, current runtime and matching server source. Do not update client/xiloader/server source toward **30260904_1**, re-import/re-prepare the client, or begin managed-server migration during this retry. The 0.5.1 and 0.5.0 retry instructions below are historical and superseded by the linked 0.5.2 sequence.

---

# Previous milestone 4 checkpoint: Android display startup recovery (0.5.1)

The first 0.5.0 Thor attempt closed the Android app immediately when opening either the client or controller setup. A new Android 13 lifecycle regression reproduces a null-session `NullPointerException` in the actual `RuntimeActivity` controller timer before the worker creates a session. Two tests fail before the fix; all three pass afterward. Implementation `4a28a0d1508c59d1c26d6645a7f42efc47e44d2e` waits for a valid session, publishes its ID safely across threads and releases mapped input on focus loss. The new Activity tests are required in CI. See [diagnosis and focused retry](android-startup-051.md).

Preserve user-confirmed 0.4.8 login and clean audio, accepted client generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, original nested Ashita xiloader, client `30251204_1` and its existing working Termux server. This fix does not change the Wine/Box64 runtime, native helpers, audio, rendering implementation, version repair, client files or server sources. Do not update toward `30260904_1` or perform the managed-server migration during the crash-recovery retry.

The earlier server-toolchain and DirectInput CI failures are resolved. [Full CI run 35671938279](https://github.com/Russianranger/lsb-android/actions/runs/35671938279) passes every gate: `verify` (including the three Android 13 lifecycle tests), `windows-launcher`, `runtime`, `server-deployment` and `runtime-release`. Both ARM64 Wine/Box64 and PRoot pass the native gamepad, audio/render and all 28 launch scenarios; the real MariaDB deployment/update/rollback test passes.

Signed `LSB-Android-0.5.1.apk` is versionCode 17, 15,907,587 bytes, SHA-256 `fee9e5dce1d8bdb23bebb7840f3034dd9e872970882a77a0528cf6921596602f`, with the original certificate for an install-in-place update. Every non-signature entry matches the passing CI artifact; only the Android DEX and version manifest differ from 0.5.0. See [validation](validation.md) for exact evidence and device limits. The next device action is an in-place 0.5.1 update, followed by existing-server login/audio, controller setup and stop/relaunch checks. No re-import or re-preparation is needed. The historical 0.5.0 device instructions below are superseded by this recovery.

---

# Previous milestone 4 checkpoint: controller, performance and existing-server migration (0.5.0)

The user confirmed 0.4.8 works: login succeeds and audio is clean. Preserve accepted generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, the original nested Ashita xiloader (SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`), runtime and version repair. Client patch.ver is `30251204_1`; current upstream source expects `30260904_1`, so test the existing matching server before any source update.

0.5.0 implements a virtual SDL/DirectInput gamepad bridge, configurable Android button mappings, RGB565 bitmap transfer, optional 960×540, reduced steady-state launcher polling, separate server/database generations and crystal artwork. New device behavior and actual FFXI performance remain unverified. See [implementation, test boundaries and device sequence](milestone-050.md).

The continuation fixes the server Python compiler dependency and the DirectInput test's startup timing. [Full CI run 35660037932](https://github.com/Russianranger/lsb-android/actions/runs/35660037932) passes Android packaging, native Windows checks, ARM64 Wine/Box64 and PRoot input/audio/render/launch regressions, real MariaDB deployment/update/rollback, and the runtime release gate. The controller helper loads natively; Box64's duplicate emulated-preload warning was not the failure. See [diagnosis](ci-recovery-050.md) and [validation/artifact identity](validation.md).

Signed `LSB-Android-0.5.0.apk` is versionCode 16, 15,907,587 bytes, SHA-256 `f949f3d5b4e00ab170511106693412ea13634425e19ea7fbefb475c9ee113a5e`, using the original certificate for an install-in-place update. First Thor action: use the existing Termux server and matching `30251204_1` client/loader, verify login and clean audio at the accepted renderer/resolution, then test controller setup and release behavior. Do not update client, xiloader or server source. Full actual LSB compilation, runtime installation on Android and world entry on the managed server remain device work; CI uses synthetic server executables.

---

# Active milestone 3: restore the missing version registry value (0.4.8)

The new `patch_pol.zip` matches the selected polcore.dll. Its unchanged patch.ver decodes as `30251204_1` with Interface string `"0"`. The exact original callback returns -1 for a missing registry value and 0 for `"0"` in isolated x86 emulation; input bytes are preserved. The app had created InstallFolder/language keys but omitted Interface. See [evidence, implementation and limits](version-repair-048.md).

0.4.8 validates the complete supported version record before restoring only a missing selected-region, 32-bit `Interface\\0001` REG_SZ value. Existing values/types are preserved; readback failure removes the new value. `version_config` in the native launch receipt reports the repair. Client files, preparation, runtime and loader remain unchanged. This is a targeted fix for the reproduced version-reader failure; whole-game startup remains unverified. Implementation `0f6c31bdac679a04c59aa6e05440d77a3dc9db22` passes [all three CI gates](https://github.com/Russianranger/lsb-android/actions/runs/35639901043): 116 JVM checks, 34 Python contracts, 36 native Windows checks, the version-registry fixture and 28 launch cases in each of Wine/Box64 and PRoot. The first-repair and subsequent-preservation cases pass in both environments. Signed `LSB-Android-0.4.8.apk` is 7,751,854 bytes, SHA-256 `facac9d63ed16c0f7e68559331350d43a25ad42d7798b2c95c698a22b58ee609`, using the original certificate. See [validation](validation.md) for its identity and verification limits.

Next device action: install in place, keep the accepted preparation and current settings, start Termux server, launch once with startup capture enabled, and export Diagnostics if it stops. No re-import or re-preparation. Do not replace version files or suppress callback failures. If a registry value already exists, preserve it and use the receipt to guide the next investigation.

---

# Previous milestone 3 step: inspect version-data processing (0.4.7 device result)

The new `lsb-support(6).zip` captures successful root FTABLE/VTABLE opens and first reads, plus a successful complete 288-byte patch.ver read. The working directory matches FFXiMain's parent. All ten API hooks are installed; there are no dropped records. GameMain returns `0x88770000` after 1,227 ms, before any Windows-version, DirectPlay, window or D3D-creation event. Outer GameStart still returns S_OK. See [the device evidence, remaining branches and exact next inputs](version-startup-047.md).

The matching DLL puts a PlayOnline common-function-table callback (offset `0x124c`, index 1171) immediately after its version-file read. A negative callback result or file-manager completion failure can produce this exit. Successful ReadFile alone does not distinguish them. Next obtain `FINAL FANTASY XI/patch.ver` and the selected `PlayOnlineViewer/viewer/com/polcore.dll` from the same import/backup, verify the recorded DLL hash, and inspect that implementation locally. Do not use the patch-cache DLL. These bytes are absent from both the support ZIP and the earlier two-DLL upload.

No code, APK or runtime change is made for this analysis; 0.4.7 remains installed. Do not ask for another identical launch, re-import or preparation. Preserve the accepted generation and original files. Do not fabricate version data, bypass validation or change graphics/dependencies without a concrete failing operation. Startup remains unresolved.

---

# Previous milestone 3 step: pinpoint the pre-window operation (0.4.7)

The supplied `ffxi_lsb.zip` matches both recorded DLL hashes. Offline inspection confirms that `0x88770000` is a shared early-startup failure result, not a specific Direct3D/Windows error. GameMain checks its file manager (including FTABLE.DAT, VTABLE.DAT and patch.ver), Windows version, DirectPlay and window creation before its main loop. The pinned runtime contains dpnhpast.dll. The old unknown-version label does not establish that patch.ver is missing. See [static findings, RVAs and trace interpretation](startup-files-047.md).

0.4.7 retains the prepared client and adds fixed, numeric observation of the actual FFXiMain file/platform/window API results, plus a metadata-only inventory of three startup files. No proprietary DLL is bundled or committed. No game instruction, data file, registry setting or renderer is changed. The existing opt-in temporary-loader mode is retained. Reporting includes the earlier inner-HRESULT correction.

Implementation `8ef8234edb9f1c64eabfd9b1bef4a5a2cf53535e` passes [all gates](https://github.com/Russianranger/lsb-android/actions/runs/35634762962): 116 JVM checks, 33 Python contracts, 36 native Windows checks and 26 launch cases in each of Wine/Box64 and PRoot. The signed device APK identity is in [validation](validation.md).

Next device action: install in place, leave **Capture FFXI startup result** checked, start the existing Termux server, launch once and export Diagnostics. No re-import, preparation or additional DLL upload is requested. This is targeted diagnosis, not a confirmed startup fix. Use the next receipt to identify a concrete failing operation before repairing client files or changing runtime components. In particular, do not fabricate patch.ver or claim DirectPlay is absent based only on its dynamic load name.

# Active milestone 3: identify the inner GameMain failure (0.4.6 device result)

The newest report `lsb-support (3)(2).zip` records successful FFXI and GameMain COM creation, followed by **GameMain HRESULT `0x88770000` after 1,441 ms**. Outer GameStart returns S_OK after 1,983 ms, and the loader exits 0 without an observed game window. This is the captured failing boundary; its underlying cause is not yet known. See [exact evidence, reporting correction and next input](game-main-failure-046.md).

Source reporting now surfaces the inner failure. Thirty-one Python contracts and replay of the actual device receipt pass. No new APK is delivered for this reporting-only correction; the existing 0.4.6 already captures the needed boundary. Do not ask for another identical launch. Next obtain `FINAL FANTASY XI/FFXiMain.dll` and `FFXi.dll` from the same import/backup, check the hashes documented above, and statically inspect the return path. The support ZIP contains hashes, not these binaries. Preserve the accepted generation, original loader, imported files and runtime. Neither a renderer change nor a dependency sweep is justified yet.

---

# Active milestone 3: observe the actual GameStart result (0.4.6)

The new `lsb-support (2)(2).zip`, SHA-256 `f8b8c13088bd2ea888195f2fe27c32e4e6e19d4f48399a2b808ad264051378f2`, records 0.4.5 session `a0898d38-3a79-4290-9d76-8c1dbd237c8f`. Loader PID 284 exits 0 after 15,688 ms, with all five expected modules and no sampled game window/dialog. Windowed settings and all 20 dependency loads pass. Missing-DLL 0xc0000135/RPC 1722 belong to PID 292. Loader 0x80000026 is first-chance; no fatal game exception or DXVK initialization message was captured. Do not treat these warnings as the proven cause or prescribe another dependency/renderer change.

0.4.6 adds **Capture FFXI startup result** (initially checked; unchecking restores direct original-loader execution). It runs an exclusive temporary same-directory copy with an added diagnostic DLL import, observes the known IFFXiEntry GameStart and optional IGameMain boundary, and retains fixed stage/PID/HRESULT/duration metadata. Imported code, loader file, accepted generation, registry registration and runtime are not replaced. The copy has a different filename, image layout and checksum, so this is an explicitly identified diagnostic execution mode. It is removed after normal/failure/Stop paths; force-kill can leave an unselected small copy. See [design, evidence and limits](game-start-trace-046.md).

The next device test is an in-place 0.4.6 install, same settings/server/preparation, trace checked, one launch and fresh Diagnostics. The actual game remains unresolved. Correlate `startup` records to the receipt child PID: loader hook count, FFXI COM result, GameStart enter/return (unsigned HRESULT and elapsed milliseconds), and optional nested GameMain result. A missing return with abnormal process exit differs from an ordinary returned HRESULT. A zero return without a game window is not verified startup; zero hook count means that boundary was not instrumented. Preserve source loader SHA `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8` and generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`.

Implementation `3003880bd3f2cbb3be4f608274a8e86b4dce1b38` passes every gate in [run 35629179765](https://github.com/Russianranger/lsb-android/actions/runs/35629179765): 116 JVM checks, 27 Python contracts, 36 native Windows checks and all 25 launch scenarios separately under ARM64 Wine/Box64 and PRoot. S_OK/S_FALSE/E_FAIL/nested E_OUTOFMEMORY, unhandled exception, Stop in GameStart, trace-off, private virtual-slot preservation, original hashes and copy cleanup pass. See [validation](validation.md) for the corrected test-session race and exact boundaries. Delivered `LSB-Android-0.4.6.apk` is 7,747,758 bytes, SHA-256 `293a337e63c970986baf768198dd31ecb66a4784e8b441e24b81d19770227f85`, using the original signing certificate; observer DLL SHA-256 `afe0e602262413e656c6cdb8566a80f475ea8c2afa74c5c3fdad361025ce8b3b`. Actual GameStart outcome and game display on the Thor still require the next device report.

---

# Active milestone 3: capture the silent startup failure (0.4.5)

The latest `lsb-support (1)(1).zip` (SHA-256 `3bdcbd1030a7201edcd75312736bc7432df8abf1a4fec72850916e717f3fea95`) records 0.4.4 session `aa460280-6a87-4994-a0cb-aeac44e1b07c`. The five windowed values were applied and read back successfully; original settings were saved. The loader nevertheless closed with child/bridge exit 0 after 15,111 ms, with POL/FFXI/FFXiMain/D3D8/D3D9 observed and no game window or standard dialog observed in 35 samples. This rejects windowed mode as a sufficient fix. Do not repeat a settings-only update or infer a proven exception from the user's word “crashing.”

0.4.5 fixes a diagnostic blind spot: the launch environment previously set `WINEDEBUG=-all` and `DXVK_LOG_LEVEL=none`. It now captures bounded, filtered Wine DLL/COM/exception/error metadata and DXVK initialization/error categories through the existing private pipe. DXVK file output is explicitly disabled. Numeric Wine process/thread IDs can be correlated with the native receipt's child PID; unhandled process failure still depends on the real exit, not a first-chance exception or a recoverable warning. Unknown text, paths, GUIDs, register/stack contents and credentials are discarded. This is a diagnostic update, not a demonstrated fix for the proprietary GameStart return.

Keep prepared generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, imported xiloader SHA-256 `78fe8ab1dee5aaac3f866001b706d19d233996e584cf78f3a47b26a0d62cdaf8`, the source import, saved display originals and Wine/Box64/Turnip/DXVK runtime. No prerequisite installation, new preparation, GameHub transfer or renderer change is supported by this report. Next device action: install 0.4.5 in place, launch once with the existing settings/server, then export fresh Diagnostics. The next report must establish whether a captured error/exception explains the early return. If it is still silent, the imported loader does not expose GameStart's return/HRESULT; direct source-level loader instrumentation may be required. Never claim DLL loading or Vulkan probe frames establish game rendering.

Implementation `6563b1e351ef2a9debd32e2eb1684005a6dbd42f` passes all gates in [run 35624389711](https://github.com/Russianranger/lsb-android/actions/runs/35624389711): 116 JVM checks, 21 Python contracts, 36 native Windows checks and all 18 launch scenarios separately under ARM64 Wine/Box64 and PRoot. The real missing-DLL and unhandled divide-by-zero cases retain safe metadata and the correct child PID; recoverable warnings do not terminate a healthy fixture. Immediate Stop retains its diagnostic snapshot. Delivered `LSB-Android-0.4.5.apk` is 7,723,027 bytes, SHA-256 `7893b550d952e70ee7e975b5cd59319a6661bebe1ae51a6266c55c4759844413`, using the original signing certificate. See [startup diagnostics](startup-diagnostics-045.md) and [validation](validation.md) for exact evidence and limits. Actual game startup remains unresolved pending the fresh device report.

---

# Active milestone 3: explicit windowed display profile (0.4.4)

The new `lsb-support(5).zip` device report (SHA-256 `871b057ff6b647abbea1f31d45857bb226d9321d0126cebf869e564d4e6f79e3`) records 0.4.3 session `be51e556-b2a6-45a9-959c-4353bb3f8113`. Login succeeds; POL, FFXI, FFXiMain, D3D8 and D3D9 are observed, but no game window/dialog is observed before exit 0. All five display values already existed: 640×480 overlay, 512×512 background, fullscreen mode 0. The 0.4.3 missing-only policy made no changes. Do not repeat that test or claim it exercised 1280×720 windowed mode.

0.4.4 adds an explicit Client display selector, defaulting to Windowed 1280×720. It saves original display values in the same prepared prefix before overriding the five known DWORDs, with Keep current and Restore saved original choices. This is a targeted candidate fix for the observed fullscreen configuration, not proof that fullscreen caused the exit. Retain generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, source import, xiloader, prefix and runtime. Implementation `b40c7e6885ca2cb34a0045793f722a2495516842` passes all gates in [run 35618686567](https://github.com/Russianranger/lsb-android/actions/runs/35618686567), including 16 launch cases separately under ARM64 Wine/Box64 and PRoot and the regional registry recovery fixtures. Delivered `LSB-Android-0.4.4.apk` has SHA-256 `f6b03de94325cf438b7137a7e5d24541e20a19e7b13dda8be0bd77e9eb837113` and the original signing certificate. Next device test: install in place, select Windowed 1280×720, start the existing Termux server and launch; send new Diagnostics if no game appears. See [validation.md](validation.md) for evidence, exact build identity and limits.

---

# Active milestone 3: post-login exit investigation (0.4.3)

The 0.4.2 device report `lsb-support (3)(1).zip` (SHA-256 `9011837bb7be404ce1e60e4bfbe927034c82d6c57a1d135c957d7ba93345e364`) records session `56ca8e82-a27b-4107-8971-c9b9989672f7`: `login_message_seen`, `server_connected`, native child exit 0 and bridge exit 0 after about 35.5 seconds. The user confirms successful login followed by exit. This establishes authentication progress, not world entry; no captured exception or initialization failure establishes the cause. Preserve generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, imported loader and current runtime.

0.4.3 fills missing FFXI display settings, records safe post-login observations, and stops describing an unobserved game startup as a normal close. Implementation `483c69087d0e23570fc55827e60564a08b9162ae` passes all gates in [run 35591567783](https://github.com/Russianranger/lsb-android/actions/runs/35591567783), including all 14 launch cases in ARM64 Wine/Box64 and PRoot and the US/EU/JP display-settings fixture. Delivered `LSB-Android-0.4.3.apk` uses the original certificate and has SHA-256 `b7a96fc46560b710412dc484470551ed62af618f04650ccc1a2bfde62cabb2ea`. See the current section of [validation.md](validation.md) for source evidence, exact build identity and limits. Do not assert the actual game is fixed until the device retry. No re-import, preparation copy, runtime replacement or GameHub test is needed.

---

# Active milestone 3: login rejection feedback (0.4.2)

The 0.4.1 device test accepts the dependency-checker fix: all 20 DLLs loaded, exit 0, and xiloader started. The new blocker is a `login_rejected` event while the loader stayed alive and the screen remained black. The old log does not distinguish wrong credentials, account state or version rejection. 0.4.2 adds specific safe event classification, live failure handling/automatic shutdown, accurate waiting-for-login status and an Android error dialog returning to the Client form. Implementation `90f812a836b0da8a185440b6c25a2eada1cdf762` passes all gates in [run 35551248809](https://github.com/Russianranger/lsb-android/actions/runs/35551248809), including all five new rejection/automatic-stop cases under ARM64 Wine/Box64 and PRoot. Delivered `LSB-Android-0.4.2.apk` has SHA-256 `da5e0e65eb3d639a5a10a9899fab6a04d8f89644705dd892f9b949972fce57ae` and the original certificate. Read [the current validation section](validation.md) before requesting another test. Preserve current generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`, source import, loader hash and Turnip 26. No blanket graphics or prerequisite changes are supported by this report.

---

# Active milestone 3: fix the 0.4.0 pre-launch exit

The 2026-09-21 Thor support report confirms both launch attempts stopped in the dependency checker, after all 20 import DLL loads passed, with process signal 11. xiloader never started. 0.4.1 changes only the checker lifetime, receipt validation and version labels; the accepted client/prefix, pinned runtime and login flow remain in use. See [diagnosis and validation](validation.md). Implementation `07f6923a56d02c582d179f63ce1fdf8d00229a16` passes all three gates in [run 35547759081](https://github.com/Russianranger/lsb-android/actions/runs/35547759081), including the exact 20-import list twice and unload/exit/missing/initialization-crash regressions under ARM64 Wine/Box64 and PRoot. The original-certificate 0.4.1 APK has SHA-256 `8aa64a92e8a94cb3c40325d5e21e6395e72e6a45d778e1ee8b479e3effd91236` (7,710,656 bytes). Device retry will use the same preparation after installing the update. Do not prescribe a full import/preparation or Visual C++ install based on this report. Precise Wine/Box64 crash attribution remains unproven without a stack.

---

# Active milestone 3: login and world entry 0.4.0 (2026-09-20)

The user authorized this step after proprietary registration/COM passed. The implementation reuses the activated client/prefix, adds Android server/account/password fields, checks the exact imported loader's CLI markers and normal-import DLL loading, and starts it through a native x86 worker with pipe-only credential transport. Launch output persists fixed event names rather than arbitrary credential-bearing child text. The worker records full Windows process failure/exit codes. Prerequisite repair clones the active working client and prefix, retaining current user files and the accepted generation until all checks pass.

Implementation `54f50948af52b3fd286d316d7cba3a73cea74bce` passes all three gates in [run 35537087441](https://github.com/Russianranger/lsb-android/actions/runs/35537087441). Existing 90 core checks, 18 preparation/recovery checks, eight credential-transport checks and ten Python contracts pass. Launch/relaunch, literal credential arguments and working directory, full Windows failure status, explicit Stop, missing-dependency rejection and check-only behavior all pass under ARM64 Wine/Box64 and PRoot. Logs/session reports exclude the deliberately echoed test credentials. The first ARM64 attempt caught a synthetic-fixture encoding mismatch; the corrected fixture passes the same inspection and real launch path. The new fixtures use synthetic loaders, not user credentials or a game server. Launcher repair requires its loader-check receipt before activation, and login discards the PRoot wrapper's raw output as well as unfiltered child output. The original-certificate device APK identity and precise limits are recorded in [validation.md](validation.md).

**Historical 0.4.0 device action (superseded by 0.4.1 above):** install 0.4.0 in place, start the existing Termux server, enter the server account under **Client → Play FINAL FANTASY XI**, and choose **Launch FFXI**. Keep `127.0.0.1`, Turnip 26, and the activated preparation. No further import, preparation copy, open probe or GameHub test is requested. After the attempt, stop/exit and export diagnostics; report the last visible stage and any character/world entry. [Detailed procedure and limits](client-launch-040.md).

**Acceptance still pending:** exact imported-loader parser and dependency compatibility at runtime, server authentication, character/world entry, actual game rendering/audio/input and stability. Static CLI-marker inspection is not a complete parser proof; loadable DLLs are not proof that all exports/behavior satisfy the loader. Process alive/exit 0 never marks login or world entry verified. Existing accounts with ASCII input are supported; account creation, OTP, credential persistence, full controller mapping and server integration remain later work.

---

# Milestone 2 complete: client registration and COM initialization 0.3.0 (2026-09-20)

The user authorized the next step after the Thor completed milestone 1. The 0.3.0 implementation adds a separate full working client and cloned prefix, real registration and client COM checks, per-step reports, prerequisite EXE execution, retry without full recopy, and atomic activation/rollback of matching client/prefix generations. See [client-initialization-030.md](client-initialization-030.md) for device procedure, boundaries and provenance. The selected import survives and remains the source of truth; no GameHub test, fresh prefix or import is requested.

Implementation checkpoint `d0f5acb62727cb82fbe3eb406e3f1c800a78a367` passed all three verification gates in [run 35532787286](https://github.com/Russianranger/lsb-android/actions/runs/35532787286), including synthetic US/EU/JP initialization and failure/prerequisite recovery under ARM64 Wine and PRoot. Local checks pass: existing 90 import/recovery checks, 15 preparation/cancellation/rollback checks and six backend contracts. Following Android-only changes remove the old one-minute display wait during large preparation copies and record a fresh diagnostic state for early copy failures; these compile in the delivered signed APK, whose native/backend bytes are identical to the CI checkpoint. Final APK identity and precise verification boundaries are recorded in [validation.md](validation.md).

**Device acceptance:** `lsb-support (2).zip` confirms all six initialization steps passed with the user's actual US installation on the Thor. The separate working client and matching prefix were activated as generation `768f3a6d-8dfb-462f-8b9d-46cdd7101505`; session `93e77044-aa73-457c-934e-501daa5f0812` completed normally. Registration, actual in-process POL/FFXI COM construction and the first preparation/activation are now device-verified. See [validation.md](validation.md) for exact evidence and remaining limits.

**Next implementation milestone:** login and world entry using the activated preparation. Add Android server/account fields and launch the existing imported xiloader with argument-array execution, its correct working directory and credentials excluded from logs, support exports and ordinary preferences. Verify the exact imported loader's CLI behavior and its Visual C++ runtime dependencies before login; its PE imports include `MSVCP140.dll`, `VCRUNTIME140.dll` and UCRT API DLLs, but it has not been executed. The saved server remains `127.0.0.1` for the existing Termux server. Preserve the imported loader, activated client/prefix, source import, backup and Turnip 26. No repeated preparation, open-probe test, re-import, GameHub transfer or blanket dependency install is needed.

**Carry forward:** Wine reports RpcSs startup errors and Box64 cannot load `libXcomposite.so.1`; these did not block the completed registration/COM operations. Keep them visible during launch testing rather than treating the run as warning-free or proof of full Wine service compatibility. The native D3D8 DLL loaded during FFXiMain registration; actual FFXI rendering, login, audio and controls are not yet exercised. Device prerequisite execution and failure/rollback paths remain untested because this preparation passed without a prerequisite installer.

---

# Milestone 1 complete: in-app runtime probe 0.2.0 (2026-09-20)

The user confirms **both imported FFXI data and a backup survive**, and authorized the first runtime milestone. Do not ask for them again or request a GameHub test. The current work implements a fresh, isolated runtime with an embedded display/audio/input path and an open x86 registry/COM/D3D8 probe. See [runtime-milestone-020.md](runtime-milestone-020.md) for setup, candidate choice, ownership boundaries and test interpretation.

The first candidate is explicitly Wine 10 WoW64 / Box64 0.4.4, reused from the source-tracked TRASC runtime. This is not a claim that it recreates the captured Proton/FEX environment. FFXI registration, prerequisites and xiloader launch are not part of this probe build. The existing app import and backup are retained and not mounted by the runtime.

Implementation is on the existing `codex/client-baseline` branch / draft PR #1. Implementation commit `e07f29c32a99cec2d26a5f39e3866ccc9369a24b` passed the APK, native Windows and ARM64 runtime gates in [run 35530166981](https://github.com/Russianranger/lsb-android/actions/runs/35530166981). The original-certificate 0.2.0 APK and exact evidence/limitations are recorded in [validation.md](validation.md). The pinned runtime release is published. Thor hardware, registry/COM, input, audible output, explicit Stop, normal probe exit and relaunch are now confirmed. Milestone 1 is complete; proceed to transactional client initialization. Preserve the existing LSB application ID and signing key. The runtime release mirrors only pinned open components/source archives after verification; it is separate from user client data.


## Initial Thor device results (2026-09-20)

Reviewed the supplied `lsb-support(3).zip`, screenshot `Screenshot_20260920-140524.png`, and the user's report that the triangle and sound worked. The retained session is 0.2.0 on Android 13 / AYN Thor, from 19:01:07 to 19:05:57 UTC.

- **Confirmed:** actual Turnip Adreno 740, Mesa 26.0.0, hardware verification true, DXVK 2.5.3 in D3D8 compatibility mode; 2,000 presented frames in the final retained probe sample. The screenshot independently shows the colored triangle.
- **Confirmed:** 32-bit registry round-trip and native test-DLL COM activation; `hresult=0`.
- **Confirmed:** 9 key-down events and 8 pointer/button events reached the Windows probe. This proves key delivery, not complete text entry or gameplay/controller coverage.
- **Confirmed:** four PCM streams with nonzero audio samples and the user's audible-tone report. Active tone portions have zero reported underruns; some short silent tail streams report one. Do not generalize this short test to sustained game audio stability.
- **Confirmed:** requested Stop ended the supervisor with exit 0, `phase=stopped`, `alive=false`; display and audio closed. This initial bundle was not a completed-probe receipt: `probe_exit` and `automatic_checks_passed` were absent. The subsequent lifecycle report below closes that gap.
- **Confirmed:** `game_files_mounted=false`; current inventory still selects the installed `PlayOnlineViewer/viewer/com/polcore.dll`, with 66,322 imported files and a 32-bit xiloader. Inventory does not establish proprietary runtime compatibility.

The probe intentionally uses a 40 ms draw timer (about 25 FPS). The screenshot's 25 FPS is not a measured FFXI performance ceiling. Its overlapping counter text comes from transparent DrawText on a non-erased invalidated area in `windows/runtime-probe.c`; clear the header rectangle before drawing in the next code build. This cosmetic issue does not invalidate the recorded input counters.

Wine first-prefix setup logged OLE/RpcSs, driver setup and hostname warnings, but subsequently completed and the test COM activation succeeded. No fatal crash or failed HRESULT is shown in the retained probe. The display broken-pipe message coincides with shutdown, not an observed rendering failure. Keep these observations scoped to the open probe; no proprietary registration has been tested.

## Lifecycle acceptance: milestone 1 closed (2026-09-20)

The user reports “exit and restart worked” and supplied `lsb-support (1).zip`. Both the previous and current runtime state records independently report `phase=completed`, `probe_exit=0`, `automatic_checks_passed=true`, verified hardware rendering, passing 32-bit registry/COM checks and HRESULT 0:

| Session (UTC) | D3D8 frames | Result |
| --- | ---: | --- |
| 19:12:35–19:14:37 | 116 | Normal probe exit, checks passed |
| 19:14:52–19:15:28 | 106 | Relaunch and normal probe exit, checks passed |

The final Android state is `alive=false`, `starting=false`; the display and audio close after the probe completes. Both runs use the same Turnip 26 / Adreno 740 candidate. Game files remain unmounted. Input counts in these short lifecycle-only runs are zero; the earlier 9 key and 8 pointer/button events already establish input delivery and need not be retested.

The second run logs `err:ole:start_rpcss Failed to start RpcSs service` despite passing the in-process test COM activation. Preserve this observation for real POL/FFXI initialization; the synthetic class does not test out-of-process COM or RpcSs behavior. Neither run reports a failed probe HRESULT or abnormal probe exit.

**Accepted milestone:** independent Windows runtime, fresh-prefix execution, real hardware D3D8, registry, in-process COM, audible sound, input, explicit Stop and normal exit/relaunch. No more open-probe repetitions are required. Foreground/background resume, sustained gameplay stability and full controller/text input remain later device acceptance work, not completed claims.

**Next implementation milestone:** transactional POL/FFXI initialization against the surviving managed import, preserving its original payload and the tested runtime. Revalidate the selected installed US POL core, create a separate working installation/staged prefix, record registration/prerequisite results and actual proprietary COM activation, and preserve rollback on failure. No FFXI launch compatibility is established yet. Retain the existing prefix and renderer; do not request a reinstall, fresh prefix, re-import or GameHub test.

---

# Historical scope/audit before 0.2.0: fresh in-app FFXI client (2026-09-20)

The following is the pre-implementation audit. Current progress and surviving-data confirmation above supersede its pending-state statements.

The user reports that file transfer into GameHub Lite corrupted the container. There is no working external client for testing. The LandSandBoat server remains available in Termux. Continue toward a client that starts inside LSB Android and connects to that existing server. Server integration/compiler work remains later in the original project scope.

This handoff records the changed premise and source audit. It does not introduce a runtime, new APK or claim successful FFXI execution. Released 0.1.3 remains the import/recovery/export baseline at `ce17df84be6dafebc756d5ea8a01fcb20ad56a3e`; draft PR #1 contains that work. No new GameHub transfer, CMD or EXE test is requested. The cause of the reported corruption is unknown.

## Recoverable state and missing evidence

- LSB Android imports live in its own storage, selected by `MainActivity.storage()`, under `lsb/session/current/client`; they are separate from GameHub's prefix. A corrupted GameHub container does not establish that these files were lost. Check the existing app before requesting a new full import. Do not uninstall or clear app data.
- The latest supplied 0.1.2 support report previously confirmed an imported US client, the installed `PlayOnlineViewer/viewer/com/polcore.dll`, `PlayOnlineViewer/pol.exe`, and `FINAL FANTASY XI/ashitav4/bootloader/xiloader.exe`, targeting `127.0.0.1`. It recorded successful import and backup/restore operations. This is historical inventory, not a new check of surviving device files or proof of launch compatibility.
- An existing LSB session backup or complete client ZIP can supply game data without an existing Windows registry. Whether those bytes still survive has not been confirmed after the corruption. Diagnostic ZIPs contain inventory and logs, not the game payload. There are no proprietary game files in this repository or development workspace.
- The recovered successful setup ran official installers before replacing updated payloads. Support both rebuilding registration from an imported installation and running user-supplied official installers inside the fresh prefix if registration alone is insufficient. Never label a registry recipe as equivalent to the complete installer state without testing.
- xiloader 2.0 autologin used server/username/password arguments because interactive console entry was unreliable. The app needs native credential fields. Keep the existing loader version identifiable; do not silently upgrade it along with the app or claim the current upstream parser verifies the imported executable.

## Runtime reuse audit

Reference repository: [Russianranger/trasc-server-android at 95475c747be1fbac1ac3f54197e962ce5ffc1f42](https://github.com/Russianranger/trasc-server-android/tree/95475c747be1fbac1ac3f54197e962ce5ffc1f42). It was inspected read-only; no changes to the working EQ app are needed for this project.

| Area | Reusable foundation | FFXI-specific work |
| --- | --- | --- |
| Install and process supervision | `ClientRuntime.java`, `RuntimeManager.java`, `TarExtractor.java`, packaged PRoot, staged rootfs installation, process shutdown | Remove server-manager coupling; give LSB its own rootfs, prefix, sockets, caches and journal; enforce stopped-runtime updates |
| Display and input | `ClientActivity.java`, `RfbConnection.java`, `DisplayInput.java`, native surface and keyboard/controller code | Separate EQ game behavior and controller defaults; verify Wine desktop, dialogs and FFXI input |
| Audio | Android audio bridge and `backend/client_audio.py` | Verify PlayOnline and FFXI output in the selected runtime |
| Graphics | Source-built glibc/KGSL Turnip 24.3.4 and 26.0.0, hardware/presentation probe, DXVK 2.5.3 | Existing bundle only installs x86 `d3d9.dll`. Add and verify x86 `d3d8.dll` plus `d3d9.dll` for the D3D8 route; detect existing wrappers before applying overrides |
| Windows compatibility | Reproducible Wine 10.0 amd64 WoW64 + Box64 0.4.4 build | This is not the captured Proton 10 arm64x + FEX environment. Do not copy it and claim FFXI compatibility. Evaluate a source-buildable matching Wine/FEX combination first; treat any alternate stack as a distinct candidate |
| Diagnostics/recovery | Bounded logs, runtime probes, process exit records, stopped session snapshots | Add prefix-generation IDs, exact component/loader hashes, 32-bit registry and COM probe results, redaction and FFXI bootstrap stage reporting |

The existing TRASC Wine/Box64 package is not a drop-in host for an ARM64EC FEX DLL. Wine architecture, translation backend, host libraries and graphics bridges must be packaged and qualified together. The screenshots' component names and version labels do not provide those binaries or establish individual necessity. Copying every opaque GameHub component is not a reproducible installation recipe.

[DXVK 2.5.3's installation table](https://github.com/doitsujin/dxvk/blob/v2.5.3/README.md) explicitly requires both `d3d8.dll` and `d3d9.dll` for D3D8. TRASC's `scripts/build-vulkan.sh` and `backend/client_vulkan.py` package only D3D9. Do not equate a passing EQ/D3D9 test with a verified FFXI rendering path. Check the actual imported client's wrapper/DLL load chain during the proprietary test.

## Implementation sequence and acceptance

1. **Independent runtime proof.** Package the selected, pinned Wine/translation stack and in-app display, input and audio. Create a fresh app-private prefix from scratch. Run an open 32-bit Windows probe for execution, registry view, test-DLL COM activation, D3D8 rendering through the chosen GPU path, keyboard/mouse and clean exit/relaunch. Record architecture, hashes and actual renderer. This stage can proceed without FFXI files. Do not require a GameHub export or an external app to execute commands.
2. **Transactional client initialization.** Revalidate any surviving managed client or import its backup. Preserve the original payload while creating a writable working installation and staged prefix. Apply prerequisites as individually recorded steps. Use the selected installed POL core, correct installation root and region; reject patch-cache selections. Invoke installer/registration APIs from the app supervisor, capture exit/HRESULT details, and test real POL/FFXI COM activation. Offer official installer execution in the same in-app display when needed. A failed step must leave the prior usable generation available.
3. **Login and world entry.** Add Android account/server fields and a launch action that invokes the established xiloader 2.0 with correct working directory and arguments, without shell interpolation or interactive-console dependence. Passwords must not enter normal preferences, launcher exports, support bundles or command logs; optional persistence requires protected storage. Check and redact child-process output too. Start the existing Termux server and test the configured host (historically `127.0.0.1`). TCP reachability alone does not prove authentication, map/UDP connectivity or world readiness.
4. **Recovery and updates.** With the runtime fully stopped, snapshot the prefix, mutable client settings and component manifest; stage installation/repair/update changes; only promote a validated generation. Keep a rollback path and enough-space checks. Separate large client content from runtime binaries and generated prefix state, but preserve a coherent matching snapshot across updates. Expose failures and allow retry without re-importing all game data.
5. **Device acceptance.** Character selection, world entry, zoning, audio, controller/keyboard input, logout/relaunch, app switching and recovery after an interrupted preparation step. Evaluate performance after correct startup and rendering. Only then resume the standalone server runtime/compiler work.

Next engineering checkpoint is the independent in-app runtime probe, not another GameHub transfer. Runtime choice remains to be qualified; there is no source-verified equivalent of the opaque captured GameHub package in this repository yet. Do not promise a playable APK before that checkpoint and the real client test pass.

## Project boundary

The original goal remains one app for client setup/update/play and eventually server management/builds. For this recovery phase, the working Termux server is the connection target. No server migration, database rewrite or external container repair is necessary to develop the client. Fresh initialization must not read or mutate GameHub's files. An app update must retain the existing LSB application ID/signing certificate and imported files. Existing session exports do not contain a Wine prefix; extend backup format/version explicitly when runtime state becomes available.
