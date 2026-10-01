"""Native Wine / FEX WoW64 selection. Never operate on the Box64 prefix."""
import hashlib,json,re,struct,shutil
from pathlib import Path

CANDIDATE='wine10-arm64-fex2510'
KEY_FILES=('bin/wine','bin/wineserver','lib/wine/aarch64-unix/ntdll.so','lib/wine/aarch64-windows/libwow64fex.dll')

def digest(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def verify(wine,bundle,prefix):
    expected=json.loads((bundle/'fex-bundle.json').read_text())
    manifest=wine/'lsb-fex.json'
    if expected.get('format')!=1 or expected.get('candidate')!=CANDIDATE or digest(manifest)!=expected.get('manifest_sha256'):
        raise ValueError('FEX runtime identity mismatch; reinstall the FEX runtime')
    marker=json.loads((prefix/'lsb-runtime-engine.json').read_text())
    if marker!={'engine':'fex','runtime':expected['sha256']}:
        raise ValueError('FEX requires its separate Windows environment')
    inventory=json.loads(manifest.read_text())
    for name in KEY_FILES:
        p=wine/name
        if p.is_symlink() or digest(p)!=inventory['files'].get(name):raise ValueError('FEX component checksum failed: '+name)
        with p.open('rb') as f:
            h=f.read(64)
            if name.endswith('.dll'):
                if h[:2]!=b'MZ':raise ValueError('Invalid FEX DLL')
                f.seek(struct.unpack_from('<I',h,60)[0])
                if f.read(6)!=b'PE\0\0\x64\xaa':raise ValueError('FEX DLL must be ARM64')
            elif h[:5]!=b'\x7fELF\x02' or struct.unpack_from('<H',h,18)[0]!=183:
                raise ValueError('FEX Wine host must be native ARM64')
    result={k:expected[k] for k in ('candidate','sha256','wine_commit','fex_commit','guest','host')}
    result['wine_backports']=expected.get('wine_backports',[])
    result['fex_patches']=expected.get('fex_patches',[])
    return result

def select_translator(prefix):
    # Wine 10 uses the default value of the x86 subkey, not a value named x86.
    # Edit only the isolated stopped prefix; wine.inf supplies this for new ones.
    p=prefix/'system.reg'
    if not p.exists():return
    text=p.read_text();key=r'Software\\Microsoft\\Wow64\\x86'
    pattern=re.compile(r'^\['+re.escape(key)+r'\][^\n]*\n(?P<body>.*?)(?=^\[|\Z)',re.M|re.S)
    match=pattern.search(text)
    value='@="libwow64fex.dll"\n'
    if match:
        body=re.sub(r'^@=.*\n?', '',match.group('body'),flags=re.M)
        text=text[:match.start('body')]+value+body+text[match.end('body'):]
    else:text+='\n['+key+']\n'+value
    temp=p.with_suffix('.fex-new');temp.write_text(text);temp.replace(p)

def refresh_host_builtins(wine,prefix):
    """Replace only Wine-owned AMD64 system files in the isolated copied prefix.

    Wine's loader selects builtins by the old PE's machine field; wineboot
    cannot repair an AMD64 wineboot/kernel32 before it can itself start.
    Native overrides, all I386 files, registry and client files stay intact.
    """
    inventory=json.loads((wine/'lsb-fex.json').read_text())['files']
    windows=prefix/'drive_c/windows';changed=[]
    if any(p.is_symlink() for p in (prefix/'drive_c',windows,windows/'system32')):
        raise ValueError('FEX system directories cannot be links')
    if not windows.is_dir():return changed
    paths=list(windows.iterdir())
    if (windows/'system32').is_dir():paths+=list((windows/'system32').rglob('*'))
    for target in paths:
        if target.is_dir():continue
        owned=False
        if target.is_symlink():
            link=target.readlink()
            owned=link.parent==Path('/opt/wine/lib/wine/x86_64-windows') and link.name==target.name
        elif target.is_file():
            with target.open('rb') as f:
                h=f.read(96)
                if len(h)==96 and h[:2]==b'MZ' and h[64:].startswith((b'Wine builtin DLL\0',b'Wine placeholder DLL\0')):
                    f.seek(struct.unpack_from('<I',h,60)[0]);owned=f.read(6)==b'PE\0\0\x64\x86'
        if not owned:continue
        name='lib/wine/aarch64-windows/'+target.name
        source=wine/name
        if name not in inventory:continue # Obsolete component: let wineboot handle it.
        if source.is_symlink() or digest(source)!=inventory[name]:raise ValueError('FEX migration component checksum failed: '+target.name)
        temp=target.with_name(target.name+'.fex-new');temp.unlink(missing_ok=True)
        shutil.copyfile(source,temp);temp.replace(target)
        changed.append(str(target.relative_to(prefix)))
    return changed

ENV_KEYS=('FEX_X87REDUCEDPRECISION','FEX_X87STRICTREDUCEDPRECISION',
          'DXVK_HUD','DXVK_CONFIG','MESA_VK_WSI_DEBUG','TU_DEBUG','WINEDLLOVERRIDES')

def environment_hash(env):
    value=14695981039346656037
    for key in ENV_KEYS:
        for byte in (key+'='+env.get(key,'')+'\0').encode('utf-8'):
            value=((value^byte)*1099511628211)&0xffffffffffffffff
    return f'{value:016x}'

def cpu_features(text):
    rows=re.findall(r'^LSB_FEX_FEATURES child=([01]) three_cpuid=([01]) three_api=([01]) sse2_cpuid=([01]) sse2_api=([01]) PASS\s*$',text,re.M)
    if len(rows)!=2 or {r[0] for r in rows}!={'0','1'} or rows[0][1:]!=rows[1][1:]:
        raise RuntimeError('FEX parent/child CPU features were not verified')
    _,three,api,sse2,api_sse2=rows[0]
    if three!=api or sse2!='1' or api_sse2!='1':
        raise RuntimeError('FEX Windows CPU features disagree with guest instructions')
    return dict(three_dnow=three=='1',sse2=True,windows_api_matches_cpuid=True,parent_child_agree=True)

def check(supervisor,env=None,launch=False):
    env=supervisor.env if env is None else env
    mode='1' if supervisor.req.get('fex_x87',False) else '0'
    expected=environment_hash(env)
    name='fex-launch-check.log' if launch else 'fex-check.log'
    p=supervisor.spawn(supervisor.wine_command(r'P:\fex-check.exe',mode,expected),name,env=env,fixed_output=True)
    supervisor.wait(p,45,'FEX 32-bit execution check')
    supervisor.logs[-1].thread.join(3)
    text=(Path('/logs')/name).read_text(errors='replace')
    if 'LSB_FEX_CHECK bits=32 process=014c native=aa64 PASS' not in text or not re.search(r'Loaded .*libwow64fex\.dll.*builtin',text,re.I):
        raise RuntimeError('FEX execution was not verified; select Box64 to return to the working runtime')
    host=re.search(r'LSB_FEX_HOST isar0=([0-9a-f]{16}) isar1=([0-9a-f]{16}) ctr=([0-9a-f]{16})',text)
    if not host:raise RuntimeError('FEX host CPU features were not verified')
    if f'LSB_FEX_ENV child=1 hash={expected} PASS' not in text:
        raise RuntimeError('FEX Windows child did not inherit the selected environment')
    features=cpu_features(text)
    supervisor.status(**{'fex_launch_cpu_features' if launch else 'fex_cpu_features':features})
    arithmetic=re.search(r'LSB_FEX_ARITH mode=([01]) iterations=100000 elapsed_us=(\d+) qpc_frequency=(\d+) sleep_us=(\d+) PASS',text)
    if not arithmetic or arithmetic[1]!=mode:
        raise RuntimeError('FEX arithmetic mode was not verified; turn off faster x87 or select Box64')
    report={'mode':'strict64' if mode=='1' else 'full80','iterations':100000,
            'elapsed_us':int(arithmetic[2]),'qpc_frequency':int(arithmetic[3]),'sleep_us':int(arithmetic[4]),
            'child_environment_verified':True,'environment_hash':expected}
    supervisor.status(**{'fex_launch_arithmetic' if launch else 'fex_arithmetic':report})
    supervisor.status(fex_execution_verified=True,fex_host_registers=dict(zip(('isar0','isar1','ctr'),host.groups())))
