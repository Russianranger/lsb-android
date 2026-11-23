-- Solo HELM keeps the zone's own drops, tool, quests, limits, and relocation.
require('modules/module_utils')
require('scripts/globals/hobbies/helm/logic')
local m = Module:new('handheld_solo_gathering')

m:addOverride('xi.helm.onTrade', function(player, npc, trade, helmType, csid, func)
    local config = xi.settings.handheld_solo or {}
    local info = xi.helm.dataTable[helmType]
    local zoneId = player:getZoneID()
    local original = info and info.zone[zoneId]
    if not original or not player:isPC() or (BotIsBot and BotIsBot(player:getID())) then
        return super(player, npc, trade, helmType, csid, func)
    end

    -- Only the synchronous tool trade sees these two rates. Restore the original
    -- record before returning, even if a downstream quest hook fails.
    local obtain = math.max(0, math.min(100, tonumber(config.HELM_OBTAIN_FLOOR) or 80))
    local multiplier = math.max(0, math.min(1, tonumber(config.HELM_BREAK_SCALE) or 0.5))
    info.zone[zoneId] = setmetatable({
        obtainRate = math.max(original.obtainRate, obtain),
        breakRate = original.breakRate * multiplier,
    }, { __index = original })
    local ok, result = pcall(super, player, npc, trade, helmType, csid, func)
    info.zone[zoneId] = original
    if not ok then
        error(result, 0)
    end
    return result
end)

return m
