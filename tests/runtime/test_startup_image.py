import hashlib
from pathlib import Path
import struct
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime'))
from startup_image import instrument, prepare
from supervisor import validate_request, PrivateEvents
from client_launch import exit_problem


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


if __name__=='__main__':unittest.main()
