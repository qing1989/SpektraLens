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
import android.util.LruCache;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Loads visible home images with in-memory LRU and bounded concurrency. All view state belongs to the main thread. */
final class HomePhotoImages implements AutoCloseable {
  private static final long CACHE_BYTES = 48L * 1024 * 1024;
  private static final int MAX_CONCURRENT_DECODES = 2;
  private static final int PRUNE_EVERY_WRITES = 16;
  private static final int JPEG_CACHE_QUALITY = 90;
  /** A decode that keeps failing would retry on every layout pass; give up after a few tries. */
  private static final int MAX_FAILURES = 3;
  private final Context context;
  private final Handler main;
  private final ScrollView scroll;
  private final File cache;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService decodePool = Executors.newFixedThreadPool(MAX_CONCURRENT_DECODES);
  private final ExecutorService rawWorker;
  /** Preview width cap; smaller on devices whose heap cannot hold a 2048px preview comfortably. */
  private final int maxEdge;
  private final String tempPrefix = "image-" + Long.toHexString(System.nanoTime()) + "-";
  private final LruCache<String, Bitmap> memoryCache;
  private final HashMap<String, Long> developedGenerations = new HashMap<>();
  private final HashMap<String, Long> sourceGenerations = new HashMap<>();
  private final LinkedHashMap<ImageView, Item> items = new LinkedHashMap<>();
  private final LinkedHashMap<Item, Request> inFlight = new LinkedHashMap<>();
  private final Rect visibleRect = new Rect();
  private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::loadVisible;
  private SpectralRenderer renderer;
  private volatile boolean closed;
  private Runnable renderDemandListener;
  private Consumer<String> developedListener;
  private final ThumbnailWriteGate writes = new ThumbnailWriteGate();
  private final PendingBitmaps deliveries = new PendingBitmaps();
  private final AtomicInteger pendingWrites = new AtomicInteger();
  private final AtomicInteger cacheWritesSincePrune = new AtomicInteger();

  int pendingWrites() { return pendingWrites.get(); }

  void setRenderDemandListener(Runnable listener) {
    renderDemandListener = listener;
  }

  void setDevelopedListener(Consumer<String> listener) {
    developedListener = listener;
  }

  void clearMemoryCache() {
    memoryCache.evictAll();
  }

  static File alphaLook(File jpeg) {
    String name = jpeg.getName();
    return new File(jpeg.getParentFile(), name.substring(0, name.length() - 4) + ".png");
  }

  static File preferredLook(File jpeg) {
    if (jpeg == null) return null;
    File alpha = alphaLook(jpeg);
    return alpha.isFile() ? alpha : jpeg;
  }

  int visibilityRank(String id) {
    int rank = 2;
    for (Item item : items.values()) {
      if (matches(item, id) && item.view.isShown() && item.view.getLocalVisibleRect(visibleRect))
        rank = Math.min(rank, item.hero ? 0 : 1);
    }
    return rank;
  }

  private static boolean matches(Item item, String id) {
    return item.developed != null && (id + ".jpg").equals(item.developed.getName());
  }

  private void bumpDevelopedGeneration(String id) {
    Long prev = developedGenerations.get(id);
    developedGenerations.put(id, prev == null ? 1L : prev + 1L);
    String marker = "/" + id + ".jpg|";
    for (String key : memoryCache.snapshot().keySet()) {
      if (key.contains(marker)) memoryCache.remove(key);
    }
  }

  private void evictMemoryByOriginal(File original) {
    if (original == null) return;
    String prefix = original.getAbsolutePath() + "|";
    for (String key : memoryCache.snapshot().keySet()) {
      if (key.startsWith(prefix)) memoryCache.remove(key);
    }
  }

  private void evictMemoryByIdentity(String identity) {
    String prefix = identity + "|";
    for (String key : memoryCache.snapshot().keySet()) {
      if (key.startsWith(prefix)) memoryCache.remove(key);
    }
  }

  private static String sourceIdentity(Item item) {
    String orig = item.original == null ? "demo" : item.original.getAbsolutePath();
    String dev = item.developed == null ? "" : item.developed.getAbsolutePath();
    return orig + "|" + dev;
  }

  private long sourceGeneration(String identity) {
    Long gen = sourceGenerations.get(identity);
    return gen == null ? 0L : gen;
  }

