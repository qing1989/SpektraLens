// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
// Android scan-side edits. Tone order follows SpektraLab Canvas/Shaders.metal;
// this mobile path grades encoded sRGB, using the sRGB mid-grey pivot.
#include "edit_pipeline.hpp"
#include "film_border.hpp"
#include "output.hpp"
#include "platform.hpp"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <vector>
#include <zlib.h>

namespace mobile {
namespace {
constexpr float pi = 3.14159265358979323846f;
float sat(float x) { return std::clamp(x, 0.f, 1.f); }
float num(const spk::Json &j, const char *k, float def = 0, float lo = -1e6f, float hi = 1e6f) {
    return std::clamp(float(j.at(k).as_double(def)), lo, hi);
}
float smooth(float a, float b, float x) {
    float t = sat((x - a) / (b - a));
    return t * t * (3 - 2 * t);
}
float luma(const float c[3]) { return c[0] * .2126f + c[1] * .7152f + c[2] * .0722f; }
float tonepos(float x) {
    return x <= .4614f ? .5f * x / .4614f : .5f + .5f * (x - .4614f) / (1 - .4614f);
}
struct SilverEdrLut {
    static constexpr int kSize = 4096;
    static constexpr float kLinLow = .05f;
    std::array<float, kSize + 1> srgb_lin{};
    std::array<float, kSize + 1> lin_srgb{};
    std::array<float, kSize + 1> shoulder_scale{};

    SilverEdrLut() {
        constexpr float kShoulder = .40f;
        constexpr float kPaperWhite = .7292f;
        constexpr float kTargetWhite = .96f;
        constexpr float kExtra = kTargetWhite - kPaperWhite;
        constexpr float kPower = 2.3f;
        constexpr float kSlope = 1.f + kExtra * kPower / (kPaperWhite - kShoulder);
        constexpr float kHead = 1.f - kTargetWhite;
        for (int i = 0; i <= kSize; ++i) {
            const float t = float(i) / float(kSize);
            srgb_lin[i] = t <= .040449936f ? t / 12.92f : std::pow((t + .055f) / 1.055f, 2.4f);
            const float yl = kLinLow + t * (1.f - kLinLow);
            lin_srgb[i] = 1.055f * std::pow(yl, 1.f / 2.4f) - .055f;
            const float y = kShoulder + t * (1.f - kShoulder);
            float mapped;
            if (y <= kPaperWhite) {
                const float u = (y - kShoulder) / (kPaperWhite - kShoulder);
                mapped = y + kExtra * std::pow(u, kPower);
            } else {
                mapped = kTargetWhite + kHead * (1.f - std::exp(-kSlope * (y - kPaperWhite) / kHead));
            }
            shoulder_scale[i] = mapped / (y + 1e-10f);
        }
    }

    static float sample(const std::array<float, kSize + 1> &table, float t) {
        const float pos = sat(t) * float(kSize);
        const int idx = std::min(int(pos), kSize - 1);
        const float frac = pos - float(idx);
        return table[idx] + (table[idx + 1] - table[idx]) * frac;
    }

    float to_lin(float v) const { return sample(srgb_lin, v); }

    float to_srgb(float y) const {
        y = sat(y);
        if (y < kLinLow)
            return y <= .0031308f ? 12.92f * y : 1.055f * std::pow(y, 1.f / 2.4f) - .055f;
        return sample(lin_srgb, (y - kLinLow) / (1.f - kLinLow));
    }

    float scale_for(float y) const {
        return sample(shoulder_scale, (y - .40f) / (1.f - .40f));
    }
};

const SilverEdrLut &silver_edr_lut() {
    static const SilverEdrLut lut;
    return lut;
}

// Highlight shoulder expansion for monochrome silver paper (Ilford Multigrade IV RC),
// which has no calibrated upstream edr_tone_map. Leaves shadows/midtones (Y <= 0.40)
// strictly untouched and expands the CAM16-compressed paper white (Y ~ 0.7292, sRGB ~ 222)
// smoothly to Y ~ 0.96 (sRGB ~ 250) with C1 continuity and soft roll-off toward 1.0.
void apply_silver_highlight_edr(float c[3]) {
    if (std::max({c[0], c[1], c[2]}) <= .665f) return;
    const auto &lut = silver_edr_lut();
    constexpr float kShoulder = .40f;
    if (c[0] == c[1] && c[1] == c[2]) {
        const float y = lut.to_lin(c[0]);
        if (y <= kShoulder) return;
        const float out = lut.to_srgb(y * lut.scale_for(y));
        c[0] = c[1] = c[2] = out;
        return;
    }
    const float r = lut.to_lin(c[0]), g = lut.to_lin(c[1]), b = lut.to_lin(c[2]);
    const float y = r * .2126f + g * .7152f + b * .0722f;
    if (y <= kShoulder) return;
    const float scale = lut.scale_for(y);
    c[0] = lut.to_srgb(r * scale);
    c[1] = lut.to_srgb(g * scale);
    c[2] = lut.to_srgb(b * scale);
}
uint16_t quant(float x) { return uint16_t(std::lround(sat(x) * 65535.f)); }

// Scan-side light added to the developed image. The saved seed determines the
// shape once per render; resizing evaluates the same shape at new pixel centers.
struct LightLeak {
    float amount;
    bool monochrome;
    bool firstRoll;
    uint32_t seed;

    explicit LightLeak(const spk::Json &edits)
        : amount(num(edits, "light_leak_amount", 0, 0, 100) / 100),
          monochrome(edits.at("light_leak_monochrome").as_bool()),
          firstRoll(edits.at("light_leak_mode").as_string() == "first_roll"), seed(0) {
        double saved = edits.at("light_leak_seed").as_double();
        if (saved >= 0 && saved <= std::numeric_limits<int32_t>::max())
            seed = static_cast<uint32_t>(saved);
    }

    bool active() const { return amount > 0; }

