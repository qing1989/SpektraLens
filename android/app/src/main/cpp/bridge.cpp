// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
#include "edit_pipeline.hpp"
#include "engine_boot.hpp"
#include "output.hpp"
#include "raw_adapter.hpp"
#include "spektrafilm/spk_engine.h"
#include <algorithm>
#include <chrono>
#include <android/bitmap.h>
#include <cmath>
#include <cstdint>
#include <jni.h>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
struct Context {
    spk_engine *engine = nullptr;
    spk_session *session = nullptr;
    std::string resources, raw_path;
    mobile::RawImage raw_preview;
    ~Context() {
        if (session)
            spk_session_release(session);
        if (engine)
            spk_engine_destroy(engine);
    }
};
std::string utf(JNIEnv *e, jstring s) {
    if (!s)
        throw std::runtime_error("Missing string");
    const char *p = e->GetStringUTFChars(s, nullptr);
    if (!p)
        throw std::bad_alloc();
    std::string out(p);
    e->ReleaseStringUTFChars(s, p);
    return out;
}
Context *ctx(jlong p) {
    if (!p)
        throw std::runtime_error("Engine is closed");
    return reinterpret_cast<Context *>(p);
}
void fail(JNIEnv *e, const std::string &message) {
    if (!e->ExceptionCheck())
        e->ThrowNew(e->FindClass("java/lang/IllegalStateException"), message.c_str());
}
void check(Context *c, spk_status status) {
    if (status != SPK_OK)
        throw std::runtime_error(spk_last_error(c->engine));
}
spk::Json json(const std::string &s) {
    spk::Json j;
    std::string error;
    if (!spk::Json::parse(s, j, error) || !j.is_object())
        throw std::runtime_error("Invalid edits: " + error);
    return j;
}
void set_params(Context *c, const std::string &p) {
    if (!c->session)
        throw std::runtime_error("No photograph loaded");
    char *reply = nullptr;
    auto status = spk_set_params(c->session, p.c_str(), &reply);
    if (reply)
        spk_string_free(reply);
    check(c, status);
}
void open_rgb(Context *c, const float *rgb, uint32_t w, uint32_t h, const std::string &p) {
    spk_image image{rgb, w, h, 3};
    char *reply = nullptr;
    auto next = spk_open(c->engine, &image, p.c_str(), &reply);
    if (reply)
        spk_string_free(reply);
    if (!next)
        throw std::runtime_error(spk_last_error(c->engine));
    if (c->session)
        spk_session_release(c->session);
    c->session = next;
}
mobile::Image16 bitmap_pixels(JNIEnv *e, jobject bitmap) {
    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(e, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        throw std::runtime_error("Input must be RGBA8888 sRGB");
    mobile::Image16 image;
    image.width = info.width;
    image.height = info.height;
    image.rgba.resize(size_t(info.width) * info.height * 4);
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(e, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        throw std::runtime_error("Cannot read input");
    for (uint32_t y = 0; y < info.height; y++) {
        auto row = static_cast<uint8_t *>(pixels) + y * info.stride;
        for (uint32_t x = 0; x < info.width; x++)
            for (int k = 0; k < 4; k++)
                image.rgba[(size_t(y) * info.width + x) * 4 + k] = uint16_t(row[x * 4 + k]) * 257;
    }
    AndroidBitmap_unlockPixels(e, bitmap);
    return image;
}
jobject bitmap(JNIEnv *e, const uint16_t *src, uint32_t w, uint32_t h, uint32_t stride) {
    auto bc = e->FindClass("android/graphics/Bitmap"),
         cc = e->FindClass("android/graphics/Bitmap$Config");
    auto config = e->GetStaticObjectField(
        cc, e->GetStaticFieldID(cc, "ARGB_8888", "Landroid/graphics/Bitmap$Config;"));
    auto out = e->CallStaticObjectMethod(
        bc,
        e->GetStaticMethodID(bc, "createBitmap",
                             "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"),
        jint(w), jint(h), config);
    if (e->ExceptionCheck() || !out)
        return nullptr;
    AndroidBitmapInfo info{};
    AndroidBitmap_getInfo(e, out, &info);
    void *pixels = nullptr;
    if (AndroidBitmap_lockPixels(e, out, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
        throw std::runtime_error("Cannot write preview");
    for (uint32_t y = 0; y < h; y++) {
        auto row = static_cast<uint8_t *>(pixels) + y * info.stride;
        for (uint32_t x = 0; x < w; x++) {
            for (int k = 0; k < 3; k++)
                row[x * 4 + k] =
                    uint8_t((uint32_t(src[(size_t(y) * stride + x) * 4 + k]) + 128) / 257);
            row[x * 4 + 3] = 255;
        }
    }
    AndroidBitmap_unlockPixels(e, out);
    return out;
}
using Clock = std::chrono::steady_clock;
double elapsed_ms(Clock::time_point start) {
    return std::chrono::duration<double, std::milli>(Clock::now() - start).count();
}
mobile::Image16 render_edit(Context *c, const std::string &p, const spk::Json &edits, bool full,
                          spk::Json *timings = nullptr) {
    auto start = Clock::now();
    set_params(c, p);
    if (timings) timings->set("set_params_ms", spk::Json(elapsed_ms(start)));
    spk_result result{};
    try {
        start = Clock::now();
        check(c, spk_render(c->session, full ? "full" : "live", &result));
        if (timings) timings->set("spectral_ms", spk::Json(elapsed_ms(start)));
        start = Clock::now();
        auto out = mobile::apply_edits(result.rgba16, result.width, result.height,
                                       result.row_stride_px, edits);
        if (timings) timings->set("edits_ms", spk::Json(elapsed_ms(start)));
        spk_result_free(&result);
        return out;
    } catch (...) {
        spk_result_free(&result);
        throw;
    }
}
} // namespace
extern "C" JNIEXPORT jlong JNICALL Java_app_spektrafilm_NativeEngine_create(JNIEnv *e, jclass,
                                                                            jstring path) {
    try {
        auto c = std::make_unique<Context>();
        c->resources = utf(e, path);
        mobile::boot_begin();
        c->engine = spk_engine_create(c->resources.c_str(), nullptr);
        if (!c->engine)
            throw std::runtime_error(spk_last_error(nullptr));
        mobile::boot_ready(spk_capabilities(c->engine));
        return reinterpret_cast<jlong>(c.release());
    } catch (const std::exception &ex) {
        mobile::boot_failed(ex.what());
        fail(e, ex.what());
        return 0;
    }
}
extern "C" JNIEXPORT jstring JNICALL
Java_app_spektrafilm_NativeEngine_capabilities(JNIEnv *e, jclass, jlong p) {
    try {
        return e->NewStringUTF(spk_capabilities(ctx(p)->engine));
    } catch (const std::exception &ex) {
        fail(e, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT jstring JNICALL Java_app_spektrafilm_NativeEngine_inspectRaw(JNIEnv *e, jclass,
                                                                                  jstring path) {
    try {
        return e->NewStringUTF(mobile::inspect_raw(utf(e, path)).dump().c_str());
    } catch (const std::exception &ex) {
        fail(e, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT jobject JNICALL
Java_app_spektrafilm_NativeEngine_rawPreview(JNIEnv *e, jclass, jlong p, jstring path, jint edge) {
    try {
        auto c = ctx(p);
        auto name = utf(e, path);
        auto raw = mobile::decode_raw(name, std::max(1, edge));
        char *reply = nullptr;
        const float *cmax = nullptr;
        uint32_t count = 0;
        check(c, spk_output_transform(c->engine, "ProPhoto RGB", "sRGB", "{\"algorithm\":\"off\"}",
                                      &reply, &cmax, &count));
        std::string encoded(reply);
        spk_string_free(reply);
        auto transform = json(encoded);
        const auto &m = transform.at("matrix").items();
        mobile::Image16 im;
        im.width = raw.width;
        im.height = raw.height;
        im.rgba.resize(size_t(im.width) * im.height * 4);
        for (size_t i = 0; i < size_t(im.width) * im.height; i++) {
            for (int k = 0; k < 3; k++) {
                float v = 0;
                for (int z = 0; z < 3; z++)
                    v += float(m[k * 3 + z].as_double()) * raw.rgb[i * 3 + z];
                im.rgba[i * 4 + k] = uint16_t(
                    std::lround(std::clamp(spkhost::cctf_encode_mode(v, 0), 0.f, 1.f) * 65535));
            }
            im.rgba[i * 4 + 3] = 65535;
        }
        c->raw_preview = std::move(raw);
        c->raw_path = name;
        return bitmap(e, im.rgba.data(), im.width, im.height, im.width);
    } catch (const std::exception &ex) {
        fail(e, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT void JNICALL Java_app_spektrafilm_NativeEngine_openRaw(JNIEnv *e, jclass,
                                                                            jlong p, jstring path,
                                                                            jstring params,
                                                                            jint edge) {
    try {
        auto c = ctx(p);
        auto name = utf(e, path);
        auto j = json(utf(e, params));
        j.set("input_color_space", spk::Json(std::string("ProPhoto RGB")));
        j.set("input_cctf_decoding", spk::Json(false));
        if (edge > 0 && c->raw_path == name &&
            std::max(c->raw_preview.width, c->raw_preview.height) <= uint32_t(edge) &&
            !c->raw_preview.rgb.empty()) {
            auto &r = c->raw_preview;
            open_rgb(c, r.rgb.data(), r.width, r.height, j.dump());
        } else {
            auto r = mobile::decode_raw(name, std::max(0, edge));
            open_rgb(c, r.rgb.data(), r.width, r.height, j.dump());
        }
    } catch (const std::exception &ex) {
        fail(e, ex.what());
    }
}
extern "C" JNIEXPORT void JNICALL
Java_app_spektrafilm_NativeEngine_open(JNIEnv *e, jclass, jlong p, jobject source, jstring params) {
    try {
        auto c = ctx(p);
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(e, source, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
            throw std::runtime_error("Input must be RGBA8888 sRGB");
        std::vector<float> rgb(size_t(info.width) * info.height * 3);
        void *pixels = nullptr;
        if (AndroidBitmap_lockPixels(e, source, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot read input");
        for (uint32_t y = 0; y < info.height; y++) {
            auto row = static_cast<uint8_t *>(pixels) + y * info.stride;
            for (uint32_t x = 0; x < info.width; x++)
                for (int k = 0; k < 3; k++)
                    rgb[(size_t(y) * info.width + x) * 3 + k] = row[x * 4 + k] / 255.f;
        }
        AndroidBitmap_unlockPixels(e, source);
        open_rgb(c, rgb.data(), info.width, info.height, utf(e, params));
    } catch (const std::exception &ex) {
        fail(e, ex.what());
    }
}
extern "C" JNIEXPORT jobject JNICALL Java_app_spektrafilm_NativeEngine_render(JNIEnv *e, jclass,
                                                                              jlong p,
                                                                              jstring params,
                                                                              jboolean full) {
    spk_result result{};
    try {
        auto c = ctx(p);
        set_params(c, utf(e, params));
        check(c, spk_render(c->session, full ? "full" : "live", &result));
        auto out = bitmap(e, result.rgba16, result.width, result.height, result.row_stride_px);
        spk_result_free(&result);
        return out;
    } catch (const std::exception &ex) {
        spk_result_free(&result);
        fail(e, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT jobject JNICALL
Java_app_spektrafilm_NativeEngine_renderEdited(JNIEnv *e, jclass, jlong p, jstring params,
                                               jstring edits, jboolean full) {
    try {
        auto im = render_edit(ctx(p), utf(e, params), json(utf(e, edits)), full);
        return bitmap(e, im.rgba.data(), im.width, im.height, im.width);
    } catch (const std::exception &ex) {
        fail(e, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT jobject JNICALL
Java_app_spektrafilm_NativeEngine_editBitmap(JNIEnv *e, jclass, jobject source, jstring edits) {
    try {
        auto im = bitmap_pixels(e, source);
        auto out =
            mobile::apply_edits(im.rgba.data(), im.width, im.height, im.width, json(utf(e, edits)));
        return bitmap(e, out.rgba.data(), out.width, out.height, out.width);
    } catch (const std::exception &ex) {
        fail(e, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT jstring JNICALL
Java_app_spektrafilm_NativeEngine_dimensions(JNIEnv *e, jclass, jint w, jint h, jstring edits) {
    try {
        if (w < 1 || h < 1)
            throw std::runtime_error("Invalid image size");
        return e->NewStringUTF(mobile::output_dimensions(w, h, json(utf(e, edits))).dump().c_str());
    } catch (const std::exception &ex) {
        fail(e, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT jstring JNICALL
Java_app_spektrafilm_NativeEngine_exportImage(JNIEnv *e, jclass, jlong p, jstring params,
                                              jstring edits, jstring path, jstring format) {
    try {
        auto c = ctx(p);
        auto j = json(utf(e, edits));
        auto timings = spk::Json::object();
        auto im = render_edit(c, utf(e, params), j, true, &timings);
        auto start = Clock::now();
        mobile::write_image(im, utf(e, path), utf(e, format), c->resources, j);
        timings.set("encode_write_ms", spk::Json(elapsed_ms(start)));
        auto result = mobile::output_dimensions(im.width, im.height, spk::Json::object());
        result.set("timings", std::move(timings));
        return e->NewStringUTF(result.dump().c_str());
    } catch (const std::exception &ex) {
        fail(e, ex.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT void JNICALL Java_app_spektrafilm_NativeEngine_closeImage(JNIEnv *, jclass,
                                                                               jlong p) {
    if (p) {
        auto c = ctx(p);
        if (c->session) {
            spk_session_release(c->session);
            c->session = nullptr;
        }
    }
}
extern "C" JNIEXPORT void JNICALL Java_app_spektrafilm_NativeEngine_destroy(JNIEnv *, jclass,
                                                                            jlong p) {
    delete reinterpret_cast<Context *>(p);
}

extern "C" JNIEXPORT void JNICALL Java_app_spektrafilm_NativeEngine_configureBackend(JNIEnv* e,jclass,jstring mode,jstring libraries,jstring state){
    try{mobile::configure_backend(utf(e,mode),utf(e,libraries),utf(e,state));}catch(const std::exception& ex){fail(e,ex.what());}
}
extern "C" JNIEXPORT jstring JNICALL Java_app_spektrafilm_NativeEngine_bootStatus(JNIEnv* e,jclass){return e->NewStringUTF(mobile::boot_snapshot().c_str());}