  private static String developedId(File developed) {
    if (developed == null) return "";
    String name = developed.getName();
    return name.endsWith(".jpg") ? name.substring(0, name.length() - 4) : name;
  }

  private String memoryCacheKey(Item item, int width, int height) {
    String identity = sourceIdentity(item);
    long devGen = 0L;
    if (item.developed != null) {
      Long g = developedGenerations.get(developedId(item.developed));
      if (g != null) devGen = g;
    }
    return identity
        + "|"
        + sourceGeneration(identity)
        + "|"
        + devGen
        + "|"
        + width
        + "x"
        + height;
  }

  private void invalidateDeveloped(String id) {
    writes.invalidate(id);
    bumpDevelopedGeneration(id);
    for (Item item : items.values()) {
      if (!matches(item, id)) continue;
      ++item.imageVersion;
      Request active = inFlight.get(item);
      if (active != null) active.current = false;
      item.requestedWidth = item.requestedHeight = 0;
    }
  }

  boolean isWriting(String id) {
    return writes.isWriting(id);
  }

  private static final class Item {
    final ImageView view;
    final File original, legacy;
    final boolean hero;
    // 编辑器退出时存下的显影效果小图；没编辑过的底片没有这个文件，显示原片。
    File developed;
    int requestedWidth, requestedHeight;
    long imageVersion;
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
    final long imageVersion;
    final String memoryKey;
    boolean delivered;
    final SpectralRenderer renderer;
    volatile boolean current = true;
    boolean waitingForRaw;

    Request(Item item, int width, int height, SpectralRenderer renderer, String memoryKey) {
      this.item = item;
      this.imageVersion = item.imageVersion;
      this.width = width;
      this.height = height;
      this.renderer = renderer;
      this.memoryKey = memoryKey;
    }
  }

