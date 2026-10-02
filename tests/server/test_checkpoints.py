import gzip, importlib.util, json, tempfile, unittest
from pathlib import Path
from unittest import mock
spec=importlib.util.spec_from_file_location('checkpoint_manager',Path(__file__).resolve().parents[2]/'server/manager.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

class CheckpointTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        root=Path(self.temp.name);self.patch=mock.patch.multiple(m,STATE=root/'state',RUN=root/'run',LOGS=root/'logs');self.patch.start();self.addCleanup(self.patch.stop)
        for folder in (m.STATE,m.RUN,m.LOGS):folder.mkdir()
        self.id='11111111-1111-4111-8111-111111111111';self.generation=m.STATE/'generations'/self.id
        (self.generation/'server/sql').mkdir(parents=True);(self.generation/'server/sql/accounts.sql').write_text('schema fixture')
        self.meta=dict(database='xidb',expected_client='30260904_1',binaries={'xi_map':'abc'},generation=self.id,build_id='fixture-build')
        m.atomic(self.generation/'deployment.json',self.meta);m.atomic(m.STATE/'active.json',dict(current=self.id,previous=None))
        self.payload=b"CREATE TABLE fixture(id INT); INSERT INTO fixture VALUES (42);\n"*300
    def save(self,keep=5):
        def dump(generation,target,capture_counts=False):
            self.assertTrue(capture_counts);target.write_bytes(self.payload);return dict(accounts=2,chars=3)
        with mock.patch.object(m,'dump_database',side_effect=dump):return m.create_checkpoint(dict(checkpoint_keep=keep))
    def test_compressed_receipt_has_counts_digest_identity_and_no_client_data(self):
        receipt=self.save();folder,loaded=m.checkpoint_record(receipt['checkpoint_id'])
        self.assertEqual(receipt,loaded);self.assertEqual(gzip.decompress((folder/'database.sql.gz').read_bytes()),self.payload)
        self.assertEqual(receipt['sha256'],m.file_sha256(folder/'database.sql.gz'));self.assertEqual(receipt['accounts'],2)
        self.assertEqual(receipt['characters'],3);self.assertEqual(receipt['sql_bytes'],len(self.payload));self.assertLess(receipt['bytes'],len(self.payload))
        self.assertEqual(set(p.name for p in folder.iterdir()),{'receipt.json','database.sql.gz'})
    def test_retention_only_removes_old_complete_saves_after_success(self):
        first=self.save(2);second=self.save(2)
        before=set(p.name for p in m.checkpoint_root().iterdir())
        with mock.patch.object(m,'dump_database',side_effect=RuntimeError('disk full')):
            with self.assertRaisesRegex(RuntimeError,'disk full'):m.create_checkpoint(dict(checkpoint_keep=2))
        self.assertEqual(before,set(p.name for p in m.checkpoint_root().iterdir()))
        incomplete=m.checkpoint_root()/'unfinished.new';incomplete.mkdir();(incomplete/'keep').write_text('preserved')
        third=self.save(2)
        self.assertFalse((m.checkpoint_root()/first['checkpoint_id']).exists());self.assertTrue((m.checkpoint_root()/second['checkpoint_id']).is_dir());self.assertTrue((m.checkpoint_root()/third['checkpoint_id']).is_dir())
        self.assertEqual((incomplete/'keep').read_text(),'preserved')
    def test_restore_uses_verified_private_sql_without_changing_import(self):
        receipt=self.save();(m.STATE/'import.sql').write_bytes(b'import must be kept')
        pointer=(m.STATE/'active.json').read_bytes()
        def restore(req,dump=None):self.assertEqual(dump.read_bytes(),self.payload);self.assertNotEqual(dump,m.STATE/'import.sql')
        with mock.patch.object(m,'restore_database',side_effect=restore) as called:
            m.restore_checkpoint(dict(checkpoint_id=receipt['checkpoint_id']));called.assert_called_once()
        self.assertEqual((m.STATE/'import.sql').read_bytes(),b'import must be kept');self.assertEqual((m.STATE/'active.json').read_bytes(),pointer)
        self.assertFalse(list(m.RUN.glob('checkpoint-*.sql')))
    def test_corruption_schema_change_and_symlink_fail_before_restore(self):
        receipt=self.save();folder=m.checkpoint_root()/receipt['checkpoint_id'];file=folder/'database.sql.gz';original=file.read_bytes();pointer=(m.STATE/'active.json').read_bytes()
        with mock.patch.object(m,'restore_database') as restore:
            file.write_bytes(original[:-1]+bytes([original[-1]^1]))
            with self.assertRaisesRegex(ValueError,'checksum'):m.restore_checkpoint(dict(checkpoint_id=receipt['checkpoint_id']))
            file.write_bytes(original);(self.generation/'server/sql/accounts.sql').write_text('new schema')
            with self.assertRaisesRegex(ValueError,'different server'):m.restore_checkpoint(dict(checkpoint_id=receipt['checkpoint_id']))
            file.unlink();file.symlink_to(m.STATE/'active.json')
            with self.assertRaisesRegex(ValueError,'Incomplete'):m.restore_checkpoint(dict(checkpoint_id=receipt['checkpoint_id']))
            restore.assert_not_called()
        self.assertEqual((m.STATE/'active.json').read_bytes(),pointer)
    def test_cancelled_save_and_oversized_expansion_keep_current(self):
        receipt=self.save();pointer=(m.STATE/'active.json').read_bytes()
        def dump(generation,target,capture_counts=False):target.write_bytes(self.payload);return dict(accounts=2,chars=3)
        with mock.patch.object(m,'cancelled',side_effect=InterruptedError('cancelled')),mock.patch.object(m,'dump_database',side_effect=dump):
            with self.assertRaises(InterruptedError):m.create_checkpoint({})
        self.assertEqual(len(list(m.checkpoint_root().iterdir())),1)
        folder=m.checkpoint_root()/receipt['checkpoint_id'];receipt['sql_bytes']=len(self.payload)-1;m.atomic(folder/'receipt.json',receipt)
        with mock.patch.object(m,'restore_database') as restore:
            with self.assertRaisesRegex(ValueError,'recorded size'):m.restore_checkpoint(dict(checkpoint_id=receipt['checkpoint_id']))
            restore.assert_not_called()
        self.assertEqual((m.STATE/'active.json').read_bytes(),pointer);self.assertFalse(list(m.RUN.glob('checkpoint-*.sql')))
    def test_invalid_ids_and_retention_are_rejected(self):
        for value in ('../active.json','',None,'A'*36):
            with self.assertRaises(ValueError):m.checkpoint_record(value)
        for value in (True,0,1,4,999):
            with self.assertRaises(ValueError):m.create_checkpoint(dict(checkpoint_keep=value))
