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
mode=config.get('mode','update')
if mode not in ('fresh','update'):raise ValueError('Unknown database preparation mode')
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
    def executemany(self,*args,**kwargs):
        try:return self.cursor.executemany(*args,**kwargs)
        except Exception:
            failed.append('Database statement failed');raise
    def __iter__(self):return iter(self.cursor)
class Connection:
    def __init__(self,connection):self.connection=connection
    def __getattr__(self,name):return getattr(self.connection,name)
    def cursor(self,*args,**kwargs):return Cursor(self.connection.cursor(*args,**kwargs))
def local_connect(*args,**kwargs):
    kwargs.pop('host',None);kwargs.pop('port',None);kwargs['unix_socket']=sock
    try:return Connection(original(*args,**kwargs))
    except Exception:
        failed.append('Database connection failed');raise
mariadb.connect=local_connect
original_run=subprocess.run
def checked_run(*args,**kwargs):
    command=args[0] if args else kwargs.get('args',[])
    is_sql=isinstance(command,(list,tuple)) and command and Path(str(command[0])).name in ('mysql','mariadb','mysqldump','mariadb-dump')
    if is_sql:
        # An explicit upstream -P can select TCP despite MYSQL_UNIX_PORT.
        # This generation intentionally has no TCP listener: force its private
        # socket for every CLI statement as well as Python connector calls.
        if kwargs.get('shell'):raise RuntimeError('Database tool SQL commands must use direct argument lists')
        cleaned=[command[0]];index=1
        while index<len(command):
            value=str(command[index])
            if value in ('-h','--host','-P','--port','-S','--socket','--protocol'):
                index+=2;continue
            if value.startswith(('--host=','--port=','--socket=','--protocol=')) or (len(value)>2 and value[:2] in ('-h','-P','-S')):
                index+=1;continue
            cleaned.append(command[index]);index+=1
            if value in ('-e','--execute') and index<len(command):
                cleaned.append(command[index]);index+=1
        command=[*cleaned,'--protocol=SOCKET','--socket='+sock]
        if args:args=(command,*args[1:])
        else:kwargs['args']=command
    result=original_run(*args,**kwargs)
    if is_sql and result.returncode:failed.append('Database import command failed')
    return result

subprocess.run=checked_run
# The CLI imports inside dbtool use mysql; its defaults file supplies the same
# socket and restricted database user. No client-update task is invoked.
os.environ['MYSQL_UNIX_PORT']=sock
sys.path.insert(0,str(Path(root)/'tools'))
# dbtool closes a successful CLI action with quit(); catch that success so
# migrate cannot terminate this wrapper before the full update runs.
# Keep the selected build's client setting: some upstream versions rewrite it
# even when auto_update_client is False.
login=Path(root)/'settings/login.lua'
original_login=login.read_bytes() if login.is_file() else None
migration_errors=Path(root)/'tools/migration_errors.log'
migration_errors.unlink(missing_ok=True)
try:
    for action in (('setup',) if mode=='fresh' else ('migrate','update')):
        sys.argv=[str(Path(root)/'tools/dbtool.py'),action,'full']
        scope=runpy.run_path(sys.argv[0],run_name='lsb_dbtool')
        main=scope.get('main')
        if not callable(main):raise RuntimeError('Selected source has no supported database tool')
        namespace=main.__globals__
        original_connect=namespace.get('connect')
        if callable(original_connect):
            def checked_connect():
                connected=original_connect()
                if connected is False:raise RuntimeError('The source database tool could not connect')
                return connected
            namespace['connect']=checked_connect
        original_version=namespace.get('write_version')
        if callable(original_version):
            def silent_version(silent=True):return original_version(silent=True)
            namespace['write_version']=silent_version
        try:
            if mode=='fresh':
                # Manager already created the isolated database and restricted
                # user; CLI setup would unconditionally CREATE DATABASE again.
                for name in ('fetch_credentials','fetch_configs','fetch_versions','setup_db'):
                    function=namespace.get(name)
                    if not callable(function):raise RuntimeError('This source database tool cannot create a fresh database')
                    function()
                if callable(namespace.get('run_all_migrations')):namespace['run_all_migrations'](True)
                if callable(namespace.get('close')):namespace['close']()
            else:main()
        except SystemExit as error:
            if error.code not in (None,0):raise RuntimeError('The source database tool exited with an error') from None
        if failed:raise RuntimeError('The upstream database tool reported an SQL error; staged update will not activate')
        if migration_errors.is_file() and migration_errors.stat().st_size:
            raise RuntimeError('The upstream database tool reported migration errors; staged update will not activate')
finally:
    if original_login is None:login.unlink(missing_ok=True)
    else:login.write_bytes(original_login)
