// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

public final class ExportOptionsTest {
  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void reject(int mode, int quality) {
    try {
      new ExportOptions(mode, quality);
      throw new AssertionError("Invalid export options accepted: " + mode + "/" + quality);
    } catch (IllegalArgumentException expected) {
      return;
    }
  }

  public static void main(String[] args) {
    check(ExportOptions.RECOMMENDED == 90, "Default quality must be 90");
    int[] qualities = {95, 90, 85};
    for (int quality : qualities) {
      ExportOptions save = new ExportOptions(ExportOptions.JPEG, quality);
      ExportOptions share = new ExportOptions(ExportOptions.SHARE_JPEG, quality);
      check(save.isJpeg() && share.isJpeg(), "Save and share must both encode JPEG");
      check(save.jpegQuality == share.jpegQuality, "Sharing must preserve chosen quality");
      check(!ExportOptions.qualityLabel(quality).isEmpty(), "Quality needs a label");
      check(!ExportOptions.qualityDescription(quality).isEmpty(), "Quality needs a description");
      check(!new ExportOptions(ExportOptions.PNG, quality).isJpeg(), "PNG is lossless");
      check(!new ExportOptions(ExportOptions.TIFF, quality).isJpeg(), "TIFF is lossless");
    }
    for (int quality : new int[] {-1, 0, 70, 82, 89, 96, 100, 101})
      reject(ExportOptions.JPEG, quality);
    reject(-1, 90);
    reject(4, 90);

    long[] original = ExportOptions.estimateJpegBytes(120000, 600, 400, 6000, 4000);
    long[] portrait = ExportOptions.estimateJpegBytes(120000, 400, 600, 4000, 6000);
    check(original != null && original[0] > 0 && original[1] > original[0], "Positive size range");
    check(original[0] == portrait[0] && original[1] == portrait[1], "Orientation-independent estimate");
    long[] reduced = ExportOptions.estimateJpegBytes(60000, 600, 400, 6000, 4000);
    check(reduced[0] < original[0] && reduced[1] < original[1], "Smaller sample reduces estimate");
    long[] huge = ExportOptions.estimateJpegBytes(200000, 768, 512, 100000, 100000);
    check(huge[0] > 0 && huge[1] > huge[0], "Pixel area cannot overflow int");
    check(ExportOptions.estimateJpegBytes(0, 600, 400, 6000, 4000) == null, "Empty sample unknown");
    check(ExportOptions.estimateJpegBytes(120000, 0, 400, 6000, 4000) == null, "Invalid sample unknown");
    check(ExportOptions.estimateJpegBytes(120000, 600, 400, 0, 4000) == null, "Unknown output unknown");
    System.out.println("ExportOptionsTest: presets, sharing, lossless modes and size estimates passed");
  }
}
