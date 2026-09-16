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
libs = ['-luser32', '-ladvapi32', '-lshell32', '-lole32']
def build(source, output, extra=()):
    subprocess.run([cc, *flags, *extra, str(root/source), '-o', str(output), *libs], check=True)
build('windows/launcher.c', assets/'LSB-FFXI.exe')
if args.tests:
    tests = root/'out/windows-tests'; tests.mkdir(parents=True, exist_ok=True)
    build('windows/launcher.c', tests/'LSB-FFXI-test.exe', ['-DLSB_TEST_MODE', '-Wno-unused-function'])
    build('tests/windows/register-stub.c', tests/'register-stub.dll', ['-shared', '-Wl,--kill-at'])
    build('tests/windows/loader-stub.c', tests/'loader-stub.exe')
print('Built', assets/'LSB-FFXI.exe')
