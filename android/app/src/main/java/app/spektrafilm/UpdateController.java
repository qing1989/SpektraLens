// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.widget.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Only update metadata and APK bytes cross the network; no photo or device data is read. */
final class UpdateController {
  static final String MANIFEST = "https://github.com/qing1989/SpektraLens/releases/latest/download/latest.json";
  static final String RELEASES = "https://github.com/qing1989/SpektraLens/releases/latest";
  private final Activity activity;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean cancelled = new AtomicBoolean();
  private volatile HttpURLConnection connection;
  private boolean busy, closed;
  private File updateFile;
  private AlertDialog progress;
  private final String version;
  private final long code;

  UpdateController(Activity activity) {
    this.activity = activity;
    updateFile = new File(activity.getCacheDir(), "updates/" + java.util.UUID.randomUUID() + ".apk");
    try {
      android.content.pm.PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
      version = info.versionName; code = info.getLongVersionCode();
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  void check() {
    if (busy || closed) return;
    busy = true; cancelled.set(false);
    progress = new Haptics.DialogBuilder(activity).setTitle("检查更新")
        .setMessage("正在检查新版本…").setNegativeButton("取消", (d, n) -> cancel()).create();
    progress.setOnCancelListener(d -> cancel()); progress.show();
    worker.execute(() -> {
      try {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        transfer(MANIFEST, bytes, 65536, null);
        UpdateManifest manifest = UpdateManifest.parse(bytes.toString(StandardCharsets.UTF_8.name()));
        post(() -> {
          finishProgress();
          if (!manifest.isNewerThan(code)) { message("已是最新版本", "当前版本为 " + version + "。"); return; }
          if (Build.VERSION.SDK_INT < manifest.minSdk) {
            message("暂时无法更新", "新版本需要 Android API " + manifest.minSdk + " 或更高版本。"); return;
          }
          new Haptics.DialogBuilder(activity).setTitle("发现新版本 " + manifest.versionName)
              .setMessage(manifest.releaseNotes.isEmpty() ? "此版本未提供更新说明。" : manifest.releaseNotes)
              .setNegativeButton("稍后", null).setPositiveButton("下载", (d, n) -> download(manifest)).show();
        });
      } catch (Exception e) { fail("暂时无法检查更新，请稍后重试。", e); }
    });
  }

  private void download(UpdateManifest manifest) {
    if (busy || closed) return;
    busy = true; cancelled.set(false);
    LinearLayout content = new LinearLayout(activity); content.setOrientation(LinearLayout.VERTICAL);
    int pad = (int) (24 * activity.getResources().getDisplayMetrics().density);
    content.setPadding(pad, pad, pad, pad);
    ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
    bar.setMax(100); bar.setIndeterminate(manifest.sizeBytes == UpdateManifest.MAX_APK_BYTES);
    TextView label = new TextView(activity); label.setText("正在下载…");
    content.addView(label); content.addView(bar);
    progress = new Haptics.DialogBuilder(activity).setTitle("下载更新 " + manifest.versionName)
        .setView(content).setNegativeButton("取消", (d, n) -> cancel()).create();
    progress.setOnCancelListener(d -> cancel()); progress.show();
    worker.execute(() -> {
      File file = apk();
      try {
        File directory = file.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("cache directory");
        Exception last = null;
        boolean verified = false;
        for (String url : manifest.urls) {
          if (cancelled.get()) throw new InterruptedIOException();
          try {
            try (OutputStream out = new FileOutputStream(file)) {
              transfer(url, out, manifest.sizeBytes, count -> post(() -> {
                bar.setProgress((int) (count * 100 / manifest.sizeBytes));
                label.setText(String.format(java.util.Locale.ROOT, "已下载 %.1f MB", count / 1048576.0));
              }));
            }
            if (!UpdateManifest.verify(file, manifest.sha256)) throw new IOException("SHA-256 mismatch");
            verified = true; break;
          } catch (Exception e) { file.delete(); last = e; }
        }
        if (!verified) throw new IOException("all download sources failed", last);
        if (cancelled.get()) throw new InterruptedIOException();
        post(() -> { finishProgress(); requestInstall(manifest.sha256); });
      } catch (Exception e) { file.delete(); fail("下载或校验失败，更新包已删除。请稍后重试。", e); }
    });
  }

  private interface Progress { void bytes(long count); }
  private void transfer(String address, OutputStream out, long limit, Progress progress) throws IOException {
    String next = address;
    for (int redirects = 0; redirects <= 5; redirects++) {
      UpdateManifest.https(next);
      HttpURLConnection http = (HttpURLConnection) new URL(next).openConnection();
      connection = http;
      try {
        http.setInstanceFollowRedirects(false); http.setConnectTimeout(15000); http.setReadTimeout(20000);
        http.setRequestProperty("User-Agent", "SpektraLens/" + version);
        http.setRequestProperty("Accept-Encoding", "identity");
        if (cancelled.get()) throw new InterruptedIOException();
        int status = http.getResponseCode();
        if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
          String location = http.getHeaderField("Location");
          if (location == null) throw new IOException("missing redirect");
          next = new URL(new URL(next), location).toString(); continue;
        }
        if (status != 200 || http.getContentLengthLong() > limit) throw new IOException("HTTP status or size: " + status);
        try (InputStream in = http.getInputStream()) {
          byte[] buffer = new byte[32768]; long total = 0, notified = 0; int n;
          while ((n = in.read(buffer)) != -1) {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            total += n; if (total > limit) throw new IOException("size limit");
            out.write(buffer, 0, n);
            if (progress != null && total - notified >= 131072) { progress.bytes(total); notified = total; }
          }
          if (http.getContentLengthLong() >= 0 && total != http.getContentLengthLong()) throw new IOException("truncated download");
          if (progress != null) progress.bytes(total);
        }
        return;
      } finally { http.disconnect(); connection = null; }
    }
    throw new IOException("redirect limit");
  }

  private File apk() { return updateFile; }
  private SharedPreferences pending() { return activity.getSharedPreferences("pending_update", Context.MODE_PRIVATE); }
  private void requestInstall(String hash) {
    if (!activity.getPackageManager().canRequestPackageInstalls()) {
      new Haptics.DialogBuilder(activity).setTitle("允许安装更新")
          .setMessage("请在系统设置中允许 SpektraLens 安装未知应用，返回后继续安装。")
          .setNegativeButton("稍后", (d, n) -> apk().delete())
          .setPositiveButton("前往设置", (d, n) -> {
            pending().edit().putString("sha256", hash).putString("file", apk().getName()).apply();
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + activity.getPackageName()))); }
            catch (Exception e) { pending().edit().clear().apply(); fail("无法打开安装授权设置。", e); }
          }).show();
    } else install(hash);
  }

  void resume() {
    String hash = pending().getString("sha256", null);
    if (hash == null) return;
    String name = pending().getString("file", "");
    pending().edit().clear().apply();
    if (!name.matches("[0-9a-f-]{36}\\.apk")) return;
    updateFile = new File(activity.getCacheDir(), "updates/" + name);
    if (activity.getPackageManager().canRequestPackageInstalls()) { install(hash); return; }
    // Retry reuses the verified package instead of asking for another 22 MB download;
    // declining removes it so no unreferenced APK is left behind in the cache.
    new Haptics.DialogBuilder(activity).setTitle("尚未允许安装")
        .setMessage("需要允许 SpektraLens 安装应用，才能继续安装已下载的更新。")
        .setNegativeButton("稍后", (d, n) -> apk().delete())
        .setPositiveButton("重试", (d, n) -> requestInstall(hash)).show();
  }

  private void install(String hash) {
    if (busy || closed) return;
    busy = true; cancelled.set(false);
    worker.execute(() -> {
      PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
      int id = -1;
      try {
        if (!UpdateManifest.verify(apk(), hash)) throw new IOException("SHA-256 mismatch before install");
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());
        params.setSize(apk().length());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED);
        id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
          try (InputStream in = new FileInputStream(apk()); OutputStream out = session.openWrite("base.apk", 0, apk().length())) {
            byte[] buffer = new byte[32768]; int n;
            while ((n = in.read(buffer)) != -1) {
              if (cancelled.get()) throw new InterruptedIOException();
              out.write(buffer, 0, n);
            }
            session.fsync(out);
          }
          Intent callback = new Intent(activity, UpdateInstallActivity.class);
          int flags = PendingIntent.FLAG_UPDATE_CURRENT;
          if (Build.VERSION.SDK_INT >= 31) flags |= PendingIntent.FLAG_MUTABLE;
          android.os.Bundle callbackOptions = null;
          if (Build.VERSION.SDK_INT >= 35) {
            // Android 15 requires the creator to opt in before the installer can open this
            // private status Activity and present STATUS_PENDING_USER_ACTION.
            ActivityOptions options = ActivityOptions.makeBasic();
            options.setPendingIntentCreatorBackgroundActivityStartMode(
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            callbackOptions = options.toBundle();
          }
          PendingIntent result = PendingIntent.getActivity(activity, id, callback, flags, callbackOptions);
          if (cancelled.get()) throw new InterruptedIOException();
          session.commit(result.getIntentSender());
        }
        apk().delete(); post(() -> busy = false);
      } catch (Exception e) {
        if (id >= 0) { try { installer.abandonSession(id); } catch (Exception ignored) { } }
        apk().delete(); fail("无法安装更新，请确认安装包与当前应用使用相同签名。", e);
      }
    });
  }

  private void message(String title, String text) {
    new Haptics.DialogBuilder(activity).setTitle(title).setMessage(text).setPositiveButton("完成", null)
        .setNeutralButton("打开发布页", (d, n) -> openReleases(activity)).show();
  }
  static void openReleases(Activity activity) {
    try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))); }
    catch (Exception e) { Toast.makeText(activity, "未找到可打开网页的应用", Toast.LENGTH_SHORT).show(); }
  }
  private void post(Runnable action) { activity.runOnUiThread(() -> { if (!closed && !activity.isFinishing() && !cancelled.get()) action.run(); }); }
  private void fail(String text, Exception e) {
    Log.w("SpektraUpdate", text, e);
    post(() -> { finishProgress(); message("更新未完成", text); });
  }
  private void finishProgress() { busy = false; if (progress != null) { progress.dismiss(); progress = null; } }
  private void cancel() {
    cancelled.set(true);
    HttpURLConnection http = connection;
    if (http != null) workerDisconnect(http);
    // Keep the operation busy until its worker has unwound; a new operation cannot reuse its file.
    worker.execute(() -> { apk().delete(); activity.runOnUiThread(this::finishProgress); });
  }
  private void workerDisconnect(HttpURLConnection http) { new Thread(http::disconnect, "update-cancel").start(); }
  void close() {
    closed = true; cancelled.set(true);
    if (connection != null) workerDisconnect(connection);
    if (progress != null) progress.dismiss();
    File cacheFile = apk();
    boolean preserve = cacheFile.getName().equals(pending().getString("file", null));
    worker.execute(() -> { if (!preserve) cacheFile.delete(); });
    worker.shutdown();
  }
}
