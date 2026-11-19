// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import java.io.File;
import java.io.IOException;

/** Shared raster decode with vendor-tolerant fallbacks for JPEG/PNG/HEIC/HEIF. */
final class RasterDecoder {
  static final class Result {
    Bitmap bitmap;
    int width, height;
  }

  private RasterDecoder() {}

  static Result decode(File source, int longEdge) throws IOException {
    IOException first = null;
    try {
      return decodeWithImageDecoder(source, longEdge, true, true);
    } catch (IOException | RuntimeException error) {
      first = asIOException(error);
      android.util.Log.w("SpektraLensAndroid", "Strict raster decode failed: " + source, error);
    }
    try {
      return decodeWithImageDecoder(source, longEdge, false, true);
    } catch (IOException | RuntimeException error) {
      android.util.Log.w("SpektraLensAndroid", "SRGB raster decode failed: " + source, error);
    }
    try {
      return decodeWithImageDecoder(source, longEdge, false, false);
    } catch (IOException | RuntimeException error) {
      android.util.Log.w("SpektraLensAndroid", "Relaxed raster decode failed: " + source, error);
    }
    Result fallback = decodeWithBitmapFactory(source, longEdge);
    if (fallback != null) return fallback;
    throw first == null ? new IOException("照片解码失败") : first;
  }

  private static Result decodeWithImageDecoder(
      File source, int longEdge, boolean mutable, boolean srgb) throws IOException {
    Result result = new Result();
    Bitmap decoded =
        ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(source),
            (decoder, info, value) -> {
              decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
              if (mutable) decoder.setMutableRequired(true);
              if (srgb) decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
              result.width = info.getSize().getWidth();
              result.height = info.getSize().getHeight();
              if (longEdge > 0) {
                float scale =
                    Math.min(1, longEdge / (float) Math.max(result.width, result.height));
                decoder.setTargetSize(
                    Math.max(1, Math.round(result.width * scale)),
                    Math.max(1, Math.round(result.height * scale)));
              }
            });
    result.bitmap = normalise(decoded);
    return result;
  }

  private static Result decodeWithBitmapFactory(File source, int longEdge) {
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
    int sample = 1;
    if (longEdge > 0) {
      int max = Math.max(bounds.outWidth, bounds.outHeight);
      while (max / sample > longEdge) sample *= 2;
    }
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inSampleSize = sample;
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    Bitmap decoded = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
    if (decoded == null) return null;
    Result result = new Result();
    result.width = bounds.outWidth;
    result.height = bounds.outHeight;
    try {
      result.bitmap = normalise(decoded);
    } catch (IOException error) {
      return null;
    }
    return result;
  }

  private static Bitmap normalise(Bitmap bitmap) throws IOException {
    if (bitmap == null) throw new IOException("照片解码没有返回图像");
    if (bitmap.getConfig() == Bitmap.Config.ARGB_8888 && bitmap.isMutable()) return bitmap;
    Bitmap mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true);
    if (mutable == null) throw new IOException("无法转换照片预览");
    Bitmaps.release(bitmap);
    return mutable;
  }

  private static IOException asIOException(Throwable error) {
    return error instanceof IOException
        ? (IOException) error
        : new IOException(error.getMessage() == null ? error.toString() : error.getMessage(), error);
  }
}
