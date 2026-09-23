"""App-owned probe or staged client initialization; no managed import/server mounts."""
import hashlib
import json
import os
import re
from pathlib import Path
import secrets
import shutil
import signal
import struct
import subprocess
import threading
import time

SESSION=Path('/session')
PREFIX=Path('/prefix')
LOGS=Path('/logs')
BUNDLE=Path('/opt/lsb')
PROBE=Path('/probe')
STOP=False


def atomic(path, data):
    tmp=path.with_suffix('.new');tmp.write_text(json.dumps(data,indent=2)+'\n');tmp.replace(path)


def pe32(path):
    with path.open('rb') as f:
        if f.read(2)!=b'MZ':return False
        f.seek(60);offset=struct.unpack('<I',f.read(4))[0]
        if offset>16*1024*1024:return False
        f.seek(offset);return f.read(6)==b'PE\0\0\x4c\x01'


def verify_bundle(folder):
    m=json.loads((folder/'bundle.json').read_text())
    if m.get('format')!=1 or m.get('candidate')!='wine10-box64-0.4.4' or m.get('dxvk')!='2.5.3':raise ValueError('Unsupported runtime component bundle')
    required={'turnip.so','turnip-26.0.0.so','vulkan-probe','dxvk-d3d8.dll','dxvk-d3d9.dll','dxvk-2.7.1-d3d8.dll','dxvk-2.7.1-d3d9.dll','libasound_module_pcm_trasc.so','audio-bundle.json','wineserver','wineserver-patch.json'}
    if set(m.get('files',{}))!=required:raise ValueError('Runtime component inventory does not match')
    for n,digest in m['files'].items():
        p=folder/n
        if p.is_symlink() or hashlib.sha256(p.read_bytes()).hexdigest()!=digest:raise ValueError('Component checksum failed: '+n)
    for n in ('d3d8','d3d9','2.7.1-d3d8','2.7.1-d3d9'):
        if not pe32(folder/('dxvk-'+n+'.dll')):raise ValueError('DXVK DLL is not x86')
    return m


def validate_request(req):
    if req.get('format')!=1 or req.get('renderer') not in ('turnip26','turnip24','software'):raise ValueError('Unsupported runtime request')
    if set(req)-{'format','renderer','audio','session_id','action','display_profile','startup_trace','gamepad','display_fps','dxvk_hud','dxvk_diagnostics','native_surface','dxvk_version','shm_upload','turnip_sysmem','dxvk_two_compilers','engine'}:raise ValueError('Unexpected runtime request field')
    if req.get('engine','box64') not in ('box64','fex'):raise ValueError('Unsupported runtime engine')
    if req.get('display_fps',30) not in (30,60):raise ValueError('Unsupported display frame rate')
    if req.get('dxvk_version','2.5.3') not in ('2.5.3','2.7.1'):raise ValueError('Unsupported DXVK version')
    for key in ('gamepad','dxvk_hud','dxvk_diagnostics','native_surface','shm_upload','turnip_sysmem','dxvk_two_compilers'):
        if key in req and not isinstance(req[key],bool):raise ValueError('Unsupported '+key+' setting')
    if 'startup_trace' in req and (req.get('action')!='launch' or not isinstance(req['startup_trace'],bool)):raise ValueError('Unsupported startup trace setting')
    if 'display_profile' in req and (req.get('action')!='launch' or req['display_profile'] not in ('windowed720','windowed540','preserve','restore')):raise ValueError('Unsupported FFXI display setting')
    if req.get('action','probe') not in ('probe','initialize','installer','launch','check-launcher','repair-launcher','gamepad-config'):raise ValueError('Unsupported runtime action')
    if not isinstance(req.get('audio'),bool):raise ValueError('Invalid audio setting')
    if not isinstance(req.get('session_id'),str) or len(req['session_id'])!=36:raise ValueError('Missing session identity')
    return req


