import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock


SERVER = Path(__file__).resolve().parents[2] / 'server'
spec = importlib.util.spec_from_file_location('patch_backend', SERVER / 'manager.py')
backend = importlib.util.module_from_spec(spec)
spec.loader.exec_module(backend)
PATCHES = json.loads((SERVER / 'source-patches.json').read_text())


class SourcePatchTests(unittest.TestCase):
    def fixture(self, root, newline='\n'):
        grouped = {}
        for patch in PATCHES:
            grouped.setdefault(patch['path'], []).append(patch['before'])
        for name, before in grouped.items():
            path = root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(('unrelated prefix\n' + '\n'.join(before) + '\nunrelated suffix\n').replace('\n', newline).encode())

    def test_patch_group_preserves_line_endings_and_is_idempotent(self):
        for newline in ('\n', '\r\n'):
            with self.subTest(newline=newline), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.fixture(root, newline)
                with mock.patch.object(backend, 'RUN', root / 'run'):
                    report = backend.apply_source_patches(root)
                    self.assertEqual([entry['state'] for entry in report], ['applied'] * 3)
                    first = {path: path.read_bytes() for path in root.rglob('*.cpp')}
                    repeated = backend.apply_source_patches(root)
                    self.assertEqual([entry['state'] for entry in repeated], ['already_applied'] * 3)
                    self.assertEqual(first, {path: path.read_bytes() for path in root.rglob('*.cpp')})
                    for patch in PATCHES:
                        content = (root / patch['path']).read_bytes()
                        self.assertIn(patch['after'].replace('\n', newline).encode(), content)
                        self.assertIn(b'unrelated prefix', content)
                        self.assertIn(b'unrelated suffix', content)
                        if newline == '\r\n':
                            self.assertNotIn(b'\n', content.replace(b'\r\n', b''))

    def test_partial_group_fails_before_changing_any_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            caller = root / PATCHES[2]['path']
            caller.write_text('custom caller requires review\n')
            original = {path: path.read_bytes() for path in root.rglob('*.cpp')}
            with mock.patch.object(backend, 'RUN', root / 'run'):
                with self.assertRaises((RuntimeError, ValueError)):
                    backend.apply_source_patches(root)
            self.assertEqual(original, {path: path.read_bytes() for path in root.rglob('*.cpp')})

    def test_duplicate_context_fails_before_changing_any_source(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.fixture(root)
            common = root / PATCHES[0]['path']
            common.write_text(common.read_text() + '\n' + PATCHES[0]['before'])
            original = {path: path.read_bytes() for path in root.rglob('*.cpp')}
            with mock.patch.object(backend, 'RUN', root / 'run'):
                with self.assertRaises((RuntimeError, ValueError)):
                    backend.apply_source_patches(root)
            self.assertEqual(original, {path: path.read_bytes() for path in root.rglob('*.cpp')})

    def test_unrelated_revision_is_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'unrelated.cpp').write_text('unrelated revision\n')
            with mock.patch.object(backend, 'RUN', root / 'run'):
                report = backend.apply_source_patches(root)
            self.assertEqual([entry['state'] for entry in report], ['not_applicable'] * 3)
            self.assertEqual((root / 'unrelated.cpp').read_text(), 'unrelated revision\n')


if __name__ == '__main__':
    unittest.main()
