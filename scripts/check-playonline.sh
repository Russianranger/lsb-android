#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/prepare-runtime.py --release
mkdir -p out/playonline-test/backend out/playonline-test/probe out/playonline-test/logs
cp out/runtime-assets/* out/presentation/* runtime/*.py out/playonline-test/backend/
cp out/runtime-probes/* out/playonline-test/probe/
cp out/runtime-probes/liblsb-gamepad.so out/playonline-test/backend/
chmod +x out/playonline-test/backend/vulkan-probe out/playonline-test/backend/wineserver out/playonline-test/backend/x11-frame-bridge out/playonline-test/backend/x11-upload-check
# Same pinned device rootfs and software renderer as check-runtime.sh. Keep this
# short fixture separate so its evidence is published before long renderer runs.
docker import .tools/runtime/runtime-arm64.tar.gz lsb-playonline:base
cat > out/playonline-test/Dockerfile <<'DOCKER'
FROM lsb-playonline:base
RUN apt-get update && apt-get install -y --no-install-recommends mesa-vulkan-drivers && apt-get clean && rm -rf /var/lib/apt/lists/*
DOCKER
docker build -t lsb-playonline:test out/playonline-test
# Reproduce the pinned rootfs's empty resolver and prove the app's supplied
# resolver/hosts bindings work without contacting public DNS or patch services.
mkdir -p out/playonline-test/network
python3 - <<'PYDNS'
from pathlib import Path
root = Path('out/playonline-test/network')
(root / 'empty-resolv.conf').write_text('')
(root / 'empty-hosts').write_text('')
(root / 'configured-resolv.conf').write_text('nameserver 127.0.0.2\noptions timeout:1 attempts:1\n')
(root / 'configured-hosts').write_text('127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n')
PYDNS
for resolver_case in empty configured; do
 docker run --rm --network none \
  -v "$PWD/out/playonline-test/backend:/opt/lsb:ro" -v "$PWD/tests/runtime:/tests:ro" \
  -v "$PWD/out/playonline-test/network/$resolver_case-resolv.conf:/etc/resolv.conf:ro" \
  -v "$PWD/out/playonline-test/network/$resolver_case-hosts:/etc/hosts:ro" \
  -v "$PWD/out/playonline-test/logs:/logs" \
  lsb-playonline:test python3 /tests/network_smoke.py "$resolver_case"
done
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends unrar-free msitools
python3 scripts/prepare-playonline-smoke.py
# The public installer/client are read-only inputs outside uploaded artifacts.
# Disposable offline viewer screenshots contain no accounts or installed user data.
docker run --rm --network none \
 -v "$PWD/out/playonline-test/backend:/opt/lsb:ro" \
 -v "$PWD/out/playonline-test/backend/wineserver:/opt/wine/bin/wineserver:ro" \
 -v "$PWD/out/playonline-test/probe:/probe:ro" -v "$PWD/tests/runtime:/tests:ro" \
 -v "$PWD/.tools/playonline-smoke/viewer:/official:ro" \
 -v "$PWD/out/playonline-test/logs:/logs" \
 lsb-playonline:test python3 /tests/playonline_smoke.py
# Separately opt in after the deterministic/offline gates. This uses a new
# container, empty prefix and public installer copy only. The private trace and
# internal runtime reports are not under the uploaded logs artifact directory.
if [[ "${LSB_PLAYONLINE_ONLINE:-0}" == "1" ]]; then
 online_status=0
 for online_case in missing-interface msi-interface; do
 mkdir -p "out/playonline-test/online-private/$online_case"
 docker run --rm --network bridge --tmpfs /prefix --tmpfs /client --tmpfs /session \
  -v "$PWD/out/playonline-test/backend:/opt/lsb:ro" \
  -v "$PWD/out/playonline-test/backend/wineserver:/opt/wine/bin/wineserver:ro" \
  -v "$PWD/out/playonline-test/probe:/probe:ro" -v "$PWD/tests/runtime:/tests:ro" \
  -v "$PWD/.tools/playonline-smoke/viewer:/official:ro" \
  -v "$PWD/out/playonline-test/online-private/$online_case:/logs" \
  -v "$PWD/out/playonline-test/logs:/evidence" \
  lsb-playonline:test python3 /tests/playonline_online_smoke.py "$online_case" || online_status=1
 done
 exit "$online_status"
fi
