"""Run the imported revision's dbtool against the isolated staging socket."""
import os
from pathlib import Path
import runpy
import sys
import mariadb
import json
import subprocess
import hashlib
import re

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

def align_legacy_account_id():
    """Repair the observed signed legacy key only in this isolated generation.

    dbtool intentionally preserves existing accounts tables. Older imported
    servers can have a signed AUTO_INCREMENT id even though current source
    declares it unsigned, preventing the new accounts_files foreign key.
    Keep the imported rows and identity allocation policy; never reimport the
    protected table, remove its constraints, or generalize to other columns.
    """
    sql=Path(root)/'sql'
    child=sql/'accounts_files.sql'
    parent=sql/'accounts.sql'
    if not child.is_file():return
    def source_sql(path):
        return re.sub(r'/\*.*?\*/|--[^\n]*','',path.read_text(),flags=re.S)
    def definition(path,name):
        declarations=re.findall(r'CREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+`'+name+
                                r'`\s*\(([^;]*?)\)\s*ENGINE\s*=\s*InnoDB\b[^;]*;',source_sql(path),re.I|re.S)
        return declarations[0] if len(declarations)==1 else ''
    child_sql=definition(child,'accounts_files')
    if not (re.search(r'^\s*`accid`\s+int(?:\(\d+\))?\s+unsigned\b',child_sql,re.I|re.M)
            and re.search(r'FOREIGN\s+KEY\s*\(\s*`accid`\s*\)\s+REFERENCES\s+`accounts`\s*\(\s*`id`\s*\)',child_sql,re.I)):
        return
    if not parent.is_file() or not re.search(r'^\s*`id`\s+int(?:\(\d+\))?\s+unsigned\b',definition(parent,'accounts'),re.I|re.M):
        raise RuntimeError('Selected source has incompatible accounts.id and accounts_files.accid definitions')
    connection=local_connect(user='lsb',password=password,database=database)
    cursor=connection.cursor()
    def column():
        cursor.execute('SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT FROM information_schema.COLUMNS '
                       'WHERE TABLE_SCHEMA=? AND TABLE_NAME=\'accounts\' AND COLUMN_NAME=\'id\'',(database,))
        return cursor.fetchone()
    def table():
        cursor.execute('SELECT ENGINE, AUTO_INCREMENT FROM information_schema.TABLES '
                       'WHERE TABLE_SCHEMA=? AND TABLE_NAME=\'accounts\'',(database,))
        return cursor.fetchone()
    def fingerprint():
        digest=hashlib.sha256();count=0
        cursor.execute('SELECT * FROM `accounts` ORDER BY `id`')
        for row in cursor:
            payload=repr(tuple(row)).encode('utf-8')
            digest.update(len(payload).to_bytes(8,'big'));digest.update(payload);count+=1
        return count,digest.hexdigest()
    try:
        before=column()
        # Missing protected tables are created by the selected upstream tool.
        if before is None:return
        kind,nullable,default,extra,comment=before
        if re.fullmatch(r'int(?:\(\d+\))? unsigned',kind,re.I):return
        if (not re.fullmatch(r'int(?:\(\d+\))?',kind,re.I) or nullable!='NO'
                or extra not in ('','auto_increment') or comment):
            raise RuntimeError('Cannot safely align legacy accounts.id: unsupported column definition')
        default_sql=''
        if default is not None:
            literal=str(default)
            if re.fullmatch(r"'[0-9]+'",literal):literal=literal[1:-1]
            if not re.fullmatch(r'[0-9]+',literal) or int(literal)>4294967295:
                raise RuntimeError('Cannot safely align legacy accounts.id: unsupported default')
            default_sql=' DEFAULT '+literal
        table_before=table()
        if not table_before or str(table_before[0]).lower()!='innodb':
            raise RuntimeError('Cannot safely align legacy accounts.id: accounts must use InnoDB')
        cursor.execute('SELECT COLUMN_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=? '
                       'AND TABLE_NAME=\'accounts\' AND INDEX_NAME=\'PRIMARY\' ORDER BY SEQ_IN_INDEX',(database,))
        if cursor.fetchall()!=[('id',)]:
            raise RuntimeError('Cannot safely align legacy accounts.id: expected a single-column primary key')
        cursor.execute('SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE '
                       'WHERE (REFERENCED_TABLE_SCHEMA=? AND REFERENCED_TABLE_NAME=\'accounts\' AND REFERENCED_COLUMN_NAME=\'id\') '
                       'OR (TABLE_SCHEMA=? AND TABLE_NAME=\'accounts\' AND COLUMN_NAME=\'id\' AND REFERENCED_TABLE_NAME IS NOT NULL)',
                       (database,database))
        if cursor.fetchone() is not None:
            raise RuntimeError('Cannot safely align legacy accounts.id: existing foreign-key dependencies require a source migration')
        cursor.execute('SELECT MIN(`id`) FROM `accounts`')
        minimum=cursor.fetchone()[0]
        if minimum is not None and minimum<0:
            raise RuntimeError('Cannot safely align legacy accounts.id: negative account IDs cannot become unsigned')
        rows_before=fingerprint()
        print('Aligning legacy accounts.id to the selected source unsigned key in the staged database; preserving accounts and AUTO_INCREMENT.',flush=True)
        cursor.execute('ALTER TABLE `accounts` MODIFY COLUMN `id` INT UNSIGNED NOT NULL'+default_sql+
                       (' AUTO_INCREMENT' if extra=='auto_increment' else ''))
        connection.commit()
        after=column()
        if (not after or not re.fullmatch(r'int(?:\(\d+\))? unsigned',after[0],re.I)
                or after[1:]!=before[1:] or table()!=table_before or fingerprint()!=rows_before):
            raise RuntimeError('Legacy accounts.id verification failed; staged update will not activate')
        print('Legacy account key aligned; all account rows and the next account ID are unchanged.',flush=True)
    finally:
        cursor.close();connection.close()

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
        if action=='update':align_legacy_account_id()
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
