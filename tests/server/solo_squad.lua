-- Exercises the actual command/zone/XP module with the map's event and native
-- interfaces substituted. The independent SQL test uses real InnoDB.
local command, gameIn, spawned
local mates, recall, timers = {}, 0, {}
local lastMessage
local rosterReads = 0
local map = { party = {}, exp = nil, mog = false, fighting = false, level = 75, jobUnlocks = true }
package.preload['modules/module_utils'] = function() end
package.preload['scripts/globals/trust'] = function() end
package.preload['scripts/globals/gambits'] = function() end
local function strict(t)
    return setmetatable(t, { __index = function(_, k) error('unknown engine constant ' .. k) end })
end
ai = { t = strict({ PARTY=1, SELF=2, TARGET=3 }), c=strict({ HPP_LT=1, ALWAYS=2, NOT_STATUS=3, NOT_HAS_TOP_ENMITY=4 }),
       r=strict({ MA=1, JA=2 }), s=strict({ HIGHEST=1, SPECIFIC=2, BEST_AGAINST_TARGET=3 }) }
xi = { objType={ TRUST=3 }, module={ registerCommand=function(name, value) assert(name == 'squad'); command=value.onTrigger end },
       trust={ movementType={ MID_RANGE=6, MELEE=0 } }, mobMod={TRUST_DISTANCE=1}, ja={PROVOKE=1},
       effect=strict({FLASH=1,HASTE=2,PROTECT=3,MARCH=4}), magic={spell={FLASH=1,HASTE=2},spellFamily={CURE=1,PROTECT=2,MARCH=3}} }
function GetSpell(id) return { getID = function() return id end } end
xi.trust.canCast = function(_, spell, variant)
    if spell:getID() == 923 then assert(variant == 1012) end
    return map.trustBlocked and 1 or 0
end
Module = { new=function() return { addOverride=function(_, name, fn) assert(name == 'xi.player.onGameIn'); gameIn=fn end } end }
super=function() end
function HandheldSquadRoster(owner) assert(owner == 9); rosterReads = rosterReads+1; return mates end
function HandheldSquadCreate(owner, slot, name, job, level)
    assert(owner == 9 and slot >= 1 and slot <= 6 and not mates[slot])
    mates[slot] = { name=name, main=job, sub=0, role='auto', jobs={[job]={level=level,xp=0,needed=500}}, gear={} }
    return true
end
function HandheldSquadRelease(_, slot, name) assert(mates[slot].name == name);mates[slot]=nil;return true end
function HandheldSquadSetProfile(_, slot, main, sub, role)
    mates[slot].main, mates[slot].sub, mates[slot].role=main, sub, role
    return true
end
function HandheldSquadSetGear(_, slot, _, outfit) mates[slot].gear=outfit;return true end
local gains=0
function HandheldSquadGainXp(_, slot, job, xp)
    gains=gains+1
    local p=mates[slot].jobs[job]
    p.xp=p.xp+xp
    return p.level
end
function HandheldCanEquip(id, _, level, slot) return slot==4 and id==100 and level>=10 end
function HandheldMateWasReleased(entity) return entity.released == true end
function HandheldCanCastSpell(_, id) return id~=611 end
function DismissHandheldMate() spawned=nil end

