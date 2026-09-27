"""Compile the phone-selected source archive with the real Android backend.

This deliberately starts from GitHub's archive, without Git metadata or mesh
submodules, just like Fetch selected source in the app. It does not start a
database or server, and never substitutes prebuilt server executables.
"""

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tarfile
import time
import traceback
import urllib.request

import source_patch_integration
import sol_key_integration
import accept_loop_integration


REVISION = '16281a81de58acfb315b639d9b79aaacd52a64f2'
EXPECTED_CLIENT = '30260904_1'
ARCHIVE_URL = f'https://github.com/LandSandBoat/server/archive/{REVISION}.tar.gz'
ARTIFACTS = Path('/artifacts')
WORK = Path('/tmp/lsb-real-source-build')
SOURCE = WORK / 'import' / f'server-{REVISION}'
STAGING = WORK / 'staging'


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def source_fingerprint(root):
    digest = hashlib.sha256()
    count = 0
    for path in sorted(root.rglob('*')):
        if path.is_file():
            digest.update(str(path.relative_to(root)).encode() + b'\0')
            digest.update(sha256(path).encode() + b'\n')
            count += 1
    return dict(sha256=digest.hexdigest(), files=count)


def capture(args, **kwargs):
    result = subprocess.run(args, capture_output=True, text=True, errors='replace',
                            timeout=60, **kwargs)
    return result, result.stdout + result.stderr


def download_archive():
    archive = WORK / 'server.tar.gz'
    for attempt in range(1, 4):
        try:
            request = urllib.request.Request(ARCHIVE_URL, headers={'User-Agent': 'LSB-Android-source-build'})
            with urllib.request.urlopen(request, timeout=120) as response, archive.open('wb') as output:
                shutil.copyfileobj(response, output)
            return archive
        except OSError:
            if attempt == 3:
                raise
            time.sleep(attempt * 2)


def runtime_smoke(name):
    # Immediate binding records the executable's allocator resolutions even
    # when the help path does not happen to call every imported malloc-family
    # function. No LD_PRELOAD is used: the executable must link jemalloc itself.
    env = dict(os.environ, LC_ALL='C', LD_DEBUG='bindings', LD_BIND_NOW='1')
    env.pop('LD_PRELOAD', None)
    result = subprocess.run([str(STAGING / name), '--help'], cwd=STAGING,
                            capture_output=True, text=True, errors='replace',
                            timeout=60, env=env)
    (ARTIFACTS / 'logs' / f'{name}-help.txt').write_text(result.stdout)
    (ARTIFACTS / 'logs' / f'{name}-bindings.log').write_text(result.stderr)
    if result.returncode != 0 or 'usage' not in result.stdout.lower():
        raise AssertionError(f'{name} --help did not exit successfully with usage text')
    # glibc's LD_DEBUG output includes the originating ELF, destination ELF and
    # symbol. Inspect bindings from this executable, not unrelated libraries.
    bindings = {}
    allocator_symbols = ('malloc', 'calloc', 'realloc', 'free')
    for line in result.stderr.splitlines():
        match = re.search(r'binding file (.+?) \[\d+\] to (.+?) \[\d+\]:.*symbol [`\']([^\']+)\'', line)
        if match and Path(match.group(1)).name == name and match.group(3) in allocator_symbols:
            bindings[match.group(3)] = match.group(2)
    if 'malloc' not in bindings:
        raise AssertionError(f'{name}: dynamic loader did not record a malloc binding')
    incorrect = {symbol: provider for symbol, provider in bindings.items()
                 if Path(provider).name != 'libjemalloc.so.2'}
    if incorrect:
        raise AssertionError(f'{name}: allocator symbols bound outside jemalloc: {incorrect}')
    return dict(help_exit=result.returncode, allocator_bindings=bindings,
                sha256=sha256(STAGING / name), size=(STAGING / name).stat().st_size)


def preserve_cmake_evidence():
    build = STAGING / 'build'
    destination = ARTIFACTS / 'cmake'
    if not build.exists():
        return
    destination.mkdir(exist_ok=True)
    for path in build.rglob('*'):
        if path.is_file() and path.name in ('CMakeCache.txt', 'CMakeConfigureLog.yaml',
                                            'CMakeOutput.log', 'CMakeError.log',
                                            'link.txt', 'flags.make'):
            target = destination / path.relative_to(build)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)


