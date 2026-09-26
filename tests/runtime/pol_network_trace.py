"""Test-only, bounded metadata from Wine 10's private Winsock trace.

Source grammar: wine-mirror/wine tag wine-10.0, dlls/ws2_32/socket.c
(connect, WS2_sendto, WS2_recv_base), protocol.c (gethostbyname), and
include/ntstatus.h. No host, address, socket handle, buffer or payload is
returned. A lookup/connection entry is an attempt, not evidence of success.
A successful receive status does not establish that any bytes were received.
"""
import re
import threading


class NetworkDiagnostics:
    WINE = re.compile(rb'^(?:[0-9]+\.[0-9]+:)?(?:[0-9a-f]{4,8}:){1,2}'
                      rb'trace:winsock:([a-z0-9_]+) (.*)$')
    STATUS = rb'(0|0x[0-9a-f]{1,8})'
    CONNECT_STATUS = re.compile(rb'failed, status ' + STATUS + rb'\.')
    IO_STATUS = re.compile(rb'status ' + STATUS + rb'\.')
    IPV4_CONNECT = re.compile(rb'socket (?:0|0x[0-9a-f]{1,16}), addr '
                             rb'\{ family af_inet, address ([0-9.]{7,15}), port ([0-9]{1,5}) \}, len ([0-9]{1,3})')
    IPV6_CONNECT = re.compile(rb'socket (?:0|0x[0-9a-f]{1,16}), addr '
                             rb'\{ family af_inet6, address [0-9a-f:.]{2,45}, flow label (?:0|0x[0-9a-f]{1,8}), '
                             rb'port ([0-9]{1,5}), scope [0-9]{1,10} \}, len ([0-9]{1,3})')
    HOSTS = {b'"qc000.pol.com"': 'patch_query', b'"ci000.pol.com"': 'content_info',
             b'"pp000.pol.com"': 'patch_profile', b'"pc000.pol.com"': 'patch_content',
             b'"www.playonline.com"': 'official_web'}
    LIMIT = 1000000
    MAX_STATUSES = 24
    MAX_CONNECTS = 16

    def __init__(self):
        self.lock = threading.RLock()
        self.tail = b''
        self.discard = False
        self.counts = {}
        self.lookups = {}
        self.connects = {}
        self.statuses = {}
        self.incomplete = 0
        self.dropped = 0

    @classmethod
    def bump(cls, rows, key):
        rows[key] = min(cls.LIMIT, rows.get(key, 0) + 1)

    @staticmethod
    def classification(status):
        if status == 0:
            return 'completed'
        # Wine maps DEVICE_NOT_READY and REQUEST_NOT_ACCEPTED to WSAEWOULDBLOCK;
        # connect maps ADDRESS_ALREADY_ASSOCIATED to WSAEALREADY. These states
        # can be ordinary nonblocking progress, not a failed patch service.
        if status in (0x103, 0xc00000a3, 0xc00000d0, 0xc0000238):
            return 'pending'
        if status == 0xc000023b:
            return 'already_connected'
        return 'failed' if status & 0xc0000000 == 0xc0000000 else 'other'

    def line(self, data):
        with self.lock:
            if len(data) > 4096:
                self.incomplete = min(self.LIMIT, self.incomplete + 1)
                return
            data = re.sub(rb'\x1b\[[0-?]*[ -/]*[@-~]', b'', data).strip().lower()
            row = self.WINE.fullmatch(data)
            if not row:
                return
            function, message = row.groups()
            if function == b'gethostbyname':
                # Only the source function's quoted-string entry is recognized.
                # Unknown/private names become a single fixed label.
                if re.fullmatch(rb'"[^\r\n]*"', message) or message == b'(null)':
                    self.bump(self.lookups, self.HOSTS.get(message, 'other'))
                    self.bump(self.counts, 'lookup_started')
                return
            if function == b'connect':
                address = self.IPV4_CONNECT.fullmatch(message)
                family = 'ipv4'
                if address:
                    octets = address[1].split(b'.')
                    if len(octets) != 4 or any(not x or int(x) > 255 for x in octets):
                        return
                    port, size = int(address[2]), int(address[3])
                else:
                    address = self.IPV6_CONNECT.fullmatch(message)
                    family = 'ipv6'
                    if address:
                        port, size = int(address[1]), int(address[2])
                if address:
                    if port > 65535 or size != (16 if family == 'ipv4' else 28):
                        return
                    self.bump(self.counts, 'connect_started')
                    key = family, port
                    if key in self.connects or len(self.connects) < self.MAX_CONNECTS:
                        self.bump(self.connects, key)
                    else:
                        self.dropped = min(self.LIMIT, self.dropped + 1)
                    return
                status = self.CONNECT_STATUS.fullmatch(message)
                operation = 'connect'
            elif function in (b'ws2_sendto', b'ws2_recv_base'):
                status = self.IO_STATUS.fullmatch(message)
                operation = 'send' if function == b'ws2_sendto' else 'receive'
            else:
                return
            if status:
                value = int(status[1], 16)
                if operation == 'connect' and value == 0:
                    return  # connect never emits a failure row on success.
                self.bump(self.counts, operation + '_status')
                key = operation, value
                if key in self.statuses or len(self.statuses) < self.MAX_STATUSES:
                    self.bump(self.statuses, key)
                else:
                    self.dropped = min(self.LIMIT, self.dropped + 1)

    def feed(self, chunk):
        with self.lock:
            parts = chunk.replace(b'\0', b'').split(b'\n')
            for index, part in enumerate(parts):
                if not self.discard:
                    if len(self.tail) + len(part) > 4096:
                        self.tail = b''
                        self.discard = True
                        self.incomplete = min(self.LIMIT, self.incomplete + 1)
                    else:
                        self.tail += part
                if index < len(parts) - 1:
                    if not self.discard:
                        self.line(self.tail)
                    self.tail = b''
                    self.discard = False

    def finish(self):
        with self.lock:
            if not self.discard and self.tail:
                self.line(self.tail)
            self.tail = b''
            self.discard = False

    def snapshot(self):
        with self.lock:
            return {'format': 1, 'policy': 'fixed_metadata_only', 'layer': 'wine_winsock_trace',
                    'counts': dict(self.counts), 'lookup_started': dict(self.lookups),
                    'connect_started': [{'family': family, 'port': port, 'count': count}
                                        for (family, port), count in self.connects.items()],
                    'statuses': [{'operation': operation, 'status': status,
                                  'classification': self.classification(status), 'count': count}
                                 for (operation, status), count in self.statuses.items()],
                    'incomplete_lines': self.incomplete, 'dropped_statuses': self.dropped}
