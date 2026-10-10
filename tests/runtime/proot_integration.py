"""Focused filtered gameplay startup on real ARM64 Wine/FEX and synthetic PE32s.

The host observer alone publishes the patched PRoot activation marker. The guest
runs the unmodified production launch path five times, in fresh host processes.
A synthetic Windows loader then starts the existing texture/pixel oracle with
NULL environment, proving the actual launch child inherits the two-worker DXVK
configuration. CI uses lavapipe, so this is not an Adreno performance test.
"""
import hashlib
import json
import mmap
import os
from pathlib import Path
import re
import shutil
import socket
import stat
import struct
import subprocess
import sys
import time
import uuid

MARKER = b'TRASC PRoot: seccomp acceleration observed\n'
PREFLIGHT = b'LSB_PROOT_PREFLIGHT_V1 PASS\n'


def observe(arguments):
    proof, stage, private, logs = Path(arguments[0]), arguments[1], Path(arguments[2]), Path(arguments[3])
    command = arguments[4:]
    assert (stage in ('preflight', 'native-presentation') or re.fullmatch(r'launch-[1-5]', stage)) and command
    assert os.geteuid() == 0, 'Host filter observer must own the protected proof'
    proof.mkdir(parents=True, exist_ok=True)
    os.chown(proof, 0, 0)
    os.chmod(proof, 0o755)
    (proof / 'active').unlink(missing_ok=True)
    if stage == 'preflight':
        (proof / 'preflight').unlink(missing_ok=True)

    def publish(name, data):
        temporary = proof / (name + '.new')
        temporary.write_bytes(data)
        os.chmod(temporary, 0o444)
        temporary.replace(proof / name)

    # Guest output is redirected before Python starts; only the PRoot host
    # diagnostic descriptor feeds this observer. Guest code cannot fabricate
    # the marker by printing a string or writing to this root-owned binding.
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    active = False
    tail = b''
    for line in process.stdout:
        tail = (tail + line)[-16384:]
        if line == MARKER:
            publish('active', MARKER)
            active = True
    code = process.wait()
    result = {'format': 1, 'stage': stage, 'host_pid': process.pid,
              'host_marker_observed': active, 'exit_code': code,
              'scope': 'synthetic offline ARM64 CI only'}
    if code or not active:
        result['host_output_tail'] = tail.decode('utf-8', errors='replace')
        if private.is_file():
            with private.open('rb') as stream:
                stream.seek(max(0, private.stat().st_size - 16384))
                result['guest_output_tail'] = stream.read(16384).decode('utf-8', errors='replace')
    (logs / ('filter-' + stage + '.json')).write_text(json.dumps(result, indent=2))
    assert code == 0, result
    assert active, 'Patched PRoot did not report actual seccomp acceleration'
    if stage == 'native-presentation':
        # This separate fixture has no login input. Preserve its actual pixel,
        # ownership and resize receipts alongside the host filter proof.
        data = private.read_bytes()
        assert b'LSB_private_account' not in data and b'quoted' not in data and 'quoted'.encode('utf-16le') not in data
        (logs / 'native-presentation-fixture.log').write_bytes(data)
    if stage == 'preflight':
        assert private.read_bytes() == PREFLIGHT, 'Production filtered preflight did not pass'
        publish('preflight', PREFLIGHT)
    print('PASS: host-observed actual filtered PRoot ' + stage, flush=True)


