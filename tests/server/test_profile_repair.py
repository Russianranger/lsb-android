"""A missing fifth service can be built without replacing the active player database."""
import contextlib
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

spec=importlib.util.spec_from_file_location('profile_repair_manager',Path(__file__).resolve().parents[2]/'server/manager.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

def elf(name):
    header=bytearray(64);header[:6]=b'\x7fELF\x02\x01';header[18:20]=(183).to_bytes(2,'little')
    return bytes(header)+name.encode()

class ProfileRepairTests(unittest.TestCase):
    def setUp(self):
        temporary=tempfile.TemporaryDirectory();self.addCleanup(temporary.cleanup);self.home=Path(temporary.name)
        self.stack=contextlib.ExitStack();self.addCleanup(self.stack.close)
        self.stack.enter_context(mock.patch.multiple(m,STATE=self.home/'state',RUN=self.home/'run',LOGS=self.home/'logs',INPUT=self.home/'input'))
        for folder in (m.STATE,m.RUN,m.LOGS,m.INPUT):folder.mkdir()
        self.generation=m.STATE/'generations/11111111-1111-1111-1111-111111111111';self.root=self.generation/'server'
        for name in ('src/profile','sql','settings/default','tools','navmeshes','ximeshes'):(self.root/name).mkdir(parents=True)
        (self.root/'CMakeLists.txt').write_text('project(profile_fixture C CXX)\n')
        (self.root/'src/profile/CMakeLists.txt').write_text('xi_add_executable(xi_profile profileserver.rc main.cpp)\n')
        (self.root/'src/profile/main.cpp').write_text('int main(){return 0;}\n')
        (self.root/'settings/default/login.lua').write_text("CLIENT_VER = '30260904_1',\n")
        (self.root/'settings/network.lua').write_text('existing networking settings\n')
        for name in m.PROCESSES:(self.root/name).write_bytes(elf(name))
        for name in ('navmeshes/a','ximeshes/b'):(self.root/name).write_bytes(b'large runtime map fixture')
        database=self.generation/'mysql';database.mkdir();(database/'accounts.ibd').write_bytes(b'untouched account rows')
        (m.STATE/'import.sql').write_bytes(b'unrelated staged database import')
        self.original_build=dict(build_id='existing-jemalloc-build',jobs=3,allocator='jemalloc',binaries={n:{'sha256':m.file_sha256(self.root/n)} for n in m.PROCESSES})
        self.info=dict(generation=self.generation.name,database='xidb',accounts=3,characters=3,build_id='existing-jemalloc-build',build=self.original_build,
                       binaries={name:m.file_sha256(self.root/name) for name in m.PROCESSES})
        m.atomic(self.generation/'deployment.json',self.info);m.atomic(m.STATE/'active.json',{'current':self.generation.name,'previous':'previous-server'})
        build_path=m.STATE/'source-build/server';build_path.mkdir(parents=True);m.atomic(build_path/'android-build.json',self.original_build)
        self.before=self.snapshot(m.STATE)
        for name in ('start_database','stop_database','import_database','sql','write_network','credentials','dump_database'):
            self.stack.enter_context(mock.patch.object(m,name,side_effect=AssertionError('Profile repair must not touch database: '+name)))
        self.commands=[]
        self.stack.enter_context(mock.patch.object(m,'command',side_effect=self.compile))
        self.stack.enter_context(mock.patch.object(m.subprocess,'run',side_effect=self.loader))

    @staticmethod
    def snapshot(root):return {p.relative_to(root).as_posix():p.read_bytes() for p in root.rglob('*') if p.is_file()}
    def compile(self,args,**kwargs):
        self.commands.append(args)
        if args[:2]==['cmake','--build']:
            self.assertEqual(args[args.index('--target')+1:],['xi_profile'])
            root=Path(args[2]).parent
            self.assertFalse((root/'navmeshes').exists());self.assertFalse((root/'ximeshes').exists())
            self.assertTrue(all(not (root/name).exists() for name in m.PROCESSES))
            (root/'xi_profile').write_bytes(elf('new profile jemalloc'))
    def loader(self,args,**kwargs):
        if args[0]=='ldd':return subprocess.CompletedProcess(args,0,'libjemalloc.so.2 => /usr/lib/libjemalloc.so.2\nlibc.so.6 => /usr/lib/libc.so.6\n','')
        if args[0]=='readelf':return subprocess.CompletedProcess(args,0,'(NEEDED) [libjemalloc.so.2]\n(NEEDED) [libc.so.6]\n','')
        self.fail(str(args))
    def request(self,**kwargs):return dict({'generation':self.generation.name},**kwargs)
    def assert_existing_preserved(self):
        after=self.snapshot(m.STATE)
        metadata='generations/'+self.generation.name+'/deployment.json'
        for name,content in self.before.items():
            if name!=metadata:self.assertEqual(after[name],content,name)
        self.assertEqual(json.loads((self.generation/'deployment.json').read_text())['build'],self.original_build)
    def test_repairs_only_profile_with_retained_jobs_and_jemalloc_without_database_access(self):
        m.repair_profile(self.request());self.assert_existing_preserved()
        compile=next(args for args in self.commands if args[:2]==['cmake','--build'])
        self.assertEqual(compile[compile.index('--parallel')+1],'3')
        info=json.loads((self.generation/'deployment.json').read_text())
        self.assertEqual(set(info['binaries']),set(m.ALL_PROCESSES));self.assertEqual(info['binaries']['xi_profile'],m.file_sha256(self.root/'xi_profile'))
        self.assertEqual(info['profile_repair']['original_build_id'],'existing-jemalloc-build');self.assertEqual(info['profile_repair']['allocator'],'jemalloc')
        self.assertFalse((self.generation/'profile-repair-pending.json').exists())
        self.assertTrue((m.LOGS/'profile-repair-report.json').exists());self.assertFalse((m.LOGS/'build-report.json').exists())
        before=self.snapshot(m.STATE);self.commands.clear();m.repair_profile(self.request(jobs=1))
        self.assertFalse(self.commands);self.assertEqual(before,self.snapshot(m.STATE))
    def test_generation_source_binary_change_or_compiler_failure_cannot_install(self):
        with self.assertRaisesRegex(ValueError,'active deployment changed'):m.repair_profile(self.request(generation='wrong'))
        failures=(RuntimeError('compiler failed'),InterruptedError('cancelled'))
        for failure in failures:
            with self.subTest(error=str(failure)),mock.patch.object(m,'build',side_effect=failure):
                with self.assertRaises(type(failure)):m.repair_profile(self.request(jobs=2))
            self.assertFalse((self.root/'xi_profile').exists());self.assert_existing_preserved();self.assertEqual(json.loads((self.generation/'deployment.json').read_text()),self.info)
        real=self.compile
        def change(args,**kwargs):
            real(args,**kwargs)
            if args[:2]==['cmake','--build']:(self.root/'src/profile/main.cpp').write_text('externally changed source')
        with mock.patch.object(m,'command',side_effect=change),self.assertRaisesRegex(ValueError,'Active deployment changed'):m.repair_profile(self.request())
        self.assertFalse((self.root/'xi_profile').exists())
    def test_binary_change_missing_jemalloc_and_symlinks_are_rejected(self):
        (self.root/'xi_world').write_bytes(elf('changed'))
        with self.assertRaisesRegex(ValueError,'binaries do not match'):m.repair_profile(self.request())
        (self.root/'xi_world').write_bytes(elf('xi_world'))
        real=self.loader
        def missing(args,**kwargs):
            if args[0]=='readelf':return subprocess.CompletedProcess(args,0,'(NEEDED) [libc.so.6]','')
            return real(args,**kwargs)
        with mock.patch.object(m.subprocess,'run',side_effect=missing),self.assertRaisesRegex(RuntimeError,'without a shared jemalloc'):m.repair_profile(self.request())
        self.assertFalse((self.root/'xi_profile').exists());self.assert_existing_preserved()
        (self.root/'src/link').symlink_to(self.home)
        with self.assertRaisesRegex(ValueError,'symbolic link'):m.repair_profile(self.request())
    def test_recovery_completes_only_validated_profile_and_metadata_after_publication_crash(self):
        class Crash(BaseException):pass
        real=m.atomic
        def crash(path,value):
            if path==self.generation/'deployment.json':raise Crash()
            real(path,value)
        with mock.patch.object(m,'atomic',side_effect=crash),self.assertRaises(Crash):m.repair_profile(self.request())
        self.assertTrue((self.root/'xi_profile').exists());self.assertTrue((self.generation/'profile-repair-pending.json').exists())
        self.assertEqual(json.loads((self.generation/'deployment.json').read_text()),self.info)
        m.recover_profile_repair(self.generation);self.assert_existing_preserved()
        self.assertFalse((self.generation/'profile-repair-pending.json').exists())
        self.assertEqual(json.loads((self.generation/'deployment.json').read_text())['binaries']['xi_profile'],m.file_sha256(self.root/'xi_profile'))
    def test_recovery_before_binary_publish_refuses_changed_existing_files(self):
        class Crash(BaseException):pass
        original=m.recover_profile_repair;count=[0]
        def crash(generation):
            count[0]+=1
            if count[0]==2:raise Crash()
            original(generation)
        with mock.patch.object(m,'recover_profile_repair',side_effect=crash),self.assertRaises(Crash):m.repair_profile(self.request())
        self.assertFalse((self.root/'xi_profile').exists())
        (self.root/'xi_connect').write_bytes(elf('changed after journal'))
        with self.assertRaisesRegex(ValueError,'existing server binary changed'):m.recover_profile_repair(self.generation)
        self.assertFalse((self.root/'xi_profile').exists());self.assertEqual(json.loads((self.generation/'deployment.json').read_text()),self.info)

if __name__=='__main__':unittest.main()
