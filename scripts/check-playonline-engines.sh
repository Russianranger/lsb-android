#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
# Actual patched PRoot with filtering active in all four cases. Compare the
# DirectDraw compatibility and Vulkan backends using independent stopped seed
# copies. Only the Vulkan arm repeats official viewer/lifecycle qualification.
matrix="$PWD/out/playonline-test/engine-matrix"
mkdir -p "$matrix"/{root,wine,baseline-prefix,seed-session,seed-tmp,seed-logs}
tar -xzf out/fex-runtime/runtime-fex-arm64.tar.gz -C "$matrix/wine"
cp out/fex-runtime/fex-bundle.json out/playonline-test/backend/
container=$(docker create lsb-playonline:test /bin/true)
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
mkdir -p "$matrix/root"/{prefix,session,logs,client,baseline-prefix,official,tests,fixtures,ci-filter,opt/lsb,probe}
host=(sudo unshare --net --setgid "$(id -g)" --setuid "$(id -u)" --
 /usr/bin/env -u PROOT_NO_SECCOMP -u TRASC_PROOT_REPORT
 "PROOT_LOADER=$matrix/proot-src/src/loader/loader" "PROOT_TMP_DIR=$short/loader")
proot=("$matrix/proot-src/src/proot" --link2symlink --kill-on-exit -0 -r "$matrix/root"
 -b /dev -b /proc -b /sys -b "$PWD/out/playonline-test/backend:/opt/lsb"
 -b "$PWD/out/playonline-test/probe:/probe" -b "$PWD/tests/runtime:/tests"
 -b "$PWD/out/windows-tests:/fixtures" -b "$PWD/.tools/playonline-smoke/viewer:/official")
guest=(/usr/bin/env -i HOME=/root USER=root PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
 LANG=C.UTF-8 TMPDIR=/tmp PYTHONUNBUFFERED=1)