def prepare_client():
    client = Path('/client')
    files = {
        'PlayOnlineViewer/viewer/com/polcore.dll': 'client-com-stub.dll',
        'PlayOnlineViewer/pol.exe': 'loader-stub.exe',
        'FINAL FANTASY XI/FFXi.dll': 'client-com-stub.dll',
        'FINAL FANTASY XI/FFXiMain.dll': 'client-com-stub.dll',
        'FINAL FANTASY XI/boot loader/xiloader.exe': 'filtered-login-stub.exe',
    }
    for relative, source in files.items():
        target = client / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(Path('/fixtures') / source, target)
    manifest = {'format': 1, 'generation': str(uuid.uuid4()), 'region': 'US',
                'pol': 'PlayOnlineViewer', 'core': 'PlayOnlineViewer/viewer/com/polcore.dll',
                'game': 'FINAL FANTASY XI', 'loader': 'FINAL FANTASY XI/boot loader/xiloader.exe',
                'key_files': {name: hashlib.sha256((client / name).read_bytes()).hexdigest() for name in files},
                'inventory_sha256': '0' * 64}
    Path('/session/client-manifest.json').write_text(json.dumps(manifest))
    runtime = json.loads(Path('/opt/lsb/fex-bundle.json').read_text())
    Path('/prefix/lsb-runtime-engine.json').write_text(json.dumps({'engine': 'fex', 'runtime': runtime['sha256']}))


def host_proof():
    active = Path('/ci-filter/active')
    deadline = time.monotonic() + 10
    while not active.exists() and time.monotonic() < deadline:
        time.sleep(.02)
    assert active.read_bytes() == MARKER, 'Actual host launch-filter marker absent'
    preflight = Path('/ci-filter/preflight')
    assert preflight.read_bytes() == PREFLIGHT, 'Production filtered preflight absent'
    for path in (active, preflight):
        try:
            with path.open('ab'):
                pass
        except PermissionError:
            continue
        raise AssertionError('Guest can rewrite the host filter proof')


def consume_native_surface(process, session, logs, trial):
    """Drive the current production helper through the real Surface protocol.

    This consumer returns write ownership only after reading the readonly map.
    The separate filtered X11 fixture checks exact pixels, resize and unchanged
    frames; these requests prove the selected helper also serves this launch.
    """
    expected_capture, expected_pacing = (90, 'relative') if trial == 'capture90' else (60, 'deadline' if trial == 'capture_deadline' else 'relative')
    socket_path, pixels = session / 'native-display.sock', session / 'framebuffer.bin'
    log = logs / 'native-display.log'
    deadline = time.monotonic() + 40
    while True:
        assert process.poll() is None, 'Production launch exited before native frames were consumed'
        text = log.read_text(errors='replace') if log.is_file() else ''
        matches = re.findall(r'^LSB_NATIVE_CONFIG pid=(\d+) display_fps=(\d+) capture_fps=(\d+) pacing=(\S+)\s*$', text, re.M)
        if matches and socket_path.exists() and pixels.exists():
            break
        assert time.monotonic() < deadline, 'Current native bridge did not become ready'
        time.sleep(.02)
    assert len(matches) == 1, matches
    pid, display, capture, pacing = matches[0]
    assert (int(display), int(capture), pacing) == (60, expected_capture, expected_pacing), matches
    assert 'LSB native Surface bridge ready, cap=60; X11 readback retained' in text, text[-4000:]
    assert stat.S_ISSOCK(socket_path.stat().st_mode) and stat.S_IMODE(socket_path.stat().st_mode) == 0o600
    assert stat.S_ISREG(pixels.stat().st_mode) and stat.S_IMODE(pixels.stat().st_mode) == 0o600
    assert pixels.stat().st_size == 1280 * 720 * 4, 'Unexpected shared native framebuffer size'
    headers = []
    with pixels.open('rb') as backing, mmap.mmap(backing.fileno(), 0, access=mmap.ACCESS_READ) as mapped:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
            client.settimeout(5)
            client.connect(str(socket_path))
            started = time.monotonic()
            for index in range(10):
                client.sendall(b'\x01')
                data = b''
                while len(data) < 32:
                    part = client.recv(32 - len(data))
                    assert part, 'Native producer disconnected before its frame header'
                    data += part
                header = struct.unpack('!8I', data)
                magic, width, height, stride, capture_us, sequence, size, flags = header
                assert magic == 0x4c534631 and (width, height, stride) == (1280, 720, 1280 * 4), header
                assert flags & 1, 'Current launch did not use native MIT-SHM readback'
                assert not flags & ~3 and size == (0 if flags & 2 else width * height * 4), header
                assert sequence > (headers[-1][5] if headers else 0), header
                if index == 0:
                    assert not flags & 2, 'First consumer request must publish a complete frame'
                    snapshot = mapped[:]
                    time.sleep(.04)
                    assert mapped[:] == snapshot, 'Producer overwrote pixels while the consumer owned them'
                elif flags & 2:
                    assert capture_us == 0, header
                client.setblocking(False)
                try:
                    assert not client.recv(1), 'Native pixels were sent over the header socket'
                except BlockingIOError:
                    pass
                finally:
                    client.settimeout(5)
                headers.append(header)
            elapsed = time.monotonic() - started
    assert elapsed >= 7 / expected_capture, 'Native producer ignored the selected capture cap'
    deadline = time.monotonic() + 5
    while True:
        records = []
        for line in log.read_text(errors='replace').splitlines():
            try:
                record = json.loads(line)
            except ValueError:
                continue
            if record.get('transport') == 'shared-file-v1':
                records.append(record)
        if sum(record['requests'] for record in records) == len(headers):
            break
        assert process.poll() is None, 'Production helper exited before its final consumer metrics'
        assert time.monotonic() < deadline, 'Current native consumer metrics were not flushed'
        time.sleep(.02)
    assert all((record['display_fps'], record['capture_fps'], record['pacing']) ==
               (60, expected_capture, expected_pacing) for record in records), records
    return {'pid': int(pid), 'display_fps': 60, 'capture_fps': expected_capture,
            'pacing': expected_pacing, 'requests': len(headers), 'elapsed_ms': round(elapsed * 1000),
            'readonly_ownership_verified': True, 'header_only_socket': True, 'metrics': records}


