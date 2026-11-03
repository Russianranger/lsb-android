#!/usr/bin/env python3
"""Exercise the capture helper's real deadline policy without a display clock."""
from pathlib import Path
import subprocess
root=Path(__file__).resolve().parents[1]
out=root/'out/tests/capture-pacing';out.parent.mkdir(parents=True,exist_ok=True)
subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror',str(root/'tests/capture_pacing.c'),'-o',str(out)],check=True)
subprocess.run([str(out)],check=True)

wait=root/'out/tests/capture-wait'
subprocess.run(['cc','-std=c11','-O2','-Wall','-Wextra','-Werror','-pthread',str(root/'tests/capture_wait.c'),'-o',str(wait)],check=True)
subprocess.run([str(wait)],check=True,timeout=10)
