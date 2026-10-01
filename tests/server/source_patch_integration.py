"""Compile the real staged decoder functions and exercise their boundaries."""

from pathlib import Path
import re
import subprocess


PREAMBLE = r'''
#include <algorithm>
#include <array>
#include <cassert>
#include <cstdint>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>
using uint8 = std::uint8_t;
using uint32 = std::uint32_t;
constexpr std::size_t PacketNameLength = 16;
constexpr std::size_t DecodeStringLength = 21;
constexpr std::size_t LinkshellStringLength = 20;
constexpr std::size_t SignatureStringLength = 16;

// A minimal implementation of the six-bit unpacking dependency. Decoder bodies
// below are extracted from the actual staged source, never copied into this test.
uint8 unpackBitsLE(uint8* bytes, uint32 offset, uint32 width)
{
    assert(width == 6);
    uint8 value = 0;
    for (uint32 bit = 0; bit < width; ++bit)
        value |= ((bytes[(offset + bit) / 8] >> ((offset + bit) % 8)) & 1u) << bit;
    return value;
}
'''

CHECKS = r'''
std::string packed(const std::vector<uint8>& codes)
{
    std::string bytes((codes.size() * 6 + 7) / 8, '\0');
    for (std::size_t index = 0; index < codes.size(); ++index)
        for (std::size_t bit = 0; bit < 6; ++bit)
            bytes[(index * 6 + bit) / 8] |= ((codes[index] >> bit) & 1u) << ((index * 6 + bit) % 8);
    return bytes;
}

void check(void (*decode)(const std::string&, char*), const std::vector<uint8>& codes,
           const std::string& expected)
{
    struct Guarded {
        char before = '#';
        std::array<char, DecodeStringLength> value;
        char after = '!';
    } output;
    output.value.fill('~');
    decode(packed(codes), output.value.data());
    assert(output.before == '#' && output.after == '!');
    assert(std::memchr(output.value.data(), '\0', output.value.size()) != nullptr);
    assert(std::string(output.value.data()) == expected);
    for (std::size_t index = expected.size(); index < output.value.size(); ++index)
        assert(output.value[index] == '\0');
}

int main()
{
    std::vector<uint8> linkshell(20, 1);
    check(DecodeStringLinkshell, linkshell, std::string(20, 'a'));
    linkshell[19] = 63;
    check(DecodeStringLinkshell, linkshell, std::string(19, 'a'));
    check(DecodeStringLinkshell, {63, 0, 0, 0}, "");
    check(DecodeStringLinkshell, {1, 2, 63, 0}, "ab");
    check(DecodeStringLinkshell, {1, 2, 0, 9}, "a");
    check(DecodeStringLinkshell, {}, "");

    std::vector<uint8> signature(16, 37);
    check(DecodeStringSignature, signature, std::string(16, 'a'));
    signature[15] = 0;
    check(DecodeStringSignature, signature, std::string(15, 'a'));
    signature[1] = 0;
    signature[2] = 38;
    check(DecodeStringSignature, signature, "a");
    check(DecodeStringSignature, std::vector<uint8>(16, 0), "");
    std::cout << "PASS: decoded full-width names, terminators, zero padding and output bounds\n";
}
'''


def decoder(source, name):
    start = source.index('void ' + name + '(')
    opening = source.index('{', start)
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == '{':
            depth += 1
        elif source[index] == '}':
            depth -= 1
            if depth == 0:
                return source[start:index + 1]
    raise AssertionError('Cannot extract real decoder ' + name)


def check(root, output, compiler='g++-15'):
    root, output = Path(root), Path(output)
    output.mkdir(parents=True, exist_ok=True)
    source = (root / 'src/common/utils.cpp').read_text()
    caller = (root / 'src/map/items/item_linkshell.cpp').read_text()
    assert re.search(r'char\s+decoded\[DecodeStringLength\]\s*=\s*\{\};\s*DecodeStringLinkshell', caller), \
        'The linkshell item caller must use the decoded-size buffer'
    fixture = output / 'source-patch-boundaries.cpp'
    fixture.write_text(PREAMBLE + '\n' + decoder(source, 'DecodeStringLinkshell') + '\n' +
                       decoder(source, 'DecodeStringSignature') + '\n' + CHECKS)
    executable = output / 'source-patch-boundaries'
    commands = ([compiler, '-std=c++23', '-O3', '-Wall', '-Wextra', '-Werror',
                 '-Wstringop-truncation', str(fixture), '-o', str(executable)], [str(executable)])
    log = output / 'source-patch-boundaries.log'
    with log.open('w') as stream:
        for command in commands:
            stream.write('$ ' + ' '.join(command) + '\n')
            stream.flush()
            subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=120)
    return dict(state='passed', compiler=compiler, cases=10,
                coverage=['20-character linkshell', '16-character decoded signature',
                          'empty and embedded-NUL input', 'zero padding', 'output bounds'])


if __name__ == '__main__':
    import sys
    check(sys.argv[1], sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else 'g++-15')
