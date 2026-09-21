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

    def test_bounds_deduplication_and_overlong_line_recovery(self):
        raw=b''.join(('0001:trace:seh:dispatch_exception code=%08x flags=0\n'%i).encode() for i in range(200))
        events,report=self.parsed(raw+raw+b'X'*9000+b'\ninfo:  DXVK: v2.5.3\n')
        self.assertEqual(len(report['records']),64)
        self.assertGreater(report['dropped_records'],0)
        self.assertEqual(report['counts']['wine_exception_raised'],400)
        self.assertEqual(report['counts']['dxvk_initialization_message'],1)
        self.assertLessEqual(len(events.tail),4096)

    def test_chunked_utf16_color_eof_and_echoes(self):
        for encoding in ('ascii','utf-16le','utf-16be'):
            raw=('username=00fc:err:module:LdrLoadDll secret\npassword=err:   D3D9: secret\n'
                 '\x1b[31m00fc:warn:module:LdrLoadDll Failed to load module L"secret.dll"; status=c0000135\x1b[0m').encode(encoding)
            _,report=self.parsed(raw)
            self.assertEqual(len(report['records']),1)
            self.assertEqual(report['records'][0]['code'],0xc0000135)
            self.assertNotIn('secret',json.dumps(report))


if __name__=='__main__':unittest.main()
