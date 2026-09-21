import importlib.util
import copy
import io
from pathlib import Path
import sys
import tempfile
import unittest

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime'))
import client_launch
import supervisor


class LaunchContracts(unittest.TestCase):
    def test_checker_receipt_cannot_hide_crash_or_missing_import(self):
        names=['CRYPT32.dll','MSVCP140.dll']
        report={'format':1,'bits':32,'check_policy':'load_only','ok':True,
                'dependencies':[{'name':n,'ok':True,'win32_error':0,'loaded_path':'C:\\windows\\system32\\'+n} for n in names]}
        client_launch.validate_check(report,names,0)
        # The supplied device report had all successful loads but exit -11.
        for code in [-11,1,86,None]:
            with self.assertRaises(RuntimeError):client_launch.validate_check(report,names,code)
        for mutation in ['missing','reordered','failed','wrong-policy','wrong-bits']:
            bad=copy.deepcopy(report)
            if mutation=='missing':bad['dependencies'].pop()
            elif mutation=='reordered':bad['dependencies'].reverse()
            elif mutation=='failed':bad['dependencies'][0].update(ok=False,win32_error=126)
            elif mutation=='wrong-policy':bad.pop('check_policy')
            else:bad['bits']=64
            with self.assertRaises(RuntimeError):client_launch.validate_check(bad,names,0)

    def test_pipe_validates_without_echoing_secrets(self):
        payload=b'LSBLOGIN1\n127.0.0.1\naccount\nPa"ss&\\word \n'
        self.assertEqual(client_launch.credentials(io.BytesIO(payload)),(payload,'127.0.0.1'))
        for data in [payload+b'EXTRA',payload.replace(b'account',b''),payload.replace(b'127.0.0.1',b'localhost;command'),b'x'*1026,payload.replace(b'account',b'bad\x00secret')]:
            with self.assertRaises(ValueError) as error:client_launch.credentials(io.BytesIO(data))
            self.assertNotIn('Pa"ss',str(error.exception));self.assertNotIn('bad\x00secret',str(error.exception))

    def test_cli_requires_real_markers_and_selects_historical_aliases(self):
        with tempfile.TemporaryDirectory() as t:
            path=Path(t)/'loader.exe';path.write_bytes(b'--server\0--user\0--pass\0--lang\0')
            self.assertEqual(client_launch.cli_flags(path),['--server','--user','--pass','--lang'])
            path.write_bytes(path.read_bytes()+b'--username\0--password\0')
            self.assertEqual(client_launch.cli_flags(path)[1:3],['--username','--password'])
            path.write_bytes(b'unsupported')
            with self.assertRaises(ValueError):client_launch.cli_flags(path)

    def test_private_logs_drop_raw_credentials_in_all_chunks(self):
        secret='secret "quoted" \\ ending\\'
        raw=(secret+'\n'+'X'*90000+'\x1b[31m'+secret+'\nSuccessfully logged in.\n').encode()+secret.encode('utf-16le')
        events=supervisor.PrivateEvents()
        output=b''.join(events.feed(raw[i:i+3]) for i in range(0,len(raw),3))
        self.assertEqual(output,b'login_message_seen\n')
        with tempfile.TemporaryDirectory() as t:
            path=Path(t)/'private.log';log=supervisor.BoundedLog(path,lambda:True)
            log.start(io.BufferedReader(io.BytesIO(raw)));log.thread.join(5)
            self.assertFalse(log.thread.is_alive());self.assertEqual(path.read_bytes(),b'login_message_seen\n')

    def test_launch_request_cannot_persist_credentials(self):
        req={'format':1,'session_id':'0'*36,'renderer':'turnip26','audio':True,'action':'launch'}
        self.assertEqual(supervisor.validate_request(req),req)
        for key in ['username','password','command','login_ticket']:
            with self.assertRaises(ValueError):supervisor.validate_request(dict(req,**{key:'secret'}))


if __name__=='__main__':unittest.main()
