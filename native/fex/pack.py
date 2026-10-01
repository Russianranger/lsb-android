"""Deterministic GNU tar overlay and source archive; compatible with TarExtractor."""
import gzip,hashlib,json,os,tarfile
from pathlib import Path

out=Path('/out');out.mkdir(exist_ok=True)
root=Path('/stage/opt/wine')
def sha(p):
    with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def archive(name,entries):
    with (out/name).open('wb') as raw,gzip.GzipFile(filename='',fileobj=raw,mode='wb',mtime=0,compresslevel=6) as gz,tarfile.open(fileobj=gz,mode='w',format=tarfile.GNU_FORMAT) as tar:
        for p,n in entries:
            info=tar.gettarinfo(str(p),n);info.uid=info.gid=info.mtime=0;info.uname=info.gname=''
            if info.isfile():
                with p.open('rb') as f:tar.addfile(info,f)
            else:tar.addfile(info)
files={p.relative_to(root).as_posix():sha(p) for p in sorted(root.rglob('*')) if p.is_file() and not p.is_symlink()}
m={'format':1,'candidate':'wine10-arm64-fex2510','wine_commit':'b073859675060c9211fcbccfd90e4e87520dc2c2','fex_commit':'320c5f18475b0c8a7e99c51a5fdc5b5e35b147ab','guest':'i386','host':'aarch64','wine_backports':['d53a9ba0cd5ee46852b00e4a106e2eb679b5aa3d'],'files':files}
m['fex_patches']=[{'name':name,'sha256':sha(Path('/recipe',name))} for name in ('cpu-features.patch','x87-flags.patch')]
(root/'lsb-fex.json').write_text(json.dumps(m,sort_keys=True,indent=2)+'\n')
archive('runtime-fex-arm64.tar.gz',[(p,p.relative_to(root).as_posix()) for p in sorted(root.rglob('*'))])
sources=[]
for name in ('wine','fex'):
    for p in sorted(Path('/src',name).rglob('*')):
        if '.git' not in p.parts:sources.append((p,p.relative_to('/src').as_posix()))
for p in sorted(Path('/recipe').rglob('*')):sources.append((p,'recipe/'+p.relative_to('/recipe').as_posix()))
archive('runtime-fex-sources.tar.gz',sources)
p=out/'runtime-fex-arm64.tar.gz'
manifest={k:v for k,v in m.items() if k!='files'}
manifest.update(sha256=sha(p),bytes=p.stat().st_size,manifest_sha256=sha(root/'lsb-fex.json'),url='https://github.com/Russianranger/lsb-android/releases/download/runtime-fex-v3/runtime-fex-arm64.tar.gz')
(out/'fex-bundle.json').write_text(json.dumps(manifest,indent=2)+'\n')
import shutil
shutil.copytree('/cpu-evidence',out/'cpu-evidence',dirs_exist_ok=True)
shutil.copytree('/flags-evidence',out/'flags-evidence',dirs_exist_ok=True)
print(json.dumps(manifest))
