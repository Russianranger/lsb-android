"""Real ccache hit/invalidation check, using separate disposable workspaces."""
import importlib.util, os, shutil, subprocess, tempfile
from pathlib import Path
spec=importlib.util.spec_from_file_location('cache_manager',Path(__file__).resolve().parents[2]/'server/manager.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
assert shutil.which('ccache') and shutil.which('cc'),'Install ccache and a C compiler for this check'
with tempfile.TemporaryDirectory() as d:
    root=Path(d);os.environ['CCACHE_DIR']=str(root/'cache')
    def compile(name,header=1,flags=('-O2',)):
        work=root/name;work.mkdir();(work/'fixture.h').write_text('#define VALUE '+str(header)+'\n')
        (work/'fixture.c').write_text('#include "fixture.h"\nint fixture(void){return VALUE;}\n')
        _,env,_=m.compiler_cache(work)
        subprocess.run(['ccache','cc',*flags,'-c','fixture.c','-o','fixture.o'],cwd=work,env=env,check=True)
        return m.cache_stats(env),(work/'fixture.o').read_bytes()
    first,object1=compile('first');second,object2=compile('second')
    hits=lambda stats:stats.get('direct_cache_hit',0)+stats.get('preprocessed_cache_hit',0)
    assert hits(second)>hits(first) and object1==object2,(first,second)
    third,object3=compile('third',header=2);assert hits(third)==hits(second) and object3!=object2
    fourth,_=compile('fourth',header=2,flags=('-O0',));assert hits(fourth)==hits(third)
print('PASS: fresh-workspace object reuse; header and compiler flag changes invalidate cached outputs')
