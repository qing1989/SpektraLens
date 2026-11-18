// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.util.Locale;
import java.util.regex.Pattern;

/** Static-image allow-list and Motion Photo / Live Photo rejection shared with the gallery. */
final class PhotoFormatPolicy {
  private static final Pattern SUPPORTED_EXTENSION =
      Pattern.compile(".*\\.(?:jpe?g|png|heic|heif|dng)$", Pattern.CASE_INSENSITIVE);
  private static final Pattern ANY_EXTENSION =
      Pattern.compile(".*\\.[a-z0-9]{2,6}$", Pattern.CASE_INSENSITIVE);
  private static final Pattern MOVING_PHOTO =
      Pattern.compile(
          "(?i)(?:motionphoto\\s*[=:]\\s*[\"']?1|microvideo|"
              + "live[-_ ]?photo|com\\.apple\\.quicktime\\.content\\.identifier|"
              + "apple\\s*:\\s*contentidentifier)");

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
        || normalizedMime.equals("image/x-adobe-dng")
        || normalizedMime.equals("image/dng")) return true;
    return SUPPORTED_EXTENSION.matcher(safeName).matches();
  }

  static boolean movingPhotoMetadata(String xmp) {
    return xmp != null && MOVING_PHOTO.matcher(xmp).find();
  }
}
