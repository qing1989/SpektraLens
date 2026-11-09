// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
// ICC colour-space descriptors from SpektraLab/engine/host/image_io.cpp (GPL-3.0-or-later).
// Keep the host image loader out of Android; Android ImageDecoder handles raster inputs.
#include "image_io.hpp"
namespace spkhost {
RgbSpace display_p3_space() {
    RgbSpace s;
    s.to_xyz_d50 = {0.5151215, 0.2919769,  0.1571045, 0.2411957, 0.6922455,
                    0.0665588, -0.0010529, 0.0418854, 0.7840729};
    Trc t;
    t.kind = Trc::Kind::Para;
    t.para_type = 3;
    t.p = {2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, 0, 0};
    s.trc = {t, t, t};
    s.description = "Display P3";
    return s;
}
RgbSpace linear_prophoto_space() {
    RgbSpace s;
    s.to_xyz_d50 = {0.7976749, 0.1351917, 0.0313534, 0.2880402, 0.7118741,
                    0.0000857, 0,         0,         0.8252100};
    Trc t;
    t.kind = Trc::Kind::Gamma;
    t.gamma = 1;
    s.trc = {t, t, t};
    s.description = "linear ProPhoto RGB (assumed)";
    return s;
}
} // namespace spkhost
