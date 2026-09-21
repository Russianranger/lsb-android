#!/bin/bash
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends ca-certificates git python3 python3-venv python3-dev python3-yaml python3-pip build-essential gcc-15 g++-15 cmake make pkg-config libluajit-5.1-dev libzmq3-dev libssl-dev zlib1g-dev libzstd-dev libdwarf-dev mariadb-server mariadb-client libmariadb-dev-compat binutils-dev
# Python extensions use Python's configured, unversioned native compiler.
# gcc-15 alone supplies aarch64-linux-gnu-gcc-15, not aarch64-linux-gnu-gcc.
python3 - <<'PY'
import shlex, shutil, sysconfig
compiler = shlex.split(sysconfig.get_config_var('CC'))[0]
if not shutil.which(compiler):
    raise SystemExit('Missing Python extension compiler: ' + compiler)
print('Python extension compiler:', compiler)
PY
apt-get clean
printf '%s\n' 'Ubuntu 26.04 ARM64 server environment' > /lsb-server-ready
