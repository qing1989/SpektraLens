// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Bitmap;

/** Single place where bitmaps are handed back, so no call site has to repeat the guards. */
final class Bitmaps {
  private Bitmaps() {}

  /** Safe to call with null or an already released bitmap. */
  static void release(Bitmap bitmap) {
    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
  }
}
