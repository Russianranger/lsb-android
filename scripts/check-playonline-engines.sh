#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Invoked after check-playonline.sh has prepared its pinned official fixture and
# Bookworm rootfs plus CI Mesa package. The renderer and all flags are identical
# across engines; this checks real PRoot rather than substituting Docker for it.
matrix="$PWD/out/playonline-test/engine-matrix"
mkdir -p "$matrix"/{root,wine,baseline-prefix,seed-session,seed-tmp,seed-logs}
tar -xzf out/fex-runtime/runtime-fex-arm64.tar.gz -C "$matrix/wine"
cp out/fex-runtime/fex-bundle.json out/playonline-test/backend/
container=$(docker create lsb-playonline:test)
docker export "$container" | tar -xf - -C "$matrix/root"
docker rm "$container" >/dev/null
sudo apt-get install -y --no-install-recommends build-essential libtalloc-dev gawk
git clone --quiet https://github.com/termux/proot.git "$matrix/proot-src"
git -C "$matrix/proot-src" checkout --quiet 7266fb3e8516535682f5a9c8f3a7e70f6506eddb
git -C "$matrix/proot-src" apply "$PWD/native/proot-acceleration.patch" "$PWD/native/proot-sysvipc.patch"
sed -i '1i#include <string.h>' "$matrix/proot-src/src/extension/ashmem_memfd/ashmem_memfd.c"
make -C "$matrix/proot-src/src" -j2 PROOT_UNBUNDLE_LOADER=/unused HAS_LOADER_32BIT=
short=$(mktemp -d /tmp/lsb-pol.XXXXXX)
trap 'rm -rf "$short"' EXIT
mkdir -p "$short/loader" "$short/seed-tmp"
mkdir -p "$matrix/root"/{prefix,session,logs,client,baseline-prefix,official,tests,fixtures,opt/lsb,probe}
common=(sudo unshare --net --setgid "$(id -g)" --setuid "$(id -u)" --
 /usr/bin/env "PROOT_LOADER=$matrix/proot-src/src/loader/loader" "PROOT_TMP_DIR=$short/loader" PROOT_NO_SECCOMP=1
 "$matrix/proot-src/src/proot" --link2symlink --kill-on-exit -0 -r "$matrix/root"
 -b /dev -b /proc -b /sys -b "$PWD/out/playonline-test/backend:/opt/lsb"
 -b "$PWD/out/playonline-test/probe:/probe" -b "$PWD/tests/runtime:/tests"
 -b "$PWD/out/windows-tests:/fixtures" -b "$PWD/.tools/playonline-smoke/viewer:/official")
guest=(/usr/bin/env -i HOME=/root USER=root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
 LANG=C.UTF-8 TMPDIR=/tmp PYTHONUNBUFFERED=1)
timeout --kill-after=5s 240s "${common[@]}" \
 -b "$PWD/out/playonline-test/backend/wineserver:/opt/wine/bin/wineserver" \
 -b "$matrix/baseline-prefix:/prefix" -b "$matrix/seed-session:/session" -b "$matrix/seed-logs:/logs" -b "$short/seed-tmp:/tmp" \
 -w /probe "${guest[@]}" /usr/bin/python3 /tests/playonline_engine_matrix.py seed
icd=$(find "$matrix/root/usr/share/vulkan/icd.d" -maxdepth 1 -name 'lvp_icd*.json' -print -quit)
test -n "$icd"
icd="${icd#"$matrix/root"}"
for engine in box64 fex; do
 mkdir -p "$short/$engine"/{prefix,session,tmp,client} "out/playonline-test/logs/$engine"
 wine_bind=(-b "$PWD/out/playonline-test/backend/wineserver:/opt/wine/bin/wineserver")
 if [[ "$engine" == fex ]]; then wine_bind=(-b "$matrix/wine:/opt/wine"); fi
 # PRoot's socket names contain host paths: short per-engine prefixes avoid
 # AF_UNIX limits without reducing isolation between the two comparisons.
 timeout --kill-after=5s 900s "${common[@]}" "${wine_bind[@]}" \
  -b "$matrix/baseline-prefix:/baseline-prefix" -b "$short/$engine/prefix:/prefix" \
  -b "$short/$engine/session:/session" -b "$short/$engine/tmp:/tmp" -b "$short/$engine/client:/client" \
  -b "$PWD/out/playonline-test/logs/$engine:/logs" -w /probe "${guest[@]}" \
  LSB_TEST_ENGINE="$engine" LSB_TEST_RENDERER=turnip26 LSB_TEST_VULKAN_ICD="$icd" \
  LSB_PLAYONLINE_PRODUCTION_ONLY=1 LSB_TEST_COPIED_PREFIX=1 LSB_TEST_REPAIR_DIAGNOSTICS=1 \
  /usr/bin/python3 /tests/playonline_engine_matrix.py
done
