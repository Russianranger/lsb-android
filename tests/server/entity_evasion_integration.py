"""Check the staged getEVA implementation with real polymorphic type checks."""

from pathlib import Path
import subprocess


PREAMBLE = r'''
#include <cassert>
#include <cstdint>
#include <iostream>
#include <string>
using uint16 = std::uint16_t;
static int errors = 0;
static int names_read = 0;
static int evasion_calls = 0;
template <typename... Args>
void ShowError(const char*, const Args&...) { ++errors; }
class CBaseEntity
{
public:
    virtual ~CBaseEntity() = default;
    virtual const std::string& getName() const { ++names_read; return name; }
private:
    std::string name = "fixture entity";
};
class CBattleEntity : public CBaseEntity
{
public:
    uint16 EVA() { ++evasion_calls; return 1234; }
};
class CPlayerEntity : public CBattleEntity {};
class CNpcEntity : public CBaseEntity {};
class CLuaBaseEntity
{
public:
    explicit CLuaBaseEntity(CBaseEntity* entity) : m_PBaseEntity(entity) {}
    uint16 getEVA();
private:
    CBaseEntity* m_PBaseEntity;
};
'''

CHECKS = r'''
int main()
{
    CBattleEntity battle;
    CPlayerEntity player;
    CNpcEntity npc;
    assert(CLuaBaseEntity(&battle).getEVA() == 1234);
    assert(CLuaBaseEntity(&player).getEVA() == 1234);
    assert(evasion_calls == 2 && errors == 0 && names_read == 0);
    assert(CLuaBaseEntity(&npc).getEVA() == 0);
    assert(evasion_calls == 2 && errors == 1 && names_read == 1);
    assert(CLuaBaseEntity(nullptr).getEVA() == 0);
    assert(evasion_calls == 2 && errors == 2 && names_read == 1);
    std::cout << "PASS: real getEVA type guard preserves battle results and safely rejects NPC/null\n";
}
'''


def check(root, output, compiler='g++-15'):
    root, output = Path(root), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    source = (root / 'src/map/lua/lua_base_entity.cpp').read_text()
    start = source.index('uint16 CLuaBaseEntity::getEVA()')
    end = source.index('\n}\n', start) + 2
    method = source[start:end]
    assert 'dynamic_cast<CBattleEntity*>' in method, 'getEVA must check the actual entity type'
    fixture = output / 'entity-evasion.cpp'
    fixture.write_text(PREAMBLE + '\n' + method + '\n' + CHECKS)
    executable = output / 'entity-evasion'
    commands = ([compiler, '-std=c++23', '-O3', '-Wall', '-Wextra', '-Werror',
                 '-Wnonnull', '-fsanitize=undefined', '-fno-sanitize-recover=all',
                 str(fixture), '-o', str(executable)], [str(executable)])
    with (output / 'entity-evasion.log').open('w') as stream:
        for command in commands:
            stream.write('$ ' + ' '.join(command) + '\n')
            stream.flush()
            subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=120)
    return dict(state='passed', compiler=compiler, cases=4,
                coverage=['battle value', 'derived battle type', 'non-battle rejection', 'null rejection'],
                sanitizer='undefined')
