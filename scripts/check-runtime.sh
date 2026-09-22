#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/prepare-runtime.py --release
mkdir -p out/runtime-test/backend out/runtime-test/probe out/runtime-test/logs
cp out/runtime-assets/* out/presentation/* runtime/*.py out/runtime-test/backend/
cp out/runtime-probes/* out/runtime-test/probe/
cp out/runtime-probes/liblsb-gamepad.so out/runtime-test/backend/
chmod +x out/runtime-test/backend/vulkan-probe out/runtime-test/backend/wineserver out/runtime-test/backend/x11-frame-bridge
# Source package expands to an ordinary GNU tar suitable for Docker import.
docker import .tools/runtime/runtime-arm64.tar.gz lsb-runtime:base
cat > out/runtime-test/Dockerfile <<'DOCKER'
FROM lsb-runtime:base
RUN apt-get update && apt-get install -y --no-install-recommends mesa-vulkan-drivers && apt-get clean && rm -rf /var/lib/apt/lists/*
DOCKER
docker build -t lsb-runtime:test out/runtime-test
python3 scripts/check-display-wire.py --backend docker
for renderer in software turnip26; do
 mkdir -p "out/runtime-test/logs/$renderer"
 args=()
 if [ "$renderer" = turnip26 ]; then args=(-e LSB_TEST_VULKAN_ICD=/usr/share/vulkan/icd.d/lvp_icd.aarch64.json); fi
 docker run --rm --network none "${args[@]}" -e LSB_TEST_RENDERER="$renderer" \
  -v "$PWD/out/runtime-test/backend:/opt/lsb:ro" \
  -v "$PWD/out/runtime-test/backend/wineserver:/opt/wine/bin/wineserver:ro" \
  -v "$PWD/out/windows-tests:/fixtures:ro" -v "$PWD/out/runtime-test/probe:/probe:ro" -v "$PWD/tests/runtime:/tests:ro" \
  -v "$PWD/out/runtime-test/logs/$renderer:/logs" lsb-runtime:test sh -c 'python3 /tests/integration.py && if [ "$LSB_TEST_RENDERER" = software ]; then python3 /tests/initialization.py && python3 /tests/dependency_check.py && python3 /tests/launching.py && python3 /tests/gamepad.py; fi'
 done
# Test the same rootfs through patched PRoot; no Android device is claimed by CI.
sudo apt-get install -y build-essential libtalloc-dev gawk
mkdir -p out/runtime-test/proot-root out/runtime-test/classes out/runtime-test/proot-logs
javac -d out/runtime-test/classes tests/runtime/java/android/system/Os.java app/src/main/java/io/github/russianranger/lsb/TarExtractor.java tests/runtime/java/io/github/russianranger/lsb/ExtractRuntimeHost.java
java -cp out/runtime-test/classes io.github.russianranger.lsb.ExtractRuntimeHost .tools/runtime/runtime-arm64.tar.gz out/runtime-test/proot-root
if [ ! -d out/runtime-test/proot-src ]; then git clone https://github.com/termux/proot.git out/runtime-test/proot-src; fi
git -C out/runtime-test/proot-src checkout 7266fb3e8516535682f5a9c8f3a7e70f6506eddb
git -C out/runtime-test/proot-src apply "$PWD/native/proot-acceleration.patch" "$PWD/native/proot-sysvipc.patch"
sed -i '1i#include <string.h>' out/runtime-test/proot-src/src/extension/ashmem_memfd/ashmem_memfd.c
make -C out/runtime-test/proot-src/src -j2 PROOT_UNBUNDLE_LOADER=/unused HAS_LOADER_32BIT=
python3 scripts/check-display-wire.py --backend proot
short=$(mktemp -d /tmp/lsb-probe.XXXXXX)
trap 'rm -rf "$short"' EXIT
mkdir -p "$short/session" "$short/prefix" "$short/tmp"
mkdir -p out/runtime-test/proot-root/opt/lsb out/runtime-test/proot-root/probe out/runtime-test/proot-root/tests out/runtime-test/proot-root/fixtures out/runtime-test/proot-root/client
PROOT_LOADER="$PWD/out/runtime-test/proot-src/src/loader/loader" PROOT_NO_SECCOMP=1 PROOT_TMP_DIR="$short/tmp" \
 out/runtime-test/proot-src/src/proot --link2symlink --kill-on-exit -0 -r "$PWD/out/runtime-test/proot-root" \
 -b /dev -b /proc -b /sys -b "$PWD/out/runtime-test/backend:/opt/lsb" \
 -b "$PWD/out/runtime-test/backend/wineserver:/opt/wine/bin/wineserver" \
 -b "$PWD/out/runtime-test/probe:/probe" -b "$PWD/tests/runtime:/tests" -b "$PWD/out/windows-tests:/fixtures" \
 -b "$short/session:/session" -b "$short/prefix:/prefix" -b "$short/tmp:/tmp" -b "$PWD/out/runtime-test/proot-logs:/logs" \
 -w /probe /usr/bin/env -i HOME=/root USER=root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 TMPDIR=/tmp PYTHONUNBUFFERED=1 /bin/sh -c 'python3 /tests/integration.py && python3 /tests/initialization.py && python3 /tests/dependency_check.py && python3 /tests/launching.py && python3 /tests/gamepad.py'
