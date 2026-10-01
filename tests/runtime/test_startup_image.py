import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime'))
from startup_image import instrument, prepare
from supervisor import validate_request, PrivateEvents
from client_launch import exit_problem, data_inventory


def fixture():
    data=bytearray(1024);data[:2]=b'MZ'
    struct.pack_into('<I',data,60,64);data[64:68]=b'PE\0\0'
    struct.pack_into('<HH',data,68,0x14c,1);struct.pack_into('<HH',data,84,224,0x102)
    opt=88;struct.pack_into('<H',data,opt,0x10b)
    for k,v in {8:512,16:4096,32:4096,36:512,56:8192,60:512,92:16,104:4096,108:40}.items():
        struct.pack_into('<I',data,opt+k,v)
    struct.pack_into('<8sIIIIIIHHI',data,312,b'.rdata\0\0',512,4096,512,512,0,0,0,0,0x40000040)
    struct.pack_into('<IIIII',data,512,4160,0,0,4140,4168)
    data[556:566]=b'ole32.dll\0'
    struct.pack_into('<IIII',data,576,4176,0,4176,0)
    data[592:611]=b'\0\0CoCreateInstance\0'
    return bytes(data)


class ImageContracts(unittest.TestCase):
    def test_added_import_preserves_original_code_and_rvas(self):
        raw=fixture();out=instrument(raw)
        self.assertEqual(out[512:1024],raw[512:1024])
        self.assertEqual(struct.unpack_from('<I',out,104)[0],4096)  # entry point
        self.assertEqual(struct.unpack_from('<H',out,70)[0],2)
        self.assertEqual(struct.unpack_from('<II',out,192),(8192,60))
        self.assertEqual(out[1024:1044],raw[512:532])
        self.assertIn(b'P:\\startup-trace.dll\0',out)

    def test_invalid_layouts_fail_without_an_output_copy(self):
        for offset,content in [(0,b'NO'),(68,b'\x64\x86'),(88,b'\x0b\x02'),(352,b'x'),(196,bytes(4)),(84,bytes(2))]:
            raw=bytearray(fixture());raw[offset:offset+len(content)]=content
            with self.assertRaises(ValueError):instrument(raw)
        for length in (0,63,90,510,1000):
            with self.assertRaises(ValueError):instrument(fixture()[:length])

    def test_original_is_identical_and_existing_files_are_never_overwritten(self):
        with tempfile.TemporaryDirectory() as t:
            source=Path(t)/'xiloader.exe';source.write_bytes(fixture())
            other=Path(t)/'lsb-startup-existing.exe';other.write_bytes(b'keep')
            digest=hashlib.sha256(source.read_bytes()).hexdigest()
            copy,report=prepare(source,digest)
            self.assertEqual(report['source_sha256'],digest)
            self.assertEqual(report['image_sha256'],hashlib.sha256(copy.read_bytes()).hexdigest())
            self.assertEqual(hashlib.sha256(source.read_bytes()).hexdigest(),digest)
            self.assertEqual(other.read_bytes(),b'keep');self.assertNotEqual(copy,source)
            with self.assertRaises(ValueError):prepare(source,'0'*64)
            self.assertEqual(len(list(Path(t).glob('*.exe'))),3)

    def test_trace_option_is_boolean_and_launch_only(self):
        req={'format':1,'session_id':'0'*36,'renderer':'software','audio':False,'action':'launch'}
        for value in (True,False):validate_request(dict(req,startup_trace=value))
        for value in ('yes',None,1,[],{}):
            with self.assertRaises(ValueError):validate_request(dict(req,startup_trace=value))
        with self.assertRaises(ValueError):validate_request(dict(req,action='probe',startup_trace=True))

    def test_startup_result_privacy_and_process_correlation(self):
        events=PrivateEvents()
        raw=(b'lsb-startup-v1 game_start_return 000000fc 00000100 80004005 0000002a\n'
             b'lsb-startup-v1 private_password 000000fc 00000100 80004005 0000002a\n'
             b'lsb-startup-v1 game_start_return 000000fc 00000100 80004005 0000002a secret\n')
        for i in range(0,len(raw),3):events.feed(raw[i:i+3])
        report=events.diagnostics.snapshot();self.assertEqual(len(report['records']),1)
        self.assertNotIn('private',str(report));self.assertNotIn('secret',str(report))
        process={'phase':'exited','child_exit':0,'observation':{'child_pid':252}}
        self.assertIn('0x80004005 after 42 ms',exit_problem(process,['login_message_seen'],report)[1])
        process['observation']['child_pid']=1
        self.assertEqual(exit_problem(process,['login_message_seen'],report)[0],'closed_before_game_window')
        process['observation']['ffxi_window_seen']=True
        self.assertIsNone(exit_problem(process,['login_message_seen'],report))

    def test_startup_result_survives_noisy_wine_output(self):
        events=PrivateEvents()
        for i in range(100):events.feed(('0001:trace:seh:dispatch_exception code=%08x flags=0\n'%i).encode())
        events.feed(b'lsb-startup-v1 game_start_return 000000fc 00000100 80004005 0000002a\n')
        rows=events.diagnostics.snapshot()['records']
        self.assertEqual(len(rows),64)
        self.assertEqual(rows[-1]['event'],'game_start_return')

    def test_inner_failure_is_not_hidden_by_outer_success(self):
        # 0.4.6 Thor report: GameMain fails, GameStart and process return zero.
        events=PrivateEvents()
        events.feed(b'lsb-startup-v1 game_start_enter 0000011c 00000120 00000000 00000000\n'
                    b'lsb-startup-v1 game_main_return 0000011c 00000120 88770000 000005a1\n'
                    b'lsb-startup-v1 game_start_return 0000011c 00000120 00000000 000007bf\n')
        process={'phase':'exited','child_exit':0,'observation':{'child_pid':284}}
        result=exit_problem(process,['login_message_seen'],events.diagnostics.snapshot())
        self.assertEqual(result[0],'game_main_failed')
        self.assertIn('0x88770000 after 1441 ms',result[1])
        process['observation']['ffxi_window_seen']=True
        self.assertIsNone(exit_problem(process,['login_message_seen'],events.diagnostics.snapshot()))
        process['observation']['ffxi_window_seen']=False
        process['child_exit']=0xc0000094
        self.assertIsNone(exit_problem(process,['login_message_seen'],events.diagnostics.snapshot()))

    def test_other_process_and_successful_inner_returns_do_not_report_failure(self):
        process={'phase':'exited','child_exit':0,'observation':{'child_pid':284}}
        for code in (0,1):
            events=PrivateEvents()
            events.feed(('lsb-startup-v1 game_main_return 0000011c 00000120 %08x 00000001\n'%code).encode())
            events.feed(b'lsb-startup-v1 game_main_return 00000124 00000128 88770000 00000002\n'
                        b'lsb-startup-v1 game_start_return 0000011c 00000120 00000000 00000003\n')
            self.assertEqual(exit_problem(process,['login_message_seen'],events.diagnostics.snapshot())[0],
                             'game_start_returned_without_window')
        process['observation'].pop('child_pid')
        self.assertEqual(exit_problem(process,['login_message_seen'],events.diagnostics.snapshot())[0],
                         'closed_before_game_window')

    def test_earlier_inner_failure_does_not_override_a_later_attempt(self):
        process={'phase':'exited','child_exit':0,'observation':{'child_pid':284}}
        for retry in (b'game_main_return 0000011c 00000120 00000000 00000002',
                      b'game_start_enter 0000011c 00000120 00000000 00000000'):
            events=PrivateEvents()
            events.feed(b'lsb-startup-v1 game_main_return 0000011c 00000120 88770000 00000001\n'
                        b'lsb-startup-v1 '+retry+b'\n'
                        b'lsb-startup-v1 game_start_return 0000011c 00000120 00000000 00000003\n')
            self.assertEqual(exit_problem(process,['login_message_seen'],events.diagnostics.snapshot())[0],
                             'game_start_returned_without_window')

    def test_repeated_startup_boundaries_keep_order_within_the_record_limit(self):
        events=PrivateEvents()
        enter=b'lsb-startup-v1 game_start_enter 0000011c 00000120 00000000 00000000\n'
        for unused in range(80):events.feed(enter)
        events.feed(b'lsb-startup-v1 game_main_return 0000011c 00000120 88770000 00000001\n')
        events.feed(enter)
        events.feed(b'lsb-startup-v1 game_start_return 0000011c 00000120 00000000 00000003\n')
        report=events.diagnostics.snapshot()
        self.assertEqual(len(report['records']),64)
        self.assertEqual(report['counts']['startup_game_start_enter'],81)
        self.assertEqual(report['records'][-2]['event'],'game_start_enter')
        process={'phase':'exited','child_exit':0,'observation':{'child_pid':284}}
        self.assertEqual(exit_problem(process,['login_message_seen'],report)[0],
                         'game_start_returned_without_window')

    def test_binary_version_inventory_distinguishes_missing_and_readable_files(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            (root/'PATCH.VER').write_bytes(b'\xff\x01\x02\x00')
            (root/'VTABLE.DAT').mkdir()
            result=data_inventory(root)
            self.assertEqual(result['patch.ver'],{'state':'readable','bytes':4})
            self.assertEqual(result['FTABLE.DAT'],{'state':'missing'})
            self.assertEqual(result['VTABLE.DAT'],{'state':'not_regular'})
            (root/'patch.ver').write_bytes(b'other')
            self.assertEqual(data_inventory(root)['patch.ver']['state'],'ambiguous_case')
            (root/'FTABLE.DAT').symlink_to(root/'PATCH.VER')
            self.assertEqual(data_inventory(root)['FTABLE.DAT']['state'],'not_regular')

    def test_file_trace_retains_numeric_metadata_without_paths_or_contents(self):
        events=PrivateEvents()
        events.feed(b'lsb-startup-v1 main_file_open 0000011c 00000120 00000002 02000000\n'
                    b'lsb-startup-v1 main_file_read 0000011c 00000120 00000000 01000120\n'
                    b'lsb-startup-v1 main_file_open 0000011c 00000120 00000002 02000000 secret-path\n')
        report=events.diagnostics.snapshot()
        self.assertEqual(len(report['records']),2)
        self.assertEqual(report['records'][0]['code'],2)
        self.assertEqual(report['records'][1]['detail'],1<<24|0x120)
        self.assertNotIn('secret',str(report))


if __name__=='__main__':unittest.main()
