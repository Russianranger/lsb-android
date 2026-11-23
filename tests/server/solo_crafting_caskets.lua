xi = {
    settings = { main = { MAX_LEVEL = 99, CASKET_DROP_RATE = 1 }, map = { CRAFT_COMMON_CAP = 700 } },
    zone = { GIDDEUS = 145 }, item = { BRASS_RING = 13465, POTION = 4112 },
    objType = { NPC = 2 }, job = { MON = 23 }, status = { DISAPPEAR = 2 },
}
applySettings()
applySettings()
assert(xi.settings.main.MAX_LEVEL == 99 and xi.settings.map.FISHING_ENABLE)
assert(xi.settings.map.CRAFT_SPECIALIZATION_POINTS == 3200)
xi.settings.map = { CRAFT_COMMON_CAP = 600, CRAFT_SPECIALIZATION_POINTS = 400, FISHING_ENABLE = false }
xi.settings.handheld_solo = { ENABLE_FISHING = false }
applySettings()
assert(xi.settings.map.CRAFT_SPECIALIZATION_POINTS == 4000 and not xi.settings.map.FISHING_ENABLE)

local hooks = {}
Module = { new = function()
    return { addOverride = function(_, target, handler) hooks[target] = handler end }
end }
for _, name in ipairs({ 'modules/module_utils', 'scripts/globals/mobs', 'scripts/globals/caskets',
                        'scripts/globals/hobbies/helm/logic' }) do
    package.preload[name] = function() end
end
dofile(moduleDir .. '/gathering.lua')
dofile(moduleDir .. '/caskets.lua')
local now = 10000
GetSystemTime = function() return now end
GetItemByID = function(id) return (id == 13465 or id == 4112) and {} or nil end
math.random = function(low) return low or 0 end

local function entity(id, zoneId)
    local vars = {}
    return {
        getLocalVar = function(_, key) return vars[key] or 0 end,
        setLocalVar = function(_, key, value) vars[key] = value end,
        getID = function() return id end, getZoneID = function() return zoneId end,
    }
end
local player = entity(44, 145)
player.isPC = function() return true end
local info = { obtainRate = 70, breakRate = 20, drops = { 1, 2 } }
xi.helm = { dataTable = { { zone = { [145] = info } } } }
local gather = hooks['xi.helm.onTrade']
super = function(p, npc, trade, typ, csid, fn)
    local adapted = xi.helm.dataTable[typ].zone[145]
    assert(adapted.obtainRate == 80 and adapted.breakRate == 10 and adapted.drops == info.drops)
    assert(p == player and csid == 100 and fn() == 55)
    return 47
end
assert(gather(player, {}, {}, 1, 100, function() return 55 end) == 47)
assert(xi.helm.dataTable[1].zone[145] == info)
super = function() error('fixture') end
assert(not pcall(gather, player, {}, {}, 1))
assert(xi.helm.dataTable[1].zone[145] == info)
BotIsBot = function() return true end
super = function() assert(xi.helm.dataTable[1].zone[145] == info) end
gather(player, {}, {}, 1)
BotIsBot = nil

local gate = 1
player.getLeaderID = function() return gate end
player.getMainJob = function() return 1 end
player.printToPlayer = function() end
player.getTreasurePool = function() return {} end
local awards, gil = {}, 0
player.addTreasure = function(_, item) table.insert(awards, item) end
player.addGil = function(_, amount) gil = gil + amount end
local zone, spawned = {}, {}
player.getZone = function() return zone end
zone.insertDynamicEntity = function(_, spec)
    local chest = entity(#spawned + 1, 145)
    chest.setStatus = function(_, status) chest.status = status end
    chest.updateToEntireZone = function(_, status) assert(status == xi.status.DISAPPEAR) end
    chest.setUntargetable = function() end
    chest.setAnimationSub = function() end
    chest.timer = function(_, ms, fn) assert(ms == 180000); chest.expire = fn end
    chest.trigger = spec.onTrigger
    assert(spec.look == 969 and spec.x == 3 and spec.releaseIdOnDisappear)
    table.insert(spawned, chest)
    return chest
end
local kill = hooks['xi.mob.onMobDeathEx']
local mob = entity(99, 145)
mob.getMaster = function() return nil end
mob.getInstance = function() return nil end
mob.getBattlefield = function() return nil end
mob.getMainLvl = function() return 18 end
mob.getPos = function() return { x = 3, y = 2, z = 5, rot = 1 } end
super = function() end
kill(mob, player, false)
mob.getInstance = function() return {} end
kill(mob, player, true)
mob.getInstance = function() return nil end
assert(#spawned == 0)
kill(mob, player, true)
kill(mob, player, true)
assert(#spawned == 1)
local first = spawned[1]
gate = 2
first.trigger(player, first)
assert(#awards == 0 and gil == 0)
gate = 1
first.trigger(player, first)
first.trigger(player, first)
first.expire(first)
assert(#awards == 1 and awards[1] == 13465 and gil == 46 and first.status == 2)
for _ = 1, 15 do now = now + 1; kill(mob, player, true) end
assert(#spawned == 7)
spawned[2].expire(spawned[2])
spawned[2].trigger(player, spawned[2])
now = now + 1
kill(mob, player, true)
assert(#spawned == 8 and #awards == 1)
local native = entity(222, 100)
native:setLocalVar('[caskets]SPAWNSTATUS', 1)
native:setLocalVar('[caskets]MOBLVL', 5)
native:setLocalVar('[caskets]PARTYID', 1)
native:setLocalVar('[caskets]LOOT_TYPE', 1)
local openNative = hooks['xi.caskets.onTrigger']
gate = 2
super = function() end
openNative(player, native)
assert(native:getLocalVar('[caskets]ITEM1') == 0)
gate = 1
super = function(_, chest) chest:setLocalVar('[caskets]SPAWNSTATUS', 2) end
openNative(player, native)
openNative(player, native)
assert(native:getLocalVar('[caskets]ITEM1') == 4112 and gil == 66)
