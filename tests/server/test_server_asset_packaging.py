from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
from server_assets import stage_server_assets


class ServerAssetPackagingTest(unittest.TestCase):
    def test_nested_payload_is_exact_and_repeatable(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            files = {
                'runtime/proot_preflight.py': b'preflight',
                'server/manager.py': b'manager',
                'server/solo_world/patches/squad.patch': b'patch',
                'server/solo_world/__init__.py': b'',
                'server/solo_world/__pycache__/auction.cpython-312.pyc': b'cache',
                'server/solo_world/modules/squad/sql/squad.sql': b'schema',
            }
            for name, content in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
            stage = stage_server_assets(root)
            (stage / 'retired.sql').write_text('obsolete')
            stage_server_assets(root)
            expected = {name.removeprefix('server/'): content for name, content in files.items()
                        if name.startswith('server/') and '__pycache__' not in name}
            expected['proot_preflight.py'] = b'preflight'
            actual = {path.relative_to(stage).as_posix(): path.read_bytes()
                      for path in stage.rglob('*') if path.is_file()}
            self.assertEqual(expected, actual)
