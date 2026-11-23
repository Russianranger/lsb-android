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
ai = { t = strict({ PARTY=1, SELF=2, TARGET=3, TRIGGER_SELF_ACTION_TARGET=12 }),
       c=strict({ HPP_LT=1, ALWAYS=2, NOT_STATUS=3, NOT_HAS_TOP_ENMITY=4, STATUS=9, TP_GTE=6, NO_SAMBA=23, NO_STORM=24 }),
       r=strict({ MA=1, JA=2 }), s=strict({ HIGHEST=1, SPECIFIC=2, BEST_AGAINST_TARGET=3, HIGHEST_WALTZ=9, STORM_DAY=12, HELIX_DAY=13 }) }
xi = { objType={ TRUST=3 }, module={ registerCommand=function(name, value) assert(name == 'squad'); command=value.onTrigger end },
       trust={ movementType={ MID_RANGE=6, MELEE=0 } }, mobMod={TRUST_DISTANCE=1}, job=strict({DNC=19,SCH=20}),
       ja=strict({PROVOKE=1,DRAIN_SAMBA=184,DRAIN_SAMBA_II=185,DRAIN_SAMBA_III=186,HASTE_SAMBA=189,CURING_WALTZ=190,QUICKSTEP=201,
                  LIGHT_ARTS=211,DARK_ARTS=212,ADDENDUM_WHITE=234,ADDENDUM_BLACK=235}),
       effect=strict({FLASH=1,HASTE=2,PROTECT=3,MARCH=4,REGEN=5,HELIX=6,LIGHT_ARTS=7,DARK_ARTS=8,ADDENDUM_WHITE=9,ADDENDUM_BLACK=10}),
       magic={spell=strict({CURE=1,FLASH=112,HASTE=57,STONE=159,STUN=252}),spellFamily=strict({CURE=1,PROTECT=2,MARCH=3,REGEN=4,STONE=5})} }
function GetSpell(id) return { getID = function() return id end } end
xi.trust.canCast = function(_, spell, variant)
    if spell:getID() == 923 then assert(variant == 1012) end
    if spell:getID() == 945 then
        assert(variant == 1013)
        if map.hasLilisetteII then return 1 end
    end
    if spell:getID() == 968 then assert(variant == nil) end
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
function HandheldCanCastSpell(mate, id)
    if mate:getMainJob() == xi.job.SCH then
        if id == xi.magic.spell.STONE then return mate:getMainLvl() >= 4 end
        if id == xi.magic.spell.CURE then return mate:getMainLvl() >= 5 end
        return false
    end
    return id~=611
end
function DismissHandheldMate() spawned=nil end

local player = { vars = {} }
function GetPlayerByID(id) if id == 9 then return player end end
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
    assert(type(template)=='number' and not spawned and job<=20 and level<=75)
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
        setAutoAttackEnabled=function() end, setMobMod=function(s,_,value) s.placement=value end,
        removeAllGambits=function(s) s.gambits={} end,
        addGambit=function(s, target, cond, action, retry)
            assert(cond[1] and action[1] and action[2] and action[3]); assert(action[3]~=611)
            action.target, action.cond, action.retry=target, cond, retry
            s.gambits[#s.gambits+1]=action
        end }
    if job == xi.job.SCH then
        spawned:addGambit(ai.t.TARGET, { ai.c.ALWAYS, 0 }, { ai.r.MA, ai.s.SPECIFIC, xi.magic.spell.STUN })
    end
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
for _, job in ipairs({'DNC','SCH'}) do
    run('job','2',job); has('unlocked'); assert(mates[2].main==1)
    run('sub','2',job); has('unlocked'); assert(mates[2].sub==0)
    run('new','Una',job); has('unlocked')
end
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
local ids={'WAR','MNK','WHM','BLM','RDM','THF','PLD','DRK','BST','BRD','RNG','SAM','NIN','DRG','SMN','BLU','COR','PUP','DNC','SCH'}
for n, job in ipairs(ids) do
    run('job','2',job); run('call','2')
    assert(spawned and spawned:getMainJob()==n)
    run('dismiss')
end
run('job','2','GEO'); has('unlocked'); assert(mates[2].main==20)
local function gambit(kind, selector, id)
    for _, g in ipairs(spawned.gambits) do
        if g[1] == kind and g[2] == selector and g[3] == id then return g end
    end
end
local function jobAbility(id) return gambit(ai.r.JA, ai.s.SPECIFIC, id) end
local function callAt(job, level, role)
    run('dismiss')
    mates[2].jobs[job]={level=level,xp=0,needed=500}
    run('job','2',ids[job]); run('role','2',role or 'auto'); run('call','2')
    assert(spawned and spawned:getMainLvl()==math.min(map.level,level))
