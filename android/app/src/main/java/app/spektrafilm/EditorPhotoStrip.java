// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Recent-photo strip under the editor preview. Thumbnails use the 240px import JPEGs, preload the
 * active window before the row is built, decode the visible window lazily and share a bounded LRU.
 * No full-size photo is touched here.
 */
final class EditorPhotoStrip extends HorizontalScrollView {
  interface Listener {
    void onPhotoSelected(String id);
  }

  static final class Photo {
    final String id, label;
    final File thumbnail, source;

    Photo(String id, String label, File thumbnail, File source) {
      this.id = id;
      this.label = label;
      this.thumbnail = thumbnail;
      this.source = source;
    }
  }

  private static final int MAX_THUMB_DP = 54, MIN_THUMB_DP = 46, GAP_DP = 6, PAD_DP = 12, RADIUS_DP = 7;
  private static final int CACHE_BYTES = 12 * 1024 * 1024;
  private static final int PRELOAD_RADIUS = 4;

  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newFixedThreadPool(2);
  private final LinearLayout row;
  private final Listener listener;
  private final LruCache<String, Bitmap> cache;
  private final Map<String, Integer> loading = new HashMap<>();
  private final Set<String> failed = new HashSet<>();
  private final Map<String, Thumb> thumbs = new LinkedHashMap<>();
  private final Rect visible = new Rect();
  private List<Photo> photos = new ArrayList<>();
  private String activeId;
  private Thumb selectedThumb;
  private int thumbSize;
  private int generation;
  private boolean closed;

  private static final class Thumb {
    final Photo photo;
    final FrameLayout view;
    final ImageView image;

    Thumb(Photo photo, FrameLayout view, ImageView image) {
      this.photo = photo;
      this.view = view;
      this.image = image;
    }
  }

