// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.util.Locale;
import java.util.regex.Pattern;

/** Static-image and camera-RAW allow-list shared across gallery and file import. */
final class PhotoFormatPolicy {
  private static final String RAW_EXT_GROUP =
      "dng|rw2|arw|cr2|cr3|nef|nrw|raf|orf|pef|srw|3fr|iiq|sr2|srf|raw|gpr|dcr|kdc|mrw|x3f|erf";
  private static final Pattern SUPPORTED_EXTENSION =
      Pattern.compile(".*\\.(?:jpe?g|png|heic|heif|" + RAW_EXT_GROUP + ")$", Pattern.CASE_INSENSITIVE);
  private static final Pattern RAW_EXTENSION =
      Pattern.compile(".*\\.(" + RAW_EXT_GROUP + ")$", Pattern.CASE_INSENSITIVE);
  private static final Pattern ANY_EXTENSION =
      Pattern.compile(".*\\.[a-z0-9]{2,6}$", Pattern.CASE_INSENSITIVE);

  private PhotoFormatPolicy() {}

  static boolean supported(String name, String mime) {
    String safeName = name == null ? "" : name;
    if (ANY_EXTENSION.matcher(safeName).matches())
      return SUPPORTED_EXTENSION.matcher(safeName).matches();
    String normalizedMime = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
    if (normalizedMime.equals("image/jpeg")
        || normalizedMime.equals("image/png")
        || normalizedMime.equals("image/heic")
        || normalizedMime.equals("image/heif")
        || isRawMime(normalizedMime)) return true;
    return SUPPORTED_EXTENSION.matcher(safeName).matches();
  }

  static boolean isRaw(String name, String mime) {
    String safeName = name == null ? "" : name;
    if (ANY_EXTENSION.matcher(safeName).matches())
      return RAW_EXTENSION.matcher(safeName).matches();
    String normalizedMime = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
    return isRawMime(normalizedMime);
  }

  static String rawBadge(String name) {
    if (name == null) return "";
    java.util.regex.Matcher m = RAW_EXTENSION.matcher(name);
    return m.matches() ? m.group(1).toUpperCase(Locale.ROOT) : "";
  }

  private static boolean isRawMime(String normalizedMime) {
    return normalizedMime.equals("image/x-adobe-dng")
        || normalizedMime.equals("image/dng")
        || normalizedMime.equals("image/x-panasonic-rw2")
        || normalizedMime.equals("image/x-panasonic-raw")
        || normalizedMime.equals("image/x-sony-arw")
        || normalizedMime.equals("image/x-sony-sr2")
        || normalizedMime.equals("image/x-sony-srf")
        || normalizedMime.equals("image/x-canon-cr2")
        || normalizedMime.equals("image/x-canon-cr3")
        || normalizedMime.equals("image/x-nikon-nef")
        || normalizedMime.equals("image/x-nikon-nrw")
        || normalizedMime.equals("image/x-fuji-raf")
        || normalizedMime.equals("image/x-olympus-orf")
        || normalizedMime.equals("image/x-pentax-pef")
        || normalizedMime.equals("image/x-samsung-srw")
        || normalizedMime.equals("image/x-dcraw")
        || normalizedMime.equals("image/x-raw")
        || normalizedMime.equals("image/raw");
  }
}
