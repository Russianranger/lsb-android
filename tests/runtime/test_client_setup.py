import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('client_setup',Path(__file__).resolve().parents[2]/'runtime/client_setup.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)


class ClientContract(unittest.TestCase):
    def test_path_boundary(self):
        with tempfile.TemporaryDirectory() as t:
            old=module.CLIENT;module.CLIENT=Path(t)
            try:
                for path in ['../original','/original','a/../b','a//b','a/./b','a\\b','C:game','x./y','x /y','x\n/y','a"b','a*b']:
                    with self.assertRaises(ValueError,msg=path):module.client_path(path)
                (Path(t)/'link').symlink_to('/tmp')
                with self.assertRaises(ValueError):module.client_path('link/file')
                self.assertEqual(module.windows_path('PlayOnlineViewer/viewer/com/polcore.dll'),r'D:\PlayOnlineViewer\viewer\com\polcore.dll')
            finally:module.CLIENT=old
    def test_pe_architecture_and_bounds(self):
        with tempfile.TemporaryDirectory() as t:
            p=Path(t)/'fixture.dll';data=bytearray(248);data[:2]=b'MZ';struct.pack_into('<I',data,60,64)
            data[64:70]=b'PE\0\0\x4c\x01';struct.pack_into('<H',data,84,160);data[88:90]=b'\x0b\x01'
            p.write_bytes(data);self.assertEqual(module.imports(p),[])
            data[68:70]=b'\x64\x86';p.write_bytes(data)
            with self.assertRaisesRegex(ValueError,'x86'):module.imports(p)
            p.write_bytes(b'MZ')
            with self.assertRaises(ValueError):module.imports(p)
    def test_request_has_no_arbitrary_command(self):
        spec=importlib.util.spec_from_file_location('supervisor',Path(__file__).resolve().parents[2]/'runtime/supervisor.py')
        sup=importlib.util.module_from_spec(spec);spec.loader.exec_module(sup)
        req={'format':1,'session_id':'0'*36,'renderer':'turnip26','audio':True,'action':'initialize'}
        self.assertEqual(sup.validate_request(req),req)
        for extra in [{'action':'launch'},{'command':'anything'},{'client':'/original'},{'password':'x'}]:
            with self.assertRaises(ValueError):sup.validate_request(dict(req,**extra))


if __name__=='__main__':unittest.main()
