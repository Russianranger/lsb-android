#!/bin/bash
set -euo pipefail
trap 'result=$?; if [ "$result" != 0 ]; then python3 - <<"PYLOG"
from pathlib import Path
for root in ("/server-logs", "/server-run", "/tmp/lsb-session-roundtrip/restored-logs", "/tmp/lsb-session-roundtrip/restored-run"):
 for p in Path(root).glob("*"):
  if p.is_file() and p.suffix in (".log", ".json"): print(p, p.read_text(errors="replace")[-5000:])
PYLOG
fi' EXIT
printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d
bash /src/server/bootstrap.sh
apt-get install -y --no-install-recommends proot default-jdk-headless libtalloc-dev gawk
# Use the application's pinned PRoot and exact patches, including its native
# activation marker. A distribution PRoot could silently use compatibility mode.
git clone https://github.com/termux/proot.git /tmp/lsb-server-proot
git -C /tmp/lsb-server-proot checkout 7266fb3e8516535682f5a9c8f3a7e70f6506eddb
git -C /tmp/lsb-server-proot apply /src/native/proot-acceleration.patch /src/native/proot-sysvipc.patch
sed -i '1i#include <string.h>' /tmp/lsb-server-proot/src/extension/ashmem_memfd/ashmem_memfd.c
# glibc 2.42 declares SYS_SECCOMP as an enum in signal.h. Read that
# declaration before the old pinned PRoot's fallback macro of the same name.
# This host-fixture include does not change the packaged Android native binary.
sed -i '1i#include <signal.h>' /tmp/lsb-server-proot/src/compat.h
make -C /tmp/lsb-server-proot/src -j2 PROOT_UNBUNDLE_LOADER=/unused HAS_LOADER_32BIT=
export LSB_SERVER_TEST_PROOT=/tmp/lsb-server-proot/src
python3 -m unittest discover -s /src/tests/server -p 'test_*.py'
python3 /src/tests/server/mesh_integration.py
python3 /src/tests/server/integration.py
python3 /src/tests/server/profile_repair_integration.py
