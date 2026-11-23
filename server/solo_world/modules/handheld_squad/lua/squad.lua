-----------------------------------
-- Persistent personal companions for the local server. All gameplay runs in xi_map.
-----------------------------------
require('modules/module_utils')
require('scripts/globals/gambits')
require('scripts/globals/trust')

local module = Module:new('handheld_personal_squad')
local jobs = { 'WAR', 'MNK', 'WHM', 'BLM', 'RDM', 'THF', 'PLD', 'DRK', 'BST', 'BRD', 'RNG', 'SAM', 'NIN', 'DRG', 'SMN', 'BLU', 'COR', 'PUP' }
-- Spell IDs identify stock Trust combat scripts and client appearances. SMN and
-- BLU have no matching stock Trust. Nashmeira provides a non-passive melee base.
local templates = { 897, 933, 898, 896, 952, 901, 902, 906, 943, 911, 940, 900, 918, 915, 923, 923, 967, 923 }
-- The stock templates disallow these alternate named Trusts in the same party.
local alternate = { [933] = 1006, [896] = 1019, [906] = 1010, [900] = 1005, [923] = 1012 }
local slotNames = { [0] = 'main', 'sub', 'ranged', 'ammo', 'head', 'body', 'hands', 'legs', 'feet', 'neck', 'waist', 'ear1', 'ear2', 'ring1', 'ring2', 'back' }
local roles = { auto = true, tank = true, damage = true, healer = true, support = true }
local jobIds = {}
for id, name in ipairs(jobs) do
    jobIds[name] = id
end

local recallVar = '[HHSquad]Recall'
local listener = 'HANDHELD_SQUAD_XP'

local function say(player, message)
    player:printToPlayer('[Squad] ' .. message)
end

local function progress(mate, job)
    return mate.jobs[job] or { level = 1, xp = 0, needed = nil }
end

local function activeMate(player)
    for _, member in pairs(player:getPartyWithTrusts()) do
        if member:getObjType() == xi.objType.TRUST and member:getLocalVar('HANDHELD_MATE') == player:getID() then
            return member
        end
    end
end

local function unlocked(player, job)
    return job and (job <= 6 or player:hasJob(job))
end

-- Only a called mate installs this listener. The engine sends the actual monster
-- and the owner's final EXP award. No scan of other zones or periodic DB writes.
local function gainExperience(player, mob, gain)
    if not mob or not gain or gain <= 0 then
        return
    end
    local mate = activeMate(player)
    if not mate or not mate:isAlive() or mate:getLocalVar('HH_LEVEL') >= 75 or
        mate:getZoneID() ~= mob:getZoneID() or mate:checkDistance(mob) >= 100 then
        return
    end
    local level = HandheldSquadGainXp(player:getID(), mate:getLocalVar('HH_SLOT'), mate:getMainJob(), gain)
    if level > mate:getLocalVar('HH_LEVEL') then
        mate:setLocalVar('HH_LEVEL', level)
        say(player, string.format('%s reached %s %d. Their new stats apply on the next call.', mate:getPacketName(), jobs[mate:getMainJob()], level))
    end
end

local function healing(mate, threshold)
    mate:addGambit(ai.t.PARTY, { ai.c.HPP_LT, threshold }, { ai.r.MA, ai.s.HIGHEST, xi.magic.spellFamily.CURE })
end

local function blueMagic(mate, role)
    local function spell(target, condition, value, id, cooldown)
        if HandheldCanCastSpell(mate, id) then
            mate:addGambit(target, { condition, value }, { ai.r.MA, ai.s.SPECIFIC, id }, cooldown)
        end
    end
    -- The spell container applies the pinned database's job level restrictions.
    if role ~= 'damage' then
        spell(ai.t.PARTY, ai.c.HPP_LT, 65, 593, 3) -- Magic Fruit
        spell(ai.t.PARTY, ai.c.HPP_LT, 65, 578, 3) -- Wild Carrot
        spell(ai.t.SELF, ai.c.HPP_LT, 60, 549, 3)  -- Pollen
    end
    for _, id in ipairs({ 611, 560, 554, 569, 623, 529, 597, 577 }) do
        spell(ai.t.TARGET, ai.c.ALWAYS, 0, id, 12)
    end
