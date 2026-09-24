import importlib.util
from pathlib import Path
import tempfile, unittest
import hashlib, io, json, subprocess, sys, types
from unittest import mock
spec=importlib.util.spec_from_file_location('manager',Path(__file__).resolve().parents[2]/'server/manager.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class ServerTests(unittest.TestCase):
    def setUp(self):
        self.globals=mock.patch.multiple(m,STATE=m.STATE,RUN=m.RUN,LOGS=m.LOGS,INPUT=m.INPUT)
        self.globals.start();self.addCleanup(self.globals.stop)
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
    def test_eight_wrapper_source_and_prebuilt_outputs(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);m.RUN=root
            source=root/'input'/'a/b/c/d/e/f/g/server'
            for folder in ('src','sql','settings','tools','build/bin'):(source/folder).mkdir(parents=True)
            (source/'CMakeLists.txt').write_text('project(server)')
            for name in m.PROCESSES:(source/'build/bin'/name).write_bytes(b'prebuilt '+name.encode())
            self.assertEqual(m.source_root(root/'input'),source)
            with mock.patch.object(m,'command'):m.snapshot_source(source,root/'staged')
            for name in m.PROCESSES:self.assertEqual((root/'staged'/name).read_bytes(),(source/'build/bin'/name).read_bytes())
            self.assertFalse((root/'staged/build').exists())
            (source/'build/xi_map').write_bytes(b'ambiguous')
            with self.assertRaisesRegex(ValueError,'Multiple build outputs'):
                with mock.patch.object(m,'command'):m.snapshot_source(source,root/'ambiguous')
            with mock.patch.object(m,'command'):m.snapshot_source(source,root/'rebuild',recover_build_binaries=False)
            self.assertFalse((root/'rebuild/build').exists())
            self.assertTrue(all(not (root/'rebuild'/name).exists() for name in m.PROCESSES))
    def test_requested_build_replaces_old_root_binaries(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);m.RUN=root
            for name in m.PROCESSES:(root/name).write_bytes(b'old')
            def compile(args,**kwargs):
                if args[:2]==['cmake','--build']:
                    self.assertTrue(all(not (root/name).exists() for name in m.PROCESSES))
                    (root/'build/bin').mkdir(parents=True)
                    for index,name in enumerate(m.PROCESSES):
                        # Cover both supported CMake output layouts.
                        (root/name if index==0 else root/'build/bin'/name).write_bytes(b'new')
            with mock.patch.object(m,'status'),mock.patch.object(m,'command',side_effect=compile):m.build(root,2)
            self.assertTrue(all((root/name).read_bytes()==b'new' for name in m.PROCESSES))
    def test_initial_source_snapshot_preserves_nested_runtime_data(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);m.RUN=root;source=root/'source'
            for folder in ('src','sql','tools','settings','data/zones/fixture','scripts/module/data'):(source/folder).mkdir(parents=True)
            (source/'CMakeLists.txt').write_text('project(server)')
            assets={'data/zones/fixture/runtime.bin':b'\x00\xff\x01','scripts/module/data/custom.dat':b'custom asset'}
            for name,value in assets.items():(source/name).write_bytes(value)
            with mock.patch.object(m,'command'):m.snapshot_source(source,root/'staged')
            for name,value in assets.items():
                self.assertEqual((root/'staged'/name).read_bytes(),value)
                self.assertEqual((source/name).read_bytes(),value)
    def test_restore_snapshot_keeps_assets_and_checks_links(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);m.RUN=root;source=root/'server'
            for folder in ('data','scripts','build','.venv'):(source/folder).mkdir(parents=True)
            (source/'data/asset').write_bytes(b'asset');(source/'scripts/custom.lua').write_text('custom')
            (source/'build/stale').write_text('old');m.snapshot_deployment(source,root/'copy')
            self.assertEqual((root/'copy/data/asset').read_bytes(),b'asset')
            self.assertEqual((root/'copy/scripts/custom.lua').read_text(),'custom')
            self.assertFalse((root/'copy/build').exists());self.assertFalse((root/'copy/.venv').exists())
            (source/'escape').symlink_to(root/'copy/data/asset')
            with self.assertRaises(ValueError):m.snapshot_deployment(source,root/'bad')
    def test_failed_or_cancelled_dump_keeps_previous_export(self):
        for failure in (RuntimeError('failed dump'),InterruptedError('cancelled')):
            with self.subTest(failure=type(failure).__name__),tempfile.TemporaryDirectory() as d:
                root=Path(d);m.RUN=root;generation=root/'generation';generation.mkdir()
                (generation/'deployment.json').write_text('{"database":"xidb"}')
                target=root/'export.sql';target.write_bytes(b'last good export')
                def fail(args,stdout=None,**kwargs):stdout.write(b'partial');raise failure
                with mock.patch.object(m,'start_database',return_value={'root':'secret'}),mock.patch.object(m,'stop_database') as stop,mock.patch.object(m,'command',side_effect=fail):
                    with self.assertRaises(type(failure)):m.dump_database(generation,target)
                self.assertEqual(target.read_bytes(),b'last good export');stop.assert_called_once()
                self.assertFalse(list(root.glob('export.sql.*.part')))
    def test_successful_dump_replaces_export_after_shutdown(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);m.RUN=root;generation=root/'generation';generation.mkdir()
            (generation/'deployment.json').write_text('{"database":"xidb"}')
            target=root/'export.sql';target.write_bytes(b'old')
            def dump(args,stdout=None,**kwargs):
                self.assertIn('--routines',args);self.assertIn('--events',args);self.assertIn('--triggers',args)
                stdout.write(b'complete dump')
            def stop(creds):self.assertEqual(target.read_bytes(),b'old')
            with mock.patch.object(m,'start_database',return_value={'root':'secret'}),mock.patch.object(m,'stop_database',side_effect=stop),mock.patch.object(m,'command',side_effect=dump):m.dump_database(generation,target)
            self.assertEqual(target.read_bytes(),b'complete dump');self.assertFalse(list(root.glob('*.part')))
    def test_restore_metadata_failure_or_stop_keeps_active_pair(self):
        for failure in ('metadata','stop'):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as d:
                root=Path(d);m.STATE=root/'state';m.RUN=root/'run';m.RUN.mkdir();old=m.STATE/'generations/old';server=old/'server';server.mkdir(parents=True)
                for name in m.PROCESSES:(server/name).write_bytes(name.encode())
                original={'database':'xidb','local_zones':False,'binaries':{name:hashlib.sha256((server/name).read_bytes()).hexdigest() for name in m.PROCESSES}}
                (old/'deployment.json').write_text(json.dumps(original));(m.STATE/'active.json').write_text('{"current":"old","previous":null}');(m.STATE/'import.sql').write_text('fixture')
                pointer=(m.STATE/'active.json').read_bytes();real_atomic=m.atomic
                def atomic(path,value):
                    if failure=='metadata' and path.name=='deployment.json':raise OSError('disk full')
                    real_atomic(path,value)
                def stop(creds):
                    if failure=='stop':(m.RUN/'stop').touch()
                with mock.patch.object(m,'status'),mock.patch.object(m,'validate_binaries'),mock.patch.object(m,'import_database',return_value={'game':'secret'}),mock.patch.object(m,'account_counts',return_value={'accounts':2,'chars':3}),mock.patch.object(m,'write_network'),mock.patch.object(m,'sql') as sql,mock.patch.object(m,'stop_database',side_effect=stop) as shutdown,mock.patch.object(m,'atomic',side_effect=atomic):
                    with self.assertRaises(OSError if failure=='metadata' else InterruptedError):m.restore_database({'local_zones':True})
                    shutdown.assert_called_once();sql.assert_not_called()
                self.assertEqual((m.STATE/'active.json').read_bytes(),pointer)
                self.assertEqual(json.loads((old/'deployment.json').read_text()),original)
                self.assertTrue(all((server/name).read_bytes()==name.encode() for name in m.PROCESSES))
    def test_shutdown_failure_cleans_children_without_rollback_claim(self):
        for outcome in (subprocess.CompletedProcess([],1),subprocess.TimeoutExpired('mariadb-admin',30)):
            with self.subTest(outcome=type(outcome).__name__),tempfile.TemporaryDirectory() as d:
                m.RUN=Path(d)
                with mock.patch.object(m.subprocess,'run',side_effect=outcome if isinstance(outcome,Exception) else None,return_value=outcome),mock.patch.object(m,'stop_children') as stop:
                    with self.assertRaises(RuntimeError) as error:m.stop_database({'root':'test-secret'})
                stop.assert_called_once();self.assertNotIn('kept',str(error.exception));self.assertNotIn('test-secret',str(error.exception))
    def test_account_late_failure_or_cancel_never_claims_write_was_undone(self):
        for outcome in (OSError('metadata write failed'),InterruptedError('stopped')):
            with self.subTest(outcome=type(outcome).__name__),tempfile.TemporaryDirectory() as d:
                root=Path(d);m.RUN=root/'run';m.STATE=root/'state';m.LOGS=root/'logs';m.RUN.mkdir()
                (m.RUN/'request.json').write_text('{"action":"create-account"}')
                committed=root/'committed'
                def create(*args):committed.touch();raise outcome
                account_module=types.SimpleNamespace(create_account=create)
                with mock.patch.dict(sys.modules,{'accounts':account_module,'manager':m}),mock.patch.object(m,'current',return_value=root),mock.patch.object(sys,'stdin',types.SimpleNamespace(buffer=io.BytesIO())),mock.patch.object(m,'status') as status:
                    if isinstance(outcome,InterruptedError):
                        m.main();self.assertEqual(status.call_args.args[0],'stopped');message=status.call_args.args[1]
                    else:
                        with self.assertRaises(RuntimeError) as error:m.main()
                        message=str(error.exception)
                self.assertTrue(committed.exists());self.assertIn('Check whether the account exists before retrying',message)
                self.assertNotIn('kept',message);self.assertNotIn('rolled back',message)

if __name__=='__main__':unittest.main()
