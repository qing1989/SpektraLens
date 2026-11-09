// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
#include "raw_adapter.hpp"
#include "io/raw_decoder.hpp"
#include <algorithm>
#include <cmath>
#include <libraw/libraw.h>
#include <stdexcept>

namespace mobile {
spk::Json inspect_raw(const std::string &path) {
    spk::Json j = spk::Json::object();
    j.set("raw", spk::Json(false));
    LibRaw raw;
    if (raw.open_file(path.c_str()) != LIBRAW_SUCCESS || raw.imgdata.idata.raw_count < 1)
        return j;
    j.set("raw", spk::Json(true));
    j.set("make", spk::Json(std::string(raw.imgdata.idata.make)));
    j.set("model", spk::Json(std::string(raw.imgdata.idata.model)));
    uint32_t w = raw.imgdata.sizes.width, h = raw.imgdata.sizes.height;
    if (raw.imgdata.sizes.flip >= 5)
        std::swap(w, h);
    j.set("width", spk::Json(double(w)));
    j.set("height", spk::Json(double(h)));
    j.set("timestamp", spk::Json(double(raw.imgdata.other.timestamp) * 1000));
    j.set("iso", spk::Json(double(raw.imgdata.other.iso_speed)));
    j.set("aperture", spk::Json(double(raw.imgdata.other.aperture)));
    j.set("shutter", spk::Json(double(raw.imgdata.other.shutter)));
    j.set("focal_mm", spk::Json(double(raw.imgdata.other.focal_len)));
    j.set("decoder", spk::Json(std::string(LibRaw::version())));
    return j;
}
RawImage decode_raw(const std::string &path, uint32_t long_edge) {
    spk::io::DecodedRaw decoded;
    std::string error;
    if (!spk::io::decode_raw_compatible(std::filesystem::path(path), decoded, error))
        throw std::runtime_error(error);
    RawImage out;
    out.full_width = out.width = decoded.width;
    out.full_height = out.height = decoded.height;
    if (!long_edge || std::max(out.width, out.height) <= long_edge) {
        out.rgb = std::move(decoded.rgb);
        return out;
    }
    double scale = double(long_edge) / std::max(out.width, out.height);
    out.width = std::max(1u, uint32_t(std::lround(out.width * scale)));
    out.height = std::max(1u, uint32_t(std::lround(out.height * scale)));
    out.rgb.resize(size_t(out.width) * out.height * 3);
    for (uint32_t y = 0; y < out.height; y++)
        for (uint32_t x = 0; x < out.width; x++) {
            float sx = (x + .5f) * decoded.width / out.width - .5f,
                  sy = (y + .5f) * decoded.height / out.height - .5f;
            int x0 = std::clamp(int(std::floor(sx)), 0, int(decoded.width) - 1),
                y0 = std::clamp(int(std::floor(sy)), 0, int(decoded.height) - 1);
            int x1 = std::min(x0 + 1, int(decoded.width) - 1),
                y1 = std::min(y0 + 1, int(decoded.height) - 1);
            float fx = std::clamp(sx - x0, 0.f, 1.f), fy = std::clamp(sy - y0, 0.f, 1.f);
            for (int k = 0; k < 3; k++) {
                float a = decoded.rgb[(size_t(y0) * decoded.width + x0) * 3 + k] * (1 - fx) +
                          decoded.rgb[(size_t(y0) * decoded.width + x1) * 3 + k] * fx;
                float b = decoded.rgb[(size_t(y1) * decoded.width + x0) * 3 + k] * (1 - fx) +
                          decoded.rgb[(size_t(y1) * decoded.width + x1) * 3 + k] * fx;
                out.rgb[(size_t(y) * out.width + x) * 3 + k] = a * (1 - fy) + b * fy;
            }
        }
    return out;
}
} // namespace mobile
