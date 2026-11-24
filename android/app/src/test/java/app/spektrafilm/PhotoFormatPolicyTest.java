// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

public final class PhotoFormatPolicyTest {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    for (String mime :
        new String[] {
          "image/jpeg",
          "image/png",
          "image/heic",
          "image/heif",
          "image/x-adobe-dng",
          "image/dng"
        }) check(PhotoFormatPolicy.supported("photo", mime), "supported MIME " + mime);
    for (String name :
        new String[] {"photo.JPG", "photo.jpeg", "photo.png", "photo.HEIC", "photo.heif", "photo.DNG"})
      check(PhotoFormatPolicy.supported(name, "application/octet-stream"), "supported " + name);
    for (String mime : new String[] {"video/mp4", "image/gif", "image/webp", "image/avif"})
      check(!PhotoFormatPolicy.supported("photo", mime), "rejected MIME " + mime);
    check(
        !PhotoFormatPolicy.supported("animated.gif", "image/jpeg"),
        "unsupported extension wins over a misleading vendor MIME");
    System.out.println("PhotoFormatPolicy: " + checks + " checks passed");
  }
}
