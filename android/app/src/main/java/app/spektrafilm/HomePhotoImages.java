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
  private final Context context;
  private final Handler main;
  private final ScrollView scroll;
  private final File cache;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService rawWorker;
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
    int requestedWidth, requestedHeight;
    boolean waitingForRaw;

    Item(ImageView view, File original, File legacy, boolean hero) {
      this.view = view;
      this.original = original;
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
    cache = new File(context.getCacheDir(), "home-images-v1");
    scroll.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> loadVisible());
  }

  void setRenderer(SpectralRenderer renderer) {
    if (running != null && running.renderer != null && running.renderer != renderer) {
      running.current = false;
    }
    this.renderer = renderer;
    if (renderer == null) return;
    for (Item item : items.values()) {
      if (item.waitingForRaw) {
        item.requestedWidth = item.requestedHeight = 0;
        item.waitingForRaw = false;
      }
    }
    loadVisible();
  }

  boolean bind(ImageView view, File original, File legacy, boolean hero) {
    Item existing = items.get(view);
    if (existing != null && Objects.equals(existing.original, original)) return false;
    if (running != null && running.item.view == view) running.current = false;
    items.put(view, new Item(view, original, legacy, hero));
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
          Math.min(
              1, HomeImageDecoder.MAX_EDGE / (double) Math.max(view.getWidth(), view.getHeight()));
      int width = Math.max(1, (int) Math.round(view.getWidth() * scale));
      int height = Math.max(1, (int) Math.round(view.getHeight() * scale));
      if (running != null
          && running.item == item
          && (width != running.width || height != running.height)) running.current = false;
      if (running == null
          && next == null
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
      if (bitmap != null) bitmap.recycle();
      if (items.get(item.view) == item) item.requestedWidth = item.requestedHeight = 0;
    }
    loadVisible();
  }

  private Bitmap load(Request request) throws Exception {
    Item item = request.item;
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
    Bitmap fallback = read(item.legacy);
    if (fallback != null) {
      main.post(
          () -> {
            if (!closed
                && request.current
                && items.get(item.view) == item
                && item.view.getDrawable() == null) item.view.setImageBitmap(fallback);
            else fallback.recycle();
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
      File[] files = cache.listFiles((dir, name) -> name.endsWith(".png"));
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
