// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <algorithm>
#include <cstdint>
#include <string_view>

namespace handheld
{
constexpr int lastEraJob = 20; // Scholar

constexpr bool validJobLevel(int job, int level)
{
    return job >= 1 && job <= lastEraJob && level >= 1 && level <= 75;
}

constexpr bool validProfile(std::string_view name, int job, int level, int subJob, int subLevel)
{
    if (!validJobLevel(job, level) || subJob < 0 || subJob > lastEraJob || subJob == job ||
        subLevel < 0 || subLevel > level / 2 || ((subJob == 0) != (subLevel == 0)))
    {
        return false;
    }
    return name.size() >= 3 && name.size() <= 15 &&
           std::ranges::all_of(name, [](char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z'); });
}

constexpr bool canWear(int job, int level, int slot, int requiredLevel, std::uint32_t jobs, std::uint16_t slots)
{
    return validJobLevel(job, level) && slot >= 0 && slot < 16 && level >= requiredLevel &&
           (jobs & (1U << (job - 1))) && (slots & (1U << slot));
}
} // namespace handheld
