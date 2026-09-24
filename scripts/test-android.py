#!/usr/bin/env python3
"""Run the actual Android display Activity and controller on an API 33 JVM sandbox.

Test dependencies stay out of the app. No game files or native runtime are needed.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--android-jar', type=Path, required=True)
p.add_argument('--maven', default=shutil.which('mvn') or str(root/'.tools/maven/apache-maven-3.9.9/bin/mvn'))
p.add_argument('--maven-settings', type=Path)
args = p.parse_args()
work = root/'out/android-tests'
deps = work/'dependencies'
classes = work/'classes'
if deps.exists(): shutil.rmtree(deps)
deps.mkdir(parents=True)
if classes.exists(): shutil.rmtree(classes)
classes.mkdir()

def run(*cmd):
    subprocess.run([str(x) for x in cmd], cwd=root, check=True)

maven = [args.maven, '-B', '-q']
if args.maven_settings: maven += ['--settings', str(args.maven_settings)]
run(*maven, '-f', root/'tests/android/pom.xml',
    'org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy-dependencies',
    '-DincludeScope=test', '-DoutputDirectory='+str(deps))
for aar in deps.glob('*.aar'):
    with zipfile.ZipFile(aar) as z:
        aar.with_suffix('.jar').write_bytes(z.read('classes.jar'))
classpath = os.pathsep.join(map(str, [classes, args.android_jar.resolve(), *sorted(deps.glob('*.jar'))]))
sources = sorted((root/'app/src/main/java').rglob('*.java')) + sorted((root/'tests/android').glob('*.java'))
run('java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '--release', '8', '-cp', classpath, '-d', classes, *sources)
run('java', '-ea', '-Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2',
    '-cp', classpath, 'org.junit.runner.JUnitCore', 'io.github.russianranger.lsb.RuntimeActivityTest', 'io.github.russianranger.lsb.DisplayBitmapTest', 'io.github.russianranger.lsb.DisplayPerformanceTest', 'io.github.russianranger.lsb.NativePerformanceTest', 'io.github.russianranger.lsb.FantasyTilesTest', 'io.github.russianranger.lsb.SessionHistoryTest')
