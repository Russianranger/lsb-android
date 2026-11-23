"""Exercise actual MariaDB transactions, stock lookup and the game's payment trigger contract."""
from concurrent.futures import ThreadPoolExecutor
import importlib.util
import math
from pathlib import Path
import re
import unittest

from solo_mariadb_fixture import MariaDBFixture

source = Path(__file__).resolve().parents[2] / 'server/solo_world/auction.py'
spec = importlib.util.spec_from_file_location('handheld_auction', source)
auction = importlib.util.module_from_spec(spec)
spec.loader.exec_module(auction)

SCHEMA = """
CREATE TABLE auction_house (
    id INT UNSIGNED PRIMARY KEY AUTO_INCREMENT, itemid SMALLINT UNSIGNED NOT NULL,
    stack TINYINT UNSIGNED NOT NULL DEFAULT 0, seller INT UNSIGNED NOT NULL DEFAULT 0,
    seller_name VARCHAR(15), date INT UNSIGNED NOT NULL DEFAULT 0, price INT UNSIGNED NOT NULL,
    buyer INT UNSIGNED NOT NULL DEFAULT 0, buyer_name VARCHAR(15), sale INT UNSIGNED DEFAULT 0,
    sell_date INT UNSIGNED DEFAULT 0, INDEX (buyer_name, itemid, stack, price), INDEX (seller)
) ENGINE=InnoDB;
CREATE TABLE auction_house_items (itemid SMALLINT UNSIGNED PRIMARY KEY) ENGINE=InnoDB;
CREATE TABLE item_basic (itemid SMALLINT UNSIGNED PRIMARY KEY, stackSize INT UNSIGNED, aH INT, flags INT,
                         BaseSell INT UNSIGNED) ENGINE=Aria;
CREATE TABLE delivery_box (charid INT UNSIGNED, quantity INT UNSIGNED) ENGINE=InnoDB;
CREATE TRIGGER auction_house_list BEFORE INSERT ON auction_house FOR EACH ROW
    INSERT IGNORE INTO auction_house_items VALUES (NEW.itemid);
DELIMITER //
CREATE TRIGGER auction_house_buy BEFORE UPDATE ON auction_house FOR EACH ROW
BEGIN
    IF OLD.seller <> 0 AND NEW.sale <> 0 THEN
        INSERT INTO delivery_box VALUES (NEW.seller, NEW.sale);
    END IF;
END//
DELIMITER ;
"""


class AuctionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        fixture = MariaDBFixture()
        cls.db = fixture.__enter__()
        cls.addClassCleanup(fixture.__exit__, None, None, None)

    def setUp(self):
        self.query('DROP TABLE IF EXISTS auction_house, auction_house_items, item_basic, delivery_box, '
                   'lsb_solo_auction_state, lsb_solo_auction_offers;')
        self.query(SCHEMA)
        rows = [f'({itemid},{1 if itemid == 17389 else 12},35,0,15)' for itemid, ask in auction.SUPPLIES]
        self.query('INSERT INTO item_basic VALUES ' + ','.join(rows))
        self.query('INSERT INTO item_basic VALUES (29001,1,12,0,12500),(29002,1,0,0,50),(29003,1,12,64,50);')

    def query(self, sql):
        return self.db.run(sql).strip()

    def due(self):
        self.query('UPDATE lsb_solo_auction_state SET last_tick = 0 WHERE id = 1;')
        return auction.tick(self.db.run)

    def test_live_bids_restocks_and_disable_preserve_history_and_other_sellers(self):
        self.assertEqual(auction.disable(self.db.run), 0)
        self.assertEqual(self.query("SHOW TABLES LIKE 'lsb_solo_auction_state'"), '')
        self.query("""INSERT INTO auction_house (itemid,price,seller_name) VALUES (4101,60,'OtherSupplier');""")
        first = auction.tick(self.db.run)
        self.assertGreater(first['listed'], 0)
        self.assertLessEqual(first['listed'], 16)
        self.assertEqual(self.query("SELECT COUNT(*) FROM auction_house WHERE itemid=4101 AND stack=0"), '1')
        # Same selection/update predicate used by LSB when the player bids.
        bid = """UPDATE auction_house SET buyer=1001, buyer_name='Hero', sale=300, sell_date=UNIX_TIMESTAMP()
                 WHERE itemid=4096 AND buyer_name IS NULL AND stack=0 AND price<=300 ORDER BY price LIMIT 1;"""
        self.query(bid)
        for _ in range(math.ceil(len(auction.SUPPLIES) / auction.ITEMS_PER_TICK)):
            self.due()
        self.assertEqual(self.query("SELECT COUNT(*) FROM auction_house WHERE itemid=4096 AND sale=0 AND stack=0"), '1')
        self.assertEqual(self.query("SELECT COUNT(*) FROM auction_house WHERE itemid=17389 AND stack=1"), '0')
        self.assertEqual(self.query("SELECT COUNT(*) FROM auction_house WHERE itemid=6468 AND sale=0"), '2')
        self.assertGreater(auction.disable(self.db.run), 0)
        self.assertEqual(self.query("SELECT itemid, sale, seller_name FROM auction_house ORDER BY id"),
                         f"4101\t0\tOtherSupplier\n4096\t300\t{auction.TRADER}")
        self.assertEqual(auction.disable(self.db.run), 0)

    def test_normal_payout_full_stack_high_level_limits_and_no_replay(self):
        self.query("""INSERT INTO auction_house (itemid,stack,price,seller,seller_name,date) VALUES
            (4096,0,300,1001,'Main',1),(4096,1,3600,1001,'Main',1),(4096,0,350,1001,'Main',1),
            (4096,0,100,1001,'Main',UNIX_TIMESTAMP()),(29001,0,37500,1002,'Alt',1),
            (29002,0,1,1002,'Alt',1),(29003,0,1,1002,'Alt',1);""")
        self.assertEqual(auction.tick(self.db.run)['bought'], 3)
        self.assertEqual(self.query("SELECT charid, quantity FROM delivery_box ORDER BY charid, quantity"),
                         '1001\t300\n1001\t3600\n1002\t37500')
        self.assertEqual(auction.tick(self.db.run)['status'], 'idle')
        self.assertEqual(self.due()['bought'], 0)
        self.assertEqual(self.query("SELECT COUNT(*) FROM delivery_box"), '3')
        self.assertEqual(self.query("SELECT COUNT(*) FROM auction_house WHERE seller<>0 AND sale=0"), '4')

    def test_simultaneous_workers_bounded_and_eventual(self):
        # Exceed a scan window and ensure the first full sweep cannot skip earlier listings.
        rows = ["(4096,1001,100,1)" for _ in range(auction.SCAN_WINDOW + 1)]
        self.query("INSERT INTO auction_house (itemid,seller,price,date) VALUES " + ','.join(rows))
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = list(pool.map(lambda _: auction.tick(self.db.run), range(2)))
        self.assertEqual(sorted(result['status'] for result in results), ['done', 'idle'])
        self.assertEqual(sum(result['bought'] for result in results), auction.BUYS_PER_TICK)
        for _ in range(9):
            self.assertLessEqual(self.due()['bought'], auction.BUYS_PER_TICK)
        self.assertEqual(self.query("SELECT COUNT(*) FROM delivery_box"), str(len(rows)))

    def test_failed_batch_rolls_back_sale_payment_stock_and_clock(self):
        self.query("INSERT INTO auction_house (itemid,seller,price,date) VALUES (4096,1001,100,1)")
        self.query("""CREATE TRIGGER reject_new_offer BEFORE INSERT ON auction_house FOR EACH ROW
                   SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture failure after buyer phase';""")
        with self.assertRaisesRegex(RuntimeError, 'fixture failure'):
            auction.tick(self.db.run)
        self.assertEqual(self.query('SELECT COUNT(*) FROM delivery_box'), '0')
        self.assertEqual(self.query('SELECT sale FROM auction_house'), '0')
        self.assertEqual(self.query('SELECT last_tick FROM lsb_solo_auction_state'), '0')
        self.query('DROP TRIGGER reject_new_offer')
        self.assertEqual(auction.tick(self.db.run)['bought'], 1)

    def test_no_sales_if_payout_trigger_is_missing(self):
        self.query('DROP TRIGGER auction_house_buy')
        with self.assertRaisesRegex(RuntimeError, 'Unsupported auction'):
            auction.tick(self.db.run)
        self.assertEqual(self.query('SELECT COUNT(*) FROM auction_house'), '0')

    def test_native_price_history_offer_uses_catalogue_and_preserves_competing_orders(self):
        # Prepare the exact parameterized INSERT from xi_search on real MariaDB.
        patch = (source.parent / 'patches/0002-auction-demand.patch').read_text()
        fragment = patch.split('const auto result = db::preparedStmt(', 1)[1].split('stack, stack, itemId', 1)[0]
        insert = ''.join(re.findall(r'"([^"\n]+)"', fragment))
        def browse(item, stack=0):
            return self.query(f"""PREPARE supply FROM "{insert}";
                SET @item = {item}, @stack = {stack};
                EXECUTE supply USING @stack, @stack, @item, @stack, @stack;
                SELECT ROW_COUNT();""")

        auction.tick(self.db.run)
        # Oxossi Facon is level 99 in stock LSB. FOREIGN or non-tradeable IDs must never be created.
        self.query('UPDATE item_basic SET stackSize = 1 WHERE itemid = 18827')
        self.assertEqual(browse(18827, 1), '0')
        self.assertEqual(browse(18827), '1')
        self.assertEqual(browse(18827), '0')
        self.assertEqual(browse(29001), '0')
        self.query('UPDATE item_basic SET flags = 32768 WHERE itemid = 4612')
        self.assertEqual(browse(4612), '0')
        self.query('INSERT INTO auction_house (itemid,seller,price) VALUES (16535,1001,1800)')
        self.assertEqual(browse(16535), '0')
        self.assertEqual(self.query("SELECT price FROM auction_house WHERE itemid = 18827"), '22000')
