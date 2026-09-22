#!/usr/bin/env python3
"""Build the native 32-bit Windows helper; no proprietary files or VC runtime needed."""
import argparse, os, shutil, subprocess
from pathlib import Path
root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--cc', default=os.environ.get('LSB_MINGW_CC'))
p.add_argument('--tests', action='store_true')
args = p.parse_args()
cc = args.cc or shutil.which('i686-w64-mingw32-gcc')
if not cc:
    local = root/'.tools/mingw/usr/bin/i686-w64-mingw32-gcc-posix'
    if local.exists(): cc = str(local)
if not cc: raise SystemExit('Install gcc-mingw-w64-i686-posix, or set LSB_MINGW_CC to its compiler.')
assets = root/'out/launcher-assets'; assets.mkdir(parents=True, exist_ok=True)
flags = ['-std=c11', '-Os', '-Wall', '-Wextra', '-Wno-misleading-indentation', '-static', '-static-libgcc', '-municode', '-mwindows', '-Wl,--no-insert-timestamp', '-s']
libs = ['-luser32', '-ladvapi32', '-lshell32', '-lole32', '-luuid']
def build(source, output, extra=()):
    subprocess.run([cc, *flags, str(root/source), *extra, '-o', str(output), *libs], check=True)
build('windows/launcher.c', assets/'LSB-FFXI.exe')
probes = root/'out/runtime-probes'; probes.mkdir(parents=True, exist_ok=True)
build('windows/client-init.c', probes/'client-init.exe', ['-mconsole'])
build('windows/client-launch.c', probes/'client-launch.exe', ['-mconsole'])
build('windows/startup-trace.c', probes/'startup-trace.dll', ['-shared', '-Wl,--kill-at'])
build('windows/probe-com.c', probes/'probe-com.dll', ['-shared', '-Wl,--kill-at'])
subprocess.run([cc, *flags, str(root/'windows/runtime-probe.c'), '-o', str(probes/'runtime-probe.exe'), *libs, '-ld3d8', '-lwinmm', '-luuid', '-lm'], check=True)
build('windows/graphics-check.c', probes/'graphics-check.exe', ['-ld3d8'])
if args.tests:
    tests = root/'out/windows-tests'; tests.mkdir(parents=True, exist_ok=True)
    build('windows/launcher.c', tests/'LSB-FFXI-test.exe', ['-DLSB_TEST_MODE', '-Wno-unused-function'])
    build('tests/windows/client-com-stub.c', tests/'client-com-stub.dll', ['-shared', '-Wl,--kill-at'])
    build('tests/windows/register-stub.c', tests/'register-stub.dll', ['-shared', '-Wl,--kill-at'])
    build('tests/windows/loader-stub.c', tests/'loader-stub.exe')
    build('tests/windows/prerequisite-stub.c', tests/'prerequisite-stub.exe')
    build('tests/windows/login-stub.c', tests/'login-stub.exe', ['-mconsole'])
    build('tests/windows/display-config.c', tests/'display-config.exe', ['-mconsole'])
    build('tests/windows/gamepad-check.c', tests/'gamepad-check.exe', ['-mconsole','-ldinput8','-ldxguid'])
    build('tests/windows/version-registry.c', tests/'version-registry.exe', ['-mconsole'])
    build('tests/windows/missing-dependency.c', tests/'lsb-missing-fixture.dll', ['-shared', '-Wl,--out-implib,'+str(tests/'libmissing.a')])
    build('tests/windows/login-stub.c', tests/'login-missing.exe', ['-mconsole','-DMISSING_IMPORT','-L'+str(tests),'-lmissing'])
    build('tests/windows/dependency-lifetime.c', tests/'dependency-detach.dll', ['-shared'])
    build('tests/windows/dependency-lifetime.c', tests/'dependency-attach.dll', ['-shared','-DFAIL_ATTACH'])
    build('tests/windows/dependency-cycle.c', tests/'dependency-cycle.exe', ['-mconsole'])
print('Built', assets/'LSB-FFXI.exe')
