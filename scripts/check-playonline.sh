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