# The root observer receives PRoot's own descriptor, while all guest stdout and
# stderr are redirected before test Python starts. Ordinary traced CI uid cannot
# rewrite root-owned 0444 evidence in the separate 0755 /ci-filter binding.
observe_filter() {
 local proof="$1" stage="$2" private_output="$3" evidence="$4"
 shift 4
 sudo python3 - "$proof" "$stage" "$private_output" "$evidence" "$@" <<'PYOBSERVER'
import json
import os
from pathlib import Path
import subprocess
import sys
proof, stage, private_output = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
evidence = Path(sys.argv[4])
proof.mkdir(parents=True, exist_ok=True)
os.chmod(proof, 0o755)
active = proof / 'active'
active.unlink(missing_ok=True)
if stage == 'preflight':
    (proof / 'preflight').unlink(missing_ok=True)

def publish(name, data):
    temporary = proof / (name + '.new')
    temporary.write_bytes(data)
    os.chmod(temporary, 0o444)
    temporary.replace(proof / name)

marker = b'TRASC PRoot: seccomp acceleration observed\n'
process = subprocess.Popen(sys.argv[5:], stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
observed = False
host_tail = b''
for line in process.stdout:
    host_tail = (host_tail + line)[-16384:]
    if line == marker:
        publish('active', marker)
        observed = True
code = process.wait()
if code or not observed:
    # These disposable CI fixtures contain no user input. Guest output stays
    # separate from the live marker parser, including when preserving failure
    # evidence; do not make a failed assertion disappear with the temp tree.
    tail = b''
    if private_output.is_file():
        with private_output.open('rb') as stream:
            stream.seek(max(0, private_output.stat().st_size - 16384))
            tail = stream.read(16384)
    failure = {'format': 1, 'stage': stage, 'exit_code': code,
               'host_marker_observed': observed,
               'host_output_tail': host_tail.decode('utf-8', errors='replace'),
               'guest_output_tail': tail.decode('utf-8', errors='replace'),
               'scope': 'disposable public and synthetic CI fixtures only'}
    (evidence / ('filter-' + stage + '-failure.json')).write_text(json.dumps(failure, indent=2))
    print(json.dumps(failure, sort_keys=True), flush=True)
assert code == 0, 'Filtered PRoot ' + stage + ' failed (exit ' + str(code) + ')'
assert observed, 'Actual PRoot filtering activation was not observed'
if stage == 'preflight':
    expected = b'LSB_PROOT_PREFLIGHT_V1 PASS\n'
    assert private_output.read_bytes() == expected, 'Production filtered preflight did not pass'
    publish('preflight', expected)
print('PASS: host-observed actual filtered PRoot ' + stage, flush=True)
PYOBSERVER
}
timeout --kill-after=5s 240s "${host[@]}" PROOT_NO_SECCOMP=1 "${proot[@]}" \
 -b "$PWD/out/playonline-test/backend/wineserver:/opt/wine/bin/wineserver" \
 -b "$matrix/baseline-prefix:/prefix" -b "$matrix/seed-session:/session" -b "$matrix/seed-logs:/logs" -b "$short/seed-tmp:/tmp" \
 -w /probe "${guest[@]}" /usr/bin/python3 /tests/playonline_engine_matrix.py seed
icd=$(find "$matrix/root/usr/share/vulkan/icd.d" -maxdepth 1 -name 'lvp_icd*.json' -print -quit)
test -n "$icd"
icd="${icd#"$matrix/root"}"
failed_cases=()
for engine in box64 fex; do
 wine_bind=(-b "$PWD/out/playonline-test/backend/wineserver:/opt/wine/bin/wineserver")
 if [[ "$engine" == fex ]]; then wine_bind=(-b "$matrix/wine:/opt/wine"); fi
 for mode in baseline filtered; do
  case_root="$short/$engine-$mode"
  logs="$PWD/out/playonline-test/logs/$engine/$mode"
  proof="$matrix/filter-control/$engine-$mode"
  mkdir -p "$case_root"/{prefix,session,tmp,client} "$logs"
  # Short paths avoid PRoot's host-path AF_UNIX limit. Each mode gets its own
  # stopped prefix copy; neither uses a warmed prefix from the other mode.
  bindings=("${wine_bind[@]}" -b "$matrix/baseline-prefix:/baseline-prefix"
   -b "$case_root/prefix:/prefix" -b "$case_root/session:/session"
   -b "$case_root/tmp:/tmp" -b "$case_root/client:/client" -b "$logs:/logs")
  settings=(LSB_TEST_ENGINE="$engine" LSB_TEST_RENDERER=turnip26 LSB_TEST_VULKAN_ICD="$icd"
   LSB_TEST_MATRIX_MODE="$mode" LSB_PLAYONLINE_PRODUCTION_ONLY=1 LSB_TEST_COPIED_PREFIX=1
   LSB_TEST_REPAIR_DIAGNOSTICS=1 LSB_TEST_DDRAW_PROBE=1)
  backend=0
  limit=900s
  if [[ "$mode" == filtered ]]; then backend=1; limit=1200s; fi
  sudo mkdir -p "$proof"
  sudo chmod 755 "$proof"
  bindings+=(-b "$proof:/ci-filter")
  if ! observe_filter "$proof" preflight "$case_root/session/preflight-private.log" "$logs" \
   timeout --kill-after=5s 30s "${host[@]}" TRASC_PROOT_REPORT=1 "${proot[@]}" "${bindings[@]}" \
   -w /probe /bin/sh -c 'exec "$@" > /session/preflight-private.log 2>&1' sh \
   "${guest[@]}" /usr/bin/python3 /opt/lsb/proot_preflight.py; then
   failed_cases+=("$engine/$mode: preflight")
   continue
  fi
  if ! observe_filter "$proof" qualification "$case_root/session/qualification-private.log" "$logs" \
   timeout --kill-after=5s "$limit" "${host[@]}" TRASC_PROOT_REPORT=1 "${proot[@]}" "${bindings[@]}" \
   -w /probe /bin/sh -c 'exec "$@" > /session/qualification-private.log 2>&1' sh \
   "${guest[@]}" "${settings[@]}" LSB_TEST_FILTERED=1 LSB_TEST_UPDATER_VULKAN_DDRAW="$backend" \
   /usr/bin/python3 /tests/playonline_engine_matrix.py; then
   failed_cases+=("$engine/$mode: qualification")
  fi
 done
done
# Capture independent engine evidence after a failure, but never publish a
# successful paired comparison or permit this gate to pass with any failed arm.
if (( ${#failed_cases[@]} )); then
 printf 'FAILED updater qualification: %s\n' "${failed_cases[@]}" >&2
 exit 1
fi
python3 - <<'PYPAIR'
import json
from pathlib import Path
rows = []
for engine in ('box64', 'fex'):
    root = Path('out/playonline-test/logs') / engine
    baseline = json.loads((root / 'baseline/playonline-engine-matrix.json').read_text())
    candidate = json.loads((root / 'filtered/playonline-engine-matrix.json').read_text())
    assert baseline['baseline_prefix_sha256'] == candidate['baseline_prefix_sha256']
    assert baseline['renderer'] == candidate['renderer'] == 'DXVK 2.5.3 / CI lavapipe'
    assert baseline['filtering'] and candidate['filtering'], 'Renderer pair must both use actual PRoot filtering'
    assert baseline['mode'] == 'compatibility' and candidate['mode'] == 'vulkan'
    assert baseline['directdraw_workload'] == candidate['directdraw_workload']
    before_draw, after_draw = (row['directdraw_graphics']['probe'] for row in (baseline, candidate))
    assert before_draw['backend'] == 'opengl' and after_draw['backend'] == 'vulkan'
    directdraw_timings = []
    for field in ('elapsed_wall_ms', 'elapsed_ms', 'render_ms', 'first_frame_ms', 'max_frame_ms'):
        before, after = before_draw[field], after_draw[field]
        directdraw_timings.append({'name': field, 'opengl': before, 'vulkan': after,
                                  'opengl_over_vulkan': round(before / after, 3) if after else None})
    phases = []
    before_phases = baseline['repair_io']['windows']['phases']
    after_phases = candidate['repair_io']['windows']['phases']
    assert len(before_phases) == len(after_phases)
    for before, after in zip(before_phases, after_phases):
        assert (before['name'], before['operations'], before['bytes']) == (after['name'], after['operations'], after['bytes'])
        phases.append({'name': before['name'], 'operations': before['operations'], 'bytes': before['bytes'],
                       'compatibility_us': before['qpc_us'], 'vulkan_us': after['qpc_us']})
    rows.append({'engine': engine, 'filtering': 'syscall_filter_in_both_arms',
                 'directdraw_workload': baseline['directdraw_workload'],
                 'directdraw_timings_ms': directdraw_timings,
                 'compatibility_adapter_vendor_id': before_draw['adapter_vendor_id'],
                 'vulkan_adapter_vendor_id': after_draw['adapter_vendor_id'],
                 'file_io_control_phases': phases,
                 'compatibility_native_io_ms': baseline['repair_io']['native']['elapsed_ms'],
                 'vulkan_native_io_ms': candidate['repair_io']['native']['elapsed_ms'],
                 'compatibility_windows_io_ms': baseline['repair_io']['windows']['elapsed_ms'],
                 'vulkan_windows_io_ms': candidate['repair_io']['windows']['elapsed_ms']})
result = {'format': 1, 'cases': rows, 'speed_threshold': None, 'online_repair_verified': False,
          'interpretation': 'One paired DirectDraw sample per engine, with filtering active in both arms. CI uses software GL/Vulkan, not the phone GPU; file I/O is a separate control. Host caches/order may differ. No phone repair throughput guarantee.'}
Path('out/playonline-test/logs/playonline-directdraw-comparison.json').write_text(json.dumps(result, indent=2))
print('PASS: filtered updater DirectDraw compatibility/Vulkan qualification for both engines; comparison', json.dumps(result, sort_keys=True))
PYPAIR
