"""Isolated, generation-based LSB deployment. Never touches Termux or client files."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid

STATE=Path('/state')
RUN=Path('/server-run')
LOGS=Path('/server-logs')
INPUT=Path('/input')
PROCESSES=('xi_world','xi_search','xi_map','xi_connect')
DB_PORT=13306
STARTUP_TIMEOUT_SECONDS=30*60
children=[]
secret_values=[]

def atomic(path, value):
    path.parent.mkdir(parents=True,exist_ok=True)
    temp=path.with_name(path.name+'.new')
    with temp.open('w') as f:json.dump(value,f,indent=2);f.flush();os.fsync(f.fileno())
    temp.replace(path)

def status(phase, message, **fields):
    atomic(RUN/'status.json',dict(phase=phase,message=message,**fields))

def cancelled():
    if (RUN/'stop').exists():raise InterruptedError('Server operation stopped')

def checked_name(name):
    if not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,47}',name or ''):raise ValueError('Use a database name with letters, digits and underscores')
    if name.lower() in ('mysql','sys','information_schema','performance_schema'):raise ValueError('Choose a game database name')
    return name

def source_root(root):
    candidates=[]
    def walk(p, depth):
        if (p/'CMakeLists.txt').is_file() and (p/'src').is_dir() and (p/'sql').is_dir():candidates.append(p);return
        if depth:
            for child in p.iterdir():
                if child.is_dir() and not child.is_symlink() and child.name not in ('.git','build','ext'):walk(child,depth-1)
    walk(root,8)
    if len(candidates)!=1:raise ValueError('Import exactly one server folder with CMakeLists.txt, src/ and sql/')
    return candidates[0]

def source_info(root):
    files={}
    for name in ('CMakeLists.txt','settings/default/login.lua','settings/login.lua'):
        path=root/name
        if path.is_file():files[name]=hashlib.sha256(path.read_bytes()).hexdigest()
    version='unknown'
    for name in ('settings/default/login.lua','settings/login.lua'):
        path=root/name
        if path.is_file():
            match=re.search(r"^\s*CLIENT_VER\s*=\s*['\"]([^'\"]+)['\"]",path.read_text(errors='replace'),re.M)
            if match:version=match[1]
    return dict(expected_client=version,source_hashes=files,meshes={name:(root/name).is_dir() and any((root/name).iterdir()) for name in ('navmeshes','ximeshes')})

def command(args, cwd=None, stdin=None, stdout=None, timeout=3600, env=None):
    cancelled()
    with (LOGS/'operation.log').open('ab') as log:
        p=subprocess.Popen([str(x) for x in args],cwd=cwd,stdin=stdin or subprocess.DEVNULL,stdout=stdout or log,stderr=log,env=env,start_new_session=True)
        children.append(p);deadline=time.monotonic()+timeout
        while p.poll() is None:
            cancelled()
            if time.monotonic()>deadline:raise RuntimeError('Server command timed out: '+str(args[0]))
            time.sleep(.15)
        if p.returncode:raise RuntimeError('Server command failed: '+str(args[0])+' (exit '+str(p.returncode)+'). See Server operation log.')

def stop_children():
    for p in reversed(children):
        if p.poll() is None:
            try:os.killpg(p.pid,signal.SIGTERM)
            except ProcessLookupError:pass
    for p in children:
        try:p.wait(timeout=15)
        except subprocess.TimeoutExpired:
            try:os.killpg(p.pid,signal.SIGKILL)
            except ProcessLookupError:pass
            p.wait(timeout=5)
    children.clear()

def validate_snapshot_links(source,excluded):
    root=source.resolve()
    def walk(folder,ancestors):
        cancelled();resolved=folder.resolve()
        if resolved in ancestors:raise ValueError('Source contains a recursive directory link')
        ancestors=ancestors|{resolved}
        for child in folder.iterdir():
            # Match copytree's ignore rule before inspecting or following links.
            if child.name in excluded:continue
            cancelled()
            if child.is_symlink() and not child.resolve().is_relative_to(root):raise ValueError('Source contains an external symlink')
            if child.is_dir():walk(child,ancestors)
    walk(source,set())

def snapshot_source(source, target, recover_build_binaries=True):
    # ZIP imports have already rejected links. Copy only internal links from a
    # managed update; never import .git hooks, old build trees or database files.
    excluded={'.git','.venv','venv','build','logs','log','mysql','node_modules'}
    def ignore(path,names):return excluded.intersection(names)
    validate_snapshot_links(source,excluded)
    shutil.copytree(source,target,ignore=ignore,symlinks=False)
    # Existing Linux server exports may keep their executables under build/.
    # That tree is deliberately not carried into a new build, but its one
    # unambiguous executable per process can be used for a prebuilt deployment.
    if recover_build_binaries:
        for name in PROCESSES:
            if not (target/name).exists():
                found=[p for p in (source/'build').rglob(name) if p.is_file()]
                if len(found)>1:raise ValueError('Multiple build outputs for '+name+'. Keep one executable or rebuild the imported source.')
                if found:
                    if not found[0].resolve().is_relative_to(source.resolve()):raise ValueError('Source contains an external symlink')
                    shutil.copy2(found[0],target/name)
    command(['git','init','-q'],cwd=target)
    command(['git','-c','user.name=LSB Android','-c','user.email=local@localhost','add','sql','tools','settings','CMakeLists.txt'],cwd=target)
    command(['git','-c','user.name=LSB Android','-c','user.email=local@localhost','commit','-qm','Imported server snapshot'],cwd=target)

def snapshot_deployment(source,target):
    # Restore the SQL against the active server revision, never a newly imported
    # source tree. Keep server assets including data/, custom scripts and meshes.
    excluded={'.git','.venv','venv','build','logs','log','node_modules'}
    def ignore(path,names):return excluded.intersection(names)
    validate_snapshot_links(source,excluded)
    def copy(source,target):
        cancelled();return shutil.copy2(source,target)
    shutil.copytree(source,target,ignore=ignore,copy_function=copy,symlinks=False)

def configure_tools(root):
    # Preserve a supplied schema revision, disable dbtool's client auto-update.
    import yaml
    file=root/'tools/config.yaml'
    values={}
    if file.exists():
        for item in yaml.safe_load(file.read_text()) or []:
            if isinstance(item,dict):values.update(item)
    values.update(mysql_bin='/usr/bin/',auto_backup=0,auto_update_client=False)
    file.write_text(yaml.safe_dump([{k:v} for k,v in values.items()]))

def checked_jobs(jobs):
    if type(jobs) is not int or not 1<=jobs<=16:raise ValueError('Use between 1 and 16 build workers')
    return jobs

def build(root, jobs):
    checked_jobs(jobs)
    root=root.resolve();started=time.time()
    (root/'android-build.json').unlink(missing_ok=True)
    hook=Path(__file__).with_name('android-build.cmake').resolve()
    options=['-DCMAKE_BUILD_TYPE=Release','-DCMAKE_C_COMPILER=gcc-15','-DCMAKE_CXX_COMPILER=g++-15',
             '-DPCH_ENABLE=OFF','-DENABLE_IPO=OFF','-DPython_EXECUTABLE='+str(root/'.venv/bin/python'),
             '-DCMAKE_PROJECT_TOP_LEVEL_INCLUDES='+str(hook)]
    report=dict(format=1,state='building',started_at=started,source=source_info(root),
                allocator='jemalloc',jobs=jobs,cmake_options=options,
                build_hook_sha256=hashlib.sha256(hook.read_bytes()).hexdigest())
    atomic(LOGS/'build-report.json',report)
    try:
        report['patches']=apply_source_patches(root)
        atomic(LOGS/'build-report.json',report)
        status('building','Preparing the selected source and Python build dependencies…')
        requirements=root/'tools/requirements.txt'
        command(['/usr/bin/python3','-m','venv',str(root/'.venv')],timeout=120)
        if requirements.is_file():command([root/'.venv/bin/pip','install','-r',requirements],cwd=root,timeout=1800)
        # This is an isolated staging tree. A requested build must never retain
        # an old executable just because CMake emits the new one in build/.
        for name in PROCESSES:(root/name).unlink(missing_ok=True)
        status('building','Configuring the server with jemalloc…')
        command(['cmake','-S',root,'-B',root/'build',*options],cwd=root)
        status('building','Compiling the four server programs with jemalloc…')
        command(['cmake','--build',root/'build','--parallel',str(jobs),'--target',*PROCESSES],cwd=root,timeout=max(7200,21600//jobs))
        for name in PROCESSES:
            if not (root/name).is_file():
                found=[p for p in (root/'build').rglob(name) if p.is_file()]
                if len(found)!=1:raise RuntimeError('Build did not produce '+name)
                shutil.copy2(found[0],root/name)
        status('building','Checking ARM64 binaries and jemalloc linkage…')
        validate_binaries(root)
        report['binaries']=validate_jemalloc(root)
        report.update(state='passed',finished_at=time.time(),elapsed_seconds=round(time.time()-started,2))
        atomic(root/'android-build.json',report)
        atomic(LOGS/'build-report.json',report)
        return report
    except Exception as error:
        report.update(state='stopped' if isinstance(error,InterruptedError) else 'failed',
                      error=str(error),finished_at=time.time(),elapsed_seconds=round(time.time()-started,2))
        atomic(LOGS/'build-report.json',report)
        raise

def apply_source_patches(root):
    """Small, recorded compatibility fixes in the disposable source copy only."""
    patches=json.loads(Path(__file__).with_name('source-patches.json').read_text())
    # Preflight every group before writing. Related changes (such as a decoded
    # write and its caller's capacity) must apply together; unrelated fixes can
    # be absent from an older source revision without blocking other groups.
    matches={}
    for patch in patches:
        path=root/patch['path'];matched=False
        if path.is_file():
            raw=path.read_bytes();text=raw.decode('utf-8')
            newline='\r\n' if b'\r\n' in raw else '\n'
            before=text.count(patch['before'].replace('\n',newline))
            after=text.count(patch['after'].replace('\n',newline))
            if before+after>1:raise ValueError('Ambiguous source patch '+patch['id']+'; selected source needs review')
            matched=before+after==1
        matches.setdefault(patch['group'],[]).append(matched)
    for group,found in matches.items():
        if any(found) and not all(found):
            raise ValueError('The '+group+' compatibility patch set does not match this source; selected source needs review')
    evidence=[]
    for patch in patches:
        cancelled();path=root/patch['path']
        item=dict(id=patch['id'],group=patch['group'],path=patch['path'],state='not_applicable')
        if path.is_file():
            original=path.read_bytes();text=original.decode('utf-8')
            # Retain the upstream line-ending convention in the staged file.
            newline='\r\n' if b'\r\n' in original else '\n'
            before=patch['before'].replace('\n',newline)
            after=patch['after'].replace('\n',newline)
            count=text.count(before);applied=text.count(after)
            if count>1 or applied>1 or (count and applied):
                raise ValueError('Ambiguous source patch '+patch['id']+'; selected source needs review')
            if count:
                updated=text.replace(before,after,1).encode('utf-8')
                path.write_bytes(updated)
                item.update(state='applied',before_sha256=hashlib.sha256(original).hexdigest(),
                            after_sha256=hashlib.sha256(updated).hexdigest())
            elif applied:
                item.update(state='already_applied',after_sha256=hashlib.sha256(original).hexdigest())
        evidence.append(item)
    return evidence

def validate_jemalloc(root):
    """Installing a package or mentioning it in CMake is not linkage proof."""
    binaries={}
    for name in PROCESSES:
        cancelled();path=root/name
        result=subprocess.run(['readelf','-d',str(path)],capture_output=True,text=True,timeout=30,
                              env=dict(os.environ,LC_ALL='C'))
        if result.returncode:raise RuntimeError('Cannot inspect allocator linkage for '+name)
        needed=re.findall(r'\(NEEDED\).*?\[([^\]]+)\]',result.stdout)
        if 'libjemalloc.so.2' not in needed:
            raise RuntimeError(name+' was built without a shared jemalloc dependency; build rejected')
        # Linux resolves malloc in dependency order. Reject libc taking priority.
        if 'libc.so.6' in needed and needed.index('libc.so.6')<needed.index('libjemalloc.so.2'):
            raise RuntimeError(name+' links libc before jemalloc; build rejected')
        with path.open('rb') as stream:
            digest=hashlib.file_digest(stream,'sha256').hexdigest()
        binaries[name]=dict(sha256=digest,
                            bytes=path.stat().st_size,needed=needed,allocator='libjemalloc.so.2')
    return binaries

def tree_fingerprint(root, include_binaries=True):
    """Hash copied source/runtime payload; never generated builds or log files."""
    excluded={'.git','.venv','venv','build','logs','log','mysql','node_modules','android-build.json'}
    digest=hashlib.sha256();count=0
    def walk(folder):
        nonlocal count
        for path in sorted(folder.iterdir(),key=lambda p:p.name):
            cancelled()
            if path.name in excluded or (not include_binaries and path.parent==root and path.name in PROCESSES):continue
            if path.is_symlink():raise ValueError('Build payload contains a symlink; copy the source again')
            relative=path.relative_to(root).as_posix().encode()
            if path.is_dir():
                digest.update(b'directory\0'+relative+b'\0');walk(path)
            elif path.is_file():
                with path.open('rb') as stream:value=hashlib.file_digest(stream,'sha256').digest()
                digest.update(b'file\0'+relative+b'\0'+value);count+=1
                if count%1000==0:print('Verified '+str(count)+' source files',flush=True)
            else:raise ValueError('Build payload contains an unsupported file')
    walk(root)
    return digest.hexdigest()

def selected_source(req, content_hash):
    # Provenance describes the selected acquisition, not a synthetic snapshot Git commit.
    identity=req.get('source_identity',{})
    if not isinstance(identity,dict):raise ValueError('Source identity must be an object')
    result={key:str(identity[key])[:1000] for key in ('repository','ref','commit','origin','snapshot_id') if key in identity}
    result['content_sha256']=content_hash
    return result

def build_source(req):
    # Compiling never touches the active server/database. Only this workspace is replaced.
    source=source_root(INPUT);workspace=STATE/'source-build';root=workspace/'server'
    status('copying','Copying the selected source for a reusable jemalloc build…')
    if workspace.exists():shutil.rmtree(workspace)
    workspace.mkdir(parents=True)
    snapshot_source(source,root,recover_build_binaries=False)
    identity=selected_source(req,tree_fingerprint(root,include_binaries=False))
    report=build(root,checked_jobs(req.get('jobs',2)))
    identity['expected_client']=report['source']['expected_client']
    report.update(build_id=str(uuid.uuid4()),selected_source=identity,content_sha256=tree_fingerprint(root))
    atomic(root/'android-build.json',report);atomic(LOGS/'build-report.json',report)
    status('build_ready','Jemalloc build '+report['build_id'][:8]+' is ready. Choose its database, then check and deploy this exact build.',build=report)

def load_build(build_id=None):
    root=STATE/'source-build/server';path=root/'android-build.json'
    if not path.is_file():raise ValueError('Build the selected source with jemalloc first')
    report=json.loads(path.read_text())
    if report.get('state')!='passed' or report.get('allocator')!='jemalloc':raise ValueError('The latest build did not pass jemalloc validation')
    if build_id is not None and (not build_id or report.get('build_id')!=build_id):raise ValueError('The selected build changed. Review the latest successful build before staging it.')
    validate_binaries(root);binaries=validate_jemalloc(root)
    if binaries!=report.get('binaries'):raise ValueError('Built binaries changed after compilation; rebuild the selected source')
    if report.get('content_sha256') and tree_fingerprint(root)!=report['content_sha256']:raise ValueError('Built source or runtime files changed after compilation; rebuild the selected source')
    recorded=report.get('source',{});actual=source_info(root)
    if recorded.get('expected_client')!=actual['expected_client'] or recorded.get('source_hashes')!=actual['source_hashes']:
        raise ValueError('The built source no longer matches its recorded source and client version')
    return root,report

def adopt_build(req):
    # A previous app's receipt can prove binaries, but cannot prove a repository
    # identity. Never relabel these files as the currently fetched source.
    root,report=load_build()
    if not report.get('build_id'):
        report.update(build_id=str(uuid.uuid4()),
                      selected_source=dict(origin='Previously built source; original repository not recorded',expected_client=report['source']['expected_client']),
                      content_sha256=tree_fingerprint(root),adopted_at=time.time())
        atomic(root/'android-build.json',report);atomic(LOGS/'build-report.json',report)
    status('build_ready','Existing successful jemalloc build verified. Its original repository identity was not recorded.',build=report)

def initialize_database(generation,name):
    name=checked_name(name);creds=start_database(generation)
    sql("CREATE DATABASE `"+name+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci; CREATE USER 'lsb'@'localhost' IDENTIFIED BY '"+creds['game']+"'; CREATE USER 'lsb'@'127.0.0.1' IDENTIFIED BY '"+creds['game']+"'; GRANT ALL ON `"+name+"`.* TO 'lsb'@'localhost'; GRANT ALL ON `"+name+"`.* TO 'lsb'@'127.0.0.1';",creds['root'])
    return creds

def prepare_database(root,name,creds,mode):
    if not (root/'.venv/bin/python').exists():
        command(['/usr/bin/python3','-m','venv',root/'.venv'])
        command([root/'.venv/bin/pip','install','-r',root/'tools/requirements.txt'],cwd=root,timeout=1800)
    configure_tools(root)
    config=RUN/'update-credentials.json'
    atomic(config,dict(database=name,password=creds['game'],mode=mode));os.chmod(config,0o600)
    try:command([root/'.venv/bin/python',Path(__file__).with_name('db_update.py'),root,RUN/'mysql.sock',config],cwd=root,timeout=3600)
    finally:config.unlink(missing_ok=True)

def write_staged(generation,info):
    atomic(generation/'deployment.json',info)
    atomic(STATE/'staged.json',info)

def active_generation():
    path=STATE/'active.json'
    return json.loads(path.read_text()).get('current') if path.is_file() else None

def file_sha256(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def invalidate_database_stage(reason):
    path=STATE/'staged.json'
    if not path.is_file():return
    info=json.loads(path.read_text())
    if info.get('database_mode')!='copy-current' or info.get('generation')==active_generation():return
    if info.get('staged_from_generation')!=active_generation():return
    info.update(state='staged',stale_database=True,check_error=reason);info.pop('checked_at',None)
    generation=STATE/'generations'/info['generation']
    write_staged(generation,info)

def stage_build(req):
    root,report=load_build(req.get('build_id',''));mode=req.get('database_mode')
    if mode not in ('fresh','import','copy-current'):raise ValueError('Choose a fresh database, imported SQL, or a copy of the active database')
    name=checked_name(req.get('database','xidb'));base=active_generation();dump=STATE/'import.sql'
    if mode=='copy-current':
        previous=current();name=json.loads((previous/'deployment.json').read_text())['database']
        dump=RUN/'previous.sql'
        status('backing_up','Copying the active database before preparing the selected build…')
        dump_database(previous,dump)
    if mode!='fresh' and not dump.is_file():raise ValueError('Import a database SQL or SQL.gz file first')
    generation=STATE/'generations'/str(uuid.uuid4());generation.mkdir(parents=True)
    staged=generation/'server';creds=None
    status('copying','Staging exact jemalloc build '+report['build_id'][:8]+' and its source files…')
    snapshot_source(root,staged,recover_build_binaries=False)
    if tree_fingerprint(staged)!=report['content_sha256']:raise ValueError('Copied build does not match the successful build receipt')
    try:
        status('building_database','Building a fresh database from this build’s SQL…' if mode=='fresh' else 'Importing the selected database into an isolated generation…')
        creds=initialize_database(generation,name) if mode=='fresh' else import_database(generation,dump,name)
        before=account_counts(name,creds) if mode!='fresh' else None
        write_network(staged,name,creds)
        prepare_database(staged,name,creds,'fresh' if mode=='fresh' else 'update')
        counts=account_counts(name,creds)
        if before is not None and counts!=before:raise RuntimeError('Account or character counts changed while preparing the database; active deployment kept')
        if req.get('local_zones',True):sql("UPDATE zone_settings SET zoneip='127.0.0.1',zoneport=54230;",creds['game'],'lsb',name)
        info=source_info(staged)
        if info['expected_client']!=report['source']['expected_client']:raise ValueError('Database preparation changed the build’s expected client version')
        info.update(format=2,state='staged',generation=generation.name,build_id=report['build_id'],build=report,
                    selected_source=report.get('selected_source',{}),database=name,database_mode=mode,
                    database_input_sha256=(file_sha256(dump) if mode!='fresh' else None),
                    accounts=counts['accounts'],characters=counts['chars'],created_at=time.time(),
                    staged_from_generation=base,local_zones=req.get('local_zones',True),client_pair=req.get('client_pair',{}),
                    binaries={n:report['binaries'][n]['sha256'] for n in PROCESSES},
                    staged_content_sha256=tree_fingerprint(staged))
    finally:
        if creds is not None:stop_database(creds)
        else:stop_children()
        if mode=='copy-current':dump.unlink(missing_ok=True)
    cancelled();write_staged(generation,info)
    status('staged','Exact build and database staged. Check the staged pair before deploying it.',deployment=info)

def staged_pair(req):
    generation_id=req.get('generation','')
    if not re.fullmatch(r'[0-9a-f-]{36}',generation_id):raise ValueError('Choose a staged generation')
    generation=STATE/'generations'/generation_id;path=generation/'deployment.json'
    if not path.is_file():raise ValueError('The selected staged generation is no longer available')
    info=json.loads(path.read_text())
    if info.get('generation')!=generation_id or not req.get('build_id') or info.get('build_id')!=req['build_id']:
        raise ValueError('The selected staged generation does not match this build')
    pointer=STATE/'staged.json'
    if not pointer.is_file() or json.loads(pointer.read_text()).get('generation')!=generation_id:raise ValueError('The staged pair changed. Review the latest staged build and database.')
    if active_generation()==generation_id:raise ValueError('This build and database are already deployed')
    return generation,info

def check_staged(req):
    generation,info=staged_pair(req);root=generation/'server';creds=None
    if info.get('stale_database'):raise ValueError('The active database changed after this copy was staged. Stage its database again to preserve newer progress.')
    info['state']='staged';info.pop('checked_at',None);info.pop('check_error',None)
    write_staged(generation,info)
    status('checking_staged','Checking the staged source, jemalloc binaries and database…')
    try:
        if tree_fingerprint(root)!=info.get('staged_content_sha256'):raise ValueError('Staged source or runtime files changed; stage the build again')
        validate_binaries(root);binaries=validate_jemalloc(root)
        if binaries!=info['build']['binaries']:raise ValueError('Staged binaries do not match the exact successful jemalloc build')
        if {n:binaries[n]['sha256'] for n in PROCESSES}!=info['binaries']:raise ValueError('Staged binary receipt is inconsistent')
        if source_info(root)['expected_client']!=info['expected_client']:raise ValueError('Staged source expects a different client')
        creds=start_database(generation)
        counts=account_counts(info['database'],creds)
        if counts!={'accounts':info['accounts'],'chars':info['characters']}:raise ValueError('Staged database counts changed; stage the database again')
        command(['mariadb-check',cnf('lsb',creds['game']),'--check','--databases',info['database']],timeout=3600)
    except Exception as error:
        info['check_error']=str(error);write_staged(generation,info);raise
    finally:
        if creds is not None:stop_database(creds)
        else:stop_children()
    cancelled();info.update(state='checked',checked_at=time.time());write_staged(generation,info)
    status('staged_checked','Staged source, all four jemalloc programs and database checks passed. Ready to deploy.',deployment=info)
    return generation,info

def deploy_staged(req):
    generation,info=staged_pair(req)
    if info.get('state')!='checked':raise ValueError('Check the staged build and database before deploying')
    if active_generation()!=info.get('staged_from_generation'):raise ValueError('The active deployment changed after staging. Stage this build and database again.')
    generation,info=check_staged(req)
    cancelled();previous=active_generation()
    atomic(STATE/'active.json',dict(current=generation.name,previous=previous))
    status('ready','Build '+info['build_id'][:8]+' and its checked database deployed. The previous server and database are retained.',deployment=info)

def validate_binaries(root):
    # Retain the loader's evidence even when validation fails before any server
    # process or database starts. Previously capture_output discarded the only
    # useful missing-library names from both the UI and support export.
    LOGS.mkdir(parents=True,exist_ok=True)
    failures=[]
    report=LOGS/'dependencies.log'
    report.write_text('Server dependency check (Linux ARM64)\n')
    def record(name, detail):
        with report.open('a') as out:out.write('\n'+name+'\n'+detail[-16384:]+'\n')
    for name in PROCESSES:
        cancelled()
        file=root/name
        if not file.is_file():
            reason='Missing '+name+'. Choose Build imported revision and deploy.'
            record(name,reason);failures.append(reason);continue
        with file.open('rb') as f:header=f.read(64)
        if len(header)<20 or header[:5]!=b'\x7fELF\x02' or header[5]!=1 or int.from_bytes(header[18:20],'little')!=183:
            reason=name+' must be a Linux ARM64 executable. Rebuild the imported source in this app.'
            record(name,reason);failures.append(reason);continue
        os.chmod(file,0o755)
        try:
            result=subprocess.run(['ldd',str(file)],capture_output=True,text=True,errors='replace',timeout=30,env=dict(os.environ,LC_ALL='C'))
        except subprocess.TimeoutExpired:
            reason=name+': library check timed out after 30 seconds'
            record(name,reason);failures.append(reason);continue
        details=result.stdout+result.stderr
        record(name,'ldd exit='+str(result.returncode)+'\n'+details)
        missing=sorted(set(re.findall(r'^\s*(\S+)\s+=>\s+not found\s*$',details,re.M)))
        if missing:
            failures.append(name+': missing '+', '.join(missing));continue
        if 'not found' in details:
            # Also preserve versioned ABI failures (GLIBC/GLIBCXX), which cannot
            # safely be repaired by aliasing one library version to another.
            lines=[' '.join(line.split()) for line in details.splitlines() if 'not found' in line]
            failures.append(name+': '+ '; '.join(lines)[:1500]);continue
        if result.returncode:
            # glibc ldd returns 1 for valid static executables too. Accept those
            # only after readelf independently confirms no dynamic dependencies.
            static=False
            if 'not a dynamic executable' in details or 'statically linked' in details:
                try:
                    elf=subprocess.run(['readelf','-lW','-dW',str(file)],capture_output=True,text=True,errors='replace',timeout=30,env=dict(os.environ,LC_ALL='C'))
                    static=elf.returncode==0 and 'INTERP' not in elf.stdout and '(NEEDED)' not in elf.stdout
                    record(name,'readelf exit='+str(elf.returncode)+'\n'+elf.stdout+elf.stderr)
                except subprocess.TimeoutExpired:pass
            if not static:failures.append(name+': library check failed (exit '+str(result.returncode)+')')
    if failures:
        record('Summary','\n'.join(failures))
        raise ValueError('Server dependency check failed: '+'; '.join(failures)+'. Update server runtime and build tools, then retry deployment. If unavailable library versions remain, rebuild the same imported revision. See Server operation log for dependency details.')
    record('Summary','PASS: all four Linux ARM64 server executables passed dependency validation')

def credentials(generation):
    file=generation/'database-credentials.json'
    if not file.exists():atomic(file,dict(root=secrets.token_hex(24),game=secrets.token_hex(24)))
    data=json.loads(file.read_text());secret_values.extend(data.values());return data

def cnf(user,password):
    file=RUN/(user+'.cnf');file.write_text('[client]\nuser='+user+'\npassword='+password+'\nsocket='+str(RUN/'mysql.sock')+'\n');os.chmod(file,0o600);return '--defaults-extra-file='+str(file)

def sql(text, password='', user='root', database=None):
    args=['mariadb',cnf(user,password),'--batch','--skip-column-names','--local-infile=0']
    if database:args.append(checked_name(database))
    result=subprocess.run(args,input=text,text=True,capture_output=True,timeout=60)
    if result.returncode:
        safe=result.stderr
        for value in secret_values:safe=safe.replace(value,'[redacted]')
        raise RuntimeError('Database command failed: '+safe[-1200:])
    return result.stdout

def start_database(generation, network=False):
    data=generation/'database';fresh=not (data/'mysql').is_dir();creds=credentials(generation)
    if fresh:
        data.mkdir(parents=True,exist_ok=True)
        command(['mariadb-install-db','--no-defaults','--datadir='+str(data),'--auth-root-authentication-method=normal','--skip-test-db','--user=root'],timeout=180)
    args=['mariadbd','--no-defaults','--user=root','--datadir='+str(data),'--socket='+str(RUN/'mysql.sock'),'--pid-file='+str(RUN/'mysql.pid'),'--log-error='+str(LOGS/'database.log'),'--skip-log-bin','--innodb-buffer-pool-size=256M','--innodb-use-native-aio=0']
    if network:args+=['--bind-address=127.0.0.1','--port='+str(DB_PORT)]
    else:args+=['--skip-networking']
    log=(LOGS/'database-console.log').open('ab');p=subprocess.Popen(args,stdout=log,stderr=log,start_new_session=True);log.close();children.append(p)
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        cancelled()
        if p.poll() is not None:raise RuntimeError('MariaDB exited during startup. See Server database log.')
        try:sql('SELECT 1;',password='' if fresh else creds['root']);break
        except (RuntimeError,subprocess.TimeoutExpired):time.sleep(.3)
    else:raise RuntimeError('MariaDB did not become ready')
    if fresh:
        hosts=sql("SELECT Host FROM mysql.user WHERE User='root';").splitlines()
        statements=[]
        for host in hosts:statements.append("ALTER USER 'root'@'"+host.replace("'","''")+"' IDENTIFIED BY '"+creds['root']+"';")
        statements.append("DELETE FROM mysql.global_priv WHERE User=''; FLUSH PRIVILEGES;")
        sql('\n'.join(statements))
    return creds

def stop_database(creds):
    try:
        result=subprocess.run(['mariadb-admin',cnf('root',creds['root']),'shutdown'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=30)
        if result.returncode:raise RuntimeError('MariaDB did not shut down cleanly. Check the Server database log.')
    except subprocess.TimeoutExpired:raise RuntimeError('MariaDB did not finish shutting down. Check the Server database log.') from None
    finally:stop_children()

def clean_dump(source, target):
    # Strip dump-generated DEFINER ownership only from executable comment lines;
    # never rewrite INSERT data, stored strings, database names or client versions.
    with source.open('rb') as inp,target.open('wb') as out:
        for line in inp:
            if line.lstrip().startswith(b'/*!'):
                line=re.sub(rb'DEFINER\s*=\s*`[^`]*`@`[^`]*`',b'',line)
            out.write(line)
            cancelled()

def import_database(generation, dump, name):
    name=checked_name(name);creds=initialize_database(generation,name)
    cleaned=RUN/'import.sql';clean_dump(dump,cleaned)
    try:
        with cleaned.open('rb') as stream:command(['mariadb',cnf('lsb',creds['game']),'--binary-mode','--local-infile=0',name],stdin=stream,timeout=3600)
    finally:cleaned.unlink(missing_ok=True)
    return creds

def account_counts(name,creds):
    tables=set(sql('SHOW TABLES;',creds['game'],'lsb',name).splitlines())
    if not {'accounts','chars','zone_settings'}.issubset(tables):raise ValueError('The database is missing accounts, chars or zone_settings')
    return {t:int(sql('SELECT COUNT(*) FROM `'+t+'`;',creds['game'],'lsb',name)) for t in ('accounts','chars')}

def write_network(root,name,creds):
    folder=root/'settings';folder.mkdir(exist_ok=True)
    for default in (folder/'default').glob('*.lua'):
        if not (folder/default.name).exists():shutil.copy2(default,folder/default.name)
    file=folder/'network.lua';text=file.read_text()
    values={'SQL_HOST':"'127.0.0.1'",'SQL_PORT':str(DB_PORT),'SQL_LOGIN':"'lsb'",'SQL_PASSWORD':"'"+creds['game']+"'",'SQL_DATABASE':"'"+name+"'",'LOGIN_AUTH_IP':"'127.0.0.1'",'LOGIN_DATA_IP':"'127.0.0.1'",'LOGIN_VIEW_IP':"'127.0.0.1'",'ZMQ_IP':"'127.0.0.1'"}
    for key,value in values.items():
        text,count=re.subn(r'(?m)^(\s*'+key+r'\s*=)[^\n]*',lambda m:m[1]+' '+value+',',text)
        if count!=1:raise ValueError('Cannot safely configure network setting '+key)
    file.write_text(text)

def current():
    p=STATE/'active.json'
    if not p.exists():raise ValueError('Deploy the existing server and SQL backup first')
    value=json.loads(p.read_text());return STATE/'generations'/value['current']

def dump_database(generation,target):
    meta=json.loads((generation/'deployment.json').read_text());creds=None
    temporary=target.with_name(target.name+'.'+str(uuid.uuid4())+'.part')
    try:
        try:
            creds=start_database(generation)
            with temporary.open('xb') as out:
                os.chmod(temporary,0o600)
                command(['mariadb-dump',cnf('root',creds['root']),'--single-transaction','--routines','--triggers','--events','--hex-blob',meta['database']],stdout=out)
                out.flush();os.fsync(out.fileno())
        finally:
            if creds is not None:stop_database(creds)
            else:stop_children()
        cancelled();temporary.replace(target)
    finally:temporary.unlink(missing_ok=True)

def restore_database(req):
    previous=current();meta=json.loads((previous/'deployment.json').read_text())
    name=checked_name(meta['database']);dump=STATE/'import.sql'
    if not dump.is_file():raise ValueError('Import the existing server database as SQL or SQL.gz first')
    generation=STATE/'generations'/str(uuid.uuid4());generation.mkdir(parents=True)
    root=generation/'server';creds=None
    status('copying','Copying the current server revision for database restore…')
    snapshot_deployment(previous/'server',root);validate_binaries(root)
    binaries={n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in PROCESSES}
    if binaries!=meta.get('binaries'):raise ValueError('Current server binaries do not match the deployment record; active deployment kept')
    status('importing_database','Restoring SQL into a separate database generation…')
    try:
        creds=import_database(generation,dump,name)
        counts=account_counts(name,creds);write_network(root,name,creds)
        local_zones=meta.get('local_zones',req.get('local_zones',True))
        if local_zones:sql("UPDATE zone_settings SET zoneip='127.0.0.1',zoneport=54230;",creds['game'],'lsb',name)
        info=dict(meta)
        info.update(generation=generation.name,accounts=counts['accounts'],characters=counts['chars'],created_at=time.time(),updated=False,local_zones=local_zones,restored_from_generation=previous.name)
        atomic(generation/'deployment.json',info)
    finally:
        if creds is not None:stop_database(creds)
        else:stop_children()
    cancelled()
    atomic(STATE/'active.json',dict(current=generation.name,previous=previous.name))
    status('ready','Database restored with the same server revision. The previous server and database are retained.',deployment=info)

def deploy(req):
    source=source_root(INPUT);name=checked_name(req.get('database','xidb'));updating=req['action']=='update'
    previous=current() if updating else None
    dump=STATE/'import.sql'
    if updating:
        name=json.loads((previous/'deployment.json').read_text())['database'];dump=RUN/'previous.sql'
        status('backing_up','Saving a complete database copy before staging the update…');dump_database(previous,dump)
    if not dump.is_file():raise ValueError('Import the existing server database as SQL or SQL.gz first')
    generation=STATE/'generations'/str(uuid.uuid4());generation.mkdir(parents=True)
    root=generation/'server'
    status('copying','Staging an independent server copy…');snapshot_source(source,root,recover_build_binaries=not req.get('build',False))
    if updating:
        for f in (previous/'server/settings').glob('*.lua'):shutil.copy2(f,root/'settings'/f.name)
    build_report=build(root,int(req.get('jobs',2))) if req.get('build',False) else None
    validate_binaries(root)
    status('importing_database','Importing SQL into a separate database generation…')
    creds=import_database(generation,dump,name)
    try:
        before=account_counts(name,creds);write_network(root,name,creds)
        if req.get('local_zones',True):sql("UPDATE zone_settings SET zoneip='127.0.0.1',zoneport=54230;",creds['game'],'lsb',name)
        if updating:
            status('updating_database','Applying this source revision’s database updates to the staged copy…')
            if not (root/'.venv/bin/python').exists():
                command(['/usr/bin/python3','-m','venv',root/'.venv'])
                command([root/'.venv/bin/pip','install','-r',root/'tools/requirements.txt'],cwd=root)
            configure_tools(root)
            # dbtool uses TCP credentials, while this staging database has no TCP listener.
            # Inject the private socket using a small process-local connector wrapper.
            wrapper=Path(__file__).with_name('db_update.py')
            config=RUN/'update-credentials.json';atomic(config,dict(database=name,password=creds['game']));os.chmod(config,0o600)
            try:command([root/'.venv/bin/python',wrapper,root,RUN/'mysql.sock',config],cwd=root,timeout=3600)
            finally:config.unlink(missing_ok=True)
            after=account_counts(name,creds)
            if after!=before:raise RuntimeError('Account or character counts changed during the update; active deployment kept')
        info=source_info(root)
        if build_report is not None:info['build']=build_report
        info.update(format=1,generation=generation.name,database=name,accounts=before['accounts'],characters=before['chars'],created_at=time.time(),updated=updating,local_zones=req.get('local_zones',True),client_pair=req.get('client_pair',{}),binaries={n:hashlib.sha256((root/n).read_bytes()).hexdigest() for n in PROCESSES})
        atomic(generation/'deployment.json',info)
    finally:stop_database(creds)
    old=json.loads((STATE/'active.json').read_text()).get('current') if (STATE/'active.json').exists() else None
    cancelled()
    atomic(STATE/'active.json',dict(current=generation.name,previous=old))
    status('ready','Server and database deployed. The previous generation is retained.',deployment=info)

def ensure_ports():
    for port,kind in [(13306,socket.SOCK_STREAM),(54231,socket.SOCK_STREAM),(54230,socket.SOCK_STREAM),(54001,socket.SOCK_STREAM),(54002,socket.SOCK_STREAM),(54003,socket.SOCK_STREAM),(54230,socket.SOCK_DGRAM)]:
        with socket.socket(socket.AF_INET,kind) as s:
            try:s.bind(('127.0.0.1',port))
            except OSError:raise RuntimeError('Port '+str(port)+' is in use. Stop the other server app or Termux server before starting this server.')

class StartupProgress:
    """Read only this start's log bytes; an open login socket is not world readiness."""
    def __init__(self, logs):
        self.logs=logs;self.ready=set();self.stages={};self.recent=[]
        self.offsets={};self.identities={};self.fragments={};self.oversized=set()
        for name in PROCESSES:
            path=logs/(name+'.log')
            try:
                info=path.stat();self.offsets[name]=info.st_size;self.identities[name]=(info.st_dev,info.st_ino)
            except FileNotFoundError:self.offsets[name]=0

    def _line(self, name, raw):
        line=re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]','',raw.decode('utf-8',errors='replace')).strip()
        # The four LSB processes report this only after markLoaded, including
        # xi_map after all NPC/mob scripts. Accept each process's own marker.
        role=name.removeprefix('xi_')
        if re.search(r'(?:^|\]\s*)The '+role+r'-server is ready to work\.\.\.(?:\s|$)',line):
            self.ready.add(name);message=role+': Ready'
        else:
            # Only startup progress belongs in the live summary; leave arbitrary
            # game output (including character/account details) in its normal log.
            found=re.search(r'(?:^|\]\s*)(?:do_init: |luautils: )?((?:[Ll]oading |Initializing engine|Lua initializing|Waiting for |Finished waiting for |Removing expired database variables)[^\r\n]*)',line)
            if not found:return
            stage=re.sub(r'\s+\([A-Za-z_][A-Za-z_0-9]*:\d+\)$','',found[1])[:200]
            self.stages[name]=stage;message=role+': '+stage
        if not self.recent or self.recent[-1]!=message:self.recent=(self.recent+[message])[-12:]

    def poll(self):
        for name in PROCESSES:
            path=self.logs/(name+'.log')
            try:
                with path.open('rb') as log:
                    info=os.fstat(log.fileno());identity=(info.st_dev,info.st_ino)
                    if identity!=self.identities.get(name,identity) or info.st_size<self.offsets[name]:
                        self.offsets[name]=0;self.fragments.pop(name,None);self.oversized.discard(name)
                        self.ready.discard(name);self.stages.pop(name,None)
                    self.identities[name]=identity;log.seek(self.offsets[name])
                    data=log.read(256*1024);self.offsets[name]=log.tell()
            except FileNotFoundError:continue
            parts=(self.fragments.get(name,b'')+data).split(b'\n')
            for line in parts[:-1]:
                if name not in self.oversized and len(line)<=8192:self._line(name,line)
                self.oversized.discard(name)
            tail=parts[-1]
            if len(tail)>8192:self.oversized.add(name)
            self.fragments[name]=b'' if name in self.oversized else tail

    def snapshot(self, elapsed, port_ready):
        pending=[name for name in PROCESSES if name not in self.ready]
        focus='xi_map' if 'xi_map' in pending else next(iter(pending),None)
        stage=(self.stages.get(focus,'Waiting for '+focus.removeprefix('xi_')+' readiness') if focus else
               'Waiting for the login port' if not port_ready else 'All server scripts loaded')
        return dict(elapsed_seconds=int(elapsed),ready_processes=[name for name in PROCESSES if name in self.ready],
                    pending_processes=pending,stage=stage,recent_lines=self.recent[:],login_port_reachable=port_ready)

