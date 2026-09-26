import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'runtime'))
import viewer_inventory as inventory


class ViewerInventoryTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'viewer-private-account'
        self.root.mkdir()

    def file(self, relative, content=b'official-file-fixture'):
        destination = self.root / relative
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(content)
        return destination

    def test_fixed_files_are_read_only_and_no_content_or_path_is_exported(self):
        content = b'private-account-and-password-must-not-be-exported'
        selected = self.file('PoL.ExE', content)
        self.file('VERSION.DAT', b'1.18.00n')
        self.file('patch.ver', content)
        self.file('patch.ini', content)
        self.file('usr/account.ini', content)
        self.file('private_account.dll', content)
        report = inventory.snapshot(self.root, 'US')
        self.assertEqual(report['files']['viewer_executable'], {
            'state': 'ok', 'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest()})
        self.assertEqual(report['files']['viewer_version_data']['bytes'], 8)
        self.assertEqual(selected.read_bytes(), content)
        text = json.dumps(report)
        for secret in ('private', str(self.root), 'account.ini', '1.18.00n'):
            self.assertNotIn(secret, text)
        self.assertLess(len(text), 2048)
        self.assertEqual(report['policy'], 'fixed_viewer_file_metadata_only')

    def test_region_selects_exact_live_components_and_only_verified_cache(self):
        fixtures = {
            'viewer/com/app.dll': b'app',
            'viewer/com/polcore.dll': b'core-us-jp',
            'viewer/com/polcoreeu.dll': b'core-eu',
            'viewer/contents/polcontentsINT.dll': b'contents-int',
            'viewer/contents/PolContents.dll': b'contents-jp',
            'patchfiles/PlayOnlineViewer/viewer/com/polcore.dll': b'cached-core',
        }
        for name, content in fixtures.items():
            self.file(name, content)
        for region, core, contents in [('US', b'core-us-jp', b'contents-int'),
                                       ('EU', b'core-eu', b'contents-int'),
                                       ('JP', b'core-us-jp', b'contents-jp')]:
            with self.subTest(region=region):
                rows = inventory.snapshot(self.root, region)['files']
                self.assertEqual(rows['viewer_core']['sha256'], hashlib.sha256(core).hexdigest())
                self.assertEqual(rows['viewer_contents']['sha256'], hashlib.sha256(contents).hexdigest())
                self.assertEqual('cached_viewer_core' in rows, region == 'US')

    def test_replacement_is_observable_but_never_called_complete(self):
        executable = self.file('pol.exe', b'before')
        before = inventory.snapshot(self.root, 'US')
        executable.write_bytes(b'after')
        after = inventory.snapshot(self.root, 'US')
        self.assertNotEqual(before['files']['viewer_executable'], after['files']['viewer_executable'])
        self.assertNotIn('complete', json.dumps(after))
        self.assertNotIn('verified', json.dumps(after))

    def test_rejects_links_case_ambiguity_directories_and_pipes(self):
        target = self.file('private-source', b'private')
        (self.root / 'pol.exe').symlink_to(target)
        self.file('patch.ver')
        self.file('PATCH.VER')
        (self.root / 'patch.ini').mkdir()
        os.mkfifo(self.root / 'version.dat')
        (self.root / 'viewer').symlink_to(self.root, target_is_directory=True)
        rows = inventory.snapshot(self.root, 'US')['files']
        self.assertEqual(rows['viewer_executable'], {'state': 'unsafe_path'})
        self.assertEqual(rows['viewer_patch_version'], {'state': 'ambiguous_path'})
        self.assertEqual(rows['viewer_patch_settings'], {'state': 'not_regular'})
        self.assertEqual(rows['viewer_version_data'], {'state': 'not_regular'})
        self.assertEqual(rows['viewer_core'], {'state': 'unsafe_path'})
        parent_link = Path(self.temporary.name) / 'alias'
        parent_link.symlink_to(self.root, target_is_directory=True)
        # An intermediate ancestor is rejected, not just the final component.
        linked = inventory.snapshot(parent_link / 'patch.ini', 'US')
        self.assertEqual({v['state'] for v in linked['files'].values()}, {'unsafe_path'})

    def test_size_time_and_directory_budgets_emit_bounded_states(self):
        self.file('pol.exe', b'x' * 21)
        with patch.object(inventory, 'MAX_FILE_BYTES', 20):
            row = inventory.snapshot(self.root, 'US')['files']['viewer_executable']
        self.assertEqual(row, {'state': 'too_large', 'bytes': 21})
        with patch.object(inventory, 'TIME_BUDGET_SECONDS', 0):
            report = inventory.snapshot(self.root, 'US')
        self.assertEqual({v['state'] for v in report['files'].values()}, {'budget_exhausted'})
        self.file('ignored-one')
        with patch.object(inventory, 'MAX_DIRECTORY_ENTRIES', 1):
            report = inventory.snapshot(self.root, 'US')
        self.assertEqual({v['state'] for v in report['files'].values()}, {'directory_limit'})

    def test_concurrent_file_change_withholds_digest(self):
        executable = self.file('pol.exe', b'initial')
        real_read = os.read
        changed = False

        def changed_read(descriptor, count):
            nonlocal changed
            block = real_read(descriptor, count)
            if block and not changed:
                changed = True
                executable.write_bytes(b'replaced-with-longer-bytes')
            return block

        with patch.object(inventory.os, 'read', side_effect=changed_read):
            row = inventory.snapshot(self.root, 'US')['files']['viewer_executable']
        self.assertEqual(row, {'state': 'changed_during_read'})

    def test_unavailable_root_region_and_error_text_do_not_escape(self):
        report = inventory.snapshot(self.root / 'missing', 'US')
        self.assertEqual({v['state'] for v in report['files'].values()}, {'missing'})
        report = inventory.snapshot(self.root, 'private-region')
        self.assertEqual({v['state'] for v in report['files'].values()}, {'unsupported_region'})
        with patch.object(inventory.os, 'open', side_effect=PermissionError(13, 'private-password')):
            report = inventory.snapshot(self.root, 'US')
        self.assertEqual({v['state'] for v in report['files'].values()}, {'unreadable'})
        self.assertNotIn('private', json.dumps(report))


if __name__ == '__main__':
    unittest.main()
