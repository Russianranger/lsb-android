"""Real MariaDB deployment/recovery with synthetic ARM64 server processes."""
from pathlib import Path
import hashlib, importlib.util, json, os, re, shutil, subprocess, sys, time
from test_accounts import ACCOUNT_SCHEMA_SQL, write_source_fixture

repo=Path(__file__).resolve().parents[2]
state=Path('/state');run=Path('/server-run');logs=Path('/server-logs');source=Path('/input/server')
for p in (state,run,logs,source,Path('/client')):p.mkdir(parents=True,exist_ok=True)
(Path('/client')/'sentinel').write_bytes(b'accepted client kept')
for folder in ('src','sql','tools','settings/default','scripts','navmeshes','ximeshes'):(source/folder).mkdir(parents=True,exist_ok=True)
write_source_fixture(source)
(source/'CMakeLists.txt').write_text('cmake_minimum_required(VERSION 3.25)\nproject(server)\n')
(source/'settings/default/login.lua').write_text("xi = xi or {}\nxi.settings = xi.settings or {}\nxi.settings.login = {\n CLIENT_VER = '30251204_1',\n VER_LOCK = 2,\n}\n")
(source/'settings/default/network.lua').write_text("xi = xi or {}\nxi.settings = xi.settings or {}\nxi.settings.network = {\n"+'\n'.join(k+" = 'original'," for k in ('SQL_HOST','SQL_PORT','SQL_LOGIN','SQL_PASSWORD','SQL_DATABASE','LOGIN_AUTH_IP','LOGIN_DATA_IP','LOGIN_VIEW_IP','ZMQ_IP'))+'\n}\n')
(source/'sql/fixture.sql').write_text('-- imported SQL sources retained\n')
(source/'tools/requirements.txt').write_text('mariadb\npyyaml\n')
(source/'tools/config.yaml').write_text('- auto_update_client: true\n')
(source/'tools/dbtool.py').write_text('''import os,sys,mariadb
def main():
 c=mariadb.connect(user=os.environ['XI_NETWORK_SQL_LOGIN'],password=os.environ['XI_NETWORK_SQL_PASSWORD'],database=os.environ['XI_NETWORK_SQL_DATABASE'])
 q=c.cursor()
 if sys.argv[1]=='update':q.execute('CREATE TABLE IF NOT EXISTS update_marker (id INTEGER)');c.commit()
 c.close()
''')
program=Path('/tmp/server-stub.c');program.write_text('''#include <signal.h>
#include <unistd.h>
#include <string.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <fcntl.h>
#include <stdio.h>
#include <jemalloc/jemalloc.h>
#include <bfd.h>
static volatile sig_atomic_t done;static void stop(int s){(void)s;done=1;}
int main(int argc,char**argv){(void)argc;bfd_init();bfd* input=bfd_openr(argv[0],NULL);if(!input||!bfd_check_format(input,bfd_object)||!bfd_close(input))return 10;puts("bfd=2.45 object verified");const char* version=NULL;size_t length=sizeof(version);if(mallctl("version",&version,&length,NULL,0))return 9;printf("allocator=jemalloc %s\\n",version);fflush(stdout);signal(SIGTERM,stop);int fd=-1;if(strstr(argv[0],"xi_connect")){fd=socket(AF_INET,SOCK_STREAM,0);int one=1;setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));struct sockaddr_in a={0};a.sin_family=AF_INET;a.sin_port=htons(54231);a.sin_addr.s_addr=htonl(0x7f000001);if(bind(fd,(void*)&a,sizeof(a))||listen(fd,4)||fcntl(fd,F_SETFL,O_NONBLOCK)<0)return 8;}const char* role=strrchr(argv[0],'/');role=role?role+1:argv[0];role+=3;
if(!strcmp(role,"map")){puts("[map][info] Loading Mob scripts (LoadMOBList:662)");fflush(stdout);sleep(4);}
printf("The %s-server is ready to work after 4.12 seconds... (markLoaded:275)\\n",role);fflush(stdout);
while(!done){if(fd>=0){int client=accept(fd,NULL,NULL);if(client>=0)close(client);}usleep(100000);}if(fd>=0)close(fd);return 0;}
''')
dump=ACCOUNT_SCHEMA_SQL.encode()+b"\nINSERT INTO accounts(id,login) VALUES(1,'fixture'); CREATE TABLE chars(charid INT PRIMARY KEY,charname VARCHAR(32)); INSERT INTO chars VALUES(1,'Fixture'); CREATE TABLE zone_settings(zoneid INT,zoneip VARCHAR(32),zoneport INT); INSERT INTO zone_settings VALUES(1,'192.0.2.5',54231);\n"+b"""
CREATE TABLE fixture_blobs(id INT PRIMARY KEY, content BLOB);
INSERT INTO fixture_blobs VALUES (1, 0x000A0DFF275C);
CREATE TABLE fixture_audit(id INT);
CREATE VIEW fixture_view AS SELECT id FROM fixture_blobs;
DELIMITER ;;
CREATE PROCEDURE fixture_proc() SELECT HEX(content) FROM fixture_blobs;;
CREATE TRIGGER fixture_trigger AFTER INSERT ON fixture_blobs FOR EACH ROW BEGIN INSERT INTO fixture_audit VALUES(NEW.id); END;;
CREATE EVENT fixture_event ON SCHEDULE EVERY 1 DAY DISABLE DO INSERT INTO fixture_audit VALUES (999);;
DELIMITER ;
"""
(state/'import.sql').write_bytes(dump)
manager=repo/'server/manager.py'
def invoke(action,success=True,payload=None,**extra):
 (run/'stop').unlink(missing_ok=True)
 (run/'request.json').write_text(json.dumps({'action':action,'database':'xidb','build':False,'jobs':2,**extra}))
 result=subprocess.run([sys.executable,manager],input=payload,timeout=600)
 report=json.loads((run/'status.json').read_text())
 if success:assert result.returncode==0,report
 else:assert result.returncode!=0,report
 return report