end

local function applyRole(mate, job, sub, role)
    if role ~= 'auto' then
        mate:removeAllGambits()
        mate:setAutoAttackEnabled(true)
        mate:setMobMod(xi.mobMod.TRUST_DISTANCE, xi.trust.movementType.MELEE)
        if role == 'tank' then
            if (job == 1 and mate:getMainLvl() >= 5) or (sub == 1 and mate:getSubLvl() >= 5) then
                mate:addGambit(ai.t.SELF, { ai.c.NOT_HAS_TOP_ENMITY, 0 }, { ai.r.JA, ai.s.SPECIFIC, xi.ja.PROVOKE })
            end
            if HandheldCanCastSpell(mate, xi.magic.spell.FLASH) then
                mate:addGambit(ai.t.TARGET, { ai.c.NOT_STATUS, xi.effect.FLASH }, { ai.r.MA, ai.s.SPECIFIC, xi.magic.spell.FLASH }, 30)
            end
            healing(mate, 45)
        elseif role == 'healer' or role == 'support' then
            mate:setMobMod(xi.mobMod.TRUST_DISTANCE, xi.trust.movementType.MID_RANGE)
            healing(mate, role == 'healer' and 82 or 58)
            mate:addGambit(ai.t.PARTY, { ai.c.NOT_STATUS, xi.effect.PROTECT }, { ai.r.MA, ai.s.HIGHEST, xi.magic.spellFamily.PROTECT })
            if HandheldCanCastSpell(mate, xi.magic.spell.HASTE) then
                mate:addGambit(ai.t.PARTY, { ai.c.NOT_STATUS, xi.effect.HASTE }, { ai.r.MA, ai.s.SPECIFIC, xi.magic.spell.HASTE })
            end
            if job == 10 then
                mate:addGambit(ai.t.SELF, { ai.c.NOT_STATUS, xi.effect.MARCH }, { ai.r.MA, ai.s.HIGHEST, xi.magic.spellFamily.MARCH })
            end
        elseif role == 'damage' and job == 4 then
            mate:addGambit(ai.t.TARGET, { ai.c.ALWAYS, 0 }, { ai.r.MA, ai.s.BEST_AGAINST_TARGET, 0 }, 8)
        end
    elseif job == 15 and (sub == 3 or sub == 5) then
        healing(mate, 75)
    end
    if job == 16 then
        blueMagic(mate, role)
    end
end

