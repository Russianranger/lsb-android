"""Install missing map assets from the selected source's pinned submodules.

Source ZIPs omit gitlinks. This module fills those runtime data directories without
rebuilding binaries or changing existing mesh assets. Network access is limited to
GitHub metadata and two known mesh repositories; branch tips are never selected.
"""
import configparser
import hashlib
import json
import math
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import struct
import tarfile
import tempfile
import time
import urllib.request
import uuid
import zlib

REPOSITORIES = {
    'navmeshes': 'LandSandBoat/xiNavmeshes',
    'ximeshes': 'InoUno/ximeshes',
}
# Legacy build receipts did not retain a source commit. Only an exact match of
# the submodule declaration and runtime readers may use this compatibility pin.
# This establishes asset compatibility, not the identity of the original source.
COMPATIBILITY = [{
    'source': 'LandSandBoat/server@6d5a5137024e21602e37032f6e55a682971329be',
    'files': {
        '.gitmodules': 'eff6fb891e232a5ce1fc0c8defd46153f1db7477c695a33462d2e8de5c8dba27',
        'src/map/map_engine.cpp': '786c3c9c440f338d3c2d7abb806de5d4536160d0ff3ab20eafe253bb3e631c33',
        'src/map/navmesh/detour_navmesh.cpp': 'f8d97025fb51ca1073c616237e07c41bf9dfea3d3c5cd3bbae6857cb4ca879be',
        'src/map/ximesh/ximesh_impl.cpp': '2cc2897932a621c86c93f28cc86e9c28855593c84569b929ef3847432b580a60',
        'src/map/ximesh/ximesh_structs.h': 'da1a978bca7fd631023c07894d2c8ba19b2993752a13490e85852c2ce4210887',
        'src/map/zone.cpp': 'd783a853b4904d1f0775159a2efff5a619c6d953180704e42ea3939f40d8b8ea',
    },
    'pins': {
        'navmeshes': 'e8ca9f0419982d4da0eef8542d13f09865e6c556',
        'ximeshes': '906d725cb40ed4eeeaa8299bb95922dca31be2d5',
    },
}]
MAX_ARCHIVE_BYTES = 768 * 1024 * 1024
MAX_EXPANDED_BYTES = 2 * 1024 * 1024 * 1024
MAX_FILE_BYTES = 128 * 1024 * 1024
MAX_FILES = 20000
BLOCK = 1024 * 1024
CACHE_RECEIPT = '.lsb-mesh-cache.json'


def _cancel(cancelled):
    if cancelled and cancelled():
        raise InterruptedError('Map asset preparation stopped')


def _declarations(root):
    path = root / '.gitmodules'
    if not path.exists() and not path.is_symlink():
        return {}
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 128 * 1024:
        raise ValueError('Cannot safely read the source .gitmodules file')
    parser = configparser.ConfigParser(interpolation=None, strict=True)
    try:
        parser.read_string(path.read_text())
    except (configparser.Error, UnicodeError) as error:
        raise ValueError('Cannot read map submodules from the source .gitmodules file') from error
    declared = {}
    for section in parser.sections():
        name = parser.get(section, 'path', fallback='').strip()
        if name not in REPOSITORIES:
            if section in ('submodule \"navmeshes\"', 'submodule \"ximeshes\"'):
                raise ValueError('Unsupported map submodule path: ' + name)
            continue
        repository = REPOSITORIES[name]
        url = parser.get(section, 'url', fallback='').strip()
        if url not in ('https://github.com/' + repository, 'https://github.com/' + repository + '.git'):
            raise ValueError('Unsupported ' + name + ' submodule URL; import its map assets with the source')
        if name in declared:
            raise ValueError('Duplicate map submodule path: ' + name)
        declared[name] = repository
    return declared


def _nav_header(path, header):
    if len(header) < 48:
        raise ValueError('Truncated navigation mesh: ' + path.name)
    magic, version, tiles = struct.unpack_from('<III', header)
    values = struct.unpack_from('<5fii', header, 12)
    tile_ref, tile_bytes = struct.unpack_from('<II', header, 40)
    if (magic != 0x4D534554 or version != 1 or not tiles or tiles > 1048576
            or not all(math.isfinite(value) for value in values[:5])
            or values[3] <= 0 or values[4] <= 0 or values[5] <= 0 or values[6] <= 0
            or not tile_ref or not tile_bytes or tile_bytes > path.stat().st_size - 48):
        raise ValueError('Unsupported navigation mesh header: ' + path.name)


