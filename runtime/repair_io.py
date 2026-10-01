"""Bounded synthetic I/O comparison. Never reads a client file or account path."""
import json
import os
from pathlib import Path
import resource
import signal
import stat
import threading
import time
import zlib

SESSION = Path('/session')
NAMES = ('enumerate', 'metadata', 'case_lookup', 'small_reads', 'sequential', 'sleep', 'event_wait', 'cpu_crc')
COUNTS = (256, 128, 256, 128, 4, 8, 1024, 64)
BYTES = (0, 0, 0, 524288, 4194304, 0, 0, 4194304)
PAYLOAD = bytes(range(256)) * 256
ROW_KEYS = {'name', 'operations', 'bytes', 'qpc_us', 'tick_ms', 'cpu_user_us', 'cpu_kernel_us',
            'cpu_error', 'error', 'timed_out'}


def validate(value):
    """Only fixed, typed aggregate measurements cross into the support report."""
    keys = {'format', 'bits', 'state', 'qpc_frequency', 'elapsed_ms', 'phases'}
    if (not isinstance(value, dict) or set(value) != keys or type(value['format']) is not int or value['format'] != 1
            or type(value['bits']) is not int or value['bits'] != 32
            or value['state'] not in ('running', 'completed', 'failed', 'timed_out')
            or type(value['qpc_frequency']) is not int or not 0 < value['qpc_frequency'] < 2**63
            or type(value['elapsed_ms']) is not int or not 0 <= value['elapsed_ms'] < 2**53
            or not isinstance(value['phases'], list) or len(value['phases']) > len(NAMES)):
        raise ValueError('Invalid synthetic benchmark receipt')
    for i, row in enumerate(value['phases']):
        if (not isinstance(row, dict) or set(row) != ROW_KEYS or row['name'] != NAMES[i]
                or type(row['timed_out']) is not bool
                or any(type(row[k]) is not int or not 0 <= row[k] < 2**53 for k in ROW_KEYS - {'name', 'timed_out'})
                or row['operations'] > COUNTS[i] or row['bytes'] > BYTES[i]
                or any(row[k] > 0xffffffff for k in ('error', 'cpu_error'))):
            raise ValueError('Invalid synthetic benchmark phase')
        terminal = i == len(value['phases']) - 1 and value['state'] in ('failed', 'timed_out')
        if not terminal and (row['error'] or row['timed_out'] or row['operations'] != COUNTS[i] or row['bytes'] != BYTES[i]):
            raise ValueError('Incomplete successful benchmark phase')
    rows = value['phases']
    if (value['state'] == 'completed' and len(rows) != len(NAMES)
            or value['state'] in ('failed', 'timed_out') and (not rows or not (rows[-1]['error'] or rows[-1]['timed_out']))
            or value['state'] == 'timed_out' and not rows[-1]['timed_out']
            or value['state'] == 'failed' and (not rows[-1]['error'] or rows[-1]['timed_out'])):
        raise ValueError('Inconsistent synthetic benchmark result')
    return value


def receipt():
    try:
        fd = os.open(SESSION / 'repair-io-result.json', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
        with os.fdopen(fd, 'rb') as stream:
            if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):
                return None
            data = stream.read(8193)
        return validate(json.loads(data)) if len(data) <= 8192 else None
    except (OSError, ValueError, TypeError, KeyError):
        return None


def fixtures(root, stopped):
    # Refuse an existing name, including links. The directory is discarded with
    # the session; callers never merge fixture files into a client or prefix.
    root.mkdir(mode=0o700)
    (root / 'case').mkdir(mode=0o700)
    for i in range(32):
        stopped()
        with (root / f'f{i:02}.bin').open('xb') as stream:
            stream.write(PAYLOAD[:4096])
    with (root / 'large.bin').open('xb') as stream:
        for _ in range(16):
            stopped(); stream.write(PAYLOAD)
    for i in range(256):
        stopped()
        with (root / 'case' / f'Entry{i:03}.DAT').open('xb'):
            pass


