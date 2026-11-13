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
}

int main(int argc, char **argv) {
    try {
        check(argc == 3, "Usage: export_quality_test resources output");
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