    void apply(Image16 &out, int left, int top, int width, int height, bool filmBorder) const {
        if (!active()) return;
        // The slider uses a square-root response so low values reach a visible
        // fog earlier while 0/100 stay fixed. The saved seed rebuilds the same
        // shape, and resizing evaluates the normalized profiles at new pixel
        // centers.
        const float strength = std::sqrt(amount);
        // A fixed integer generator keeps stored recipes independent of the
        // platform's standard-library distribution implementations.
        uint32_t state = seed;
        const auto next = [&state]() {
            uint32_t bits = (state += 0x9e3779b9u);
            bits = (bits ^ (bits >> 16)) * 0x21f0aaadu;
            bits = (bits ^ (bits >> 15)) * 0x735a2d97u;
            return float((bits ^ (bits >> 15)) >> 8) / 16777216.f;
        };
        const int edge = int(next() * (firstRoll ? 2 : 4));
        if (firstRoll) {
            const float widthFraction = .19f + .09f * next();
            const float phase = next() * 2 * pi;
            applyFirstRoll(out, left, top, width, height, filmBorder, edge != 0, widthFraction, phase);
            return;
        }
        const bool sideEdge = edge < 2;  // Left/right, then top/bottom.
        const bool farEdge = (edge & 1) != 0;
        const float center = .08f + .84f * next();
        // A broad, low-frequency veil covers the whole frame. The core stays
        // near the chosen edge, so the leak still has a believable entry point
        // instead of reading as a flat graduated filter.
        const float lateralWidth = .42f + .16f * next();
        const float coreDepth = .26f + .10f * next();
        const float coreWidth = .34f + .12f * next();
        const float fogGreen = .80f + .08f * next();
        const float fogBlue = .68f + .14f * next();
        const uint16_t fogGreen16 = uint16_t(std::lround(fogGreen * 65535));
        const uint16_t fogBlue16 = uint16_t(std::lround(fogBlue * 65535));
        const int outW = int(out.width), outH = int(out.height);
        std::vector<float> veilX(outW), coreX(outW), lateralX(outW), coreAlongX(outW);
        std::vector<float> veilY(outH), coreY(outH), lateralY(outH), coreAlongY(outH);
        for (int x = 0; x < outW; ++x) {
            const float u = (x - left + .5f) / width;
            const float across = farEdge ? 1 - u : u;
            veilX[x] = .08f + .52f * (1 - smooth(0, 1, across));
            coreX[x] = 1 - smooth(0, coreDepth, across);
            lateralX[x] = 1 - .45f * smooth(0, 1, std::fabs(u - center) / lateralWidth);
            coreAlongX[x] = 1 - smooth(0, coreWidth, std::fabs(u - center));
        }
        for (int y = 0; y < outH; ++y) {
            const float v = (y - top + .5f) / height;
            const float across = farEdge ? 1 - v : v;
            veilY[y] = .08f + .52f * (1 - smooth(0, 1, across));
            coreY[y] = 1 - smooth(0, coreDepth, across);
            lateralY[y] = 1 - .45f * smooth(0, 1, std::fabs(v - center) / lateralWidth);
            coreAlongY[y] = 1 - smooth(0, coreWidth, std::fabs(v - center));
        }
        const int xBegin = filmBorder ? 0 : left;
        const int xEnd = filmBorder ? outW : left + int(width);
        const int yBegin = filmBorder ? 0 : top;
        const int yEnd = filmBorder ? outH : top + int(height);
        for (int y = yBegin; y < yEnd; ++y)
            for (int x = xBegin; x < xEnd; ++x) {
                const float veil = sideEdge ? veilX[x] * lateralY[y] : veilY[y] * lateralX[x];
                const float core = sideEdge ? coreX[x] * coreAlongY[y] : coreY[y] * coreAlongX[x];
                const float fog = std::min(.86f, strength * (veil + .25f * core));
                auto *pixel = &out.rgba[(size_t(y) * out.width + x) * 4];
                const float floor = std::min({pixel[0], pixel[1], pixel[2]}) / 65535.f;
                const float keep = floor < .97f ? 1.f : std::max(0.f, (1.f - floor) / .03f);
                // Keep deep blacks clean: the veil is most visible over the
                // lit part of the frame, as in the reference scan. The film
                // base lies outside the photo, so it takes the full veil and
                // visibly continues the leak past the gate.
                const bool inside = x >= left && x < left + int(width)
                                 && y >= top && y < top + int(height);
                const float luma = (pixel[0] * .2126f + pixel[1] * .7152f
                                    + pixel[2] * .0722f) / 65535.f;
                const float lift = inside ? smooth(.03f, .38f, luma) : 1.f;
                const float mix = fog * keep * lift;
                pixel[0] = blend(pixel[0], 65535, mix);
                pixel[1] = blend(pixel[1], monochrome ? 65535 : fogGreen16, mix);
                pixel[2] = blend(pixel[2], monochrome ? 65535 : fogBlue16, mix);
            }
    }

    static uint16_t blend(uint16_t from, uint16_t to, float mix) {
        return uint16_t(std::lround(from + (int(to) - from) * mix));
    }

