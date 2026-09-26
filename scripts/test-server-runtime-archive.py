#!/usr/bin/env python3
"""Exercise the APK's tar extractor against its exact pinned Ubuntu server base.

This host check verifies extraction, including contents and links; it does not
claim that Android/PRoot package installation or the user's server was run.
"""
import argparse
from collections import Counter
import hashlib
import os
from pathlib import Path
import re
import stat
import subprocess
import tarfile
import tempfile
import time
import urllib.request


REPO = Path(__file__).resolve().parents[1]
JAVA = REPO / "app/src/main/java/io/github/russianranger/lsb"


def digest(stream):
    result = hashlib.sha256()
    while chunk := stream.read(1024 * 1024):
        result.update(chunk)
    return result.hexdigest()


def pin():
    source = (JAVA / "ServerRuntime.java").read_text()
    values = {}
    for name in ("BASE", "BASE_SHA"):
        found = re.findall(r'private\s+static\s+final\s+String\s+' + name
                           + r'\s*=\s*"([^"\n]+)"\s*;', source)
        if len(found) != 1:
            raise RuntimeError("Cannot read the unique server runtime pin: " + name)
        values[name] = found[0]
    if not re.fullmatch(r"[0-9a-f]{64}", values["BASE_SHA"]):
        raise RuntimeError("Invalid server runtime checksum pin")
    return values["BASE"], values["BASE_SHA"]


def checked_archive(selected):
    url, expected = pin()
    archive = selected or REPO / ".tools/server-runtime" / (expected + ".tar.gz")
    if not archive.is_file():
        if selected:
            raise FileNotFoundError(archive)
        archive.parent.mkdir(parents=True, exist_ok=True)
        # A failed download never becomes the cached archive.
        with tempfile.NamedTemporaryFile(dir=archive.parent, delete=False) as out:
            partial = Path(out.name)
            try:
                print("Downloading the pinned Ubuntu server base", flush=True)
                with urllib.request.urlopen(url, timeout=60) as response:
                    total = 0
                    deadline = time.monotonic() + 300
                    while chunk := response.read(1024 * 1024):
                        total += len(chunk)
                        if total > 100 * 1024 * 1024:
                            raise RuntimeError("Unexpected server base size")
                        if time.monotonic() > deadline:
                            raise TimeoutError("Server base download exceeded five minutes")
                        out.write(chunk)
                out.flush()
                with partial.open("rb") as check:
                    if digest(check) != expected:
                        raise RuntimeError("Server base checksum mismatch")
                partial.replace(archive)
            finally:
                partial.unlink(missing_ok=True)
    with archive.open("rb") as stream:
        actual = digest(stream)
    if actual != expected:
        raise RuntimeError("Server base checksum mismatch: " + str(archive))
    print("PASS: server base SHA-256 " + actual, flush=True)
    return archive.resolve()


def verify(archive, root):
    counts = Counter()
    size = 0
    pax_entries = 0
    with tarfile.open(archive, "r:gz") as reference:
        for member in reference:
            if member.pax_headers:
                pax_entries += 1
            path = root / member.name
            mode = path.lstat().st_mode
            if member.isfile():
                if not stat.S_ISREG(mode):
                    raise AssertionError("Not a regular file: " + member.name)
                expected_mode = 0o755 if member.mode & 0o111 else 0o644
                if stat.S_IMODE(mode) != expected_mode:
                    raise AssertionError("Incorrect file permissions: " + member.name)
                if path.stat().st_size != member.size:
                    raise AssertionError("Incorrect file size: " + member.name)
                with reference.extractfile(member) as expected, path.open("rb") as actual:
                    if digest(expected) != digest(actual):
                        raise AssertionError("Incorrect file contents: " + member.name)
                counts["files"] += 1
                size += member.size
            elif member.isdir():
                if not stat.S_ISDIR(mode):
                    raise AssertionError("Not a directory: " + member.name)
                counts["directories"] += 1
            elif member.issym():
                if not stat.S_ISLNK(mode) or os.readlink(path) != member.linkname:
                    raise AssertionError("Incorrect symlink: " + member.name)
                counts["symlinks"] += 1
            elif member.islnk():
                target = root / member.linkname
                actual, expected = path.lstat(), target.lstat()
                if (not stat.S_ISREG(actual.st_mode)
                        or not stat.S_ISREG(expected.st_mode)
                        or (actual.st_dev, actual.st_ino) != (expected.st_dev, expected.st_ino)):
                    raise AssertionError("Incorrect hardlink: " + member.name)
                counts["hardlinks"] += 1
            else:
                raise AssertionError("Unexpected reference tar member: " + member.name)
    if not (root / "usr/bin/bash").is_file():
        raise AssertionError("Server base does not contain bash")
    if not counts["files"] or not counts["symlinks"] or not counts["hardlinks"]:
        raise AssertionError("Expected runtime files and links were not exercised")
    if not pax_entries:
        raise AssertionError("Pinned server archive did not exercise PAX metadata")
    print("PASS: exact server archive extraction matches Python tar reference; "
          + ", ".join(f"{count} {kind}" for kind, count in sorted(counts.items()))
          + f", {size} regular-file bytes, {pax_entries} entries with PAX metadata", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, help="Use a local copy of the pinned archive")
    args = parser.parse_args()
    archive = checked_archive(args.archive)
    with tempfile.TemporaryDirectory(prefix="lsb-server-archive-") as temporary:
        work = Path(temporary)
        classes, root = work / "classes", work / "rootfs"
        classes.mkdir()
        subprocess.run(["java", "-m", "jdk.compiler/com.sun.tools.javac.Main", "-d", str(classes),
                        str(REPO / "tests/runtime/java/android/system/Os.java"),
                        str(JAVA / "TarExtractor.java"),
                        str(REPO / "tests/runtime/java/io/github/russianranger/lsb/TarExtractorTest.java"),
                        str(REPO / "tests/runtime/java/io/github/russianranger/lsb/ExtractRuntimeHost.java")],
                       check=True, cwd=REPO, timeout=120)
        subprocess.run(["java", "-ea", "-cp", str(classes),
                        "io.github.russianranger.lsb.TarExtractorTest"],
                       check=True, cwd=REPO, timeout=120)
        subprocess.run(["java", "-cp", str(classes),
                        "io.github.russianranger.lsb.ExtractRuntimeHost",
                        str(archive), str(root)], check=True, cwd=REPO, timeout=180)
        verify(archive, root)


if __name__ == "__main__":
    main()
