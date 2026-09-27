#!/bin/bash
set -euo pipefail

mkdir -p /artifacts/logs
printf '#!/bin/sh\nexit 101\n' >/usr/sbin/policy-rc.d
chmod +x /usr/sbin/policy-rc.d
bash /src/server/bootstrap.sh 2>&1 | tee /artifacts/logs/bootstrap.log

# Keep successful object compilations across diagnostic runs. Ccache checks the
# compiler, flags, source and included headers; changed patches still rebuild.
# This cache is CI-only and never imports server executables into the staging tree.
apt-get install -y --no-install-recommends ccache
export CCACHE_DIR=/ccache CCACHE_MAXSIZE=2G CCACHE_COMPILERCHECK=content
export CMAKE_C_COMPILER_LAUNCHER=ccache CMAKE_CXX_COMPILER_LAUNCHER=ccache
ccache --zero-stats

# The backend writes subprocess output to operation.log. Stream the same log
# into Actions while preserving it as a diagnostic artifact on any failure.
touch /artifacts/logs/operation.log
tail -n +1 -F /artifacts/logs/operation.log &
log_pid=$!
finish() {
    kill "$log_pid" 2>/dev/null || true
    ccache --show-stats > /artifacts/logs/compiler-cache.log
}
trap finish EXIT
# Collect independent compiler errors across targets in this diagnostic gate.
# The Android backend itself retains its normal fail-fast build behavior.
export MAKEFLAGS=-k
python3 -u /src/tests/server/source_build_integration.py
