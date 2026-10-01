"""Exact ARM64 BFD 2.45 compatibility, separate from the system toolchain."""
from pathlib import Path
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request

VERSION = '2.45-7ubuntu1.2'
LIBDIR = Path('/opt/lsb-compat/binutils-2.45/lib')
CONFIG = Path('/etc/ld.so.conf.d/lsb-binutils-2.45.conf')
# Ubuntu questing-security/main/binary-arm64/Packages, retrieved 2026-09-25.
# Extract only these SONAMEs; never install old binutils packages or libbfd.so.
PACKAGES = (
    ('libbinutils', 800450,
     'b07eb33595ee9e00b2bf6b1af8885e62ddad432e8c2bdbe9ed69b6698db3884c',
     'libbfd-2.45-system.so'),
    ('libsframe2', 16336,
     '90501ac7289e12e9b7ce81b2c3f61b410bd2d59dd29ce30b3cf820460690187f',
     'libsframe.so.2'),
)
MIRRORS = ('https://ports.ubuntu.com/ubuntu-ports/',
           'https://old-releases.ubuntu.com/ubuntu/')


def download_package(folder, name, size, digest):
    filename = f'{name}_{VERSION}_arm64.deb'
    last_error = None
    for mirror in MIRRORS:
        url = mirror + 'pool/main/b/binutils/' + filename
        try:
            with urllib.request.urlopen(url, timeout=30) as response:
                content = response.read(size + 1)
        except (OSError, urllib.error.URLError) as error:
            last_error = error
            continue
        if len(content) != size or hashlib.sha256(content).hexdigest() != digest:
            raise RuntimeError('Compatibility package checksum/size mismatch: ' + filename)
        target = folder / filename
        target.write_bytes(content)
        return target, url
    raise RuntimeError('Could not download compatibility package ' + filename) from last_error


def check_loader(directory=None):
    env = dict(os.environ, LC_ALL='C')
    if directory is not None:
        env['LD_LIBRARY_PATH'] = str(directory)
    else:
        env.pop('LD_LIBRARY_PATH', None)
    # A fresh process resolves all transitive dependencies with RTLD_NOW. The
    # second check after ldconfig must work without any search-path override.
    subprocess.run([sys.executable, '-c',
                    "import ctypes; b=ctypes.CDLL('libbfd-2.45-system.so'); "
                    "b.bfd_init.restype=ctypes.c_uint; assert b.bfd_init() != 0"],
                   env=env, check=True, timeout=30)


def install():
    if subprocess.check_output(['dpkg', '--print-architecture'], text=True).strip() != 'arm64':
        raise RuntimeError('BFD compatibility requires the ARM64 server runtime')
    LIBDIR.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.bfd-', dir=LIBDIR.parent) as temporary:
        work = Path(temporary)
        staged = work / 'lib'
        staged.mkdir()
        provenance = []
        for name, size, digest, soname in PACKAGES:
            print('Installing server compatibility:', name, VERSION, flush=True)
            package, url = download_package(work, name, size, digest)
            unpacked = work / name
            subprocess.run(['dpkg-deb', '--extract', str(package), str(unpacked)], check=True)
            library = unpacked / 'usr/lib/aarch64-linux-gnu' / soname
            shutil.copyfile(library, staged / soname)
            (staged / soname).chmod(0o644)
            provenance.append(dict(package=name, version=VERSION, architecture='arm64',
                                   url=url, sha256=digest, soname=soname))
        check_loader(staged)
        # Downloads and loader checks finish before changing the live bundle.
        # The fixed-version files replace atomically, allowing interrupted retries.
        LIBDIR.mkdir(exist_ok=True)
        for _, _, _, soname in PACKAGES:
            os.replace(staged / soname, LIBDIR / soname)
        receipt = work / 'packages.json'
        receipt.write_text(json.dumps(provenance, indent=2) + '\n')
        os.replace(receipt, LIBDIR.parent / 'packages.json')
        config = CONFIG.with_suffix('.conf.new')
        config.write_text(str(LIBDIR) + '\n')
        os.replace(config, CONFIG)
        subprocess.run(['ldconfig'], check=True)
        check_loader()
    print('BFD 2.45 and SFrame 2 compatibility ready; system build tools retained.', flush=True)


if __name__ == '__main__':
    install()
