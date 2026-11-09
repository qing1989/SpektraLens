// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.os.Handler;
import android.util.Log;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.json.JSONObject;

/** Loads only visible home images, one at a time. All view state belongs to the main thread. */
final class HomePhotoImages implements AutoCloseable {
  private static final long CACHE_BYTES = 48L * 1024 * 1024;
  /** A decode that keeps failing would retry on every layout pass; give up after a few tries. */
  private static final int MAX_FAILURES = 3;
  private final Context context;
  private final Handler main;
  private final ScrollView scroll;
  private final File cache;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService rawWorker;
  /** Preview width cap; smaller on devices whose heap cannot hold a 2048px preview comfortably. */
  private final int maxEdge;
  private final LinkedHashMap<ImageView, Item> items = new LinkedHashMap<>();
  private final Rect visibleRect = new Rect();
  private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::loadVisible;
  private SpectralRenderer renderer;
  private Request running;
  private boolean closed;

  private static final class Item {
    final ImageView view;
    final File original, legacy;
    final boolean hero;
    // 编辑器退出时存下的显影效果小图；没编辑过的底片没有这个文件，显示原片。
    File developed;
    int requestedWidth, requestedHeight;
    int failures;
    boolean waitingForRaw;

    Item(ImageView view, File original, File developed, File legacy, boolean hero) {
      this.view = view;
      this.original = original;
      this.developed = developed;
      this.legacy = legacy;
      this.hero = hero;
    }
  }

  private static final class Request {
    final Item item;
    final int width, height;
    final SpectralRenderer renderer;
    volatile boolean current = true;
    boolean waitingForRaw;

    Request(Item item, int width, int height, SpectralRenderer renderer) {
      this.item = item;
      this.width = width;
      this.height = height;
      this.renderer = renderer;
    }
  }

