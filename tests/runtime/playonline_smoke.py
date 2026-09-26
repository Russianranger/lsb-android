"""Offline official viewer COM comparison; never treat a splash as usable UI.

Run in a fresh --network none container with /official mounted read-only. Each
case uses an account-free, disposable prefix and client copy. Only fixed Wine
metadata and screenshots of that public viewer are retained. Proprietary
binaries, MSI contents, retail credentials and arbitrary stdout are not kept.
A visible process and colored pixels can be a modal error: screenshots require
review, and neither this test nor offline startup claims completed online repair.
"""
from collections import Counter
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import time
import uuid
import zlib

sys.path.insert(0, '/opt/lsb')
from integration import recv

SESSION = Path('/session')
LOGS = Path('/logs')
CLIENT = Path('/client')
POL_SHA = '5c2d45bd277eaf815d2790fee79548a88e25679986ca483773c69382ef92c404'
VARIANTS = ('registry-only', 'core-registered', 'production')
POL_CLASSES = {'{3501f5dd-7894-42df-866a-a2b6527d8049}',
               '{40555aae-53ad-4abc-ae65-8441755e7d69}',
               '{3fc1ef9a-f346-413c-bb47-ed6f9a4bd52f}',
               '{62021866-976b-49a3-a18b-7a44869008a2}'}


def png_chunk(kind, payload):
    return struct.pack('>I', len(payload)) + kind + payload + struct.pack('>I', zlib.crc32(kind + payload))


@contextmanager
def viewer_display():
    # A successful viewer can switch fullscreen resolution after its splash.
    # The fixed 1280x720 synthetic graphics fixture is unsuitable here: use the
    # dimensions actually advertised by this fresh RFB connection, bounded.
    with socket.socket(socket.AF_UNIX) as connection:
        connection.settimeout(10)
        connection.connect('/session/display.sock')
        assert recv(connection, 12) == b'RFB 003.008\n', 'Unexpected RFB version'
        connection.sendall(b'RFB 003.008\n')
        kinds = recv(connection, 1)[0]
        assert 1 in recv(connection, kinds), 'RFB no-auth unavailable'
        connection.sendall(b'\x01')
        assert recv(connection, 4) == bytes(4), 'RFB authentication failed'
        connection.sendall(b'\x01')
        width, height = struct.unpack('>HH', recv(connection, 4))
        assert 1 <= width <= 4096 and 1 <= height <= 4096, 'RFB dimensions outside fixture bound'
        recv(connection, 16)
        title_size = struct.unpack('>I', recv(connection, 4))[0]
        assert title_size <= 4096, 'RFB title exceeds fixture bound'
        recv(connection, title_size)  # Never retain the arbitrary desktop title.
        connection.sendall(bytes(4) + bytes([32, 24, 0, 1, 0, 255, 0, 255, 0, 255, 16, 8, 0, 0, 0, 0]))
        connection.sendall(struct.pack('>BBHi', 2, 0, 1, 0))
        yield connection, (width, height)


def screen_evidence(connection, destination, dimensions=(1280, 720)):
    """Request one full raw RFB frame and store a standalone RGB PNG."""
    width, height = dimensions
    connection.sendall(struct.pack('>BBHHHH', 3, 0, 0, 0, width, height))
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
        frame = bytearray(width * height * 3)
        covered = bytearray(width * height)
        for _ in range(count):
            x, y, rect_width, rect_height, encoding = struct.unpack('>HHHHi', recv(connection, 12))
            assert encoding == 0 and x + rect_width <= width and y + rect_height <= height
            raw = recv(connection, rect_width * rect_height * 4)
            for row in range(rect_height):
                offset = ((y + row) * width + x) * 3
                source = raw[row * rect_width * 4:(row + 1) * rect_width * 4]
                # viewer_display() selects 32-bit little-endian truecolor with RGB
                # shifts 16/8/0; incoming pixel bytes are B G R unused.
                rgb = bytearray(rect_width * 3)
                rgb[0::3], rgb[1::3], rgb[2::3] = source[2::4], source[1::4], source[0::4]
                frame[offset:offset + len(rgb)] = rgb
                pixel_offset = (y + row) * width + x
                covered[pixel_offset:pixel_offset + rect_width] = b'\1' * rect_width
        assert all(covered), 'RFB did not provide the requested complete frame'
        filtered = b''.join(b'\0' + frame[row * width * 3:(row + 1) * width * 3] for row in range(height))
        destination.write_bytes(b'\x89PNG\r\n\x1a\n' +
            png_chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 2, 0, 0, 0)) +
            png_chunk(b'IDAT', zlib.compress(filtered)) + png_chunk(b'IEND', b''))
        colors = Counter(bytes(frame[index:index + 3]) for index in range(0, len(frame), 3))
        return {'width': width, 'height': height, 'distinct_colors': len(colors),
                'nonbackground_pixels': sum(colors.values()) - max(colors.values(), default=0),
                'screenshot': destination.name,
                'screenshot_sha256': hashlib.sha256(destination.read_bytes()).hexdigest()}


