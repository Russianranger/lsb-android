"""Read-only, bounded metadata for fixed PlayOnline files in the staged copy.

No directory tree, account file, registry value or file contents are exported.
Changes between snapshots are observations, not proof that repair completed.
"""
import errno
import hashlib
import os
from pathlib import Path
import stat
import time

MAX_FILE_BYTES = 16 * 1024 * 1024
MAX_DIRECTORY_ENTRIES = 4096
TIME_BUDGET_SECONDS = 2.0
CHUNK_BYTES = 128 * 1024


class _Unavailable(Exception):
    def __init__(self, state):
        self.state = state


def _budget(deadline):
    if time.monotonic() >= deadline:
        raise _Unavailable('budget_exhausted')


def _error(error):
    if error.errno == errno.ENOENT:
        return 'missing'
    if error.errno in (errno.ELOOP, errno.ENOTDIR):
        return 'unsafe_path'
    return 'unreadable'


def _root(path, deadline):
    # The caller supplies the already validated staged POL directory. Opening
    # every component relative to a descriptor also prevents ancestor symlinks.
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts:
        raise _Unavailable('unsafe_path')
    current = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in path.parts[1:]:
            _budget(deadline)
            child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                            dir_fd=current)
            os.close(current)
            current = child
        return current
    except BaseException:
        os.close(current)
        raise


def _child(directory, fixed_name, deadline):
    # Windows names are case insensitive; ambiguity is never resolved by
    # picking whichever filename happens to be returned first.
    match = None
    with os.scandir(directory) as entries:
        for count, entry in enumerate(entries):
            _budget(deadline)
            if count >= MAX_DIRECTORY_ENTRIES:
                raise _Unavailable('directory_limit')
            if entry.name.casefold() == fixed_name.casefold():
                if match is not None:
                    raise _Unavailable('ambiguous_path')
                match = entry.name
    if match is None:
        raise _Unavailable('missing')
    return match


def _identity(value):
    return (value.st_dev, value.st_ino, value.st_size,
            value.st_mtime_ns, value.st_ctime_ns)


def _file(root, relative, deadline):
    directory = os.dup(root)
    descriptor = None
    try:
        parts = relative.split('/')
        for part in parts[:-1]:
            name = _child(directory, part, deadline)
            child = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW,
                            dir_fd=directory)
            os.close(directory)
            directory = child
        name = _child(directory, parts[-1], deadline)
        descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK,
                             dir_fd=directory)
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode):
            return {'state': 'not_regular'}
        if before.st_size > MAX_FILE_BYTES:
            return {'state': 'too_large', 'bytes': before.st_size}
        digest = hashlib.sha256()
        read = 0
        while True:
            _budget(deadline)
            block = os.read(descriptor, min(CHUNK_BYTES, MAX_FILE_BYTES + 1 - read))
            if not block:
                break
            read += len(block)
            if read > MAX_FILE_BYTES:
                return {'state': 'changed_during_read'}
            digest.update(block)
        after = os.fstat(descriptor)
        linked = os.stat(name, dir_fd=directory, follow_symlinks=False)
        if read != before.st_size or _identity(before) != _identity(after) or _identity(after) != _identity(linked):
            return {'state': 'changed_during_read'}
        return {'state': 'ok', 'bytes': read, 'sha256': digest.hexdigest()}
    except _Unavailable as error:
        return {'state': error.state}
    except OSError as error:
        return {'state': _error(error)}
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(directory)


def snapshot(pol, region):
    """Snapshot a validated absolute staged viewer directory; never write it.

    A two-second shared budget and 16 MiB per-file limit bound normal local
    reads. Missing, unsafe and changing files remain fixed diagnostic states.
    Call before launching the viewer and after all viewer processes exit.
    """
    started = time.monotonic()
    deadline = started + TIME_BUDGET_SECONDS
    files = {
        'viewer_executable': 'pol.exe',
        'viewer_application': 'viewer/com/app.dll',
        'viewer_core': 'viewer/com/' + ('polcoreeu.dll' if region == 'EU' else 'polcore.dll'),
        'viewer_contents': 'viewer/contents/' + ('PolContents.dll' if region == 'JP' else 'polcontentsINT.dll'),
        'viewer_patch_version': 'patch.ver',
        'viewer_version_data': 'version.dat',
        'viewer_patch_settings': 'patch.ini',
    }
    # Only this exact US cache layout is established by the owner's source
    # inventory. Do not scan patchfiles or infer other regional cache layouts.
    if region == 'US':
        files['cached_viewer_core'] = 'patchfiles/PlayOnlineViewer/viewer/com/polcore.dll'
    root = None
    try:
        if region not in ('US', 'EU', 'JP'):
            raise _Unavailable('unsupported_region')
        root = _root(pol, deadline)
        observed = {label: _file(root, relative, deadline) for label, relative in files.items()}
    except _Unavailable as error:
        observed = {label: {'state': error.state} for label in files}
    except OSError as error:
        observed = {label: {'state': _error(error)} for label in files}
    finally:
        if root is not None:
            os.close(root)
    return {'format': 1, 'policy': 'fixed_viewer_file_metadata_only',
            'files': observed, 'elapsed_ms': max(0, int((time.monotonic() - started) * 1000))}
