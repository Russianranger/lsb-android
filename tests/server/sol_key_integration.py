"""Exercise normalized literal keys and unchanged lookup paths in real sol."""

from pathlib import Path
import subprocess


PREAMBLE = r'''
#include <sol/sol.hpp>
#include <cassert>
#include <cstdint>
#include <iostream>
#include <string>
#include <string_view>

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

    // Different literal extents exercise the other packet sites that exposed
    // GCC's array-specialization merging, including table-valued lookups.
    sol::table odds = lua.create_table();
    odds[1] = 120;
    data["odds"] = odds;
    data["grade"] = 2;
    data["raceNumber"] = 987654;
    data["itemNo"] = 65000;
    data["count"] = 7;
    data["tradeCode"] = -1;
    auto odds_value = data.get<sol::optional<sol::table>>("odds");
    assert(odds_value && odds_value->get_or<std::uint32_t>(1, 0) == 120);
    assert(data.get_or<std::uint32_t>("grade", 0) == 2);
    assert(data.get_or<std::uint32_t>("raceNumber", 0) == 987654);
    assert(data.get_or("itemNo", std::uint16_t{0}) == 65000);
    assert(data.get_or("count", std::uint8_t{0}) == 7);
    assert(data.get_or("tradeCode", std::int32_t{0}) == -1);
    assert(data.raw_get<std::uint16_t>("itemNo") == 65000);
    assert(data.get_or<std::uint8_t>("str", 0) == 0);

    // Both get_field overloads and global lookup preserve Lua stack behavior.
    auto* state = lua.lua_state();
    const int original_top = lua_gettop(state);
    data.push();
    sol::stack::get_field(state, "count");
    assert(lua_tointeger(state, -1) == 7);
    lua_pop(state, 1);
    char mutable_key[] = "grade";
    sol::stack::get_field(state, mutable_key, -1);
    assert(lua_tointeger(state, -1) == 2);
    lua_pop(state, 2);
    assert(lua_gettop(state) == original_top);
    lua["globalName"] = 42;
    assert(lua.get<int>("globalName") == 42);
    sol::stack::get_field<true>(state, "globalName");
    assert(lua_tointeger(state, -1) == 42);
    lua_pop(state, 1);

    // Only non-raw narrow arrays change representation. String-view and raw
    // binary keys retain their explicit length; C-string keys still stop at NUL.
    const std::string binary_key("a\0b", 3);
    data["a"] = 88;
    data.raw_set(binary_key, 101);
    assert(data.raw_get<int>(binary_key) == 101);
    assert(data.get<int>(std::string_view(binary_key)) == 101);
    assert(data.get<int>("a\0b") == 88);
    assert(data.raw_get<int>("a\0b") == 88);
    data[""] = 19;
    assert(data.get<int>("") == 19);
    const char* pointer_key = "count";
    assert(data.get<int>(pointer_key) == 7);
    assert(data.get<int>(std::string("count")) == 7);

    sol::table fallback = lua.create_table();
    fallback["fallback"] = 79;
    sol::table metatable = lua.create_table();
    metatable[sol::meta_function::index] = fallback;
    data[sol::metatable_key] = metatable;
    assert(data.get<int>("fallback") == 79);
    assert(!data.raw_get<sol::optional<int>>("fallback"));
    assert(lua_gettop(state) == original_top);
    std::cout << "PASS: real sol/LuaJIT literal, pointer, numeric, raw/binary, global and metamethod lookups\n";
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
    header = (root / 'ext/sol/include/sol/sol.hpp').read_text()
    assert header.count('field_getter<const char*, global, raw> {}.get(L, key_pointer') == 2, \
        'Both direct-field overloads must normalize narrow array keys'
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
    return dict(state='passed', compiler=compiler, cases=10,
                coverage=['empty table defaults', 'distinct stat and scalar fields',
                          'missing stat defaults and uint8 maximum', 'absent stat table',
                          'different literal extents', 'global and stack overloads',
                          'mutable/pointer/string/numeric keys', 'raw and binary keys',
                          'empty and embedded-NUL literals', 'metamethod versus raw lookup'])


if __name__ == '__main__':
    import sys
    check(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else 'g++-15')
