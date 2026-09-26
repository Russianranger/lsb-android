"""Bounded metadata from private launch output; never retain raw Wine/DXVK text.

Warnings and first-chance exceptions are evidence, not automatic launch failures.
Patterns follow Wine 10 and DXVK 2.5.3. Unknown messages contribute counts only.
"""
import re
import threading
from graphics_diagnostics import GraphicsDiagnostics


class StartupDiagnostics:
    # Exact upstream DXVK 2.5.3/2.7.1 messages only. Retain a fixed reason,
    # never free-form shader text, interface names, resource paths or tokens.
    DXVK_REASONS = {
        b'd3d8device::setrenderstate: unimplemented render state d3drs_linepattern':'line_pattern_unsupported',
        b'd3d8device::setrenderstate: unimplemented render state d3drs_patchsegments':'patch_segments_unsupported',
        b'd3d8device::setindices: basevertexindex exceeds int_max':'base_vertex_out_of_range',
        b'd3d9deviceex::setupfpu: not supported on this arch.':'fpu_setup_unsupported',
        b'd3d8device: error! failed to get d3d9 bridge.':'d3d9_bridge_missing',
        b'd3dtop_premodulate: not implemented':'premodulate_unsupported',
        b'unhandled texture op!':'texture_operation_unsupported',
    }
    WINE = re.compile(rb'^(?:[0-9]+\.[0-9]+:)?((?:[0-9a-f]{4,8}:){1,2})(trace|warn|err|fixme):([a-z0-9_]+):([a-z0-9_]+) (.*)$')
    MODULES = {n.lower().encode(): n for n in
               ('polcore.dll', 'polcoreeu.dll', 'FFXi.dll', 'FFXiMain.dll', 'd3d8.dll', 'd3d9.dll',
                'pol.exe', 'PolHook.dll', 'ddraw.dll')}
    CHANNELS = {'ole', 'seh', 'module', 'loaddll', 'vulkan', 'wined3d', 'd3d', 'x11drv', 'wgl', 'rpc', 'service'}
    # Only fixed source labels leave this parser, never unknown function names.
    FUNCTIONS = {'import_dll': 'dll_import', 'find_forwarded_export': 'forwarded_export',
                 'process_attach': 'dll_initialization', 'ldrgetprocedureaddress': 'dll_export',
                 'ldrloaddll': 'dll_load', 'load_dll': 'dll_load', 'com_get_class_object': 'com_class',
                 'cocreateinstanceex': 'com_create', 'apartment_get_inproc_class_object': 'com_inproc',
                 'start_rpcss': 'rpc_service', 'virtual_setup_exception': 'exception',
                 'dispatch_exception': 'exception', 'show_exception': 'exception'}
    # Wine 10.0 dlls/combase/combase.c: com_get_class_object and
    # CoCreateInstanceEx. Exact source messages only, not arbitrary GUIDs in
    # application output. COM identities are numeric component metadata; no
    # registry values, account text, DLL paths or unknown labels are retained.
    GUID = rb'\{[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\}'
    COM_NOT_REGISTERED = re.compile(rb'class (' + GUID + rb') not registered')
    COM_CONTEXT_FAILED = re.compile(rb'no class object (' + GUID + rb') could be created for context (0x[0-9a-f]{1,8}|0)')
    COM_CREATE_FAILED = re.compile(rb'no instance created for interface (' + GUID + rb') of class (' + GUID + rb'), hr (0x[0-9a-f]{1,8}|0)\.')

    def __init__(self):
        self.lock = threading.Lock()
        self.graphics = GraphicsDiagnostics()
        self.records = []
        self.counts = {}
        self.seen = set()
        self.dropped = 0

    def add(self, source, event, **fields):
        key = (source, event, *sorted(fields.items()))
        with self.lock:
            count_key = source + '_' + event
            self.counts[count_key] = min(1000000, self.counts.get(count_key, 0) + 1)
            # Startup boundaries are ordered calls, not repeatable warnings.
            # Deduplicating them can hide a successful retry or its entry.
            if source != 'startup' and key in self.seen:
                return
            if len(self.records) >= 64:
                self.dropped = min(1000000, self.dropped + 1)
                # Direct startup boundaries must survive noisy Wine warnings.
                # Keep 64 rows; prefer displacing an indirect record.
                victim=next((i for i,r in enumerate(self.records) if r['source']!='startup'),None)
                if source!='startup':return
                if victim is None:victim=0  # retain the newest call boundaries
                removed=self.records.pop(victim)
                removed_key=(removed['source'],removed['event'],*sorted((k,v) for k,v in removed.items() if k not in ('source','event')))
                self.seen.discard(removed_key)
            self.seen.add(key)
            self.records.append(dict(source=source, event=event, **fields))

    def snapshot(self):
        with self.lock:
            report = {'format': 1, 'policy': 'fixed_metadata_only',
                    'records': [dict(r) for r in self.records], 'counts': dict(self.counts),
                    'dropped_records': self.dropped}
            graphics=self.graphics.snapshot()
            if graphics is not None:report['graphics']=graphics
            return report

    def line(self, data):
        if data.startswith(b'lsb-d3d8-v1 '):
            self.graphics.line(data);return
        startup = re.fullmatch(rb'lsb-startup-v1 ([a-z0-9_]+) ([0-9a-f]{8}) ([0-9a-f]{8}) ([0-9a-f]{8}) ([0-9a-f]{8})', data)
        if startup:
            name,pid,tid,code,detail=startup.groups()
            allowed={'observer_loaded','observer_failed','loader_import_hooks','ffxi_import_hooks',
                     'ffxi_com_enter','ffxi_com_return','game_start_hook_ready','game_start_enter','game_start_return',
                     'game_main_com_enter','game_main_com_return','game_main_hook_ready','game_main_enter','game_main_return',
                     'main_import_hooks','main_directory','main_file_open','main_file_read','main_file_size',
                     'main_directplay_load','main_windows_version','main_window_class','main_window_create','main_d3d8_create',
                     'main_math_profile','main_math_dispatch','main_math_final','main_math_cpu','main_math_unavailable',
                     'main_math_skipped','main_math_begin','main_math_result','main_math_returns','main_math_case',
                     'main_math_expected','main_math_actual','main_math_controls','main_math_pending'}
            if name.decode() in allowed:
                self.add('startup',name.decode(),process_id=int(pid,16),thread_id=int(tid,16),code=int(code,16),detail=int(detail,16))
            return
        wine = self.WINE.fullmatch(data)
        if wine:
            ids, level, channel, function, message = wine.groups()
            ids=ids.rstrip(b':').split(b':')
            owner={'thread_id':int(ids[-1],16)}
            if len(ids)==2:owner['process_id']=int(ids[0],16)
            if channel == b'loaddll' and function == b'build_module':
                match = re.fullmatch(rb'loaded l"([^"\r\n]+)" at [0-9a-f]+: (native|builtin)', message)
                if match:
                    name = match[1].replace(b'\\\\', b'\\').rsplit(b'\\', 1)[-1]
                    if name in self.MODULES:
                        self.add('wine', 'module_loaded', module=self.MODULES[name], origin=match[2].decode(), **owner)
            if channel == b'seh':
                # Capture the exception number, never addresses, registers, arguments or strings.
                match = re.match(rb'code=([0-9a-f]{1,8})(?: |$)', message)
                if match:
                    self.add('wine', 'exception_raised', code=int(match[1], 16), **owner)
            if level in (b'err', b'warn') or (channel == b'ole' and level == b'fixme'):
                category = self.FUNCTIONS.get(function.decode(), 'other')
                known_channel = channel.decode() if channel.decode() in self.CHANNELS else 'other'
                fields = dict(channel=known_channel, category=category, severity=level.decode())
                # Recognized trailing HRESULT/NTSTATUS syntax only; no arbitrary numbers/text.
                code = re.search(rb'(?:hr |status[= ]|error )(?:0x)?([0-9a-f]{8})[.)]?$', message)
                if code:
                    fields['code'] = int(code[1], 16)
                if channel == b'ole' and function == b'com_get_class_object' and level == b'err':
                    missing = self.COM_NOT_REGISTERED.fullmatch(message)
                    context = self.COM_CONTEXT_FAILED.fullmatch(message)
                    if missing:
                        fields.update(clsid=missing[1].decode('ascii'), reason='class_not_registered')
                    elif context:
                        fields.update(clsid=context[1].decode('ascii'), context=int(context[2], 16),
                                      reason='class_context_unavailable')
                elif channel == b'ole' and function == b'cocreateinstanceex' and level == b'fixme':
                    creation = self.COM_CREATE_FAILED.fullmatch(message)
                    if creation:
                        fields.update(iid=creation[1].decode('ascii'), clsid=creation[2].decode('ascii'),
                                      code=int(creation[3], 16), reason='interface_creation_failed')
                self.add('wine', 'diagnostic', **fields, **owner)
            return
        dxvk = re.fullmatch(rb'(info|warn|err):\s+(.*)', data)
        if dxvk:
            level, message = dxvk.groups()
            if message.startswith(b'dxvk:'):
                self.add('dxvk', 'initialization_message')
            elif level in (b'warn', b'err'):
                category = 'other'
                for prefix, label in ((b'd3d8', 'd3d8'), (b'd3d9', 'd3d9'),
                                      (b'createvk', 'vulkan_creation'), (b'failed to create vulkan', 'vulkan_creation'),
                                      (b'dxvkinstance', 'vulkan_instance'), (b'dxvkadapter', 'vulkan_adapter'),
                                      (b'dxvkdevice', 'vulkan_device'), (b'vk', 'vulkan')):
                    if message.startswith(prefix):
                        category = label
                        break
                reason=self.DXVK_REASONS.get(message)
                for method,label in ((b'capturestateblock','state_block_capture_invalid'),
                                     (b'applystateblock','state_block_apply_invalid'),
                                     (b'deletestateblock','state_block_delete_invalid')):
                    if re.fullmatch(rb'd3d8device::'+method+rb': invalid token: [0-9a-f]{1,8}',message):reason=label
                detail={'reason':reason} if reason else {}
                self.add('dxvk', 'diagnostic', severity=level.decode(), category=category,**detail)