    void applyFirstRoll(Image16 &out, int left, int top, int width, int height,
                        bool filmBorder, bool fromRight, float widthFraction, float phase) const {
        const float strength = smooth(0, .81f, amount);
        const float feather = .085f;
        const int right = int(out.width) - width - left;
        const int entry = filmBorder ? (fromRight ? right : left) : 0;
        const int opposite = filmBorder ? (fromRight ? left : right) : 0;
        const int yBegin = filmBorder ? -top : 0;
        const int yEnd = filmBorder ? int(out.height) - top : height;
        for (int y = yBegin; y < yEnd; ++y) {
            const float t = (y + .5f) / height;
            const float wave = .012f * std::sin(2 * pi * t + phase)
                             + .005f * std::sin(6 * pi * t - phase);
            const float boundary = widthFraction * (.56f + .44f * amount) + wave;
            const int limit = std::min(width + opposite, int(std::ceil(double(boundary + feather) * width)));
            for (int inward = -entry; inward < limit; ++inward) {
                const float distance = (inward + .5f) / width - boundary;
                const float mix = strength * (1 - smooth(-.012f, feather, distance));
                const float rim = smooth(-.032f, .024f, distance);
                const int x = fromRight ? width - 1 - inward : inward;
                auto *pixel = &out.rgba[(size_t(y + top) * out.width + x + left) * 4];
                const uint16_t green = monochrome ? 65535 : uint16_t(63600 - 8200 * rim);
                const uint16_t blue = monochrome ? 65535 : uint16_t(57500 - 47900 * rim);
                pixel[0] = blend(pixel[0], 65535, mix);
                pixel[1] = blend(pixel[1], green, mix);
                pixel[2] = blend(pixel[2], blue, mix);
            }
        }
    }
};

struct Curve {
    float v[1025];
    bool active = false;
    explicit Curve(const spk::Json &points) {
        std::vector<std::pair<float, float>> p;
        for (const auto &pair : points.items())
            if (pair.items().size() == 2)
                p.emplace_back(sat(pair.items()[0].as_double()), sat(pair.items()[1].as_double()));
        if (p.size() < 2)
            p = {{0, 0}, {1, 1}};
        std::sort(p.begin(), p.end());
        p.erase(std::unique(p.begin(), p.end(),
                            [](auto a, auto b) { return std::fabs(a.first - b.first) < .0001f; }),
                p.end());
        if (p.size() < 2)
            p = {{0, 0}, {1, 1}};
        std::vector<float> d(p.size() - 1), m(p.size());
        for (size_t i = 0; i < d.size(); i++)
            d[i] = (p[i + 1].second - p[i].second) / (p[i + 1].first - p[i].first);
        m.front() = d.front();
        m.back() = d.back();
        for (size_t i = 1; i + 1 < p.size(); i++)
            m[i] = d[i - 1] * d[i] <= 0 ? 0 : 2 * d[i - 1] * d[i] / (d[i - 1] + d[i]);
        for (int i = 0; i <= 1024; i++) {
            float x = i / 1024.f, y;
            auto it = std::upper_bound(p.begin(), p.end(), x,
                                       [](float f, auto a) { return f < a.first; });
            if (it == p.begin())
                y = p.front().second;
            else if (it == p.end())
                y = p.back().second;
            else {
                size_t k = size_t(it - p.begin() - 1);
                float h = p[k + 1].first - p[k].first, t = (x - p[k].first) / h, t2 = t * t,
                      t3 = t2 * t;
                y = (2 * t3 - 3 * t2 + 1) * p[k].second + (t3 - 2 * t2 + t) * h * m[k] +
                    (-2 * t3 + 3 * t2) * p[k + 1].second + (t3 - t2) * h * m[k + 1];
            }
            v[i] = sat(y);
            active |= std::fabs(v[i] - x) > 1e-5f;
        }
    }
    float apply(float x) const {
        float f = sat(x) * 1024;
        int a = std::min(1023, int(f));
        return v[a] + (v[a + 1] - v[a]) * (f - a);
    }
};

struct Grade {
    bool enabled = true, curves = false, hsl = false;
    float wb[3], gain, contrast, brightness, shadows, highlights, black, white, saturation,
        vignette, midpoint;
    float balance[4][3]{}, lum[4]{}, hues[8]{}, sats[8]{}, lights[8]{};
    Curve rgb, yl, r, g, b;
    explicit Grade(const spk::Json &j)
        : rgb(j.at("curve_rgb")), yl(j.at("curve_luma")), r(j.at("curve_r")), g(j.at("curve_g")),
          b(j.at("curve_b")) {
        enabled = j.at("grade_enabled").as_bool(true);
        float temp = num(j, "grade_temperature", 0, -100, 100) / 100,
              tint = num(j, "grade_tint", 0, -100, 100) / 100;
        wb[0] = 1 + temp * .18f + tint * .06f;
        wb[1] = 1 - tint * .12f;
        wb[2] = 1 - temp * .18f + tint * .06f;
        gain = std::pow(2.f, num(j, "grade_exposure", 0, -5, 5) / 2.2f);
        contrast = num(j, "grade_contrast", 0, -50, 50) / 100;
        brightness = num(j, "grade_brightness", 0, -50, 50) / 100;
        shadows = num(j, "grade_shadows", 0, -100, 100) / 100;
        highlights = num(j, "grade_highlights", 0, -100, 100) / 100;
        black = num(j, "grade_black", 0, 0, 50) / 100;
        white = num(j, "grade_white", 0, 0, 50) / 100;
        saturation = 1 + num(j, "grade_saturation", 0, -100, 100) / 100;
        vignette = num(j, "grade_vignette", 0, -100, 100) / 100;
        midpoint = num(j, "grade_vignette_midpoint", 50, 0, 100) / 100;
        curves = rgb.active || yl.active || r.active || g.active || b.active;
        const char *zones[] = {"master", "shadows", "midtones", "highlights"};
        for (int i = 0; i < 4; i++) {
            std::string root = "balance_" + std::string(zones[i]);
            float h = num(j, (root + "_hue").c_str(), 0, 0, 360) * pi / 180;
            float s = num(j, (root + "_saturation").c_str(), 0, 0, 100) / 400;
            lum[i] = num(j, (root + "_luminance").c_str(), 0, -100, 100) / 400;
            for (int k = 0; k < 3; k++)
                balance[i][k] = std::cos(h - k * 2 * pi / 3) * s;
        }
        const char *bands[] = {"red",  "orange", "yellow", "green",
                               "cyan", "blue",   "purple", "magenta"};
        for (int i = 0; i < 8; i++) {
            std::string root = "hsl_" + std::string(bands[i]);
            hues[i] = num(j, (root + "_hue").c_str(), 0, -100, 100) / 100;
            sats[i] = num(j, (root + "_saturation").c_str(), 0, -100, 100) / 100;
            lights[i] = num(j, (root + "_luminance").c_str(), 0, -100, 100) / 100;
            hsl |= hues[i] != 0 || sats[i] != 0 || lights[i] != 0;
        }
    }
    /**
     * True when every grade control is at its neutral value. The export path can then copy the
     * engine result instead of resampling and grading a second full-size 16-bit image.
     */
    bool identity() const {
        if (!enabled) return true;
        if (curves || hsl) return false;
        if (std::fabs(wb[0] - 1) > 1e-6f || std::fabs(wb[1] - 1) > 1e-6f ||
            std::fabs(wb[2] - 1) > 1e-6f) return false;
        if (std::fabs(gain - 1) > 1e-6f || std::fabs(contrast) > 1e-6f ||
            std::fabs(brightness) > 1e-6f || std::fabs(shadows) > 1e-6f ||
            std::fabs(highlights) > 1e-6f || std::fabs(black) > 1e-6f ||
            std::fabs(white) > 1e-6f || std::fabs(saturation - 1) > 1e-6f ||
            std::fabs(vignette) > 1e-6f) return false;
        for (int i = 0; i < 4; i++) {
            if (std::fabs(lum[i]) > 1e-6f) return false;
            for (int k = 0; k < 3; k++)
                if (std::fabs(balance[i][k]) > 1e-6f) return false;
        }
        return true;
    }

