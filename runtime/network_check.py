"""Bounded public-name resolution in the guest used by Wine's Winsock.

No patch protocol, account data, resolved IPs, aliases or raw errors are retained.
Resolving a name does not prove that the patch service itself is available.
"""
import ipaddress
import json
from pathlib import Path
import socket
import threading
import time

TARGETS = (('patch_server', 'qc000.pol.com'), ('official_web', 'www.playonline.com'))


def validate(result):
    fields = {'format', 'layer', 'checks', 'any_resolved', 'all_resolved',
              'elapsed_ms', 'patch_connection_verified'}
    if (not isinstance(result, dict) or set(result) != fields or result['format'] != 1 or
            result['layer'] != 'guest_ipv4_resolver' or result['patch_connection_verified'] is not False or
            type(result['elapsed_ms']) is not int or not 0 <= result['elapsed_ms'] <= 60000 or
            not isinstance(result['checks'], list) or len(result['checks']) != len(TARGETS)):
        raise ValueError('Invalid guest network receipt')
    for row, (target, _) in zip(result['checks'], TARGETS):
        if (not isinstance(row, dict) or set(row) != {'target', 'ok', 'address_count', 'error', 'error_code', 'elapsed_ms'} or
                row['target'] != target or type(row['ok']) is not bool or
                row['error'] not in ('none', 'lookup_failed', 'no_ipv4', 'timeout') or
                type(row['address_count']) is not int or not 0 <= row['address_count'] <= 64 or
                type(row['error_code']) is not int or not -65535 <= row['error_code'] <= 65535 or
                type(row['elapsed_ms']) is not int or not 0 <= row['elapsed_ms'] <= 60000 or
                row['ok'] != (row['address_count'] > 0) or row['ok'] != (row['error'] == 'none')):
            raise ValueError('Invalid guest network result')
    if (result['any_resolved'] is not any(row['ok'] for row in result['checks']) or
            result['all_resolved'] is not all(row['ok'] for row in result['checks'])):
        raise ValueError('Inconsistent guest network result')
    return result


def check(resolve=socket.gethostbyname_ex, timeout=8.0):
    started = time.monotonic()
    rows = [None] * len(TARGETS)
    lock = threading.Lock()

    def lookup(index, host):
        begin = time.monotonic()
        row = {'target': TARGETS[index][0], 'ok': False, 'address_count': 0,
               'error': 'lookup_failed', 'error_code': 0}
        try:
            # PlayOnline uses gethostbyname: prove IPv4 lookup, not just AAAA.
            addresses = resolve(host)[2]
            count = len({str(ipaddress.IPv4Address(value)) for value in addresses[:64]})
            row.update(ok=count > 0, address_count=count, error='none' if count else 'no_ipv4')
        except (OSError, ValueError, TypeError, IndexError) as error:
            code = getattr(error, 'errno', 0)
            row['error_code'] = code if type(code) is int and -65535 <= code <= 65535 else 0
        row['elapsed_ms'] = max(0, int((time.monotonic() - begin) * 1000))
        with lock:
            rows[index] = row

    workers = [threading.Thread(target=lookup, args=(i, host), daemon=True)
               for i, (_, host) in enumerate(TARGETS)]
    for worker in workers:
        worker.start()
    deadline = started + timeout
    for worker in workers:
        worker.join(max(0, deadline - time.monotonic()))
    with lock:
        result = [dict(row) if row is not None else
                  {'target': TARGETS[i][0], 'ok': False, 'address_count': 0,
                   'error': 'timeout', 'error_code': 0,
                   'elapsed_ms': max(0, int((time.monotonic() - started) * 1000))}
                  for i, row in enumerate(rows)]
    return {'format': 1, 'layer': 'guest_ipv4_resolver', 'checks': result,
            'any_resolved': any(row['ok'] for row in result),
            'all_resolved': all(row['ok'] for row in result),
            'elapsed_ms': max(0, int((time.monotonic() - started) * 1000)),
            'patch_connection_verified': False}


def main():
    result = check()
    path = Path('/session/network-check.json')
    temporary = path.with_suffix('.new')
    temporary.write_text(json.dumps(result, indent=2) + '\n')
    temporary.replace(path)


if __name__ == '__main__':
    main()
