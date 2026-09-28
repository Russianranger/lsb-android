"""Real mesh archives, child-process startup, and isolated-generation regression.

Only manager database operations and ELF validation are replaced in the focused
startup fixture. The existing integration.py gate separately exercises those
with real MariaDB and ARM64 jemalloc executables. Production mesh parsing,
extraction, atomic installation and child processes remain real here.
"""
from contextlib import ExitStack
from pathlib import Path
import hashlib
import importlib.util
import io
import json
import os
import shutil
import socket
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from unittest import mock

REPO = Path(__file__).resolve().parents[2]
SOURCE_PIN = "6d5a5137024e21602e37032f6e55a682971329be"
IDENTITY = {"repository": "LandSandBoat/server", "commit": SOURCE_PIN}
NAV_PIN = "e8ca9f0419982d4da0eef8542d13f09865e6c556"
XI_PIN = "906d725cb40ed4eeeaa8299bb95922dca31be2d5"
ZONE = "Celennia_Memorial_Library"
GITMODULES = """[submodule "navmeshes"]
    path = navmeshes
    url = https://github.com/LandSandBoat/xiNavmeshes.git
[submodule "ximeshes"]
    path = ximeshes
    url = https://github.com/InoUno/ximeshes.git
"""
FIXTURES = {
    "navmeshes": ("LandSandBoat/xiNavmeshes", NAV_PIN, ".nav", 12500,
                  "bf85ddfd72dc26cf31e26d1d943549e2c54703228acd5edf16a2c39d286bd692"),
    "ximeshes": ("InoUno/ximeshes", XI_PIN, ".ximesh", 24750,
                 "89d17be9b97ab87052a0d9af699beef322fd3bce78ae09652f79f2f46f7a6e90"),
}
LOADERS = {
    "src/map/navmesh/detour_navmesh.cpp": "f8d97025fb51ca1073c616237e07c41bf9dfea3d3c5cd3bbae6857cb4ca879be",
    "src/map/ximesh/ximesh_impl.cpp": "2cc2897932a621c86c93f28cc86e9c28855593c84569b929ef3847432b580a60",
    "src/map/ximesh/ximesh_structs.h": "da1a978bca7fd631023c07894d2c8ba19b2993752a13490e85852c2ce4210887",
}


def verified_download(url, sha256, size=None):
    request = urllib.request.Request(url, headers={"User-Agent": "LSB-Android-mesh-regression"})
    with urllib.request.urlopen(request, timeout=90) as response:
        data = response.read(5 * 1024 * 1024 + 1)
    assert len(data) <= 5 * 1024 * 1024, url
    assert hashlib.sha256(data).hexdigest() == sha256, url
    if size is not None:
        assert len(data) == size, url
    return data


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def file_hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_tree(root, loaders):
    root.mkdir(parents=True)
    (root / ".gitmodules").write_text(GITMODULES)
    for name, data in loaders.items():
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    for name in FIXTURES:
        folder = root / name
        folder.mkdir()
        (folder / ".gitread").write_text("Git submodule omitted from source ZIP")


def archive_bytes(folder, content):
    repo, pin, suffix, _, _ = FIXTURES[folder]
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as archive:
        top = repo.split("/")[1] + "-" + pin
        for name, data in ((ZONE + suffix, content), ("README.md", b"Official mesh fixture")):
            entry = tarfile.TarInfo(top + "/" + name)
            entry.size = len(data)
            archive.addfile(entry, io.BytesIO(data))
    return output.getvalue()


class Response(io.BytesIO):
    def __init__(self, data, url):
        super().__init__(data)
        self.headers = {"Content-Length": str(len(data))}
        self.url = url
        self.status = 200

    def geturl(self):
        return self.url


STUB = r'''#!/usr/bin/env python3
from pathlib import Path
import select, signal, socket, sys, time
role = Path(sys.argv[0]).name.removeprefix("xi_")
if role == "map":
    for name, suffix in (("navmeshes", ".nav"), ("ximeshes", ".ximesh")):
        if not (Path(name) / ("Celennia_Memorial_Library" + suffix)).is_file():
            print("[map][critical] Missing " + name + " runtime data", flush=True)
            sys.exit(255)
    print("[map][info] Loading Mob scripts (LoadMOBList:662)", flush=True)
    time.sleep(3)
listener = None
if role == "connect":
    listener = socket.socket()
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind(("127.0.0.1", 54231))
    listener.listen(8)
print("The " + role + "-server is ready to work after 4.12 seconds... (markLoaded:275)", flush=True)
signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
while True:
    if listener and select.select([listener], [], [], .1)[0]:
        peer, _ = listener.accept()
        peer.close()
    elif not listener:
        time.sleep(.1)
'''