    void apply(float c[3], float u, float v) const {
        if (!enabled)
            return;
        for (int k = 0; k < 3; k++) {
            c[k] *= wb[k] * gain;
            if (contrast != 0)
                c[k] = (c[k] - .4614f) * (1 + contrast * 1.2f) + .4614f;
            if (brightness != 0)
                c[k] = std::pow(std::max(0.f, c[k]), 1 / (1 + brightness * .8f));
        }
        float pos = sat(tonepos(luma(c))), ws = (1 - pos) * (1 - pos), wh = pos * pos;
        float wh_lift = smooth(.45f, .95f, pos);
        for (int k = 0; k < 3; k++) {
            c[k] += shadows * .25f * ws * std::max(0.f, 1 - c[k]);
            c[k] += highlights > 0
                        ? highlights * .78f * wh_lift * std::max(0.f, 1 - c[k])
                        : highlights * .25f * wh * c[k];
            c[k] = (c[k] - black) / std::max(1 - black - white, .05f);
        }
        float l = luma(c);
        pos = sat(tonepos(l));
        ws = (1 - pos) * (1 - pos);
        wh = pos * pos;
        float wm = std::max(1 - ws - wh, 0.f);
        for (int k = 0; k < 3; k++) {
            c[k] = l + (c[k] - l) * saturation;
            c[k] += balance[0][k] + balance[1][k] * ws + balance[2][k] * wm + balance[3][k] * wh;
            c[k] *= 1 + lum[0] + lum[1] * ws + lum[2] * wm + lum[3] * wh;
        }
        if (hsl) {
            float mx = std::max({c[0], c[1], c[2]}), mn = std::min({c[0], c[1], c[2]}),
                  delta = mx - mn;
            if (delta > 1e-6f) {
                float hue = (mx == c[0]   ? std::fmod((c[1] - c[2]) / delta + 6, 6)
                             : mx == c[1] ? (c[2] - c[0]) / delta + 2
                                          : (c[0] - c[1]) / delta + 4) *
                            60;
                float light = (mx + mn) * .5f,
                      s = delta / std::max(1 - std::fabs(2 * light - 1), .0001f), dh = 0, ds = 0,
                      dl = 0;
                const float centres[] = {0, 30, 60, 120, 180, 240, 285, 330};
                for (int i = 0; i < 8; i++) {
                    float d = std::fabs(hue - centres[i]);
                    d = std::min(d, 360 - d);
                    float weight = std::max(0.f, 1 - d / 50);
                    weight *= weight;
                    dh += hues[i] * weight * 35;
                    ds += sats[i] * weight;
                    dl += lights[i] * weight * .25f;
                }
                hue = std::fmod(hue + dh + 360, 360);
                s = sat(s * (1 + ds));
                light = sat(light + dl);
                float ch = (1 - std::fabs(2 * light - 1)) * s,
                      x = ch * (1 - std::fabs(std::fmod(hue / 60, 2) - 1)), m = light - ch * .5f;
                float q[3]{};
                int sector = int(hue / 60);
                switch (sector) {
                case 0:
                    q[0] = ch;
                    q[1] = x;
                    break;
                case 1:
                    q[0] = x;
                    q[1] = ch;
                    break;
                case 2:
                    q[1] = ch;
                    q[2] = x;
                    break;
                case 3:
                    q[1] = x;
                    q[2] = ch;
                    break;
                case 4:
                    q[0] = x;
                    q[2] = ch;
                    break;
                default:
                    q[0] = ch;
                    q[2] = x;
                }
                for (int k = 0; k < 3; k++)
                    c[k] = q[k] + m;
            }
        }
        if (curves) {
            for (int k = 0; k < 3; k++)
                c[k] = sat(c[k]);
            float y = luma(c), yy = yl.apply(y);
            for (int k = 0; k < 3; k++)
                c[k] = rgb.apply(sat(y > 1e-4f ? c[k] * yy / y : yy));
            c[0] = r.apply(c[0]);
            c[1] = g.apply(c[1]);
            c[2] = b.apply(c[2]);
        }
        if (vignette != 0) {
            float dx = (u - .5f) * 2, dy = (v - .5f) * 2;
            float f = smooth(midpoint * 1.2f, 1.5f, std::sqrt(dx * dx + dy * dy));
            for (int k = 0; k < 3; k++)
                c[k] *= 1 + vignette * f * .9f;
        }
    }
};

void rect(Image16 &im, int x, int y, int w, int h, const float c[3], float alpha = 1) {
    int x0 = std::max(x, 0), y0 = std::max(y, 0), x1 = std::min(int(im.width), x + w),
        y1 = std::min(int(im.height), y + h);
    for (int yy = y0; yy < y1; yy++)
        for (int xx = x0; xx < x1; xx++) {
            auto *p = &im.rgba[(size_t(yy) * im.width + xx) * 4];
            for (int k = 0; k < 3; k++)
                p[k] = quant(p[k] / 65535.f * (1 - alpha) + c[k] * alpha);
        }
}
uint64_t glyph(char c) {
    const char *letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-./ ";
    static const uint64_t bitmap[] = {
        0x0e11111f111111ULL, 0x1e11111e11111eULL, 0x0e11101010110eULL, 0x1e11111111111eULL,
        0x1f10101e10101fULL, 0x1f10101e101010ULL, 0x0e11101711110fULL, 0x1111111f111111ULL,
        0x0e04040404040eULL, 0x0702020212120cULL, 0x11121418141211ULL, 0x1010101010101fULL,
        0x111b1515111111ULL, 0x11191513111111ULL, 0x0e11111111110eULL, 0x1e11111e101010ULL,
        0x0e11111115120dULL, 0x1e11111e141211ULL, 0x0f10100e01011eULL, 0x1f040404040404ULL,
        0x1111111111110eULL, 0x11111111110a04ULL, 0x11111115151b11ULL, 0x11110a040a1111ULL,
        0x11110a04040404ULL, 0x1f01020408101fULL, 0x0e11131519110eULL, 0x040c040404040eULL,
        0x0e11010204081fULL, 0x1e01010e01011eULL, 0x02060a121f0202ULL, 0x1f10101e01011eULL,
        0x0e10101e11110eULL, 0x1f010204080808ULL, 0x0e11110e11110eULL, 0x0e11110f01010eULL,
        0x0000001f000000ULL, 0x00000000000c0cULL, 0x01010204081010ULL, 0};
    char upper = char(std::toupper(static_cast<unsigned char>(c)));
    const char *p = std::strchr(letters, upper);
    return p ? bitmap[p - letters] : 0;
}
void text(Image16 &im, const std::string &value, int x, int y, int size, const float c[3],
          float opacity) {
    for (char ch : value) {
        uint64_t bits = glyph(ch);
        for (int row = 0; row < 7; row++) {
            int mask = int((bits >> ((6 - row) * 8)) & 31);
            for (int col = 0; col < 5; col++)
                if (mask & (1 << (4 - col)))
                    rect(im, x + col * size, y + row * size, size, size, c, opacity);
        }
        x += 6 * size;
    }
}
// ---- 日期背（quartz date）---------------------------------------------------
// 真实日期背是一排 LED 段把日期曝光到底片上，光在乳剂里散射，所以数字边缘是软
// 的、周围有一圈同色的晕。字形也不是字体，是七段数码管（廉价机型用点阵）。
// Upright date-back segments in glyph coordinates (up from the baseline).
struct SegRect {
    float x0, up, w, hh;
};

constexpr float kDateStroke = .12f;
constexpr float kDateDigitWidth = .54f;
constexpr float kDateTracking = .23f;

float date_advance(char ch) {
    if (ch >= '0' && ch <= '9') return kDateDigitWidth + kDateTracking;
    if (ch == '\'') return .34f;
    if (ch == '.' || ch == ',') return .40f;
    return .55f;
}

void add_seven_seg(const std::string &value, float h, float penX, std::vector<SegRect> &out) {
    static const char *kSeg[10] = {"abcdef", "bc", "abged", "abgcd", "fgbc",
                                   "afgcd", "afgedc", "abc", "abcdefg", "abcdfg"};
    const float w = kDateDigitWidth * h, th = kDateStroke * h, gap = .035f * h;
    float x = penX;
    auto box = [&](float bx, float up, float bw, float bh) {
        out.push_back(SegRect{bx, up, bw, bh});
    };
    for (char ch : value) {
        if (ch == '\'') {
            box(x, .68f * h, th, .25f * h);
        } else if (ch == '.' || ch == ',') {
            box(x, 0, th, th);
        } else if (ch == '1') {
            const float middle = x + (w - th) * .5f;
            box(middle, 0, th, .5f * h - gap * .5f);
            box(middle, .5f * h + gap * .5f, th, .5f * h - gap * .5f);
        } else if (ch >= '0' && ch <= '9') {
            const char *segments = kSeg[ch - '0'];
            const float upper = .5f * (h + th);
            const float upperEnd = std::strchr(segments, 'a') ? h - th * .5f - gap : h;
            const float lower = std::strchr(segments, 'd') ? th * .5f + gap : 0;
            const float lowerLength = .5f * (h - th) - lower;
            for (const char *seg = segments; *seg; ++seg) {
                switch (*seg) {
                    case 'a': box(x + th * .5f, h - th, w - th, th); break;
                    case 'g': box(x + th * .5f, .5f * (h - th), w - th, th); break;
                    case 'd': box(x + th * .5f, 0, w - th, th); break;
                    case 'f': box(x, upper, th, upperEnd - upper); break;
                    case 'b': box(x + w - th, upper, th, upperEnd - upper); break;
                    case 'e': box(x, lower, th, lowerLength); break;
                    case 'c': box(x + w - th, lower, th, lowerLength); break;
                    default: break;
                }
            }
        }
        x += date_advance(ch) * h;
    }
}

float seven_seg_width(const std::string &value, float h) {
    float x = 0;
    for (char ch : value) x += date_advance(ch) * h;
    return std::max(0.f, x - kDateTracking * h);
}

// Intersect the segment rectangle with its four diagonal end cuts.
float date_segment_distance(float x, float y, const SegRect &s) {
    const float dx = std::fabs(x - (s.x0 + s.w * .5f)) - s.w * .5f;
    const float dy = std::fabs(y - (s.up + s.hh * .5f)) - s.hh * .5f;
    const float chamfer = std::min(s.w, s.hh) * .36f;
    return std::max({dx, dy, (dx + dy + chamfer) * .70710678f});
}

/**
 * 单方向盒式模糊，前缀和实现：每行只扫两遍，代价与半径无关。
 * 边缘按「窗口截断后取平均」处理，和原来的 count 归一一致。
 */
void blur_axis(std::vector<float> &v, int w, int h, int r, bool horizontal) {
    int lines = horizontal ? h : w, n = horizontal ? w : h;
    std::vector<double> prefix(size_t(n) + 1);
    for (int line = 0; line < lines; line++) {
        prefix[0] = 0;
        for (int i = 0; i < n; i++)
            prefix[i + 1] =
                prefix[i] + (horizontal ? v[size_t(line) * w + i] : v[size_t(i) * w + line]);
        for (int i = 0; i < n; i++) {
            int lo = std::max(0, i - r), hi = std::min(n - 1, i + r);
            float value = float((prefix[hi + 1] - prefix[lo]) / double(hi - lo + 1));
            if (horizontal) v[size_t(line) * w + i] = value;
            else v[size_t(i) * w + line] = value;
        }
    }
}

/**
 * 可分离盒式模糊。passes 次同半径的盒式模糊 ≈ 高斯：一次是平顶、在 r 处
 * 硬切；两次是三角，三次已经很像高斯。光晕要的是后者——尾巴一路淡出去，
 * 而不是在某个半径上突然收住。
 */
void blur(std::vector<float> &v, int w, int h, int r, int passes = 1) {
    if (r < 1) return;
    for (int pass = 0; pass < passes; pass++) {
        blur_axis(v, w, h, r, true);
        blur_axis(v, w, h, r, false);
    }
}

void date(Image16 &im, const spk::Json &j, int ix, int iy, int iw, int ih) {
    if (!j.at("date_enabled").as_bool())
        return;
    std::string value = j.at("date_text").as_string();
    if (value.empty())
        return;
    const std::string curly = "’";
    for (size_t pos = value.find(curly); pos != std::string::npos; pos = value.find(curly, pos + 1))
        value.replace(pos, curly.size(), 1, '\'');
    value = value.substr(0, 24);
    int size =
        std::max(1, int(std::lround(std::min(iw, ih) * .004f * num(j, "date_size", 1, .5, 3))));
    int inset = std::max(4, int(std::min(iw, ih) * .035f));
    if (iw <= 2 * inset) return;
    float h = 7.f * size;
    float w = seven_seg_width(value, h);
    float avail = float(iw - 2 * inset);
    if (w > avail && w > 1) {
        h *= avail / w;
        w = seven_seg_width(value, h);
    }
    std::string corner = j.at("date_corner").as_string();
    float left = corner.find("left") != std::string::npos;
    float top = corner.find("top") != std::string::npos;
    float x0 = left ? ix + inset : ix + iw - inset - w;
    float baseline = top ? iy + inset + h : iy + ih - inset;

    std::vector<SegRect> segs;
    add_seven_seg(value, h, x0, segs);
    if (segs.empty()) return;

    float c[3] = {1, .49f, .20f};
    if (j.at("date_color").as_string() == "white")
        c[1] = c[2] = .94f;
    float strength = num(j, "date_intensity", 1, .1, 2) * .8f;

    // 掩膜：以水印为中心留出光晕的余量。最外层做两次模糊，支持域是半径的两倍，
    // 缓冲留少了尾巴会被硬切出一道直边。
    int pad = std::max(6, int(std::ceil(h * 1.05f)));
    int mw = int(std::ceil(w)) + pad * 2 + 3, mh = int(h) + pad * 2 + 3;
    int ox = int(std::floor(x0)) - pad, oy = int(std::floor(baseline - h)) - pad;
    std::vector<float> mask(size_t(mw) * mh, 0.f);

    // Continuous coverage preserves cut ends at export size and anti-aliases the preview.
    for (const SegRect &s : segs) {
        int px0 = std::max(0, int(std::floor(s.x0)) - ox - 1);
        int px1 = std::min(mw - 1, int(std::ceil(s.x0 + s.w)) - ox + 1);
        int py0 = std::max(0, int(std::floor(baseline - s.up - s.hh)) - oy - 1);
        int py1 = std::min(mh - 1, int(std::ceil(baseline - s.up)) - oy + 1);
        for (int py = py0; py <= py1; py++) {
            float iy = float(py + oy) + .5f, up = baseline - iy;
            for (int px = px0; px <= px1; px++) {
                float d = date_segment_distance(float(px + ox) + .5f, up, s);
                float cover = std::clamp(.5f - d, 0.f, 1.f);
                if (cover <= 0.f) continue;
                float &cell = mask[size_t(py) * mw + px];
                cell = std::min(1.f, cell + cover);
            }
        }
    }
    // Only soften the core when the output has enough pixels to keep segment gaps.
    if (kDateStroke * h >= 5.f) blur(mask, mw, mh, 1);

    // 三层柔光 ≈ 光学 PSF：近晕贴着笔画，中晕过渡，远晕铺开。每层两次盒式模糊，
    // 外层因此没有那个「到 r 就没了」的硬边。
    std::vector<float> glow = mask;
    blur(glow, mw, mh, std::max(1, int(h * .10f)), 2);
    for (float &value : glow) value *= .50f;
    std::vector<float> layer = mask;
    blur(layer, mw, mh, std::max(2, int(h * .24f)), 2);
    for (size_t i = 0; i < glow.size(); i++) glow[i] += layer[i] * .32f;
    layer = mask;
    blur(layer, mw, mh, std::max(3, int(h * .48f)), 2);
    for (size_t i = 0; i < glow.size(); i++) glow[i] += layer[i] * .18f;

    for (int py = 0; py < mh; py++) {
        int y = oy + py;
        if (y < 0 || y >= int(im.height)) continue;
        for (int px = 0; px < mw; px++) {
            int x = ox + px;
            if (x < 0 || x >= int(im.width)) continue;
            size_t i = size_t(py) * mw + px;
            // 核心直接给满；光晕只在核心之外叠加，并按 (1-core) 收敛。
            // 之前是三层直接相加再截断到 .96，笔画周围一大片会一起顶到上限，
            // 等于把近晕整块压成平台，看上去反而没有过渡。
            float core = mask[i];
            float alpha = core + glow[i] * .62f * (1.f - core);
            alpha = std::min(1.f, sat(alpha) * strength);
            if (alpha <= 0.002f) continue;
            auto *p = &im.rgba[(size_t(y) * im.width + x) * 4];
            for (int k = 0; k < 3; k++) p[k] = quant(p[k] / 65535.f * (1 - alpha) + c[k] * alpha);
        }
    }
}
void paper_caption(Image16 &im, const FrameLayout &f, const spk::Json &j, uint32_t ih) {
    if (f.style == "instant") {
        std::string caption = j.at("edge_caption").as_string();
        if (!caption.empty()) {
            int size = std::max(1, int(f.top) / 13);
            float gray[3] = {.22f, .22f, .22f};
            text(im, caption.substr(0, 42), int(f.left), int(f.top + ih + f.bottom / 2), size,
                 gray, 1);
        }
    }
}
void png_chunk(std::vector<uint8_t> &out, const char *type, const uint8_t *data, size_t n) {
    auto be = [&](uint32_t v) {
        out.push_back(uint8_t(v >> 24));
        out.push_back(uint8_t(v >> 16));
        out.push_back(uint8_t(v >> 8));
        out.push_back(uint8_t(v));
    };
    be(uint32_t(n));
    size_t start = out.size();
    out.insert(out.end(), type, type + 4);
    if (n)
        out.insert(out.end(), data, data + n);
    be(uint32_t(crc32(0, out.data() + start, uInt(n + 4))));
}
} // namespace

Geometry::Geometry(const spk::Json &j, uint32_t sw, uint32_t sh) : source_w(sw), source_h(sh) {
    x = num(j, "geo_x", 0, 0, .999);
    y = num(j, "geo_y", 0, 0, .999);
    w = num(j, "geo_w", 1, .001, 1);
    h = num(j, "geo_h", 1, .001, 1);
    uint32_t left = std::min(sw - 1, uint32_t(std::lround(x * sw))),
             top = std::min(sh - 1, uint32_t(std::lround(y * sh)));
    uint32_t right = std::clamp(uint32_t(std::lround(std::min(1., x + w) * sw)), left + 1, sw),
             bottom = std::clamp(uint32_t(std::lround(std::min(1., y + h) * sh)), top + 1, sh);
    x = double(left);
    y = double(top);
    crop_w = right - left;
    crop_h = bottom - top;
    angle = num(j, "geo_angle", 0, -45, 45) * pi / 180;
    cos_a = std::cos(angle);
    sin_a = std::sin(angle);
    turns = ((j.at("geo_turns").as_int() % 4) + 4) % 4;
    flip_h = j.at("geo_flip_h").as_bool();
    flip_v = j.at("geo_flip_v").as_bool();
    out_w = turns % 2 ? crop_h : crop_w;
    out_h = turns % 2 ? crop_w : crop_h;
    const double abs_cos = std::fabs(cos_a), abs_sin = std::fabs(sin_a);
    fit = std::max(abs_cos + abs_sin * double(crop_h) / crop_w,
                   abs_cos + abs_sin * double(crop_w) / crop_h);
}
void Geometry::sample(uint32_t ox, uint32_t oy, double &sx, double &sy) const {
    double u = (ox + .5) / out_w, v = (oy + .5) / out_h;
    if (flip_h)
        u = 1 - u;
    if (flip_v)
        v = 1 - v;
    double a = u, b = v;
    if (turns == 1) {
        a = v;
        b = 1 - u;
    } else if (turns == 2) {
        a = 1 - u;
        b = 1 - v;
    } else if (turns == 3) {
        a = 1 - v;
        b = u;
    }
    double dx = (a - .5) * crop_w / fit, dy = (b - .5) * crop_h / fit;
    sx = x + crop_w * .5 + cos_a * dx + sin_a * dy - .5;
    sy = y + crop_h * .5 - sin_a * dx + cos_a * dy - .5;
}
namespace {
bool identity_geometry(const Geometry &geo) {
    return geo.x == 0 && geo.y == 0 && geo.crop_w == geo.source_w && geo.crop_h == geo.source_h &&
           geo.out_w == geo.source_w && geo.out_h == geo.source_h && geo.angle == 0 &&
           geo.turns == 0 && !geo.flip_h && !geo.flip_v;
}
bool date_active(const spk::Json &edits) {
    return edits.at("date_enabled").as_bool() && !edits.at("date_text").as_string().empty();
}
}  // namespace

Image16 apply_edits(const uint16_t *pixels, uint32_t w, uint32_t h, uint32_t stride,
                    const spk::Json &edits, bool transparent_frame) {
    if (!w || !h || !pixels)
        throw std::runtime_error("No rendered pixels");
    Geometry geo(edits, w, h);
    Grade grade(edits);
    FrameLayout f(geo.out_w, geo.out_h, edits);
    LightLeak leak(edits);
    const bool silver_edr = edits.at("silver_highlight_edr").as_bool();
    if (uint64_t(f.width) * f.height > 300000000ull)
        throw std::runtime_error("Edited output exceeds 300 megapixels");
    // Keep this predicate in sync with every visual key consumed below. A new overlay or
    // crop field must make identity() false, otherwise the fast path would silently skip it.
    if (identity_geometry(geo) && !silver_edr && grade.identity() && f.identity()
        && !date_active(edits) && !leak.active()) {
        Image16 out;
        out.width = w;
        out.height = h;
        out.rgba.resize(size_t(w) * h * 4);
        const size_t row_pixels = size_t(w) * 4;
        for (uint32_t y = 0; y < h; y++) {
            auto *row = &out.rgba[size_t(y) * row_pixels];
            std::memcpy(row, pixels + size_t(y) * stride * 4,
                        row_pixels * sizeof(uint16_t));
            // The slow path always writes opaque output; preserve that for editBitmap inputs
            // that may still carry an alpha channel.
            for (uint32_t x = 0; x < w; x++) row[size_t(x) * 4 + 3] = 65535;
        }
        return out;
    }
    Image16 out;
    out.width = f.width;
    out.height = f.height;
    out.rgba.resize(size_t(f.width) * f.height * 4);
    float base[3] = {.008f, .008f, .008f};
    if (f.style == "clean_white" || f.style == "instant")
        base[0] = base[1] = base[2] = .96f;
    const uint16_t base_q[4] = {quant(base[0]), quant(base[1]), quant(base[2]), 65535};
    auto fill_span = [&](uint32_t y, uint32_t x_begin, uint32_t x_end) {
        auto *row = &out.rgba[(size_t(y) * out.width + x_begin) * 4];
        for (uint32_t x = x_begin; x < x_end; ++x, row += 4) {
            row[0] = base_q[0];
            row[1] = base_q[1];
            row[2] = base_q[2];
            row[3] = 65535;
        }
    };
    if (!f.identity()) {
        for (uint32_t y = 0; y < f.top; ++y) fill_span(y, 0, out.width);
        for (uint32_t y = f.top; y < f.top + geo.out_h; ++y) {
            if (f.left > 0) fill_span(y, 0, f.left);
            if (f.left + geo.out_w < out.width) fill_span(y, f.left + geo.out_w, out.width);
        }
        for (uint32_t y = f.top + geo.out_h; y < out.height; ++y) fill_span(y, 0, out.width);
    }
    const bool id_geo = identity_geometry(geo);
    if (id_geo && !silver_edr && grade.identity()) {
        const size_t row_values = size_t(w) * 4;
        for (uint32_t y = 0; y < h; ++y) {
            auto *dst = &out.rgba[(size_t(y + f.top) * out.width + f.left) * 4];
            std::memcpy(dst, pixels + size_t(y) * stride * 4, row_values * sizeof(uint16_t));
            for (size_t i = 3; i < row_values; i += 4) dst[i] = 65535;
        }
    } else if (id_geo) {
        constexpr float inv65535 = 1.f / 65535.f;
        const float inv_w = 1.f / float(w), inv_h = 1.f / float(h);
        for (uint32_t y = 0; y < h; ++y) {
            const float ny = (y + .5f) * inv_h;
            const auto *src = pixels + size_t(y) * stride * 4;
            auto *dst = &out.rgba[(size_t(y + f.top) * out.width + f.left) * 4];
            for (uint32_t x = 0; x < w; ++x, src += 4, dst += 4) {
                float c[3] = {src[0] * inv65535, src[1] * inv65535, src[2] * inv65535};
                if (silver_edr) apply_silver_highlight_edr(c);
                grade.apply(c, (x + .5f) * inv_w, ny);
                dst[0] = quant(c[0]);
                dst[1] = quant(c[1]);
                dst[2] = quant(c[2]);
                dst[3] = 65535;
            }
        }
    } else {
        const float inv_w = 1.f / float(geo.out_w), inv_h = 1.f / float(geo.out_h);
        for (uint32_t y = 0; y < geo.out_h; y++) {
            const float ny = (y + .5f) * inv_h;
            auto *dst = &out.rgba[(size_t(y + f.top) * out.width + f.left) * 4];
            for (uint32_t x = 0; x < geo.out_w; x++, dst += 4) {
                double sx, sy;
                geo.sample(x, y, sx, sy);
                sx = std::clamp(sx, 0., double(w - 1));
                sy = std::clamp(sy, 0., double(h - 1));
                uint32_t x0 = uint32_t(sx), y0 = uint32_t(sy), x1 = std::min(x0 + 1, w - 1),
                         y1 = std::min(y0 + 1, h - 1);
                float fx = float(sx - x0), fy = float(sy - y0), c[3];
                const auto *p00 = pixels + (size_t(y0) * stride + x0) * 4;
                const auto *p10 = pixels + (size_t(y0) * stride + x1) * 4;
                const auto *p01 = pixels + (size_t(y1) * stride + x0) * 4;
                const auto *p11 = pixels + (size_t(y1) * stride + x1) * 4;
                for (int k = 0; k < 3; k++) {
                    float a = p00[k] * (1 - fx) + p10[k] * fx;
                    float b = p01[k] * (1 - fx) + p11[k] * fx;
                    c[k] = (a * (1 - fy) + b * fy) / 65535.f;
                }
                if (silver_edr) apply_silver_highlight_edr(c);
                grade.apply(c, (x + .5f) * inv_w, ny);
                dst[0] = quant(c[0]);
                dst[1] = quant(c[1]);
                dst[2] = quant(c[2]);
                dst[3] = 65535;
            }
        }
    }
    if (f.film != FilmBorder::None) paint_film_base(out, f);
    // The leak exposes the acetate before the perforations and optical lettering are applied.
    leak.apply(out, f.left, f.top, geo.out_w, geo.out_h, f.acetate());
    paper_caption(out, f, edits, geo.out_h);
    date(out, edits, f.left, f.top, geo.out_w, geo.out_h);
    if (f.film != FilmBorder::None) finish_film_border(out, f, edits, transparent_frame);
    return out;
}
spk::Json output_dimensions(uint32_t w, uint32_t h, const spk::Json &edits) {
    Geometry geo(edits, w, h);
    FrameLayout frame(geo.out_w, geo.out_h, edits);
    spk::Json j = spk::Json::object();
    j.set("width", spk::Json(double(frame.width)));
    j.set("height", spk::Json(double(frame.height)));
    return j;
}
void write_image(const Image16 &im, const std::string &path, const std::string &format,
                 const std::string &resources, const spk::Json &edits) {
    std::vector<uint8_t> icc, out;
    std::string error;
    if (!spkhost::icc_for("sRGB", std::filesystem::path(resources), icc, error))
        throw std::runtime_error(error);
    spkhost::Exif exif;
    exif.make = edits.at("source_make").as_string();
    exif.model = edits.at("source_model").as_string();
    exif.datetime_original = edits.at("source_datetime").as_string();
    exif.iso = edits.at("source_iso").as_double();
    exif.aperture = edits.at("source_aperture").as_double();
    exif.shutter_s = edits.at("source_shutter").as_double();
    exif.focal_mm = edits.at("source_focal_mm").as_double();
    const double equivalent = edits.at("source_focal_35mm").as_double();
    if (equivalent >= 1 && equivalent <= std::numeric_limits<uint16_t>::max())
        exif.focal_35mm = uint16_t(std::lround(equivalent));
    if (format == "tiff") {
        std::vector<uint16_t> rgb(size_t(im.width) * im.height * 3);
        for (size_t i = 0; i < rgb.size() / 3; i++)
            for (int k = 0; k < 3; k++)
                rgb[i * 3 + k] = im.rgba[i * 4 + k];
        if (!spkhost::encode_tiff(rgb.data(), im.width, im.height, 16, icc, out, error, &exif))
            throw std::runtime_error(error);
    } else if (format == "png") {
        size_t stride = size_t(im.width) * 6;
        std::vector<uint8_t> raw((stride + 1) * im.height);
        for (uint32_t y = 0; y < im.height; y++)
            for (uint32_t x = 0; x < im.width; x++)
                for (int k = 0; k < 3; k++) {
                    uint16_t v = im.rgba[(size_t(y) * im.width + x) * 4 + k];
                    size_t p = size_t(y) * (stride + 1) + 1 + x * 6 + k * 2;
                    raw[p] = uint8_t(v >> 8);
                    raw[p + 1] = uint8_t(v);
                }
        uLongf count = compressBound(raw.size());
        std::vector<uint8_t> compressed(count);
        int status = compress2(compressed.data(), &count, raw.data(), raw.size(), 3);
        if (status != Z_OK)
            throw std::runtime_error("PNG16 compression failed");
        out = {137, 80, 78, 71, 13, 10, 26, 10};
        uint8_t header[13] = {uint8_t(im.width >> 24),
                              uint8_t(im.width >> 16),
                              uint8_t(im.width >> 8),
                              uint8_t(im.width),
                              uint8_t(im.height >> 24),
                              uint8_t(im.height >> 16),
                              uint8_t(im.height >> 8),
                              uint8_t(im.height),
                              16,
                              2,
                              0,
                              0,
                              0};
        png_chunk(out, "IHDR", header, 13);
        uint8_t intent = 0;
        png_chunk(out, "sRGB", &intent, 1);
        auto block = spkhost::exif_block(exif, im.width, im.height);
        png_chunk(out, "eXIf", block.data(), block.size());
        png_chunk(out, "IDAT", compressed.data(), count);
        png_chunk(out, "IEND", nullptr, 0);
    } else {
        std::vector<uint8_t> rgb(size_t(im.width) * im.height * 3);
        for (size_t i = 0; i < rgb.size() / 3; i++)
            for (int k = 0; k < 3; k++)
                rgb[i * 3 + k] = uint8_t((uint32_t(im.rgba[i * 4 + k]) + 128) / 257);
        if (!spkhost::encode_jpeg(rgb.data(), im.width, im.height,
                                  int(num(edits, "export_quality", 96, 70, 100)), icc, out, error,
                                  &exif))
            throw std::runtime_error(error);
    }
    if (!spkhost::write_file_atomic(std::filesystem::path(path), out, false, error))
        throw std::runtime_error(error);
}
} // namespace mobile
