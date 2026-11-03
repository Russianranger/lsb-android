#!/usr/bin/env python3
"""Run the real Surface JNI request loop against a socket peer and window test double."""
from pathlib import Path
import os, shutil, subprocess
root=Path(__file__).resolve().parents[1]
java=Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else Path(shutil.which('java')).resolve().parents[1]
out=root/'out/tests/native-surface-pipeline';out.parent.mkdir(parents=True,exist_ok=True)
subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror','-pthread',
    '-I'+str(root/'tests/native-stubs'),'-I'+str(java/'include'),'-I'+str(java/'include/linux'),
    str(root/'tests/native_surface_pipeline.c'),'-o',str(out)],check=True)
subprocess.run([str(out)],check=True,timeout=30)
