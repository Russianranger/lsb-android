"""Opt-in public PlayOnline version-check diagnostic after the offline gate.

Use a fresh container, official installer-only client and empty prefix. The only
input is one click on the known first-run 640x480 Next button; no account fields,
retail credentials or user files are supplied. Raw Wine tracing stays under the
private /logs mount, which must never be uploaded. Only fixed metadata and
screenshots of this account-free viewer are written to /evidence. Screenshots
require human review: pixels and a running process do not prove an update.
"""
import hashlib
import json
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import threading
import time
import uuid

from playonline_smoke import CLIENT, LOGS, POL_SHA, SESSION, read_json, screen_evidence, viewer_display
from pol_network_trace import NetworkDiagnostics

EVIDENCE = Path('/evidence')
RAW_TRACE = LOGS / 'public-winsock.raw'
RAW_LIMIT = 8 * 1024 * 1024
STARTUP_LIMIT = 240
POST_CLICK_LIMIT = 180
VARIANTS = ('missing-interface', 'msi-interface')
INTERFACE_VERSION = '001b1394'
FIXED_FILES = {'viewer_executable': 'pol.exe', 'viewer_patch_version': 'patch.ver',
               'viewer_patch_settings': 'patch.ini', 'viewer_application': 'viewer/com/app.dll',
               'viewer_core': 'viewer/com/polcore.dll'}


def file_metadata():
    results = {}
    for label, relative in FIXED_FILES.items():
        path = CLIENT / 'Viewer'
        for part in relative.split('/'):
            matches = [child for child in path.iterdir() if child.name.casefold() == part.casefold()] if path.is_dir() else []
            if len(matches) != 1:
                path = None
                break
            path = matches[0]
        if path is None or not path.is_file():
            results[label] = {'present': False}
        else:
            size = path.stat().st_size
            result = {'present': True, 'bytes': size}
            if size <= 16 * 1024 * 1024:
                result['sha256'] = hashlib.sha256(path.read_bytes()).hexdigest()
            results[label] = result
    return results


def worker(variant):
    import client_update
    import playonline_smoke
    import supervisor

    original_environment = client_update.private_environment
    original_run = client_update.run
    original_line = supervisor.PrivateEvents.line
    lock = threading.Lock()
    written = 0
    clipped = False

    def query_interface(instance, name):
        process = instance.spawn(instance.wine_command('reg', 'query',
            r'HKLM\Software\PlayOnlineUS\Interface', '/v', '1000', '/reg:32'), name)
        instance.wait(process, 90, 'Public fixture interface readback', accepted=(0, 1))
        instance.logs[-1].thread.join(2)
        raw = (LOGS / name).read_bytes()[:8192]
        # Accept only the precise known MSI version, never arbitrary registry
        # content or Wine text in the uploaded diagnostic.
        matches = re.findall(rb'(?im)^\s*1000\s+REG_SZ\s+([0-9a-f]{8})\s*$', raw.replace(b'\0', b''))
        return process.returncode, matches == [INTERFACE_VERSION.encode('ascii')]

    def fixture_run(instance):
        before_code, before_matches = query_interface(instance, 'fixture-interface-before.log')
        assert before_code == 1 and not before_matches, 'Fresh prefix unexpectedly contains viewer interface version'
        if variant == 'msi-interface':
            process = instance.spawn(instance.wine_command('reg', 'add',
                r'HKLM\Software\PlayOnlineUS\Interface', '/v', '1000', '/t', 'REG_SZ',
                '/d', INTERFACE_VERSION, '/f', '/reg:32'), 'fixture-interface-add.log')
            instance.wait(process, 90, 'Public fixture MSI interface value')
        after_code, after_matches = query_interface(instance, 'fixture-interface-after.log')
        assert (after_code, after_matches) == ((0, True) if variant == 'msi-interface' else (1, False)), 'Fixture interface comparison did not read back'
        (LOGS / 'fixture-interface.json').write_text(json.dumps({
            'variant': variant, 'initially_absent': True, 'value_installed': variant == 'msi-interface',
            'readback_verified': True, 'msi_interface_version': INTERFACE_VERSION if after_matches else None}))
        original_run(instance)

    def fixture_environment(instance):
        result = original_environment(instance)
        # Never enable this on the Android path. This source is an account-free
        # public installer and the private trace is excluded from artifacts.
        result['WINEDEBUG'] += ',trace+winsock'
        return result

    with RAW_TRACE.open('wb') as raw:
        def fixture_line(events, line):
            nonlocal written, clipped
            # Each PrivateEvents instance frames its own pipe first. Tapping
            # complete bounded lines avoids mixing fragmented parallel streams.
            chunk = line + b'\n'
            with lock:
                room = max(0, RAW_LIMIT - written)
                raw.write(chunk[:room])
                raw.flush()
                written += min(room, len(chunk))
                clipped |= len(chunk) > room
            return original_line(events, line)

        client_update.private_environment = fixture_environment
        client_update.run = fixture_run
        supervisor.PrivateEvents.line = fixture_line
        try:
            playonline_smoke.worker('production')
        finally:
            (LOGS / 'public-trace-size.json').write_text(json.dumps(
                {'bytes': written, 'limit': RAW_LIMIT, 'clipped': clipped}))