local function callMate(player, slot, mate)
    if not player:isAlive() or player:inMogHouse() or player:hasEnmity() then
        return false, 'Call your mate outside the Mog House while alive and free of enmity.'
    end
    local leader = player:getPartyLeader()
    if player:checkSoloPartyAlliance() == 2 or (leader and leader:getID() ~= player:getID()) or #player:getPartyWithTrusts() >= 6 then
        return false, 'Call as party leader or solo, with room in your party.'
    end
    if activeMate(player) then
        return false, 'Only one personal mate can be out. Use !squad dismiss first.'
    end
    -- This check is shared with normal FFXI Trust spells. It includes the area's
    -- Trust flag, battlefield and key-item rules, party size and recent joins.
    -- Recruiting an independent companion doesn't grant or consume a Trust spell.
    local template = templates[mate.main]
    local spell = GetSpell(template)
    if not spell or xi.trust.canCast(player, spell, alternate[template]) ~= 0 then
        return false, 'Normal Trust summoning rules prevent a call here or at this time.'
    end
    local ownLevel = progress(mate, mate.main).level
    local level = math.min(75, ownLevel, player:getMainLvl())
    local sub, subLevel = 0, 0
    if mate.sub > 0 and (player:hasJob(0) or player:getMainLvl() >= 18 or ownLevel >= 18) then
        subLevel = math.min(progress(mate, mate.sub).level, math.floor(level / 2))
        if subLevel > 0 then sub = mate.sub end
    end
    -- Revalidate saved equipment against the effective level after level sync.
    local outfit = {}
    for index, id in pairs(mate.gear) do
        if HandheldCanEquip(id, mate.main, level, index) then outfit[index] = id end
    end
    local trust = SpawnHandheldMate(player, template, mate.name, mate.main, level, sub, subLevel, outfit)
    if not trust then return false, 'The map could not call this mate here.' end
    trust:setLocalVar('HH_SLOT', slot)
    trust:setLocalVar('HH_LEVEL', ownLevel)
    applyRole(trust, mate.main, sub, mate.role)
    player:removeListener(listener)
    player:addListener('EXPERIENCE_POINTS', listener, gainExperience)
    player:setCharVar(recallVar, slot)
    player:setLocalVar('HH_ENTITY', trust:getID())
    local function forget(entity)
        local owner = entity:getMaster()
        if owner and owner:getLocalVar('HH_ENTITY') == entity:getID() then
            owner:setLocalVar('HH_ENTITY', 0)
            owner:setCharVar(recallVar, 0)
            owner:removeListener(listener)
        end
    end
    trust:addListener('DEATH', 'HANDHELD_SQUAD_FALL', forget)
    trust:addListener('DESPAWN', 'HANDHELD_SQUAD_RELEASE', function(entity)
        -- The client Release action and zoning both despawn a Trust. Only the
        -- first one sets released(), and a late callback must not clear a new call.
        if HandheldMateWasReleased(entity) then forget(entity) end
    end)
    return true, string.format('%s joined you as %s %d (%s).', mate.name, jobs[mate.main], level, mate.role)
end

local function describe(player, slot, mate)
    local p = progress(mate, mate.main)
    say(player, string.format('%d. %s  %s %d%s  XP %d%s  role %s', slot, mate.name, jobs[mate.main], p.level,
        mate.sub > 0 and ('/' .. jobs[mate.sub]) or '', p.xp, p.needed and p.needed > 0 and ('/' .. p.needed) or '', mate.role))
end

local function help(player)
    say(player, '!squad new NAME JOB [level] | list | call SLOT | dismiss. Save six mates, call one.')
    say(player, '!squad job SLOT JOB | sub SLOT JOB/none | role SLOT auto/tank/damage/healer/support.')
    say(player, '!squad equip SLOT copies eligible worn gear. !squad gear SLOT shows it. !squad levels SLOT lists trained jobs.')
    say(player, '!squad release SLOT NAME permanently removes a saved mate and frees their slot.')
end

