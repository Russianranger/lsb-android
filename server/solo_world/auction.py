"""A bounded, local supplier and buyer for the ordinary FFXI auction house.

run_sql is manager.sql bound to the active game database, with one MariaDB CLI
session per call. All game mutations and the due marker commit together.
"""
INTERVAL_SECONDS = 300
ITEMS_PER_TICK = 8
BUYS_PER_TICK = 8
SCAN_WINDOW = 64
TRADER = 'LSB Provisioner'  # 15 characters; human character names cannot contain spaces

# ID and single-item asking price in gil. A stack uses the installed stack size.
# Standing stock is basic supplies; the buyer also handles other marketable items.
SUPPLIES = (
    (4096, 300), (4099, 100), (4101, 100), (4098, 100),  # fire, earth, water, wind crystals
    (17395, 25), (17389, 500), (817, 200), (648, 700),  # lugworm, bamboo rod, grass thread, copper
    (4097, 200), (4100, 400), (4102, 500), (4103, 200),  # ice, lightning, light, dark crystals
    (649, 300), (650, 500), (651, 1600), (654, 6900),  # bronze, brass, iron, darksteel
    (705, 100), (708, 200), (719, 2900), (818, 500),  # arrowwood, maple, ebony, cotton
    (816, 600), (821, 4700), (850, 1000), (912, 290),  # silk, rainbow thread, leather, beeswax ingredient
    (4151, 650), (4154, 500), (4164, 800), (4165, 1000),  # echo drops, holy water, powder, oil
    (4370, 180), (4378, 100), (4381, 740), (4558, 1300),  # honey, milk, mithkabob, Yagudo drink
    (16998, 35), (17320, 20), (17330, 10), (6468, 2500),  # insect paste, arrows, sublime sushi
    (16535, 300), (16448, 200), (17088, 100), (17024, 100),  # bronze sword, dagger, ash staff, club
    (16385, 100), (16640, 350), (16768, 350), (16896, 900),  # cesti, bronze axe, zaghnal, kunai
    (16704, 700), (16966, 1400), (17160, 500), (17345, 100),  # great axe, tachi, longbow, flute
    (16834, 1600), (17217, 1900), (12576, 300), (12600, 250),  # brass spear, crossbow, harness, robe
    (12568, 400), (12560, 1800), (12582, 1500), (12602, 10500),  # leather, scale, bone, wool armor
    (16536, 5300), (16451, 5800), (16584, 21000), (16412, 12000),  # iron sword, mythril dagger/claymore/claws
    (12552, 10600), (12306, 6200), (12603, 17100), (13712, 7000),  # chainmail, kite shield, velvet, carapace
    (17557, 29000), (16591, 31500), (16789, 51000), (17628, 20000),  # light staff, zweihander, scythe, knife
    (12547, 44000), (12612, 26000), (18901, 34500), (19733, 15000),  # darksteel, silk, falcata, beursault bow
    (18621, 29000), (19284, 5500), (18827, 22000), (18630, 29000),  # warmage's pole, hiwa, oxossi facon, nathushne
    (11830, 32000), (11852, 53000), (10456, 35000), (10274, 53000),  # body armor at levels 77, 90, 96 and 99
    (4609, 100), (4610, 550), (4611, 2500), (4612, 10500),  # Cure I-IV
    (4651, 250), (4623, 400), (4620, 4400), (4665, 10000),  # Protect, Paralyna, Raise, Haste
    (4767, 100), (4757, 1450), (4752, 750), (4753, 6600),  # Stone, Blizzard, Fire I-II
)
# Increment when item IDs, prices or order change; imported worlds pick up the new offers.
CATALOG_VERSION = 1

