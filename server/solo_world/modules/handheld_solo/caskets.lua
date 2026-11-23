-- Stock-zone blue caskets award ordinary consumables. Brown caskets keep
-- their native lock and zone-specific drops. Extra zones use built-in models.
require('modules/module_utils')
require('scripts/globals/caskets')
require('scripts/globals/mobs')
local m = Module:new('handheld_solo_caskets')

local extraZones = {}
for _, name in ipairs({
    'GHELSBA_OUTPOST', 'FORT_GHELSBA', 'GIDDEUS', 'PALBOROUGH_MINES',
    'DAVOI', 'BEADEAUX', 'CASTLE_OZTROJA', 'BIBIKI_BAY', 'ATTOHWA_CHASM',
    'BHAFLAU_THICKETS', 'WAJAOM_WOODLANDS', 'CAEDARVA_MIRE',
    'CEIZAK_BATTLEGROUNDS', 'YAHSE_HUNTING_GROUNDS',
}) do
    if xi.zone[name] then
        extraZones[xi.zone[name]] = true
    end
end

local supplies = {
    { 'POTION', 'ANTIDOTE', 'FLASK_OF_ECHO_DROPS' },
    { 'HI_POTION', 'ETHER', 'POT_OF_SILENT_OIL' },
    { 'X_POTION', 'HI_ETHER', 'PINCH_OF_PRISM_POWDER' },
}
local materials = {
    { 'CHUNK_OF_COPPER_ORE', 'ASH_LOG', 'SHEEPSKIN' },
    { 'CHUNK_OF_IRON_ORE', 'LIZARD_SKIN', 'SILVER_INGOT' },
    { 'CHUNK_OF_MYTHRIL_ORE', 'DARKSTEEL_INGOT', 'ROSEWOOD_LOG' },
}
local rings = {
    { 'BRASS_RING' },
    { 'SILVER_RING', 'AMETHYST_RING' },
    { 'MYTHRIL_RING', 'GARNET_RING', 'FLUORITE_RING' },
}
local function rollItem(pools, level)
    local tier = level >= 50 and 3 or level >= 20 and 2 or 1
    local pool = pools[tier]
    local index = math.random(1, #pool)
    for offset = 0, #pool - 1 do
        local itemId = xi.item[pool[(index + offset - 1) % #pool + 1]]
        if itemId and GetItemByID(itemId) then
            return itemId
        end
    end
end

local function isOwner(player, npc, key)
    local owner = npc:getLocalVar(key)
    return player:isPC() and owner > 0 and owner == player:getLeaderID()
end

local function payGil(player, level)
    local gil = math.random(10, 20) + math.min(99, math.max(1, level)) * 2
    player:addGil(gil)
    player:printToPlayer(string.format('You find %d gil in the casket.', gil))
end

m:addOverride('xi.caskets.onTrigger', function(player, npc)
    local owner = isOwner(player, npc, '[caskets]PARTYID')
    local level = npc:getLocalVar('[caskets]MOBLVL')
    if owner and npc:getLocalVar('[caskets]SPAWNSTATUS') == 1 and
        npc:getLocalVar('[caskets]LOOT_TYPE') == 1 and npc:getLocalVar('[caskets]ITEMS_SET') == 0 then
        local itemId = rollItem(supplies, level)
        if itemId then
            npc:setLocalVar('[caskets]ITEM1', itemId)
            npc:setLocalVar('[caskets]ITEMS_SET', 1)
            npc:setLocalVar('[caskets]LOOT_TYPE', 2)
        end
    end
    super(player, npc)
    if owner and npc:getLocalVar('[caskets]SPAWNSTATUS') == 2 and
        npc:getLocalVar('[handheld]GilPaid') == 0 then
        npc:setLocalVar('[handheld]GilPaid', 1)
        payGil(player, level)
    end
end)

-- Account for unloaded zones as well as normal timer expiry. Only consult a
-- zone's handful of entries after an eligible kill rolls a casket.
local active = {}
local lifetime = 180
local function roomInZone(zoneId, now)
    local slots = active[zoneId] or {}
    local count = 0
    for id, expires in pairs(slots) do
        if expires <= now then
            slots[id] = nil
        else
            count = count + 1
        end
    end
    active[zoneId] = slots
    return count < 6
end

local function removeChest(npc)
    local slots = active[npc:getZoneID()]
    if slots then
        slots[npc:getID()] = nil
    end
    npc:setUntargetable(true)
    npc:setStatus(xi.status.DISAPPEAR)
    npc:updateToEntireZone(xi.status.DISAPPEAR, 0)
end

local function openChest(player, npc)
    if npc:getLocalVar('Used') ~= 0 or not isOwner(player, npc, 'Owner') then
        return
    end
    local itemId = npc:getLocalVar('Reward')
    if itemId <= 0 or not GetItemByID(itemId) or not player:getTreasurePool() then
        return
    end
    npc:setLocalVar('Used', 1)
    npc:setUntargetable(true)
    player:addTreasure(itemId, npc)
    payGil(player, npc:getLocalVar('Level'))
    removeChest(npc)
end

m:addOverride('xi.mob.onMobDeathEx', function(mob, player, isKiller, isWeaponSkillKill)
    super(mob, player, isKiller, isWeaponSkillKill)
    local zoneId = mob:getZoneID()
    if not isKiller or not extraZones[zoneId] or not player or not player:isPC() or
        mob:getMaster() or mob:getInstance() or mob:getBattlefield() or
        player:getMainJob() == xi.job.MON or (BotIsBot and BotIsBot(player:getID())) then
        return
    end

    local now = GetSystemTime()
    if mob:getLocalVar('[handheld]CasketKill') == now then
        return
    end
    mob:setLocalVar('[handheld]CasketKill', now)
    local rate = xi.settings.main.CASKET_DROP_RATE
    if rate <= 0 or math.random() >= rate or not roomInZone(zoneId, now) then
        return
    end
    local level = mob:getMainLvl()
    local roll = math.random(1, 100)
    local pool, model = supplies, 965
    if roll <= 5 then
        pool, model = rings, 969
    elseif roll <= 30 then
        pool, model = materials, 966
    end
    local itemId = rollItem(pool, level)
    if not itemId then
        return
    end
    local pos = mob:getPos()
    local npc = player:getZone():insertDynamicEntity({
        objtype = xi.objType.NPC, name = 'Handheld_Casket', packetName = 'Treasure Casket',
        look = model, x = pos.x, y = pos.y, z = pos.z, rotation = pos.rot,
        onTrigger = openChest, releaseIdOnDisappear = true,
    })
    if not npc then
        return
    end
    npc:setLocalVar('Owner', player:getLeaderID())
    npc:setLocalVar('Level', level)
    npc:setLocalVar('Reward', itemId)
    active[zoneId][npc:getID()] = now + lifetime
    npc:setAnimationSub(4)
    npc:timer(lifetime * 1000, function(chest)
        if chest:getLocalVar('Used') == 0 then
            chest:setLocalVar('Used', 1)
            removeChest(chest)
        end
    end)
    player:printToPlayer('The monster was concealing a treasure casket!')
end)

return m
