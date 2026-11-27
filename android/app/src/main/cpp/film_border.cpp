// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
#include "film_border.hpp"
#include "stb_image.h"
#define STB_TRUETYPE_IMPLEMENTATION
#include "stb_truetype.h"
#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <string_view>
#include <unordered_map>

namespace mobile {
namespace {
using Color = std::array<float, 3>;
struct Point { float x, y; };

constexpr Color backing = {223 / 255.f, 216 / 255.f, 204 / 255.f};
constexpr Color amber = {.95f, .65f, .32f};
constexpr Color silver = {.85f, .89f, .94f};
constexpr Color olive = {.78f, .68f, .47f};

FilmBorder film_style(std::string_view id) {
    if (id == "135_black") return FilmBorder::Black135;
    if (id == "135_warm") return FilmBorder::Warm135;
    if (id == "135_bw_silver") return FilmBorder::Silver135;
    if (id == "slide_mount") return FilmBorder::Slide;
    if (id == "120_medium") return FilmBorder::Medium120;
    if (id == "4x5_sheet") return FilmBorder::Sheet;
    return FilmBorder::None;
}

bool strip(FilmBorder film) {
    return film == FilmBorder::Black135 || film == FilmBorder::Warm135
        || film == FilmBorder::Silver135;
}

float unit(float value) { return std::clamp(value, 0.f, 1.f); }
uint16_t channel(float value) { return uint16_t(std::lround(unit(value) * 65535.f)); }

uint16_t *pixel(Image16 &image, int x, int y) {
    return &image.rgba[(size_t(y) * image.width + x) * 4];
}

void tint(uint16_t *p, const Color &rgb, float opacity) {
    for (int k = 0; k < 3; ++k)
        p[k] = channel(std::lerp(p[k] / 65535.f, rgb[k], opacity));
}

float rounded_box(float x, float y, float half_width, float half_height, float radius) {
    const float dx = std::abs(x) - half_width + radius;
    const float dy = std::abs(y) - half_height + radius;
    const float ox = std::max(0.f, dx), oy = std::max(0.f, dy);
    return std::sqrt(ox * ox + oy * oy) + std::min(0.f, std::max(dx, dy)) - radius;
}

float grain(int x, int y) {
    uint32_t bits = uint32_t(x) * 0x9e3779b9u ^ uint32_t(y) * 0x85ebca6bu;
    bits = (bits ^ (bits >> 16)) * 0x7feb352du;
    return float(bits >> 24) / 255.f - .5f;
}

struct BorderPainter {
    Image16 &image;
    const bool rotated;
    const int width, height;

    BorderPainter(Image16 &image, bool rotated = false)
        : image(image), rotated(rotated), width(rotated ? image.height : image.width),
          height(rotated ? image.width : image.height) {}

    // Keep the strip decoration in landscape coordinates. A portrait photo rotates only the
    // strip artwork, independently of the image's crop/rotation.
    uint16_t *at(int u, int v) const {
        return rotated ? pixel(image, height - 1 - v, u) : pixel(image, u, v);
    }

    template <typename Draw>
    void sample(float x0, float y0, float x1, float y1, const Draw &draw) const {
        const int left = std::max(0, int(std::floor(x0)));
        const int top = std::max(0, int(std::floor(y0)));
        const int right = std::min(width, int(std::ceil(x1)));
        const int bottom = std::min(height, int(std::ceil(y1)));
        for (int y = top; y < bottom; ++y)
            for (int x = left; x < right; ++x)
                draw(at(x, y), x + .5f, y + .5f);
    }

    void bar(float x, float y, float w, float h, const Color &ink, float opacity) const {
        sample(x, y, x + w, y + h, [&](uint16_t *p, float u, float v) {
            const float dx = std::min(u + .5f, x + w) - std::max(u - .5f, x);
            const float dy = std::min(v + .5f, y + h) - std::max(v - .5f, y);
            tint(p, ink, opacity * unit(dx) * unit(dy));
        });
    }

    template <typename Distance>
    void cut(float x, float y, float half_w, float half_h, float rim, const Distance &distance) const {
        sample(x - half_w - rim - 1, y - half_h - rim - 1,
               x + half_w + rim + 1, y + half_h + rim + 1,
               [&](uint16_t *p, float u, float v) {
            const float d = distance(u - x, v - y);
            if (d >= rim + .5f) return;
            const float keep = unit(d + .5f);
            p[3] = uint16_t(std::lround(p[3] * keep));
            const float light = unit(1.f - std::max(0.f, d) / rim);
            tint(p, backing, .25f * light * keep);
        });
    }

