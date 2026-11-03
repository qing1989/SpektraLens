// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Bitmap;

/** All calls belong to the single render executor. Bitmap input/output is encoded sRGB. */
public final class NativeEngine {
  static {
    System.loadLibrary("spektra");
  }

  public static native void configureBackend(String mode, String libraries, String statePath);

  public static native String bootStatus();

  static native String gpuCheck(String resources, String check);

  static native void gpuTestStorageLimit(long bytes);

  public static native long create(String resources);

  public static native String capabilities(long engine);

  public static native void open(long engine, Bitmap source, String params);

  public static native Bitmap render(long engine, String params, boolean full);

  public static native String inspectRaw(String path);

  public static native Bitmap rawPreview(long engine, String path, int longEdge);

  public static native void openRaw(long engine, String path, String params, int longEdge);

  public static native Bitmap renderEdited(long engine, String params, String edits, boolean full);

  public static native Bitmap editBitmap(Bitmap source, String edits);

  public static native String dimensions(int width, int height, String edits);

  public static native String exportImage(
      long engine, String params, String edits, String path, String format);

  public static native void closeImage(long engine);

  public static native void destroy(long engine);
}
