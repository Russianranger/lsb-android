"""Real MariaDB deployment/recovery with synthetic ARM64 server processes."""
from pathlib import Path
import json, os, shutil, subprocess, sys, time

repo=Path(__file__).resolve().parents[2]
state=Path('/state');run=Path('/server-run');logs=Path('/server-logs');source=Path('/input/server')
for p in (state,run,logs,source,Path('/client')):p.mkdir(parents=True,exist_ok=True)
(Path('/client')/'sentinel').write_bytes(b'accepted client kept')
for folder in ('src','sql','tools','settings/default','scripts','navmeshes','ximeshes'):(source/folder).mkdir(parents=True,exist_ok=True)
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
static volatile sig_atomic_t done;static void stop(int s){(void)s;done=1;}
int main(int argc,char**argv){(void)argc;signal(SIGTERM,stop);int fd=-1;if(strstr(argv[0],"xi_connect")){fd=socket(AF_INET,SOCK_STREAM,0);int one=1;setsockopt(fd,SOL_SOCKET,SO_REUSEADDR,&one,sizeof(one));struct sockaddr_in a={0};a.sin_family=AF_INET;a.sin_port=htons(54231);a.sin_addr.s_addr=htonl(0x7f000001);if(bind(fd,(void*)&a,sizeof(a))||listen(fd,4))return 8;}while(!done)sleep(1);if(fd>=0)close(fd);return 0;}
''')
subprocess.run(['gcc-15','-O2',program,'-o','/tmp/server-stub'],check=True)
for name in ('xi_connect','xi_map','xi_search','xi_world'):shutil.copy2('/tmp/server-stub',source/name)
dump=b"CREATE TABLE accounts(id INT PRIMARY KEY,name VARCHAR(32)); INSERT INTO accounts VALUES(1,'fixture'); CREATE TABLE chars(charid INT PRIMARY KEY,charname VARCHAR(32)); INSERT INTO chars VALUES(1,'Fixture'); CREATE TABLE zone_settings(zoneid INT,zoneip VARCHAR(32),zoneport INT); INSERT INTO zone_settings VALUES(1,'192.0.2.5',54231);\n"
(state/'import.sql').write_bytes(dump)
manager=repo/'server/manager.py'
def invoke(action,success=True,**extra):
 (run/'stop').unlink(missing_ok=True)
 (run/'request.json').write_text(json.dumps(dict(action=action,database='xidb',build=False,jobs=2,**extra)))
 result=subprocess.run([sys.executable,manager],timeout=600)
 report=json.loads((run/'status.json').read_text())
 if success:assert result.returncode==0,report
 else:assert result.returncode!=0,report
 return report
def selected():return json.loads((state/'active.json').read_text())
first=invoke('deploy')['deployment'];assert first['accounts']==first['characters']==1
assert (state/'import.sql').read_bytes()==dump
assert (state/'generations'/first['generation']/'server/settings/default/network.lua').read_text()==(source/'settings/default/network.lua').read_text()
print('PASS: real MariaDB import, ARM64 binary validation, isolated settings and unchanged source SQL',flush=True)
invoke('backup');assert b'Fixture' in (state/'export.sql').read_bytes()
print('PASS: full database export from the managed generation',flush=True)
before=selected();(state/'import.sql').write_bytes(b'NOT SQL;\n')
invoke('deploy',False);assert selected()==before
(state/'import.sql').write_bytes(dump)
print('PASS: failed SQL import retains the active server/database pair',flush=True)
second=invoke('update')['deployment'];assert second['accounts']==second['characters']==1 and second['generation']!=first['generation']
assert 'false' in (state/'generations'/second['generation']/'server/tools/config.yaml').read_text()
print('PASS: source/database update uses a clone, preserves accounts/characters and disables client updating',flush=True)
invoke('rollback');assert selected()['current']==first['generation']
invoke('rollback');assert selected()['current']==second['generation']
print('PASS: rollback switches matching server/database generations in both directions',flush=True)
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
print('PASS: managed database + four server processes start, report readiness and stop without changing client data',flush=True)
