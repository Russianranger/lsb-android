#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p out/fex-runtime
docker build -f native/fex/Dockerfile -t lsb-fex-build .
docker run --rm -v "$PWD/out/fex-runtime:/out" -v "$PWD/native/fex:/recipe:ro" lsb-fex-build
sudo chown -R "$(id -u):$(id -g)" out/fex-runtime
