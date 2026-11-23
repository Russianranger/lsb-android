"""Use the selected build's actual tables and triggers for the solo update/reopen path."""
from solo_mariadb_fixture import MariaDBFixture


def check(root):
    from solo_world import WorldSession, prepare_schema, auction, conquest
    with MariaDBFixture() as db:
        for name in ('accounts', 'accounts_sessions', 'chars', 'item_basic', 'auction_house', 'auction_house_items', 'delivery_box', 'conquest_system'):
            db.run((root / 'sql' / (name + '.sql')).read_text())
        db.run("INSERT INTO chars(charid,accid,charname,pos_zone) VALUES (17,17,'Returning',100);")
        db.run((root / 'sql/triggers.sql').read_text())
        standings = db.run('SELECT * FROM conquest_system ORDER BY region_id;')
        db.run("""INSERT INTO auction_house(itemid, stack, seller, seller_name, date, price)
                  VALUES (4096, 0, 17, 'Returning', UNIX_TIMESTAMP()-3600, 250);""")
        prepare_schema(root, db.run)
        db.run("""INSERT INTO handheld_squad_mates(charid,slot,name,main_job) VALUES(17,1,'Mira',3);
                  INSERT INTO handheld_squad_jobs VALUES(17,1,3,150);""")
        # An older saved world may be staged/checked again or reopened after a stop.
        for _ in range(2):
            prepare_schema(root, db.run)
            world = WorldSession(db.run)
            world.start()
            world.service()
            world.stop()
        assert standings == db.run('SELECT * FROM conquest_system ORDER BY region_id;')
        assert db.run('SELECT charname FROM chars WHERE charid=17;').strip() == 'Returning'
        assert db.run('SELECT total_xp FROM handheld_squad_jobs WHERE charid=17 AND job=3;').strip() == '150'
        assert db.run('SELECT COUNT(*) FROM delivery_box WHERE charid=17 AND quantity=250;').strip() == '1'
        assert int(db.run("SELECT COUNT(*) FROM auction_house WHERE seller_name='LSB Provisioner' AND sale=0;")) > 0
        # Restore this solo backup into a standard build before re-enabling it.
        # End the old simulation clock and withdraw only the supplier's unsold offers.
        conquest.disable(db.run)
        auction.disable(db.run)
        assert db.run('SELECT COUNT(*) FROM lsb_solo_conquest_clock;').strip() == '0'
        assert db.run("SELECT COUNT(*) FROM auction_house WHERE seller_name='LSB Provisioner' AND sale=0;").strip() == '0'
        assert db.run("SELECT COUNT(*) FROM auction_house WHERE seller=17 AND sale=250;").strip() == '1'
        assert db.run('SELECT total_xp FROM handheld_squad_jobs WHERE charid=17 AND job=3;').strip() == '150'
    return dict(state='passed', preserved_character=True, preserved_mate_xp=True, duplicate_payments=0)
