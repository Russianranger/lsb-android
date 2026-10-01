#!/usr/bin/env python3
"""Build the ARM64 guest capture helper or Android Surface JNI library."""
import argparse,hashlib,json,os,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('target',choices=['guest','android']);p.add_argument('--ndk',type=Path);args=p.parse_args()
def run(*cmd):return subprocess.check_output(list(map(str,cmd)),cwd=root,text=True).strip()
if args.target=='guest':
    out=root/'out/presentation';out.mkdir(parents=True,exist_ok=True)
    subprocess.run(['docker','build','-t','lsb-presentation:1',str(root/'native/presentation')],check=True)
    container=run('docker','create','lsb-presentation:1')
    try:
        for name in ['x11-frame-bridge','presentation-notices.txt','liblsb-x11-upload.so','x11-upload-check']:run('docker','cp',container+':/out/'+name,out/name)
    finally:run('docker','rm',container)
    (out/'presentation-bundle.json').write_text(json.dumps({'format':1,'upload_files':{n:hashlib.sha256((out/n).read_bytes()).hexdigest() for n in ['liblsb-x11-upload.so','x11-upload-check']},'sha256':hashlib.sha256((out/'x11-frame-bridge').read_bytes()).hexdigest()})+'\n')
else:
    ndk=args.ndk or Path(os.environ.get('ANDROID_HOME',''))/'ndk/27.2.12479018'
    cc=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin/aarch64-linux-android26-clang'
    if not cc.is_file():raise SystemExit('Install Android NDK 27.2.12479018 or pass --ndk.')
    out=root/'out/runtime-libs/arm64-v8a';out.mkdir(parents=True,exist_ok=True)
    subprocess.run([str(cc),'-shared','-fPIC','-std=c11','-O2','-Wall','-Wextra','-Werror',str(root/'native/presentation/android-surface.c'),'-landroid','-Wl,-z,max-page-size=16384','-o',str(out/'liblsb-presentation.so')],check=True)
