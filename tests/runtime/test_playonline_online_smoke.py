"""Readback regression for the public-installer diagnostic fixture only."""
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime'))
from playonline_online_smoke import interface_readback


class InterfaceReadbackTest(unittest.TestCase):
    VALUE = b'    1000    REG_SZ    001b1394\r\n'

    def test_value_after_verbose_startup(self):
        raw = b'0024:trace:loaddll:build_module fixture startup message\n' * 200 + self.VALUE
        self.assertGreater(len(raw), 8192)
        self.assertFalse(interface_readback(raw[:8192]))
        self.assertTrue(interface_readback(raw))

    def test_redirected_unicode_output(self):
        self.assertTrue(interface_readback(self.VALUE.decode().encode('utf-16le')))

    def test_missing_wrong_or_duplicate_value(self):
        for raw in (b'', b'1000 REG_SZ incorrect\n', self.VALUE.replace(b'001b1394', b'001b1395'), self.VALUE * 2):
            self.assertFalse(interface_readback(raw))

    def test_log_bound(self):
        self.assertFalse(interface_readback(b'x' * (2 * 1024 * 1024) + b'\n' + self.VALUE))


if __name__ == '__main__':
    unittest.main()
