// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
#include "edit_pipeline.hpp"
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
    size_t tinted = 0;
    for (uint32_t y = 0; y < height; ++y)
        for (uint32_t x = 0; x < width; ++x) {
            const size_t i = (size_t(y) * width + x) * 4;
            const size_t large_i = (size_t(y * 3 + 1) * large.width + x * 3 + 1) * 4;
            tinted += warm.rgba[i] > warm.rgba[i + 1];
            for (int c = 0; c < 3; ++c)
                check(std::abs(int(warm.rgba[i + c]) - int(large.rgba[large_i + c])) <= 1,
                      "Resizing moved or reshaped the leak");
        }
    check(tinted > 0 && tinted < width * height / 3, "Leak must be warm and localized");
    const size_t middle = (size_t(height / 2) * width + width / 2) * 4;
    check(warm.rgba[middle] == base.rgba[middle], "Leak reached the photo centre");

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
        for (const std::string style : {"135_black", "135_warm", "clean_white", "instant"}) {
            edits.set("light_leak_mode", spk::Json(mode));
            edits.set("edge_style", spk::Json(style));
            edits.set("light_leak_amount", spk::Json(0.0));
            const auto without = render(width, height, edits);
            edits.set("light_leak_amount", spk::Json(80.0));
            const auto with = render(width, height, edits);
            const auto left = (with.width - width) / 2;
            const auto top = (with.height - height) / (style == "instant" ? 4 : 2);
            const bool film = style == "135_black" || style == "135_warm";
            size_t changedBorder = 0, holes = 0;
            for (uint32_t y = 0; y < with.height; ++y)
                for (uint32_t x = 0; x < with.width; ++x) {
                    const size_t i = (size_t(y) * with.width + x) * 4;
                    if (x >= left && x < left + width && y >= top && y < top + height) {
                        if (film && (x < left + 3 || x >= left + width - 3
                            || y < top + 3 || y >= top + height - 3)) continue;
                        const size_t j = (size_t(y - top) * width + x - left) * 4;
                        const auto &photo = mode == "color" ? strongColor : firstRoll;
                        check(std::equal(with.rgba.data() + i, with.rgba.data() + i + 4,
                                         photo.rgba.data() + j), "Frame changed the photo leak");
                        continue;
                    }
                    const bool changed = !std::equal(with.rgba.data() + i, with.rgba.data() + i + 4,
                                                     without.rgba.data() + i);
                    changedBorder += changed;
                    if (film && without.rgba[i] >= 54394 && without.rgba[i + 2] == 52428) {
                        check(!changed, "Leak tinted a perforation");
                        ++holes;
                    }
                }
            check((changedBorder > 0) == film, "Leak must extend only onto film base");
            if (film) check(holes > 0, "Perforation fixture did not find a hole");
        }
    std::cout << "Light leak: neutral off, repeatable seed, scaled placement, highlights, monochrome and frame\n";
}

void check_film_border() {
    for (const bool portrait : {false, true}) {
        const uint32_t w = portrait ? 200 : 300, h = portrait ? 300 : 200;
        std::vector<uint16_t> source(size_t(w) * h * 4, 32000);
        for (const std::string style : {"135_black", "135_warm"}) {
            auto edits = spk::Json::object();
            edits.set("edge_style", spk::Json(style));
            const auto result = mobile::apply_edits(source.data(), w, h, w, edits);
            const auto dimensions = mobile::output_dimensions(w, h, edits);
            check(dimensions.at("width").as_int() == int(result.width)
                  && dimensions.at("height").as_int() == int(result.height), "Film dimensions differ");
            const int left = int(result.width - w) / 2, top = int(result.height - h) / 2;
            check(portrait ? left > top : top > left, "Film rails on the wrong sides");
            const int rail = portrait ? left : top;
            const int across = portrait ? int(result.width) : int(result.height);
            const int along = portrait ? int(result.height) : int(result.width);
            for (int row : {int(rail * .42f), across - 1 - int(rail * .42f)}) {
                int holes = 0;
                bool previous = false;
                for (int pos = 0; pos < along; ++pos) {
                    const int x = portrait ? row : pos, y = portrait ? pos : row;
                    const bool inHole = result.rgba[(size_t(y) * result.width + x) * 4] > 51000;
                    if (inHole && !previous) ++holes;
                    previous = inHole;
                }
                check(holes == 8, "Expected eight perforations per film rail");
            }
        }
    }
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
    check(render("2026.11.21").rgba != source, "Legacy dotted date did not render");
}
}

int main(int argc, char **argv) {
    try {
        check(argc == 3, "Usage: export_quality_test resources output");
        check_light_leak();
        check_film_border();
        check_date_imprint();
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
