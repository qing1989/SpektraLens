// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.*;

/** Same native spectral pipeline, with a bundled CPU Vulkan implementation for compatibility. */
final class SpectralRenderer implements AutoCloseable {
  private final Context context;
  private volatile RendererConnection connection;
  private volatile boolean closed;
  private volatile String backend = "", capabilities = "", fallbackReason = "";
  private String resourceRoot;
  private Bundle lastOpen;
  private int lastOpenCode;
  private long testGpuStorageLimit;

  SpectralRenderer(Context c) {
    context = c.getApplicationContext();
  }

  static String preferredBackend(String preference) {
    return "cpu".equals(preference) ? "cpu" : "gpu";
  }

  String start(String resources, String preference) {
    return start(resources, preference, 15000, 0);
  }

  String start(String resources, String preference, long gpuBudgetMs, long testGpuDelayMs) {
    return start(resources, preference, gpuBudgetMs, testGpuDelayMs, 0);
  }

  String start(
      String resources,
      String preference,
      long gpuBudgetMs,
      long testGpuDelayMs,
      long storageLimit) {
    if (closed) throw new IllegalStateException("Renderer disposed");
    testGpuStorageLimit = storageLimit;
    resourceRoot = resources;
    boolean cpu = preferredBackend(preference).equals("cpu");
    try {
      capabilities =
          createSession(
              resources, cpu ? "cpu" : "gpu", cpu ? 90000 : gpuBudgetMs, cpu ? 0 : testGpuDelayMs);
    } catch (RuntimeException ex) {
      RendererConnection failed = connection;
      if (failed != null) failed.close();
      if (cpu || closed) throw ex;
      fallbackReason = ex.getMessage() == null ? ex.toString() : ex.getMessage();
      capabilities = createSession(resources, "cpu", 90000, 0);
    }
    if (closed) throw new IllegalStateException("Renderer disposed");
    return capabilities;
  }

  private String createSession(String resources, String mode, long budget, long delay) {
    for (int attempt = 0; attempt < 2; attempt++) {
      RendererConnection next = replaceConnection(mode);
      try {
        return next.create(resources, budget, delay, mode.equals("gpu") ? testGpuStorageLimit : 0);
      } catch (IllegalStateException ex) {
        boolean retry = ex instanceof RendererConnection.StaleSessionException;
        for (Throwable cause = ex; cause != null; cause = cause.getCause())
          if (cause instanceof DeadObjectException) retry = true;
        next.close();
        // A bind can still receive a retiring service's binder during rapid Activity recreation.
        // Retry only that dead/stale startup session; shader errors and timeouts retain their
        // policy.
        if (attempt != 0 || closed || !retry) throw ex;
      }
    }
    throw new IllegalStateException("无法建立新的显影会话");
  }

  private synchronized RendererConnection replaceConnection(String mode) {
    if (closed) throw new IllegalStateException("Renderer disposed");
    if (connection != null) connection.close();
    backend = mode;
    return connection = new RendererConnection(context, mode);
  }

  String backend() {
    return backend;
  }

  boolean isAlive() {
    return connection != null && connection.isAlive();
  }

  String fallbackReason() {
    return fallbackReason;
  }

  int processId() {
    return connection == null ? 0 : connection.processId();
  }

  String capabilities() {
    return capabilities;
  }

  private Bundle request(int op, Bundle args, long deadline) {
    if (connection == null) throw new IllegalStateException("显影引擎尚未初始化");
    try {
      return connection.request(op, args, deadline);
    } catch (IllegalStateException ex) {
      String message = String.valueOf(ex.getMessage()).toLowerCase(java.util.Locale.ROOT);
      boolean graphicsFailure =
          !connection.isAlive()
              || message.contains("vulkan")
              || message.contains("vkresult")
              || message.contains("maxstoragebufferrange")
              || message.contains("device_lost")
              || message.contains("响应超时")
              || message.contains("out of device memory")
              || message.contains("no legal band boundary");
      boolean operation =
          op == RendererService.RENDER
              || op == RendererService.EXPORT
              || op == RendererService.OPEN
              || op == RendererService.OPEN_RAW
              || op == RendererService.OPEN_FILE
              || op == RendererService.RAW_PREVIEW;
      if (closed || !backend.equals("gpu") || !graphicsFailure || !operation) throw ex;
      fallbackReason = String.valueOf(ex.getMessage());
      connection.close();
      capabilities = createSession(resourceRoot, "cpu", 90000, 0);
      if (op == RendererService.RENDER || op == RendererService.EXPORT) {
        if (lastOpen == null) throw new IllegalStateException("照片需要重新打开", ex);
        Bitmap bitmap = lastOpen.getParcelable("bitmap");
        if (bitmap != null && bitmap.isRecycled())
          throw new IllegalStateException("照片预览已切换，请重试", ex);
        connection.request(lastOpenCode, lastOpen, 120000);
      }
      return connection.request(op, args, deadline);
    }
  }

  void open(Bitmap bitmap, String params) {
    Bundle a = new Bundle();
    a.putParcelable("bitmap", bitmap);
    a.putString("params", params);
    lastOpen = a;
    lastOpenCode = RendererService.OPEN;
    request(RendererService.OPEN, a, 60000);
  }

  void openFile(String path, String params) {
    Bundle a = new Bundle();
    a.putString("path", path);
    a.putString("params", params);
    lastOpen = a;
    lastOpenCode = RendererService.OPEN_FILE;
    request(RendererService.OPEN_FILE, a, 120000);
  }

  void openRaw(String path, String params, int edge) {
    Bundle a = new Bundle();
    a.putString("path", path);
    a.putString("params", params);
    a.putInt("edge", edge);
    lastOpen = a;
    lastOpenCode = RendererService.OPEN_RAW;
    request(RendererService.OPEN_RAW, a, 120000);
  }

  Bitmap rawPreview(String path, int edge) {
    Bundle a = new Bundle();
    a.putString("path", path);
    a.putInt("edge", edge);
    return request(RendererService.RAW_PREVIEW, a, 120000).getParcelable("bitmap");
  }

  Bitmap render(String params, String edits, boolean full) {
    Bundle a = new Bundle();
    a.putString("params", params);
    a.putString("edits", edits);
    a.putBoolean("full", full);
    return request(RendererService.RENDER, a, full ? 180000 : 60000).getParcelable("bitmap");
  }

  String exportImage(String params, String edits, String path, String format) {
    Bundle a = new Bundle();
    a.putString("params", params);
    a.putString("edits", edits);
    a.putString("path", path);
    a.putString("format", format);
    return request(RendererService.EXPORT, a, 240000).getString("text");
  }

  void closeImage() {
    try {
      request(RendererService.CLOSE_IMAGE, new Bundle(), 3000);
    } finally {
      lastOpen = null;
    }
  }

  public synchronized void close() {
    closed = true;
    lastOpen = null;
    if (connection != null) connection.close();
  }
}
