#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/prepare-runtime.py --release
mkdir -p out/fex-test/{wine,backend,probe,baseline-prefix,client,session,logs/seed,logs/migrated,logs/dxvk271,logs/proot}
tar -xzf out/fex-runtime/runtime-fex-arm64.tar.gz -C out/fex-test/wine
cp out/runtime-assets/* out/presentation/* runtime/*.py out/fex-test/backend/
cp out/fex-runtime/fex-bundle.json out/fex-test/backend/
cp out/runtime-probes/* out/fex-test/probe/
cp out/runtime-probes/liblsb-gamepad.so out/fex-test/backend/
chmod +x out/fex-test/backend/{vulkan-probe,wineserver,x11-frame-bridge,x11-upload-check}
docker import .tools/runtime/runtime-arm64.tar.gz lsb-fex:base
# Audit dynamic dependencies in the actual unchanged Bookworm device rootfs.
# Wine loads its own ntdll/win32u by absolute path; standalone ldd needs their
# packaged directory to resolve those same internal modules.
docker run --rm -v "$PWD/out/fex-test/wine:/opt/fex:ro" lsb-fex:base sh -ec '
 export LD_LIBRARY_PATH=/opt/fex/lib/wine/aarch64-unix
 for f in /opt/fex/bin/wine /opt/fex/bin/wineserver /opt/fex/lib/wine/aarch64-unix/*.so; do
   ldd "$f"; done' > out/fex-test/logs/dependencies.log
if grep -q 'not found' out/fex-test/logs/dependencies.log; then cat out/fex-test/logs/dependencies.log; exit 1; fi
common=(-v "$PWD/out/fex-test/backend:/opt/lsb:ro" -v "$PWD/out/fex-test/probe:/probe:ro" -v "$PWD/out/windows-tests:/fixtures:ro" -v "$PWD/tests/runtime:/tests:ro")
docker run --rm --network none "${common[@]}" \
 -v "$PWD/out/fex-test/backend/wineserver:/opt/wine/bin/wineserver:ro" \
 -v "$PWD/out/fex-test/baseline-prefix:/prefix" -v "$PWD/out/fex-test/client:/client" \
 -v "$PWD/out/fex-test/session:/session" -v "$PWD/out/fex-test/logs/seed:/logs" \
 lsb-fex:base sh -ec 'chown 0:0 /prefix; python3 /tests/initialization.py'
native=(-v "$PWD/out/fex-test/wine:/opt/wine:ro" -e LSB_TEST_ENGINE=fex)
docker run --rm --network none "${common[@]}" "${native[@]}" \
 -v "$PWD/out/fex-test/baseline-prefix:/baseline-prefix:ro" -v "$PWD/out/fex-test/client:/client" \
 -v "$PWD/out/fex-test/session:/session" -v "$PWD/out/fex-test/logs/migrated:/logs" \
 lsb-fex:base sh -ec 'python3 /tests/fex_prefix.py copy; python3 /tests/integration.py; python3 /tests/fex_crash.py; python3 /tests/dependency_check.py; python3 /tests/launching.py; python3 /tests/gamepad.py; python3 /tests/fex_prefix.py verify'
# The Android path uses the existing patched PRoot, without binfmt or FEXLoader.
sudo apt-get update -qq
sudo apt-get install -y build-essential libtalloc-dev gawk
mkdir -p out/fex-test/proot-root out/fex-test/classes
javac -d out/fex-test/classes tests/runtime/java/android/system/Os.java app/src/main/java/io/github/russianranger/lsb/TarExtractor.java tests/runtime/java/io/github/russianranger/lsb/ExtractRuntimeHost.java
java -cp out/fex-test/classes io.github.russianranger.lsb.ExtractRuntimeHost .tools/runtime/runtime-arm64.tar.gz out/fex-test/proot-root
git clone https://github.com/termux/proot.git out/fex-test/proot-src
git -C out/fex-test/proot-src checkout 7266fb3e8516535682f5a9c8f3a7e70f6506eddb
git -C out/fex-test/proot-src apply "$PWD/native/proot-acceleration.patch" "$PWD/native/proot-sysvipc.patch"
sed -i '1i#include <string.h>' out/fex-test/proot-src/src/extension/ashmem_memfd/ashmem_memfd.c
make -C out/fex-test/proot-src/src -j2 PROOT_UNBUNDLE_LOADER=/unused HAS_LOADER_32BIT=
short=$(mktemp -d /tmp/lsb-fex.XXXXXX)
trap 'rm -rf "$short"' EXIT
mkdir -p "$short/prefix" "$short/tmp"
sudo chown -R "$(id -u):$(id -g)" out/fex-test/{baseline-prefix,client,session}
mkdir -p out/fex-test/proot-root/{opt/lsb,probe,tests,fixtures,client,baseline-prefix}

# Additional opt-in syscall-filter proof. Keep the full compatibility run below;
# this uses the same pinned, patched PRoot binary with a fresh private prefix,
# session and tmp directory. Activation must be observed, never inferred from
# a passing probe that may have silently fallen back to ordinary ptrace.
mkdir -p "$short/filter-prefix" "$short/filter-session" "$short/filter-tmp" out/fex-test/logs/proot-filter
filtered_proot=(env -u PROOT_NO_SECCOMP
 "PROOT_LOADER=$PWD/out/fex-test/proot-src/src/loader/loader"
 "PROOT_TMP_DIR=$short/filter-tmp" TRASC_PROOT_REPORT=1
 "$PWD/out/fex-test/proot-src/src/proot" --link2symlink --kill-on-exit --sysvipc -0 -r "$PWD/out/fex-test/proot-root"
 -b /dev -b /proc -b /sys -b "$PWD/out/fex-test/backend:/opt/lsb" -b "$PWD/out/fex-test/wine:/opt/wine"
 -b "$PWD/out/fex-test/probe:/probe" -b "$PWD/tests/runtime:/tests" -b "$PWD/out/windows-tests:/fixtures"
 -b "$PWD/out/fex-test/baseline-prefix:/baseline-prefix" -b "$PWD/out/fex-test/client:/client"
 -b "$short/filter-session:/session" -b "$short/filter-prefix:/prefix" -b "$short/filter-tmp:/tmp"
 -b "$PWD/out/fex-test/logs/proot-filter:/logs"
 -w /probe /usr/bin/env -i HOME=/root USER=root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
 LANG=C.UTF-8 TMPDIR=/tmp PYTHONUNBUFFERED=1 LSB_TEST_ENGINE=fex)
timeout --kill-after=5s 30s "${filtered_proot[@]}" python3 /opt/lsb/proot_preflight.py --sysvipc \
 2>&1 | tee out/fex-test/logs/proot-filter/preflight.log
python3 - <<'PY'
from pathlib import Path
rows=Path('out/fex-test/logs/proot-filter/preflight.log').read_text().splitlines()
assert 'TRASC PRoot: seccomp acceleration observed' in rows,'PRoot did not activate syscall filtering'
assert 'LSB_PROOT_PREFLIGHT_V1 PASS' in rows,'Production credential-free preflight did not pass'
assert 'LSB_PROOT_PREFLIGHT_V1 FAIL' not in rows
print('PASS: enabled PRoot filter observed with production file, mmap, socket, thread, fork and shared-memory checks',flush=True)
PY
"${filtered_proot[@]}" /bin/sh -ec 'python3 /tests/fex_prefix.py copy; python3 /tests/integration.py; python3 /tests/fex_prefix.py verify' \
 2>&1 | tee out/fex-test/logs/proot-filter/integration.log
python3 - <<'PY'
from pathlib import Path
rows=Path('out/fex-test/logs/proot-filter/integration.log').read_text().splitlines()
assert 'TRASC PRoot: seccomp acceleration observed' in rows,'FEX integration did not activate syscall filtering'
assert any(row.startswith('PASS: software cycle 2 PE32, registry, COM, D3D8 pixels, keyboard/mouse, PCM and clean exit') for row in rows)
assert 'PASS: explicit stop closes Wine and its private display' in rows
print('PASS: enabled PRoot filter preserves real FEX execution, D3D8 pixels, native capture, PCM, input and clean stop',flush=True)
PY

# Modern Mesa/DXVK checks are independent of the syscall-filter proof above.
cat > out/fex-test/Dockerfile <<'DOCKER'
FROM lsb-fex:base
RUN sha256sum /usr/bin/Xtigervnc > /tmp/xserver.sha256 && rm -f /etc/apt/sources.list /etc/apt/sources.list.d/* && echo 'deb https://deb.debian.org/debian trixie main' > /etc/apt/sources.list && apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y -o Dpkg::Options::=--force-confold install --no-install-recommends mesa-vulkan-drivers && sha256sum -c /tmp/xserver.sha256 && apt-get clean && rm -rf /var/lib/apt/lists/*
DOCKER
docker build -t lsb-fex:modern out/fex-test
docker run --rm --network none "${common[@]}" "${native[@]}" \
 -e LSB_TEST_RENDERER=turnip26 -e LSB_TEST_DXVK=2.7.1 -e LSB_TEST_VULKAN_ICD=/usr/share/vulkan/icd.d/lvp_icd.json \
 -v "$PWD/out/fex-test/logs/dxvk271:/logs" lsb-fex:modern \
 sh -ec 'python3 /tests/fex_prefix.py copy; python3 /tests/integration.py'

# Full compatibility-mode gate remains mandatory after the focused filter check.
PROOT_LOADER="$PWD/out/fex-test/proot-src/src/loader/loader" PROOT_NO_SECCOMP=1 PROOT_TMP_DIR="$short/tmp" \
 out/fex-test/proot-src/src/proot --link2symlink --kill-on-exit --sysvipc -0 -r "$PWD/out/fex-test/proot-root" \
 -b /dev -b /proc -b /sys -b "$PWD/out/fex-test/backend:/opt/lsb" -b "$PWD/out/fex-test/wine:/opt/wine" \
 -b "$PWD/out/fex-test/probe:/probe" -b "$PWD/tests/runtime:/tests" -b "$PWD/out/windows-tests:/fixtures" \
 -b "$PWD/out/fex-test/baseline-prefix:/baseline-prefix" -b "$PWD/out/fex-test/client:/client" \
 -b "$PWD/out/fex-test/session:/session" -b "$short/prefix:/prefix" -b "$short/tmp:/tmp" -b "$PWD/out/fex-test/logs/proot:/logs" \
 -w /probe /usr/bin/env -i HOME=/root USER=root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 TMPDIR=/tmp PYTHONUNBUFFERED=1 LSB_TEST_ENGINE=fex \
 /bin/sh -ec 'python3 /tests/fex_prefix.py copy; python3 /tests/integration.py; python3 /tests/initialization.py; python3 /tests/dependency_check.py; python3 /tests/launching.py; python3 /tests/gamepad.py; python3 /tests/fex_prefix.py verify'
