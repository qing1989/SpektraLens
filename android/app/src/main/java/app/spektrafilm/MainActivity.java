// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.widget.*;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class MainActivity extends Activity {
  static final int BG = AppStyle.BG,
      INK = AppStyle.INK,
      MUTED = AppStyle.MUTED,
      ACCENT = AppStyle.ACCENT,
      LINE = AppStyle.OUTLINE;
  /** 面板顶上把手那一行的高度（dp）。常驻占位，不可用时只把胶囊调暗，见 updatePanelHeight。 */
  static final int HANDLE_ROW_DP = 26;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService importWorker = Executors.newFixedThreadPool(2);
  private PhotoImportJob activeImport;
  private volatile long photoLoadGeneration;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ArrayList<View> controls = new ArrayList<>();
  private final ArrayList<Stock> films = new ArrayList<>(), papers = new ArrayList<>();
  private JSONObject settings = new JSONObject();
  private SharedPreferences prefs;
  private LinearLayout root, editor, header, statusRow, editorBody, previewColumn, toolsColumn;
  private FrameLayout photoStage;
  private PanelHandle panelHandle;
  private OnBackInvokedCallback backCallback;
  private boolean panelExpanded, curveHintShown;
  private ScrollView panelScroll;
  /** 当前页正文高度（dp，不含把手那一行）。只决定把手显隐与展开态收回，不参与面板高度。 */
  private int panelBodyHeight;
  private float panelDragY;
  private int panelDragHeight;
  private boolean panelDragging;
  private TextView status,
      filmTitle,
      filmSub,
      paperTitle,
      paperSub,
      fileLabel,
      exportButton,
      compareButton;
  private PhotoView photo;
  private ProgressBar progress;
  private Bitmap source, developed, compared;
  private File sourceFile;
  private JSONObject sourceMetadata = new JSONObject();
  private int fullWidth, fullHeight;
  private boolean sourceRaw;
  private String gradeSection = "基础",
      curveChannel = "rgb",
      balanceZone = "midtones",
      hslBand = "red";
  private volatile boolean destroyed;
  private volatile int revision;
  private long engine; // readiness marker; native engine pointers remain in the renderer process.
  private volatile SpectralRenderer renderer;
  private int engineBootGeneration;
  private long engineBootStarted;
  private boolean engineBootPending;
  private Bitmap openedSource;
  private boolean ready, exporting, comparing;
  private String filename = "内置静物色卡";
  private Runnable pending;
  private final Map<String, SeekBar> sliders = new HashMap<>();
  private final Map<String, TextView> values = new HashMap<>();
  private FrameLayout screens;
  private FlowingBackgroundView flowingBackground;
  private LinearLayout homeScreen, editorScreen, panel, tabRow, filmCards, historyList;
  private ImageView homeHero;
  private HomePhotoImages homeImages;
  private TextView photoPickerButton;
  private GallerySheet gallerySheet;
  private String pickerSettings;
  private int selectedTab;
  private String filmCategory = "全部";
  private final ArrayList<Frame> library = new ArrayList<>();
  private String activeId = "demo";
  private final ArrayDeque<String> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
  private String committed;
  private TextView undoButton, redoButton;
  private TextView recentCount;
  /** 上一次渲染历史列表时的内容指纹，用来跳过无意义的重建。 */
  private String historySignature;

  private static final class Frame {
    String id, name, path, params;

    Frame(String i, String n, String p, String s) {
      id = i;
      name = n;
      path = p;
      params = s;
    }
  }

  static final class Stock {
    String id, name, type, target;

    Stock(JSONObject j) {
      id = j.optString("stock");
      name = j.optString("name");
      type = j.optString("type");
      target = j.optString("target_print");
    }
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Haptics.install(getWindow());
    prefs = getSharedPreferences("darkroom", MODE_PRIVATE);
    try {
      settings = new JSONObject(prefs.getString("settings", "{}"));
      loadCatalog();
    } catch (Exception ignored) {
      settings = new JSONObject();
    }
    defaults();
    committed = settings.toString();
    loadLibrary();
    buildUI();
    installBackHandler();
    updateStockLabels();
    setStatus("正在准备 " + getString(R.string.app_name) + "…", true);
    initializeRenderer();
    handleShare(getIntent());
  }

  private void initializeRenderer() {
    final int boot = ++engineBootGeneration;
    final long photoGeneration = photoLoadGeneration;
    final Frame restoreFrame = findFrame(activeId);
    final boolean restore = source == null;
    ready = false;
    if (homeImages != null) homeImages.setRenderer(null);
    engine = 0;
    openedSource = null;
    engineBootPending = true;
    engineBootStarted = SystemClock.elapsedRealtime();
    SpectralRenderer next = new SpectralRenderer(this);
    renderer = next;
    watchRendererBoot(boot);
    worker.execute(
        () -> {
          if (destroyed) {
            next.close();
            return;
          }
          try {
            File resources =
                EngineResources.prepare(this, new File(getFilesDir(), "engine_267ee243_mobile_v3"));
            String capabilities =
                next.start(resources.getAbsolutePath(), prefs.getString("engine_mode", "auto"));
            if (destroyed || boot != engineBootGeneration) {
              next.close();
              return;
            }
            engine = 1;
            android.util.Log.i("SpektraLensAndroid", "Engine ready: " + capabilities);
            LoadedPhoto initial = null;
            String initialName = demoName();
            boolean demo = false;
            if (restore && photoGeneration == photoLoadGeneration) {
              File last =
                  restoreFrame == null
                      ? new File(getFilesDir(), "source.img")
                      : new File(restoreFrame.path);
              try {
                demo = !last.exists();
                initial = last.exists() ? readPhoto(last, 1024) : loadDemo();
                initialName =
                    last.exists()
                        ? (restoreFrame == null
                            ? prefs.getString("filename", "上次导入的照片")
                            : restoreFrame.name)
                        : demoName();
              } catch (Throwable ex) {
                android.util.Log.e("SpektraLensAndroid", "Restore failed; keeping original", ex);
                initial = loadDemo();
                demo = true;
              }
            }
            final LoadedPhoto first = initial;
            final String name = initialName;
            final boolean isDemo = demo;
            ui(
                () -> {
                  if (boot != engineBootGeneration) {
                    if (first != null) Bitmaps.release(first.bitmap);
                    return;
                  }
                  ready = true;
                  homeImages.setRenderer(next);
                  engineBootPending = false;
                  prefs.edit().remove("last_engine_error").apply();
                  if (first != null) {
                    if (photoGeneration == photoLoadGeneration && source == null) {
                      if (isDemo) {
                        activeId = "demo";
                        prefs.edit().putString("activeId", "demo").apply();
                      }
                      adopt(first);
                      filename = name;
                      fileLabel.setText(filename);
                      refreshStrip();
                      photo.images(source, null);
                      // 恢复会话时首页大图也要用显影后的样子（卡片在 refreshHistory 里自带）。
                      syncHeroLook();
                    } else Bitmaps.release(first.bitmap);
                  }
                  if (activeImport == null) {
                    if (source != null && editorScreen.getVisibility() == View.VISIBLE)
                      scheduleRender();
                    else setStatus("已准备好 · 选择照片开始显影", false);
                  }
                });
          } catch (Throwable ex) {
            next.close();
            ui(
                () -> {
                  if (boot != engineBootGeneration) return;
                  ready = false;
                  engine = 0;
                  engineBootPending = false;
                  if (activeImport != null) {
                    prefs.edit().putString("last_engine_error", ex.toString()).apply();
                    return;
                  }
                  setStatus("显影引擎启动未完成 · 可重试兼容模式", false, true);
                  exportButton.setText("重启兼容显影");
                  exportButton.setEnabled(true);
                  exportButton.setOnClickListener(v -> restartRenderer());
                  android.util.Log.e("SpektraLensAndroid", "Renderer initialization", ex);
                  prefs.edit().putString("last_engine_error", ex.toString()).apply();
                });
          }
        });
  }

  private void watchRendererBoot(int boot) {
    if (destroyed || boot != engineBootGeneration || ready || !engineBootPending) return;
    if (activeImport == null) {
      SpectralRenderer current = renderer;
      String mode = current != null && current.backend().equals("cpu") ? "兼容显影" : "显影引擎";
      String stage = "连接计算进程";
      if (current != null) {
        File state = new File(getFilesDir(), "engine-" + current.backend() + ".json");
        try (InputStream in = new FileInputStream(state);
            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
          copy(in, out);
          JSONObject j = new JSONObject(out.toString("UTF-8"));
          if (j.optInt("pid") == current.processId()) {
            String key = j.optString("stage");
            if (key.equals("spectral_resources")) stage = "加载胶片与印相数据";
            else if (key.startsWith("vkCreateComputePipelines")) stage = "编译计算自检程序";
            else if (key.startsWith("vkWait") || key.startsWith("vkQueue")) stage = "执行计算自检";
            else if (key.equals("vkCreateDevice")) stage = "建立计算设备";
            else if (key.equals("ready")) stage = "恢复照片预览";
            else stage = "加载计算驱动";
          }
        } catch (Exception ignored) {
          // 状态行只是文案，键名解析失败就保持上一段说明
        }
      }
      setStatus(
          "准备"
              + mode
              + " · "
              + stage
              + " · "
              + ((SystemClock.elapsedRealtime() - engineBootStarted) / 1000)
              + " 秒",
          true);
      exportButton.setText("查看显影诊断");
      exportButton.setEnabled(true);
      exportButton.setOnClickListener(v -> showDiagnostics());
    }
    main.postDelayed(() -> watchRendererBoot(boot), 500);
  }

  private void restartRenderer() {
    restartRenderer("cpu");
  }

  private void restartRenderer(String mode) {
    if (exporting) return;
    if (activeImport != null) cancelImport("已取消导入");
    ++revision;
    ++engineBootGeneration;
    if (pending != null) main.removeCallbacks(pending);
    SpectralRenderer old = renderer;
    if (old != null) old.close();
    prefs.edit().putString("engine_mode", mode).apply();
    initializeRenderer();
  }

  private void chooseRendererMode() {
    if (exporting) return;
    String[] modes = {"auto", "gpu", "cpu"};
    String selected = prefs.getString("engine_mode", "auto");
    int choice = selected.equals("cpu") ? 2 : selected.equals("gpu") ? 1 : 0;
    new Haptics.DialogBuilder(this)
        .setTitle("选择显影模式")
        .setSingleChoiceItems(
            new String[] {"自动 · 优先 GPU，故障时兼容显影", "GPU · 重新尝试本机驱动", "兼容显影 · CPU 计算"},
            choice,
            (dialog, which) -> {
              dialog.dismiss();
              restartRenderer(modes[which]);
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void defaults() {
    putDefault("film_stock", "kodak_portra_400");
    putDefault("print_stock", "kodak_portra_endura");
    putDefault("exposure_compensation_ev", 0);
    putDefault("print_brightness", 0);
    putDefault("m_filter_shift", 0);
    putDefault("y_filter_shift", 0);
    putDefault("grain_amount", 1);
    putDefault("halation_amount", 1);
    putDefault("film_format_mm", 36);
    putDefault("product_defaults", true);
    // Preserve the imported file's exposure until the user explicitly enables the meter.
    putDefault("auto_exposure", false);
    putDefault("auto_exposure_method", "balanced");
    // 旧版三档测光（center_weighted / average / median）已不在面板上出现；开着测光的老会话
    // 迁到上游的均衡档，否则面板上会一个都不选中。
    if (settings.optBoolean("auto_exposure", false)) {
      String meter = settings.optString("auto_exposure_method", "balanced");
      if (meter.equals("center_weighted") || meter.equals("average") || meter.equals("median"))
        set("auto_exposure_method", "balanced");
    }
    putDefault("glare_amount", 1);
    putDefault("dir_couplers_amount", 1);
    putDefault("halation_scatter_amount", 1);
    putDefault("density_curve_gamma", 1);
    putDefault("grade_vignette_midpoint", 50);
    putDefault("geo_w", 1);
    putDefault("geo_h", 1);
    putDefault("edge_style", "none");
    putDefault("edge_amount", 6);
    putDefault("edge_frame_number", 36);
    putDefault("date_text", new SimpleDateFormat("yyyy.MM.dd", Locale.US).format(new Date()));
    putDefault("date_size", 1);
    putDefault("date_intensity", 1);
    putDefault("date_corner", "bottom_right");
    putDefault("date_color", "orange");
  }

  private void putDefault(String k, Object v) {
    if (!settings.has(k)) set(k, v);
  }

  private void set(String k, Object v) {
    try {
      if (k.equals("film_stock")) {
        Stock stock = find(films, String.valueOf(v));
        filmCategory = category(stock);
        if (stock != null
            && !String.valueOf(v).equals(settings.optString(k))
            && find(papers, stock.target) != null) settings.put("print_stock", stock.target);
      }
      settings.put(k, v);
    } catch (JSONException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String category(Stock stock) {
    return stock == null
        ? "全部"
        : stock.type.equals("positive")
            ? "反转片"
            : stock.id.contains("vision3") || stock.id.contains("verita") ? "电影胶片" : "彩色负片";
  }

  private int dp(float n) {
    return Math.round(n * getResources().getDisplayMetrics().density);
  }

  private TextView text(String s, int size, int color) {
    TextView v = new TextView(this);
    v.setText(s);
    v.setTextSize(size);
    v.setTextColor(color);
    v.setGravity(Gravity.CENTER_VERTICAL);
    v.setIncludeFontPadding(false);
    v.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    return v;
  }

  private GradientDrawable box(int color, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private TextView button(String title, int color, int background) {
    return button(title, color, background, AppStyle.R_MEDIUM);
  }

  /** 同一容器里的控件可以指定和容器同档的半径，见 AppStyle 的两条配套规则。 */
  private TextView button(String title, int color, int background, float radius) {
    TextView v = text(title, AppStyle.TYPE_BODY, color);
    v.setGravity(Gravity.CENTER);
    // 按钮里折行必然压到上下留白，所以默认锁单行，放不下就用省略号。
    v.setSingleLine(true);
    v.setEllipsize(android.text.TextUtils.TruncateAt.END);
    v.setPadding(dp(14), dp(8), dp(14), dp(8));
    AppStyle.action(
        v,
        color,
        background == BG
            ? Color.TRANSPARENT
            : background == ACCENT
                ? ACCENT
                : background == AppStyle.SELECTED ? AppStyle.SELECTED : AppStyle.CONTROL,
        radius);
    v.setMinHeight(dp(44));
    controls.add(v);
    return v;
  }

  private LinearLayout vertical() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.VERTICAL);
    return v;
  }

  private LinearLayout horizontal() {
    LinearLayout v = new LinearLayout(this);
    v.setOrientation(LinearLayout.HORIZONTAL);
    v.setGravity(Gravity.CENTER_VERTICAL);
    return v;
  }

  private void add(LinearLayout l, View v, int w, int h) {
    l.addView(v, new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h)));
  }

  private void gap(LinearLayout l, int h) {
    add(l, new View(this), 1, h);
  }

  private void rule(LinearLayout l) {
    View v = new View(this);
    v.setBackgroundColor(LINE);
    add(l, v, -1, 1);
  }

  private void section(String index, String name, String detail) {
    gap(editor, 25);
    LinearLayout r = horizontal();
    TextView a = text(index, AppStyle.TYPE_LABEL, ACCENT);
    a.setTypeface(Typeface.MONOSPACE);
    add(r, a, 33, 28);
    TextView b = text(name, AppStyle.TYPE_HEADING, INK);
    b.setTypeface(null, Typeface.BOLD);
    r.addView(b, new LinearLayout.LayoutParams(0, dp(28), 1));
    TextView c = text(detail, AppStyle.TYPE_MICRO, MUTED);
    c.setSingleLine(true);
    c.setEllipsize(android.text.TextUtils.TruncateAt.END);
    add(r, c, -2, 28);
    add(editor, r, -1, -2);
    gap(editor, 9);
  }

  private void buildUI() {
    root = vertical();
    root.setBackgroundColor(BG);
    setContentView(root);
    root.setOnApplyWindowInsetsListener(
        (v, insets) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
          } else
            root.setPadding(
                insets.getSystemWindowInsetLeft(),
                insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),
                insets.getSystemWindowInsetBottom());
          return insets;
        });
    screens = new FrameLayout(this);
    add(root, screens, -1, -1);
    screens.addOnLayoutChangeListener(
        (v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
          if (r - l != oldR - oldL || b - t != oldB - oldT) updateEditorLayout();
        });
    flowingBackground = new FlowingBackgroundView(this);
    screens.addView(flowingBackground, new FrameLayout.LayoutParams(-1, -1));
    editorScreen = vertical();
    editorScreen.setBackgroundColor(BG);
    screens.addView(editorScreen, new FrameLayout.LayoutParams(-1, -1));
    header = horizontal();
    header.setPadding(dp(12), dp(3), dp(12), dp(3));
    FrameLayout toolbar = new FrameLayout(this);
    add(header, toolbar, -1, 48);
    TextView back = button("返回", INK, BG);
    back.setTextSize(AppStyle.TYPE_BODY);
    back.setPadding(0, 0, 0, 0);
    back.setSingleLine(true);
    back.setContentDescription("返回首页");
    back.setOnClickListener(v -> showHome());
    toolbar.addView(
        back,
        new FrameLayout.LayoutParams(dp(48), dp(44), Gravity.START | Gravity.CENTER_VERTICAL));
    TextView title = text("照片编辑", AppStyle.TYPE_TITLE, INK);
    title.setGravity(Gravity.CENTER);
    title.setTypeface(null, Typeface.BOLD);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    FrameLayout.LayoutParams titleLayout = new FrameLayout.LayoutParams(-1, dp(40), Gravity.CENTER);
    // Equal insets reserve the wider action on both sides, so the title stays on the screen axis.
    titleLayout.leftMargin = titleLayout.rightMargin = dp(94);
    toolbar.addView(title, titleLayout);
    exportButton = button("显影准备中…", AppStyle.BG, ACCENT);
    exportButton.setTextSize(AppStyle.TYPE_LABEL);
    exportButton.setPadding(dp(8), 0, dp(8), 0);
    exportButton.setSingleLine(true);
    exportButton.setTypeface(null, Typeface.BOLD);
    exportButton.setOnClickListener(v -> exportOptions());
    exportButton.setEnabled(false);
    toolbar.addView(
        exportButton,
        new FrameLayout.LayoutParams(dp(88), dp(42), Gravity.END | Gravity.CENTER_VERTICAL));
    add(editorScreen, header, -1, -2);
    editorBody = new LinearLayout(this);
    editorScreen.addView(editorBody, new LinearLayout.LayoutParams(-1, 0, 1));
    previewColumn = vertical();
    toolsColumn = vertical();
    editorBody.addView(previewColumn);
    editorBody.addView(toolsColumn);
    photoStage = new FrameLayout(this);
    previewColumn.addView(photoStage, new LinearLayout.LayoutParams(-1, 0, 1));
    photo = new PhotoView(this);
    photoStage.addView(photo, new FrameLayout.LayoutParams(-1, -1));
    statusRow = horizontal();
    statusRow.setPadding(dp(18), 0, dp(18), 0);
    progress = new ProgressBar(this);
    add(statusRow, progress, 12, 12);
    add(statusRow, new View(this), 7, 1);
    status = text("准备中", AppStyle.TYPE_MICRO, MUTED);
    status.setSingleLine(true);
    status.setEllipsize(android.text.TextUtils.TruncateAt.END);
    status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    statusRow.addView(status, new LinearLayout.LayoutParams(0, dp(30), 1));
    TextView zoom = button("适应画面", MUTED, BG);
    zoom.setPadding(0, 0, 0, 0);
    zoom.setMinHeight(0);
    zoom.setTextSize(AppStyle.TYPE_LABEL);
    zoom.setOnClickListener(
        v -> {
          photo.resetZoom();
        });
    AppStyle.surface(statusRow, AppStyle.withAlpha(AppStyle.SURFACE, .95f), AppStyle.R_SMALL);
    FrameLayout.LayoutParams feedback = new FrameLayout.LayoutParams(-1, dp(28), Gravity.BOTTOM);
    feedback.setMargins(dp(12), 0, dp(12), dp(6));
    photoStage.addView(statusRow, feedback);
    statusRow.setVisibility(View.GONE);
    LinearLayout meta = horizontal();
    meta.setPadding(dp(12), 0, dp(12), 0);
    photoPickerButton = button("照片", INK, BG);
    photoPickerButton.setTextSize(AppStyle.TYPE_LABEL);
    photoPickerButton.setPadding(0, 0, 0, 0);
    photoPickerButton.setMinHeight(0);
    photoPickerButton.setOnClickListener(v -> choosePhoto());
    add(meta, photoPickerButton, 48, 40);
    refreshStrip();
    fileLabel = text(filename, AppStyle.TYPE_MICRO, MUTED);
    fileLabel.setSingleLine(true);
    fileLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
    // Keep source details available without crowding the compact photo action bar.
    fileLabel.setVisibility(View.GONE);
    meta.addView(fileLabel, new LinearLayout.LayoutParams(0, dp(40), 1));
    undoButton = button("撤销", MUTED, BG);
    undoButton.setTextSize(AppStyle.TYPE_LABEL);
    undoButton.setPadding(0, 0, 0, 0);
    undoButton.setMinHeight(0);
    undoButton.setContentDescription("撤销");
    undoButton.setOnClickListener(v -> historyStep(false));
    add(meta, undoButton, 44, 40);
    redoButton = button("重做", MUTED, BG);
    redoButton.setTextSize(AppStyle.TYPE_LABEL);
    redoButton.setPadding(0, 0, 0, 0);
    redoButton.setMinHeight(0);
    redoButton.setContentDescription("重做");
    redoButton.setOnClickListener(v -> historyStep(true));
    add(meta, redoButton, 44, 40);
    meta.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    add(meta, zoom, 64, 40);
    compareButton = button("对比原图", INK, BG);
    compareButton.setTextSize(AppStyle.TYPE_LABEL);
    compareButton.setMinHeight(0);
    compareButton.setPadding(dp(8), 0, dp(8), 0);
    compareButton.setOnClickListener(
        v -> {
          comparing = !comparing;
          updateComparison();
        });
    add(meta, compareButton, 80, 40);
    add(previewColumn, meta, -1, 40);
    tabRow = horizontal();
    tabRow.setPadding(dp(6), dp(4), dp(6), dp(4));
    AppStyle.surface(tabRow, AppStyle.SURFACE, AppStyle.R_LARGE, false);
    panel = vertical();
    AppStyle.surface(panel, AppStyle.SURFACE, AppStyle.R_LARGE);
    LinearLayout.LayoutParams panelLayout = new LinearLayout.LayoutParams(-1, dp(panelHeight()));
    panelLayout.setMargins(dp(12), dp(4), dp(12), 0);
    toolsColumn.addView(panel, panelLayout);
    buildPanel();
    LinearLayout.LayoutParams navigation = new LinearLayout.LayoutParams(-1, dp(58));
    navigation.setMargins(dp(12), dp(10), dp(12), dp(10));
    toolsColumn.addView(tabRow, navigation);
    updateEditorLayout();
    buildHome();
    showHome();
    updateHistoryButtons();
  }

  private int panelHeight() {
    return panelHeight(panelExpanded);
  }

  private boolean wideEditor() {
    android.content.res.Configuration configuration = getResources().getConfiguration();
    return configuration.screenWidthDp >= 600 && configuration.screenHeightDp < 500;
  }

  private boolean narrowControls() {
    int width = getResources().getConfiguration().screenWidthDp;
    if (wideEditor()) width = Math.round(width / 2.1f);
    return width < 360;
  }

  private void updateEditorLayout() {
    if (editorBody == null || panel == null) return;
    boolean wide = wideEditor();
    editorBody.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    previewColumn.setLayoutParams(
        wide
            ? new LinearLayout.LayoutParams(0, -1, 1.1f)
            : new LinearLayout.LayoutParams(-1, 0, 1));
    toolsColumn.setLayoutParams(
        wide ? new LinearLayout.LayoutParams(0, -1, 1) : new LinearLayout.LayoutParams(-1, -2));
    updatePanelHeight();
  }

  /** 编辑区高度（dp）。面板高度、图表高度都从这里推，别各自算一遍。 */
  private int editorHeightDp() {
    return editorScreen != null && editorScreen.getHeight() > 0
        ? Math.round(editorScreen.getHeight() / getResources().getDisplayMetrics().density)
        : getResources().getConfiguration().screenHeightDp;
  }

  /** 面板的绝对上限（dp）：屏高扣掉顶栏、状态条、工具行、导航，照片只剩 92dp 的极端值。 */
  private int panelLimit() {
    return Math.max(96, editorHeightDp() - 284);
  }

  /**
   * 收起态高度（dp）：320，但不超过屏幕能给的绝对上限。
   *
   * <p>**只跟屏幕有关，和当前页内容无关**。这条是本文件里唯一的高度来源：切 tab、切分段、
   * 换通道都不改它的值，照片区就不会跟着缩放——编辑照片时画面尺寸来回跳是最伤的事，
   * 比面板底部那几十 dp 空白重要得多。装不下的内容靠滚动，另给一根把手能拖到展开态。
   */
  private int panelCollapsedHeight() {
    return Math.min(320, panelLimit());
  }

  /** 展开态高度（dp）：屏高的 58%，最多 470。 */
  private int panelExpandedHeight() {
    int roomy = Math.min(470, Math.round(editorHeightDp() * .58f));
    return Math.min(panelLimit(), Math.max(panelCollapsedHeight(), roomy));
  }

  private int panelHeight(boolean expanded) {
    return expanded ? panelExpandedHeight() : panelCollapsedHeight();
  }

  /** 正文可视高度（dp）：面板高度扣掉把手那一行。 */
  private int panelViewportHeight(boolean expanded) {
    return panelHeight(expanded) - HANDLE_ROW_DP;
  }

  /**
   * 曲线页的图表高度（dp）：收起态的正文预算扣掉图表以外的行，剩下的全给图表。
   * 曲线页正文 = 分类 38 + 间距 8 + 启用开关 46 + 通道行 36 + 图表 + 底部内边距 10，
   * 图表 = 320 − 138 − 2 = 154（2dp 是逐项 dp→px 取整的余量），落在 294 的正文可视区里，
   * 一次看全，不用去拖那根 3dp 的把手。
   */
  private int curveChartHeight() {
    return Math.min(215, Math.max(150, panelCollapsedHeight() - 166));
  }

  /**
   * 曲线操作的用法提示：只在本进程第一次进曲线页时飘一条，2.6 秒后自己淡出。
   * 原来是面板里一行常驻的 28dp 小字——学会之后就是纯占地方，还挤掉了图表的高度。
   */
  private void showCurveHint() {
    if (curveHintShown || photoStage == null) return;
    curveHintShown = true;
    TextView hint = text("点按添加 · 拖动调整 · 长按删除内部点", AppStyle.TYPE_MICRO, INK);
    hint.setSingleLine(true);
    hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
    hint.setGravity(Gravity.CENTER);
    hint.setPadding(dp(14), 0, dp(14), 0);
    hint.setAlpha(0f);
    AppStyle.surface(hint, AppStyle.withAlpha(AppStyle.SURFACE, .95f), AppStyle.R_SMALL);
    // 停在照片下沿、状态条上方，不压住曲线图，也不占面板的任何一行。
    FrameLayout.LayoutParams layout =
        new FrameLayout.LayoutParams(-2, dp(30), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    layout.setMargins(dp(12), 0, dp(12), dp(44));
    photoStage.addView(hint, layout);
    hint.animate().alpha(1f).setDuration(200).start();
    hint.postDelayed(
        () ->
            hint.animate()
                .alpha(0f)
                .setDuration(320)
                .withEndAction(() -> photoStage.removeView(hint))
                .start(),
        2600);
  }

  /**
   * 量一次正文高度（dp，只算滚动区里的内容，不含把手那一行）。
   *
   * <p>正文高度**不参与**面板高度计算（面板高度只跟屏幕有关），它只决定两件事：
   * 把手要不要露出来、以及从上一页带过来的展开态要不要收回。
   * 单位一律用 dp：早先这里存的是 px，却拿去和 dp 常量比较，在 2.6 倍密度的机器上
   * 「内容矮就缩到内容高」永远判成「很高」，这些自适应分支其实从没生效过。
   */
  private void fitPanelToContent() {
    if (panel == null || panelScroll == null || editor == null) return;
    int width =
        panelScroll.getWidth() - panelScroll.getPaddingLeft() - panelScroll.getPaddingRight();
    if (width <= 0) {
      width = getResources().getDisplayMetrics().widthPixels - dp(12) * 2;
    }
    editor.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    int body =
        editor.getMeasuredHeight() + panelScroll.getPaddingTop() + panelScroll.getPaddingBottom();
    int heightDp = Math.max(1, Math.round(body / getResources().getDisplayMetrics().density));
    boolean changed = heightDp != panelBodyHeight;
    panelBodyHeight = heightDp;
    // 收起态就装得下的页，展开态只可能是上一页带过来的（装得下的页没有把手可点，
    // 用户没有别的路径进到这个状态），收回即可；收回之后把手随即禁用。不会再被展开。
    boolean collapsed = panelExpanded && !panelContentOverflows();
    if (collapsed) panelExpanded = false;
    if (changed || collapsed) updatePanelHeight();
  }

  /** 正文在收起态下装不下（留 12dp 容差：溢出几像素不值得给一根把手）。 */
  private boolean panelContentOverflows() {
    return panelBodyHeight > 0 && panelBodyHeight > panelViewportHeight(false) + 12;
  }

  /**
   * 把手值不值得用：屏能展开，且这一页确实还有事可做（能滚，或者正展开着能拖回去）。
   */
  private boolean panelHandleUsable() {
    return !wideEditor()
        && panelExpandedHeight() > panelCollapsedHeight() + 12
        && (panelExpanded || panelContentOverflows());
  }

  private void updatePanelHeight() {
    if (panel == null) return;
    LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) panel.getLayoutParams();
    layout.height = wideEditor() ? 0 : dp(panelHeight());
    layout.weight = wideEditor() ? 1 : 0;
    panel.requestLayout();
    if (panelHandle != null) {
      // 横屏平板把面板当侧栏，那 26dp 的把手行没有意义，收掉。
      panelHandle.setVisibility(wideEditor() ? View.GONE : View.VISIBLE);
      // 竖屏下面板几何永远不动：把手行常驻 26dp，不可用时只把胶囊调暗、禁掉点击，
      // 既不会把整页内容挪一下，也不会给出一根点了没反应的「亮着」的把手。
      boolean usable = panelHandleUsable();
      panelHandle.setAlpha(usable ? 1f : .45f);
      panelHandle.setEnabled(usable && !exporting);
      panelHandle.setContentDescription(panelExpanded ? "收起调节面板" : "展开调节面板");
    }
  }

  private void addPanelHandle() {
    panelHandle = new PanelHandle(this);
    panelHandle.setContentDescription(panelExpanded ? "收起调节面板" : "展开调节面板");
    panelHandle.setFocusable(true);
    panelHandle.setClickable(true);
    controls.add(panelHandle);
    View grip = new View(this);
    grip.setBackground(box(AppStyle.GRIP, AppStyle.R_SMALL));
    FrameLayout.LayoutParams gripLayout =
        new FrameLayout.LayoutParams(dp(30), dp(3), Gravity.CENTER);
    panelHandle.addView(grip, gripLayout);
    add(panel, panelHandle, -1, HANDLE_ROW_DP);
    panelHandle.setOnClickListener(
        v -> {
          if (!panelHandleUsable()) return;
          panelExpanded = !panelExpanded;
          updatePanelHeight();
        });
    panelHandle.setOnTouchListener(
        (v, event) -> {
          // 这一页没内容可滚、也不是展开态时，拖拽没有意义（只会拉出一块空白）。
          // 导出结束后 controls 会把所有控件整体 setEnabled(true)，所以这里再判一次。
          if (!v.isEnabled() || !panelHandleUsable()) return false;
          if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            panelDragY = event.getRawY();
            panelDragHeight = panel.getHeight();
            panelDragging = false;
            return true;
          }
          float delta = panelDragY - event.getRawY();
          if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            if (Math.abs(delta) > ViewConfiguration.get(this).getScaledTouchSlop())
              panelDragging = true;
            if (panelDragging) {
              panel.getLayoutParams().height =
                  Math.max(
                      dp(panelHeight(false)),
                      Math.min(dp(panelHeight(true)), panelDragHeight + Math.round(delta)));
              panel.requestLayout();
            }
            return true;
          }
          if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (!panelDragging) v.performClick();
            else {
              panelExpanded =
                  panel.getLayoutParams().height
                      > dp((panelHeight(false) + panelHeight(true)) / 2f);
              updatePanelHeight();
            }
            return true;
          }
          if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            updatePanelHeight();
            return true;
          }
          return true;
        });
  }

  private void buildHome() {
    if (homeImages != null) homeImages.close();
    if (homeScreen != null) screens.removeView(homeScreen);
    homeScreen = vertical();
    screens.addView(homeScreen, new FrameLayout.LayoutParams(-1, -1));
    ScrollView scroll = new ScrollView(this);
    homeScreen.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    homeImages = new HomePhotoImages(this, main, scroll, worker);
    if (ready) homeImages.setRenderer(renderer);
    LinearLayout content = vertical();
    content.setPadding(dp(20), dp(20), dp(20), dp(28));
    scroll.setVerticalScrollBarEnabled(false);
    scroll.addView(content);
    LinearLayout head = horizontal();
    LinearLayout branding = vertical();
    TextView logo = text(getString(R.string.app_name), AppStyle.TYPE_BRAND, INK);
    logo.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    logo.setLetterSpacing(-.035f);
    add(branding, logo, -1, 44);
    TextView cn = text("随身的胶片实验室", AppStyle.TYPE_LABEL, MUTED);
    add(branding, cn, -1, 28);
    head.addView(branding, new LinearLayout.LayoutParams(0, -2, 1));
    TextView help = button("关于", MUTED, BG);
    help.setTextSize(AppStyle.TYPE_LABEL);
    help.setPadding(0, 0, 0, 0);
    help.setSingleLine(true);
    help.setContentDescription("关于、许可与支持范围");
    help.setOnClickListener(v -> about());
    add(head, help, 42, 44);
    add(content, head, -1, -2);
    gap(content, 24);
    FrameLayout hero = new FrameLayout(this);
    AppStyle.surface(hero, AppStyle.SURFACE, AppStyle.R_MEDIUM);
    hero.setClipToOutline(true);
    homeHero = new ImageView(this);
    homeHero.setScaleType(ImageView.ScaleType.CENTER_CROP);
    homeHero.setAdjustViewBounds(false);
    homeHero.setContentDescription("最近导入的照片预览");
    if (source != null) homeHero.setImageBitmap(source);
    hero.addView(homeHero, new FrameLayout.LayoutParams(-1, -1));
    Frame restored = findFrame(activeId);
    File heroFile = sourceFile;
    if (heroFile == null) {
      File candidate =
          restored == null ? new File(getFilesDir(), "source.img") : new File(restored.path);
      if (candidate.isFile()) heroFile = candidate;
    }
    homeImages.bind(
        homeHero, heroFile, restored == null ? null : developedLook(restored.id), null, true);
    add(content, hero, -1, 240);
    gap(content, 16);
    TextView start = button("导入照片，开始显影", AppStyle.BG, ACCENT);
    start.setTextSize(AppStyle.TYPE_TITLE);
    start.setTypeface(null, Typeface.BOLD);
    start.setOnClickListener(v -> importPhoto());
    add(content, start, -1, 54);
    gap(content, 12);
    TextView privacy = text("RAW / JPEG / PNG / HEIC\n本机处理 · 自动保存调整 · 保留原照片", AppStyle.TYPE_MICRO, MUTED);
    privacy.setLineSpacing(dp(4), 1);
    add(content, privacy, -1, 54);
    gap(content, 24);
    LinearLayout historyHead = horizontal();
    TextView recent = text("最近的底片", AppStyle.TYPE_HEADING, INK);
    recent.setTypeface(null, Typeface.BOLD);
    historyHead.addView(recent, new LinearLayout.LayoutParams(0, dp(40), 1));
    recentCount = text(library.size() + " 张", AppStyle.TYPE_MICRO, MUTED);
    add(historyHead, recentCount, -2, 40);
    add(content, historyHead, -1, -2);
    gap(content, 9);
    historyList = vertical();
    add(content, historyList, -1, -2);
    refreshHistory();
  }

  private void showHome() {
    if (homeScreen != null) {
      flowingBackground.setVisibility(View.VISIBLE);
      homeScreen.setVisibility(View.VISIBLE);
      editorScreen.setVisibility(View.GONE);
      saveDevelopedLook();
      refreshHistory();
      homeImages.loadVisible();
    }
  }

  /**
   * 编辑器退回首页时，把当前显影效果落成小图（先入 io 队列，后面的卡片加载才能读到新文件），
   * 并让首页大图改用显影后的样子。示例照片不入库，没有卡片，跳过。
   */
  private void saveDevelopedLook() {
    if (developed == null || developed.isRecycled()) return;
    Frame frame = findFrame(activeId);
    if (frame == null) return;
    homeImages.storeDeveloped(frame.id, developed);
    syncHeroLook();
  }

  private void syncHeroLook() {
    if (homeHero != null && sourceFile != null)
      homeImages.bind(homeHero, sourceFile, developedLook(activeId), null, true);
  }

  private File developedLook(String id) {
    return id == null ? null : new File(new File(getFilesDir(), "developed"), id + ".jpg");
  }

  private void showEditor() {
    flowingBackground.setVisibility(View.GONE);
    homeScreen.setVisibility(View.GONE);
    editorScreen.setVisibility(View.VISIBLE);
    homeImages.loadVisible();
  }

  private void updateComparison() {
    photo.compare(comparing);
    compareButton.setText(comparing ? "结束对比" : "对比原图");
    compareButton.setSelected(comparing);
    AppStyle.action(
        compareButton,
        comparing ? ACCENT : INK,
        comparing ? AppStyle.CONTROL : Color.TRANSPARENT,
        AppStyle.R_MEDIUM);
  }

  private void buildPanel() {
    if (panel == null) return;
    // Keep the deferred crop tool's ID reserved without exposing its panel.
    if (selectedTab == 3) selectedTab = 0;
    controls.removeIf(v -> descendant(v, panel) || descendant(v, tabRow));
    panel.removeAllViews();
    tabRow.removeAllViews();
    sliders.clear();
    values.clear();
    addPanelHandle();
    String[] labels = {"胶片", "调整", "边框", "质感"};
    int[] tools = {0, 2, 4, 5};
    for (int i = 0; i < labels.length; i++) {
      final int n = tools[i];
      boolean selected = selectedTab == n || (n == 0 && selectedTab == 1);
      // 选中块与导航条同一档半径（26）。40 高装不下 26，平台会按半高裁成胶囊，
      // 于是两条弧的曲率读起来是一个家族，而不是「方角塞进圆角」。
      TextView tab =
          button(
              labels[i],
              selected ? ACCENT : MUTED,
              selected ? AppStyle.SELECTED : BG,
              AppStyle.R_LARGE);
      tab.setTextSize(AppStyle.TYPE_TITLE);
      tab.setPadding(0, 0, 0, 0);
      tab.setMinHeight(0);
      tab.setSelected(selected);
      // 选中块原来铺满整个格子，四角会顶到导航条自己的圆角上，看起来像套了个方框。
      // 内缩 4/5dp 之后就是一颗独立的 chip，和面板里的分段控件是同一个语言。
      if (selected) {
        tab.setBackground(
            new android.graphics.drawable.InsetDrawable(
                tab.getBackground(), dp(4), dp(5), dp(4), dp(5)));
      }
      tab.setOnClickListener(
          v -> {
            selectedTab = n;
            buildPanel();
          });
      tabRow.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
    }
    ScrollView scroll = new ScrollView(this);
    panelScroll = scroll;
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    editor = vertical();
    editor.setPadding(dp(14), 0, dp(14), dp(10));
    // 任何一页内容变化都重新量一次，面板高度才能跟着当前页的功能走。
    editor.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            panel.post(this::fitPanelToContent));
    scroll.addView(editor);
    updatePanelHeight();
    if (selectedTab <= 1) {
      LinearLayout modes = horizontal();
      for (int i = 0; i < 2; i++) {
        final int mode = i;
        TextView tab =
            button(
                i == 0 ? "胶片型号" : "相纸与印相",
                selectedTab == i ? ACCENT : MUTED,
                selectedTab == i ? AppStyle.SELECTED : BG);
        tab.setTextSize(AppStyle.TYPE_LABEL);
        tab.setPadding(0, 0, 0, 0);
        tab.setMinHeight(0);
        tab.setSelected(selectedTab == i);
        tab.setOnClickListener(
            v -> {
              selectedTab = mode;
              buildPanel();
            });
        modes.addView(tab, new LinearLayout.LayoutParams(0, dp(42), 1));
      }
      add(editor, modes, -1, 42);
      gap(editor, 8);
    }
    if (selectedTab == 0) {
      LinearLayout filters = horizontal();
      String[] categories = {"全部", "彩色负片", "电影胶片", "反转片"};
      for (String category : categories) {
        TextView chip =
            button(
                category,
                category.equals(filmCategory) ? INK : MUTED,
                category.equals(filmCategory) ? AppStyle.CONTROL : BG);
        chip.setTextSize(AppStyle.TYPE_MICRO);
        chip.setSelected(category.equals(filmCategory));
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setSingleLine(true);
        chip.setHorizontallyScrolling(false);
        chip.setAutoSizeTextTypeUniformWithConfiguration(
            10, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        chip.setMinHeight(0);
        chip.setOnClickListener(
            v -> {
              filmCategory = category;
              buildPanel();
            });
        LinearLayout.LayoutParams chipLayout = new LinearLayout.LayoutParams(0, dp(40), 1);
        chipLayout.rightMargin = dp(3);
        filters.addView(chip, chipLayout);
      }
      add(editor, filters, -1, 40);
      gap(editor, 8);
      // 一屏正好放四张：卡片吃掉全部可用宽度，卡片间隔收到 4dp、内部只留 2dp，
      // 图标就能长到卡宽的 80%（100×80 画布），比原来 45dp 的小图大三分之一。
      int screenDp = getResources().getConfiguration().screenWidthDp;
      if (wideEditor()) screenDp = Math.round(screenDp / 2.1f);
      final int cardGap = 6;
      final int stride = Math.max(64, (screenDp - 52) / 4);
      final int cardWidth = stride - cardGap;
      final int iconHeight = Math.round(cardWidth * .80f);
      // 两行名称需要 38dp：11sp × 2 行再加字体自带的上下留白，33dp 会把第二行顶出去。
      final int labelHeight = 38;
      final int cardHeight = iconHeight + labelHeight + 3 + 3 + 6;

      HorizontalScrollView filmScroll = new HorizontalScrollView(this);
      filmScroll.setHorizontalScrollBarEnabled(false);
      filmScroll.setClipToPadding(false);
      filmCards = horizontal();
      filmScroll.addView(filmCards);
      int selectedPosition = 0;
      int position = 0;
      for (Stock stock : films) {
        String category = category(stock);
        if (!filmCategory.equals("全部") && !filmCategory.equals(category)) continue;
        boolean selected = stock.id.equals(settings.optString("film_stock"));
        if (selected) selectedPosition = position;
        LinearLayout card = vertical();
        card.setPadding(dp(1), dp(3), dp(1), dp(3));
        card.setSelected(selected);
        card.setFocusable(true);
        FilmBadge badge = new FilmBadge(this, stock.id, stock.name, false);
        // 选中态画在图标外圈。整卡描边会把两行名称一起圈进来，卡片又窄，描出来是个细长的
        // 怪形状，而且名称换行就顶到框上；只圈图标既像「挑了这一卷」，也不会被文字撑变形。
        FrameLayout iconBox = new FrameLayout(this);
        // 图标与环之间留 2dp 空隙，但描边是向内画的，所以内边距要写成「空隙 + 描边」：
        // 环占外侧 stroke，剩下的才是露出来的空隙。只按空隙设内边距，环会贴到图标上。
        final float gapDp = 2, strokeDp = 1.6f;
        int pad = dp(gapDp + strokeDp);
        iconBox.setPadding(pad, pad, pad, pad);
        iconBox.addView(badge, new FrameLayout.LayoutParams(-1, -1));
        // 外径同理要补两项：环的内弧 = 外径 − 描边，要让内弧正好落在「图标边缘 + 空隙」上，
        // 外径就是 图标半径 + 空隙 + 描边。少补描边（上一版就是），内弧会比图标更圆，
        // 四角挤出一道楔形空隙，看着就是两个圆角不一样。
        float iconScale =
            Math.min(
                (cardWidth - 2 - 2 * pad / (float) dp(1)) / (float) FilmCanisterArtwork.WIDTH,
                (iconHeight - 2 * pad / (float) dp(1)) / (float) FilmCanisterArtwork.HEIGHT);
        GradientDrawable ring =
            box(
                Color.TRANSPARENT,
                FilmCanisterArtwork.RADIUS * iconScale + gapDp + strokeDp);
        if (selected) ring.setStroke(Math.max(1, dp(strokeDp)), ACCENT);
        iconBox.setBackground(ring);
        add(card, iconBox, -1, iconHeight);
        TextView name = text(shortName(stock.name), AppStyle.TYPE_MICRO, selected ? ACCENT : INK);
        name.setMaxLines(2);
        name.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setLineSpacing(dp(1), 1f);
        // 图标与文字之间留一口气，贴着图标会显得拥挤。
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(-1, dp(labelHeight));
        nameLayout.topMargin = dp(6);
        card.addView(name, nameLayout);
        card.setTooltipText(stock.name);
        card.setContentDescription("胶片 " + stock.name + (selected ? "，已选" : ""));
        card.setOnClickListener(
            v -> {
              set("film_stock", stock.id);
              scheduleRender();
              buildPanel();
            });
        controls.add(card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(cardWidth), dp(cardHeight));
        lp.rightMargin = dp(cardGap);
        filmCards.addView(card, lp);
        position++;
      }
      add(editor, filmScroll, -1, cardHeight + 2);
      final int offset = selectedPosition * dp(stride);
      filmScroll
          .getViewTreeObserver()
          .addOnGlobalLayoutListener(
              new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                public void onGlobalLayout() {
                  if (filmScroll.getWidth() > 0) {
                    filmScroll.scrollTo(Math.max(0, offset - dp(stride)), 0);
                    filmScroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                  }
                }
              });
      gap(editor, 6);
      slider("exposure_compensation_ev", "胶片曝光", -3, 3, .1, " EV");

    } else if (selectedTab == 1) {
      if (positiveFilm()) {
        panelNote("反转片直接扫描", "当前胶片经过显影后直接扫描，相纸和印相滤色不参与处理。可到「调整」调整扫描结果。");
        return;
      }
      LinearLayout heading = horizontal();
      TextView title = text("印相材料", AppStyle.TYPE_BODY, INK);
      heading.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1));
      TextView pick = button("全部 8 款", ACCENT, AppStyle.CONTROL);
      pick.setPadding(dp(16), 0, dp(16), 0);
      pick.setMinHeight(0);
      pick.setTextSize(AppStyle.TYPE_LABEL);
      // 宽度跟着文字走：一旦被挤就整行折成两行，药丸里塞两行很难看，所以锁死单行。
      pick.setSingleLine(true);
      pick.setEllipsize(android.text.TextUtils.TruncateAt.END);
      pick.setOnClickListener(v -> chooseStock(false));
      add(heading, pick, -2, 40);
      add(editor, heading, -1, 40);
      gap(editor, 6);
      Stock stock = find(papers, settings.optString("print_stock"));
      LinearLayout material = horizontal();
      material.setPadding(dp(14), dp(10), dp(14), dp(10));
      material.setBackground(box(AppStyle.CONTROL, AppStyle.R_MEDIUM));
      LinearLayout names = vertical();
      paperTitle = text(stock == null ? "" : shortName(stock.name), AppStyle.TYPE_TITLE, INK);
      paperTitle.setSingleLine(true);
      paperTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
      add(names, paperTitle, -1, 30);
      paperSub = text(stock == null ? "" : maker(stock) + " · 光谱印相材料", AppStyle.TYPE_MICRO, MUTED);
      paperSub.setSingleLine(true);
      paperSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
      add(names, paperSub, -1, 24);
      material.addView(names, new LinearLayout.LayoutParams(0, -2, 1));
      material.setOnClickListener(v -> chooseStock(false));
      controls.add(material);
      add(editor, material, -1, 78);
      TextView linkedPaper = text("相纸与已选择的胶片联动，默认无需选择", AppStyle.TYPE_MICRO, MUTED);
      linkedPaper.setMaxLines(2);
      add(editor, linkedPaper, -1, 40);
      gap(editor, 14);
      slider("print_brightness", "印相亮度", -2, 2, .05, " EV");
      slider("m_filter_shift", "洋红滤色", -.25, .25, .01, "");
      slider("y_filter_shift", "黄色滤色", -.25, .25, .01, "");
    } else if (selectedTab == 2) {
      buildGradePanel();
    } else if (selectedTab == 4) {
      buildFramePanel();
    } else {
      TextView intro = text("质感从胶片计算而来", AppStyle.TYPE_BODY, INK);
      add(editor, intro, -1, 28);
      TextView note = text("以物理画幅缩放颗粒与光晕，保留各胶片的特性。", AppStyle.TYPE_MICRO, MUTED);
      add(editor, note, -1, 28);
      gap(editor, 8);
      slider("grain_amount", "银盐颗粒", 0, 2, .05, " ×");
      slider("halation_amount", "高光光晕", 0, 4, .1, " ×");
      slider("halation_boost_ev", "光晕曝光增益", 0, 8, .1, " EV");
      slider("halation_scatter_amount", "乳剂散射", 0, 1, .05, "");
      toggle("antihalation_removed", "移除防光晕层", false);
      slider("dir_couplers_amount", "显影耦合", 0, 4, .1, " ×");
      slider("density_curve_gamma", "胶片密度曲线", .2, 4, .05, " ×");
      slider("lens_blur_um", "镜头柔化", 0, 40, .5, " μm");
      slider("scanner_lens_blur", "扫描柔化", 0, 5, .1, " px");
      if (!positiveFilm()) {
        slider("glare_amount", "相纸炫光", 0, 30, .5, " ×");
        slider("preflash_exposure", "相纸预闪", 0, .1, .002, "");
      }
      // 测光：上游把「关闭」也当成一个档位（Custom），所以这里是一排意图而不是开关 + 下拉，
      // 免得开关关着、下面那行还亮着却不起作用。
      TextView meterLabel = text("测光", AppStyle.TYPE_LABEL, MUTED);
      add(editor, meterLabel, -1, 22);
      LinearLayout meters = horizontal();
      String[] meterNames = {"关闭", "均衡", "中央主体", "保护高光", "保护阴影"};
      String[] meterIds = {"custom", "balanced", "center", "protect_highlights", "protect_shadows"};
      String activeMeter = meterState();
      for (int i = 0; i < meterNames.length; i++) {
        boolean on = meterIds[i].equals(activeMeter);
        TextView chip = button(meterNames[i], on ? INK : MUTED, on ? AppStyle.CONTROL : BG);
        chip.setTextSize(AppStyle.TYPE_LABEL);
        chip.setPadding(dp(4), 0, dp(4), 0);
        chip.setMinHeight(0);
        chip.setSelected(on);
        chip.setAutoSizeTextTypeUniformWithConfiguration(
            9, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        final String meter = meterIds[i];
        chip.setOnClickListener(
            v -> {
              set("auto_exposure", !meter.equals("custom"));
              if (!meter.equals("custom")) set("auto_exposure_method", meter);
              scheduleRender();
              buildPanel();
            });
        LinearLayout.LayoutParams chipLayout = new LinearLayout.LayoutParams(0, dp(38), 1);
        if (i > 0) chipLayout.leftMargin = dp(4);
        meters.addView(chip, chipLayout);
      }
      add(editor, meters, -1, 38);
      gap(editor, 9);
      LinearLayout format = horizontal();
      TextView label = text("胶片画幅", AppStyle.TYPE_LABEL, INK);
      format.addView(label, new LinearLayout.LayoutParams(0, dp(40), 1));
      TextView pick = button(formatName(), ACCENT, AppStyle.CONTROL);
      pick.setTextSize(AppStyle.TYPE_LABEL);
      pick.setPadding(dp(16), 0, dp(16), 0);
      // 宽度跟着文字走，小屏 + 大字号时容易被父容器挤到省略号；锁一个下限宽度，
      // 再给它一点与标签的距离，就不至于贴着「胶片画幅」还被截断。
      pick.setMinWidth(dp(96));
      pick.setSingleLine(true);
      pick.setEllipsize(android.text.TextUtils.TruncateAt.END);
      pick.setMinHeight(0);
      pick.setOnClickListener(
          v ->
              new Haptics.DialogBuilder(this)
                  .setTitle("胶片画幅")
                  .setItems(
                      new String[] {"135 · 36 mm", "中画幅 · 60 mm", "小画幅 · 18 mm"},
                      (d, n) -> {
                        set("film_format_mm", new int[] {36, 60, 18}[n]);
                        scheduleRender();
                        buildPanel();
                      })
                  .show());
      LinearLayout.LayoutParams pickLayout = new LinearLayout.LayoutParams(-2, dp(40));
      pickLayout.leftMargin = dp(10);
      format.addView(pick, pickLayout);
      add(editor, format, -1, 40);
      gap(editor, 9);
      rule(editor);
      gap(editor, 8);
      TextView reset = button("恢复默认显影参数", MUTED, AppStyle.CONTROL);
      reset.setOnClickListener(
          v ->
              new Haptics.DialogBuilder(this)
                  .setTitle("恢复默认显影？")
                  .setMessage("保留当前照片，将胶片、相纸与调整恢复为默认值。可用撤销返回。")
                  .setPositiveButton("恢复", (d, n) -> reset())
                  .setNegativeButton("取消", null)
                  .show());
      add(editor, reset, -1, 39);
    }
    // 内容一建好就同步量一次。面板高度只跟屏幕有关，不差这一帧；但把手显隐和展开态要在
    // 这一帧就定下来，否则从展开态切到装得下的页会先闪一帧空白再收回。
    fitPanelToContent();
  }

  private void panelNote(String title, String detail) {
    TextView t = text(title, AppStyle.TYPE_BODY, INK);
    add(editor, t, -1, 30);
    TextView d = text(detail, AppStyle.TYPE_MICRO, MUTED);
    d.setLineSpacing(dp(3), 1);
    add(editor, d, -1, -2);
    gap(editor, 12);
  }

  private void toggle(String key, String label, boolean fallback) {
    Switch sw = new Switch(this);
    sw.setText(label);
    sw.setTextSize(AppStyle.TYPE_BODY);
    sw.setTextColor(INK);
    sw.setChecked(settings.optBoolean(key, fallback));
    sw.setPadding(0, dp(6), 0, dp(6));
    add(editor, sw, -1, 46);
    controls.add(sw);
    sw.setOnCheckedChangeListener(
        (v, on) -> {
          set(key, on);
          scheduleRender();
        });
  }

  private void choice(String title, String[] names, String[] ids, String key) {
    String current = settings.optString(key, ids[0]);
    int index = Arrays.asList(ids).indexOf(current);
    TextView pick = button(title + "   " + names[Math.max(0, index)] + "", ACCENT, AppStyle.CONTROL);
    pick.setTextSize(AppStyle.TYPE_LABEL);
    pick.setMinHeight(0);
    add(editor, pick, -1, 39);
    gap(editor, 9);
    pick.setOnClickListener(
        v ->
            new Haptics.DialogBuilder(this)
                .setTitle(title)
                .setItems(
                    names,
                    (d, n) -> {
                      set(key, ids[n]);
                      scheduleRender();
                      buildPanel();
                    })
                .setNegativeButton("取消", null)
                .show());
  }

  private void buildGradePanel() {
    LinearLayout groups = horizontal();
    String[] sections = {"基础", "曲线", "色彩平衡", "HSL 分色", "暗角"};
    for (String name : sections) {
      TextView tab =
          button(
              name.equals("HSL 分色") ? "HSL" : name,
              gradeSection.equals(name) ? ACCENT : MUTED,
              gradeSection.equals(name) ? AppStyle.SELECTED : BG);
      tab.setTextSize(AppStyle.TYPE_MICRO);
      tab.setPadding(0, 0, 0, 0);
      tab.setMinHeight(0);
      tab.setSelected(gradeSection.equals(name));
      tab.setOnClickListener(
          v -> {
            gradeSection = name;
            buildPanel();
          });
      groups.addView(tab, new LinearLayout.LayoutParams(0, dp(38), 1));
    }
    add(editor, groups, -1, 38);
    gap(editor, 8);
    toggle("grade_enabled", "启用调整", true);
    if (gradeSection.equals("基础")) {
      slider("grade_temperature", "冷暖", -100, 100, 1, "");
      slider("grade_tint", "绿 / 洋红", -100, 100, 1, "");
      slider("grade_exposure", "扫描曝光", -5, 5, .05, " EV");
      slider("grade_contrast", "对比度", -50, 50, 1, "");
      slider("grade_brightness", "亮度", -50, 50, 1, "");
      slider("grade_shadows", "阴影", -100, 100, 1, "");
      slider("grade_highlights", "高光", -100, 100, 1, "");
      slider("grade_black", "黑场截点", 0, 50, 1, " %");
      slider("grade_white", "白场截点", 0, 50, 1, " %");
      slider("grade_saturation", "饱和度", -100, 100, 1, "");
    } else if (gradeSection.equals("曲线")) {
      String[] ids = {"rgb", "luma", "r", "g", "b"},
          names = {"RGB 总曲线", "亮度曲线", "红通道", "绿通道", "蓝通道"};
      // 通道按钮和「重置」并成一行。重置原来单占 36dp 一整行，为一个低频按钮付一整行代价，
      // 曲线底下因此空出一截；挪到行尾做小字按钮，通道名也能占到更宽的宽度。
      LinearLayout channelRow = horizontal();
      TextView channel =
          button(
              names[Math.max(0, Arrays.asList(ids).indexOf(curveChannel))] + "",
              INK,
              AppStyle.CONTROL);
      channelRow.addView(channel, new LinearLayout.LayoutParams(0, dp(36), 1));
      channel.setOnClickListener(
          v ->
              new Haptics.DialogBuilder(this)
                  .setTitle("曲线通道")
                  .setItems(
                      names,
                      (d, n) -> {
                        curveChannel = ids[n];
                        buildPanel();
                      })
                  .show());
      String key = "curve_" + curveChannel;
      TextView clear = button("重置", MUTED, BG);
      clear.setTextSize(AppStyle.TYPE_LABEL);
      clear.setPadding(dp(6), 0, dp(4), 0);
      clear.setMinHeight(0);
      clear.setContentDescription("重置当前曲线");
      clear.setOnClickListener(
          v -> {
            set(
                key,
                new JSONArray()
                    .put(new JSONArray().put(0).put(0))
                    .put(new JSONArray().put(1).put(1)));
            scheduleRender();
            buildPanel();
          });
      channelRow.addView(clear, new LinearLayout.LayoutParams(dp(48), dp(36)));
      add(editor, channelRow, -1, 36);
      int color =
          curveChannel.equals("r")
              ? AppStyle.CURVE_R
              : curveChannel.equals("g")
                  ? AppStyle.CURVE_G
                  : curveChannel.equals("b") ? AppStyle.CURVE_B : ACCENT;
      final CurveEditor[] holder = new CurveEditor[1];
      holder[0] =
          new CurveEditor(
              this,
              settings.optJSONArray(key),
              color,
              () -> {
                set(key, holder[0].value());
                scheduleRender();
              });
      // 图表高度跟着面板预算走：矮屏机器上图表自己矮一点，好过被面板底边切掉半截——
      // 图里那截曲线是拖点用的，切掉了等于这页没法用。
      add(editor, holder[0], -1, curveChartHeight());
      controls.add(holder[0]);
      showCurveHint();
    } else if (gradeSection.equals("色彩平衡")) {
      String[] ids = {"master", "shadows", "midtones", "highlights"},
          names = {"整体", "阴影", "中间调", "高光"};
      TextView zone =
          button(
              "区域   " + names[Math.max(0, Arrays.asList(ids).indexOf(balanceZone))] + "",
              INK,
              AppStyle.CONTROL);
      add(editor, zone, -1, 38);
      zone.setOnClickListener(
          v ->
              new Haptics.DialogBuilder(this)
                  .setTitle("色彩平衡区域")
                  .setItems(
                      names,
                      (d, n) -> {
                        balanceZone = ids[n];
                        buildPanel();
                      })
                  .show());
      gap(editor, 12);
      String key = "balance_" + balanceZone;
      slider(key + "_hue", "色相", 0, 360, 1, " °");
      slider(key + "_saturation", "着色强度", 0, 100, 1, "");
      slider(key + "_luminance", "区域亮度", -100, 100, 1, "");
    } else if (gradeSection.equals("HSL 分色")) {
      String[] ids = {"red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta"},
          names = {"红", "橙", "黄", "绿", "青", "蓝", "紫", "洋红"};
      TextView band =
          button(
              "色彩   " + names[Math.max(0, Arrays.asList(ids).indexOf(hslBand))] + "",
              INK,
              AppStyle.CONTROL);
      add(editor, band, -1, 38);
      band.setOnClickListener(
          v ->
              new Haptics.DialogBuilder(this)
                  .setTitle("HSL 分色")
                  .setItems(
                      names,
                      (d, n) -> {
                        hslBand = ids[n];
                        buildPanel();
                      })
                  .show());
      gap(editor, 12);
      String key = "hsl_" + hslBand;
      slider(key + "_hue", "色相偏移", -100, 100, 1, "");
      slider(key + "_saturation", "分色饱和度", -100, 100, 1, "");
      slider(key + "_luminance", "分色明度", -100, 100, 1, "");
    } else {
      slider("grade_vignette", "暗角 / 亮角", -100, 100, 1, "");
      slider("grade_vignette_midpoint", "暗角范围", 0, 100, 1, "");
    }
  }

  private String dimensionsLabel() {
    if (fullWidth < 1 || fullHeight < 1) return "读取尺寸后显示";
    try {
      JSONObject d =
          new JSONObject(NativeEngine.dimensions(fullWidth, fullHeight, settings.toString()));
      return d.optInt("width") + " × " + d.optInt("height");
    } catch (JSONException e) {
      return "尺寸读取失败";
    }
  }

  private void buildGeometryPanel() {
    panelNote(
        "裁切与几何",
        "原图 "
            + fullWidth
            + " × "
            + fullHeight
            + " · 导出 "
            + dimensionsLabel()
            + "\n裁切只保存范围，旋转、翻转和拉直会用于预览与导出。");
    TextView crop = button("打开裁切框", ACCENT, 0xff24211c);
    add(editor, crop, -1, 43);
    gap(editor, 10);
    crop.setOnClickListener(v -> cropDialog());
    LinearLayout row = horizontal();
    for (String label : new String[] {"左转 90°", "右转 90°"}) {
      TextView b = button(label, INK, AppStyle.CONTROL);
      b.setTextSize(AppStyle.TYPE_LABEL);
      row.addView(b, new LinearLayout.LayoutParams(0, dp(38), 1));
      b.setOnClickListener(
          v -> {
            int turns = settings.optInt("geo_turns");
            set("geo_turns", (turns + (label.startsWith("左") ? 3 : 1)) % 4);
            scheduleRender();
            buildPanel();
          });
    }
    add(editor, row, -1, 38);
    gap(editor, 12);
    toggle("geo_flip_h", "水平翻转", false);
    toggle("geo_flip_v", "垂直翻转", false);
    slider("geo_angle", "拉直", -45, 45, .1, " °");
    TextView reset = button("恢复完整构图", MUTED, AppStyle.CONTROL);
    add(editor, reset, -1, 39);
    reset.setOnClickListener(
        v -> {
          for (String k : new String[] {"geo_x", "geo_y", "geo_angle", "geo_turns"}) set(k, 0);
          set("geo_w", 1);
          set("geo_h", 1);
          set("geo_flip_h", false);
          set("geo_flip_v", false);
          scheduleRender();
          buildPanel();
        });
  }

  private void cropDialog() {
    if (source == null || exporting) return;
    LinearLayout body = vertical();
    body.setPadding(dp(10), dp(8), dp(10), dp(8));
    body.setBackgroundColor(BG);
    RectF initial =
        new RectF(
            (float) settings.optDouble("geo_x", 0),
            (float) settings.optDouble("geo_y", 0),
            (float) (settings.optDouble("geo_x", 0) + settings.optDouble("geo_w", 1)),
            (float) (settings.optDouble("geo_y", 0) + settings.optDouble("geo_h", 1)));
    CropEditor crop = new CropEditor(this, source, initial);
    TextView ratio = button("比例：自由裁切", ACCENT, AppStyle.CONTROL);
    add(body, ratio, -1, 39);
    ratio.setOnClickListener(
        v ->
            new Haptics.DialogBuilder(this)
                .setTitle("裁切比例")
                .setItems(
                    new String[] {
                      "自由裁切", "原图比例", "1 : 1", "3 : 2", "2 : 3", "4 : 3", "3 : 4", "16 : 9",
                      "9 : 16"
                    },
                    (d, n) -> {
                      crop.ratio(
                          new float[] {
                                0,
                                source.getWidth() / (float) source.getHeight(),
                                1,
                                1.5f,
                                2 / 3f,
                                4 / 3f,
                                .75f,
                                16 / 9f,
                                9 / 16f
                              }
                              [n]);
                      ratio.setText(
                          "比例："
                              + new String[] {
                                    "自由裁切", "原图", "1 : 1", "3 : 2", "2 : 3", "4 : 3", "3 : 4",
                                    "16 : 9", "9 : 16"
                                  }
                                  [n]
                              + "");
                    })
                .show());
    int height =
        Math.min(
            390,
            (int)
                (getResources().getDisplayMetrics().heightPixels
                    / getResources().getDisplayMetrics().density
                    * .48));
    add(body, crop, -1, height);
    TextView hint = text("拖动四角调整 · 拖动内部移动 · 应用后可撤销", AppStyle.TYPE_MICRO, MUTED);
    hint.setSingleLine(true);
    hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
    hint.setPadding(dp(6), 0, 0, 0);
    add(body, hint, -1, 32);
    new Haptics.DialogBuilder(this)
        .setTitle("非破坏裁切")
        .setView(body)
        .setPositiveButton(
            "应用裁切",
            (d, n) -> {
              RectF r = crop.value();
              set("geo_x", (double) r.left);
              set("geo_y", (double) r.top);
              set("geo_w", (double) r.width());
              set("geo_h", (double) r.height());
              scheduleRender();
              buildPanel();
              photo.resetZoom();
            })
        .setNeutralButton(
            "完整画面",
            (d, n) -> {
              set("geo_x", 0);
              set("geo_y", 0);
              set("geo_w", 1);
              set("geo_h", 1);
              scheduleRender();
              buildPanel();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void editTextValue(String key, String title, int limit, String allowed) {
    EditText input = new EditText(this);
    AppStyle.field(input);
    input.setSingleLine(true);
    input.setText(settings.optString(key));
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(limit)});
    LinearLayout body = vertical();
    body.setPadding(dp(20), dp(6), dp(20), dp(6));
    add(body, input, -1, 48);
    TextView note = text(allowed, AppStyle.TYPE_MICRO, MUTED);
    add(body, note, -1, -2);
    AlertDialog dialog =
        new Haptics.DialogBuilder(this)
            .setTitle(title)
            .setView(body)
            .setPositiveButton("应用", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.setOnShowListener(
        v ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    w -> {
                      String value = input.getText().toString().toUpperCase(Locale.US);
                      if (!value.matches("[A-Z0-9 ./'-]*")) {
                        input.setError("请使用英文字母、数字、空格或 - . / 符号");
                        return;
                      }
                      set(key, value);
                      scheduleRender();
                      buildPanel();
                      dialog.dismiss();
                    }));
    dialog.show();
  }

  private void buildFramePanel() {

    choice(
        "边框样式",
        new String[] {"无片边", "135 黑色齿孔", "135 暖色齿孔", "白色留边", "即时相纸"},
        new String[] {"none", "135_black", "135_warm", "clean_white", "instant"},
        "edge_style");
    if (!settings.optString("edge_style", "none").equals("none")) {
      slider("edge_amount", "片边宽度", 2, 15, .5, " %");
      if (settings.optString("edge_style").startsWith("135"))
        slider("edge_frame_number", "底片编号", 1, 99, 1, "");
      if (settings.optString("edge_style").equals("instant")) {
        TextView caption =
            button("相纸题注  " + settings.optString("edge_caption", "添加英文或数字"), INK, AppStyle.CONTROL);
        add(editor, caption, -1, 39);
        caption.setOnClickListener(
            v -> editTextValue("edge_caption", "相纸题注", 42, "支持英文、数字、空格及 - . /，最多 42 字符"));
      }
    }
    gap(editor, 8);
    toggle("date_enabled", "显示日期", false);
    TextView date = button("日期文字  " + settings.optString("date_text"), INK, AppStyle.CONTROL);
    add(editor, date, -1, 39);
    gap(editor, 8);
    date.setOnClickListener(
        v -> editTextValue("date_text", "日期文字", 24, "支持英文、数字、空格及 - . /，最多 24 字符"));
    if (sourceMetadata.optLong("timestamp") > 0) {
      TextView shot = button("使用原片拍摄日期", ACCENT, AppStyle.CONTROL);
      add(editor, shot, -1, 36);
      gap(editor, 8);
      shot.setOnClickListener(
          v -> {
            set(
                "date_text",
                new SimpleDateFormat("yyyy.MM.dd", Locale.US)
                    .format(new Date(sourceMetadata.optLong("timestamp"))));
            scheduleRender();
            buildPanel();
          });
    }
    choice(
        "日期位置",
        new String[] {"右下", "左下", "右上", "左上"},
        new String[] {"bottom_right", "bottom_left", "top_right", "top_left"},
        "date_corner");
    choice("日期颜色", new String[] {"琥珀橙", "暖白"}, new String[] {"orange", "white"}, "date_color");
    slider("date_size", "日期字号", .5, 3, .1, " ×");
    slider("date_intensity", "日期亮度", .1, 2, .05, " ×");
  }

  private boolean descendant(View child, View parent) {
    android.view.ViewParent p = child.getParent();
    while (p != null) {
      if (p == parent) return true;
      p = p.getParent();
    }
    return false;
  }

  private String currentFilmName() {
    Stock s = find(films, settings.optString("film_stock"));
    return s == null ? "" : shortName(s.name);
  }

  @Override
  public void onBackPressed() {
    // Android 13 及以上的返回手势走 OnBackInvokedCallback（见 installBackHandler）；这里只覆盖 12L 及以下。
    handleBack();
  }

  private void handleBack() {
    if (activeImport != null) {
      cancelImport("已取消导入");
      return;
    }
    if (homeScreen.getVisibility() != View.VISIBLE) showHome();
    else finish();
  }

  private void installBackHandler() {
    if (Build.VERSION.SDK_INT < 33) return;
    backCallback = this::handleBack;
    getOnBackInvokedDispatcher()
        .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration c) {
    super.onConfigurationChanged(c);
    if (panel != null) {
      editorScreen.post(
          () -> {
            updateEditorLayout();
            buildPanel();
          });
    }
  }

  /** 面板上那个「测光」药丸当前该亮哪一档；auto_exposure 关闭就是「关闭」。 */
  private String meterState() {
    if (!settings.optBoolean("auto_exposure", false)) return "custom";
    return settings.optString("auto_exposure_method", "balanced");
  }

  private String formatName() {
    // 值框里只放物理宽度，格式全称留给对话框——「135 · 36 mm」在值框里会被截成
    // 「135 · 36…」，反而看不出是什么。
    int mm = settings.optInt("film_format_mm", 36);
    return mm == 60 ? "60 mm" : mm == 18 ? "18 mm" : "36 mm";
  }

  private void slider(
      String key, String label, double min, double max, double step, String suffix) {
    LinearLayout line = horizontal();
    TextView name = text(label, AppStyle.TYPE_BODY, INK);
    name.setGravity(Gravity.CENTER_VERTICAL);
    name.setMaxLines(2);
    name.setEllipsize(android.text.TextUtils.TruncateAt.END);
    boolean narrow = narrowControls();
    add(line, name, narrow ? 66 : 76, 48);
    TextView value = text("", AppStyle.TYPE_LABEL, AppStyle.VALUE);
    value.setTypeface(Typeface.MONOSPACE);
    value.setGravity(Gravity.CENTER);
    value.setSingleLine(true);
    value.setHorizontallyScrolling(false);
    value.setIncludeFontPadding(false);
    value.setPadding(dp(4), 0, dp(4), 0);
    value.setAutoSizeTextTypeUniformWithConfiguration(
        8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    GradientDrawable valueBox = box(AppStyle.FIELD, AppStyle.R_SMALL);
    valueBox.setStroke(Math.max(1, dp(.6f)), AppStyle.OUTLINE);
    value.setBackground(valueBox);
    AdjustmentSeekBar bar = new AdjustmentSeekBar(this, sliderColors(key));
    bar.setMax((int) Math.round((max - min) / step));
    bar.setProgress((int) Math.round((settings.optDouble(key, 0) - min) / step));
    // 双极参数（-x..+y）在 0（默认值）处设刻度，拖回默认那一下的触感更明确
    if (min < 0 && max > 0) bar.setDetentProgress((int) Math.round(-min / step));
    bar.setContentDescription(label);
    controls.add(bar);
    LinearLayout.LayoutParams trackLayout = new LinearLayout.LayoutParams(0, dp(48), 1);
    trackLayout.leftMargin = dp(4);
    trackLayout.rightMargin = dp(6);
    line.addView(bar, trackLayout);
    add(line, value, narrow ? 68 : 72, 32);
    add(editor, line, -1, 56);
    sliders.put(key, bar);
    values.put(key, value);
    value.setText(number(settings.optDouble(key, 0), suffix));
    if (Build.VERSION.SDK_INT >= 30) bar.setStateDescription(value.getText());
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar b, int pos, boolean user) {
            if (user) {
              double v = Math.round((min + pos * step) * 10000) / 10000.0;
              set(key, v);
              value.setText(number(v, suffix));
              if (Build.VERSION.SDK_INT >= 30) b.setStateDescription(value.getText());
              if (key.startsWith("balance_") && key.endsWith("_hue")) {
                String saturation = key.substring(0, key.length() - 4) + "_saturation";
                SeekBar related = sliders.get(saturation);
                if (related instanceof AdjustmentSeekBar)
                  ((AdjustmentSeekBar) related).setTrackColors(sliderColors(saturation));
              }
            }
          }

          public void onStartTrackingTouch(SeekBar b) {}

          public void onStopTrackingTouch(SeekBar b) {
            scheduleRender();
          }
        });
  }

  private int[] sliderColors(String key) {
    if (key.equals("grade_temperature")) return new int[] {0xff659ee8, 0xff999292, 0xffffa34e};
    if (key.equals("grade_tint") || key.equals("m_filter_shift"))
      return new int[] {0xff6dbb8c, 0xff8b858d, 0xffd77cbb};
    if (key.equals("y_filter_shift")) return new int[] {0xff699fe6, 0xff92908a, 0xffe7c64d};
    if (key.startsWith("hsl_")) {
      String band = key.substring(4, key.lastIndexOf('_'));
      int color =
          switch (band) {
            case "red" -> 0xfff24b55;
            case "orange" -> 0xffff963f;
            case "yellow" -> 0xffe4cc50;
            case "green" -> 0xff72bf79;
            case "cyan" -> 0xff58c5cd;
            case "blue" -> 0xff629eef;
            case "purple" -> 0xffa789df;
            default -> 0xffd679bc;
          };
      float[] hsv = new float[3];
      Color.colorToHSV(color, hsv);
      if (key.endsWith("_hue"))
        return new int[] {hueColor(hsv[0] - 35), color, hueColor(hsv[0] + 35)};
      if (key.endsWith("_luminance"))
        return new int[] {
          mixColor(color, Color.BLACK, .7f), color, mixColor(color, Color.WHITE, .7f)
        };
      return new int[] {0xff626268, mixColor(color, 0xff85858b, .5f), color};
    }
    if (key.startsWith("balance_")) {
      if (key.endsWith("_hue"))
        return new int[] {
          0xffed686d, 0xffe5cb62, 0xff77c48b, 0xff64c8d4, 0xff7e9ee7, 0xffc586cc, 0xffed686d
        };
      if (key.endsWith("_saturation")) {
        String hue = key.substring(0, key.length() - "saturation".length()) + "hue";
        int color = hueColor((float) settings.optDouble(hue, 0));
        return new int[] {0xff626268, mixColor(color, 0xff85858b, .5f), color};
      }
    }
    if (key.equals("grade_saturation")) return new int[] {0xff63636b, 0xffbe9570, 0xffff9348};
    if (key.equals("exposure_compensation_ev")
        || key.equals("print_brightness")
        || key.equals("grade_exposure")
        || key.equals("grade_brightness")
        || key.equals("grade_contrast")
        || key.equals("grade_shadows")
        || key.equals("grade_highlights")
        || key.equals("grade_black")
        || key.equals("grade_white")
        || key.startsWith("grade_vignette")
        || key.endsWith("_luminance")) return new int[] {0xff53535c, 0xff909098, 0xffd8d8dd};
    return new int[] {0xff67646a, 0xffac7e58, ACCENT};
  }

  private int hueColor(float hue) {
    return Color.HSVToColor(new float[] {(hue % 360 + 360) % 360, .64f, .94f});
  }

  private int mixColor(int from, int to, float weight) {
    return Color.rgb(
        Math.round(Color.red(from) * (1 - weight) + Color.red(to) * weight),
        Math.round(Color.green(from) * (1 - weight) + Color.green(to) * weight),
        Math.round(Color.blue(from) * (1 - weight) + Color.blue(to) * weight));
  }

  private String number(double value, String suffix) {
    return String.format(
        Locale.US, "%s%.2f%s", value > 0 && !suffix.contains("×") ? "+" : "", value, suffix);
  }

  private void loadCatalog() throws Exception {
    JSONObject j = new JSONObject(readAsset("catalog.json"));
    for (int i = 0; i < j.getJSONArray("films").length(); i++)
      films.add(new Stock(j.getJSONArray("films").getJSONObject(i)));
    for (int i = 0; i < j.getJSONArray("papers").length(); i++)
      papers.add(new Stock(j.getJSONArray("papers").getJSONObject(i)));
    films.sort(Comparator.comparing(s -> s.name));
    papers.sort(Comparator.comparing(s -> s.name));
  }

  private static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] bytes = new byte[65536];
    int n;
    while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
  }

  private String readAsset(String path) throws IOException {
    try (InputStream s = getAssets().open(path);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      copy(s, out);
      return out.toString("UTF-8");
    }
  }

  private Stock find(ArrayList<Stock> list, String id) {
    for (Stock s : list) if (s.id.equals(id)) return s;
    return null;
  }

  private String shortName(String name) {
    return name.replace("Kodak ", "")
        .replace("Fujifilm ", "")
        .replace("Professional ", "")
        .replace("Crystal Archive Type II", "Crystal Archive")
        // Push 版本的名字带「(Push 1)」，在 71dp 的卡片里必然折行；图标上已经有 +1/+2 角标，
        // 名字只留型号即可，完整名称仍在 contentDescription 里。
        .replaceAll("\\s*\\(Push [12]\\)$", "");
  }

  private String maker(Stock s) {
    return s.name.startsWith("Fujifilm") ? "FUJIFILM" : "KODAK";
  }

  private void updateStockLabels() {
    Stock f = find(films, settings.optString("film_stock")),
        p = find(papers, settings.optString("print_stock"));
    if (f != null && filmTitle != null) {
      filmTitle.setText(shortName(f.name));
      filmSub.setText(maker(f) + ("positive".equals(f.type) ? " · 反转片" : " · 彩色负片"));
    }
    if (p != null && paperTitle != null) {
      paperTitle.setText(shortName(p.name));
      paperSub.setText(
          maker(p)
              + (p.id.equals("kodak_2383") || p.id.equals("kodak_2393") ? " · 拷贝片" : " · 印相纸"));
    }
  }

  private void chooseStock(boolean isFilm) {
    if (exporting) return;
    ArrayList<Stock> list = isFilm ? films : papers;
    String key = isFilm ? "film_stock" : "print_stock";
    String[] names = new String[list.size()];
    int selected = -1;
    for (int i = 0; i < names.length; i++) {
      Stock s = list.get(i);
      names[i] = s.name;
      if (s.id.equals(settings.optString(key))) selected = i;
    }
    new Haptics.DialogBuilder(this)
        .setTitle(isFilm ? "选择胶片 · 光谱配置" : "选择相纸 / 拷贝片")
        .setSingleChoiceItems(
            names,
            selected,
            (dialog, n) -> {
              set(key, list.get(n).id);
              updateStockLabels();
              scheduleRender();
              buildPanel();
              dialog.dismiss();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private boolean positiveFilm() {
    Stock stock = find(films, settings.optString("film_stock"));
    return stock != null && stock.type.equals("positive");
  }

  private String params() {
    try {
      JSONObject j = new JSONObject();
      String[] keys = {
        "film_stock",
        "print_stock",
        "exposure_compensation_ev",
        "m_filter_shift",
        "y_filter_shift",
        "grain_amount",
        "halation_amount",
        "film_format_mm",
        "auto_exposure",
        "auto_exposure_method",
        "glare_amount",
        "dir_couplers_amount",
        "halation_scatter_amount",
        "halation_boost_ev",
        "density_curve_gamma",
        "antihalation_removed",
        "lens_blur_um",
        "scanner_lens_blur",
        "preflash_exposure"
      };
      for (String k : keys) if (settings.has(k)) j.put(k, settings.get(k));
      j.put("print_exposure", Math.pow(2, -settings.optDouble("print_brightness", 0)));
      j.put("input_color_space", sourceRaw ? "ProPhoto RGB" : "sRGB");
      j.put("input_cctf_decoding", !sourceRaw);
      j.put("output_color_space", "sRGB");
      j.put("output_cctf_encoding", true);
      j.put("preview_long_edge", 1024);
      j.put("scan_film", positiveFilm());
      j.put("grain_active", settings.optDouble("grain_amount", 1) > 0);
      j.put("halation_active", settings.optDouble("halation_amount", 1) > 0);
      j.put("glare_active", !positiveFilm() && settings.optDouble("glare_amount", 1) > 0);
      j.put("dir_couplers_active", settings.optDouble("dir_couplers_amount", 1) > 0);
      j.put("scene_latitude_active", false);
      j.put("contrast_mask_active", false);
      j.put("overscan_active", false);
      j.put("date_imprint_active", false);
      return j.toString();
    } catch (JSONException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String openParams(String params) throws JSONException {
    JSONObject j = new JSONObject(params);
    j.put("product_defaults", true);
    return j.toString();
  }

  private String editParams() {
    try {
      JSONObject j = new JSONObject(settings.toString());
      j.put("film_label", currentFilmName());
      j.put("source_make", sourceMetadata.optString("make"));
      j.put("source_model", sourceMetadata.optString("model"));
      j.put("source_iso", sourceMetadata.optDouble("iso"));
      j.put("source_aperture", sourceMetadata.optDouble("aperture"));
      j.put("source_shutter", sourceMetadata.optDouble("shutter"));
      j.put("source_focal_mm", sourceMetadata.optDouble("focal_mm"));
      long date = sourceMetadata.optLong("timestamp");
      if (date > 0)
        j.put(
            "source_datetime",
            new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date(date)));
      return j.toString();
    } catch (JSONException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private void scheduleRender() {
    if (activeImport != null) return;
    String now = settings.toString();
    if (committed != null && !committed.equals(now)) {
      undoStack.push(committed);
      while (undoStack.size() > 30) undoStack.removeLast();
      redoStack.clear();
    }
    committed = now;
    updateHistoryButtons();
    prefs.edit().putString("settings", settings.toString()).apply();
    saveActiveFrame();
    int ticket = ++revision;
    if (!ready || source == null || exporting) return;
    exportButton.setEnabled(false);
    exportButton.setText("正在显影…");
    setStatus(
        "照片已读取 · 正在显影 · " + shortName(find(films, settings.optString("film_stock")).name), true);
    main.postDelayed(
        () -> {
          if (!destroyed
              && ticket == revision
              && activeImport == null
              && !exportButton.isEnabled()) {
            setStatus("照片已读取 · 显影响应较慢，可查看诊断", false, true);
            exportButton.setText("查看显影诊断");
            exportButton.setEnabled(true);
            exportButton.setOnClickListener(v -> showDiagnostics());
          }
        },
        30000);
    if (pending != null) main.removeCallbacks(pending);
    pending =
        () -> {
          String p = params();
          Bitmap input = source;
          String edits = editParams();
          File original = sourceFile;
          boolean raw = sourceRaw;
          worker.execute(
              () -> {
                if (ticket != revision || destroyed) return;
                try {
                  ensureOpen(input, original, raw, p);
                  long start = System.nanoTime();
                  Bitmap image = renderer.render(p, edits, false);
                  JSONObject comparison = new JSONObject(edits);
                  comparison.put("grade_enabled", false);
                  comparison.put("date_enabled", false);
                  Bitmap before = NativeEngine.editBitmap(input, comparison.toString());
                  double sec = (System.nanoTime() - start) / 1e9;
                  ui(
                      () -> {
                        if (ticket != revision) {
                          Bitmaps.release(image);
                          Bitmaps.release(before);
                          return;
                        }
                        Bitmap old = developed, oldBefore = compared;
                        developed = image;
                        compared = before;
                        photo.images(before, image);
                        if (old != image) Bitmaps.release(old);
                        Bitmaps.release(oldBefore);
                        setStatus(
                            String.format(
                                Locale.CHINA,
                                "%d × %d · 本次显影 %.2f 秒",
                                image.getWidth(),
                                image.getHeight(),
                                sec),
                            false);
                        exportButton.setText("导出照片");
                        exportButton.setEnabled(true);
                        exportButton.setOnClickListener(v -> exportOptions());
                      });
                } catch (Throwable ex) {
                  if (ticket == revision) {
                    SpectralRenderer current = renderer;
                    if (current != null && !current.isAlive())
                      ui(
                          () -> {
                            if (ticket != revision) return;
                            if (current.backend().equals("gpu")) restartRenderer();
                            else {
                              ready = false;
                              homeImages.setRenderer(null);
                              engine = 0;
                              openedSource = null;
                              setStatus("兼容显影进程已退出 · 可重启", false, true);
                              exportButton.setText("重启兼容显影");
                              exportButton.setEnabled(true);
                              exportButton.setOnClickListener(v -> restartRenderer());
                            }
                          });
                    else report("显影未完成", ex);
                  }
                }
              });
        };
    main.postDelayed(pending, 220);
  }

  private void ensureOpen(Bitmap input, File file, boolean raw, String params)
      throws JSONException {
    if (openedSource != input) {
      if (raw) renderer.openRaw(file.getAbsolutePath(), openParams(params), 1024);
      else renderer.open(input, openParams(params));
      openedSource = input;
    }
  }

  private void setStatus(String message, boolean busy) {
    setStatus(message, busy, false);
  }

  private void setStatus(String message, boolean busy, boolean keepVisible) {
    status.setText(message);
    progress.setVisibility(busy ? View.VISIBLE : View.GONE);
    if (statusRow != null) statusRow.setVisibility(busy || keepVisible ? View.VISIBLE : View.GONE);
  }

  private void ui(Runnable r) {
    main.post(
        () -> {
          if (!destroyed) r.run();
        });
  }

  private void report(String title, Throwable ex) {
    android.util.Log.e("SpektraLensAndroid", title, ex);
    ui(
        () -> {
          if (activeImport != null) return;
          setStatus(title, false, true);
          exportButton.setText("重试显影");
          exportButton.setEnabled(ready);
          exportButton.setOnClickListener(
              v -> {
                exportButton.setOnClickListener(w -> exportOptions());
                scheduleRender();
              });
          new Haptics.DialogBuilder(this)
              .setTitle(title)
              .setMessage(
                  (ex.getMessage() == null ? ex.toString() : ex.getMessage())
                      + "\n\n"
                      + "照片与编辑设置会保留。可重试，或换用较小的照片。RAW 支持取决于 LibRaw 对相机型号的兼容；原尺寸显影需要足够的设备内存和 Vulkan"
                      + " 缓冲空间。")
              .setPositiveButton("知道了", null)
              .show();
        });
  }

  private static final class LoadedPhoto {
    Bitmap bitmap;
    File file;
    JSONObject meta;
    int width, height;
    boolean raw;
  }

  private LoadedPhoto readPhoto(File file, int longEdge) throws IOException {
    LoadedPhoto out = new LoadedPhoto();
    out.file = file;
    try {
      out.meta =
          PhotoFormats.isRaster(file)
              ? new JSONObject()
              : new JSONObject(NativeEngine.inspectRaw(file.getAbsolutePath()));
      out.raw = out.meta.optBoolean("raw");
      if (out.raw) {
        if (longEdge == 0) throw new IOException("RAW 原尺寸由本地解码器直接导出");
        out.bitmap = renderer.rawPreview(file.getAbsolutePath(), longEdge);
        out.width = out.meta.getInt("width");
        out.height = out.meta.getInt("height");
      } else {
        out.bitmap =
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(file),
                (decoder, info, src) -> {
                  decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                  decoder.setMutableRequired(true);
                  decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                  out.width = info.getSize().getWidth();
                  out.height = info.getSize().getHeight();
                  float scale =
                      longEdge == 0
                          ? 1
                          : Math.min(1, longEdge / (float) Math.max(out.width, out.height));
                  decoder.setTargetSize(
                      Math.max(1, Math.round(out.width * scale)),
                      Math.max(1, Math.round(out.height * scale)));
                });
        if (out.bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
          Bitmap normalised = out.bitmap.copy(Bitmap.Config.ARGB_8888, true);
          if (normalised == null) throw new IOException("无法转换照片预览");
          Bitmaps.release(out.bitmap);
          out.bitmap = normalised;
        }
        try {
          androidx.exifinterface.media.ExifInterface exif =
              new androidx.exifinterface.media.ExifInterface(file.getAbsolutePath());
          out.meta.put(
              "make", exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE));
          out.meta.put(
              "model", exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL));
          String date =
              exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL);
          if (date != null)
            out.meta.put(
                "timestamp",
                new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(date).getTime());
          out.meta.put(
              "iso",
              exif.getAttributeDouble(
                  androidx.exifinterface.media.ExifInterface.TAG_ISO_SPEED_RATINGS, 0));
          out.meta.put(
              "aperture",
              exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, 0));
          out.meta.put(
              "shutter",
              exif.getAttributeDouble(
                  androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, 0));
          out.meta.put(
              "focal_mm",
              exif.getAttributeDouble(
                  androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH, 0));
        } catch (Exception ignored) {
          // 单条 EXIF 缺失不影响导入，按默认值继续
        }
      }
      if (out.bitmap == null) throw new IOException("照片解码没有返回图像");
      return out;
    } catch (JSONException ex) {
      throw new IOException(ex);
    }
  }

  private void adopt(LoadedPhoto image) {
    Bitmap previous = source;
    if (developed != null) {
      Bitmaps.release(developed);
      developed = null;
    }
    if (compared != null) {
      Bitmaps.release(compared);
      compared = null;
    }
    source = image.bitmap;
    if (homeHero != null) {
      boolean changed = homeImages.bind(homeHero, image.file, null, null, true);
      if (changed
          || homeHero.getDrawable() == null
          || (homeHero.getDrawable() instanceof BitmapDrawable shown
              && shown.getBitmap() == previous)) {
        homeHero.setImageBitmap(source);
      }
      homeImages.loadVisible();
    }
    sourceFile = image.file;
    sourceMetadata = image.meta;
    sourceRaw = image.raw;
    fullWidth = image.width;
    fullHeight = image.height;
    // Old previews may still be queued on the worker; recycle only after those jobs finish.
    if (previous != null && previous != source)
      worker.execute(
          () ->
              ui(
                  () -> {
                    if (previous != source) Bitmaps.release(previous);
                  }));
  }

  private LoadedPhoto loadDemo() throws IOException {
    File f = new File(getCacheDir(), "demo.jpg");
    try (InputStream in = getAssets().open("demo.jpg");
        OutputStream out = new FileOutputStream(f)) {
      copy(in, out);
    } catch (FileNotFoundException ex) {
      try (OutputStream out = new FileOutputStream(f)) {
        DemoImage.create().compress(Bitmap.CompressFormat.JPEG, 100, out);
      }
    }
    return readPhoto(f, 1024);
  }

  private String demoName() {
    try {
      getAssets().open("demo.jpg").close();
      return "阿尔卑斯山 · 示例照片";
    } catch (IOException ex) {
      return "内置静物色卡 · 合成参考图";
    }
  }

  private void importPhoto() {
    importPhoto(null);
  }

  private void importPhoto(String initialSettings) {
    if (exporting) return;
    if (gallerySheet != null && gallerySheet.isShowing()) return;
    ArrayList<GalleryPhotos.Photo> imported = new ArrayList<>();
    for (Frame frame : library) {
      File thumbnail = new File(new File(getFilesDir(), "thumbnails"), frame.id + ".jpg");
      File look = developedLook(frame.id);
      imported.add(
          GalleryPhotos.Photo.imported(
              frame.id,
              frame.name,
              look.isFile()
                  ? look
                  : thumbnail.isFile() ? thumbnail : new File(frame.path)));
    }
    File demo = new File(getCacheDir(), "demo.jpg");
    if (demo.isFile()) imported.add(GalleryPhotos.Photo.imported("demo", "内置示例照片", demo));
    gallerySheet =
        new GallerySheet(
            this,
            imported,
            activeId,
            new GallerySheet.Listener() {
              public void picked(GalleryPhotos.Photo selected) {
                if (selected.uri != null) importUri(selected.uri, initialSettings);
                else if ("demo".equals(selected.frameId)) {
                  showEditor();
                  loadDemoAction(initialSettings);
                } else
                  for (Frame frame : library)
                    if (frame.id.equals(selected.frameId)) {
                      openFrame(frame, initialSettings);
                      break;
                    }
              }

              public void files() {
                chooseFile(initialSettings);
              }

              public void systemPicker() {
                chooseSystemPhoto(initialSettings);
              }

              public void permission() {
                requestGalleryPermission();
              }
            });
    gallerySheet.show();
    if (GalleryPhotos.access(this) == GalleryPhotos.Access.NONE
        && !prefs.getBoolean("gallery_permission_requested", false)) requestGalleryPermission();
  }

  private void chooseFile() {
    chooseFile(null);
  }

  private void chooseFile(String initialSettings) {
    pickerSettings = initialSettings;
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("*/*");
    startActivityForResult(intent, 10);
  }

  private void chooseSystemPhoto() {
    chooseSystemPhoto(null);
  }

  private void chooseSystemPhoto(String initialSettings) {
    pickerSettings = initialSettings;
    Intent intent =
        new Intent(
            Build.VERSION.SDK_INT >= 33
                ? MediaStore.ACTION_PICK_IMAGES
                : Intent.ACTION_GET_CONTENT);
    intent.setType("image/*");
    try {
      startActivityForResult(intent, 10);
    } catch (ActivityNotFoundException ex) {
      chooseFile(initialSettings);
    }
  }

  private void requestGalleryPermission() {
    GalleryPhotos.Access access = GalleryPhotos.access(this);
    if (access == GalleryPhotos.Access.FULL) {
      if (gallerySheet != null) gallerySheet.refresh();
      return;
    }
    boolean rationale = false;
    for (String permission : GalleryPhotos.permissions())
      rationale |= shouldShowRequestPermissionRationale(permission);
    if (access == GalleryPhotos.Access.NONE
        && prefs.getBoolean("gallery_permission_denied", false)
        && !rationale) {
      startActivity(
          new Intent(
              android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
              Uri.parse("package:" + getPackageName())));
      return;
    }
    prefs.edit().putBoolean("gallery_permission_requested", true).apply();
    requestPermissions(GalleryPhotos.permissions(), 41);
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 41) {
      prefs
          .edit()
          .putBoolean(
              "gallery_permission_denied", GalleryPhotos.access(this) == GalleryPhotos.Access.NONE)
          .apply();
      if (gallerySheet != null && gallerySheet.isShowing()) gallerySheet.refresh();
    }
  }

  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req == 10) {
      String initialSettings = pickerSettings;
      pickerSettings = null;
      if (res == RESULT_OK && data != null && data.getData() != null)
        importUri(data.getData(), initialSettings);
    }
  }

  @Override
  protected void onNewIntent(Intent i) {
    super.onNewIntent(i);
    setIntent(i);
    handleShare(i);
  }

  private void handleShare(Intent intent) {
    if (Intent.ACTION_SEND.equals(intent.getAction())) {
      Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
      if (uri != null) main.post(() -> importUri(uri));
    }
  }

  private void importUri(Uri uri) {
    importUri(uri, null);
  }

  private void importUri(Uri uri, String initialSettings) {
    if (exporting || destroyed) return;
    if (activeImport != null) cancelImport("已取消上一次导入");
    final PhotoImportJob job;
    try {
      job = new PhotoImportJob(getFilesDir(), initialSettings);
    } catch (IOException ex) {
      report("无法准备照片副本", ex);
      return;
    }
    activeImport = job;
    ++photoLoadGeneration;
    ++revision;
    if (pending != null) main.removeCallbacks(pending);
    showEditor();
    for (View v : controls) v.setEnabled(false);
    exportButton.setText("取消导入");
    exportButton.setEnabled(true);
    exportButton.setOnClickListener(v -> cancelImport("已取消导入"));
    setStatus("正在读取照片信息…", true);
    watchImport(job);
    importWorker.execute(
        () -> {
          boolean handedToRenderer = false;
          try {
            try (android.database.Cursor cursor =
                getContentResolver()
                    .query(
                        uri,
                        new String[] {
                          android.provider.OpenableColumns.DISPLAY_NAME,
                          android.provider.OpenableColumns.SIZE
                        },
                        null,
                        null,
                        null,
                        job.signal)) {
              if (cursor != null && cursor.moveToFirst()) {
                int nameColumn =
                    cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn))
                  job.name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn))
                  job.total = cursor.getLong(sizeColumn);
              }
            } catch (android.os.OperationCanceledException ex) {
              throw ex;
            } catch (RuntimeException ex) {
              android.util.Log.w("SpektraLensAndroid", "Provider metadata unavailable", ex);
            }
            job.check();
            job.stage("打开相册照片");
            try (android.content.res.AssetFileDescriptor descriptor =
                getContentResolver().openAssetFileDescriptor(uri, "r", job.signal)) {
              if (descriptor == null) throw new IOException("相册没有返回可读取的照片");
              if (job.total < 0) job.total = descriptor.getLength();
              try (InputStream in = descriptor.createInputStream();
                  OutputStream out = new FileOutputStream(job.temp)) {
                job.input = in;
                job.check();
                job.stage("复制原照片");
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) != -1) {
                  job.check();
                  out.write(buffer, 0, n);
                  job.copied += n;
                  job.lastProgress = SystemClock.elapsedRealtime();
                }
                job.check();
              } finally {
                job.input = null;
              }
            }
            if (job.copied == 0) throw new IOException("所选照片没有可读取的内容");
            job.check();
            if (PhotoFormats.isRaster(job.temp)) {
              job.stage("解码普通照片");
              decodeImportedPhoto(job);
            } else {
              job.raw = true;
              job.stage("识别照片格式 / 等待 RAW 解码");
              handedToRenderer = true;
              worker.execute(
                  () -> {
                    try {
                      job.check();
                      job.stage("解码 RAW 照片");
                      decodeImportedPhoto(job);
                    } catch (Throwable ex) {
                      failImport(job, ex);
                    } finally {
                      job.temp.delete();
                    }
                  });
            }
          } catch (Throwable ex) {
            failImport(job, ex);
          } finally {
            if (!handedToRenderer) job.temp.delete();
          }
        });
  }

  private void decodeImportedPhoto(PhotoImportJob job) throws IOException {
    job.check();
    LoadedPhoto loaded = readPhoto(job.temp, 1024);
    File stored = null;
    try {
      job.check();
      job.stage("保存照片与缩略图");
      File dir = new File(getFilesDir(), "photos");
      if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建图库");
      String id = UUID.randomUUID().toString();
      stored = new File(dir, id + ".img");
      if (!job.temp.renameTo(stored)) throw new IOException("无法保存导入照片");
      loaded.file = stored;
      saveThumbnail(id, loaded.bitmap);
      job.check();
      File original = stored;
      main.post(
          () -> {
            if (destroyed || activeImport != job || job.cancelled.get()) {
              Bitmaps.release(loaded.bitmap);
              original.delete();
              new File(new File(getFilesDir(), "thumbnails"), id + ".jpg").delete();
              return;
            }
            writeImportDiagnostic(job, "completed", null);
            activeImport = null;
            for (View v : controls) v.setEnabled(true);
            saveActiveFrame();
            try {
              settings = new JSONObject(job.initialSettings == null ? "{}" : job.initialSettings);
            } catch (JSONException ex) {
              settings = new JSONObject();
            }
            defaults();
            filmCategory = category(find(films, settings.optString("film_stock")));
            if (job.initialSettings == null) {
              selectedTab = 0;
              gradeSection = "基础";
              curveChannel = "rgb";
              balanceZone = "midtones";
              hslBand = "red";
            }
            comparing = false;
            updateComparison();
            adopt(loaded);
            filename = job.name;
            activeId = id;
            syncHeroLook();
            library.add(
                0, new Frame(id, filename, original.getAbsolutePath(), settings.toString()));
            undoStack.clear();
            redoStack.clear();
            committed = settings.toString();
            prefs
                .edit()
                .putString("filename", filename)
                .putString("activeId", id)
                .putString("settings", settings.toString())
                .apply();
            fileLabel.setText(filename);
            photo.images(source, null);
            photo.resetZoom();
            refreshStrip();
            saveLibrary();
            buildPanel();
            if (ready) scheduleRender();
            else {
              setStatus(
                  engineBootPending ? "照片已读取 · 正在准备显影引擎…" : "照片已读取 · 可重启兼容显影",
                  engineBootPending,
                  !engineBootPending);
              exportButton.setText(engineBootPending ? "查看显影诊断" : "重启兼容显影");
              exportButton.setEnabled(true);
              exportButton.setOnClickListener(
                  v -> {
                    if (engineBootPending) showDiagnostics();
                    else restartRenderer();
                  });
            }
          });
    } catch (Throwable ex) {
      Bitmaps.release(loaded.bitmap);
      if (stored != null) stored.delete();
      throw ex;
    }
  }

  private void watchImport(PhotoImportJob job) {
    if (destroyed || activeImport != job || job.cancelled.get()) return;
    long now = SystemClock.elapsedRealtime();
    if (job.expired(now)) {
      writeImportDiagnostic(job, "timeout", job.phase + "阶段长时间没有进展");
      cancelImport(job.phase + "超时 · 可重新选择照片");
      return;
    }
    String bytes =
        job.copied > 0 ? String.format(Locale.US, " · %.1f MB", job.copied / 1048576.0) : "";
    if (job.total > 0 && job.copied > 0)
      bytes += String.format(Locale.US, " / %.1f MB", job.total / 1048576.0);
    setStatus(job.phase + bytes + " · " + ((now - job.started) / 1000) + " 秒", true);
    writeImportDiagnostic(job, "running", null);
    main.postDelayed(() -> watchImport(job), 1000);
  }

  private void cancelImport(String message) {
    PhotoImportJob job = activeImport;
    if (job == null) return;
    if (!message.contains("超时")) writeImportDiagnostic(job, "cancelled", null);
    activeImport = null;
    job.cancel();
    for (View v : controls) v.setEnabled(true);
    setStatus(message, false, true);
    updateHistoryButtons();
    exportButton.setText(developed != null ? "导出照片" : "重新导入照片");
    exportButton.setEnabled(true);
    exportButton.setOnClickListener(
        v -> {
          if (developed != null) exportOptions();
          else importPhoto();
        });
  }

  private void failImport(PhotoImportJob job, Throwable ex) {
    if (job.cancelled.get()) {
      android.util.Log.i("SpektraLensAndroid", "Import cancelled at " + job.phase);
      return;
    }
    android.util.Log.e("SpektraLensAndroid", "Import failed at " + job.phase, ex);
    main.post(
        () -> {
          if (destroyed || activeImport != job || job.cancelled.get()) return;
          writeImportDiagnostic(job, "failed", ex.toString());
          cancelImport("照片导入失败 · 可重新选择");
          // Keep the failed stage in the diagnostic, rather than overwriting it as cancellation.
          writeImportDiagnostic(job, "failed", ex.toString());
          new Haptics.DialogBuilder(this)
              .setTitle("照片导入失败")
              .setMessage(
                  job.phase + "\n" + (ex.getMessage() == null ? ex.toString() : ex.getMessage()))
              .setPositiveButton("知道了", null)
              .setNeutralButton("查看诊断", (d, n) -> showDiagnostics())
              .show();
        });
  }

  private void writeImportDiagnostic(PhotoImportJob job, String outcome, String error) {
    try {
      JSONObject j = new JSONObject();
      j.put("app_version", "0.3.6");
      j.put("device", Build.MANUFACTURER + " " + Build.MODEL);
      j.put("android_api", Build.VERSION.SDK_INT);
      j.put("stage", job.phase);
      j.put("outcome", outcome);
      j.put("filename", job.name);
      j.put("bytes_copied", job.copied);
      j.put("total_bytes", job.total);
      j.put("elapsed_ms", SystemClock.elapsedRealtime() - job.started);
      if (error != null) j.put("error", error);
      android.util.AtomicFile atomic =
          new android.util.AtomicFile(new File(getFilesDir(), "last_import.json"));
      FileOutputStream stream = null;
      try {
        stream = atomic.startWrite();
        stream.write(j.toString(2).getBytes(StandardCharsets.UTF_8));
        atomic.finishWrite(stream);
      } catch (IOException ex) {
        if (stream != null) atomic.failWrite(stream);
        throw ex;
      }
    } catch (Exception ex) {
      android.util.Log.w("SpektraLensAndroid", "Cannot save import diagnostic", ex);
    }
  }

  private void showDiagnostics() {
    String diagnostic =
        "设备："
            + Build.MANUFACTURER
            + " "
            + Build.MODEL
            + "\nAndroid API "
            + Build.VERSION.SDK_INT
            + "\n显影引擎："
            + (ready ? "已初始化" : "准备中或不可用")
            + "\n\n";
    File file = new File(getFilesDir(), "last_import.json");
    try (InputStream in = new FileInputStream(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      copy(in, out);
      diagnostic += out.toString("UTF-8");
    } catch (IOException ex) {
      diagnostic += "暂无导入记录";
    }
    for (String backend : new String[] {"gpu", "cpu"}) {
      File nativeState = new File(getFilesDir(), "engine-" + backend + ".json");
      try (InputStream in = new FileInputStream(nativeState);
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        copy(in, out);
        diagnostic += "\n\n引擎 " + backend + "：\n" + out.toString("UTF-8");
      } catch (IOException ignored) {
        // 引擎日志读不到不影响诊断主内容
      }
    }
    SpectralRenderer current = renderer;
    if (current != null)
      diagnostic +=
          "\n当前模式："
              + current.backend()
              + "\n回退原因："
              + current.fallbackReason()
              + "\n计算设备："
              + current.capabilities();
    File exportTrace = new File(getFilesDir(), "last_export_diagnostic.json");
    try (InputStream in = new FileInputStream(exportTrace);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      copy(in, out);
      diagnostic += "\n\n最近一次导出：\n" + out.toString("UTF-8");
    } catch (IOException ignored) {
      // 还没生成导出追踪文件时忽略
    }
    diagnostic += "\n启动错误：" + prefs.getString("last_engine_error", "无");
    final String message = diagnostic;
    new Haptics.DialogBuilder(this)
        .setTitle("照片处理诊断")
        .setMessage(message)
        .setPositiveButton("关闭", null)
        .setNegativeButton("切换显影模式", (d, n) -> chooseRendererMode())
        .setNeutralButton(
            "复制诊断",
            (d, n) -> {
              ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
                  .setPrimaryClip(
                      ClipData.newPlainText(getString(R.string.app_name) + " 诊断", message));
              Toast.makeText(this, "诊断已复制", Toast.LENGTH_SHORT).show();
            })
        .show();
  }

  private void loadDemoAction() {
    loadDemoAction(null);
  }

  private void loadDemoAction(String initialSettings) {
    if (!ready || exporting) return;
    ++revision;
    setStatus("载入示例…", true);
    worker.execute(
        () -> {
          try {
            LoadedPhoto b = loadDemo();
            ui(
                () -> {
                  saveActiveFrame();
                  try {
                    settings = new JSONObject(initialSettings == null ? "{}" : initialSettings);
                  } catch (JSONException error) {
                    settings = new JSONObject();
                  }
                  defaults();
                  filmCategory = category(find(films, settings.optString("film_stock")));
                  committed = settings.toString();
                  comparing = false;
                  updateComparison();
                  if (initialSettings == null) {
                    selectedTab = 0;
                    gradeSection = "基础";
                    curveChannel = "rgb";
                    balanceZone = "midtones";
                    hslBand = "red";
                  }
                  adopt(b);
                  activeId = "demo";
                  prefs.edit().putString("activeId", "demo").apply();
                  developed = null;
                  filename = demoName();
                  fileLabel.setText(filename);
                  photo.images(source, null);
                  photo.resetZoom();
                  undoStack.clear();
                  redoStack.clear();
                  refreshStrip();
                  buildPanel();
                  scheduleRender();
                });
          } catch (Throwable ex) {
            report("示例载入失败", ex);
          }
        });
  }

  private void reset() {
    settings = new JSONObject();
    defaults();
    buildPanel();
    updateStockLabels();
    scheduleRender();
  }

  private void loadLibrary() {
    activeId = prefs.getString("activeId", "demo");
    File file = new File(getFilesDir(), "library.json");
    if (file.exists())
      try (InputStream in = new FileInputStream(file);
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        copy(in, out);
        JSONArray list = new JSONArray(out.toString("UTF-8"));
        for (int i = 0; i < list.length(); i++) {
          JSONObject j = list.getJSONObject(i);
          String id = j.getString("id");
          File original = new File(new File(getFilesDir(), "photos"), id + ".img");
          if (original.exists())
            library.add(
                new Frame(
                    id, j.getString("name"), original.getAbsolutePath(), j.getString("params")));
        }
      } catch (Exception ex) {
        android.util.Log.e("SpektraLensAndroid", "Read library", ex);
      }
    Frame frame = findFrame(activeId);
    if (frame != null)
      try {
        settings = new JSONObject(frame.params);
        defaults();
        committed = settings.toString();
      } catch (JSONException ignored) {
        // 帧记录里的参数损坏时退回默认值
      }
  }

  private Frame findFrame(String id) {
    for (Frame f : library) if (f.id.equals(id)) return f;
    return null;
  }

  private void saveActiveFrame() {
    Frame frame = findFrame(activeId);
    if (frame != null) {
      frame.params = settings.toString();
      saveLibrary();
    }
  }

  private void saveLibrary() {
    try {
      JSONArray list = new JSONArray();
      for (Frame frame : library) {
        JSONObject j = new JSONObject();
        j.put("id", frame.id);
        j.put("name", frame.name);
        j.put("params", frame.params);
        list.put(j);
      }
      File temp = new File(getFilesDir(), "library.pending");
      try (FileOutputStream out = new FileOutputStream(temp)) {
        out.write(list.toString().getBytes(StandardCharsets.UTF_8));
        out.getFD().sync();
      }
      if (!temp.renameTo(new File(getFilesDir(), "library.json")))
        throw new IOException("Cannot write library");
    } catch (Exception ex) {
      android.util.Log.e("SpektraLensAndroid", "Save library", ex);
      Toast.makeText(this, "编辑记录保存失败，请检查剩余空间", Toast.LENGTH_LONG).show();
    }
  }

  private void saveThumbnail(String id, Bitmap image) throws IOException {
    File dir = new File(getFilesDir(), "thumbnails");
    if (!dir.exists() && !dir.mkdirs()) throw new IOException("无法创建缩略图目录");
    float scale = Math.min(1, 240f / Math.max(image.getWidth(), image.getHeight()));
    Bitmap small =
        Bitmap.createScaledBitmap(
            image,
            Math.max(1, Math.round(image.getWidth() * scale)),
            Math.max(1, Math.round(image.getHeight() * scale)),
            true);
    try (OutputStream out = new FileOutputStream(new File(dir, id + ".jpg"))) {
      small.compress(Bitmap.CompressFormat.JPEG, 85, out);
    } finally {
      if (small != image) Bitmaps.release(small);
    }
  }

  private void refreshStrip() {
    if (photoPickerButton != null) {
      photoPickerButton.setContentDescription("选择手机相册或已导入的照片");
      photoPickerButton.setTooltipText(filename);
    }
  }

  private void choosePhoto() {
    importPhoto();
  }

  private void refreshHistory() {
    if (historyList == null) return;
    if (recentCount != null)
      recentCount.setText(library.size() + " 张 · 长按可删除");
    // Rebuilding every card also rebuilds every ImageView, so returning from the editor used to
    // re-decode the whole strip and visibly reshuffle it. Only rebuild when something changed.
    StringBuilder signature = new StringBuilder();
    for (Frame frame : library)
      signature.append(frame.id).append(':').append(frame.path).append(':')
          .append(frame.params).append(';');
    String current = signature.toString();
    if (current.equals(historySignature) && historyList.getChildCount() > 0) return;
    historySignature = current;
    controls.removeIf(v -> descendant(v, historyList));
    homeImages.clearCards();
    historyList.removeAllViews();
    if (library.isEmpty()) {
      LinearLayout empty = vertical();
      empty.setPadding(dp(16), dp(15), dp(16), dp(15));
      AppStyle.surface(empty, AppStyle.SURFACE, AppStyle.R_MEDIUM);
      TextView title = text("你的第一张底片，还在等光。", AppStyle.TYPE_BODY, INK);
      add(empty, title, -1, 26);
      TextView detail = text("导入照片后，可在这里继续上次的编辑。", AppStyle.TYPE_MICRO, MUTED);
      add(empty, detail, -1, 24);
      add(historyList, empty, -1, 88);
      return;
    }
    for (int i = 0; i < library.size(); i += 2) {
      LinearLayout row = horizontal();
      row.setGravity(Gravity.TOP);
      for (int k = i; k < Math.min(i + 2, library.size()); k++) {
        Frame frame = library.get(k);
        LinearLayout card = vertical();
        AppStyle.surface(card, AppStyle.SURFACE, AppStyle.R_MEDIUM);
        card.setClipToOutline(true);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        add(card, image, -1, 125);
        homeImages.bind(
            image,
            new File(frame.path),
            developedLook(frame.id),
            new File(new File(getFilesDir(), "thumbnails"), frame.id + ".jpg"),
            false);
        LinearLayout names = vertical();
        names.setPadding(dp(12), dp(10), dp(12), dp(12));
        TextView name = text(PhotoLabels.title(frame.name), AppStyle.TYPE_BODY, INK);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        add(names, name, -1, 26);
        File original = new File(frame.path);
        TextView details =
            text(
                PhotoLabels.format(original, frame.name)
                    + " · "
                    + android.text.format.Formatter.formatShortFileSize(this, original.length()),
                10,
                MUTED);
        add(names, details, -1, 22);
        String stock = "已保存调整";
        try {
          Stock s = find(films, new JSONObject(frame.params).optString("film_stock"));
          if (s != null) stock = shortName(s.name);
        } catch (Exception ignored) {
          // 历史记录引用的胶片已不存在，保留原来的标签
        }
        TextView look = text(stock, AppStyle.TYPE_MICRO, ACCENT);
        look.setSingleLine(true);
        look.setEllipsize(android.text.TextUtils.TruncateAt.END);
        add(names, look, -1, 24);
        add(card, names, -1, -2);
        card.setOnClickListener(v -> openFrame(frame));
        card.setOnLongClickListener(
            v -> {
              v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
              confirmRemove(frame);
              return true;
            });
        controls.add(card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (k == i) lp.rightMargin = dp(10);
        row.addView(card, lp);
      }
      if (i + 1 == library.size())
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
      add(historyList, row, -1, -2);
      gap(historyList, 12);
    }
  }

  private void confirmRemove(Frame frame) {
    new Haptics.DialogBuilder(this)
        .setTitle("移除这张照片？")
        .setMessage("删除应用内的照片副本与编辑记录。手机相册中的原照片不受影响。")
        .setPositiveButton(
            "移除",
            (d, n) -> {
              if (exporting) return;
              library.remove(frame);
              new File(frame.path).delete();
              new File(new File(getFilesDir(), "developed"), frame.id + ".jpg").delete();
              saveLibrary();
              refreshHistory();
              homeImages.remove(new File(frame.path));
              homeImages.removeDeveloped(frame.id);
              refreshStrip();
              if (activeId.equals(frame.id)) loadDemoAction();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void openFrame(Frame frame) {
    openFrame(frame, null);
  }

  private void openFrame(Frame frame, String initialSettings) {
    if (exporting || !ready) return;
    if (frame.id.equals(activeId)) {
      showEditor();
      if (initialSettings != null) {
        try {
          settings = new JSONObject(initialSettings);
        } catch (JSONException ignored) {
          // 外部传入的参数不可解析时用默认值
        }
        defaults();
        filmCategory = category(find(films, settings.optString("film_stock")));
        buildPanel();
        scheduleRender();
      } else if (developed == null) {
        // Startup restores the source on Home and defers developing until this photo is opened.
        scheduleRender();
      }
      return;
    }
    saveActiveFrame();
    int ticket = ++revision;
    showEditor();
    setStatus("读取底片与编辑记录…", true);
    exportButton.setEnabled(false);
    worker.execute(
        () -> {
          try {
            LoadedPhoto b = readPhoto(new File(frame.path), 1024);
            saveThumbnail(frame.id, b.bitmap);
            ui(
                () -> {
                  if (ticket != revision) {
                    Bitmaps.release(b.bitmap);
                    return;
                  }
                  adopt(b);
                  developed = null;
                  activeId = frame.id;
                  syncHeroLook();
                  filename = frame.name;
                  try {
                    settings =
                        new JSONObject(initialSettings == null ? frame.params : initialSettings);
                  } catch (JSONException ex) {
                    settings = new JSONObject();
                  }
                  defaults();
                  filmCategory = category(find(films, settings.optString("film_stock")));
                  committed = settings.toString();
                  undoStack.clear();
                  redoStack.clear();
                  if (initialSettings != null && !frame.params.equals(settings.toString()))
                    undoStack.push(frame.params);
                  prefs
                      .edit()
                      .putString("activeId", activeId)
                      .putString("filename", filename)
                      .apply();
                  fileLabel.setText(filename);
                  photo.images(source, null);
                  photo.resetZoom();
                  refreshStrip();
                  buildPanel();
                  scheduleRender();
                });
          } catch (Throwable ex) {
            report("照片读取失败", ex);
          }
        });
  }

  private void updateHistoryButtons() {
    if (undoButton != null) {
      undoButton.setEnabled(!undoStack.isEmpty() && !exporting);
      undoButton.setAlpha(undoStack.isEmpty() ? .3f : 1);
      undoButton.setVisibility(undoStack.isEmpty() ? View.GONE : View.VISIBLE);
      redoButton.setEnabled(!redoStack.isEmpty() && !exporting);
      redoButton.setAlpha(redoStack.isEmpty() ? .3f : 1);
      redoButton.setVisibility(redoStack.isEmpty() ? View.GONE : View.VISIBLE);
    }
  }

  private void historyStep(boolean redo) {
    if (exporting) return;
    ArrayDeque<String> from = redo ? redoStack : undoStack, to = redo ? undoStack : redoStack;
    if (from.isEmpty()) return;
    to.push(settings.toString());
    try {
      settings = new JSONObject(from.pop());
    } catch (JSONException ex) {
      return;
    }
    committed = settings.toString();
    filmCategory = category(find(films, settings.optString("film_stock")));
    buildPanel();
    updateStockLabels();
    scheduleRender();
  }

  private void exportOptions() {
    if (!ready || developed == null || exporting) return;
    String dimensions = dimensionsLabel();
    new Haptics.DialogBuilder(this)
        .setTitle("原尺寸导出 · " + dimensions)
        .setItems(
            new String[] {"保存 JPEG · 8 位", "保存 PNG · 16 位", "保存 TIFF · 16 位", "分享 JPEG · 8 位"},
            (d, n) -> export(n))
        .setNegativeButton("取消", null)
        .show();
  }

  private void lock(boolean value) {
    exporting = value;
    for (View v : controls) v.setEnabled(!value);
    exportButton.setEnabled(!value && developed != null);
    updateHistoryButtons();
  }

  private String sha256(File file) throws Exception {
    java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) digest.update(b, 0, n);
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : digest.digest()) hex.append(String.format(Locale.US, "%02x", b & 255));
    return hex.toString();
  }

  private void export(int mode) {
    if (exporting || sourceFile == null) return;
    lock(true);
    ++revision;
    if (pending != null) main.removeCallbacks(pending);
    setStatus("从原文件读取并显影…", true);
    exportButton.setText("正在导出…");
    String p = params(),
        edits = editParams(),
        name = filename,
        exportedSettings = settings.toString();
    File original = sourceFile;
    boolean raw = sourceRaw;
    int width = fullWidth, height = fullHeight;
    String format = mode == 1 ? "png" : mode == 2 ? "tiff" : "jpeg";
    ExportTrace trace = new ExportTrace(this);
    trace.put("source_name", name);
    trace.put("source_bytes", original.length());
    trace.put("source_width", width);
    trace.put("source_height", height);
    trace.put("source_raw", raw);
    trace.put("format", format);
    worker.execute(
        () -> {
          Uri uri = null;
          File cache = null;
          try {
            trace.put("backend_before", renderer.backend());
            trace.phase("open_ms", "读取原文件");
            android.app.ActivityManager.MemoryInfo memory =
                new android.app.ActivityManager.MemoryInfo();
            ((android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE))
                .getMemoryInfo(memory);
            long estimate = (long) width * height * 100 + 128L * 1024 * 1024;
            if (estimate > memory.availMem * .85)
              throw new IOException(
                  "本次原尺寸显影预计需要约 "
                      + (estimate / 1024 / 1024)
                      + " MB 可用内存，当前约 "
                      + (memory.availMem / 1024 / 1024)
                      + " MB。请关闭其他应用后重试，或使用较小的源照片。");
            renderer.closeImage();
            openedSource = null;
            if (raw) renderer.openRaw(original.getAbsolutePath(), openParams(p), 0);
            else {
              renderer.openFile(original.getAbsolutePath(), openParams(p));
            }
            String base =
                getString(R.string.app_name)
                    + "_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String ext = mode == 1 ? ".png" : mode == 2 ? ".tiff" : ".jpg";
            String mime = mode == 1 ? "image/png" : mode == 2 ? "image/tiff" : "image/jpeg";
            cache = new File(getCacheDir(), base + ext);
            trace.phase("develop_encode_ms", "原尺寸显影与编码");
            ui(() -> setStatus("正在原尺寸显影与编码…", true));
            JSONObject dimensions =
                new JSONObject(renderer.exportImage(p, edits, cache.getAbsolutePath(), format));
            trace.put("backend_after", renderer.backend());
            trace.put("fallback_reason", renderer.fallbackReason());
            trace.put("native_timings", dimensions.optJSONObject("timings"));
            trace.put("output_width", dimensions.getInt("width"));
            trace.put("output_height", dimensions.getInt("height"));
            trace.put("output_bytes", cache.length());
            trace.phase("gallery_ms", "写入相册");
            ui(() -> setStatus(mode == 3 ? "正在准备分享…" : "正在写入相册…", true));
            if (!cache.isFile() || cache.length() == 0) throw new IOException("导出没有生成有效文件");
            if (mode != 3) {
              ContentValues v = new ContentValues();
              v.put(MediaStore.Images.Media.DISPLAY_NAME, base + ext);
              v.put(MediaStore.Images.Media.MIME_TYPE, mime);
              v.put(
                  MediaStore.Images.Media.RELATIVE_PATH,
                  "Pictures/" + getString(R.string.app_name));
              v.put(MediaStore.Images.Media.IS_PENDING, 1);
              uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
              if (uri == null) throw new IOException("无法创建相册条目");
              try (OutputStream out = getContentResolver().openOutputStream(uri);
                  InputStream in = new FileInputStream(cache)) {
                if (out == null) throw new IOException("无法写入相册");
                copy(in, out);
              }
              v.clear();
              v.put(MediaStore.Images.Media.IS_PENDING, 0);
              getContentResolver().update(uri, v, null, null);
            }
            trace.phase("verification_record_ms", "校验并保存导出记录");
            JSONObject recipe = new JSONObject();
            recipe.put("app_version", "0.3.6");
            recipe.put("source_name", name);
            recipe.put("source_sha256", sha256(original));
            recipe.put("source_width", width);
            recipe.put("source_height", height);
            recipe.put("source_raw", raw);
            recipe.put(
                "raw_policy",
                raw
                    ? "LibRaw 0.22.2 compatible16; AHD; as-shot WB; linear ProPhoto RGB"
                    : "Android ImageDecoder to sRGB");
            recipe.put("params", new JSONObject(p));
            recipe.put("product_defaults", true);
            recipe.put("edits", new JSONObject(edits));
            recipe.put("width", dimensions.getInt("width"));
            recipe.put("height", dimensions.getInt("height"));
            recipe.put("color_space", "sRGB");
            recipe.put("bits_per_channel", mode == 1 || mode == 2 ? 16 : 8);
            recipe.put("backend", "native-vulkan");
            recipe.put("renderer_mode", renderer.backend());
            recipe.put("fallback_reason", renderer.fallbackReason());
            recipe.put("native_timings", dimensions.optJSONObject("timings"));
            recipe.put("renderer_capabilities", new JSONObject(renderer.capabilities()));
            recipe.put("export", base + ext);
            recipe.put("output_sha256", sha256(cache));
            recipe.put("presentation_model", "scan-side borders and date");
            File records = new File(getFilesDir(), "exports");
            if (!records.exists() && !records.mkdirs()) throw new IOException("无法保存导出记录");
            byte[] record = recipe.toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream out = new FileOutputStream(new File(records, base + ".json"))) {
              out.write(record);
              out.getFD().sync();
            }
            try (FileOutputStream out =
                new FileOutputStream(new File(getFilesDir(), "last_export.json"))) {
              out.write(record);
              out.getFD().sync();
            }
            trace.finish(null);
            android.util.Log.i("SpektraLensAndroid", "Export complete: " + recipe);
            String label = dimensions.getInt("width") + " × " + dimensions.getInt("height");
            File sharedFile = cache;
            ui(
                () -> {
                  lock(false);
                  setStatus("已导出 · " + label, false);
                  exportButton.setText("导出照片");
                  if (mode == 3) {
                    Uri shared =
                        Uri.parse("content://app.spektrafilm.files/" + sharedFile.getName());
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType(mime);
                    share.putExtra(Intent.EXTRA_STREAM, shared);
                    share.setClipData(ClipData.newRawUri("照片", shared));
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "分享显影照片"));
                  } else
                    new Haptics.DialogBuilder(this)
                        .setTitle("已保存到相册")
                        .setMessage(
                            "Pictures / "
                                + getString(R.string.app_name)
                                + "\n"
                                + label
                                + " · sRGB · "
                                + format.toUpperCase(Locale.US)
                                + "\n\n"
                                + (mode == 2 ? "TIFF 为 16 位文件；部分相册不显示其缩略图，可从文件管理器打开。\n\n" : "")
                                + "包含本次调色、片边与日期。导出配方已保存在本机编辑记录中。")
                        .setNegativeButton("回到首页", (dialog, which) -> showHome())
                        .setPositiveButton(
                            "再编辑一张", (dialog, which) -> importPhoto(exportedSettings))
                        .show();
                });
          } catch (Throwable ex) {
            trace.put("backend_after", renderer.backend());
            trace.put("fallback_reason", renderer.fallbackReason());
            trace.finish(ex);
            if (uri != null) getContentResolver().delete(uri, null, null);
            if (cache != null) cache.delete();
            ui(() -> lock(false));
            report("导出未完成", ex);
          } finally {
            try {
              renderer.closeImage();
            } catch (RuntimeException ex) {
              android.util.Log.w("SpektraLensAndroid", "Renderer already closed", ex);
            }
            openedSource = null;
          }
        });
  }

  private void about() {
    String message =
        getString(R.string.app_name)
            + " · Android 0.3.6\n\n"
            + "本机运行 C++ / Vulkan 光谱胶片模型，使用 380–780 nm、5 nm 间隔的 81 波长数据。\n\n"
            + "原始模型与彩色配置：Andrea Volpato / spektrafilm\n"
            + "原生引擎：Hanze Qiu 与 SpektraLab 贡献者\n"
            + "移动 GPU 参考：SAINTTaiYi / SpektraLab-Android v1.3.3j\n"
            + "Android 客户端与 JNI：本项目移植\n\n"
            + "胶卷图标：SpektraLens 根据各款胶片型号独立绘制。\n\n"
            + "程序：GPL-3.0-or-later\n"
            + "胶片、相纸配置：CC BY-SA 4.0，数据未经修改\n\n"
            + "RAW：LibRaw 0.22.2，原尺寸去马赛克与拍摄白平衡；预览最长边 1024，导出重新读取原文件。输出 sRGB JPEG / 16 位 PNG / 16 位"
            + " TIFF。\n\n"
            + "扫描调色：白平衡偏移、色调、HSL、分区色彩平衡、五组曲线与暗角。片边和日期为扫描后的呈现层，不是桌面版负片曝光模型。\n\n"
            + "极大图仍受设备内存和 Vulkan 限制；未移植场景纬度映射、批量工作流和专业输出色域。\n\n"
            + "这里的光晕与颗粒来自胶片计算；手机屏幕表现和性能仍取决于设备。";
    new Haptics.DialogBuilder(this)
        .setTitle("关于 " + getString(R.string.app_name))
        .setMessage(message)
        .setPositiveButton("完成", null)
        .setNeutralButton("许可全文", (d, n) -> licenses())
        .setNegativeButton("处理诊断", (d, n) -> showDiagnostics())
        .show();
  }

  private void licenses() {
    try {
      String[] names = getAssets().list("licenses");
      new Haptics.DialogBuilder(this)
          .setTitle("许可与署名")
          .setItems(
              names,
              (d, n) -> {
                try {
                  ScrollView s = new ScrollView(this);
                  TextView t = text(readAsset("licenses/" + names[n]), AppStyle.TYPE_LABEL, INK);
                  t.setPadding(dp(20), dp(16), dp(20), dp(16));
                  s.addView(t);
                  new Haptics.DialogBuilder(this)
                      .setTitle(names[n])
                      .setView(s)
                      .setPositiveButton("关闭", null)
                      .show();
                } catch (IOException e) {
                  Toast.makeText(this, e.toString(), Toast.LENGTH_LONG).show();
                }
              })
          .setPositiveButton("关闭", null)
          .show();
    } catch (IOException ex) {
      Toast.makeText(this, ex.toString(), Toast.LENGTH_LONG).show();
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (flowingBackground != null) flowingBackground.setHostResumed(true);
    if (gallerySheet != null && gallerySheet.isShowing()) gallerySheet.refresh();
  }

  @Override
  protected void onPause() {
    if (flowingBackground != null) flowingBackground.setHostResumed(false);
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (flowingBackground != null) flowingBackground.setHostResumed(false);
    if (gallerySheet != null) gallerySheet.dismiss();
    if (backCallback != null && Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
      backCallback = null;
    }
    destroyed = true;
    if (homeImages != null) homeImages.close();
    if (activeImport != null) {
      activeImport.cancel();
      activeImport = null;
    }
    importWorker.shutdownNow();
    revision++;
    ++engineBootGeneration;
    main.removeCallbacksAndMessages(null);
    SpectralRenderer current = renderer;
    if (current != null) current.close();
    engine = 0;
    worker.shutdown();
    super.onDestroy();
  }
}
