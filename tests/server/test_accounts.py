import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('accounts',Path(__file__).resolve().parents[2]/'server/accounts.py')
a=importlib.util.module_from_spec(spec);spec.loader.exec_module(a)

ACCOUNT_SCHEMA_SQL="""CREATE TABLE accounts (
 id int(10) unsigned NOT NULL DEFAULT 0,
 login varchar(16) NOT NULL DEFAULT '',
 password varchar(64) NOT NULL DEFAULT '',
 current_email varchar(64) NOT NULL DEFAULT '',
 registration_email varchar(64) NOT NULL DEFAULT '',
 timecreate datetime NOT NULL DEFAULT '0000-00-00 00:00:00',
 timelastmodify timestamp NOT NULL DEFAULT '0000-00-00 00:00:00',
 content_ids tinyint(2) unsigned NOT NULL DEFAULT 16,
 expansions smallint(4) unsigned NOT NULL DEFAULT 4094,
 features tinyint(2) unsigned NOT NULL DEFAULT 253,
 status tinyint(3) unsigned NOT NULL DEFAULT 1,
 priv tinyint(3) unsigned NOT NULL DEFAULT 1,
 PRIMARY KEY (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
"""
# A synthetic selected revision with the reviewed upstream account contract.
SOURCE_FILES={
    'sql/accounts.sql':ACCOUNT_SCHEMA_SQL.replace(' login ',' `login` ').replace(' password ',' `password` ').replace('PRIMARY KEY (id)','PRIMARY KEY (`id`)'),
    'src/login/auth_session.cpp':"""void fixture() {
 if (loginHelpers::isStringMalformed(username, 16)) return;
 if (loginHelpers::isStringMalformed(password, 32)) return;
 auto query = "SELECT COALESCE(MAX(accounts.id), 0) FROM accounts";
 accid = (accid < 1000 ? 1000 : accid);
 auto insert = "INSERT INTO accounts(id,login,password,timecreate,timelastmodify,status,priv) VALUES(?,?,?,?,NULL,?,?)";
 auto generated = BCrypt::generateHash(password);
 bool bcryptFormat = passHash[2] == 'b';
 bool accepted = BCrypt::validatePassword(password, passHash);
}
""",
    'src/login/auth_session.h':"enum Status { NORMAL = 0x01, BANNED = 0x02 };\nenum Privilege { USER = 0x01, ADMIN = 0x02 };\n",
    'src/login/login_helpers.cpp':"""bool isStringMalformed(const std::string& str, std::size_t max_length) {
 auto bad = [](const char& c) { return c < 0x20; };
 return str.empty() || str.size() > max_length || any_bad(str, bad);
}
""",
}


def write_source_fixture(root):
    for name,text in SOURCE_FILES.items():
        file=root/name;file.parent.mkdir(parents=True,exist_ok=True);file.write_text(text)


def schema_rows():
    types=['int(10) unsigned','varchar(16)','varchar(64)','varchar(64)','varchar(64)',
           'datetime','timestamp','tinyint(2) unsigned','smallint(4) unsigned','tinyint(2) unsigned','tinyint(3) unsigned','tinyint(3) unsigned']
    return ''.join(name+'\t'+kind+'\tNO\t'+('PRI' if name=='id' else '')+'\t\n' for name,kind in zip(a.SCHEMA_COLUMNS,types))


class Manager:
    def __init__(self):self.events=[];self.counts={'accounts':2,'chars':1}
    def checked_name(self,name):return name
    def cancelled(self):pass
    def start_database(self,generation):self.events.append('start');return {'root':'database-only-secret','game':'restricted-database-secret'}
    def stop_database(self,creds):self.events.append('stop')
    def stop_children(self):self.events.append('children')
    def cnf(self,*args):return '--defaults-extra-file=/private/root.cnf'
    def account_counts(self,*args):return self.counts
    def atomic(self,path,data):path.write_text(json.dumps(data))


