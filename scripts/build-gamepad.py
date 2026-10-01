#!/usr/bin/env python3
"""Build a small ARM64 glibc preload without changing the pinned Wine runtime."""
from pathlib import Path
import shutil, subprocess, os
root=Path(__file__).resolve().parents[1]
out=root/'out/runtime-probes/liblsb-gamepad.so';out.parent.mkdir(parents=True,exist_ok=True)
cc=shutil.which('clang')
local=root/'.tools/clang/usr/lib/llvm-18/bin/clang'
if not cc and local.exists():
 cc=str(local)
 os.environ['LD_LIBRARY_PATH']=str(root/'.tools/clang/usr/lib/x86_64-linux-gnu')
 os.environ['PATH']=str(local.parent)+':'+os.environ['PATH']
if not cc: raise SystemExit('Install clang and lld to build the ARM64 gamepad bridge.')
subprocess.run([cc,'--target=aarch64-linux-gnu','-fuse-ld=lld','-std=c11','-O2','-Wall','-Wextra','-Werror','-fPIC','-shared','-nostdlib','-fno-stack-protector','-Wl,-soname,liblsb-gamepad.so',str(root/'native/gamepad.c'),'-o',str(out)],check=True)
print('Built ARM64 SDL gamepad bridge')
