import json,sys,unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime'))
from supervisor import PrivateEvents

class GraphicsContracts(unittest.TestCase):
    def parse(self,raw):
        events=PrivateEvents()
        for i in range(0,len(raw),5):events.feed(raw[i:i+5])
        events.finish();return events.diagnostics.snapshot()
    def test_fixed_schema_chunking_and_private_suffix_rejection(self):
        report=self.parse(b'lsb-d3d8-v1 device 00000020 00000500 000002d0\n'
                          b'lsb-d3d8-v1 state 00000000 secret\n'
                          b'lsb-d3d8-v1 device 00000020 00000500 000002d0 secret\n'
                          b'lsb-d3d8-v1 private 00000001\n')
        self.assertEqual(report['graphics']['records'],[dict(event='device',behavior_flags=32,width=1280,height=720)])
        self.assertNotIn('secret',json.dumps(report));self.assertNotIn('private',json.dumps(report))
    def test_game_math_receipts_survive_chunking_without_free_text(self):
        report=self.parse(b'lsb-startup-v1 main_math_dispatch 00000001 00000002 00000002 00000222\n'
                          b'lsb-startup-v1 main_math_result 00000001 00000002 00000000 000000c0\n'
                          b'lsb-startup-v1 main_math_actual 00000001 00000002 00000000 00000000 secret\n')
        self.assertEqual([(r['event'],r['code'],r['detail']) for r in report['records']],
                         [('main_math_dispatch',2,0x222),('main_math_result',0,192)])
        self.assertNotIn('secret',json.dumps(report))
    def test_trace_absent_when_off_and_bounded_separately_from_startup(self):
        self.assertNotIn('graphics',self.parse(b'info: DXVK: v2.7.1\n'))
        raw=b'lsb-startup-v1 game_main_enter 00000001 00000002 00000000 00000000\n'
        raw+=b''.join(('lsb-d3d8-v1 frame '+' '.join(['%08x'%i]+['00000000']*12)+'\n').encode() for i in range(1000))
        raw+=b'lsb-d3d8-v1 complete 00000001 00000400 00000800 00000001\n'
        report=self.parse(raw)
        self.assertEqual(len(report['graphics']['records']),64)
        self.assertGreater(report['graphics']['dropped_records'],0)
        self.assertEqual(report['graphics']['records'][-1]['event'],'complete')
        self.assertEqual(report['records'][0]['event'],'game_main_enter')
        self.assertEqual(report['dropped_records'],0)