def serve():
    invalidate_database_stage('The active server was started after this database copy was staged. Stage its database again.')
    generation=current();root=generation/'server';validate_binaries(root);ensure_ports()
    meta=json.loads((generation/'deployment.json').read_text());status('starting','Starting the managed server…',deployment=meta)
    creds=start_database(generation,True)
    workers=[]
    try:
        progress=StartupProgress(LOGS)
        for name in PROCESSES:
            log=(LOGS/(name+'.log')).open('ab')
            p=subprocess.Popen([str(root/name)],cwd=root,stdin=subprocess.DEVNULL,stdout=log,stderr=log,start_new_session=True)
            log.close();children.append(p);workers.append((name,p))
        ready=False;started=time.monotonic();last_report=None;last_report_at=0
        while True:
            cancelled()
            for name,p in workers:
                if p.poll() is not None:raise RuntimeError(name+' exited with code '+str(p.returncode)+'. See Server logs.')
            if not ready:
                progress.poll();port_ready=False
                try:
                    with socket.create_connection(('127.0.0.1',54231),timeout=.3):pass
                    port_ready=True
                except OSError:pass
                now=time.monotonic();elapsed=now-started;report=progress.snapshot(elapsed,port_ready)
                ready=not report['pending_processes'] and port_ready
                if ready:status('running','Server ready — all scripts loaded. You can connect now.',deployment=meta,startup=report)
                elif elapsed>STARTUP_TIMEOUT_SECONDS:
                    waiting=', '.join(report['pending_processes']) or 'the login port'
                    raise RuntimeError('Server startup was not confirmed within 30 minutes; waiting for '+waiting+'. Last stage: '+report['stage']+'. See Server operation log.')
                else:
                    # Refresh elapsed time while a large script-loading stage is
                    # quiet, but avoid rewriting status on every supervisor tick.
                    signature=(report['stage'],tuple(report['ready_processes']),tuple(report['recent_lines']),port_ready)
                    if signature!=last_report or now-last_report_at>=2:
                        message='Starting server: '+report['stage']+' ('+str(int(elapsed))+'s). Wait for “Server ready” before connecting.'
                        status('starting',message,deployment=meta,startup=report);last_report=signature;last_report_at=now
            time.sleep(.5)
    finally:
        # Stop game writers before shutting down their database.
        try:status('stopping','Stopping the managed server and database…',deployment=meta)
        except OSError:pass  # A full disk must not prevent clean database shutdown.
        for _,p in workers:
            if p.poll() is None:
                try:os.killpg(p.pid,signal.SIGTERM)
                except ProcessLookupError:pass
        for _,p in workers:
            try:p.wait(timeout=15)
            except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=5)
        stop_database(creds)

