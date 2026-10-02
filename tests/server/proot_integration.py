"""Real ARM64 MariaDB and jemalloc server startup through the application's PRoot."""
import json
import hashlib
import os
from pathlib import Path
import subprocess
import time

MARKER='TRASC PRoot: seccomp acceleration observed\n'


def verify(repo, backend):
    native=Path(os.environ['LSB_SERVER_TEST_PROOT'])
    environment=dict(os.environ,PROOT_LOADER=str(native/'loader/loader'),PROOT_TMP_DIR='/tmp',TRASC_PROOT_REPORT='1')
    environment.pop('PROOT_NO_SECCOMP',None)
    command=[str(native/'proot'),'--link2symlink','--kill-on-exit','-0','-r','/',
             '-b',str(repo/'server')+':/opt/lsb-server','-w','/state',
             '/usr/bin/env','-i','HOME=/root','USER=root','PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin',
             'LANG=C.UTF-8','TMPDIR=/tmp','PYTHONUNBUFFERED=1','LSB_SERVER_OWNER=ci-filter']
    before=(backend.STATE/'active.json').read_bytes()
    def digest(path):
        with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()
    data_hashes={p:digest(p) for p in backend.current().joinpath('database').rglob('*') if p.is_file()}
    assert data_hashes,'Expected a stopped physical database to verify'
    probe=subprocess.run(command+['/usr/bin/python3',str(repo/'runtime/proot_preflight.py'),'--server'],
                         env=environment,capture_output=True,text=True,timeout=20)
    assert probe.returncode==0 and MARKER in probe.stderr and 'LSB_PROOT_PREFLIGHT_V1 PASS\n' in probe.stdout,(probe.returncode,probe.stdout,probe.stderr)
    assert (backend.STATE/'active.json').read_bytes()==before
    assert all(digest(p)==value for p,value in data_hashes.items()),'Preflight must not open database data'
    (backend.RUN/'stop').unlink(missing_ok=True);(backend.RUN/'status.json').unlink(missing_ok=True)
    (backend.RUN/'request.json').write_text(json.dumps(dict(action='start')))
    output=Path('/tmp/lsb-server-filter-launch.log')
    with output.open('wb') as log:
        process=subprocess.Popen(command+['/usr/bin/python3','/opt/lsb-server/accelerated_start.py'],
                                 env=environment,stdin=subprocess.PIPE,stdout=log,stderr=log)
    try:
        deadline=time.monotonic()+5
        while MARKER not in output.read_text(errors='replace'):
            assert process.poll() is None and time.monotonic()<deadline,'Native server filter activation was not observed'
            time.sleep(.02)
        time.sleep(.2)
        assert not (backend.RUN/'status.json').exists(),'Unreleased gate must not execute manager.py'
        assert all(digest(p)==value for p,value in data_hashes.items()),'Unreleased gate opened database data'
        process.stdin.write(b'LSB_SERVER_FILTER_GO_V1\n');process.stdin.close()
        deadline=time.monotonic()+120;loading=False
        while time.monotonic()<deadline:
            assert process.poll() is None,output.read_text(errors='replace')[-3000:]
            try:
                status=json.loads((backend.RUN/'status.json').read_text());startup=status.get('startup',{})
                if status.get('phase')=='starting' and startup.get('login_port_reachable') and 'xi_map' in startup.get('pending_processes',[]):loading=True
                if status.get('phase')=='running':
                    assert loading and not startup['pending_processes']
                    assert set(startup['ready_processes'])==set(backend.PROCESSES)
                    break
            except (OSError,ValueError):pass
            time.sleep(.1)
        else:raise AssertionError('Filtered server did not become ready')
        (backend.RUN/'stop').touch();assert process.wait(timeout=50)==0
        assert json.loads((backend.RUN/'status.json').read_text())['phase']=='stopped'
    finally:
        if process.poll() is None:process.terminate();process.wait(timeout=50)
    assert (backend.STATE/'active.json').read_bytes()==before
    print('PASS: observed native PRoot filtering, credential-free preflight and launch gate leave stopped data intact; real ARM64 MariaDB and jemalloc services reach readiness and shut down cleanly',flush=True)
