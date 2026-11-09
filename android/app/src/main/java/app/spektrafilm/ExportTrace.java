// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Records the real export stages, including queueing and runtime GPU fallback. */
final class ExportTrace {
  private final AtomicFile file;
  private final JSONObject record = new JSONObject(), times = new JSONObject();
  private final long started = SystemClock.elapsedRealtime();
  private long stageStarted = started;
  private String stageKey = "queue_ms";

  ExportTrace(Context context) {
    file = new AtomicFile(new File(context.getFilesDir(), "last_export_diagnostic.json"));
    put("app_version", "0.3.6");
    put("device", Build.MANUFACTURER + " " + Build.MODEL);
    put("android_api", Build.VERSION.SDK_INT);
    put("stage", "等待导出");
    put("outcome", "running");
    put("timings", times);
    save();
  }

  void put(String key, Object value) {
    try {
      record.put(key, value);
    } catch (JSONException ex) {
      throw new IllegalArgumentException(ex);
    }
  }

  private void checkpoint() {
    try {
      long now = SystemClock.elapsedRealtime();
      times.put(stageKey, now - stageStarted);
      stageStarted = now;
      record.put("elapsed_ms", now - started);
    } catch (JSONException ex) {
      throw new IllegalArgumentException(ex);
    }
  }

  void phase(String key, String label) {
    checkpoint();
    stageKey = key;
    put("stage", label);
    save();
  }

  void finish(Throwable failure) {
    checkpoint();
    put("outcome", failure == null ? "completed" : "failed");
    if (failure != null) put("error", failure.toString());
    save();
  }

  private void save() {
    FileOutputStream stream = null;
    try {
      stream = file.startWrite();
      stream.write(record.toString(2).getBytes(StandardCharsets.UTF_8));
      file.finishWrite(stream);
    } catch (Exception ex) {
      if (stream != null) file.failWrite(stream);
      android.util.Log.w("SpektraLensAndroid", "Cannot save export timings", ex);
    }
  }
}
