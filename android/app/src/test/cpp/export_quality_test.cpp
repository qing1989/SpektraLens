// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
#include "edit_pipeline.hpp"
#include "film_border.hpp"
#include "stb_image.h"
#include <algorithm>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <stdexcept>

namespace {
void check(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}

std::vector<uint8_t> read(const std::filesystem::path &path) {
    std::ifstream input(path, std::ios::binary);
    check(bool(input), "Cannot read " + path.string());
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}

int first_quantizer(const std::vector<uint8_t> &bytes) {
    size_t position = 2;
    while (position + 5 < bytes.size()) {
        check(bytes[position] == 0xff, "Malformed JPEG marker");
        uint8_t marker = bytes[position + 1];
        size_t length = (size_t(bytes[position + 2]) << 8) | bytes[position + 3];
        check(length >= 2 && position + 2 + length <= bytes.size(), "Malformed JPEG segment");
        if (marker == 0xdb) return bytes[position + 5];
        if (marker == 0xda || marker == 0xd9) break;
        position += length + 2;
    }
    throw std::runtime_error("Missing JPEG quantization table");
}

uint32_t big32(const std::vector<uint8_t> &bytes, size_t offset) {
    return (uint32_t(bytes.at(offset)) << 24) | (uint32_t(bytes.at(offset + 1)) << 16)
        | (uint32_t(bytes.at(offset + 2)) << 8) | bytes.at(offset + 3);
}

uint16_t little16(const std::vector<uint8_t> &bytes, size_t offset) {
    return bytes.at(offset) | (uint16_t(bytes.at(offset + 1)) << 8);
}

uint32_t little32(const std::vector<uint8_t> &bytes, size_t offset) {
    return little16(bytes, offset) | (uint32_t(little16(bytes, offset + 2)) << 16);
}

size_t exif_payload(const std::vector<uint8_t> &bytes, const std::string &format) {
    if (format == "tiff") return 0;
    if (format == "png") {
        for (size_t pos = 8; pos + 12 <= bytes.size(); pos += 12 + big32(bytes, pos))
            if (std::string(bytes.begin() + pos + 4, bytes.begin() + pos + 8) == "eXIf")
                return pos + 8;
    } else {
        for (size_t pos = 2; pos + 10 <= bytes.size();) {
            const auto marker = bytes[pos + 1];
            if (marker == 0xda || marker == 0xd9) break;
            const size_t length = (size_t(bytes[pos + 2]) << 8) | bytes[pos + 3];
            check(length >= 2, "Malformed JPEG EXIF fixture");
            if (marker == 0xe1 && std::string(bytes.begin() + pos + 4, bytes.begin() + pos + 8) == "Exif")
                return pos + 10;
            pos += 2 + length;
        }
    }
    throw std::runtime_error("Missing EXIF block in " + format);
}

size_t tiff_entry(const std::vector<uint8_t> &bytes, size_t directory, uint16_t tag) {
    for (size_t i = 0; i < little16(bytes, directory); ++i) {
        const size_t entry = directory + 2 + i * 12;
        if (little16(bytes, entry) == tag) return entry;
    }
    return 0;
}

void check_focal_exif(const mobile::Image16 &image, const std::filesystem::path &directory,
                      const std::string &resources) {
    for (const std::string format : {"jpeg", "png", "tiff"})
        for (unsigned mask = 0; mask < 4; ++mask) {
            auto edits = spk::Json::object();
            if (mask & 1) edits.set("source_focal_mm", spk::Json(9.2));
            if (mask & 2) edits.set("source_focal_35mm", spk::Json(70.0));
            auto path = directory / ("focal-" + std::to_string(mask) + "." + format);
            mobile::write_image(image, path.string(), format, resources, edits);
            const auto bytes = read(path);
            const size_t base = exif_payload(bytes, format);
            check(little16(bytes, base) == 0x4949, "Unexpected EXIF byte order");
            const size_t pointer = tiff_entry(bytes, base + little32(bytes, base + 4), 0x8769);
            check(pointer != 0, "Missing EXIF IFD");
            const size_t ifd = base + little32(bytes, pointer + 8);
            const size_t actual = tiff_entry(bytes, ifd, 0x920a);
            const size_t equivalent = tiff_entry(bytes, ifd, 0xa405);
            check(bool(actual) == bool(mask & 1), "Wrong actual focal-length presence in " + format);
            check(bool(equivalent) == bool(mask & 2), "Wrong 35mm focal-length presence in " + format);
            if (actual) {
                const size_t value = base + little32(bytes, actual + 8);
                check(little16(bytes, actual + 2) == 5 && little32(bytes, value) == 920
                      && little32(bytes, value + 4) == 100, "Actual focal length altered");
            }
            if (equivalent)
                check(little16(bytes, equivalent + 2) == 3 && little16(bytes, equivalent + 8) == 70,
                      "35mm equivalent focal length altered");
        }
    std::cout << "JPEG/PNG16/TIFF16: independent actual and 35mm focal lengths\n";
}

void check_tiff(const std::vector<uint8_t> &bytes, uint32_t width, uint32_t height) {
    check(bytes.at(0) == 'I' && bytes.at(1) == 'I', "Expected little-endian TIFF");
    size_t directory = little32(bytes, 4);
    int found = 0;
    for (size_t index = 0; index < little16(bytes, directory); index++) {
        size_t entry = directory + 2 + index * 12;
        uint16_t tag = little16(bytes, entry);
        uint32_t value = little32(bytes, entry + 8);
        if (tag == 256) { check(value == width, "TIFF width changed"); found++; }
        if (tag == 257) { check(value == height, "TIFF height changed"); found++; }
        if (tag == 258) {
            check(little32(bytes, entry + 4) == 3, "TIFF RGB channels changed");
            for (size_t channel = 0; channel < 3; channel++)
                check(little16(bytes, value + channel * 2) == 16, "TIFF must remain 16 bit");
            found++;
        }
    }
    check(found == 3, "TIFF metadata incomplete");
}

void check_light_leak() {
    constexpr uint32_t width = 80, height = 100;
    auto render = [](uint32_t w, uint32_t h, const spk::Json &edits, uint16_t gray = 18000) {
        std::vector<uint16_t> source(size_t(w) * h * 4, gray);
        for (size_t i = 3; i < source.size(); i += 4) source[i] = 65535;
        return mobile::apply_edits(source.data(), w, h, w, edits);
    };
    const auto base = render(width, height, spk::Json::object());
    auto edits = spk::Json::object();
    edits.set("light_leak_seed", spk::Json(42.0));
    edits.set("light_leak_amount", spk::Json(0.0));
    check(render(width, height, edits).rgba == base.rgba, "Disabled leak changed pixels");

    edits.set("light_leak_amount", spk::Json(25.0));
    const auto warm = render(width, height, edits);
    check(warm.width == width && warm.height == height, "Leak changed photo dimensions");
    check(warm.rgba == render(width, height, edits).rgba, "Same leak seed changed");
    const auto white = render(width, height, spk::Json::object(), 65535);
    check(render(width, height, edits, 65535).rgba == white.rgba, "Leak altered white highlights");
    const auto large = render(width * 3, height * 3, edits);
    size_t tinted = 0, visible25 = 0;
    for (uint32_t y = 0; y < height; ++y)
        for (uint32_t x = 0; x < width; ++x) {
            const size_t i = (size_t(y) * width + x) * 4;
            const size_t large_i = (size_t(y * 3 + 1) * large.width + x * 3 + 1) * 4;
            tinted += warm.rgba[i] > warm.rgba[i + 1];
            visible25 += int(warm.rgba[i]) - int(warm.rgba[i + 2]) > 1500;
            for (int c = 0; c < 3; ++c)
                check(std::abs(int(warm.rgba[i + c]) - int(large.rgba[large_i + c])) <= 1,
                      "Resizing moved or reshaped the leak");
        }
    check(tinted > width * height * 95 / 100, "Color leak must veil the whole frame");
    // The slider uses a square-root response so 25% must already read as a
    // visible veil, not as a barely perceptible edge tint.
    check(visible25 > width * height / 2, "25% color leak is too faint");
    auto band = [&](int x0, int x1, int y0, int y1) {
        long sum = 0;
        for (int y = y0; y < y1; ++y)
            for (int x = x0; x < x1; ++x) {
                const size_t i = (size_t(y) * width + x) * 4;
                sum += int(warm.rgba[i]) - int(warm.rgba[i + 2]);
            }
        return double(sum) / ((x1 - x0) * (y1 - y0));
    };
    const size_t middle = (size_t(height / 2) * width + width / 2) * 4;
    check(warm.rgba[middle] != base.rgba[middle], "Full-frame veil missing at centre");
    const double centreTint = band(width * 2 / 5, width * 3 / 5, height * 2 / 5, height * 3 / 5);
    const double edgeTint = std::max({band(0, width / 10, 0, height),
                                      band(width - width / 10, width, 0, height),
                                      band(0, width, 0, height / 10),
                                      band(0, width, height - height / 10, height)});
    check(edgeTint > centreTint * 1.5, "Veil lacks a brighter entry edge");

    edits.set("light_leak_seed", spk::Json(198.0));
    check(render(width, height, edits).rgba != warm.rgba, "Changing seed did not alter the leak");
    edits.set("light_leak_monochrome", spk::Json(true));
    const auto mono = render(width, height, edits);
    check(mono.rgba != base.rgba, "Black-and-white leak was skipped");
    for (size_t i = 0; i < mono.rgba.size(); i += 4)
        check(mono.rgba[i] == mono.rgba[i + 1] && mono.rgba[i] == mono.rgba[i + 2],
              "Leak added color in black and white");

    edits.set("edge_style", spk::Json(std::string("clean_white")));
    const auto framed = render(width, height, edits);
    edits.set("light_leak_amount", spk::Json(0.0));
    const auto framed_base = render(width, height, edits);
    check(framed.width > width && framed.height > height, "Frame was not rendered");
    const auto margin = (framed.width - width) / 2;
    for (uint32_t y = 0; y < framed.height; ++y)
        for (uint32_t x = 0; x < framed.width; ++x) {
            if (x >= margin && x < margin + width && y >= margin && y < margin + height) continue;
            const size_t i = (size_t(y) * framed.width + x) * 4;
            check(std::equal(framed.rgba.data() + i, framed.rgba.data() + i + 4,
                             framed_base.rgba.data() + i), "Leak extended into the frame");
        }
    edits = spk::Json::object();
    edits.set("light_leak_seed", spk::Json(42.0));
    edits.set("light_leak_amount", spk::Json(80.0));
    const uint16_t pale = 58982;
    const auto paleColor = render(width, height, edits, pale);
    size_t visibleTint = 0;
    for (size_t i = 0; i < paleColor.rgba.size(); i += 4)
        visibleTint += int(paleColor.rgba[i]) - paleColor.rgba[i + 2] > 1500;
    check(visibleTint > width * height / 25, "Strong color leak is too faint on a pale photo");
    const auto strongColor = render(width, height, edits);
    edits.set("light_leak_mode", spk::Json(std::string("first_roll")));
    const auto firstRoll = render(width, height, edits);
    const auto rollLarge = render(width * 3, height * 3, edits);
    check(firstRoll.rgba != strongColor.rgba, "First roll used the color-leak shape");
    check(firstRoll.rgba == render(width, height, edits).rgba, "First-roll seed changed");
    size_t burned = 0;
    for (uint32_t y = 0; y < height; ++y)
        for (uint32_t x = 0; x < width; ++x) {
            const size_t i = (size_t(y) * width + x) * 4;
            const size_t wide = (size_t(y * 3 + 1) * rollLarge.width + x * 3 + 1) * 4;
            burned += firstRoll.rgba[i] > 64000 && firstRoll.rgba[i + 2] > 54000;
            for (int c = 0; c < 3; ++c)
                check(std::abs(int(firstRoll.rgba[i + c]) - int(rollLarge.rgba[wide + c])) <= 1,
                      "Resizing moved the first-roll boundary");
        }
    check(burned > width * height / 8, "First roll has no broad pale core");
    check(firstRoll.rgba[middle] == base.rgba[middle], "First roll reached the photo centre");
    edits.set("light_leak_monochrome", spk::Json(true));
    const auto monoRoll = render(width, height, edits);
    for (size_t i = 0; i < monoRoll.rgba.size(); i += 4)
        check(monoRoll.rgba[i] == monoRoll.rgba[i + 1] && monoRoll.rgba[i] == monoRoll.rgba[i + 2],
              "First roll tinted the monochrome image");
    edits.set("light_leak_monochrome", spk::Json(false));
    edits.set("edge_amount", spk::Json(15.0));
    for (const std::string mode : {"color", "first_roll"})
        for (const std::string style : {"135_black", "135_warm", "135_bw_silver", "120_medium",
                                        "4x5_sheet", "slide_mount", "clean_white", "instant"}) {
            edits.set("light_leak_mode", spk::Json(mode));
            edits.set("edge_style", spk::Json(style));
            edits.set("light_leak_amount", spk::Json(0.0));
            const auto without = render(width, height, edits);
            edits.set("light_leak_amount", spk::Json(80.0));
            const auto with = render(width, height, edits);
            const mobile::FrameLayout layout(width, height, edits);
            const auto left = uint32_t(layout.left), top = uint32_t(layout.top);
            const bool strip = style.starts_with("135_");
            size_t changedBorder = 0, holes = 0;
            for (uint32_t y = 0; y < with.height; ++y)
                for (uint32_t x = 0; x < with.width; ++x) {
                    const size_t i = (size_t(y) * with.width + x) * 4;
                    if (x >= left && x < left + width && y >= top && y < top + height) {
                        // Material and gate shading are confined to the image edge.
                        const uint32_t rim = style == "slide_mount" ? 20 : 3;
                        if (layout.film != mobile::FilmBorder::None
                            && (x < left + rim || x >= left + width - rim
                                || y < top + rim || y >= top + height - rim)) continue;
                        const size_t j = (size_t(y - top) * width + x - left) * 4;
                        const auto &photo = mode == "color" ? strongColor : firstRoll;
                        check(std::equal(with.rgba.data() + i, with.rgba.data() + i + 4,
                                         photo.rgba.data() + j), "Frame changed the photo leak");
                        continue;
                    }
                    const bool changed = !std::equal(with.rgba.data() + i, with.rgba.data() + i + 4,
                                                     without.rgba.data() + i);
                    changedBorder += changed;
                    if (strip && without.rgba[i] == 223 * 257 && without.rgba[i + 2] == 204 * 257) {
                        check(!changed, "Leak tinted a perforation");
                        ++holes;
                    }
                }
            check((changedBorder > 0) == layout.acetate(), "Leak must extend only onto acetate");
            if (strip) check(holes > 0, "Perforation fixture did not find a hole");
        }
    std::cout << "Light leak: neutral off, repeatable seed, full-frame veil, entry edge, highlights, monochrome and frame\n";
}

void check_highlight_expansion() {
    auto eval_gray = [](uint8_t srgb, const spk::Json &edits) -> double {
        uint16_t v = uint16_t(srgb) * 257;
        uint16_t px[4] = {v, v, v, 65535};
        auto out = mobile::apply_edits(px, 1, 1, 1, edits);
        check(out.rgba[0] == out.rgba[1] && out.rgba[1] == out.rgba[2],
              "Highlight expansion altered neutral balance");
        return out.rgba[0] / 257.0;
    };
    // Silver monochrome paper shoulder expansion: shadows/midtones exact, paper white ~222 -> ~250, strictly monotonic.
    auto silver = spk::Json::object();
    silver.set("silver_highlight_edr", spk::Json(true));
    check(eval_gray(60, silver) == 60.0, "Silver EDR altered shadow");
    check(eval_gray(118, silver) == 118.0, "Silver EDR altered mid-gray");
    check(eval_gray(165, silver) == 165.0, "Silver EDR altered upper midtone");
    const double paper_white = eval_gray(222, silver);
    check(paper_white >= 248.0 && paper_white <= 253.0, "Silver EDR did not lift paper white to ~250");
    double prev = -1.0;
    for (int v = 0; v <= 255; ++v) {
        double cur = eval_gray(uint8_t(v), silver);
        check(cur >= prev, "Silver EDR lost monotonicity");
        prev = cur;
    }
    // Positive highlights slider: lifts dull snow strongly without washing shadows or midtones, strictly monotonic.
    auto hi_up = spk::Json::object();
    hi_up.set("grade_highlights", spk::Json(100.0));
    check(eval_gray(60, hi_up) == 60.0, "Positive highlights washed shadows");
    check(eval_gray(118, hi_up) <= 123.0, "Positive highlights bled too far into mid-gray");
    const double snow = eval_gray(210, hi_up);
    check(snow >= 235.0 && snow <= 246.0, "Positive highlights +100 did not lift dull snow enough");
    const double bright = eval_gray(240, hi_up);
    check(bright > snow && bright < 254.5, "Positive highlights clipped bright snow");
    // Negative highlights slider still pulls highlights down.
    auto hi_down = spk::Json::object();
    hi_down.set("grade_highlights", spk::Json(-100.0));
    check(eval_gray(210, hi_down) <= 180.0, "Negative highlights lost recovery range");
    std::cout << "Highlight expansion: silver EDR shoulder and enhanced positive highlights slider passed\n";
}

void check_film_border() {
    for (const bool portrait : {false, true}) {
        const uint32_t w = portrait ? 200 : 300, h = portrait ? 300 : 200;
        std::vector<uint16_t> source(size_t(w) * h * 4, 32000);
        for (const std::string style : {"135_black", "135_warm", "135_bw_silver", "slide_mount",
                                        "120_medium", "4x5_sheet"}) {
            auto edits = spk::Json::object();
            edits.set("edge_style", spk::Json(style));
            const auto result = mobile::apply_edits(source.data(), w, h, w, edits, true);
            const auto flattened = mobile::apply_edits(source.data(), w, h, w, edits);
            const auto dimensions = mobile::output_dimensions(w, h, edits);
            check(dimensions.at("width").as_int() == int(result.width)
                  && dimensions.at("height").as_int() == int(result.height), "Film dimensions differ");
            check(result.has_alpha && !flattened.has_alpha, "Incorrect film rendering mode");
            const mobile::FrameLayout layout(w, h, edits);
            const auto pixel = [&](int x, int y) {
                return result.rgba.data() + (size_t(y) * result.width + x) * 4;
            };
            const auto *center = pixel(layout.left + w / 2, layout.top + h / 2);
            check(center[0] == 32000 && center[3] == 65535, "Transparent frame changed photo centre");
            for (size_t i = 0; i < result.rgba.size(); i += 4) {
                const float alpha = result.rgba[i + 3] / 65535.f;
                constexpr int backing[] = {223 * 257, 216 * 257, 204 * 257};
                check(flattened.rgba[i + 3] == 65535, "Film export must be opaque RGB");
                for (int k = 0; k < 3; ++k) {
                    const long composite = std::lround(result.rgba[i + k] * alpha
                                                       + backing[k] * (1.f - alpha));
                    check(std::abs(flattened.rgba[i + k] - composite) <= 1, "Film matte changed the composite");
                }
            }

            if (style.starts_with("135_")) {
                check(portrait ? layout.left > layout.top : layout.top > layout.left,
                      "Film rails on the wrong sides");
                const int rail = portrait ? layout.left : layout.top;
                const int across = portrait ? result.width : result.height;
                const int along = portrait ? result.height : result.width;
                for (int row : {int(rail * .43f), across - 1 - int(rail * .43f)}) {
                    int holes = 0;
                    bool previous = false;
                    for (int pos = 0; pos < along; ++pos) {
                        const bool inHole = (portrait ? pixel(row, pos) : pixel(pos, row))[3] == 0;
                        if (inHole && !previous) ++holes;
                        previous = inHole;
                    }
                    check(holes == 8, "Expected eight fully transparent perforations per film rail");
                }
                const int u = portrait ? layout.top : layout.left;
                const auto *base = portrait ? pixel(int(rail * .48f), u)
                                            : pixel(u, int(rail * .48f));
                check(style == "135_bw_silver" ? base[3] > 43000 && base[3] < 58000 : base[3] == 65535,
                      "Incorrect acetate transmissivity");
            } else if (style == "slide_mount") {
                check(result.width == result.height, "Ordinary photos should have square slide mounts");
                const auto *card = pixel(layout.left + w / 2, layout.top / 4);
                check(card[0] > 53000 && card[0] > card[2] && card[3] == 65535, "Ivory mount must be solid");
            } else if (style == "120_medium") {
                int notches = 0;
                bool previous = false;
                for (int y = layout.top; y < layout.top + int(h); ++y) {
                    const auto *p = pixel(layout.left - 1, y);
                    const bool gate = p[0] >= 27000;
                    if (gate && !previous) ++notches;
                    previous = gate;
                    check(p[3] == 65535, "Medium-format gate must have no transparent holes");
                }
                check(notches == 2, "Expected two V-shaped gate marks");
            } else {
                check(layout.top > layout.bottom, "Missing asymmetric sheet holder");
                int cutouts = 0;
                bool previous = false;
                for (int x = result.width / 2; x < int(result.width) - 2; ++x) {
                    const bool transparent = pixel(x, 1)[3] == 0;
                    if (transparent && !previous) ++cutouts;
                    previous = transparent;
                }
                check(cutouts == 3, "Sheet notch code must open through the top edge");
            }
        }
    }

    // Slide mount: portrait and square mounts must keep stamps, lettering and red dot clear of the embossed groove.
    for (const auto [w, h] : std::vector<std::pair<uint32_t, uint32_t>>{{600, 800}, {600, 600}}) {
        std::vector<uint16_t> source(size_t(w) * h * 4, 32000);
        for (const double amount : {2.0, 6.0, 15.0}) {
            auto edits = spk::Json::object();
            edits.set("edge_style", spk::Json(std::string("slide_mount")));
            edits.set("edge_amount", spk::Json(amount));
            edits.set("edge_frame_number", spk::Json(36.0));
            const auto result = mobile::apply_edits(source.data(), w, h, w, edits, true);
            const mobile::FrameLayout layout(w, h, edits);
            const float pad = float(std::min({layout.left, layout.right, layout.top, layout.bottom}));
            const float rim_w = std::max(1.f, pad * .048f);
            const float lip = std::max(1.f, pad * .27f);
            const int groove_end = int(std::ceil(lip + 2.4f * rim_w));
            // Any red stamp or dark deboss overlapping the embossed groove drops green/blue far below ivory.
            for (int y = 0; y <= groove_end; ++y) {
                for (const int row : {y, int(result.height) - 1 - y}) {
                    for (uint32_t x = uint32_t(groove_end); x + uint32_t(groove_end) < result.width; ++x) {
                        const auto *p = result.rgba.data() + (size_t(row) * result.width + x) * 4;
                        if (p[3] < 65535) continue;
                        check(p[1] > 46000 && int(p[0]) - int(p[2]) < 9000,
                              "Slide mount lettering or dot overlapped the embossed groove");
                    }
                }
            }
        }
    }

    // 4x5 sheet: V-notch rim highlight must decay smoothly below the tip without a bounding-box cliff.
    {
        const uint32_t w = 1600, h = 1200;
        std::vector<uint16_t> source(size_t(w) * h * 4, 32000);
        auto edits = spk::Json::object();
        edits.set("edge_style", spk::Json(std::string("4x5_sheet")));
        edits.set("edge_amount", spk::Json(6.0));
        const auto result = mobile::apply_edits(source.data(), w, h, w, edits, true);
        const mobile::FrameLayout layout(w, h, edits);
        const float pitch = std::min(layout.top * .58f, result.width * .067f);
        const float right = result.width - std::max(pitch, layout.right * .65f);
        const float depth = pitch * .90f;
        const float rim = std::max(.55f, pitch * .10f);
        const int vx = int(std::lround(right - pitch));
        const int y0 = int(std::floor(depth)) + 1;
        const int y1 = std::min(int(result.height) - 2, int(std::ceil(depth + rim + 4)));
        int maxDrop = 0;
        for (int y = y0; y < y1; ++y) {
            const int cur = int(result.rgba[(size_t(y) * result.width + vx) * 4]);
            const int next = int(result.rgba[(size_t(y + 1) * result.width + vx) * 4]);
            maxDrop = std::max(maxDrop, cur - next);
        }
        check(maxDrop < 12 * 257, "4x5 sheet V-notch highlight has a sharp bounding-box cutoff");
        const int tailR = int(result.rgba[(size_t(y1) * result.width + vx) * 4]) / 257;
        check(tailR < 26, "4x5 sheet V-notch highlight failed to fade to acetate base");
    }

    std::cout << "Film borders: both 135 colours plus silver, slide, 120 and sheet. Alpha, mattes and dimensions\n";
}

void check_date_imprint() {
    constexpr uint32_t width = 1200, height = 900;
    constexpr float glyphHeight = 28;
    constexpr int inset = 31;
    std::vector<uint16_t> source(size_t(width) * height * 4, 12000);
    for (size_t i = 3; i < source.size(); i += 4) source[i] = 65535;
    auto edits = spk::Json::object();
    edits.set("date_enabled", spk::Json(true));
    edits.set("date_corner", spk::Json(std::string("top_left")));
    auto render = [&](const std::string &text) {
        edits.set("date_text", spk::Json(text));
        return mobile::apply_edits(source.data(), width, height, width, edits);
    };
    const auto digits = render("0123456789");
    auto coreHeight = [&](const mobile::Image16 &image, int left, int right) {
        int top = height, bottom = -1;
        for (int y = inset - 2; y < inset + int(glyphHeight) + 2; ++y)
            for (int x = left; x < right; ++x)
                if (image.rgba[(size_t(y) * width + x) * 4] > 43000) {
                    top = std::min(top, y);
                    bottom = std::max(bottom, y);
                }
        return bottom < top ? 0 : bottom - top + 1;
    };
    for (int digit = 0; digit < 10; ++digit) {
        const int left = int(inset + digit * (.77f * glyphHeight));
        const int right = left + int(.69f * glyphHeight);
        const int measured = coreHeight(digits, left, right);
        check(std::abs(measured - int(glyphHeight)) <= 1,
              "Date digit " + std::to_string(digit) + " height: " + std::to_string(measured));
    }
    const auto shortDate = render("'26 11 21");
    check(shortDate.rgba == render("’26 11 21").rgba, "Curly year apostrophe changed the imprint");
    const int apostrophe = coreHeight(shortDate, inset, inset + 5);
    check(apostrophe >= 4 && apostrophe <= 8, "Missing or oversized year apostrophe");
    // Enhanced optical halation must visibly glow warm red-orange 6px outside digit '0' without clipping at far margin.
    const auto *halo = &digits.rgba[(size_t(inset + 14) * width + (inset - 6)) * 4];
    check(halo[0] > 18000 && int(halo[0]) - int(halo[2]) > 5000,
          "Date imprint halation glow is too weak outside strokes");
    const auto *farEdge = &digits.rgba[(size_t(inset + 14) * width + (inset + 260)) * 4];
    check(farEdge[0] == 12000 && farEdge[1] == 12000 && farEdge[2] == 12000,
          "Date imprint halation leaked beyond margin");
    check(render("2026.11.21").rgba != source, "Legacy dotted date did not render");
}

void check_foliage_guard() {
    auto make_solid_rgb = [](uint8_t r, uint8_t g, uint8_t b, uint32_t w, uint32_t h) {
        std::vector<float> rgb(size_t(w) * h * 3);
        for (size_t i = 0; i < size_t(w) * h; ++i) {
            rgb[i * 3 + 0] = r / 255.f;
            rgb[i * 3 + 1] = g / 255.f;
            rgb[i * 3 + 2] = b / 255.f;
        }
        return rgb;
    };
    auto make_solid_rgba16 = [](uint8_t r, uint8_t g, uint8_t b, uint32_t w, uint32_t h) {
        std::vector<uint16_t> px(size_t(w) * h * 4);
        for (size_t i = 0; i < size_t(w) * h; ++i) {
            px[i * 4 + 0] = uint16_t(r) * 257;
            px[i * 4 + 1] = uint16_t(g) * 257;
            px[i * 4 + 2] = uint16_t(b) * 257;
            px[i * 4 + 3] = 65535;
        }
        return px;
    };

    // 1. Yellowed spring leaf recovers cleanly toward natural green (G > R).
    {
        const auto src_rgb = make_solid_rgb(142, 178, 84, 8, 8);
        const auto gate = mobile::build_foliage_gate(src_rgb.data(), 8, 8, false);
        check(gate.active(), "Spring leaf must activate foliage gate");
        const auto sim_yellowed = make_solid_rgba16(185, 152, 62, 8, 8);
        const auto out = mobile::apply_edits(sim_yellowed.data(), 8, 8, 8, spk::Json::object(),
                                             false, &gate);
        const int r = (out.rgba[0] + 128) / 257;
        const int g = (out.rgba[1] + 128) / 257;
        const int b = (out.rgba[2] + 128) / 257;
        check(g > r + 10 && std::abs(r - 152) <= 2 && std::abs(g - 165) <= 2 && std::abs(b - 83) <= 2,
              "Yellowed spring leaf did not match expected Oklab recovery: "
                  + std::to_string(r) + "," + std::to_string(g) + "," + std::to_string(b));

        // Cool-shifted green must remain untouched (preserves Fuji cool greens).
        const auto sim_cool = make_solid_rgba16(96, 184, 118, 8, 8);
        const auto out_cool = mobile::apply_edits(sim_cool.data(), 8, 8, 8, spk::Json::object(),
                                                  false, &gate);
        check(out_cool.rgba == sim_cool, "Foliage guard altered cool-shifted green");

        // Monochrome silver EDR must bypass foliage guard completely.
        auto mono_edits = spk::Json::object();
        mono_edits.set("silver_highlight_edr", spk::Json(true));
        const auto out_mono_guard = mobile::apply_edits(sim_yellowed.data(), 8, 8, 8, mono_edits,
                                                        false, &gate);
        const auto out_mono_plain = mobile::apply_edits(sim_yellowed.data(), 8, 8, 8, mono_edits,
                                                        false, nullptr);
        check(out_mono_guard.rgba == out_mono_plain.rgba,
              "Silver monochrome path must bypass foliage guard");
    }

    // 2. Zero collateral damage (exact bit equality) on skin, yellow flowers, golden hour,
    //    pink petals, blue sky, and neutral gray.
    {
        const std::array<uint8_t, 3> swatches[] = {
            {216, 168, 142}, // Light Skin
            {186, 130, 102}, // Medium Skin
            {235, 198,  42}, // Yellow Flower
            {232, 162,  68}, // Golden Hour
            {222,  52, 110}, // Pink Petal
            { 38, 128, 212}, // Blue Sky
            {128, 128, 128}, // Neutral Gray
        };
        for (const auto &sw : swatches) {
            const auto src_rgb = make_solid_rgb(sw[0], sw[1], sw[2], 4, 4);
            const auto gate = mobile::build_foliage_gate(src_rgb.data(), 4, 4, false);
            check(!gate.active(), "Non-foliage swatch unexpectedly activated foliage gate");
            const auto sim = make_solid_rgba16(185, 152, 62, 4, 4);
            const auto out = mobile::apply_edits(sim.data(), 4, 4, 4, spk::Json::object(),
                                                 false, &gate);
            check(out.rgba == sim, "Non-foliage swatch was modified by foliage guard");
        }
    }

    // 3. Scaled gate + 90-degree rotation maps green region to the exact transformed location.
    {
        const uint32_t sw = 16, sh = 16;
        std::vector<float> src_rgb(size_t(sw) * sh * 3);
        for (uint32_t y = 0; y < sh; ++y)
            for (uint32_t x = 0; x < sw; ++x) {
                const bool left = x < sw / 2;
                const size_t i = (size_t(y) * sw + x) * 3;
                src_rgb[i + 0] = (left ? 142 : 216) / 255.f;
                src_rgb[i + 1] = (left ? 178 : 168) / 255.f;
                src_rgb[i + 2] = (left ?  84 : 142) / 255.f;
            }
        const auto gate = mobile::build_foliage_gate(src_rgb.data(), sw, sh, false);
        check(gate.active(), "Half-green fixture must activate gate");

        // Downsampled rendered image (8x8 vs 16x16 gate) rotated 90 deg clockwise (turns=1):
        // left half of source (x < w/2) maps to top half of rotated output (y < h/2).
        const auto sim = make_solid_rgba16(185, 152, 62, 8, 8);
        auto rot = spk::Json::object();
        rot.set("geo_turns", spk::Json(1.0));
        const auto out = mobile::apply_edits(sim.data(), 8, 8, 8, rot, false, &gate);
        const auto *top_px = &out.rgba[(size_t(1) * 8 + 4) * 4];
        const auto *bot_px = &out.rgba[(size_t(6) * 8 + 4) * 4];
        check(top_px[1] > top_px[0], "Rotated green half was not guarded at top");
        check(bot_px[0] == 185 * 257 && bot_px[1] == 152 * 257 && bot_px[2] == 62 * 257,
              "Rotated skin half was altered by foliage guard");
    }

    // 4. Linear ProPhoto RGB input (RAW path) matches sRGB green gating and rejects skin/yellow.
    {
        // Inverse of SpektraLab's ProPhoto->sRGB matrix applied to linear sRGB spring leaf (142,178,84)
        // produces approx ProPhoto linear (0.296, 0.409, 0.138); pure green ProPhoto (0.15, 0.45, 0.10) is vivid foliage.
        const std::vector<float> raw_green = {0.296f, 0.409f, 0.138f, 0.296f, 0.409f, 0.138f,
                                              0.296f, 0.409f, 0.138f, 0.296f, 0.409f, 0.138f};
        const auto raw_gate = mobile::build_foliage_gate(raw_green.data(), 2, 2, true);
        check(raw_gate.active(), "Linear ProPhoto green leaf must activate foliage gate");
        const auto sim = make_solid_rgba16(185, 152, 62, 2, 2);
        const auto out = mobile::apply_edits(sim.data(), 2, 2, 2, spk::Json::object(),
                                             false, &raw_gate);
        check(out.rgba[1] > out.rgba[0], "Linear ProPhoto foliage gate did not recover green");

        // Warm skin in linear ProPhoto RGB must NOT trigger foliage gate.
        const std::vector<float> raw_skin = {0.52f, 0.36f, 0.26f, 0.52f, 0.36f, 0.26f,
                                             0.52f, 0.36f, 0.26f, 0.52f, 0.36f, 0.26f};
        const auto skin_gate = mobile::build_foliage_gate(raw_skin.data(), 2, 2, true);
        check(!skin_gate.active(), "Linear ProPhoto skin must not activate foliage gate");
    }

    std::cout << "Foliage guard: green recovery, cool-green & swatch isolation, rotation/scaling, and ProPhoto RAW passed\n";
}

void check_film_cards(const std::string &resources) {
    for (const bool landscape : {true, false}) {
        const uint32_t w = landscape ? 900 : 600;
        const uint32_t h = landscape ? 600 : 800;
        std::vector<uint16_t> source(size_t(w) * h * 4, 28000);
        for (size_t i = 3; i < source.size(); i += 4) source[i] = 65535;
        for (const std::string style : {"film_card_white", "film_card_black"}) {
            auto edits = spk::Json::object();
            edits.set("edge_style", spk::Json(style));
            edits.set("film_stock", spk::Json(std::string(style == "film_card_white"
                                                              ? "fujifilm_c200"
                                                              : "cinestill_800t")));
            edits.set("card_date", spk::Json(std::string("2026.11.27")));
            const auto dims = mobile::output_dimensions(w, h, edits);
            const auto out = mobile::apply_edits(source.data(), w, h, w, edits, false, nullptr,
                                                 resources);
            check(dims.at("width").as_int() == int(out.width)
                      && dims.at("height").as_int() == int(out.height),
                  "Film card dimensions mismatch for " + style);
            check(!out.has_alpha, "Film card must be opaque");
            const mobile::FrameLayout layout(w, h, edits);
            if (style == "film_card_black" && landscape) {
                check(layout.right > layout.left * 4, "Black card landscape must have right sidebar");
                check(layout.bottom == layout.top, "Black card landscape must keep equal top/bottom margins");
            } else {
                check(layout.bottom > layout.top * 2, "Film card footer must be taller than top margin");
            }
            // Verify center photo pixel untouched
            const auto *center = &out.rgba[(size_t(layout.top + h / 2) * out.width + layout.left + w / 2) * 4];
            check(center[0] == 28000 && center[3] == 65535, "Film card altered photo center");

            // Verify canister + typography visibly painted on the card metadata zone (not blank!)
            const uint16_t bg_r = uint16_t(std::lround((style == "film_card_white" ? 248.f : 15.f) / 255.f * 65535.f));
            size_t painted = 0;
            const int x0 = (style == "film_card_black" && landscape) ? layout.left + int(w) + 6 : layout.left;
            const int x1 = (style == "film_card_black" && landscape) ? int(out.width) - 4 : layout.left + int(w);
            const int y0 = (style == "film_card_black" && landscape) ? layout.top : layout.top + int(h) + 4;
            const int y1 = (style == "film_card_black" && landscape) ? layout.top + int(h) : int(out.height) - 2;
            for (int y = y0; y < y1; ++y)
                for (int x = x0; x < x1; ++x) {
                    const auto *p = &out.rgba[(size_t(y) * out.width + x) * 4];
                    if (std::abs(int(p[0]) - int(bg_r)) > 4000) ++painted;
                }
            check(painted > 500, "Film card metadata/canister did not render for " + style);
        }
    }
    // Verify all 25 catalog canister PNGs exist on disk
    const std::array<const char *, 25> all_stocks = {
        "fujifilm_c200", "fujifilm_pro_400h", "fujifilm_xtra_400", "kodak_ektar_100",
        "kodak_gold_200", "kodak_portra_160", "kodak_portra_400", "kodak_portra_800",
        "kodak_portra_800_push1", "kodak_portra_800_push2", "kodak_ultramax_400",
        "fujifilm_provia_100f", "fujifilm_velvia_100", "kodak_ektachrome_100",
        "kodak_kodachrome_64", "cinestill_800t", "kodak_verita_200d", "kodak_vision3_50d",
        "kodak_vision3_200t", "kodak_vision3_250d", "kodak_vision3_500t",
        "fujifilm_neopan_acros_100_ii", "ilford_hp5_plus_400", "kodak_tmax_100", "kodak_tri_x_400"
    };
    for (const char *sid : all_stocks) {
        const auto p = std::filesystem::path(resources) / "canisters" / (std::string(sid) + ".png");
        check(std::filesystem::is_regular_file(p), "Missing bundled canister PNG: " + p.string());
    }
    check(std::filesystem::is_regular_file(std::filesystem::path(resources) / "canisters" / "inter_card.ttf"),
          "Missing bundled inter_card.ttf");
    std::cout << "Film cards: white & black cards (landscape/portrait), canister blit, typography, and 25 assets passed\n";
}
}

