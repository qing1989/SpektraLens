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
          "image/dng",
          "image/x-panasonic-rw2",
          "image/x-sony-arw",
          "image/x-canon-cr2",
          "image/x-canon-cr3",
          "image/x-nikon-nef",
          "image/x-fuji-raf",
          "image/x-olympus-orf",
          "image/x-dcraw"
        }) check(PhotoFormatPolicy.supported("photo", mime), "supported MIME " + mime);
    for (String name :
        new String[] {
          "photo.JPG",
          "photo.jpeg",
          "photo.png",
          "photo.HEIC",
          "photo.heif",
          "photo.DNG",
          "P1151055.RW2",
          "DSC001.ARW",
          "IMG_001.CR2",
          "IMG_002.CR3",
          "DSC_001.NEF",
          "DSCF001.RAF",
          "P100.ORF",
          "IMGP001.PEF",
          "SAM001.SRW",
          "A001.3FR",
          "B001.IIQ"
        })
      check(PhotoFormatPolicy.supported(name, "application/octet-stream"), "supported " + name);
    for (String rawName :
        new String[] {"photo.DNG", "P1151055.RW2", "DSC001.ARW", "IMG.CR3", "DSC.NEF", "F.RAF"}) {
      check(PhotoFormatPolicy.isRaw(rawName, "application/octet-stream"), "isRaw " + rawName);
      check(!PhotoFormatPolicy.rawBadge(rawName).isEmpty(), "rawBadge " + rawName);
    }
    check("RW2".equals(PhotoFormatPolicy.rawBadge("P1151055.RW2")), "RW2 badge");
    check(!PhotoFormatPolicy.isRaw("photo.JPG", "image/jpeg"), "JPG is not RAW");
    check(PhotoFormatPolicy.isRaw("photo", "image/x-panasonic-rw2"), "RAW MIME without extension");
    for (String mime : new String[] {"video/mp4", "image/gif", "image/webp", "image/avif"})
      check(!PhotoFormatPolicy.supported("photo", mime), "rejected MIME " + mime);
    check(
        !PhotoFormatPolicy.supported("animated.gif", "image/jpeg"),
        "unsupported extension wins over a misleading vendor MIME");
    check(
        !PhotoFormatPolicy.supported("clip.mp4", "application/octet-stream"),
        "video extension rejected");
    check(
        !PhotoFormatPolicy.supported("document.pdf", "application/octet-stream"),
        "non-photo document rejected");
    System.out.println("PhotoFormatPolicy: " + checks + " checks passed");
  }
}
