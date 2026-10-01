"""Exercise the real compiled profile service with isolated synthetic credentials.

The source-build gate owns all inputs: selected upstream SQL, freshly compiled
ARM64 xi_profile, and a temporary MariaDB. No device database or loader is used.
"""

from pathlib import Path
import os
import signal
import socket
import ssl
import struct
import subprocess
import time


PORTS = (51220, 51240)
ACCOUNT_ID = 1000
SESSION_HASH = bytes(range(16))


def receive(sock, size):
    data = b''
    while len(data) < size:
        part = sock.recv(size - len(data))
        if not part:
            raise AssertionError('Profile connection closed before its expected reply')
        data += part
    return data


def connect(context, port, session_hash):
    raw = socket.create_connection(('127.0.0.1', port), timeout=5)
    try:
        tls = context.wrap_socket(raw, server_hostname='localhost')
    except BaseException:
        raw.close()
        raise
    assert tls.version() == 'TLSv1.3', 'Profile transport must negotiate TLS 1.3'
    tls.sendall(struct.pack('<I', ACCOUNT_ID) + session_hash)
    return tls


def check(root, output, work, backend):
    root, output, work = Path(root), Path(output), Path(work)
    work.mkdir(parents=True, exist_ok=False)
    original = {name: getattr(backend, name) for name in ('RUN', 'LOGS', 'STATE')}
    # Keep synthetic passwords, MariaDB files and private client option files
    # out of uploaded evidence, including failures during initialization.
    for name in original:
        location = work / name.lower()
        location.mkdir()
        setattr(backend, name, location)
    generation = work / 'generation'
    generation.mkdir()
    profile = None
    creds = None
    settings = root / 'settings'
    # Upstream copies defaults during settings initialization. Track new files
    # so this standalone startup test leaves the compiled payload unchanged.
    originals = {p: p.read_bytes() for p in settings.glob('*.lua')}
    certs = {root / name: (root / name).read_bytes() if (root / name).exists() else None
             for name in ('profile.cert', 'profile.key')}
    try:
        for port in PORTS:
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', port))
        creds = backend.initialize_database(generation, 'profile_fixture')
        for name in ('accounts', 'accounts_profile'):
            backend.sql((root / 'sql' / (name + '.sql')).read_text(), creds['game'], 'lsb', 'profile_fixture')
        backend.sql(
            "INSERT INTO accounts(id,login,password,status,priv,timecreate,timelastmodify) "
            "VALUES(1000,'profile_fixture','unused',1,1,NOW(),NOW());"
            "INSERT INTO accounts_profile(accid,session_hash,refreshed) VALUES(1000,UNHEX('" + SESSION_HASH.hex() + "'),NOW());",
            creds['game'], 'lsb', 'profile_fixture')
        backend.stop_database(creds)
        creds = backend.start_database(generation, network=True)
        env = dict(os.environ, XI_NETWORK_SQL_HOST='127.0.0.1', XI_NETWORK_SQL_PORT=str(backend.DB_PORT),
                   XI_NETWORK_SQL_LOGIN='lsb', XI_NETWORK_SQL_PASSWORD=creds['game'],
                   XI_NETWORK_SQL_DATABASE='profile_fixture', XI_NETWORK_PROFILE_PORT='51220')
        log = output / 'profile-service.log'
        with log.open('wb') as stream:
            profile = subprocess.Popen([str(root / 'xi_profile')], cwd=root, env=env,
                                       stdin=subprocess.DEVNULL, stdout=stream, stderr=stream, start_new_session=True)
        deadline = time.monotonic() + 45
        while 'The profile-server is ready to work' not in log.read_text(errors='replace'):
            if profile.poll() is not None:
                raise AssertionError('Real xi_profile exited during initialization; see profile-service.log')
            if time.monotonic() > deadline:
                raise AssertionError('Real xi_profile did not report ready; see profile-service.log')
            time.sleep(.1)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
        context.check_hostname = False
        context.verify_mode = ssl.CERT_NONE  # This test just generated a self-signed upstream certificate.
        context.minimum_version = ssl.TLSVersion.TLSv1_3
        for port in PORTS:
            with connect(context, port, b'wrong-session!!!') as tls:
                assert tls.recv(1) == b'', 'Invalid profile credential was not rejected'
        with connect(context, 51220, SESSION_HASH) as tls:
            # ProfOpenData is 40 packed bytes. The server then emits the real
            # 24-byte ProfOpenAns before reading the first profile request.
            tls.sendall(struct.pack('<BBHHHI28s', 0, 0, 0, 1, 0, 0, bytes(28)))
            answer = receive(tls, 24)
            assert answer[1] == 0, 'Profile Open response reports an error'
        with connect(context, 51240, SESSION_HASH) as tls:
            greeting = b''
            while not greeting.endswith(b'\n') and len(greeting) < 512:
                greeting += receive(tls, 1)
            assert greeting.startswith(b':srv NOTICE AUTH '), 'IRC did not issue the profile authentication challenge'
        assert profile.poll() is None, 'Profile service exited after handling client connections'
        assert backend.sql('SELECT COUNT(*) FROM accounts;', creds['game'], 'lsb', 'profile_fixture').strip() == '1'
        return dict(state='passed', executable='xi_profile', transport='TLSv1.3', ports=list(PORTS),
                    database='isolated synthetic fixture', tests=['real service initialization',
                    'wrong session denied on both listeners', 'profile Open reply', 'IRC authentication challenge',
                    'account row retained'])
    finally:
        if profile is not None:
            if profile.poll() is None:
                os.killpg(profile.pid, signal.SIGTERM)
            try:
                profile.wait(timeout=15)
            except subprocess.TimeoutExpired:
                os.killpg(profile.pid, signal.SIGKILL)
                profile.wait(timeout=5)
        if creds is not None:
            backend.stop_database(creds)
        else:
            backend.stop_children()
        for path in settings.glob('*.lua'):
            if path not in originals:
                path.unlink()
        for path, data in originals.items():
            path.write_bytes(data)
        for path, data in certs.items():
            if data is None:
                path.unlink(missing_ok=True)
            else:
                path.write_bytes(data)
        for name, location in original.items():
            setattr(backend, name, location)