def read_json(path):
    try:
        return json.loads(path.read_text())
    except (OSError, ValueError):
        return {}


def worker(label):
    import client_update
    import supervisor
    from client_setup import windows_path

    # Explicit comparison of .37 install-path-only behavior and an already
    # core-registered gameplay prefix. A later production helper must not make
    # the regression baseline accidentally use the fix being evaluated.
    if label != 'production' and hasattr(client_update, 'prepare_playonline_components'):
        client_update.prepare_playonline_components = lambda *args, **kwargs: None
    original_check = client_update.check_dependencies

    def prepare_then_check(instance, manifest, executable, environment, report, record):
        preparation = {'format': 1, 'variant': label, 'steps': [],
                       'session_id': instance.req['session_id'], 'generation': manifest['generation']}
        destination = LOGS / (label + '-preparation.json')
        destination.write_text(json.dumps(preparation, indent=2))
        if label == 'core-registered':
            pol = CLIENT / manifest['pol']
            matches = [path for path in pol.rglob('*') if path.is_file() and
                       path.relative_to(pol).as_posix().lower() == 'viewer/com/polcore.dll']
            assert len(matches) == 1, 'Official fixture core DLL is missing or ambiguous'
            core = windows_path(matches[0].relative_to(CLIENT).as_posix())
            for operation, arguments in (('register', [core]), ('com', [manifest['region'], 'pol', core])):
                receipt_path = SESSION / 'client-step.json'
                receipt_path.unlink(missing_ok=True)
                process = instance.spawn(instance.wine_command(r'P:\client-init.exe', operation, *arguments),
                                         'official-core-' + operation + '.log', env=environment)
                writer = instance.logs[-1]
                try:
                    instance.wait(process, 90, 'Official core ' + operation, accepted=(0, 1))
                finally:
                    receipt = read_json(receipt_path)
                    step = {'operation': operation, 'exit_code': process.poll(),
                            'receipt': {key: receipt.get(key) for key in
                                        ('format', 'bits', 'operation', 'ok', 'hresult', 'win32_error')},
                            'startup_diagnostics': client_update.diagnostics(writer, drain=True)}
                    preparation['steps'].append(step)
                    destination.write_text(json.dumps(preparation, indent=2))
                assert process.returncode == 0 and receipt.get('operation') == operation and \
                    receipt.get('bits') == 32 and receipt.get('ok') is True, 'Official core preparation failed'
        original_check(instance, manifest, executable, environment, report, record)

    client_update.check_dependencies = prepare_then_check
    supervisor.main()


