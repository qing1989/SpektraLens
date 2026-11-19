// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.util.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Image-only, multi-select photo gallery. Metadata and bounded thumbnails never use the render
 * queue. The sheet stays open while the user selects, switches albums or grants access.
 */
final class GallerySheet extends Dialog {
  interface Listener {
    void importPhotos(List<GalleryPhotos.Photo> photos);

    void permission();
  }

  private static final int INK = AppStyle.INK, MUTED = AppStyle.MUTED, GOLD = AppStyle.GOLD;
  private final Activity activity;
  private final Listener listener;
  private final int maxSelection;
  private List<GalleryPhotos.Photo> phone = new ArrayList<>(), visible = new ArrayList<>();
  private final LinkedHashMap<String, GalleryPhotos.Photo> selected = new LinkedHashMap<>();
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
  private final LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
  private CancellationSignal querySignal;
  private GalleryPaging paging;
  private boolean closed, loading, albumPanelOpen, fullScan;
  private String albumId = "", loadError = "";
  private LinearLayout root;
  private GridView grid;
  private ListView albumList;
  private LinearLayout albumPanel;
  private ImageView albumArrow;
  private TextView title, access, emptyText, selectionSummary, confirmButton;
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
          tile.bind(photo, selected.containsKey(photo.key()));
          return tile;
        }
      };

  /** maxSelection=0 means unrestricted; one is used by the export success “再编辑一张” path. */
  GallerySheet(Activity activity, int maxSelection, Listener listener) {
    super(activity);
    this.activity = activity;
    this.maxSelection = Math.max(0, maxSelection);
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
    view.setIncludeFontPadding(false);
    AppStyle.setTypeface(view, AppStyle.FONT_REGULAR);
    return view;
  }

  private TextView action(String value, int color, Runnable run) {
    TextView view = text(value, AppStyle.TYPE_BODY, color);
    view.setGravity(Gravity.CENTER);
    view.setPadding(dp(12), 0, dp(12), 0);
    view.setOnClickListener(v -> run.run());
    view.setFocusable(true);
    AppStyle.pillAction(view, color, Color.TRANSPARENT);
    return view;
  }

  private TextView primaryAction(String value, Runnable run) {
    TextView view = text(value, AppStyle.TYPE_BODY, AppStyle.ON_PRIMARY);
    view.setGravity(Gravity.CENTER);
    view.setPadding(dp(12), 0, dp(12), 0);
    view.setOnClickListener(v -> run.run());
    view.setFocusable(true);
    AppStyle.pillAction(view, AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    AppStyle.setTypeface(view, AppStyle.FONT_BOLD);
    return view;
  }

  private void build() {
    root = column();
    AppStyle.surface(root, AppStyle.SURFACE, AppStyle.R_LARGE);
    PanelHandle grab = new PanelHandle(getContext());
    grab.setContentDescription("上拉展开图库，下拉收起");
    grab.setFocusable(true);
    grab.setClickable(true);
    View handle = new View(getContext());
    GradientDrawable pill = new GradientDrawable();
    pill.setColor(AppStyle.GRIP);
    pill.setCornerRadius(dp(3));
    handle.setBackground(pill);
    FrameLayout.LayoutParams line =
        new FrameLayout.LayoutParams(dp(34), dp(4), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
    line.topMargin = dp(11);
    grab.addView(handle, line);
    grab.setOnClickListener(
        v -> {
          boolean expand = getWindow().getAttributes().height < expandedHeight;
          if (albumPanelOpen) setAlbumPanelOpen(false, false);
          height(expand ? expandedHeight : collapsedHeight);
        });
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
            if (dragging) {
              if (albumPanelOpen) setAlbumPanelOpen(false, false);
              height(Math.max(dp(160), Math.min(expandedHeight, dragHeight - Math.round(dy))));
            }
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
    header.setPadding(dp(20), 0, dp(20), dp(5));
    LinearLayout albumSelector = column();
    albumSelector.setOrientation(LinearLayout.HORIZONTAL);
    albumSelector.setGravity(Gravity.CENTER_VERTICAL);
    albumSelector.setMinimumHeight(dp(AppStyle.TOUCH_MIN));
    albumSelector.setPadding(0, dp(4), dp(20), dp(4));
    albumSelector.setClickable(true);
    albumSelector.setFocusable(true);
    albumSelector.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x24ffffff),
            null,
            AppStyle.shape(getContext(), Color.WHITE, AppStyle.R_SMALL, false)));
    albumSelector.setContentDescription("最近照片，点击选择其他相册");
    albumSelector.setOnClickListener(v -> albums());
    LinearLayout albumLine = new LinearLayout(getContext());
    albumLine.setOrientation(LinearLayout.HORIZONTAL);
    albumLine.setGravity(Gravity.CENTER_VERTICAL);
    title = text("最近照片", AppStyle.TYPE_HEADING, INK);
    AppStyle.setTypeface(title, AppStyle.FONT_BOLD);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    title.setMaxWidth(dp(150));
    title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    albumLine.addView(title, new LinearLayout.LayoutParams(-2, dp(30)));
    albumArrow = new ImageView(getContext());
    albumArrow.setImageResource(R.drawable.ic_material_keyboard_arrow_down);
    albumArrow.setImageTintList(ColorStateList.valueOf(Color.WHITE));
    albumArrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(24), dp(24));
    arrowParams.leftMargin = dp(4);
    albumLine.addView(albumArrow, arrowParams);
    albumSelector.addView(albumLine, new LinearLayout.LayoutParams(-2, dp(44)));
    header.addView(albumSelector, new LinearLayout.LayoutParams(0, dp(58), 1));
    selectionSummary = text("已选 0 张", AppStyle.TYPE_MICRO, GOLD);
    AppStyle.setNumericTypeface(selectionSummary);
    selectionSummary.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    header.addView(selectionSummary, new LinearLayout.LayoutParams(dp(86), dp(58)));
    root.addView(header);

    access = action("", GOLD, listener::permission);
    access.setTextSize(AppStyle.TYPE_MICRO);
    access.setGravity(Gravity.CENTER_VERTICAL);
    access.setPadding(dp(20), 0, dp(20), 0);
    root.addView(access, new LinearLayout.LayoutParams(-1, dp(AppStyle.TOUCH_MIN)));
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
    grid.setContentDescription("照片网格，三列，点击选择或取消选择");
    grid.setOnItemClickListener(
        (parent, view, position, id) -> {
          if (position >= visible.size()) return;
          GalleryPhotos.Photo photo = visible.get(position);
          String key = photo.key();
          if (selected.containsKey(key)) selected.remove(key);
          else {
            if (maxSelection > 0 && selected.size() >= maxSelection) {
              AppNotice.show(this, "最多选择 " + maxSelection + " 张照片");
            } else selected.put(key, photo);
          }
          updateSelectionUi();
          adapter.notifyDataSetChanged();
        });
    body.addView(grid, new FrameLayout.LayoutParams(-1, -1));
    empty = column();
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(dp(24), dp(12), dp(24), dp(12));
    emptyText = text("正在读取相册…", AppStyle.TYPE_HEADING, MUTED);
    emptyText.setGravity(Gravity.CENTER);
    empty.addView(emptyText, new LinearLayout.LayoutParams(-1, -2));
    body.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    grid.setEmptyView(empty);

    albumPanel = column();
    albumPanel.setVisibility(View.GONE);
    albumList = new ListView(getContext());
    albumList.setDivider(new ColorDrawable(AppStyle.OUTLINE));
    albumList.setDividerHeight(1);
    albumList.setSelector(new ColorDrawable(Color.TRANSPARENT));
    albumList.setClipToPadding(false);
    albumList.setPadding(0, dp(2), 0, dp(2));
    albumPanel.addView(albumList, new LinearLayout.LayoutParams(-1, -1));
    body.addView(albumPanel, new FrameLayout.LayoutParams(-1, -1));
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

    LinearLayout footer = new LinearLayout(getContext());
    footer.setPadding(dp(14), dp(8), dp(14), dp(14));
    footer.setGravity(Gravity.CENTER_VERTICAL);
    TextView cancel = text("取消", AppStyle.TYPE_BODY, INK);
    cancel.setGravity(Gravity.CENTER);
    cancel.setPadding(dp(12), 0, dp(12), 0);
    cancel.setOnClickListener(v -> dismiss());
    AppStyle.pillAction(cancel, INK, AppStyle.CONTROL);
    footer.addView(cancel, new LinearLayout.LayoutParams(0, dp(AppStyle.TOUCH_PRIMARY), 1));
    confirmButton = primaryAction("确认导入", this::confirmImport);
    LinearLayout.LayoutParams confirmParams =
        new LinearLayout.LayoutParams(0, dp(AppStyle.TOUCH_PRIMARY), 1);
    confirmParams.leftMargin = dp(12);
    footer.addView(confirmButton, confirmParams);
    root.addView(footer);
    setContentView(root);
  }

  public void show() {
    Window window = getWindow();
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    window.setGravity(Gravity.BOTTOM);
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams attributes = window.getAttributes();
    attributes.dimAmount = .58f;
    attributes.y = dp(10);
    attributes.alpha = 1f;
    window.setAttributes(attributes);
    window.setWindowAnimations(R.style.GallerySheetAnimation);
    resize(true);
    root.setAlpha(1f);
    super.show();
    activity.getWindow().getDecorView().addOnLayoutChangeListener(windowSizeChanged);
    refresh();
  }

  private void resize(boolean initial) {
    boolean expanded =
        albumPanelOpen
            || (!initial
                && getWindow().getAttributes().height
                    > (collapsedHeight + expandedHeight) / 2);
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
    collapsedHeight = Math.min(available, Math.max(dp(250), Math.round(available * .58f)));
    expandedHeight = Math.max(collapsedHeight, Math.round(available * .94f));
    getWindow().setLayout(sheetWidth, expanded ? expandedHeight : collapsedHeight);
  }

  private void height(int pixels) {
    if (!closed) getWindow().setLayout(sheetWidth, pixels);
  }

  void refresh() {
    if (closed) return;
    if (querySignal != null) querySignal.cancel();
    querySignal = new CancellationSignal();
    loading = GalleryPhotos.access(activity) != GalleryPhotos.Access.NONE;
    loadError = "";
    phone = new ArrayList<>();
    seenKeys.clear();
    paging = new GalleryPaging(GalleryPhotos.PAGE_SIZE);
    fullScan = false;
    filter(false);
    if (!loading) return;
    loadPage();
  }

  /**
   * Reads one metadata page and publishes it as soon as it lands, so a phone with tens of
   * thousands of photos shows its first screen immediately instead of a spinner until the whole
   * library has been walked.
   */
  private void loadPage() {
    final CancellationSignal signal = querySignal;
    final GalleryPaging cursor = paging;
    if (closed || signal == null || cursor == null) return;
    final int offset = cursor.offset();
    final int limit = fullScan ? 0 : cursor.pageSize();
    metadata.execute(
        () -> {
          try {
            GalleryPhotos.Page result = GalleryPhotos.page(activity, signal, offset, limit);
            main.post(() -> acceptPage(signal, result));
          } catch (OperationCanceledException ignored) {
            // User dismissed or restarted the query; this page is intentionally stale.
          } catch (Throwable error) {
            main.post(() -> failQuery(signal));
          }
        });
  }

  private void acceptPage(CancellationSignal signal, GalleryPhotos.Page result) {
    if (closed || signal.isCanceled() || querySignal != signal || paging == null) return;
    ArrayList<GalleryPhotos.Photo> merged = new ArrayList<>(phone);
    int added = 0;
    for (GalleryPhotos.Photo photo : result.photos)
      if (seenKeys.add(photo.key())) {
        merged.add(photo);
        added++;
      }
    merged.sort(GalleryPhotos.NEWEST_FIRST);
    phone = merged;
    loading = false;
    if (added > 0 || phone.isEmpty()) filter(false);
    int next =
        result.complete
            ? GalleryPaging.DONE
            : paging.accept(result.rows, result.photos.size(), added);
    if (fullScan) return; // The whole library was read in one pass; nothing follows.
    if (next == GalleryPaging.MORE) loadPage();
    else if (next == GalleryPaging.FULL_SCAN) {
      // Some vendors ignore QUERY_ARG_OFFSET; re-read the library in one pass instead of stopping
      // after the first page.
      seenKeys.clear();
      phone = new ArrayList<>();
      fullScan = true;
      filter(false);
      loadPage();
    }
  }

  private void failQuery(CancellationSignal signal) {
    if (closed || signal.isCanceled() || querySignal != signal) return;
    loading = false;
    if (phone.isEmpty()) loadError = "相册暂时无法读取";
    filter(false);
  }

  private void filter(boolean scrollToTop) {
    if (closed) return;
    visible = new ArrayList<>();
    for (GalleryPhotos.Photo photo : phone)
      if (albumId.isEmpty() || albumId.equals(photo.albumId)) visible.add(photo);
    String label = "最近照片";
    if (!albumId.isEmpty())
      for (GalleryPhotos.Photo photo : phone)
        if (albumId.equals(photo.albumId)) {
          label = photo.albumName;
          break;
        }
    title.setText(label);
    GalleryPhotos.Access permission = GalleryPhotos.access(activity);
    access.setVisibility(permission == GalleryPhotos.Access.PARTIAL ? View.VISIBLE : View.GONE);
    access.setText("可见部分照片 · 管理");
    while (empty.getChildCount() > 1) empty.removeViewAt(1);
    if (loading) emptyText.setText("正在读取相册…");
    else if (permission == GalleryPhotos.Access.NONE) {
      emptyText.setText("允许访问相册后，在这里选择要导入的照片");
      LinearLayout.LayoutParams button =
          new LinearLayout.LayoutParams(-1, dp(AppStyle.TOUCH_MIN));
      button.topMargin = dp(14);
      empty.addView(primaryAction("允许访问相册", listener::permission), button);
    } else emptyText.setText(loadError.isEmpty() ? "这个相册还没有可用照片" : loadError);
    adapter.notifyDataSetChanged();
    if (albumPanelOpen) populateAlbumRows();
    updateSelectionUi();
    if (scrollToTop) grid.setSelection(0);
  }

  private void updateSelectionUi() {
    selectionSummary.setText(String.format(Locale.CHINA, "已选 %d 张", selected.size()));
    confirmButton.setText("确认导入");
    confirmButton.setEnabled(!selected.isEmpty());
  }

  private void confirmImport() {
    if (selected.isEmpty()) return;
    ArrayList<GalleryPhotos.Photo> result = new ArrayList<>(selected.values());
    if (maxSelection > 0 && result.size() > maxSelection)
      result = new ArrayList<>(result.subList(0, maxSelection));
    dismiss();
    listener.importPhotos(result);
  }

  private void albums() {
    if (albumPanelOpen) {
      setAlbumPanelOpen(false);
      return;
    }
    populateAlbumRows();
    setAlbumPanelOpen(true);
  }

  private void setAlbumPanelOpen(boolean open) {
    setAlbumPanelOpen(open, true);
  }

  private void setAlbumPanelOpen(boolean open, boolean resizeSheet) {
    albumPanelOpen = open;
    albumPanel.animate().cancel();
    albumArrow.animate().cancel();
    albumArrow.animate().rotation(open ? 180f : 0f).setDuration(160).start();
    if (open) {
      grid.setVisibility(View.GONE);
      empty.setVisibility(View.GONE);
      albumPanel.setVisibility(View.VISIBLE);
      albumPanel.setAlpha(0f);
      albumPanel.setTranslationY(-dp(8));
      albumPanel
          .animate()
          .alpha(1f)
          .translationY(0)
          .setDuration(180)
          .start();
      albumList.requestFocus();
      height(expandedHeight);
    } else {
      albumPanel
          .animate()
          .alpha(0f)
          .translationY(-dp(6))
          .setDuration(140)
          .withEndAction(
              () -> {
                if (!albumPanelOpen) {
                  albumPanel.setVisibility(View.GONE);
                  grid.setVisibility(View.VISIBLE);
                  empty.setVisibility(visible.isEmpty() ? View.VISIBLE : View.GONE);
                }
              })
          .start();
      if (resizeSheet) height(collapsedHeight);
    }
  }

  private void populateAlbumRows() {
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
      names.add(albums.get(id));
    }
    BaseAdapter albumAdapter =
        new BaseAdapter() {
          public int getCount() {
            return ids.size();
          }

          public Object getItem(int position) {
            return names.get(position);
          }

          public long getItemId(int position) {
            return position;
          }

          public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row =
                recycled instanceof LinearLayout
                    ? (LinearLayout) recycled
                    : new LinearLayout(getContext());
            row.removeAllViews();
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(dp(54));
            row.setPadding(dp(20), 0, dp(16), 0);
            String id = ids.get(position);
            boolean active = albumId.equals(id);
            TextView name = text(names.get(position), AppStyle.TYPE_BODY, active ? GOLD : INK);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setLineSpacing(dp(2), 1);
            row.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
            int count = id.isEmpty() ? phone.size() : counts.getOrDefault(id, 0);
            TextView total = text(String.valueOf(count), AppStyle.TYPE_MICRO, active ? GOLD : MUTED);
            AppStyle.setNumericTypeface(total);
            total.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(total, new LinearLayout.LayoutParams(dp(48), -1));
            if (active) {
              ImageView check = new ImageView(getContext());
              check.setImageResource(R.drawable.ic_material_check);
              check.setImageTintList(ColorStateList.valueOf(GOLD));
              check.setPadding(dp(3), dp(3), dp(3), dp(3));
              check.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
              LinearLayout.LayoutParams checkParams = new LinearLayout.LayoutParams(dp(24), dp(24));
              checkParams.leftMargin = dp(4);
              row.addView(check, checkParams);
            } else row.addView(new View(getContext()), new LinearLayout.LayoutParams(dp(28), 1));
            row.setContentDescription(
                names.get(position) + "，" + count + " 张照片" + (active ? "，当前相册" : ""));
            return row;
          }
        };
    albumList.setAdapter(albumAdapter);
    int current = ids.indexOf(albumId);
    if (current >= 0) albumList.setSelection(current);
    albumList.setOnItemClickListener(
        (parent, view, position, id) -> {
          albumId = ids.get(position);
          // Keep the newly expanded sheet height so the selected album opens with more rows visible.
          setAlbumPanelOpen(false, false);
          filter(true);
        });
  }

  private final class Tile extends FrameLayout {
    final ImageView image, check;
    final View scrim;
    CancellationSignal signal;
    Future<?> future;
    boolean loading;

    Tile() {
      super(GallerySheet.this.getContext());
      image = new ImageView(getContext());
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      addView(image, new FrameLayout.LayoutParams(-1, -1));
      scrim = new View(getContext());
      scrim.setBackgroundColor(0x66000000);
      scrim.setVisibility(View.GONE);
      addView(scrim, new FrameLayout.LayoutParams(-1, -1));
      check = new ImageView(getContext());
      check.setImageResource(R.drawable.ic_material_check);
      check.setImageTintList(ColorStateList.valueOf(AppStyle.ON_PRIMARY));
      check.setBackground(AppStyle.pill(getContext(), GOLD, false));
      check.setPadding(dp(4), dp(4), dp(4), dp(4));
      check.setVisibility(View.GONE);
      FrameLayout.LayoutParams checkParams =
          new FrameLayout.LayoutParams(
              dp(AppStyle.SELECTION_BADGE), dp(AppStyle.SELECTION_BADGE), Gravity.TOP | Gravity.END);
      checkParams.topMargin = dp(7);
      checkParams.rightMargin = dp(7);
      addView(check, checkParams);
      setBackground(
          AppStyle.shape(getContext(), AppStyle.CONTROL, AppStyle.R_SMALL, false));
      setClipToOutline(true);
      setLayoutParams(new AbsListView.LayoutParams(-1, -2));
    }

    protected void onMeasure(int width, int height) {
      int side = View.MeasureSpec.getSize(width);
      super.onMeasure(
          View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY));
    }

    void bind(GalleryPhotos.Photo photo, boolean isSelected) {
      String key = photo.key();
      boolean same = key.equals(image.getTag());
      setContentDescription(
          (isSelected ? "已选择，" : "") + photo.name + "，点击" + (isSelected ? "取消选择" : "选择"));
      scrim.setVisibility(isSelected ? View.VISIBLE : View.GONE);
      check.setVisibility(isSelected ? View.VISIBLE : View.GONE);
      setForeground(
          new RippleDrawable(
              ColorStateList.valueOf(0x30ffffff),
              isSelected ? borderFor() : null,
              AppStyle.shape(getContext(), Color.WHITE, AppStyle.R_SMALL, false)));
      if (same) {
        Bitmap hit = cache.get(key);
        if (hit != null) {
          if (image.getDrawable() == null) image.setImageBitmap(hit);
          return;
        }
        if (loading || image.getDrawable() != null) return;
      } else {
        cancelLoad();
        image.setTag(key);
        image.setImageBitmap(null);
      }
      startLoad(photo, key);
    }

    private void cancelLoad() {
      if (signal != null) {
        signal.cancel();
        thumbnailSignals.remove(signal);
        signal = null;
      }
      if (future != null) {
        future.cancel(true);
        future = null;
      }
      loading = false;
    }

    private void startLoad(GalleryPhotos.Photo photo, String key) {
      loading = true;
      CancellationSignal request = signal = new CancellationSignal();
      thumbnailSignals.add(request);
      future =
          thumbnails.submit(
              () -> {
                Bitmap loaded = null;
                try {
                  request.throwIfCanceled();
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
                } catch (Exception ignored) {
                }
                Bitmap bitmap = loaded;
                main.post(
                    () -> {
                      thumbnailSignals.remove(request);
                      if (signal != request) return;
                      signal = null;
                      future = null;
                      loading = false;
                      if (closed || request.isCanceled() || bitmap == null) return;
                      cache.put(key, bitmap);
                      if (key.equals(image.getTag())) image.setImageBitmap(bitmap);
                    });
              });
    }

    private Drawable borderFor() {
      GradientDrawable border = new GradientDrawable();
      border.setColor(Color.TRANSPARENT);
      border.setStroke(dp(2), GOLD);
      border.setCornerRadius(dp(AppStyle.R_SMALL));
      return border;
    }
  }

  void setPhotosForTest(List<GalleryPhotos.Photo> photos) {
    phone = new ArrayList<>(photos);
    loading = false;
    filter(false);
  }

  private void release() {
    if (closed) return;
    closed = true;
    AppNotice.clear(getWindow());
    if (querySignal != null) querySignal.cancel();
    for (CancellationSignal signal : thumbnailSignals) signal.cancel();
    thumbnailSignals.clear();
    albumPanel.animate().cancel();
    albumArrow.animate().cancel();
    if (root != null) root.animate().cancel();
    activity.getWindow().getDecorView().removeOnLayoutChangeListener(windowSizeChanged);
    metadata.shutdownNow();
    thumbnails.shutdownNow();
    grid.setAdapter(null);
    phone.clear();
    visible.clear();
    selected.clear();
    cache.evictAll();
    main.removeCallbacksAndMessages(null);
  }
}
