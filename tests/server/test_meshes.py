"""Pinned map assets, existing user data and atomic failure recovery."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import struct
import tarfile
import tempfile
import unittest
from unittest import mock
import zlib

spec = importlib.util.spec_from_file_location('mesh_assets', Path(__file__).resolve().parents[2] / 'server/meshes.py')
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)

PINS = dict(navmeshes='a' * 40, ximeshes='b' * 40)
IDENTITY = dict(repository='owner/server', commit='c' * 40, ref='base')
NAV = struct.pack('<III5fiiII', 0x4D534554, 1, 1, 0, 0, 0, 32, 32, 2, 2097152, 4194304, 4) + b'data'
XI = zlib.compress(struct.pack('<HHIIHHI', 1, 1, 24, 28, 1, 1, 0) + bytes(12))
MODULES = ''.join('[submodule "' + name + '"]\n path = ' + name + '\n url = https://github.com/' + repository + '.git\n'
                  for name, repository in m.REPOSITORIES.items())


class Response(io.BytesIO):
    def __init__(self, content, length=None):
        super().__init__(content)
        self.headers = {'Content-Length': str(len(content) if length is None else length)}


def archive(kind, entries=None):
    buffer = io.BytesIO()
    prefix = m.REPOSITORIES[kind].split('/')[1] + '-' + PINS[kind]
    if entries is None:
        entries = [('test.nav' if kind == 'navmeshes' else 'test.ximesh', NAV if kind == 'navmeshes' else XI, tarfile.REGTYPE)]
    with tarfile.open(fileobj=buffer, mode='w:gz') as tar:
        folder = tarfile.TarInfo(prefix)
        folder.type = tarfile.DIRTYPE
        tar.addfile(folder)
        for name, payload, kind in entries:
            entry = tarfile.TarInfo(prefix + '/' + name)
            entry.size = len(payload)
            entry.type = kind
            if kind in (tarfile.SYMTYPE, tarfile.LNKTYPE):
                entry.linkname = '../../outside'
            tar.addfile(entry, io.BytesIO(payload) if entry.isfile() else None)
    return buffer.getvalue()


class MeshTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name) / 'server'
        self.root.mkdir()
        (self.root / '.gitmodules').write_text(MODULES)
        self.messages = []
        self.requests = []
        self.archives = {kind: archive(kind) for kind in m.REPOSITORIES}
        self.entries = [dict(path=name, mode='160000', type='commit', sha=pin) for name, pin in PINS.items()]
        patch = mock.patch.object(m, '_request', side_effect=self.request)
        self.http = patch.start()
        self.addCleanup(patch.stop)

    def request(self, url):
        self.requests.append(url)
        if url == 'https://api.github.com/repos/owner/server/git/trees/' + IDENTITY['commit']:
            return Response(json.dumps(dict(tree=self.entries, truncated=False)).encode())
        for name, repository in m.REPOSITORIES.items():
            if url == 'https://codeload.github.com/' + repository + '/tar.gz/' + PINS[name]:
                return Response(self.archives[name])
        raise AssertionError('Unexpected unpinned request: ' + url)

    def ensure(self, **kwargs):
        return m.ensure(self.root, IDENTITY, self.messages.append, **kwargs)

    def assert_no_partial(self):
        self.assertFalse(any(self.root.glob('.mesh-*')))
        self.assertFalse(any(self.root.glob('.*-previous-*')))

    def test_missing_gitlinks_download_exact_pins_and_validate(self):
        result = self.ensure()
        for name, repository in m.REPOSITORIES.items():
            self.assertEqual(result[name], dict(repository=repository, commit=PINS[name], assets=1, provenance='selected-source-gitlink'))
        self.assertEqual(len(self.requests), 3)
        self.assertEqual(set(m.validate(self.root)), set(m.REPOSITORIES))
        self.assertTrue(any('Downloading' in message for message in self.messages))
        self.assertTrue(any('ready: 1 map assets' in message for message in self.messages))
        self.assert_no_partial()

    def test_existing_custom_assets_are_preserved_offline_without_source_identity(self):
        for kind, payload in (('navmeshes', NAV + b'custom'), ('ximeshes', XI)):
            folder = self.root / kind
            folder.mkdir()
            (folder / ('custom.nav' if kind == 'navmeshes' else 'custom.ximesh')).write_bytes(payload)
            (folder / 'personal-notes.txt').write_text('user notes')
        before = {str(path): path.read_bytes() for path in self.root.rglob('*') if path.is_file()}
        result = m.ensure(self.root, {}, self.messages.append)
        self.assertTrue(all(report['provenance'] == 'existing-assets' for report in result.values()))
        self.assertEqual(before, {str(path): path.read_bytes() for path in self.root.rglob('*') if path.is_file()})
        self.http.assert_not_called()

    def test_second_ensure_needs_no_network(self):
        self.ensure()
        self.http.reset_mock()
        self.ensure()
        self.http.assert_not_called()

    def test_nested_only_assets_are_not_ready_for_root_level_loader(self):
        folder = self.root / 'navmeshes/nested'
        folder.mkdir(parents=True)
        (folder / 'custom.nav').write_bytes(NAV)
        with self.assertRaisesRegex(ValueError, 'Missing navmeshes'):
            m.validate(self.root)
        result = self.ensure()
        self.assertEqual(result['navmeshes']['assets'], 1)
        self.assertEqual((folder / 'custom.nav').read_bytes(), NAV)


    def test_source_without_declared_mesh_submodules_stays_legacy(self):
        for content in (None, '[submodule "unrelated"]\n path = thirdparty\n url = https://example.invalid/repo\n'):
            with self.subTest(content=content):
                (self.root / '.gitmodules').unlink(missing_ok=True)
                if content is not None:
                    (self.root / '.gitmodules').write_text(content)
                self.assertEqual(m.ensure(self.root, {}), {})
                self.assertEqual(m.validate(self.root), {})
        self.http.assert_not_called()

    def test_validate_is_read_only_and_readme_is_not_a_mesh(self):
        for name in m.REPOSITORIES:
            (self.root / name).mkdir()
            (self.root / name / 'README.md').write_text('not an asset')
        with self.assertRaisesRegex(ValueError, 'Missing navmeshes'):
            m.validate(self.root)
        self.http.assert_not_called()
        result = self.ensure()
        self.assertEqual(result['navmeshes']['assets'], 1)
        self.assertEqual((self.root / 'navmeshes/README.md').read_text(), 'not an asset')

    def test_unknown_url_and_path_are_rejected_before_network(self):
        for content in (MODULES.replace('https://github.com/LandSandBoat/xiNavmeshes.git', 'https://attacker.invalid/files'),
                        MODULES.replace('path = navmeshes', 'path = ../navmeshes')):
            with self.subTest(content=content):
                (self.root / '.gitmodules').write_text(content)
                with self.assertRaisesRegex(ValueError, 'Unsupported'):
                    self.ensure()
        self.http.assert_not_called()

    def test_unrecorded_source_without_compatible_readers_fails_actionably(self):
        with self.assertRaisesRegex(ValueError, 'no verifiable source commit'):
            m.ensure(self.root, {'origin': 'Previously built source; original repository not recorded'})
        self.http.assert_not_called()
        self.assert_no_partial()

    def test_compatibility_catalog_matches_normalized_readers_not_a_newly_fetched_source(self):
        parser = self.root / 'src/reader.cpp'
        parser.parent.mkdir()
        parser.write_bytes(b'compatible reader\r\n')
        catalog = [dict(source='official/source@known', pins=PINS, files={
            '.gitmodules': hashlib.sha256(MODULES.encode()).hexdigest(),
            'src/reader.cpp': hashlib.sha256(b'compatible reader\n').hexdigest(),
        })]
        with mock.patch.object(m, 'COMPATIBILITY', catalog):
            result = m.ensure(self.root, {'origin': 'legacy'}, self.messages.append)
        self.assertTrue(all(report['provenance'] == 'compatible-loader-catalog' for report in result.values()))
        self.assertEqual(len(self.requests), 2)
        self.assertTrue(all('/tar.gz/' in url for url in self.requests))
        self.assertEqual(parser.read_bytes(), b'compatible reader\r\n')

    def test_compatibility_fails_when_a_reader_changed(self):
        (self.root / 'reader.cpp').write_text('modified reader')
        catalog = [dict(source='source@pin', pins=PINS, files={'reader.cpp': hashlib.sha256(b'original reader').hexdigest()})]
        with mock.patch.object(m, 'COMPATIBILITY', catalog), self.assertRaisesRegex(ValueError, 'no verifiable source commit'):
            m.ensure(self.root, {})
        self.http.assert_not_called()

    def test_present_but_invalid_source_commit_does_not_fall_back(self):
        with mock.patch.object(m, 'COMPATIBILITY', [dict(source='source@pin', pins=PINS, files={})]), self.assertRaisesRegex(ValueError, 'identity is invalid'):
            m.ensure(self.root, dict(repository='owner/server', commit='base'))
        self.http.assert_not_called()

    def test_no_branch_tip_fallback_when_exact_tree_lacks_a_gitlink(self):
        self.entries[0]['mode'] = '040000'
        self.entries[0]['type'] = 'tree'
        with self.assertRaisesRegex(ValueError, 'no pinned navmeshes'):
            self.ensure()
        self.assertEqual(len(self.requests), 1)
        self.assertFalse((self.root / 'navmeshes').exists())

    def test_gitlink_duplicates_are_rejected(self):
        self.entries.append(dict(self.entries[0]))
        with self.assertRaisesRegex(ValueError, 'no pinned navmeshes'):
            self.ensure()
        self.assertEqual(len(self.requests), 1)

    def test_truncated_metadata_is_rejected(self):
        self.http.side_effect = lambda url: Response(json.dumps(dict(tree=self.entries, truncated=True)).encode())
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            self.ensure()

    def test_tar_traversal_links_and_special_files_never_publish(self):
        for name, kind in (('../outside', tarfile.REGTYPE), ('link', tarfile.SYMTYPE), ('hard', tarfile.LNKTYPE),
                           ('device', tarfile.CHRTYPE), ('pipe', tarfile.FIFOTYPE), ('bad\\path', tarfile.REGTYPE)):
            with self.subTest(name=name, kind=kind):
                self.archives['navmeshes'] = archive('navmeshes', [(name, b'', kind)])
                with self.assertRaisesRegex(ValueError, 'Unsafe entry'):
                    self.ensure()
                self.assertFalse((self.root / 'navmeshes').exists())
                self.assertFalse((self.root.parent / 'outside').exists())
                self.assert_no_partial()

    def test_tar_with_no_real_assets_fails(self):
        self.archives['navmeshes'] = archive('navmeshes', [('README.md', b'not a mesh', tarfile.REGTYPE)])
        with self.assertRaisesRegex(ValueError, 'no usable map assets'):
            self.ensure()
        self.assertFalse((self.root / 'navmeshes').exists())
        self.assert_no_partial()

    def test_lfs_pointer_is_rejected_and_not_published(self):
        self.archives['navmeshes'] = archive('navmeshes', [('test.nav', b'version https://git-lfs.github.com/spec/v1\noid sha256:a', tarfile.REGTYPE)])
        with self.assertRaisesRegex(ValueError, 'Git LFS pointers'):
            self.ensure()
        self.assertFalse((self.root / 'navmeshes').exists())
        self.assert_no_partial()

    def test_invalid_existing_assets_are_not_replaced(self):
        (self.root / 'navmeshes').mkdir()
        broken = self.root / 'navmeshes/custom.nav'
        broken.write_bytes(b'bad data')
        with self.assertRaisesRegex(ValueError, 'Truncated navigation mesh'):
            self.ensure()
        self.assertEqual(broken.read_bytes(), b'bad data')
        self.http.assert_not_called()

    def test_invalid_ximesh_fails_before_publication(self):
        self.archives['ximeshes'] = archive('ximeshes', [('bad.ximesh', b'invalid compressed content', tarfile.REGTYPE)])
        with self.assertRaisesRegex(ValueError, 'Invalid collision mesh'):
            self.ensure()
        self.assertTrue((self.root / 'navmeshes/test.nav').is_file())
        self.assertFalse((self.root / 'ximeshes').exists())
        self.assert_no_partial()

    def test_source_and_existing_directory_symlinks_are_rejected(self):
        (self.root / 'navmeshes').symlink_to(self.root.parent, target_is_directory=True)
        with self.assertRaisesRegex(ValueError, 'ordinary directory'):
            self.ensure()
        (self.root / 'navmeshes').unlink()
        (self.root / '.gitmodules').rename(self.root / 'real-gitmodules')
        (self.root / '.gitmodules').symlink_to(self.root / 'real-gitmodules')
        with self.assertRaisesRegex(ValueError, 'safely read'):
            self.ensure()
        self.http.assert_not_called()

    def test_short_http_download_keeps_existing_placeholder(self):
        (self.root / 'navmeshes').mkdir()
        note = self.root / 'navmeshes/note.txt'
        note.write_text('preserve me')
        request = self.request
        def shorter(url):
            response = request(url)
            if '/tar.gz/' in url:
                response.headers['Content-Length'] = str(int(response.headers['Content-Length']) + 3)
            return response
        self.http.side_effect = shorter
        with self.assertRaisesRegex(ValueError, 'incomplete'):
            self.ensure()
        self.assertEqual(note.read_text(), 'preserve me')
        self.assertEqual(list(note.parent.iterdir()), [note])
        self.assert_no_partial()

    def test_compressed_expanded_and_entry_limits(self):
        for field, value in (('MAX_ARCHIVE_BYTES', 1), ('MAX_EXPANDED_BYTES', 1), ('MAX_FILES', 1), ('MAX_FILE_BYTES', 1)):
            with self.subTest(field=field), mock.patch.object(m, field, value), self.assertRaisesRegex(ValueError, 'limit|size|entries'):
                self.ensure()
            self.assertFalse((self.root / 'navmeshes').exists())
            self.assert_no_partial()

    def test_cancel_during_download_leaves_no_published_directory(self):
        request = self.request
        stopped = False
        def response(url):
            nonlocal stopped
            result = request(url)
            if '/tar.gz/' in url:
                stopped = True
            return result
        self.http.side_effect = response
        with self.assertRaises(InterruptedError):
            self.ensure(cancelled=lambda: stopped)
        self.assertFalse((self.root / 'navmeshes').exists())
        self.assert_no_partial()

    def test_exception_style_cancellation_is_supported(self):
        def cancel():
            raise InterruptedError('server stop')
        with self.assertRaisesRegex(InterruptedError, 'server stop'):
            self.ensure(cancelled=cancel)
        self.http.assert_not_called()

    def test_cache_reuses_pinned_assets_with_independent_copies(self):
        cache = self.root.parent / 'mesh-cache'
        first = self.ensure(cache=cache)
        second = self.root.parent / 'second-server'
        second.mkdir()
        (second / '.gitmodules').write_text(MODULES)
        self.requests.clear()
        result = m.ensure(second, IDENTITY, self.messages.append, cache=cache)
        self.assertEqual(result, first)
        self.assertEqual(len(self.requests), 1)  # Verify this source's gitlinks; no archive re-download.
        asset = second / 'navmeshes/test.nav'
        self.assertNotEqual(asset.stat().st_ino, (cache / ('navmeshes-' + PINS['navmeshes']) / 'test.nav').stat().st_ino)
        asset.write_bytes(b'edited by user')
        self.assertEqual((self.root / 'navmeshes/test.nav').read_bytes(), NAV)
        self.assertEqual((cache / ('navmeshes-' + PINS['navmeshes']) / 'test.nav').read_bytes(), NAV)

    def test_modified_or_incomplete_cache_is_redownloaded_before_use(self):
        for mutation in ('modify', 'delete'):
            with self.subTest(mutation=mutation):
                cache = self.root.parent / ('cache-' + mutation)
                # This test needs a missing generation even after its first loop.
                import shutil
                for name in m.REPOSITORIES:
                    shutil.rmtree(self.root / name, ignore_errors=True)
                self.ensure(cache=cache)
                nav = cache / ('navmeshes-' + PINS['navmeshes']) / 'test.nav'
                if mutation == 'modify':
                    nav.write_bytes(NAV + b'changed but still valid header')
                else:
                    nav.unlink()
                shutil.rmtree(self.root / 'navmeshes')
                self.requests.clear()
                self.ensure(cache=cache)
                self.assertEqual(len(self.requests), 2)  # tree metadata + replacement nav archive
                self.assertEqual((self.root / 'navmeshes/test.nav').read_bytes(), NAV)
                self.assertEqual(nav.read_bytes(), NAV)
                self.assertFalse((self.root / 'navmeshes' / m.CACHE_RECEIPT).exists())



if __name__ == '__main__':
    unittest.main()
