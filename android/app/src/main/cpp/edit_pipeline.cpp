// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
// Android scan-side edits. Tone order follows SpektraLab Canvas/Shaders.metal;
// this mobile path grades encoded sRGB, using the sRGB mid-grey pivot.
#include "edit_pipeline.hpp"
#include "output.hpp"
#include "platform.hpp"
#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <fstream>
#include <limits>
#include <stdexcept>
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
uint16_t quant(float x) { return uint16_t(std::lround(sat(x) * 65535.f)); }

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
        float pos = tonepos(luma(c)), ws = (1 - pos) * (1 - pos), wh = pos * pos;
        for (int k = 0; k < 3; k++) {
            c[k] += shadows * .25f * ws * (1 - c[k]);
            c[k] += highlights * .25f * wh * (highlights > 0 ? 1 - c[k] : c[k]);
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

struct FrameLayout {
    uint32_t margin = 0, bottom = 0, width = 0, height = 0;
    std::string style;
    FrameLayout(uint32_t w, uint32_t h, const spk::Json &j) {
        style = j.at("edge_style").as_string();
        if (style.empty())
            style = "none";
        if (style != "none")
            margin = std::max(
                2u, uint32_t(std::lround(std::min(w, h) * num(j, "edge_amount", 6, 2, 15) / 100)));
        bottom = style == "instant" ? margin * 3 : margin;
        width = w + margin * 2;
        height = h + margin + bottom;
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
void date(Image16 &im, const spk::Json &j, int ix, int iy, int iw, int ih) {
    if (!j.at("date_enabled").as_bool())
        return;
    std::string value = j.at("date_text").as_string();
    if (value.empty())
        return;
    value = value.substr(0, 24);
    int size =
        std::max(1, int(std::lround(std::min(iw, ih) * .004f * num(j, "date_size", 1, .5, 3))));
    int inset = std::max(4, int(std::min(iw, ih) * .035f));
    size = std::max(1, std::min(size, (iw - 2 * inset) / std::max(1, int(value.size()) * 6)));
    int width = int(value.size()) * size * 6, height = size * 7;
    std::string corner = j.at("date_corner").as_string();
    int x = corner.find("left") != std::string::npos ? ix + inset : ix + iw - inset - width;
    int y = corner.find("top") != std::string::npos ? iy + inset : iy + ih - inset - height;
    float c[3] = {1, .40f, .08f};
    if (j.at("date_color").as_string() == "white")
        c[1] = c[2] = .94f;
    float a = num(j, "date_intensity", 1, .1, 2) * .8f;
    text(im, value, x - size, y, size, c, std::min(.2f, a * .16f));
    text(im, value, x + size, y, size, c, std::min(.2f, a * .16f));
    text(im, value, x, y, size, c, std::min(.96f, a));
}
void borders(Image16 &im, const FrameLayout &f, const spk::Json &j, uint32_t iw, uint32_t ih) {
    if (f.margin == 0)
        return;
    if (f.style == "135_black" || f.style == "135_warm") {
        float holes[3] = {.83f, .83f, .80f};
        int m = int(f.margin), a = std::max(2, int(m * .42f)), b = std::max(2, int(m * .45f)),
            step = std::max(4, int(m * .86f));
        if (iw >= ih) {
            for (int x = m / 2; x < int(im.width) - a; x += step) {
                rect(im, x, m / 5, a, b, holes);
                rect(im, x, int(im.height) - m * 3 / 4, a, b, holes);
            }
        } else {
            for (int y = m / 2; y < int(im.height) - a; y += step) {
                rect(im, m / 5, y, b, a, holes);
                rect(im, int(im.width) - m * 3 / 4, y, b, a, holes);
            }
        }
        int size = std::max(1, m / 24);
        float ink[3] = {.82f, .56f, .19f};
        std::string label = j.at("film_label").as_string();
        if (label.empty())
            label = "SPEKTRA FILM";
        label += "  " + std::to_string(std::clamp(j.at("edge_frame_number").as_int(36), 0, 99));
        text(im, label, m, int(im.height) - std::max(7 * size + 2, m / 4), size, ink, .95f);
    }
    if (f.style == "instant") {
        std::string caption = j.at("edge_caption").as_string();
        if (!caption.empty()) {
            int size = std::max(1, int(f.margin) / 13);
            float gray[3] = {.22f, .22f, .22f};
            text(im, caption.substr(0, 42), int(f.margin), int(f.margin + ih + f.bottom / 2), size,
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
    turns = ((j.at("geo_turns").as_int() % 4) + 4) % 4;
    flip_h = j.at("geo_flip_h").as_bool();
    flip_v = j.at("geo_flip_v").as_bool();
    out_w = turns % 2 ? crop_h : crop_w;
    out_h = turns % 2 ? crop_w : crop_h;
    fit =
        std::max(std::fabs(std::cos(angle)) + std::fabs(std::sin(angle)) * double(crop_h) / crop_w,
                 std::fabs(std::cos(angle)) + std::fabs(std::sin(angle)) * double(crop_w) / crop_h);
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
    sx = x + crop_w * .5 + std::cos(angle) * dx + std::sin(angle) * dy - .5;
    sy = y + crop_h * .5 - std::sin(angle) * dx + std::cos(angle) * dy - .5;
}
Image16 apply_edits(const uint16_t *pixels, uint32_t w, uint32_t h, uint32_t stride,
                    const spk::Json &edits) {
    if (!w || !h || !pixels)
        throw std::runtime_error("No rendered pixels");
    Geometry geo(edits, w, h);
    Grade grade(edits);
    FrameLayout f(geo.out_w, geo.out_h, edits);
    if (uint64_t(f.width) * f.height > 300000000ull)
        throw std::runtime_error("Edited output exceeds 300 megapixels");
    Image16 out;
    out.width = f.width;
    out.height = f.height;
    out.rgba.resize(size_t(f.width) * f.height * 4);
    float base[3] = {.008f, .008f, .008f};
    if (f.style == "135_warm") {
        base[0] = .10f;
        base[1] = .055f;
        base[2] = .02f;
    } else if (f.style == "clean_white" || f.style == "instant")
        base[0] = base[1] = base[2] = .96f;
    for (size_t i = 0; i < out.rgba.size(); i += 4) {
        for (int k = 0; k < 3; k++)
            out.rgba[i + k] = quant(base[k]);
        out.rgba[i + 3] = 65535;
    }
    for (uint32_t y = 0; y < geo.out_h; y++)
        for (uint32_t x = 0; x < geo.out_w; x++) {
            double sx, sy;
            geo.sample(x, y, sx, sy);
            sx = std::clamp(sx, 0., double(w - 1));
            sy = std::clamp(sy, 0., double(h - 1));
            uint32_t x0 = uint32_t(sx), y0 = uint32_t(sy), x1 = std::min(x0 + 1, w - 1),
                     y1 = std::min(y0 + 1, h - 1);
            float fx = float(sx - x0), fy = float(sy - y0), c[3];
            for (int k = 0; k < 3; k++) {
                float a = pixels[(size_t(y0) * stride + x0) * 4 + k] * (1 - fx) +
                          pixels[(size_t(y0) * stride + x1) * 4 + k] * fx;
                float b = pixels[(size_t(y1) * stride + x0) * 4 + k] * (1 - fx) +
                          pixels[(size_t(y1) * stride + x1) * 4 + k] * fx;
                c[k] = (a * (1 - fy) + b * fy) / 65535.f;
            }
            grade.apply(c, (x + .5f) / geo.out_w, (y + .5f) / geo.out_h);
            auto *p = &out.rgba[(size_t(y + f.margin) * out.width + x + f.margin) * 4];
            for (int k = 0; k < 3; k++)
                p[k] = quant(c[k]);
        }
    borders(out, f, edits, geo.out_w, geo.out_h);
    date(out, edits, int(f.margin), int(f.margin), int(geo.out_w), int(geo.out_h));
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