def selected():return json.loads((state/'active.json').read_text())
def tree_hashes(root):return {str(p.relative_to(root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in root.rglob('*') if p.is_file()}
spec=importlib.util.spec_from_file_location('integration_manager',manager);backend=importlib.util.module_from_spec(spec);spec.loader.exec_module(backend)
# Reproduce the phone's exact SONAME failure using real BFD 2.45 calls. Fetch
# its matching headers only for this fixture; the installed compiler stays current.
spec=importlib.util.spec_from_file_location('bfd_compat',repo/'server/bfd_compat.py');bfd=importlib.util.module_from_spec(spec);spec.loader.exec_module(bfd)
bfd_work=Path('/tmp/lsb-bfd-fixture');bfd_work.mkdir()
headers,_=bfd.download_package(bfd_work,'binutils-dev',12947812,'a36b1e07661eabd0d9c9f22299b07b12bbcf6084b9be724ac55bfe946933230e')
subprocess.run(['dpkg-deb','--extract',headers,bfd_work/'headers'],check=True)
bfd_program=bfd_work/'bfd-check.c';bfd_program.write_text('''#include <bfd.h>
#include <stdio.h>
int main(int argc,char**argv){(void)argc;bfd_init();bfd* input=bfd_openr(argv[0],NULL);if(!input||!bfd_check_format(input,bfd_object))return 1;printf("BFD 2.45 opened %s\\n",bfd_get_target(input));return bfd_close(input)?0:2;}
''')
subprocess.run(['gcc-15','-I'+str(bfd_work/'headers/usr/include'),bfd_program,'-L'+str(bfd.LIBDIR),'-Wl,-rpath-link,'+str(bfd.LIBDIR),'-l:libbfd-2.45-system.so','-o',bfd_work/'bfd-check'],check=True)
# The long-running server fixtures also use the exact BFD ABI, so managed start
# tests actual process execution, not just ldd or a standalone dlopen.
subprocess.run(['gcc-15','-O2','-I'+str(bfd_work/'headers/usr/include'),program,'-L'+str(bfd.LIBDIR),'-Wl,-rpath-link,'+str(bfd.LIBDIR),'-l:libbfd-2.45-system.so','-ljemalloc','-o','/tmp/server-stub'],check=True)
for name in backend.PROCESSES:shutil.copy2('/tmp/server-stub',source/name)
original_binaries={name:(source/name).read_bytes() for name in backend.PROCESSES}
system_bfd=Path('/usr/lib/aarch64-linux-gnu/libbfd.so');system_target=system_bfd.resolve();system_hash=hashlib.sha256(system_bfd.read_bytes()).hexdigest()
assert system_target.name!='libbfd-2.45-system.so',system_target
package_state=subprocess.check_output(['dpkg-query','-W','binutils','binutils-dev','libbinutils'])
for _,_,_,soname in bfd.PACKAGES:(bfd.LIBDIR/soname).rename(bfd_work/soname)
subprocess.run(['ldconfig'],check=True)
failed=invoke('deploy',False)
assert 'libbfd-2.45-system.so' in failed['message'],failed
assert not (state/'active.json').exists() and (state/'import.sql').read_bytes()==dump
assert all((source/name).read_bytes()==content for name,content in original_binaries.items())
bfd.install();backend.validate_binaries(source)
subprocess.run([bfd_work/'bfd-check'],check=True)
assert system_bfd.resolve()==system_target and hashlib.sha256(system_bfd.read_bytes()).hexdigest()==system_hash
assert subprocess.check_output(['dpkg-query','-W','binutils','binutils-dev','libbinutils'])==package_state
assert all((source/name).read_bytes()==content for name,content in original_binaries.items())
print('PASS: exact BFD 2.45 dependency failure repaired with real BFD/SFrame calls; current toolchain, imported binaries and SQL preserved',flush=True)
def query_generation(generation,query):
 folder=state/'generations'/generation;meta=json.loads((folder/'deployment.json').read_text());creds=None
 try:
  creds=backend.start_database(folder)
  return backend.sql(query,creds['game'],'lsb',meta['database'])
 finally:
  if creds is not None:backend.stop_database(creds)
  else:backend.stop_children()
pair={'client_expected':'retained fixture client'}
# Docker supplies /etc/hosts, but the pinned Ubuntu Base archive leaves it empty.
# Reproduce the phone failure with file-only name lookup, then use the exact
# packaged hosts file that Android binds into every server guest invocation.
hosts=Path('/etc/hosts');nss=Path('/etc/nsswitch.conf');original_nss=nss.read_text()
try:
 nss.write_text(re.sub(r'(?m)^hosts:.*$', 'hosts: files', original_nss))
 assert 'hosts: files' in nss.read_text()
 hosts.write_text('')
 failed=invoke('deploy',False)
 assert 'mariadb-install-db' in failed['message'],failed
 assert "nor 'localhost' could be looked up" in (logs/'operation.log').read_text()
 assert not (state/'active.json').exists() and (state/'import.sql').read_bytes()==dump
 assert all((source/name).read_bytes()==content for name,content in original_binaries.items())
 resolved=subprocess.run(['proot','-r','/','-b',str(repo/'server/hosts')+':/etc/hosts','/usr/bin/resolveip','localhost'],capture_output=True,text=True,timeout=30,env=dict(os.environ,PROOT_NO_SECCOMP='1'))
 assert resolved.returncode==0 and '127.0.0.1' in resolved.stdout,(resolved.stdout,resolved.stderr)
 assert hosts.read_bytes()==b'', 'PRoot bind must not replace the host file'
 hosts.write_bytes((repo/'server/hosts').read_bytes())
 first=invoke('deploy',client_pair=pair,local_zones=False)['deployment'];assert first['accounts']==first['characters']==1
 assert (state/'import.sql').read_bytes()==dump
 assert all((source/name).read_bytes()==content for name,content in original_binaries.items())
finally:
 nss.write_text(original_nss)
print('PASS: empty Ubuntu hosts reproduces MariaDB initialization failure; private PRoot loopback binding resolves localhost and real SQL deployment passes without DNS',flush=True)
assert (state/'import.sql').read_bytes()==dump
assert (state/'generations'/first['generation']/'server/settings/default/network.lua').read_text()==(source/'settings/default/network.lua').read_text()
print('PASS: real MariaDB import, ARM64 binary validation, isolated settings and unchanged source SQL',flush=True)
assert 'libjemalloc.so.2 =>' in (logs/'dependencies.log').read_text()
assert 'libjemalloc.so.2 => not found' not in (logs/'dependencies.log').read_text()
print('PASS: imported ARM64 jemalloc-linked server executables resolve the installed allocator',flush=True)
# Exercise a real ELF dependency failure, not just mocked ldd text. The existing
# selected database/server pair and staged SQL must survive this failed deploy.
compat=Path('/tmp/lsb-dependency-fixture');compat.mkdir()
(compat/'library.c').write_text('int lsb_fixture_dependency(void){return 0;}\n')
(compat/'main.c').write_text('extern int lsb_fixture_dependency(void); int main(void){return lsb_fixture_dependency();}\n')
library=compat/'liblsb_import_fixture.so.1'
subprocess.run(['gcc-15','-shared','-fPIC',compat/'library.c','-Wl,-soname,liblsb_import_fixture.so.1','-o',library],check=True)
subprocess.run(['gcc-15',compat/'main.c','-L'+str(compat),'-Wl,-rpath,'+str(compat),'-l:liblsb_import_fixture.so.1','-o',compat/'xi_world'],check=True)
original=(source/'xi_world').read_bytes();shutil.copy2(compat/'xi_world',source/'xi_world')
library.rename(compat/'library.hidden');pointer=selected();imported=tree_hashes(source)
failed=invoke('deploy',False)
assert 'xi_world' in failed['message'] and 'liblsb_import_fixture.so.1' in failed['message'],failed
assert 'liblsb_import_fixture.so.1 => not found' in (logs/'dependencies.log').read_text()
assert selected()==pointer and (state/'import.sql').read_bytes()==dump and tree_hashes(source)==imported
(compat/'library.hidden').rename(library);backend.validate_binaries(source)
(source/'xi_world').write_bytes(original)
print('PASS: exact missing ELF dependency is reported; restoring the library passes without rebuilding, and failed deployment retains source/SQL/active database',flush=True)
invoke('backup');assert b'Fixture' in (state/'export.sql').read_bytes()
full_export=(state/'export.sql').read_bytes()
for value in (b'fixture_proc',b'fixture_trigger',b'fixture_event',b'fixture_view',b'0x000A0DFF275C'):assert value in full_export,value
print('PASS: full database export includes views, routines, triggers, events and binary values',flush=True)
before=selected();(state/'import.sql').write_bytes(b'NOT SQL;\n')
invoke('deploy',False);assert selected()==before
(state/'import.sql').write_bytes(dump)
print('PASS: failed SQL import retains the active server/database pair',flush=True)
second=invoke('update',client_pair=pair,local_zones=False)['deployment'];assert second['accounts']==second['characters']==1 and second['generation']!=first['generation']
assert 'false' in (state/'generations'/second['generation']/'server/tools/config.yaml').read_text()
print('PASS: source/database update uses a clone, preserves accounts/characters and disables client updating',flush=True)
invoke('rollback');assert selected()['current']==first['generation']
invoke('rollback');assert selected()['current']==second['generation']
print('PASS: rollback switches matching server/database generations in both directions',flush=True)
# A new source import must have no influence on a SQL-only restore. The active
# generation supplies the binaries, scripts, settings and recorded client pair.
(source/'xi_map').write_bytes(b'new imported source binary must not be used')
(source/'scripts/new-import.lua').write_text('not part of the deployed revision')
source_before=tree_hashes(source);old_server=state/'generations'/second['generation']/'server';old_hashes=tree_hashes(old_server)
(state/'import.sql').write_bytes(full_export+b"\nINSERT INTO chars VALUES (2,'Restored');\n")
restored=invoke('restore-db',build=True,local_zones=True)['deployment']
assert restored['generation']!=second['generation'] and selected()['previous']==second['generation']
assert restored['accounts']==1 and restored['characters']==2
for key in ('binaries','client_pair','source_hashes','expected_client','local_zones'):assert restored[key]==second[key],key
assert restored['restored_from_generation']==second['generation']
restored_server=state/'generations'/restored['generation']/'server'
assert not (restored_server/'scripts/new-import.lua').exists()
assert tree_hashes(source)==source_before and tree_hashes(old_server)==old_hashes
values=query_generation(restored['generation'],"SELECT COUNT(*) FROM chars; SELECT zoneip FROM zone_settings; SELECT HEX(content) FROM fixture_blobs WHERE id=1; SELECT COUNT(*) FROM fixture_view; CALL fixture_proc(); INSERT INTO fixture_blobs VALUES(2,0x0102); SELECT id FROM fixture_audit; SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE() AND EVENT_NAME='fixture_event'; SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='update_marker';")
assert values.splitlines()==['2','192.0.2.5','000A0DFF275C','1','000A0DFF275C','2','1','0'],values
print('PASS: SQL-only restore preserves binaries/client pairing/source, restores objects and counts, and runs no build or migrations',flush=True)
before=selected();(state/'import.sql').write_bytes(b'NOT SQL;\n')
invoke('restore-db',False);assert selected()==before
assert query_generation(restored['generation'],'SELECT COUNT(*) FROM chars;').strip()=='2'
invoke('rollback');assert selected()['current']==second['generation']
assert query_generation(second['generation'],'SELECT COUNT(*) FROM chars;').strip()=='1'
invoke('rollback');assert selected()['current']==restored['generation']
print('PASS: failed restore leaves the active database usable; rollback retains the previous matching server/database',flush=True)
password=b'Private account fixture 42!';payload=b'LSBACCOUNT1\nNewPlayer\n'+password+b'\n'
created=invoke('create-account',payload=payload);assert created['account_id']==1000 and created['accounts']==2 and created['characters']==2
record=query_generation(restored['generation'],"SELECT id,password,status,priv FROM accounts WHERE login='NewPlayer';").strip().split('\t')
assert record[0]=='1000' and record[2:]==['1','1']
import bcrypt
assert bcrypt.checkpw(password,record[1].encode('ascii'))
invoke('create-account',False,payload=b'LSBACCOUNT1\nnewplayer\nDifferent secret\n')
assert query_generation(restored['generation'],"SELECT COUNT(*) FROM accounts; SELECT password FROM accounts WHERE login='NewPlayer';").splitlines()==['2',record[1]]
receipt=json.loads((state/'generations'/restored['generation']/'deployment.json').read_text());assert receipt['accounts']==2 and receipt['characters']==2
for folder in (run,logs):
 for path in folder.rglob('*'):
  if path.is_file():
   contents=path.read_bytes();assert password not in contents and record[1].encode('ascii') not in contents,path
assert tree_hashes(source)==source_before and tree_hashes(old_server)==old_hashes
print('PASS: private bcrypt account creation, ordinary privileges, duplicate protection and no password/hash in request, status or logs',flush=True)
(run/'stop').unlink(missing_ok=True);(run/'request.json').write_text(json.dumps(dict(action='start')))
process=subprocess.Popen([sys.executable,manager])
try:
 deadline=time.monotonic()+120;observed_script_loading=False
 while time.monotonic()<deadline:
  if process.poll() is not None:raise AssertionError((run/'status.json').read_text())
  try:
   report=json.loads((run/'status.json').read_text());startup=report.get('startup',{})
   if report.get('phase')=='starting' and startup.get('login_port_reachable') and startup.get('stage')=='Loading Mob scripts':
    assert 'xi_map' in startup['pending_processes'];observed_script_loading=True
   if report.get('phase')=='running':
    assert observed_script_loading,'Login port must remain starting while map scripts load'
    assert startup['pending_processes']==[] and set(startup['ready_processes'])==set(backend.PROCESSES)
    break
  except (OSError,ValueError):pass
  time.sleep(.2)
 else:raise AssertionError('Server readiness timeout')
 (run/'stop').touch();assert process.wait(timeout=50)==0
 assert json.loads((run/'status.json').read_text())['phase']=='stopped'
finally:
 if process.poll() is None:process.terminate();process.wait(timeout=50)
assert (Path('/client')/'sentinel').read_bytes()==b'accepted client kept'
for name in ('xi_connect','xi_map','xi_search','xi_world'):
 assert 'allocator=jemalloc ' in (logs/(name+'.log')).read_text(),name
 assert 'bfd=2.45 object verified' in (logs/(name+'.log')).read_text(),name
print('PASS: managed database + four ARM64 processes recognize current timed readiness, wait for map scripts after the login port opens, and stop without changing client data',flush=True)

# Exercise the complete Android session transport with a real, cleanly stopped
# MariaDB directory. A copy models the exporting app; a distinct destination
# models the restore-test package. No SQL re-import or database migration may
# hide a broken physical database restore.
invoke('backup')
original_state=tree_hashes(state);original_source=tree_hashes(source)
session=Path('/tmp/lsb-session-roundtrip');session.mkdir()
session_source=session/'source';session_target=session/'restore-test'
files=session_source/'files';managed=session_source/'managed'
files.mkdir(parents=True);managed.mkdir()
shutil.copytree(state,files/'server-runtime/state',symlinks=True)
shutil.copytree(source,managed/'server/current',symlinks=True)
shutil.copytree(Path('/client'),managed/'session/current/client',symlinks=True)

def archived_objects(root):
 result={}
 for path in root.rglob('*'):
  info=path.lstat();name=str(path.relative_to(root))
  if path.is_symlink():result[name]=('link',os.readlink(path))
  elif path.is_file():result[name]=('file',info.st_mode&0o777,hashlib.sha256(path.read_bytes()).hexdigest())
  elif path.is_dir():result[name]=('directory',info.st_mode&0o777)
  else:raise AssertionError('Unexpected live object in stopped session: '+name)
 return result

snapshot=archived_objects(session_source)
classes=session/'classes';classes.mkdir()
core=repo/'app/src/main/java/io/github/russianranger/lsb/core'
subprocess.run(['javac','--release','8','-d',classes,*sorted(core.glob('*.java')),repo/'tests/server/SessionArchiveRoundTrip.java'],check=True,timeout=120)
subprocess.run(['java','-ea','-cp',classes,'SessionArchiveRoundTrip',session_source,session_target,session/'complete-session.zip'],check=True,timeout=300)
assert archived_objects(session_target)==snapshot,'Full session changed file contents, modes or links'
assert archived_objects(session_source)==snapshot,'Archive modified its exporting app'

previous_paths=(backend.STATE,backend.RUN,backend.LOGS)
backend.STATE=session_target/'files/server-runtime/state'
# Keep Unix socket paths short; on Android these guest paths remain /server-run.
backend.RUN=session/'restored-run';backend.LOGS=session/'restored-logs'
backend.RUN.mkdir();backend.LOGS.mkdir()
creds=None
try:
 generation=backend.current()
 assert generation.name==restored['generation']
 meta=json.loads((generation/'deployment.json').read_text())
 assert meta['accounts']==2 and meta['characters']==2
 backend.validate_binaries(generation/'server')
 creds=backend.start_database(generation)
 values=backend.sql("SELECT COUNT(*) FROM accounts; SELECT COUNT(*) FROM chars; SELECT HEX(content) FROM fixture_blobs WHERE id=1; SELECT COUNT(*) FROM fixture_view; CALL fixture_proc(); SELECT COUNT(*) FROM information_schema.EVENTS WHERE EVENT_SCHEMA=DATABASE() AND EVENT_NAME='fixture_event'; INSERT INTO fixture_blobs VALUES(3,0xAABB); SELECT COUNT(*) FROM fixture_audit WHERE id=3; SELECT COUNT(*) FROM fixture_blobs;",creds['game'],'lsb',meta['database'])
 assert values.splitlines()==['2','2','000A0DFF275C','2','000A0DFF275C','0102','1','1','3'],values
 restored_record=backend.sql("SELECT password FROM accounts WHERE login='NewPlayer';",creds['game'],'lsb',meta['database']).strip()
 assert restored_record==record[1] and bcrypt.checkpw(password,restored_record.encode('ascii'))
finally:
 try:
  if creds is not None:backend.stop_database(creds)
  else:backend.stop_children()
 finally:backend.STATE,backend.RUN,backend.LOGS=previous_paths
assert tree_hashes(state)==original_state and tree_hashes(source)==original_source
assert archived_objects(session_source)==snapshot
assert (session_target/'managed/session/current/client/sentinel').read_bytes()==b'accepted client kept'
assert b'NewPlayer' in (session_target/'files/server-runtime/state/export.sql').read_bytes()
print('PASS: complete-session restore boots physical MariaDB at a new app path, preserves accounts/passwords/characters/blobs/routines/events/triggers/client/server, and restored-only writes leave the original app unchanged',flush=True)

# The Build tab reuses a checked build and stages its database separately.
# Use the phone build's exact upstream dbtool and account schemas, with tiny
# synthetic world/player fixtures. Pin every downloaded byte so this exercises
# the real account foreign key and CLI imports without changing with upstream.
import urllib.request
build_root=state/'source-build/server'
if build_root.parent.exists():shutil.rmtree(build_root.parent)
backend.snapshot_source(state/'generations'/selected()['current']/'server',build_root,recover_build_binaries=False)
upstream_revision='6d5a5137024e21602e37032f6e55a682971329be'
for relative,expected in {
 'tools/dbtool.py':'e4ca6e9945c00d58d156091e28fc65d99ce17ff9cb8cd8cc06c4c66f58108018',
 'sql/accounts.sql':'61aab89643ee8f5e6fd4232ea3cf50e1befa0b78a6e807f6e88345d7b5d6fa1f',
 'sql/accounts_files.sql':'6f332ce589194c113438c3f47bc6bb6b29bd3b72324f9436376e0b40967cf1a7',
}.items():
 with urllib.request.urlopen('https://raw.githubusercontent.com/LandSandBoat/server/'+upstream_revision+'/'+relative,timeout=60) as response:
  content=response.read()
 assert hashlib.sha256(content).hexdigest()==expected,relative
 (build_root/relative).write_bytes(content)
(build_root/'tools/requirements.txt').write_text('mariadb\npyyaml\nGitPython\ncolorama\n')
(build_root/'tools/migrations').mkdir(exist_ok=True)
(build_root/'modules').mkdir(exist_ok=True);(build_root/'modules/init.txt').write_text('')
# Preserve existing accounts/chars during updates; the upstream tool treats
# these filenames as protected. Fresh setup imports the real account tables.
(build_root/'sql/chars.sql').write_text("DROP TABLE IF EXISTS chars; CREATE TABLE chars(charid INT PRIMARY KEY,charname VARCHAR(32));\n")
(build_root/'sql/zone_settings.sql').write_text("DROP TABLE IF EXISTS zone_settings; CREATE TABLE zone_settings(zoneid INT,zoneip VARCHAR(32),zoneport INT); INSERT INTO zone_settings VALUES(42,'192.0.2.42',54230);\n")
backend.validate_binaries(build_root)
receipt=dict(format=1,state='passed',allocator='jemalloc',jobs=2,source=backend.source_info(build_root),binaries=backend.validate_jemalloc(build_root))
backend.atomic(build_root/'android-build.json',receipt)
adopted=invoke('adopt-build')['build'];build_id=adopted['build_id']
assert adopted['binaries']==receipt['binaries'] and 'repository' not in adopted['selected_source']
active_before=selected()['current'];pointer_before=selected()
# Fetching a different source now must not influence any deployment step.
(source/'sql/fixture.sql').write_text('THIS IS NOT VALID SQL; newly fetched source must not be used')
(source/'settings/default/login.lua').write_text("CLIENT_VER = 'different-fetched-client',\n")
built_hashes={name:hashlib.sha256((build_root/name).read_bytes()).hexdigest() for name in backend.PROCESSES}

def stage_build_fixture(mode):
 report=invoke('stage-build',build_id=build_id,database_mode=mode)['deployment']
 assert report['build_id']==build_id and report['selected_source']==adopted['selected_source']
 assert report['binaries']==built_hashes and selected()['current']==active_before
 return report,dict(build_id=build_id,generation=report['generation'])

preserved,selection=stage_build_fixture('copy-current')
assert preserved['accounts']==2 and preserved['characters']==2
assert query_generation(preserved['generation'],"SELECT zoneid FROM zone_settings; SELECT COUNT(*) FROM chars;").splitlines()==['42','2']
invoke('check-staged',**selection)
assert selected()==pointer_before
# A real account mutation invalidates a staged preserving copy, even though
# the active generation ID stays the same.
invoke('create-account',payload=b'LSBACCOUNT1\nPipelinePlayer\nPrivate pipeline fixture 42!\n')
assert json.loads((state/'staged.json').read_text())['stale_database']
invoke('check-staged',False,**selection)
preserved,selection=stage_build_fixture('copy-current')
assert preserved['accounts']==3 and preserved['characters']==2
invoke('check-staged',**selection);invoke('deploy-staged',**selection)
assert selected()==dict(current=preserved['generation'],previous=active_before)
assert query_generation(preserved['generation'],"SELECT COUNT(*) FROM accounts; SELECT COUNT(*) FROM chars; SELECT zoneid FROM zone_settings;").splitlines()==['3','2','42']
invoke('rollback');assert selected()['current']==active_before
print('PASS: exact upstream migrate/update preserve accounts, reuse the recorded jemalloc build, reject stale player copies and deploy/rollback atomically',flush=True)

fresh,selection=stage_build_fixture('fresh')
assert fresh['accounts']==0 and fresh['characters']==0
assert query_generation(fresh['generation'],"SELECT COUNT(*) FROM accounts; SELECT COUNT(*) FROM chars; SELECT zoneid FROM zone_settings;").splitlines()==['0','0','42']
invoke('deploy-staged',False,**selection)
invoke('check-staged',**selection)
staged_binary=state/'generations'/fresh['generation']/'server/xi_map'
original_binary=staged_binary.read_bytes();staged_binary.write_bytes(original_binary+b'changed')
invoke('deploy-staged',False,**selection);assert selected()['current']==active_before
staged_binary.write_bytes(original_binary)
invoke('check-staged',**selection);invoke('deploy-staged',**selection)
assert selected()==dict(current=fresh['generation'],previous=active_before)
assert query_generation(fresh['generation'],"SELECT COUNT(*) FROM accounts;").strip()=='0'
invoke('rollback');assert selected()['current']==active_before
print('PASS: exact upstream fresh setup creates the build SQL database, mandatory checks reject tampering, and replacement keeps the old server/database',flush=True)

(state/'import.sql').write_bytes(dump)
imported,selection=stage_build_fixture('import')
assert imported['accounts']==1 and imported['characters']==1
assert imported['database_input_sha256']==hashlib.sha256(dump).hexdigest()
invoke('check-staged',**selection);invoke('deploy-staged',**selection)
assert query_generation(imported['generation'],"SELECT COUNT(*) FROM accounts; SELECT COUNT(*) FROM chars; SELECT HEX(content) FROM fixture_blobs WHERE id=1;").splitlines()==['1','1','000A0DFF275C']
invoke('rollback');assert selected()['current']==active_before
assert (Path('/client')/'sentinel').read_bytes()==b'accepted client kept'
print('PASS: imported SQL stages and replaces only the selected checked generation, retains SQL objects and allows rollback',flush=True)

# The phone's imported legacy schema used signed AUTO_INCREMENT account IDs.
# Its rows are deliberately NOT a fixture: reproduce the DDL with synthetic
# accounts/passwords/characters already created above, and preserve the source
# database while repairing only a new staged generation.
def account_schema(generation):
 return query_generation(generation,"SELECT COLUMN_TYPE,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='accounts' AND COLUMN_NAME='id'; SELECT AUTO_INCREMENT FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='accounts';").splitlines()

def player_snapshot(generation):
 return query_generation(generation,"SELECT * FROM accounts ORDER BY id; SELECT * FROM chars ORDER BY charid; SELECT id,HEX(content) FROM fixture_blobs ORDER BY id;")

def generation_cli(generation,statement):
 folder=state/'generations'/generation;creds=None
 try:
  creds=backend.start_database(folder)
  return subprocess.run(['mariadb',backend.cnf('lsb',creds['game']),'--batch','--skip-column-names','xidb'],input=statement,capture_output=True,text=True,timeout=60)
 finally:
  if creds is not None:backend.stop_database(creds)
  else:backend.stop_children()

query_generation(active_before,'ALTER TABLE accounts MODIFY COLUMN id INT NOT NULL AUTO_INCREMENT; ALTER TABLE accounts AUTO_INCREMENT=4000;')
legacy_schema=account_schema(active_before)
assert 'unsigned' not in legacy_schema[0] and legacy_schema[0].endswith('\tauto_increment') and legacy_schema[1]=='4000',legacy_schema
legacy_rows=player_snapshot(active_before);legacy_pointer=selected()
# Same import envelope as upstream dbtool: disabling checks does not make
# mismatched signed/unsigned foreign-key definitions legal in InnoDB.
reproduced=generation_cli(active_before,'SET foreign_key_checks=0;\n'+(build_root/'sql/accounts_files.sql').read_text())
assert reproduced.returncode!=0 and 'ERROR 1005' in reproduced.stderr and 'errno: 150' in reproduced.stderr,reproduced.stderr
assert account_schema(active_before)==legacy_schema and player_snapshot(active_before)==legacy_rows
print('PASS: unpatched real MariaDB reproduces phone ERROR 1005 / errno 150 with exact upstream accounts_files.sql and synthetic legacy signed IDs',flush=True)

normalized,selection=stage_build_fixture('copy-current')
normalized_id=normalized['generation']
assert normalized['accounts']==3 and normalized['characters']==2
assert 'unsigned' in account_schema(normalized_id)[0] and account_schema(normalized_id)[1]=='4000'
assert player_snapshot(normalized_id)==legacy_rows
assert account_schema(active_before)==legacy_schema and player_snapshot(active_before)==legacy_rows and selected()==legacy_pointer
assert query_generation(normalized_id,"SELECT COLUMN_NAME,REFERENCED_TABLE_NAME,REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='accounts_files' AND REFERENCED_TABLE_NAME IS NOT NULL;").strip()=='accid\taccounts\tid'
assert query_generation(normalized_id,"INSERT INTO accounts_files(accid,path,data) VALUES(1,0x0066697874757265,0x000A0DFF275C); START TRANSACTION; DELETE FROM accounts WHERE id=1; SELECT COUNT(*) FROM accounts_files; ROLLBACK;").strip()=='0'
assert query_generation(normalized_id,'SELECT HEX(path),HEX(data) FROM accounts_files;').strip()=='0066697874757265\t000A0DFF275C'
rejected=generation_cli(normalized_id,"INSERT INTO accounts_files(accid,path,data) VALUES(2147483647,0x01,0x02);")
assert rejected.returncode!=0 and 'ERROR 1452' in rejected.stderr,rejected.stderr
assert query_generation(normalized_id,'SELECT COUNT(*) FROM accounts_files;').strip()=='1'
invoke('check-staged',**selection);invoke('deploy-staged',**selection)
new_secret=b'Preserved schema player 42!'
new_account=invoke('create-account',payload=b'LSBACCOUNT1\nAlignedPlayer\n'+new_secret+b'\n')
assert new_account['accounts']==4 and new_account['characters']==2
new_record=query_generation(normalized_id,"SELECT id,password FROM accounts WHERE login='AlignedPlayer';").strip().split('\t')
assert int(new_record[0])==new_account['account_id']==4000 and bcrypt.checkpw(new_secret,new_record[1].encode('ascii'))
assert query_generation(normalized_id,"SELECT password FROM accounts WHERE login='NewPlayer';").strip()==record[1]
assert account_schema(normalized_id)[1]=='4001'
# A second preparation must retain already existing protected account files;
# unsigned account IDs require no further schema changes or FK removal.
repeat=invoke('stage-build',build_id=build_id,database_mode='copy-current')['deployment']
assert repeat['accounts']==4 and repeat['characters']==2 and selected()['current']==normalized_id
assert player_snapshot(repeat['generation'])==player_snapshot(normalized_id)
assert account_schema(repeat['generation'])==account_schema(normalized_id)
assert query_generation(repeat['generation'],'SELECT HEX(path),HEX(data) FROM accounts_files;').strip()=='0066697874757265\t000A0DFF275C'
invoke('rollback');assert selected()['current']==active_before
assert account_schema(active_before)==legacy_schema and player_snapshot(active_before)==legacy_rows
print('PASS: legacy copy-current aligns only staged IDs, preserves account/password/character/blob values and AUTO_INCREMENT, enforces real FK/cascade, permits app account creation, and retains protected account-file blobs on repeated preparation',flush=True)

legacy_dump=dump.replace(b'id int(10) unsigned NOT NULL DEFAULT 0',b'id int(11) NOT NULL AUTO_INCREMENT',1)+b'\nALTER TABLE accounts AUTO_INCREMENT=4000;\n'
assert legacy_dump!=dump
(state/'import.sql').write_bytes(legacy_dump)
imported,selection=stage_build_fixture('import')
assert imported['accounts']==1 and imported['characters']==1
assert imported['database_input_sha256']==hashlib.sha256(legacy_dump).hexdigest()
assert 'unsigned' in account_schema(imported['generation'])[0] and account_schema(imported['generation'])[1]=='4000'
assert query_generation(imported['generation'],"SELECT id,login FROM accounts; SELECT charid,charname FROM chars; SELECT HEX(content) FROM fixture_blobs WHERE id=1;").splitlines()==['1\tfixture','1\tFixture','000A0DFF275C']
invoke('check-staged',**selection);invoke('deploy-staged',**selection)
assert (state/'import.sql').read_bytes()==legacy_dump
invoke('rollback');assert selected()['current']==active_before
print('PASS: imported legacy SQL receives the same isolated compatibility migration while its original dump and deployed database remain unchanged',flush=True)

safe_pointer=selected();safe_stage=(state/'staged.json').read_bytes()
for label,extra_sql,expected_error in (
 ('negative IDs',b"INSERT INTO accounts(id,login) VALUES(-1,'Negative');\n",'negative account IDs'),
 ('custom ID width',b'ALTER TABLE accounts MODIFY COLUMN id BIGINT NOT NULL AUTO_INCREMENT;\n','unsupported column definition'),
 ('existing foreign key',b'CREATE TABLE custom_account_link(accid INT NOT NULL,FOREIGN KEY(accid) REFERENCES accounts(id));\n','existing foreign-key dependencies'),
):
 unsafe_dump=legacy_dump+extra_sql;(state/'import.sql').write_bytes(unsafe_dump)
 invoke('stage-build',False,build_id=build_id,database_mode='import')
 assert expected_error in (logs/'operation.log').read_text(),label
 assert selected()==safe_pointer and (state/'staged.json').read_bytes()==safe_stage
 assert account_schema(active_before)==legacy_schema and player_snapshot(active_before)==legacy_rows
 assert (state/'import.sql').read_bytes()==unsafe_dump
(state/'import.sql').write_bytes(legacy_dump)
assert (Path('/client')/'sentinel').read_bytes()==b'accepted client kept'
print('PASS: negative IDs, unsupported custom columns and existing foreign-key dependencies fail closed without selecting a partial stage or changing active player data/client/import bytes',flush=True)

# SQL-only checkpoints retain exact player/world objects without carrying the
# client or runtimes. Their compatibility check precedes any database import.
checkpoint_base=selected()['current'];checkpoint_rows=player_snapshot(checkpoint_base)
checkpoint=invoke('create-checkpoint',checkpoint_keep=2)['checkpoint']
checkpoint_file=state/'checkpoints'/checkpoint['checkpoint_id']/'database.sql.gz'
assert checkpoint_file.is_file() and checkpoint['characters']==2 and checkpoint['accounts']==3
import_before=(state/'import.sql').read_bytes();pointer_before=selected();compressed_before=checkpoint_file.read_bytes()
checkpoint_file.write_bytes(compressed_before[:-1]+bytes([compressed_before[-1]^1]))
invoke('restore-checkpoint',False,checkpoint_id=checkpoint['checkpoint_id']);assert selected()==pointer_before
checkpoint_file.write_bytes(compressed_before)
query_generation(checkpoint_base,"INSERT INTO chars VALUES (990,'AfterCheckpoint');")
checkpoint_restored=invoke('restore-checkpoint',checkpoint_id=checkpoint['checkpoint_id'])['deployment']
assert selected()==dict(current=checkpoint_restored['generation'],previous=checkpoint_base)
assert player_snapshot(checkpoint_restored['generation'])==checkpoint_rows
assert query_generation(checkpoint_base,'SELECT COUNT(*) FROM chars;').strip()=='3'
assert (state/'import.sql').read_bytes()==import_before
assert query_generation(checkpoint_restored['generation'],"SELECT HEX(content) FROM fixture_blobs WHERE id=1; SELECT COUNT(*) FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='fixture_proc';").splitlines()==['000A0DFF275C','1']
assert not list(run.glob('checkpoint-*.sql'))
invoke('rollback');assert selected()['current']==checkpoint_base
# A changed schema definition must reject this checkpoint before activation.
schema_file=state/'generations'/checkpoint_base/'server/sql/fixture.sql';schema_before=schema_file.read_bytes();schema_file.write_bytes(schema_before+b'\n-- changed schema revision\n')
checkpoint_pointer=selected();invoke('restore-checkpoint',False,checkpoint_id=checkpoint['checkpoint_id']);assert selected()==checkpoint_pointer
schema_file.write_bytes(schema_before)
newer=invoke('create-checkpoint',checkpoint_keep=2)['checkpoint'];newest=invoke('create-checkpoint',checkpoint_keep=2)['checkpoint']
assert not checkpoint_file.exists() and len(list((state/'checkpoints').iterdir()))==2
assert (state/'checkpoints'/newer['checkpoint_id']/'database.sql.gz').is_file()
assert (state/'checkpoints'/newest['checkpoint_id']/'database.sql.gz').is_file()
print('PASS: real SQL checkpoint restore preserves player/world objects, current progress remains recoverable via rollback, corrupt/mismatched checkpoints fail closed, import stays intact, and retention prunes only after successful saves',flush=True)
