-- On a disposable, Solo World-enabled server, copy this file into
-- scripts/tests/systems/handheld_squad_live.lua. Link the two handheld_squad
-- C++ modules into xi_test as described in native-solo-tests.md.
local ffi = require('ffi')

-- Send a normal client Say packet. Its payload starts after the header, mode and padding.
local function command(client, text)
    local message = '!' .. text
    local size = 4 * math.ceil((6 + #message + 1) / 4)
    local packet = ffi.new('uint8_t[?]', size)
    ffi.copy(packet + 6, message, #message)
    client.packets:send(0x0B5, packet, size)
end

describe('Handheld native integration', function()
    local function active(p)
        for _, mate in ipairs(p:getPartyWithTrusts()) do
            if mate:isTrust() and mate:getLocalVar('HANDHELD_MATE') == p:getID() then
                return mate
            end
        end
    end

    it('persists a WHM, fights, zones, releases with the normal menu, and recalls', function()
        local owner = xi.test.world:spawnPlayer({ zone = xi.zone.WEST_RONFAURE, level = 12, job = xi.job.WAR })
        local id = owner:getID()
        local p = GetPlayerByID(id)
        assert(p, 'zone lookup of the fixture player failed')
        assert(p:getGMLevel() == 0, 'expected an ordinary character')
        assert(xi.commands.squad.cmdprops.permission == 0, 'command missing or privileged')
        command(owner, 'squad new June WHM 10')
        local roster = HandheldSquadRoster(id)
        assert(roster[1] and roster[1].name == 'June' and roster[1].jobs[3].level == 10, 'new roster not persisted')
        command(owner, 'squad call 1')
        xi.test.world:skipTime(1)
        local mate = active(p)
        assert(mate, string.format('native template missing: call policy=%s owner=%s partySize=%d, alive=%s enmity=%s',
            tostring(xi.trust.canCast(p, GetSpell(xi.magic.spell.KUPIPI))), p:getName(),
            #p:getPartyWithTrusts(), tostring(p:isAlive()), tostring(p:hasEnmity())))
        assert(mate:getMainJob() == xi.job.WHM and mate:getMainLvl() == 10, 'expected WHM level 10')
        assert(mate:getPacketName() == 'June' and mate:getTrustID() == xi.magic.spell.KUPIPI, 'expected named Kupipi template')
        assert(mate:getMaxMP() > 0 and #p:getPartyWithTrusts() == 2, 'expected caster MP and one mate')
        command(owner, 'squad call 1')
        assert(#p:getPartyWithTrusts() == 2, 'duplicate call accepted')

        -- The normal packet engage path and actual EXP distribution, with a controlled target.
        local rabbit = owner.entities:moveTo('Wild_Rabbit')
        rabbit:setLevelRange(12, 12)
        rabbit:respawn()
        owner:setUnkillable(true)
        owner:setMod(xi.mod.ACC, 1000)
        owner:setMod(xi.mod.ATT, 1000)
        mate:setPos(owner:getXPos(), owner:getYPos(), owner:getZPos())
        local cure = false
        mate:addListener('MAGIC_USE', 'FIXTURE_CURE_OBSERVER', function(caster, target, spell)
            if target:getID() == id and spell:getID() >= 1 and spell:getID() <= 6 then
                cure = true
            end
        end)
        rabbit:setUnkillable(true)
        owner:setHP(math.max(2, math.floor(owner:getMaxHP() / 8)))
        owner.actions:engage(rabbit)
        for _ = 1, 20 do
            xi.test.world:tickEntity(owner)
            xi.test.world:tickEntity(mate)
            xi.test.world:skipTime(2)
            if cure then
                break
            end
        end
        assert(cure, 'summoned WHM did not complete a Cure on its injured owner during combat')
        rabbit:setUnkillable(false)
        owner:claimAndKillMob(rabbit)
        xi.test.world:skipTime(4)
        assert(not rabbit:isAlive(), 'engaged target survived')
        local gained = HandheldSquadRoster(id)[1].jobs[3]
        assert(gained.level > 10 or gained.xp > 0, 'no squad XP on real claimed mob death')
        owner:gotoZone(xi.zone.EAST_RONFAURE)
        xi.test.world:skipTime(10)
        p = GetPlayerByID(id)
        mate = active(p)
        assert(mate and mate:getPacketName() == 'June', 'zoning failed to restore mate')
        owner.entities:moveTo(mate)
        owner.actions:trigger(mate)
        assert(p:getCharVar('[HHSquad]Recall') == 0, 'menu release failed to clear the persisted recall slot immediately')
        xi.test.world:skipTime(3)
        xi.test.world:tickEntity(owner)
        xi.test.world:skipTime(2)
        assert(not active(p), 'menu release failed to remove the active mate')
        assert(p:getCharVar('[HHSquad]Recall') == 0, 'menu removed the mate but left the persisted recall slot set')

        owner:gotoZone(xi.zone.WEST_RONFAURE)
        xi.test.world:skipTime(10)
        p = GetPlayerByID(id)
        assert(not active(p), 'released mate returned after zoning')
        command(owner, 'squad call 1')
        assert(active(p), 'manual recall of persisted mate failed')

        -- Release and cross the zone line before the client animation has completed.
        owner.actions:trigger(active(p))
        owner:gotoZone(xi.zone.EAST_RONFAURE)
        xi.test.world:skipTime(10)
        p = GetPlayerByID(id)
        assert(not active(p) and p:getCharVar('[HHSquad]Recall') == 0, 'immediate zoning resurrected a released mate')
        command(owner, 'squad call 1')
        mate = active(p)
        assert(mate, 'manual recall after immediate zoning failed')

        -- A normal Trust and the personal companion can be released independently.
        owner:spawnTrust(xi.magic.spell.NAJI)
        xi.test.world:skipTime(1)
        local guest
        for _, member in ipairs(p:getPartyWithTrusts()) do
            if member:isTrust() and member:getTrustID() == xi.magic.spell.NAJI then
                guest = member
            end
        end
        assert(guest, 'ordinary control Trust did not spawn')
        owner.actions:trigger(guest)
        xi.test.world:skipTime(3)
        assert(active(p) and p:getCharVar('[HHSquad]Recall') == 1, 'releasing a different Trust cleared the personal recall')

        local died = false
        mate:addListener('DEATH', 'FIXTURE_MATE_DEATH', function() died = true end)
        local attacker = owner.entities:moveTo('Wild_Rabbit')
        mate:takeDamage(mate:getMaxHP() + 1, attacker, xi.attackType.PHYSICAL, xi.damageType.SLASHING)
        xi.test.world:tickEntity(mate)
        xi.test.world:skipTime(3)
        assert(died, 'lethal damage failed to trigger a real Trust death')
        assert(not active(p), 'fallen mate remained in the party')
        assert(p:getCharVar('[HHSquad]Recall') == 0, 'fallen mate kept its recall slot')
        assert(HandheldSquadRoster(id)[1].jobs[3].xp == gained.xp, 'fallen mate lost saved job progress')
        owner:gotoZone(xi.zone.WEST_RONFAURE)
        xi.test.world:skipTime(10)
        assert(not active(GetPlayerByID(id)), 'fallen mate returned automatically on zoning')
    end)

    it('reads native hobby settings and lets the player claim one expanded casket', function()
        assert(xi.test.world:getSetting('map.CRAFT_SPECIALIZATION_POINTS') == 3200, 'native crafting settings')
        assert(xi.test.world:getSetting('map.FISHING_ENABLE') == true, 'native fishing settings')
        assert(xi.test.world:getSetting('main.MAX_LEVEL') == 99, 'native character level cap')
        xi.test.world:setSetting('main.CASKET_DROP_RATE', 1)
        local owner = xi.test.world:spawnPlayer({ zone = xi.zone.GHELSBA_OUTPOST, level = 12, job = xi.job.WAR })
        owner:setUnkillable(true)
        owner:setMod(xi.mod.ACC, 1000)
        owner:setMod(xi.mod.ATT, 1000)
        local mob = owner.entities:moveTo('Orcish_Grunt')
        mob:setLevelRange(12, 12)
        mob:respawn()
        owner:claimAndKillMob(mob)
        xi.test.world:skipTime(3)
        assert(not mob:isAlive(), 'eligible monster did not die')
        local p = GetPlayerByID(owner:getID())
        local chests = p:getZone():queryEntitiesByName('DE_Handheld_Casket')
        assert(#chests == 1 and chests[1]:getLocalVar('Reward') > 0, 'expected one real casket with a valid item')
        local chest = chests[1]
        assert(chest:getLocalVar('Owner') == p:getLeaderID(), 'claim is not owned by the killer')
        owner.entities:moveTo(chest)
        local gil = p:getGil()
        owner.actions:trigger(chest)
        xi.test.world:skipTime(1)
        assert(p:getGil() > gil, 'casket trigger did not award its claimed gil')
    end)

    it('calls and dismisses all twenty jobs with their stock Trust identities', function()
        local owner = xi.test.world:spawnPlayer({ zone = xi.zone.WEST_RONFAURE, level = 75, job = xi.job.WAR })
        local p = GetPlayerByID(owner:getID())
        local jobNames = {
            'WAR', 'MNK', 'WHM', 'BLM', 'RDM', 'THF', 'PLD', 'DRK', 'BST', 'BRD',
            'RNG', 'SAM', 'NIN', 'DRG', 'SMN', 'BLU', 'COR', 'PUP', 'DNC', 'SCH',
        }
        local templates = {
            897, 933, 898, 896, 952, 901, 902, 906, 943, 911,
            940, 900, 918, 915, 923, 923, 967, 923, 945, 968,
        }
        for jobId, job in ipairs(jobNames) do
            p:unlockJob(jobId)
            command(owner, 'squad new Field ' .. job .. ' 12')
            command(owner, 'squad call 1')
            xi.test.world:skipTime(1)
            local mate = active(p)
            assert(mate and mate:getMainJob() == jobId and mate:getMainLvl() == 12, job .. ' job or level')
            assert(mate:getPacketName() == 'Field' and mate:getTrustID() == templates[jobId], job .. ' Trust identity')
            if jobId == xi.job.SCH then
                assert(HandheldCanCastSpell(mate, xi.magic.spell.CURE) and HandheldCanCastSpell(mate, xi.magic.spell.STONE),
                    'level 12 Scholar missing its real job spell access')
                assert(not HandheldCanCastSpell(mate, xi.magic.spell.STUN), 'Scholar inherited the Trust model Stun')
            end
            command(owner, 'squad dismiss')
            xi.test.world:skipTime(3)
            assert(not active(p), 'dismiss of ' .. job)
            command(owner, 'squad release 1 Field')
            assert(not HandheldSquadRoster(p:getID())[1], 'roster release of ' .. job)
        end
    end)
end)
