"""Bounded, read-only process counters for a PlayOnline repair session.

Membership is established by the supervisor's exact tracer identity or observed
ancestry, never by an executable name. Process and task names from stat/comm
are mapped to fixed labels and are not exported. No cmdline, environ, filenames or window text is
read. Samples describe only observed processes; discovery is deliberately
bounded and a short-lived/unreadable process may be missed.
"""
from collections import deque
import copy
import json
import re
import os
from pathlib import Path
import threading
import time

INTERVAL_SECONDS = 5.0
DISCOVERY_SECONDS = 15.0
THREAD_SECONDS = 15.0
MAX_PROCESSES = 64
MAX_VIEWER_THREADS = 64
MAX_SCAN_ENTRIES = 512
MAX_SAMPLES = 120
MAX_REPORT_BYTES = 128 * 1024
BUDGET_SECONDS = 0.1
_STATES = frozenset(('R', 'S', 'D', 'Z', 'T', 't', 'I'))
_NAMES = {'pol.exe': 'viewer', 'wineserver': 'wineserver',
          'wineserver64': 'wineserver', 'box64': 'translator',
          'fexinterpreter': 'translator', 'fexloader': 'translator',
          'xtigervnc': 'display', 'xvnc': 'display'}
_IO = ('rchar', 'wchar', 'syscr', 'syscw', 'read_bytes', 'write_bytes')
# Exact names emitted by Wine's wined3d command stream and DXVK 2.5.3.
# Mesa llvmpipe emits "llvmpipe-%u". Other names are deliberately not guessed:
# a role identifies a named worker, not its current stack or active renderer.
_THREAD_NAMES = {'wined3d_cs': 'wined3d_command_stream',
                 'dxvk-cs': 'dxvk_command_stream',
                 'dxvk-submit': 'dxvk_submission', 'dxvk-queue': 'dxvk_submission',
                 'dxvk-shader-h': 'dxvk_compiler', 'dxvk-shader-n': 'dxvk_compiler',
                 'dxvk-shader-l': 'dxvk_compiler'}


def _thread_role(pid, tid, name):
    if pid == tid:
        return 'main'
    if re.fullmatch(r'llvmpipe-[0-9]{1,6}', name):
        return 'software_rasterizer'
    return _THREAD_NAMES.get(name, 'other')


def _read(path, limit):
    with path.open('rb') as stream:
        value = stream.read(limit + 1)
    if len(value) > limit:
        raise ValueError('oversized proc record')
    return value.decode('ascii', 'replace')


def _stat(path):
    value = _read(path, 4096)
    begin, end = value.find('('), value.rfind(')')
    fields = value[end + 1:].split()
    if begin < 1 or end < begin or len(fields) < 22:
        raise ValueError('invalid proc stat')
    numbers = [int(fields[i]) for i in (1, 11, 12, 17, 19)]
    if min(numbers) < 0:
        raise ValueError('invalid proc counters')
    return {'pid': int(value[:begin]), 'name': value[begin + 1:end].lower(),
            'state': fields[0] if fields[0] in _STATES else 'other',
            'ppid': numbers[0], 'ticks': numbers[1] + numbers[2],
            'threads': numbers[3], 'start': numbers[4]}


def _status(path):
    values = {}
    for line in _read(path, 8192).splitlines():
        key, _, value = line.partition(':')
        if key in ('Uid', 'TracerPid'):
            values[key] = int(value.split()[0])
    if set(values) != {'Uid', 'TracerPid'} or min(values.values()) < 0:
        raise ValueError('invalid proc status')
    return values


def _io(path):
    values = {}
    for line in _read(path, 2048).splitlines():
        key, _, value = line.partition(':')
        if key in _IO:
            values[key] = int(value.strip())
    if set(values) != set(_IO) or min(values.values()) < 0:
        raise ValueError('invalid proc io')
    return values


