#!/usr/bin/env python3
"""Build the dependency-free Android app with standard SDK tools (JDK 17 + SDK 35)."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
android = '{http://schemas.android.com/apk/res/android}'
manifest_path = root/'app/src/main/AndroidManifest.xml'
manifest = ET.parse(manifest_path).getroot()
version = manifest.get(android+'versionName')
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--android-jar', type=Path, required=True)
p.add_argument('--build-tools', type=Path, required=True)
p.add_argument('--keystore', type=Path, required=True)
p.add_argument('--alias', default='lsb-preview')
p.add_argument('--output', type=Path, default=root/f'out/LSB-Android-{version}.apk')
p.add_argument('--with-restore-test', action='store_true', help='Also build an isolated LSB Restore Test app from the same compiled code and runtime assets')
p.add_argument('--restore-test-output', type=Path, default=root/f'out/LSB-Android-Restore-Test-{version}.apk')
args = p.parse_args()
if args.with_restore_test and args.output.resolve() == args.restore_test_output.resolve():
    p.error('The normal and restore-test APK outputs must be different files')
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
if not args.keystore.exists():
    args.keystore.parent.mkdir(parents=True, exist_ok=True)
    run('keytool', '-genkeypair', '-keystore', args.keystore, '-storepass', 'android', '-keypass', 'android', '-alias', args.alias, '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=LSB Android Preview, O=Russianranger')

def build_apk(variant, source_manifest, output):
    folder = work/variant
    folder.mkdir(parents=True, exist_ok=True)
    unsigned = folder/'unsigned.apk'
    run(args.build_tools/'aapt2', 'link', '-I', args.android_jar, '--manifest', source_manifest, '--min-sdk-version', '26', '--target-sdk-version', '35', resources, '-o', unsigned)
    with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
        z.write(root/'out/launcher-assets/LSB-FFXI.exe', 'assets/LSB-FFXI.exe')
        for source in ['runtime', 'out/runtime-assets', 'out/runtime-probes', 'out/presentation']:
            for f in sorted((root/source).iterdir()):
                if f.is_file(): z.write(f, 'assets/runtime/'+f.name)
        for f in sorted((root/'server').glob('*')):
            if f.is_file(): z.write(f, 'assets/server/'+f.name)
        for f in sorted((root/'out/runtime-libs').rglob('*.so')):
            z.write(f, 'lib/'+f.relative_to(root/'out/runtime-libs').as_posix())
        for f in sorted(dex.glob('*.dex')): z.write(f, f.name)
        for f in sorted((root/'app/src/main/assets').rglob('*')):
            if f.is_file(): z.write(f, 'assets/'+f.relative_to(root/'app/src/main/assets').as_posix())
    aligned = folder/'aligned.apk'
    run(args.build_tools/'zipalign', '-f', '4', unsigned, aligned)
    output.parent.mkdir(parents=True, exist_ok=True)
    run('java', '-jar', args.build_tools/'lib/apksigner.jar', 'sign', '--ks', args.keystore, '--ks-key-alias', args.alias, '--ks-pass', 'pass:android', '--key-pass', 'pass:android', '--out', output, aligned)
    run('java', '-jar', args.build_tools/'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', output)
    digest = hashlib.sha256(output.read_bytes()).hexdigest()
    output.with_suffix('.apk.sha256').write_text(digest+'  '+output.name+'\n')
    print(f'Built {output} ({output.stat().st_size} bytes) SHA-256 {digest}')


build_apk('standard', manifest_path, args.output)
if args.with_restore_test:
    # Keep Java component and JNI class names unchanged. Android assigns this
    # package its own UID, preferences, private files and external files folder.
    manifest.set('package', manifest.get('package')+'.restoretest')
    overlay = ET.parse(root/'app/src/restoreTest/AndroidManifest.xml').getroot()
    manifest.find('application').set(android+'label', overlay.find('application').get(android+'label'))
    for tag in ('activity', 'service', 'receiver', 'provider'):
        for component in manifest.findall('application/'+tag):
            name = component.get(android+'name', '')
            if name.startswith('.') or '.' not in name:
                raise SystemExit('Restore-test components must have fully qualified Java class names: '+name)
    ET.register_namespace('android', android[1:-1])
    test_manifest = work/'restore-test-manifest.xml'
    ET.ElementTree(manifest).write(test_manifest, encoding='utf-8', xml_declaration=True)
    build_apk('restore-test', test_manifest, args.restore_test_output)
    run('python3', root/'scripts/verify-apk-pair.py', '--aapt2', args.build_tools/'aapt2', '--standard', args.output, '--restore-test', args.restore_test_output)
