import importlib.util
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
from types import SimpleNamespace

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime'))
import client_launch
import supervisor


class LaunchContracts(unittest.TestCase):
    def network_receipt(self):
        report=json.loads((Path(__file__).parent/'fixtures/thor-051-dependencies.json').read_text())
        return report,[row['name'] for row in report['dependencies']]

    def dependency_attempts(self,attempts,report,cancel=False):
        bad,names=self.network_receipt()
        with tempfile.TemporaryDirectory() as tmp:
            result=Path(tmp)/'loader-check.json'
            processes=[Mock(poll=Mock(return_value=code)) for code,_ in attempts]
            s=SimpleNamespace(wine_command=lambda *a:supervisor.Supervisor.wine_command(SimpleNamespace(engine='box64'),*a),env={},spawn=Mock(side_effect=processes),status=Mock(),stopped=Mock())
            def wait(p,*args,**kw):
                code,receipt=attempts[processes.index(p)]
                if receipt is not None:result.write_text(json.dumps(receipt))
                if code not in kw['accepted']:raise RuntimeError('checker crashed')
            s.wait=wait
            if cancel:s.status.side_effect=lambda *a,**k:setattr(s.stopped,'side_effect',supervisor.Stopped())
            with patch.object(client_launch,'Path',return_value=result),patch.object(client_launch,'record'):
                client_launch.check_dependencies(s,report,'FINAL FANTASY XI/xiloader.exe',names)
            self.assertEqual(s.spawn.call_count,len(attempts))
            for call in s.spawn.call_args_list:
                self.assertEqual(call.args[0][3],'check')
                self.assertEqual(call.args[0][5:],names)
                self.assertNotIn('pipe_input',call.kwargs)

    def test_observed_ws2_initialization_failure_rechecks_all_imports_and_retains_both(self):
        bad,names=self.network_receipt();good=copy.deepcopy(bad);good['ok']=True
        good['dependencies'][1].update(ok=True,win32_error=0,loaded_path=r'C:\windows\system32\WS2_32.dll')
        report={};self.dependency_attempts([(1,bad),(0,good)],report)
        self.assertEqual([a['exit'] for a in report['check_attempts']],[1,0])
        self.assertEqual(report['check_attempts'][0]['dependencies'],bad)
        client_launch.validate_check(report['dependencies'],names,report['check_exit'])

    def test_persistent_ws2_failure_stops_after_one_retry_with_specific_error(self):
        bad,_=self.network_receipt();report={}
        with self.assertRaisesRegex(RuntimeError,r'WS2_32.dll \(Windows error 1114\)'):
            self.dependency_attempts([(1,bad),(1,bad)],report)
        self.assertEqual(len(report['check_attempts']),2)

    def test_missing_crashed_partial_and_other_dependency_failures_are_never_retried(self):
        bad,names=self.network_receipt()
        cases=[]
        for error in [126,127,5]:
            receipt=copy.deepcopy(bad);receipt['dependencies'][1]['win32_error']=error;cases.append((1,receipt))
        receipt=copy.deepcopy(bad);receipt['dependencies'][0].update(ok=False,win32_error=1114);cases.append((1,receipt))
        receipt=copy.deepcopy(bad);receipt['dependencies'].pop();cases.append((1,receipt))
        receipt=copy.deepcopy(bad);receipt['dependencies'][0]['win32_error']=True;cases.append((1,receipt))
        receipt=copy.deepcopy(bad);receipt['dependencies'][0]['loaded_path']='';cases.append((1,receipt))
        cases.extend([(-11,bad),(86,None)])
        for code,receipt in cases:
            with self.subTest(code=code,receipt=receipt):
                self.assertFalse(client_launch.retry_network_initialization(receipt or {},names,code))
                report={}
                with self.assertRaises(RuntimeError):self.dependency_attempts([(code,receipt)],report)
                self.assertEqual(len(report['check_attempts']),1)

    def test_stop_cancels_network_retry_and_preserves_first_receipt(self):
        bad,_=self.network_receipt();report={}
        with self.assertRaises(supervisor.Stopped):self.dependency_attempts([(1,bad)],report,cancel=True)
        self.assertEqual(len(report['check_attempts']),1)

    def test_immediate_stop_retains_startup_diagnostics_before_first_poll(self):
        writer=SimpleNamespace(events=supervisor.PrivateEvents())
        writer.events.feed(b'0034:warn:module:load_dll Failed to load module L"secret.dll"; status=c0000135\n')
        process=SimpleNamespace(stdin=io.BytesIO(),poll=lambda:None)
        s=SimpleNamespace(wine_command=lambda *a:supervisor.Supervisor.wine_command(SimpleNamespace(engine='box64'),*a),req={},env={'WINEDLLOVERRIDES':''},logs=[writer],
                          stopped=Mock(side_effect=[None,supervisor.Stopped()]),
                          spawn=Mock(return_value=process),status=Mock())
        report={};manifest={'loader':'FINAL FANTASY XI/xiloader.exe','region':'US','game':'FINAL FANTASY XI'}
        snapshots=[]
        with patch.object(client_launch,'check',return_value=(manifest,report,['--server','--username','--password','--lang'])),\
             patch.object(client_launch,'Path'),\
             patch.object(client_launch,'record',side_effect=lambda s,r:snapshots.append(copy.deepcopy(r))),\
             patch.object(client_launch.sys,'stdin',SimpleNamespace(buffer=io.BytesIO(b'LSBLOGIN1\n127.0.0.1\naccount\npassword\n'))):
            with self.assertRaises(supervisor.Stopped):client_launch.run(s,Mock())
        self.assertEqual(snapshots[-1]['startup_diagnostics']['records'][0]['code'],0xc0000135)
        self.assertNotIn('secret',str(snapshots))
        self.assertNotIn('password',str(snapshots))
        self.assertEqual(s.spawn.call_args.args[0][-2:], [r'D:\FINAL FANTASY XI','1'])

    def test_version_registry_failure_has_specific_message(self):
        reason,message=client_launch.exit_problem({'phase':'version_configuration_failed','win32_error':5},[])
        self.assertEqual(reason,'version_configuration_failed')
        self.assertIn('Windows error 5',message)
        self.assertIn('client files were kept',message)

    def test_specific_login_failures_survive_chunking_color_and_utf16(self):
        cases=[('Failed to login. Invalid username or password.','login_invalid_credentials'),
               ('Failed to login. Account already logged in.','login_already_active'),
               ('Failed to login. Expected xiloader version mismatch; check with your provider.','login_version_mismatch'),
               ('Failed to login.','login_rejected'),('Failed to connect to server!','connection_failed')]
        cases += [('Failed to initialize instance of polcore!','polcore_initialization_failed'),
                  ('Failed to initialize instance of FFxi!','ffxi_initialization_failed'),
                  ('Failed to locate profileServerPortAddress2!','polcore_patch_failed')]
        for message,event in cases:
            for encoding in ['ascii','utf-16le','utf-16be']:
                raw=('[09/21/26 01:00:00] \x1b[31m'+message+'\x1b[0m\r\n').encode(encoding)
                events=supervisor.PrivateEvents()
                output=b''.join(events.feed(raw[i:i+3]) for i in range(0,len(raw),3))+events.finish()
                self.assertEqual(output,(event+'\n').encode())
                self.assertEqual(events.snapshot(),[event])
                phase,_,reason=client_launch.progress(events.snapshot(),1)
                self.assertEqual((phase,reason),('launch_failed',event))
                self.assertNotIn('login_message_seen',events.snapshot())

    def test_failure_matching_waits_for_complete_line_and_ignores_echoes(self):
        events=supervisor.PrivateEvents()
        self.assertEqual(events.feed(b'Failed to login.'),b'')
        self.assertEqual(events.snapshot(),[])
        self.assertEqual(events.feed(b' Expected xiloader version mismatch.\n'),b'login_version_mismatch\n')
        events=supervisor.PrivateEvents()
        raw=b'username=Failed to login. Invalid username or password.\npassword=Successfully logged in.\n'+b'X'*90000+b'Failed to login.\n'
        self.assertEqual(events.feed(raw)+events.finish(),b'')
        self.assertLessEqual(len(events.tail),4096)

    def test_progress_does_not_equate_process_alive_or_already_logged_in_with_login(self):
        self.assertEqual(client_launch.progress([],0)[0],'waiting_for_login')
        self.assertIn('Still waiting',client_launch.progress([],61)[1])
        self.assertEqual(client_launch.progress(['login_message_seen'],1)[0],'login_message_received')
        self.assertEqual(client_launch.progress(['login_message_seen','login_already_active'],1)[2],'login_already_active')

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

    def test_post_login_zero_exit_requires_an_observed_game_window(self):
        process={'phase':'exited','child_exit':0,'observation':{'ffxi_window_seen':False}}
        self.assertEqual(client_launch.exit_problem(process,['login_message_seen'])[0],'closed_before_game_window')
        # A missing observer is unknown, never inferred to be a successful game.
        self.assertIsNotNone(client_launch.exit_problem({'phase':'exited','child_exit':0},['login_message_seen']))
        process['observation']['ffxi_window_seen']=True
        self.assertIsNone(client_launch.exit_problem(process,['login_message_seen']))
        self.assertEqual(client_launch.progress(['login_message_seen'],1,process)[0],'game_window_observed')
        self.assertEqual(client_launch.progress(['login_message_seen','ffxi_initialization_failed'],1,process)[2],'ffxi_initialization_failed')
        self.assertIsNone(client_launch.exit_problem({'phase':'exited','child_exit':0xc0000135},['login_message_seen']))
        self.assertEqual(client_launch.exit_problem({'phase':'configuration_failed','win32_error':13},[])[0],'display_configuration_failed')

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

    def test_display_profile_is_bounded_and_launch_only(self):
        req={'format':1,'session_id':'0'*36,'renderer':'turnip26','audio':True,'action':'launch'}
        for profile in ('windowed720','preserve','restore'):
            self.assertEqual(supervisor.validate_request(dict(req,display_profile=profile))['display_profile'],profile)
        for profile in ('fullscreen','windowed720 --password secret',None,[],42):
            with self.assertRaises(ValueError):supervisor.validate_request(dict(req,display_profile=profile))
        with self.assertRaises(ValueError):supervisor.validate_request(dict(req,action='probe',display_profile='windowed720'))
        self.assertIn('No saved original',client_launch.exit_problem({'phase':'configuration_failed','win32_error':1168},[])[1])


if __name__=='__main__':unittest.main()
