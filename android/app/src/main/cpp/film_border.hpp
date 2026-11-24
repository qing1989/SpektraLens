// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
#pragma once

#include "edit_pipeline.hpp"

namespace mobile {

enum class FilmBorder { None, Black135, Warm135, Silver135, Slide, Medium120, Sheet };

struct FrameLayout {
    int left = 0, top = 0, right = 0, bottom = 0;
    uint32_t width = 0, height = 0;
    int photo_width, photo_height;
    std::string style;
    FilmBorder film;

    FrameLayout(uint32_t w, uint32_t h, const spk::Json &edits);
    bool identity() const { return style == "none"; }
    bool acetate() const { return film != FilmBorder::None && film != FilmBorder::Slide; }
};

void paint_film_base(Image16 &image, const FrameLayout &layout);
void finish_film_border(Image16 &image, const FrameLayout &layout, const spk::Json &edits,
                        bool transparent);
} // namespace mobile
