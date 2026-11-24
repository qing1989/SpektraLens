// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

final class ExportOptions {
  static final int JPEG = 0, PNG = 1, TIFF = 2, SHARE_JPEG = 3;
  static final int HIGH = 95, RECOMMENDED = 90, COMPACT = 85;
  private static final long FIXED_MEMORY = 128L * 1024 * 1024;

  final int mode;
  final int jpegQuality;
  /** 0 keeps the source resolution; a positive value is the maximum render long edge. */
  final int maxLongEdge;

  ExportOptions(int mode, int jpegQuality) {
    this(mode, jpegQuality, 0);
  }

  ExportOptions(int mode, int jpegQuality, int maxLongEdge) {
    if (mode < JPEG || mode > SHARE_JPEG)
      throw new IllegalArgumentException("Unsupported export format");
    if (jpegQuality != HIGH && jpegQuality != RECOMMENDED && jpegQuality != COMPACT)
      throw new IllegalArgumentException("Unsupported JPEG quality");
    if (maxLongEdge < 0) throw new IllegalArgumentException("Invalid export edge");
    this.mode = mode;
    this.jpegQuality = jpegQuality;
    this.maxLongEdge = maxLongEdge;
  }

  boolean isJpeg() {
    return mode == JPEG || mode == SHARE_JPEG;
  }

  static String qualityLabel(int quality) {
    return switch (quality) {
      case HIGH -> "高画质";
      case RECOMMENDED -> "推荐";
      case COMPACT -> "节省空间";
      default -> throw new IllegalArgumentException("Unsupported JPEG quality");
    };
  }

  static String qualityDescription(int quality) {
    return switch (quality) {
      case HIGH -> "尽量保留颗粒与细节";
      case RECOMMENDED -> "适合日常保存与分享";
      case COMPACT -> "文件更小，细节可能略有损失";
      default -> throw new IllegalArgumentException("Unsupported JPEG quality");
    };
  }

  /** Conservative peak-memory budget used before starting a full-size export. */
  static long estimateBytes(int width, int height) {
    return (long) width * height * 100 + FIXED_MEMORY;
  }

  static long estimateBytes(int width, int height, int outputWidth, int outputHeight) {
    // Additional 16-bit working pixels and encoding buffers outside the source image.
    long extraPixels = Math.max(0, (long) outputWidth * outputHeight - (long) width * height);
    return estimateBytes(width, height) + extraPixels * 28;
  }

  /** Preserve aspect ratio; 0 means the source resolution. */
  static int[] scaledSize(int width, int height, int maxLongEdge) {
    int longest = Math.max(width, height);
    if (maxLongEdge <= 0 || longest <= maxLongEdge) return new int[] {width, height};
    double scale = maxLongEdge / (double) longest;
    return new int[] {
      Math.max(1, (int) Math.round(width * scale)),
      Math.max(1, (int) Math.round(height * scale))
    };
  }

  /**
   * Long edge whose conservative estimate fits in 80% of the available bytes. Returns 0 when the
   * device cannot fit even a 1024px export.
   */
  static int safeLongEdge(int width, int height, long availableBytes) {
    return safeLongEdge(width, height, availableBytes, estimateBytes(width, height));
  }

  static int safeLongEdge(int width, int height, long availableBytes, long estimate) {
    double budget = availableBytes * .80 - FIXED_MEMORY;
    if (budget <= 0) return 0;
    double scale = Math.sqrt(budget / (estimate - FIXED_MEMORY));
    int edge = Math.min(Math.max(width, height),
                        (int) Math.floor(Math.max(width, height) * scale));
    return edge >= 1024 ? edge : 0;
  }

  static long[] estimateJpegBytes(
      long sampleBytes, int sampleWidth, int sampleHeight, int outputWidth, int outputHeight) {
    if (sampleBytes <= 0 || sampleWidth <= 0 || sampleHeight <= 0
        || outputWidth <= 0 || outputHeight <= 0) return null;
    double ratio = (double) outputWidth * outputHeight / ((double) sampleWidth * sampleHeight);
    double estimate = Math.max(1, sampleBytes - 1024) * ratio + 4096;
    return new long[] {
      Math.max(1024, Math.round(estimate * .80)),
      Math.max(2048, Math.round(estimate * 1.20))
    };
  }

  /** Native TIFF output is uncompressed 16-bit RGB; metadata overhead is bounded and small. */
  static long estimateTiffBytes(int width, int height) {
    if (width <= 0 || height <= 0) return 0;
    return (long) width * height * 6 + 32L * 1024;
  }

  /**
   * PNG16 uses the native zlib level-3 encoder with filter byte 0. The caller supplies the
   * compressed/raw ratio measured on a downscaled 16-bit sample; the factor covers the finer
   * low-byte variation of a true 16-bit export.
   */
  static long[] estimatePngBytes(double compressedRatio, int width, int height) {
    if (compressedRatio <= 0 || width <= 0 || height <= 0) return null;
    double estimate = (long) width * height * 6 * compressedRatio * 1.20 + 32L * 1024;
    return new long[] {
      Math.max(1024, Math.round(estimate * .80)),
      Math.max(2048, Math.round(estimate * 1.20))
    };
  }
}
