from pathlib import Path
import shutil
import subprocess
import unittest

from solo_mariadb_fixture import MariaDBFixture

ROOT = Path(__file__).resolve().parents[2]
MODULE = ROOT / 'server/solo_world/modules/handheld_squad'


class HandheldSquadTests(unittest.TestCase):
    def test_commands_and_game_events(self):
        runtime = shutil.which('luajit') or shutil.which('lua')
        if not runtime:
            self.skipTest('Lua interpreter required')
        subprocess.run([runtime, str(Path(__file__).with_name('solo_squad.lua')),
                        str(MODULE / 'lua/squad.lua')], check=True)

    def test_upgrade_persistence_and_character_deletion(self):
        with MariaDBFixture() as db:
            db.run('CREATE TABLE chars (charid INT UNSIGNED PRIMARY KEY, name VARCHAR(15)) ENGINE=InnoDB; '
                   "INSERT INTO chars VALUES (4, 'Existing'), (9, 'Other');")
            schema = (MODULE / 'sql/handheld_squad.sql').read_text()
            db.run(schema)
            db.run("INSERT INTO handheld_squad_mates (charid,slot,name,main_job) VALUES (4,1,'June',3),(9,1,'June',3); "
                   'INSERT INTO handheld_squad_jobs VALUES (4,1,3,521),(9,1,3,10); '
                   'INSERT INTO handheld_squad_gear VALUES (4,1,3,5,1234);')
            db.run(schema)
            self.assertEqual(db.run('SELECT name FROM chars WHERE charid=4;').strip(), 'Existing')
            self.assertEqual(db.run('SELECT j.total_xp, g.itemid FROM handheld_squad_jobs j '
                                    'JOIN handheld_squad_gear g USING(charid,slot,job);').strip(), '521\t1234')
            db.run('DELETE FROM chars WHERE charid=4;')
            self.assertEqual(db.run('SELECT charid FROM handheld_squad_jobs;').strip(), '9')
            self.assertEqual(db.run('SELECT COUNT(*) FROM handheld_squad_gear;').strip(), '0')


if __name__ == '__main__':
    unittest.main()
