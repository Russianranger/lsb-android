import importlib.util,json,pathlib,tempfile,unittest,uuid,io,time,hashlib
from unittest.mock import patch,Mock
spec=importlib.util.spec_from_file_location('supervisor',pathlib.Path(__file__).resolve().parents[2]/'runtime/supervisor.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class Contracts(unittest.TestCase):
 def tuning_fixture(self,folder,request,env=None):
  s=object.__new__(module.Supervisor);s.req=dict(renderer='turnip26',**request)
  s.env=dict(env or {});s.state={};s.status=Mock();s.spawn=Mock();s.wait=Mock();s.logs=[Mock()]
  (folder/'graphics-tuning.log').write_text('info: DXVK: Using 2 compiler threads\n')
  s.spawn.return_value.poll.return_value=0
  return s
 def test_tuning_disabled_preserves_environment_and_skips_check(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   env={'TU_DEBUG':'perf','DXVK_CONFIG':'d3d9.maxFrameRate = 30','LD_PRELOAD':'gamepad upload'}
   s=self.tuning_fixture(pathlib.Path(t),{},env);s.configure_graphics_tuning()
   self.assertEqual(s.env,env);s.spawn.assert_not_called()
   self.assertFalse(any(s.status.call_args.kwargs['graphics_tuning']['active'].values()))
 def test_tuning_controls_are_independent_and_confirmed(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   for sysmem,workers in [(True,False),(False,True),(True,True)]:
    s=self.tuning_fixture(pathlib.Path(t),{'turnip_sysmem':sysmem,'dxvk_two_compilers':workers},{'LD_PRELOAD':'gamepad upload','DXVK_CONFIG':'d3d9.maxFrameRate = 30'})
    s.configure_graphics_tuning();report=s.status.call_args.kwargs['graphics_tuning']
    self.assertEqual(report['active'],{'turnip_sysmem':sysmem,'dxvk_two_compilers':workers})
    self.assertEqual(s.env.get('TU_DEBUG'), 'sysmem' if sysmem else None)
    self.assertEqual('dxvk.numCompilerThreads = 2' in s.env['DXVK_CONFIG'],workers)
    self.assertTrue(s.env['DXVK_CONFIG'].startswith('d3d9.maxFrameRate = 30'));self.assertEqual(s.env['LD_PRELOAD'],'gamepad upload')
    self.assertTrue(s.spawn.call_args.kwargs['fixed_output'])
 def test_tuning_failed_or_unconfirmed_check_restores_both_options(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   for previous in ({},{'TU_DEBUG':'perf','DXVK_CONFIG':'d3d9.maxFrameRate = 30'}):
    for failure in ('exit','unconfirmed'):
     s=self.tuning_fixture(pathlib.Path(t),{'turnip_sysmem':True,'dxvk_two_compilers':True},previous)
     if failure=='exit':s.wait.side_effect=RuntimeError('fixture failure')
     else:(pathlib.Path(t)/'graphics-tuning.log').write_text('info: DXVK: Using 8 compiler threads\n')
     s.configure_graphics_tuning();self.assertEqual(s.env,previous)
     report=s.status.call_args.kwargs['graphics_tuning'];self.assertIn('fallback',report);self.assertFalse(any(report['active'].values()))
 def test_tuning_does_not_swallow_stop_or_apply_to_software(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   s=self.tuning_fixture(pathlib.Path(t),{'turnip_sysmem':True,'dxvk_two_compilers':True});s.wait.side_effect=module.Stopped()
   with self.assertRaises(module.Stopped):s.configure_graphics_tuning()
   s=self.tuning_fixture(pathlib.Path(t),{'turnip_sysmem':True,'dxvk_two_compilers':True});s.req['renderer']='software';s.configure_graphics_tuning()
   self.assertEqual(s.env,{});s.spawn.assert_not_called()
   s=self.tuning_fixture(pathlib.Path(t),{'turnip_sysmem':True});s.req['renderer']='turnip24';s.configure_graphics_tuning()
   self.assertEqual(s.env,{});s.spawn.assert_not_called()
 def test_tuning_inputs_are_booleans_not_arbitrary_driver_flags(self):
  req={'format':1,'renderer':'turnip26','audio':True,'session_id':str(uuid.uuid4())}
  for key in ('turnip_sysmem','dxvk_two_compilers'):
   self.assertEqual(module.validate_request(dict(req,**{key:True}))[key],True)
   for value in ('sysmem,noconform',1,None):
    with self.assertRaises(ValueError):module.validate_request(dict(req,**{key:value}))
 def test_graphics_choices_are_closed_and_reversible(self):
  req={'format':1,'renderer':'turnip26','audio':True,'session_id':str(uuid.uuid4())}
  for version in ('2.5.3','2.7.1'):self.assertEqual(module.validate_request(dict(req,dxvk_version=version,shm_upload=True))['dxvk_version'],version)
  for version in ('latest','../2.7.1',None):
   with self.assertRaises(ValueError):module.validate_request(dict(req,dxvk_version=version))
  with self.assertRaises(ValueError):module.validate_request(dict(req,shm_upload='yes'))
 def test_dxvk_pair_switch_and_failed_candidate_restore(self):
  with tempfile.TemporaryDirectory() as t:
   p=pathlib.Path(t);bundle=p/'bundle';bundle.mkdir();prefix=p/'prefix';dest=prefix/'drive_c/windows/syswow64';dest.mkdir(parents=True);(prefix/'lsb-cache').mkdir()
   files={}
   for name in ('d3d8','d3d9','2.7.1-d3d8','2.7.1-d3d9'):
    filename='dxvk-'+name+'.dll';(bundle/filename).write_bytes(name.encode());files[filename]=hashlib.sha256(name.encode()).hexdigest()
   with patch.object(module,'BUNDLE',bundle),patch.object(module,'PREFIX',prefix):
    s=object.__new__(module.Supervisor);s.env={};s.req={'dxvk_version':'2.7.1'};s.state={'vulkan':{'device':'fixture'}};s.status=Mock();s.spawn=Mock();s.wait=Mock()
    s.select_dxvk({'files':files})
    self.assertEqual((dest/'d3d8.dll').read_bytes(),b'2.7.1-d3d8');self.assertEqual((dest/'d3d9.dll').read_bytes(),b'2.7.1-d3d9')
    self.assertEqual(s.status.call_args.kwargs['dxvk_selected'],'2.7.1')
    s.wait.side_effect=RuntimeError('fixture incompatible');s.spawn.return_value.poll.return_value=12
    s.select_dxvk({'files':files})
    self.assertEqual((dest/'d3d8.dll').read_bytes(),b'd3d8');self.assertEqual((dest/'d3d9.dll').read_bytes(),b'd3d9')
    self.assertEqual(s.status.call_args.kwargs['dxvk_selected'],'2.5.3');self.assertIn('dxvk_fallback',s.state)
    self.assertEqual(s.env['DXVK_STATE_CACHE_PATH'],str(prefix/'lsb-cache'))
 def test_upload_failure_preserves_gamepad_preload(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'BUNDLE',pathlib.Path(t)):
   s=object.__new__(module.Supervisor);s.env={'LD_PRELOAD':'/opt/lsb/liblsb-gamepad.so'};s.req={'shm_upload':True};s.state={};s.status=Mock();s.spawn=Mock()
   s.configure_upload();s.spawn.assert_not_called()
   self.assertEqual(s.env,{'LD_PRELOAD':'/opt/lsb/liblsb-gamepad.so'})
   self.assertFalse(s.status.call_args.kwargs['shm_upload_active'])
   self.assertIn('shm_upload_fallback',s.status.call_args.kwargs)
 def test_display_rate_accepts_only_supported_caps(self):
  req={'format':1,'renderer':'turnip26','audio':True,'session_id':str(uuid.uuid4())}
  for fps in (30,60):self.assertEqual(module.validate_request(dict(req,display_fps=fps))['display_fps'],fps)
  for fps in (0,45,120,'60',True):
   with self.assertRaises(ValueError):module.validate_request(dict(req,display_fps=fps))
 def test_diagnostic_hud_is_explicit_and_does_not_change_normal_hud(self):
  req={'format':1,'renderer':'turnip26','audio':True,'session_id':str(uuid.uuid4())}
  self.assertEqual(module.graphics_hud(req),'devinfo,fps')
  self.assertEqual(module.graphics_hud(dict(req,dxvk_hud=False)),'')
  detailed=dict(req,dxvk_hud=False,dxvk_diagnostics=True)
  self.assertEqual(module.validate_request(detailed),detailed)
  self.assertEqual(module.graphics_hud(detailed),'devinfo,fps,frametimes,compiler,cs')
  with self.assertRaises(ValueError):module.validate_request(dict(req,dxvk_diagnostics='true'))
 def test_request_excludes_game_paths_and_commands(self):
  req={'format':1,'renderer':'turnip26','audio':True,'session_id':str(uuid.uuid4())}
  self.assertEqual(module.validate_request(req),req)
  for key in ['command','client','password','allow_software_vulkan']:
   with self.assertRaises(ValueError):module.validate_request(dict(req,**{key:'anything'}))
  for renderer in ['','vulkan','turnip26;echo bad']:
   with self.assertRaises(ValueError):module.validate_request(dict(req,renderer=renderer))
 def test_log_bounded_without_stalling_child(self):
  with tempfile.TemporaryDirectory() as t:
   path=pathlib.Path(t)/'wine.log';writer=module.BoundedLog(path);writer.start(io.BufferedReader(io.BytesIO(b'a'*(6*1024*1024))))
   writer.thread.join(5);self.assertFalse(writer.thread.is_alive());self.assertLessEqual(path.stat().st_size,2*1024*1024);self.assertLessEqual(path.with_suffix('.previous.log').stat().st_size,2*1024*1024)
 def test_bundle_rejects_missing_inventory(self):
  with tempfile.TemporaryDirectory() as t:
   p=pathlib.Path(t);(p/'bundle.json').write_text(json.dumps({'format':1,'candidate':'wine10-box64-0.4.4','dxvk':'2.5.3','files':{}}))
   with self.assertRaisesRegex(ValueError,'inventory'):module.verify_bundle(p)
 def test_native_surface_is_optional_and_validated(self):
  req={'format':1,'renderer':'turnip26','audio':True,'session_id':str(uuid.uuid4()),'native_surface':True}
  self.assertEqual(module.validate_request(req),req)
  with self.assertRaises(ValueError):module.validate_request(dict(req,native_surface='true'))
  with tempfile.TemporaryDirectory() as t,patch.object(module,'BUNDLE',pathlib.Path(t)):
   s=object.__new__(module.Supervisor);s.req=req;s.spawn=Mock();s.status=Mock()
   s.start_native_surface();s.spawn.assert_not_called();self.assertIn('native_surface_fallback',s.status.call_args.kwargs)
   binary=pathlib.Path(t)/'x11-frame-bridge';binary.write_bytes(b'fixture')
   manifest=pathlib.Path(t)/'presentation-bundle.json';manifest.write_text(json.dumps({'format':1,'sha256':hashlib.sha256(binary.read_bytes()).hexdigest()}))
   s.start_native_surface();s.spawn.assert_called_once();self.assertTrue(s.spawn.call_args.kwargs['fixed_output'])
   self.assertEqual(s.spawn.call_args.args[0][-1],'30')
   s.spawn.reset_mock();s.req=dict(req,display_fps=60);s.start_native_surface();self.assertEqual(s.spawn.call_args.args[0][-1],'60')
   s.spawn.reset_mock();binary.write_bytes(b'corrupted');s.start_native_surface();s.spawn.assert_not_called()
   s.req=dict(req,native_surface=False);s.status.reset_mock();s.start_native_surface();s.spawn.assert_not_called();s.status.assert_not_called()
if __name__=='__main__':unittest.main()