class Monitor:
    """Call start(), snapshot() and stop(); failure never blocks a repair.

    `root_pid` is the current supervisor, not a Wine PID. Linux CPU ticks and I/O
    are host process counters. CPU deltas exclude newly discovered processes and
    PID reuse; missing counters are reported as unavailable, never fabricated as
    zero. A tracer can outlive the supervisor but must retain its original start
    time. An ancestry-only scope cannot discover already-detached unknown tasks.
    """
    def __init__(self, root_pid=None, proc_root='/proc', clock=time.monotonic,
                 session_id=None, output_path=None):
        if session_id is not None and not re.fullmatch(r'[a-zA-Z0-9-]{1,64}', session_id):
            raise ValueError('invalid session identity')
        self.session_id = session_id
        self.output_path = None if output_path is None else Path(output_path)
        self._write_lock = threading.Lock()
        self._write_failures = 0
        self._last_write_ms = None
        self.root_pid = os.getpid() if root_pid is None else root_pid
        self.proc = Path(proc_root)
        self.clock = clock
        self.tick_hz = os.sysconf('SC_CLK_TCK')
        self.started = clock()
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread = None
        self._samples = deque(maxlen=MAX_SAMPLES)
        self._known = {}
        self._previous = {}
        self._thread_previous = {}
        self._threads_at = None
        self._anchor = None
        self._tracer = None
        self._uid = None
        self._discovery_at = float('-inf')
        self._scan_iterator = None
        self._recorded = 0
        self._failed = 0
        self._discovery = {'state': 'not_started'}
        self._state = 'not_started'

    def _record(self, pid):
        folder = self.proc / str(pid)
        row = _stat(folder / 'stat')
        status = _status(folder / 'status')
        if row['pid'] != pid:
            raise ValueError('proc identity changed')
        # Verify the numeric start time after status to reject PID reuse races.
        after = _stat(folder / 'stat')
        if after['pid'] != pid or after['start'] != row['start']:
            raise ValueError('proc identity changed')
        row.update(uid=status['Uid'], tracer=status['TracerPid'])
        return row

    def _establish(self):
        root = self._record(self.root_pid)
        identity = (self.root_pid, root['start'])
        if self._anchor is not None and identity != self._anchor:
            raise ValueError('supervisor identity changed')
        self._anchor, self._uid = identity, root['uid']
        self._known[self.root_pid] = root['start']
        if root['tracer']:
            try:
                tracer = self._record(root['tracer'])
                candidate = (tracer['pid'], tracer['start'])
                if tracer['uid'] == self._uid and (self._tracer is None or candidate == self._tracer):
                    self._tracer = candidate
                    self._known[candidate[0]] = candidate[1]
                    if len(self._known) > MAX_PROCESSES:
                        removable = [pid for pid in self._known if pid not in (self.root_pid, candidate[0])]
                        self._known.pop(removable[-1], None)
                    return True
            except (OSError, ValueError):
                pass
        return False

    def _discover(self, deadline, tracer_valid, sampled):
        visited, unreadable, same_uid = 0, 0, 0
        bounded = False
        # Parent identities read successfully in this same poll can establish
        # ancestry immediately, without a second /proc read or a lucky cursor
        # order. Unknown/reused PIDs are absent from `sampled`.
        rows = {pid: {'pid': pid, 'start': start} for pid, start in sampled}
        try:
            # Retain one directory cursor between polls so a capped scan does
            # not repeatedly inspect the same low PIDs and miss later children.
            if self._scan_iterator is None:
                self._scan_iterator = os.scandir(self.proc)
            for _ in range(MAX_SCAN_ENTRIES):
                if self.clock() >= deadline:
                    bounded = True
                    break
                try:
                    item = next(self._scan_iterator)
                except StopIteration:
                    self._scan_iterator.close()
                    self._scan_iterator = None
                    break
                visited += 1
                if not item.name.isascii() or not item.name.isdecimal():
                    continue
                pid = int(item.name)
                try:
                    row = self._record(pid)
                except (OSError, ValueError):
                    unreadable += 1
                    continue
                if row['uid'] == self._uid:
                    same_uid += 1
                    rows[pid] = row
                    # Enroll an already validated match immediately. A slow
                    # proc read may use the remaining budget; discarding that
                    # row before membership resolution would otherwise make
                    # every crowded PRoot scan discover no processes at all.
                    known = self._known.get(pid) == row['start']
                    if known:
                        continue
                    parent = rows.get(row['ppid'])
                    descendant = parent is not None and self._known.get(parent['pid']) == parent['start']
                    traced = tracer_valid and row['tracer'] == self._tracer[0]
                    if not known and (descendant or traced):
                        if len(self._known) >= MAX_PROCESSES:
                            bounded = True
                        else:
                            self._known[pid] = row['start']
            else:
                bounded = True
            # Resolve chains in the observed rows, independent of PID order.
            for _ in range(min(MAX_PROCESSES, len(rows)) + 1):
                changed = False
                for pid, row in rows.items():
                    if self.clock() >= deadline:
                        bounded = True
                        break
                    known = self._known.get(pid) == row['start']
                    if known:
                        continue
                    parent = rows.get(row['ppid'])
                    descendant = parent is not None and self._known.get(parent['pid']) == parent['start']
                    if not descendant and row['ppid'] in self._known and self.clock() < deadline:
                        try:
                            parent = self._record(row['ppid'])
                            descendant = self._known.get(parent['pid']) == parent['start']
                        except (OSError, ValueError):
                            pass
                    traced = tracer_valid and row['tracer'] == self._tracer[0]
                    if not known and (descendant or traced):
                        if len(self._known) >= MAX_PROCESSES:
                            bounded = True
                            continue
                        self._known[pid] = row['start']
                        changed = True
                if not changed:
                    break
            self._discovery = {'state': 'bounded' if bounded else 'observed',
                               'entries': visited, 'unreadable': unreadable,
                               'same_uid': same_uid}
        except OSError:
            self._discovery = {'state': 'unavailable', 'entries': visited,
                               'unreadable': unreadable, 'same_uid': same_uid}

    def _viewer_threads(self, viewers, started, deadline):
        """Read only owned viewer tasks within the poll's remaining budget.

        Per-thread identities include both process and thread start times.
        Every attempted thread poll replaces the baseline, so a missed task
        never contributes a delta covering an unreported longer interval.
        Raw names, paths and identities never enter the exported report.
        """
        current, roles = {}, {}
        entries = unreadable = observed = 0
        bounded = False
        for pid, expected in viewers:
            if entries >= MAX_VIEWER_THREADS or self.clock() >= deadline:
                bounded = True
                break
            rows = []
            try:
                owner = self._record(pid)
                if owner['start'] != expected or owner['uid'] != self._uid:
                    raise ValueError('viewer identity changed')
                with os.scandir(self.proc / str(pid) / 'task') as tasks:
                    for item in tasks:
                        if entries >= MAX_VIEWER_THREADS or self.clock() >= deadline:
                            bounded = True
                            break
                        entries += 1
                        if not item.name.isascii() or not item.name.isdecimal():
                            continue
                        tid = int(item.name)
                        folder = self.proc / str(pid) / 'task' / str(tid)
                        try:
                            row = _stat(folder / 'stat')
                            status = _status(folder / 'status')
                            name = _read(folder / 'comm', 64).rstrip('\n').lower()
                            after = _stat(folder / 'stat')
                            if (row['pid'] != tid or after['pid'] != tid
                                    or row['start'] != after['start']
                                    or status['Uid'] != self._uid
                                    or name != row['name'] or name != after['name']):
                                raise ValueError('thread identity changed')
                            rows.append(((pid, expected, tid, row['start']),
                                         row['ticks'], _thread_role(pid, tid, name)))
                        except (OSError, ValueError):
                            unreadable += 1
                # Reject the whole viewer observation if the owning process
                # was replaced or changed credentials while tasks were read.
                owner = self._record(pid)
                if owner['start'] != expected or owner['uid'] != self._uid:
                    raise ValueError('viewer identity changed')
            except (OSError, ValueError):
                unreadable += 1
                continue
            for identity, ticks, role in rows:
                observed += 1
                counters = roles.setdefault(role, {'threads': 0, 'cpu_delta_ms': 0,
                    'cpu_delta_threads': 0, 'cpu_baseline_threads': 0})
                counters['threads'] += 1
                previous = self._thread_previous.get(identity)
                if previous is not None and previous['role'] == role and ticks >= previous['ticks']:
                    counters['cpu_delta_ms'] += (ticks - previous['ticks']) * 1000 // self.tick_hz
                    counters['cpu_delta_threads'] += 1
                else:
                    counters['cpu_baseline_threads'] += 1
                current[identity] = {'ticks': ticks, 'role': role}
        for counters in roles.values():
            if not counters['cpu_delta_threads']:
                counters['cpu_delta_ms'] = None
        report = {'state': ('bounded' if bounded else 'observed' if observed else
                            'unavailable' if viewers else 'no_viewer'),
                  'interval_ms': None if self._threads_at is None else
                      max(0, int((started - self._threads_at) * 1000)),
                  'entries': entries, 'unreadable': unreadable,
                  'threads_observed': observed, 'roles': roles}
        self._thread_previous = current
        self._threads_at = started
        return report

    def sample(self):
        """One bounded poll; public for deterministic fixtures, not a hot loop."""
        started = self.clock()
        deadline = started + BUDGET_SECONDS
        categories = {}
        missing = 0
        budget_hit = False
        try:
            tracer_valid = self._establish()
        except (OSError, ValueError):
            with self._lock:
                self._state = 'anchor_unavailable'
                self._failed += 1
            return
        current = {}
        viewers = []
        for pid, expected in list(self._known.items()):
            if self.clock() >= deadline:
                budget_hit = True
                break
            folder = self.proc / str(pid)
            try:
                row = _stat(folder / 'stat')
                if row['pid'] != pid or row['start'] != expected:
                    self._known.pop(pid, None)
                    continue
                try:
                    io = _io(folder / 'io')
                except (OSError, ValueError):
                    io = None
                after = _stat(folder / 'stat')
                if after['pid'] != pid or after['start'] != expected:
                    self._known.pop(pid, None)
                    continue
            except (OSError, ValueError):
                missing += 1
                # Gone/reused PIDs cannot retain scope membership indefinitely.
                self._known.pop(pid, None)
                continue
            category = ('supervisor' if pid == self.root_pid else
                        'tracer' if tracer_valid and (pid, expected) == self._tracer else
                        _NAMES.get(row['name'], 'other'))
            counters = categories.setdefault(category, {'processes': 0, 'threads': 0,
                'states': {}, 'cpu_delta_ms': 0, 'cpu_delta_processes': 0,
                'cpu_baseline_processes': 0, 'io_available_processes': 0,
                'io_delta_processes': 0, 'io_delta': {key: 0 for key in _IO}})
            counters['processes'] += 1
            counters['threads'] += row['threads']
            counters['states'][row['state']] = counters['states'].get(row['state'], 0) + 1
            identity = (pid, expected)
            previous = self._previous.get(identity)
            if previous is not None and row['ticks'] >= previous['ticks']:
                counters['cpu_delta_ms'] += (row['ticks'] - previous['ticks']) * 1000 // self.tick_hz
                counters['cpu_delta_processes'] += 1
            else:
                counters['cpu_baseline_processes'] += 1
            if io is not None:
                counters['io_available_processes'] += 1
                if previous is not None and previous['io'] is not None and all(io[k] >= previous['io'][k] for k in _IO):
                    for key in _IO:
                        counters['io_delta'][key] += io[key] - previous['io'][key]
                    counters['io_delta_processes'] += 1
            current[identity] = {'ticks': row['ticks'], 'io': io}
            if category == 'viewer':
                viewers.append(identity)
        for counters in categories.values():
            if not counters['cpu_delta_processes']:
                counters['cpu_delta_ms'] = None
            if not counters['io_delta_processes']:
                counters['io_delta'] = None
        # Known processes are sampled first. Discovery uses only the remaining
        # budget, so a crowded /proc cannot starve already identified children.
        if started - self._discovery_at >= DISCOVERY_SECONDS:
            self._discover(deadline, tracer_valid, current)
            self._discovery_at = started
        # Hot-thread diagnostics run less often, after normal process discovery,
        # and share its time budget. They cannot add an unbounded /proc walk.
        thread_report = None
        if self._threads_at is None or started - self._threads_at >= THREAD_SECONDS:
            thread_report = self._viewer_threads(viewers, started, deadline)
        elapsed = max(0, int((self.clock() - started) * 1000))
        sample = {'elapsed_ms': max(0, int((started - self.started) * 1000)),
                  'interval_ms': None if not self._samples else max(0, int((started - self._last_sample) * 1000)),
                  'sampling_ms': elapsed, 'state': 'bounded' if budget_hit else 'observed',
                  'scope': 'shared_tracer_and_ancestry' if tracer_valid else 'observed_ancestry_only',
                  'missing_processes': missing, 'known_processes': len(self._known),
                  'discovery': dict(self._discovery), 'categories': categories}
        if thread_report is not None:
            sample['viewer_threads'] = thread_report
        self._previous = current
        self._last_sample = started
        with self._lock:
            self._state = 'running'
            self._recorded += 1
            self._samples.append(sample)
            # Keep serialized diagnostics bounded even when all fixed categories
            # are present. Drop old samples, never shorten or invent counters.
            while len(json.dumps(self._snapshot_locked(), separators=(',', ':')).encode()) > MAX_REPORT_BYTES:
                for _ in range(max(1, len(self._samples) // 4)):
                    self._samples.popleft()
            sample['sampling_ms'] = max(0, int((self.clock() - started) * 1000))

    def _run(self):
        while not self._stop.is_set():
            try:
                self.sample()
            except Exception:
                # Diagnostics must never terminate or slow the updater retries.
                with self._lock:
                    self._failed += 1
                    self._state = 'sampling_unavailable'
            self._persist()
            self._stop.wait(INTERVAL_SECONDS)

    def start(self):
        if self._thread is None:
            self._thread = threading.Thread(target=self._run, daemon=True, name='repair-counters')
            self._thread.start()
        return self

    def _snapshot_locked(self):
        return {'format': 1, 'session_id': self.session_id,
                'policy': 'bounded_observed_process_counters',
                'state': self._state, 'sample_interval_ms': int(INTERVAL_SECONDS * 1000),
                'discovery_interval_ms': int(DISCOVERY_SECONDS * 1000),
                'viewer_thread_interval_ms': int(THREAD_SECONDS * 1000),
                'viewer_thread_limit': MAX_VIEWER_THREADS,
                'viewer_thread_policy': 'fixed_worker_names_not_stack_attribution',
                'clock_ticks_per_second': self.tick_hz,
                'sample_limit': MAX_SAMPLES, 'process_limit': MAX_PROCESSES,
                'serialized_limit_bytes': MAX_REPORT_BYTES,
                'samples_recorded': self._recorded, 'failed_samples': self._failed,
                'write_failures': self._write_failures, 'last_write_ms': self._last_write_ms,
                'samples': list(self._samples)}

    def snapshot(self):
        with self._lock:
            return copy.deepcopy(self._snapshot_locked())

    def _persist(self):
        if self.output_path is None or not self._write_lock.acquire(blocking=False):
            return
        started = self.clock()
        temporary = self.output_path.with_name(self.output_path.name + '.new')
        try:
            payload = json.dumps(self.snapshot(), separators=(',', ':'))
            if len(payload.encode()) > MAX_REPORT_BYTES:
                raise ValueError('report too large')
            temporary.write_text(payload)
            os.replace(temporary, self.output_path)
        except (OSError, ValueError):
            with self._lock:
                self._write_failures += 1
        finally:
            with self._lock:
                self._last_write_ms = max(0, int((self.clock() - started) * 1000))
            self._write_lock.release()

    def stop(self):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=0.5)
        if self._thread is None or not self._thread.is_alive():
            if self._scan_iterator is not None:
                self._scan_iterator.close()
                self._scan_iterator = None
        with self._lock:
            if self._thread is None or not self._thread.is_alive():
                if self._state == 'running':
                    self._state = 'stopped'
        self._persist()
        return self.snapshot()