def startup_fixture(fixtures, loaders):
    manager = load("mesh_integration_manager", REPO / "server/manager.py")
    with tempfile.TemporaryDirectory(prefix="lsb-mesh-startup-") as temporary:
        top = Path(temporary)
        state, run, logs = (top / name for name in ("state", "run", "logs"))
        for folder in (state, run, logs):
            folder.mkdir()
        generation = state / "generations" / "fixture"
        root = generation / "server"
        source_tree(root, loaders)
        database = generation / "db" / "player-data.sentinel"
        database.parent.mkdir()
        database.write_bytes(b"existing stopped player database\x00\xff")
        binaries = {}
        for name in manager.PROCESSES:
            path = root / name
            path.write_text(STUB)
            path.chmod(0o755)
            binaries[name] = file_hash(path)
        metadata = dict(database="xidb", generation="fixture", build_id="completed-jemalloc-build",
                        selected_source=IDENTITY, binaries=binaries, accounts=2, characters=3)
        (generation / "deployment.json").write_text(json.dumps(metadata))
        (state / "active.json").write_text(json.dumps(dict(current="fixture", previous="older")))
        pointer = (state / "active.json").read_bytes()
        original_database = database.read_bytes()
        original_metadata = (generation / "deployment.json").read_bytes()
        # Exactly the archive-fetch deployment defect: submodule placeholders
        # make directories look nonempty, but the map worker still exits 255.
        failed = subprocess.run([root / "xi_map"], cwd=root, capture_output=True, text=True, timeout=10)
        assert failed.returncode == 255 and "Missing navmeshes" in failed.stdout, failed
        print("PASS: actual map fixture exits 255 with source-ZIP mesh placeholders", flush=True)

        tree = {"truncated": False, "tree": [
            {"path": name, "mode": "160000", "type": "commit", "sha": FIXTURES[name][1]}
            for name in FIXTURES
        ]}
        archives = {name: archive_bytes(name, fixtures[name]) for name in FIXTURES}
        requests = []
        fail_download = [False]

        def transport(request, *args, **kwargs):
            url = request if isinstance(request, str) else request.full_url
            requests.append(url)
            if url == "https://api.github.com/repos/LandSandBoat/server/git/trees/" + SOURCE_PIN:
                return Response(json.dumps(tree).encode(), url)
            for name, (repo, pin, _, _, _) in FIXTURES.items():
                if url == "https://codeload.github.com/" + repo + "/tar.gz/" + pin:
                    if fail_download[0]:
                        raise OSError("fixture network unavailable")
                    return Response(archives[name], url)
            raise AssertionError("Unexpected fixture request: " + url)

        statuses = []
        started = []
        real_status = manager.status
        real_connect = socket.create_connection
        successful_probes = []

        def status(phase, message, **fields):
            real_status(phase, message, **fields)
            statuses.append(dict(phase=phase, message=message, **fields))
            if phase == "running":
                (run / "stop").touch()

        def start_database(*args, **kwargs):
            assert not started, "A single start must launch the database once"
            for name, (_, _, suffix, _, sha) in FIXTURES.items():
                assert file_hash(root / name / (ZONE + suffix)) == sha
            started.append(True)
            return {"game": "synthetic fixture credential"}

        def probe(*args, **kwargs):
            connection = real_connect(*args, **kwargs)
            successful_probes.append(True)
            return connection

        def assert_preserved():
            assert (state / "active.json").read_bytes() == pointer
            assert database.read_bytes() == original_database
            assert {name: file_hash(root / name) for name in binaries} == binaries
            actual = json.loads((generation / "deployment.json").read_text())
            assert {name: actual[name] for name in metadata} == metadata

        with ExitStack() as stack:
            stack.enter_context(mock.patch.multiple(manager, STATE=state, RUN=run, LOGS=logs, children=[], STARTUP_TIMEOUT_SECONDS=30))
            stack.enter_context(mock.patch.object(manager, "validate_binaries"))
            stack.enter_context(mock.patch.object(manager, "start_database", side_effect=start_database))
            stopped = stack.enter_context(mock.patch.object(manager, "stop_database"))
            stack.enter_context(mock.patch.object(manager, "status", side_effect=status))
            stack.enter_context(mock.patch("urllib.request.urlopen", side_effect=transport))
            stack.enter_context(mock.patch("socket.create_connection", side_effect=probe))
            try:
                fail_download[0] = True
                try:
                    manager.serve()
                except OSError as error:
                    assert "unavailable" in str(error)
                else:
                    raise AssertionError("Missing mesh download must fail before starting the database")
                assert not started and not manager.children
                assert (generation / "deployment.json").read_bytes() == original_metadata
                assert_preserved()
                print("PASS: failed mesh preparation starts no database or workers and preserves selected build/player data", flush=True)
                fail_download[0] = False
                requests.clear()
                try:
                    manager.serve()
                except InterruptedError:
                    pass
                else:
                    raise AssertionError("Fixture must stop after manager reports readiness")
                assert any(item["phase"] == "preparing_meshes" for item in statuses)
                assert any(item["phase"] == "starting" and item.get("startup", {}).get("login_port_reachable")
                           and "xi_map" in item.get("startup", {}).get("pending_processes", []) for item in statuses)
                running = [item for item in statuses if item["phase"] == "running"]
                assert len(running) == 1 and not running[0]["startup"]["pending_processes"]
                assert len(successful_probes) == 1, "Do not repeatedly open and close the TLS login port"
                assert len(started) == 1 and stopped.call_count == 1
                assert len([url for url in requests if "codeload.github.com" in url]) == 2
                assert_preserved()
                before = dict(requests=requests[:], metadata=(generation / "deployment.json").read_bytes())
                (run / "stop").unlink()
                manager.prepare_meshes(root, IDENTITY)
                assert requests == before["requests"], "Ready meshes must allow an offline restart"
                assert (generation / "deployment.json").read_bytes() == before["metadata"]
                assert_preserved()
                print("PASS: manager repairs meshes before database/process startup, recognizes timed readiness, probes login once, preserves binaries/database, and repeats offline", flush=True)
            finally:
                manager.stop_children()