def capture(name, started, click=False):
    with viewer_display() as (connection, dimensions):
        if click and dimensions != (640, 480):
            return None
        result = screen_evidence(connection, EVIDENCE / name, dimensions)
        result['elapsed_ms'] = int((time.monotonic() - started) * 1000)
        if click:
            # This is the installer-only first-run screen verified by the
            # offline fixture. Record the click, not an inferred UI outcome.
            connection.sendall(struct.pack('>BBHH', 5, 0, 277, 414))
            connection.sendall(struct.pack('>BBHH', 5, 1, 277, 414))
            time.sleep(.1)
            connection.sendall(struct.pack('>BBHH', 5, 0, 277, 414))
        return result


def main(variant):
    for directory in (SESSION, LOGS, CLIENT, EVIDENCE):
        directory.mkdir(exist_ok=True)
    # These paths belong only to this new disposable container. Never mount an
    # existing working client or prefix into this diagnostic invocation.
    Path('/prefix').mkdir(exist_ok=True)
    assert not any(Path('/prefix').iterdir()), 'Online fixture requires an empty prefix'
    assert not (CLIENT / 'Viewer').exists(), 'Online fixture requires a fresh client'
    shutil.copytree('/official', CLIENT / 'Viewer')
    (CLIENT / 'Game').mkdir()
    assert hashlib.sha256((CLIENT / 'Viewer/pol.exe').read_bytes()).hexdigest() == POL_SHA
    before = file_metadata()
    manifest = {'format': 1, 'generation': str(uuid.uuid4()), 'region': 'US', 'pol': 'Viewer',
                'game': 'Game', 'executable': 'Viewer/pol.exe', 'sha256': POL_SHA}
    request = {'format': 1, 'engine': 'box64', 'session_id': str(uuid.uuid4()),
               'renderer': 'software', 'audio': False, 'action': 'update-client', 'network_preflight': True}
    (SESSION / 'client-update-manifest.json').write_text(json.dumps(manifest))
    (SESSION / 'request.json').write_text(json.dumps(request))
    process = subprocess.Popen([sys.executable, __file__, '--worker', variant],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    started = time.monotonic()
    visible_at = clicked_at = None
    shots = []
    failures = []
    milestones = [10, 45, 90, POST_CLICK_LIMIT - 10]
    pending = list(milestones)
    outcome = 'startup_timeout'
    receipt = {}
    try:
        while process.poll() is None:
            now = time.monotonic()
            receipt = read_json(SESSION / 'playonline-process.json')
            if clicked_at is None:
                if now - started >= STARTUP_LIMIT:
                    break
                if receipt.get('visible_window_seen'):
                    if visible_at is None:
                        visible_at = now
                    if now - visible_at >= 25:
                        try:
                            shot = capture('online-' + variant + '-before-next.png', started, click=True)
                            if shot:
                                shots.append(shot)
                                clicked_at = time.monotonic()
                                outcome = 'observation_window_complete'
                        except (OSError, EOFError, AssertionError) as error:
                            if type(error).__name__ not in failures:
                                failures.append(type(error).__name__)
            else:
                elapsed = now - clicked_at
                if pending and elapsed >= pending[0]:
                    marker = pending.pop(0)
                    try:
                        shot = capture('online-' + variant + '-after-next-%03d.png' % marker, started)
                        shot['after_click_ms'] = int((time.monotonic() - clicked_at) * 1000)
                        shots.append(shot)
                    except (OSError, EOFError, AssertionError) as error:
                        if type(error).__name__ not in failures:
                            failures.append(type(error).__name__)
                if elapsed >= POST_CLICK_LIMIT:
                    break
            time.sleep(.25)
        if process.poll() is not None:
            outcome = 'supervisor_exited'
    finally:
        alive = process.poll() is None
        (SESSION / 'stop').write_text('stop')
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
    diagnostics = NetworkDiagnostics()
    if RAW_TRACE.is_file():
        with RAW_TRACE.open('rb') as raw:
            for chunk in iter(lambda: raw.read(32768), b''):
                diagnostics.feed(chunk)
    diagnostics.finish()
    report = read_json(LOGS / 'client-update.json')
    components = [{'component': entry.get('component'), 'steps': [
        {key: step.get(key) for key in ('operation', 'exit_code', 'elapsed_ms')}
        for step in entry.get('steps', [])]} for entry in report.get('component_registration', [])]
    result = {'format': 1, 'network': 'enabled', 'source': 'fresh_public_installer', 'variant': variant,
              'official_viewer_sha256': POL_SHA, 'session_id': request['session_id'],
              'credentials_supplied': False, 'user_files_mounted': False,
              'next_click_sent': clicked_at is not None, 'next_click_position': [277, 414],
              'post_click_limit_seconds': POST_CLICK_LIMIT, 'observation': outcome,
              'alive_before_stop': alive, 'visible_window_seen': bool(receipt.get('visible_window_seen')),
              'viewer_usable': None, 'online_repair_verified': False,
              'screenshots_require_review': True, 'screenshots': shots, 'capture_failures': failures,
              'network_trace': diagnostics.snapshot(), 'trace_capture': read_json(LOGS / 'public-trace-size.json'),
              'network_preflight': read_json(LOGS / 'network-check.json'),
              'interface_comparison': read_json(LOGS / 'fixture-interface.json'),
              'component_preparation': components, 'files_before': before, 'files_after': file_metadata(),
              'preparation_elapsed_ms': report.get('preparation_elapsed_ms'),
              'viewer_elapsed_ms': report.get('viewer_elapsed_ms'),
              'elapsed_ms': int((time.monotonic() - started) * 1000)}
    (EVIDENCE / ('official-playonline-online-' + variant + '.json')).write_text(json.dumps(result, indent=2))
    assert clicked_at is not None, 'Public viewer never reached the bounded Next-click diagnostic'
    assert shots, 'Public online viewer has no reviewable screenshot'
    print('PASS: bounded public viewer online diagnostic captured; version response and screenshots require review', flush=True)


if __name__ == '__main__':
    if len(sys.argv) == 3 and sys.argv[1] == '--worker' and sys.argv[2] in VARIANTS:
        worker(sys.argv[2])
    else:
        assert len(sys.argv) == 2 and sys.argv[1] in VARIANTS, 'Select a fixed public fixture variant'
        main(sys.argv[1])