  HomePhotoImages(Context context, Handler main, ScrollView scroll, ExecutorService rawWorker) {
    this.context = context.getApplicationContext();
    this.main = main;
    this.scroll = scroll;
    this.rawWorker = rawWorker;
    this.maxEdge = HomeImageDecoder.maxEdge(context);
    // v2: v1 was written while the area resize dropped whole columns, so those PNGs have vertical
    // hairlines baked in. The file name only carries size and mtime, so bumping the directory is
    // the only way to make already-cached previews regenerate.
    cache = new File(context.getCacheDir(), "home-images-v2");
    // A previous run killed mid-write leaves image-*.pending behind; the size cap below only
    // covered the finished PNGs, so those temp files used to pile up forever.
    io.execute(this::sweepPending);
    scroll.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> loadVisible());
  }

  private void sweepPending() {
    deleteTree(new File(context.getCacheDir(), "home-images-v1"));
    File[] files = cache.listFiles();
    if (files == null) return;
    for (File file : files) if (file.getName().endsWith(".pending")) file.delete();
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    file.delete();
  }

  void setRenderer(SpectralRenderer renderer) {
    if (running != null && running.renderer != null && running.renderer != renderer) {
      running.current = false;
    }
    this.renderer = renderer;
    if (renderer == null) return;
    for (Item item : items.values()) {
      item.failures = 0;
      if (item.waitingForRaw) {
        item.requestedWidth = item.requestedHeight = 0;
        item.waitingForRaw = false;
      }
    }
    loadVisible();
  }

  boolean bind(ImageView view, File original, File developed, File legacy, boolean hero) {
    Item existing = items.get(view);
    if (existing != null && Objects.equals(existing.original, original)) {
      if (Objects.equals(existing.developed, developed)) return false;
      // 照片没换、显影效果换了（编辑后回首页）：只换来源并强制下次加载。
      existing.developed = developed;
      existing.requestedWidth = existing.requestedHeight = 0;
      return false;
    }
    if (running != null && running.item.view == view) running.current = false;
    items.put(view, new Item(view, original, developed, legacy, hero));
    return true;
  }

  void clearCards() {
    if (running != null && !running.item.hero) running.current = false;
    items.values().removeIf(item -> !item.hero);
  }

  void remove(File original) {
    String prefix = original.getName() + "-";
    io.execute(
        () -> {
          File[] files = cache.listFiles((dir, name) -> name.startsWith(prefix));
          if (files != null) for (File file : files) file.delete();
        });
  }

  /** 底片从历史里移除时，把它名下的显影小图缓存一并清掉。 */
  void removeDeveloped(String id) {
    io.execute(
        () -> {
          File[] files = cache.listFiles((dir, name) -> name.startsWith("developed-" + id + ".jpg"));
          if (files != null) for (File file : files) file.delete();
        });
  }

  /**
   * 编辑器退回首页时存一张显影效果小图（最长边 1024，覆盖首页大图与卡片的真实显示尺寸），
   * 首页从此不碰渲染引擎也能显示编辑后的样子。原片位图随时可能被编辑器回收，所以先在
   * 主线程降采样出一份副本再交给 io 线程；本方法要在首页卡片加载之前调用，io 单线程
   * 先写后读，卡片读到的才是新文件。
   */
  void storeDeveloped(String id, Bitmap image) {
    if (closed || id == null || image == null || image.isRecycled()) return;
    float scale = Math.min(1, 1024f / Math.max(image.getWidth(), image.getHeight()));
    int w = Math.max(1, Math.round(image.getWidth() * scale));
    int h = Math.max(1, Math.round(image.getHeight() * scale));
    Bitmap scaled = Bitmap.createScaledBitmap(image, w, h, true);
    if (scaled == image) scaled = image.copy(Bitmap.Config.ARGB_8888, false);
    if (scaled == null) return;
    final Bitmap small = scaled;
    io.execute(
        () -> {
          writeDeveloped(id, small);
          Bitmaps.release(small);
          main.post(() -> { if (!closed) loadVisible(); });
        });
  }

  private void writeDeveloped(String id, Bitmap small) {
    try {
      File dir = new File(context.getFilesDir(), "developed");
      if (!dir.isDirectory() && !dir.mkdirs()) return;
      File temp = File.createTempFile("look-", ".pending", dir);
      try (FileOutputStream out = new FileOutputStream(temp)) {
        if (!small.compress(Bitmap.CompressFormat.JPEG, 88, out)) {
          temp.delete();
          return;
        }
      }
      if (!temp.renameTo(new File(dir, id + ".jpg"))) temp.delete();
    } catch (IOException ex) {
      Log.w("SpektraLensAndroid", "Developed thumbnail unavailable", ex);
    }
  }

  void loadVisible() {
    if (closed) return;
    Request next = null;
    for (Item item : items.values()) {
      ImageView view = item.view;
      boolean visible = view.isShown() && view.getLocalVisibleRect(visibleRect);
      if (!visible) {
        if (running != null && running.item == item) running.current = false;
        if (!item.hero) {
          if (view.getDrawable() != null) view.setImageDrawable(null);
          item.requestedWidth = item.requestedHeight = 0;
        }
        continue;
      }
      if (view.getWidth() == 0 || view.getHeight() == 0) continue;
      double scale =
          Math.min(1, maxEdge / (double) Math.max(view.getWidth(), view.getHeight()));
      int width = Math.max(1, (int) Math.round(view.getWidth() * scale));
      int height = Math.max(1, (int) Math.round(view.getHeight() * scale));
      if (running != null
          && running.item == item
          && (width != running.width || height != running.height)) running.current = false;
      if (running == null
          && next == null
          && item.failures < MAX_FAILURES
          && (width != item.requestedWidth || height != item.requestedHeight)) {
        next = new Request(item, width, height, renderer);
      }
    }
    if (next == null) return;
    Request request = next;
    running = request;
    request.item.requestedWidth = request.width;
    request.item.requestedHeight = request.height;
    io.execute(
        () -> {
          Bitmap image = null;
          try {
            image = load(request);
          } catch (Exception ex) {
            if (request.current) Log.w("SpektraLensAndroid", "Home image unavailable", ex);
          } finally {
            Bitmap result = image;
            main.post(() -> finish(request, result));
          }
        });
  }

  private void finish(Request request, Bitmap bitmap) {
    running = null;
    Item item = request.item;
    if (!closed && request.current && items.get(item.view) == item) {
      if (bitmap != null) item.view.setImageBitmap(bitmap);
      item.waitingForRaw = request.waitingForRaw;
      if (item.waitingForRaw && renderer != null) {
        item.requestedWidth = item.requestedHeight = 0;
        item.waitingForRaw = false;
      }
    } else {
      Bitmaps.release(bitmap);
      if (items.get(item.view) == item) {
        // Reset both so the next layout pass retries. Leaving them set made a failed decode look
        // like a finished one: the cell stayed blank until it scrolled out and back in.
        if (bitmap == null && request.current) item.failures++;
        item.requestedWidth = item.requestedHeight = 0;
      }
    }
    loadVisible();
  }

  private Bitmap load(Request request) throws Exception {
    Item item = request.item;
    // 编辑过的底片优先显示显影后的样子：1024px 小图直接按视图尺寸居中裁切（面积平均降
    // 采样，和原片路径同一套几何），再进 PNG 缓存，滚动回来即时显示。没有或读不出来，
    // 回落原片解码，行为与从前完全一致。
    if (item.developed != null && item.developed.isFile()) {
      String lookKey =
          "developed-"
              + item.developed.getName()
              + "-"
              + item.developed.length()
              + "-"
              + item.developed.lastModified();
      File lookTarget =
          new File(cache, lookKey + "-" + request.width + "x" + request.height + ".png");
      Bitmap saved = read(lookTarget);
      if (saved != null) {
        lookTarget.setLastModified(System.currentTimeMillis());
        return saved;
      }
      Bitmap look = read(item.developed);
      if (look != null) {
        look = HomeImageDecoder.crop(look, request.width, request.height);
        if (request.current) save(lookTarget, look);
        return look;
      }
    }
    File original = item.original;
    String key =
        original == null
            ? "demo"
            : original.getName() + "-" + original.length() + "-" + original.lastModified();
    File target = new File(cache, key + "-" + request.width + "x" + request.height + ".png");
    Bitmap saved = read(target);
    if (saved != null) {
      target.setLastModified(System.currentTimeMillis());
      return saved;
    }
    // 已有显影效果时不再闪显原片占位图，避免「原图→显影图」的跳变。
    Bitmap fallback =
        item.developed == null || !item.developed.isFile() ? read(item.legacy) : null;
    if (fallback != null) {
      main.post(
          () -> {
            if (!closed
                && request.current
                && items.get(item.view) == item
                && item.view.getDrawable() == null) item.view.setImageBitmap(fallback);
            else Bitmaps.release(fallback);
          });
    }
    if (!request.current) return null;
    Bitmap image;
    JSONObject raw =
        original != null && !PhotoFormats.isRaster(original)
            ? new JSONObject(NativeEngine.inspectRaw(original.getAbsolutePath()))
            : null;
    if (raw != null && raw.optBoolean("raw")) {
      if (request.renderer == null) {
        request.waitingForRaw = true;
        return null;
      }
      int w = Math.max(1, raw.getInt("width")), h = Math.max(1, raw.getInt("height"));
      double coverScale = Math.max(request.width / (double) w, request.height / (double) h);
      // The renderer also caches its latest RAW decode for editing. Keep that preview at 1024
      // pixels or more when generating a small home card.
      int edge = Math.max(1024, (int) Math.ceil(Math.min(3072, Math.max(w, h) * coverScale * 2)));
      Future<Bitmap> future =
          rawWorker.submit(
              () ->
                  request.current
                      ? request.renderer.rawPreview(original.getAbsolutePath(), edge)
                      : null);
      Bitmap rawImage;
      try {
        rawImage = future.get();
      } catch (InterruptedException ex) {
        future.cancel(false);
        Thread.currentThread().interrupt();
        return null;
      }
      if (rawImage == null) return null;
      image = HomeImageDecoder.crop(rawImage, request.width, request.height);
    } else {
      ImageDecoder.Source source =
          original == null
              ? ImageDecoder.createSource(context.getAssets(), "demo.jpg")
              : ImageDecoder.createSource(original);
      image = HomeImageDecoder.decode(source, request.width, request.height);
    }
    if (request.current) save(target, image);
    return image;
  }

  private Bitmap read(File file) {
    if (file == null || !file.isFile()) return null;
    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
    if (bitmap != null) bitmap.setDensity(Bitmap.DENSITY_NONE);
    return bitmap;
  }

  private void save(File target, Bitmap image) {
    File temp = null;
    try {
      if (!cache.isDirectory() && !cache.mkdirs()) return;
      temp = File.createTempFile("image-", ".pending", cache);
      try (FileOutputStream out = new FileOutputStream(temp)) {
        if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) return;
      }
      if (!temp.renameTo(target)) return;
      // Count every file in the directory, not just *.png: leftover temp files were invisible to
      // the cap before this change.
      File[] files = cache.listFiles();
      if (files == null) return;
      Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
      long bytes = 0;
      for (File file : files) {
        bytes += file.length();
        if (bytes > CACHE_BYTES) file.delete();
      }
    } catch (IOException ex) {
      Log.w("SpektraLensAndroid", "Home image cache unavailable", ex);
    } finally {
      if (temp != null) temp.delete();
    }
  }

  @Override
  public void close() {
    closed = true;
    if (running != null) running.current = false;
    scroll.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
    scroll.setOnScrollChangeListener(null);
    items.clear();
    io.shutdownNow();
  }
}