def _xi_header(path, header):
    try:
        decoded = zlib.decompressobj().decompress(header, 20)
    except zlib.error as error:
        raise ValueError('Invalid collision mesh: ' + path.name) from error
    if len(decoded) != 20:
        raise ValueError('Truncated collision mesh: ' + path.name)
    width, height, blocks, placements, block_count, placement_count, wide_search = struct.unpack('<HHIIHHI', decoded)
    grid_end = 20 + width * height * 4
    if (not width or not height or grid_end > 64 * 1024 * 1024
            or blocks < grid_end or placements < blocks
            or blocks >= 64 * 1024 * 1024 or placements >= 64 * 1024 * 1024):
        raise ValueError('Unsupported collision mesh header: ' + path.name)


def _inspect(path, kind, cancelled=None):
    """Validate real assets, not README files or the presence of a directory."""
    if not path.exists() and not path.is_symlink():
        return 0
    if path.is_symlink() or not path.is_dir():
        raise ValueError('Map asset path must be an ordinary directory: ' + kind)
    count = total = files = 0
    suffix = '.nav' if kind == 'navmeshes' else '.ximesh'
    for folder, directories, names in os.walk(path, followlinks=False):
        _cancel(cancelled)
        for name in directories:
            if (Path(folder) / name).is_symlink():
                raise ValueError('Map assets contain a symlink: ' + kind)
        for name in names:
            _cancel(cancelled)
            item = Path(folder) / name
            info = item.lstat()
            if not stat.S_ISREG(info.st_mode):
                raise ValueError('Map assets contain a nonregular file: ' + kind)
            files += 1
            total += info.st_size
            if files > MAX_FILES or total > MAX_EXPANDED_BYTES or info.st_size > MAX_FILE_BYTES:
                raise ValueError('Map assets exceed the supported size: ' + kind)
            with item.open('rb') as stream:
                header = stream.read(4096)
            if header.startswith(b'version https://git-lfs.github.com/spec/v1'):
                raise ValueError('Map assets contain Git LFS pointers; import the actual mesh files: ' + kind)
            if item.suffix.lower() == suffix:
                if kind == 'navmeshes':
                    _nav_header(item, header)
                else:
                    _xi_header(item, header)
                if Path(folder) == path:
                    count += 1
    return count


def validate(root):
    """Check declared runtime assets without downloads or any filesystem edits."""
    root = Path(root)
    result = {}
    for name, repository in _declarations(root).items():
        count = _inspect(root / name, name)
        if not count:
            raise ValueError('Missing ' + name + ' map assets; prepare the staged build or start the managed server to download its pinned assets')
        result[name] = dict(repository=repository, assets=count, provenance='existing-assets')
    return result


def _request(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers={
        'User-Agent': 'LSB-Android-map-assets', 'Accept': 'application/vnd.github+json',
    }), timeout=30)


def _tree(repository, commit, cancelled):
    _cancel(cancelled)
    url = 'https://api.github.com/repos/' + repository + '/git/trees/' + commit
    with _request(url) as response:
        data = response.read(2 * 1024 * 1024 + 1)
    _cancel(cancelled)
    if len(data) > 2 * 1024 * 1024:
        raise ValueError('Source gitlink metadata exceeds the supported size')
    try:
        tree = json.loads(data)
    except (ValueError, UnicodeError) as error:
        raise ValueError('Cannot read the selected source map gitlinks') from error
    if not isinstance(tree, dict) or tree.get('truncated') or not isinstance(tree.get('tree'), list):
        raise ValueError('Selected source gitlink metadata is incomplete')
    return tree['tree']


def _pins(root, identity, declared, cancelled):
    identity = identity if isinstance(identity, dict) else {}
    repository, commit = identity.get('repository', ''), identity.get('commit', '')
    if isinstance(repository, str) and re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository) and isinstance(commit, str) and re.fullmatch(r'[0-9a-fA-F]{40}', commit):
        entries = _tree(repository, commit, cancelled)
        result = {}
        for name in declared:
            matches = [entry for entry in entries if isinstance(entry, dict) and entry.get('path') == name]
            if (len(matches) != 1 or matches[0].get('mode') != '160000' or matches[0].get('type') != 'commit'
                    or not re.fullmatch(r'[0-9a-fA-F]{40}', str(matches[0].get('sha', '')))):
                raise ValueError('Selected source has no pinned ' + name + ' gitlink; import matching map assets')
            result[name] = dict(commit=matches[0]['sha'].lower(), provenance='selected-source-gitlink')
        return result
    if commit:
        raise ValueError('Build source identity is invalid; import matching map assets or fetch and build a recorded source revision')
    # Never substitute the currently fetched source or a branch tip for a legacy
    # build whose original commit was not recorded.
    for catalog in COMPATIBILITY:
        matches = True
        for relative, digest in catalog['files'].items():
            path = root / relative
            if path.is_symlink() or not path.is_file() or path.stat().st_size > 2 * 1024 * 1024 or hashlib.sha256(path.read_bytes().replace(b'\r\n', b'\n')).hexdigest() != digest:
                matches = False
                break
        if matches and all(name in catalog['pins'] for name in declared):
            return {name: dict(commit=catalog['pins'][name], provenance='compatible-loader-catalog',
                               compatibility_source=catalog['source']) for name in declared}
    raise ValueError('Missing map assets and this build has no verifiable source commit or known compatible mesh readers. Import the matching navmeshes/ximeshes directories, or fetch and build a recorded source revision.')


