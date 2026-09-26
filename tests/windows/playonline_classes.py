"""Check real viewer class factories with synthetic DLLs; never create objects."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import winreg


CLASSES = (
    ('pol-app', 'app.dll', '{40555AAE-53AD-4ABC-AE65-8441755E7D69}'),
    ('pol-contents', 'PolContents.dll', '{62021866-976B-49A3-A18B-7A44869008A2}'),
    ('pol-contents-int', 'polcontentsINT.dll', '{3FC1EF9A-F346-413C-BB47-ED6F9A4BD52F}'),
)


def test_playonline_classes(source, check):
    view = winreg.KEY_WOW64_32KEY
    with tempfile.TemporaryDirectory(prefix='lsb-pol-classes-', dir='D:\\') as temporary:
        root = Path(temporary)
        folder = root/'Viewer class path ñ with spaces'
        folder.mkdir()
        receipt = root/'client-step.json'

        def invoke(*arguments):
            receipt.unlink(missing_ok=True)
            result = subprocess.run([str(source/'client-init-test.exe'), *arguments], cwd=root,
                                    capture_output=True, timeout=30)
            # client-init.c emits UTF-8 explicitly; a Windows locale decode
            # corrupts the accented fixture directory and breaks path equality.
            value = json.loads(receipt.read_text(encoding='utf-8'))
            # Keep a failed assertion actionable without exposing arbitrary
            # helper output, registry strings or the temporary executable path.
            numeric = {key: value[key] for key in
                       ('format', 'bits', 'hresult', 'win32_error', 'child_exit')
                       if type(value.get(key)) is int}
            numeric.update(exit_code=result.returncode,
                           ok=value.get('ok') is True,
                           operation_matches=value.get('operation') == arguments[0],
                           loaded_path_matches=value.get('loaded_path') == arguments[-1])
            print('Native COM fixture receipt:', json.dumps(numeric, sort_keys=True), flush=True)
            check(value['bits'] == 32 and value['operation'] == arguments[0],
                  'native class helper writes its 32-bit operation receipt')
            return result.returncode, value

        for label, name, clsid in CLASSES:
            path = folder/name
            shutil.copyfile(source/'pol-class-stub.dll', path)
            key_name = 'Software\\Classes\\CLSID\\'+clsid
            # Never overwrite an installation on a developer's machine. These
            # known identities must be absent on the disposable native runner.
            for hive in (winreg.HKEY_CURRENT_USER, winreg.HKEY_LOCAL_MACHINE):
                try:
                    with winreg.OpenKey(hive, key_name, 0, winreg.KEY_READ | view):
                        raise AssertionError('Viewer COM fixture requires an unused class registration')
                except FileNotFoundError:
                    pass
            created = False
            try:
                code, value = invoke('class', label, str(path))
                check(code == 1 and not value['ok'] and value['win32_error'] in (2, 3),
                      label+' missing class rejected before factory activation')
                created = True
                code, value = invoke('register', str(path))
                check(code == 0 and value['ok'], label+' fixture registers its real 32-bit class')
                code, value = invoke('class', label, str(path))
                check(code == 0 and value['ok'] and value['hresult'] == 0 and value['loaded_path'] == str(path),
                      label+' accepts exact registered DLL and callable class factory')
                check(Path(str(path)+'.factory').is_file() and Path(str(path)+'.released').is_file()
                      and not Path(str(path)+'.created').exists(),
                      label+' releases the factory without creating a viewer object')

                for suffix in ('.factory', '.released'):
                    Path(str(path)+suffix).unlink()
                with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, key_name+'\\InprocServer32', 0,
                                    winreg.KEY_SET_VALUE | view) as key:
                    winreg.SetValueEx(key, '', 0, winreg.REG_SZ, str(root/name))
                code, value = invoke('class', label, str(path))
                check(code == 1 and not value['ok'] and value['hresult'] == 0x80004005
                      and not Path(str(path)+'.factory').exists(),
                      label+' wrong DLL registration rejected before loading its factory')

                invoke('register', str(path))
                marker = Path(str(path)+'.fail-factory')
                marker.touch()
                code, value = invoke('class', label, str(path))
                check(code == 1 and not value['ok'] and value['hresult'] == 0x80004002,
                      label+' propagates the real class factory failure HRESULT')
                marker.unlink()
            finally:
                if created:
                    for key in (key_name+'\\InprocServer32', key_name):
                        try:
                            winreg.DeleteKeyEx(winreg.HKEY_LOCAL_MACHINE, key, view, 0)
                        except FileNotFoundError:
                            pass

        path = folder/'app.dll'
        for arguments in (
                ('pol-missing', str(path)), ('pol-app', str(folder/'polcontentsINT.dll')),
                ('pol-app', str(path), 'extra'), ('pol-app', r'C:\viewer\app.dll'),
                ('pol-app', r'D:\viewer\..\app.dll'), ('pol-app', r'D:\viewer.\app.dll'),
                ('pol-app', r'D:\viewer \app.dll'), ('pol-app', r'D:\viewer\\app.dll'),
                ('pol-app', r'D:\viewer\app.dll:stream'), ('pol-app', r'D:\viewer?\app.dll')):
            code, value = invoke('class', *arguments)
            check(code == 1 and not value['ok'] and value['hresult'] == 0x80070057,
                  'unknown class, mismatched DLL or noncanonical path rejected')
