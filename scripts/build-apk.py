#!/usr/bin/env python3
"""Build the dependency-free Android app with standard SDK tools (JDK 17 + SDK 35)."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--android-jar', type=Path, required=True)
p.add_argument('--build-tools', type=Path, required=True)
p.add_argument('--keystore', type=Path, required=True)
p.add_argument('--alias', default='lsb-preview')
p.add_argument('--output', type=Path, default=root/'out/LSB-Android-0.5.32.apk')
args = p.parse_args()
work = root/'out/apk-build'
classes = work/'classes'
dex = work/'dex'
classes.mkdir(parents=True, exist_ok=True)
dex.mkdir(parents=True, exist_ok=True)
for f in classes.rglob('*.class'): f.unlink()
for f in dex.glob('*.dex'): f.unlink()
def run(*cmd): subprocess.run([str(x) for x in cmd], check=True, cwd=root)
run('python3', root/'scripts/build-launcher.py')
run('python3', root/'scripts/prepare-runtime.py')
run('python3', root/'scripts/build-presentation.py', 'android')
if not (root/'out/presentation/x11-frame-bridge').is_file():raise SystemExit('Build the ARM64 guest presentation artifact first.')
java_files = sorted((root/'app/src/main/java').rglob('*.java'))
run('java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '--release', '8', '-cp', args.android_jar, '-d', classes, *java_files)
class_jar = work/'classes.jar'
with zipfile.ZipFile(class_jar, 'w', zipfile.ZIP_DEFLATED) as z:
    for f in classes.rglob('*.class'): z.write(f, f.relative_to(classes).as_posix())
run('java', '-cp', args.build_tools/'lib/d8.jar', 'com.android.tools.r8.D8', '--min-api', '26', '--lib', args.android_jar, '--output', dex, class_jar)
resources=work/'resources.zip'
run(args.build_tools/'aapt2','compile','--dir',root/'app/src/main/res','-o',resources)
unsigned = work/'unsigned.apk'
run(args.build_tools/'aapt2', 'link', '-I', args.android_jar, '--manifest', root/'app/src/main/AndroidManifest.xml', '--min-sdk-version', '26', '--target-sdk-version', '35', resources,'-o', unsigned)
with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(root/'out/launcher-assets/LSB-FFXI.exe', 'assets/LSB-FFXI.exe')
    for folder in ['runtime', 'out/runtime-assets', 'out/runtime-probes', 'out/presentation']:
        for f in sorted((root/folder).iterdir()):
            if f.is_file(): z.write(f, 'assets/runtime/'+f.name)
    for f in sorted((root/'server').glob('*')):
        if f.is_file():z.write(f,'assets/server/'+f.name)
    for f in sorted((root/'out/runtime-libs').rglob('*.so')):
        z.write(f, 'lib/'+f.relative_to(root/'out/runtime-libs').as_posix())
    for f in sorted(dex.glob('*.dex')): z.write(f, f.name)
    for f in sorted((root/'app/src/main/assets').rglob('*')):
        if f.is_file(): z.write(f, 'assets/'+f.relative_to(root/'app/src/main/assets').as_posix())
aligned = work/'aligned.apk'
run(args.build_tools/'zipalign', '-f', '4', unsigned, aligned)
if not args.keystore.exists():
    args.keystore.parent.mkdir(parents=True, exist_ok=True)
    run('keytool', '-genkeypair', '-keystore', args.keystore, '-storepass', 'android', '-keypass', 'android', '-alias', args.alias, '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=LSB Android Preview, O=Russianranger')
args.output.parent.mkdir(parents=True, exist_ok=True)
run('java', '-jar', args.build_tools/'lib/apksigner.jar', 'sign', '--ks', args.keystore, '--ks-key-alias', args.alias, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', args.output, aligned)
run('java', '-jar', args.build_tools/'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', args.output)
digest = hashlib.sha256(args.output.read_bytes()).hexdigest()
args.output.with_suffix('.apk.sha256').write_text(digest+'  '+args.output.name+'\n')
print(f'Built {args.output} ({args.output.stat().st_size} bytes) SHA-256 {digest}')
