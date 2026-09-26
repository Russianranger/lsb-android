import copy
import json
from pathlib import Path
import socket
import sys
import tempfile
import threading
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime'))
import network_check
import supervisor


class NetworkCheckTests(unittest.TestCase):
    def success(self):
        return network_check.check(lambda host: ('PRIVATE-CANONICAL-NAME', ['PRIVATE-ALIAS'],
                                                ['192.0.2.42', '192.0.2.42']))

    def test_resolution_retains_counts_not_network_values(self):
        result = network_check.validate(self.success())
        self.assertTrue(result['all_resolved'])
        self.assertEqual([row['address_count'] for row in result['checks']], [1, 1])
        self.assertFalse(result['patch_connection_verified'])
        text = json.dumps(result)
        for private in ('192.0.2.42', 'PRIVATE-', 'ci000.pol.com', 'www.playonline.com'):
            self.assertNotIn(private, text)

    def test_lookup_errors_are_numeric_and_partial_dns_is_distinct(self):
        def lookup(host):
            if host == network_check.TARGETS[0][1]:
                raise socket.herror(1, 'PRIVATE DNS value')
            return ('ignored', [], ['192.0.2.1'])
        result = network_check.validate(network_check.check(lookup))
        self.assertTrue(result['any_resolved'])
        self.assertFalse(result['all_resolved'])
        self.assertEqual(result['checks'][0]['error_code'], 1)
        self.assertNotIn('PRIVATE', json.dumps(result))

    def test_slow_resolver_has_bounded_wait(self):
        release = threading.Event()
        def slow(host):
            release.wait(1)
            return ('ignored', [], ['192.0.2.1'])
        try:
            result = network_check.validate(network_check.check(slow, timeout=.01))
            self.assertFalse(result['any_resolved'])
            self.assertTrue(all(row['error'] == 'timeout' for row in result['checks']))
            self.assertLess(result['elapsed_ms'], 500)
        finally:
            release.set()

    def test_receipt_cannot_add_private_data_or_claim_unverified_connection(self):
        base = self.success()
        for key, value in [('addresses', ['192.0.2.1']), ('patch_connection_verified', True),
                           ('any_resolved', False), ('elapsed_ms', True)]:
            with self.subTest(key=key):
                changed = copy.deepcopy(base); changed[key] = value
                with self.assertRaises(ValueError):
                    network_check.validate(changed)
        changed = copy.deepcopy(base); changed['checks'][0]['target'] = 'PRIVATE HOST'
        with self.assertRaises(ValueError):
            network_check.validate(changed)

    def run_preflight(self, root, result, stale=None):
        session = root / 'session'; logs = root / 'logs'
        session.mkdir(); logs.mkdir()
        if stale is not None:
            (session / 'network-check.json').write_text(json.dumps(stale))
        instance = supervisor.Supervisor({'format': 1, 'renderer': 'software', 'audio': False,
            'session_id': '12345678-1234-1234-1234-123456789abc', 'action': 'update-client', 'network_preflight': True})
        states = []
        instance.status = lambda phase=None, **fields: states.append((phase, fields))
        def spawn(*args, **kwargs):
            if result is not None:
                (session / 'network-check.json').write_text(json.dumps(result))
            return object()
        instance.spawn = spawn
        instance.wait = lambda *args, **kwargs: None
        with patch.object(supervisor, 'SESSION', session), patch.object(supervisor, 'LOGS', logs):
            instance.check_update_network()
        return states, json.loads((logs / 'network-check.json').read_text())

    def test_valid_receipt_is_bound_to_current_session(self):
        with tempfile.TemporaryDirectory() as folder:
            states, saved = self.run_preflight(Path(folder), self.success())
        self.assertEqual(saved['session_id'], '12345678-1234-1234-1234-123456789abc')
        self.assertEqual(states[0][0], 'checking_update_network')

    def test_missing_receipt_cannot_reuse_an_earlier_success(self):
        with tempfile.TemporaryDirectory() as folder, self.assertRaisesRegex(RuntimeError, 'could not finish'):
            self.run_preflight(Path(folder), None, stale=self.success())

    def test_all_failed_dns_stops_before_wine_launch(self):
        def failed(host):
            raise socket.herror(1, 'not retained')
        result = network_check.check(failed)
        with tempfile.TemporaryDirectory() as folder, self.assertRaisesRegex(RuntimeError, 'cannot resolve'):
            self.run_preflight(Path(folder), result)

    def test_preflight_cannot_be_requested_for_gameplay(self):
        for action in ('launch', 'probe', 'verify-client-update'):
            with self.subTest(action=action), self.assertRaises(ValueError):
                supervisor.validate_request({'format': 1, 'renderer': 'software', 'audio': False,
                    'session_id': '12345678-1234-1234-1234-123456789abc', 'action': action, 'network_preflight': True})


if __name__ == '__main__':
    unittest.main()
