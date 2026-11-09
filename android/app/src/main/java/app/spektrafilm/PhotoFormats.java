// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * Recognise ordinary raster headers before considering a RAW decoder, regardless of file suffix.
 */
final class PhotoFormats {
  static boolean isRaster(File file) throws IOException {
    byte[] b = new byte[64];
    int n;
    try (InputStream in = new FileInputStream(file)) {
      n = in.read(b);
    }
    if (n < 2) return false;
    if ((b[0] & 255) == 255 && (b[1] & 255) == 216) return true; // JPEG, including Ultra HDR JPEG
    if (n >= 8 && (b[0] & 255) == 137 && ascii(b, 1, 3).equals("PNG")) return true;
    if (n >= 6 && (ascii(b, 0, 6).equals("GIF87a") || ascii(b, 0, 6).equals("GIF89a"))) return true;
    if (b[0] == 'B' && b[1] == 'M') return true;
    if (n >= 12 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP")) return true;
    if (n >= 12 && ascii(b, 4, 4).equals("ftyp")) {
      // HEIF/HEIC and AVIF are raster containers; Canon CR3's crx brand is deliberately excluded.
      for (int i = 8; i + 4 <= n; i += 4) {
        String brand = ascii(b, i, 4);
        if (brand.equals("heic")
            || brand.equals("heix")
            || brand.equals("hevc")
            || brand.equals("hevx")
            || brand.equals("mif1")
            || brand.equals("msf1")
            || brand.equals("avif")
            || brand.equals("avis")) return true;
      }
    }
    return false; // TIFF/DNG/other camera containers still need RAW inspection.
  }

  private static String ascii(byte[] b, int start, int length) {
    return new String(b, start, length, StandardCharsets.US_ASCII);
  }
}
