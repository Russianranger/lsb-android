# Beta 0.7: update and focused AYN Thor checks

Stop the client and managed server, then install **LSB-Android-Beta-0.7.apk** over the existing standard LSB app. The original signing certificate and package are retained; Android version 0.7.0/code 74 advances Beta 0.6 and the 0.6.1/0.6.2 previews. Do not uninstall or clear app storage. Keep the existing complete backup. No client import, server rebuild, database preparation or redeployment is required for this update.

1. Confirm the existing client, active server deployment, saved quick-login settings and checkpoints remain selected.
2. Start the managed server normally. Acceleration is on by default; wait until all services are Ready, then use quick login to enter the existing character and confirm its inventory and location. Close the client and stop the server normally.
3. Confirm the Server page has no acceleration switch. If troubleshooting is needed, open **More → Advanced settings and repairs → Server runtime settings**. This contains the switch and the last-start mode. It is locked while the server or a maintenance task is active. An explicitly saved OFF choice remains respected.
4. Launch the same client again and check that repeat validation stays quick. Full inspection is available in Advanced; it is not required on each launch.

The earlier 0.6.1 checkpoint, retention, rebuild/cache and backup/recovery instructions remain applicable in [TESTING-0.6.1.md](TESTING-0.6.1.md). Those implementations are unchanged by the Beta 0.7 UI change and do not need another full milestone pass. If testing a checkpoint restore, save a fresh checkpoint first and remember that restore returns player progress to that saved point. Full-session backups and SQL-only checkpoints serve different scopes.

The accepted Thor support log confirms native filtering and map readiness at 95.96 seconds. It contains three accounts and three characters in the existing deployment. This is not an isolated percentage benchmark against a different build/deployment. A missing acceleration confirmation causes compatibility fallback before database access; subsequent server errors do not trigger an automatic restart. Builds, backups and database maintenance continue in compatibility mode. Script caching, deferred zones and other proposed boot optimizations are postponed.

If a problem occurs, export a support ZIP from Diagnostics. The server receipt is `server/proot-acceleration.json`, separate from the client receipt; startup/status logs retain readiness and timing evidence.