int main(int argc, char **argv) {
    try {
        check(argc == 3, "Usage: export_quality_test resources output");
        check_light_leak();
        check_highlight_expansion();
        check_film_border();
        check_date_imprint();
        check_foliage_guard();
        check_film_cards(argv[1]);
        std::filesystem::path directory(argv[2]);
        std::filesystem::create_directories(directory);
        mobile::Image16 image;
        image.width = 320;
        image.height = 224;
        image.rgba.resize(size_t(image.width) * image.height * 4);
        uint32_t random = 314159;
        for (uint32_t row = 0; row < image.height; row++)
            for (uint32_t column = 0; column < image.width; column++) {
                random = random * 1664525 + 1013904223;
                size_t offset = (size_t(row) * image.width + column) * 4;
                image.rgba[offset] = uint16_t((column * 157 + (random & 8191)) % 65536);
                image.rgba[offset + 1] = uint16_t((row * 239 + ((random >> 8) & 8191)) % 65536);
                image.rgba[offset + 2] = uint16_t((column * 71 + row * 83 + (random >> 20)) % 65536);
                image.rgba[offset + 3] = 65535;
            }
        const auto original = image.rgba;
        check_focal_exif(image, directory, argv[1]);
        size_t previous_size = 0;
        int previous_quantizer = 0;
        for (int quality : {95, 90, 85}) {
            auto edits = spk::Json::object();
            edits.set("export_quality", spk::Json(double(quality)));
            auto dimensions = mobile::output_dimensions(image.width, image.height, edits);
            check(dimensions.at("width").as_int() == int(image.width), "Quality changes width");
            check(dimensions.at("height").as_int() == int(image.height), "Quality changes height");
            auto unchanged = mobile::apply_edits(
                image.rgba.data(), image.width, image.height, image.width, edits);
            check(unchanged.rgba == original, "Encoding quality must not alter developed pixels");
            auto graded = spk::Json::object();
            graded.set("grade_exposure", spk::Json(1.0));
            auto gradedImage = mobile::apply_edits(
                image.rgba.data(), image.width, image.height, image.width, graded);
            check(gradedImage.rgba != original, "Grade edits must not use the identity fast path");
            auto cropped = spk::Json::object();
            cropped.set("geo_w", spk::Json(0.5));
            auto croppedImage = mobile::apply_edits(
                image.rgba.data(), image.width, image.height, image.width, cropped);
            check(croppedImage.width == image.width / 2, "Geometry crop width changed");
            auto framed = spk::Json::object();
            framed.set("edge_style", spk::Json(std::string("135_black")));
            framed.set("edge_amount", spk::Json(6.0));
            auto framedImage = mobile::apply_edits(
                image.rgba.data(), image.width, image.height, image.width, framed);
            check(framedImage.width > image.width, "Frame edits must not use the identity fast path");
            auto path = directory / ("quality-" + std::to_string(quality) + ".jpg");
            mobile::write_image(image, path.string(), "jpeg", argv[1], edits);
            auto bytes = read(path);
            int width = 0, height = 0, channels = 0;
            unsigned char *decoded =
                stbi_load_from_memory(bytes.data(), int(bytes.size()), &width, &height, &channels, 3);
            check(decoded != nullptr, "JPEG must decode");
            stbi_image_free(decoded);
            check(width == int(image.width) && height == int(image.height), "JPEG resized");
            int quantizer = first_quantizer(bytes);
            check(quantizer == (quality == 95 ? 2 : quality == 90 ? 3 : 5), "Wrong JPEG quality");
            if (previous_size) {
                check(bytes.size() < previous_size, "Lower quality must reduce this texture fixture");
                check(quantizer > previous_quantizer, "JPEG preset did not reach encoder");
            }
            previous_size = bytes.size();
            previous_quantizer = quantizer;
            check(image.rgba == original, "Export modified its input");
            std::cout << "JPEG " << quality << ": " << width << "x" << height
                      << ", " << bytes.size() << " bytes, quantizer " << quantizer << "\n";
        }
        for (const std::string format : {"png", "tiff"}) {
            std::vector<uint8_t> previous;
            for (int quality : {95, 85}) {
                auto edits = spk::Json::object();
                edits.set("export_quality", spk::Json(double(quality)));
                auto path = directory / (format + "-" + std::to_string(quality) + "." + format);
                mobile::write_image(image, path.string(), format, argv[1], edits);
                auto bytes = read(path);
                if (format == "png") {
                    check(bytes.at(0) == 137 && bytes.at(1) == 'P', "PNG header missing");
                    check(bytes.at(24) == 16, "PNG must remain 16 bit");
                    check(big32(bytes, 16) == image.width && big32(bytes, 20) == image.height,
                          "PNG resized");
                } else {
                    check_tiff(bytes, image.width, image.height);
                }
                if (!previous.empty()) check(bytes == previous, "JPEG quality leaked into " + format);
                previous = std::move(bytes);
            }
            std::cout << format << ": 16 bit, original dimensions, quality-independent bytes\n";
        }
        std::cout << "Export quality encoding regression passed\n";
        return 0;
    } catch (const std::exception &error) {
        std::cerr << error.what() << "\n";
        return 1;
    }
}