def main():
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    WORK.mkdir(parents=True, exist_ok=False)
    for folder in ('logs', 'run', 'state'):
        (ARTIFACTS / folder).mkdir(exist_ok=True)
    report = dict(source_repository='LandSandBoat/server', source_revision=REVISION,
                  source_archive_url=ARCHIVE_URL, expected_client=EXPECTED_CLIENT,
                  machine=platform.machine(), status='started', binaries={})
    backend = None
    started = time.monotonic()
    try:
        if platform.machine() != 'aarch64':
            raise AssertionError('This gate must run natively on Linux ARM64')
        tools_log = []
        for args in (['gcc-15', '--version'], ['g++-15', '--version'], ['cmake', '--version'],
                     ['dpkg-query', '-W', 'libjemalloc2', 'libjemalloc-dev', 'python3']):
            result, output = capture(args)
            tools_log.append('$ ' + ' '.join(args) + '\n' + output)
            if result.returncode:
                raise AssertionError('Toolchain inspection failed: ' + ' '.join(args))
        (ARTIFACTS / 'toolchain.log').write_text('\n'.join(tools_log))
        print(f'Downloading LandSandBoat/server at {REVISION}', flush=True)
        archive = download_archive()
        report['source_archive_sha256'] = sha256(archive)
        with tarfile.open(archive, 'r:gz') as source_archive:
            source_archive.extractall(WORK / 'import', filter='data')
        assert SOURCE.is_dir() and not (SOURCE / '.git').exists(), 'Expected a source-only GitHub archive'
        report['source_without_git'] = True
        before = source_fingerprint(SOURCE)
        report['imported_source'] = before

        sys.path.insert(0, '/src/server')
        spec = importlib.util.spec_from_file_location('server_backend', '/src/server/manager.py')
        backend = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(backend)
        backend.LOGS = ARTIFACTS / 'logs'
        backend.RUN = ARTIFACTS / 'run'
        backend.STATE = ARTIFACTS / 'state'
        backend.INPUT = SOURCE
        info = backend.source_info(SOURCE)
        report['source_info'] = info
        assert info['expected_client'] == EXPECTED_CLIENT, info
        backend.snapshot_source(SOURCE, STAGING, recover_build_binaries=False)
        assert all(not (STAGING / name).exists() for name in backend.PROCESSES)
        print('Building the imported source through manager.build with two workers', flush=True)
        backend.build(STAGING, 2)
        backend.validate_binaries(STAGING)
        report['backend_build_report'] = json.loads((backend.LOGS / 'build-report.json').read_text())
        report['source_patch_boundaries'] = source_patch_integration.check(STAGING, ARTIFACTS / 'logs')
        report['accept_loop_lifetimes'] = accept_loop_integration.check(STAGING, ARTIFACTS / 'logs')
        report['sol_key_lookups'] = sol_key_integration.check(STAGING, ARTIFACTS / 'logs')
        for name in backend.PROCESSES:
            print(f'Checking real {name} startup and dynamic allocator binding', flush=True)
            report['binaries'][name] = runtime_smoke(name)
        assert source_fingerprint(SOURCE) == before, 'Compilation modified the imported source'
        report['imported_source_unchanged'] = True
        binaries = ARTIFACTS / 'binaries'
        binaries.mkdir()
        for name in backend.PROCESSES:
            shutil.copy2(STAGING / name, binaries / name)
        report['status'] = 'passed'
        print('PASS: all four selected-source ARM64 executables compile, start and bind malloc to jemalloc', flush=True)
    except BaseException as error:
        report['status'] = 'failed'
        report['error'] = str(error)
        (ARTIFACTS / 'logs' / 'failure.log').write_text(traceback.format_exc())
        raise
    finally:
        if backend is not None:
            backend.stop_children()
        report['elapsed_seconds'] = round(time.monotonic() - started, 1)
        preserve_cmake_evidence()
        (ARTIFACTS / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        if report['status'] == 'passed':
            shutil.copy2(ARTIFACTS / 'report.json', ARTIFACTS / 'binaries' / 'build-report.json')


if __name__ == '__main__':
    main()
