"""Compile the same level, name and equipment checks used in xi_map."""
from pathlib import Path
import subprocess
import unittest

INCLUDES = Path(__file__).resolve().parents[2] / 'server/solo_world/modules/handheld_squad/cpp'


class PersonalMateNativeRules(unittest.TestCase):
    def test_profile_and_equipment_boundaries(self):
        checks = r'''
#include "handheld_mate_rules.h"
using namespace handheld;
static_assert(validProfile("Amy", 1, 1, 0, 0));
static_assert(validProfile("Abcdefghijklmno", 18, 75, 3, 37));
static_assert(!validProfile("Abcdefghijklmnop", 1, 1, 0, 0));
static_assert(!validProfile("A!", 1, 1, 0, 0));
static_assert(!validProfile("Ada\n", 1, 1, 0, 0));
static_assert(!validProfile("Adi", 1, 76, 0, 0));
static_assert(validProfile("Adi", 19, 1, 0, 0));
static_assert(validProfile("Adi", 20, 75, 19, 37));
static_assert(validProfile("Adi", 19, 75, 20, 37));
static_assert(!validProfile("Adi", 21, 75, 0, 0));
static_assert(!validProfile("Adi", 1, 75, 21, 37));
static_assert(!validProfile("Adi", 4, 10, 4, 5));
static_assert(!validProfile("Adi", 4, 10, 3, 6));
static_assert(!validProfile("Adi", 4, 10, 0, 5));
static_assert(canWear(18, 75, 15, 75, 1U << 17, 1U << 15));
static_assert(canWear(19, 75, 15, 75, 1U << 18, 1U << 15));
static_assert(canWear(20, 75, 15, 75, 1U << 19, 1U << 15));
static_assert(!canWear(20, 75, 15, 75, 1U << 18, 1U << 15));
static_assert(!canWear(21, 75, 15, 75, ~0U, 1U << 15));
static_assert(!canWear(18, 74, 15, 75, 1U << 17, 1U << 15));
static_assert(!canWear(18, 75, 15, 75, 1U << 16, 1U << 15));
static_assert(!canWear(18, 75, 14, 75, 1U << 17, 1U << 15));
static_assert(!canWear(0, 75, -1, 1, ~0U, 0xFFFF));
static_assert(!canWear(1, 75, 16, 1, ~0U, 0xFFFF));
'''
        subprocess.run(['c++', '-std=c++20', '-fsyntax-only', '-x', 'c++', '-I', str(INCLUDES), '-'],
                       input=checks, text=True, check=True)