class PrivateEvents:
    """During login, arbitrary child text is never retained. Only fixed event names
    are emitted, including when credentials are quoted, split, ANSI or UTF-16."""
    # Longest/specific messages precede the generic rejection. Match a complete
    # bounded line, not arbitrary substrings inside echoed account/password text.
    TOKENS=[(b'failed to login. invalid username or password',b'login_invalid_credentials'),
            (b'failed to login. account already logged in',b'login_already_active'),
            (b'failed to login. expected xiloader version mismatch',b'login_version_mismatch'),
            (b'failed to login',b'login_rejected'),(b'failed to log in',b'login_rejected'),
            (b'incorrect password',b'login_invalid_credentials'),(b'invalid password',b'login_invalid_credentials'),
            (b'failed to connect',b'connection_failed'),(b'failed to initialize connection',b'connection_failed'),
            (b'failed to initialize listen server',b'listen_failed'),
            (b'failed to initialize instance of polcore',b'polcore_initialization_failed'),
            (b'failed to initialize instance of ffxi',b'ffxi_initialization_failed'),
            (b'failed to locate profileserverportaddress',b'polcore_patch_failed'),
            (b'failed to initialize com',b'com_initialization_failed'),
            (b'failed to detour function',b'loader_hook_failed'),
            (b'closing...',b'loader_closing'),
            (b'failed to obtain remote server information',b'connection_failed'),
            (b'mbedtls_net_connect failed',b'connection_failed'),
            (b'mbedtls_ssl_handshake returned',b'authentication_handshake_failed'),
            (b'remote failed to reply within the timeout',b'connection_timeout'),
            (b'bad json reply from remote',b'login_invalid_reply'),
            (b'xi_connect didn\'t send a proper reply command',b'login_invalid_reply'),
            (b'error from remote:',b'login_server_error'),
            (b'trust token rejected!',b'login_additional_authentication'),
            (b'please log in again and enter your otp code',b'login_additional_authentication'),
            (b'successfully logged in',b'login_message_seen'),(b'login successful',b'login_message_seen'),
            (b'autologin activated!',b'autologin_started'),(b'connected to server!',b'server_connected'),
            (b'launching final fantasy',b'game_launch_message_seen'),
            (b'unhandled exception',b'windows_exception'),(b'err:module:import_dll',b'dll_import_error')]
    def __init__(self):
        from startup_diagnostics import StartupDiagnostics
        self.tail=b'';self.seen=set();self.discard=False;self.lock=threading.Lock()
        self.diagnostics=StartupDiagnostics()
    def snapshot(self):
        with self.lock:return sorted(e.decode('ascii') for e in self.seen)
    def line(self,data):
        data=re.sub(rb'\x1b\[[0-?]*[ -/]*[@-~]',b'',data).strip().lower()
        self.diagnostics.line(data)
        data=re.sub(rb'^\[\d{2}/\d{2}/\d{2,4} \d{2}:\d{2}:\d{2}\]\s*',b'',data)
        for token,event in self.TOKENS:
            if data.startswith(token):
                with self.lock:
                    if event in self.seen:return b''
                    self.seen.add(event)
                return event+b'\n'
        return b''
    def feed(self,chunk):
        out=[]
        # Removing NULs also recognizes fixed ASCII messages printed as UTF-16.
        parts=chunk.replace(b'\0',b'').split(b'\n')
        for i,part in enumerate(parts):
            if not self.discard:
                if len(self.tail)+len(part)>4096:self.tail=b'';self.discard=True
                else:self.tail+=part
            if i<len(parts)-1:
                if not self.discard:out.append(self.line(self.tail))
                self.tail=b'';self.discard=False
        return b''.join(out)
    def finish(self):
        out=b'' if self.discard else self.line(self.tail)
        self.tail=b'';self.discard=False;return out


class BoundedLog:
    def __init__(self,path,private=lambda:False):self.path=path;self.thread=None;self.private=private;self.events=PrivateEvents()
    def pump(self,stream):
        limit=2*1024*1024;written=0
        with self.path.open('wb') as out:
            while True:
                chunk=stream.read1(32768)
                if not chunk:break
                if self.private():chunk=self.events.feed(chunk)
                if written+len(chunk)>limit:
                    out.flush();shutil.copyfile(self.path,self.path.with_suffix('.previous.log'));out.seek(0);out.truncate();written=0
                out.write(chunk);out.flush();written+=len(chunk)
            if self.private():out.write(self.events.finish());out.flush()
        stream.close()
    def start(self,stream):
        self.thread=threading.Thread(target=self.pump,args=(stream,),daemon=True);self.thread.start()


def graphics_hud(req):
    if req.get('dxvk_diagnostics',False):return 'devinfo,fps,frametimes,compiler,cs'
    return 'devinfo,fps' if req.get('dxvk_hud',True) else ''


class Stopped(Exception):pass


