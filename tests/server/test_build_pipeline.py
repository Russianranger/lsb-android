"""Exact-build staging, database replacement and activation safety contracts."""
import contextlib
import hashlib
import importlib.util
import json
from pathlib import Path
import runpy
import subprocess
import sys
import tempfile
import types
import unittest
from unittest import mock

spec=importlib.util.spec_from_file_location('pipeline_manager',Path(__file__).resolve().parents[2]/'server/manager.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class BuildPipelineTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup)
        self.root=Path(temporary.name)
        patch=mock.patch.multiple(m,STATE=self.root/'state',RUN=self.root/'run',LOGS=self.root/'logs',INPUT=self.root/'input')
        patch.start();self.addCleanup(patch.stop)
        for path in (m.STATE,m.RUN,m.LOGS,m.INPUT):path.mkdir()
        self.creds={'root':'root-test','game':'game-test'}
        self.counts={'accounts':2,'chars':3}
        self.commands=[]
        self.stack=contextlib.ExitStack();self.addCleanup(self.stack.close)
        self.stack.enter_context(mock.patch.object(m,'command',side_effect=lambda args,**kw:self.commands.append(args)))
        self.stack.enter_context(mock.patch.object(m,'validate_binaries'))
        self.stack.enter_context(mock.patch.object(m,'validate_jemalloc',side_effect=self.binaries))
        self.stack.enter_context(mock.patch.object(m,'start_database',return_value=self.creds))
        self.stack.enter_context(mock.patch.object(m,'stop_database'))
        self.stack.enter_context(mock.patch.object(m,'sql'))
        self.stack.enter_context(mock.patch.object(m,'prepare_database'))
        self.stack.enter_context(mock.patch.object(m,'write_network'))
        self.stack.enter_context(mock.patch.object(m,'account_counts',side_effect=lambda *args:dict(self.counts)))
        self.build=self.make_build()
        old=m.STATE/'generations/old';old.mkdir(parents=True)
        m.atomic(old/'deployment.json',{'database':'xidb'})
        m.atomic(m.STATE/'active.json',{'current':'old','previous':None})
        (m.STATE/'import.sql').write_bytes(b'original imported SQL')
        self.pointer=(m.STATE/'active.json').read_bytes()

    def binaries(self,root):
        return {name:dict(sha256=hashlib.sha256((root/name).read_bytes()).hexdigest(),
                         bytes=(root/name).stat().st_size,needed=['libjemalloc.so.2','libc.so.6'],allocator='libjemalloc.so.2')
                for name in m.PROCESSES}

    def make_build(self):
        root=m.STATE/'source-build/server'
        for folder in ('src','sql','tools','settings/default','scripts','modules'):(root/folder).mkdir(parents=True,exist_ok=True)
        (root/'CMakeLists.txt').write_text('project(server)')
        (root/'settings/default/login.lua').write_text("CLIENT_VER = '30260904_1',")
        (root/'scripts/custom.lua').write_text('built scripts')
        (root/'sql/accounts.sql').write_text('built SQL')
        for name in m.PROCESSES:(root/name).write_bytes(('fresh '+name).encode())
        report=dict(state='passed',allocator='jemalloc',build_id='built-one',jobs=3,source=m.source_info(root),
                    selected_source=dict(repository='owner/server',commit='a'*40,snapshot_id='selected-one',content_sha256='b'*64),
                    binaries=self.binaries(root),content_sha256=m.tree_fingerprint(root),content_fingerprint_version=m.PAYLOAD_FINGERPRINT_VERSION)
        m.atomic(root/'android-build.json',report)
        return report

    def request(self,mode='fresh'):
        return dict(build_id=self.build['build_id'],database_mode=mode,database='xidb',local_zones=True)

    def stage(self,mode='fresh'):
        with mock.patch.object(m,'dump_database',side_effect=lambda previous,target:target.write_bytes(b'active SQL')):
            m.stage_build(self.request(mode))
        info=json.loads((m.STATE/'staged.json').read_text())
        return dict(generation=info['generation'],build_id=info['build_id']),info

    def enable_meshes(self):
        root=m.STATE/'source-build/server'
        (root/'.gitmodules').write_text('[submodule "ximeshes"]\npath = ximeshes\n')
        self.build['content_sha256']=m.tree_fingerprint(root)
        m.atomic(root/'android-build.json',self.build)
        return root

    @contextlib.contextmanager
    def mesh_module(self,ensure=None,validate=None):
        # Exercise the real manager wrapper, with runtime asset acquisition kept
        # tiny and offline. Mesh header/archive safety is tested by that module.
        module=types.SimpleNamespace(ensure=mock.Mock(side_effect=ensure),validate=mock.Mock(side_effect=validate))
        spec=types.SimpleNamespace(loader=mock.Mock())
        with mock.patch.object(m.importlib.util,'spec_from_file_location',return_value=spec),mock.patch.object(m.importlib.util,'module_from_spec',return_value=module):
            yield module

    def test_worker_range_rejects_bool_zero_and_excess(self):
        for jobs in range(1,17):self.assertEqual(m.checked_jobs(jobs),jobs)
        for jobs in (True,False,0,-1,17,1.5,'2',None):
            with self.subTest(jobs=jobs),self.assertRaises(ValueError):m.checked_jobs(jobs)

    def test_stage_uses_exact_completed_build_when_fetched_source_changes(self):
        (m.INPUT/'different-source').write_text('newly fetched revision')
        request,info=self.stage()
        staged=m.STATE/'generations'/request['generation']/'server'
        self.assertEqual((staged/'scripts/custom.lua').read_text(),'built scripts')
        self.assertEqual((staged/'sql/accounts.sql').read_text(),'built SQL')
        self.assertEqual(info['build_id'],'built-one')
        self.assertEqual(info['selected_source']['commit'],'a'*40)
        self.assertEqual(info['database_mode'],'fresh')
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)
        self.assertFalse(any(args and args[0]=='cmake' for args in self.commands))

    def test_wrong_build_or_changed_payload_cannot_stage(self):
        with self.assertRaisesRegex(ValueError,'selected build changed'):m.stage_build(dict(self.request(),build_id='old-build'))
        root=m.STATE/'source-build/server';(root/'scripts/custom.lua').write_text('changed after build')
        with self.assertRaisesRegex(ValueError,'runtime files changed'):m.stage_build(self.request())
        self.assertFalse((m.STATE/'staged.json').exists())
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)

    def test_stage_meshes_are_acquired_before_staged_database_and_hashed_without_changing_build(self):
        build_root=self.enable_meshes();receipt=(build_root/'android-build.json').read_bytes()
        mesh_receipt={'ximeshes':{'revision':'c'*40,'files':1}}
        def ensure(root,identity,**kwargs):
            self.assertNotEqual(root,build_root)
            self.assertEqual(identity,self.build['selected_source'])
            self.assertEqual(m.tree_fingerprint(root),self.build['content_sha256'])
            m.start_database.assert_not_called()
            (root/'ximeshes').mkdir();(root/'ximeshes/fixture.xim').write_bytes(b'tiny map asset')
            return mesh_receipt
        with self.mesh_module(ensure=ensure,validate=lambda root:mesh_receipt) as module:
            request,info=self.stage()
            staged=m.STATE/'generations'/info['generation']/'server'
            self.assertEqual(module.ensure.call_count,1)
            self.assertEqual(module.ensure.call_args.kwargs['cache'],m.STATE/'mesh-cache')
            self.assertEqual(info['mesh_assets'],mesh_receipt)
            self.assertEqual((staged/'ximeshes/fixture.xim').read_bytes(),b'tiny map asset')
            self.assertEqual(info['staged_content_sha256'],m.tree_fingerprint(staged))
            self.assertNotEqual(info['staged_content_sha256'],self.build['content_sha256'])
            self.assertEqual(info['build']['content_sha256'],self.build['content_sha256'])
            self.assertEqual(m.tree_fingerprint(build_root),self.build['content_sha256'])
            self.assertEqual((build_root/'android-build.json').read_bytes(),receipt)
            self.assertFalse((build_root/'ximeshes').exists())
            self.assertTrue(m.start_database.called)
            m.check_staged(request)
            module.validate.assert_called_once_with(staged)
            self.assertEqual(module.ensure.call_count,1)  # Checking never downloads or repairs staged assets.
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)

    def test_mesh_acquisition_cannot_run_before_completed_and_copied_build_verification(self):
        root=self.enable_meshes();script=root/'scripts/custom.lua';original=script.read_bytes()
        snapshot=m.snapshot_source
        def corrupt_copy(source,target,**kwargs):
            snapshot(source,target,**kwargs)
            (target/'scripts/custom.lua').write_text('changed while copying')
        with self.mesh_module() as module:
            script.write_text('changed after compilation')
            with self.assertRaisesRegex(ValueError,'runtime files changed'):m.stage_build(self.request())
            script.write_bytes(original)
            with mock.patch.object(m,'snapshot_source',side_effect=corrupt_copy):
                with self.assertRaisesRegex(ValueError,'Copied build does not match'):m.stage_build(self.request())
            module.ensure.assert_not_called();module.validate.assert_not_called()
        m.start_database.assert_not_called()
        self.assertFalse((m.STATE/'staged.json').exists())
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)

    def test_mesh_preparation_failure_retains_active_previous_stage_and_completed_build(self):
        self.stage();previous=(m.STATE/'staged.json').read_bytes()
        root=self.enable_meshes();receipt=(root/'android-build.json').read_bytes()
        for error in (ValueError('invalid map data'),InterruptedError('stopped')):
            def ensure(staged,identity,**kwargs):
                (staged/'ximeshes').mkdir();(staged/'ximeshes/partial.xim').write_bytes(b'incomplete')
                raise error
            m.start_database.reset_mock()
            with self.subTest(error=type(error).__name__),self.mesh_module(ensure=ensure) as module:
                with self.assertRaises(type(error)):m.stage_build(self.request())
                module.ensure.assert_called_once();module.validate.assert_not_called()
            m.start_database.assert_not_called()
            self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)
            self.assertEqual((m.STATE/'staged.json').read_bytes(),previous)
            self.assertEqual(m.tree_fingerprint(root),self.build['content_sha256'])
            self.assertEqual((root/'android-build.json').read_bytes(),receipt)

    def test_check_invalid_meshes_revokes_prior_check_without_database_or_download(self):
        self.enable_meshes()
        with self.mesh_module(ensure=lambda *args,**kwargs:{},validate=lambda root:{}) as module:
            request,info=self.stage();m.check_staged(request)
            self.assertEqual(json.loads((m.STATE/'staged.json').read_text())['state'],'checked')
            module.ensure.reset_mock();module.validate.reset_mock()
            module.validate.side_effect=ValueError('invalid map header')
            m.start_database.reset_mock();self.commands.clear()
            with self.assertRaisesRegex(ValueError,'invalid map header'):m.check_staged(request)
            module.ensure.assert_not_called()
            module.validate.assert_called_once_with(m.STATE/'generations'/info['generation']/'server')
        m.start_database.assert_not_called();self.assertEqual(self.commands,[])
        failed=json.loads((m.STATE/'staged.json').read_text())
        self.assertEqual(failed['state'],'staged');self.assertNotIn('checked_at',failed)
        self.assertEqual(failed['check_error'],'invalid map header')
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)

    def test_legacy_receipt_adoption_does_not_borrow_current_source_identity(self):
        root=m.STATE/'source-build/server';report=dict(self.build)
        for key in ('build_id','selected_source','content_sha256'):report.pop(key)
        m.atomic(root/'android-build.json',report)
        m.adopt_build({'source_identity':{'repository':'wrong/new-source'}})
        adopted=json.loads((root/'android-build.json').read_text())
        self.assertTrue(adopted['build_id'])
        self.assertNotIn('repository',adopted['selected_source'])
        self.assertIn('not recorded',adopted['selected_source']['origin'])
        self.assertEqual(adopted['binaries'],report['binaries'])
        (root/'xi_map').write_bytes(b'corrupt')
        with self.assertRaisesRegex(ValueError,'binaries changed'):m.adopt_build({})

    def add_asio_cache(self,root):
        # The exact asio-1-38-0 checkout layout fetched by the phone's source
        # revision: these are compiler compatibility links, not runtime assets.
        cache=root/'.cpm-cache/asio/213145964e945b838d29d274e090666d1151b845'
        for folder in ('asio','include','src'):(cache/folder).mkdir(parents=True)
        (cache/'include/asio.hpp').write_text('dependency header')
        (cache/'asio/include').symlink_to('../include',target_is_directory=True)
        (cache/'asio/src').symlink_to('../src',target_is_directory=True)

    def test_successful_compile_with_asio_cache_finalizes_and_stages_exact_build(self):
        root=m.STATE/'source-build/server'
        m.snapshot_source(root,m.INPUT/'selected',recover_build_binaries=False)
        for name in m.PROCESSES:(m.INPUT/'selected'/name).unlink()
        compiles=[]
        def command(args,**kwargs):
            self.commands.append(args)
            if args[:2]==['cmake','--build']:
                compiles.append(args)
                self.add_asio_cache(root)
                for name in m.PROCESSES:(root/name).write_bytes(('new jemalloc '+name).encode())
        with mock.patch.object(m,'command',side_effect=command):
            m.build_source(dict(jobs=2,source_identity=dict(repository='LandSandBoat/server',commit='6'*40)))
        self.build=json.loads((root/'android-build.json').read_text())
        self.assertEqual(self.build['state'],'passed');self.assertTrue(self.build['build_id'])
        self.assertEqual(self.build['selected_source']['commit'],'6'*40)
        self.assertEqual(json.loads((m.RUN/'status.json').read_text())['phase'],'build_ready')
        self.assertEqual(self.build['content_sha256'],m.tree_fingerprint(root))
        self.assertEqual(len(compiles),1)
        self.assertEqual(compiles[0][compiles[0].index('--parallel')+1],'2')
        request,info=self.stage();m.check_staged(request)
        staged=m.STATE/'generations'/info['generation']/'server'
        self.assertFalse((staged/'.cpm-cache').exists())
        self.assertEqual(self.binaries(staged),self.build['binaries'])
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)

    def test_phone_bare_passed_receipt_can_adopt_and_stage_without_recompiling(self):
        root=m.STATE/'source-build/server';self.add_asio_cache(root)
        report=dict(self.build)
        for key in ('build_id','selected_source','content_sha256','content_fingerprint_version'):report.pop(key)
        m.atomic(root/'android-build.json',report)
        m.adopt_build({'source_identity':{'repository':'unrelated/new-fetch'}})
        self.build=json.loads((root/'android-build.json').read_text())
        self.assertEqual(self.build['binaries'],report['binaries'])
        self.assertNotIn('repository',self.build['selected_source'])
        self.assertEqual(self.build['content_fingerprint_version'],2)
        request,info=self.stage();m.check_staged(request)
        staged=m.STATE/'generations'/info['generation']/'server'
        self.assertFalse((staged/'.cpm-cache').exists())
        self.assertEqual(self.binaries(staged),report['binaries'])
        self.assertFalse(any(args[0]=='cmake' for args in self.commands))
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)
        (root/'scripts/custom.lua').write_text('changed after adoption')
        with self.assertRaisesRegex(ValueError,'runtime files changed'):m.adopt_build({})

    def test_legacy_complete_receipt_keeps_original_hash_validation(self):
        root=m.STATE/'source-build/server';cache=root/'.cpm-cache/dependency'
        cache.parent.mkdir();cache.write_bytes(b'original dependency')
        report=dict(self.build,content_sha256=m.tree_fingerprint(root,version=1));report.pop('content_fingerprint_version')
        m.atomic(root/'android-build.json',report)
        request,info=self.stage()
        self.assertFalse((m.STATE/'generations'/info['generation']/'server/.cpm-cache').exists())
        self.assertEqual(info['build']['content_fingerprint_version'],2)
        self.assertEqual(json.loads((root/'android-build.json').read_text()),report)
        cache.write_bytes(b'changed old receipt input')
        with self.assertRaisesRegex(ValueError,'runtime files changed'):m.stage_build(self.request())
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)

    def test_legacy_staged_hash_is_not_rebaselined(self):
        request,info=self.stage();generation=m.STATE/'generations'/info['generation'];root=generation/'server'
        cache=root/'.cpm-cache/dependency';cache.parent.mkdir();cache.write_bytes(b'original dependency')
        info.pop('staged_fingerprint_version');info['staged_content_sha256']=m.tree_fingerprint(root,version=1)
        m.write_staged(generation,info);m.check_staged(request)
        cache.write_bytes(b'changed after check')
        with self.assertRaisesRegex(ValueError,'Staged source or runtime files changed'):m.deploy_staged(request)
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)

    def test_new_receipt_ignores_only_root_cache_and_rejects_runtime_symlinks_with_path(self):
        root=m.STATE/'source-build/server';self.add_asio_cache(root)
        m.load_build(self.build['build_id'])
        nested=root/'scripts/.cpm-cache';nested.mkdir();(nested/'asset').symlink_to(root/'sql/accounts.sql')
        with self.assertRaisesRegex(ValueError,r'symlink: scripts/\.cpm-cache/asset'):m.load_build(self.build['build_id'])
        (nested/'asset').unlink();nested.rmdir()
        (root/'scripts/runtime-alias').symlink_to(root/'.cpm-cache',target_is_directory=True)
        with self.assertRaisesRegex(ValueError,'symlink: scripts/runtime-alias'):m.load_build(self.build['build_id'])

    def test_import_and_copy_current_preserve_counts_and_record_input(self):
        for mode in ('import','copy-current'):
            with self.subTest(mode=mode):
                request,info=self.stage(mode)
                self.assertEqual(info['accounts'],2);self.assertEqual(info['characters'],3)
                payload=b'active SQL' if mode=='copy-current' else b'original imported SQL'
                self.assertEqual(info['database_input_sha256'],hashlib.sha256(payload).hexdigest())
                self.assertEqual(info['staged_from_generation'],'old')
                self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)

    def test_database_preparation_failure_or_count_change_retains_active_and_previous_stage(self):
        _,previous=self.stage()
        for failure in ('sql','counts','shutdown','cancel'):
            with self.subTest(failure=failure),contextlib.ExitStack() as patches:
                if failure=='sql':patches.enter_context(mock.patch.object(m,'prepare_database',side_effect=RuntimeError('SQL error')))
                elif failure=='counts':patches.enter_context(mock.patch.object(m,'account_counts',side_effect=[{'accounts':2,'chars':3},{'accounts':1,'chars':3}]))
                elif failure=='shutdown':patches.enter_context(mock.patch.object(m,'stop_database',side_effect=RuntimeError('shutdown failed')))
                else:patches.enter_context(mock.patch.object(m,'stop_database',side_effect=lambda creds:(m.RUN/'stop').touch()))
                with self.assertRaises((RuntimeError,InterruptedError)):m.stage_build(self.request('import'))
                self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)
                self.assertEqual(json.loads((m.STATE/'staged.json').read_text())['generation'],previous['generation'])
            (m.RUN/'stop').unlink(missing_ok=True)

    def test_check_is_required_rechecked_at_deploy_and_retains_rollback(self):
        request,info=self.stage()
        with self.assertRaisesRegex(ValueError,'Check the staged'):m.deploy_staged(request)
        m.check_staged(request)
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)
        self.assertTrue(any(args[0]=='mariadb-check' for args in self.commands))
        m.deploy_staged(request)
        pointer=json.loads((m.STATE/'active.json').read_text())
        self.assertEqual(pointer,{'current':info['generation'],'previous':'old'})
        self.assertTrue((m.STATE/'generations/old/deployment.json').is_file())

    def test_check_and_activation_reject_stale_or_mismatched_artifacts(self):
        request,info=self.stage();m.check_staged(request)
        with self.assertRaisesRegex(ValueError,'does not match'):m.check_staged(dict(request,build_id='different'))
        root=m.STATE/'generations'/info['generation']/'server'
        (root/'xi_map').write_bytes(b'changed after successful check')
        with self.assertRaisesRegex(ValueError,'Staged source or runtime files changed'):m.deploy_staged(request)
        self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)
        failed=json.loads((m.STATE/'staged.json').read_text())
        self.assertEqual(failed['state'],'staged');self.assertNotIn('checked_at',failed)

    def test_check_shutdown_failure_or_cancel_never_activates(self):
        for failure in ('shutdown','cancel'):
            request,info=self.stage();m.check_staged(request)
            effect=RuntimeError('shutdown failed') if failure=='shutdown' else lambda creds:(m.RUN/'stop').touch()
            with self.subTest(failure=failure),mock.patch.object(m,'stop_database',side_effect=effect):
                with self.assertRaises((RuntimeError,InterruptedError)):m.deploy_staged(request)
            self.assertEqual((m.STATE/'active.json').read_bytes(),self.pointer)
            self.assertEqual(json.loads((m.STATE/'staged.json').read_text())['state'],'staged')
            (m.RUN/'stop').unlink(missing_ok=True)

    def test_active_generation_change_and_new_player_progress_require_restage(self):
        request,info=self.stage('copy-current');m.check_staged(request)
        m.invalidate_database_stage('The active server started')
        stale=json.loads((m.STATE/'staged.json').read_text())
        self.assertTrue(stale['stale_database'])
        with self.assertRaisesRegex(ValueError,'active database changed'):m.check_staged(request)
        request,info=self.stage('fresh');m.check_staged(request)
        m.atomic(m.STATE/'active.json',{'current':'another','previous':'old'})
        with self.assertRaisesRegex(ValueError,'active deployment changed'):m.deploy_staged(request)
        self.assertEqual(json.loads((m.STATE/'active.json').read_text())['current'],'another')

