// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
#pragma once
#include "json.hpp"
#include <array>
#include <cstdint>
#include <string>
#include <vector>
namespace mobile {
struct Image16 {
    uint32_t width = 0, height = 0;
    std::vector<uint16_t> rgba;
};
struct Geometry {
    double x = 0, y = 0, w = 1, h = 1, angle = 0;
    int turns = 0;
    bool flip_h = false, flip_v = false;
    uint32_t crop_w = 0, crop_h = 0, out_w = 0, out_h = 0;
    double fit = 1;
    Geometry(const spk::Json &, uint32_t, uint32_t);
    void sample(uint32_t ox, uint32_t oy, double &sx, double &sy) const;
    uint32_t source_w = 0, source_h = 0;
};
Image16 apply_edits(const uint16_t *rgba, uint32_t width, uint32_t height, uint32_t stride,
                    const spk::Json &edits);
spk::Json output_dimensions(uint32_t width, uint32_t height, const spk::Json &edits);
void write_image(const Image16 &, const std::string &path, const std::string &format,
                 const std::string &resources, const spk::Json &edits);
} // namespace mobile