    void opening(float x, float y, float w, float h, float radius, float rim) const {
        cut(x, y, w * .5f, h * .5f, rim, [&](float u, float v) {
            return rounded_box(u, v, w * .5f, h * .5f, radius);
        });
    }
};

// Single-stroke edge lettering. Each path encodes points on a 4-by-6 grid, with
// '/' lifting the pen. Optical coverage and the halo are sampled at output resolution.
constexpr char letters[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-./";
constexpr std::string_view strokes[] = {
    "06 20 46/14 34", "06 00 30 41 42 33 03/33 44 45 36 06", "41 30 10 01 05 16 36 45",
    "06 00 20 41 45 26 06", "40 00 06 46/03 33", "40 00 06/03 33",
    "41 30 10 01 05 16 36 45 43 23", "00 06/40 46/03 43",
    "10 30/20 26/16 36", "40 45 36 16 05", "00 06/40 03 46", "00 06 46",
    "06 00 23 40 46", "06 00 46 40", "10 30 41 45 36 16 05 01 10",
    "06 00 30 41 42 33 03", "10 30 41 45 36 16 05 01 10/24 46",
    "06 00 30 41 42 33 03/23 46", "41 30 10 01 02 13 33 44 45 36 16 05",
    "00 40/20 26", "00 05 16 36 45 40", "00 26 40", "00 16 23 36 40",
    "00 46/40 06", "00 23 40/23 26", "00 40 06 46",
    "10 30 41 45 36 16 05 01 10/14 32", "11 20 26/16 36",
    "01 10 30 41 42 06 46", "00 30 41 32 23 33 44 45 36 16 05",
    "30 04 44/30 36", "40 00 03 33 44 45 36 16 05",
    "41 30 10 01 05 16 36 45 44 33 13 03", "00 40 16",
    "13 02 01 10 30 41 42 33 13 04 05 16 36 45 44 33",
    "43 33 13 02 01 10 30 41 45 36 16 05", "13 33", "26 26", "06 40"};
static_assert(std::size(strokes) + 1 == std::size(letters));

struct Segment {
    Point a, b;
    float distance(Point p) const {
        const float dx = b.x - a.x, dy = b.y - a.y;
        const float t = unit(((p.x - a.x) * dx + (p.y - a.y) * dy)
                             / std::max(.0001f, dx * dx + dy * dy));
        const float px = p.x - a.x - t * dx, py = p.y - a.y - t * dy;
        return px * px + py * py;
    }
};

void glyph(const BorderPainter &p, std::string_view line, float x, float y, float h,
           const Color &color, float opacity, bool glow) {
    std::array<Segment, 16> segments{};
    size_t count = 0;
    for (size_t i = 0; i + 4 < line.size(); i += 3) {
        if (line[i + 2] == '/') continue;
        auto point = [&](size_t pos) {
            return Point{x + (line[pos] - '0') * h * .163f, y + (line[pos + 1] - '0') * h / 6};
        };
        segments[count++] = {point(i), point(i + 3)};
    }
    const float radius = .075f * h;
    const float blur = glow ? h * .12f : 0.f;
    const float pad = radius + blur + .5f;
    p.sample(x - pad, y - pad, x + .652f * h + pad, y + h + pad,
             [&](uint16_t *target, float u, float v) {
        float squared = pad * pad;
        for (size_t n = 0; n < count; ++n)
            squared = std::min(squared, segments[n].distance({u, v}));
        const float d = std::sqrt(squared);
        const float core = unit(radius + .5f - d);
        const float bloom = glow ? .27f * unit(1.f - (d - radius) / blur) * (1.f - core) : 0.f;
        tint(target, color, (core + bloom) * opacity);
    });
}

void imprint(const BorderPainter &p, std::string_view label, float x, float cy, float h,
             float max_width, const Color &color, float opacity, bool right = false,
             bool glow = true) {
    if (label.empty() || max_width <= 0.f) return;
    h = std::min(h, max_width / (.9f * label.size()));
    if (h < .75f) return;
    if (right) x -= .9f * h * label.size();
    for (unsigned char ch : label) {
        const char *found = std::strchr(letters, std::toupper(ch));
        if (ch && found) glyph(p, strokes[found - letters], x, cy - .5f * h, h, color, opacity, glow);
        x += .9f * h;
    }
}

void decorate_strip(Image16 &image, const FrameLayout &f, const std::string &label,
                    const std::string &number) {
    const bool rotate = f.photo_height > f.photo_width;
    const bool bw = f.film == FilmBorder::Silver135;
    const BorderPainter p(image, rotate);
    const float rail = rotate ? f.left : f.top;
    const float end = rotate ? f.top : f.left;
    const float length = rotate ? f.photo_height : f.photo_width;
    const float pitch = length / 8.f;
    const float w = std::min(.39f * rail, .42f * pitch), h = .52f * rail;
    for (int side = 0; side < 2; ++side) {
        const float row = side == 0 ? .43f * rail : p.height - .43f * rail;
        for (int i = 0; i < 8; ++i)
            p.opening(end + (i + .5f) * pitch, row, w, h, w * .24f, std::max(.55f, rail * .047f));
    }

    const Color mark = bw ? silver : amber;
    constexpr std::string_view dx = "1011010011101011001011010100110110101";
    const float step = length / 58.f;
    if (bw) {
        for (int i = 0; i < 12; ++i)
            p.bar(end + (i + 1) * length / 14.f, .082f * rail, (i % 3 == 0 ? 2.f : .65f) * step,
                  .06f * rail, mark, .52f);
    } else {
        // A decorative two-track edge code, using an original pattern.
        for (size_t i = 0; i < dx.size(); ++i) {
            const float x = end + (i + 2) * step;
            if (i % 2 == 0) p.bar(x, .035f * rail, .51f * step, .046f * rail, mark, .86f);
            if (dx[i] == '1') p.bar(x, .095f * rail, .66f * step, .063f * rail, mark, .88f);
        }
    }

    const float text_h = std::min(.182f * rail, length / 100.f);
    const float inset = end + length * .027f;
    const float inner = .837f * rail;
    const std::string half_frame = number + "A";
    imprint(p, number, inset, inner, text_h, length * .15f, mark, .82f);
    imprint(p, half_frame, inset + .55f * length, inner, text_h, length * .15f, mark, .86f);
    imprint(p, label, inset, p.height - inner, text_h, length * .73f, mark, .9f);
    imprint(p, half_frame, p.width - inset, p.height - inner, text_h, length * .16f, mark, .86f, true);
}

void decorate_slide(const BorderPainter &p, const FrameLayout &f, const std::string &number) {
    const float pad = std::min({f.left, f.right, f.top, f.bottom});
    const float rim_w = std::max(1.f, pad * .048f);
    const float lip = std::max(1.f, pad * .27f);
    const float groove_end = lip + 2.4f * rim_w;
    const float bevel = std::max(1.f, .19f * pad);
    const float band = std::max(0.f, f.top - bevel - groove_end);
    const float em = std::min(pad * .20f, band * .45f);
    const float half_glyph = .575f * em;
    const float highlight_shift = std::max(.65f, em * .095f);
    const float min_center = groove_end + half_glyph + highlight_shift + std::max(1.f, .06f * pad);
    const float mid_center = groove_end + .5f * band;
    const float y_top = std::min(mid_center, std::max(.43f * f.top, min_center));
    const float y_bot = p.height - y_top;
    const float r = std::min(pad * .105f, em * .56f);
    const float inset = std::max(pad * .53f, groove_end + std::max(1.f, .09f * pad));
    const float dot_x = inset + r;
    constexpr Color stamp = {.66f, .23f, .17f};
    constexpr Color deboss = {.43f, .39f, .34f};
    constexpr Color highlight = {.99f, .97f, .93f};
    imprint(p, "SPEKTRA", inset, y_top, em, p.width * .32f, stamp, .8f, false, false);
    imprint(p, number, p.width - inset, y_top, em, p.width * .16f, deboss, .78f, true, false);

    p.sample(dot_x - r - 1.f, y_bot - r - 1.f, dot_x + r + 1.f, y_bot + r + 1.f,
             [&](uint16_t *target, float u, float v) {
        const float d = std::hypot(u - dot_x, v - y_bot);
        tint(target, stamp, .92f * unit(r + .5f - d));
    });
    imprint(p, "E-6 SLIDE", p.width - inset, y_bot + highlight_shift,
            em, p.width * .45f, highlight, .94f, true, false);
    imprint(p, "E-6 SLIDE", p.width - inset, y_bot, em, p.width * .45f, deboss, .67f, true, false);
}

void decorate_medium(const BorderPainter &p, const FrameLayout &f, const std::string &label,
                     const std::string &number) {
    const float inset = f.left + f.photo_width * .025f;
    const float y = .51f * f.top, em = f.top * .29f;
    imprint(p, number, inset, y, em, f.photo_width * .18f, olive, .9f);
    imprint(p, number + "A", p.width - inset, y, em, f.photo_width * .18f, olive, .86f, true);
    for (int i = 0; i < 8; ++i) {
        const float x = f.left + f.photo_width * (.24f + .067f * i);
        p.bar(x, y, em * (i % 3 == 0 ? .65f : .21f), em * .14f, olive, .52f);
    }
    imprint(p, label, inset, p.height - .48f * f.bottom, em, f.photo_width * .55f, olive, .86f);
    imprint(p, "120", p.width - inset, p.height - .48f * f.bottom, em, f.photo_width * .19f,
            olive, .77f, true);
}

void decorate_sheet(const BorderPainter &p, const FrameLayout &f, const std::string &label,
                    const std::string &number) {
    const float pitch = std::min(f.top * .58f, p.width * .067f);
    const float right = p.width - std::max(pitch, f.right * .65f);
    const float radius = pitch * .26f;
    const float depth = pitch * .90f;
    const float rim = std::max(.55f, pitch * .10f);
    p.opening(right - 2.f * pitch, .06f * depth, 2 * radius, 1.6f * depth, radius, rim);
    p.opening(right, .06f * depth, 2 * radius, 1.6f * depth, radius, rim);
    const float len2 = std::max(1e-4f, radius * radius + depth * depth);
    p.cut(right - pitch, 0.f, pitch * .5f, depth, rim, [&](float x, float y) {
        const float ax = std::abs(x), dy = y - depth;
        const float t = std::max(0.f, (ax * radius - dy * depth) / len2);
        const float dist = std::hypot(ax - t * radius, dy + t * depth);
        return (ax * depth + dy * radius) < 0.f ? -dist : dist;
    });
    const float pin = pitch * .35f;
    p.opening(f.left * .9f, f.top * .41f, pin, pin, pin * .5f, rim);

    const float y = p.height - .5f * f.bottom;
    const float em = f.bottom * .25f;
    const float inset = f.left + f.photo_width * .022f;
    imprint(p, "4X5 / " + label, inset, y, em, f.photo_width * .68f, silver, .72f);
    imprint(p, number, p.width - inset, y, em, f.photo_width * .14f, silver, .61f, true);
}

template <typename Paint>
void each_rebate_pixel(Image16 &image, const FrameLayout &f, int inner, const Paint &paint) {
    const int right = f.left + f.photo_width, bottom = f.top + f.photo_height;
    for (int y = 0; y < int(image.height); ++y) {
        if (y < f.top + inner || y >= bottom - inner || f.photo_width <= 2 * inner) {
            for (int x = 0; x < int(image.width); ++x) paint(pixel(image, x, y), x, y);
        } else {
            for (int x = 0; x < f.left + inner; ++x) paint(pixel(image, x, y), x, y);
            for (int x = right - inner; x < int(image.width); ++x) paint(pixel(image, x, y), x, y);
        }
    }
}
} // namespace

FrameLayout::FrameLayout(uint32_t w, uint32_t h, const spk::Json &j)
    : photo_width(w), photo_height(h), style(j.at("edge_style").as_string()), film(film_style(style)) {
    if (style.empty()) style = "none";
    if (!identity()) {
        const int short_edge = std::min(w, h), long_edge = std::max(w, h);
        const float amount = std::clamp(float(j.at("edge_amount").as_double(6)), 2.f, 15.f);
        const int margin = std::max(2, int(std::lround(short_edge * amount / 100.f)));
        left = right = top = bottom = margin;
        if (strip(film)) {
            const int end = std::max(1, int(std::lround(margin * .56)));
            const int rail = int(std::lround(margin * 1.82));
            left = right = w >= h ? end : rail;
            top = bottom = w >= h ? rail : end;
        } else if (film == FilmBorder::Slide) {
            const int pad = std::max(2, int(std::lround(short_edge * (.17f + .019f * amount))));
            // A square mount for ordinary photos. Cap the extra matte for tall/panoramic sources.
            const int balance = std::min(long_edge - short_edge, short_edge * 9 / 10);
            left = right = top = bottom = pad;
            if (w < h) {
                left += balance / 2;
                right += (balance + 1) / 2;
            } else {
                top += balance / 2;
                bottom += (balance + 1) / 2;
            }
        } else if (film == FilmBorder::Medium120) {
            left = right = std::max(1, int(std::lround(margin * .82f)));
            top = bottom = int(std::lround(margin * 1.6f));
        } else if (film == FilmBorder::Sheet) {
            top = int(std::lround(margin * 1.45f));
            bottom = int(std::lround(margin * 1.12f));
        } else if (style == "instant") {
            bottom = top * 3;
        } else if (style == "film_card_white") {
            const bool landscape = w >= h;
            const int pad = std::max(4, int(std::lround(short_edge * (landscape ? .048f : .050f))));
            left = right = top = pad;
            bottom = std::max(16, int(std::lround(short_edge * (landscape ? .146f : .152f))));
        } else if (style == "film_card_black") {
            const bool landscape = w >= h;
            if (landscape) {
                const int pad = std::max(4, int(std::lround(short_edge * .038f)));
                const int gap_mid = std::max(4, int(std::lround(short_edge * .036f)));
                const int side_w = std::max(28, int(std::lround(w * .175f)));
                left = top = bottom = pad;
                right = gap_mid + side_w + pad;
            } else {
                const int pad = std::max(4, int(std::lround(short_edge * .046f)));
                left = right = top = pad;
                bottom = std::max(18, int(std::lround(short_edge * .160f)));
            }
        }
    }
    width = w + left + right;
    height = h + top + bottom;
}

void paint_film_base(Image16 &image, const FrameLayout &f) {
    const bool slide = f.film == FilmBorder::Slide;
    const bool medium = f.film == FilmBorder::Medium120;
    const bool translucent = f.film == FilmBorder::Silver135;
    const float short_edge = std::min(f.photo_width, f.photo_height);
    const float pad = std::min({f.left, f.right, f.top, f.bottom});
    const float bevel = std::max(1.f, .19f * pad);
    const float gate_radius = short_edge * (slide ? .016f : .009f);
    const float corner_radius = std::max(.5f, pad * (slide ? .23f : .13f));
    const float rim = slide ? bevel : std::max(1.f, short_edge * .002f);
    const float half_w = .5f * f.photo_width, half_h = .5f * f.photo_height;
    const float outer_w = image.width * .5f, outer_h = image.height * .5f;
    const Color color = slide ? Color{.896f, .879f, .839f}
        : translucent ? Color{.125f, .142f, .167f}
        : f.film == FilmBorder::Warm135 ? Color{.143f, .088f, .064f}
        : f.film == FilmBorder::Black135 ? Color{.077f, .068f, .061f}
        : Color{.061f, .059f, .065f};

    each_rebate_pixel(image, f, int(std::ceil(gate_radius + rim + 2)),
                      [&](uint16_t *p, int x, int y) {
        const float u = x + .5f - f.left - half_w, v = y + .5f - f.top - half_h;
        const bool inside = x >= f.left && x < f.left + f.photo_width
                         && y >= f.top && y < f.top + f.photo_height;
        const float gate = rounded_box(u, v, half_w, half_h, gate_radius);
        if (inside && gate < -rim) return;

        const float ax = x + .5f - outer_w, ay = y + .5f - outer_h;
        const float edge = rounded_box(ax, ay, outer_w, outer_h, corner_radius);
        const float mottling = grain(x, y) * (slide ? .027f : .022f);
        const float face = -.012f * ax / outer_w - .015f * ay / outer_h;
        float sheen = 0.f;
        if (slide) {
            const float rim_w = std::max(1.f, pad * .048f);
            const float lip = std::max(1.f, pad * .27f);
            const float groove = unit(1.f - std::abs(-edge - lip) / rim_w);
            const float highlight = unit(1.f - std::abs(-edge - lip - 1.4f * rim_w) / rim_w);
            sheen = -.056f * groove + .028f * highlight;
        } else {
            sheen = .059f * unit(1.f + edge / std::max(1.f, .11f * pad));
        }
        Color base = color;
        for (float &c : base) c += face + mottling + sheen;

        const float shade = unit(1.f - std::abs(gate) / rim);
        if (slide && !inside && gate < bevel) {
            // Recessed window bevel. The lower and right faces catch the light.
            const float facing = std::abs(u) / half_w > std::abs(v) / half_h ? u / half_w : v / half_h;
            for (float &c : base) c += shade * (.08f * facing - .25f * shade);
        }
        if (inside) {
            const float cover = unit(.5f + gate);
            if (cover > 0.f) tint(p, base, cover);
            if (shade > 0.f)
                tint(p, Color{.018f, .016f, .016f}, shade * (slide ? .36f : .24f));
        } else {
            tint(p, base, 1.f);
            const float opacity = translucent ? .76f + .06f * (x / float(image.width)) : 1.f;
            p[3] = channel(opacity * unit(.5f - edge));
        }
    });

    if (medium) {
        // A camera gate exposes two small wedges of the emulsion beside the rectangular image.
        // Continue the leftmost photo pixels into the wedges; the strip itself has no holes.
        const float depth = .34f * f.left;
        const float half = std::max(1.f, short_edge * .010f);
        const BorderPainter p(image);
        for (float row : {.145f, .205f}) {
            const float cy = f.top + f.photo_height * row;
            p.sample(f.left - depth - 1.f, cy - half - 1.f, f.left, cy + half + 1.f,
                     [&](uint16_t *target, float x, float y) {
                const float d = depth * unit(1.f - std::abs(y - cy) / half);
                const float coverage = unit(x - f.left + d + .5f);
                const auto *from = pixel(image, f.left, int(y));
                for (int k = 0; k < 3; ++k)
                    target[k] = uint16_t(std::lround(std::lerp(float(target[k]), float(from[k]), coverage)));
            });
        }
    }
}

void finish_film_border(Image16 &image, const FrameLayout &f, const spk::Json &edits,
                        bool transparent) {
    std::string label = edits.at("film_label").as_string().substr(0, 40);
    if (label.empty()) label = "SPEKTRA";
    const std::string number = std::to_string(std::clamp(edits.at("edge_frame_number").as_int(36), 0, 99));
    const BorderPainter p(image);
    if (strip(f.film)) decorate_strip(image, f, label, number);
    else if (f.film == FilmBorder::Slide) decorate_slide(p, f, number);
    else if (f.film == FilmBorder::Medium120) decorate_medium(p, f, label, number);
    else if (f.film == FilmBorder::Sheet) decorate_sheet(p, f, label, number);

    image.has_alpha = transparent;
    if (!transparent) {
        each_rebate_pixel(image, f, 0, [](uint16_t *p, int, int) {
            if (p[3] == 65535) return;
            tint(p, backing, 1.f - p[3] / 65535.f);
            p[3] = 65535;
        });
    }
}

namespace {
struct FilmCardSpec {
    const char *stock_id;
    const char *brand;
    const char *model;
    const char *full;
    const char *sub;
    const char *iso;
    const char *type;
    const char *proc;
};

constexpr FilmCardSpec kFilmCardSpecs[] = {
    {"fujifilm_c200",                "FUJIFILM",  "C200",          "FUJIFILM C200",          "COLOR NEGATIVE / 35MM",  "200",  "NEGATIVE", "C-41"},
    {"fujifilm_pro_400h",            "FUJIFILM",  "PRO 400H",      "FUJIFILM PRO 400H",      "COLOR NEGATIVE / 35MM",  "400",  "NEGATIVE", "C-41"},
    {"fujifilm_xtra_400",            "FUJIFILM",  "X-TRA 400",     "FUJIFILM X-TRA 400",     "COLOR NEGATIVE / 35MM",  "400",  "NEGATIVE", "C-41"},
    {"kodak_ektar_100",              "KODAK",     "EKTAR 100",     "KODAK EKTAR 100",        "COLOR NEGATIVE / 35MM",  "100",  "NEGATIVE", "C-41"},
    {"kodak_gold_200",               "KODAK",     "GOLD 200",      "KODAK GOLD 200",         "COLOR NEGATIVE / 35MM",  "200",  "NEGATIVE", "C-41"},
    {"kodak_portra_160",             "KODAK",     "PORTRA 160",    "KODAK PORTRA 160",       "COLOR NEGATIVE / 35MM",  "160",  "NEGATIVE", "C-41"},
    {"kodak_portra_400",             "KODAK",     "PORTRA 400",    "KODAK PORTRA 400",       "COLOR NEGATIVE / 35MM",  "400",  "NEGATIVE", "C-41"},
    {"kodak_portra_800",             "KODAK",     "PORTRA 800",    "KODAK PORTRA 800",       "COLOR NEGATIVE / 35MM",  "800",  "NEGATIVE", "C-41"},
    {"kodak_portra_800_push1",       "KODAK",     "PORTRA 800 +1", "KODAK PORTRA 800 +1",    "PUSH +1 / COLOR NEGATIVE","1600","PUSH +1",  "C-41"},
    {"kodak_portra_800_push2",       "KODAK",     "PORTRA 800 +2", "KODAK PORTRA 800 +2",    "PUSH +2 / COLOR NEGATIVE","3200","PUSH +2",  "C-41"},
    {"kodak_ultramax_400",           "KODAK",     "ULTRAMAX 400",  "KODAK ULTRAMAX 400",     "COLOR NEGATIVE / 35MM",  "400",  "NEGATIVE", "C-41"},
    {"fujifilm_provia_100f",         "FUJIFILM",  "PROVIA 100F",   "FUJIFILM PROVIA 100F",   "COLOR REVERSAL / E-6",   "100",  "SLIDE",    "E-6"},
    {"fujifilm_velvia_100",          "FUJIFILM",  "VELVIA 100",    "FUJIFILM VELVIA 100",    "COLOR REVERSAL / E-6",   "100",  "SLIDE",    "E-6"},
    {"kodak_ektachrome_100",         "KODAK",     "EKTACHROME 100","KODAK EKTACHROME 100",   "COLOR REVERSAL / E-6",   "100",  "SLIDE",    "E-6"},
    {"kodak_kodachrome_64",          "KODAK",     "KODACHROME 64", "KODAK KODACHROME 64",    "COLOR REVERSAL / K-14",  "64",   "SLIDE",    "K-14"},
    {"cinestill_800t",               "CINESTILL", "800T",          "CINESTILL 800T",         "MOTION PICTURE / ECN-2", "800",  "TUNGSTEN", "ECN-2"},
    {"kodak_verita_200d",            "KODAK",     "VERITA 200D",   "KODAK VERITA 200D",      "MOTION PICTURE / ECN-2", "200",  "DAYLIGHT", "ECN-2"},
    {"kodak_vision3_50d",            "KODAK",     "VISION3 50D",   "KODAK VISION3 50D",      "MOTION PICTURE / ECN-2", "50",   "DAYLIGHT", "ECN-2"},
    {"kodak_vision3_200t",           "KODAK",     "VISION3 200T",  "KODAK VISION3 200T",     "MOTION PICTURE / ECN-2", "200",  "TUNGSTEN", "ECN-2"},
    {"kodak_vision3_250d",           "KODAK",     "VISION3 250D",  "KODAK VISION3 250D",     "MOTION PICTURE / ECN-2", "250",  "DAYLIGHT", "ECN-2"},
    {"kodak_vision3_500t",           "KODAK",     "VISION3 500T",  "KODAK VISION3 500T",     "MOTION PICTURE / ECN-2", "500",  "TUNGSTEN", "ECN-2"},
    {"fujifilm_neopan_acros_100_ii", "FUJIFILM",  "ACROS II",      "FUJIFILM ACROS II",      "BLACK & WHITE / SILVER", "100",  "B&W",      "SILVER"},
    {"ilford_hp5_plus_400",          "ILFORD",    "HP5 PLUS 400",  "ILFORD HP5 PLUS 400",    "BLACK & WHITE / SILVER", "400",  "B&W",      "SILVER"},
    {"kodak_tmax_100",               "KODAK",     "T-MAX 100",     "KODAK T-MAX 100",        "BLACK & WHITE / SILVER", "100",  "B&W",      "SILVER"},
    {"kodak_tri_x_400",              "KODAK",     "TRI-X 400",     "KODAK TRI-X 400",        "BLACK & WHITE / SILVER", "400",  "B&W",      "SILVER"},
};

const FilmCardSpec &film_card_spec(std::string_view stock_id) {
    for (const auto &spec : kFilmCardSpecs)
        if (stock_id == spec.stock_id) return spec;
    return kFilmCardSpecs[0];
}

struct CanisterRgba {
    int width = 0, height = 0;
    std::vector<uint8_t> rgba;
};

struct CardAssetCache {
    std::mutex mutex;
    std::string loaded_dir;
    std::vector<uint8_t> font_bytes;
    stbtt_fontinfo font{};
    bool font_ready = false;
    std::unordered_map<std::string, CanisterRgba> canisters;

