"""An isolated local MariaDB for tests of the Android solo modules."""

import getpass
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import unittest


class MariaDBFixture:
    def __init__(self, tcp=False):
        self.tcp = tcp
        self.port = None

    def __enter__(self):
        base = os.environ.get('MARIADB_BASE')
        if base:
            base = Path(base)
            install = str(base / 'scripts' / 'mariadb-install-db')
            server = str(base / 'bin' / 'mariadbd')
            client = str(base / 'bin' / 'mariadb')
            basedir = ['--basedir=' + str(base)]
        else:
            install, server, client = (shutil.which(tool) for tool in ('mariadb-install-db', 'mariadbd', 'mariadb'))
            basedir = []
        if not all((install, server, client)):
            raise unittest.SkipTest('MariaDB server and client tools are required')
        self.temp = tempfile.TemporaryDirectory(prefix='lsb-solo-db-')
        self.process = None
        root = Path(self.temp.name)
        datadir = '--datadir=' + str(root / 'data')
        unix_socket = '--socket=' + str(root / 'mysql.sock')
        server_transport = [unix_socket, '--skip-networking']
        client_transport = [unix_socket]
        if not self.tcp:
            try:
                with socket.socket(socket.AF_UNIX) as probe:
                    probe.bind(str(root / 'probe'))
            except PermissionError:  # Some CI sandboxes only allow loopback TCP.
                self.tcp = True
        if self.tcp:
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', 0))
                self.port = probe.getsockname()[1]
            port = '--port=' + str(self.port)
            server_transport = ['--socket=', '--bind-address=127.0.0.1', port]
            client_transport = ['--protocol=TCP', '--host=127.0.0.1', port]
        self.client = [client, '--no-defaults', *client_transport, '--user=root', '--batch', '--skip-column-names']
        try:
            subprocess.run([install, '--no-defaults', *basedir, datadir, '--auth-root-authentication-method=normal',
                            '--skip-test-db'], check=True, capture_output=True, text=True, timeout=90)
            log = root / 'server.log'
            with log.open('w') as output:
                self.process = subprocess.Popen([server, '--no-defaults', *basedir, datadir, *server_transport,
                                                 '--skip-log-bin', '--innodb-buffer-pool-size=32M',
                                                 '--pid-file=' + str(root / 'server.pid'), '--user=' + getpass.getuser()],
                                                stdout=output, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 25
            while time.monotonic() < deadline:
                if self.process.poll() is not None:
                    raise RuntimeError(log.read_text())
                try:
                    actual = self.run('SELECT @@datadir;').strip()
                except RuntimeError:
                    actual = ''
                if actual and Path(actual).resolve() == (root / 'data').resolve():
                    self.run('CREATE DATABASE solo_test CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;')
                    self.client.append('solo_test')
                    return self
                time.sleep(0.1)
            raise RuntimeError('MariaDB fixture did not become ready: ' + log.read_text())
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def run(self, stmt):
        result = subprocess.run(self.client, input=stmt, text=True, capture_output=True, timeout=30)
        if result.returncode:
            raise RuntimeError(result.stderr)
        return result.stdout

    def __exit__(self, *unused):
        if self.process is not None and self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.temp.cleanup()
