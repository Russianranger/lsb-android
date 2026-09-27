"""Exercise PE32 file, case lookup, checksum, wait and partial receipt APIs."""
import json
from pathlib import Path
import subprocess
import tempfile


def test_repair_io(source, check):
    names = ['enumerate', 'metadata', 'case_lookup', 'small_reads', 'sequential', 'sleep', 'event_wait', 'cpu_crc']
    counts = [256, 128, 256, 128, 4, 8, 1024, 64]
    expected_bytes = [0, 0, 0, 524288, 4194304, 0, 0, 4194304]
    row_keys = {'name', 'operations', 'bytes', 'qpc_us', 'tick_ms', 'cpu_user_us', 'cpu_kernel_us',
                'cpu_error', 'error', 'timed_out'}
    with tempfile.TemporaryDirectory(prefix='lsb-repair-io-') as temporary:
        root = Path(temporary); fixture = root / 'repair-io'; fixture.mkdir(); (fixture / 'case').mkdir()
        payload = bytes(range(256)) * 16
        for i in range(32):
            (fixture / f'f{i:02}.bin').write_bytes(payload)
        (fixture / 'large.bin').write_bytes(payload * 256)
        for i in range(256):
            (fixture / 'case' / f'Entry{i:03}.DAT').touch()
        path = root / 'repair-io-result.json'

        def run(binary='repair-io-test.exe', args=()):
            path.unlink(missing_ok=True)
            result = subprocess.run([str(source / binary), *args], cwd=root, capture_output=True, timeout=20)
            check(not result.stdout and not result.stderr, 'benchmark emits no raw paths, file data or process output')
            return result.returncode, json.loads(path.read_text()) if path.exists() else None

        code, report = run()
        check(code == 0 and report['state'] == 'completed' and report['bits'] == 32,
              'real PE32 benchmark completes all fixture phases')
        check(set(report) == {'format', 'bits', 'state', 'qpc_frequency', 'elapsed_ms', 'phases'}
              and len(path.read_bytes()) < 4096 and report['qpc_frequency'] > 0,
              'benchmark receipt contains bounded fixed metadata and a real QPC frequency')
        check([row['name'] for row in report['phases']] == names
              and [row['operations'] for row in report['phases']] == counts
              and [row['bytes'] for row in report['phases']] == expected_bytes,
              'all file operations, bytes and case-insensitive DAT lookups actually execute')
        check(all(set(row) == row_keys and not row['error'] and not row['timed_out']
                  and not row['cpu_error'] for row in report['phases']),
              'records successful checksums, waits and CPU measurements per phase')
        sleep = report['phases'][5]
        check(sleep['tick_ms'] >= 60 and sleep['qpc_us'] >= 60000,
              'sleep probe measures real waits using two Windows clocks')
        # A checksum failure after successful metadata demonstrates content was
        # read rather than merely reporting counts from the fixture definition.
        (fixture / 'f00.bin').write_bytes(b'\0' * 4096)
        code, report = run()
        check(code == 1 and report['state'] == 'failed' and len(report['phases']) == 4
              and report['phases'][-1]['name'] == 'small_reads' and report['phases'][-1]['error'] == 23,
              'corrupt content stops promptly and retains completed phase receipts')
        (fixture / 'f00.bin').write_bytes(payload)
        (fixture / 'f31.bin').unlink()
        code, report = run()
        check(code == 1 and report['state'] == 'failed' and len(report['phases']) == 2
              and report['phases'][-1]['operations'] == 31 and report['phases'][-1]['error'] == 2,
              'missing fixture reports numeric file error with partial operation count')
        code, report = run('repair-io-timeout-test.exe')
        check(code == 1 and report['state'] == 'timed_out' and len(report['phases']) == 1
              and report['phases'][0]['timed_out'] and report['phases'][0]['operations'] == 0,
              'inner deadline produces an explicit bounded partial result')
        code, report = run(args=['unexpected-private-path'])
        check(code == 80 and report is None, 'production argument policy rejects all caller-selected paths')
        (root / 'repair-io-result.new').mkdir()
        code, report = run()
        check(code == 90 and report is None, 'unwritable receipt fails before benchmark operations')
