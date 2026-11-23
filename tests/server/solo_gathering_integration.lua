-- Copy to scripts/tests/systems/handheld_solo_gathering.lua in the prepared
-- pinned server, then run xi_test on that file with a disposable world database.
local ffi = require('ffi')

describe('Handheld gathering, crafting and fishing', function()
    it('accepts a hatchet at the East Ronfaure logging point and awards a zone drop', function()
        local zone = xi.zone.EAST_RONFAURE
        local p = xi.test.world:spawnPlayer({ zone = zone })
        local info = xi.helm.dataTable[xi.helmType.LOGGING]
        local original = info.zone[zone]
        xi.test.world:skipTime(info.respawnTime + 4)
        local point = assert(zones[zone].npc.LOGGING[1], 'expected a registered logging point')
        p:addItem(xi.item.HATCHET)
        -- The first roll (76) misses retail's 73.22% obtain rate and succeeds at
        -- the solo floor (80%). The second (5) breaks a retail hatchet at 7.10%
        -- but keeps it at the solo rate of 3.55%. Only control the two RNG rolls.
        local rolls = { 76, 5 }
        local randomFloat = math.randomFloat
        stub('math.randomFloat', function(a, b)
            if a == 0 and b == 100 and #rolls > 0 then return table.remove(rolls, 1) end
            return randomFloat(a, b)
        end)
        p.actions:tradeNpc(point, { xi.item.HATCHET }, { eventId = 901 })
        assert(#rolls == 0, 'the trade did not reach both HELM rolls')
        local count = 0
        for _, drop in ipairs(original.drops) do count = count + p:getItemCount(drop[2]) end
        assert(count == 1 and p:getItemCount(xi.item.HATCHET) == 1, 'expected one zone drop and the retained hatchet')
        assert(info.zone[zone] == original and original.obtainRate == 73.22 and original.breakRate == 7.10,
            'solo trade leaked its rate overrides into the zone')
    end)

    it('raises a second craft above 70 without reducing an existing 110 craft', function()
        local p = xi.test.world:spawnPlayer({ zone = xi.zone.SOUTHERN_SAN_DORIA })
        local craft, other = xi.skill.WOODWORKING, xi.skill.SMITHING
        local crystal, log, lumber = xi.item.WIND_CRYSTAL, xi.item.PETRIFIED_LOG, xi.item.PIECE_OF_ANCIENT_LUMBER
        p:setSkillRank(other, 10)
        p:setSkillLevel(other, 1100)
        p:setSkillRank(craft, 9)
        p:setSkillLevel(craft, 705)
        -- Use a legal level 72 recipe. Keep the actual native synth transaction,
        -- material use, result award and skill specialization checks in the loop.
        p:setMod(xi.mod.SYNTH_SKILL_GAIN, 100)
        xi.test.world:setSeed(1)
        for _ = 1, 4 do
            p:addItem(crystal)
            if not p:hasItem(log) then p:addItem(log) end
            p.actions:craft(crystal, { log })
            xi.test.world:skipTime(17)
            xi.test.world:skipTime(15)
            if p:hasItem(lumber) and p:getCharSkillLevel(craft) > 705 then break end
        end
        assert(p:hasItem(lumber) and not p:hasItem(crystal) and not p:hasItem(log), 'expected native synth to consume both materials and award lumber')
        assert(p:getCharSkillLevel(craft) > 705 and p:getCharSkillLevel(other) == 1100, 'the woodworking skill-up reduced smithing')
    end)

    it('starts fishing with an equipped rod and bait and releases the line', function()
        local p = xi.test.world:spawnPlayer({ zone = xi.zone.PORT_SAN_DORIA, level = 10 })
        p:addItem(xi.item.BAMBOO_FISHING_ROD)
        p:addItem(xi.item.LITTLE_WORM)
        p:equipItem(xi.item.BAMBOO_FISHING_ROD, nil, xi.slot.RANGED)
        p:equipItem(xi.item.LITTLE_WORM, nil, xi.slot.AMMO)
        assert(p:getEquipID(xi.slot.RANGED) == xi.item.BAMBOO_FISHING_ROD and p:getEquipID(xi.slot.AMMO) == xi.item.LITTLE_WORM)
        -- Match the layouts in 0x01a_action.h and 0x110_fishing_2.h. The server
        -- sets fishing animation 56 only after area, settings, gear and bait checks.
        local cast = ffi.new('struct { uint32_t header, id; uint16_t index, action; uint32_t args[4]; }')
        cast.id, cast.index, cast.action = p:getID(), p:getTargID(), 0x0E
        p.packets:send(0x01A, cast, ffi.sizeof(cast))
        assert(p:getCharVar('[Fish]LastCastTime') > 0 and p:getAnimation() == xi.animation.NEW_FISHING_START, 'native fishing did not start')
        local release = ffi.new('struct { uint32_t header, id; int32_t param; uint16_t index; uint8_t mode, padding; int32_t param2; }')
        release.id, release.index, release.mode = p:getID(), p:getTargID(), 4
        p.packets:send(0x110, release, ffi.sizeof(release))
        assert(p:getAnimation() == xi.animation.NONE and p:hasItem(xi.item.BAMBOO_FISHING_ROD), 'failed to release the line cleanly')
    end)
end)
