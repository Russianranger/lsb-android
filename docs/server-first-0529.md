# 0.5.29 — start with the matching server

The first target is the user's existing LandSandBoat server paired with the
accepted client and xiloader. Updating from upstream is a later, explicit step.
The server ZIP and logical database backup have not been supplied in this turn;
these controls do not establish that the user's exact server has run in the app.

## Install and import

1. Install the signed 0.5.32 APK over the existing app. Keep the client runtime,
   imported game files and working client settings.
2. Stop the client and the old Termux server. In **Server → Import your working
   server**, choose **Install server runtime and build tools** (or **Update server runtime and build tools** for
   an older installed runtime). This downloads the separate ARM64 Linux/MariaDB
   environment and installs its dependencies; the first setup needs internet.
3. Choose **Import existing server folder ZIP** and select the complete server folder, including
   `CMakeLists.txt`, `src/`, `sql/`, `scripts/`, settings and meshes. Existing
   `xi_connect`, `xi_map`, `xi_search`, `xi_world` binaries can be in the root or
   uniquely under `build/`. Ordinary wrapper folders and sibling notes are allowed.
4. Choose **Import existing database (.sql / .sql.gz)** and select a full logical dump of this
   server's database. A ZIP of the server folder or a raw MariaDB data directory
   is not a live account/character backup. Leave the database name at `xidb`
   unless your existing dump uses another name. Keep the local-zone address
   setting enabled for client and server on the same device.
5. Choose **Deploy matching server + database**. This checks imported binaries
   and their dependencies. If they are incompatible, choose **Build imported
   revision and deploy** to compile that same source revision. This does not
   fetch current upstream code or run a database update. From 0.5.31, the runtime
   includes jemalloc for imported binaries linked against it. Version 0.5.32 also
   supplies the exact BFD 2.45/SFrame 2 libraries used by the imported binaries,
   alongside the current build tools. Update the server
   runtime first when prompted. Any remaining missing library or ABI version is
   named in the error and retained in the server operation log/support ZIP.
6. Confirm the displayed expected client version, then choose **Start managed server**.
   In the Client tab, use server address `127.0.0.1` and the existing account to
   reach character selection and enter the world. Verify existing character
   progress and one zone transition before treating the deployment as accepted.

The **Export Termux packaging helper** action supplies an interactive exporter for the
Linux distro that currently runs the `landsandboat` folder. Run it there with
`python3 export-existing-lsb.py /path/to/landsandboat`; it asks for the database connection and produces both
the server ZIP and `.sql.gz` backup. It does not stop or replace that installation.
Stop gameplay while exporting so the snapshot represents a quiet database.

## Create an account

Stop the client and server, open **Server → Create account**, enter the account
name, password and confirmation, and choose **Create player account**. Start
the server again and use those credentials in the existing client login form.

Names allow 1–16 printable ASCII characters and passwords 1–32, including spaces
and quotes. Spaces are preserved. The account is a normal player, never a GM.
The deployed source and schema must match the supported bcrypt login contract;
unsupported forks fail without inventing a different password format. Duplicate
names are checked with the database's own collation under a table write lock.
An interrupted result may occur after an insert; check whether the account works
before trying again.

Credentials are passed once through process stdin, not shell arguments, intents,
preferences, request files, status or support logs. Password fields clear after
submission and when the activity pauses. Account databases and their backups
necessarily contain password hashes; keep backups private.

## Back up, restore and recover

With the client and server stopped, **Database backup & restore → Export full
database backup** writes a logical `.sql.gz` backup, including tables, views,
routines, triggers and events. Save it outside the app before risky operations.

Import a `.sql` or `.sql.gz`, then choose **Restore imported database**. The
confirmation names the effect: account and character state comes from that dump.
The app stages the restore with a copy of the currently deployed server, verifies
its binaries, imports and closes MariaDB before activating the new pair. It does
not fetch source, build, or run schema migrations, even when a newer source ZIP
has been selected. The old pair remains available through **Restore previous server +
database**. A failed staging/import leaves the active pair selected.

Only the previous pair is the quick rollback target; export separate database
backups for longer history. Rolling back also restores the database at that
pair's saved point, so later gameplay progress does not move between pairs.

## Later: official upstream source

**Source updates · later** defaults new repository settings to
`https://github.com/LandSandBoat/server`, ref `base`, and preserves already saved
repository preferences. Fetch resolves a commit before downloading. Inspect the
expected client version and missing submodules before choosing the explicit
build/update action. It stages source plus a database clone, retains the previous
pair, and does not update the client or xiloader. Do this only after the imported
matching server has been verified.

## Validation scope

Unit tests cover credential framing and disposal, duplicate/error handling,
build output discovery, failed restore/cancellation, atomic export and rollback.
Android tests exercise the tiles, private password fields, restore confirmation,
service cleanup and ZIP imports while checking accepted client settings persist.
CI runs real MariaDB on ARM64 with synthetic server processes to test database
objects, bcrypt accounts and recovery. This is not a substitute for starting the
user's actual LandSandBoat archive and playing on the device.
