import importlib.util, os, tempfile, unittest
from pathlib import Path
from unittest import mock
spec=importlib.util.spec_from_file_location('cache_manager',Path(__file__).resolve().parents[2]/'server/manager.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
class CompilerCacheTests(unittest.TestCase):
    def test_missing_cache_preserves_normal_compilation(self):
        with mock.patch.object(m.shutil,'which',return_value=None):
            options,env,receipt=m.compiler_cache(Path('/unused'))
        self.assertEqual(options,[]);self.assertIsNone(env);self.assertFalse(receipt['enabled']);self.assertIsNone(m.cache_stats(env))
    def test_cache_is_outside_workspace_bounded_and_checks_compiler_contents(self):
        with tempfile.TemporaryDirectory() as d:
            state=Path(d)/'state';build=state/'source-build/server'
            with mock.patch.object(m,'STATE',state),mock.patch.object(m.shutil,'which',return_value='/usr/bin/ccache'),mock.patch.dict(os.environ,{'CCACHE_SLOPPINESS':'time_macros,file_stat_matches','CCACHE_MAXSIZE':'50G'},clear=True):
                options,env,receipt=m.compiler_cache(build)
            self.assertEqual(Path(env['CCACHE_DIR']),state/'compiler-cache');self.assertTrue(Path(env['CCACHE_DIR']).is_dir());self.assertNotIn('CCACHE_SLOPPINESS',env)
            self.assertEqual(env['CCACHE_COMPILERCHECK'],'content');self.assertEqual(env['CCACHE_MAXSIZE'],'2G');self.assertEqual(env['CCACHE_BASEDIR'],str(build));self.assertTrue(receipt['enabled'])
            self.assertTrue(all(option.endswith('=/usr/bin/ccache') for option in options));self.assertTrue(receipt['namespace'].startswith('gcc15-jemalloc-'))
    def test_namespaces_are_shared_across_clean_builds_and_separate_hook_revisions(self):
        with tempfile.TemporaryDirectory() as d,mock.patch.object(m.shutil,'which',return_value='/usr/bin/ccache'),mock.patch.dict(os.environ,{'CCACHE_DIR':str(Path(d)/'cache')},clear=True):
            first=m.compiler_cache(Path(d)/'first')[2]['namespace'];second=m.compiler_cache(Path(d)/'second')[2]['namespace'];self.assertEqual(first,second)
            original=Path.read_bytes
            def changed(path):return original(path)+(b'new jemalloc rule' if path.name=='android-build.cmake' else b'')
            with mock.patch.object(Path,'read_bytes',changed):self.assertNotEqual(first,m.compiler_cache(Path(d)/'third')[2]['namespace'])
    def test_symlink_cache_directory_is_rejected(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d);(root/'real').mkdir();(root/'cache').symlink_to(root/'real',target_is_directory=True)
            with mock.patch.object(m.shutil,'which',return_value='/usr/bin/ccache'),mock.patch.dict(os.environ,{'CCACHE_DIR':str(root/'cache')},clear=True):
                with self.assertRaisesRegex(ValueError,'symlink'):m.compiler_cache(root/'build')
