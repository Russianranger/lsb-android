#!/bin/bash
set -euo pipefail

mkdir -p /artifacts/logs
printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d
bash /src/server/bootstrap.sh 2>&1 | tee /artifacts/logs/bootstrap.log

# The backend writes subprocess output to operation.log. Stream the same log
# into Actions while preserving it as a diagnostic artifact on any failure.
touch /artifacts/logs/operation.log
tail -n +1 -F /artifacts/logs/operation.log &
log_pid=$!
trap 'kill "$log_pid" 2>/dev/null || true' EXIT
python3 -u /src/tests/server/source_build_integration.py
