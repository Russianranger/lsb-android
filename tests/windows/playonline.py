"""Real CreateProcess/exit/window checks; no installed client or account needed."""
import ctypes
from ctypes import wintypes
import json
import shutil
import subprocess
import tempfile
import time
from pathlib import Path


def read_shared(path):
    # The observer must allow the writer's atomic replacement. Python's normal
    # Windows file open need not include FILE_SHARE_DELETE and would introduce a
    # sharing violation that the production Linux reader does not cause.
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.CreateFileW.argtypes = (wintypes.LPCWSTR, wintypes.DWORD, wintypes.DWORD,
                                  wintypes.LPVOID, wintypes.DWORD, wintypes.DWORD, wintypes.HANDLE)
    kernel.CreateFileW.restype = wintypes.HANDLE
    kernel.ReadFile.argtypes = (wintypes.HANDLE, wintypes.LPVOID, wintypes.DWORD,
                               ctypes.POINTER(wintypes.DWORD), wintypes.LPVOID)
    kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
    handle = kernel.CreateFileW(str(path), 0x80000000, 7, None, 3, 0, None)
    if handle == ctypes.c_void_p(-1).value:
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        data = ctypes.create_string_buffer(1024)
        size = wintypes.DWORD()
        if not kernel.ReadFile(handle, data, len(data), ctypes.byref(size), None):
            raise ctypes.WinError(ctypes.get_last_error())
        return data.raw[:size.value]
    finally:
        kernel.CloseHandle(handle)


