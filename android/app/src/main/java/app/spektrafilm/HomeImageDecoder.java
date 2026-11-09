// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import java.io.IOException;

/** Center-crop at display size, retaining extra samples for antialiasing before reduction. */
final class HomeImageDecoder {
  static final int MAX_EDGE = 2048;
  private static final double MAX_DECODE_PIXELS = 6_000_000;

  static Bitmap decode(ImageDecoder.Source source, int width, int height) throws IOException {
    Bitmap crop =
        ImageDecoder.decodeBitmap(
            source,
            (decoder, info, ignored) -> {
              int w = info.getSize().getWidth();
              int h = info.getSize().getHeight();
              double scale = Math.min(1, 2 * Math.max(width / (double) w, height / (double) h));
              scale = Math.min(scale, Math.sqrt(MAX_DECODE_PIXELS / ((double) w * h)));
              int decodedWidth = Math.max(1, (int) Math.round(w * scale));
              int decodedHeight = Math.max(1, (int) Math.round(h * scale));
              decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
              decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
              decoder.setTargetSize(decodedWidth, decodedHeight);
              decoder.setCrop(centerCrop(decodedWidth, decodedHeight, width, height));
            });
    return finish(crop, width, height);
  }

  /** Existing RAW decoding supplies a software sRGB bitmap owned by this request. */
  static Bitmap crop(Bitmap image, int width, int height) {
    Rect region = centerCrop(image.getWidth(), image.getHeight(), width, height);
    Bitmap cropped =
        Bitmap.createBitmap(image, region.left, region.top, region.width(), region.height());
    if (cropped != image) image.recycle();
    return finish(cropped, width, height);
  }

  private static Rect centerCrop(int w, int h, int width, int height) {
    int cropWidth = Math.max(1, Math.min(w, (int) Math.round(h * (width / (double) height))));
    int cropHeight = Math.max(1, Math.min(h, (int) Math.round(w * (height / (double) width))));
    int left = (w - cropWidth) / 2, top = (h - cropHeight) / 2;
    return new Rect(left, top, left + cropWidth, top + cropHeight);
  }

  private static Bitmap finish(Bitmap crop, int width, int height) {
    double scale =
        Math.min(1, Math.min(width / (double) crop.getWidth(), height / (double) crop.getHeight()));
    int w = Math.max(1, (int) Math.round(crop.getWidth() * scale));
    int h = Math.max(1, (int) Math.round(crop.getHeight() * scale));
    if (w != crop.getWidth() || h != crop.getHeight()) {
      Bitmap reduced = areaResize(crop, w, h);
      crop.recycle();
      crop = reduced;
    }
    crop.setDensity(Bitmap.DENSITY_NONE);
    return crop;
  }

  private static Bitmap areaResize(Bitmap bitmap, int width, int height) {
    int w = bitmap.getWidth(), h = bitmap.getHeight();
    int[] pixels = new int[w * h], row = new int[width];
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
    Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    double stepX = w / (double) width, stepY = h / (double) height;
    double area = stepX * stepY;
    for (int y = 0; y < height; y++) {
      double top = y * stepY, bottom = Math.min(h, (y + 1) * stepY);
      for (int x = 0; x < width; x++) {
        double left = x * stepX, right = Math.min(w, (x + 1) * stepX);
        double alpha = 0, red = 0, green = 0, blue = 0;
        for (int sy = (int) top; sy < bottom; sy++) {
          double wy = Math.min(bottom, sy + 1) - Math.max(top, sy);
          for (int sx = (int) left; sx < right; sx++) {
            double weight = wy * (Math.min(right, sx + 1) - Math.max(left, sx));
            int color = pixels[sy * w + sx];
            double a = weight * (color >>> 24);
            alpha += a;
            red += a * ((color >>> 16) & 255);
            green += a * ((color >>> 8) & 255);
            blue += a * (color & 255);
          }
        }
        row[x] =
            alpha == 0
                ? 0
                : ((int) Math.round(alpha / area) << 24)
                    | ((int) Math.round(red / alpha) << 16)
                    | ((int) Math.round(green / alpha) << 8)
                    | (int) Math.round(blue / alpha);
      }
      result.setPixels(row, 0, width, 0, y, width, 1);
    }
    return result;
  }
}
