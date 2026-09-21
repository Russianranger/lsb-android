import importlib.util
from pathlib import Path
import tempfile, unittest
spec=importlib.util.spec_from_file_location('manager',Path(__file__).resolve().parents[2]/'server/manager.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class ServerTests(unittest.TestCase):
    def test_database_names(self):
        self.assertEqual(m.checked_name('my_xidb2'),'my_xidb2')
        for name in ('mysql','sys','x;DROP DATABASE a','../x','', '1x', 'a'*49):
            with self.assertRaises(ValueError):m.checked_name(name)
    def test_source_selection_and_version(self):
        with tempfile.TemporaryDirectory() as d:
            top=Path(d);root=top/'wrapper/server'
            for name in ('src','sql','settings/default'):(root/name).mkdir(parents=True)
            (root/'CMakeLists.txt').write_text('project(server)')
            (root/'settings/default/login.lua').write_text("CLIENT_VER = '30260904_1',")
            (root/'settings/login.lua').write_text("CLIENT_VER = '30251204_1',")
            self.assertEqual(m.source_root(top),root)
            self.assertEqual(m.source_info(root)['expected_client'],'30251204_1')
            other=top/'other'
            for name in ('src','sql'):(other/name).mkdir(parents=True)
            (other/'CMakeLists.txt').write_text('project(server)')
            with self.assertRaises(ValueError):m.source_root(top)
    def test_dump_data_not_rewritten(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);m.RUN=root
            original=b"/*!50017 DEFINER=`root`@`localhost`*/\nINSERT INTO notes VALUES ('DEFINER=`root`@`localhost`');\n"
            (root/'source').write_bytes(original);m.clean_dump(root/'source',root/'out')
            self.assertEqual((root/'source').read_bytes(),original)
            self.assertEqual((root/'out').read_bytes(),b"/*!50017 */\nINSERT INTO notes VALUES ('DEFINER=`root`@`localhost`');\n")
    def test_atomic_failure_preserves_pointer(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'active.json';m.atomic(p,{'current':'one','previous':None})
            original=p.read_bytes()
            with self.assertRaises(TypeError):m.atomic(p,{'current':object()})
            self.assertEqual(p.read_bytes(),original)
    def test_external_source_link_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);source=root/'source';source.mkdir();outside=root/'outside';outside.write_text('kept');(source/'escape').symlink_to(outside)
            with self.assertRaises(ValueError):m.snapshot_source(source,root/'target')
            self.assertEqual(outside.read_text(),'kept')

if __name__=='__main__':unittest.main()