class Supervisor:
    def __init__(self,req):
        self.req=validate_request(req);self.children=[];self.logs=[];self.private_output=False
        self.state={'format':1,'phase':'starting','session_id':req['session_id'],'runtime_candidate':'Wine 10 WoW64 / Box64 0.4.4','renderer_requested':req['renderer'],'started_at':time.time(),'game_files_mounted':req.get('action','probe')!='probe','action':req.get('action','probe')}
        self.env=dict(os.environ,HOME='/root',USER='root',DISPLAY=':7',XAUTHORITY=str(SESSION/'Xauthority'),
            WINEPREFIX=str(PREFIX),WINEARCH='win64',WINEDEBUG='-all,+timestamp,+pid,err+all,trace+loaddll',
            WINEDLLOVERRIDES='winemenubuilder,mscoree,mshtml,winegstreamer=',
            BOX64_PATH='/opt/wine/bin',BOX64_LD_LIBRARY_PATH='/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu:/opt/wine/lib/wine/x86_64-unix',
            BOX64_DYNAREC_STRONGMEM='1',BOX64_DYNAREC_BIGBLOCK='2',BOX64_DYNAREC_SAFEFLAGS='1',BOX64_MAXCPU='0',
            BOX64_RCFILE=str(SESSION/'box64.rc'),BOX64_LOG='1',BOX64_NOBANNER='0',
            LIBGL_ALWAYS_SOFTWARE='1',GALLIUM_DRIVER='llvmpipe',LP_NUM_THREADS='4',WINE_D3D_CONFIG='csmt=1')
        self.engine=self.req.get('engine','box64')
        self.state['runtime_engine']=self.engine
        if self.engine=='fex':
            self.env={k:v for k,v in self.env.items() if not k.startswith('BOX64_')}
            self.state['runtime_candidate']='Wine 10 native ARM64 / FEX 2510 WoW64'
    def wine_command(self,*args):
        return ([] if self.engine=='fex' else ['/usr/local/bin/box64'])+['/opt/wine/bin/wine',*args]
    def server_command(self,*args):
        return ([] if self.engine=='fex' else ['/usr/local/bin/box64'])+['/opt/wine/bin/wineserver',*args]
    def status(self,phase=None,**fields):
        if phase:self.state['phase']=phase
        self.state.update(fields);atomic(SESSION/'status.json',self.state);atomic(LOGS/'runtime-state.json',self.state)
    def stopped(self):
        if STOP or (SESSION/'stop').exists():raise Stopped()
    def spawn(self,args,name,env=None,pipe_input=False,fixed_output=False):
        proc=subprocess.Popen(args,env=env or self.env,cwd=PROBE,stdin=subprocess.PIPE if pipe_input else subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,start_new_session=True)
        writer=BoundedLog(LOGS/name,lambda:self.private_output and not fixed_output);writer.start(proc.stdout);self.logs.append(writer);self.children.append(proc);return proc
    def wait(self,proc,timeout,label,accepted=(0,)):
        deadline=time.monotonic()+timeout
        while proc.poll() is None:
            self.stopped()
            if time.monotonic()>deadline:raise RuntimeError(label+' timed out; export Diagnostics')
            time.sleep(.15)
        if proc.returncode not in accepted:raise RuntimeError(label+' exited with code '+str(proc.returncode)+'; export Diagnostics')
    def wine(self,args,timeout=180,label='Wine setup'):
        self.wait(self.spawn(self.wine_command(*args),'wine-setup.log'),timeout,label)
    def graphics(self,bundle):
        if self.req['renderer']=='software':
            self.env['WINEDLLOVERRIDES']+=';d3d8,d3d9=b'
            self.status(graphics='WineD3D / software diagnostic',hardware_verified=False);return
        driver='turnip-26.0.0.so' if self.req['renderer']=='turnip26' else 'turnip.so'
        atomic(SESSION/'turnip-icd.json',{'file_format_version':'1.0.0','ICD':{'library_path':str(BUNDLE/driver),'api_version':'1.3.0'}})
        self.env.update(VK_ICD_FILENAMES=str(SESSION/'turnip-icd.json'),VK_DRIVER_FILES=str(SESSION/'turnip-icd.json'),MESA_VK_WSI_DEBUG='sw',
            DXVK_LOG_LEVEL='info',DXVK_LOG_PATH=str(LOGS),DXVK_HUD=graphics_hud(self.req),DXVK_STATE_CACHE_PATH=str(PREFIX/'lsb-cache'),MESA_SHADER_CACHE_DIR=str(PREFIX/'lsb-cache'))
        (PREFIX/'lsb-cache').mkdir(exist_ok=True)
        command=[str(BUNDLE/'vulkan-probe')]
        # CI-only environment injection. Android uses env -i and never exposes this switch.
        test_icd=os.environ.get('LSB_TEST_VULKAN_ICD')
        if test_icd:self.env.update(VK_ICD_FILENAMES=test_icd,VK_DRIVER_FILES=test_icd);command+=['--allow-software']
        p=self.spawn(command,'vulkan.log');self.wait(p,35,'Vulkan device and presentation check')
        self.logs[-1].thread.join(3)
        records=[]
        for line in (LOGS/'vulkan.log').read_text(errors='replace').splitlines():
            try:records.append(json.loads(line))
            except ValueError:pass
        if not records:raise RuntimeError('Vulkan probe did not report its renderer')
        report=records[-1]
        if report.get('presentation_frames')!=3 or report.get('api_version',0)<(1<<22|3<<12):raise RuntimeError('Vulkan presentation or version check failed')
        if not test_icd and (report.get('software') is not False or report.get('vendor_id')!=0x5143 or report.get('driver_id')!=18):raise RuntimeError('Qualcomm hardware was not verified; no software fallback was selected')
        self.state['graphics_hud']=self.env['DXVK_HUD'];self.state['vulkan']=report;self.status(graphics='DXVK 2.5.3 / '+report.get('device','unknown'),hardware_verified=not bool(test_icd),driver_sha256=bundle['files'][driver])
        self.env['WINEDLLOVERRIDES']+=';d3d8,d3d9=n'
    def configure_upload(self):
        self.state['shm_upload_requested']=self.req.get('shm_upload',False)
        if not self.req.get('shm_upload',False):
            self.status(shm_upload_active=False);return
        previous=self.env.get('LD_PRELOAD','')
        proc=None
        try:
            manifest=json.loads((BUNDLE/'presentation-bundle.json').read_text())
            for name in ('liblsb-x11-upload.so','x11-upload-check'):
                path=BUNDLE/name
                if path.is_symlink() or hashlib.sha256(path.read_bytes()).hexdigest()!=manifest.get('upload_files',{}).get(name):raise ValueError('Upload component checksum failed')
            self.env.update(LD_PRELOAD=' '.join(filter(None,[previous,str(BUNDLE/'liblsb-x11-upload.so')])),LSB_X11_UPLOAD='1',LSB_X11_UPLOAD_STATS=str(LOGS))
            proc=self.spawn([str(BUNDLE/'x11-upload-check')],'wsi-check.log',fixed_output=True)
            self.wait(proc,20,'Shared-memory upload check')
            counters=struct.unpack('<12Q',(LOGS/('wsi-upload-'+str(proc.pid)+'.bin')).read_bytes())
            if counters[0]!=0x4c534257534931 or counters[2]<36 or counters[7]:raise ValueError('X11 shared-memory upload unavailable')
            self.status(shm_upload_active=True,shm_upload_preflight_frames=counters[2])
        except (OSError,ValueError,RuntimeError,struct.error) as error:
            if proc is not None and proc.poll() is None:
                os.killpg(proc.pid,signal.SIGKILL);proc.wait()
            if previous:self.env['LD_PRELOAD']=previous
            else:self.env.pop('LD_PRELOAD',None)
            self.env.pop('LSB_X11_UPLOAD',None);self.env.pop('LSB_X11_UPLOAD_STATS',None)
            self.status(shm_upload_active=False,shm_upload_fallback=str(error))

    def install_dxvk(self,version):
        for name in ('d3d8','d3d9'):
            filename='dxvk-'+('2.7.1-' if version=='2.7.1' else '')+name+'.dll'
            dest=PREFIX/'drive_c/windows/syswow64'/(name+'.dll')
            tmp=dest.with_suffix('.lsb-new');shutil.copyfile(BUNDLE/filename,tmp);tmp.replace(dest)
        cache=PREFIX/'lsb-cache'
        if version!='2.5.3':cache=cache/('dxvk-'+version)
        cache.mkdir(exist_ok=True)
        self.env['DXVK_STATE_CACHE_PATH']=str(cache)

    def select_dxvk(self,bundle):
        requested=self.req.get('dxvk_version','2.5.3');version=requested
        self.install_dxvk(version)
        if version=='2.7.1':
            proc=None
            try:
                proc=self.spawn(self.wine_command('P:\\graphics-check.exe'),'dxvk-compatibility.log')
                self.wait(proc,45,'DXVK 2.7.1 draw and presentation check')
                self.state['dxvk_compatibility']='eight D3D8 draw/present frames passed'
            except RuntimeError as error:
                if proc is not None and proc.poll() is None:
                    os.killpg(proc.pid,signal.SIGKILL);proc.wait()
                version='2.5.3';self.install_dxvk(version)
                self.state['dxvk_fallback']=str(error)
        self.status(dxvk_requested=requested,dxvk_selected=version,
            dxvk_dll_sha256={name:bundle['files']['dxvk-'+('2.7.1-' if version=='2.7.1' else '')+name+'.dll'] for name in ('d3d8','d3d9')},
            graphics='DXVK '+version+' / '+self.state['vulkan'].get('device','unknown'))

    def configure_graphics_tuning(self):
        # Opt-in controls for the already selected renderer, never a DLL/driver
        # replacement. The original environment is restored together on failure.
        requested={key:self.req.get(key,False) for key in ('turnip_sysmem','dxvk_two_compilers')}
        report={'requested':requested,'active':dict.fromkeys(requested,False)}
        active={'turnip_sysmem':requested['turnip_sysmem'] and self.req['renderer']=='turnip26',
                'dxvk_two_compilers':requested['dxvk_two_compilers'] and self.req['renderer']!='software'}
        if requested['turnip_sysmem'] and self.req['renderer']!='turnip26':
            report['turnip_note']='System-memory experiment applies only to Turnip 26'
        if not any(active.values()):
            self.status(graphics_tuning=report);return
        previous={key:self.env.get(key) for key in ('TU_DEBUG','DXVK_CONFIG')}
        proc=None
        try:
            # No noconform/nosync/relaxed-memory flags. Sysmem still renders on
            # Adreno; it bypasses Turnip's tile-memory render-pass selection.
            if active['turnip_sysmem']:self.env['TU_DEBUG']='sysmem'
            if active['dxvk_two_compilers']:
                self.env['DXVK_CONFIG']=';'.join(filter(None,[previous['DXVK_CONFIG'],'dxvk.numCompilerThreads = 2']))
            proc=self.spawn(self.wine_command('P:\\graphics-check.exe'),'graphics-tuning.log',fixed_output=True)
            self.wait(proc,45,'Graphics tuning draw and presentation check')
            self.logs[-1].thread.join(3)
            if active['dxvk_two_compilers'] and 'DXVK: Using 2 compiler threads' not in (LOGS/'graphics-tuning.log').read_text(errors='replace'):
                raise ValueError('DXVK did not confirm two compiler workers')
            report.update(active=active,check='eight D3D8 draw/present frames passed',
                          turnip_debug='sysmem' if active['turnip_sysmem'] else 'unchanged',
                          compiler_threads=2 if active['dxvk_two_compilers'] else 'automatic')
        except (OSError,ValueError,RuntimeError) as error:
            if proc is not None and proc.poll() is None:
                os.killpg(proc.pid,signal.SIGKILL);proc.wait()
            for key,value in previous.items():
                if value is None:self.env.pop(key,None)
                else:self.env[key]=value
            report['fallback']=str(error)
        self.status(graphics_tuning=report)

    def start_native_surface(self):
        if not self.req.get('native_surface',False):return
        try:
            import json
            executable=BUNDLE/'x11-frame-bridge'
            manifest=json.loads((BUNDLE/'presentation-bundle.json').read_text())
            if manifest.get('format')!=1 or executable.is_symlink() or hashlib.sha256(executable.read_bytes()).hexdigest()!=manifest.get('sha256'):
                raise ValueError('Native display component checksum failed')
            self.spawn([str(executable),str(SESSION/'native-display.sock'),str(SESSION/'framebuffer.bin'),str(self.req.get('display_fps',30))], 'native-display.log',fixed_output=True)
            self.status(native_surface_requested=True)
        except (OSError,ValueError,KeyError) as error:
            # The Android viewer falls back to the existing RFB path. A missing
            # optional display helper must never stop the working game launch.
            self.status(native_surface_requested=True,native_surface_fallback=str(error))

    def start(self):
        for p in (SESSION,PREFIX,LOGS):p.mkdir(parents=True,exist_ok=True)
        self.status();bundle=verify_bundle(BUNDLE)
        if self.engine=='fex':
            from fex_runtime import verify,select_translator
            self.state['fex_runtime']=verify(Path('/opt/wine'),BUNDLE,PREFIX)
            select_translator(PREFIX)
        (SESSION/'box64.rc').write_text('[wine]\nBOX64_MAXCPU=0\n[wine64]\nBOX64_MAXCPU=0\n[explorer.exe]\nBOX64_DYNAREC_BIGBLOCK=0\n')
        if self.req['audio']:
            if not (SESSION/'audio.sock').is_socket():raise RuntimeError('Android audio bridge is unavailable')
            (SESSION/'asound.conf').write_text('</usr/share/alsa/alsa.conf>\npcm_type.trasc { lib "/opt/lsb/libasound_module_pcm_trasc.so" }\npcm.trasc { type trasc }\npcm.!default { type plug slave { pcm "trasc" format S16_LE rate 48000 channels 2 } }\n')
            self.env.update(ALSA_CONFIG_PATH=str(SESSION/'asound.conf'),TRASC_AUDIO_SOCKET=str(SESSION/'audio.sock'))
            self.env['WINEDLLOVERRIDES']+=';winepulse.drv=d'
        else:self.env['WINEDLLOVERRIDES']+=';winepulse.drv,winealsa.drv=d'
        (SESSION/'Xauthority').touch(mode=0o600)
        self.wait(self.spawn(['xauth','-f',str(SESSION/'Xauthority'),'add',':7','.',secrets.token_hex(16)],'xauth.log'),10,'X authentication')
        x=self.spawn(['Xtigervnc',':7','-geometry','960x540' if self.req.get('display_profile')=='windowed540' else '1280x720','-depth','24','-rfbport','-1','-rfbunixpath',str(SESSION/'display.sock'),'-rfbunixmode','0600','-SecurityTypes','None','-nolisten','tcp','-auth',str(SESSION/'Xauthority'),'-AlwaysShared','-ZlibLevel','1','-FrameRate',str(self.req.get('display_fps',30)),'-desktop','LSB runtime probe'],'display.log')
        for _ in range(200):
            self.stopped()
            if x.poll() is not None:raise RuntimeError('Display startup failed; see display.log')
            if (SESSION/'display.sock').exists():break
            time.sleep(.1)
        else:raise RuntimeError('Display socket did not appear')
        self.start_native_surface()
        if self.req.get('gamepad',False):
            self.env.update(LD_PRELOAD=str(BUNDLE/'liblsb-gamepad.so'),LSB_GAMEPAD_STATE=str(SESSION/'gamepad.bin'))
        self.status('preparing_prefix',display_ready=True)
        # Setup uses Wine's own defaults, without loading native DXVK before system files exist.
        marker=PREFIX/'lsb-prefix-ready.json'
        signature={'format':1,'runtime':'08c639c26506dc6fbd15464bec475337087bb23cb7c0c5ace2db5240ee36424f'}
        if self.engine=='fex':signature={'format':1,'runtime':self.state['fex_runtime']['sha256']}
        ready=False
        try:ready=json.loads(marker.read_text())==signature
        except (OSError,ValueError):pass
        if self.req.get('action','probe')=='probe':marker.unlink(missing_ok=True)
        if self.engine=='fex' and not ready:
            from fex_runtime import refresh_host_builtins
            self.state['fex_migrated_host_files']=refresh_host_builtins(Path('/opt/wine'),PREFIX)
        self.wine(['wineboot','-i' if ready else '-u'],240,'Fresh Windows prefix initialization')
        for name in ('ntdll.dll','kernel32.dll','kernelbase.dll'):
            if not pe32(PREFIX/'drive_c/windows/syswow64'/name):raise RuntimeError('Missing 32-bit Windows system file: '+name)
        devices=PREFIX/'dosdevices';devices.mkdir(exist_ok=True)
        mappings=[('p:',PROBE),('z:',Path('/'))]
        if self.req.get('action','probe')!='probe':mappings.append(('d:',Path('/client')))
        for name,target in mappings:
            p=devices/name
            if p.is_symlink():p.unlink()
            if p.exists():raise RuntimeError('Reserved probe drive is occupied')
            p.symlink_to(target)
        if self.engine=='fex':
            from fex_runtime import check
            check(self)
            # Conversion is complete only after native-host/PE32 execution proof.
            # Keep later launches on wineboot -i, preserving the copied settings.
            if not ready:atomic(marker,signature)
        self.status('checking_graphics');self.graphics(bundle)
        if self.req['renderer']!='software':
            self.configure_upload()
            self.select_dxvk(bundle)
        self.configure_graphics_tuning()
        if self.req.get('action')=='gamepad-config':
            from client_setup import validate_manifest,client_path,windows_path
            manifest=json.loads((SESSION/'client-manifest.json').read_text());validate_manifest(manifest)
            area={'US':'ToolsUS','EU':'ToolsEU','JP':'Tools'}[manifest['region']]
            folder=client_path(manifest['game'])/area
            files=list(folder.glob('*.exe')) if folder.is_dir() else []
            choices=[p for p in files if 'padconfig' in p.name.lower()]
            if not choices:choices=[p for p in files if 'config' in p.name.lower()]
            args=[windows_path(choices[0].relative_to('/client').as_posix())] if choices else ['control','joy.cpl']
            self.status('configuring_controller',message='FFXI controller configuration' if choices else 'Windows gamepad calibration (FFXI config executable not found)')
            self.wait(self.spawn(self.wine_command(*args),'gamepad-config.log'),3600,'Controller configuration')
            self.status('completed');return
        if self.req.get('action') in ('launch','check-launcher'):
            from client_launch import check, run
            self.status(prefix_system_files_verified=True)
            if self.req['action']=='launch':run(self,x)
            else:check(self);self.status('completed')
            return
        if self.req.get('action','probe')!='probe':
            self.status('initializing_client',prefix_system_files_verified=True)
            from client_setup import initialize
            initialize(self)
            if self.req['action']=='repair-launcher':
                from client_launch import check
                check(self);self.status('completed')
            atomic(marker,signature)
            return
        self.status('starting_probe',prefix_system_files_verified=True)
        args=self.wine_command(r'P:\runtime-probe.exe')
        if os.environ.get('LSB_TEST_AUTOCLOSE')=='1':args+=['--autotest']
        p=self.spawn(args,'wine-probe.log');self.status('probe_running')
        last=None
        while p.poll() is None:
            self.stopped()
            if x.poll() is not None:raise RuntimeError('Display exited during the probe')
            try:
                evidence=json.loads((SESSION/'probe.json').read_text())
                if evidence!=last:self.status(probe=evidence);last=evidence
            except (OSError,ValueError):pass
            time.sleep(.4)
        for writer in self.logs:writer.thread.join(.1)
        evidence=json.loads((SESSION/'probe.json').read_text()) if (SESSION/'probe.json').exists() else {}
        passed=p.returncode==0 and evidence.get('bits')==32 and evidence.get('registry32') is True and evidence.get('com') is True and evidence.get('d3d8_frames',0)>=10 and evidence.get('hresult')==0
        self.status(probe=evidence,probe_exit=p.returncode,automatic_checks_passed=passed)
        if not passed:raise RuntimeError('Windows probe did not complete all registry/COM/D3D8 checks; export Diagnostics')
        atomic(marker,signature);self.status('completed')
    def stop(self):
        try:subprocess.run(self.server_command('-k'),env=self.env,timeout=12,stdin=subprocess.DEVNULL,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        except (OSError,subprocess.TimeoutExpired):pass
        for p in reversed(self.children):
            if p.poll() is None:
                try:os.killpg(p.pid,signal.SIGTERM)
                except ProcessLookupError:pass
        for p in self.children:
            try:p.wait(timeout=3)
            except subprocess.TimeoutExpired:
                try:os.killpg(p.pid,signal.SIGKILL)
                except ProcessLookupError:pass
                p.wait(timeout=3)
        for writer in self.logs:writer.thread.join(2)
        for name in ('display.sock','native-display.sock','framebuffer.bin'):
            (SESSION/name).unlink(missing_ok=True)
        self.status(display_ready=False,ended_at=time.time())


def main():
    def stop(*_):
        global STOP;STOP=True
    signal.signal(signal.SIGTERM,stop);signal.signal(signal.SIGINT,stop)
    s=Supervisor(json.loads((SESSION/'request.json').read_text()))
    try:s.start()
    except Stopped:
        from client_launch import finish_report
        finish_report(s,'stopped');s.status('stopped')
    except Exception as e:
        from client_launch import finish_report
        finish_report(s,'error');s.status('error',error=str(e));raise
    finally:s.stop()

if __name__=='__main__':main()
