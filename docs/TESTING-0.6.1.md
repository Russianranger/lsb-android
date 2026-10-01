# LSB Android 0.6.1 improvement pass

Beta 0.6 on Main preserves the phone-tested 0.5.55 production payload and its
original Android signature. The published APK's internal Android version remains
0.5.55. This branch is the next pass, version 0.6.1 / code 72.

## Changes to exercise

- Launch the activated client twice. Repeat launches reuse its initialized
  inventory and hash the selected critical binaries again, without walking the
  ROM tree. Prepare/initialize/update verification still inspect the full tree.
  Advanced → Inspect all activated client files runs the full inspection on
  demand. Changed DLL contents must still block launch even with unchanged size
  and modification time.
- Build → Install compiler cache upgrades an existing build runtime with ccache.
  New installations include it. Rebuild the fetched source with jemalloc, then
  repeat: source builds use independent staging directories and retain only
  validated outputs. The persistent cache is limited to 2 GiB and isolated by
  compiler contents, compiler options, headers, build hook and source patch
  rules. Build reports include before/after ccache counters. Compiler cache
  contents are reproducible and omitted from full session backups.
- Server → Database checkpoints saves dated compressed SQL including accounts,
  characters, world tables, routines, triggers and events. Choose retention of
  2, 3, 5 or 10; pruning occurs after a successful new save. Restore checks the
  compressed checksum, size and matching deployed build/schema, then imports
  into an independent generation. The current deployment becomes the rollback
  target. The imported SQL selection remains unchanged. A checkpoint is local
  app data; use the full working-combination export before uninstalling or
  moving to another server build. Complete backups include checkpoints.
- Long operations save their latest step, last 12 updates and elapsed time
  periodically. An app-process interruption retains this evidence on reopening,
  with an Interrupted outcome. It does not automatically restart a transfer or
  compile. Estimated remaining time is shown only for a measured client copy
  with a known total. Builds retain elapsed time and live logs.

## Delivery

Only the standard `io.github.russianranger.lsb` APK is built for this pass. The
workflow no longer produces or publishes an LSB Android Restore Test APK.

The original private signing key was lost during workspace maintenance. The
existing Beta 0.6 release retains its original signature. An APK built in CI uses
its disposable test signer and cannot update the installed release. Future
installable improvements need the original key or a separately approved,
persistently saved replacement key and a backup/reinstall/restore transition.

## Automated verification

Local core import/session recovery checks, 197 runtime unit tests and 136 server
unit tests pass, and the production Android Java sources compile against API 35.
CI also exercises Android 13 lifecycle/UI/progress/critical-file checks, real
ccache reuse and invalidation, and real MariaDB checkpoint restoration and
retention. Native runtime qualification remains separate from phone play testing.
