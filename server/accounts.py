"""Create an ordinary account in a stopped, app-owned LSB deployment.

Credentials enter through one bounded stdin message. Neither plaintext nor the
bcrypt hash is sent to argv, an operation log, an exception, or a status receipt.
Only the account database intentionally retains the resulting hash.
"""
import json
from pathlib import Path
import re
import subprocess

MAGIC=b'LSBACCOUNT1'
PAYLOAD_LIMIT=len(MAGIC)+1+16+1+32+1
SOURCE_LIMIT=2*1024*1024
SCHEMA_COLUMNS={
    'id':r'int(?:\(10\))? unsigned', 'login':r'varchar\(16\)', 'password':r'varchar\(64\)',
    'current_email':r'varchar\(64\)', 'registration_email':r'varchar\(64\)',
    'timecreate':r'datetime', 'timelastmodify':r'timestamp',
    'content_ids':r'tinyint(?:\(2\))? unsigned', 'expansions':r'smallint(?:\(4\))? unsigned',
    'features':r'tinyint(?:\(2\))? unsigned', 'status':r'tinyint(?:\(3\))? unsigned',
    'priv':r'tinyint(?:\(3\))? unsigned',
}


def read_credentials(stream):
    payload=stream.read(PAYLOAD_LIMIT+1)
    if not isinstance(payload,bytes) or len(payload)>PAYLOAD_LIMIT:
        raise ValueError('Account request exceeds the supported size')
    fields=payload.split(b'\n')
    if len(fields)!=4 or fields[0]!=MAGIC or fields[-1]!=b'':
        raise ValueError('Account request is incomplete or malformed')
    login,password=fields[1:3]
    if not 1<=len(login)<=16 or not 1<=len(password)<=32 or any(c<32 or c>126 for c in login+password):
        raise ValueError('Use a 1–16 character login and 1–32 character password with printable ASCII characters')
    return login,password


def validate_source(root):
    paths=('sql/accounts.sql','src/login/auth_session.cpp','src/login/auth_session.h','src/login/login_helpers.cpp')
    texts={}
    try:
        for name in paths:
            path=root/name
            if path.is_symlink() or not path.is_file() or path.stat().st_size>SOURCE_LIMIT or not path.resolve().is_relative_to(root.resolve()):
                raise ValueError()
            text=path.read_text(encoding='utf-8-sig')
            texts[name]=re.sub(r'/\*.*?\*/|//[^\n]*','',text,flags=re.S)
    except (OSError,UnicodeError,ValueError):
        raise ValueError('This server source does not expose the supported bcrypt account format') from None
    auth=texts['src/login/auth_session.cpp'];header=texts['src/login/auth_session.h'];helpers=texts['src/login/login_helpers.cpp'];schema=texts['sql/accounts.sql']
    requirements=(
        (auth,r'BCrypt::generateHash\(\s*password\s*\)'),
        (auth,r'BCrypt::validatePassword\(\s*password\s*,\s*passHash\s*\)'),
        (auth,r'passHash\[2\]\s*==\s*\x27b\x27'),
        (auth,r'isStringMalformed\(\s*username\s*,\s*16\s*\)'),
        (auth,r'isStringMalformed\(\s*password\s*,\s*32\s*\)'),
        (auth,r'MAX\(accounts\.id\)'), (auth,r'accid\s*<\s*1000\s*\?\s*1000\s*:\s*accid'),
        (auth,r'INSERT INTO accounts\(id,login,password,timecreate,timelastmodify,status,priv\)'),
        (header,r'NORMAL\s*=\s*0x01\b'),(header,r'USER\s*=\s*0x01\b'),
        (helpers,r'bool\s+isStringMalformed\('),(helpers,r'str\.empty\(\)'),
        (helpers,r'str\.size\(\)\s*>\s*max_length'),(helpers,r'c\s*<\s*0x20\b'),
        (schema,r'`login`\s+varchar\(16\)'),(schema,r'`password`\s+varchar\(64\)'),
        (schema,r'PRIMARY KEY\s*\(\s*`id`\s*\)'),(schema,r'ENGINE\s*=\s*InnoDB'),
    )
    if any(not re.search(pattern,text,re.I) for text,pattern in requirements):
        raise ValueError('This server source does not match the supported bcrypt account format')


