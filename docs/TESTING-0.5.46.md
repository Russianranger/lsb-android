# 0.5.46: live server operation output

The 0.5.45 phone source build passed all four ARM64/jemalloc dependency checks.
Install the matching signed 0.5.46 APK over the existing app. There is no need
to repeat that completed compilation just to install this UI update.

1. Open **Diagnostics → Live server log** to view the retained
   output of the most recent server operation.
2. During the next server compilation, the newest output line should appear
   directly beneath the operation message. The phase message remains visible.
3. Open **Diagnostics → Live server log** while the operation runs. New output
   should appear automatically. Reading logs must not cancel or restart work.
4. Scroll upward to read earlier output; updates should preserve your position.
   Scroll back to the bottom to follow new output.
5. Switch tabs or briefly background and reopen the app. The live view should
   resume, and the last output should remain available after completion/failure.
6. **Server → Server logs → View server operation log** should also open during
   an active operation and update while its dialog remains open.

The view shows a bounded tail of server operation logs, so older output may
scroll out of the retained window. Log availability or quiet output is not a
compiler percentage estimate. Export Diagnostics after the next operation if
anything looks incorrect. The source compiler, patches, server/database state,
and client runtime are unchanged by this release.
