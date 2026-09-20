"""Registration of an isolated working copy. Never sees the managed import."""
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import time

CLIENT = Path('/client')


def client_path(relative):
    if relative == '.':
        return CLIENT
    if not isinstance(relative, str) or len(relative) > 220 or not relative:
        raise ValueError('Invalid client path')
    parts = relative.split('/')
    if any(p in ('', '.', '..') or p.endswith((' ', '.')) or
           any(ord(c) < 32 or c in ':\\"<>|?*' for c in p) for p in parts):
        raise ValueError('Unsafe client path')
    path = CLIENT.joinpath(*parts)
    if not path.resolve().is_relative_to(CLIENT.resolve()):
        raise ValueError('Client path escapes working copy')
    walk = CLIENT
    for part in parts:
        walk = walk / part
        if walk.is_symlink():
            raise ValueError('Client path contains a symbolic link')
    return path


def windows_path(relative):
    client_path(relative)
    return 'D:\\' + (relative.replace('/', '\\') if relative != '.' else '')


def imports(path):
    """Read bounded PE32 import names for diagnostics; never execute an image here."""
    with path.open('rb') as f:
        header = f.read(64)
        if len(header) != 64 or header[:2] != b'MZ':
            raise ValueError('Missing PE header: ' + path.name)
        offset = struct.unpack_from('<I', header, 60)[0]
        if offset > 16 * 1024 * 1024:
            raise ValueError('PE offset exceeds limit')
        f.seek(offset); pe = f.read(24)
        if len(pe) != 24 or pe[:6] != b'PE\0\0\x4c\x01':
            raise ValueError('Expected PE32 x86: ' + path.name)
        sections, optional_size = struct.unpack_from('<H', pe, 6)[0], struct.unpack_from('<H', pe, 20)[0]
        if sections > 96 or optional_size < 112 or optional_size > 4096:
            raise ValueError('Invalid PE section table')
        optional = f.read(optional_size)
        if len(optional) != optional_size or optional[:2] != b'\x0b\x01':
            raise ValueError('Expected PE32 optional header')
        import_rva = struct.unpack_from('<I', optional, 104)[0]
        ranges = []
        for _ in range(sections):
            section = f.read(40)
            if len(section) != 40: raise ValueError('Truncated PE sections')
            size, rva, raw_size, raw = struct.unpack_from('<IIII', section, 8)
            ranges.append((rva, raw_size, raw))
        def address(rva, count):
            for start, size, raw in ranges:
                if start <= rva and rva + count <= start + size:
                    return raw + rva - start
            raise ValueError('PE import points outside file-backed sections')
        names = []
        if not import_rva: return names
        for i in range(512):
            f.seek(address(import_rva + i * 20, 20)); desc = f.read(20)
            if len(desc) != 20: raise ValueError('Truncated PE imports')
            if desc == b'\0' * 20: return names
            name_rva = struct.unpack_from('<I', desc, 12)[0]
            f.seek(address(name_rva, 1)); name = f.read(256).split(b'\0', 1)[0]
            if len(name) >= 256 or not re.fullmatch(rb'[A-Za-z0-9_.-]+', name):
                raise ValueError('Invalid PE import name')
            names.append(name.decode('ascii'))
        raise ValueError('PE import table exceeds limit')


def validate_manifest(data):
    fields = {'format', 'generation', 'region', 'pol', 'core', 'game', 'loader', 'key_files', 'inventory_sha256'}
    if set(data) != fields or data['format'] != 1 or data['region'] not in ('US', 'EU', 'JP'):
        raise ValueError('Invalid initialization manifest')
    if not re.fullmatch(r'[0-9a-f-]{36}', data['generation']):
        raise ValueError('Invalid generation')
    pol, core, game = (client_path(data[k]) for k in ('pol', 'core', 'game'))
    if not core.is_relative_to(pol) or core.name.lower() != ('polcoreeu.dll' if data['region'] == 'EU' else 'polcore.dll'):
        raise ValueError('Core and region do not match the installed viewer')
    if any(p.lower() == 'patchfiles' for p in core.parts):
        raise ValueError('Patch-cache DLL cannot be initialized')
    if pol == game or data['core'] not in data['key_files']:
        raise ValueError('Invalid installation roots')
    keys = data['key_files']
    expected = {data['core'], str(PurePosixPath(data['game']) / 'FFXi.dll').lower(),
                str(PurePosixPath(data['game']) / 'FFXiMain.dll').lower(),
                str(PurePosixPath(data['pol']) / 'pol.exe').lower()}
    # Paths retain the user's filename case; compare required entries case-insensitively.
    if {k.lower() for k in keys} != {k.lower() for k in expected} | ({data['loader'].lower()} if data['loader'] else set()):
        raise ValueError('Initialization key-file inventory is incomplete')
    dependency_inventory = {}
    for relative, digest in keys.items():
        p = client_path(relative)
        if not re.fullmatch(r'[0-9a-f]{64}', digest): raise ValueError('Invalid key-file checksum')
        h = hashlib.sha256()
        with p.open('rb') as f:
            for chunk in iter(lambda: f.read(1024*1024), b''): h.update(chunk)
        if h.hexdigest() != digest:
            raise ValueError('Working key file changed: ' + relative + '. Create a new preparation from the import.')
        dependency_inventory[relative] = imports(p)
    return dependency_inventory


