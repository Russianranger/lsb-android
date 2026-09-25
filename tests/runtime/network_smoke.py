"""Deterministic guest resolver regression, with no external network or accounts.

The container runs with --network none. A tiny DNS fixture listens at a
nondefault loopback address, so an empty resolv.conf cannot accidentally pass.
All published output consists of fixed fixture labels, booleans and counters.
"""
import json
from pathlib import Path
import socket
import struct
import subprocess
import sys
import threading
import time

TARGETS = {'qc000.pol.com': bytes([192, 0, 2, 40]),
           'www.playonline.com': bytes([192, 0, 2, 41])}
SERVER = '127.0.0.2'


class DnsFixture:
    def __init__(self):
        self.stop = threading.Event()
        self.queries = {hostname: 0 for hostname in TARGETS}
        self.rejected = 0
        self.socket = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.socket.bind((SERVER, 53))
        self.socket.settimeout(.2)
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    def reply(self, request):
        if len(request) < 17 or len(request) > 512:
            raise ValueError('Invalid fixture DNS packet size')
        identifier, flags, count, answer, authority, additional = struct.unpack('>6H', request[:12])
        if flags & 0x8000 or count != 1 or answer or authority:
            raise ValueError('Unsupported fixture DNS packet')
        cursor = 12
        labels = []
        while True:
            size = request[cursor]
            cursor += 1
            if size == 0:
                break
            if size > 63 or cursor + size >= len(request):
                raise ValueError('Invalid fixture DNS label')
            labels.append(request[cursor:cursor + size].decode('ascii').lower())
            cursor += size
        if cursor + 4 > len(request):
            raise ValueError('Truncated fixture DNS question')
        kind, family = struct.unpack('>HH', request[cursor:cursor + 4])
        question = request[12:cursor + 4]
        hostname = '.'.join(labels)
        if hostname not in TARGETS or kind != 1 or family != 1:
            self.rejected += 1
            return struct.pack('>6H', identifier, 0x8183, 1, 0, 0, 0) + question
        self.queries[hostname] += 1
        # A-only IN answer, name compression points to the already validated
        # question. Fixed TEST-NET addresses are never connected to by the test.
        response = struct.pack('>6H', identifier, 0x8180, 1, 1, 0, 0) + question
        return response + struct.pack('>HHHIH', 0xc00c, 1, 1, 0, 4) + TARGETS[hostname]

    def serve(self):
        while not self.stop.is_set():
            try:
                request, peer = self.socket.recvfrom(513)
                try:
                    response = self.reply(request)
                except (ValueError, IndexError, UnicodeError, struct.error):
                    self.rejected += 1
                    continue
                self.socket.sendto(response, peer)
            except socket.timeout:
                continue
            except OSError:
                if not self.stop.is_set():
                    self.rejected += 1
                break

    def close(self):
        self.stop.set()
        self.thread.join(1)
        self.socket.close()


def main(mode):
    assert mode in ('empty', 'configured')
    configured = mode == 'configured'
    resolver = Path('/etc/resolv.conf').read_text()
    hosts = Path('/etc/hosts').read_text()
    assert resolver == ('nameserver 127.0.0.2\noptions timeout:1 attempts:1\n' if configured else '')
    assert hosts == ('127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n' if configured else '')
    fixture = DnsFixture()
    started = time.monotonic()
    try:
        # Exercise the actual libc resolver first, not a Python DNS library.
        # The same supplied file is then consumed by the production preflight.
        matched = []
        for hostname, packed in TARGETS.items():
            try:
                addresses = socket.gethostbyname_ex(hostname)[2]
                matched.append(addresses == [socket.inet_ntoa(packed)])
            except (socket.gaierror, socket.herror):
                matched.append(False)
        try:
            localhost = socket.gethostbyname('localhost') == '127.0.0.1'
        except (socket.gaierror, socket.herror):
            localhost = False
        assert matched == [configured, configured], 'Pinned guest resolver did not follow bound resolver file'
        assert localhost == configured, 'Pinned guest resolver did not follow bound loopback hosts file'
        session = Path('/session')
        session.mkdir(exist_ok=True)
        result_path = session / 'network-check.json'
        result_path.unlink(missing_ok=True)
        probe = subprocess.run([sys.executable, '/opt/lsb/network_check.py'],
                               capture_output=True, timeout=12, check=True)
        assert not probe.stdout and not probe.stderr, 'Guest preflight emitted unexpected unbounded output'
        preflight = json.loads(result_path.read_text())
        assert set(preflight) == {'format', 'layer', 'checks', 'any_resolved', 'all_resolved',
                                  'elapsed_ms', 'patch_connection_verified'}
        assert preflight['format'] == 1 and preflight['layer'] == 'guest_ipv4_resolver'
        assert preflight['any_resolved'] is configured and preflight['all_resolved'] is configured
        assert preflight['patch_connection_verified'] is False
        assert [row['target'] for row in preflight['checks']] == ['patch_server', 'official_web']
        for row in preflight['checks']:
            assert set(row) == {'target', 'ok', 'address_count', 'error', 'error_code', 'elapsed_ms'}
            assert row['ok'] is configured and row['address_count'] == int(configured)
            assert row['error'] == 'none' if configured else row['error'] in ('lookup_failed', 'timeout')
            assert type(row['error_code']) is int and type(row['elapsed_ms']) is int
        assert type(preflight['elapsed_ms']) is int and preflight['elapsed_ms'] < 11000
        report = {'format': 1, 'case': mode, 'network': 'disabled',
                  'guest_dns_resolved': matched, 'loopback_hosts_resolved': localhost,
                  'controlled_dns_query_counts': list(fixture.queries.values()),
                  'fixture_rejected_queries': fixture.rejected,
                  'production_preflight': preflight,
                  'elapsed_ms': int((time.monotonic() - started) * 1000)}
        output = Path('/logs') / ('client-network-' + mode + '.json')
        output.write_text(json.dumps(report, indent=2))
        assert all(count >= 2 for count in fixture.queries.values()) if configured else not any(fixture.queries.values())
        print('PASS: pinned guest resolver fixture', mode, json.dumps(report), flush=True)
    finally:
        fixture.close()


if __name__ == '__main__':
    main(sys.argv[1])
