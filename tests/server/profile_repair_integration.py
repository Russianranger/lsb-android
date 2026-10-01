"""Repair a deployed profile service with real ARM64 CMake, jemalloc and MariaDB.

The fixture compiles only a small server: building any of its four existing
targets deliberately fails. Production repair, receipts, dependency validation,
SQL preservation and managed startup remain unmocked.
"""
from pathlib import Path
import hashlib
import importlib.util
import json
import multiprocessing
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import uuid


REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('profile_fixture_manager', REPO / 'server/manager.py')
backend = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = backend
spec.loader.exec_module(backend)

STUB = r'''#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <fcntl.h>
#include <jemalloc/jemalloc.h>
static volatile sig_atomic_t done;
static void stop(int sig) { (void)sig; done = 1; }
static int listen_on(unsigned short port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0), one = 1;
    struct sockaddr_in address = {0};
    if (fd < 0) return -1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    address.sin_family = AF_INET; address.sin_port = htons(port);
    address.sin_addr.s_addr = htonl(0x7f000001);
    if (bind(fd, (void*)&address, sizeof(address)) || listen(fd, 8) ||
        fcntl(fd, F_SETFL, O_NONBLOCK) < 0) { close(fd); return -1; }
    return fd;
}
int main(int argc, char **argv) {
    (void)argc; signal(SIGTERM, stop); signal(SIGINT, stop);
    const char *allocator = NULL; size_t size = sizeof(allocator);
    if (mallctl("version", &allocator, &size, NULL, 0)) return 9;
    printf("allocator=jemalloc %s\n", allocator);
    const char *role = strrchr(argv[0], '/'); role = role ? role + 1 : argv[0]; role += 3;
    int first = -1, second = -1;
    if (!strcmp(role, "connect")) { first = listen_on(54231); if (first < 0) return 8; }
    if (!strcmp(role, "profile")) { first = listen_on(51220); if (first < 0) return 8; }
    printf("The %s-server is ready to work after 0.1 seconds... (markLoaded:275)\n", role);
    fflush(stdout);
    if (!strcmp(role, "profile")) {
        /* A ready marker alone must not claim its second listener works. */
        sleep(3); second = listen_on(51240); if (second < 0) return 8;
    }
    while (!done) {
        int listeners[2] = {first, second};
        for (int i = 0; i < 2; ++i) if (listeners[i] >= 0) {
            int client = accept(listeners[i], NULL, NULL); if (client >= 0) close(client);
        }
        usleep(100000);
    }
    if (first >= 0) close(first); if (second >= 0) close(second); return 0;
}
'''


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def hashes(root):
    return {str(path.relative_to(root)): sha(path) for path in root.rglob('*') if path.is_file()}


def reachable(port):
    try:
        with socket.create_connection(('127.0.0.1', port), timeout=.2):
            return True
    except OSError:
        return False


def server_child():
    try:
        backend.serve()
    except InterruptedError:
        backend.status('stopped', 'Server stopped')
    except Exception as error:
        backend.status('error', str(error))
        raise
    finally:
        backend.stop_children()


def dump(generation):
    target = backend.RUN / 'fixture-export.sql'
    backend.dump_database(generation, target)
    # Dump timestamps differ; executable SQL and every row must remain equal.
    return b'\n'.join(line for line in target.read_bytes().splitlines() if not line.startswith(b'--'))


