// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import java.io.IOException;

/** Center-crop at display size, retaining extra samples for antialiasing before reduction. */
final class HomeImageDecoder {
  static final int MAX_EDGE = 2048;
  /** 4 GB 级别的机器大盘只有 256 MB 上下，首页预览没必要按 2048 解码。 */
  static final int LOW_MEMORY_MAX_EDGE = 1280;

  private static final double MAX_DECODE_PIXELS = 6_000_000;

  /** Budget-aware preview width: keeps big previews on roomy devices, shrinks them on tight ones. */
  static int maxEdge(Context context) {
    ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
    // largeHeap is on, so the large class is the real budget for this process.
    int heapMb = manager == null ? 256 : manager.getLargeMemoryClass();
    if (heapMb <= 256) return LOW_MEMORY_MAX_EDGE;
    if (heapMb <= 384) return 1536;
    return MAX_EDGE;
  }

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
    if (cropped != image) Bitmaps.release(image);
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
      Bitmaps.release(crop);
      crop = reduced;
    }
    crop.setDensity(Bitmap.DENSITY_NONE);
    return crop;
  }

  /**
   * Area-average downscale: every source pixel contributes in proportion to how much of it falls
   * inside the destination pixel, so fine grain averages out instead of aliasing.
   *
   * <p>One {@code getPixels}/{@code setPixels} pair with pre-computed overlap weights. The previous
   * version called {@code setPixels} once per output row and recomputed min/max for every sample.
   */
  private static Bitmap areaResize(Bitmap bitmap, int width, int height) {
    int w = bitmap.getWidth(), h = bitmap.getHeight();
    int[] pixels = new int[w * h];
    bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
    int[] out = new int[width * height];

    double stepX = w / (double) width, stepY = h / (double) height;
    double area = stepX * stepY;

    // Per-axis overlap: which source cells cover a destination cell, and by how much.
    int maxSpan = (int) Math.ceil(Math.max(stepX, stepY)) + 2;
    int[] xFrom = new int[width], xSpan = new int[width];
    // Horizontal weights are kept per destination column. With a fractional stepX the first and
    // last source cell of a column are only partly covered, and how much differs from column to
    // column, so one shared scratch array cannot hold them: a previous version filled a single
    // array in this loop and used it afterwards, leaving every column on the last column's weights.
    // Columns whose span is 1 then summed to almost no coverage, and the resulting transparent
    // strips drew as evenly spaced vertical hairlines across the preview.
    double[] xWeights = new double[width * maxSpan];
    for (int x = 0; x < width; x++) {
      double left = x * stepX, right = Math.min(w, left + stepX);
      int from = (int) left, span = Math.max(1, (int) Math.ceil(right) - from);
      xFrom[x] = from;
      xSpan[x] = span;
      int base = x * maxSpan;
      for (int i = 0; i < span; i++) {
        double cell = from + i;
        xWeights[base + i] = Math.min(right, cell + 1) - Math.max(left, cell);
      }
    }

    double[] yWeight = new double[maxSpan];
    for (int y = 0; y < height; y++) {
      double top = y * stepY, bottom = Math.min(h, top + stepY);
      int from = (int) top;
      int span = Math.max(1, (int) Math.ceil(bottom) - from);
      for (int j = 0; j < span; j++) {
        double cell = from + j;
        yWeight[j] = Math.min(bottom, cell + 1) - Math.max(top, cell);
      }
      int rowStart = y * width;
      for (int x = 0; x < width; x++) {
        double alpha = 0, red = 0, green = 0, blue = 0;
        int firstColumn = xFrom[x], columns = xSpan[x], base = x * maxSpan;
        for (int j = 0; j < span; j++) {
          double weightY = yWeight[j];
          int row = (from + j) * w + firstColumn;
          for (int i = 0; i < columns; i++) {
            int color = pixels[row + i];
            // Premultiply by the source alpha so transparent edges do not darken the average.
            double weight = weightY * xWeights[base + i] * (color >>> 24);
            alpha += weight;
            red += weight * ((color >>> 16) & 255);
            green += weight * ((color >>> 8) & 255);
            blue += weight * (color & 255);
          }
        }
        if (alpha <= 0) {
          out[rowStart + x] = 0;
          continue;
        }
        // Clamp before shifting: a value above 255 would spill into the colour bits.
        int alpha8 = Math.min(255, (int) Math.round(alpha / area));
        out[rowStart + x] =
            (alpha8 << 24)
                | (Math.min(255, (int) Math.round(red / alpha)) << 16)
                | (Math.min(255, (int) Math.round(green / alpha)) << 8)
                | Math.min(255, (int) Math.round(blue / alpha));
      }
    }

    Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    result.setPixels(out, 0, width, 0, 0, width, height);
    return result;
  }
}
