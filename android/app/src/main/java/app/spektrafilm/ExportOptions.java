// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

final class ExportOptions {
  static final int JPEG = 0, PNG = 1, TIFF = 2, SHARE_JPEG = 3;
  static final int HIGH = 95, RECOMMENDED = 90, COMPACT = 85;

  final int mode;
  final int jpegQuality;

  ExportOptions(int mode, int jpegQuality) {
    if (mode < JPEG || mode > SHARE_JPEG)
      throw new IllegalArgumentException("Unsupported export format");
    if (jpegQuality != HIGH && jpegQuality != RECOMMENDED && jpegQuality != COMPACT)
      throw new IllegalArgumentException("Unsupported JPEG quality");
    this.mode = mode;
    this.jpegQuality = jpegQuality;
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

  static long[] estimateJpegBytes(
      long sampleBytes, int sampleWidth, int sampleHeight, int outputWidth, int outputHeight) {
    if (sampleBytes <= 0 || sampleWidth <= 0 || sampleHeight <= 0
        || outputWidth <= 0 || outputHeight <= 0) return null;
    double ratio = (double) outputWidth * outputHeight / ((double) sampleWidth * sampleHeight);
    double estimate = Math.max(1, sampleBytes - 1024) * ratio + 1024;
    return new long[] {
      Math.max(1024, Math.round(estimate * .6)),
      Math.max(2048, Math.round(estimate * 1.8))
    };
  }
}
