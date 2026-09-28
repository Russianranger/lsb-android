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
                    binaries=self.binaries(root),content_sha256=m.tree_fingerprint(root))
        m.atomic(root/'android-build.json',report)
        return report

    def request(self,mode='fresh'):
        return dict(build_id=self.build['build_id'],database_mode=mode,database='xidb',local_zones=True)

    def stage(self,mode='fresh'):
        with mock.patch.object(m,'dump_database',side_effect=lambda previous,target:target.write_bytes(b'active SQL')):
            m.stage_build(self.request(mode))
        info=json.loads((m.STATE/'staged.json').read_text())
        return dict(generation=info['generation'],build_id=info['build_id']),info

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

if __name__=='__main__':unittest.main()