def official_archives(loaders):
    meshes = load("mesh_integration_assets", REPO / "server/meshes.py")
    with tempfile.TemporaryDirectory(prefix="lsb-official-meshes-") as temporary:
        top = Path(temporary)
        root = top / "server"
        source_tree(root, loaders)
        result = meshes.ensure(root, IDENTITY, cache=top / "cache")
        expected = {"navmeshes": (NAV_PIN, 304, ".nav"), "ximeshes": (XI_PIN, 299, ".ximesh")}
        for name, (pin, count, suffix) in expected.items():
            assert result[name]["commit"] == pin, result
            assert result[name]["assets"] == count, result
            assert len(list((root / name).rglob("*" + suffix))) == count
            assert file_hash(root / name / (ZONE + suffix)) == FIXTURES[name][4]
        assert meshes.validate(root)
        with mock.patch("urllib.request.urlopen", side_effect=AssertionError("Repeat preparation must be offline")):
            second = meshes.ensure(root, IDENTITY, cache=top / "cache")
        for name in expected:
            assert second[name]["assets"] == result[name]["assets"]
        print("PASS: production HTTPS downloader and parser validated all 304 navmeshes + 299 ximeshes at the exact source gitlink pins; repeat preparation needs no network", flush=True)


def main():
    fixtures = {}
    for name, (repo, pin, suffix, size, sha) in FIXTURES.items():
        fixtures[name] = verified_download("https://raw.githubusercontent.com/" + repo + "/" + pin + "/" + ZONE + suffix, sha, size)
    loaders = {name: verified_download("https://raw.githubusercontent.com/LandSandBoat/server/" + SOURCE_PIN + "/" + name, sha)
               for name, sha in LOADERS.items()}
    startup_fixture(fixtures, loaders)
    if "--fixtures-only" not in sys.argv:
        official_archives(loaders)


if __name__ == "__main__":
    main()
