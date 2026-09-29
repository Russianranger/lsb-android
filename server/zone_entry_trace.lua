-- LSB Android managed zone-entry trace v1. Diagnostic only; preserve game behavior.
require('modules/module_utils')
local m = Module:new('lsb_android_zone_entry_trace')

local function record(line)
    printf('%s', line)
    local file = io.open('/server-logs/zone-entry.log', 'a')
    if file then
        if file:seek('end') > 262144 then
            file:close()
            file = io.open('/server-logs/zone-entry.log', 'w')
        end
        if file then
            file:write(os.date('%Y-%m-%d %H:%M:%S '), line, '\n')
            file:close()
        end
    end
end

local function trace(player, stage, detail)
    -- Diagnostics must never interrupt a login, even on a full disk.
    pcall(function()
        local line = string.format('[LSB zone entry] char=%s id=%s zone=%s %s %s',
            player:getName(), tostring(player:getID()), tostring(player:getZoneID()), stage, detail or '')
        record(line)
    end)
end

m:addOverride('InteractionGlobal.onZoneIn', function(player, prevZone, fallbackFn)
    trace(player, 'onZoneIn.begin', 'previous=' .. tostring(prevZone))
    local result = super(player, prevZone, fallbackFn)
    local event = type(result) == 'table' and result[1] or result
    trace(player, 'onZoneIn.end', 'event=' .. tostring(event))
    return result
end)

m:addOverride('xi.player.charCreate', function(player)
    trace(player, 'charCreate.begin')
    local result = super(player)
    trace(player, 'charCreate.end')
    return result
end)

m:addOverride('xi.player.onGameIn', function(player, firstLogin, zoning)
    trace(player, 'onGameIn.begin', 'firstLogin=' .. tostring(firstLogin) .. ' zoning=' .. tostring(zoning))
    local result = super(player, firstLogin, zoning)
    trace(player, 'onGameIn.end')
    return result
end)

m:addOverride('InteractionGlobal.afterZoneIn', function(player, fallbackFn)
    trace(player, 'afterZoneIn.begin')
    local result = super(player, fallbackFn)
    trace(player, 'afterZoneIn.end')
    return result
end)

m:addOverride('InteractionGlobal.onEventFinish', function(player, csid, option, npc, fallbackFn)
    trace(player, 'onEventFinish.begin', 'event=' .. tostring(csid) .. ' option=' .. tostring(option))
    local result = super(player, csid, option, npc, fallbackFn)
    trace(player, 'onEventFinish.end', 'event=' .. tostring(csid))
    return result
end)

pcall(record, '[LSB zone entry] trace v1 loaded')
return m