def native(root, stopped, budget=10):
    began = time.monotonic()
    report = {'state': 'running', 'elapsed_ms': 0, 'phases': []}
    event = threading.Event(); event.set()

    def step():
        stopped()
        if time.monotonic() - began >= budget:
            raise TimeoutError()

    for index, name in enumerate(NAMES):
        row = {'name': name, 'operations': 0, 'bytes': 0, 'elapsed_us': 0,
               'cpu_user_us': 0, 'cpu_kernel_us': 0, 'error': 0, 'timed_out': False}
        report['phases'].append(row)
        start = time.monotonic_ns(); before = resource.getrusage(resource.RUSAGE_SELF)
        try:
            if name == 'enumerate':
                with os.scandir(root / 'case') as entries:
                    for _ in entries:
                        step(); row['operations'] += 1
            elif name in ('metadata', 'case_lookup'):
                # Linux exact-name lookup is the reference. Wine's case lookup
                # uses deliberately mismatched case in the same 256-entry tree.
                for i in range(COUNTS[index]):
                    step()
                    path = root / f'f{i % 32:02}.bin' if name == 'metadata' else root / 'case' / f'Entry{i:03}.DAT'
                    entry = path.stat(follow_symlinks=False)
                    if not stat.S_ISREG(entry.st_mode) or entry.st_size != (4096 if name == 'metadata' else 0):
                        raise OSError(22, 'fixture')
                    row['operations'] += 1
            elif name in ('small_reads', 'sequential'):
                size = 4096 if name == 'small_reads' else 1048576
                for i in range(COUNTS[index]):
                    step(); path = root / f'f{i % 32:02}.bin' if name == 'small_reads' else root / 'large.bin'
                    total = 0; checksum = 0
                    with path.open('rb', buffering=0) as stream:
                        while total < size:
                            step(); block = stream.read(min(65536, size - total))
                            if not block:
                                raise OSError(5, 'fixture')
                            total += len(block); row['bytes'] += len(block); checksum += sum(block)
                    if checksum != size // 256 * 32640:
                        raise OSError(5, 'fixture')
                    row['operations'] += 1
            else:
                for _ in range(COUNTS[index]):
                    step()
                    if name == 'sleep':
                        time.sleep(.01)
                    elif name == 'event_wait':
                        if not event.wait(0):
                            raise OSError(5, 'event')
                    else:
                        if zlib.crc32(PAYLOAD) != 0xb11de6a1:
                            raise OSError(5, 'checksum')
                        row['bytes'] += len(PAYLOAD)
                    row['operations'] += 1
            if row['operations'] != COUNTS[index] or row['bytes'] != BYTES[index]:
                raise OSError(22, 'fixture')
        except TimeoutError:
            row['timed_out'] = True
        except OSError as error:
            row['error'] = max(0, min(0xffffffff, error.errno or 5))
        finally:
            after = resource.getrusage(resource.RUSAGE_SELF)
            row['elapsed_us'] = max(0, (time.monotonic_ns() - start) // 1000)
            row['cpu_user_us'] = max(0, int((after.ru_utime - before.ru_utime) * 1000000))
            row['cpu_kernel_us'] = max(0, int((after.ru_stime - before.ru_stime) * 1000000))
        if row['error'] or row['timed_out']:
            report['state'] = 'timed_out' if row['timed_out'] else 'failed'
            break
    else:
        report['state'] = 'completed'
    report['elapsed_ms'] = max(0, int((time.monotonic() - began) * 1000))
    return report


def run(supervisor, environment):
    """Return diagnostics without blocking repair on a benchmark failure.

    Cancellation still propagates. The 20-second parent budget includes fixture
    creation and native phases, and kills its own helper process group on timeout.
    Reap failures block this launch rather than leaving a benchmark behind.
    """
    began = time.monotonic(); process = None
    result = {'format': 1, 'policy': 'synthetic_session_files_only', 'status': 'unavailable',
              'fixture': {'small_files': 32, 'small_bytes': 4096, 'large_bytes': 1048576, 'case_entries': 256},
              'comparison': 'native_exact_case_reference; win32_mismatched_case; CPU algorithms differ',
              'native': None, 'windows': None, 'helper_wall_ms': 0, 'elapsed_ms': 0, 'exit_code': None}

    def stopped():
        supervisor.stopped()
        if time.monotonic() - began >= 20:
            raise TimeoutError()

    try:
        for name in ('repair-io-result.json', 'repair-io-result.new'):
            (SESSION / name).unlink(missing_ok=True)
        fixtures(SESSION / 'repair-io', stopped)
        result['native'] = native(SESSION / 'repair-io', stopped)
        if result['native']['state'] != 'completed':
            result['status'] = 'native_failed'
            return result
        stopped(); helper_begin = time.monotonic()
        quiet = dict(environment, WINEDEBUG='-all', BOX64_LOG='0', BOX64_NOBANNER='1')
        process = supervisor.spawn(supervisor.wine_command(r'P:\repair-io.exe'), 'repair-io.log', env=quiet)
        try:
            while process.poll() is None:
                stopped(); time.sleep(.05)
            result['exit_code'] = process.returncode
            result['windows'] = receipt()
            result['status'] = ('completed' if process.returncode == 0 and result['windows']
                                and result['windows']['state'] == 'completed' else 'helper_failed')
        finally:
            result['helper_wall_ms'] = max(0, int((time.monotonic() - helper_begin) * 1000))
    except TimeoutError:
        result['status'] = 'timed_out'; result['windows'] = receipt()
    except OSError:
        result['status'] = 'unavailable'
    finally:
        if process is not None and process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass  # It exited between poll and the group signal.
            process.wait(timeout=2)
        result['elapsed_ms'] = max(0, int((time.monotonic() - began) * 1000))
    return result
