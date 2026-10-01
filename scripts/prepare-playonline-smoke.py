#!/usr/bin/env python3
"""Fetch only the official viewer for an offline, disposable CI smoke check.

No proprietary bytes enter source control, runtime bundles, or CI artifacts.
The official 2019 multipart installer contains one independently compressed MSI
in part 5. A bounded, hash-pinned HTTP range avoids downloading the game data.
"""
import hashlib
from pathlib import Path
import shutil
import struct
import subprocess
import urllib.request
import zlib

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.tools/playonline-smoke'
URL = 'https://gdl.square-enix.com/ffxi/download/us/FFXIFullSetup_US.part5.rar'
START, END, TOTAL = 963220348, 1190951304, 1291746416
SLICE_SHA = '4607a2e1b5ec60b5614c710a04a6d16bd216ba9109afe47ec2423ba7ebc7b9e0'
MSI_SHA = '08a6d3ad218db466ec6d126215131d24c38cc7972dbf7127676e9d0799e7e032'
POL_SHA = '5c2d45bd277eaf815d2790fee79548a88e25679986ca483773c69382ef92c404'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def block(body):
    assert len(body) < 128
    encoded = bytes([len(body)]) + body
    return struct.pack('<I', zlib.crc32(encoded)) + encoded


def main():
    WORK.mkdir(parents=True, exist_ok=True)
    fragment = WORK / 'viewer.rar-fragment'
    if not fragment.exists() or digest(fragment) != SLICE_SHA:
        request = urllib.request.Request(URL, headers={'Range': f'bytes={START}-{END}'})
        temporary = fragment.with_suffix('.partial')
        with urllib.request.urlopen(request, timeout=90) as response, temporary.open('wb') as output:
            if response.status != 206 or response.headers.get('Content-Range') != f'bytes {START}-{END}/{TOTAL}':
                raise ValueError('Official viewer range changed')
            remaining = END - START + 1
            while remaining:
                data = response.read(min(1024 * 1024, remaining))
                if not data:
                    raise ValueError('Incomplete official viewer download')
                output.write(data)
                remaining -= len(data)
            if response.read(1):
                raise ValueError('Oversized official viewer download')
        if digest(temporary) != SLICE_SHA:
            raise ValueError('Official viewer fragment checksum changed')
        temporary.replace(fragment)
    # Wrap the exact single file record in ordinary non-volume RAR5 headers.
    archive = WORK / 'viewer.rar'
    with archive.open('wb') as output, fragment.open('rb') as source:
        output.write(b'Rar!\x1a\x07\x01\x00' + block(b'\x01\x00\x00'))
        shutil.copyfileobj(source, output)
        output.write(block(b'\x05\x00\x00'))
    unpack = WORK / 'unpacked'
    if unpack.exists():
        shutil.rmtree(unpack)
    unpack.mkdir()
    subprocess.run(['unrar-free', '-x', str(archive)], cwd=unpack, check=True, stdout=subprocess.DEVNULL)
    msi = unpack / 'FFXIFullSetup_US/PlayOnline/PlayOnlineViewer.msi'
    if digest(msi) != MSI_SHA:
        raise ValueError('Official viewer MSI checksum changed')
    files = WORK / 'files'
    if files.exists():
        shutil.rmtree(files)
    subprocess.run(['msiextract', '-C', str(files), str(msi)], check=True, stdout=subprocess.DEVNULL)
    viewer = files / 'Program Files/PlayOnline/SquareEnix/PlayOnlineViewer'
    if digest(viewer / 'pol.exe') != POL_SHA:
        raise ValueError('Official viewer executable checksum changed')
    target = WORK / 'viewer'
    if target.exists():
        shutil.rmtree(target)
    shutil.copytree(viewer, target)
    print('Verified official PlayOnline viewer fixture; kept outside artifacts and app assets', flush=True)


if __name__ == '__main__':
    main()
