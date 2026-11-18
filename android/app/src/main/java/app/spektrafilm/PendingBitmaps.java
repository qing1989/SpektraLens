// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Bitmap;
import android.os.Handler;
import java.util.HashSet;
import java.util.Set;

/** Owns images crossing a Handler boundary until the receiver takes ownership or the owner closes. */
final class PendingBitmaps implements AutoCloseable {
  private final Set<Bitmap> pending = new HashSet<>();
  private boolean closed;

  synchronized void post(Handler handler, Bitmap image, Runnable receive) {
    if (closed) {
      Bitmaps.release(image);
      return;
    }
    if (image != null) pending.add(image);
    if (!handler.post(() -> {
      synchronized (PendingBitmaps.this) {
        if (closed) return; // close() already released every unreceived image.
        if (image != null) pending.remove(image);
      }
      receive.run();
    })) {
      pending.remove(image);
      Bitmaps.release(image);
    }
  }

  @Override public synchronized void close() {
    closed = true;
    for (Bitmap image : pending) Bitmaps.release(image);
    pending.clear();
  }
}