def main():
    for directory in (SESSION, LOGS, CLIENT):
        directory.mkdir(exist_ok=True)
    results = {'format': 2, 'network': 'disabled', 'official_viewer_sha256': POL_SHA,
               'viewer_usable': None, 'online_repair_verified': False,
               'interpretation': 'Screenshots require review; a window or colored pixels may be a splash or error dialog.'}
    for label in VARIANTS:
        for name in ('stop', 'status.json', 'playonline-process.json', 'loader-check.json', 'client-step.json'):
            (SESSION / name).unlink(missing_ok=True)
        for name in ('client-update.json', 'runtime-state.json', label + '-preparation.json', label + '.png'):
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
        captured_at = 0
        metrics = {'distinct_colors': 0, 'nonbackground_pixels': 0}
        observed = {}
        capture_failures = []
        negotiated_dimensions = None
        try:
            while process.poll() is None and time.monotonic() - started < 240:
                receipt = read_json(SESSION / 'playonline-process.json')
                if receipt.get('visible_window_seen'):
                    observed = receipt
                    if visible_at is None:
                        visible_at = time.monotonic()
                    if time.monotonic() - visible_at > 5 and time.monotonic() - captured_at > 5:
                        try:
                            with viewer_display() as (connection, negotiated_dimensions):
                                metrics = screen_evidence(connection, LOGS / (label + '.png'), negotiated_dimensions)
                            captured_at = time.monotonic()
                        except (OSError, EOFError, AssertionError) as error:
                            failure = {'type': type(error).__name__, 'dimensions': negotiated_dimensions}
                            if failure not in capture_failures:
                                capture_failures.append(failure)
                    if time.monotonic() - visible_at > 25:
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
        preparation = read_json(LOGS / (label + '-preparation.json'))
        results[label] = {'process': final_before_stop, 'visible_process': observed,
                          'alive_before_stop': alive, 'pixels': metrics,
                          'capture_failures': capture_failures,
                          'status': report.get('status'),
                          'supervisor_phase': state.get('phase'),
                          'supervisor_error': state.get('error'),
                          'preparation': preparation,
                          'component_registration': report.get('component_registration', []),
                          'dependency_attempts': report.get('dependency_attempts', []),
                          'startup_diagnostics': report.get('startup_diagnostics', {})}
        (LOGS / 'official-playonline-smoke.json').write_text(json.dumps(results, indent=2))
        assert state.get('session_id') == request['session_id'], 'Missing current supervisor receipt'
        assert preparation.get('session_id') == request['session_id'] and \
            preparation.get('generation') == manifest['generation'], 'Missing current preparation comparison'
        assert (report.get('session_id') == request['session_id'] and
                report.get('generation') == manifest['generation'] and
                report.get('dependency_attempts')), 'Official viewer did not reach current dependency preflight'
        if observed:
            assert 'screenshot' in metrics, 'Visible official viewer has no screenshot for review'
        diagnostic = report.get('startup_diagnostics', {})
        missing_classes = {row.get('clsid') for row in diagnostic.get('records', [])
                           if row.get('reason') == 'class_not_registered'}
        if label == 'registry-only':
            assert '{3501f5dd-7894-42df-866a-a2b6527d8049}' in missing_classes, 'Missing unregistered-core regression evidence'
        elif label == 'core-registered':
            assert '{40555aae-53ad-4abc-ae65-8441755e7d69}' in missing_classes, 'Missing .37 unregistered-app regression evidence'
        else:
            components = report.get('component_registration', [])
            assert [entry.get('component') for entry in components] == ['core', 'app', 'contents'], 'Incomplete production registration inventory'
            for entry, operations in zip(components, [('register', 'com'), ('register', 'class'), ('register', 'class')]):
                steps = entry.get('steps', [])
                assert tuple(step.get('operation') for step in steps) == operations, 'Missing production register/verify step'
                for step, operation in zip(steps, operations):
                    result = step.get('result', {})
                    assert (step.get('exit_code') == 0 and result.get('operation') == operation and
                            result.get('bits') == 32 and result.get('ok') is True and
                            result.get('hresult') == 0 and result.get('win32_error') == 0), 'Production component did not register and verify successfully'
            assert observed and 'screenshot' in metrics, 'Production viewer never provided reviewable display evidence'
            assert diagnostic.get('dropped_records') == 0, 'Incomplete production startup diagnostics'
            assert not missing_classes.intersection(POL_CLASSES), 'Production viewer still reports missing PlayOnline COM class'
            # This checks the concrete COM regression, not general UI usability.
            # The offline screenshot still needs inspection for another error.
            results[label]['component_registration_verified'] = True
            results[label]['known_missing_class_errors'] = False
        print('Official PlayOnline offline COM comparison:', label,
              'window=', bool(observed), 'alive=', alive,
              'exit=', final_before_stop.get('child_exit'), 'pixels=', metrics, flush=True)
    results['harness_completed'] = True
    (LOGS / 'official-playonline-smoke.json').write_text(json.dumps(results, indent=2))
    print('PASS: official viewer comparison captured; usable UI and online repair remain unverified', flush=True)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--worker' and sys.argv[2] in VARIANTS:
        worker(sys.argv[2])
    else:
        main()