    void ensure_font(const std::string &resources) {
        if (resources.empty()) return;
        if (loaded_dir == resources && font_ready) return;
        if (loaded_dir != resources) {
            canisters.clear();
            font_bytes.clear();
            font_ready = false;
            loaded_dir = resources;
        }
        const auto path = std::filesystem::path(resources) / "canisters" / "inter_card.ttf";
        std::ifstream input(path, std::ios::binary);
        if (!input) return;
        font_bytes.assign(std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>());
        if (!font_bytes.empty() && stbtt_InitFont(&font, font_bytes.data(), 0))
            font_ready = true;
    }

    const CanisterRgba *canister(const std::string &resources, const std::string &stock_id) {
        if (resources.empty()) return nullptr;
        ensure_font(resources);
        auto it = canisters.find(stock_id);
        if (it != canisters.end())
            return it->second.rgba.empty() ? nullptr : &it->second;
        if (canisters.size() >= 10) canisters.clear();
        CanisterRgba entry;
        const auto path = std::filesystem::path(resources) / "canisters" / (stock_id + ".png");
        int w = 0, h = 0, ch = 0;
        if (unsigned char *raw = stbi_load(path.string().c_str(), &w, &h, &ch, 4)) {
            if (w > 0 && h > 0) {
                entry.width = w;
                entry.height = h;
                entry.rgba.assign(raw, raw + size_t(w) * h * 4);
            }
            stbi_image_free(raw);
        }
        auto [inserted, _] = canisters.emplace(stock_id, std::move(entry));
        return inserted->second.rgba.empty() ? nullptr : &inserted->second;
    }
};

CardAssetCache &card_cache() {
    static CardAssetCache cache;
    return cache;
}

std::string today_card_date(const spk::Json &edits) {
    std::string value = edits.at("card_date").as_string();
    if (!value.empty()) return value.substr(0, 20);
    std::time_t now = std::time(nullptr);
    std::tm tm_now{};
#if defined(_WIN32)
    localtime_s(&tm_now, &now);
#else
    localtime_r(&now, &tm_now);
#endif
    char buf[16];
    std::snprintf(buf, sizeof(buf), "%04d.%02d.%02d",
                  tm_now.tm_year + 1900, tm_now.tm_mon + 1, tm_now.tm_mday);
    return buf;
}

void draw_hairline_rect(Image16 &im, int x0, int y0, int x1, int y1, const Color &col, float alpha) {
    auto put = [&](int x, int y) {
        if (x >= 0 && x < int(im.width) && y >= 0 && y < int(im.height))
            tint(pixel(im, x, y), col, alpha);
    };
    for (int x = x0; x <= x1; ++x) { put(x, y0); put(x, y1); }
    for (int y = y0 + 1; y < y1; ++y) { put(x0, y); put(x1, y); }
}

void draw_filled_circle(Image16 &im, float cx, float cy, float r, const Color &col, float alpha) {
    const int x0 = std::max(0, int(std::floor(cx - r - 1)));
    const int x1 = std::min(int(im.width) - 1, int(std::ceil(cx + r + 1)));
    const int y0 = std::max(0, int(std::floor(cy - r - 1)));
    const int y1 = std::min(int(im.height) - 1, int(std::ceil(cy + r + 1)));
    for (int y = y0; y <= y1; ++y) {
        const float dy = y + .5f - cy;
        for (int x = x0; x <= x1; ++x) {
            const float dx = x + .5f - cx;
            const float d = std::sqrt(dx * dx + dy * dy) - r;
            const float cov = unit(.5f - d);
            if (cov > 0.f) tint(pixel(im, x, y), col, alpha * cov);
        }
    }
}

void draw_line_seg(Image16 &im, int x0, int y0, int x1, int y1, const Color &col, float alpha) {
    if (x0 == x1) {
        if (x0 < 0 || x0 >= int(im.width)) return;
        for (int y = std::max(0, std::min(y0, y1)); y <= std::min(int(im.height) - 1, std::max(y0, y1)); ++y)
            tint(pixel(im, x0, y), col, alpha);
    } else if (y0 == y1) {
        if (y0 < 0 || y0 >= int(im.height)) return;
        for (int x = std::max(0, std::min(x0, x1)); x <= std::min(int(im.width) - 1, std::max(x0, x1)); ++x)
            tint(pixel(im, x, y0), col, alpha);
    }
}

// Composite 3D canister PNG with box-area/bilinear filtering and soft elliptical studio contact shadow.
int blit_canister(Image16 &im, const CanisterRgba *can, int x, int y, int target_h, bool dark_bg) {
    if (!can || can->width <= 0 || can->height <= 0 || target_h <= 0) {
        return std::max(1, int(std::lround(target_h * .68f)));
    }
    const int target_w = std::max(1, int(std::lround(double(can->width) * target_h / double(can->height))));

    // Subtle soft studio contact shadow right beneath canister bottom rim
    const float scx = x + target_w * .5f;
    const float scy = y + target_h * .97f;
    const float srx = std::max(2.f, target_w * .56f);
    const float sry = std::max(1.5f, target_h * .085f);
    const float max_sh = dark_bg ? .48f : .22f;
    const int sx0 = std::max(0, int(std::floor(scx - srx * 1.6f)));
    const int sx1 = std::min(int(im.width) - 1, int(std::ceil(scx + srx * 1.6f)));
    const int sy0 = std::max(0, int(std::floor(scy - sry * 1.8f)));
    const int sy1 = std::min(int(im.height) - 1, int(std::ceil(scy + sry * 1.8f)));
    for (int yy = sy0; yy <= sy1; ++yy) {
        const float ny = (yy + .5f - scy) / sry;
        for (int xx = sx0; xx <= sx1; ++xx) {
            const float nx = (xx + .5f - scx) / srx;
            const float r2 = nx * nx + ny * ny;
            if (r2 > 3.2f) continue;
            const float sh = max_sh * std::exp(-1.55f * r2);
            if (sh > .003f) tint(pixel(im, xx, yy), Color{0.f, 0.f, 0.f}, sh);
        }
    }

    // Area-averaged (for downscaling) or bilinear (for upscaling) alpha-over compositing
    const float scale_x = float(can->width) / float(target_w);
    const float scale_y = float(can->height) / float(target_h);
    const bool box = scale_x >= 1.5f && scale_y >= 1.5f;
    const int dst_x0 = std::max(0, x), dst_x1 = std::min(int(im.width), x + target_w);
    const int dst_y0 = std::max(0, y), dst_y1 = std::min(int(im.height), y + target_h);
    for (int yy = dst_y0; yy < dst_y1; ++yy) {
        const float fy = (yy - y + .5f) * scale_y;
        for (int xx = dst_x0; xx < dst_x1; ++xx) {
            const float fx = (xx - x + .5f) * scale_x;
            float r = 0, g = 0, b = 0, a = 0;
            if (box) {
                const int ix0 = std::clamp(int(std::floor(fx - scale_x * .5f)), 0, can->width - 1);
                const int ix1 = std::clamp(int(std::ceil(fx + scale_x * .5f)), ix0 + 1, can->width);
                const int iy0 = std::clamp(int(std::floor(fy - scale_y * .5f)), 0, can->height - 1);
                const int iy1 = std::clamp(int(std::ceil(fy + scale_y * .5f)), iy0 + 1, can->height);
                float weight_sum = 0, rgb_w = 0;
                for (int sy = iy0; sy < iy1; ++sy) {
                    const uint8_t *row = &can->rgba[size_t(sy) * can->width * 4];
                    for (int sx = ix0; sx < ix1; ++sx) {
                        const uint8_t *s = row + size_t(sx) * 4;
                        const float sa = s[3] * (1.f / 255.f);
                        r += s[0] * (1.f / 255.f) * sa;
                        g += s[1] * (1.f / 255.f) * sa;
                        b += s[2] * (1.f / 255.f) * sa;
                        a += sa;
                        rgb_w += sa;
                        weight_sum += 1.f;
                    }
                }
                if (weight_sum > 0.f) a /= weight_sum;
                if (rgb_w > 1e-6f) { r /= rgb_w; g /= rgb_w; b /= rgb_w; }
            } else {
                const float sx = std::clamp(fx - .5f, 0.f, float(can->width - 1));
                const float sy = std::clamp(fy - .5f, 0.f, float(can->height - 1));
                const int x0 = int(sx), y0 = int(sy);
                const int x1 = std::min(x0 + 1, can->width - 1);
                const int y1 = std::min(y0 + 1, can->height - 1);
                const float tx = sx - x0, ty = sy - y0;
                const uint8_t *p00 = &can->rgba[(size_t(y0) * can->width + x0) * 4];
                const uint8_t *p10 = &can->rgba[(size_t(y0) * can->width + x1) * 4];
                const uint8_t *p01 = &can->rgba[(size_t(y1) * can->width + x0) * 4];
                const uint8_t *p11 = &can->rgba[(size_t(y1) * can->width + x1) * 4];
                const float w00 = (1 - tx) * (1 - ty), w10 = tx * (1 - ty),
                            w01 = (1 - tx) * ty, w11 = tx * ty;
                const float a00 = p00[3] / 255.f, a10 = p10[3] / 255.f,
                            a01 = p01[3] / 255.f, a11 = p11[3] / 255.f;
                a = w00 * a00 + w10 * a10 + w01 * a01 + w11 * a11;
                if (a > 1e-5f) {
                    auto ch_b = [&](int k) {
                        return (w00 * a00 * p00[k] + w10 * a10 * p10[k]
                                + w01 * a01 * p01[k] + w11 * a11 * p11[k]) / (255.f * a);
                    };
                    r = ch_b(0); g = ch_b(1); b = ch_b(2);
                }
            }
            if (a > .003f) tint(pixel(im, xx, yy), Color{r, g, b}, a);
        }
    }
    return target_w;
}

enum class TextAlign { Left, Center, Right };

float measure_ttf_width(const stbtt_fontinfo &font, std::string_view text, float scale, float tracking) {
    float total = 0.f;
    for (size_t i = 0; i < text.size(); ++i) {
        int adv = 0, lsb = 0;
        stbtt_GetCodepointHMetrics(&font, static_cast<unsigned char>(text[i]), &adv, &lsb);
        total += adv * scale;
        if (i + 1 < text.size()) {
            total += stbtt_GetCodepointKernAdvance(
                         &font, static_cast<unsigned char>(text[i]),
                         static_cast<unsigned char>(text[i + 1])) * scale + tracking;
        }
    }
    return total;
}

void draw_card_text(Image16 &im, const stbtt_fontinfo *font, std::string_view text,
                    float x, float y, float px_height, float tracking,
                    const Color &col, bool bold, TextAlign align, bool bottom_anchor = false,
                    float max_width = 0.f) {
    if (text.empty() || px_height < 2.f) return;
    if (!font) {
        // Fallback crisp vector lettering when resources directory is not supplied (e.g. unit test).
        const BorderPainter p(im);
        const float h = px_height * .72f;
        const float max_w = max_width > 4.f ? max_width : float(im.width);
        const float cy_f = bottom_anchor ? y - h * .5f : y;
        if (align == TextAlign::Center) {
            const float w_est = std::min(max_w, .9f * h * float(text.size()));
            imprint(p, text, x - w_est * .5f, cy_f, h, max_w, col, .95f, false, false);
        } else {
            imprint(p, text, x, cy_f, h, max_w, col, .95f, align == TextAlign::Right, false);
        }
        return;
    }

    float h_eff = px_height;
    float scale = stbtt_ScaleForPixelHeight(font, h_eff);
    float total_w = measure_ttf_width(*font, text, scale, tracking);
    if (max_width > 4.f && total_w > max_width) {
        const float shrink = max_width / total_w;
        h_eff = std::max(6.f, h_eff * shrink);
        tracking *= shrink;
        scale = stbtt_ScaleForPixelHeight(font, h_eff);
        total_w = measure_ttf_width(*font, text, scale, tracking);
    }

    int hx0 = 0, hy0 = 0, hx1 = 0, hy1 = 0;
    stbtt_GetCodepointBitmapBox(font, 'H', scale, scale, &hx0, &hy0, &hx1, &hy1);
    const float cap_h = std::max(h_eff * .68f, float(-hy0));
    const float baseline = bottom_anchor ? y : y + cap_h * .5f;

    float pen_x = x;
    if (align == TextAlign::Center) pen_x -= total_w * .5f;
    else if (align == TextAlign::Right) pen_x -= total_w;

    const float bold_px = bold ? std::clamp(h_eff * .022f, .35f, 3.2f) : 0.f;
    const int pad = std::max(4, int(std::ceil(bold_px)) + 2);
    const float dist_scale = 120.f / float(pad);
    const float threshold = 128.f - bold_px * dist_scale;
    const float aa_span = std::max(2.f, 1.15f * dist_scale);

    for (size_t i = 0; i < text.size(); ++i) {
        const int cp = static_cast<unsigned char>(text[i]);
        int adv = 0, lsb = 0;
        stbtt_GetCodepointHMetrics(font, cp, &adv, &lsb);
        if (cp > 32) {
            int gw = 0, gh = 0, gxoff = 0, gyoff = 0;
            if (unsigned char *sdf = stbtt_GetCodepointSDF(
                    font, scale, cp, pad, 128, dist_scale, &gw, &gh, &gxoff, &gyoff)) {
                const int ox = int(std::lround(pen_x)) + gxoff;
                const int oy = int(std::lround(baseline)) + gyoff;
                for (int gy = 0; gy < gh; ++gy) {
                    const int dy = oy + gy;
                    if (dy < 0 || dy >= int(im.height)) continue;
                    const unsigned char *row = sdf + size_t(gy) * gw;
                    for (int gx = 0; gx < gw; ++gx) {
                        const int dx = ox + gx;
                        if (dx < 0 || dx >= int(im.width)) continue;
                        const float cov = unit((float(row[gx]) - threshold) / aa_span + .5f);
                        if (cov > .004f) tint(pixel(im, dx, dy), col, cov);
                    }
                }
                stbtt_FreeSDF(sdf, nullptr);
            }
        }
        pen_x += adv * scale;
        if (i + 1 < text.size()) {
            pen_x += stbtt_GetCodepointKernAdvance(
                         font, cp, static_cast<unsigned char>(text[i + 1])) * scale + tracking;
        }
    }
}
} // namespace

void finish_film_card(Image16 &image, const FrameLayout &f, const spk::Json &edits,
                      const std::string &resources) {
    const std::string stock_id = edits.at("film_stock").as_string();
    const FilmCardSpec &spec = film_card_spec(stock_id);
    const std::string date_str = today_card_date(edits);
    const bool landscape = f.photo_width >= f.photo_height;
    const float s = float(std::min(f.photo_width, f.photo_height));

    auto &cache = card_cache();
    std::lock_guard<std::mutex> lock(cache.mutex);
    cache.ensure_font(resources);
    const stbtt_fontinfo *font = cache.font_ready ? &cache.font : nullptr;
    const CanisterRgba *can = cache.canister(resources, spec.stock_id);

    constexpr float kUnifiedCanisterRatio = .078f;

    if (f.style == "film_card_white") {
        // Subtle 1px photo edge hairline
        draw_hairline_rect(image, f.left - 1, f.top - 1,
                           f.left + f.photo_width, f.top + f.photo_height,
                           Color{230 / 255.f, 228 / 255.f, 222 / 255.f}, 1.f);

        const Color ink = {22 / 255.f, 22 / 255.f, 26 / 255.f};
        const Color muted = {98 / 255.f, 98 / 255.f, 106 / 255.f};
        const Color soft = {146 / 255.f, 144 / 255.f, 138 / 255.f};

        const int cy = f.top + f.photo_height + f.bottom / 2;
        const int can_h = std::max(10, int(std::lround(s * kUnifiedCanisterRatio)));
        const int can_w = blit_canister(image, can, f.left, cy - can_h / 2, can_h, false);

        const float tx = float(f.left + can_w) + s * .024f;
        const float rx = float(f.left + f.photo_width);
        const float gap = s * .0155f;
        const float max_left_w = std::max(24.f, float(f.photo_width) * .60f - can_w);

        draw_card_text(image, font, spec.full, tx, cy - gap,
                       s * (landscape ? .034f : .033f), s * .0016f, ink, true,
                       TextAlign::Left, false, max_left_w);
        draw_card_text(image, font, spec.sub, tx, cy + gap + s * .002f,
                       s * (landscape ? .020f : .019f), s * .0009f, muted, false,
                       TextAlign::Left, false, max_left_w);

        draw_card_text(image, font, date_str, rx, cy - gap,
                       s * (landscape ? .023f : .022f), s * .0010f, ink, false,
                       TextAlign::Right);
        draw_card_text(image, font, "SPEKTRALENS", rx, cy + gap + s * .002f,
                       s * (landscape ? .016f : .0155f), s * .0020f, soft, false,
                       TextAlign::Right);
    } else if (f.style == "film_card_black") {
        const Color line = {44 / 255.f, 44 / 255.f, 52 / 255.f};
        const Color ink = {244 / 255.f, 244 / 255.f, 248 / 255.f};
        const Color muted = {162 / 255.f, 166 / 255.f, 178 / 255.f};
        const Color dim = {122 / 255.f, 126 / 255.f, 138 / 255.f};
        const Color acc = {240 / 255.f, 82 / 255.f, 61 / 255.f};

        draw_hairline_rect(image, f.left - 1, f.top - 1,
                           f.left + f.photo_width, f.top + f.photo_height, line, 1.f);

        if (landscape) {
            const int pad = f.left;
            const int gap_mid = std::max(4, int(std::lround(s * .036f)));
            const int sx = f.left + f.photo_width + gap_mid;
            const int side_w = std::max(20, int(image.width) - pad - sx);
            const int div_x = f.left + f.photo_width + gap_mid / 2;
            draw_line_seg(image, div_x, f.top, div_x, f.top + f.photo_height, line, 1.f);

            // Top archive badge
            const float dot_r = std::max(1.5f, s * .0045f);
            draw_filled_circle(image, sx + dot_r, f.top + s * .010f, dot_r, acc, 1.f);
            draw_card_text(image, font, "ARCHIVE", sx + dot_r * 2.f + s * .010f,
                           f.top + s * .010f, s * .0165f, s * .0020f, muted, false,
                           TextAlign::Left);

            // Sleek centered canister in upper sidebar
            const int can_h = std::max(14, int(std::lround(f.photo_height * .195f)));
            const int est_w = can && can->height > 0
                ? std::max(1, int(std::lround(double(can->width) * can_h / double(can->height))))
                : int(std::lround(can_h * .68f));
            const int can_x = sx + (side_w - est_w) / 2;
            const int can_y = f.top + int(std::lround(f.photo_height * .115f));
            blit_canister(image, can, can_x, can_y, can_h, true);

            // Centered 2-line stock brand + model (auto-shrunk if long)
            const float cx = sx + side_w * .5f;
            const float ny = float(can_y + can_h) + f.photo_height * .050f;
            draw_card_text(image, font, spec.brand, cx, ny,
                           s * .0165f, s * .0022f, muted, false, TextAlign::Center, false,
                           float(side_w));
            draw_card_text(image, font, spec.model, cx, ny + s * .032f,
                           s * .030f, s * .0010f, ink, true, TextAlign::Center, false,
                           float(side_w - 4));

            const int div_y = int(std::lround(ny + s * .072f));
            draw_line_seg(image, sx, div_y, sx + side_w, div_y, line, 1.f);

            // 3-row fixed film spec table (ISO / TYPE / PROC)
            const std::array<std::pair<const char *, const char *>, 3> rows = {{
                {"ISO", spec.iso}, {"TYPE", spec.type}, {"PROC", spec.proc}
            }};
            float ry = float(div_y) + s * .044f;
            const float row_step = s * .048f;
            for (const auto &[k, v] : rows) {
                draw_card_text(image, font, k, float(sx), ry,
                               s * .0155f, s * .0012f, dim, false, TextAlign::Left);
                draw_card_text(image, font, v, float(sx + side_w), ry,
                               s * .0205f, s * .0005f, ink, false, TextAlign::Right, false,
                               float(side_w) * .66f);
                ry += row_step;
            }

            // Bottom pinned generation date
            const int by = f.top + f.photo_height - 2;
            draw_line_seg(image, sx, by - int(std::lround(s * .068f)),
                          sx + side_w, by - int(std::lround(s * .068f)), line, 1.f);
            draw_card_text(image, font, "GENERATED", float(sx), by - s * .038f,
                           s * .015f, s * .0012f, dim, false, TextAlign::Left);
            draw_card_text(image, font, date_str, float(sx), float(by - 2),
                           s * .0215f, s * .0008f, ink, false, TextAlign::Left, true,
                           float(side_w) * .80f);
            draw_card_text(image, font, "SL", float(sx + side_w), float(by - 2),
                           s * .0155f, s * .0008f, acc, true, TextAlign::Right, true);
        } else {
            // Portrait: bottom darkroom console with unified 7.8% canister & fine optical vernier ticks
            const int cy = f.top + f.photo_height + f.bottom / 2 - std::max(2, int(std::lround(s * .004f)));
            const int tick_y = int(image.height) - std::max(3, int(std::lround(s * .014f)));
            const Color tick_col = {54 / 255.f, 54 / 255.f, 64 / 255.f};
            for (int i = 0; i <= 20; ++i) {
                const int tx_i = f.left + int(std::lround(double(f.photo_width) * i / 20.0));
                const int th_i = std::max(2, int(std::lround(s * (i % 5 == 0 ? .007f : .0035f))));
                draw_line_seg(image, tx_i, tick_y, tx_i, tick_y - th_i, tick_col, 1.f);
            }

            const int can_h = std::max(10, int(std::lround(s * kUnifiedCanisterRatio)));
            const int can_w = blit_canister(image, can, f.left, cy - can_h / 2, can_h, true);

            const float tx = float(f.left + can_w) + s * .024f;
            const float rx = float(f.left + f.photo_width);
            const float gap = s * .0155f;
            const float dot_r = std::max(1.5f, s * .0042f);
            const float max_left_w = std::max(24.f, float(f.photo_width) * .60f - can_w);

            draw_filled_circle(image, tx + dot_r, cy - gap, dot_r, acc, 1.f);
            draw_card_text(image, font, spec.full, tx + dot_r * 2.f + s * .009f, cy - gap,
                           s * .033f, s * .0014f, ink, true, TextAlign::Left, false,
                           max_left_w - dot_r * 2.f - s * .009f);
            draw_card_text(image, font, spec.sub, tx, cy + gap + s * .002f,
                           s * .019f, s * .0009f, muted, false, TextAlign::Left, false,
                           max_left_w);

            draw_card_text(image, font, date_str, rx, cy - gap,
                           s * .022f, s * .0009f, ink, false, TextAlign::Right);
            draw_card_text(image, font, "SPEKTRALENS", rx, cy + gap + s * .002f,
                           s * .0155f, s * .0018f, dim, false, TextAlign::Right);
        }
    }
    image.has_alpha = false;
}
} // namespace mobile
