// SPDX-License-Identifier: GPL-3.0-or-later
#include "handheld_mate_rules.h"

#include "common/lua.h"
#include "map/entities/char_entity.h"
#include "map/entities/trust_entity.h"
#include "map/items/item_weapon.h"
#include "map/lua/lua_base_entity.h"
#include "map/mob_spell_container.h"
#include "map/modifier.h"
#include "map/party.h"
#include "map/utils/itemutils.h"
#include "map/utils/moduleutils.h"
#include "map/utils/trustutils.h"

#include <algorithm>
#include <array>
#include <memory>
#include <optional>

namespace
{
using Outfit = std::array<const CItemEquipment*, 16>;

auto playerOf(CLuaBaseEntity* entity) -> CCharEntity*
{
    return entity ? dynamic_cast<CCharEntity*>(entity->GetBaseEntity()) : nullptr;
}

auto activeMate(CCharEntity& owner) -> CTrustEntity*
{
    for (auto* trust : owner.PTrusts)
    {
        if (trust->PMaster == &owner && trust->GetLocalVar("HANDHELD_MATE") == owner.id)
        {
            return trust;
        }
    }
    return nullptr;
}

auto equipment(int itemId, int job, int level, int slot) -> const CItemEquipment*
{
    if (itemId < 1 || itemId >= MAX_ITEMID)
    {
        return nullptr;
    }
    const auto* item = xi::items::lookup<CItemEquipment>(static_cast<uint16>(itemId));
    if (!item || !handheld::canWear(job, level, slot, item->getReqLvl(), item->getJobs(), item->getEquipSlotId()))
    {
        return nullptr;
    }
    return item;
}

void equipMate(CTrustEntity& mate, const Outfit& gear)
{
    uint16 occupied = 0;
    uint16 blocked  = 0;
    for (unsigned slot = 0; slot < gear.size(); ++slot)
    {
        const auto* item = gear[slot];
        if (!item || (blocked & (1U << slot)) || (occupied & item->getRemoveSlotId()))
        {
            continue;
        }

        occupied |= static_cast<uint16>(1U << slot);
        blocked |= item->getRemoveSlotId();
        for (const auto& mod : item->modList)
        {
            mate.addModifier(mod.getModID(), mod.getModAmount());
        }
        // Mob entities own their four weapon pointers. Copy the complete item so damage,
        // delay, skill and multi-hit use the same data as the player's original weapon.
        if (slot <= SLOT_AMMO)
        {
            if (const auto* weapon = dynamic_cast<const CItemWeapon*>(item))
            {
                auto copy = std::make_unique<CItemWeapon>(*weapon);
                delete mate.m_Weapons[slot];
                mate.m_Weapons[slot] = copy.release();
            }
        }
    }
}

bool learnsSpell(CTrustEntity& mate, int id)
{
    if (id < 1 || id >= static_cast<int>(MAX_SPELL_ID))
    {
        return false;
    }
    auto* spell = spell::GetSpell(static_cast<SpellID>(id));
    if (!spell)
    {
        return false;
    }
    constexpr std::array blueSet{ 577, 597, 529, 623, 569, 554, 560, 611, 549, 578, 593 };
    const auto group = spell->getSpellGroup();
    const auto requirements = spell->getRequirements();
    constexpr int addendum = SPELLREQ_ADDENDUM_BLACK | SPELLREQ_ADDENDUM_WHITE;
    if (group == SPELLGROUP_TRUST || group == SPELLGROUP_SUMMONING || group == SPELLGROUP_NONE ||
        (group == SPELLGROUP_BLUE && std::ranges::find(blueSet, id) == blueSet.end()) ||
        (requirements & ~(addendum | SPELLREQ_MAIN_JOB_ONLY)))
    {
        return false;
    }
    auto learnsAs = [&](xi::Job job, uint8 level)
    {
        // Addenda unlock these spells for Scholar. Other jobs learn their own
        // versions normally, for example Poisona on White Mage.
        return level && spell->getJob(job) <= level && !(job == xi::Job::SCH && (requirements & addendum));
    };
    return learnsAs(mate.GetMJob(), mate.GetMLevel()) ||
           (!(requirements & SPELLREQ_MAIN_JOB_ONLY) && learnsAs(mate.GetSJob(), mate.GetSLevel()));
}

bool canCast(CLuaBaseEntity* entity, int id)
{
    auto* mate = entity ? dynamic_cast<CTrustEntity*>(entity->GetBaseEntity()) : nullptr;
    return mate && mate->GetLocalVar("HANDHELD_MATE") && learnsSpell(*mate, id);
}

void configureJobSpells(CTrustEntity& mate)
{
    const auto mainJob = mate.GetMJob();
    constexpr std::array jobs{ xi::Job::SMN, xi::Job::BLU, xi::Job::DNC, xi::Job::SCH };
    if (std::ranges::find(jobs, mainJob) == jobs.end() || !mate.SpellContainer)
    {
        return;
    }
    // These jobs have custom gambits. Build their spell families from the mate's
    // actual jobs and effective levels, including spells granted by its subjob.
    mate.SpellContainer->ClearSpells();
    for (int id = 1; id < static_cast<int>(MAX_SPELL_ID); ++id)
    {
        if (learnsSpell(mate, id))
        {
            mate.SpellContainer->AddSpell(static_cast<SpellID>(id));
        }
    }
}

auto spawnMate(CLuaBaseEntity* player, int templateId, const std::string& name, int job, int level,
               int subJob, int subLevel, const sol::table& equipmentIds) -> std::optional<CLuaBaseEntity>
{
    auto* owner = playerOf(player);
    if (!owner || !owner->loc.zone || owner->isDead() || activeMate(*owner) || templateId < 896 || templateId >= MAX_SPELL_ID ||
        !handheld::validProfile(name, job, level, subJob, subLevel))
    {
        return std::nullopt;
    }
    if (const auto* party = owner->PParty)
    {
        if (party->m_PAlliance || owner->PParty->GetLeader() != owner ||
            std::max<std::size_t>(party->members.size(), party->LoadPartySize()) + owner->PTrusts.size() >= 6)
        {
            return std::nullopt;
        }
    }

    Outfit gear{};
    for (int slot = 0; slot < static_cast<int>(gear.size()); ++slot)
    {
        gear[slot] = equipment(equipmentIds.get_or(slot, 0), job, level, slot);
    }
    auto configure = [&gear](CTrustEntity& trust)
    {
        equipMate(trust, gear);
        configureJobSpells(trust);
    };
    const trustutils::PersonalMate profile{
        name, static_cast<uint8>(job), static_cast<uint8>(level), static_cast<uint8>(subJob), static_cast<uint8>(subLevel), configure
    };
    if (auto* trust = trustutils::SpawnTrust(owner, static_cast<uint32>(templateId), &profile))
    {
        return CLuaBaseEntity(trust);
    }
    return std::nullopt;
}

bool wasReleased(CLuaBaseEntity* entity)
{
    auto* mate = entity ? dynamic_cast<CTrustEntity*>(entity->GetBaseEntity()) : nullptr;
    return mate && mate->GetLocalVar("HANDHELD_MATE") && mate->released();
}

bool dismissMate(CLuaBaseEntity* player)
{
    auto* owner = playerOf(player);
    auto* mate  = owner ? activeMate(*owner) : nullptr;
    if (!mate || mate->released())
    {
        return false;
    }
    mate->setReleased(true);
    owner->RemoveTrust(mate);
    return true;
}
} // namespace

class HandheldTrustModule final : public CPPModule
{
    void OnInit() override
    {
        lua.set_function("SpawnHandheldMate", spawnMate);
        lua.set_function("HandheldCanCastSpell", canCast);
        lua.set_function("DismissHandheldMate", dismissMate);
        lua.set_function("HandheldMateWasReleased", wasReleased);
        lua.set_function("HandheldCanEquip", [](int id, int job, int level, int slot) { return equipment(id, job, level, slot) != nullptr; });
    }
};

REGISTER_CPP_MODULE(HandheldTrustModule);
