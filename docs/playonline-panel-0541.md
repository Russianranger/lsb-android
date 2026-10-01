# PlayOnline startup and blank update panel (0.5.41)

The owner reports that PlayOnline updated, but its central panel is blank and
client File Repair is inaccessible. The screenshot still has the Version Update
footer. Support `lsb-support(20260926-185554).zip` has SHA256
`d606a75658b161755603e6156991873eccd7b01fa86588b4244db61c7bdc4537`.
The latest session is `05c099a9-9711-40f3-8e36-ea0ad12c5ea4` on version 0.5.40.

## What the report establishes

Preparation takes 85.997 seconds, including 71.277 seconds in six separate
component registration/check processes. All checks pass. Each worker repeats
Wine/Box64 startup and explorer/RPC service initialization. The viewer becomes
visible 14.732 seconds after it starts and remains alive for 289.539 seconds
until manual Stop. DNS checks pass. No crash is recorded.

The prior session's original viewer exited cleanly after approximately 109.5
minutes, and another `pol.exe` appeared before Stop. The existing prefix wait
therefore did not prematurely terminate a restarted viewer. Live core/app/
contents hashes remain the same across both attempts. The version-file check
changes from `file_unavailable` to `format_not_matched`; neither state changes
the registration value. The exact version file and staged resource inventory
were absent from this report, so it cannot independently certify a completed
viewer update or explain the blank panel.

Steady-state Android decode/draw costs are below one millisecond. The display
resizes successfully to 640 by 480, then has a long interval with no guest pixel
damage. This is insufficient to distinguish a viewer stall, guest rendering
failure or stale Xvnc damage. It is not evidence of slow Android drawing.

## Changes and limits

- The six existing component operations run in one disposable Wine process.
  Registration and class checks retain their order, exact staged paths,
  per-step results and timings. All checks rerun on every launch; no cached
  success can hide files replaced by PlayOnline. Guarded version registration
  and the load-only dependency checker remain separate.
- The updater display menu offers **Refresh display**. One manual action
  requests a non-incremental framebuffer through the existing reader. Duplicate
  pending requests are suppressed. Bounded metadata records whether complete
  pixel coverage was observed, a timeout, resize, closure or write failure.
  RFB has no request IDs: coverage does not establish which request caused the
  reply, nor prove that the guest rendered the correct controls.
- Fixed file snapshots record size/hash/status before and at the end of the
  attempt, within size/time bounds. They contain no account files or source
  contents. During Stop, children can still be closing; these observations are
  not an update-completion certificate.
- The viewer runner retains prompt first-window observation and adds a bounded
  heartbeat with child CPU time and a timed `WM_NULL` window-response probe.
  It reads no titles, account text or user input. A responsive window still
  does not establish working File Repair.

The batch addresses measured preparation overhead. Phone timing and the blank
panel still require a retry. The accepted gameplay engine/renderer/filtering,
active installation, server and database are unchanged. No unsupported CLI,
registry value or version file is fabricated to skip the official repair UI.

## Phone retry

Install the matching regular/Restore Test APKs in place. In Restore Test, use
**Client → Client update · PlayOnline → Open or resume PlayOnline update**.
The current staged copy is reused. Finish any viewer update and allow its
restart; Check Files belongs to the main menu after that stage. If the panel
stays blank, use **☰ → Refresh display** once, allow up to 15 seconds, then
export support if the controls are still absent. Do not activate a copy until
the official File Repair finishes and staged verification passes.