end
callAt(19,4); assert(spawned.template==945 and #spawned.gambits==0)
run('dismiss');map.hasLilisetteII=true;run('call','2');has('summoning rules');assert(not spawned)
map.hasLilisetteII=false
callAt(19,5)
local samba=assert(jobAbility(xi.ja.DRAIN_SAMBA))
assert(samba.target==ai.t.SELF and samba.cond[2][1]==ai.c.TP_GTE and samba.cond[2][2]==100)
callAt(19,14);assert(not gambit(ai.r.JA,ai.s.HIGHEST_WALTZ,xi.ja.CURING_WALTZ))
callAt(19,15,'healer')
local waltz=assert(gambit(ai.r.JA,ai.s.HIGHEST_WALTZ,xi.ja.CURING_WALTZ))
assert(waltz.target==ai.t.PARTY and waltz.cond[2]==82 and spawned.placement==xi.trust.movementType.MELEE)
assert(not jobAbility(xi.ja.QUICKSTEP))
callAt(19,20);local step=assert(jobAbility(xi.ja.QUICKSTEP))
assert(step.target==ai.t.TRIGGER_SELF_ACTION_TARGET and step.cond[1]==ai.c.TP_GTE and step.cond[2]==100)
callAt(19,35);assert(jobAbility(xi.ja.DRAIN_SAMBA_II).cond[2][2]==250)
callAt(19,45,'support');assert(jobAbility(xi.ja.HASTE_SAMBA).cond[2][2]==350)
callAt(19,75);assert(jobAbility(xi.ja.DRAIN_SAMBA_III).cond[2][2]==400)
local priorGains=gains
map.exp(player,mob,200); assert(gains==priorGains)
map.level=4;callAt(19,75);assert(not jobAbility(xi.ja.DRAIN_SAMBA));map.level=75
callAt(20,1)
assert(spawned.template==968 and not gambit(ai.r.MA,ai.s.SPECIFIC,xi.magic.spell.STUN))
assert(not jobAbility(xi.ja.DARK_ARTS) and not gambit(ai.r.MA,ai.s.HIGHEST,xi.magic.spellFamily.STONE))
callAt(20,4);assert(gambit(ai.r.MA,ai.s.HIGHEST,xi.magic.spellFamily.STONE))
callAt(20,5,'healer');assert(spawned.placement==xi.trust.movementType.MID_RANGE)
callAt(20,10);assert(jobAbility(xi.ja.DARK_ARTS) and not jobAbility(xi.ja.ADDENDUM_BLACK))
callAt(20,10,'support');assert(jobAbility(xi.ja.LIGHT_ARTS) and jobAbility(xi.ja.ADDENDUM_WHITE))
assert(not jobAbility(xi.ja.DARK_ARTS))
callAt(20,29);assert(gambit(ai.r.MA,ai.s.HELIX_DAY,0) and not jobAbility(xi.ja.ADDENDUM_BLACK))
callAt(20,30);assert(jobAbility(xi.ja.ADDENDUM_BLACK) and not gambit(ai.r.MA,ai.s.STORM_DAY,0))
callAt(20,75);assert(gambit(ai.r.MA,ai.s.STORM_DAY,0) and not gambit(ai.r.MA,ai.s.SPECIFIC,xi.magic.spell.STUN))
map.exp(player,mob,200); assert(gains==priorGains)
run('dismiss');run('job','2','WAR');run('sub','2','DNC')
mates[2].jobs[1]={level=75,xp=0};run('call','2')
assert(spawned:getSubLvl()==37 and jobAbility(xi.ja.DRAIN_SAMBA_II) and not jobAbility(xi.ja.DRAIN_SAMBA_III))
assert(not gambit(ai.r.JA,ai.s.HIGHEST_WALTZ,xi.ja.CURING_WALTZ))
run('dismiss');run('sub','2','none')
for _, policy in ipairs({'auto','tank','damage','healer','support'}) do
    run('job','2','BLM');run('role','2',policy);run('call','2');run('dismiss')
end
run('call','1'); spawned=nil;map.exp=nil
gameIn(player,false,false); assert(not spawned and recall==0 and #timers==0)
run('call','1');assert(mates[1].jobs[3].xp==200)
spawned.getMaster=function() return nil end
spawned:death();spawned=nil;gameIn(player,false,true)
assert(#timers==0 and not spawned)
assert(recall==0 and not map.exp)
run('release','6','Wrong');assert(mates[6])
run('release','6','Fox');assert(not mates[6]);run('new','Kit','WAR');assert(mates[6].name=='Kit')
spawned=nil;run('call','1')
-- The client reports a release before its two-second animation finishes.
local old=spawned;old:handheld_mate_release()
assert(spawned==old and recall==0 and not map.exp)
spawned=nil;gameIn(player,false,true)
assert(#timers==0 and recall==0 and not spawned)
run('call','2')
old:handheld_mate_release();assert(recall==2 and map.exp)
assert(not spawned.despawn)
print('handheld squad command, job/role, XP, zoning, and cold-login fixtures passed')
