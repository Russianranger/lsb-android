// Character-owned squad records. Job XP is cumulative so a gain can be committed
// atomically without overwriting another gain or splitting a level-up over two saves.
#include "common/database.h"
#include "common/lua.h"
#include "map/utils/charutils.h"
#include "map/utils/moduleutils.h"
#include "handheld_mate_rules.h"

#include <algorithm>
#include <stdexcept>
#include <string>

namespace
{
constexpr uint8 MaxLevel = 75;

uint32 xpToReach(const uint8 level)
{
    uint32 total = 0;
    for (uint8 i = 1; i < level; ++i)
    {
        total += charutils::GetExpNEXTLevel(i);
    }
    return total;
}

uint8 levelFromTotal(uint32& xp)
{
    uint8 level = 1;
    while (level < MaxLevel)
    {
        const uint32 needed = charutils::GetExpNEXTLevel(level);
        if (needed == 0 || xp < needed)
        {
            break;
        }
        xp -= needed;
        ++level;
    }
    return level;
}

sol::table roster(uint32 owner)
{
    auto out = ::lua.create_table();
    const auto mates = db::preparedStmt(
        "SELECT slot, name, main_job, sub_job, role FROM handheld_squad_mates WHERE charid = ? ORDER BY slot", owner);
    if (!mates)
    {
        throw std::runtime_error("Squad roster is unavailable");
    }
    while (mates->next())
    {
        const int slot = mates->get<uint8>("slot");
        auto row = ::lua.create_table();
        row["name"] = mates->get<std::string>("name");
        row["main"] = mates->get<uint8>("main_job");
        row["sub"] = mates->get<uint8>("sub_job");
        row["role"] = mates->get<std::string>("role");
        row["jobs"] = ::lua.create_table();
        row["gear"] = ::lua.create_table();
        out[slot] = row;
    }
    const auto jobs = db::preparedStmt("SELECT slot, job, total_xp FROM handheld_squad_jobs WHERE charid = ?", owner);
    const auto gear = db::preparedStmt("SELECT g.slot, g.equip_slot, g.itemid FROM handheld_squad_gear g "
                                       "JOIN handheld_squad_mates m ON m.charid=g.charid AND m.slot=g.slot AND m.main_job=g.job "
                                       "WHERE g.charid = ?", owner);
    if (!jobs || !gear)
    {
        throw std::runtime_error("Squad job or gear data is unavailable");
    }
    while (jobs->next())
    {
        const int slot = jobs->get<uint8>("slot");
        const int job = jobs->get<uint8>("job");
        uint32 xp = jobs->get<uint32>("total_xp");
        auto progress = ::lua.create_table();
        const uint8 level = levelFromTotal(xp);
        progress["level"] = level;
        progress["xp"] = xp;
        progress["needed"] = level == MaxLevel ? 0U : charutils::GetExpNEXTLevel(level);
        out[slot]["jobs"][job] = progress;
    }
    while (gear->next())
    {
        out[static_cast<int>(gear->get<uint8>("slot"))]["gear"][static_cast<int>(gear->get<uint8>("equip_slot"))] =
            gear->get<uint16>("itemid");
    }
    return out;
}

bool createMate(uint32 owner, uint8 slot, const std::string& name, uint8 job, uint8 level)
{
    if (slot < 1 || slot > 6 || !handheld::validJobLevel(job, level) || level > 12)
    {
        return false;
    }
    return db::transaction([&]()
    {
        if (!db::preparedStmt("INSERT INTO handheld_squad_mates (charid, slot, name, main_job) VALUES (?, ?, ?, ?)", owner, slot, name, job) ||
            !db::preparedStmt("INSERT INTO handheld_squad_jobs (charid, slot, job, total_xp) VALUES (?, ?, ?, ?)", owner, slot, job, xpToReach(level)))
        {
            throw std::runtime_error("Could not create squad mate");
        }
    });
}

bool releaseMate(uint32 owner, uint8 slot, const std::string& name)
{
    const auto result = db::preparedStmt("DELETE FROM handheld_squad_mates WHERE charid=? AND slot=? AND name=?", owner, slot, name);
    return result && result->rowsAffected() == 1;
}

bool setProfile(uint32 owner, uint8 slot, uint8 main, uint8 sub, const std::string& role)
{
    if (main < 1 || main > handheld::lastEraJob || sub > handheld::lastEraJob || sub == main)
    {
        return false;
    }
    return db::preparedStmt("UPDATE handheld_squad_mates SET main_job=?, sub_job=?, role=? WHERE charid=? AND slot=?",
                            main, sub, role, owner, slot) != nullptr;
}

// Copy *only eligible, currently equipped* base item IDs supplied by the command.
// Replace the outfit in a transaction; an empty outfit deliberately clears it.
bool setGear(uint32 owner, uint8 slot, uint8 job, const sol::table& items)
{
    return db::transaction([&]()
    {
        if (!db::preparedStmt("DELETE FROM handheld_squad_gear WHERE charid=? AND slot=? AND job=?", owner, slot, job))
        {
            throw std::runtime_error("Could not replace squad gear");
        }
        for (int equipSlot = 0; equipSlot < 16; ++equipSlot)
        {
            const auto item = items.get_or(equipSlot, 0);
            if (item > 0 && !db::preparedStmt("INSERT INTO handheld_squad_gear (charid, slot, job, equip_slot, itemid) "
                                               "VALUES (?, ?, ?, ?, ?)", owner, slot, job, equipSlot, item))
            {
                throw std::runtime_error("Could not save squad gear");
            }
        }
    });
}

uint8 gainXp(uint32 owner, uint8 slot, uint8 job, uint32 gain)
{
    if (gain == 0 || slot < 1 || slot > 6 || job < 1 || job > handheld::lastEraJob)
    {
        return 0;
    }
    static const uint32 cap = xpToReach(MaxLevel);
    const auto updated = db::preparedStmt(
        "INSERT INTO handheld_squad_jobs (charid, slot, job, total_xp) VALUES (?, ?, ?, ?) "
        "ON DUPLICATE KEY UPDATE total_xp=LEAST(total_xp+VALUES(total_xp), ?)",
        owner, slot, job, std::min(gain, cap), cap);
    if (!updated)
    {
        return 0;
    }
    const auto result = db::preparedStmt("SELECT total_xp FROM handheld_squad_jobs WHERE charid=? AND slot=? AND job=?", owner, slot, job);
    if (!result || !result->next())
    {
        return 0;
    }
    uint32 xp = result->get<uint32>("total_xp");
    return levelFromTotal(xp);
}
}

class HandheldSquadStorage : public CPPModule
{
    void OnInit() override
    {
        ::lua.set_function("HandheldSquadRoster", roster);
        ::lua.set_function("HandheldSquadCreate", createMate);
        ::lua.set_function("HandheldSquadSetProfile", setProfile);
        ::lua.set_function("HandheldSquadRelease", releaseMate);
        ::lua.set_function("HandheldSquadSetGear", setGear);
        ::lua.set_function("HandheldSquadGainXp", gainXp);
    }
};
REGISTER_CPP_MODULE(HandheldSquadStorage);
