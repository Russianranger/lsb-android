-- Loaded only by the native source-build test, after the engine registers its C++ modules.
local probe = Module:new('handheld_solo_startup_test')

probe:addOverride('xi.server.onServerStart', function()
    super()
    assert(type(xi.commands.squad.onTrigger) == 'function', '!squad command')
    for _, binding in ipairs({ 'SpawnHandheldMate', 'DismissHandheldMate', 'HandheldCanCastSpell', 'HandheldMateWasReleased' }) do
        assert(type(_G[binding]) == 'function', binding)
    end
    assert(not HandheldCanEquip(0, xi.job.WAR, 1, 0), 'equipment validator')

    local owner = 1001
    assert(HandheldSquadRoster(owner)[1] == nil, 'empty roster')
    assert(HandheldSquadCreate(owner, 1, 'Testmate', xi.job.WAR, 12), 'create mate')
    assert(HandheldSquadSetProfile(owner, 1, xi.job.WAR, xi.job.WHM, 'tank'), 'set profile')
    local mate = HandheldSquadRoster(owner)[1]
    assert(mate.name == 'Testmate' and mate.main == xi.job.WAR and mate.sub == xi.job.WHM and mate.role == 'tank', 'read profile')
    assert(mate.jobs[xi.job.WAR].level == 12, 'saved starting level')
    assert(HandheldSquadGainXp(owner, 1, xi.job.WAR, 9000) > 12, 'experience gain')
    assert(HandheldSquadSetGear(owner, 1, xi.job.WAR, { [0] = 16535 }), 'set gear')
    assert(HandheldSquadRoster(owner)[1].gear[0] == 16535, 'read gear')
    assert(not HandheldSquadRelease(owner, 1, 'Wrong'), 'release name confirmation')
    assert(HandheldSquadRelease(owner, 1, 'Testmate'), 'release mate')
    assert(HandheldSquadRoster(owner)[1] == nil, 'released slot')

    for _, job in ipairs({ xi.job.DNC, xi.job.SCH }) do
        assert(HandheldSquadCreate(owner, 1, 'Jobtest', job, 12), 'era job creation')
        assert(HandheldSquadRoster(owner)[1].jobs[job].level == 12, 'era job roster')
        assert(HandheldSquadGainXp(owner, 1, job, 9000) > 12, 'era job progression')
        assert(HandheldSquadRelease(owner, 1, 'Jobtest'), 'era job release')
    end
    print('Handheld native squad bindings passed')
end)
