#!/usr/bin/env python3
"""Fetch hash-pinned open runtime components; no game files or user data."""
import hashlib, json, pathlib, urllib.request, zipfile, tarfile, argparse, shutil
ROOT=pathlib.Path(__file__).resolve().parents[1]
OUT=ROOT/'out/runtime-assets'
CACHE=ROOT/'.tools/runtime'
PIN={
 'apk':('https://github.com/Russianranger/trasc-server-android/releases/download/preview/trasc-server-android-preview.apk','674e9d6d37e471e50288523cacd33b5f9d2a0f72223b549e33037135fed81626'),
 'rootfs':('https://github.com/Russianranger/trasc-server-android/releases/download/client-runtime-v1/client-runtime-arm64.tar.gz','08c639c26506dc6fbd15464bec475337087bb23cb7c0c5ace2db5240ee36424f'),
 'runtime-sources':('https://github.com/Russianranger/trasc-server-android/releases/download/client-runtime-v1/client-runtime-sources.tar.gz','343281a472e87e31df724605af40c14557270a597c5fc9ec08e8848a8f2d7753'),
 'launcher-sources':('https://github.com/Russianranger/trasc-server-android/releases/download/preview/launcher-sources.tar.gz','217983cd8480fb2106c77713e5d9248e09b3f530519db5e639be20759424a9dd'),
}
# The LSB mirror is immutable for this candidate; source URLs are bootstrap fallbacks.
MIRROR='https://github.com/Russianranger/lsb-android/releases/download/runtime-probe-v1/'
NAMES={'apk':'components.apk','rootfs':'runtime-arm64.tar.gz','runtime-sources':'runtime-sources.tar.gz','launcher-sources':'launcher-sources.tar.gz'}
def digest(path):
 with path.open('rb') as f: return hashlib.file_digest(f,'sha256').hexdigest()
def fetch(name):
 CACHE.mkdir(parents=True,exist_ok=True); target=CACHE/NAMES[name]
 if target.exists() and digest(target)==PIN[name][1]:return target
 for url in (MIRROR+NAMES[name], PIN[name][0]):
  try:
   with urllib.request.urlopen(url,timeout=90) as r, target.with_suffix('.part').open('wb') as w:shutil.copyfileobj(r,w,1024*1024)
   if digest(target.with_suffix('.part'))!=PIN[name][1]:raise ValueError('Checksum mismatch for '+name)
   target.with_suffix('.part').replace(target);return target
  except Exception as e:
   target.with_suffix('.part').unlink(missing_ok=True);last=e
 raise RuntimeError('Cannot retrieve pinned '+name+': '+str(last))
def main():
 import subprocess
 subprocess.run(["python3",str(ROOT/"scripts/build-gamepad.py")],check=True)
 p=argparse.ArgumentParser();p.add_argument('--release',action='store_true');a=p.parse_args()
 OUT.mkdir(parents=True,exist_ok=True)
 names=['turnip.so','turnip-26.0.0.so','vulkan-probe','libasound_module_pcm_trasc.so','audio-bundle.json','wineserver','wineserver-patch.json']
 with zipfile.ZipFile(fetch('apk')) as z:
  for n in names:(OUT/n).write_bytes(z.read('assets/'+n))
  for n in ['libproot.so','libproot-loader.so']:
   target=ROOT/'out/runtime-libs/arm64-v8a'/n;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read('lib/arm64-v8a/'+n))
 # DXVK contains the matching D3D8+D3D9 pair. Verified release checksum is supplied below.
 dxvk=CACHE/'dxvk-2.5.3.tar.gz'
 expected='d8e6ef7d1168095165e1f8a98c7d5a4485b080467bb573d2a9ef3e3d79ea1eb8'
 if not dxvk.exists() or digest(dxvk)!=expected:
  with urllib.request.urlopen('https://github.com/doitsujin/dxvk/releases/download/v2.5.3/dxvk-2.5.3.tar.gz',timeout=90) as r,dxvk.open('wb') as w:shutil.copyfileobj(r,w)
 if digest(dxvk)!=expected:raise ValueError('DXVK release checksum mismatch')
 with tarfile.open(dxvk) as t:
  for n in ['d3d8','d3d9']:(OUT/('dxvk-'+n+'.dll')).write_bytes(t.extractfile('dxvk-2.5.3/x32/'+n+'.dll').read())
 files={f.name:digest(f) for f in OUT.iterdir() if f.is_file() and f.name!='bundle.json'}
 (OUT/'bundle.json').write_text(json.dumps({'format':1,'candidate':'wine10-box64-0.4.4','dxvk':'2.5.3','files':files},indent=2)+'\n')
 if a.release:
  for n in PIN:fetch(n)
 packaged=ROOT/'out/runtime-packaged-assets/runtime';packaged.mkdir(parents=True,exist_ok=True)
 for folder in (OUT, ROOT/'runtime', ROOT/'out/runtime-probes'):
  if folder.exists():
   for f in folder.iterdir():
    if f.is_file():shutil.copyfile(f,packaged/f.name)
 server=ROOT/'out/server-packaged-assets/server';server.mkdir(parents=True,exist_ok=True)
 for f in (ROOT/'server').iterdir():
  if f.is_file():shutil.copyfile(f,server/f.name)
 print('Verified runtime assets:',len(files))
if __name__=='__main__':main()