def _download(url, target, label, progress, cancelled):
    progress('Downloading ' + label + ' map assets...')
    received = 0
    last_report = time.monotonic()
    with _request(url) as response, target.open('xb') as output:
        length = response.headers.get('Content-Length')
        total = int(length) if length and length.isdigit() else None
        if total is not None and total > MAX_ARCHIVE_BYTES:
            raise ValueError('Map asset archive exceeds the download limit: ' + label)
        while True:
            _cancel(cancelled)
            block = response.read(BLOCK)
            if not block:
                break
            received += len(block)
            if received > MAX_ARCHIVE_BYTES:
                raise ValueError('Map asset archive exceeds the download limit: ' + label)
            output.write(block)
            if time.monotonic() - last_report >= 1:
                progress('Downloading ' + label + ': ' + str(received // (1024 * 1024)) + ' MiB' +
                         (' / ' + str(total // (1024 * 1024)) + ' MiB' if total else ''))
                last_report = time.monotonic()
        if total is not None and received != total:
            raise ValueError('Map asset download was incomplete: ' + label)
    _cancel(cancelled)
    progress('Downloaded ' + label + ': ' + str(received // (1024 * 1024)) + ' MiB; checking archive...')


def _extract(archive, destination, repository, commit, progress, cancelled):
    expected_root = repository.split('/')[1] + '-' + commit
    destination.mkdir()
    seen = set()
    count = total = 0
    last_report = time.monotonic()
    with tarfile.open(archive, 'r|gz') as source:
        for member in source:
            _cancel(cancelled)
            path = PurePosixPath(member.name)
            parts = path.parts
            if (not parts or path.is_absolute() or '\\' in member.name or '\x00' in member.name
                    or '..' in parts or parts[0] != expected_root or member.name in seen
                    or not (member.isfile() or member.isdir())):
                raise ValueError('Unsafe entry in map asset archive: ' + member.name[:160])
            seen.add(member.name)
            count += 1
            if count > MAX_FILES:
                raise ValueError('Map asset archive contains too many entries')
            if len(parts) == 1:
                if not member.isdir():
                    raise ValueError('Map archive root must be a directory')
                continue
            total += member.size
            if member.size < 0 or member.size > MAX_FILE_BYTES or total > MAX_EXPANDED_BYTES:
                raise ValueError('Expanded map asset archive exceeds the supported size')
            target = destination.joinpath(*parts[1:])
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            stream = source.extractfile(member)
            if stream is None:
                raise ValueError('Missing data in map asset archive')
            remaining = member.size
            with stream, target.open('xb') as output:
                while remaining:
                    _cancel(cancelled)
                    block = stream.read(min(BLOCK, remaining))
                    if not block:
                        raise ValueError('Truncated map asset archive')
                    output.write(block)
                    remaining -= len(block)
            if time.monotonic() - last_report >= 1:
                progress('Extracting ' + repository + ': ' + str(total // (1024 * 1024)) + ' MiB checked')
                last_report = time.monotonic()


def _copy(source, target, cancelled):
    def copy_file(first, second):
        _cancel(cancelled)
        with open(first, 'rb') as incoming, open(second, 'xb') as outgoing:
            while True:
                _cancel(cancelled)
                block = incoming.read(BLOCK)
                if not block:
                    break
                outgoing.write(block)
        return second
    shutil.copytree(source, target, copy_function=copy_file, ignore=shutil.ignore_patterns(CACHE_RECEIPT))


def _cache_files(path, cancelled):
    files = {}
    for item in sorted(path.rglob('*')):
        if not item.is_file() or item.name == CACHE_RECEIPT:
            continue
        _cancel(cancelled)
        digest = hashlib.sha256()
        with item.open('rb') as stream:
            while True:
                _cancel(cancelled)
                block = stream.read(BLOCK)
                if not block:
                    break
                digest.update(block)
        files[item.relative_to(path).as_posix()] = digest.hexdigest()
    return files


def _cache_valid(path, name, repository, pin, cancelled):
    if path.is_symlink() or (path.exists() and not path.is_dir()):
        raise ValueError('Map asset cache must be an ordinary directory: ' + name)
    if not path.exists():
        return False
    receipt = path / CACHE_RECEIPT
    if receipt.is_symlink() or not receipt.is_file() or receipt.stat().st_size > 2 * 1024 * 1024:
        return False
    try:
        data = json.loads(receipt.read_text())
        if (not isinstance(data, dict) or data.get('repository') != repository or data.get('commit') != pin
                or not _inspect(path, name, cancelled)):
            return False
        return data.get('files') == _cache_files(path, cancelled)
    except (ValueError, UnicodeError):
        return False


def _publish(ready, target, cancelled):
    _cancel(cancelled)
    backup = target.with_name('.' + target.name + '-previous-' + uuid.uuid4().hex)
    moved = target.exists()
    if moved:
        target.rename(backup)
    try:
        ready.rename(target)
    except BaseException:
        if moved:
            backup.rename(target)
        raise
    if moved:
        shutil.rmtree(backup)


def ensure(root, identity, progress=print, cancelled=None, cache=None):
    """Fill missing declared mesh directories; preserve all usable existing data.

    The caller stops the server and serializes preparation. A failed download or
    cancellation never publishes a partial directory. A completed directory may
    remain when a later submodule fails, making the next attempt resumable.
    """
    root = Path(root)
    declared = _declarations(root)
    existing = {name: _inspect(root / name, name, cancelled) for name in declared}
    missing = {name: repository for name, repository in declared.items() if not existing[name]}
    pins = _pins(root, identity, missing, cancelled) if missing else {}
    result = {name: dict(repository=repository, assets=existing[name], provenance='existing-assets')
              for name, repository in declared.items() if existing[name]}
    for name, repository in missing.items():
        _cancel(cancelled)
        pin = pins[name]
        progress('Preparing ' + name + ' from ' + repository + ' @ ' + pin['commit'][:12])
        cache_path = Path(cache) / (name + '-' + pin['commit']) if cache is not None else None
        if cache_path is not None:
            cache_path.parent.mkdir(parents=True, exist_ok=True)
            if cache_path.parent.is_symlink():
                raise ValueError('Map asset cache must be an ordinary directory')
        with tempfile.TemporaryDirectory(prefix='.mesh-prepare-', dir=root) as temporary:
            temporary = Path(temporary)
            ready = temporary / 'assets'
            cached = cache_path is not None and _cache_valid(cache_path, name, repository, pin['commit'], cancelled)
            if cached:
                progress('Copying cached ' + name + ' map assets...')
                _copy(cache_path, ready, cancelled)
            else:
                archive = temporary / 'download.tar.gz'
                _download('https://codeload.github.com/' + repository + '/tar.gz/' + pin['commit'],
                          archive, name, progress, cancelled)
                _extract(archive, ready, repository, pin['commit'], progress, cancelled)
            count = _inspect(ready, name, cancelled)
            if not count:
                raise ValueError('Downloaded ' + name + ' contains no usable map assets')
            if cache_path is not None and not cached:
                with tempfile.TemporaryDirectory(prefix='.mesh-cache-', dir=cache_path.parent) as cache_temporary:
                    saved = Path(cache_temporary) / 'assets'
                    _copy(ready, saved, cancelled)
                    receipt = dict(repository=repository, commit=pin['commit'], files=_cache_files(saved, cancelled))
                    (saved / CACHE_RECEIPT).write_text(json.dumps(receipt, sort_keys=True))
                    _publish(saved, cache_path, cancelled)
            # A placeholder may contain notes or a README. Retain those bytes;
            # only actual map assets come from the pinned submodule archive.
            target = root / name
            if target.exists():
                for folder, directories, names in os.walk(target):
                    relative = Path(folder).relative_to(target)
                    for directory in directories:
                        (ready / relative / directory).mkdir(parents=True, exist_ok=True)
                    for filename in names:
                        _cancel(cancelled)
                        destination = ready / relative / filename
                        destination.parent.mkdir(parents=True, exist_ok=True)
                        shutil.copyfile(Path(folder) / filename, destination)
            _publish(ready, target, cancelled)
            result[name] = dict(repository=repository, assets=count, **pin)
            progress(name + ' ready: ' + str(count) + ' map assets')
    return result
