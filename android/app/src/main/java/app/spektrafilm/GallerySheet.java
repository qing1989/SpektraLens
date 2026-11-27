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

    default void files() {}
  }

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
  private boolean closed, loading, albumPanelOpen, fullScan, rawOnly;
  private String albumId = "", loadError = "";
  private LinearLayout root;
  private GridView grid;
  private ListView albumList;
  private FrameLayout photosPanel, albumPanel;
  private ImageView albumIcon, albumArrow;
  private TextView access, emptyText, selectionSummary, confirmButton, allFilter, rawFilter,
      albumSourceLabel;
  private ImageView emptyIcon;
  private LinearLayout empty, albumSourceButton;
  private final Runnable enforceOpaque =
      () -> {
        if (closed) return;
        Window window = getWindow();
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.alpha != 1f) {
          attributes.alpha = 1f;
          window.setAttributes(attributes);
        }
        window.getDecorView().setAlpha(1f);
        if (root != null) root.setAlpha(1f);
      };

  private final View.OnLayoutChangeListener windowSizeChanged =
      (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (!closed && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop))
          resize();
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
          tile.bind(photo, selectionOrder(photo.key()));
          return tile;
        }
      };

  private int selectionOrder(String key) {
    if (!selected.containsKey(key)) return 0;
    int index = 1;
    for (String candidate : selected.keySet()) {
      if (candidate.equals(key)) return index;
      index++;
    }
    return 0;
  }

  /** maxSelection=0 means unrestricted; one is used by the export success “再编辑一张” path. */
  GallerySheet(Activity activity, int maxSelection, Listener listener) {
    super(activity);
    this.activity = activity;
    this.maxSelection = Math.max(0, maxSelection);
    this.listener = listener;
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    getWindow().setWindowAnimations(0);
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

  private TextView filterChip(String label, Runnable action) {
    TextView chip = text(label, AppStyle.TYPE_MICRO, AppStyle.MUTED);
    chip.setGravity(Gravity.CENTER);
    chip.setPadding(dp(12), 0, dp(12), 0);
    chip.setFocusable(true);
    chip.setOnClickListener(v -> action.run());
    chip.setContentDescription(label + "筛选");
    styleFilter(chip, false);
    return chip;
  }

  private void styleFilter(TextView chip, boolean active) {
    chip.setTextColor(active ? AppStyle.GOLD : AppStyle.MUTED);
    AppStyle.setTypeface(chip, active ? AppStyle.FONT_SEMIBOLD : AppStyle.FONT_MEDIUM);
    GradientDrawable bg =
        AppStyle.shape(
            getContext(),
            active ? AppStyle.ACCENT_SOFT : Color.TRANSPARENT,
            AppStyle.R_SMALL,
            false);
    if (active) bg.setStroke(Math.max(1, dp(.85f)), AppStyle.GOLD);
    chip.setBackground(bg);
  }

  private LinearLayout sourceButton(int iconRes, String label, Runnable action) {
    LinearLayout button = new LinearLayout(getContext());
    button.setOrientation(LinearLayout.HORIZONTAL);
    button.setGravity(Gravity.CENTER);
    button.setPadding(dp(10), 0, dp(10), 0);
    button.setBackground(AppStyle.shape(getContext(), AppStyle.CONTROL, 10f, true));
    button.setClickable(true);
    button.setFocusable(true);
    button.setContentDescription(label);
    ImageView icon = new ImageView(getContext());
    icon.setImageResource(iconRes);
    icon.setImageTintList(ColorStateList.valueOf(AppStyle.INK));
    icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    button.addView(icon, new LinearLayout.LayoutParams(dp(18), dp(18)));
    TextView name = text(label, AppStyle.TYPE_LABEL, AppStyle.INK);
    AppStyle.setTypeface(name, AppStyle.FONT_SEMIBOLD);
    name.setGravity(Gravity.CENTER);
    LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-2, -2);
    nameParams.leftMargin = dp(6);
    button.addView(name, nameParams);
    ImageView arrow = new ImageView(getContext());
    arrow.setImageResource(R.drawable.ic_material_keyboard_arrow_down);
    arrow.setImageTintList(ColorStateList.valueOf(AppStyle.INK));
    arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams arrowParams = new LinearLayout.LayoutParams(dp(16), dp(16));
    arrowParams.leftMargin = dp(4);
    button.addView(arrow, arrowParams);
    if (label.equals("从相册选择")) {
      albumIcon = icon;
      albumSourceLabel = name;
      albumArrow = arrow;
    }
    button.setOnClickListener(v -> action.run());
    return button;
  }

  private void styleAlbumSourceButton(boolean open) {
    if (albumSourceButton == null) return;
    GradientDrawable bg =
        AppStyle.shape(getContext(), open ? AppStyle.ACCENT_SOFT : AppStyle.CONTROL, 10f, false);
    bg.setStroke(Math.max(1, dp(.85f)), open ? AppStyle.GOLD : AppStyle.OUTLINE);
    albumSourceButton.setBackground(bg);
    int fg = open ? AppStyle.GOLD : AppStyle.INK;
    if (albumIcon != null) albumIcon.setImageTintList(ColorStateList.valueOf(fg));
    if (albumSourceLabel != null) albumSourceLabel.setTextColor(fg);
    if (albumArrow != null) albumArrow.setImageTintList(ColorStateList.valueOf(fg));
  }

  private void setRawOnly(boolean value) {
    if (rawOnly == value) return;
    rawOnly = value;
    styleFilter(allFilter, !rawOnly);
    styleFilter(rawFilter, rawOnly);
    filter(false);
  }

  private static boolean isRaw(GalleryPhotos.Photo photo) {
    return photo != null && PhotoFormatPolicy.isRaw(photo.name, photo.mime);
  }

  private boolean hasRawPhotos() {
    for (GalleryPhotos.Photo photo : phone) if (isRaw(photo)) return true;
    return false;
  }

  private String defaultRawAlbum() {
    for (GalleryPhotos.Photo photo : phone)
      if (isRaw(photo) && photo.albumId.isEmpty()) return "";
    for (GalleryPhotos.Photo photo : phone) if (isRaw(photo)) return photo.albumId;
    return "";
  }


  private void build() {
    root = column();
    root.setBackground(AppStyle.shape(getContext(), AppStyle.SURFACE, AppStyle.R_LARGE, true));
    root.setClipToOutline(true);
    LinearLayout meta = new LinearLayout(getContext());
    meta.setOrientation(LinearLayout.HORIZONTAL);
    meta.setGravity(Gravity.CENTER_VERTICAL);
    meta.setPadding(dp(16), dp(14), dp(16), dp(6));
    LinearLayout filters = new LinearLayout(getContext());
    filters.setOrientation(LinearLayout.HORIZONTAL);
    allFilter = filterChip("全部", () -> setRawOnly(false));
    rawFilter = filterChip("RAW", () -> setRawOnly(true));
    styleFilter(allFilter, true);
    styleFilter(rawFilter, false);
    filters.addView(allFilter, new LinearLayout.LayoutParams(-2, dp(30)));
    LinearLayout.LayoutParams rawParams = new LinearLayout.LayoutParams(-2, dp(30));
    rawParams.leftMargin = dp(6);
    filters.addView(rawFilter, rawParams);
    meta.addView(filters, new LinearLayout.LayoutParams(0, dp(34), 1));
    selectionSummary = text("已选 0 张", AppStyle.TYPE_LABEL, AppStyle.GOLD);
    AppStyle.setNumericTypeface(selectionSummary);
    AppStyle.setTypeface(selectionSummary, AppStyle.FONT_BOLD);
    selectionSummary.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    meta.addView(selectionSummary, new LinearLayout.LayoutParams(-2, dp(34)));
    root.addView(meta);

    LinearLayout sources = new LinearLayout(getContext());
    sources.setOrientation(LinearLayout.HORIZONTAL);
    sources.setGravity(Gravity.CENTER_VERTICAL);
    sources.setPadding(dp(16), dp(6), dp(16), dp(10));
    albumSourceButton =
        sourceButton(R.drawable.ic_import_album, "从相册选择", this::albums);
    sources.addView(albumSourceButton, new LinearLayout.LayoutParams(0, dp(44), 1));
    LinearLayout fileSource =
        sourceButton(R.drawable.ic_import_folder, "从文件选择", () -> {
          dismiss();
          listener.files();
        });
    LinearLayout.LayoutParams fileParams = new LinearLayout.LayoutParams(0, dp(44), 1);
    fileParams.leftMargin = dp(9);
    sources.addView(fileSource, fileParams);
    root.addView(sources);


    access = action("", AppStyle.GOLD, listener::permission);
    access.setTextSize(AppStyle.TYPE_MICRO);
    access.setGravity(Gravity.CENTER_VERTICAL);
    access.setPadding(dp(20), 0, dp(20), 0);
    root.addView(access, new LinearLayout.LayoutParams(-1, dp(AppStyle.TOUCH_MIN)));
    FrameLayout body = new FrameLayout(getContext());
    photosPanel = new FrameLayout(getContext());
    grid = new GridView(getContext());
    int initialCols = FoldableLayout.galleryColumns(FoldableUi.windowWidthDp(activity));
    grid.setNumColumns(initialCols);
    grid.setHorizontalSpacing(dp(4));
    grid.setVerticalSpacing(dp(4));
    grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
    grid.setPadding(dp(12), dp(4), dp(12), dp(12));
    grid.setClipToPadding(false);
    grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
    grid.setAdapter(adapter);
    updateGridContentDescription(initialCols);
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
    photosPanel.addView(grid, new FrameLayout.LayoutParams(-1, -1));
    empty = column();
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(dp(24), dp(12), dp(24), dp(12));
    emptyIcon = new ImageView(getContext());
    emptyIcon.setImageResource(R.drawable.ic_ui_not_found);
    emptyIcon.setImageTintList(ColorStateList.valueOf(AppStyle.MUTED));
    emptyIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    emptyIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    emptyIcon.setVisibility(View.GONE);
    LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(64), dp(64));
    iconParams.bottomMargin = dp(16);
    empty.addView(emptyIcon, iconParams);
    emptyText = text("正在读取相册…", AppStyle.TYPE_TITLE, AppStyle.MUTED);
    emptyText.setGravity(Gravity.CENTER);
    empty.addView(emptyText, new LinearLayout.LayoutParams(-1, -2));
    photosPanel.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    grid.setEmptyView(empty);
    AppStyle.attachScrollFade(photosPanel, grid, AppStyle.SURFACE, 28, 26);
    body.addView(photosPanel, new FrameLayout.LayoutParams(-1, -1));

    albumPanel = new FrameLayout(getContext());
    albumPanel.setBackgroundColor(AppStyle.SURFACE);
    albumPanel.setVisibility(View.GONE);
    albumList = new ListView(getContext());
    albumList.setDivider(new ColorDrawable(AppStyle.DIVIDER));
    albumList.setDividerHeight(1);
    albumList.setSelector(new ColorDrawable(Color.TRANSPARENT));
    albumList.setClipToPadding(false);
    albumList.setPadding(0, dp(4), 0, dp(10));
    albumPanel.addView(albumList, new FrameLayout.LayoutParams(-1, -1));
    AppStyle.attachScrollFade(albumPanel, albumList, AppStyle.SURFACE, 28, 26);
    body.addView(albumPanel, new FrameLayout.LayoutParams(-1, -1));
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

    LinearLayout footer = new LinearLayout(getContext());
    footer.setPadding(dp(14), dp(8), dp(14), dp(14));
    footer.setGravity(Gravity.CENTER_VERTICAL);
    TextView cancel = text("取消", AppStyle.TYPE_BODY, AppStyle.INK);
    cancel.setGravity(Gravity.CENTER);
    cancel.setPadding(dp(12), 0, dp(12), 0);
    cancel.setOnClickListener(v -> dismiss());
    AppStyle.pillAction(cancel, AppStyle.INK, AppStyle.CONTROL);
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
    // Keep the window itself opaque as well as the root. Some OPPO window managers
    // composite a transparent dialog background even when the content View is opaque.
    window.setBackgroundDrawable(
        AppStyle.shape(getContext(), AppStyle.SURFACE, AppStyle.R_LARGE, true));
    window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams attributes = window.getAttributes();
    attributes.dimAmount = AppStyle.DIM_AMOUNT;
    attributes.y = dp(FoldableUi.FLOATING_SHEET_MARGIN_DP);
    attributes.alpha = 1f;
    window.setAttributes(attributes);
    // Some OEMs still apply their dialog alpha transition even when a custom
    // windowEnterAnimation is supplied. Disable the window animation and slide only the
    // opaque content, so no frame can show the Activity through the sheet.
    window.setWindowAnimations(0);
    resize();
    root.setAlpha(1f);
    root.setTranslationY(0);
    super.show();
    // Reassert alpha after attach and during the first frames. OPPO can write its own
    // transition alpha after the dialog is already added.
    enforceOpaque.run();
    main.post(enforceOpaque);
    main.postDelayed(enforceOpaque, 32);
    main.postDelayed(enforceOpaque, 96);
    main.postDelayed(enforceOpaque, 240);
    activity.getWindow().getDecorView().addOnLayoutChangeListener(windowSizeChanged);
    refresh();
  }

  @Override
  public void onWindowAttributesChanged(WindowManager.LayoutParams params) {
    if (!closed && params.alpha != 1f) params.alpha = 1f;
    super.onWindowAttributesChanged(params);
  }

  @Override
  public void dismiss() {
    if (!closed) {
      Window window = getWindow();
      window.setWindowAnimations(0);
      WindowManager.LayoutParams attributes = window.getAttributes();
      attributes.alpha = 1f;
      window.setAttributes(attributes);
      if (root != null) {
        root.animate().cancel();
        root.setAlpha(1f);
        root.setTranslationY(0);
      }
    }
    super.dismiss();
  }

  private void resize() {
    // The import sheet is intentionally fixed at its expanded height. There is no drag handle
    // and no collapsed state in the new import flow.
    Rect visible = new Rect();
    activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
    int available = visible.height() - dp(FoldableUi.FLOATING_SHEET_MARGIN_DP * 2 + 12);
    if (available <= 0)
      available = activity.getResources().getDisplayMetrics().heightPixels - dp(84);
    int availableWidth = FoldableUi.visibleWindowWidthPx(activity);
    if (availableWidth <= 0) availableWidth = activity.getResources().getDisplayMetrics().widthPixels;
    int widthDp = FoldableUi.windowWidthDp(activity);
    int maxDp = FoldableLayout.gallerySheetMaxDp(widthDp);
    int sheetWidth =
        FoldableUi.floatingSheetWidthPx(getContext(), Math.max(dp(240), availableWidth), maxDp);
    if (grid != null) {
      int cols = FoldableLayout.galleryColumns(widthDp);
      if (grid.getNumColumns() != cols) grid.setNumColumns(cols);
      updateGridContentDescription(cols);
    }
    int expandedHeight = Math.max(dp(250), Math.round(available * .84f));
    getWindow().setLayout(sheetWidth, expandedHeight);
  }

  private void updateGridContentDescription(int cols) {
    if (grid == null) return;
    String colText = cols == 5 ? "五列" : cols == 4 ? "四列" : "三列";
    grid.setContentDescription("照片网格，" + colText + "，点击选择或取消选择");
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
    boolean hasRaw = !rawOnly || hasRawPhotos();
    if (rawOnly && hasRaw && !loading) {
      boolean currentHasRaw = false;
      for (GalleryPhotos.Photo photo : phone)
        if (isRaw(photo)
            && (albumId.isEmpty() || albumId.equals(photo.albumId))) {
          currentHasRaw = true;
          break;
        }
      if (!currentHasRaw) albumId = defaultRawAlbum();
    }
    visible = new ArrayList<>();
    for (GalleryPhotos.Photo photo : phone)
      if ((albumId.isEmpty() || albumId.equals(photo.albumId))
          && (!rawOnly || isRaw(photo))) visible.add(photo);
    boolean noRaw = rawOnly && !loading && !hasRaw;
    if (noRaw && albumPanelOpen) setAlbumPanelOpen(false);
    emptyIcon.setVisibility(noRaw ? View.VISIBLE : View.GONE);
    albumSourceButton.setEnabled(!noRaw);
    albumSourceButton.setAlpha(noRaw ? .4f : 1f);
    GalleryPhotos.Access permission = GalleryPhotos.access(activity);
    access.setVisibility(permission == GalleryPhotos.Access.PARTIAL ? View.VISIBLE : View.GONE);
    access.setText("可见部分照片 · 管理");
    // Keep the icon and the text; only remove a previously added permission button.
    while (empty.getChildCount() > 2) empty.removeViewAt(2);
    if (loading) emptyText.setText("正在读取相册…");
    else if (permission == GalleryPhotos.Access.NONE) {
      emptyText.setText("允许访问相册后，在这里选择要导入的照片");
      LinearLayout.LayoutParams button =
          new LinearLayout.LayoutParams(-1, dp(AppStyle.TOUCH_MIN));
      button.topMargin = dp(14);
      empty.addView(primaryAction("允许访问相册", listener::permission), button);
    } else if (noRaw) emptyText.setText("没有发现可以导入的 RAW 照片");
    else if (rawOnly)
      emptyText.setText(loadError.isEmpty() ? "这个相册还没有 RAW 照片" : loadError);
    else emptyText.setText(loadError.isEmpty() ? "这个相册还没有可用照片" : loadError);
    adapter.notifyDataSetChanged();
    if (albumPanelOpen) populateAlbumRows();
    updateSelectionUi();
    if (scrollToTop) grid.setSelection(0);
  }

  private void updateSelectionUi() {
    int count = selected.size();
    selectionSummary.setText(String.format(Locale.CHINA, "已选 %d 张", count));
    confirmButton.setText(count > 0 ? String.format(Locale.CHINA, "确认导入 · %d 张", count) : "确认导入");
    confirmButton.setEnabled(count > 0);
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
    albumPanelOpen = open;
    // AdapterView updates the grid and empty view independently on data changes.
    (open ? photosPanel : albumPanel).setVisibility(View.GONE);
    (open ? albumPanel : photosPanel).setVisibility(View.VISIBLE);
    styleAlbumSourceButton(open);
    albumArrow.animate().cancel();
    albumArrow.animate().rotation(open ? 180f : 0f).setDuration(160).start();
    if (open) albumList.requestFocus();
  }

  private void populateAlbumRows() {
    LinkedHashMap<String, String> albums = new LinkedHashMap<>();
    LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
    int filteredTotal = 0;
    for (GalleryPhotos.Photo photo : phone) {
      if (rawOnly && !isRaw(photo)) continue;
      filteredTotal++;
      albums.put(photo.albumId, photo.albumName);
      counts.put(photo.albumId, counts.getOrDefault(photo.albumId, 0) + 1);
    }
    final int allMatchingCount = filteredTotal;
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
            TextView name =
                text(names.get(position), AppStyle.TYPE_BODY, active ? AppStyle.GOLD : AppStyle.INK);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            name.setLineSpacing(dp(2), 1);
            row.addView(name, new LinearLayout.LayoutParams(0, -1, 1));
            int count = id.isEmpty() ? allMatchingCount : counts.getOrDefault(id, 0);
            TextView total =
                text(
                    String.valueOf(count),
                    AppStyle.TYPE_MICRO,
                    active ? AppStyle.GOLD : AppStyle.MUTED);
            AppStyle.setNumericTypeface(total);
            total.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            row.addView(total, new LinearLayout.LayoutParams(dp(48), -1));
            if (active) {
              ImageView check = new ImageView(getContext());
              check.setImageResource(R.drawable.ic_material_check);
              check.setImageTintList(ColorStateList.valueOf(AppStyle.GOLD));
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
          setAlbumPanelOpen(false);
          filter(true);
        });
  }

  private static String formatBadgeText(GalleryPhotos.Photo photo) {
    if (photo == null || photo.name == null) return "";
    String raw = PhotoFormatPolicy.rawBadge(photo.name);
    if (!raw.isEmpty()) return raw;
    String name = photo.name.toLowerCase(Locale.ROOT);
    if (name.endsWith(".heic")) return "HEIC";
    if (name.endsWith(".heif") || name.endsWith(".hif")) return "HEIF";
    if (name.endsWith(".png")) return "PNG";
    if (name.endsWith(".webp")) return "WEBP";
    if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "JPG";
    return PhotoFormatPolicy.isRaw(photo.name, photo.mime) ? "RAW" : "";
  }

  private final class Tile extends FrameLayout {
    final ImageView image;
    final TextView orderBadge, formatBadge;
    final View scrim;
    final RippleDrawable selectedForeground, unselectedForeground;
    boolean foregroundSelected;
    CancellationSignal signal;
    Future<?> future;
    boolean loading;

    Tile() {
      super(GallerySheet.this.getContext());
      image = new ImageView(getContext());
      image.setScaleType(ImageView.ScaleType.CENTER_CROP);
      addView(image, new FrameLayout.LayoutParams(-1, -1));
      scrim = new View(getContext());
      scrim.setBackgroundColor(0x30000000);
      scrim.setVisibility(View.GONE);
      addView(scrim, new FrameLayout.LayoutParams(-1, -1));
      formatBadge = text("", 9, 0xfff4f4f6);
      AppStyle.setTypeface(formatBadge, AppStyle.FONT_BOLD);
      formatBadge.setPadding(dp(5), dp(1.5f), dp(5), dp(1.5f));
      formatBadge.setBackground(AppStyle.shape(getContext(), 0xb80a0a0b, 4f, false));
      formatBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      FrameLayout.LayoutParams fmtParams =
          new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
      fmtParams.leftMargin = dp(5);
      fmtParams.bottomMargin = dp(5);
      addView(formatBadge, fmtParams);
      orderBadge = text("", AppStyle.TYPE_MICRO, AppStyle.ON_ACCENT);
      AppStyle.setNumericTypeface(orderBadge);
      AppStyle.setTypeface(orderBadge, AppStyle.FONT_BOLD);
      orderBadge.setGravity(Gravity.CENTER);
      orderBadge.setBackground(AppStyle.shape(getContext(), AppStyle.GOLD, 6f, false));
      orderBadge.setVisibility(View.GONE);
      orderBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      FrameLayout.LayoutParams checkParams =
          new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END);
      checkParams.topMargin = dp(5);
      checkParams.rightMargin = dp(5);
      addView(orderBadge, checkParams);
      selectedForeground =
          new RippleDrawable(
              ColorStateList.valueOf(0x30ffffff),
              borderFor(),
              AppStyle.shape(getContext(), Color.WHITE, 6f, false));
      unselectedForeground =
          new RippleDrawable(
              ColorStateList.valueOf(0x30ffffff),
              null,
              AppStyle.shape(getContext(), Color.WHITE, 6f, false));
      setForeground(unselectedForeground);
      setBackground(AppStyle.shape(getContext(), AppStyle.CONTROL, 6f, false));
      setClipToOutline(true);
      setLayoutParams(new AbsListView.LayoutParams(-1, -2));
    }

    protected void onMeasure(int width, int height) {
      int side = View.MeasureSpec.getSize(width);
      super.onMeasure(
          View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(side, View.MeasureSpec.EXACTLY));
    }

    void bind(GalleryPhotos.Photo photo, int order) {
      boolean isSelected = order > 0;
      String key = photo.key();
      boolean same = key.equals(image.getTag());
      setContentDescription(
          (isSelected ? "已选择第 " + order + " 张，" : "")
              + photo.name
              + "，点击"
              + (isSelected ? "取消选择" : "选择"));
      scrim.setVisibility(isSelected ? View.VISIBLE : View.GONE);
      orderBadge.setVisibility(isSelected ? View.VISIBLE : View.GONE);
      if (isSelected) orderBadge.setText(String.valueOf(order));
      String fmt = formatBadgeText(photo);
      formatBadge.setVisibility(fmt.isEmpty() ? View.GONE : View.VISIBLE);
      if (!fmt.isEmpty()) formatBadge.setText(fmt);
      if (isSelected != foregroundSelected) {
        foregroundSelected = isSelected;
        setForeground(isSelected ? selectedForeground : unselectedForeground);
      }
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
      if (closed || thumbnails.isShutdown()) return;
      loading = true;
      CancellationSignal request = signal = new CancellationSignal();
      thumbnailSignals.add(request);
      try {
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
      } catch (java.util.concurrent.RejectedExecutionException ignored) {
        thumbnailSignals.remove(request);
        signal = null;
        future = null;
        loading = false;
      }
    }

    private Drawable borderFor() {
      GradientDrawable border = new GradientDrawable();
      border.setColor(Color.TRANSPARENT);
      border.setStroke(dp(2), AppStyle.GOLD);
      border.setCornerRadius(dp(6));
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