class DatabaseWrapperTests(unittest.TestCase):
    @contextlib.contextmanager
    def legacy_account_fixture(self,**overrides):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            for name in ('tools','settings','sql'):(root/name).mkdir()
            (root/'tools/dbtool.py').write_text("""import sys
from pathlib import Path
def main():
    with (Path(__file__).parents[1]/'phases').open('a') as out:out.write(sys.argv[1]+'\\n')
    raise SystemExit(0)
""")
            (root/'sql/accounts.sql').write_text("CREATE TABLE `accounts` (\n  `id` int(10) unsigned NOT NULL DEFAULT '0',\n  PRIMARY KEY (`id`)\n) ENGINE=InnoDB;")
            (root/'sql/accounts_files.sql').write_text('CREATE TABLE `accounts_files` (\n  `accid` int(10) unsigned NOT NULL,\n  FOREIGN KEY (`accid`) REFERENCES `accounts` (`id`)\n) ENGINE=InnoDB;')
            state=dict(column=('int(11)','NO',None,'auto_increment',''),table=('InnoDB',80),
                       primary=[('id',)],references=None,minimum=1,rows=[(1,'alice',b'password1'),(2,'bob',b'password2')],
                       after_corruption=None,alter_error=False,altered=False,queries=[])
            state.update(overrides)
            class FakeCursor:
                def execute(self,query,parameters=None):
                    state['queries'].append((query,parameters));self.rows=[]
                    if 'information_schema.COLUMNS' in query:self.rows=[state['column']]
                    elif 'information_schema.TABLES' in query:self.rows=[state['table']]
                    elif 'information_schema.STATISTICS' in query:self.rows=state['primary']
                    elif 'information_schema.KEY_COLUMN_USAGE' in query:self.rows=[] if state['references'] is None else [state['references']]
                    elif query.startswith('SELECT MIN'):self.rows=[(state['minimum'],)]
                    elif query.startswith('SELECT *'):self.rows=state['rows']
                    elif query.startswith('ALTER TABLE'):
                        if state['alter_error']:raise RuntimeError('ALTER denied')
                        state['altered']=True
                        state['column']=('int(10) unsigned',*state['column'][1:])
                        corruption=state['after_corruption']
                        if corruption=='rows':state['rows']=[(1,'alice',b'changed password'),(2,'bob',b'password2')]
                        elif corruption=='counter':state['table']=('InnoDB',3)
                        elif corruption=='column':state['column']=('int(10) unsigned','NO',None,'','')
                        assert (root/'phases').read_text()=='migrate\n'
                    else:raise AssertionError(query)
                def fetchone(self):return self.rows[0] if self.rows else None
                def fetchall(self):return self.rows
                def __iter__(self):return iter(self.rows)
                def close(self):pass
            connection=mock.Mock();connection.cursor.return_value=FakeCursor()
            module=types.ModuleType('mariadb');module.connect=mock.Mock(return_value=connection)
            module.original_connect=module.connect
            config=root/'config.json';config.write_text(json.dumps(dict(database='xidb',password='secret')))
            script=Path(__file__).resolve().parents[2]/'server/db_update.py'
            with mock.patch.dict(sys.modules,{'mariadb':module}),mock.patch.object(sys,'argv',[str(script),str(root),'/private/staged.sock',str(config)]),mock.patch.object(subprocess,'run'):
                yield root,state,module,lambda:runpy.run_path(str(script),run_name='__main__')

    def test_legacy_account_key_alignment_keeps_rows_counter_and_allocation_policy(self):
        for extra,default in (('auto_increment',None),('',"'0'"),('',None)):
            with self.subTest(extra=extra,default=default),self.legacy_account_fixture(column=('int(11)','NO',default,extra,'')) as (root,state,module,run):
                run()
                alter=[query for query,_ in state['queries'] if query.startswith('ALTER')]
                expected='ALTER TABLE `accounts` MODIFY COLUMN `id` INT UNSIGNED NOT NULL'
                if default is not None:expected+=' DEFAULT 0'
                if extra:expected+=' AUTO_INCREMENT'
                self.assertEqual(alter,[expected])
                self.assertEqual(state['table'],('InnoDB',80))
                self.assertEqual((root/'phases').read_text(),'migrate\nupdate\n')
                module.original_connect.assert_called_once_with(user='lsb',password='secret',database='xidb',unix_socket='/private/staged.sock')
                self.assertFalse(any('foreign_key_checks' in query.lower() or 'DROP' in query.upper() for query,_ in state['queries']))

    def test_legacy_account_alignment_is_noop_for_unsigned_or_missing_accounts(self):
        for column in (('int(10) unsigned','NO',None,'auto_increment',''),None):
            with self.subTest(column=column),self.legacy_account_fixture(column=column) as (root,state,module,run):
                run()
                self.assertFalse(state['altered'])
                self.assertEqual((root/'phases').read_text(),'migrate\nupdate\n')

    def test_legacy_account_alignment_requires_matching_selected_source(self):
        for change in ('missing-child','different-child','inconsistent-parent','unrelated-parent-table','unrelated-child-table'):
            with self.subTest(change=change),self.legacy_account_fixture() as (root,state,module,run):
                if change=='missing-child':(root/'sql/accounts_files.sql').unlink()
                elif change=='different-child':(root/'sql/accounts_files.sql').write_text('CREATE TABLE unrelated (id INT);')
                elif change=='inconsistent-parent':(root/'sql/accounts.sql').write_text('CREATE TABLE `accounts` (`id` bigint unsigned);')
                elif change=='unrelated-parent-table':
                    path=root/'sql/accounts.sql';path.write_text(path.read_text().replace('CREATE TABLE `accounts`','CREATE TABLE `unrelated`'))
                else:
                    path=root/'sql/accounts_files.sql';path.write_text(path.read_text().replace('CREATE TABLE `accounts_files`','CREATE TABLE `unrelated`'))
                if change in ('inconsistent-parent','unrelated-parent-table'):
                    with self.assertRaisesRegex(RuntimeError,'Selected source has incompatible'):run()
                else:run()
                module.original_connect.assert_not_called()
                self.assertFalse(state['altered'])

    def test_unsafe_legacy_account_shapes_fail_before_any_ddl_or_update(self):
        cases=[
            ({'minimum':-1},'negative account IDs'),
            ({'references':('custom_child','accid')},'foreign-key dependencies'),
            ({'primary':[('id',),('login',)]},'primary key'),
            ({'table':('MyISAM',80)},'InnoDB'),
            ({'column':('bigint(20)','NO',None,'auto_increment','')},'unsupported column'),
            ({'column':('int(11)','YES',None,'','')},'unsupported column'),
            ({'column':('int(11)','NO',None,'auto_increment','custom comment')},'unsupported column'),
            ({'column':('int(11)','NO','-1','','')},'unsupported default'),
        ]
        for options,error in cases:
            with self.subTest(options=options),self.legacy_account_fixture(**options) as (root,state,module,run):
                with self.assertRaisesRegex(RuntimeError,error):run()
                self.assertFalse(state['altered'])
                self.assertEqual((root/'phases').read_text(),'migrate\n')

    def test_legacy_account_alignment_rejects_data_or_sequence_changes(self):
        for corruption in ('rows','counter','column'):
            with self.subTest(corruption=corruption),self.legacy_account_fixture(after_corruption=corruption) as (root,state,module,run):
                with self.assertRaisesRegex(RuntimeError,'verification failed'):run()
                self.assertTrue(state['altered'])
                self.assertEqual((root/'phases').read_text(),'migrate\n')

    def test_legacy_account_alter_failure_prevents_upstream_update(self):
        with self.legacy_account_fixture(alter_error=True) as (root,state,module,run):
            with self.assertRaisesRegex(RuntimeError,'ALTER denied'):run()
            self.assertEqual((root/'phases').read_text(),'migrate\n')

    def test_both_update_phases_run_after_successful_exit_and_login_is_preserved(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'tools').mkdir();(root/'settings').mkdir()
            login=root/'settings/login.lua';login.write_text('original client')
            marker=root/'phases'
            code="""import sys
from pathlib import Path
def main():
    marker=Path(__file__).parents[1]/'phases'
    with marker.open('a') as out:out.write(sys.argv[1]+'\\n')
    (Path(__file__).parents[1]/'settings/login.lua').write_text('wrong client')
    raise SystemExit(0)
"""
            (root/'tools/dbtool.py').write_text(code)
            config=root/'config.json';config.write_text(json.dumps(dict(database='xidb',password='secret')))
            module=types.ModuleType('mariadb');module.connect=lambda *args,**kwargs:None
            script=Path(__file__).resolve().parents[2]/'server/db_update.py'
            with mock.patch.dict(sys.modules,{'mariadb':module}),mock.patch.object(sys,'argv',[str(script),str(root),'socket',str(config)]),mock.patch.object(subprocess,'run'):
                runpy.run_path(str(script),run_name='__main__')
            self.assertEqual(marker.read_text(),'migrate\nupdate\n')
            self.assertEqual(login.read_text(),'original client')

    def test_fresh_uses_setup_functions_not_duplicate_create_cli(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'tools').mkdir();(root/'settings').mkdir()
            code="""from pathlib import Path
def mark(value):
    with (Path(__file__).parents[1]/'phases').open('a') as out:out.write(value+'\\n')
def main():raise AssertionError('CLI setup would CREATE DATABASE twice')
def fetch_credentials():mark('credentials')
def fetch_configs():mark('configs')
def fetch_versions():mark('versions')
def write_version(silent=False):
    assert silent
    mark('version')
def setup_db():
    mark('setup')
    write_version()
def run_all_migrations(silent):mark('migrations')
def close():raise SystemExit(0)
"""
            (root/'tools/dbtool.py').write_text(code)
            config=root/'config.json';config.write_text(json.dumps(dict(database='xidb',password='secret',mode='fresh')))
            module=types.ModuleType('mariadb');module.connect=lambda *args,**kwargs:None
            script=Path(__file__).resolve().parents[2]/'server/db_update.py'
            with mock.patch.dict(sys.modules,{'mariadb':module}),mock.patch.object(sys,'argv',[str(script),str(root),'socket',str(config)]),mock.patch.object(subprocess,'run'):
                runpy.run_path(str(script),run_name='__main__')
            self.assertEqual((root/'phases').read_text(),'credentials\nconfigs\nversions\nsetup\nversion\nmigrations\n')

    def test_swallowed_database_connection_and_statement_errors_fail_closed(self):
        for failure in ('connect','execute','executemany'):
            with self.subTest(failure=failure),tempfile.TemporaryDirectory() as directory:
                root=Path(directory);(root/'tools').mkdir();(root/'settings').mkdir()
                (root/'tools/dbtool.py').write_text("""import mariadb
def main():
    try:
        connection=mariadb.connect()
        cursor=connection.cursor()
        cursor.%s('broken')
    except Exception:pass
    raise SystemExit(0)
""" % ('execute' if failure=='connect' else failure))
                config=root/'config.json';config.write_text(json.dumps(dict(database='xidb',password='secret')))
                cursor=mock.Mock()
                getattr(cursor,'execute' if failure=='connect' else failure).side_effect=RuntimeError('SQL failed')
                connection=mock.Mock();connection.cursor.return_value=cursor
                module=types.ModuleType('mariadb')
                module.connect=mock.Mock(side_effect=RuntimeError('connect failed') if failure=='connect' else None,return_value=connection)
                script=Path(__file__).resolve().parents[2]/'server/db_update.py'
                with mock.patch.dict(sys.modules,{'mariadb':module}),mock.patch.object(sys,'argv',[str(script),str(root),'socket',str(config)]),mock.patch.object(subprocess,'run'),self.assertRaisesRegex(RuntimeError,'SQL error'):
                    runpy.run_path(str(script),run_name='__main__')

    def test_upstream_mysql_cli_is_forced_to_private_socket(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);(root/'tools').mkdir();(root/'settings').mkdir()
            (root/'tools/dbtool.py').write_text("""import subprocess
def main():
    subprocess.run(['/usr/bin/mysql','-hlocalhost','-P13306','-ulsb','xidb','-e SELECT 1'],capture_output=True,text=True)
""")
            config=root/'config.json';config.write_text(json.dumps(dict(database='xidb',password='secret')))
            module=types.ModuleType('mariadb');module.connect=lambda *args,**kwargs:None
            script=Path(__file__).resolve().parents[2]/'server/db_update.py'
            with mock.patch.dict(sys.modules,{'mariadb':module}),mock.patch.object(sys,'argv',[str(script),str(root),'/private/staged.sock',str(config)]),mock.patch.object(subprocess,'run',return_value=subprocess.CompletedProcess([],0,'','')) as command:
                runpy.run_path(str(script),run_name='__main__')
            self.assertEqual(command.call_count,2)
            for call in command.call_args_list:
                self.assertEqual(call.args[0][-2:],['--protocol=SOCKET','--socket=/private/staged.sock'])
                self.assertNotIn('-hlocalhost',call.args[0]);self.assertNotIn('-P13306',call.args[0])

if __name__=='__main__':unittest.main()
