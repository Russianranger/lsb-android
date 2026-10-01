#!/usr/bin/env python3
"""Repackage the pinned 0.5.21 CI APK as the 0.5.23 comparison control.

Only manifest version metadata changes; every application byte is preserved.
Not a general APK editor. Requires the exact documented CI source and original
device signing key. See docs/thor-performance-control-0523.md.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import tempfile
import zipfile

SOURCE_SHA = 'b0d87d763057680cc56a47ab9f0b94a49ee3daaf64aa68514c021e215c4d57af'
SIGNER_SHA = 'f1e6b27114c823eaf938d0b43316572303ae9e88743776112a705356c46a035e'


def version_manifest(original):
    # Android ResChunk_header / ResStringPool_header / ResXMLTree_attrExt.
    # Reject anything outside the pinned UTF-16 manifest representation.
    b = bytearray(original)
    assert struct.unpack_from('<HHI', b) == (3, 8, len(b))
    strings, offsets, changes = [], [], []
    pos = 8
    while pos < len(b):
        kind, header, size = struct.unpack_from('<HHI', b, pos)
        assert 8 <= header <= size and pos + size <= len(b)
        if kind == 1:
            count, styles, flags, start, _ = struct.unpack_from('<5I', b, pos + 8)
            assert header == 28 and styles == flags == 0 and not strings
            for i in range(count):
                at = pos + start + struct.unpack_from('<I', b, pos + header + i * 4)[0]
                length = struct.unpack_from('<H', b, at)[0]
                assert length < 0x8000 and at + 4 + length * 2 <= pos + size
                assert b[at + 2 + length * 2:at + 4 + length * 2] == b'\0\0'
                strings.append(b[at + 2:at + 2 + length * 2].decode('utf-16le'))
                offsets.append(at + 2)
        if kind == 0x102:
            ext = pos + header
            name = struct.unpack_from('<I', b, ext + 4)[0]
            if strings[name] == 'manifest':
                start, stride, count = struct.unpack_from('<HHH', b, ext + 8)
                assert stride == 20 and ext + start + stride * count <= pos + size
                for i in range(count):
                    at = ext + start + i * stride
                    ns, name, raw, value_size, zero, typ, value = struct.unpack_from('<IIIHBBI', b, at)
                    if strings[name] not in ('versionCode', 'versionName'):
                        continue
                    assert strings[ns] == 'http://schemas.android.com/apk/res/android'
                    assert value_size == 8 and zero == 0
                    if strings[name] == 'versionCode':
                        assert typ == 0x10 and value == 37 and raw == 0xffffffff
                        struct.pack_into('<I', b, at + 16, 39)
                        changes.append('versionCode')
                    else:
                        assert typ == 3 and raw == value and strings[value] == '0.5.21'
                        b[offsets[value]:offsets[value] + 12] = '0.5.23'.encode('utf-16le')
                        changes.append('versionName')
        pos += size
    assert pos == len(b) and sorted(changes) == ['versionCode', 'versionName']
    # Both edits change one byte each; no offsets, sizes or other fields change.
    assert len(b) == len(original) and sum(x != y for x, y in zip(b, original)) == 2
    return bytes(b)


def payload(z):
    assert len(z.namelist()) == len(set(z.namelist()))
    return {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist()
            if not n.startswith('META-INF/')}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--source', required=True, type=Path)
    p.add_argument('--tools', required=True, type=Path)
    p.add_argument('--keystore', required=True, type=Path)
    p.add_argument('--output', required=True, type=Path)
    a = p.parse_args()
    assert hashlib.sha256(a.source.read_bytes()).hexdigest() == SOURCE_SHA
    a.output.parent.mkdir(parents=True, exist_ok=True)
    assert a.source.resolve() != a.output.resolve()
    run = lambda *cmd: subprocess.check_output([str(v) for v in cmd], text=True)
    with tempfile.TemporaryDirectory(prefix='lsb-baseline-') as folder:
        unsigned, aligned = Path(folder)/'unsigned.apk', Path(folder)/'aligned.apk'
        with zipfile.ZipFile(a.source) as src, zipfile.ZipFile(unsigned, 'w') as dst:
            assert src.testzip() is None
            for info in src.infolist():
                if info.filename.startswith('META-INF/'):
                    continue
                data = src.read(info.filename)
                if info.filename == 'AndroidManifest.xml':
                    data = version_manifest(data)
                dst.writestr(info, data)
        run(a.tools/'zipalign', '-f', '-P', '16', '4', unsigned, aligned)
        run('java', '-jar', a.tools/'lib/apksigner.jar', 'sign', '--ks', a.keystore,
            '--ks-key-alias', 'lsb-preview', '--ks-pass', 'pass:android', '--key-pass',
            'pass:android', '--out', a.output, aligned)
    signature = run('java', '-jar', a.tools/'lib/apksigner.jar', 'verify', '--verbose', '--print-certs', a.output)
    assert SIGNER_SHA in signature
    assert 'Verified using v2 scheme (APK Signature Scheme v2): true' in signature
    assert 'Verified using v3 scheme (APK Signature Scheme v3): true' in signature
    run(a.tools/'zipalign', '-c', '-P', '16', '4', a.output)
    original_tree = run(a.tools/'aapt2', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', a.source)
    control_tree = run(a.tools/'aapt2', 'dump', 'xmltree', '--file', 'AndroidManifest.xml', a.output)
    assert control_tree == original_tree.replace('versionCode(0x0101021b)=37', 'versionCode(0x0101021b)=39').replace('0.5.21', '0.5.23')
    badging = run(a.tools/'aapt2', 'dump', 'badging', a.output)
    assert "name='io.github.russianranger.lsb' versionCode='39' versionName='0.5.23'" in badging
    with zipfile.ZipFile(a.source) as src, zipfile.ZipFile(a.output) as dst:
        assert dst.testzip() is None
        before, after = payload(src), payload(dst)
        assert before.keys() == after.keys()
        assert [n for n in before if before[n] != after[n]] == ['AndroidManifest.xml']
        manifest = json.loads(dst.read('assets/runtime/bundle.json'))
        for name, digest in manifest['files'].items():
            assert after['assets/runtime/' + name] == digest, name
    print(json.dumps(dict(file=str(a.output), version='0.5.23', versionCode=39,
                         source_sha256=SOURCE_SHA, signer_sha256=SIGNER_SHA,
                         sha256=hashlib.sha256(a.output.read_bytes()).hexdigest(),
                         bytes=a.output.stat().st_size,
                         unchanged_payload_entries=len(before)-1), indent=2))


if __name__ == '__main__':
    main()
