import datetime
from pathlib import Path
import sys
import unittest

from solo_mariadb_fixture import MariaDBFixture

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'server'))
from solo_world import conquest


class Simulation(unittest.TestCase):
    def test_uses_native_beastmen_formula_and_region_weights(self):
        region = conquest.Region(1, 2, 5, [40, 32, 28], 20, 1)
        region.tally()  # Zulkheim: 25 * 1 * 100 / 20 = 125
        self.assertEqual(region.fields(), (1, 3, 2, 0, 0, 0, 0, 0))
        region = conquest.Region(0, 5, 5, [10, 10, 4], 0, 0)
        region.tally()
        self.assertEqual(region.owner, 5)
        region.homepoints = 1
        region.tally()
        self.assertEqual(region.owner, 3)

    def test_scores_cap_and_lag_multipliers(self):
        cap = conquest.INFLUENCE_CAP
        region = conquest.Region(0, 0, 5, [cap, 14, 20], 0, 0)
        region.gain(0, 100)
        self.assertEqual(region.scores, [cap, 4, 10])
        region.gain(1, 100)
        self.assertEqual(region.scores[1], 34)

    def test_six_hour_activity_and_separate_weekly_boundary(self):
        reset = int(datetime.datetime.fromisoformat('2026-11-22T15:00:00+00:00').timestamp())
        region = conquest.Region(0, 2, 3, [0, 100, 0], 40, 0)
        self.assertEqual(conquest.advance([region], 1, reset-90, reset-1, 0), (89, 0, 0))
        self.assertEqual(conquest.advance([region], 1, reset-1, reset, 89), (90, 0, 1))
        self.assertEqual(region.fields(), (0, 1, 2, 0, 0, 0, 0, 0))
        self.assertEqual(conquest.advance([region], 1, reset, reset+conquest.STEP-90, 90), (0, 1, 0))
        self.assertTrue(all(n > 0 for n in region.scores))

    def test_exact_boundary_and_bounded_long_absence(self):
        reset = conquest.FIRST_TALLY + 120 * conquest.WEEK
        region = conquest.Region(0, 1, 1, [0, 0, 0], 1, 0)
        result = conquest.advance([region], 4, reset - 1000 * conquest.WEEK, reset, 0)
        self.assertEqual(result, (0, 4*28, 4))
        self.assertEqual(region.scores, [0, 0, 0])


