"""Advance LandSandBoat conquest during offline hours, before xi_world opens the database."""

from dataclasses import dataclass
import random
import secrets
import time


STEP = 6 * 3600
WEEK = 7 * 86400
MAX_AWAY = 4 * WEEK
# LSB time_server.cpp tallies on MONDAY at 00:00 JST (Sunday 15:00 UTC).
FIRST_TALLY = 3 * 86400 + 15 * 3600
# After an unclean stop this interval may still have been live. Do not replay it.
HEARTBEAT_SECONDS = 300
INFLUENCE_CAP = (2**31 - 1) // 100
COUNTER_CAP = 2**31 - 1
# Home-point weights in src/map/conquest_data.h, indexed by REGION_TYPE.
WEIGHTS = (10, 25, 100, 10, 100, 10, 25, 100, 150, 150, 50, 25, 50, 25, 50, 50, 50, 250, 50)
HOME_REGIONS = ((0, 1, 2), (3, 4, 12), (5, 6, 7))
REGION_COLUMNS = ('region_id', 'region_control', 'region_control_prev', 'sandoria_influence',
                  'bastok_influence', 'windurst_influence', 'mob_kills', 'player_homepoints')
CLOCK = 'lsb_solo_conquest_clock'


@dataclass
class Region:
    id: int
    owner: int
    previous: int
    scores: list
    kills: int
    homepoints: int

    def fields(self):
        return (self.id, self.owner, self.previous, *self.scores, self.kills, self.homepoints)

    def tally(self):
        total = sum(self.scores)
        beastmen = 0
        if self.homepoints:
            beastmen = INFLUENCE_CAP
            if self.kills and total:
                beastmen = min(INFLUENCE_CAP, WEIGHTS[self.id] * self.homepoints * total // self.kills)
        scores = self.scores + [beastmen]
        top = max(scores)
        self.previous = self.owner
        self.owner = scores.index(top) if scores.count(top) == 1 else 5  # neutral on any tie
        self.scores = [0, 0, 0]
        self.kills = self.homepoints = 0

    def gain(self, nation, points):
        # Match ConquestSystem::addInfluencePoints, including saturation and overflow.
        leader = max(self.scores)
        score = self.scores[nation]
        multiplier = 3 if score < leader // 2 else 2 if score < leader else 1
        increase = max(1, points * multiplier // 10)
        overflow = max(0, score + increase - INFLUENCE_CAP)
        self.scores[nation] = min(INFLUENCE_CAP, score + increase)
        if overflow:
            for rival in range(3):
                if rival != nation:
                    self.scores[rival] = max(0, self.scores[rival] - overflow)


def activity(regions, seed, when):
    week = (when - FIRST_TALLY - 1) // WEEK  # a step ending on the tally belongs to the old week
    for region in regions:
        rng = random.Random(f'{seed}:{when}:{region.id}')
        # A different regional mix each week; home territories and incumbents attract more activity.
        local = random.Random(f'{seed}:{week}:{region.id}')
        weekly = [local.uniform(0.65, 1.45) for _ in range(3)]
        nations = [0, 1, 2]
        rng.shuffle(nations)
        for nation in nations:
            points = rng.randint(90, 180) * weekly[nation]
            if region.id in HOME_REGIONS[nation]:
                points *= 1.7
            if region.owner == nation:
                points *= 1.25
            region.gain(nation, int(points))
        kills = rng.randint(2, 6)
        region.kills = min(COUNTER_CAP, region.kills + kills)
        # Higher LSB home-point weights need fewer deaths to keep beastmen competitive.
        if rng.random() < kills / (2 * WEIGHTS[region.id]):
            region.homepoints = min(COUNTER_CAP, region.homepoints + 1)


def advance(regions, seed, last_seen, now, partial):
    """Apply completed six-hour periods and native weekly boundaries in chronological order."""
    start = max(last_seen, now - MAX_AWAY)
    if now <= start:
        return partial, 0, 0
    next_step = start + STEP - partial
    next_tally = FIRST_TALLY + ((start - FIRST_TALLY) // WEEK + 1) * WEEK
    steps = tallies = 0
    while min(next_step, next_tally) <= now:
        if next_step <= next_tally:
            activity(regions, seed, next_step)
            next_step += STEP
            steps += 1
        else:
            for region in regions:
                region.tally()
            next_tally += WEEK
            tallies += 1
    return (partial + now - start) % STEP, steps, tallies


def timestamp(now):
    return max(0, int(time.time() if now is None else now))


def sql_tuple(values):
    return '(' + ','.join(str(int(value)) for value in values) + ')'


def start(query, now=None):
    """query(stmt) runs the MariaDB CLI against the selected game DB. Return a session token."""
    now = timestamp(now)
    columns = ','.join(REGION_COLUMNS)
    engines = dict(line.split() for line in query(
        f"SELECT TABLE_NAME,ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() "
        f"AND TABLE_NAME IN ('conquest_system','{CLOCK}');").splitlines())
    if engines.get('conquest_system') != 'InnoDB' or engines.get(CLOCK, 'InnoDB') != 'InnoDB':
        raise ValueError('Conquest needs the InnoDB conquest_system table from the selected server.')
    # This clock travels with the world's DB through copies, backups and restores.
    lines = query(f"""CREATE TABLE IF NOT EXISTS {CLOCK} (
        id TINYINT UNSIGNED PRIMARY KEY, seed BIGINT UNSIGNED NOT NULL, session_id BIGINT UNSIGNED NOT NULL,
        last_seen BIGINT UNSIGNED NOT NULL, away_remainder INT UNSIGNED NOT NULL
    ) ENGINE=InnoDB;
        START TRANSACTION WITH CONSISTENT SNAPSHOT;
        SELECT COALESCE((SELECT CONCAT_WS(' ',seed,session_id,last_seen,away_remainder) FROM {CLOCK} WHERE id=1),'new');
        SELECT {columns} FROM conquest_system WHERE region_id BETWEEN 0 AND 18 ORDER BY region_id;
        COMMIT;""").splitlines()
    if len(lines) != len(WEIGHTS) + 1:
        raise ValueError('Conquest needs the 19 standard regions; the existing standings were preserved.')
    first_session = lines[0] == 'new'
    if first_session:
        seed, old_session, last_seen, partial = secrets.randbits(63), 0, now, 0
    else:
        seed, old_session, last_seen, partial = map(int, lines[0].split())
    before = [tuple(map(int, line.split())) for line in lines[1:]]
    if [row[0] for row in before] != list(range(len(WEIGHTS))) or not 0 <= partial < STEP:
        raise ValueError('Invalid conquest regions or clock.')
    regions = [Region(r, owner, prev, [s, b, w], kills, hp) for r, owner, prev, s, b, w, kills, hp in before]
    # A dirty stop has no exact shutdown instant. Skip the uncertain final heartbeat window,
    # including any native weekly tally in it. Clean stops retain partial offline hours.
    away_from = last_seen + (HEARTBEAT_SECONDS if old_session else 0)
    remainder, steps, tallies = advance(regions, seed, away_from, now, partial)

    session = secrets.randbelow(2**63 - 1) + 1
    # The CLI opens a fresh connection per call. Lock and compare our read-set here, then
    # commit the complete result with its clock in the very same InnoDB transaction.
    expected = ','.join(sql_tuple(row) for row in before)
    matches = f'(SELECT COUNT(*) FROM conquest_system WHERE ({columns}) IN ({expected}))={len(before)}'
    if first_session:
        claim = f'INSERT IGNORE INTO {CLOCK} SELECT 1,{seed},{session},{now},0 WHERE {matches};'
    else:
        claim = (f'UPDATE {CLOCK} SET session_id={session},last_seen={max(last_seen, now)},away_remainder={remainder} '
                 f'WHERE id=1 AND seed={seed} AND session_id={old_session} AND last_seen={last_seen} '
                 f'AND away_remainder={partial} AND {matches};')
    batch = ['START TRANSACTION;',
             'SELECT COUNT(*) INTO @locked FROM conquest_system WHERE region_id BETWEEN 0 AND 18 FOR UPDATE;',
             claim, 'SET @clock_claimed=ROW_COUNT();']
    for region, original in zip(regions, before):
        values = region.fields()
        if values != original:
            fields = ','.join(f'{key}={value}' for key, value in zip(REGION_COLUMNS[1:], values[1:]))
            batch.append(f'UPDATE conquest_system SET {fields} WHERE region_id={region.id} AND @clock_claimed=1;')
    batch.append('COMMIT; SELECT @clock_claimed;')
    if query('\n'.join(batch)).strip() != '1':
        raise RuntimeError('Conquest changed during startup; catch-up was not committed.')
    print(f'Conquest: {steps} offline six-hour steps, {tallies} weekly tallies.', flush=True)
    return session


def heartbeat(query, session, now=None):
    query(f'UPDATE {CLOCK} SET last_seen=GREATEST(last_seen,{timestamp(now)}) WHERE id=1 AND session_id={int(session)};')


def stop(query, session, now=None):
    query(f'UPDATE {CLOCK} SET session_id=0,last_seen=GREATEST(last_seen,{timestamp(now)}) '
          f'WHERE id=1 AND session_id={int(session)};')


def disable(query):
    """Discard the offline clock when switching this world to ordinary conquest."""
    if query(f"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{CLOCK}';").strip() == '1':
        query(f'DELETE FROM {CLOCK} WHERE id=1;')