_STATE = """
CREATE TABLE IF NOT EXISTS lsb_solo_auction_state (
    id TINYINT UNSIGNED PRIMARY KEY,
    last_tick BIGINT UNSIGNED NOT NULL DEFAULT 0,
    next_supply INT UNSIGNED NOT NULL DEFAULT 0,
    last_listing INT UNSIGNED NOT NULL DEFAULT 0,
    demand_left TINYINT UNSIGNED NOT NULL DEFAULT 0
) ENGINE=InnoDB;
INSERT IGNORE INTO lsb_solo_auction_state (id) VALUES (1);
CREATE TABLE IF NOT EXISTS lsb_solo_auction_offers (
    position INT UNSIGNED NOT NULL, itemid SMALLINT UNSIGNED PRIMARY KEY,
    ask INT UNSIGNED NOT NULL, revision INT UNSIGNED NOT NULL
) ENGINE=InnoDB;
"""


def prepare(run_sql):
    """Prepare the market tables on a staged database before xi_search can browse them."""
    run_sql(_STATE)


def tick(run_sql):
    """Supply at most 16 listings and buy at most 8 eligible auctions, once per interval."""
    offers = ','.join(f'({index},{item},{price},{CATALOG_VERSION})' for index, (item, price) in enumerate(SUPPLIES))
    # Temporary tables last only for this CLI invocation. The rotating primary-key
    # scan handles old listings fairly, even when another supplier imports a large history.
    output = run_sql(f"""
SET SESSION innodb_lock_wait_timeout = 3;
{_STATE}
SET TRANSACTION ISOLATION LEVEL READ COMMITTED;
START TRANSACTION;
DELIMITER //
BEGIN NOT ATOMIC
    DECLARE previous_tick BIGINT UNSIGNED;
    DECLARE first_supply, previous_listing, new_listing INT UNSIGNED;
    DECLARE epoch BIGINT UNSIGNED DEFAULT UNIX_TIMESTAMP();
    DECLARE added, bought INT DEFAULT 0;
    SELECT last_tick, next_supply, last_listing INTO previous_tick, first_supply, previous_listing
        FROM lsb_solo_auction_state WHERE id = 1 FOR UPDATE;
    IF previous_tick <= epoch AND epoch < previous_tick + {INTERVAL_SECONDS} THEN
        SELECT 'idle', 0, 0;
    ELSE
        -- LSB creates a delivery-box payment via its auction_house_buy trigger.
        -- Refuse an incompatible deployment before making sales or marking the tick complete.
        IF (SELECT COUNT(*) FROM information_schema.TRIGGERS
            WHERE TRIGGER_SCHEMA = DATABASE() AND EVENT_OBJECT_TABLE = 'auction_house'
            AND ((TRIGGER_NAME = 'auction_house_buy' AND EVENT_MANIPULATION = 'UPDATE')
                 OR (TRIGGER_NAME = 'auction_house_list' AND EVENT_MANIPULATION = 'INSERT'))) <> 2
            OR (SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_NAME IN ('auction_house','auction_house_items','delivery_box') AND ENGINE = 'InnoDB') <> 3 THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Unsupported auction or delivery tables/triggers';
        END IF;
        IF (SELECT COUNT(*) FROM lsb_solo_auction_offers WHERE revision = {CATALOG_VERSION}) <> {len(SUPPLIES)} THEN
            DELETE FROM lsb_solo_auction_offers;
            INSERT INTO lsb_solo_auction_offers (position,itemid,ask,revision) VALUES {offers};
        END IF;
        CREATE TEMPORARY TABLE candidates (id INT UNSIGNED PRIMARY KEY) ENGINE=MEMORY;
        INSERT INTO candidates SELECT id FROM auction_house WHERE id > previous_listing
            ORDER BY id LIMIT {SCAN_WINDOW};
        SELECT IF(COUNT(*) = {SCAN_WINDOW}, MAX(id), 0) INTO new_listing FROM candidates;
        INSERT IGNORE INTO candidates SELECT id FROM auction_house ORDER BY id DESC LIMIT {SCAN_WINDOW // 2};
        CREATE TEMPORARY TABLE purchases (id INT UNSIGNED PRIMARY KEY) ENGINE=MEMORY;
        INSERT INTO purchases
            SELECT a.id FROM candidates c
            STRAIGHT_JOIN auction_house a ON a.id = c.id
            JOIN item_basic b ON b.itemid = a.itemid
            LEFT JOIN lsb_solo_auction_offers o ON o.itemid = b.itemid
            WHERE a.seller <> 0 AND a.sale = 0 AND a.buyer_name IS NULL AND a.price > 0
                AND a.date + {INTERVAL_SECONDS} <= epoch
                AND b.aH > 0 AND (b.flags & 16448) = 0
                AND a.price <= GREATEST(b.BaseSell + 1, COALESCE(o.ask, GREATEST(50, 3 * b.BaseSell)))
                    * IF(a.stack = 1, b.stackSize, 1)
            ORDER BY a.id LIMIT {BUYS_PER_TICK};
        -- If the bounded purchase batch filled before finishing the older window,
        -- continue after its last considered sale on the next pass.
        IF new_listing > 0 AND (SELECT COUNT(*) FROM purchases) = {BUYS_PER_TICK} THEN
            SET new_listing = LEAST(new_listing, (SELECT MAX(id) FROM purchases));
        END IF;
        -- A real bidder or the player's withdrawal can win the race. Recheck the row
        -- on update; LSB's existing trigger delivers the payment exactly once.
        UPDATE purchases p STRAIGHT_JOIN auction_house a ON a.id = p.id
            SET a.buyer_name = '{TRADER}', a.sale = a.price, a.sell_date = epoch
            WHERE a.sale = 0 AND a.buyer_name IS NULL AND a.seller <> 0;
        SET bought = ROW_COUNT();

        -- Seller zero has no delivery box. A standing supply has no expiration date;
        -- the search server compares this UINT32 field to its expiration cutoff.
        INSERT INTO auction_house (itemid, stack, seller, seller_name, date, price)
            SELECT b.itemid, s.is_stack, 0, '{TRADER}', 4294967295,
                GREATEST(b.BaseSell + 1, o.ask) * IF(s.is_stack = 1, b.stackSize, 1)
            FROM lsb_solo_auction_offers o JOIN item_basic b ON b.itemid = o.itemid
            CROSS JOIN (SELECT 0 AS is_stack UNION ALL SELECT 1) s
            WHERE MOD(o.position + {len(SUPPLIES)} - first_supply, {len(SUPPLIES)}) < {ITEMS_PER_TICK}
                AND b.aH > 0 AND (b.flags & 49216) = 0 AND (s.is_stack = 0 OR b.stackSize > 1)
                AND NOT EXISTS (SELECT 1 FROM auction_house a
                    WHERE a.buyer_name IS NULL AND a.itemid = b.itemid AND a.stack = s.is_stack AND a.sale = 0
                    AND ((a.seller = 0 AND a.seller_name = '{TRADER}') OR
                        a.price <= GREATEST(b.BaseSell + 1, o.ask) * IF(s.is_stack = 1, b.stackSize, 1)));
        SET added = ROW_COUNT();
        UPDATE lsb_solo_auction_state SET last_tick = epoch, demand_left = {ITEMS_PER_TICK},
            next_supply = MOD(first_supply + {ITEMS_PER_TICK}, {len(SUPPLIES)}), last_listing = new_listing WHERE id = 1;
        SELECT 'done', added, bought;
    END IF;
END//
DELIMITER ;
COMMIT;
""")
    status, listed, bought = output.strip().split('\t')
    return {'status': status, 'listed': int(listed), 'bought': int(bought)}


def disable(run_sql):
    """Withdraw only this supplier's live offers. Actual purchases stay in market history."""
    installed = run_sql("""SELECT COUNT(*) FROM information_schema.TABLES WHERE
        TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lsb_solo_auction_state';""")
    if installed.strip() != '1':
        return 0
    data = run_sql(f"""
SET SESSION innodb_lock_wait_timeout = 3;
START TRANSACTION;
SELECT id INTO @state_row FROM lsb_solo_auction_state WHERE id = 1 FOR UPDATE;
DELETE FROM auction_house WHERE seller = 0 AND seller_name = '{TRADER}' AND sale = 0 AND buyer_name IS NULL;
SET @withdrawn = ROW_COUNT();
UPDATE lsb_solo_auction_state SET last_tick = 0, next_supply = 0, demand_left = 0 WHERE id = 1;
COMMIT;
SELECT @withdrawn;
""")
    return int(data.strip())
