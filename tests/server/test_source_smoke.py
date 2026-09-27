from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

import source_build_integration as gate


def binding(origin, symbol, provider):
    return f"  100: binding file {origin} [0] to {provider} [0]: normal symbol `{symbol}'\n"


class AllocatorSmokeTests(unittest.TestCase):
    def check_fixture(self, imports, bindings):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'logs').mkdir()
            (root / 'xi_world').write_bytes(b'compiled executable fixture')
            responses = [subprocess.CompletedProcess([], 0, 'Usage: xi_world --help\n', bindings),
                         subprocess.CompletedProcess([], 0, imports, '')]
            with mock.patch.multiple(gate, STAGING=root, ARTIFACTS=root), \
                 mock.patch.object(gate.subprocess, 'run', side_effect=responses):
                return gate.runtime_smoke('xi_world')

    def test_cpp_only_allocator_is_proven_through_libstdcpp_malloc(self):
        report = self.check_fixture('1: 0 0 FUNC GLOBAL DEFAULT UND _Znwm@GLIBCXX_3.4 (2)\n',
                                    binding('xi_world', '_Znwm', '/lib/libstdc++.so.6') +
                                    binding('/lib/libstdc++.so.6', 'malloc', '/lib/libjemalloc.so.2'))
        self.assertEqual(report['direct_allocator_imports'], [])
        self.assertEqual(report['cxx_allocator_bindings']['malloc'], '/lib/libjemalloc.so.2')

    def test_correct_main_binding_cannot_hide_wrong_libstdcpp_allocator(self):
        with self.assertRaisesRegex(AssertionError, 'outside jemalloc'):
            self.check_fixture('1: 0 0 FUNC GLOBAL DEFAULT UND malloc@GLIBC_2.17 (2)\n',
                               binding('xi_world', 'malloc', '/lib/libjemalloc.so.2') +
                               binding('/lib/libstdc++.so.6', 'malloc', '/lib/libc.so.6'))

    def test_libstdcpp_binding_cannot_replace_missing_direct_import_evidence(self):
        with self.assertRaisesRegex(AssertionError, 'missing loader bindings'):
            self.check_fixture('1: 0 0 FUNC GLOBAL DEFAULT UND malloc@GLIBC_2.17 (2)\n',
                               binding('/lib/libstdc++.so.6', 'malloc', '/lib/libjemalloc.so.2'))


if __name__ == '__main__':
    unittest.main()
