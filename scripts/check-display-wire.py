#!/usr/bin/env python3
"""Compare production RFB decoding with real TigerVNC pixels over a Unix socket."""
import argparse,os,subprocess,tempfile,time
from pathlib import Path
root=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--backend',choices=['native','docker','proot'],required=True);args=p.parse_args()
work=root/'out/display-wire';work.mkdir(parents=True,exist_ok=True)
sources=[root/'app/src/main/java/io/github/russianranger/lsb'/n for n in ['RfbConnection.java','ZrleDecoder.java','ClientFrameStats.java']]
subprocess.run(['java','-m','jdk.compiler/com.sun.tools.javac.Main','-d',str(work),*map(str,sources),str(root/'tests/runtime/java/io/github/russianranger/lsb/DisplayWireProbe.java')],check=True)
with tempfile.TemporaryDirectory(prefix='lsb-wire-') as temporary:
    folder=Path(temporary);env=dict(os.environ)
    if args.backend=='native':command=['python3',str(root/'tests/runtime/display_fixture.py'),str(folder)]
    elif args.backend=='docker':command=['docker','run','--rm','--network','none','-v',str(folder)+':/session','-v',str(root/'tests/runtime')+':/tests:ro','lsb-runtime:test','python3','/tests/display_fixture.py','/session']
    else:
        proot=root/'out/runtime-test/proot-src/src';guest=root/'out/runtime-test/proot-root';(folder/'tmp').mkdir()
        for name in ('session','tests'):(guest/name).mkdir(exist_ok=True)
        env.update(PROOT_LOADER=str(proot/'loader/loader'),PROOT_NO_SECCOMP='1',PROOT_TMP_DIR=str(folder/'tmp'))
        command=[str(proot/'proot'),'--link2symlink','--kill-on-exit','-0','-r',str(guest),'-b','/dev','-b','/proc','-b','/sys','-b',str(folder)+':/session','-b',str(folder/'tmp')+':/tmp','-b',str(root/'tests/runtime')+':/tests','-w','/tests','/usr/bin/env','-i','HOME=/root','PATH=/usr/bin:/bin','python3','/tests/display_fixture.py','/session']
    with (work/(args.backend+'.log')).open('w') as log:
        process=subprocess.Popen(command,env=env,stdout=log,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+45
            while not (folder/'ready').exists():
                if process.poll() is not None:raise RuntimeError('Display fixture stopped; see '+str(log.name))
                if time.monotonic()>deadline:raise TimeoutError('Display fixture readiness')
                time.sleep(.05)
            subprocess.run(['java','-ea','-cp',str(work),'io.github.russianranger.lsb.DisplayWireProbe',str(folder/'display.sock')],check=True,timeout=90)
        finally:
            (folder/'stop').write_text('stop')
            try:process.wait(timeout=10)
            except subprocess.TimeoutExpired:process.terminate();process.wait(timeout=10)
    if process.returncode:raise RuntimeError('Fixture failed: '+str(process.returncode))
