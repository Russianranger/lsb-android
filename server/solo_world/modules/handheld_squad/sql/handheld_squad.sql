-- Each character owns up to six saved mates; the map permits one of them to be called.
-- These tables have no seed data. Reapplying the migration preserves every roster.
CREATE TABLE IF NOT EXISTS handheld_squad_mates (
    charid INT UNSIGNED NOT NULL,
    slot TINYINT UNSIGNED NOT NULL,
    name VARCHAR(15) NOT NULL,
    main_job TINYINT UNSIGNED NOT NULL,
    sub_job TINYINT UNSIGNED NOT NULL DEFAULT 0,
    role VARCHAR(12) NOT NULL DEFAULT 'auto',
    PRIMARY KEY (charid, slot),
    UNIQUE KEY handheld_squad_name (charid, name),
    CONSTRAINT handheld_squad_owner FOREIGN KEY (charid) REFERENCES chars(charid) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE IF NOT EXISTS handheld_squad_jobs (
    charid INT UNSIGNED NOT NULL,
    slot TINYINT UNSIGNED NOT NULL,
    job TINYINT UNSIGNED NOT NULL,
    total_xp INT UNSIGNED NOT NULL DEFAULT 0,
    PRIMARY KEY (charid, slot, job),
    CONSTRAINT handheld_squad_job_owner FOREIGN KEY (charid, slot)
        REFERENCES handheld_squad_mates(charid, slot) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS handheld_squad_gear (
    charid INT UNSIGNED NOT NULL,
    slot TINYINT UNSIGNED NOT NULL,
    job TINYINT UNSIGNED NOT NULL,
    equip_slot TINYINT UNSIGNED NOT NULL,
    itemid SMALLINT UNSIGNED NOT NULL,
    PRIMARY KEY (charid, slot, job, equip_slot),
    CONSTRAINT handheld_squad_gear_owner FOREIGN KEY (charid, slot)
        REFERENCES handheld_squad_mates(charid, slot) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
