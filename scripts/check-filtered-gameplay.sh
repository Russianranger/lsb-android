#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/prepare-runtime.py --release
work="$PWD/out/filtered-gameplay-test"
mkdir -p "$work"/{backend,probe,wine,root,logs,filter-control}
cp out/runtime-assets/* out/presentation/* runtime/*.py "$work/backend/"
cp out/runtime-probes/* "$work/probe/"
cp out/runtime-probes/liblsb-gamepad.so out/fex-runtime/fex-bundle.json "$work/backend/"
chmod +x "$work/backend"/{vulkan-probe,wineserver,x11-frame-bridge,x11-upload-check}
tar -xzf out/fex-runtime/runtime-fex-arm64.tar.gz -C "$work/wine"
docker import .tools/runtime/runtime-arm64.tar.gz lsb-filtered-gameplay:base
# Match the existing DXVK 2.7.1 qualification rootfs: update Mesa only and
# preserve the exact pinned TigerVNC binary. This disposable root is CI-only.
cat > "$work/Dockerfile" <<'DOCKER'
FROM lsb-filtered-gameplay:base
RUN sha256sum /usr/bin/Xtigervnc > /tmp/xserver.sha256 && rm -f /etc/apt/sources.list /etc/apt/sources.list.d/* && echo 'deb https://deb.debian.org/debian trixie main' > /etc/apt/sources.list && apt-get update && DEBIAN_FRONTEND=noninteractive apt-get -y -o Dpkg::Options::=--force-confold install --no-install-recommends mesa-vulkan-drivers && sha256sum -c /tmp/xserver.sha256 && apt-get clean && rm -rf /var/lib/apt/lists/*
DOCKER
docker build -t lsb-filtered-gameplay:modern "$work"
container=$(docker create lsb-filtered-gameplay:modern /bin/true)
docker export "$container" | tar -xf - -C "$work/root"
docker rm "$container" >/dev/null
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends build-essential libtalloc-dev gawk
git clone --quiet https://github.com/termux/proot.git "$work/proot-src"
git -C "$work/proot-src" checkout --quiet 7266fb3e8516535682f5a9c8f3a7e70f6506eddb
git -C "$work/proot-src" apply "$PWD/native/proot-acceleration.patch" "$PWD/native/proot-sysvipc.patch"
sed -i '1i#include <string.h>' "$work/proot-src/src/extension/ashmem_memfd/ashmem_memfd.c"
make -C "$work/proot-src/src" -j2 PROOT_UNBUNDLE_LOADER=/unused HAS_LOADER_32BIT=
short=$(mktemp -d /tmp/lsb-filter.XXXXXX)
trap 'rm -rf "$short"' EXIT
mkdir -p "$short"/{prefix,session,client,tmp,loader}
mkdir -p "$work/root"/{prefix,session,client,logs,probe,fixtures,tests,ci-filter,opt/lsb,presentation}
host=(sudo unshare --net --setgid "$(id -g)" --setuid "$(id -u)" --
 /usr/bin/env -u PROOT_NO_SECCOMP "PROOT_LOADER=$work/proot-src/src/loader/loader"
 "PROOT_TMP_DIR=$short/loader" TRASC_PROOT_REPORT=1)
proot=("$work/proot-src/src/proot" --link2symlink --kill-on-exit --sysvipc -0 -r "$work/root"
 -b /dev -b /proc -b /sys -b "$work/backend:/opt/lsb" -b "$work/backend:/presentation" -b "$work/wine:/opt/wine"
 -b "$work/probe:/probe" -b "$PWD/tests/runtime:/tests" -b "$PWD/out/windows-tests:/fixtures"
 -b "$short/prefix:/prefix" -b "$short/session:/session" -b "$short/client:/client"
 -b "$short/tmp:/tmp" -b "$work/logs:/logs" -b "$work/filter-control:/ci-filter" -w /probe)
icd=$(find "$work/root/usr/share/vulkan/icd.d" -maxdepth 1 -name 'lvp_icd*.json' -print -quit)
test -n "$icd"
icd="${icd#"$work/root"}"
guest=(/usr/bin/env -i HOME=/root USER=root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
 LANG=C.UTF-8 TMPDIR=/tmp PYTHONUNBUFFERED=1 "LSB_TEST_VULKAN_ICD=$icd")
# A root-owned observer consumes only PRoot's descriptor, and gives the ordinary
# traced uid read-only marker/preflight proof. Requested options cannot stand in
# for activation. Guest stdout/stderr are redirected before the fixture starts.
sudo python3 tests/runtime/proot_integration.py observe "$work/filter-control" preflight \
 "$short/session/preflight-private.log" "$work/logs" \
 timeout --kill-after=5s 30s "${host[@]}" "${proot[@]}" \
 /bin/sh -c 'exec "$@" > /session/preflight-private.log 2>&1' sh \
 "${guest[@]}" /usr/bin/python3 /opt/lsb/proot_preflight.py --sysvipc
# Drive the real packaged native bridge under the same observed syscall filter:
# exact pixels, unchanged/duplicate frames, ownership, cursor and actual resize,
# including the J/K capture policies. This fixture owns its separate X server.
sudo python3 tests/runtime/proot_integration.py observe "$work/filter-control" native-presentation \
 "$short/session/native-presentation-private.log" "$work/logs" \
 timeout --kill-after=5s 240s "${host[@]}" "${proot[@]}" \
 /bin/sh -c 'exec "$@" > /session/native-presentation-private.log 2>&1' sh \
 "${guest[@]}" /usr/bin/python3 /tests/display_fixture.py /logs/native-presentation --check-only
for cycle in 1 2 3 4 5; do
 trial=none
 case "$cycle" in
  3) trial=capture90 ;;
  4) trial=capture_deadline ;;
  5) trial=frame_latency ;;
 esac
 sudo python3 tests/runtime/proot_integration.py observe "$work/filter-control" "launch-$cycle" \
  "$short/session/qualification-private.log" "$work/logs" \
  timeout --kill-after=5s 360s "${host[@]}" "${proot[@]}" \
  /bin/sh -c 'exec "$@" > /session/qualification-private.log 2>&1' sh \
  "${guest[@]}" /usr/bin/python3 /tests/proot_integration.py launch "$cycle" "$trial"
done
python3 - <<'PY'
import json
from pathlib import Path
logs = Path('out/filtered-gameplay-test/logs')
rows = [json.loads((logs / ('filtered-launch-' + str(cycle) + '.json')).read_text()) for cycle in range(1, 6)]
assert len({row['runtime']['session_id'] for row in rows}) == 5, 'Relaunch reused a session receipt'
assert [row['trial'] for row in rows] == ['none', 'none', 'capture90', 'capture_deadline', 'frame_latency']
assert all(row['native_consumer']['requests'] == 10 for row in rows), 'A launch did not consume real native frames'
assert len({row['native_consumer']['pid'] for row in rows}) == 5, 'Relaunch reused a native helper receipt'
hosts = [json.loads((logs / ('filter-launch-' + str(cycle) + '.json')).read_text()) for cycle in range(1, 6)]
assert len({row['host_pid'] for row in hosts}) == 5, 'Relaunch did not start a fresh host process'
assert all(row['host_marker_observed'] and row['exit_code'] == 0 for row in hosts)
assert json.loads((logs / 'filter-native-presentation.json').read_text())['host_marker_observed']
print('PASS: five independent host-filtered FEX/DXVK gameplay launches: repeated baseline and J/K/L, 720 independently checked pixels and 50 real native frame requests; filtered native pixels/ownership/resize checked; CI lavapipe, not a phone GPU result', flush=True)
PY