def launch(cycle, trial='none'):
    assert cycle in ('1', '2', '3', '4', '5')
    assert trial in ('none', 'tiler_mode', 'short_jit', 'immediate_present', 'capture90', 'capture_deadline', 'frame_latency')
    for directory in ('/prefix', '/session', '/client', '/logs'):
        Path(directory).mkdir(exist_ok=True)
    host_proof()
    if cycle == '1':
        prepare_client()
    else:
        assert Path('/prefix/lsb-prefix-ready.json').is_file(), 'First filtered launch did not leave a ready prefix'
    session, logs = Path('/session'), Path('/logs')
    child_dxvk_directory = session / 'filtered-child-dxvk'
    shutil.rmtree(child_dxvk_directory, ignore_errors=True)
    for name in ('stop', 'status.json', 'loader-process.json', 'loader-check.json',
                 'login-fixture.json', 'filtered-graphics.log'):
        (session / name).unlink(missing_ok=True)
    for name in ('dxvk-compatibility.log', 'graphics-tuning.log', 'native-display.log'):
        (logs / name).unlink(missing_ok=True)
    request = {'format': 1, 'session_id': str(uuid.uuid4()), 'engine': 'fex',
               'action': 'launch', 'renderer': 'turnip26', 'audio': False,
               'fex_x87': True, 'dxvk_version': '2.7.1', 'dxvk_two_compilers': True,
               'turnip_sysmem': False, 'dxvk_staged_buffers': False,
               'performance_trial': trial, 'proot_acceleration': True,
               'native_surface': True, 'display_fps': 60, 'shm_upload': True}
    receipt = {'format': 1, 'session_id': request['session_id'], 'requested': 'syscall_filter',
               'preflight_passed': True, 'launch_observed': True, 'mode': 'syscall_filter',
               'reason': 'ci_host_marker_observed', 'preflight_ms': 0}
    (logs / 'proot-acceleration.json').write_text(json.dumps(receipt))
    (session / 'request.json').write_text(json.dumps(request))
    # Fixed fake login matches the existing synthetic loader. It is consumed
    # only through the ordinary production pipe; no game or server is contacted.
    payload = b'LSBLOGIN1\n127.0.0.1\nLSB_private_account\nLSB "quoted" & % ! \\ tail\\\n'
    process = subprocess.Popen([sys.executable, '/opt/lsb/supervisor.py'], stdin=subprocess.PIPE)
    try:
        process.stdin.write(payload)
        process.stdin.close()
        native_consumer = consume_native_surface(process, session, logs, trial)
        process.wait(timeout=300)
    finally:
        if process.poll() is None:
            (session / 'stop').touch()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    # Keep the actual synthetic child's DXVK log distinct from its pixel
    # stdout and the production warmup. Clear it each cycle so an earlier
    # successful device cannot stand in for the current Windows child.
    child_dxvk_files = sorted(child_dxvk_directory.glob('*.log'))
    for path in child_dxvk_files:
        data = path.read_bytes()
        assert b'LSB_private_account' not in data and b'quoted' not in data and 'quoted'.encode('utf-16le') not in data, path
        shutil.copyfile(path, logs / ('filtered-child-dxvk-' + cycle + '-' + path.name))
    state = json.loads((session / 'status.json').read_text())
    assert process.returncode == 0 and state['phase'] == 'completed', state
    assert state['session_id'] == request['session_id']
    assert state['fex_execution_verified'] and state['fex_launch_arithmetic']['child_environment_verified'], state
    assert state['dxvk_selected'] == '2.7.1' and 'dxvk_fallback' not in state, state
    acceleration = state['runtime_acceleration']
    assert acceleration['active'] == 'syscall_filter' and acceleration['host_launch_observed'], acceleration
    assert acceleration['host_preflight'] == 'passed' and acceleration['compiler_threads'] == 2, acceleration
    tuning = state['graphics_tuning']
    assert tuning['active'] == {'turnip_sysmem': False, 'dxvk_two_compilers': True, 'dxvk_staged_buffers': False}, tuning
    assert tuning['compiler_threads'] == 2 and 'fallback' not in tuning, tuning
    assert tuning.get('verified') is True and tuning.get('combined_dxvk_check') is True, tuning
    performance = state['performance_trial']
    assert performance['requested'] == trial and performance['active'] == trial, performance
    if trial in ('capture90', 'capture_deadline'):
        assert performance['verified'] and performance['host_filtering_confirmed'], performance
        native = state['native_surface_configuration']
        assert all(native[key] == native_consumer[key] for key in ('pid', 'display_fps', 'capture_fps', 'pacing')), (native, native_consumer)
        assert all(performance['native_configuration'][key] == native_consumer[key] for key in ('pid', 'display_fps', 'capture_fps', 'pacing')), performance
        manifest = json.loads(Path('/opt/lsb/presentation-bundle.json').read_text())
        assert performance['native_configuration']['helper_sha256'] == manifest['sha256'], performance
        assert performance['native_configuration']['mapped_file_bytes'] == 1280 * 720 * 4, performance
        assert performance['native_configuration']['mapped_file_owner_only'] is True, performance
    assert state['native_surface_requested'] and 'native_surface_fallback' not in state and state['shm_upload_active'], state
    launch_report = json.loads((logs / 'client-launch.json').read_text())
    assert launch_report['session_id'] == request['session_id'] and launch_report['status'] == 'exited', launch_report
    assert launch_report['process']['child_exit'] == 0 and launch_report['process']['observation']['ffxi_window_seen'], launch_report
    assert launch_report['authentication_verified'] is False and launch_report['world_entry_verified'] is False
    assert json.loads((session / 'login-fixture.json').read_text())['arguments_and_cwd_match']
    child = (session / 'filtered-graphics.log').read_text(errors='replace')
    child_dxvk_path = child_dxvk_directory / 'texture-submission_d3d9.log'
    assert child_dxvk_path.is_file(), ('Synthetic child DXVK log absent', [path.name for path in child_dxvk_files])
    child_dxvk = child_dxvk_path.read_text(errors='replace')
    assert re.findall(r'^info:\s+DXVK: v(\S+)\s*$', child_dxvk, re.M) == ['2.7.1'], child_dxvk[-4000:]
    assert re.findall(r'^info:\s+DXVK: Using (\d+) compiler threads\s*$', child_dxvk, re.M) == ['2'], child_dxvk[-4000:]
    assert re.search(r'^info:\s+(?:Found config env:\s+)?dxvk\.numCompilerThreads = 2\s*$', child_dxvk, re.M), child_dxvk[-4000:]
    assert re.search(r'^LSB_TEXTURE_SUBMISSIONS formats=3 frames=4 draws=1512 samples=144 x87=[0-9a-f]{4} PASS\s*$', child, re.M), child[-4000:]
    assert 'LSB_TEXTURE_PATHS up=504 indexed16=504 indexed32=504' in child and ' FAIL' not in child, child[-4000:]
    option = {'tiler_mode': 'dxvk.tilerMode = False',
              'immediate_present': 'd3d9.presentInterval = 0',
              'frame_latency': 'd3d9.maxFrameLatency = 1'}.get(trial)
    if option:
        assert re.search(r'^info:\s+(?:Found config env:\s+)?' + re.escape(option) + r'\s*$', child_dxvk, re.M), child_dxvk[-4000:]
    if trial == 'short_jit':
        assert re.findall(r'^LSB_TEXTURE_CONFIG multiblock=(\S+)\s*$', child, re.M) == ['0'], child[-4000:]
    # No old automatic-worker device may be opened ahead of the verified
    # two-worker check. Keep all finite production budgets and legacy gates.
    assert not (logs / 'dxvk-compatibility.log').exists(), 'Filtered startup still performs redundant unqualified device warmup'
    assert all(not (session / name).exists() for name in ('display.sock', 'native-display.sock', 'framebuffer.bin')), 'Display leaked after clean launch exit'
    for path in [*logs.glob('*'), *session.glob('*.json'), session / 'filtered-graphics.log']:
        if path.is_file():
            data = path.read_bytes()
            assert b'LSB_private_account' not in data and b'quoted' not in data and 'quoted'.encode('utf-16le') not in data, path
    result = {'format': 1, 'cycle': int(cycle), 'trial': trial, 'runtime': state, 'launch': launch_report,
              'native_consumer': native_consumer,
              'inherited_graphics': {'dxvk': '2.7.1', 'compiler_threads': 2,
                                    'draws': 1512, 'pixel_samples': 144, 'passed': True},
              'interpretation': 'Real filtered ARM64 PRoot/FEX launch and inherited DXVK configuration; independent synthetic texture pixel oracle on CI lavapipe, not Thor/Adreno or live login.'}
    (logs / ('filtered-launch-' + cycle + '.json')).write_text(json.dumps(result, indent=2))
    (logs / ('filtered-graphics-' + cycle + '.log')).write_text(child)
    shutil.copyfile(logs / 'graphics-tuning.log', logs / ('graphics-tuning-' + cycle + '.log'))
    shutil.copyfile(logs / 'native-display.log', logs / ('native-display-' + cycle + '.log'))
    print('PASS: filtered gameplay launch ' + cycle + ' trial=' + trial + ' actual FEX/DXVK 2.7.1, inherited two workers, 144 pixels and clean shutdown', flush=True)


if __name__ == '__main__':
    if sys.argv[1:2] == ['observe']:
        observe(sys.argv[2:])
    elif len(sys.argv) in (3, 4) and sys.argv[1] == 'launch':
        launch(sys.argv[2], sys.argv[3] if len(sys.argv) == 4 else 'none')
    else:
        raise SystemExit('Expected observe <proof> <stage> <private-log> <logs> <command...> or launch <1|2|3|4|5> [trial]')
