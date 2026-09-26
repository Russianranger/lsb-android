"""Exercise real Win32 process/registry APIs with stub DLLs in an isolated HKCU key."""
import os, shutil, subprocess, tempfile, winreg
from pathlib import Path
from playonline import test_playonline
from playonline_classes import test_playonline_classes
source = Path(__file__).resolve().parents[2]/'out/windows-tests'
subprocess.run([str(source/'game-window.exe')],check=True,timeout=30)
subprocess.run([str(source/'version-registry.exe')],check=True,timeout=30)
subprocess.run([str(source/'viewer-version-registry.exe')],check=True,timeout=30)
subprocess.run([str(source/'game-math.exe')],check=True,timeout=30)
subprocess.run([str(source/'x87-flags.exe')],check=True,timeout=30)
checks = 0
def check(condition, message):
    global checks
    assert condition, message
    checks += 1
    print('PASS', message)
def fixture(base, region='US'):
    base.mkdir(parents=True)
    shutil.copy2(source/'LSB-FFXI-test.exe', base/'LSB-FFXI.exe')
    pol = base/'client/PlayOnlineViewer'; game = base/'client/FINAL FANTASY XI'
    core = pol/'viewer/com'/('polcoreeu.dll' if region == 'EU' else 'polcore.dll')
    core.parent.mkdir(parents=True); game.mkdir(parents=True)
    for target in [core, game/'FFXi.dll', game/'FFXiMain.dll']: shutil.copy2(source/'register-stub.dll', target)
    shutil.copy2(source/'loader-stub.exe', pol/'pol.exe'); shutil.copy2(source/'loader-stub.exe', game/'xiloader.exe')
    config = '[lsb]\nformat=1\npol=client\\PlayOnlineViewer\ngame=client\\FINAL FANTASY XI\ncore='+str(core.relative_to(base))+'\nloader=client\\FINAL FANTASY XI\\xiloader.exe\nhost=127.0.0.1\nregion='+region+'\n'
    (base/'lsb-launcher.ini').write_text(config)
    return pol, game, core
def run(base, action, **env):
    return subprocess.run([str(base/'LSB-FFXI.exe'), action], cwd=base.parent, env={**os.environ, **env}, timeout=90).returncode
def registry(region, suffix, name):
    branch = 'PlayOnline'+('' if region=='JP' else region)
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, 'Software\\LSBLauncherTests\\HKLM\\SOFTWARE\\'+branch+'\\'+suffix, 0, winreg.KEY_READ | winreg.KEY_WOW64_32KEY) as key:
        return winreg.QueryValueEx(key,name)[0]
def clear_key(root, path):
    try:
        with winreg.OpenKey(root, path, 0, winreg.KEY_ALL_ACCESS | winreg.KEY_WOW64_32KEY) as key:
            while True:
                try: child = winreg.EnumKey(key, 0)
                except OSError: break
                clear_key(root,path+'\\'+child)
        winreg.DeleteKeyEx(root,path,winreg.KEY_WOW64_32KEY,0)
    except FileNotFoundError: pass
try:
    test_playonline(source, check)
    test_playonline_classes(source, check)
    with tempfile.TemporaryDirectory(prefix='lsb-native-') as temp:
        root = Path(temp)
        for region in ['US','EU','JP']:
            base=root/('Game package ñ '+region); pol,game,core=fixture(base,region)
            check(run(base,'--repair')==0,region+' native registration succeeds from unrelated working directory')
            check(registry(region,'InstallFolder','1000')==str(pol),region+' writes viewer root to 32-bit registry')
            check(registry(region,'InstallFolder','0001')==str(game),region+' writes FFXI root')
            settings=('Square' if region=='JP' else 'SquareEnix')+'\\PlayOnlineViewer\\Settings'
            check(registry(region,settings,'Language')=={'JP':0,'US':1,'EU':2}[region],region+' language value')
            check(all(Path(str(p)+'.registered').exists() for p in [core,game/'FFXi.dll',game/'FFXiMain.dll']),region+' calls all three DLL registration exports')
            check(not (pol/'launch-observed.txt').exists(),'repair does not auto-launch game')
            check((base/'registry-before.txt').exists(),'records prior registry values')
            check(run(base,'--launch')==0,'launches child and waits for completion')
            observed=(pol/'launch-observed.txt').read_text(encoding='utf-16-le')
            check(observed.startswith(str(pol)+'\n') and '--server 127.0.0.1 --lang '+region in observed,'passes working directory, host and region without shell')
        base=root/'registration-failure';pol,game,core=fixture(base)
        check(run(base,'--repair',LSB_TEST_FAIL='FFXi.dll')!=0,'registration failure propagates')
        check(Path(str(core)+'.registered').exists() and not Path(str(game/'FFXiMain.dll')+'.registered').exists(),'stop before third DLL after second DLL fails')
        check('No game was launched' in (base/'lsb-launcher.log').read_text(),'registration failure explains partial state')
        check(run(base,'--launch',LSB_TEST_EXIT='37')==37,'returns child exit code')
        for replacement in ['client\\..\\escape.dll','client\\patchfiles\\polcore.dll','C:\\outside\\polcore.dll']:
            base=root/('invalid-'+str(checks));pol,game,core=fixture(base)
            p=base/'lsb-launcher.ini';p.write_text(p.read_text().replace('core=client\\PlayOnlineViewer\\viewer\\com\\polcore.dll','core='+replacement))
            check(run(base,'--repair')==2 and not (base/'registry-before.txt').exists(),'reject path before registry changes: '+replacement)
        base=root/'missing';pol,game,core=fixture(base);core.unlink()
        check(run(base,'--repair')!=0 and not (base/'registry-before.txt').exists(),'missing DLL fails before registry mutation')
        base=root/'host';pol,game,core=fixture(base);p=base/'lsb-launcher.ini';p.write_text(p.read_text().replace('host=127.0.0.1','host=127.0.0.1 --pass nope'))
        check(run(base,'--launch')==2 and not (pol/'launch-observed.txt').exists(),'reject injected host arguments')
    print('Completed',checks,'native Windows checks.')
finally:
    clear_key(winreg.HKEY_CURRENT_USER,'Software\\LSBLauncherTests')
