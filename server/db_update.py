"""Run the imported revision's dbtool against the isolated staging socket."""
import os
from pathlib import Path
import runpy
import sys
import mariadb
import json
import subprocess

root,sock,credentials=sys.argv[1:]
config=json.loads(Path(credentials).read_text());database=config['database'];password=config['password']
os.environ.update(XI_NETWORK_SQL_HOST='localhost',XI_NETWORK_SQL_DATABASE=database,XI_NETWORK_SQL_LOGIN='lsb',XI_NETWORK_SQL_PASSWORD=password)
original=mariadb.connect
failed=[]
class Cursor:
    def __init__(self,cursor):self.cursor=cursor
    def __getattr__(self,name):return getattr(self.cursor,name)
    def execute(self,*args,**kwargs):
        try:return self.cursor.execute(*args,**kwargs)
        except Exception:
            failed.append('Database statement failed');raise
    def __iter__(self):return iter(self.cursor)
class Connection:
    def __init__(self,connection):self.connection=connection
    def __getattr__(self,name):return getattr(self.connection,name)
    def cursor(self,*args,**kwargs):return Cursor(self.connection.cursor(*args,**kwargs))
def local_connect(*args,**kwargs):
    kwargs.pop('host',None);kwargs.pop('port',None);kwargs['unix_socket']=sock
    return Connection(original(*args,**kwargs))
mariadb.connect=local_connect
original_run=subprocess.run
def checked_run(*args,**kwargs):
    result=original_run(*args,**kwargs)
    command=args[0] if args else kwargs.get('args',[])
    if isinstance(command,(list,tuple)) and command and Path(str(command[0])).name in ('mysql','mariadb') and result.returncode:
        failed.append('Database import command failed')
    return result
subprocess.run=checked_run
# The CLI imports inside dbtool use mysql; its defaults file supplies the same
# socket and restricted database user. No client-update task is invoked.
os.environ['MYSQL_UNIX_PORT']=sock
sys.path.insert(0,str(Path(root)/'tools'))
for action in ('migrate','update'):
    sys.argv=[str(Path(root)/'tools/dbtool.py'),action,'full']
    scope=runpy.run_path(sys.argv[0],run_name='lsb_dbtool')
    scope['main']()
    if failed:raise RuntimeError('The upstream database tool reported an SQL error; staged update will not activate')
