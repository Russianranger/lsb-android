import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).parent))
from pol_network_trace import NetworkDiagnostics


class NetworkTraceTests(unittest.TestCase):
    def parsed(self, lines):
        parser = NetworkDiagnostics()
        data = '\n'.join(lines).encode()
        for index in range(0, len(data), 3):
            parser.feed(data[index:index + 3])
        parser.finish()
        return parser.snapshot()

    def test_real_source_messages_keep_only_fixed_network_metadata(self):
        result = self.parsed([
            '12.123:0010:0014:trace:winsock:gethostbyname "ci000.pol.com"',
            '0014:trace:winsock:gethostbyname "private_password.example"',
            '0014:trace:winsock:connect socket 0x40, addr { family AF_INET, address 192.0.2.34, port 80 }, len 16',
            '0014:trace:winsock:connect failed, status 0xc0000236.',
            '0014:trace:winsock:WS2_sendto status 0.',
            '0014:trace:winsock:WS2_recv_base status 0xc000020d.',
            '0014:trace:winsock:WS2_sendto socket 0x40, buffers 0x1234, private_password payload',
        ])
        self.assertEqual(result['lookup_started'], {'content_info': 1, 'other': 1})
        self.assertEqual(result['connect_started'], [{'family': 'ipv4', 'port': 80, 'count': 1}])
        self.assertEqual([(r['operation'], r['classification']) for r in result['statuses']],
                         [('connect', 'failed'), ('send', 'completed'), ('receive', 'failed')])
        serialized = json.dumps(result)
        for private in ('private', '192.0.2.34', '0x40', '0x1234', 'ci000.pol.com', 'payload'):
            self.assertNotIn(private, serialized)

    def test_transient_statuses_are_not_connection_failures(self):
        statuses = [0x103, 0xc00000a3, 0xc00000d0, 0xc0000238, 0xc000023b]
        result = self.parsed(['0014:trace:winsock:connect failed, status 0x%x.' % status
                              for status in statuses])
        self.assertEqual([r['classification'] for r in result['statuses']],
                         ['pending', 'pending', 'pending', 'pending', 'already_connected'])

    def test_exact_source_function_and_full_message_are_required(self):
        result = self.parsed([
            '0014:err:winsock:connect failed, status 0xc0000236.',
            '0014:trace:private:connect failed, status 0xc0000236.',
            '0014:trace:winsock:private failed, status 0xc0000236.',
            '0014:trace:winsock:connect failed, status 0xc0000236. private_password',
            '0014:trace:winsock:WS2_recv_base status 0x100000000.',
            '0014:trace:winsock:connect failed, status 0.',
            'username=0014:trace:winsock:WS2_sendto status 0.',
            '0014:trace:winsock:gethostbyname "qc000.pol.com" private_password',
        ])
        self.assertEqual(result['counts'], {})
        self.assertEqual(result['statuses'], [])

    def test_connect_numeric_fields_are_bounded(self):
        result = self.parsed([
            '0014:trace:winsock:connect socket 0x40, addr { family AF_INET, address 999.0.2.1, port 80 }, len 16',
            '0014:trace:winsock:connect socket 0x40, addr { family AF_INET, address 192.0.2.1, port 65536 }, len 16',
            '0014:trace:winsock:connect socket 0x40, addr { family AF_INET, address 192.0.2.1, port 80 }, len 17',
            '0014:trace:winsock:connect socket 0x40, addr { family AF_INET6, address 2001:db8::1, flow label 0, port 443, scope 0 }, len 28',
        ])
        self.assertEqual(result['connect_started'], [{'family': 'ipv6', 'port': 443, 'count': 1}])
        self.assertNotIn('2001:db8', json.dumps(result))

    def test_record_counts_and_partial_line_storage_are_bounded(self):
        parser = NetworkDiagnostics()
        for value in range(100):
            parser.line(('0014:trace:winsock:WS2_sendto status 0x%x.' % (0xc0000000 + value)).encode())
            parser.line(('0014:trace:winsock:connect socket 0x40, addr { family AF_INET, address 192.0.2.1, port %d }, len 16' % value).encode())
        parser.feed(b'x' * 9000 + b'\n0014:trace:winsock:gethostbyname "pp000.pol.com"\n')
        result = parser.snapshot()
        self.assertEqual(len(result['statuses']), parser.MAX_STATUSES)
        self.assertEqual(len(result['connect_started']), parser.MAX_CONNECTS)
        self.assertEqual(result['counts']['send_status'], 100)
        self.assertEqual(result['counts']['connect_started'], 100)
        self.assertEqual(result['lookup_started'], {'patch_profile': 1})
        self.assertEqual(result['incomplete_lines'], 1)
        self.assertGreater(result['dropped_statuses'], 0)
        self.assertLessEqual(len(parser.tail), 4096)

    def test_lookup_or_connect_entry_does_not_claim_success(self):
        result = self.parsed([
            '0014:trace:winsock:gethostbyname "qc000.pol.com"',
            '0014:trace:winsock:connect socket 0x40, addr { family AF_INET, address 192.0.2.1, port 80 }, len 16',
        ])
        self.assertEqual(result['statuses'], [])
        self.assertFalse(any('success' in key for key in result['counts']))


if __name__ == '__main__':
    unittest.main()