def main():
    for p in (STATE,RUN,LOGS):p.mkdir(parents=True,exist_ok=True)
    req=json.loads((RUN/'request.json').read_text());action=req.get('action')
    if action not in ('deploy','update','start','backup','rollback','inspect','restore-db','create-account','build-source','adopt-build','stage-build','check-staged','deploy-staged'):raise ValueError('Unknown server action')
    checked_jobs(req.get('jobs',2))
    if action=='inspect':
        info=source_info(source_root(INPUT));status('inspected','Selected source expects client '+info['expected_client']+'. Meshes: '+', '.join(k+(' present' if v else ' missing') for k,v in info['meshes'].items()),source=info)
    elif action=='build-source':build_source(req)
    elif action=='adopt-build':adopt_build(req)
    elif action=='stage-build':stage_build(req)
    elif action=='check-staged':check_staged(req)
    elif action=='deploy-staged':deploy_staged(req)
    elif action in ('deploy','update'):deploy(req)
    elif action=='restore-db':restore_database(req)
    elif action=='create-account':
        invalidate_database_stage('An account operation changed the active database. Stage its database again.')
        from accounts import create_account
        generation=current()
        try:result=create_account(sys.modules[__name__],generation,sys.stdin.buffer)
        except InterruptedError:
            status('stopped','Account operation stopped. Check whether the account exists before retrying.');return
        except Exception as error:
            # Insertion can commit before a metadata write or database shutdown
            # fails. The caller must not mistake this error for an SQL rollback.
            raise RuntimeError(str(error)+' Check whether the account exists before retrying.') from None
        status('ready','Account created. Use it to sign in to the managed server.',**result)
    elif action=='start':serve()
    elif action=='backup':
        dump_database(current(),STATE/'export.sql')
        status('ready','Full database backup is ready to export')
    elif action=='rollback':
        pointer=json.loads((STATE/'active.json').read_text());previous=pointer.get('previous')
        if not previous or not (STATE/'generations'/previous/'deployment.json').is_file():raise ValueError('No complete previous deployment is available')
        atomic(STATE/'active.json',dict(current=previous,previous=pointer['current']));status('ready','Previous server and database restored together')

if __name__=='__main__':
    try:main()
    except InterruptedError:status('stopped','Server stopped')
    except Exception as e:status('error',str(e));sys.exit(1)
    finally:stop_children()
