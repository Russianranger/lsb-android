import importlib.util,json,pathlib,tempfile,unittest,uuid,io,time,hashlib
from unittest.mock import patch,Mock
spec=importlib.util.spec_from_file_location('supervisor',pathlib.Path(__file__).resolve().parents[2]/'runtime/supervisor.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class Contracts(unittest.TestCase):
 def test_pixel_check_requires_both_modes_and_rejects_failure_receipts(self):
  sw='LSB_D3D8_PIXELS mode=swvp frames=4 samples=64 PASS\n'
  hw='LSB_D3D8_PIXELS mode=hwvp frames=4 samples=64 PASS\n'
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   for log,passed in [(sw+hw,True),(sw,False),('',False),(sw+hw+sw,False),
                      (sw+hw+'LSB_D3D8_PIXEL mode=swvp frame=0 panel=2 sample=0 expected=a04020 actual=000000 FAIL\n',False)]:
    s=object.__new__(module.Supervisor);s.engine='fex';s.req={};s.env={};s.state={'dxvk_selected':'2.7.1'}
    s.logs=[Mock()];s.spawn=Mock();s.wait=Mock();s.status=Mock()
    (pathlib.Path(t)/'graphics-pixels.log').write_text(log)
    if passed:
     s.check_graphics_pixels();self.assertEqual(s.status.call_args.kwargs['graphics_pixels']['samples'],128)
    else:
     with self.assertRaisesRegex(RuntimeError,'incomplete'):s.check_graphics_pixels()
     self.assertFalse(s.status.call_args.kwargs['graphics_pixels']['passed'])
    self.assertEqual(s.spawn.call_args.args[0][-1],'--pixels')
    self.assertTrue(s.spawn.call_args.kwargs['fixed_output'])
 def tuning_fixture(self,folder,request,env=None):
  s=object.__new__(module.Supervisor);s.engine="box64";s.req=dict(renderer='turnip26',**request)
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
    self.assertEqual(report['active'],{'turnip_sysmem':sysmem,'dxvk_two_compilers':workers,'dxvk_staged_buffers':False})
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
  for key in ('turnip_sysmem','dxvk_two_compilers','dxvk_staged_buffers','borderless','proot_acceleration'):
   self.assertEqual(module.validate_request(dict(req,**{key:True}))[key],True)
   self.assertEqual(module.validate_request(dict(req,**{key:False}))[key],False)
   for value in ('sysmem,noconform',1,None):
    with self.assertRaises(ValueError):module.validate_request(dict(req,**{key:value}))
 def test_staged_buffers_require_configuration_and_both_pixel_modes(self):
  good='info: d3d9.allowDirectBufferMapping = False\nLSB_D3D8_PIXELS mode=swvp frames=4 samples=64 PASS\nLSB_D3D8_PIXELS mode=hwvp frames=4 samples=64 PASS\n'
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   for log,passed in [(good,True),(good.split('\n',1)[1],False),(good.replace('mode=hwvp','mode=swvp'),False),(good+'LSB_D3D8_PIXEL FAIL\n',False)]:
    env={'DXVK_CONFIG':'d3d9.maxFrameRate = 30','LD_PRELOAD':'gamepad upload'}
    s=self.tuning_fixture(pathlib.Path(t),{'dxvk_staged_buffers':True},env);s.state['dxvk_selected']='2.7.1'
    (pathlib.Path(t)/'graphics-tuning.log').write_text(log)
    s.configure_graphics_tuning();report=s.status.call_args.kwargs['graphics_tuning']
    self.assertEqual(s.spawn.call_args.args[0][-1],'--pixels')
    self.assertEqual(report['active']['dxvk_staged_buffers'],passed)
    if passed:
     self.assertEqual(s.env['DXVK_CONFIG'],'d3d9.maxFrameRate = 30;d3d9.allowDirectBufferMapping = False')
     self.assertEqual(report['buffer_upload'],'staged');self.assertNotIn('TU_DEBUG',s.env)
    else:self.assertEqual(s.env,env);self.assertIn('fallback',report)
 def test_staged_buffers_skip_older_dxvk_and_software(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   for renderer,version in [('turnip26','2.5.3'),('software','2.7.1')]:
    s=self.tuning_fixture(pathlib.Path(t),{'dxvk_staged_buffers':True});s.req['renderer']=renderer;s.state['dxvk_selected']=version
    s.configure_graphics_tuning();s.spawn.assert_not_called();self.assertEqual(s.env,{})
    self.assertFalse(any(s.status.call_args.kwargs['graphics_tuning']['active'].values()))
 def trial_fixture(self,folder,trial):
  s=self.tuning_fixture(folder,{'performance_trial':trial,'dxvk_two_compilers':True})
  s.env={'DXVK_CONFIG':'dxvk.numCompilerThreads = 2','LD_PRELOAD':'gamepad upload'}
  s.state={'dxvk_selected':'2.7.1','graphics_tuning':{'active':{'dxvk_two_compilers':True},'graphics_pipeline_libraries':True}}
  workers=1 if trial=='one_compiler' else 2
  option={'one_compiler':'dxvk.numCompilerThreads = 1','cached_dynamic':'d3d9.cachedDynamicBuffers = True',
          'gpl_fast':'dxvk.enableGraphicsPipelineLibrary = True'}.get(trial,'dxvk.trackPipelineLifetime = False')
  log='info: '+option+'\ninfo: DXVK: Using '+str(workers)+' compiler threads\ninfo: graphicsPipelineLibrary : 1\ninfo: DXVK: Graphics pipeline libraries supported\nLSB_D3D8_PIXELS mode=swvp frames=4 samples=64 PASS\nLSB_D3D8_PIXELS mode=hwvp frames=4 samples=64 PASS\n'
  (folder/'performance-trial.log').write_text(log)
  return s
 def test_trial_schema_and_no_implicit_activation(self):
  req={'format':1,'renderer':'turnip26','audio':True,'session_id':str(uuid.uuid4())}
  for value in ('none','one_compiler','cached_dynamic','gpl_fast','syscall_filter','retain_pipelines','lighter_scene'):
   module.validate_request(dict(req,performance_trial=value))
  for value in ('all',None,True,'dxvk.trackPipelineLifetime = False'):
   with self.assertRaises(ValueError):module.validate_request(dict(req,performance_trial=value))
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   for request in ({},{'performance_trial':'none'}):
    s=self.tuning_fixture(pathlib.Path(t),request);s.configure_performance_trial();s.spawn.assert_not_called();self.assertEqual(s.env,{})
 def test_trials_are_isolated_and_failure_keeps_validated_baseline(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t)
   for trial in ('one_compiler','cached_dynamic','gpl_fast'):
    for failure in ('none','timeout','pixels','pixel_failure','workers','config','stop','no_gpl','no_gpl_manager'):
     if failure in ('no_gpl','no_gpl_manager') and trial!='gpl_fast':continue
     s=self.trial_fixture(folder,trial);previous=dict(s.env);log=folder/'performance-trial.log'
     if failure=='timeout':s.wait.side_effect=RuntimeError('timed out')
     if failure=='pixels':log.write_text(log.read_text().replace('mode=hwvp','mode=swvp'))
     if failure=='pixel_failure':log.write_text(log.read_text()+'LSB_D3D8_PIXEL mode=hwvp frame=0 FAIL\n')
     if failure=='workers':log.write_text(log.read_text().replace('compiler threads','unconfirmed'))
     if failure=='config':log.write_text(log.read_text().split('\n',1)[1])
     if failure=='no_gpl':log.write_text(log.read_text().replace('Library : 1','Library : 0'))
     if failure=='no_gpl_manager':log.write_text(log.read_text().replace('libraries supported','libraries not supported'))
     if failure=='stop':
      s.wait.side_effect=module.Stopped()
      with self.assertRaises(module.Stopped):s.configure_performance_trial()
      continue
     s.configure_performance_trial();report=s.status.call_args.kwargs['performance_trial']
     self.assertTrue(s.state['graphics_tuning']['active']['dxvk_two_compilers'])
     if failure=='none':
      self.assertEqual(report['active'],trial);self.assertEqual(report['compiler_threads'],1 if trial=='one_compiler' else 2)
      self.assertEqual(s.env['LD_PRELOAD'],'gamepad upload');self.assertEqual(s.spawn.call_args.args[0][-1],'--pixels')
      self.assertEqual(s.env['DXVK_CONFIG'].count(';'),1)
      if trial=='cached_dynamic':
       self.assertNotIn('allowDirectBufferMapping',s.env['DXVK_CONFIG'])
       self.assertIn('already cached',report['scope']);self.assertIn('GPU throughput',report['tradeoff'])
      if trial=='gpl_fast':
       self.assertNotIn('trackPipelineLifetime',s.env['DXVK_CONFIG'])
       self.assertIn('background optimized variants disabled',report['pipeline_mode'])
       self.assertIn('retain more',report['tradeoff'])
     else:
      self.assertEqual(report['active'],'none');self.assertIn('fallback',report);self.assertEqual(s.env,previous)
 def test_retired_trials_cannot_be_reactivated_by_old_saved_requests(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   for trial in ('retain_pipelines','lighter_scene'):
    s=self.trial_fixture(pathlib.Path(t),trial);s.req.update(action='launch',display_profile='windowed720')
    previous=dict(s.env);s.configure_performance_trial();s.spawn.assert_not_called()
    report=s.status.call_args.kwargs['performance_trial'];self.assertEqual(report['requested'],trial)
    self.assertEqual(report['active'],'none');self.assertIn('Retired',report['note']);self.assertEqual(s.env,previous)
 def test_gpl_trial_skips_probe_without_baseline_support(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   for support in (None,False):
    s=self.trial_fixture(pathlib.Path(t),'gpl_fast');previous=dict(s.env)
    s.state['graphics_tuning'].pop('graphics_pipeline_libraries')
    if support is not None:s.state['graphics_tuning']['graphics_pipeline_libraries']=support
    s.configure_performance_trial();s.spawn.assert_not_called();self.assertEqual(s.env,previous)
    report=s.status.call_args.kwargs['performance_trial']
    self.assertEqual(report['active'],'none');self.assertIn('confirmed by the baseline',report['note'])
 def test_baseline_reports_gpl_only_with_capability_and_manager_confirmation(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   for capability,manager in ((True,True),(True,False),(False,True),(False,False)):
    s=self.tuning_fixture(pathlib.Path(t),{'dxvk_two_compilers':True})
    log='info: DXVK: Using 2 compiler threads\n'
    if capability:log+='info: graphicsPipelineLibrary : 1\n'
    if manager:log+='info: DXVK: Graphics pipeline libraries supported\n'
    (pathlib.Path(t)/'graphics-tuning.log').write_text(log)
    s.configure_graphics_tuning();report=s.status.call_args.kwargs['graphics_tuning']
    self.assertEqual(report['graphics_pipeline_libraries'],capability and manager)
    self.assertTrue(report['active']['dxvk_two_compilers'])
 def syscall_trial_fixture(self,folder):
  s=self.trial_fixture(folder,'syscall_filter');s.engine='fex'
  s.req.update(action='launch',engine='fex',session_id=str(uuid.uuid4()))
  receipt={'format':1,'session_id':s.req['session_id'],'requested':'syscall_filter',
           'preflight_passed':True,'launch_observed':True,'mode':'syscall_filter'}
  (folder/'proot-acceleration.json').write_text(json.dumps(receipt))
  return s,receipt
 def test_runtime_acceleration_default_request_is_separate_from_graphics_trials(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t);s,_=self.syscall_trial_fixture(folder);previous=dict(s.env)
   s.req.update(proot_acceleration=True,performance_trial='none')
   s.configure_runtime_acceleration()
   report=s.status.call_args.kwargs['runtime_acceleration']
   self.assertTrue(report['requested']);self.assertEqual(report['active'],'syscall_filter')
   self.assertTrue(report['host_launch_observed']);self.assertEqual(report['compiler_threads'],2)
   self.assertEqual(s.env,previous);s.spawn.assert_not_called()
   s.configure_performance_trial()
   self.assertEqual(s.status.call_args.kwargs['performance_trial'],{'requested':'none','active':'none'})
 def test_runtime_acceleration_off_and_older_baseline_do_not_read_receipts(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t)
   for request in ({'proot_acceleration':False,'performance_trial':'none'},
                   {'proot_acceleration':False,'performance_trial':'syscall_filter'},
                   {'performance_trial':'none'}):
    s,_=self.syscall_trial_fixture(folder);s.req.update(request)
    (folder/'proot-acceleration.json').write_text('malformed')
    with patch.object(module.os,'open',side_effect=AssertionError('Disabled feature read its receipt')):
     s.configure_runtime_acceleration()
     report=s.status.call_args.kwargs['runtime_acceleration']
     self.assertEqual(report,{'requested':False,'active':'none'})
     s.configure_performance_trial()
    self.assertEqual(s.status.call_args.kwargs['performance_trial']['active'],'none');s.spawn.assert_not_called()
 def test_runtime_acceleration_declined_host_receipt_keeps_compatibility(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t)
   for trial in ('none','one_compiler','cached_dynamic','gpl_fast'):
    s,receipt=self.syscall_trial_fixture(folder);previous=dict(s.env)
    s.req.update(proot_acceleration=True,performance_trial=trial)
    receipt.update(preflight_passed=False,launch_observed=False,mode='compatibility')
    (folder/'proot-acceleration.json').write_text(json.dumps(receipt))
    s.configure_runtime_acceleration();report=s.status.call_args.kwargs['runtime_acceleration']
    self.assertTrue(report['requested']);self.assertEqual(report['active'],'none')
    self.assertNotIn('launch_blocked',report);self.assertIn('compatibility',report['note'])
    self.assertEqual(s.env,previous);s.spawn.assert_not_called()
 def test_runtime_acceleration_supports_legacy_f_and_explicit_feature_wins(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t)
   for explicit in (False,True):
    s,_=self.syscall_trial_fixture(folder)
    if explicit:s.req['proot_acceleration']=True
    s.configure_runtime_acceleration();feature=s.status.call_args.kwargs['runtime_acceleration']
    self.assertTrue(feature['requested']);self.assertEqual(feature['active'],'syscall_filter')
    s.state['runtime_acceleration']=feature
    with patch.object(module.os,'open',side_effect=AssertionError('Host receipt read twice')):
     s.configure_performance_trial()
    trial=s.status.call_args.kwargs['performance_trial']
    self.assertEqual(trial['active'],'none' if explicit else 'syscall_filter')
 def test_runtime_acceleration_fails_closed_for_wrong_trial_or_missing_receipt(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t)
   for trial in ('one_compiler','cached_dynamic','gpl_fast','retain_pipelines','lighter_scene'):
    s,_=self.syscall_trial_fixture(folder);previous=dict(s.env)
    s.req.update(proot_acceleration=True,performance_trial=trial)
    with self.assertRaisesRegex(RuntimeError,'disable Runtime syscall filtering'):
     s.configure_runtime_acceleration()
    report=s.status.call_args.kwargs['runtime_acceleration']
    self.assertEqual(report['active'],'syscall_filter');self.assertTrue(report['launch_blocked'])
    self.assertEqual(s.env,previous);s.spawn.assert_not_called()
   s,_=self.syscall_trial_fixture(folder);s.req.update(proot_acceleration=True,performance_trial='none')
   (folder/'proot-acceleration.json').unlink()
   with self.assertRaisesRegex(RuntimeError,'disable Runtime syscall filtering'):
    s.configure_runtime_acceleration()
   report=s.status.call_args.kwargs['runtime_acceleration']
   self.assertEqual(report['active'],'unconfirmed');self.assertTrue(report['launch_blocked'])
 def test_syscall_trial_requires_host_receipt_and_keeps_graphics_environment(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t)
   for mode in ('active','declined','wrong_session','missing','malformed','inconsistent','pending'):
    s,receipt=self.syscall_trial_fixture(folder);previous=dict(s.env);path=folder/'proot-acceleration.json'
    if mode=='declined':receipt.update(preflight_passed=False,launch_observed=False,mode='compatibility')
    if mode=='wrong_session':receipt['session_id']=str(uuid.uuid4())
    if mode=='inconsistent':receipt['mode']='compatibility'
    if mode=='pending':receipt['launch_observed']=False
    path.write_text(json.dumps(receipt))
    if mode=='missing':path.unlink()
    if mode=='malformed':path.write_text('{')
    with patch.object(module.time,'monotonic',side_effect=(1,4)),patch.object(module.time,'sleep'):
     if mode in ('active','declined'):s.configure_performance_trial()
     else:
      with self.assertRaisesRegex(RuntimeError,'activation unconfirmed'):s.configure_performance_trial()
    s.spawn.assert_not_called();self.assertEqual(s.env,previous)
    report=s.status.call_args.kwargs['performance_trial']
    self.assertEqual(report['active'],'syscall_filter' if mode=='active' else 'none' if mode=='declined' else 'unconfirmed')
    if mode not in ('active','declined'):self.assertTrue(report['launch_blocked'])
 def test_syscall_trial_waits_for_host_observation_receipt(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t);s,receipt=self.syscall_trial_fixture(folder)
   pending=dict(receipt,launch_observed=False)
   (folder/'proot-acceleration.json').write_text(json.dumps(pending))
   def observed(_): (folder/'proot-acceleration.json').write_text(json.dumps(receipt))
   with patch.object(module.time,'sleep',side_effect=observed) as wait:s.configure_performance_trial()
   wait.assert_called_once();self.assertEqual(s.status.call_args.kwargs['performance_trial']['active'],'syscall_filter')
 def test_syscall_trial_stop_during_host_observation_propagates(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t);s,receipt=self.syscall_trial_fixture(folder);previous=dict(s.env)
   receipt['launch_observed']=False;(folder/'proot-acceleration.json').write_text(json.dumps(receipt))
   s.stopped=Mock(side_effect=(None,module.Stopped()))
   with patch.object(module.time,'sleep'),self.assertRaises(module.Stopped):s.configure_performance_trial()
   self.assertEqual(s.stopped.call_count,2);self.assertEqual(s.env,previous)
   s.status.assert_not_called();s.spawn.assert_not_called()
 def test_syscall_trial_rejects_oversized_symlink_and_non_boolean_receipts(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t)
   for corruption in ('oversized','symlink','fifo','preflight_string','preflight_number','observed_string','observed_number','not_object'):
    path=folder/'proot-acceleration.json'
    if path.exists() or path.is_symlink():path.unlink()
    s,receipt=self.syscall_trial_fixture(folder)
    if corruption=='oversized':receipt['padding']='x'*16384
    if corruption=='preflight_string':receipt['preflight_passed']='true'
    if corruption=='preflight_number':receipt['preflight_passed']=1
    if corruption=='observed_string':receipt['launch_observed']='true'
    if corruption=='observed_number':receipt['launch_observed']=1
    path.write_text(json.dumps([] if corruption=='not_object' else receipt))
    if corruption=='symlink':
     other=folder/'other.json';other.write_text(path.read_text());path.unlink();path.symlink_to(other)
    if corruption=='fifo':path.unlink();module.os.mkfifo(path)
    with self.assertRaisesRegex(RuntimeError,'activation unconfirmed'):s.configure_performance_trial()
    report=s.status.call_args.kwargs['performance_trial']
    self.assertEqual(report['active'],'unconfirmed');self.assertTrue(report['launch_blocked'])
    s.spawn.assert_not_called()
 def test_active_syscall_trial_blocks_instead_of_faking_host_fallback(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t)
   for condition in ('action','engine','software','older','workers','sysmem','staged'):
    s,_=self.syscall_trial_fixture(folder);previous=dict(s.env)
    if condition=='action':s.req['action']='probe'
    elif condition=='engine':s.engine='box64'
    elif condition=='software':s.req['renderer']='software'
    elif condition=='older':s.state['dxvk_selected']='2.5.3'
    elif condition=='workers':s.state['graphics_tuning']['active']['dxvk_two_compilers']=False
    else:s.state['graphics_tuning']['active']['turnip_sysmem' if condition=='sysmem' else 'dxvk_staged_buffers']=True
    with self.assertRaisesRegex(RuntimeError,'baseline changed'):s.configure_performance_trial()
    report=s.status.call_args.kwargs['performance_trial']
    self.assertEqual(report['active'],'syscall_filter');self.assertTrue(report['launch_blocked'])
    self.assertEqual(s.env,previous);s.spawn.assert_not_called()
 def test_trial_guards_and_lighter_scene_do_not_change_other_profiles(self):
  with tempfile.TemporaryDirectory() as t,patch.object(module,'LOGS',pathlib.Path(t)):
   folder=pathlib.Path(t)
   for trial in ('one_compiler','cached_dynamic','gpl_fast'):
    for condition in ('software','older','unconfirmed','sysmem','staged'):
     s=self.trial_fixture(folder,trial);previous=dict(s.env)
     if condition=='software':s.req['renderer']='software'
     elif condition=='older':s.state['dxvk_selected']='2.5.3'
     elif condition=='unconfirmed':s.state['graphics_tuning']['active']['dxvk_two_compilers']=False
     else:s.state['graphics_tuning']['active']['turnip_sysmem' if condition=='sysmem' else 'dxvk_staged_buffers']=True
     s.configure_performance_trial();s.spawn.assert_not_called();self.assertEqual(s.env,previous)
     self.assertEqual(s.status.call_args.kwargs['performance_trial']['active'],'none')
   for action,profile in [('launch','windowed720'),('launch','preserve'),('launch','restore'),('launch','windowed540'),('probe','windowed720')]:
    s=self.trial_fixture(folder,'lighter_scene');s.req.update(action=action,display_profile=profile);previous=dict(s.env)
    s.configure_performance_trial();s.spawn.assert_not_called();self.assertEqual(s.env,previous)
    report=s.status.call_args.kwargs['performance_trial']
    self.assertEqual(report['active'],'none');self.assertIn('Retired',report['note'])
    self.assertEqual(s.req['display_profile'],profile)
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
    s=object.__new__(module.Supervisor);s.engine="box64";s.env={};s.req={'dxvk_version':'2.7.1'};s.state={'vulkan':{'device':'fixture'}};s.status=Mock();s.spawn=Mock();s.wait=Mock()
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
   s=object.__new__(module.Supervisor);s.engine="box64";s.env={'LD_PRELOAD':'/opt/lsb/liblsb-gamepad.so'};s.req={'shm_upload':True};s.state={};s.status=Mock();s.spawn=Mock()
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
   s=object.__new__(module.Supervisor);s.engine="box64";s.req=req;s.spawn=Mock();s.status=Mock()
   s.start_native_surface();s.spawn.assert_not_called();self.assertIn('native_surface_fallback',s.status.call_args.kwargs)
   binary=pathlib.Path(t)/'x11-frame-bridge';binary.write_bytes(b'fixture')
   manifest=pathlib.Path(t)/'presentation-bundle.json';manifest.write_text(json.dumps({'format':1,'sha256':hashlib.sha256(binary.read_bytes()).hexdigest()}))
   s.start_native_surface();s.spawn.assert_called_once();self.assertTrue(s.spawn.call_args.kwargs['fixed_output'])
   self.assertEqual(s.spawn.call_args.args[0][-1],'30')
   s.spawn.reset_mock();s.req=dict(req,display_fps=60);s.start_native_surface();self.assertEqual(s.spawn.call_args.args[0][-1],'60')
   s.spawn.reset_mock();binary.write_bytes(b'corrupted');s.start_native_surface();s.spawn.assert_not_called()
   s.req=dict(req,native_surface=False);s.status.reset_mock();s.start_native_surface();s.spawn.assert_not_called();s.status.assert_not_called()
if __name__=='__main__':unittest.main()
