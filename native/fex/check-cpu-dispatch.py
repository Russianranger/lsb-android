"""Run the same independent PE32 fixture against original and corrected FEX.

Only temporary prefixes and the build container's staged translator are used.
The original guest must expose the captured source-level mismatch; the patched
guest must agree with CPUID and preserve both independent numeric oracles.
"""
import hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path

wine=Path('/stage/opt/wine');dll=wine/'lib/wine/aarch64-windows/libwow64fex.dll'
fixed=dll.read_bytes();baseline=Path('/baseline-wow64fex.dll').read_bytes()
out=Path('/cpu-evidence');out.mkdir(exist_ok=True)
receipts=[]
try:
    for variant,data,expected in [('baseline',baseline,10),('corrected',fixed,0)]:
        dll.write_bytes(data)
        for mode in ('0','1'):
            with tempfile.TemporaryDirectory(prefix='lsb-cpu-') as prefix:
                env=dict(os.environ,WINEPREFIX=prefix,WINEARCH='win64',WINEDEBUG='-all',
                    WINEDLLOVERRIDES='winemenubuilder.exe=d',
                    FEX_X87REDUCEDPRECISION=mode,FEX_X87STRICTREDUCEDPRECISION=mode)
                log=out/(variant+'-'+mode+'.log')
                try:
                    with log.open('wb') as f:
                        p=subprocess.run([str(wine/'bin/wine'),'/cpu-dispatch.exe'],env=env,stdout=f,stderr=subprocess.STDOUT,timeout=90)
                finally:
                    subprocess.run([str(wine/'bin/wineserver'),'-k'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=15)
                    subprocess.run([str(wine/'bin/wineserver'),'-w'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=20)
                text=log.read_text(errors='replace');print(text,flush=True)
                assert p.returncode==expected,(variant,mode,p.returncode,expected)
                assert 'LSB_CPU_MATH samples=72 failures=0 PASS' in text,text
                if variant=='baseline':
                    assert 'cpuid_3dnow=0 api_3dnow=1' in text and 'path=x87-fallback MISMATCH' in text,text
                else:
                    assert 'cpuid_3dnow=0 api_3dnow=0' in text and 'path=sse2 PASS' in text,text
                receipts.append({'translator':variant,'sha256':hashlib.sha256(data).hexdigest(),
                    'x87_mode':'strict64' if mode=='1' else 'full80','exit':p.returncode,
                    'math_samples':72,'math_passed':True,'feature_consistent':variant=='corrected'})
finally:
    dll.write_bytes(fixed)
(out/'cpu-dispatch.json').write_text(json.dumps(receipts,indent=2)+'\n')
print('PASS: original FEX feature mismatch reproduced; corrected FEX selects SSE2; x87/SSE numeric checks pass in both precision modes',flush=True)
