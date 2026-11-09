// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
#pragma once
#include "json.hpp"
#include <cstdint>
#include <string>
#include <vector>
namespace mobile {
struct RawImage {
    uint32_t width = 0, height = 0, full_width = 0, full_height = 0;
    std::vector<float> rgb;
};
spk::Json inspect_raw(const std::string &path);
RawImage decode_raw(const std::string &path, uint32_t long_edge);
} // namespace mobile