def test_playonline(source, check):
    # GitHub's native Windows runner has a writable D: volume. Keep the same
    # strict D: mount policy in the test binary; only its fixed receipt location
    # changes so tests never write outside their isolated fixture directory.
    with tempfile.TemporaryDirectory(prefix='lsb-pol-', dir='D:\\') as temporary:
        root = Path(temporary)
        viewer = root/'Viewer path ñ with spaces'
        viewer.mkdir()
        executable = viewer/'pol.exe'
        shutil.copy2(source/'playonline-stub.exe', executable)
        receipts = root/'private receipts'
        receipts.mkdir()
        receipt_path = receipts/'playonline-process.json'
        runner = str(source/'playonline-run-test.exe')
        keys = {'format', 'bits', 'phase', 'win32_error', 'child_exit', 'child_pid',
                'visible_window_seen', 'window_error', 'elapsed_ms', 'heartbeat_samples',
                'heartbeat_elapsed_ms', 'cpu_user_ms', 'cpu_kernel_ms', 'cpu_error',
                'window_found', 'window_responsive', 'window_probe_error'}

        def clear():
            receipt_path.unlink(missing_ok=True)

        def read():
            data = read_shared(receipt_path)
            value = json.loads(data)
            check(len(data) < 1024 and set(value) == keys and value['format'] == 2,
                  'bounded fixed-field private PlayOnline receipt')
            check(b'PRIVATE-' not in data and b'Viewer' not in data and b'pol.exe' not in data,
                  'receipt contains no window title, stdout, stderr or executable path')
            return value

        def fixture(code=0, visible=0, delay=0):
            (viewer/'playonline-fixture.txt').write_bytes(f'{code}\n{visible}\n{delay}\n'.encode('ascii'))

        def invoke(*arguments):
            return subprocess.run([runner, *arguments], cwd=receipts, capture_output=True, timeout=15)

        for code in (0, 37, 259, 0xC0000005, 0xFFFFFFFF):
            clear(); fixture(code)
            result = invoke(str(executable))
            value = read()
            check(result.returncode == (0 if code == 0 else 1), 'bridge exit maps child status without DWORD truncation')
            check(value['phase'] == 'exited' and value['child_exit'] == code and value['win32_error'] == 0,
                  f'preserves exact unsigned Windows exit {code}')
            check(value['child_pid'] > 0 and value['visible_window_seen'] is False and value['window_error'] == 0,
                  'records launched child with no visible viewer')
            check(b'PRIVATE-POL-STDOUT-SENTINEL' in result.stdout and b'PRIVATE-POL-STDERR-SENTINEL' in result.stderr,
                  'child uses only inherited private stdout and stderr handles')
        clear(); fixture(0, 1, 1500)
        process = subprocess.Popen([runner, str(executable)], cwd=receipts, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        saw_running = False
        deadline = time.monotonic() + 10
        while process.poll() is None and time.monotonic() < deadline:
            if receipt_path.exists():
                try:
                    saw_running |= json.loads(read_shared(receipt_path))['phase'] == 'running'
                except (PermissionError, FileNotFoundError):
                    pass  # Atomic replacement can briefly hold the Windows file.
            time.sleep(.02)
        process.communicate(timeout=5)
        value = read()
        check(process.returncode == 0 and saw_running and value['phase'] == 'exited',
              'publishes running state before durable final receipt')
        check(value['visible_window_seen'] is True and value['window_error'] == 0 and value['elapsed_ms'] >= 1400,
              'observes visible child window without reading its private title')
        check(value['heartbeat_samples'] == 1 and value['window_found'] and value['window_responsive']
              and value['window_probe_error'] == 0 and value['cpu_error'] == 0
              and value['cpu_user_ms'] + value['cpu_kernel_ms'] > 0,
              'first visible observation reports response and real nonzero child CPU accounting')

        for visible, delay in ((1, 11500), (2, 6500)):
            clear(); fixture(0, visible, delay)
            process = subprocess.Popen([runner, str(executable)], cwd=receipts,
                                       stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            samples = {}; deadline = time.monotonic() + 20
            try:
                while process.poll() is None and time.monotonic() < deadline:
                    try:
                        current = json.loads(read_shared(receipt_path))
                        if current['phase'] == 'running' and current['heartbeat_samples']:
                            samples[current['heartbeat_samples']] = current
                    except (PermissionError, FileNotFoundError):
                        pass
                    time.sleep(.02)
                process.communicate(timeout=5)
            finally:
                if process.poll() is None:
                    process.kill(); process.communicate(timeout=5)
            value = read()
            check(process.returncode == 0 and value['phase'] == 'exited' and len(samples) >= 2,
                  'heartbeat continues after first visible window and still preserves normal exit')
            ordered = [samples[key] for key in sorted(samples)]
            check(all(b['heartbeat_elapsed_ms'] - a['heartbeat_elapsed_ms'] >= 4900
                      for a, b in zip(ordered, ordered[1:])),
                  'ongoing window probes are separated by five seconds')
            # CreateWindow/ShowWindow may handle an early cross-thread message
            # before the fixture enters its deliberately unpumped loop. The
            # subsequent heartbeat must identify that loop as unresponsive.
            responses = ordered if visible == 1 else ordered[1:]
            check(all(row['window_found'] and row['window_responsive'] is (visible == 1)
                      and row['window_probe_error'] == (0 if visible == 1 else 1460) for row in responses),
                  'distinguishes a pumping UI thread from a hung visible window with a bounded WM_NULL probe')
            check(all(b['cpu_user_ms'] >= a['cpu_user_ms'] and b['cpu_kernel_ms'] >= a['cpu_kernel_ms']
                      for a, b in zip(ordered, ordered[1:])) and value['cpu_error'] == 0,
                  'child CPU times are monotonic across samples and collected at exit')
            check(value['elapsed_ms'] < delay + 2000 and value['heartbeat_elapsed_ms'] <= value['elapsed_ms'],
                  'hung UI message probe never blocks process exit tracking')

        clear()
        missing = viewer/'missing viewer'/'pol.exe'
        missing.parent.mkdir()
        result = invoke(str(missing)); value = read()
        check(result.returncode == 1 and value['phase'] == 'create_failed' and value['win32_error'] in (2, 3),
              'missing explicit image preserves numeric CreateProcess error')
        check(value['child_pid'] == 0 and value['child_exit'] == 0 and value['visible_window_seen'] is False,
              'failed creation cannot masquerade as a started viewer')

        invalid = [[], [str(executable), 'unexpected'], [r'C:\viewer\pol.exe'], [r'D:viewer\pol.exe'],
                   ['D:/viewer/pol.exe'], [r'D:\viewer\..\pol.exe'], [r'D:\viewer.\pol.exe'],
                   [r'D:\viewer \pol.exe'], [r'D:\viewer\\pol.exe'], [r'D:\viewer\pol.exe:stream'],
                   ['D:\\viewer\\pol.exe" --account private'], [r'D:\viewer\cmd.exe']]
        for arguments in invalid:
            clear()
            check(invoke(*arguments).returncode == 80 and not receipt_path.exists(),
                  'rejects noncanonical path or extra argv before process creation')

        # The runner must not leave an untracked viewer alive if it cannot write
        # its first durable receipt. A directory blocks creation of the temp file.
        clear(); fixture(0, 0, 30000)
        (receipts/'playonline-process.new').mkdir()
        check(invoke(str(executable)).returncode == 90 and not receipt_path.exists(),
              'receipt publication failure terminates its child promptly')
