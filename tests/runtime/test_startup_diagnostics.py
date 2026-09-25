import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime'))
from supervisor import PrivateEvents
import client_launch


class StartupContracts(unittest.TestCase):
    def parsed(self, raw):
        events=PrivateEvents()
        for i in range(0,len(raw),3):events.feed(raw[i:i+3])
        events.finish()
        return events,events.diagnostics.snapshot()

    def test_real_wine_formats_retain_only_fixed_metadata(self):
        raw=(b'13429.313:00fc:0100:trace:loaddll:build_module Loaded L"D:\\private_account\\FFXiMain.dll" at 7BE70000: native\n'
             b'00fc:warn:module:load_dll Failed to load module L"private_password.dll"; status=c0000135\n'
             b'1.234:00fc:0100:fixme:ole:CoCreateInstanceEx no instance created for interface {secret} of class {secret}, hr 0x80004002.\n'
             b'1.236:00fc:0100:trace:seh:dispatch_exception code=c0000005 (EXCEPTION_ACCESS_VIOLATION) flags=0 addr=deadbeef\n'
             b'1.236:00fc:0100:trace:seh:dispatch_exception  info[0]=private_password\n')
        events,report=self.parsed(raw)
        self.assertEqual(events.snapshot(),[])
        self.assertIn({'source':'wine','event':'module_loaded','module':'FFXiMain.dll','origin':'native','process_id':252,'thread_id':256},report['records'])
        self.assertEqual({r['code'] for r in report['records'] if 'code' in r},{0xc0000135,0x80004002,0xc0000005})
        self.assertTrue(any(r.get('category')=='dll_load' and r.get('code')==0xc0000135 for r in report['records']))
        self.assertNotIn('private',json.dumps(report));self.assertNotIn('secret',json.dumps(report))
        self.assertEqual(client_launch.progress(events.snapshot(),1)[2],'')

    def test_dxvk_never_retains_free_text_or_unknown_labels(self):
        _,report=self.parsed(b'info:  DXVK: v2.5.3\nerr:   D3D8Device: ERROR! Failed to get D3D9 Bridge. secret\n'
                             b'err:   private_password\n0034:err:private_account:private_password secret\n')
        self.assertIn({'source':'dxvk','event':'initialization_message'},report['records'])
        self.assertIn({'source':'dxvk','event':'diagnostic','category':'d3d8','severity':'err'},report['records'])
        self.assertNotIn('private',json.dumps(report));self.assertNotIn('secret',json.dumps(report))

    def test_exact_wine_com_failures_keep_numeric_component_identity(self):
        clsid='{3501f5dd-7894-42df-866a-a2b6527d8049}'
        iid='{e0516654-ef77-435d-aa7d-50d2c069ce34}'
        raw=(f'1.234:0168:016c:err:ole:com_get_class_object class {clsid.upper()} not registered\n'
             f'1.235:0168:016c:err:ole:com_get_class_object no class object {clsid} could be created for context 0x17\n'
             f'1.236:0168:016c:fixme:ole:CoCreateInstanceEx no instance created for interface {iid} of class {clsid}, hr 0x80040154.\n').encode()
        events,report=self.parsed(raw)
        self.assertEqual(events.snapshot(),[])
        records=report['records']
        self.assertEqual(len(records),3)
        self.assertEqual({r['clsid'] for r in records},{clsid})
        self.assertEqual({r['process_id'] for r in records},{360})
        self.assertEqual({r['thread_id'] for r in records},{364})
        self.assertEqual(records[0]['reason'],'class_not_registered')
        self.assertEqual((records[1]['reason'],records[1]['context']),('class_context_unavailable',0x17))
        self.assertEqual((records[2]['iid'],records[2]['code'],records[2]['reason']),
                         (iid,0x80040154,'interface_creation_failed'))

    def test_com_identity_requires_exact_source_function_and_message(self):
        clsid='{3501f5dd-7894-42df-866a-a2b6527d8049}'
        for prefix,message in (
                ('err:ole:com_get_class_object',f'class {clsid} not registered private_password'),
                ('err:ole:com_get_class_object',f'private_account class {clsid} not registered'),
                ('err:ole:com_get_class_object','class {private_password} not registered'),
                ('err:ole:com_get_class_object',f'class {clsid[:-2]}z}} not registered'),
                ('err:ole:com_get_class_object',f'no class object {clsid} could be created for context 0x100000000'),
                ('err:ole:private_account',f'class {clsid} not registered'),
                ('err:private_account:com_get_class_object',f'class {clsid} not registered'),
                ('warn:ole:com_get_class_object',f'class {clsid} not registered'),
                ('fixme:ole:CoCreateInstanceEx',f'no instance created for interface {clsid} of class {clsid}, hr 0x80040154. private_password'),
                ('fixme:ole:CoCreateInstanceEx',f'no instance created for interface {{private_account}} of class {clsid}, hr 0x80040154.')):
            with self.subTest(prefix=prefix,message=message):
                _,report=self.parsed(f'0168:016c:{prefix} {message}\n'.encode())
                serialized=json.dumps(report)
                self.assertNotIn(clsid,serialized)
                self.assertNotIn('private',serialized)
                self.assertFalse(any('clsid' in r or 'iid' in r or 'context' in r for r in report['records']))

    def test_different_missing_com_classes_are_not_deduplicated(self):
        classes=['{3501f5dd-7894-42df-866a-a2b6527d8049}', '{07974581-0df6-4ef0-bd05-604b3ada9be9}']
        raw=''.join(f'0168:016c:err:ole:com_get_class_object class {clsid} not registered\n' for clsid in classes)
        _,report=self.parsed((raw+raw).encode())
        self.assertEqual(len(report['records']),2)
        self.assertEqual({r['clsid'] for r in report['records']},set(classes))
        self.assertEqual(report['counts']['wine_diagnostic'],4)

    def test_bounds_deduplication_and_overlong_line_recovery(self):
        raw=b''.join(('0001:trace:seh:dispatch_exception code=%08x flags=0\n'%i).encode() for i in range(200))
        events,report=self.parsed(raw+raw+b'X'*9000+b'\ninfo:  DXVK: v2.5.3\n')
        self.assertEqual(len(report['records']),64)
        self.assertGreater(report['dropped_records'],0)
        self.assertEqual(report['counts']['wine_exception_raised'],400)
        self.assertEqual(report['counts']['dxvk_initialization_message'],1)
        self.assertLessEqual(len(events.tail),4096)

    def test_graphics_reasons_are_exact_and_never_retain_private_suffixes(self):
        raw=(b'warn: D3D8Device::SetRenderState: Unimplemented render state D3DRS_LINEPATTERN\n'
             b'warn: D3D8Device::ApplyStateBlock: Invalid token: deadbeef\n'
             b'warn: D3D8Device::SetIndices: BaseVertexIndex exceeds INT_MAX\n'
             b'warn: D3D9DeviceEx::SetupFPU: not supported on this arch.\n'
             b'warn: D3D8Device::ApplyStateBlock: Invalid token: private_password\n'
             b'warn: D3D8Device::SetIndices: BaseVertexIndex exceeds INT_MAX private_password\n')
        _,report=self.parsed(raw)
        reasons={r['reason'] for r in report['records'] if 'reason' in r}
        self.assertEqual(reasons,{'line_pattern_unsupported','state_block_apply_invalid',
                                 'base_vertex_out_of_range','fpu_setup_unsupported'})
        self.assertNotIn('private',json.dumps(report));self.assertNotIn('deadbeef',json.dumps(report))
        self.assertIn({'source':'dxvk','event':'diagnostic','category':'d3d8','severity':'warn'},report['records'])

    def test_chunked_utf16_color_eof_and_echoes(self):
        for encoding in ('ascii','utf-16le','utf-16be'):
            raw=('username=00fc:err:module:LdrLoadDll secret\npassword=err:   D3D9: secret\n'
                 '\x1b[31m00fc:warn:module:LdrLoadDll Failed to load module L"secret.dll"; status=c0000135\x1b[0m').encode(encoding)
            _,report=self.parsed(raw)
            self.assertEqual(len(report['records']),1)
            self.assertEqual(report['records'][0]['code'],0xc0000135)
            self.assertNotIn('secret',json.dumps(report))


if __name__=='__main__':unittest.main()