def main():
    with tempfile.TemporaryDirectory(prefix='lsb-profile-repair-') as temporary:
        base = Path(temporary)
        backend.STATE = base / 'state'; backend.RUN = base / 'run'; backend.LOGS = base / 'logs'; backend.INPUT = base / 'input'
        for folder in (backend.STATE, backend.RUN, backend.LOGS, backend.INPUT):
            folder.mkdir()
        generation = backend.STATE / 'generations' / str(uuid.uuid4())
        root = generation / 'server'
        for folder in ('src/profile', 'sql', 'settings/default', 'scripts', 'tools'):
            (root / folder).mkdir(parents=True, exist_ok=True)
        (root / 'settings/default/login.lua').write_text("CLIENT_VER = '30260904_1',\n")
        (root / 'settings/default/network.lua').write_text('PROFILE_PORT = 51220,\n')
        (root / 'scripts/custom.lua').write_text('-- preserved deployed customization\n')
        (root / 'sql/accounts.sql').write_text('-- preserved deployed SQL source\n')
        (root / 'src/profile/main.c').write_text(STUB)
        (root / 'src/profile/CMakeLists.txt').write_text('add_executable(xi_profile main.c)\n')
        (root / 'forbidden.c').write_text('#error Repair must never rebuild an existing game server\n')
        (root / 'CMakeLists.txt').write_text(
            'cmake_minimum_required(VERSION 3.25)\nproject(profile_fixture C CXX)\n'
            'set(CMAKE_RUNTIME_OUTPUT_DIRECTORY "${CMAKE_SOURCE_DIR}")\n'
            'foreach(name IN ITEMS xi_world xi_search xi_map xi_connect)\n'
            '  add_executable(${name} EXCLUDE_FROM_ALL forbidden.c)\nendforeach()\n'
            'add_subdirectory(src/profile)\n')
        stub = base / 'server-stub'
        subprocess.run(['gcc-15', '-O2', root / 'src/profile/main.c', '-ljemalloc', '-o', stub], check=True)
        for name in backend.PROCESSES:
            shutil.copy2(stub, root / name)
        original_binaries = {name: sha(root / name) for name in backend.PROCESSES}
        build_id = str(uuid.uuid4())
        build = dict(format=1, state='passed', allocator='jemalloc', build_id=build_id,
                     jobs=2, source=backend.source_info(root),
                     binaries={name: dict(sha256=value, allocator='libjemalloc.so.2') for name, value in original_binaries.items()})
        backend.atomic(root / 'android-build.json', build)
        meta = dict(format=2, state='checked', generation=generation.name, database='xidb',
                    build_id=build_id, build=build, binaries=original_binaries,
                    expected_client='30260904_1', accounts=1, characters=1, local_zones=True,
                    selected_source={'origin': 'synthetic profile repair integration fixture'})
        backend.atomic(generation / 'deployment.json', meta)
        backend.atomic(backend.STATE / 'active.json', {'current': generation.name, 'previous': None})
        creds = None
        try:
            creds = backend.initialize_database(generation, 'xidb')
            backend.sql("CREATE TABLE accounts(id INT PRIMARY KEY,password VARBINARY(64)); "
                        "INSERT INTO accounts VALUES(1000,X'73796E746865746963'); "
                        "CREATE TABLE chars(charid INT PRIMARY KEY,accid INT,content BLOB); "
                        "INSERT INTO chars VALUES(42,1000,X'000A0DFF275C'); "
                        "CREATE TABLE zone_settings(zoneid INT PRIMARY KEY,zoneip VARCHAR(32),zoneport INT); "
                        "INSERT INTO zone_settings VALUES(1,'127.0.0.1',54230);",
                        creds['game'], 'lsb', 'xidb')
        finally:
            if creds is not None:
                backend.stop_database(creds)
            else:
                backend.stop_children()
        sql_before = dump(generation)
        database_before = hashes(generation / 'database')
        source_before = hashes(root)
        pointer_before = (backend.STATE / 'active.json').read_bytes()
        credentials_before = (generation / 'database-credentials.json').read_bytes()
        original_build = (root / 'android-build.json').read_bytes()
        backend.atomic(backend.RUN / 'request.json', dict(action='repair-profile', generation=generation.name, jobs=2))
        try:
            backend.main()
        except Exception:
            operation_log = backend.LOGS / 'operation.log'
            if operation_log.exists():
                details = operation_log.read_text(errors='replace')[-5000:]
                for secret in backend.secret_values:
                    details = details.replace(secret, '[redacted]')
                print('Profile fixture build output:\n' + details, flush=True)
            raise
        finally:
            backend.stop_children()
        assert (root / 'xi_profile').is_file(), 'Repair did not install xi_profile'
        assert hashes(generation / 'database') == database_before, 'Repair opened or changed the stopped database'
        assert (generation / 'database-credentials.json').read_bytes() == credentials_before
        assert (backend.STATE / 'active.json').read_bytes() == pointer_before
        assert (root / 'android-build.json').read_bytes() == original_build
        for name, expected in original_binaries.items():
            assert sha(root / name) == expected, name
        for name, expected in source_before.items():
            assert sha(root / name) == expected, 'Repair changed deployed source: ' + name
        updated = json.loads((generation / 'deployment.json').read_text())
        assert updated['build_id'] == build_id and updated['build'] == build
        repaired_metadata = (generation / 'deployment.json').read_bytes()
        backend.main()
        assert (generation / 'deployment.json').read_bytes() == repaired_metadata, 'Repeated repair rewrote its verified receipt'
        backend.validate_binaries(root)
        allocator = backend.validate_jemalloc(root)
        assert allocator['xi_profile']['allocator'] == 'libjemalloc.so.2'
        assert dump(generation) == sql_before, 'Repair changed account, character or database data'
        print('PASS: real ARM64 profile-only CMake repair preserves stopped MariaDB files, complete SQL, four binaries, source, build identity and active generation; shared jemalloc verified', flush=True)

        # A collision must be reported before opening MariaDB or any child.
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as occupied:
            occupied.bind(('127.0.0.1', 51240)); occupied.listen(1)
            stopped_before = hashes(generation / 'database')
            try:
                backend.serve()
            except RuntimeError as error:
                assert '51240' in str(error), str(error)
            else:
                raise AssertionError('Occupied profile listener was accepted')
            finally:
                backend.stop_children()
            assert hashes(generation / 'database') == stopped_before

        child = multiprocessing.get_context('fork').Process(target=server_child)
        child.start()
        try:
            deadline = time.monotonic() + 90
            observed_wait = False
            while time.monotonic() < deadline:
                assert child.is_alive(), (backend.RUN / 'status.json').read_text()
                try:
                    report = json.loads((backend.RUN / 'status.json').read_text())
                except (OSError, ValueError):
                    time.sleep(.1); continue
                first, second = reachable(51220), reachable(51240)
                if first and not second:
                    assert report['phase'] != 'running', 'Ready marker hid a missing profile listener'
                    observed_wait = True
                if report['phase'] == 'running':
                    assert observed_wait, 'Fixture did not exercise the delayed second profile listener'
                    assert first and second
                    assert set(report['startup']['ready_processes']) == set(backend.PROCESSES) | {'xi_profile'}
                    assert report['startup']['profile_ready'] is True
                    assert report['startup']['profile_ports_reachable'] == [51220, 51240]
                    break
                time.sleep(.1)
            else:
                raise AssertionError('Managed profile readiness timed out')
            (backend.RUN / 'stop').touch()
            child.join(50)
            assert child.exitcode == 0
        finally:
            if child.is_alive():
                (backend.RUN / 'stop').touch(); child.join(50)
            if child.is_alive():
                child.terminate(); child.join(10)
        (backend.RUN / 'stop').unlink(missing_ok=True)
        assert not reachable(51220) and not reachable(51240), 'Profile sockets leaked after stop'
        assert (backend.STATE / 'active.json').read_bytes() == pointer_before
        assert dump(generation) == sql_before
        assert 'allocator=jemalloc ' in (backend.LOGS / 'xi_profile.log').read_text()
        print('PASS: occupied profile port rejected before database startup; five managed ARM64 processes wait for both 51220/51240 listeners and stop cleanly with SQL retained', flush=True)


if __name__ == '__main__':
    main()