local function command(player, action, arg, value, extra)
    action = string.lower(action or '')
    if action == 'help' then return help(player) end
    if action == 'dismiss' then
        player:setCharVar(recallVar, 0)
        player:setLocalVar('HH_ENTITY', 0)
        player:removeListener(listener)
        DismissHandheldMate(player)
        return say(player, 'Your personal mate has been dismissed.')
    end
    local roster = HandheldSquadRoster(player:getID())
    if action == '' or action == 'list' then
        if next(roster) == nil then return help(player) end
        for slot = 1, 6 do
            if roster[slot] then describe(player, slot, roster[slot]) end
        end
        return
    end
    if action == 'new' then
        local name, job = arg or '', jobIds[string.upper(value or '')]
        local level = (not extra or extra == '') and 1 or tonumber(extra)
        if not name:match('^[A-Za-z]+$') or #name < 3 or #name > 15 or not unlocked(player, job) then
            return say(player, 'Use !squad new NAME JOB [level]. Use 3-15 letters and an unlocked job through PUP.')
        end
        if not level or level ~= math.floor(level) or level < 1 or level > math.min(12, player:getMainLvl()) then
            return say(player, 'Starting level must be between 1 and your level, up to 12.')
        end
        local free
        for i = 1, 6 do
            if roster[i] and roster[i].name:lower() == name:lower() then return say(player, 'That name is already on your roster.') end
            if not free and not roster[i] then free = i end
        end
        if not free then return say(player, 'All six saved slots are occupied.') end
        if not HandheldSquadCreate(player:getID(), free, name, job, level) then return say(player, 'The mate could not be saved.') end
        return say(player, string.format('Saved %s. Use !squad call %d.', name, free))
    end
    local slot = (not arg or arg == '') and 1 or tonumber(arg)
    local mate = slot and roster[slot]
    if not mate then return say(player, 'Choose a saved slot from !squad list.') end
    if action == 'call' then
        local _, message = callMate(player, slot, mate)
        return say(player, message)
    elseif action == 'levels' then
        local parts = {}
        for id, name in ipairs(jobs) do
            if mate.jobs[id] then parts[#parts + 1] = name .. ' ' .. progress(mate, id).level end
        end
        return say(player, table.concat(parts, ', ') .. '. Other accessible jobs start at 1.')
    elseif action == 'gear' then
        local parts = {}
        for index = 0, 15 do
            if mate.gear[index] then parts[#parts + 1] = slotNames[index] .. '=' .. mate.gear[index] end
        end
        if #parts == 0 then return say(player, 'Template gear. Use !squad equip SLOT to copy eligible worn equipment.') end
        for i = 1, #parts, 5 do say(player, table.concat(parts, ', ', i, math.min(i + 4, #parts))) end
        return
    end
    local active = activeMate(player)
    if active and active:getLocalVar('HH_SLOT') == slot then return say(player, 'Dismiss this mate before changing their setup.') end
    if action == 'release' then
        if not value or value:lower() ~= mate.name:lower() then
            return say(player, 'To permanently remove this mate and their progress, enter !squad release ' .. slot .. ' ' .. mate.name .. '.')
        end
        if not HandheldSquadRelease(player:getID(), slot, mate.name) then
            return say(player, 'The mate could not be removed.')
        end
        if player:getCharVar(recallVar) == slot then player:setCharVar(recallVar, 0) end
        return say(player, mate.name .. ' has left your saved squad.')
    elseif action == 'equip' then
        local gear, count = {}, 0
        for index = 0, 15 do
            local id = player:getEquipID(index)
            if id > 0 and HandheldCanEquip(id, mate.main, progress(mate, mate.main).level, index) then
                gear[index] = id
                count = count + 1
            end
        end
        if HandheldSquadSetGear(player:getID(), slot, mate.main, gear) then
            return say(player, string.format('Saved %d eligible base items from your current outfit for %s.', count, jobs[mate.main]))
        end
        return say(player, 'The outfit could not be saved.')
    elseif action == 'role' then
        local role = string.lower(value or '')
        if not roles[role] then return say(player, 'Roles are auto, tank, damage, healer and support. Auto uses the job template.') end
        mate.role = role
    elseif action == 'job' or action == 'sub' then
        local job = jobIds[string.upper(value or '')]
        if action == 'sub' and string.lower(value or '') == 'none' then
            mate.sub = 0
        elseif not unlocked(player, job) then
            return say(player, 'Choose an unlocked job between WAR and PUP.')
        elseif action == 'job' then
            mate.main = job
            if mate.sub == job then mate.sub = 0 end
        elseif mate.main == job then
            return say(player, 'Main and support jobs must differ.')
        else
            mate.sub = job
        end
    else
        return help(player)
    end
    if not HandheldSquadSetProfile(player:getID(), slot, mate.main, mate.sub, mate.role) then
        return say(player, 'The change could not be saved.')
    end
    describe(player, slot, mate)
end

xi.module.registerCommand('squad', { cmdprops = { permission = 0, parameters = 'ssss' }, onTrigger = command })

module:addOverride('xi.player.onGameIn', function(player, firstLogin, zoning)
    super(player, firstLogin, zoning)
    local slot = player:getCharVar(recallVar)
    if slot == 0 then return end
    if not zoning then
        player:setCharVar(recallVar, 0)
    elseif not player:inMogHouse() then
        player:timer(7000, function(owner)
            if owner:getCharVar(recallVar) ~= slot or activeMate(owner) then return end
            local mate = HandheldSquadRoster(owner:getID())[slot]
            if mate then
                local _, message = callMate(owner, slot, mate)
                say(owner, message)
            end
        end)
    end
end)
