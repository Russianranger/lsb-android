"""Prove the pinned x87 store changes branch results, then verify preservation.

Both translators retain the CPU feature correction. No client code/assets.
"""
import hashlib,json,os,subprocess,tempfile
from pathlib import Path
wine=Path('/stage/opt/wine');dll=wine/'lib/wine/aarch64-windows/libwow64fex.dll'
fixed=dll.read_bytes();baseline=Path('/pre-flags-wow64fex.dll').read_bytes()
out=Path('/flags-evidence');out.mkdir(exist_ok=True);receipts=[]
try:
    for variant,data,expected in [('before',baseline,21),('after',fixed,0)]:
        dll.write_bytes(data)
        for mode in ('0','1'):
            # Strict64 can reuse the original 64-bit load/store value; its
            # single-precision conversions still expose the clobbered flags.
            failures=(8 if mode=='0' else 4) if variant=='before' else 0
            with tempfile.TemporaryDirectory(prefix='lsb-flags-') as prefix:
                env=dict(os.environ,WINEPREFIX=prefix,WINEARCH='win64',WINEDEBUG='-all',
                    WINEDLLOVERRIDES='winemenubuilder.exe=d',
                    FEX_X87REDUCEDPRECISION=mode,FEX_X87STRICTREDUCEDPRECISION=mode)
                log=out/(variant+'-'+mode+'.log')
                try:
                    with log.open('wb') as f:
                        p=subprocess.run([str(wine/'bin/wine'),'/x87-flags.exe'],env=env,stdout=f,stderr=subprocess.STDOUT,timeout=90)
                finally:
                    subprocess.run([str(wine/'bin/wineserver'),'-k'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=15)
                    subprocess.run([str(wine/'bin/wineserver'),'-w'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=20)
                text=log.read_text(errors='replace');print(text,flush=True)
                assert p.returncode==expected,(variant,mode,p.returncode,expected)
                status='FAIL' if failures else 'PASS'
                assert f'LSB_X87_FLAGS samples=12 failures={failures} {status}' in text,text
                receipts.append(dict(translator=variant,sha256=hashlib.sha256(data).hexdigest(),
                    x87_mode='strict64' if mode=='1' else 'full80',exit=p.returncode,samples=12,failures=failures))
finally:
    dll.write_bytes(fixed)
(out/'x87-flags.json').write_text(json.dumps(receipts,indent=2)+'\n')
print('PASS: x87 store corrupts nonzero branch conditions before fix; all four stores preserve them after fix in both modes',flush=True)