  HomePhotoImages(Context context, Handler main, ScrollView scroll, ExecutorService rawWorker) {
    this.context = context.getApplicationContext();
    this.main = main;
    this.scroll = scroll;
    this.rawWorker = rawWorker;
    this.maxEdge = HomeImageDecoder.maxEdge(context);
    int memoryCapBytes =
        maxEdge <= HomeImageDecoder.LOW_MEMORY_MAX_EDGE ? 8 * 1024 * 1024 : 16 * 1024 * 1024;
    this.memoryCache =
        new LruCache<String, Bitmap>(memoryCapBytes) {
          @Override
          protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getAllocationByteCount());
          }
        };
    // v3: the bundled demo photo changed, and the demo cache key ("demo") has no mtime. Bumping
    // the directory also discards v1, whose area resize left vertical hairlines in the PNGs.
    cache = new File(context.getCacheDir(), "home-images-v3");
    // A previous run killed mid-write leaves image-*.pending behind; the size cap below only
    // covered the finished thumbnails, so those temp files used to pile up forever.
    io.execute(this::sweepPending);
    scroll.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> loadVisible());
  }

  private void sweepPending() {
    deleteTree(new File(context.getCacheDir(), "home-images-v1"));
    deleteTree(new File(context.getCacheDir(), "home-images-v2"));
    File[] files = cache.listFiles();
    if (files == null) return;
    for (File file : files) {
      String name = file.getName();
      if (name.endsWith(".png") || (name.endsWith(".pending") && !name.startsWith(tempPrefix))) {
        file.delete();
      }
    }
  }

  private static void deleteTree(File file) {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) deleteTree(child);
    file.delete();
  }

  void setRenderer(SpectralRenderer renderer) {
    for (Request active : inFlight.values()) {
      if (active.renderer != null && active.renderer != renderer) {
        active.current = false;
      }
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
      Request active = inFlight.get(existing);
      if (active != null) active.current = false;
      existing.developed = developed;
      ++existing.imageVersion;
      existing.requestedWidth = existing.requestedHeight = 0;
      return false;
    }
    if (existing != null) {
      Request active = inFlight.get(existing);
      if (active != null) active.current = false;
    }
    items.put(view, new Item(view, original, developed, legacy, hero));
    return true;
  }

  /** Forces a visible tile to decode again, e.g. when the banner falls back to the demo asset. */
  void reload(ImageView view) {
    Item item = items.get(view);
    if (item == null) return;
    Request active = inFlight.get(item);
    if (active != null) active.current = false;
    String identity = sourceIdentity(item);
    sourceGenerations.put(identity, sourceGeneration(identity) + 1L);
    evictMemoryByIdentity(identity);
    ++item.imageVersion;
    item.requestedWidth = item.requestedHeight = 0;
    item.failures = 0;
    loadVisible();
  }

  void clearCards() {
    for (Request active : inFlight.values()) {
      if (!active.item.hero) active.current = false;
    }
    items.values().removeIf(item -> !item.hero);
  }

  void remove(File original) {
    if (original == null) return;
    evictMemoryByOriginal(original);
    String prefix = original.getName() + "-";
    io.execute(
        () -> {
          File[] files = cache.listFiles((dir, name) -> name.startsWith(prefix));
          if (files != null) for (File file : files) file.delete();
        });
  }

  /** 底片从历史里移除时，把它名下的显影小图缓存一并清掉。 */
  void removeDeveloped(String id) {
    invalidateDeveloped(id);
    File jpeg = new File(new File(context.getFilesDir(), "developed"), id + ".jpg");
    jpeg.delete();
    alphaLook(jpeg).delete();
    io.execute(
        () -> {
          File[] files = cache.listFiles(
              (dir, name) -> name.startsWith("developed-" + id + ".jpg-")
                  || name.startsWith("developed-" + id + ".png-"));
          if (files != null) for (File file : files) file.delete();
        });
  }

  /** Main-preview bitmaps remain owned by the editor, so take one bounded copy. */
  void storeDeveloped(String id, Bitmap image) {
    if (closed || id == null || image == null || image.isRecycled()) return;
    float scale = Math.min(1, 1024f / Math.max(image.getWidth(), image.getHeight()));
    Bitmap small = Bitmap.createScaledBitmap(image,
        Math.max(1, Math.round(image.getWidth() * scale)),
        Math.max(1, Math.round(image.getHeight() * scale)), true);
    if (small == image) small = image.copy(Bitmap.Config.ARGB_8888, false);
    if (small != null) storeDevelopedOwned(id, small, () -> true);
  }

  /** Takes ownership. Visible crops are posted before encoding the developed file. */
  void storeDevelopedOwned(String id, Bitmap small, BooleanSupplier current) {
    if (closed || !current.getAsBoolean()) {
      Bitmaps.release(small);
      return;
    }
    final long version = writes.begin(id);
    bumpDevelopedGeneration(id);
    pendingWrites.incrementAndGet();
    ArrayList<Request> visible = new ArrayList<>();
    for (Item item : items.values()) {
      if (!matches(item, id)) continue;
      ++item.imageVersion;
      Request active = inFlight.get(item);
      if (active != null) active.current = false;
      item.requestedWidth = item.requestedHeight = 0;
      item.failures = 0;
      ImageView view = item.view;
      if (!view.isShown() || !view.getLocalVisibleRect(visibleRect)
          || view.getWidth() <= 0 || view.getHeight() <= 0) continue;
      double scale = Math.min(1, maxEdge / (double) Math.max(view.getWidth(), view.getHeight()));
      int width = Math.max(1, (int) Math.round(view.getWidth() * scale));
      int height = Math.max(1, (int) Math.round(view.getHeight() * scale));
      item.requestedWidth = width;
      item.requestedHeight = height;
      visible.add(new Request(item, width, height, renderer, memoryCacheKey(item, width, height)));
    }
    io.execute(() -> {
      boolean published = false;
      Bitmap flat = null;
      try {
        if (small.hasAlpha() && !visible.isEmpty()) flat = FrameStyle.flatten(small);
        for (Request request : visible) {
          if (!writeCurrent(id, version, current)) break;
          Bitmap copy = (flat == null ? small : flat).copy(Bitmap.Config.ARGB_8888, false);
          if (copy == null) continue;
          Bitmap crop = HomeImageDecoder.crop(copy, request.width, request.height);
          deliveries.post(main, crop, () -> {
            Item item = request.item;
            if (writeCurrent(id, version, current) && items.get(item.view) == item
                && item.imageVersion == request.imageVersion
                && item.requestedWidth == request.width && item.requestedHeight == request.height
                && item.view.isShown()) {
              if (request.memoryKey != null) memoryCache.put(request.memoryKey, crop);
              item.view.setImageBitmap(crop);
              request.delivered = true;
            } else Bitmaps.release(crop);
          });
        }
        published = writeDeveloped(id, small, version, current);
      } catch (Exception ex) {
        Log.w("SpektraLensAndroid", "Developed thumbnail unavailable", ex);
      } finally {
        Bitmaps.release(flat);
        Bitmaps.release(small);
        writes.finish(id, version);
        pendingWrites.decrementAndGet();
        final boolean committed = published;
        main.post(() -> {
          if (closed) return;
          if (!committed && writes.isCurrent(id, version)) {
            bumpDevelopedGeneration(id);
          }
          if (committed && writes.isCurrent(id, version) && developedListener != null)
            developedListener.accept(id);
          for (Request request : visible) {
            Item item = request.item;
            if (!request.delivered && items.get(item.view) == item
                && item.imageVersion == request.imageVersion)
              item.requestedWidth = item.requestedHeight = 0;
          }
          loadVisible();
        });
      }
    });
  }

  private boolean writeCurrent(String id, long version, BooleanSupplier current) {
    return !closed && writes.isCurrent(id, version) && current.getAsBoolean();
  }

  private boolean writeDeveloped(String id, Bitmap small, long version, BooleanSupplier current)
      throws IOException {
    if (!writeCurrent(id, version, current)) return false;
    File dir = new File(context.getFilesDir(), "developed");
    if (!dir.isDirectory() && !dir.mkdirs()) return false;
    File jpeg = new File(dir, id + ".jpg");
    File alpha = alphaLook(jpeg);
    boolean preserveAlpha = small.hasAlpha();
    File target = preserveAlpha ? alpha : jpeg;
    File previousFormat = preserveAlpha ? jpeg : alpha;
    File temp = File.createTempFile("look-", ".pending", dir);
    try {
      try (FileOutputStream out = new FileOutputStream(temp)) {
        Bitmap.CompressFormat format = preserveAlpha ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.JPEG;
        if (!small.compress(format, preserveAlpha ? 100 : 88, out)) return false;
      }
      // Invalidation and commit share a lock: an old result cannot republish a deleted look.
      temp.setLastModified(Math.max(System.currentTimeMillis(), target.lastModified() + 1));
      return writes.commit(id, version, current, () -> {
        if (!temp.renameTo(target)) return false;
        previousFormat.delete();
        return true;
      });
    } finally {
      temp.delete();
    }
  }

  void loadVisible() {
    if (closed) return;
    if (renderDemandListener != null) renderDemandListener.run();
    ArrayList<Request> toDispatch = null;
    int activeCount = inFlight.size();
    for (Item item : items.values()) {
      ImageView view = item.view;
      boolean visible = view.isShown() && view.getLocalVisibleRect(visibleRect);
      if (!visible) {
        Request active = inFlight.get(item);
        if (active != null) active.current = false;
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
      Request active = inFlight.get(item);
      if (active != null && (width != active.width || height != active.height)) {
        active.current = false;
      }
      if (width == item.requestedWidth && height == item.requestedHeight) {
        continue;
      }
      if (item.failures >= MAX_FAILURES) continue;
      if (item.developed != null && isWriting(developedId(item.developed))) {
        continue;
      }
      String memKey = memoryCacheKey(item, width, height);
      Bitmap cached = memoryCache.get(memKey);
      if (cached != null && !cached.isRecycled()) {
        if (active != null) active.current = false;
        item.requestedWidth = width;
        item.requestedHeight = height;
        item.failures = 0;
        item.waitingForRaw = false;
        view.setImageBitmap(cached);
        continue;
      }
      if (active == null && activeCount < MAX_CONCURRENT_DECODES) {
        if (toDispatch == null) toDispatch = new ArrayList<>(MAX_CONCURRENT_DECODES);
        toDispatch.add(new Request(item, width, height, renderer, memKey));
        activeCount++;
      }
    }
    if (toDispatch == null) return;
    for (Request request : toDispatch) {
      inFlight.put(request.item, request);
      request.item.requestedWidth = request.width;
      request.item.requestedHeight = request.height;
      decodePool.execute(
          () -> {
            Bitmap image = null;
            try {
              image = load(request);
            } catch (Exception ex) {
              if (request.current) Log.w("SpektraLensAndroid", "Home image unavailable", ex);
            } finally {
              Bitmap result = image;
              deliveries.post(main, result, () -> finish(request, result));
            }
          });
    }
  }

  private void finish(Request request, Bitmap bitmap) {
    Item item = request.item;
    if (inFlight.get(item) == request) inFlight.remove(item);
    if (!closed && request.current && item.imageVersion == request.imageVersion
        && items.get(item.view) == item) {
      if (bitmap != null) {
        if (request.memoryKey != null) memoryCache.put(request.memoryKey, bitmap);
        item.view.setImageBitmap(bitmap);
        item.failures = 0;
        item.waitingForRaw = false;
      } else if (request.waitingForRaw) {
        item.waitingForRaw = true;
        if (renderer != null) {
          item.requestedWidth = item.requestedHeight = 0;
          item.waitingForRaw = false;
        }
      } else {
        item.failures++;
        item.requestedWidth = item.requestedHeight = 0;
        item.waitingForRaw = false;
      }
    } else {
      Bitmaps.release(bitmap);
      if (items.get(item.view) == item
          && item.imageVersion == request.imageVersion
          && item.requestedWidth == request.width
          && item.requestedHeight == request.height) {
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
    // 采样，和原片路径同一套几何），再进 JPEG 缓存，滚动回来即时显示。没有或读不出来，
    // 回落原片解码，行为与从前完全一致。
    File developed = preferredLook(item.developed);
    if (developed != null && developed.isFile()) {
      String lookKey =
          "developed-"
              + developed.getName()
              + "-"
              + developed.length()
              + "-"
              + developed.lastModified();
      File lookTarget =
          new File(cache, lookKey + "-" + request.width + "x" + request.height + ".jpg");
      Bitmap saved = read(lookTarget, true);
      if (saved != null) {
        lookTarget.setLastModified(System.currentTimeMillis());
        return saved;
      }
      Bitmap look = read(developed, true);
      if (look != null) {
        if (look.hasAlpha()) {
          Bitmap flat = FrameStyle.flatten(look);
          Bitmaps.release(look);
          look = flat;
        }
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
    File target = new File(cache, key + "-" + request.width + "x" + request.height + ".jpg");
    Bitmap saved = read(target, true);
    if (saved != null) {
      target.setLastModified(System.currentTimeMillis());
      return saved;
    }
    // 已有显影效果时不再闪显原片占位图，避免「原图→显影图」的跳变。
    Bitmap fallback =
        developed == null || !developed.isFile() ? read(item.legacy, true) : null;
    if (fallback != null) {
      deliveries.post(main, fallback,
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

  private Bitmap read(File file, boolean preferRgb565) {
    if (file == null || !file.isFile()) return null;
    BitmapFactory.Options opts = null;
    if (preferRgb565 && !file.getName().endsWith(".png")) {
      opts = new BitmapFactory.Options();
      opts.inPreferredConfig = Bitmap.Config.RGB_565;
    }
    Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    if (bitmap != null) bitmap.setDensity(Bitmap.DENSITY_NONE);
    return bitmap;
  }

  private void save(File target, Bitmap image) {
    File temp = null;
    try {
      if (!cache.isDirectory() && !cache.mkdirs()) return;
      temp = File.createTempFile(tempPrefix, ".pending", cache);
      try (FileOutputStream out = new FileOutputStream(temp)) {
        if (!image.compress(Bitmap.CompressFormat.JPEG, JPEG_CACHE_QUALITY, out)) return;
      }
      if (!temp.renameTo(target)) return;
      if (cacheWritesSincePrune.incrementAndGet() >= PRUNE_EVERY_WRITES && !closed) {
        cacheWritesSincePrune.set(0);
        try {
          io.execute(this::pruneCache);
        } catch (RejectedExecutionException ignored) {
          // Closed while saving
        }
      }
    } catch (IOException ex) {
      Log.w("SpektraLensAndroid", "Home image cache unavailable", ex);
    } finally {
      if (temp != null) temp.delete();
    }
  }

  private void pruneCache() {
    if (closed) return;
    File[] files = cache.listFiles();
    if (files == null) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
    long bytes = 0;
    for (File file : files) {
      bytes += file.length();
      if (bytes > CACHE_BYTES && !file.getName().endsWith(".pending")) file.delete();
    }
  }

  @Override
  public void close() {
    closed = true;
    writes.close();
    deliveries.close();
    for (Request active : inFlight.values()) active.current = false;
    inFlight.clear();
    memoryCache.evictAll();
    scroll.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
    scroll.setOnScrollChangeListener(null);
    items.clear();
    developedGenerations.clear();
    sourceGenerations.clear();
    renderDemandListener = null;
    developedListener = null;
    decodePool.shutdownNow();
    // Drain transferred bitmaps so every queued owner gets its finally/recycle path.
    io.shutdown();
  }
}