class ConquestDatabase(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.db = MariaDBFixture().__enter__()
        cls.addClassCleanup(cls.db.__exit__, None, None, None)

    def setUp(self):
        self.sql = self.db.run
        self.sql('DROP TABLE IF EXISTS lsb_solo_conquest_clock, conquest_system; '
                 'CREATE TABLE conquest_system(region_id TINYINT PRIMARY KEY,region_control TINYINT,'
                 'region_control_prev TINYINT,sandoria_influence INT,bastok_influence INT,'
                 'windurst_influence INT,mob_kills INT,player_homepoints INT) ENGINE=InnoDB;')
        rows = ','.join(f'({i},0,2,80,0,0,5,0)' for i in range(19))
        self.sql('INSERT INTO conquest_system VALUES ' + rows + ';')
        self.reset = conquest.FIRST_TALLY + 100 * conquest.WEEK

    def standings(self):
        return self.sql('SELECT * FROM conquest_system ORDER BY region_id;')

    def test_adopts_existing_world_partial_periods_retry_and_reboot(self):
        before = self.standings()
        t = self.reset - 3 * conquest.STEP
        session = conquest.start(self.sql, t)
        self.assertEqual(before, self.standings())
        conquest.stop(self.sql, session, t)
        session = conquest.start(self.sql, t + 4 * 3600)
        self.assertEqual(before, self.standings())
        conquest.stop(self.sql, session, t + 5 * 3600)
        session = conquest.start(self.sql, t + 7 * 3600)
        once = self.standings()
        self.assertNotEqual(before, once)
        session = conquest.start(self.sql, t + 7 * 3600)
        self.assertEqual(self.standings(), once)
        conquest.heartbeat(self.sql, session, self.reset + 3600)
        conquest.stop(self.sql, session+1, self.reset+22*3600)  # stale session cannot stamp
        conquest.start(self.sql, self.reset+3600+conquest.HEARTBEAT_SECONDS+conquest.STEP)
        self.assertNotEqual(self.standings(), once)
        # The native game owned the weekly reset during play; our recovery did not tally again.
        self.assertEqual(self.sql('SELECT DISTINCT region_control_prev FROM conquest_system;').strip(), '2')

    def test_tally_clean_stop_and_clock_reversal(self):
        session = conquest.start(self.sql, self.reset-100)
        conquest.stop(self.sql, session, self.reset-60)
        session = conquest.start(self.sql, self.reset+60)
        after = self.standings()
        self.assertEqual(self.sql('SELECT DISTINCT region_control_prev,sandoria_influence FROM conquest_system;').strip(),'0\t0')
        conquest.stop(self.sql, session, self.reset-1000)
        session = conquest.start(self.sql, self.reset-500)
        conquest.stop(self.sql, session, self.reset)
        conquest.start(self.sql, self.reset+60)
        self.assertEqual(self.standings(), after)

    def test_partial_failure_rolls_back_all_standings_and_clock(self):
        session = conquest.start(self.sql, self.reset)
        conquest.stop(self.sql, session, self.reset)
        rows = self.standings()
        clock = self.sql('SELECT * FROM lsb_solo_conquest_clock;')
        def fail_mid_batch(stmt):
            needle = 'UPDATE conquest_system SET'
            if needle in stmt:
                index = stmt.find(needle, stmt.index(needle)+1)
                stmt = stmt[:index] + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test interruption';\n" + stmt[index:]
            return self.sql(stmt)
        with self.assertRaisesRegex(RuntimeError, 'test interruption'):
            conquest.start(fail_mid_batch, self.reset+conquest.STEP)
        self.assertEqual(self.standings(), rows)
        self.assertEqual(self.sql('SELECT * FROM lsb_solo_conquest_clock;'), clock)
        conquest.start(self.sql, self.reset+conquest.STEP)
        self.assertNotEqual(self.standings(), rows)

    def test_snapshot_race_preserves_external_conquest_change(self):
        session = conquest.start(self.sql, self.reset)
        conquest.stop(self.sql, session, self.reset)
        def update_before_commit(stmt):
            if '@locked' in stmt:
                self.sql('UPDATE conquest_system SET mob_kills=456 WHERE region_id=3;')
            return self.sql(stmt)
        with self.assertRaisesRegex(RuntimeError, 'changed during startup'):
            conquest.start(update_before_commit, self.reset + conquest.STEP)
        self.assertEqual(self.sql('SELECT mob_kills FROM conquest_system WHERE region_id=3;').strip(),'456')
        self.assertEqual(self.sql('SELECT last_seen FROM lsb_solo_conquest_clock;').strip(),str(self.reset))

    def test_disable_then_reenable_has_no_retroactive_ticks(self):
        before = self.standings()
        conquest.disable(self.sql)
        self.assertEqual(self.sql("SHOW TABLES LIKE 'lsb_solo_conquest_clock';"), '')
        conquest.start(self.sql, self.reset)
        conquest.disable(self.sql)
        conquest.start(self.sql, self.reset + 5 * conquest.WEEK)
        self.assertEqual(before, self.standings())

    def test_refuses_nontransactional_or_incomplete_regions(self):
        self.sql('ALTER TABLE conquest_system ENGINE=MyISAM;')
        with self.assertRaisesRegex(ValueError, 'InnoDB'):
            conquest.start(self.sql, self.reset)
        self.sql('ALTER TABLE conquest_system ENGINE=InnoDB; DELETE FROM conquest_system WHERE region_id=5;')
        with self.assertRaisesRegex(ValueError, '19 standard'):
            conquest.start(self.sql, self.reset)
        self.assertEqual(self.sql('SELECT COUNT(*) FROM lsb_solo_conquest_clock;').strip(), '0')
        self.sql('INSERT INTO conquest_system VALUES(5,2,2,120,30,15,2,5);')
        before = self.standings()
        conquest.start(self.sql, self.reset + conquest.WEEK)
        self.assertEqual(before, self.standings())
