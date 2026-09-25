"""Real MariaDB deployment/recovery with synthetic ARM64 server processes."""
from pathlib import Path
import hashlib, importlib.util, json, os, shutil, subprocess, sys, time
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
#include <stdio.h>
#include <jemalloc/jemalloc.h>
#include <bfd.h>
static volatile sig_atomic_t done;static void stop(int s){(void)s;done=1;}
int main(int argc,char**argv){(void)argc;bfd_init();bfd* input=bfd_openr(argv[0],NULL);if(!input||!bfd_check_format(input,bfd_object)||!bfd_close(input))return 10;puts("bfd=2.45 object verified");const char* version=NULL;size_t length=sizeof(version);if(mallctl("version",&version,&length,NULL,0))return 9;printf("allocator=jemalloc %s\\n",version);fflush(stdout);signal(SIGTERM,stop);int fd=-1;if(strstr(argv[0],"xi_connect")){fd=socket(AF_INET,SOCK_STREAM,0);int one=1;setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));struct sockaddr_in a={0};a.sin_family=AF_INET;a.sin_port=htons(54231);a.sin_addr.s_addr=htonl(0x7f000001);if(bind(fd,(void*)&a,sizeof(a))||listen(fd,4))return 8;}while(!done)sleep(1);if(fd>=0)close(fd);return 0;}
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
first=invoke('deploy',client_pair=pair,local_zones=False)['deployment'];assert first['accounts']==first['characters']==1
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
 deadline=time.monotonic()+120
 while time.monotonic()<deadline:
  if process.poll() is not None:raise AssertionError((run/'status.json').read_text())
  try:
   if json.loads((run/'status.json').read_text()).get('phase')=='running':break
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
print('PASS: managed database + four server processes start, report readiness and stop without changing client data',flush=True)
