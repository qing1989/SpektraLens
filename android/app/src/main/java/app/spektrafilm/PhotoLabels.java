// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.*;

/** Display-only labels; original filenames and stored source files are never renamed. */
final class PhotoLabels {
  private static final Pattern EXT =
      Pattern.compile(
          "(?i)\\.(jpe?g|png|heic|heif|avif|webp|gif|bmp|dng|nef|arw|cr2|cr3|raf|rw2|orf|pef|srw|tiff?)$");

  static String title(String name) {
    return EXT.matcher(name).replaceFirst("");
  }

  static String format(File file, String name) {
    try (InputStream in = new FileInputStream(file)) {
      byte[] b = new byte[64];
      int n = in.read(b);
      if (n >= 2 && (b[0] & 255) == 255 && (b[1] & 255) == 216) return "JPEG";
      if (n >= 4 && b[0] == (byte) 137 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "PNG";
      String header = new String(b, 0, Math.max(0, n), StandardCharsets.ISO_8859_1);
      if (header.startsWith("GIF8")) return "GIF";
      if (header.startsWith("BM")) return "BMP";
      if (header.startsWith("RIFF")
          && header.length() >= 12
          && header.substring(8, 12).equals("WEBP")) return "WEBP";
      if (header.length() >= 12 && header.substring(4, 8).equals("ftyp")) {
        if (header.contains("avif") || header.contains("avis")) return "AVIF";
        if (header.contains("heic")
            || header.contains("heix")
            || header.contains("hevc")
            || header.contains("hevx")) return "HEIC";
        if (header.contains("mif1") || header.contains("msf1")) return "HEIF";
      }
      if (!EXT.matcher(name).find()
          && (header.startsWith("II*\u0000") || header.startsWith("MM\u0000*"))) return "TIFF";
    } catch (IOException ignored) {
    }
    Matcher extension = EXT.matcher(name);
    if (!extension.find()) return "RAW / 图片";
    String format = extension.group(1).toUpperCase(Locale.ROOT);
    return format.equals("JPG") ? "JPEG" : format.equals("TIF") ? "TIFF" : format;
  }
}