class AccountTests(unittest.TestCase):
    def generation(self,folder):
        root=Path(folder);write_source_fixture(root/'server')
        (root/'deployment.json').write_text(json.dumps({'generation':'kept','database':'xidb','accounts':1,'characters':1,'client_pair':{'version':'unchanged'}}))
        return root
    def query(self,manager,creds,database,statement):
        if 'SELECT ENGINE' in statement:return 'InnoDB\n'
        if 'COLUMN_NAME' in statement:return schema_rows()
        self.assertIn('LOCK TABLES accounts WRITE',statement);self.assertIn('NOW(),NULL,1,1',statement)
        self.assertNotIn('simple-password',statement)
        return 'LSB_ACCOUNT1\t0\t1000\t1\n'
    def test_payload_limits_preserve_spaces_and_reject_extra_data(self):
        self.assertEqual(a.read_credentials(io.BytesIO(b'LSBACCOUNT1\n user \n pass \n')),(b' user ',b' pass '))
        payload=b'LSBACCOUNT1\n'+b'u'*16+b'\n'+b'p'*32+b'\n'
        self.assertEqual(len(payload),a.PAYLOAD_LIMIT);a.read_credentials(io.BytesIO(payload))
        for bad in (payload+b'\n',b'LSBACCOUNT1\n\np\n',b'LSBACCOUNT1\nu\n\n',b'LSBACCOUNT1\nu\np\nextra',b'LSBACCOUNT1\nu\np\r\n',b'LSBACCOUNT1\nu\np\x00\n',b'LSBACCOUNT1\nu\np\x80\n',b'OTHER\nu\np\n'):
            with self.assertRaises(ValueError):a.read_credentials(io.BytesIO(bad))
    def test_source_contract_rejects_unrecognized_hashing_before_database_start(self):
        with tempfile.TemporaryDirectory() as d:
            root=self.generation(d);a.validate_source(root/'server')
            source=root/'server/src/login/auth_session.cpp';source.write_text(source.read_text().replace('BCrypt::generateHash','Legacy::generateHash'))
            manager=Manager()
            with self.assertRaises(ValueError):a.create_account(manager,root,io.BytesIO(b'LSBACCOUNT1\nu\np\n'))
            self.assertEqual(manager.events,[])
    def test_schema_is_checked_before_hashing_or_mutation(self):
        with tempfile.TemporaryDirectory() as d:
            root=self.generation(d);manager=Manager()
            with patch.object(a,'private_sql',return_value='MyISAM\n'),patch.object(a,'password_hash') as hash_password:
                with self.assertRaises(ValueError):a.create_account(manager,root,io.BytesIO(b'LSBACCOUNT1\nu\np\n'))
                hash_password.assert_not_called()
            self.assertEqual(manager.events,['start','stop','children'])
    def test_create_uses_regular_privileges_and_updates_only_counts(self):
        with tempfile.TemporaryDirectory() as d:
            root=self.generation(d);manager=Manager()
            with patch.object(a,'private_sql',side_effect=self.query),patch.object(a,'password_hash',return_value=b'$2b$12$'+b'A'*53):
                result=a.create_account(manager,root,io.BytesIO(b'LSBACCOUNT1\nUser\nsimple-password\n'))
            self.assertEqual(result,{'account_id':1000,'accounts':2,'characters':1});self.assertEqual(manager.events,['start','stop','children'])
            meta=json.loads((root/'deployment.json').read_text());self.assertEqual(meta['client_pair'],{'version':'unchanged'});self.assertEqual(meta['accounts'],2)
            self.assertNotIn('simple-password',str(meta));self.assertNotIn('$2b$',str(meta))
    def test_duplicate_and_failed_start_keep_metadata_and_clean_up(self):
        with tempfile.TemporaryDirectory() as d:
            root=self.generation(d);original=(root/'deployment.json').read_bytes();manager=Manager()
            def duplicate(*args):return 'LSB_ACCOUNT1\t1\t1001\t0\n' if args[-1].startswith('LOCK') else self.query(*args)
            with patch.object(a,'private_sql',side_effect=duplicate),patch.object(a,'password_hash',return_value=b'$2b$12$'+b'A'*53):
                with self.assertRaisesRegex(ValueError,'already exists'):a.create_account(manager,root,io.BytesIO(b'LSBACCOUNT1\nUSER\np\n'))
            self.assertEqual((root/'deployment.json').read_bytes(),original)
            manager=Manager()
            def failed_start(generation):manager.events.append('start');raise RuntimeError('database startup failed')
            manager.start_database=failed_start
            with self.assertRaises(RuntimeError):a.create_account(manager,root,io.BytesIO(b'LSBACCOUNT1\nu\np\n'))
            self.assertEqual(manager.events,['start','children'])
    def test_sql_errors_cannot_quote_password_hash_or_query(self):
        manager=Manager();secret='query-containing-a-private-hash'
        with patch.object(a.subprocess,'run',return_value=subprocess.CompletedProcess([],1,b'',b'ERROR '+secret.encode())) as run:
            with self.assertRaises(RuntimeError) as error:a.private_sql(manager,{'game':'game-secret'},'xidb',secret)
            self.assertNotIn(secret,str(error.exception));self.assertEqual(run.call_args.kwargs['stderr'],subprocess.DEVNULL)
            self.assertNotIn(secret,str(run.call_args.args));self.assertNotIn('game-secret',str(run.call_args.args))
    def test_sql_values_are_hex_encoded_without_normalizing_credentials(self):
        login=b" ';DROP x;-- "
        statement=a.insert_statement(login,b'$2b$12$'+b'A'*53)
        self.assertNotIn(login.decode(),statement);self.assertIn(login.hex(),statement)
        self.assertIn('WHERE @lsb_exists=0 AND @lsb_id<=4294967295',statement)


if __name__=='__main__':unittest.main()