def initialize(supervisor):
    # Imported here to avoid a second module instance when supervisor.py is __main__.
    session, logs = Path('/session'), Path('/logs')
    manifest = json.loads((session / 'client-manifest.json').read_text())
    dependency_inventory = validate_manifest(manifest)
    report = {'format': 1, 'generation': manifest['generation'], 'session_id': supervisor.req['session_id'],
              'status': 'running', 'region': manifest['region'], 'inventory_sha256': manifest['inventory_sha256'],
              'imports': dependency_inventory, 'steps': [], 'game_started': False}
    def record():
        temp = logs / 'client-initialization.new'; temp.write_text(json.dumps(report, indent=2)+'\n'); temp.replace(logs/'client-initialization.json')
        supervisor.status(initialization=report)
    record()
    try:
        if supervisor.req.get('action') in ('installer','repair-launcher'):
            installer = Path('/session/prerequisite.exe')
            h = hashlib.sha256()
            with installer.open('rb') as f:
                for chunk in iter(lambda: f.read(1024*1024), b''): h.update(chunk)
            digest = h.hexdigest()
            step = {'name': 'user_prerequisite_installer', 'sha256': digest, 'status': 'running'}
            report['steps'].append(step); record(); supervisor.status('running_prerequisite_installer')
            result = session/'client-step.json'; result.unlink(missing_ok=True)
            proc = supervisor.spawn(['/usr/local/bin/box64', '/opt/wine/bin/wine', r'P:\client-init.exe', 'installer'], 'prerequisite.log')
            # The native worker retains full Windows exit 3010, which Unix otherwise truncates.
            # Interactive official redistributable; no implicit EULA acceptance or silent switches.
            try:
                supervisor.wait(proc, 1200, 'Prerequisite installer')
            finally:
                if result.is_file(): step['result'] = json.loads(result.read_text())
                step['exit_code'] = proc.poll(); record()
            receipt = step.get('result', {})
            if receipt.get('operation') != 'installer' or receipt.get('ok') is not True or receipt.get('child_exit') not in (0,3010):
                raise RuntimeError('Prerequisite installer did not return a successful Windows receipt')
            step.update(status='completed'); record()
            supervisor.wine(['wineboot', '-r'], 180, 'Restart staged Windows after prerequisite installation')
            validate_manifest(manifest)
        region = manifest['region']
        steps = [('registry', ['registry', region, windows_path(manifest['pol']), windows_path(manifest['game'])])]
        for name in ('core', 'FFXi.dll', 'FFXiMain.dll'):
            relative = manifest['core'] if name == 'core' else next(k for k in manifest['key_files'] if k.lower() == str(PurePosixPath(manifest['game'])/name).lower())
            steps.append(('register_'+name, ['register', windows_path(relative)]))
        entry = next(k for k in manifest['key_files'] if k.lower() == str(PurePosixPath(manifest['game'])/'FFXi.dll').lower())
        steps.extend([('activate_pol', ['com', region, 'pol', windows_path(manifest['core'])]),
                      ('activate_ffxi', ['com', region, 'ffxi', windows_path(entry)])])
        for name, args in steps:
            step = {'name': name, 'status': 'running'}; report['steps'].append(step); record()
            supervisor.status('client_'+name)
            result = session/'client-step.json'; result.unlink(missing_ok=True)
            proc = supervisor.spawn(['/usr/local/bin/box64', '/opt/wine/bin/wine', r'P:\client-init.exe', *args], name+'.log')
            try:
                supervisor.wait(proc, 90, 'Client step '+name)
            finally:
                if result.is_file(): step['result'] = json.loads(result.read_text())
                step['exit_code'] = proc.poll(); record()
            result_data = step.get('result', {})
            if result_data.get('ok') is not True or result_data.get('bits') != 32 or result_data.get('operation') != args[0]:
                raise RuntimeError('Missing or failed 32-bit receipt for '+name)
            step['status'] = 'passed'; record()
        report['status'] = 'passed'; record()
        supervisor.status('completed', automatic_checks_passed=True, initialization_passed=True)
    except BaseException as error:
        report['status'] = 'failed'; report['error'] = str(error) or type(error).__name__
        if report['steps'] and report['steps'][-1]['status'] == 'running': report['steps'][-1]['status'] = 'failed'
        record(); raise
