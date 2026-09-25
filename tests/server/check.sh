#!/bin/bash
set -euo pipefail
trap 'result=$?; if [ "$result" != 0 ]; then python3 - <<"PYLOG"
from pathlib import Path
for root in ("/server-logs", "/server-run"):
 for p in Path(root).glob("*"):
  if p.is_file() and p.suffix in (".log", ".json"): print(p, p.read_text(errors="replace")[-5000:])
PYLOG
fi' EXIT
printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d
bash /src/server/bootstrap.sh
apt-get install -y --no-install-recommends proot
python3 -m unittest discover -s /src/tests/server -p 'test_*.py'
python3 /src/tests/server/integration.py