local player = { vars = {} }
function player:setLocalVar(k,v) self.vars[k]=v end
function player:getLocalVar(k) return self.vars[k] or 0 end
function player:getID() return 9 end
function player:printToPlayer(message) lastMessage=message end
function player:getMainLvl() return map.level end
function player:hasJob(job) return job==0 or map.jobUnlocks end
function player:isAlive() return true end
function player:inMogHouse() return map.mog end
function player:hasEnmity() return map.fighting end
function player:getPartyLeader() return nil end
function player:checkSoloPartyAlliance() return 0 end
function player:getPartyWithTrusts()
    local party={player}
    for _, v in ipairs(map.party) do party[#party+1]=v end
    if spawned then party[#party+1]=spawned end
    return party
end
function player:getObjType() return 1 end
function player:getCharVar() return recall end
function player:setCharVar(_, v) recall=v end
function player:getEquipID(slot) return slot==4 and 100 or 200 end
function player:removeListener(name) assert(name=='HANDHELD_SQUAD_XP'); map.exp=nil end
function player:addListener(event, _, fn) assert(event=='EXPERIENCE_POINTS');map.exp=fn end
function player:timer(ms, callback) timers[#timers+1]=callback; assert(ms>0) end

local nextId=800
function SpawnHandheldMate(_, template, name, job, level, sub, subLevel, outfit)
    assert(type(template)=='number' and not spawned and job<=18 and level<=75)
    local vars = { HANDHELD_MATE=9 }
    nextId=nextId+1
    local id=nextId
    spawned = { template=template, outfit=outfit, gambits={}, dead=false, distance=5,
        getLocalVar=function(_,k) return vars[k] or 0 end, setLocalVar=function(_,k,v) vars[k]=v end,
        getID=function() return id end, getMainLvl=function() return level end, getSubLvl=function() return subLevel end,
        getMainJob=function() return job end, getZoneID=function() return 101 end,
        getObjType=function() return xi.objType.TRUST end, getPacketName=function() return name end,
        isAlive=function(s) return not s.dead end, getMaster=function() return player end,
        checkDistance=function(s) return s.distance end, addListener=function(s,event,_,fn) s[event:lower()]=fn end,
        setAutoAttackEnabled=function() end, setMobMod=function() end,
        removeAllGambits=function(s) s.gambits={} end,
        addGambit=function(s, target, cond, action)
            assert(cond[1] and action[1] and action[2] and action[3]); assert(action[3]~=611)
            s.gambits[#s.gambits+1]=action
        end }
    return spawned
end
dofile(assert(arg[1], 'pass module path'))
local function run(...) command(player, ...) end
local function has(text) assert(lastMessage:find(text,1,true), lastMessage) end
gameIn(player,false,false)
assert(not map.exp and #timers==0 and rosterReads==0)
run('new','Ana','WHM','13'); has('Starting level')
run('new','Ana','WHM','10'); run('new','ana','RDM'); has('already')
run('new','Bod','WAR'); run('new','Cat','MNK'); run('new','Dad','THF'); run('new','Eel','BLM'); run('new','Fox','RDM')
run('new','Gil','WAR'); has('All six')
map.jobUnlocks=false
run('job','2','PUP'); has('unlocked'); assert(mates[2].main==1)
map.jobUnlocks=true
run('equip','1'); assert(mates[1].gear[4]==100 and mates[1].gear[0]==nil)
run('sub','1','RDM'); run('role','1','healer')
map.trustBlocked=true
run('call','1');has('summoning rules');assert(not spawned and not map.exp)
map.trustBlocked=false
run('call','1'); assert(spawned and spawned.outfit[4]==100 and #spawned.gambits>0 and recall==1 and map.exp)
run('call','2'); has('Only one')
run('job','1','PLD'); has('Dismiss')
local mob={getZoneID=function() return 101 end}
map.exp(player,nil,200); spawned.distance=102; map.exp(player,mob,200); spawned.distance=3
map.exp(player,mob,200); assert(gains==1 and mates[1].jobs[3].xp==200)
spawned=nil; map.exp=nil; map.mog=true
gameIn(player,false,true); assert(#timers==0 and recall==1)
map.mog=false; map.trustBlocked=true
gameIn(player,false,true); table.remove(timers)(player); assert(not spawned and not map.exp)
map.trustBlocked=false; gameIn(player,false,true); assert(#timers==1); table.remove(timers)(player)
assert(spawned and map.exp)
run('dismiss'); assert(not spawned and recall==0 and not map.exp)
local ids={'WAR','MNK','WHM','BLM','RDM','THF','PLD','DRK','BST','BRD','RNG','SAM','NIN','DRG','SMN','BLU','COR','PUP'}
for n, job in ipairs(ids) do
    run('job','2',job); run('call','2')
    assert(spawned and spawned:getMainJob()==n)
    run('dismiss')
end
run('job','2','DNC'); assert(mates[2].main==18)
for _, policy in ipairs({'auto','tank','damage','healer','support'}) do
    run('job','2','BLM');run('role','2',policy);run('call','2');run('dismiss')
end
run('call','1'); spawned=nil;map.exp=nil
gameIn(player,false,false); assert(not spawned and recall==0 and #timers==0)
run('call','1');assert(mates[1].jobs[3].xp==200);spawned:death()
assert(recall==0 and not map.exp)
run('release','6','Wrong');assert(mates[6])
run('release','6','Fox');assert(not mates[6]);run('new','Kit','WAR');assert(mates[6].name=='Kit')
spawned=nil;run('call','1')
local old=spawned;spawned.released=true;spawned:despawn();spawned=nil
assert(recall==0 and not map.exp);run('call','2')
old:despawn();assert(recall==2 and map.exp)
spawned:despawn();assert(recall==2 and map.exp)
print('handheld squad command, job/role, XP, zoning, and cold-login fixtures passed')
