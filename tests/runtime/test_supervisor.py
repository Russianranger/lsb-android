import importlib.util,json,pathlib,tempfile,unittest,uuid,io,time
spec=importlib.util.spec_from_file_location('supervisor',pathlib.Path(__file__).resolve().parents[2]/'runtime/supervisor.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class Contracts(unittest.TestCase):
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
if __name__=='__main__':unittest.main()
