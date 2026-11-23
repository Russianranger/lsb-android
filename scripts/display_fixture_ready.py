"""Wait for the X11 fixture to finish validating all capture configurations."""
import time


def wait_for_ready(folder, process, log, stall_seconds=45, maximum_seconds=240):
    started = time.monotonic()
    last_progress = started
    completed = 0
    while not (folder / 'ready').exists():
        if process.poll() is not None:
            raise RuntimeError(f'Display fixture stopped; see {log}')
        now = time.monotonic()
        passed = sum(1 for _ in folder.glob('native-*.passed'))
        if passed > completed:
            completed = passed
            last_progress = now
        if now - started >= maximum_seconds:
            raise TimeoutError(f'Display fixture exceeded {maximum_seconds}s ({completed} configurations passed); see {log}')
        if now - last_progress >= stall_seconds:
            raise TimeoutError(f'Display fixture stalled for {stall_seconds}s ({completed} configurations passed); see {log}')
        time.sleep(.05)
