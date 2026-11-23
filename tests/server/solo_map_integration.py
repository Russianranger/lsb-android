"""Boot the complete solo map and exercise its registered Lua/C++ functions on MariaDB."""

import os
from pathlib import Path
import socket
import subprocess
import tempfile

from solo_mariadb_fixture import MariaDBFixture


def loopback_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def check(root, logs):
    from solo_world import prepare_schema

    # The map reads the whole world, including its native triggers. Follow the
    # selected source's fresh-install order and register the solo tables last.
    sql_dir = root / 'sql'
    triggers = sql_dir / 'triggers.sql'
    files = sorted(path for path in sql_dir.glob('*.sql') if path != triggers) + [triggers]
    with MariaDBFixture(tcp=True) as db:
        for path in files:
            db.run(path.read_text())
        prepare_schema(root, db.run)
        map_port = loopback_port()
        db.run(f"""INSERT INTO chars(charid,accid,charname,pos_zone) VALUES (1001,1001,'SoloTester',100);
                UPDATE zone_settings SET zoneip='127.0.0.1', zoneport={map_port};
                CREATE USER 'map_smoke'@'%' IDENTIFIED BY 'test';
                GRANT ALL ON solo_test.* TO 'map_smoke'@'%';""")
        env = dict(os.environ, XI_NETWORK_SQL_HOST='127.0.0.1', XI_NETWORK_SQL_PORT=str(db.port),
                   XI_NETWORK_SQL_LOGIN='map_smoke', XI_NETWORK_SQL_PASSWORD='test',
                   XI_NETWORK_SQL_DATABASE='solo_test', XI_NETWORK_ZMQ_IP='127.0.0.1',
                   XI_NETWORK_ZMQ_PORT=str(loopback_port()))
        console = logs / 'solo-map-console.log'
        with tempfile.NamedTemporaryFile(dir=root / 'modules/handheld_squad/lua', suffix='.lua') as probe:
            probe.write(Path(__file__).with_name('solo_map_startup.lua').read_bytes())
            probe.flush()
            with console.open('w') as output:
                result = subprocess.run([str(root / 'xi_map'), '--ci', '--lazy', '--ip', '127.0.0.1',
                                         '--port', str(map_port), '--log', str(logs / 'solo-map.log')],
                                        cwd=root, env=env, stdin=subprocess.DEVNULL, stdout=output,
                                        stderr=subprocess.STDOUT, timeout=240)
        text = console.read_text(errors='replace')
        markers = ('Registering module command: !squad', 'Handheld native squad bindings passed',
                   'The map-server is ready to work', 'CI mode enabled: exiting after successful initialization')
        if result.returncode or any(marker not in text for marker in markers):
            raise AssertionError(f'xi_map exited {result.returncode}, see {console}')
        return dict(sql_files=len(files), registered_command='!squad', native_bindings=True,
                    map_ready=True, exit_code=result.returncode)
