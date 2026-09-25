"""Offline official viewer comparison; no accounts, repairs, or saved pixels.

Run in a fresh --network none container with /official mounted read-only. The
only retained result is numerical process/window/pixel evidence and the existing
fixed-metadata Wine diagnostics. The fresh viewer's offline startup is not proof
of the user's updated viewer or of an actual Square Enix download.
"""
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time
import uuid

sys.path.insert(0, '/opt/lsb')
from integration import display, recv

SESSION = Path('/session')
LOGS = Path('/logs')
CLIENT = Path('/client')
POL_SHA = '5c2d45bd277eaf815d2790fee79548a88e25679986ca483773c69382ef92c404'


def screen_metrics(connection):
    connection.sendall(struct.pack('>BBHHHH', 3, 0, 0, 0, 1280, 720))
    while True:
        kind = recv(connection, 1)[0]
        if kind == 2:
            continue
        if kind == 3:
            recv(connection, 3)
            recv(connection, struct.unpack('>I', recv(connection, 4))[0])
            continue
        assert kind == 0
        recv(connection, 1)
        count = struct.unpack('>H', recv(connection, 2))[0]
        colors = Counter()
        for _ in range(count):
            x, y, width, height, encoding = struct.unpack('>HHHHi', recv(connection, 12))
            assert encoding == 0
            raw = recv(connection, width * height * 4)
            colors.update(raw[index:index + 3] for index in range(0, len(raw), 4))
        return {'distinct_colors': len(colors), 'nonbackground_pixels': sum(colors.values()) - max(colors.values(), default=0)}


def worker(baseline):
    import client_update
    import supervisor
    if baseline:
        original = client_update.private_environment

        def prior_environment(instance):
            environment = original(instance)
            environment['WINEDLLOVERRIDES'] = instance.env['WINEDLLOVERRIDES']
            return environment

        client_update.private_environment = prior_environment
    supervisor.main()


def read_json(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def main():
    for directory in (SESSION, LOGS, CLIENT):
        directory.mkdir(exist_ok=True)
    results = {}
    for label in ('baseline', 'codec-unavailable'):
        for name in ('stop', 'status.json', 'playonline-process.json', 'loader-check.json'):
            (SESSION / name).unlink(missing_ok=True)
        for name in ('client-update.json', 'runtime-state.json'):
            (LOGS / name).unlink(missing_ok=True)
        for path in (Path('/prefix'), CLIENT / 'Viewer'):
            if path.exists():
                shutil.rmtree(path)
        Path('/prefix').mkdir()
        viewer = CLIENT / 'Viewer'
        shutil.copytree('/official', viewer)
        (CLIENT / 'Game').mkdir(exist_ok=True)
        assert hashlib.sha256((viewer / 'pol.exe').read_bytes()).hexdigest() == POL_SHA
        manifest = {'format': 1, 'generation': str(uuid.uuid4()), 'region': 'US', 'pol': 'Viewer',
                    'game': 'Game', 'executable': 'Viewer/pol.exe', 'sha256': POL_SHA}
        request = {'format': 1, 'engine': 'box64', 'session_id': str(uuid.uuid4()),
                   'renderer': 'software', 'audio': False, 'action': 'update-client'}
        (SESSION / 'client-update-manifest.json').write_text(json.dumps(manifest))
        (SESSION / 'request.json').write_text(json.dumps(request))
        process = subprocess.Popen([sys.executable, __file__, '--worker', label],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        started = time.monotonic()
        visible_at = None
        metrics = {'distinct_colors': 0, 'nonbackground_pixels': 0}
        observed = {}
        try:
            while process.poll() is None and time.monotonic() - started < 180:
                receipt = read_json(SESSION / 'playonline-process.json')
                if receipt.get('visible_window_seen'):
                    observed = receipt
                    if visible_at is None:
                        visible_at = time.monotonic()
                    if time.monotonic() - visible_at > 5:
                        try:
                            with display() as connection:
                                metrics = screen_metrics(connection)
                        except (OSError, EOFError, AssertionError):
                            pass
                    if time.monotonic() - visible_at > 20:
                        break
                time.sleep(.25)
            final_before_stop = read_json(SESSION / 'playonline-process.json')
            alive = process.poll() is None
        finally:
            (SESSION / 'stop').write_text('stop')
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        report = read_json(LOGS / 'client-update.json')
        state = read_json(SESSION / 'status.json')
        results[label] = {'process': final_before_stop, 'visible_process': observed,
                          'alive_before_stop': alive, 'pixels': metrics,
                          'status': report.get('status'),
                          'supervisor_phase': state.get('phase'),
                          'supervisor_error': state.get('error'),
                          'dependency_attempts': report.get('dependency_attempts', []),
                          'startup_diagnostics': report.get('startup_diagnostics', {})}
        # Publish partial fixed/numeric evidence even when setup fails. Never
        # let a stale first-case report masquerade as the second comparison.
        (LOGS / 'official-playonline-smoke.json').write_text(json.dumps(results, indent=2))
        assert state.get('session_id') == request['session_id'], 'Missing current supervisor receipt'
        assert (report.get('session_id') == request['session_id'] and
                report.get('generation') == manifest['generation'] and
                report.get('dependency_attempts')), 'Official viewer did not reach current dependency preflight'
        print('Official PlayOnline offline comparison:', label,
              'window=', bool(observed), 'alive=', alive,
              'exit=', final_before_stop.get('child_exit'), 'pixels=', metrics, flush=True)
    fixed = results['codec-unavailable']
    pixels = fixed['pixels']
    rendered = bool(fixed['visible_process']) and pixels['distinct_colors'] > 64 and pixels['nonbackground_pixels'] > 10000
    results['format'] = 1
    results['network'] = 'disabled'
    results['official_viewer_sha256'] = POL_SHA
    results['fixed_visible_pixels'] = rendered
    results['phone_failure_reproduced'] = False
    (LOGS / 'official-playonline-smoke.json').write_text(json.dumps(results, indent=2))
    # Offline viewer revisions can stop at a network error before reaching the
    # optional codec. Keep that distinction explicit instead of labeling any
    # exit-code change a proven reproduction of the user's handset failure.
    print('Official PlayOnline offline visible pixels:', rendered,
          '(phone crash and online repair not claimed)', flush=True)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--worker':
        worker(sys.argv[2] == 'baseline')
    else:
        main()
