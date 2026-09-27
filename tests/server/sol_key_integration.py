"""Exercise the patched real chocobo Lua decoder through the bundled sol API."""

from pathlib import Path
import subprocess


PREAMBLE = r'''
#include <sol/sol.hpp>
#include <cassert>
#include <cstdint>
#include <iostream>

namespace GP_SERV_COMMAND_CHOCOBO_RACING
{
// Only this method's data shape is needed; the implementation below comes
// directly from the selected server source, and all Lua calls use real sol.
struct ChocoboParam
{
    std::uint8_t Item{}, Orders{}, Size{}, Color{}, Gender{}, Weather{}, Temperament{}, Ability1{}, Ability2{};
    struct Stat { std::uint8_t Rank{}; } STR, END, DSC, RCP;
    static auto fromLua(const sol::table& data) -> ChocoboParam;
};
'''

CHECKS = r'''
}

int main()
{
    using GP_SERV_COMMAND_CHOCOBO_RACING::ChocoboParam;
    sol::state lua;
    sol::table data = lua.create_table();
    auto empty = ChocoboParam::fromLua(data);
    assert(empty.Item == 0 && empty.Ability2 == 0);
    assert(empty.STR.Rank == 0 && empty.END.Rank == 0 && empty.DSC.Rank == 0 && empty.RCP.Rank == 0);

    data["item"] = 1;
    data["orders"] = 2;
    data["size"] = 3;
    data["color"] = 4;
    data["gender"] = 5;
    data["weather"] = 6;
    data["temperament"] = 7;
    data["ability1"] = 8;
    data["ability2"] = 9;
    sol::table stats = lua.create_table();
    stats["str"] = 11;
    stats["end"] = 22;
    stats["dsc"] = 33;
    stats["rcp"] = 44;
    data["stats"] = stats;
    auto full = ChocoboParam::fromLua(data);
    assert(full.Item == 1 && full.Orders == 2 && full.Size == 3 && full.Color == 4);
    assert(full.Gender == 5 && full.Weather == 6 && full.Temperament == 7);
    assert(full.Ability1 == 8 && full.Ability2 == 9);
    assert(full.STR.Rank == 11 && full.END.Rank == 22 && full.DSC.Rank == 33 && full.RCP.Rank == 44);

    sol::table partial = lua.create_table();
    partial["str"] = 255;
    data["stats"] = partial;
    auto sparse = ChocoboParam::fromLua(data);
    assert(sparse.STR.Rank == 255 && sparse.END.Rank == 0 && sparse.DSC.Rank == 0 && sparse.RCP.Rank == 0);
    data["stats"] = sol::lua_nil;
    auto absent = ChocoboParam::fromLua(data);
    assert(absent.STR.Rank == 0 && absent.END.Rank == 0 && absent.DSC.Rank == 0 && absent.RCP.Rank == 0);
    std::cout << "PASS: real sol/LuaJIT lookups preserve distinct short keys, missing defaults and existing fields\n";
}
'''


def from_lua(source):
    start = source.index('auto ChocoboParam::fromLua(')
    opening = source.index('{', start)
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == '{':
            depth += 1
        elif source[index] == '}':
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError('Cannot extract ChocoboParam::fromLua')


def check(root, output, compiler='g++-15'):
    root, output = Path(root), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    source = (root / 'src/map/packets/s2c/0x069_chocobo_racing.cpp').read_text()
    method = from_lua(source)
    for key in ('str', 'end', 'dsc', 'rcp'):
        assert 'static_cast<const char*>("' + key + '")' in method, 'Missing sol pointer-key patch: ' + key
    fixture = output / 'sol-chocobo-keys.cpp'
    fixture.write_text(PREAMBLE + '\n' + method + '\n' + CHECKS)
    executable = output / 'sol-chocobo-keys'
    commands = ([compiler, '-std=c++23', '-O3', '-Wall', '-Wextra', '-Werror', '-Warray-bounds',
                 '-DSOL_ALL_SAFETIES_ON=1', '-DSOL_NO_CHECK_NUMBER_PRECISION=1',
                 '-DSOL_DEFAULT_PASS_ON_ERROR=1', '-DSOL_PRINT_ERRORS=0',
                 '-isystem', str(root / 'ext/sol/include'),
                 '-isystem', str(root / 'ext/luajit/include'),
                 str(fixture), '-lluajit-5.1', '-ldl', '-o', str(executable)], [str(executable)])
    log = output / 'sol-chocobo-keys.log'
    with log.open('w') as stream:
        for command in commands:
            stream.write('$ ' + ' '.join(command) + '\n')
            stream.flush()
            subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=120)
    return dict(state='passed', compiler=compiler, cases=4,
                coverage=['empty table defaults', 'distinct stat and scalar fields',
                          'missing stat defaults and uint8 maximum', 'absent stat table'])


if __name__ == '__main__':
    import sys
    check(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else 'g++-15')