def private_sql(manager,creds,database,statement):
    """SQL is a pipe input; even database error text may quote SQL, so discard it."""
    manager.cancelled()
    args=['mariadb',manager.cnf('lsb',creds['game']),'--batch','--skip-column-names','--raw',
          '--binary-mode','--default-character-set=utf8mb4','--local-infile=0',manager.checked_name(database)]
    try:
        result=subprocess.run(args,input=statement.encode('ascii'),stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=60)
    except (OSError,subprocess.TimeoutExpired):
        raise RuntimeError('Account database operation could not complete') from None
    if result.returncode:
        raise RuntimeError('Account database operation failed; no account details were logged')
    try:return result.stdout.decode('ascii')
    except UnicodeError:raise RuntimeError('Account database returned an unsupported response') from None


def validate_schema(query):
    engine=query("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='accounts';").strip()
    rows=query("SELECT COLUMN_NAME,COLUMN_TYPE,IS_NULLABLE,COLUMN_KEY,EXTRA FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='accounts' ORDER BY ORDINAL_POSITION;")
    columns={}
    for line in rows.splitlines():
        fields=line.split('\t')
        if len(fields)!=5 or fields[0] in columns:raise ValueError('The accounts table has an unsupported schema')
        columns[fields[0]]=fields[1:]
    if engine!='InnoDB' or set(columns)!=set(SCHEMA_COLUMNS):raise ValueError('The accounts table has an unsupported schema')
    for name,pattern in SCHEMA_COLUMNS.items():
        kind,nullable,key,extra=columns[name]
        if not re.fullmatch(pattern,kind,re.I) or nullable!='NO' or extra or (name=='id' and key!='PRI'):
            raise ValueError('The accounts table has an unsupported schema')


def password_hash(password):
    try:
        import bcrypt
        value=bcrypt.hashpw(password,bcrypt.gensalt(rounds=12,prefix=b'2b'))
        if not re.fullmatch(rb'\$2b\$12\$[./A-Za-z0-9]{53}',value) or not bcrypt.checkpw(password,value):raise ValueError()
        return value
    except (ImportError,ValueError,TypeError):
        raise RuntimeError('Account password hashing is unavailable; update the server runtime tools') from None


def insert_statement(login,hashed):
    # Hex literals avoid quoting/SQL-mode ambiguity. The server stays offline;
    # the table lock additionally serializes duplicate checking and ID allocation.
    login_value="CONVERT(X'"+login.hex()+"' USING utf8mb4)"
    hash_value="CONVERT(X'"+hashed.hex()+"' USING ascii)"
    return ("LOCK TABLES accounts WRITE;\n"
            "SET @lsb_exists=(SELECT COUNT(*) FROM accounts WHERE login="+login_value+");\n"
            "SET @lsb_id=GREATEST(COALESCE((SELECT MAX(id) FROM accounts),0)+1,1000);\n"
            "INSERT INTO accounts(id,login,password,timecreate,timelastmodify,status,priv) "
            "SELECT @lsb_id,"+login_value+","+hash_value+",NOW(),NULL,1,1 "
            "WHERE @lsb_exists=0 AND @lsb_id<=4294967295;\n"
            "SELECT 'LSB_ACCOUNT1',@lsb_exists,@lsb_id,ROW_COUNT();\n"
            "UNLOCK TABLES;\n")


def create_account(manager,generation,input_stream):
    login,password=read_credentials(input_stream)
    validate_source(generation/'server')
    meta=json.loads((generation/'deployment.json').read_text());database=manager.checked_name(meta['database'])
    creds=None;hashed=None
    try:
        creds=manager.start_database(generation)
        query=lambda statement:private_sql(manager,creds,database,statement)
        validate_schema(query)
        hashed=password_hash(password);password=None
        response=query(insert_statement(login,hashed));hashed=None
        match=re.fullmatch(r'LSB_ACCOUNT1\t([0-9]+)\t([0-9]+)\t([0-9]+)\n?',response)
        if not match:raise RuntimeError('Account creation result could not be verified; check the account before retrying')
        duplicate,account_id,changed=map(int,match.groups())
        if duplicate:raise ValueError('An account with that login already exists; the existing account was kept')
        if account_id>4294967295:raise ValueError('The account ID range is exhausted')
        if changed!=1 or account_id<1000:raise RuntimeError('Account creation result could not be verified; check the account before retrying')
        counts=manager.account_counts(database,creds)
        # Refresh metadata instead of overwriting it with the initial snapshot.
        latest=json.loads((generation/'deployment.json').read_text())
        if latest.get('database')!=database:raise RuntimeError('Account was created, but the deployment metadata changed; export support')
        latest.update(accounts=counts['accounts'],characters=counts['chars']);manager.atomic(generation/'deployment.json',latest)
        return dict(account_id=account_id,accounts=counts['accounts'],characters=counts['chars'])
    finally:
        password=None;hashed=None
        try:
            if creds is not None:manager.stop_database(creds)
        finally:manager.stop_children()
