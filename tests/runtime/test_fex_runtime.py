import hashlib,json,struct,sys,tempfile,unittest,uuid
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'runtime'))
import fex_runtime as fex
from supervisor import Supervisor

class FexContracts(unittest.TestCase):
    def test_engine_selection_preserves_baseline_and_removes_box64_environment(self):
        req=dict(format=1,renderer='software',audio=False,session_id=str(uuid.uuid4()))
        baseline=Supervisor(req);native=Supervisor(dict(req,engine='fex'))
        self.assertEqual(baseline.wine_command('a'),['/usr/local/bin/box64','/opt/wine/bin/wine','a'])
        self.assertEqual(native.wine_command('a'),['/opt/wine/bin/wine','a'])
        self.assertEqual(native.server_command('-k'),['/opt/wine/bin/wineserver','-k'])
        self.assertFalse(any(k.startswith('BOX64_') for k in native.env))
        self.assertEqual(native.env['WINEDLLOVERRIDES'],baseline.env['WINEDLLOVERRIDES']+';winedbg.exe=')
        for name in ('LIBGL_ALWAYS_SOFTWARE','GALLIUM_DRIVER','LP_NUM_THREADS'):
            self.assertEqual(native.env[name],baseline.env[name])
        for value in ('latest','../fex',None,True):
            with self.assertRaises(ValueError):Supervisor(dict(req,engine=value))

    def test_fex_precision_is_explicit_reversible_and_baseline_is_unchanged(self):
        req=dict(format=1,renderer='software',audio=False,session_id=str(uuid.uuid4()))
        for enabled in (False,True):
            native=Supervisor(dict(req,engine='fex',fex_x87=enabled))
            for key in ('FEX_X87REDUCEDPRECISION','FEX_X87STRICTREDUCEDPRECISION'):
                self.assertEqual(native.env[key],'1' if enabled else '0')
                self.assertNotIn(key,Supervisor(dict(req,fex_x87=enabled)).env)
        for value in ('1',1,None):
            with self.assertRaises(ValueError):Supervisor(dict(req,fex_x87=value))

    def test_child_environment_hash_excludes_secrets_and_detects_missing_control(self):
        a={'FEX_X87REDUCEDPRECISION':'1','FEX_X87STRICTREDUCEDPRECISION':'1','DXVK_HUD':'fps'}
        self.assertEqual(fex.environment_hash(a),fex.environment_hash(dict(a,PASSWORD='secret')))
        self.assertNotEqual(fex.environment_hash(a),fex.environment_hash(dict(a,DXVK_HUD='')))
        self.assertNotEqual(fex.environment_hash(a),fex.environment_hash(dict(a,FEX_X87REDUCEDPRECISION='0')))

    def test_stopped_prefix_registry_selects_only_x86_translator(self):
        with tempfile.TemporaryDirectory() as t:
            p=Path(t);hive=p/'system.reg'
            original='WINE REGISTRY Version 2\n#arch=win64\n\n[Software\\\\Microsoft\\\\Wow64\\\\x86] 123\n@="wow64cpu.dll"\n"other"="keep"\n\n[Game] 12\n"Version"="30251204_1"\n'
            hive.write_text(original);fex.select_translator(p);once=hive.read_text();fex.select_translator(p)
            self.assertEqual(hive.read_text(),once)
            self.assertEqual(once,original.replace('@="wow64cpu.dll"','@="libwow64fex.dll"'))
            hive.write_text('WINE REGISTRY Version 2\n#arch=win64\n');fex.select_translator(p)
            self.assertIn('[Software\\\\Microsoft\\\\Wow64\\\\x86]\n@="libwow64fex.dll"',hive.read_text())

    def fixture(self,folder):
        wine=folder/'wine';bundle=folder/'bundle';prefix=folder/'prefix'
        for p in (wine,bundle,prefix):p.mkdir()
        files={}
        for name in fex.KEY_FILES:
            p=wine/name;p.parent.mkdir(parents=True,exist_ok=True)
            if name.endswith('.dll'):
                data=bytearray(128);data[:2]=b'MZ';struct.pack_into('<I',data,60,64);data[64:70]=b'PE\0\0\x64\xaa'
            else:
                data=bytearray(64);data[:6]=b'\x7fELF\x02\x01';struct.pack_into('<H',data,18,183)
            p.write_bytes(data);files[name]=fex.digest(p)
        (wine/'lsb-fex.json').write_text(json.dumps({'files':files}))
        m=dict(format=1,candidate=fex.CANDIDATE,manifest_sha256=fex.digest(wine/'lsb-fex.json'),sha256='1'*64,wine_commit='w',fex_commit='f',guest='i386',host='aarch64')
        (bundle/'fex-bundle.json').write_text(json.dumps(m))
        (prefix/'lsb-runtime-engine.json').write_text(json.dumps({'engine':'fex','runtime':'1'*64}))
        return wine,bundle,prefix,m

    def test_identity_corruption_and_baseline_prefix_are_rejected(self):
        with tempfile.TemporaryDirectory() as t:
            wine,bundle,prefix,m=self.fixture(Path(t));self.assertEqual(fex.verify(wine,bundle,prefix)['host'],'aarch64')
            marker=prefix/'lsb-runtime-engine.json';marker.write_text(json.dumps({'engine':'box64','runtime':'1'*64}))
            with self.assertRaisesRegex(ValueError,'separate'):fex.verify(wine,bundle,prefix)
            marker.write_text(json.dumps({'engine':'fex','runtime':'1'*64}))
            (wine/'bin/wine').write_bytes(b'corrupt')
            with self.assertRaisesRegex(ValueError,'checksum'):fex.verify(wine,bundle,prefix)

    def test_matching_hashes_do_not_accept_x86_host_binary(self):
        with tempfile.TemporaryDirectory() as t:
            wine,bundle,prefix,m=self.fixture(Path(t));p=wine/'bin/wine';data=bytearray(p.read_bytes());struct.pack_into('<H',data,18,62);p.write_bytes(data)
            inventory=json.loads((wine/'lsb-fex.json').read_text());inventory['files']['bin/wine']=fex.digest(p)
            (wine/'lsb-fex.json').write_text(json.dumps(inventory));m['manifest_sha256']=fex.digest(wine/'lsb-fex.json');(bundle/'fex-bundle.json').write_text(json.dumps(m))
            with self.assertRaisesRegex(ValueError,'native ARM64'):fex.verify(wine,bundle,prefix)

    def test_host_migration_preserves_overrides_and_i386_files(self):
        with tempfile.TemporaryDirectory() as t:
            root=Path(t);wine=root/'wine';prefix=root/'prefix';system=prefix/'drive_c/windows/system32'
            system.mkdir(parents=True);native=wine/'lib/wine/aarch64-windows';native.mkdir(parents=True)
            def pe(machine,owned=True):
                b=bytearray(160);b[:2]=b'MZ';struct.pack_into('<I',b,60,128)
                if owned:b[64:81]=b'Wine builtin DLL\0'
                b[128:132]=b'PE\0\0';struct.pack_into('<H',b,132,machine);return bytes(b)
            original={}
            for name,machine,owned in [('wineboot.exe',0x8664,True),('kernel32.dll',0x8664,True),('custom.dll',0x8664,False),('i386.dll',0x14c,True)]:
                original[name]=pe(machine,owned);(system/name).write_bytes(original[name]);(native/name).write_bytes(pe(0xaa64))
            (system/'link.dll').symlink_to('/opt/wine/lib/wine/x86_64-windows/link.dll');(native/'link.dll').write_bytes(pe(0xaa64))
            (system/'external.dll').symlink_to('/client/keep.dll');(native/'external.dll').write_bytes(pe(0xaa64))
            files={'lib/wine/aarch64-windows/'+p.name:fex.digest(p) for p in native.iterdir()}
            (wine/'lsb-fex.json').write_text(json.dumps({'files':files}))
            changed=fex.refresh_host_builtins(wine,prefix)
            self.assertEqual({Path(n).name for n in changed},{'wineboot.exe','kernel32.dll','link.dll'})
            for name in ('custom.dll','i386.dll'):self.assertEqual((system/name).read_bytes(),original[name])
            self.assertEqual((system/'external.dll').readlink(),Path('/client/keep.dll'))
            self.assertEqual((system/'wineboot.exe').read_bytes(),pe(0xaa64))
            self.assertFalse((system/'link.dll').is_symlink())
            self.assertEqual(fex.refresh_host_builtins(wine,prefix),[])
            outside=root/'outside';outside.mkdir();(outside/'kernel32.dll').write_bytes(original['kernel32.dll'])
            import shutil
            shutil.rmtree(system);system.symlink_to(outside,target_is_directory=True)
            with self.assertRaisesRegex(ValueError,'cannot be links'):fex.refresh_host_builtins(wine,prefix)
            self.assertEqual((outside/'kernel32.dll').read_bytes(),original['kernel32.dll'])
