// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * A single-photo, image-only gallery. Metadata and bounded thumbnails never use the render queue.
 */
final class GallerySheet extends Dialog {
  interface Listener {
    void picked(GalleryPhotos.Photo photo);

    void files();

    void systemPicker();

    void permission();
  }

  private static final int INK = AppStyle.INK, MUTED = AppStyle.MUTED, ORANGE = AppStyle.ACCENT;
  private final Activity activity;
  private final Listener listener;
  private final List<GalleryPhotos.Photo> imported;
  private List<GalleryPhotos.Photo> phone = new ArrayList<>(), visible = new ArrayList<>();
  private final String activeId;
  private final ExecutorService metadata = Executors.newSingleThreadExecutor(),
      thumbnails = Executors.newFixedThreadPool(3);
  private final Handler main = new Handler(Looper.getMainLooper());
  private final LruCache<String, Bitmap> cache =
      new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        protected int sizeOf(String key, Bitmap bitmap) {
          return bitmap.getAllocationByteCount();
        }
      };
  private final ArrayList<CancellationSignal> thumbnailSignals = new ArrayList<>();
  private CancellationSignal querySignal;
  private boolean importedMode, closed, loading;
  private String albumId = "", loadError = "";
  private GridView grid;
  private TextView title, access, sourceButton, emptyText;
  private LinearLayout empty;
  private int collapsedHeight, expandedHeight, sheetWidth;
  private float dragY;
  private int dragHeight;
  private boolean dragging;
  private final View.OnLayoutChangeListener windowSizeChanged =
      (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (!closed && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop))
          resize(false);
      };
  private final BaseAdapter adapter =
      new BaseAdapter() {
        public int getCount() {
          return visible.size();
        }

        public Object getItem(int position) {
          return visible.get(position);
        }

        public long getItemId(int position) {
          return visible.get(position).key().hashCode();
        }

        public View getView(int position, View recycled, ViewGroup parent) {
          Tile tile = recycled instanceof Tile ? (Tile) recycled : new Tile();
          GalleryPhotos.Photo photo = visible.get(position);
          tile.bind(photo);
          return tile;
        }
      };

  GallerySheet(
      Activity activity, List<GalleryPhotos.Photo> imported, String activeId, Listener listener) {
    super(activity);
    this.activity = activity;
    this.imported = new ArrayList<>(imported);
    this.activeId = activeId;
    this.listener = listener;
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    Haptics.install(getWindow());
    build();
    setCanceledOnTouchOutside(true);
    setOnDismissListener(dialog -> release());
  }

  private int dp(float value) {
    return Math.round(value * getContext().getResources().getDisplayMetrics().density);
  }

  private LinearLayout column() {
    LinearLayout view = new LinearLayout(getContext());
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(getContext());
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setGravity(Gravity.CENTER_VERTICAL);
    view.setFontFeatureSettings("kern");
    view.setIncludeFontPadding(false);
    return view;
  }

  private TextView action(String value, int color, Runnable run) {
    TextView view = text(value, 13, color);
    view.setGravity(Gravity.CENTER);
    view.setPadding(dp(12), 0, dp(12), 0);
    view.setOnClickListener(v -> run.run());
    view.setFocusable(true);
    AppStyle.action(view, color, Color.TRANSPARENT, 18);
    return view;
  }

  private void build() {
    LinearLayout root = column();
    AppStyle.surface(root, AppStyle.SURFACE, 26);
    PanelHandle grab = new PanelHandle(getContext());
    grab.setContentDescription("上拉展开图库，下拉收起");
    grab.setFocusable(true);
    grab.setClickable(true);
    View handle = new View(getContext());
    GradientDrawable pill = new GradientDrawable();
    pill.setColor(0xff74747e);
    pill.setCornerRadius(dp(3));
    handle.setBackground(pill);
    FrameLayout.LayoutParams line =
        new FrameLayout.LayoutParams(dp(34), dp(4), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    line.topMargin = dp(11);
    grab.addView(handle, line);
    grab.setOnClickListener(
        v ->
            height(
                getWindow().getAttributes().height < expandedHeight
                    ? expandedHeight
                    : collapsedHeight));
    grab.setOnTouchListener(
        (v, event) -> {
          if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragY = event.getRawY();
            dragHeight = getWindow().getAttributes().height;
            dragging = false;
            return true;
          }
          float dy = event.getRawY() - dragY;
          if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (Math.abs(dy) > dp(5)) dragging = true;
            if (dragging)
              height(Math.max(dp(160), Math.min(expandedHeight, dragHeight - Math.round(dy))));
            return true;
          }
          if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!dragging) v.performClick();
            else if (getWindow().getAttributes().height < collapsedHeight * .72f) dismiss();
            else
              height(
                  getWindow().getAttributes().height > (collapsedHeight + expandedHeight) / 2
                      ? expandedHeight
                      : collapsedHeight);
            return true;
          }
          if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            height(collapsedHeight);
            return true;
          }
          return false;
        });
    root.addView(grab, new LinearLayout.LayoutParams(-1, dp(27)));
    LinearLayout header = new LinearLayout(getContext());
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(8), 0, dp(8), 0);
    title = action("图库", INK, this::albums);
    title.setTextSize(18);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    title.setTypeface(null, Typeface.BOLD);
    title.setGravity(Gravity.CENTER_VERTICAL);
    title.setContentDescription("图库，切换相册");
    header.addView(title, new LinearLayout.LayoutParams(0, dp(49), 1));
    header.addView(
        action("取消", MUTED, this::dismiss), new LinearLayout.LayoutParams(dp(64), dp(49)));
    root.addView(header);
    access = action("", ORANGE, listener::permission);
    access.setTextSize(11);
    access.setGravity(Gravity.CENTER_VERTICAL);
    access.setPadding(dp(20), 0, dp(20), 0);
    root.addView(access, new LinearLayout.LayoutParams(-1, dp(30)));
    FrameLayout body = new FrameLayout(getContext());
    grid = new GridView(getContext());
    grid.setNumColumns(3);
    grid.setHorizontalSpacing(dp(4));
    grid.setVerticalSpacing(dp(4));
    grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
    grid.setPadding(dp(12), 0, dp(12), dp(8));
    grid.setClipToPadding(false);
    grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
    grid.setAdapter(adapter);
    grid.setContentDescription("照片网格，三列");
    grid.setOnItemClickListener(
        (parent, view, position, id) -> {
          if (position >= visible.size()) return;
          GalleryPhotos.Photo selected = visible.get(position);
          dismiss();
          listener.picked(selected);
        });
    body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
    empty = column();
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(dp(24), dp(12), dp(24), dp(12));
    emptyText = text("正在读取相册…", 14, MUTED);
    emptyText.setGravity(Gravity.CENTER);
    empty.addView(emptyText, new LinearLayout.LayoutParams(-1, -2));
    body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    grid.setEmptyView(empty);
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout footer = new LinearLayout(getContext());
    footer.setPadding(dp(12), dp(6), dp(12), dp(12));
    sourceButton =
        action(
            "已导入",
            INK,
            () -> {
              importedMode = !importedMode;
              albumId = "";
              filter(true);
            });
    footer.addView(sourceButton, new LinearLayout.LayoutParams(0, dp(42), 1));
    footer.addView(
        action(
            "从文件选择",
            ORANGE,
            () -> {
              dismiss();
              listener.files();
            }),
        new LinearLayout.LayoutParams(0, dp(42), 1));
    root.addView(footer);
    setContentView(root);
  }

  public void show() {
    super.show();
    Window window = getWindow();
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    window.setGravity(Gravity.BOTTOM);
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams attributes = window.getAttributes();
    attributes.dimAmount = .58f;
    attributes.y = dp(10);
    window.setAttributes(attributes);
    activity.getWindow().getDecorView().addOnLayoutChangeListener(windowSizeChanged);
    resize(true);
    refresh();
  }

  private void resize(boolean initial) {
    boolean expanded =
        !initial && getWindow().getAttributes().height > (collapsedHeight + expandedHeight) / 2;
    Rect visible = new Rect();
    activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
    int available = visible.height() - dp(20);
    if (available <= 0)
      available = activity.getResources().getDisplayMetrics().heightPixels - dp(72);
    int availableWidth =
        visible.width() > 0
            ? visible.width()
            : activity.getResources().getDisplayMetrics().widthPixels;
    sheetWidth = Math.min(dp(720), Math.max(dp(240), availableWidth - dp(24)));
    collapsedHeight = Math.min(available, Math.max(dp(240), Math.round(available * .56f)));
    expandedHeight = Math.max(collapsedHeight, Math.round(available * .94f));
    getWindow().setLayout(sheetWidth, expanded ? expandedHeight : collapsedHeight);
  }

  private void height(int pixels) {
    if (!closed) getWindow().setLayout(sheetWidth, pixels);
  }

  void refresh() {
    if (closed) return;
    if (querySignal != null) querySignal.cancel();
    CancellationSignal signal = querySignal = new CancellationSignal();
    loading = GalleryPhotos.access(activity) != GalleryPhotos.Access.NONE;
    loadError = "";
    phone = new ArrayList<>();
    filter(false);
    if (!loading) return;
    metadata.execute(
        () -> {
          try {
            List<GalleryPhotos.Photo> result = GalleryPhotos.query(activity, signal);
            main.post(
                () -> {
                  if (closed || signal.isCanceled() || querySignal != signal) return;
                  phone = result;
                  loading = false;
                  filter(false);
                });
          } catch (OperationCanceledException ignored) {
          } catch (Exception error) {
            main.post(
                () -> {
                  if (closed || signal.isCanceled() || querySignal != signal) return;
                  loading = false;
                  loadError = "相册暂时无法读取";
                  filter(false);
                });
          }
        });
  }

  private void filter(boolean scrollToTop) {
    if (closed) return;
    visible = new ArrayList<>();
    for (GalleryPhotos.Photo photo : importedMode ? imported : phone)
      if (importedMode || albumId.isEmpty() || albumId.equals(photo.albumId)) visible.add(photo);
    String label = "图库";
    if (importedMode) label = "已导入";
    else if (!albumId.isEmpty())
      for (GalleryPhotos.Photo photo : phone)
        if (albumId.equals(photo.albumId)) {
          label = photo.albumName;
          break;
        }
    title.setText(label);
    sourceButton.setText(importedMode ? "手机相册" : "已导入");
    GalleryPhotos.Access permission = GalleryPhotos.access(activity);
    access.setVisibility(
        !importedMode && permission == GalleryPhotos.Access.PARTIAL ? View.VISIBLE : View.GONE);
    access.setText("可见部分照片 · 管理");
    // Empty states are rebuilt without inventing any photo/camera tiles.
    while (empty.getChildCount() > 1) empty.removeViewAt(1);
    if (importedMode) emptyText.setText("还没有导入照片");
    else if (loading) emptyText.setText("正在读取相册…");
    else if (permission == GalleryPhotos.Access.NONE) {
      emptyText.setText("允许访问相册后，在这里浏览照片");
      LinearLayout.LayoutParams button = new LinearLayout.LayoutParams(-1, dp(44));
      button.topMargin = dp(14);
      empty.addView(action("允许访问相册", ORANGE, listener::permission), button);
    } else emptyText.setText(loadError.isEmpty() ? "这个相册还没有可用照片" : loadError);
    if (!importedMode && !loading && visible.isEmpty())
      empty.addView(
          action(
              "系统选择照片",
              INK,
              () -> {
                dismiss();
                listener.systemPicker();
              }),
          new LinearLayout.LayoutParams(-1, dp(44)));
    adapter.notifyDataSetChanged();
    if (scrollToTop) grid.setSelection(0);
  }

  private void albums() {
    LinkedHashMap<String, String> albums = new LinkedHashMap<>();
    LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
    for (GalleryPhotos.Photo photo : phone) {
      albums.put(photo.albumId, photo.albumName);
      counts.put(photo.albumId, counts.getOrDefault(photo.albumId, 0) + 1);
    }
    ArrayList<String> ids = new ArrayList<>(), names = new ArrayList<>();
    ids.add("");
    names.add("最近照片");
    for (String id : albums.keySet()) {
      ids.add(id);
      names.add(albums.get(id) + "  " + counts.get(id));
    }
    new Haptics.DialogBuilder(activity)
        .setTitle("选择相册")
        .setItems(
            names.toArray(new String[0]),
            (dialog, which) -> {
              importedMode = false;
              albumId = ids.get(which);
              filter(true);
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private final class Tile extends FrameLayout {
    final ImageView image;
    CancellationSignal signal;
    Future<?> future;

    Tile() {
      super(GallerySheet.this.getContext());
      image = new ImageView(getContext());
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      setBackground(AppStyle.shape(getContext(), AppStyle.CONTROL, 9, false));
      setClipToOutline(true);
      addView(image, new FrameLayout.LayoutParams(-1, -1));
      setLayoutParams(new AbsListView.LayoutParams(-1, -2));
    }

    protected void onMeasure(int width, int height) {
      int side = View.MeasureSpec.getSize(width);
      super.onMeasure(
          View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY));
    }

    void bind(GalleryPhotos.Photo photo) {
      if (signal != null) {
        signal.cancel();
        thumbnailSignals.remove(signal);
      }
      if (future != null) future.cancel(true);
      String key = photo.key();
      image.setTag(key);
      setContentDescription("照片 " + photo.name);
      setForeground(
          new RippleDrawable(
              android.content.res.ColorStateList.valueOf(0x30ffffff),
              borderFor(photo),
              AppStyle.shape(getContext(), Color.WHITE, 9, false)));
      Bitmap hit = cache.get(key);
      image.setImageBitmap(hit);
      if (hit != null) return;
      CancellationSignal request = signal = new CancellationSignal();
      thumbnailSignals.add(request);
      future =
          thumbnails.submit(
              () -> {
                try {
                  request.throwIfCanceled();
                  Bitmap loaded;
                  if (photo.uri != null) {
                    int side =
                        Math.max(
                            160,
                            Math.min(
                                512, activity.getResources().getDisplayMetrics().widthPixels / 3));
                    loaded =
                        activity
                            .getContentResolver()
                            .loadThumbnail(photo.uri, new Size(side, side), request);
                  } else {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(photo.thumbnail.getAbsolutePath(), options);
                    options.inSampleSize = 1;
                    while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize
                        > 480) options.inSampleSize *= 2;
                    options.inJustDecodeBounds = false;
                    request.throwIfCanceled();
                    loaded = BitmapFactory.decodeFile(photo.thumbnail.getAbsolutePath(), options);
                  }
                  if (loaded == null) return;
                  main.post(
                      () -> {
                        thumbnailSignals.remove(request);
                        if (closed || request.isCanceled()) return;
                        cache.put(key, loaded);
                        if (key.equals(image.getTag())) image.setImageBitmap(loaded);
                      });
                } catch (Exception ignored) {
                  main.post(() -> thumbnailSignals.remove(request));
                }
              });
    }

    private Drawable borderFor(GalleryPhotos.Photo photo) {
      if (photo.frameId == null || !photo.frameId.equals(activeId)) return null;
      GradientDrawable border = new GradientDrawable();
      border.setColor(Color.TRANSPARENT);
      border.setStroke(dp(2), ORANGE);
      border.setCornerRadius(dp(9));
      return border;
    }
  }

  private void release() {
    if (closed) return;
    closed = true;
    if (querySignal != null) querySignal.cancel();
    for (CancellationSignal signal : thumbnailSignals) signal.cancel();
    thumbnailSignals.clear();
    activity.getWindow().getDecorView().removeOnLayoutChangeListener(windowSizeChanged);
    metadata.shutdownNow();
    thumbnails.shutdownNow();
    grid.setAdapter(null);
    phone.clear();
    visible.clear();
    imported.clear();
    cache.evictAll();
    main.removeCallbacksAndMessages(null);
  }
}
