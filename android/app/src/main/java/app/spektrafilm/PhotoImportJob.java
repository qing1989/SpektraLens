// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.os.CancellationSignal;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One import owns its cancellation, watchdog and unique temporary file. No renderer dependency. */
final class PhotoImportJob {
  final CancellationSignal signal = new CancellationSignal();
  final AtomicBoolean cancelled = new AtomicBoolean();
  final File temp;
  final String initialSettings;
  final long started = SystemClock.elapsedRealtime();
  volatile long lastProgress = started, stageStarted = started, copied, total = -1;
  volatile String phase = "读取照片信息", name = "导入的照片";
  volatile Closeable input;
  volatile boolean raw;

  PhotoImportJob(File directory) throws IOException {
    this(directory, null);
  }

  PhotoImportJob(File directory, String initialSettings) throws IOException {
    this.initialSettings = initialSettings;
    temp = File.createTempFile("import-", ".pending", directory);
  }

  void stage(String phase) {
    this.phase = phase;
    lastProgress = stageStarted = SystemClock.elapsedRealtime();
  }

  void check() {
    if (cancelled.get()) throw new android.os.OperationCanceledException();
  }

  boolean expired(long now) {
    return now - lastProgress > (raw ? 120_000 : 30_000);
  }

  void cancel() {
    if (!cancelled.compareAndSet(false, true)) return;
    // A provider cancellation is a Binder call; never run it or stream.close() on the UI thread.
    new Thread(
            () -> {
              try {
                signal.cancel();
              } catch (RuntimeException ignored) {
                // 已经取消的任务再次取消会抛，忽略
              }
              Closeable c = input;
              if (c != null)
                try {
                  c.close();
                } catch (IOException ignored) {
                  // 收尾时关闭失败无法补救，忽略
                }
            },
            "SpektraLens-import-cancel")
        .start();
  }
}
