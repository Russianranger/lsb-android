# 0.5.45: build the selected server with jemalloc

Install the matching APK over the existing app. Keep the successful Box64
updater settings. This release's changes concern server compilation.

1. Stop the client and managed server, then open **Server**.
2. If offered, run **Install or update server tools**.
3. Under **Source builds & updates**, keep the already selected
   `LandSandBoat/server @ 16281a81de58acfb315b639d9b79aaacd52a64f2`
   snapshot (expected client `30260904_1`). There is no need to fetch it again.
4. Choose **Build selected source with jemalloc**. The default is two workers;
   use one if Android is running short of memory. Keep the app open and the
   device powered during this first compilation.
5. Expect **All four ARM64 server programs built with jemalloc**. Export
   Diagnostics after success or failure; it includes `server/build-report.json`
   and the compiler/dependency output in `server/operation.log`.

The build check does not need an SQL import or activate a new deployment. It
does not start the database, migrate player data, or change the prepared client.
**Build and apply source + database update** remains a separate action.

GitHub source archives omit `navmeshes` and `ximeshes`; their absence does not
prevent compilation. Compilation and `--help` checks alone do not establish a
working world, correct meshes, client compatibility, or successful database
migrations. Those need separate deployment validation.

The host build uses Ubuntu 26.04, GCC 15, the source's Python requirements,
Release mode, no first-party PCH or IPO, and shared jemalloc. Every newly built
server must pass ARM64 ELF and dependency checks plus an explicit jemalloc
`DT_NEEDED` check. The native ARM64 CI gate also checks real allocator symbol
bindings in each executable without `LD_PRELOAD`.