  EditorPhotoStrip(Context context, Listener listener) {
    super(context);
    this.context = context;
    this.listener = listener;
    AppStyle.hideScrollBars(this);
    setOverScrollMode(OVER_SCROLL_NEVER);
    setFillViewport(false);
    setClipToPadding(false);
    setPadding(dp(PAD_DP), dp(6), dp(PAD_DP), dp(4));
    row = new LinearLayout(context);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    addView(row, new FrameLayout.LayoutParams(-2, -1));
    int cacheBytes =
        Math.max(
            2 * 1024 * 1024,
            Math.min(CACHE_BYTES, (int) (Runtime.getRuntime().maxMemory() / 16)));
    cache =
        new LruCache<String, Bitmap>(cacheBytes) {
          @Override
          protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
          }
        };
    setOnScrollChangeListener((v, x, y, oldX, oldY) -> loadVisible());
    addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
            applyThumbSize();
            loadVisible();
            post(() -> scrollToActive(false));
          }
        });
  }

  void setPhotos(List<Photo> next, String activeId, boolean scrollToActive) {
    if (closed) return;
    this.activeId = activeId;
    if (samePhotos(next)) {
      updateSelection();
      preloadAround(activeId);
      loadVisible();
      postDelayed(this::loadVisible, 80);
      if (scrollToActive) post(() -> scrollToActive(false));
      return;
    }
    ++generation;
    loading.clear();
    failed.clear();
    row.removeAllViews();
    thumbs.clear();
    selectedThumb = null;
    photos = new ArrayList<>(next);
    thumbSize = computeThumbSize();
    // Start the visible window while the row/panel is still being built; decode results are cached.
    preloadAround(activeId);
    for (Photo photo : photos) {
      Thumb thumb = createThumb(photo);
      thumbs.put(photo.id, thumb);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(thumbSize, thumbSize);
      if (row.getChildCount() > 0) params.leftMargin = dp(GAP_DP);
      row.addView(thumb.view, params);
    }
    selectedThumb = thumbs.get(activeId);
    post(this::loadVisible);
    // One extra pass after the first layout/scroll settles. Preloaded bitmaps are already cached,
    // so this only attaches them if the initial pass ran before the strip had a size.
    postDelayed(this::loadVisible, 80);
    if (scrollToActive) post(() -> scrollToActive(false));
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    post(this::loadVisible);
  }

  @Override
  protected void onVisibilityChanged(View changedView, int visibility) {
    super.onVisibilityChanged(changedView, visibility);
    if (visibility == VISIBLE) post(this::loadVisible);
  }

  void setActive(String id, boolean scrollToActive) {
    if (closed || id == null || id.equals(activeId)) return;
    activeId = id;
    updateSelection();
    preloadAround(id);
    if (scrollToActive) post(() -> scrollToActive(true));
  }

  void invalidate(String id) {
    if (closed || id == null) return;
    cache.remove(id);
    failed.remove(id);
    Thumb thumb = thumbs.get(id);
    if (thumb != null) {
      thumb.image.setImageDrawable(null);
      loadVisible();
    }
  }

  void close() {
    if (closed) return;
    closed = true;
    ++generation;
    loading.clear();
    failed.clear();
    io.shutdownNow();
    row.removeAllViews();
    thumbs.clear();
    selectedThumb = null;
    cache.evictAll();
  }

  /** Five complete thumbnails fit on phones/medium; expanded foldables show six. */
  private int computeThumbSize() {
    int width = getWidth();
    if (width <= 0) return dp(MAX_THUMB_DP);
    int cols = FoldableLayout.editorStripColumns(FoldableUi.windowWidthDp(context));
    int available = width - dp(PAD_DP) * 2 - dp(GAP_DP) * Math.max(1, cols - 1);
    int size = Math.max(dp(MIN_THUMB_DP), available / Math.max(1, cols));
    return Math.min(dp(MAX_THUMB_DP), size);
  }

  private void applyThumbSize() {
    int size = computeThumbSize();
    if (size == thumbSize) return;
    thumbSize = size;
    for (Thumb thumb : thumbs.values()) {
      ViewGroup.LayoutParams params = thumb.view.getLayoutParams();
      if (params == null) continue;
      params.width = size;
      params.height = size;
      thumb.view.setLayoutParams(params);
    }
  }

  private boolean samePhotos(List<Photo> next) {
    if (next.size() != photos.size()) return false;
    for (int i = 0; i < next.size(); i++) {
      if (!next.get(i).id.equals(photos.get(i).id)) return false;
    }
    return true;
  }

  private Thumb createThumb(Photo photo) {
    FrameLayout view = new FrameLayout(context);
    view.setTag("editor.strip." + photo.id);
    view.setClickable(true);
    view.setFocusable(true);
    // Fixed 2dp ring: the photo sits inside the border, not underneath it.
    view.setPadding(dp(2), dp(2), dp(2), dp(2));
    ImageView image = new ImageView(context);
    image.setScaleType(ImageView.ScaleType.CENTER_CROP);
    image.setBackground(AppStyle.shape(context, AppStyle.CONTROL, RADIUS_DP - 2, false));
    image.setClipToOutline(true);
    image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    view.addView(image, new FrameLayout.LayoutParams(-1, -1));
    view.setOnClickListener(
        v -> {
          if (!closed) listener.onPhotoSelected(photo.id);
        });
    Thumb thumb = new Thumb(photo, view, image);
    style(thumb, photo.id.equals(activeId));
    return thumb;
  }

  private void style(Thumb thumb, boolean selected) {
    int stroke = dp(selected ? 1.75f : 0f);
    GradientDrawable border = AppStyle.shape(context, Color.TRANSPARENT, RADIUS_DP, false);
    if (selected) border.setStroke(stroke, AppStyle.GOLD);
    // Keep the selected accent ring outside the inset image; unselected tiles stay borderless.
    int ring = dp(2);
    int offset = selected ? Math.max(0, ring - Math.round(stroke / 2f)) : 0;
    LayerDrawable inset = new LayerDrawable(new android.graphics.drawable.Drawable[] {border});
    inset.setLayerInset(0, offset, offset, offset, offset);
    GradientDrawable mask = AppStyle.shape(context, Color.WHITE, RADIUS_DP, false);
    thumb.view.setForeground(
        new RippleDrawable(
            ColorStateList.valueOf(AppStyle.RIPPLE), selected ? inset : null, mask));
    thumb.view.setSelected(selected);
    thumb.view.setContentDescription(
        selected ? "当前照片，" + thumb.photo.label : "切换到 " + thumb.photo.label);
  }

  private void updateSelection() {
    Thumb next = thumbs.get(activeId);
    if (next == selectedThumb) return;
    if (selectedThumb != null) style(selectedThumb, false);
    if (next != null) style(next, true);
    selectedThumb = next;
  }

  private void scrollToActive(boolean smooth) {
    Thumb thumb = thumbs.get(activeId);
    if (thumb == null || getWidth() <= 0) return;
    int target =
        dp(PAD_DP)
            + thumb.view.getLeft()
            + thumb.view.getWidth() / 2
            - getWidth() / 2;
    int max =
        Math.max(0, row.getWidth() + dp(PAD_DP) * 2 - getWidth());
    target = Math.max(0, Math.min(target, max));
    if (smooth) smoothScrollTo(target, 0);
    else scrollTo(target, 0);
  }

  private void loadVisible() {
    if (closed || getWidth() <= 0) return;
    for (Thumb thumb : thumbs.values()) {
      if (thumb.image.getDrawable() != null) continue;
      if (isVisible(thumb)) startDecode(thumb.photo, thumb);
    }
  }

  private boolean isVisible(Thumb thumb) {
    if (!thumb.view.isShown()) return false;
    int viewLeft = getScrollX() - getPaddingLeft();
    int viewRight = viewLeft + getWidth();
    return thumb.view.getRight() >= viewLeft
        && thumb.view.getLeft() <= viewRight
        && thumb.view.getLocalVisibleRect(visible);
  }

  private void preloadAround(String id) {
    if (closed || photos.isEmpty()) return;
    int index = -1;
    for (int i = 0; i < photos.size(); i++) {
      if (photos.get(i).id.equals(id)) {
        index = i;
        break;
      }
    }
    if (index < 0) return;
    int first = Math.max(0, index - PRELOAD_RADIUS);
    int last = Math.min(photos.size() - 1, index + PRELOAD_RADIUS);
    for (int i = first; i <= last; i++) startDecode(photos.get(i), null);
  }

  private void startDecode(Photo photo, Thumb thumb) {
    String id = photo.id;
    if (failed.contains(id)) return;
    Bitmap cached = cache.get(id);
    if (cached != null) {
      if (thumb != null && thumb.image.getDrawable() == null)
        thumb.image.setImageBitmap(cached);
      return;
    }
    int current = generation;
    Integer pending = loading.get(id);
    if (pending != null && pending == current) return;
    loading.put(id, current);
    io.execute(
        () -> {
          Bitmap bitmap = decode(photo);
          main.post(
              () -> {
                if (Integer.valueOf(current).equals(loading.get(id))) loading.remove(id);
                if (closed || current != generation) return;
                if (bitmap == null) {
                  failed.add(id);
                  return;
                }
                cache.put(id, bitmap);
                Thumb target = thumb != null ? thumb : thumbs.get(id);
                // Set even before the strip finishes its first layout; visibility timing must not
                // leave a preloaded thumbnail blank until the user taps the strip.
                if (target != null && target.image.getDrawable() == null)
                  target.image.setImageBitmap(bitmap);
              });
        });
  }

  private static Bitmap decode(Photo photo) {
    Bitmap thumbnail = decodeFile(photo.thumbnail);
    if (thumbnail != null) return thumbnail;
    // Older libraries may predate the 240px thumbnail files. Decode a bounded source fallback
    // instead of leaving the tile blank until the user opens that photo.
    if (photo.source == null || !photo.source.isFile()) return null;
    try {
      RasterDecoder.Result result = RasterDecoder.decode(photo.source, 320);
      return result == null ? null : result.bitmap;
    } catch (Exception ignored) {
      return null;
    }
  }

  private static Bitmap decodeFile(File file) {
    if (file == null || !file.isFile()) return null;
    try {
      BitmapFactory.Options options = new BitmapFactory.Options();
      options.inPreferredConfig = Bitmap.Config.ARGB_8888;
      return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private int dp(float value) {
    return AppStyle.dp(context, value);
  }
}
