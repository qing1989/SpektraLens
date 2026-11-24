// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
#include "film_border.hpp"
#include <algorithm>
#include <array>
#include <cctype>
#include <cmath>
#include <cstring>
#include <string_view>

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
} // namespace mobile
