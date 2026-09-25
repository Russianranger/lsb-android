import hashlib
import importlib.util
import io
from pathlib import Path
import tempfile
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('bfd_compat', Path(__file__).resolve().parents[2] / 'server/bfd_compat.py')
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)


class BfdCompatibilityTests(unittest.TestCase):
    def test_download_rejects_modified_truncated_and_oversized_packages(self):
        expected = b'verified package'
        for content in (b'Verified package', expected[:-1], expected + b'extra'):
            with self.subTest(content=content), tempfile.TemporaryDirectory() as d:
                root = Path(d)
                with mock.patch.object(b.urllib.request, 'urlopen', return_value=io.BytesIO(content)):
                    with self.assertRaisesRegex(RuntimeError, 'checksum/size mismatch'):
                        b.download_package(root, 'libbinutils', len(expected), hashlib.sha256(expected).hexdigest())
                self.assertEqual(list(root.iterdir()), [])

    def test_archive_fallback_requires_the_same_verified_bytes(self):
        content = b'verified package'
        with tempfile.TemporaryDirectory() as d:
            with mock.patch.object(b.urllib.request, 'urlopen', side_effect=[OSError('unavailable'), io.BytesIO(content)]) as fetch:
                package, url = b.download_package(Path(d), 'libbinutils', len(content), hashlib.sha256(content).hexdigest())
            self.assertEqual(package.read_bytes(), content)
            self.assertTrue(url.startswith(b.MIRRORS[1]))
            self.assertEqual(fetch.call_count, 2)

    def test_failed_download_or_loader_check_keeps_live_libraries_and_config(self):
        for failure in ('download', 'loader'):
            with self.subTest(failure=failure), tempfile.TemporaryDirectory() as d:
                root = Path(d); libdir = root / 'compat/lib'; libdir.mkdir(parents=True)
                config = root / 'compat.conf'; config.write_text('existing loader path\n')
                for _, _, _, soname in b.PACKAGES:
                    (libdir / soname).write_bytes(b'existing library')
                before = {p: p.read_bytes() for p in root.rglob('*') if p.is_file()}
                def download(*args):
                    if failure == 'download':
                        raise RuntimeError('download failed')
                    return root / 'fixture.deb', 'https://example.test/fixture.deb'
                def extract(command, **kwargs):
                    dest = Path(command[-1]) / 'usr/lib/aarch64-linux-gnu'; dest.mkdir(parents=True)
                    for _, _, _, soname in b.PACKAGES:
                        (dest / soname).write_bytes(b'staged library')
                with mock.patch.multiple(b, LIBDIR=libdir, CONFIG=config), \
                     mock.patch.object(b.subprocess, 'check_output', return_value='arm64\n'), \
                     mock.patch.object(b.subprocess, 'run', side_effect=extract), \
                     mock.patch.object(b, 'download_package', side_effect=download), \
                     mock.patch.object(b, 'check_loader', side_effect=RuntimeError('loader failed')):
                    with self.assertRaisesRegex(RuntimeError, failure + ' failed'):
                        b.install()
                self.assertEqual({p: p.read_bytes() for p in root.rglob('*') if p.is_file()}, before)

    def test_final_loader_check_does_not_inherit_temporary_search_path(self):
        with mock.patch.dict(b.os.environ, {'LD_LIBRARY_PATH': '/tmp/unrelated'}), \
             mock.patch.object(b.subprocess, 'run') as run:
            b.check_loader()
            self.assertNotIn('LD_LIBRARY_PATH', run.call_args.kwargs['env'])


if __name__ == '__main__':
    unittest.main()
