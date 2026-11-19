// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.annotation.SuppressLint;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.view.*;
import android.view.animation.PathInterpolator;
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
      GOLD = AppStyle.GOLD,
      LINE = AppStyle.OUTLINE;
  /** 面板下沿的渐隐高度（dp）：提示「下面还有」，滚到底就淡出。 */
  static final int FADE_ROW_DP = 24;
  /** 面板的最大高度（dp）。预览区按这个值预留，所以它也决定照片能有多大。 */
  static final int PANEL_MAX_DP = 320;
  private static final long PANEL_MOTION_MS = 200, TOOL_MOTION_MS = 140;
  private static final int MAX_BATCH_IMPORT = 20;
  private static final PathInterpolator EDITOR_EASING = new PathInterpolator(.2f, 0f, 0f, 1f);
  private static final int GALLERY_SOURCE_EDGE = 1024;
  private static final long GALLERY_CACHE_BYTES = 16L * 1024 * 1024;
  private UpdateController updates;
  private UpdateBadge homeAbout;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService thumbnailIo = Executors.newSingleThreadExecutor();
  private final ExecutorService bootStateIo = Executors.newSingleThreadExecutor();
  private final ExecutorService libraryWriter = Executors.newSingleThreadExecutor();
  private final Object libraryFileLock = new Object();
  private final java.util.concurrent.atomic.AtomicInteger libraryWritesPending =
      new java.util.concurrent.atomic.AtomicInteger();
  private volatile long libraryWrittenVersion;
  private long libraryWriteVersion;
  private volatile boolean libraryDirty;
  private Runnable libraryWritePending;
  private final ThumbnailWorkQueue homeThumbJobs = new ThumbnailWorkQueue();
  private final PendingBitmaps thumbnailDeliveries = new PendingBitmaps();
  private volatile boolean homeThumbPaused = true;
  private volatile int mainPreviewRevision = -1;
  private String developedPhotoId, developedRecipe;
  private Bitmap storedHomeLook;
  private String storedHomeLookId;
  private final ExecutorService importWorker = Executors.newFixedThreadPool(2);
  private volatile PhotoImportJob activeImport;
  private volatile long photoLoadGeneration;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ArrayList<View> controls = new ArrayList<>();
  private final ArrayList<Stock> films = new ArrayList<>(), papers = new ArrayList<>();
  private JSONObject settings = new JSONObject();
  private SharedPreferences prefs;
  private LinearLayout root, editor, header, statusRow, editorBody, previewColumn, toolsColumn;
  private FrameLayout photoStage;
  private OnBackInvokedCallback backCallback;
  private boolean curveHintShown;
  private ScrollView panelScroll;
  private View panelFade;
  /** 预览区与工具列之间的弹性间隔：把工具列（面板 + 导航）推到底，贴住屏幕下沿。 */
  private View panelSpacer;
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
  // 画廊只展示原片的默认胶片风格；二次调节仅影响主预览，不使画廊缓存失效。
  private final LinkedHashMap<String, Bitmap> filmThumbs =
      new LinkedHashMap<>(32, .75f, true);
  private final Set<String> thumbQueued = new HashSet<>();
  private String thumbPrint = "";
  private volatile int thumbGeneration;
  private boolean thumbBusy;
  private ThumbnailDiskCache filmDiskCache;
  private String filmCacheResources = "";
  private final Set<String> thumbDiskChecked = new HashSet<>();
  private final Map<String, Integer> thumbDiskPending = new HashMap<>();
  private final Map<String, String> thumbStockDiskKeys = new HashMap<>();
  private final Set<String> removedGalleryPhotos = ConcurrentHashMap.newKeySet();
  private final java.util.concurrent.atomic.AtomicInteger thumbWrites =
      new java.util.concurrent.atomic.AtomicInteger();
  private volatile long thumbRenderCount, homeThumbRenderCount;
  private long thumbDiskHits;
  private final Set<String> thumbFailed = new HashSet<>();
  private int previewWorkTicket = -1;
  // 面板每次重建都会换掉 ImageView，所以渲染完成时不能只认建时的那个引用。
  private final Map<String, ImageView> thumbTargets = new HashMap<>();
  // 面板重建后引擎可能还没就绪，缩略图请求会被跳过；就绪/渲染完成后要能再补一次。
  private HorizontalScrollView filmScrollRef;
  private List<ImageView> thumbViewsRef = new ArrayList<>();
  private List<String> thumbStocksRef = new ArrayList<>();
  private int thumbStridePx, thumbSizePx;
  // 画廊的滚动位置按「分类」各自记忆：切到某分类时回到它上次停留的位置，没动过手就从第一张
  // 开始。每导入一张新照片清空一次，并从「全部」第一张重新开始。
  // pendingFilmScrollX 由「点卡片 / 点分类 / 换照片」显式指定，-1 表示本次没有指定。
  private String filmSessionKey = "";
  private final Map<String, Integer> categoryScrollX = new HashMap<>();
  private int pendingFilmScrollX = -1;
  private int paperGradeScrollX;
  private int engineBootGeneration;
  private long engineBootStarted;
  private boolean engineBootPending;
  private String engineBootStage = "连接计算进程";
  private boolean bootStateReading;
  private Bitmap openedSource;
  private boolean ready, comparing;
  private volatile boolean exporting, batchImportRunning;
  private String filename = "胶片边缘 · 示例照片";
  private Runnable pending;
  private final Map<String, SeekBar> sliders = new HashMap<>();
  private final Map<String, TextView> values = new HashMap<>();
  private FrameLayout screens;
  private AboutPage aboutPage;
  private SponsorPage sponsorPage;
  private FlowingBackgroundView flowingBackground;
  private LinearLayout homeScreen, editorScreen, panel, tabRow, filmCards, historyList;
  private FrameLayout homeStage;
  private LinearLayout homeContent;
  private ImageView homeHero;
  private TextView homeImportCancel;
  private ImportButtonLayout homeImportSlot;
  private ImportProgressDrawable homeImportProgress;
  private HomePhotoImages homeImages;
  private TextView homeImportButton, homeImportHint, batchPasteBar, manageDeleteBar, homeManage;
  private View manageActionRow;
  private TextView historyTitle, homeSelectAll, homeClearSelection;
  private LinearLayout manageHeaderActions;
  private final LinkedHashSet<String> manageSelection = new LinkedHashSet<>();
  private boolean manageMode;
  private FrameLayout batchPasteShell;
  private final Map<String, BatchCardViews> batchCardViews = new HashMap<>();
  private GallerySheet gallerySheet;
  private LibraryActionSheet frameActions;
  private ExportSheet exportSheet;
  private int selectedTab;
  private boolean panelCollapsed;
  private ValueAnimator panelAnimator;
  private String filmCategory = "全部";
  private final ArrayList<Frame> library = new ArrayList<>();
  private final LinkedHashSet<String> batchSelection = new LinkedHashSet<>();
  private String activeId = "demo";
  private String editorPhotoId = "";
  private final ArrayDeque<String> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
  private String committed;
  private TextView undoButton, redoButton;
  private TextView recentCount;
  /** 上一次渲染历史列表时的内容指纹，用来跳过无意义的重建。 */
  private String historySignature;
  private boolean batchPasteMode;
  private volatile int batchImportGeneration;
  private boolean editorNeedsSync;
  private int homeExportGeneration;

  private static final class Frame {
    String id, name, path, params;
    boolean favorite;
    long sourceTimestamp, importedAt;
    String sourceMediaId = "";
    boolean edited;

    Frame(String i, String n, String p, String s) {
      id = i;
      name = n;
      path = p;
      params = s;
      importedAt = System.currentTimeMillis();
    }
  }

  private static final class EditClipboard {
    final String sourceId, sourceName, params;

    EditClipboard(String sourceId, String sourceName, String params) {
      this.sourceId = sourceId;
      this.sourceName = sourceName;
      this.params = params;
    }

    boolean valid() {
      return sourceId != null
          && !sourceId.isBlank()
          && params != null
          && !params.isBlank();
    }
  }

  private static final class BatchCardViews {
    final View photoBox, scrim;
    final ImageView check;
    final FrameLayout.LayoutParams favoriteParams;

    BatchCardViews(
        View photoBox,
        View scrim,
        FrameLayout.LayoutParams favoriteParams,
        ImageView check) {
      this.photoBox = photoBox;
      this.scrim = scrim;
      this.favoriteParams = favoriteParams;
      this.check = check;
    }
  }

  private static final class PendingImport {
    final String id, name, path;
    final long sourceTimestamp, mediaId;

    PendingImport(String id, String name, String path, long sourceTimestamp, long mediaId) {
      this.id = id;
      this.name = name;
      this.path = path;
      this.sourceTimestamp = sourceTimestamp;
      this.mediaId = mediaId;
    }
  }

  static final class Stock {
    String id, name, type, target, antihalation;
    boolean monochrome;

    Stock(JSONObject j) {
      id = j.optString("stock");
      name = j.optString("name");
      type = j.optString("type");
      target = j.optString("target_print");
      antihalation = j.optString("antihalation");
      monochrome = j.optBoolean("monochrome");
    }
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Haptics.install(getWindow());
    filmDiskCache = new ThumbnailDiskCache(new File(getCacheDir(), "film-thumbs-v1"),
        64L * 1024 * 1024);
    thumbnailIo.execute(filmDiskCache::sweepPending);
    updates = new UpdateController(this);
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
    updates.setAvailabilityListener(this::refreshUpdateNotice);
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
    engineBootStage = "连接计算进程";
    bootStateReading = false;
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
            String resourceKey = android.util.Base64.encodeToString(
                java.nio.file.Files.readAllBytes(new File(resources, ".ready").toPath()),
                android.util.Base64.NO_WRAP)
                + ":" + getPackageManager().getPackageInfo(getPackageName(), 0).lastUpdateTime;
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
                  filmCacheResources = resourceKey;
                  ready = true;
                  refillThumbs();
                  pumpHomeThumbnails();
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
                      photo.images(source, null);
                      // 恢复会话时首页大图也要用显影后的样子（卡片在 refreshHistory 里自带）。
                      syncHeroLook();
                    } else Bitmaps.release(first.bitmap);
                  }
                  if (activeImport == null) {
                    if (source != null && (editorScreen.getVisibility() == View.VISIBLE || mainPreviewPending()))
                      scheduleRender();
                    else setStatus("已准备好 · 选择照片开始显影", false);
                  }
                  queueMissingHomeThumbnails();
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
      if (current != null && !bootStateReading) {
        bootStateReading = true;
        int pid = current.processId();
        String backend = current.backend();
        try {
          bootStateIo.execute(
              () -> {
                String stage = readRendererStage(backend, pid);
                ui(
                    () -> {
                      bootStateReading = false;
                      if (destroyed
                          || boot != engineBootGeneration
                          || ready
                          || !engineBootPending
                          || activeImport != null) return;
                      if (stage != null) engineBootStage = stage;
                      showBootStatus(mode, boot);
                    });
              });
        } catch (RuntimeException ex) {
          bootStateReading = false;
        }
      }
      showBootStatus(mode, boot);
    }
    main.postDelayed(() -> watchRendererBoot(boot), 500);
  }

  private void showBootStatus(String mode, int boot) {
    if (destroyed || boot != engineBootGeneration || ready || !engineBootPending) return;
    setStatus(
        "准备"
            + mode
            + " · "
            + engineBootStage
            + " · "
            + ((SystemClock.elapsedRealtime() - engineBootStarted) / 1000)
            + " 秒",
        true);
    exportButton.setText("查看显影诊断");
    exportButton.setEnabled(true);
    exportButton.setOnClickListener(v -> showDiagnostics());
  }

  private String readRendererStage(String backend, int pid) {
    File state = new File(getFilesDir(), "engine-" + backend + ".json");
    try (InputStream in = new FileInputStream(state);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      copy(in, out);
      JSONObject j = new JSONObject(out.toString("UTF-8"));
      if (j.optInt("pid") != pid) return null;
      String key = j.optString("stage");
      if (key.equals("spectral_resources")) return "加载胶片与印相数据";
      if (key.startsWith("vkCreateComputePipelines")) return "编译计算自检程序";
      if (key.startsWith("vkWait") || key.startsWith("vkQueue")) return "执行计算自检";
      if (key.equals("vkCreateDevice")) return "建立计算设备";
      if (key.equals("ready")) return "恢复照片预览";
      return "加载计算驱动";
    } catch (Exception ignored) {
      return null;
    }
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

  /** The unedited-photo default is C200 even when new stocks sort ahead of it. */
  private String defaultFilmStock() {
    return "fujifilm_c200";
  }

  private void defaults() {
    putDefault("film_stock", defaultFilmStock());
    putDefault("print_stock", "kodak_portra_endura");
    putDefault("exposure_compensation_ev", 0);
    putDefault("print_brightness", 0);
    putDefault("m_filter_shift", 0);
    putDefault("y_filter_shift", 0);
    putDefault("camera_filter", "");
    putDefault("paper_grade", FilmStockPolicy.DEFAULT_PAPER_GRADE);
    putDefault("halation_headroom_ev", 4);
    // 初始化与单项复位共用同一份默认值，非零默认值不能按 0 处理。
    for (String key : new String[] {
      "grain_amount", "halation_amount", "glare_amount", "dir_couplers_amount",
      "halation_scatter_amount", "density_curve_gamma", "grade_vignette_midpoint",
      "halation_headroom_ev", "edge_amount", "edge_frame_number", "date_size", "date_intensity"
    }) putDefault(key, sliderDefault(key));
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
    putDefault("geo_w", 1);
    putDefault("geo_h", 1);
    putDefault("edge_style", "none");
    putDefault("date_text", new SimpleDateFormat("yyyy.MM.dd", Locale.US).format(new Date()));
    putDefault("date_corner", "bottom_right");
    putDefault("date_color", "orange");
    normalizePaper();
  }

  private void putDefault(String k, Object v) {
    if (!settings.has(k)) set(k, v);
  }

  private static double sliderDefault(String key) {
    return switch (key) {
      case "grain_amount", "halation_amount", "glare_amount", "dir_couplers_amount",
          "halation_scatter_amount", "density_curve_gamma", "date_size", "date_intensity" -> 1;
      case "halation_headroom_ev" -> 4;
      case "grade_vignette_midpoint" -> 50;
      case "edge_amount" -> 6;
      case "edge_frame_number" -> 36;
      default -> 0;
    };
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
      if (k.equals("print_stock")
          && !FilmStockPolicy.canPrint(settings.optString("film_stock"), String.valueOf(v))) return;
      settings.put(k, v);
      if (k.equals("film_stock") || k.equals("print_stock")) normalizePaper();
    } catch (JSONException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String category(Stock stock) {
    return stock == null
        ? "全部"
        : stock.monochrome
            ? "黑白"
            : stock.type.equals("positive")
            ? "反转片"
            : stock.id.contains("vision3")
                    || stock.id.contains("verita")
                    || FilmStockPolicy.cinestill800t(stock.id)
                ? "电影胶片"
                : "彩色负片";
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
    AppStyle.setTypeface(v, AppStyle.FONT_REGULAR);
    return v;
  }

  private GradientDrawable box(int color, float radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(radius));
    return d;
  }

  private TextView button(String title, int color, int background) {
    TextView v = text(title, AppStyle.TYPE_BODY, color);
    v.setGravity(Gravity.CENTER);
    // 按钮里折行必然压到上下留白，所以默认锁单行，放不下就用省略号。
    v.setSingleLine(true);
    v.setEllipsize(android.text.TextUtils.TruncateAt.END);
    v.setPadding(dp(14), dp(8), dp(14), dp(8));
    AppStyle.pillAction(
        v,
        color,
        background == BG ? Color.TRANSPARENT : background);
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
    TextView a = text(index, AppStyle.TYPE_LABEL, GOLD);
    AppStyle.setNumericTypeface(a);
    add(r, a, 33, 28);
    TextView b = text(name, AppStyle.TYPE_HEADING, INK);
    AppStyle.setTypeface(b, AppStyle.FONT_BOLD);
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
    AppStyle.setTypeface(title, AppStyle.FONT_BOLD);
    title.setSingleLine(true);
    title.setEllipsize(android.text.TextUtils.TruncateAt.END);
    FrameLayout.LayoutParams titleLayout = new FrameLayout.LayoutParams(-1, dp(40), Gravity.CENTER);
    // Equal insets reserve the wider action on both sides, so the title stays on the screen axis.
    titleLayout.leftMargin = titleLayout.rightMargin = dp(94);
    toolbar.addView(title, titleLayout);
    exportButton = button("显影准备中…", AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    exportButton.setTextSize(AppStyle.TYPE_LABEL);
    exportButton.setPadding(dp(8), 0, dp(8), 0);
    exportButton.setSingleLine(true);
    AppStyle.setTypeface(exportButton, AppStyle.FONT_BOLD);
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
    // 展开时各工具的预览高度一致；主动收起面板时把释放的空间交给照片。
    // 弹性间隔只吸收像素取整余量，保证底部导航贴底。
    panelSpacer = new View(this);
    editorBody.addView(previewColumn);
    editorBody.addView(panelSpacer, new LinearLayout.LayoutParams(-1, 0, 1));
    editorBody.addView(toolsColumn);
    // 预览区高度是从编辑区高度推出来的，编辑区一变（旋转、窗口尺寸、系统栏变化）就重算。
    editorBody.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          if (bottom - top != oldBottom - oldTop) updateEditorLayout();
        });
    photoStage = new FrameLayout(this);
    previewColumn.addView(photoStage, new LinearLayout.LayoutParams(-1, 0, 1));
    photo = new PhotoView(this);
    photo.setOnClickListener(v -> togglePanelFromPreview());
    photoStage.addView(photo, new FrameLayout.LayoutParams(-1, -1));
    statusRow = horizontal();
    statusRow.setPadding(dp(18), 0, dp(18), 0);
    progress = new ProgressBar(this);
    add(statusRow, progress, 12, 12);
    add(statusRow, new View(this), 7, 1);
    status = text("准备中", AppStyle.TYPE_MICRO, MUTED);
    AppStyle.setNumericTypeface(status);
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
    tabRow.setPadding(dp(5), dp(4), dp(5), dp(4));
    tabRow.setBackground(AppStyle.pill(this, AppStyle.SURFACE, false));
    tabRow.setClipToOutline(true);
    panel = vertical();
    AppStyle.surface(panel, AppStyle.SURFACE, AppStyle.R_LARGE, false);
    panel.setElevation(0);
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
    cancelPanelAnimation();
    finishToolAnimations();
    panel.setAlpha(1f);
    panel.setTranslationY(0f);
    fixPanelContentHeight(false);
    boolean wide = wideEditor() && !panelCollapsed;
    editorBody.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
    panelSpacer.setVisibility(wide ? View.GONE : View.VISIBLE);
    panel.setVisibility(panelCollapsed ? View.GONE : View.VISIBLE);
    if (wide) {
      // 横屏平板：预览和工具列左右分栏，各占一份权重。
      previewColumn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1.1f));
      toolsColumn.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1));
    } else {
      // 收起时横屏也切为上下布局：照片使用全宽，导航仍在底部。
      previewColumn.setLayoutParams(new LinearLayout.LayoutParams(-1, previewHeightPx()));
      toolsColumn.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
    }
    updatePanelHeight();
  }

  /** 只取消时间轴，保留当前帧尺寸和透明度，供快速反向点击接着播放。 */
  private void cancelPanelAnimation() {
    ValueAnimator previous = panelAnimator;
    panelAnimator = null;
    if (previous == null) return;
    // cancel() 也会发送 onAnimationEnd；移除旧监听，避免它把新动画吸到旧终点。
    previous.removeAllListeners();
    previous.removeAllUpdateListeners();
    previous.cancel();
  }

  private void fixPanelContentHeight(boolean animating) {
    if (panel.getChildCount() == 0) return;
    View content = panel.getChildAt(0);
    LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) content.getLayoutParams();
    // 外层负责裁切收放，内部 ScrollView 保持完整高度，避免视口逐帧变小导致滚动跳动。
    layout.height = animating ? dp(panelHeight()) : 0;
    layout.weight = animating ? 0 : 1;
    content.setLayoutParams(layout);
  }

  private void animatePanel() {
    cancelPanelAnimation();
    // 左右分栏切换涉及重排；旋转、小窗口初次布局和系统关闭动画时直接落到最终布局。
    if (wideEditor() || !editorScreen.isShown() || editorBody.getHeight() <= 0
        || !panel.isLaidOut() || !ValueAnimator.areAnimatorsEnabled()) {
      updateEditorLayout();
      refillRevealedPanel();
      return;
    }
    LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) panel.getLayoutParams();
    boolean visible = panel.getVisibility() == View.VISIBLE;
    final int fromHeight = visible ? Math.max(0, layout.height) : 0;
    final int fromMargin = visible ? layout.topMargin : 0;
    final int toHeight = panelCollapsed ? 0 : dp(panelHeight());
    final int toMargin = panelCollapsed ? 0 : dp(4);
    if (fromHeight == toHeight) {
      updateEditorLayout();
      refillRevealedPanel();
      return;
    }
    final float fromAlpha = visible ? panel.getAlpha() : 0f;
    final float fromShift = visible ? panel.getTranslationY() : dp(8);
    final float toAlpha = panelCollapsed ? 0f : 1f;
    final float toShift = panelCollapsed ? dp(8) : 0f;
    fixPanelContentHeight(true);
    panel.setVisibility(View.VISIBLE);
    layout.weight = 0;
    ValueAnimator animation = ValueAnimator.ofFloat(0f, 1f);
    panelAnimator = animation;
    // 反向接续只走剩余路程，不再让短短几像素花满 200ms。
    animation.setDuration(
        Math.max(80, PANEL_MOTION_MS * Math.abs(toHeight - fromHeight) / dp(panelHeight())));
    animation.setInterpolator(EDITOR_EASING);
    animation.addUpdateListener(
        value -> {
          float fraction = (Float) value.getAnimatedValue();
          layout.height = Math.round(fromHeight + (toHeight - fromHeight) * fraction);
          layout.topMargin = Math.round(fromMargin + (toMargin - fromMargin) * fraction);
          panel.setLayoutParams(layout);
          LinearLayout.LayoutParams preview =
              (LinearLayout.LayoutParams) previewColumn.getLayoutParams();
          // 共用一次空间预算，连 4dp 顶距也一起收放，底部导航不会上下浮动。
          preview.height =
              Math.max(0, editorBody.getHeight() - dp(58) - dp(20)
                  - layout.height - layout.topMargin);
          previewColumn.setLayoutParams(preview);
          panel.setAlpha(fromAlpha + (toAlpha - fromAlpha) * fraction);
          panel.setTranslationY(fromShift + (toShift - fromShift) * fraction);
        });
    animation.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator ended) {
            if (panelAnimator != ended) return;
            panelAnimator = null;
            updateEditorLayout();
            refillRevealedPanel();
          }
        });
    animation.start();
  }

  private void refillRevealedPanel() {
    if (!panelCollapsed)
      panel.post(
          () -> {
            if (!destroyed && !panelCollapsed && panelAnimator == null) {
              updatePanelFade();
              refillThumbs();
            }
          });
  }

  private void finishToolAnimations() {
    if (tabRow == null) return;
    for (int i = 0; i < tabRow.getChildCount(); i++) {
      View tab = tabRow.getChildAt(i);
      tab.animate().cancel();
      tab.setAlpha(1f);
      tab.setScaleX(1f);
      tab.setScaleY(1f);
    }
  }

  private void animateSelectedTool() {
    if (!editorScreen.isShown() || !ValueAnimator.areAnimatorsEnabled()) return;
    for (int i = 0; i < tabRow.getChildCount(); i++) {
      View tab = tabRow.getChildAt(i);
      if (!tab.isSelected()) continue;
      tab.setAlpha(.7f);
      tab.setScaleX(.96f);
      tab.setScaleY(.96f);
      tab.animate().alpha(1f).scaleX(1f).scaleY(1f)
          .setDuration(TOOL_MOTION_MS).setInterpolator(EDITOR_EASING).start();
    }
  }

  /**
   * 预览区（照片 + 工具行）高度的像素值：编辑区扣掉导航 58 及其两侧留白 20；
   * 面板展开时再扣掉顶距 4 与统一面板高度。
   *
   * <p>各工具在展开状态尺寸相同，只有用户主动收起面板才扩大照片区。
   */
  private int previewHeightPx() {
    if (editorBody == null || editorBody.getHeight() <= 0) return 0;
    int tools = dp(58) + dp(20);
    if (!panelCollapsed) tools += dp(4) + dp(panelMaxHeight());
    // 不设下限：极小窗口（分屏、悬浮窗）里面板本身已经按 panelLimit 缩过，再把这里垫高
    // 会把工具列顶出编辑区。宁可让照片区先让位——这和之前 weight=1 时的行为一致。
    return Math.max(0, editorBody.getHeight() - tools);
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
   * 面板的最大高度（dp）：320，但不超过屏幕能给的绝对上限。
   *
   * <p>这个值同时决定预览区能有多大（见 {@link #previewHeightPx()}），所以它一变，照片就
   * 跟着变——它只跟屏幕有关，不跟页面内容有关。
   */
  private int panelMaxHeight() {
    return Math.min(PANEL_MAX_DP, panelLimit());
  }

  /**
   * 面板高度（dp）：固定 {@link #panelMaxHeight()}，和当前页内容无关。
   *
   * <p>九个编辑页一个高度，切 tab、切分段、换通道都不改变它。内容超出就在面板内滚动。
   * 再点当前底部工具可整体隐藏面板，不引入随内容高矮变化的第三种高度。
   */
  private int panelHeight() {
    return panelMaxHeight();
  }

  /**
   * 曲线页的图表高度（dp）：面板预算扣掉图表以外的行，剩下的全给图表。
   * 曲线页正文 = 顶部内边距 10 + 分类 38 + 间距 8 + 启用开关 46 + 通道行 36 + 图表
   * + 底部内边距 10，图表 = 320 − 150 − 2 = 168（2dp 是逐项 dp→px 取整的余量）。
   */
  private int curveChartHeight() {
    return Math.min(215, Math.max(150, panelMaxHeight() - 150));
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
   * 底部渐隐只在「下面还有内容」时出现，直接读滚动能力，与面板高度无关：
   * 装得下的页（胶片、曲线、暗角）永远是灭的，长页滚到底才灭。
   */
  private void updatePanelFade() {
    if (panelFade == null || panelScroll == null) return;
    panelFade.setAlpha(panelScroll.canScrollVertically(1) ? 1f : 0f);
  }

  private void updatePanelHeight() {
    if (panel == null) return;
    LinearLayout.LayoutParams layout = (LinearLayout.LayoutParams) panel.getLayoutParams();
    boolean wide = wideEditor() && !panelCollapsed;
    layout.height = wide ? 0 : dp(panelHeight());
    layout.weight = wide ? 1 : 0;
    layout.topMargin = dp(4);
    panel.requestLayout();
    // 换页、换屏之后可视区可能变，「下面还有没有」得重新判一次。
    panel.post(this::updatePanelFade);
  }

  private void buildHome() {
    if (homeImages != null) homeImages.close();
    if (homeScreen != null) screens.removeView(homeScreen);
    homeScreen = vertical();
    screens.addView(homeScreen, new FrameLayout.LayoutParams(-1, -1));
    homeStage = new FrameLayout(this);
    homeScreen.addView(homeStage, new LinearLayout.LayoutParams(-1, 0, 1));
    ScrollView scroll = new ScrollView(this);
    homeStage.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    homeImages = new HomePhotoImages(this, main, scroll, worker);
    homeImages.setRenderDemandListener(this::pumpHomeThumbnails);
    if (ready) homeImages.setRenderer(renderer);
    LinearLayout content = homeContent = vertical();
    content.setPadding(dp(20), dp(20), dp(20), dp(28));
    scroll.setVerticalScrollBarEnabled(false);
    scroll.addView(content);
    LinearLayout head = horizontal();
    LinearLayout branding = vertical();
    TextView logo = text(getString(R.string.app_name), AppStyle.TYPE_BRAND, INK);
    AppStyle.setTypeface(logo, AppStyle.FONT_SEMIBOLD);
    logo.setLetterSpacing(-.035f);
    add(branding, logo, -1, 44);
    TextView cn = text("暗房里没有过去，只有正在发生的影像", AppStyle.TYPE_LABEL, MUTED);
    cn.setLineSpacing(dp(2), 1);
    add(branding, cn, -1, -2);
    head.addView(branding, new LinearLayout.LayoutParams(0, -2, 1));
    TextView help = button("关于", MUTED, BG);
    help.setTextSize(AppStyle.TYPE_LABEL);
    help.setPadding(0, 0, 0, 0);
    help.setSingleLine(true);
    help.setMinWidth(dp(42));
    help.setContentDescription("关于、许可与支持范围");
    help.setOnClickListener(
        v -> {
          if (!manageMode) about();
        });
    homeAbout = new UpdateBadge(help, "new");
    controls.remove(help);
    controls.add(homeAbout);
    homeAbout.setTag("home.about");
    homeAbout.setAvailable(updates.hasAvailableUpdate());
    add(head, homeAbout, -2, -2);
    add(content, head, -1, -2);
    gap(content, 24);
    FrameLayout hero = new FrameLayout(this);
    AppStyle.surface(hero, AppStyle.SURFACE, AppStyle.R_MEDIUM);
    hero.setClipToOutline(true);
    homeHero = new ImageView(this);
    homeHero.setScaleType(ImageView.ScaleType.CENTER_CROP);
    homeHero.setAdjustViewBounds(false);
    homeHero.setContentDescription("最近导入的照片预览");
    if (source != null && library.isEmpty()) homeHero.setImageBitmap(source);
    hero.addView(homeHero, new FrameLayout.LayoutParams(-1, -1));
    bindHomeHeroToRecent();
    add(content, hero, -1, 240);
    gap(content, 16);
    homeImportButton = button("开始导入照片", AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    homeImportButton.setTextSize(AppStyle.TYPE_TITLE);
    AppStyle.setTypeface(homeImportButton, AppStyle.FONT_BOLD);
    homeImportButton.setOnClickListener(v -> importPhoto());
    homeImportProgress = new ImportProgressDrawable(this);
    homeImportCancel = button("取消", INK, BG);
    homeImportCancel.setTextSize(AppStyle.TYPE_MICRO);
    AppStyle.setTypeface(homeImportCancel, AppStyle.FONT_MEDIUM);
    homeImportCancel.setPadding(dp(4), 0, dp(4), 0);
    homeImportCancel.setContentDescription("取消照片导入");
    homeImportCancel.setOnClickListener(v -> cancelBatchImport());
    homeImportCancel.setVisibility(View.GONE);
    homeImportSlot = new ImportButtonLayout(this, homeImportButton, homeImportCancel);
    add(content, homeImportSlot, -1, -2);
    gap(content, 12);
    TextView privacy =
        text("JPG / PNG / HEIC / HEIF / DNG\n本机处理 · 自动保存调整 · 保留原照片", AppStyle.TYPE_MICRO, MUTED);
    privacy.setLineSpacing(dp(4), 1);
    add(content, privacy, -1, 54);
    homeImportHint = text("", AppStyle.TYPE_MICRO, MUTED);
    homeImportHint.setLineSpacing(dp(3), 1);
    homeImportHint.setVisibility(View.GONE);
    LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
    hintParams.topMargin = dp(7);
    content.addView(homeImportHint, hintParams);
    gap(content, 24);
    LinearLayout historyHead = horizontal();
    historyTitle = text("最近照片", AppStyle.TYPE_HEADING, INK);
    AppStyle.setNumericTypeface(historyTitle);
    AppStyle.setTypeface(historyTitle, AppStyle.FONT_BOLD);
    historyTitle.setMaxLines(1);
    historyHead.addView(historyTitle, new LinearLayout.LayoutParams(0, dp(40), 1));
    homeManage = text("照片管理", AppStyle.TYPE_BODY, GOLD);
    AppStyle.setTypeface(homeManage, AppStyle.FONT_MEDIUM);
    homeManage.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
    homeManage.setPadding(dp(14), 0, 0, 0);
    homeManage.setMinHeight(dp(44));
    homeManage.setContentDescription("照片管理，选择并删除照片");
    homeManage.setOnClickListener(v -> enterManageMode());
    add(historyHead, homeManage, -2, 44);
    manageHeaderActions = horizontal();
    manageHeaderActions.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
    homeSelectAll = text("全选", AppStyle.TYPE_BODY, GOLD);
    AppStyle.setTypeface(homeSelectAll, AppStyle.FONT_MEDIUM);
    homeSelectAll.setGravity(Gravity.CENTER);
    homeSelectAll.setPadding(dp(14), 0, 0, 0);
    homeSelectAll.setMinHeight(dp(44));
    homeSelectAll.setContentDescription("全选照片");
    homeSelectAll.setOnClickListener(v -> selectAllPhotos());
    manageHeaderActions.addView(homeSelectAll, new LinearLayout.LayoutParams(-2, dp(44)));
    homeClearSelection = text("取消选择", AppStyle.TYPE_BODY, GOLD);
    AppStyle.setTypeface(homeClearSelection, AppStyle.FONT_MEDIUM);
    homeClearSelection.setGravity(Gravity.CENTER);
    homeClearSelection.setPadding(dp(18), 0, 0, 0);
    homeClearSelection.setMinHeight(dp(44));
    homeClearSelection.setContentDescription("取消选择全部照片");
    homeClearSelection.setOnClickListener(v -> clearManageSelection());
    manageHeaderActions.addView(homeClearSelection, new LinearLayout.LayoutParams(-2, dp(44)));
    manageHeaderActions.setVisibility(View.GONE);
    add(historyHead, manageHeaderActions, -2, 44);
    add(content, historyHead, -1, -2);
    gap(content, 9);
    historyList = vertical();
    add(content, historyList, -1, -2);
    batchPasteBar = button(EditClipboardPolicy.batchButtonLabel(0), AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    batchPasteBar.setTextSize(AppStyle.TYPE_BODY);
    AppStyle.setTypeface(batchPasteBar, AppStyle.FONT_BOLD);
    batchPasteBar.setEnabled(false);
    batchPasteBar.setOnClickListener(v -> pasteToBatch());
    manageDeleteBar = button("删除", AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    manageDeleteBar.setTextSize(AppStyle.TYPE_BODY);
    AppStyle.setTypeface(manageDeleteBar, AppStyle.FONT_BOLD);
    manageDeleteBar.setEnabled(false);
    manageDeleteBar.setOnClickListener(v -> confirmManageDelete());
    batchPasteShell = new FrameLayout(this);
    AppStyle.surface(
        batchPasteShell, AppStyle.withAlpha(AppStyle.SURFACE, .96f), AppStyle.R_MEDIUM);
    FrameLayout barStack = new FrameLayout(this);
    LinearLayout batchActions = new LinearLayout(this);
    batchActions.setOrientation(LinearLayout.HORIZONTAL);
    batchActions.setGravity(Gravity.CENTER_VERTICAL);
    batchActions.setPadding(dp(14), dp(8), dp(14), dp(14));
    TextView batchCancel = button("取消", INK, AppStyle.CONTROL);
    batchCancel.setGravity(Gravity.CENTER);
    batchCancel.setPadding(dp(12), 0, dp(12), 0);
    batchCancel.setOnClickListener(v -> exitBatchPaste());
    batchActions.addView(
        batchCancel, new LinearLayout.LayoutParams(0, dp(AppStyle.TOUCH_PRIMARY), 1));
    batchPasteBar.setPadding(dp(12), 0, dp(12), 0);
    LinearLayout.LayoutParams pasteParams =
        new LinearLayout.LayoutParams(0, dp(AppStyle.TOUCH_PRIMARY), 1);
    pasteParams.leftMargin = dp(12);
    batchActions.addView(batchPasteBar, pasteParams);
    LinearLayout manageActions = new LinearLayout(this);
    manageActions.setOrientation(LinearLayout.HORIZONTAL);
    manageActions.setGravity(Gravity.CENTER_VERTICAL);
    manageActions.setPadding(dp(14), dp(8), dp(14), dp(14));
    TextView manageCancel = button("取消", INK, AppStyle.CONTROL);
    manageCancel.setGravity(Gravity.CENTER);
    manageCancel.setPadding(dp(12), 0, dp(12), 0);
    manageCancel.setOnClickListener(v -> exitManageMode());
    manageActions.addView(
        manageCancel, new LinearLayout.LayoutParams(0, dp(AppStyle.TOUCH_PRIMARY), 1));
    manageDeleteBar.setPadding(dp(12), 0, dp(12), 0);
    LinearLayout.LayoutParams deleteParams =
        new LinearLayout.LayoutParams(0, dp(AppStyle.TOUCH_PRIMARY), 1);
    deleteParams.leftMargin = dp(12);
    manageActions.addView(manageDeleteBar, deleteParams);
    manageActions.setVisibility(View.GONE);
    manageActionRow = manageActions;
    barStack.addView(batchActions, new FrameLayout.LayoutParams(-1, -2));
    barStack.addView(manageActions, new FrameLayout.LayoutParams(-1, -2));
    batchPasteShell.addView(barStack, new FrameLayout.LayoutParams(-1, -2));
    batchPasteShell.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          if (bottom - top != oldBottom - oldTop) updateBatchPasteInsets();
        });
    FrameLayout.LayoutParams batchParams =
        new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    batchParams.setMargins(dp(10), 0, dp(10), dp(10));
    homeStage.addView(batchPasteShell, batchParams);
    updateBatchPasteBar(false);
    refreshHistory();
  }

  private void showHome() {
    if (homeScreen != null) {
      if (photo != null) photo.cancelPendingSingleTap();
      finishPendingSliderTaps();
      updateEditorLayout();
      flowingBackground.setVisibility(View.VISIBLE);
      homeThumbPaused = false;
      homeScreen.setVisibility(View.VISIBLE);
      editorScreen.setVisibility(View.GONE);
      saveDevelopedLook();
      if (!batchImportRunning && !selectionActive()) setHomeImportHint(idleHomeHint());
      refreshHistory();
      homeImages.loadVisible();
      queueMissingHomeThumbnails();
    }
  }

  /**
   * 编辑器退回首页时直接显示显影效果，再后台保存同一结果；未完成的新配方不能复用旧预览。
   * 示例照片不入库，没有卡片，跳过。
   */
  private void saveDevelopedLook() {
    if (developed == null || developed.isRecycled() || mainPreviewPending()) return;
    if (storedHomeLook == developed && activeId.equals(storedHomeLookId)) return;
    Frame frame = findFrame(activeId);
    if (frame == null || !frame.id.equals(developedPhotoId)
        || !frame.params.equals(developedRecipe)) return;
    homeThumbJobs.invalidate(frame.id);
    homeImages.storeDeveloped(frame.id, developed);
    storedHomeLook = developed;
    storedHomeLookId = frame.id;
    syncHeroLook();
  }

  private void syncHeroLook() {
    bindHomeHeroToRecent();
  }

  private void bindHomeHeroToRecent() {
    if (homeHero == null || homeImages == null) return;
    Frame recent = library.isEmpty() ? null : library.get(0);
    if (recent == null) {
      // Everything was deleted: fall back to the bundled demo photo instead of a stale source.
      homeImages.bind(homeHero, null, null, null, true);
      homeImages.reload(homeHero);
      homeImages.loadVisible();
      homeHero.setClickable(false);
      homeHero.setFocusable(false);
      homeHero.setOnClickListener(null);
      homeHero.setContentDescription("示例照片预览");
      return;
    }
    File heroFile = new File(recent.path);
    homeImages.bind(homeHero, heroFile, developedLook(recent.id), null, true);
    homeHero.setClickable(true);
    homeHero.setFocusable(true);
    homeHero.setContentDescription("编辑 " + PhotoLabels.title(recent.name));
    homeHero.setOnClickListener(
        v -> {
          if (!manageMode) editFrameFromHome(recent);
        });
  }

  private File developedLook(String id) {
    return id == null ? null : new File(new File(getFilesDir(), "developed"), id + ".jpg");
  }

  private void showEditor() {
    closeAbout();
    homeThumbPaused = true;
    flowingBackground.setVisibility(View.GONE);
    homeScreen.setVisibility(View.GONE);
    editorScreen.setVisibility(View.VISIBLE);
    homeImages.loadVisible();
  }

  private void updateComparison() {
    photo.compare(comparing);
    compareButton.setText(comparing ? "结束对比" : "对比原图");
    compareButton.setSelected(comparing);
    AppStyle.pillAction(
        compareButton,
        comparing ? GOLD : INK,
        comparing ? AppStyle.CONTROL : Color.TRANSPARENT);
    AppStyle.setTypeface(
        compareButton, comparing ? AppStyle.FONT_BOLD : AppStyle.FONT_REGULAR);
  }

  private boolean isCurrentTool(int tool) {
    return selectedTab == tool || (tool == 0 && selectedTab == 1);
  }

  private void finishPendingSliderTaps() {
    for (SeekBar bar : sliders.values())
      if (bar instanceof AdjustmentSeekBar) ((AdjustmentSeekBar) bar).finishPendingTap();
  }

  private boolean touchIsInside(View view, MotionEvent event) {
    Rect bounds = new Rect();
    return view.getGlobalVisibleRect(bounds)
        && bounds.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
  }

  @Override
  public boolean dispatchTouchEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      if (photo != null && !touchIsInside(photo, event)) photo.cancelPendingSingleTap();
      // 点击另一个控件已不可能组成同一滑块的双击。先提交上一条单击，再执行切页、撤销、
      // 导出或换照片，避免延迟确认把旧值写进下一次操作。
      for (SeekBar bar : sliders.values())
        if (bar instanceof AdjustmentSeekBar)
          ((AdjustmentSeekBar) bar).finishPendingTapOutside(event.getRawX(), event.getRawY());
    }
    return super.dispatchTouchEvent(event);
  }

  private void selectEditorTool(int tool) {
    if (exporting || activeImport != null) return;
    photo.cancelPendingSingleTap();
    finishPendingSliderTaps();
    if (isCurrentTool(tool)) {
      togglePanelVisibility();
    } else {
      panelCollapsed = false;
      selectedTab = tool;
      buildPanel(true);
      animatePanel();
      animateSelectedTool();
    }
  }

  private void togglePanelFromPreview() {
    if (exporting || activeImport != null
        || editorScreen == null || editorScreen.getVisibility() != View.VISIBLE) return;
    finishPendingSliderTaps();
    togglePanelVisibility();
  }

  /** Flips only presentation state; selectedTab retains the last tool and secondary tab. */
  private void togglePanelVisibility() {
    panelCollapsed = !panelCollapsed;
    // 不重建参数内容：保留面板滚动位置、二级页签、画廊 View 和已完成的缩略图。
    buildToolNavigation();
    animatePanel();
    animateSelectedTool();
  }

  private void buildToolNavigation() {
    finishToolAnimations();
    controls.removeIf(v -> descendant(v, tabRow));
    tabRow.removeAllViews();
    String[] labels = {"胶片", "调整", "边框", "质感"};
    int[] tools = {0, 2, 4, 5};
    for (int i = 0; i < labels.length; i++) {
      final int tool = tools[i];
      boolean selected = !panelCollapsed && isCurrentTool(tool);
      TextView tab =
          button(
              labels[i],
              selected ? GOLD : MUTED,
              selected ? AppStyle.SELECTED : BG);
      tab.setTextSize(AppStyle.TYPE_TITLE);
      tab.setPadding(0, 0, 0, 0);
      tab.setMinHeight(0);
      tab.setSelected(selected);
      AppStyle.setTypeface(
          tab, selected ? AppStyle.FONT_BOLD : AppStyle.FONT_REGULAR);
      tab.setContentDescription(
          labels[i] + (selected ? "，收起参数面板" : "，展开参数面板"));
      // 导航本身已有 4dp 上下内边距，只再缩 1dp，避免选中胶囊被双重内缩压扁。
      if (selected)
        tab.setBackground(
            new android.graphics.drawable.InsetDrawable(
                tab.getBackground(), 0, dp(1), 0, dp(1)));
      tab.setOnClickListener(v -> selectEditorTool(tool));
      tabRow.addView(tab, new LinearLayout.LayoutParams(0, -1, 1));
    }
  }

  private void buildPanel() {
    buildPanel(false);
  }

  private void buildPanel(boolean preserveTransition) {
    if (panel == null) return;
    // Keep the deferred crop tool's ID reserved without exposing its panel.
    if (selectedTab == 3) selectedTab = 0;
    // 换照片从「全部」开头展示；未编辑照片默认选 C200（当前第二张），已编辑照片保留参数。
    String sessionKey = sourceFile == null ? "internal" : sourceFile.getAbsolutePath();
    if (!sessionKey.equals(filmSessionKey)) {
      filmSessionKey = sessionKey;
      panelCollapsed = false;
      preserveTransition = false;
      categoryScrollX.clear();
      filmCategory = "全部";
      pendingFilmScrollX = 0;
    }
    controls.removeIf(v -> descendant(v, panel));
    panel.removeAllViews();
    filmScrollRef = null;
    thumbTargets.clear();
    thumbViewsRef = new ArrayList<>();
    thumbStocksRef = new ArrayList<>();
    sliders.clear();
    values.clear();
    buildToolNavigation();
    ScrollView scroll = new ScrollView(this);
    panelScroll = scroll;
    scroll.setFillViewport(true);
    scroll.setVerticalScrollBarEnabled(false);
    // 面板底部原来是一条硬切口：内容滚到一半时最后一行被齐着切掉，看起来像「被裁」而不是
    // 「还能往下滚」——曲线页当初被当成 bug 报上来，就是这个观感。盖一层从透明到面板色的
    // 渐隐，只在下面确实还有内容时出现，滚到底就消失。它不吃触摸，只是提示层。
    FrameLayout body = new FrameLayout(this);
    body.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
    panelFade = new View(this);
    panelFade.setBackground(
        new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {AppStyle.withAlpha(AppStyle.SURFACE, 0f), AppStyle.SURFACE}));
    panelFade.setClickable(false);
    panelFade.setFocusable(false);
    panelFade.setAlpha(0f);
    body.addView(panelFade, new FrameLayout.LayoutParams(-1, dp(FADE_ROW_DP), Gravity.BOTTOM));
    scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> updatePanelFade());
    panel.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
    editor = vertical();
    // 面板顶上不再有把手那一行，正文直接贴着圆角顶边，所以自己留 10dp 呼吸。
    editor.setPadding(dp(14), dp(10), dp(14), dp(10));
    // 内容增减会改变「能不能滚」，渐隐跟着重判一次。
    editor.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
            panel.post(this::updatePanelFade));
    scroll.addView(editor);
    if (!preserveTransition) updateEditorLayout();
    if (selectedTab <= 1) {
      LinearLayout modes = horizontal();
      for (int i = 0; i < 2; i++) {
        final int mode = i;
        TextView tab =
            button(
                i == 0 ? "胶片型号" : "相纸与印相",
                selectedTab == i ? GOLD : MUTED,
                selectedTab == i ? AppStyle.SELECTED : BG);
        tab.setTextSize(AppStyle.TYPE_LABEL);
        tab.setPadding(0, 0, 0, 0);
        tab.setMinHeight(0);
        tab.setSelected(selectedTab == i);
        AppStyle.setTypeface(
            tab, selectedTab == i ? AppStyle.FONT_BOLD : AppStyle.FONT_REGULAR);
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
      String[] categories = {"全部", "彩色负片", "电影胶片", "反转片", "黑白"};
      for (String category : categories) {
        TextView chip =
            button(
                category,
                category.equals(filmCategory) ? GOLD : MUTED,
                category.equals(filmCategory) ? AppStyle.CONTROL : BG);
        chip.setTextSize(AppStyle.TYPE_MICRO);
        chip.setSelected(category.equals(filmCategory));
        chip.setPadding(dp(10), 0, dp(10), 0);
        chip.setSingleLine(true);
        chip.setHorizontallyScrolling(false);
        chip.setAutoSizeTextTypeUniformWithConfiguration(
            10, 12, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
        chip.setMinHeight(0);
        AppStyle.setTypeface(
            chip,
            category.equals(filmCategory) ? AppStyle.FONT_BOLD : AppStyle.FONT_REGULAR);
        chip.setOnClickListener(
            v -> {
              filmCategory = category;
              // 该分类动过手就回到上次停留的位置，没动过就从第一张开始。
              pendingFilmScrollX = categoryScrollX.getOrDefault(category, 0);
              buildPanel();
            });
        LinearLayout.LayoutParams chipLayout = new LinearLayout.LayoutParams(0, dp(40), 1);
        chipLayout.rightMargin = dp(3);
        filters.addView(chip, chipLayout);
      }
      add(editor, filters, -1, 40);
      gap(editor, 8);
      // 画廊式胶片选择：方形缩略图就是「当前这张照片用这卷胶片显影后的样子」，标签在下方，
      // 选中用 2dp 白色描边。缩略图仍逐卷独立 open，但显影进程只解码一次 1024px 源图；
      // 引擎完成规定的 800px 显影后，再把返回值和缓存压到卡片尺寸。
      final int thumbSize = 88, thumbGap = 10, labelHeight = 22;
      final int stride = thumbSize + thumbGap;
      final int cardHeight = thumbSize + 7 + labelHeight + 4;
      syncThumbCache();

      HorizontalScrollView filmScroll = new HorizontalScrollView(this);
      filmScroll.setHorizontalScrollBarEnabled(false);
      filmScroll.setClipToPadding(false);
      filmCards = horizontal();
      filmCards.setPadding(0, 0, dp(12), 0);
      filmScroll.addView(filmCards);

      thumbTargets.clear();
      final List<ImageView> thumbViews = new ArrayList<>();
      final List<String> thumbStocks = new ArrayList<>();
      int position = 0;
      for (Stock stock : films) {
        String category = category(stock);
        if (!filmCategory.equals("全部") && !filmCategory.equals(category)) continue;
        boolean selected = stock.id.equals(settings.optString("film_stock"));
        final int index = position;
        LinearLayout card = vertical();
        card.setSelected(selected);
        card.setFocusable(true);

        FrameLayout frame = new FrameLayout(this);
        GradientDrawable ring = box(Color.TRANSPARENT, AppStyle.R_MEDIUM);
        if (selected) ring.setStroke(dp(2), GOLD);
        // The image and its inset stroke share the same bounds/radius (no fourth corner size).
        frame.setForeground(ring);
        ImageView thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        thumb.setTag(stock.id);
        thumb.setBackground(box(AppStyle.CONTROL, AppStyle.R_MEDIUM));
        thumb.setClipToOutline(true);
        frame.addView(thumb, new FrameLayout.LayoutParams(-1, -1));
        add(card, frame, thumbSize, thumbSize);

        // 标签保持原来的样子：选中用强调色、未选中用正文色，不加底。
        TextView name =
            text(shortName(stock.name), AppStyle.TYPE_MICRO, selected ? GOLD : INK);
        AppStyle.setTypeface(
            name, selected ? AppStyle.FONT_MEDIUM : AppStyle.FONT_REGULAR);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        name.setPadding(dp(6), 0, dp(6), 0);
        LinearLayout.LayoutParams nameLayout = new LinearLayout.LayoutParams(-1, dp(labelHeight));
        nameLayout.topMargin = dp(7);
        card.addView(name, nameLayout);

        card.setContentDescription("胶片 " + stock.name + (selected ? "，已选" : ""));
        card.setOnClickListener(
            v -> {
              // 点中间那张一律不动；只有点当前视口最左 / 最右那张、且外侧还有胶片时，
              // 才跟着滑一格，把外面的胶片带进来。
              int base = filmScroll.getScrollX();
              int target = base;
              int total = filmCards.getChildCount();
              int first = 0, last = total - 1;
              for (int i = 0; i < total; i++) {
                if (filmCards.getChildAt(i).getRight() > base + 1) {
                  first = i;
                  break;
                }
              }
              int rightEdge = base + filmScroll.getWidth();
              for (int i = total - 1; i >= 0; i--) {
                if (filmCards.getChildAt(i).getLeft() < rightEdge - 1) {
                  last = i;
                  break;
                }
              }
              int step = dp(stride);
              if (index == first && index > 0) target = Math.max(0, base - step);
              else if (index == last && index < total - 1) target = base + step;

              String keepCategory = filmCategory;
              set("film_stock", stock.id);
              // 选胶片不跟着切分类，否则在「全部」下点一张卡，列表会突然变短。
              filmCategory = keepCategory;
              categoryScrollX.put(keepCategory, target);
              pendingFilmScrollX = target;
              scheduleRender();
              buildPanel();
            });
        controls.add(card);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(thumbSize), dp(cardHeight));
        lp.rightMargin = dp(thumbGap);
        filmCards.addView(card, lp);
        thumbViews.add(thumb);
        thumbStocks.add(stock.id);
        thumbTargets.put(stock.id, thumb);
        position++;
      }
      add(editor, filmScroll, -1, cardHeight);
      final int targetScroll =
          pendingFilmScrollX >= 0
              ? pendingFilmScrollX
              : Math.max(0, categoryScrollX.getOrDefault(filmCategory, 0));
      pendingFilmScrollX = -1;
      filmScroll
          .getViewTreeObserver()
          .addOnGlobalLayoutListener(
              new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                public void onGlobalLayout() {
                  if (filmScroll.getWidth() > 0) {
                    filmScroll.scrollTo(targetScroll, 0);
                    filmScroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                    fillVisibleThumbs(
                        filmScroll, thumbViews, thumbStocks, dp(stride), dp(thumbSize));
                  }
                }
              });
      filmScroll.setOnScrollChangeListener(
          (v, x, y, ox, oy) ->
              fillVisibleThumbs(filmScroll, thumbViews, thumbStocks, dp(stride), dp(thumbSize)));
      filmScrollRef = filmScroll;
      thumbViewsRef = thumbViews;
      thumbStocksRef = thumbStocks;
      thumbStridePx = dp(stride);
      thumbSizePx = dp(thumbSize);
      gap(editor, 6);
      if (monochromeFilm()) blackWhiteFilterChoice();
      slider("exposure_compensation_ev", "胶片曝光", -3, 3, .1, " EV");

    } else if (selectedTab == 1) {
      if (positiveFilm()) {
        panelNote("反转片直接扫描", "当前胶片经过显影后直接扫描，相纸和印相滤色不参与处理。可到「调整」调整扫描结果。");
        return;
      }
      LinearLayout heading = horizontal();
      TextView title = text("印相材料", AppStyle.TYPE_BODY, INK);
      AppStyle.setTypeface(title, AppStyle.FONT_BOLD);
      heading.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1));
      int paperCount = availablePapers().size();
      TextView pick = button("全部 " + paperCount + " 款", GOLD, AppStyle.CONTROL);
      pick.setPadding(dp(16), 0, dp(16), 0);
      pick.setMinHeight(0);
      pick.setTextSize(AppStyle.TYPE_LABEL);
      // 宽度跟着文字走：一旦被挤就整行折成两行，药丸里塞两行很难看，所以锁死单行。
      pick.setSingleLine(true);
      pick.setEllipsize(android.text.TextUtils.TruncateAt.END);
      pick.setOnClickListener(v -> chooseStock(false));
      if (paperCount > 1) add(heading, pick, -2, 32);
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
      if (paperCount > 1) {
        material.setOnClickListener(v -> chooseStock(false));
        controls.add(material);
      }
      add(editor, material, -1, 78);
      TextView linkedPaper = text("相纸与已选择的胶片联动，默认无需选择", AppStyle.TYPE_MICRO, MUTED);
      linkedPaper.setMaxLines(2);
      add(editor, linkedPaper, -1, 40);
      if (FilmStockPolicy.monochrome(settings.optString("film_stock"))) {
        paperGradeChoice();
        gap(editor, 7);
      } else {
        gap(editor, 14);
      }
      slider("print_brightness", "印相亮度", -2, 2, .05, " EV");
      if (!monochromeFilm()) {
        slider("m_filter_shift", "洋红滤色", -.25, .25, .01, "");
        slider("y_filter_shift", "黄色滤色", -.25, .25, .01, "");
      }
    } else if (selectedTab == 2) {
      buildGradePanel();
    } else if (selectedTab == 4) {
      buildFramePanel();
    } else {
      panelNote(
          "质感从胶片计算而来",
          "以物理画幅缩放颗粒与光晕，保留各胶片的特性。");
      slider("grain_amount", "银盐颗粒", 0, 2, .05, " ×");
      slider("halation_amount", "高光光晕", 0, 4, .1, " ×");
      if (FilmStockPolicy.cinestill800t(settings.optString("film_stock")))
        slider("halation_headroom_ev", "高光余量", 0, 8, .1, " EV");
      slider("halation_boost_ev", "光晕增强", 0, 8, .1, " EV");
      slider("halation_scatter_amount", "乳剂散射", 0, 1, .05, "");
      toggle("antihalation_removed", "移除防光晕层", false);
      slider("dir_couplers_amount", "显影耦合", 0, 4, .1, " ×");
      slider("density_curve_gamma", "胶片反差", .2, 4, .05, " ×");
      slider("lens_blur_um", "镜头柔化", 0, 40, .5, " μm");
      slider("scanner_lens_blur", "扫描柔化", 0, 5, .1, " px");
      if (!positiveFilm()) {
        slider("glare_amount", "相纸炫光", 0, 30, .5, " ×");
        slider("preflash_exposure", "相纸预闪", 0, .1, .002, "");
      }
      // 测光：上游把「关闭」也当成一个档位（Custom），所以这里是一排意图而不是开关 + 下拉，
      // 免得开关关着、下面那行还亮着却不起作用。
      gap(editor, 8);
      TextView meterLabel = text("测光", AppStyle.TYPE_LABEL, MUTED);
      meterLabel.setTag("editor.meter.label");
      add(editor, meterLabel, -1, 22);
      gap(editor, 8);
      LinearLayout meters = horizontal();
      meters.setTag("editor.meter.options");
      String[] meterNames = {"关闭", "均衡", "中央主体", "保护高光", "保护阴影"};
      String[] meterIds = {"custom", "balanced", "center", "protect_highlights", "protect_shadows"};
      String activeMeter = meterState();
      for (int i = 0; i < meterNames.length; i++) {
        boolean on = meterIds[i].equals(activeMeter);
        TextView chip = button(meterNames[i], on ? GOLD : MUTED, on ? AppStyle.CONTROL : BG);
        chip.setTextSize(AppStyle.TYPE_LABEL);
        chip.setPadding(dp(4), 0, dp(4), 0);
        chip.setMinHeight(0);
        chip.setSelected(on);
        AppStyle.setTypeface(
            chip, on ? AppStyle.FONT_BOLD : AppStyle.FONT_REGULAR);
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
      gap(editor, 14);
      LinearLayout format = horizontal();
      TextView label = text("胶片画幅", AppStyle.TYPE_LABEL, INK);
      format.addView(label, new LinearLayout.LayoutParams(0, dp(40), 1));
      TextView pick = button(formatName(), GOLD, AppStyle.CONTROL);
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
    // 新页从顶部开始，滚动事件不一定触发，渐隐要自己重算一次。
    panel.post(this::updatePanelFade);
  }

  private void panelNote(String title, String detail) {
    TextView t = text(title, AppStyle.TYPE_BODY, INK);
    AppStyle.setTypeface(t, AppStyle.FONT_BOLD);
    add(editor, t, -1, 30);
    TextView d = text(detail, AppStyle.TYPE_MICRO, MUTED);
    d.setLineSpacing(dp(3), 1);
    add(editor, d, -1, -2);
    gap(editor, 12);
  }

  private void blackWhiteFilterChoice() {
    String[] ids = {"", "w8", "w21", "w25", "w58", "w47"};
    String[] values = {"无", "黄色", "橙色", "红色", "绿色", "蓝色"};
    String[] names = {
      "无滤镜：保持原始明暗关系",
      "黄色：压暗蓝天，增强云层",
      "橙色：加强天空反差，增加远景层次",
      "红色：强烈压暗蓝天，突出暖色主体",
      "绿色：提亮绿色植被，压暗红橙",
      "蓝色：压暗暖色，突出雾气与冷调"
    };
    String current = FilmStockPolicy.cameraFilter(
        settings.optString("film_stock"), settings.optString("camera_filter"));
    int selected = Math.max(0, Arrays.asList(ids).indexOf(current));

    LinearLayout row = horizontal();
    row.setPadding(0, 0, 0, 0);
    row.setFocusable(true);
    row.setClickable(true);
    row.setTag("black_white_filter");
    row.setContentDescription("黑白滤镜，当前" + values[selected]);
    TextView label = text("黑白滤镜", AppStyle.TYPE_BODY, INK);
    AppStyle.setTypeface(label, AppStyle.FONT_MEDIUM);
    row.addView(label, new LinearLayout.LayoutParams(0, dp(44), 1));

    TextView value = text(values[selected], AppStyle.TYPE_LABEL, GOLD);
    AppStyle.setTypeface(value, AppStyle.FONT_MEDIUM);
    value.setGravity(Gravity.CENTER);
    value.setPadding(dp(13), 0, dp(13), 0);
    value.setMinWidth(dp(58));
    GradientDrawable valueShape = AppStyle.pill(this, AppStyle.CONTROL, false);
    valueShape.setStroke(Math.max(1, dp(.6f)), AppStyle.OUTLINE);
    value.setBackground(valueShape);
    row.addView(value, new LinearLayout.LayoutParams(-2, dp(32), 0));
    controls.add(row);
    row.setOnClickListener(
        v ->
            new Haptics.DialogBuilder(this)
                .setTitle("黑白滤镜")
                .setSingleChoiceItems(
                    names,
                    selected,
                    (dialog, which) -> {
                      set("camera_filter", ids[which]);
                      scheduleRender();
                      buildPanel();
                      dialog.dismiss();
                    })
                .setNegativeButton("取消", null)
                .show());
    add(editor, row, -1, 44);
    gap(editor, 4);
  }

  private void paperGradeChoice() {
    String[] ids = {"00", "1", "2", "4", "5"};
    String[] names = {"低反差", "较低", "标准", "较高", "高反差"};
    String current = FilmStockPolicy.paperGrade(
        settings.optString("film_stock"), settings.optString("paper_grade"));

    LinearLayout heading = horizontal();
    TextView label = text("相纸反差", AppStyle.TYPE_BODY, INK);
    AppStyle.setTypeface(label, AppStyle.FONT_MEDIUM);
    heading.addView(label, new LinearLayout.LayoutParams(0, dp(36), 1));
    TextView description =
        text(
            paperGradeDescription(current),
            AppStyle.TYPE_MICRO,
            GOLD);
    description.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    description.setTag("paper_grade_description");
    description.setMaxLines(2);
    description.setSingleLine(false);
    heading.addView(description, new LinearLayout.LayoutParams(-2, dp(36), 0));
    add(editor, heading, -1, 36);
    gap(editor, 2);

    HorizontalScrollView gradeScroll = new HorizontalScrollView(this);
    gradeScroll.setTag("paper_grade_scroll");
    gradeScroll.setHorizontalScrollBarEnabled(false);
    gradeScroll.setClipToPadding(false);
    LinearLayout grades = horizontal();
    grades.setPadding(0, 0, dp(4), 0);
    for (int i = 0; i < ids.length; i++) {
      boolean on = ids[i].equals(current);
      TextView chip = button(names[i], on ? GOLD : MUTED, AppStyle.CONTROL);
      chip.setTextSize(AppStyle.TYPE_MICRO);
      chip.setPadding(0, 0, 0, 0);
      chip.setMinWidth(0);
      chip.setMinHeight(0);
      chip.setSingleLine(true);
      chip.setSelected(on);
      AppStyle.setTypeface(chip, on ? AppStyle.FONT_BOLD : AppStyle.FONT_MEDIUM);
      GradientDrawable shape = AppStyle.pill(this, AppStyle.CONTROL, false);
      shape.setStroke(dp(on ? 1 : .6f), on ? GOLD : AppStyle.OUTLINE);
      chip.setBackground(shape);
      chip.setContentDescription(
          "相纸反差 " + names[i] + (on ? "，已选，" + paperGradeDescription(ids[i]) : ""));
      final int index = i;
      chip.setOnClickListener(
          v -> {
            int base = gradeScroll.getScrollX(), target = base;
            int total = grades.getChildCount(), first = 0, last = total - 1;
            for (int child = 0; child < total; child++)
              if (grades.getChildAt(child).getRight() > base + 1) {
                first = child;
                break;
              }
            int rightEdge = base + gradeScroll.getWidth();
            for (int child = total - 1; child >= 0; child--)
              if (grades.getChildAt(child).getLeft() < rightEdge - 1) {
                last = child;
                break;
              }
            int step = dp(55);
            if (index == first && index > 0) target = Math.max(0, base - step);
            else if (index == last && index < total - 1) target = base + step;
            paperGradeScrollX = target;
            set("paper_grade", ids[index]);
            scheduleRender();
            buildPanel();
          });
      LinearLayout.LayoutParams chipLayout = new LinearLayout.LayoutParams(dp(49), dp(36));
      if (i < ids.length - 1) chipLayout.rightMargin = dp(6);
      grades.addView(chip, chipLayout);
    }
    gradeScroll.addView(grades);
    gradeScroll
        .getViewTreeObserver()
        .addOnGlobalLayoutListener(
            new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
              public void onGlobalLayout() {
                if (gradeScroll.getWidth() > 0 && grades.getWidth() > 0) {
                  restorePaperGradePosition(
                      gradeScroll, grades, Arrays.asList(ids).indexOf(current));
                  gradeScroll.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                }
              }
            });
    gradeScroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> paperGradeScrollX = x);
    add(editor, gradeScroll, -1, 38);
  }

  private void restorePaperGradePosition(
      HorizontalScrollView gradeScroll, LinearLayout grades, int selected) {
    if (selected < 0 || selected >= grades.getChildCount()) return;
    int viewport = gradeScroll.getWidth();
    int maximum = Math.max(0, grades.getWidth() - viewport);
    int target = Math.max(0, Math.min(paperGradeScrollX, maximum));
    View chip = grades.getChildAt(selected);
    // Preserve a manually chosen middle position, but never rebuild the panel with the selected
    // grade hidden just outside the viewport (for example after restored settings or larger text).
    if (chip.getLeft() < target) target = chip.getLeft();
    else if (chip.getRight() > target + viewport) target = chip.getRight() - viewport;
    paperGradeScrollX = Math.max(0, Math.min(target, maximum));
    gradeScroll.scrollTo(paperGradeScrollX, 0);
  }

  private static String paperGradeDescription(String grade) {
    return switch (grade) {
      case "00" -> "高光柔和，暗部较松";
      case "1" -> "轻微柔化";
      case "4" -> "明显增强层次和力量感";
      case "5" -> "最硬朗，保留黑白摄影的极端风格";
      default -> "默认，保持当前黑白效果";
    };
  }

  private void toggle(String key, String label, boolean fallback) {
    Switch sw = new Switch(this);
    sw.setText(label);
    sw.setTextSize(AppStyle.TYPE_BODY);
    sw.setTextColor(INK);
    AppStyle.setTypeface(sw, AppStyle.FONT_REGULAR);
    AppStyle.styleSwitch(sw);
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
    TextView pick =
        button(title + "   " + names[Math.max(0, index)] + "", GOLD, AppStyle.CONTROL);
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
    String[] sections = {"基础", "曲线", "颜色分级", "颜色混合", "暗角"};
    for (String name : sections) {
      TextView tab =
          button(
              name.equals("颜色混合") ? "HSL" : name,
              gradeSection.equals(name) ? GOLD : MUTED,
              gradeSection.equals(name) ? AppStyle.SELECTED : BG);
      tab.setTextSize(AppStyle.TYPE_MICRO);
      tab.setPadding(0, 0, 0, 0);
      tab.setMinHeight(0);
      tab.setSelected(gradeSection.equals(name));
      AppStyle.setTypeface(
          tab, gradeSection.equals(name) ? AppStyle.FONT_BOLD : AppStyle.FONT_REGULAR);
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
      slider("grade_temperature", "色温", -100, 100, 1, "");
      slider("grade_tint", "色调", -100, 100, 1, "");
      slider("grade_exposure", "曝光", -5, 5, .05, " EV");
      slider("grade_contrast", "对比度", -50, 50, 1, "");
      slider("grade_brightness", "亮度", -50, 50, 1, "");
      slider("grade_shadows", "阴影", -100, 100, 1, "");
      slider("grade_highlights", "高光", -100, 100, 1, "");
      slider("grade_black", "黑点", 0, 50, 1, " %");
      slider("grade_white", "白点", 0, 50, 1, " %");
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
                  : curveChannel.equals("b") ? AppStyle.CURVE_B : INK;
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
    } else if (gradeSection.equals("颜色分级")) {
      String[] ids = {"master", "shadows", "midtones", "highlights"},
          names = {"全局", "阴影", "中间调", "高光"};
      TextView zone =
          button(
              "区域   " + names[Math.max(0, Arrays.asList(ids).indexOf(balanceZone))] + "",
              INK,
              AppStyle.CONTROL);
      add(editor, zone, -1, 38);
      zone.setOnClickListener(
          v ->
              new Haptics.DialogBuilder(this)
                  .setTitle("颜色分级区域")
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
      slider(key + "_saturation", "饱和度", 0, 100, 1, "");
      slider(key + "_luminance", "明亮度", -100, 100, 1, "");
    } else if (gradeSection.equals("颜色混合")) {
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
                  .setTitle("颜色混合（HSL）")
                  .setItems(
                      names,
                      (d, n) -> {
                        hslBand = ids[n];
                        buildPanel();
                      })
                  .show());
      gap(editor, 12);
      String key = "hsl_" + hslBand;
      slider(key + "_hue", "色相", -100, 100, 1, "");
      slider(key + "_saturation", "饱和度", -100, 100, 1, "");
      slider(key + "_luminance", "明亮度", -100, 100, 1, "");
    } else {
      slider("grade_vignette", "暗角", -100, 100, 1, "");
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
    TextView crop =
        button("打开裁切框", AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    AppStyle.setTypeface(crop, AppStyle.FONT_BOLD);
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
    TextView ratio = button("比例：自由裁切", GOLD, AppStyle.CONTROL);
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
      slider("edge_amount", "边框宽度", 2, 15, .5, " %");
      if (settings.optString("edge_style").startsWith("135"))
        slider("edge_frame_number", "底片编号", 1, 99, 1, "");
      if (settings.optString("edge_style").equals("instant")) {
        TextView caption =
            button("相纸文字  " + settings.optString("edge_caption", "添加英文或数字"), INK, AppStyle.CONTROL);
        add(editor, caption, -1, 39);
        caption.setOnClickListener(
            v -> editTextValue("edge_caption", "相纸文字", 42, "支持英文、数字、空格及 - . /，最多 42 字符"));
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
      TextView shot = button("使用原片拍摄日期", GOLD, AppStyle.CONTROL);
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
    if (sponsorPage != null) {
      sponsorPage.navigateBack();
      return;
    }
    if (aboutPage != null) {
      // 免责声明子页先退回概览，再退才关闭整页。
      aboutPage.navigateBack();
      return;
    }
    if (batchImportRunning) {
      cancelBatchImport();
      return;
    }
    if (activeImport != null) {
      cancelImport("已取消导入");
      return;
    }
    if (manageMode) {
      exitManageMode();
      return;
    }
    if (batchPasteMode) {
      exitBatchPaste();
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
    if (photo != null) photo.cancelPendingSingleTap();
    if (panel != null) {
      updateEditorLayout();
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
    AppStyle.setNumericTypeface(value);
    value.setGravity(Gravity.CENTER);
    value.setSingleLine(true);
    value.setHorizontallyScrolling(false);
    value.setIncludeFontPadding(false);
    value.setPadding(dp(4), 0, dp(4), 0);
    value.setAutoSizeTextTypeUniformWithConfiguration(
        8, 11, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    GradientDrawable valueBox = AppStyle.pill(this, AppStyle.FIELD, false);
    valueBox.setStroke(Math.max(1, dp(.6f)), AppStyle.OUTLINE);
    value.setBackground(valueBox);
    AdjustmentSeekBar bar = new AdjustmentSeekBar(this, sliderColors(key));
    bar.setMax((int) Math.round((max - min) / step));
    double defaultValue = sliderDefault(key);
    bar.setProgress((int) Math.round((settings.optDouble(key, defaultValue) - min) / step));
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
    value.setText(number(settings.optDouble(key, defaultValue), suffix));
    if (Build.VERSION.SDK_INT >= 30) bar.setStateDescription(value.getText());
    bar.setOnResetListener(
        () -> {
          if (exporting || activeImport != null || destroyed
              || Math.abs(settings.optDouble(key, defaultValue) - defaultValue) < .00001) return;
          set(key, defaultValue);
          bar.setProgress((int) Math.round((defaultValue - min) / step));
          refreshSliderValue(key, defaultValue, suffix, bar, value);
          bar.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
          scheduleRender();
        });
    bar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          private double before;
          private boolean tracking;

          public void onProgressChanged(SeekBar b, int pos, boolean user) {
            if (user && !destroyed && !exporting && activeImport == null) {
              double v = Math.round((min + pos * step) * 10000) / 10000.0;
              set(key, v);
              refreshSliderValue(key, v, suffix, b, value);
              // 键盘/无障碍调值没有拖动结束事件，也需要提交编辑。
              if (!tracking) scheduleRender();
            }
          }

          public void onStartTrackingTouch(SeekBar b) {
            tracking = true;
            before = settings.optDouble(key, defaultValue);
          }

          public void onStopTrackingTouch(SeekBar b) {
            tracking = false;
            if (!destroyed && !exporting && activeImport == null
                && Math.abs(settings.optDouble(key, defaultValue) - before) > .00001)
              scheduleRender();
          }
        });
  }

  private void refreshSliderValue(
      String key, double v, String suffix, SeekBar bar, TextView value) {
    value.setText(number(v, suffix));
    if (Build.VERSION.SDK_INT >= 30) bar.setStateDescription(value.getText());
    if (key.startsWith("balance_") && key.endsWith("_hue")) {
      String saturation = key.substring(0, key.length() - 4) + "_saturation";
      SeekBar related = sliders.get(saturation);
      if (related instanceof AdjustmentSeekBar)
        ((AdjustmentSeekBar) related).setTrackColors(sliderColors(saturation));
    }
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
    return new int[] {0xff67646a, 0xffac7e58, 0xffff7a1a};
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

  private interface ByteProgress {
    void onBytes(long copied);
  }

  private static void copy(InputStream in, OutputStream out) throws IOException {
    copy(in, out, null);
  }

  private static void copy(InputStream in, OutputStream out, ByteProgress progress)
      throws IOException {
    byte[] bytes = new byte[65536];
    int n;
    long copied = 0;
    while ((n = in.read(bytes)) != -1) {
      out.write(bytes, 0, n);
      copied += n;
      if (progress != null) progress.onBytes(copied);
    }
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
        .replace("Ilford ", "")
        .replace("Neopan 100 Acros II", "Acros II 100")
        .replace("Professional ", "")
        .replace("Crystal Archive Type II", "Crystal Archive")
        // Push 版本的名字带「(Push 1)」，在 71dp 的卡片里必然折行；图标上已经有 +1/+2 角标，
        // 名字只留型号即可，完整名称仍在 contentDescription 里。
        .replaceAll("\\s*\\(Push [12]\\)$", "");
  }

  private String maker(Stock s) {
    return s.id.substring(0, s.id.indexOf('_')).toUpperCase(Locale.ROOT);
  }

  private void updateStockLabels() {
    Stock f = find(films, settings.optString("film_stock")),
        p = find(papers, settings.optString("print_stock"));
    if (f != null && filmTitle != null) {
      filmTitle.setText(shortName(f.name));
      filmSub.setText(maker(f) + " · " + category(f));
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
    ArrayList<Stock> list = isFilm ? films : availablePapers();
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

  private boolean monochromeFilm() {
    return FilmStockPolicy.monochrome(settings.optString("film_stock"));
  }

  private ArrayList<Stock> availablePapers() {
    ArrayList<Stock> available = new ArrayList<>();
    for (Stock paper : papers)
      if (FilmStockPolicy.canPrint(settings.optString("film_stock"), paper.id))
        available.add(paper);
    return available;
  }

  private void normalizePaper() {
    Stock film = find(films, settings.optString("film_stock"));
    if (film == null) return;
    String paper = settings.optString("print_stock");
    if (find(papers, paper) == null) paper = "";
    try {
      settings.put("print_stock", FilmStockPolicy.paperFor(film.id, film.target, paper));
    } catch (JSONException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private String params() {
    return params(settings, find(films, settings.optString("film_stock")), sourceRaw);
  }

  private String params(JSONObject recipe, Stock stock) {
    return params(recipe, stock, sourceRaw);
  }

  private String params(JSONObject recipe, Stock stock, boolean raw) {
    try {
      JSONObject j = new JSONObject();
      boolean positive = stock != null && stock.type.equals("positive");
      String filmStock = recipe.optString("film_stock");
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
      for (String k : keys) if (recipe.has(k)) j.put(k, recipe.get(k));
      j.put("input_color_space", raw ? "ProPhoto RGB" : "sRGB");
      j.put("input_cctf_decoding", !raw);
      j.put("output_color_space", "sRGB");
      j.put("output_cctf_encoding", true);
      j.put("preview_long_edge", 1024);
      j.put("scan_film", positive);
      j.put("grain_active", recipe.optDouble("grain_amount", 1) > 0);
      j.put("halation_active", recipe.optDouble("halation_amount", 1) > 0);
      j.put("glare_active", !positive && recipe.optDouble("glare_amount", 1) > 0);
      j.put("dir_couplers_active", recipe.optDouble("dir_couplers_amount", 1) > 0);
      j.put("scene_latitude_active", false);
      j.put("contrast_mask_active", false);
      j.put("overscan_active", false);
      j.put("date_imprint_active", false);
      for (Map.Entry<String, Object> override :
          FilmStockPolicy.renderOverrides(
                  filmStock,
                  stock == null ? "" : stock.target,
                  recipe.optString("print_stock"),
                  recipe.optDouble("grain_amount", 1),
                  recipe.optString("camera_filter"),
                  recipe.optString("paper_grade", FilmStockPolicy.DEFAULT_PAPER_GRADE),
                  recipe.optDouble("print_brightness", 0),
                  recipe.optDouble("halation_headroom_ev", 4))
              .entrySet()) {
        j.put(override.getKey(), override.getValue());
      }
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
    return editParams(settings, sourceMetadata);
  }

  private String editParams(JSONObject recipe, JSONObject metadata) {
    try {
      JSONObject j = new JSONObject(recipe.toString());
      Stock stock = find(films, recipe.optString("film_stock"));
      j.put("film_label", stock == null ? "" : shortName(stock.name));
      JSONObject source = metadata == null ? new JSONObject() : metadata;
      j.put("source_make", source.optString("make"));
      j.put("source_model", source.optString("model"));
      j.put("source_iso", source.optDouble("iso"));
      j.put("source_aperture", source.optDouble("aperture"));
      j.put("source_shutter", source.optDouble("shutter"));
      j.put("source_focal_mm", source.optDouble("focal_mm"));
      long date = source.optLong("timestamp");
      if (date > 0)
        j.put(
            "source_datetime",
            new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(new Date(date)));
      return j.toString();
    } catch (JSONException ex) {
      throw new IllegalStateException(ex);
    }
  }

  private boolean galleryVisible() {
    return !destroyed
        && selectedTab == 0
        && editorScreen.getVisibility() == View.VISIBLE
        && filmScrollRef != null
        && filmScrollRef.isShown();
  }

  private void syncThumbCache() {
    String print = thumbFingerprint();
    if (print.equals(thumbPrint)) return;
    thumbPrint = print;
    ++thumbGeneration;
    // All cache/view bookkeeping stays on the UI thread. Detach before recycling.
    for (ImageView view : thumbTargets.values()) view.setImageDrawable(null);
    for (Bitmap stale : filmThumbs.values()) Bitmaps.release(stale);
    filmThumbs.clear();
    thumbFailed.clear();
    thumbDiskChecked.clear();
    thumbDiskPending.clear();
    thumbStockDiskKeys.clear();
  }

  private void refillThumbs() {
    if (!galleryVisible() || thumbViewsRef.isEmpty() || thumbStridePx <= 0) return;
    syncThumbCache();
    fillVisibleThumbs(filmScrollRef, thumbViewsRef, thumbStocksRef, thumbStridePx, thumbSizePx);
  }

  /** Render visible cards plus a 60dp buffer, never all stocks up front. */
  private void fillVisibleThumbs(
      HorizontalScrollView scroll,
      List<ImageView> views,
      List<String> stocks,
      int stridePx,
      int sizePx) {
    if (!galleryVisible() || scroll != filmScrollRef || scroll.getWidth() <= 0) return;
    syncThumbCache();
    int left = scroll.getScrollX(), right = left + scroll.getWidth();
    // Selected visible card first, then actual viewport, then the prefetch margin.
    for (int pass = 0; pass < 3; pass++) {
      for (int i = 0; i < views.size(); i++) {
        int l = i * stridePx, r = l + sizePx;
        boolean visible = r > left && l < right;
        int priority = visible ? (stocks.get(i).equals(settings.optString("film_stock")) ? 0 : 1) : 2;
        if (pass != priority || r < left - dp(60) || l > right + dp(60)) continue;
        requestFilmThumb(stocks.get(i), views.get(i));
      }
    }
  }

  private void requestFilmThumb(String stock, ImageView target) {
    Bitmap cached = filmThumbs.get(stock);
    if (cached != null && !cached.isRecycled()) {
      target.setImageBitmap(cached);
      return;
    }
    // One thumbnail at a time lets a newly requested main preview jump ahead of the rest.
    if (thumbFailed.contains(stock)
        || source == null
        || !ready
        || activeImport != null) return;
    String knownKey = thumbStockDiskKeys.get(stock);
    if (knownKey != null && thumbDiskPending.containsKey(knownKey)) return;
    boolean waitForPreview = thumbBusy || exporting || mainPreviewPending()
        || previewWorkTicket == revision || thumbWrites.get() >= 2;
    if (waitForPreview && (sourceFile == null
        || (knownKey != null && thumbDiskChecked.contains(knownKey)))) return;
    final String renderParams, openParams1;
    try {
      Stock choice = find(films, stock);
      JSONObject recipe = new JSONObject();
      recipe.put("film_stock", stock);
      recipe.put(
          "print_stock",
          choice != null && find(papers, choice.target) != null
              ? choice.target
              : "kodak_portra_endura");
      recipe.put("exposure_compensation_ev", 0);
      recipe.put("print_brightness", 0);
      recipe.put("m_filter_shift", 0);
      recipe.put("y_filter_shift", 0);
      recipe.put("camera_filter", "");
      recipe.put("paper_grade", FilmStockPolicy.DEFAULT_PAPER_GRADE);
      recipe.put("grain_amount", 1);
      recipe.put("halation_amount", 1);
      recipe.put("halation_headroom_ev", 4);
      recipe.put("film_format_mm", 36);
      recipe.put("auto_exposure", sourceRaw);
      recipe.put("auto_exposure_method", "balanced");
      recipe.put("glare_amount", 1);
      recipe.put("dir_couplers_amount", 1);
      recipe.put("halation_scatter_amount", 1);
      recipe.put("density_curve_gamma", 1);
      JSONObject j = new JSONObject(params(recipe, choice));
      // Neutral recipe only: user exposure/curves/HSL/paper/grain/date edits stay in the main view.
      // Engine constraints: minimum 800, product_defaults only in the separate open parameters.
      j.put("preview_long_edge", 800);
      renderParams = j.toString();
      openParams1 = openParams(renderParams);
    } catch (JSONException ex) {
      thumbFailed.add(stock);
      return;
    }
    final Bitmap input = source;
    final File file = sourceFile;
    final boolean raw = sourceRaw;
    final int generation = thumbGeneration;
    final String fingerprint = thumbPrint;
    final int postLongEdge = galleryThumbEdge();
    final SpectralRenderer activeRenderer = renderer;
    final String backendKey = galleryBackendKey();
    final String photoKey = file == null ? null : ThumbnailDiskCache.key(file.getAbsolutePath());
    final String diskKey = file == null ? null : photoKey + "-" + ThumbnailDiskCache.key(
        "neutral-gallery-v1", file.getAbsolutePath(), Long.toString(file.length()),
        Long.toString(file.lastModified()), backendKey, renderParams, Integer.toString(postLongEdge));
    if (diskKey != null) thumbStockDiskKeys.put(stock, diskKey);
    if (diskKey != null && !thumbDiskChecked.contains(diskKey)) {
      if (!thumbDiskPending.containsKey(diskKey)) {
        thumbDiskPending.put(diskKey, generation);
        thumbnailIo.execute(() -> {
          Bitmap saved = null;
          File entry = filmDiskCache.get(diskKey);
          if (entry != null) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(entry.getAbsolutePath(), bounds);
            if (bounds.outWidth > 0 && bounds.outHeight > 0
                && Math.max(bounds.outWidth, bounds.outHeight) <= 512)
              saved = BitmapFactory.decodeFile(entry.getAbsolutePath());
            if (saved == null) filmDiskCache.remove(diskKey);
          }
          Bitmap hit = saved;
          thumbnailDeliveries.post(main, hit, () -> {
            if (Integer.valueOf(generation).equals(thumbDiskPending.get(diskKey)))
              thumbDiskPending.remove(diskKey);
            boolean current = !destroyed && generation == thumbGeneration
                && fingerprint.equals(thumbFingerprint()) && backendKey.equals(galleryBackendKey());
            if (current) {
              thumbDiskChecked.add(diskKey);
              if (hit != null) {
                ++thumbDiskHits;
                cacheFilmThumb(stock, hit);
                ImageView live = thumbTargets.get(stock);
                if (live != null && stock.equals(live.getTag())) live.setImageBitmap(hit);
              }
            } else Bitmaps.release(hit);
            if (!destroyed) refillThumbs();
          });
        });
      }
      return;
    }
    // Disk hits need no renderer slot; only cache misses wait for the main preview.
    if (thumbBusy || exporting || mainPreviewPending() || previewWorkTicket == revision
        || thumbWrites.get() >= 2) return;
    thumbBusy = true;
    thumbQueued.add(stock);
    worker.execute(
        () -> {
          Bitmap made = null;
          boolean failed = false;
          try {
            if (!destroyed && generation == thumbGeneration && activeRenderer == renderer
                && !mainPreviewPending()) {
              // Each stock must reopen the current photo. Always invalidate main-preview state,
              // including errors, because opening a thumbnail changes the engine's fixed stock.
              try {
                openedSource = null;
                long started = SystemClock.elapsedRealtime();
                if (raw) activeRenderer.openRaw(file.getAbsolutePath(), openParams1, 800);
                else if (file != null)
                  activeRenderer.openFile(file.getAbsolutePath(), openParams1, GALLERY_SOURCE_EDGE);
                else activeRenderer.open(input, openParams1);
                long opened = SystemClock.elapsedRealtime();
                made = activeRenderer.render(renderParams, "{}", false, postLongEdge);
                ++thumbRenderCount;
                if (!destroyed && diskKey != null && backendKey.equals(galleryBackendKey())
                    && !removedGalleryPhotos.contains(photoKey) && thumbWrites.get() < 2) {
                  Bitmap diskCopy = made.copy(Bitmap.Config.ARGB_8888, false);
                  if (diskCopy != null) {
                    thumbWrites.incrementAndGet();
                    try {
                      thumbnailIo.execute(() -> {
                      try {
                        if (!destroyed && !removedGalleryPhotos.contains(photoKey))
                          filmDiskCache.put(diskKey, output ->
                              diskCopy.compress(Bitmap.CompressFormat.PNG, 100, output)
                                  && !removedGalleryPhotos.contains(photoKey));
                      } catch (IOException ex) {
                        android.util.Log.w("FilmThumb", "Disk cache unavailable", ex);
                      } finally {
                        Bitmaps.release(diskCopy);
                        thumbWrites.decrementAndGet();
                        main.post(this::refillThumbs);
                      }
                      });
                    } catch (RejectedExecutionException closed) {
                      Bitmaps.release(diskCopy);
                      thumbWrites.decrementAndGet();
                    }
                  }
                }
                long rendered = SystemClock.elapsedRealtime();
                android.util.Log.i(
                    "FilmThumb",
                    stock
                        + " opened="
                        + (opened - started)
                        + "ms rendered="
                        + (rendered - opened)
                        + "ms bitmap="
                        + made.getWidth()
                        + "x"
                        + made.getHeight());
              } finally {
                openedSource = null;
              }
            }
          } catch (Throwable ex) {
            failed = true;
            android.util.Log.w("FilmThumb", stock + " failed", ex);
          }
          final Bitmap result = made;
          final boolean error = failed;
          thumbnailDeliveries.post(main, result,
              () -> {
                thumbBusy = false;
                thumbQueued.remove(stock);
                boolean current =
                    !destroyed
                        && generation == thumbGeneration
                        && fingerprint.equals(thumbFingerprint());
                if (current && result != null) {
                  cacheFilmThumb(stock, result);
                  ImageView live = thumbTargets.get(stock);
                  if (live != null && stock.equals(live.getTag())) live.setImageBitmap(result);
                } else Bitmaps.release(result);
                if (current && error) thumbFailed.add(stock);
                if (!destroyed) refillThumbs();
              });
        });
  }

  private String galleryBackendKey() {
    SpectralRenderer current = renderer;
    return filmCacheResources + ":" + (current == null ? "" : current.backend() + ":" + current.capabilities());
  }

  private int galleryThumbEdge() {
    // 88dp cards stay sharp on 4x-density screens without carrying an 800px bitmap for every
    // stock. The engine still renders at its required 800px minimum.
    return Math.max(256, Math.min(512, dp(112)));
  }

  private void cacheFilmThumb(String stock, Bitmap bitmap) {
    Bitmap previous = filmThumbs.put(stock, bitmap);
    if (previous != null && previous != bitmap) {
      ImageView target = thumbTargets.get(stock);
      if (target != null) target.setImageDrawable(null);
      Bitmaps.release(previous);
    }
    trimThumbCache();
  }

  private void trimThumbCache() {
    long bytes = 0;
    for (Bitmap bitmap : filmThumbs.values()) bytes += bitmapBytes(bitmap);
    if (bytes <= GALLERY_CACHE_BYTES) return;
    Iterator<Map.Entry<String, Bitmap>> oldest = filmThumbs.entrySet().iterator();
    while (bytes > GALLERY_CACHE_BYTES && oldest.hasNext()) {
      Map.Entry<String, Bitmap> entry = oldest.next();
      Bitmap stale = entry.getValue();
      bytes -= bitmapBytes(stale);
      ImageView target = thumbTargets.get(entry.getKey());
      if (target != null && entry.getKey().equals(target.getTag()))
        target.setImageDrawable(null);
      oldest.remove();
      Bitmaps.release(stale);
    }
  }

  private long bitmapBytes(Bitmap bitmap) {
    return bitmap == null || bitmap.isRecycled() ? 0 : bitmap.getAllocationByteCount();
  }

  /** Gallery recipes are fixed defaults; only photo/engine identity invalidates their results. */
  private String thumbFingerprint() {
    return photoLoadGeneration
        + ":"
        + engineBootGeneration
        + ":"
        + System.identityHashCode(source)
        + ":"
        + (sourceFile == null ? "" : sourceFile.getAbsolutePath())
        + ":" + galleryBackendKey();
  }

  private boolean mainPreviewPending() {
    return mainPreviewRevision == revision;
  }

  private void scheduleRender() {
    scheduleRender(null);
  }

  private void scheduleRender(Runnable onRendered) {
    if (activeImport != null) return;
    String now = settings.toString();
    if (committed != null && !committed.equals(now)) {
      Frame active = findFrame(activeId);
      if (active != null) active.edited = true;
      undoStack.push(committed);
      while (undoStack.size() > 30) undoStack.removeLast();
      redoStack.clear();
    }
    committed = now;
    updateHistoryButtons();
    prefs.edit().putString("settings", settings.toString()).apply();
    saveActiveFrame();
    int ticket = ++revision;
    mainPreviewRevision = ticket;
    if (!ready || source == null || exporting) return;
    previewWorkTicket = ticket;
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
          String recipeForResult = settings.toString();
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
                  Bitmap before = renderer.editBitmap(input, comparison.toString());
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
                        developedPhotoId = activeId;
                        developedRecipe = recipeForResult;
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
                        previewWorkTicket = -1;
                        mainPreviewRevision = -1;
                        refillThumbs();
                        if (homeScreen != null && homeScreen.getVisibility() == View.VISIBLE)
                          saveDevelopedLook();
                        pumpHomeThumbnails();
                        if (onRendered != null) onRendered.run();
                      });
                } catch (Throwable ex) {
                  if (ticket == revision) {
                    main.post(() -> {
                      if (ticket == revision) {
                        mainPreviewRevision = -1;
                        previewWorkTicket = -1;
                        pumpHomeThumbnails();
                      }
                    });
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
        RasterDecoder.Result decoded = RasterDecoder.decode(file, longEdge);
        out.bitmap = decoded.bitmap;
        out.width = decoded.width;
        out.height = decoded.height;
        readRasterMetadata(file, out.meta);
      }
      if (out.bitmap == null) throw new IOException("照片解码没有返回图像");
      return out;
    } catch (JSONException ex) {
      throw new IOException(ex);
    }
  }

  /** Batch rendering needs metadata, not a second decoded source bitmap. */
  private JSONObject readPhotoMetadata(File file) throws IOException, JSONException {
    if (!PhotoFormats.isRaster(file))
      return new JSONObject(NativeEngine.inspectRaw(file.getAbsolutePath()));
    JSONObject meta = new JSONObject();
    readRasterMetadata(file, meta);
    return meta;
  }

  private void readRasterMetadata(File file, JSONObject meta) {
    try {
      androidx.exifinterface.media.ExifInterface exif =
          new androidx.exifinterface.media.ExifInterface(file.getAbsolutePath());
      meta.put(
          "make", exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE));
      meta.put(
          "model", exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL));
      String date =
          exif.getAttribute(androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL);
      if (date != null)
        meta.put(
            "timestamp",
            new SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(date).getTime());
      meta.put(
          "iso",
          exif.getAttributeDouble(
              androidx.exifinterface.media.ExifInterface.TAG_ISO_SPEED_RATINGS, 0));
      meta.put(
          "aperture",
          exif.getAttributeDouble(androidx.exifinterface.media.ExifInterface.TAG_F_NUMBER, 0));
      meta.put(
          "shutter",
          exif.getAttributeDouble(
              androidx.exifinterface.media.ExifInterface.TAG_EXPOSURE_TIME, 0));
      meta.put(
          "focal_mm",
          exif.getAttributeDouble(
              androidx.exifinterface.media.ExifInterface.TAG_FOCAL_LENGTH, 0));
    } catch (Exception ignored) {
      // 单条 EXIF 缺失不影响导入，按默认值继续
    }
  }

  private void adopt(LoadedPhoto image) {
    adopt(image, true);
  }

  private void adopt(LoadedPhoto image, boolean syncHomeHero) {
    if (photo != null) photo.cancelPendingSingleTap();
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
    if (syncHomeHero && homeHero != null) {
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
      return "胶片边缘 · 示例照片";
    } catch (IOException ex) {
      return "内置静物色卡 · 合成参考图";
    }
  }

  private void importPhoto() {
    importPhoto(null);
  }

  private void importPhoto(String initialSettings) {
    if (exporting || manageMode) return;
    if (gallerySheet != null && gallerySheet.isShowing()) return;
    gallerySheet =
        new GallerySheet(
            this,
            initialSettings == null ? MAX_BATCH_IMPORT : 1,
            new GallerySheet.Listener() {
              public void importPhotos(List<GalleryPhotos.Photo> photos) {
                if (photos == null || photos.isEmpty()) return;
                if (initialSettings != null) {
                  if (photos.size() == 1 && photos.get(0).uri != null)
                    importUri(photos.get(0).uri, initialSettings);
                  return;
                }
                importPhotosBatch(photos);
              }

              public void permission() {
                requestGalleryPermission();
              }
            });
    gallerySheet.show();
    if (GalleryPhotos.access(this) == GalleryPhotos.Access.NONE
        && !prefs.getBoolean("gallery_permission_requested", false)) requestGalleryPermission();
  }

  private void importPhotosBatch(List<GalleryPhotos.Photo> photos) {
    if (exporting || batchImportRunning || photos == null || photos.isEmpty()) return;
    if (photos.size() > MAX_BATCH_IMPORT)
      photos = new ArrayList<>(photos.subList(0, MAX_BATCH_IMPORT));
    showHome();
    saveActiveFrame();
    final int generation = ++batchImportGeneration;
    final String defaults = pristineParamsJson();
    final ArrayList<GalleryPhotos.Photo> queue = new ArrayList<>(photos);
    batchImportRunning = true;
    batchPasteMode = false;
    batchSelection.clear();
    refreshHistory();
    startHomeImportProgress(queue.size());
    worker.execute(() -> runBatchImport(queue, generation, defaults));
  }

  private String pristineParamsJson() {
    JSONObject previous = settings;
    String previousCategory = filmCategory;
    settings = new JSONObject();
    defaults();
    String result = settings.toString();
    settings = previous;
    filmCategory = previousCategory;
    return result;
  }

  private void runBatchImport(
      List<GalleryPhotos.Photo> photos, int generation, String pristineParams) {
    ArrayList<PendingImport> imported = new ArrayList<>();
    int failed = 0;
    for (int index = 0; index < photos.size(); index++) {
      if (destroyed || generation != batchImportGeneration) break;
      GalleryPhotos.Photo selected = photos.get(index);
      final int position = index;
      ui(
          () -> {
            if (generation == batchImportGeneration)
              setHomeImportProgress(position, photos.size(), 0);
          });
      PhotoImportJob job = null;
      File stored = null;
      try {
        job = new PhotoImportJob(getFilesDir(), pristineParams);
        activeImport = job;
        readProviderMetadata(selected, job);
        job.check();
        job.stage("打开相册照片");
        copyProviderPhoto(selected.uri, job, index, photos.size(), generation);
        if (job.copied == 0) throw new IOException("所选照片没有可读取的内容");
        job.check();
        job.stage("解码照片");
        LoadedPhoto loaded = readPhoto(job.temp, 1024);
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
          imported.add(
              new PendingImport(
                  id,
                  job.name,
                  stored.getAbsolutePath(),
                  selected.timestamp,
                  selected.mediaId));
          stored = null;
          writeImportDiagnostic(job, "completed", null);
        } finally {
          Bitmaps.release(loaded.bitmap);
        }
      } catch (Throwable error) {
        if (generation == batchImportGeneration && !(error instanceof OperationCanceledException)) {
          failed++;
          android.util.Log.w(
              "SpektraLensAndroid",
              "Batch import failed for " + (selected == null ? "photo" : selected.name),
              error);
        }
        if (job != null) writeImportDiagnostic(job, "failed", error.toString());
      } finally {
        if (stored != null) {
          stored.delete();
          String id = stored.getName().replaceFirst("\\.img$", "");
          new File(new File(getFilesDir(), "thumbnails"), id + ".jpg").delete();
        }
        if (job != null) job.temp.delete();
        if (activeImport == job) activeImport = null;
      }
      if (generation == batchImportGeneration)
        ui(() -> setHomeImportProgress(position, photos.size(), 1));
    }
    final ArrayList<PendingImport> completed = imported;
    final int failures = failed;
    if (destroyed || generation != batchImportGeneration) {
      deletePendingImports(completed);
      return;
    }
    ui(() -> finishBatchImport(completed, failures, generation));
  }

  private void deletePendingImports(List<PendingImport> imported) {
    for (PendingImport item : imported) {
      new File(item.path).delete();
      new File(new File(getFilesDir(), "thumbnails"), item.id + ".jpg").delete();
    }
  }

  private void readProviderMetadata(GalleryPhotos.Photo selected, PhotoImportJob job) {
    try (android.database.Cursor cursor =
        getContentResolver()
            .query(
                selected.uri,
                new String[] {
                  android.provider.OpenableColumns.DISPLAY_NAME,
                  android.provider.OpenableColumns.SIZE
                },
                null,
                null,
                null,
                job.signal)) {
      if (cursor != null && cursor.moveToFirst()) {
        int nameColumn = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
        int sizeColumn = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
        if (nameColumn >= 0 && !cursor.isNull(nameColumn)) job.name = cursor.getString(nameColumn);
        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) job.total = cursor.getLong(sizeColumn);
      }
    } catch (RuntimeException error) {
      android.util.Log.w("SpektraLensAndroid", "Provider metadata unavailable", error);
    }
  }

  private void copyProviderPhoto(
      Uri uri, PhotoImportJob job, int index, int total, int generation) throws IOException {
    IOException first = null;
    try {
      try (android.content.res.AssetFileDescriptor descriptor =
          getContentResolver().openAssetFileDescriptor(uri, "r", job.signal)) {
        if (descriptor == null) throw new IOException("相册没有返回可读取的照片");
        if (job.total < 0) job.total = descriptor.getLength();
        try (InputStream in = descriptor.createInputStream();
            OutputStream out = new FileOutputStream(job.temp)) {
          copyStream(in, out, job, index, total, generation);
        }
      }
      if (job.copied > 0) {
        job.check();
        return;
      }
      throw new IOException("相册没有返回可读取的照片");
    } catch (android.os.OperationCanceledException error) {
      throw error;
    } catch (IOException | RuntimeException error) {
      first = asIOException(error);
      android.util.Log.w("SpektraLensAndroid", "AssetFileDescriptor read failed; retrying stream", error);
    }

    job.copied = 0;
    try (InputStream in = getContentResolver().openInputStream(uri);
        OutputStream out = new FileOutputStream(job.temp)) {
      if (in == null) throw new IOException("相册没有返回可读取的照片");
      copyStream(in, out, job, index, total, generation);
    } catch (android.os.OperationCanceledException error) {
      throw error;
    } catch (IOException | RuntimeException error) {
      IOException fallback = asIOException(error);
      if (first != null) fallback.addSuppressed(first);
      throw fallback;
    }
    if (job.copied == 0) throw new IOException("所选照片没有可读取的内容");
    job.check();
  }

  private void copyStream(
      InputStream in, OutputStream out, PhotoImportJob job, int index, int total, int generation)
      throws IOException {
    job.input = in;
    job.copied = 0;
    try {
      job.check();
      job.stage("复制原照片");
      byte[] buffer = new byte[65536];
      int n;
      long lastProgressPost = 0;
      while ((n = in.read(buffer)) != -1) {
        job.check();
        out.write(buffer, 0, n);
        job.copied += n;
        job.lastProgress = SystemClock.elapsedRealtime();
        if (job.lastProgress - lastProgressPost >= 100) {
          lastProgressPost = job.lastProgress;
          float fraction =
              job.total > 0 ? Math.min(.92f, job.copied / (float) job.total * .92f) : .08f;
          ui(
              () -> {
                if (generation == batchImportGeneration)
                  setHomeImportProgress(index, total, fraction);
              });
        }
      }
      job.check();
    } finally {
      job.input = null;
    }
  }

  private static IOException asIOException(Throwable error) {
    return error instanceof IOException
        ? (IOException) error
        : new IOException(error.getMessage() == null ? error.toString() : error.getMessage(), error);
  }

  private void finishBatchImport(
      ArrayList<PendingImport> imported, int failed, int generation) {
    if (destroyed) return;
    if (generation != batchImportGeneration) {
      deletePendingImports(imported);
      return;
    }
    batchImportRunning = false;
    main.post(this::pumpHomeThumbnails);
    activeImport = null;
    ArrayList<Frame> added = new ArrayList<>();
    for (PendingImport item : imported) {
      Frame frame =
          new Frame(item.id, item.name, item.path, pristineParamsJson());
      frame.sourceTimestamp = item.sourceTimestamp;
      frame.importedAt = System.currentTimeMillis();
      frame.sourceMediaId = String.valueOf(item.mediaId);
      frame.edited = false;
      library.add(frame);
      added.add(frame);
    }
    sortLibrary();
    if (!added.isEmpty() && !writeLibrary()) {
      library.removeAll(added);
      sortLibrary();
      deletePendingImports(imported);
      historySignature = null;
      refreshHistory();
      setHomeImportHint("照片导入失败 · 本次导入未保存");
      resetHomeImportButton();
      new Haptics.DialogBuilder(this)
          .setTitle("照片导入失败")
          .setMessage("无法保存图库记录。本次导入的照片没有加入应用，请检查设备剩余空间后重试。")
          .setPositiveButton("知道了", null)
          .show();
      return;
    }
    syncHeroLook();
    refreshHistory();
    if (imported.isEmpty()) {
      setHomeImportHint("没有导入照片 · 可重新选择");
      resetHomeImportButton();
      new Haptics.DialogBuilder(this)
          .setTitle("照片导入未完成")
          .setMessage(
              failed > 0
                  ? failed + " 张照片未能读取。不支持的视频、损坏文件或无法解码的照片会被跳过。"
                  : "没有可导入的照片。")
          .setPositiveButton("知道了", null)
          .show();
      return;
    }
    setHomeImportHint("已导入 " + imported.size() + " 张 · 点击任意照片即可编辑");
    completeHomeImportProgress(imported.size(), failed);
    if (failed > 0) {
      new Haptics.DialogBuilder(this)
          .setTitle("部分照片已导入")
          .setMessage(
              "已导入 "
                  + imported.size()
                  + " 张照片，并停留在首页。\n\n另有 "
                  + failed
                  + " 张未能导入，已跳过。")
          .setPositiveButton("知道了", null)
          .show();
    }
  }

  private void cancelBatchImport() {
    if (!batchImportRunning) return;
    ++batchImportGeneration;
    batchImportRunning = false;
    main.post(this::pumpHomeThumbnails);
    PhotoImportJob job = activeImport;
    activeImport = null;
    if (job != null) job.cancel();
    resetHomeImportButton();
    setHomeImportHint(idleHomeHint());
    showNotice("已取消导入");
  }

  private void resetHomeImportButton() {
    if (homeImportButton == null) return;
    if (homeImportProgress != null) homeImportProgress.setAnimating(false);
    if (homeImportCancel != null) homeImportCancel.setVisibility(View.GONE);
    if (homeImportHint != null) homeImportHint.setVisibility(View.VISIBLE);
    homeImportButton.setEnabled(!exporting);
    AppStyle.pillAction(homeImportButton, AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    AppStyle.setTypeface(homeImportButton, AppStyle.FONT_BOLD);
    homeImportButton.setTextSize(AppStyle.TYPE_TITLE);
    homeImportButton.setPadding(dp(14), dp(8), dp(14), dp(8));
    homeImportButton.setGravity(Gravity.CENTER);
    homeImportButton.setContentDescription(null);
    homeImportButton.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
    homeImportSlot.setLabel("开始导入照片", "开始导入照片");
  }

  private void startHomeImportProgress(int total) {
    if (homeImportProgress == null || homeImportButton == null) return;
    homeImportButton.setEnabled(false);
    homeImportButton.setBackground(homeImportProgress);
    homeImportButton.setTextColor(INK);
    homeImportButton.setTextSize(AppStyle.TYPE_BODY);
    homeImportButton.setGravity(Gravity.CENTER);
    homeImportButton.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    homeImportCancel.setVisibility(View.VISIBLE);
    if (homeImportHint != null) homeImportHint.setVisibility(View.GONE);
    homeImportProgress.setFraction(0);
    homeImportProgress.setAnimating(true);
    updateHomeImportLabel(String.format(Locale.CHINA, "正在导入 0 / %d", total));
  }

  private void updateHomeImportLabel(String label) {
    String prefix = "正在导入 ";
    homeImportSlot.setLabel(label, label.startsWith(prefix) ? label.substring(prefix.length()) : label);
  }

  private void setHomeImportProgress(int index, int total, float currentFraction) {
    if (homeImportProgress == null || homeImportButton == null) return;
    float overall =
        Math.max(0, Math.min(1, (index + Math.max(0, Math.min(1, currentFraction))) / total));
    String label =
        String.format(
            Locale.CHINA,
            "正在导入 %d / %d",
            Math.min(total, index + 1),
            total);
    homeImportProgress.setFraction(overall);
    updateHomeImportLabel(label);
  }

  private void completeHomeImportProgress(int imported, int failed) {
    if (homeImportProgress == null || homeImportButton == null) return;
    homeImportProgress.setFraction(1);
    homeImportProgress.setAnimating(false);
    homeImportCancel.setVisibility(View.GONE);
    homeImportButton.setPadding(dp(14), dp(8), dp(14), dp(8));
    String label =
        failed > 0
            ? String.format(Locale.CHINA, "已导入 %d 张 · %d 张跳过", imported, failed)
            : String.format(Locale.CHINA, "已导入 %d 张", imported);
    updateHomeImportLabel(label);
    final int generation = batchImportGeneration;
    main.postDelayed(
        () -> {
          if (!batchImportRunning && generation == batchImportGeneration)
            resetHomeImportButton();
        },
        520);
  }

  /** Empty-library home keeps the area clear; only import progress or failures use this line. */
  private String idleHomeHint() {
    return "";
  }

  private void setHomeImportHint(String value) {
    if (homeImportHint == null) return;
    boolean visible = value != null && !value.isEmpty();
    homeImportHint.setText(visible ? value : "");
    homeImportHint.setVisibility(visible ? View.VISIBLE : View.GONE);
  }

  private void showNotice(String message) {
    showNotice(message, false);
  }

  private void showNotice(String message, boolean longer) {
    AppNotice.show(this, message, noticeBottomDp(), longer);
  }

  private int batchPasteHeight() {
    return batchPasteShell != null && batchPasteShell.getHeight() > 0
        ? batchPasteShell.getHeight() : dp(70);
  }

  private int noticeBottomDp() {
    return selectionActive()
        ? (int) Math.ceil(batchPasteHeight() / getResources().getDisplayMetrics().density) + 34
        : 44;
  }

  private void updateBatchPasteInsets() {
    if (homeContent != null) {
      int bottom = selectionActive() ? batchPasteHeight() + dp(18) : dp(28);
      if (homeContent.getPaddingBottom() != bottom)
        homeContent.setPadding(homeContent.getPaddingLeft(), homeContent.getPaddingTop(),
            homeContent.getPaddingRight(), bottom);
    }
    AppNotice.setBottomMargin(getWindow(), noticeBottomDp());
  }

  private void updateBatchPasteBar(boolean animate) {
    if (batchPasteBar == null) return;
    View shell = batchPasteShell == null ? batchPasteBar : batchPasteShell;
    updateBatchPasteInsets();
    shell.animate().cancel();
    if (selectionActive()) {
      boolean firstShow = shell.getVisibility() != View.VISIBLE;
      shell.setVisibility(View.VISIBLE);
      if (!animate) {
        shell.setAlpha(1f);
        shell.setTranslationY(0);
      } else {
        if (firstShow) {
          shell.setAlpha(0f);
          shell.setTranslationY(dp(12));
        }
        shell.animate().alpha(1f).translationY(0).setDuration(180).start();
      }
    } else if (shell.getVisibility() == View.VISIBLE) {
      if (!animate) {
        shell.setVisibility(View.GONE);
        shell.setAlpha(1f);
        shell.setTranslationY(0);
        resetActionRows();
      } else
        shell
            .animate()
            .alpha(0f)
            .translationY(dp(8))
            .setDuration(140)
            .withEndAction(
                () -> {
                  if (!selectionActive()) {
                    shell.setVisibility(View.GONE);
                    shell.setAlpha(1f);
                    shell.setTranslationY(0);
                    resetActionRows();
                  }
                })
            .start();
    }
  }

  private void resetActionRows() {
    if (batchPasteBar != null && batchPasteBar.getParent() instanceof View pasteRow)
      pasteRow.setVisibility(View.VISIBLE);
    if (manageActionRow != null) manageActionRow.setVisibility(View.GONE);
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
            copyProviderPhoto(uri, job, 0, 1, -1);
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
            Frame importedFrame =
                new Frame(id, filename, original.getAbsolutePath(), settings.toString());
            importedFrame.edited = job.initialSettings != null;
            importedFrame.importedAt = System.currentTimeMillis();
            library.add(importedFrame);
            sortLibrary();
            syncHeroLook();
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
      j.put("app_version", getPackageVersion());
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
              showNotice("诊断已复制");
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
                  // The banner follows the newest library photo; only an empty library shows demo.
                  adopt(b, false);
                  syncHeroLook();
                  activeId = "demo";
                  prefs.edit().putString("activeId", "demo").apply();
                  developed = null;
                  filename = demoName();
                  fileLabel.setText(filename);
                  photo.images(source, null);
                  photo.resetZoom();
                  undoStack.clear();
                  redoStack.clear();
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
          if (original.exists()) {
            Frame frame =
                new Frame(
                    id, j.getString("name"), original.getAbsolutePath(), j.getString("params"));
            frame.favorite = j.optBoolean("favorite", false);
            frame.sourceTimestamp =
                j.optLong("sourceTimestamp", Math.max(0, original.lastModified()));
            frame.importedAt = j.optLong("importedAt", Math.max(0, original.lastModified()));
            frame.sourceMediaId = j.optString("sourceMediaId", "");
            frame.edited = j.has("edited") ? j.optBoolean("edited", true) : true;
            library.add(frame);
          }
        }
      } catch (Exception ex) {
        android.util.Log.e("SpektraLensAndroid", "Read library", ex);
      }
    sortLibrary();
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

  private void sortLibrary() {
    // 最近照片按“最近导入”优先；同一批导入再按拍摄时间新到旧排列。
    library.sort(
        Comparator.comparingLong((Frame frame) -> frame.importedAt)
            .thenComparingLong(frame -> frame.sourceTimestamp)
            .reversed());
  }

  private void saveActiveFrame() {
    Frame frame = findFrame(activeId);
    if (frame != null) {
      String params = settings.toString();
      if (!params.equals(frame.params)) {
        homeThumbJobs.invalidate(frame.id);
        homeImages.removeDeveloped(frame.id);
        File look = developedLook(frame.id);
        if (look != null) look.delete();
      }
      frame.params = params;
      scheduleLibraryWrite();
    }
  }

  private void saveLibrary() {
    if (libraryWritePending != null) {
      main.removeCallbacks(libraryWritePending);
      libraryWritePending = null;
    }
    libraryDirty = false;
    if (!writeLibrary()) {
      libraryDirty = true;
      showNotice("编辑记录保存失败，请检查剩余空间", true);
    }
  }

  private boolean writeLibrary() {
    return writeLibrary(librarySnapshot(), ++libraryWriteVersion);
  }

  private JSONArray librarySnapshot() {
    JSONArray list = new JSONArray();
    try {
      for (Frame frame : library) {
        JSONObject j = new JSONObject();
        j.put("id", frame.id);
        j.put("name", frame.name);
        j.put("params", frame.params);
        j.put("favorite", frame.favorite);
        j.put("sourceTimestamp", frame.sourceTimestamp);
        j.put("importedAt", frame.importedAt);
        j.put("sourceMediaId", frame.sourceMediaId);
        j.put("edited", frame.edited);
        list.put(j);
      }
    } catch (JSONException ex) {
      throw new IllegalStateException(ex);
    }
    return list;
  }

  private boolean writeLibrary(JSONArray list, long version) {
    synchronized (libraryFileLock) {
      if (version < libraryWrittenVersion) return true;
      try {
        File temp = new File(getFilesDir(), "library.pending");
        try (FileOutputStream out = new FileOutputStream(temp)) {
          out.write(list.toString().getBytes(StandardCharsets.UTF_8));
          out.getFD().sync();
        }
        if (!temp.renameTo(new File(getFilesDir(), "library.json")))
          throw new IOException("Cannot write library");
        libraryWrittenVersion = version;
        return true;
      } catch (Exception ex) {
        android.util.Log.e("SpektraLensAndroid", "Save library", ex);
        new File(getFilesDir(), "library.pending").delete();
        return false;
      }
    }
  }

  private void scheduleLibraryWrite() {
    libraryDirty = true;
    if (libraryWritePending != null) main.removeCallbacks(libraryWritePending);
    libraryWritePending =
        () -> {
          libraryWritePending = null;
          if (!libraryDirty || destroyed) return;
          JSONArray snapshot = librarySnapshot();
          long version = ++libraryWriteVersion;
          libraryDirty = false;
          libraryWritesPending.incrementAndGet();
          try {
            libraryWriter.execute(
                () -> {
                  boolean ok = false;
                  try {
                    ok = writeLibrary(snapshot, version);
                  } finally {
                    libraryWritesPending.decrementAndGet();
                  }
                  if (!ok) {
                    libraryDirty = true;
                    ui(() -> showNotice("编辑记录保存失败，请检查剩余空间", true));
                  }
                });
          } catch (RuntimeException ex) {
            libraryWritesPending.decrementAndGet();
            libraryDirty = true;
            android.util.Log.e("SpektraLensAndroid", "Queue library write", ex);
          }
        };
    main.postDelayed(libraryWritePending, 300);
  }

  private void flushLibraryWrite() {
    if (libraryWritePending != null) {
      main.removeCallbacks(libraryWritePending);
      libraryWritePending = null;
    }
    if (!libraryDirty && libraryWritesPending.get() == 0) return;
    JSONArray snapshot = librarySnapshot();
    long version = ++libraryWriteVersion;
    libraryDirty = false;
    if (!writeLibrary(snapshot, version)) {
      libraryDirty = true;
      showNotice("编辑记录保存失败，请检查剩余空间", true);
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

  private void refreshHistory() {
    if (historyList == null) return;
    EditClipboard clipboard = loadEditClipboard();
    batchSelection.removeIf(id -> findFrame(id) == null);
    manageSelection.removeIf(id -> findFrame(id) == null);
    updateHistoryHeader();
    updateBatchControls(clipboard);
    // Rebuilding every card also rebuilds every ImageView, so returning from the editor used to
    // re-decode the whole strip and visibly reshuffle it. Only rebuild when something changed.
    StringBuilder signature = new StringBuilder();
    for (Frame frame : library)
      signature.append(frame.id).append(':').append(frame.path).append(':')
          .append(frame.params).append(':').append(frame.favorite).append(':')
          .append(frame.edited).append(';');
    signature
        .append("clip=")
        .append(clipboard == null ? "" : clipboard.sourceId)
        .append(";batch=")
        .append(batchPasteMode)
        .append(";manage=")
        .append(manageMode);
    String current = signature.toString();
    if (current.equals(historySignature) && historyList.getChildCount() > 0) return;
    historySignature = current;
    controls.removeIf(v -> descendant(v, historyList));
    homeImages.clearCards();
    historyList.removeAllViews();
    batchCardViews.clear();
    if (library.isEmpty()) {
      LinearLayout empty = vertical();
      empty.setPadding(dp(16), dp(15), dp(16), dp(15));
      AppStyle.surface(empty, AppStyle.SURFACE, AppStyle.R_MEDIUM);
      TextView title = text("你的第一张照片，还在等光。", AppStyle.TYPE_BODY, INK);
      add(empty, title, -1, 26);
      TextView detail = text("导入照片后，点击任意照片即可开始编辑。", AppStyle.TYPE_MICRO, MUTED);
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
        FrameLayout photoBox = new FrameLayout(this);
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        photoBox.addView(image, new FrameLayout.LayoutParams(-1, -1));
        homeImages.bind(
            image,
            new File(frame.path),
            developedLook(frame.id),
            new File(new File(getFilesDir(), "thumbnails"), frame.id + ".jpg"),
            false);
        boolean selectedForBatch = selectionContains(frame);
        View scrim = new View(this);
        scrim.setBackgroundColor(0x66000000);
        scrim.setVisibility(selectedForBatch ? View.VISIBLE : View.GONE);
        photoBox.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        ImageView favorite = null;
        FrameLayout.LayoutParams favoriteParams = null;
        if (frame.favorite) {
          favorite = new ImageView(this);
          favorite.setImageResource(R.drawable.ic_material_favorite);
          favorite.setPadding(0, 0, 0, 0);
          favorite.setBackground(null);
          favorite.setContentDescription("已收藏");
          favorite.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
          favoriteParams =
              new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.END);
          favoriteParams.topMargin = dp(9);
          favoriteParams.rightMargin = dp(selectionActive() ? 16 + AppStyle.SELECTION_BADGE : 8);
          photoBox.addView(favorite, favoriteParams);
        }
        if (clipboard != null
            && clipboard.valid()
            && clipboard.sourceId.equals(frame.id)) {
          TextView copied = text("已复制", AppStyle.TYPE_MICRO, GOLD);
          copied.setGravity(Gravity.CENTER);
          AppStyle.setTypeface(copied, AppStyle.FONT_BOLD);
          copied.setPadding(dp(10), 0, dp(10), 0);
          copied.setBackground(AppStyle.pill(this, 0xcc16141a, false));
          FrameLayout.LayoutParams copiedParams =
              new FrameLayout.LayoutParams(-2, dp(28), Gravity.TOP | Gravity.START);
          copiedParams.topMargin = dp(8);
          copiedParams.leftMargin = dp(8);
          photoBox.addView(copied, copiedParams);
        }
        ImageView checked = new ImageView(this);
        checked.setImageResource(R.drawable.ic_material_check);
        checked.setContentDescription("已选择");
        checked.setVisibility(selectionActive() ? View.VISIBLE : View.GONE);
        FrameLayout.LayoutParams checkedParams =
            new FrameLayout.LayoutParams(
                dp(AppStyle.SELECTION_BADGE), dp(AppStyle.SELECTION_BADGE), Gravity.TOP | Gravity.END);
        checkedParams.topMargin = dp(8);
        checkedParams.rightMargin = dp(8);
        photoBox.addView(checked, checkedParams);
        styleBatchCheck(checked, selectedForBatch);
        batchCardViews.put(
            frame.id, new BatchCardViews(photoBox, scrim, favoriteParams, checked));
        add(card, photoBox, -1, 125);
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
                AppStyle.TYPE_MICRO,
                MUTED);
        AppStyle.setNumericTypeface(details);
        add(names, details, -1, 22);
        String stock = frame.edited ? "已保存调整" : "暂未编辑";
        if (frame.edited)
          try {
            Stock s = find(films, new JSONObject(frame.params).optString("film_stock"));
            if (s != null) stock = shortName(s.name);
          } catch (Exception ignored) {
            // 历史记录引用的胶片已不存在，保留原来的标签
          }
        TextView look = text(stock, AppStyle.TYPE_MICRO, frame.edited ? GOLD : MUTED);
        look.setSingleLine(true);
        look.setEllipsize(android.text.TextUtils.TruncateAt.END);
        add(names, look, -1, 24);
        add(card, names, -1, -2);
        photoBox.setClickable(true);
        photoBox.setFocusable(true);
        photoBox.setContentDescription(
            selectionActive()
                ? (selectedForBatch ? "取消选择 " : "选择 ") + PhotoLabels.title(frame.name)
                : "编辑 " + PhotoLabels.title(frame.name));
        photoBox.setOnClickListener(
            v -> {
              if (manageMode) toggleManageSelection(frame);
              else if (batchPasteMode) toggleBatchSelection(frame);
              else editFrameFromHome(frame);
            });
        photoBox.setOnLongClickListener(
            v -> {
              if (selectionActive()) return true;
              v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
              showFrameActions(frame);
              return true;
            });
        card.setOnLongClickListener(
            v -> {
              if (selectionActive()) return true;
              v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
              showFrameActions(frame);
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

  private boolean selectionActive() {
    return manageMode || batchPasteMode;
  }

  private boolean selectionContains(Frame frame) {
    return manageMode
        ? manageSelection.contains(frame.id)
        : batchPasteMode && batchSelection.contains(frame.id);
  }

  private void updateHistoryHeader() {
    if (historyTitle == null) return;
    String suffix = " · " + library.size() + " 张";
    android.text.SpannableStringBuilder label =
        new android.text.SpannableStringBuilder("最近照片").append(suffix);
    int start = label.length() - suffix.length();
    label.setSpan(
        new android.text.style.RelativeSizeSpan(
            AppStyle.TYPE_MICRO / (float) AppStyle.TYPE_HEADING),
        start,
        label.length(),
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    label.setSpan(
        new android.text.style.ForegroundColorSpan(MUTED),
        start,
        label.length(),
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    label.setSpan(
        new android.text.style.StyleSpan(android.graphics.Typeface.NORMAL),
        start,
        label.length(),
        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    historyTitle.setText(label);
    historyTitle.setContentDescription("最近照片，共 " + library.size() + " 张");
    if (homeManage != null)
      homeManage.setVisibility(
          library.isEmpty() || selectionActive() ? View.GONE : View.VISIBLE);
    if (manageHeaderActions != null)
      manageHeaderActions.setVisibility(manageMode ? View.VISIBLE : View.GONE);
    if (homeClearSelection != null) {
      boolean canClear = manageMode && !manageSelection.isEmpty();
      homeClearSelection.setEnabled(canClear);
      homeClearSelection.setTextColor(canClear ? GOLD : AppStyle.DISABLED);
    }
  }

  private void enterManageMode() {
    if (exporting || batchImportRunning || activeImport != null || library.isEmpty()) return;
    if (batchPasteMode) exitBatchPaste();
    manageMode = true;
    manageSelection.clear();
    historySignature = null;
    refreshHistory();
    updateBatchPasteBar(true);
  }

  private void exitManageMode() {
    if (!manageMode) return;
    manageMode = false;
    manageSelection.clear();
    historySignature = null;
    refreshHistory();
    updateBatchPasteBar(true);
  }

  private void toggleManageSelection(Frame frame) {
    if (!manageSelection.remove(frame.id)) manageSelection.add(frame.id);
    updateBatchSelectionVisual(frame, true);
    updateBatchControls(loadEditClipboard());
    updateHistoryHeader();
  }

  private void selectAllPhotos() {
    if (!manageMode || library.isEmpty()) return;
    boolean changed = false;
    for (Frame frame : library) changed |= manageSelection.add(frame.id);
    if (changed) refreshManageSelectionVisuals(false);
    updateBatchControls(loadEditClipboard());
    updateHistoryHeader();
  }

  private void clearManageSelection() {
    if (!manageMode || manageSelection.isEmpty()) return;
    manageSelection.clear();
    refreshManageSelectionVisuals(false);
    updateBatchControls(loadEditClipboard());
    updateHistoryHeader();
  }

  private void refreshManageSelectionVisuals(boolean animate) {
    for (Frame frame : library) updateBatchSelectionVisual(frame, animate);
  }

  private void confirmManageDelete() {
    if (exporting || !manageMode || manageSelection.isEmpty()) return;
    new Haptics.DialogBuilder(this)
        .setTitle("删除所选照片？")
        .setMessage("删除应用内的照片副本与编辑记录。手机相册中的原照片不受影响。")
        .setPositiveButton("删除", (d, n) -> removeSelectedPhotos())
        .setNegativeButton("取消", null)
        .show();
  }

  private void removeSelectedPhotos() {
    if (exporting) return;
    ArrayList<Frame> targets = new ArrayList<>();
    for (String id : new ArrayList<>(manageSelection)) {
      Frame frame = findFrame(id);
      if (frame != null) targets.add(frame);
    }
    if (targets.isEmpty()) {
      exitManageMode();
      return;
    }
    ArrayList<Frame> previous = new ArrayList<>(targets);
    library.removeAll(targets);
    if (!writeLibrary()) {
      library.addAll(previous);
      sortLibrary();
      historySignature = null;
      refreshHistory();
      showNotice("删除失败，照片仍保留在应用中");
      return;
    }
    EditClipboard clipboard = loadEditClipboard();
    boolean clearClipboard = clipboard != null;
    boolean activeDeleted = false;
    for (Frame frame : targets) {
      if (clearClipboard && clipboard != null && frame.id.equals(clipboard.sourceId)) {
        prefs.edit().remove("edit_clipboard").apply();
        clearClipboard = false;
      }
      if (activeId.equals(frame.id)) activeDeleted = true;
      removeFrameArtifacts(frame);
    }
    manageSelection.clear();
    manageMode = false;
    historySignature = null;
    if (library.isEmpty()) {
      new File(getFilesDir(), "source.img").delete();
      loadDemoAction();
    } else if (activeDeleted) {
      loadDemoAction();
    }
    refreshHistory();
    syncHeroLook();
    updateBatchPasteBar(true);
    showHome();
  }

  /** Removes one photo's private copy and every derived preview. Library records stay untouched. */
  private void removeFrameArtifacts(Frame frame) {
    batchSelection.remove(frame.id);
    manageSelection.remove(frame.id);
    homeThumbJobs.invalidate(frame.id);
    String photoKey = ThumbnailDiskCache.key(new File(frame.path).getAbsolutePath());
    removedGalleryPhotos.add(photoKey);
    thumbnailIo.execute(() -> filmDiskCache.removePhoto(photoKey));
    homeImages.removeDeveloped(frame.id);
    new File(frame.path).delete();
    new File(new File(getFilesDir(), "thumbnails"), frame.id + ".jpg").delete();
    new File(new File(getFilesDir(), "developed"), frame.id + ".jpg").delete();
    homeImages.remove(new File(frame.path));
  }

  private void updateBatchControls(EditClipboard clipboard) {
    if (batchPasteBar != null) {
      batchPasteBar.setText(EditClipboardPolicy.batchButtonLabel(batchSelection.size()));
      batchPasteBar.setEnabled(
          EditClipboardPolicy.canBatchPaste(
              clipboard != null && clipboard.valid(), batchSelection.size()));
      if (batchPasteBar.getParent() instanceof View pasteRow) {
        if (manageMode) pasteRow.setVisibility(View.GONE);
        else if (batchPasteMode) pasteRow.setVisibility(View.VISIBLE);
      }
    }
    if (manageActionRow != null) {
      if (manageMode) manageActionRow.setVisibility(View.VISIBLE);
      else if (batchPasteMode) manageActionRow.setVisibility(View.GONE);
    }
    if (manageDeleteBar != null) {
      manageDeleteBar.setText(
          String.format(Locale.CHINA, "删除 · %d 张", manageSelection.size()));
      manageDeleteBar.setEnabled(manageMode && !manageSelection.isEmpty() && !exporting);
    }
    updateBatchPasteBar(true);
    if (selectionActive()) setHomeImportHint("");
    else if (!batchImportRunning) setHomeImportHint(idleHomeHint());
  }

  private EditClipboard loadEditClipboard() {
    String raw = prefs.getString("edit_clipboard", "");
    if (raw == null || raw.isBlank()) return null;
    try {
      JSONObject value = new JSONObject(raw);
      EditClipboard clipboard =
          new EditClipboard(
              value.optString("source_id"),
              value.optString("source_name"),
              value.optString("params"));
      return clipboard.valid() ? clipboard : null;
    } catch (JSONException ignored) {
      return null;
    }
  }

  private boolean saveEditClipboard(Frame frame) {
    try {
      JSONObject value = new JSONObject();
      value.put("source_id", frame.id);
      value.put("source_name", frame.name);
      value.put("params", frame.params);
      boolean saved = prefs.edit().putString("edit_clipboard", value.toString()).commit();
      if (!saved) showNotice("无法保存编辑参数副本");
      return saved;
    } catch (JSONException error) {
      showNotice("无法复制编辑参数");
      return false;
    }
  }

  private void clearEditClipboard() {
    if (!prefs.edit().remove("edit_clipboard").commit()) {
      showNotice("无法清除编辑参数副本");
      return;
    }
    historySignature = null;
    refreshHistory();
  }

  private void showFrameActions(Frame frame) {
    if (batchImportRunning || exporting) return;
    saveActiveFrame();
    EditClipboard clipboard = loadEditClipboard();
    boolean canPaste = clipboard != null && clipboard.valid();
    boolean isSource = canPaste && frame.id.equals(clipboard.sourceId);
    ArrayList<LibraryActionSheet.ActionItem> items = new ArrayList<>();
    items.add(
        new LibraryActionSheet.ActionItem(
            LibraryActionSheet.EDIT,
            EditClipboardPolicy.editMenuLabel(frame.edited),
            "",
            "",
            true,
            false));
    items.add(
        new LibraryActionSheet.ActionItem(
            LibraryActionSheet.COPY,
            isSource ? "清除已复制编辑参数" : "复制编辑参数",
            isSource ? "移除当前复制的编辑参数" : "保存这张照片的全部调整",
            isSource ? "当前来源" : "",
            true,
            false));
    items.add(
        new LibraryActionSheet.ActionItem(
            LibraryActionSheet.PASTE,
            "粘贴编辑参数",
            "应用到当前照片",
            canPaste ? "可粘贴" : "未复制",
            canPaste,
            false));
    items.add(
        new LibraryActionSheet.ActionItem(
            LibraryActionSheet.BATCH_PASTE,
            "批量粘贴编辑参数",
            "一次最多选择 20 张照片",
            canPaste ? "可多选" : "未复制",
            canPaste,
            false));
    items.add(
        new LibraryActionSheet.ActionItem(
            LibraryActionSheet.FAVORITE,
            frame.favorite ? "取消收藏" : "添加收藏",
            "",
            "",
            true,
            false));
    items.add(
        new LibraryActionSheet.ActionItem(
            LibraryActionSheet.EXPORT, "导出照片", "", "", true, false));
    items.add(
        new LibraryActionSheet.ActionItem(
            LibraryActionSheet.DELETE, "删除照片", "", "", true, true));
    if (frameActions != null && frameActions.isShowing()) frameActions.dismiss();
    LibraryActionSheet sheet =
        new LibraryActionSheet(
            this, items,
            action -> {
              switch (action) {
                case LibraryActionSheet.EDIT -> editFrameFromHome(frame);
                case LibraryActionSheet.COPY -> {
                  if (isSource) clearEditClipboard();
                  else if (saveEditClipboard(frame)) {
                    historySignature = null;
                    refreshHistory();
                    showNotice("编辑参数已复制");
                  }
                }
                case LibraryActionSheet.PASTE -> {
                  if (canPaste) pasteToFrame(frame);
                }
                case LibraryActionSheet.BATCH_PASTE -> {
                  if (canPaste) beginBatchPaste();
                }
                case LibraryActionSheet.FAVORITE -> toggleFavorite(frame);
                case LibraryActionSheet.EXPORT -> exportFrame(frame);
                case LibraryActionSheet.DELETE -> confirmRemove(frame);
                default -> {
                  // Every visible row maps to one declared library action.
                }
              }
            });
    sheet.setOnDismissListener(
        dialog -> {
          if (frameActions == sheet) frameActions = null;
        });
    frameActions = sheet;
    sheet.show();
  }

  private void toggleFavorite(Frame frame) {
    frame.favorite = !frame.favorite;
    if (!writeLibrary()) {
      frame.favorite = !frame.favorite;
      showNotice("收藏状态保存失败");
      return;
    }
    historySignature = null;
    refreshHistory();
  }

  private void pasteToFrame(Frame frame) {
    EditClipboard clipboard = loadEditClipboard();
    if (clipboard == null || !clipboard.valid()) {
      showNotice("还没有复制编辑参数");
      return;
    }
    try {
      new JSONObject(clipboard.params);
    } catch (JSONException error) {
      showNotice("复制参数已损坏，请重新复制");
      clearEditClipboard();
      return;
    }
    saveActiveFrame();
    String previous = frame.params;
    boolean previousEdited = frame.edited;
    applyPastedParams(frame, clipboard.params);
    if (!writeLibrary()) {
      applyPastedParams(frame, previous);
      frame.edited = previousEdited;
      refreshPastedThumbnails(Collections.singletonList(frame));
      showNotice("粘贴失败，未修改照片");
      return;
    }
    historySignature = null;
    refreshHistory();
    refreshPastedThumbnails(Collections.singletonList(frame));
    showNotice("已粘贴编辑参数");
  }

  private void applyPastedParams(Frame frame, String params) {
    boolean unchanged = params.equals(frame.params);
    frame.edited = true;
    File look = developedLook(frame.id);
    if (unchanged && ((look != null && look.isFile()) || homeImages.isWriting(frame.id)
        || homeThumbJobs.contains(frame.id, params)
        || (frame.id.equals(activeId) && mainPreviewPending()))) return;
    homeThumbJobs.invalidate(frame.id);
    frame.params = params;
    homeImages.removeDeveloped(frame.id);
    new File(new File(getFilesDir(), "developed"), frame.id + ".jpg").delete();
    if (frame.id.equals(activeId)) {
      try {
        settings = new JSONObject(params);
        defaults();
        committed = settings.toString();
        filmCategory = category(find(films, settings.optString("film_stock")));
        undoStack.clear();
        redoStack.clear();
        prefs.edit().putString("settings", settings.toString()).apply();
        if (editorScreen != null && editorScreen.getVisibility() == View.VISIBLE) {
          buildPanel();
        } else {
          editorNeedsSync = true;
        }
        scheduleRender();
      } catch (JSONException ignored) {
        // The clipboard is validated before this method is called.
      }
    }
  }

  /** Enqueue latest recipes only; each completion returns control to the main-preview queue. */
  private void refreshPastedThumbnails(List<Frame> candidates) {
    if (candidates == null) return;
    for (Frame frame : candidates) {
      if (frame == null || frame.id.equals(activeId) || frame.params == null) continue;
      File look = developedLook(frame.id);
      if ((look != null && look.isFile()) || homeImages.isWriting(frame.id)) continue;
      homeThumbJobs.offer(frame.id, frame.params);
    }
    pumpHomeThumbnails();
  }

  /** A killed process may have saved recipes before their derived JPEGs finished writing. */
  private void queueMissingHomeThumbnails() {
    if (homeImages == null || destroyed) return;
    ArrayList<Frame> missing = new ArrayList<>();
    for (Frame frame : library) {
      File look = developedLook(frame.id);
      if (!frame.edited || (look != null && look.isFile()) || homeImages.isWriting(frame.id)) continue;
      if (!frame.id.equals(activeId)) missing.add(frame);
      else if (!homeThumbPaused && ready && source != null && !mainPreviewPending()) {
        boolean valid = developed != null && frame.id.equals(developedPhotoId)
            && frame.params.equals(developedRecipe);
        if (!valid) scheduleRender();
      }
    }
    refreshPastedThumbnails(missing);
  }

  private void pumpHomeThumbnails() {
    if (destroyed || homeThumbPaused || !ready || renderer == null || homeImages == null
        || exporting || activeImport != null || batchImportRunning || mainPreviewPending()
        || homeImages.pendingWrites() >= 2) return;
    ThumbnailWorkQueue.Job job = homeThumbJobs.poll(homeImages::visibilityRank);
    if (job == null) return;
    Frame frame = findFrame(job.id);
    if (frame == null || !job.params.equals(frame.params) || frame.id.equals(activeId)) {
      homeThumbJobs.invalidate(job.id);
      homeThumbJobs.finish(job);
      main.post(this::pumpHomeThumbnails);
      return;
    }
    final String path = frame.path;
    final SpectralRenderer activeRenderer = renderer;
    final int rendererGeneration = engineBootGeneration;
    worker.execute(() -> {
      Bitmap made = null;
      boolean retry = false;
      try {
        if (!destroyed && homeThumbJobs.isCurrent(job)) {
          retry = homeThumbPaused || mainPreviewPending() || exporting || batchImportRunning
              || activeImport != null || activeRenderer != renderer
              || rendererGeneration != engineBootGeneration;
          if (!retry) {
            JSONObject metadata = readPhotoMetadata(new File(path));
            boolean raw = metadata.optBoolean("raw");
            JSONObject recipe = new JSONObject(job.params);
            Stock stock = find(films, recipe.optString("film_stock"));
            JSONObject engineRecipe = new JSONObject(params(recipe, stock, raw));
            engineRecipe.put("preview_long_edge", 1024);
            String engineParams = engineRecipe.toString();
            openedSource = null;
            try {
              if (raw) activeRenderer.openRaw(path, openParams(engineParams), 1024);
              else activeRenderer.openFile(path, openParams(engineParams), 1024);
              made = activeRenderer.render(engineParams, editParams(recipe, metadata), false, 1024);
              ++homeThumbRenderCount;
            } finally {
              openedSource = null;
            }
          }
        }
      } catch (Throwable ex) {
        android.util.Log.w("SpektraLensAndroid", "Refresh pasted home thumbnail for " + job.id, ex);
      }
      final Bitmap result = made;
      final boolean again = retry;
      thumbnailDeliveries.post(main, result, () -> {
        boolean sameEngine = activeRenderer == renderer && rendererGeneration == engineBootGeneration;
        if ((again || !sameEngine) && !destroyed) homeThumbJobs.retry(job);
        else homeThumbJobs.finish(job);
        Frame current = findFrame(job.id);
        if (!destroyed && sameEngine && homeThumbJobs.isCurrent(job) && current != null
            && job.params.equals(current.params) && result != null) {
          homeImages.storeDevelopedOwned(job.id, result, () -> homeThumbJobs.isCurrent(job));
        } else Bitmaps.release(result);
        pumpHomeThumbnails();
      });
    });
  }

  private void beginBatchPaste() {
    EditClipboard clipboard = loadEditClipboard();
    if (clipboard == null || !clipboard.valid()) {
      showNotice("还没有复制编辑参数");
      return;
    }
    if (manageMode) exitManageMode();
    batchPasteMode = true;
    batchSelection.clear();
    historySignature = null;
    refreshHistory();
    showNotice("点击缩略图勾选照片，最多选择 20 张");
  }

  private void exitBatchPaste() {
    if (!batchPasteMode) return;
    batchPasteMode = false;
    batchSelection.clear();
    historySignature = null;
    refreshHistory();
    showNotice("已退出批量选择");
  }

  private void toggleBatchSelection(Frame frame) {
    if (batchSelection.remove(frame.id)) {
      updateBatchSelectionVisual(frame, true);
      updateBatchControls(loadEditClipboard());
      return;
    }
    if (!EditClipboardPolicy.acceptsAnotherSelection(batchSelection.size())) {
      showNotice("已超过最大选择数量");
      return;
    }
    batchSelection.add(frame.id);
    updateBatchSelectionVisual(frame, true);
    updateBatchControls(loadEditClipboard());
  }

  private void updateBatchSelectionVisual(Frame frame, boolean animate) {
    BatchCardViews views = batchCardViews.get(frame.id);
    if (views == null) return;
    boolean selected = selectionContains(frame);
    views.photoBox.setContentDescription(
        (selected ? "取消选择 " : "选择 ") + PhotoLabels.title(frame.name));
    if (views.favoriteParams != null) {
      int margin = dp(selectionActive() ? 16 + AppStyle.SELECTION_BADGE : 8);
      if (views.favoriteParams.rightMargin != margin) {
        views.favoriteParams.rightMargin = margin;
        views.photoBox.requestLayout();
      }
    }
    views.scrim.animate().cancel();
    views.check.animate().cancel();
    styleBatchCheck(views.check, selected);
    if (!selectionActive()) {
      views.scrim.setVisibility(View.GONE);
      views.check.setVisibility(View.GONE);
      views.scrim.setAlpha(1f);
      views.check.setAlpha(1f);
      views.check.setScaleX(1f);
      views.check.setScaleY(1f);
      return;
    }
    views.check.setVisibility(View.VISIBLE);
    if (selected) {
      views.scrim.setVisibility(View.VISIBLE);
      if (!animate) {
        views.scrim.setAlpha(1f);
        views.check.setAlpha(1f);
        views.check.setScaleX(1f);
        views.check.setScaleY(1f);
      } else {
        views.scrim.setAlpha(0f);
        views.check.setAlpha(0f);
        views.check.setScaleX(.82f);
        views.check.setScaleY(.82f);
        views.scrim.animate().alpha(1f).setDuration(TOOL_MOTION_MS).start();
        views.check
            .animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(TOOL_MOTION_MS)
            .start();
      }
    } else if (!animate) {
      views.scrim.setVisibility(View.GONE);
      views.scrim.setAlpha(1f);
      views.check.setAlpha(1f);
      views.check.setScaleX(1f);
      views.check.setScaleY(1f);
    } else {
      views.scrim.animate().alpha(0f).setDuration(TOOL_MOTION_MS).start();
      views.check
          .animate()
          .alpha(1f)
          .scaleX(1f)
          .scaleY(1f)
          .setDuration(TOOL_MOTION_MS)
          .withEndAction(
              () -> {
                if (!selectionActive()) {
                  views.scrim.setVisibility(View.GONE);
                  views.check.setVisibility(View.GONE);
                } else if (!selectionContains(frame)) {
                  views.scrim.setVisibility(View.GONE);
                }
                views.scrim.setAlpha(1f);
                views.check.setAlpha(1f);
                views.check.setScaleX(1f);
                views.check.setScaleY(1f);
              })
          .start();
    }
  }

  private void styleBatchCheck(ImageView check, boolean selected) {
    GradientDrawable circle = new GradientDrawable();
    circle.setShape(GradientDrawable.OVAL);
    circle.setColor(selected ? GOLD : 0x66000000);
    circle.setStroke(Math.max(1, dp(selected ? 0 : 1)), Color.WHITE);
    check.setBackground(circle);
    check.setImageResource(R.drawable.ic_material_check);
    check.setImageTintList(ColorStateList.valueOf(AppStyle.ON_PRIMARY));
    check.setImageAlpha(selected ? 255 : 0);
    check.setPadding(dp(4), dp(4), dp(4), dp(4));
    check.setContentDescription(selected ? "已选择" : "未选择");
  }

  private void pasteToBatch() {
    EditClipboard clipboard = loadEditClipboard();
    if (clipboard == null || !clipboard.valid()) {
      showNotice("还没有复制编辑参数");
      return;
    }
    saveActiveFrame();
    ArrayList<Frame> targets = new ArrayList<>();
    ArrayList<String> ids = new ArrayList<>();
    for (String id : batchSelection) {
      Frame frame = findFrame(id);
      if (frame != null) {
        targets.add(frame);
        ids.add(id);
      }
    }
    if (!EditClipboardPolicy.validBatch(clipboard.params, targets, ids, clipboard.sourceId)) {
      showNotice("请选择 1 至 20 张照片");
      return;
    }
    try {
      new JSONObject(clipboard.params);
    } catch (JSONException error) {
      showNotice("复制参数已损坏，请重新复制");
      return;
    }
    LinkedHashMap<String, String> previous = new LinkedHashMap<>();
    LinkedHashMap<String, Boolean> previousEdited = new LinkedHashMap<>();
    for (Frame frame : targets) {
      previous.put(frame.id, frame.params);
      previousEdited.put(frame.id, frame.edited);
    }
    int count = targets.size();
    for (Frame frame : targets) applyPastedParams(frame, clipboard.params);
    if (!writeLibrary()) {
      for (Frame frame : targets) {
        String old = previous.get(frame.id);
        if (old != null) {
          applyPastedParams(frame, old);
          frame.edited = Boolean.TRUE.equals(previousEdited.get(frame.id));
        }
      }
      refreshPastedThumbnails(targets);
      showNotice("批量粘贴失败，照片未修改");
      return;
    }
    batchPasteMode = false;
    batchSelection.clear();
    historySignature = null;
    refreshHistory();
    refreshPastedThumbnails(targets);
    showNotice("已粘贴到 " + count + " 张照片");
  }

  private void exportFrame(Frame frame) {
    if (destroyed || exporting || !ready || frame == null) {
      showNotice("显影引擎暂不可用，请稍后重试");
      return;
    }
    saveActiveFrame();
    if (frame.id.equals(activeId) && developed != null && !developed.isRecycled()) {
      exportOptions();
      return;
    }
    final int generation = ++homeExportGeneration;
    int ticket = ++revision;
    showNotice("正在准备导出…");
    worker.execute(
        () -> {
          try {
            LoadedPhoto loaded = readPhoto(new File(frame.path), 1024);
            try {
              saveThumbnail(frame.id, loaded.bitmap);
            } catch (IOException thumbError) {
              android.util.Log.w("SpektraLensExport", "Refresh export thumbnail", thumbError);
            }
            ui(
                () -> {
                  if (destroyed || ticket != revision || generation != homeExportGeneration) {
                    Bitmaps.release(loaded.bitmap);
                    return;
                  }
                  adopt(loaded, false);
                  developed = null;
                  activeId = frame.id;
                  filename = frame.name;
                  try {
                    settings = new JSONObject(frame.params);
                  } catch (JSONException ignored) {
                    settings = new JSONObject();
                  }
                  defaults();
                  filmCategory = category(find(films, settings.optString("film_stock")));
                  committed = settings.toString();
                  undoStack.clear();
                  redoStack.clear();
                  editorNeedsSync = true;
                  prefs
                      .edit()
                      .putString("activeId", activeId)
                      .putString("filename", filename)
                      .apply();
                  fileLabel.setText(filename);
                  photo.images(source, null);
                  photo.resetZoom();
                  scheduleRender(
                      () -> {
                        if (!destroyed
                            && generation == homeExportGeneration
                            && frame.id.equals(activeId)) {
                          homeExportGeneration++;
                          exportOptions();
                        }
                      });
                });
          } catch (Throwable ex) {
            android.util.Log.e("SpektraLensExport", "Prepare home export", ex);
            ui(
                () -> {
                  if (generation == homeExportGeneration)
                    showNotice("照片准备失败，请重试");
                });
          }
        });
  }

  private void confirmRemove(Frame frame) {
    new Haptics.DialogBuilder(this)
        .setTitle("删除这张照片？")
        .setMessage("删除应用内的照片副本与编辑记录。手机相册中的原照片不受影响。")
        .setPositiveButton(
            "删除",
            (d, n) -> {
              if (exporting) return;
              int index = library.indexOf(frame);
              if (index < 0) return;
              library.remove(index);
              if (!writeLibrary()) {
                library.add(Math.min(index, library.size()), frame);
                sortLibrary();
                showNotice("删除失败，照片仍保留在应用中");
                return;
              }
              EditClipboard clipboard = loadEditClipboard();
              if (clipboard != null && frame.id.equals(clipboard.sourceId))
                prefs.edit().remove("edit_clipboard").apply();
              removeFrameArtifacts(frame);
              if (batchPasteMode && batchSelection.isEmpty()) batchPasteMode = false;
              historySignature = null;
              if (library.isEmpty()) {
                new File(getFilesDir(), "source.img").delete();
                loadDemoAction();
              } else if (activeId.equals(frame.id)) {
                loadDemoAction();
              }
              refreshHistory();
              syncHeroLook();
              showHome();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  /** Apply the unedited-photo entry state without recording it as a user adjustment. */
  private boolean prepareUneditedPhoto(Frame frame, String initialSettings) {
    boolean firstEntry = !frame.id.equals(editorPhotoId);
    editorPhotoId = frame.id;
    if (!firstEntry || frame.edited || initialSettings != null) return false;
    String previous = settings.toString();
    set("film_stock", defaultFilmStock());
    selectedTab = 0;
    filmCategory = "全部";
    panelCollapsed = false;
    categoryScrollX.clear();
    pendingFilmScrollX = 0;
    committed = settings.toString();
    return !previous.equals(committed);
  }

  /** Every Home editing entry opens the film tool, keeping the saved recipe. */
  private void editFrameFromHome(Frame frame) {
    if (exporting || !ready) return;
    selectedTab = 0;
    panelCollapsed = false;
    editorNeedsSync = true;
    openFrame(frame);
  }

  private void openFrame(Frame frame) {
    openFrame(frame, null);
  }

  private void openFrame(Frame frame, String initialSettings) {
    if (exporting || !ready) return;
    if (frame.id.equals(activeId)) {
      boolean unedited =
          !frame.id.equals(editorPhotoId) && !frame.edited && initialSettings == null;
      boolean defaultChanged = prepareUneditedPhoto(frame, initialSettings);
      if (editorNeedsSync || unedited) {
        updateStockLabels();
        buildPanel();
        editorNeedsSync = false;
      }
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
      } else if (defaultChanged || developed == null) {
        // Startup restores the source on Home and defers developing until this photo is opened.
        scheduleRender();
      }
      return;
    }
    saveActiveFrame();
    int ticket = ++revision;
    showEditor();
    setStatus("读取照片与编辑记录…", true);
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
                  prepareUneditedPhoto(frame, initialSettings);
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
                  buildPanel();
                  editorNeedsSync = false;
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
    normalizePaper();
    committed = settings.toString();
    filmCategory = category(find(films, settings.optString("film_stock")));
    buildPanel();
    updateStockLabels();
    scheduleRender();
  }

  private void exportOptions() {
    if (!ready || developed == null || exporting) return;
    if (exportSheet != null && exportSheet.isShowing()) return;
    int outputWidth = 0, outputHeight = 0;
    String dimensions = "读取尺寸后显示";
    try {
      JSONObject output =
          new JSONObject(NativeEngine.dimensions(fullWidth, fullHeight, editParams()));
      outputWidth = output.getInt("width");
      outputHeight = output.getInt("height");
      dimensions = outputWidth + " × " + outputHeight;
    } catch (JSONException | RuntimeException error) {
      android.util.Log.w("SpektraLensExport", "Output dimensions unavailable", error);
    }
    File selectedSource = sourceFile;
    int selectedRevision = revision;
    exportSheet =
        new ExportSheet(
            this, dimensions, outputWidth, outputHeight, developed,
            options -> {
              if (sourceFile != selectedSource || revision != selectedRevision) {
                showNotice("照片已更新，请重新打开导出面板");
                return false;
              }
              return export(options);
            });
    exportSheet.setOnDismissListener(dialog -> styleExportButtonForSheet(false));
    styleExportButtonForSheet(true);
    exportSheet.show();
  }

  private void styleExportButtonForSheet(boolean sheetOpen) {
    if (exportButton == null) return;
    if (sheetOpen) {
      AppStyle.pillAction(exportButton, INK, AppStyle.CONTROL);
      AppStyle.setTypeface(exportButton, AppStyle.FONT_REGULAR);
    } else {
      AppStyle.pillAction(exportButton, AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
      AppStyle.setTypeface(exportButton, AppStyle.FONT_BOLD);
    }
  }

  private void lock(boolean value) {
    exporting = value;
    if (!value) main.post(this::pumpHomeThumbnails);
    for (View v : controls) v.setEnabled(!value);
    exportButton.setEnabled(!value && developed != null);
    updateHistoryButtons();
    if (!value) refillThumbs();
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

  private static long exportCreepMillis(int width, int height) {
    double megapixels = (double) width * height / 1_000_000.0;
    return (long) Math.max(3000, Math.min(24000, 3000 + megapixels * 200));
  }

  private void export(int mode) {
    export(new ExportOptions(mode, ExportOptions.RECOMMENDED));
  }

  private boolean export(ExportOptions options) {
    if (exporting || sourceFile == null) return false;
    int mode = options.mode;
    final String exportEdits;
    try {
      JSONObject edits = new JSONObject(editParams());
      edits.remove("export_quality");
      if (options.isJpeg()) edits.put("export_quality", options.jpegQuality);
      exportEdits = edits.toString();
    } catch (JSONException error) {
      report("无法读取导出设置", error);
      return false;
    }
    lock(true);
    ++revision;
    if (pending != null) main.removeCallbacks(pending);
    setStatus("从原文件读取并显影…", true);
    exportButton.setText("正在导出…");
    String p = params(),
        edits = exportEdits,
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
    if (options.isJpeg()) trace.put("jpeg_quality", options.jpegQuality);
    final ExportSheet sheet = exportSheet;
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
            ui(
                () -> {
                  if (sheet != null) sheet.setProgress(0.18f, 500);
                });
            String base =
                getString(R.string.app_name)
                    + "_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
            String ext = mode == 1 ? ".png" : mode == 2 ? ".tiff" : ".jpg";
            String mime = mode == 1 ? "image/png" : mode == 2 ? "image/tiff" : "image/jpeg";
            cache = new File(getCacheDir(), base + ext);
            trace.phase("develop_encode_ms", "原尺寸显影与编码");
            ui(() -> setStatus("正在原尺寸显影与编码…", true));
            long creepMs = exportCreepMillis(width, height);
            ui(
                () -> {
                  if (sheet != null) sheet.setProgress(0.82f, creepMs);
                });
            JSONObject dimensions =
                new JSONObject(renderer.exportImage(p, edits, cache.getAbsolutePath(), format));
            trace.put("backend_after", renderer.backend());
            trace.put("fallback_reason", renderer.fallbackReason());
            trace.put("native_timings", dimensions.optJSONObject("timings"));
            trace.put("output_width", dimensions.getInt("width"));
            trace.put("output_height", dimensions.getInt("height"));
            trace.put("output_bytes", cache.length());
            float afterRender = mode == 3 ? 0.90f : 0.86f;
            ui(
                () -> {
                  if (sheet != null) sheet.setProgress(afterRender, 250);
                });
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
                long total = Math.max(1, cache.length());
                long[] last = {0};
                copy(
                    in,
                    out,
                    copied -> {
                      if (copied - last[0] >= 262144 || copied >= total) {
                        last[0] = copied;
                        float fraction =
                            0.86f + 0.10f * Math.min(1f, copied / (float) total);
                        ui(
                            () -> {
                              if (sheet != null) sheet.setProgress(fraction);
                            });
                      }
                    });
              }
              v.clear();
              v.put(MediaStore.Images.Media.IS_PENDING, 0);
              getContentResolver().update(uri, v, null, null);
            }
            trace.phase("verification_record_ms", "校验并保存导出记录");
            ui(
                () -> {
                  if (sheet != null) sheet.setProgress(0.97f, 200);
                });
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
            if (options.isJpeg()) recipe.put("jpeg_quality", options.jpegQuality);
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
            String fileSize = android.text.format.Formatter.formatShortFileSize(this, cache.length());
            ui(
                () -> {
                  if (sheet != null) sheet.finishExport();
                  lock(false);
                  setStatus("已导出 · " + label, false);
                  exportButton.setText("导出照片");
                  if (mode == 3) {
                    Uri shared =
                        Uri.parse(
                            "content://" + getPackageName() + ".files/" + sharedFile.getName());
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
                                + " · "
                                + fileSize
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
            ui(
                () -> {
                  if (sheet != null) sheet.failExport();
                  lock(false);
                });
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
    return true;
  }

  private String getPackageVersion() {
    try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
    catch (Exception e) { return ""; }
  }

  private void about() {
    if (aboutPage != null) return;
    int sponsorQr = sponsorQrResource();
    AboutPage page =
        new AboutPage(
            this,
            getPackageVersion(),
            sponsorQr == 0 ? null : this::sponsor,
            this::closeAbout,
            () -> updates.check(),
            this::licenses,
            this::showDiagnostics);
    aboutPage = page;
    page.setUpdateAvailable(updates.hasAvailableUpdate());
    screens.addView(page, new FrameLayout.LayoutParams(-1, -1));
    page.setAlpha(0f);
    page.animate().alpha(1f).setDuration(160).start();
  }

  private void refreshUpdateNotice() {
    boolean available = updates.hasAvailableUpdate();
    if (homeAbout != null) homeAbout.setAvailable(available);
    if (aboutPage != null) aboutPage.setUpdateAvailable(available);
  }

  private void closeAbout() {
    closeSponsor();
    if (aboutPage == null) return;
    AboutPage page = aboutPage;
    aboutPage = null;
    page.animate()
        .alpha(0f)
        .setDuration(120)
        .withEndAction(() -> screens.removeView(page))
        .start();
  }

  private void sponsor() {
    if (sponsorPage != null) return;
    int resource = sponsorQrResource();
    if (resource == 0) return;
    SponsorPage page = new SponsorPage(this, resource, worker, this::closeSponsor);
    sponsorPage = page;
    screens.addView(page, new FrameLayout.LayoutParams(-1, -1));
    page.setAlpha(0f);
    page.animate().alpha(1f).setDuration(160).start();
  }

  private void closeSponsor() {
    SponsorPage page = sponsorPage;
    if (page == null) return;
    sponsorPage = null;
    page.animate()
        .alpha(0f)
        .setDuration(120)
        .withEndAction(
            () -> {
              screens.removeView(page);
              page.release();
            })
        .start();
  }

  @SuppressLint("DiscouragedApi") // Public source builds intentionally omit this private resource.
  private int sponsorQrResource() {
    return getResources().getIdentifier("sponsor_wechat_qr", "raw", getPackageName());
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
                  showNotice(e.toString(), true);
                }
              })
          .setPositiveButton("关闭", null)
          .show();
    } catch (IOException ex) {
      showNotice(ex.toString(), true);
    }
  }

  @Override
  protected void onStart() {
    super.onStart();
    if (updates != null) updates.checkInBackground();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (updates != null) updates.resume();
    if (flowingBackground != null) flowingBackground.setHostResumed(true);
    if (gallerySheet != null && gallerySheet.isShowing()) gallerySheet.refresh();
    if (batchImportRunning && homeImportProgress != null) homeImportProgress.setAnimating(true);
  }

  @Override
  protected void onPause() {
    if (photo != null) photo.cancelPendingSingleTap();
    finishPendingSliderTaps();
    updateEditorLayout();
    if (flowingBackground != null) flowingBackground.setHostResumed(false);
    AppNotice.clear(getWindow());
    if (homeImportProgress != null) homeImportProgress.setAnimating(false);
    flushLibraryWrite();
    super.onPause();
  }

  @Override
  protected void onDestroy() {
    if (photo != null) photo.cancelPendingSingleTap();
    if (batchImportRunning) cancelBatchImport();
    cancelPanelAnimation();
    finishToolAnimations();
    if (flowingBackground != null) flowingBackground.setHostResumed(false);
    if (gallerySheet != null) gallerySheet.dismiss();
    if (exportSheet != null) exportSheet.dismiss();
    if (sponsorPage != null) {
      SponsorPage page = sponsorPage;
      sponsorPage = null;
      screens.removeView(page);
      page.release();
    }
    if (backCallback != null && Build.VERSION.SDK_INT >= 33) {
      getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(backCallback);
      backCallback = null;
    }
    if (updates != null) updates.close();
    flushLibraryWrite();
    destroyed = true;
    homeThumbJobs.clear();
    thumbnailDeliveries.close();
    ++thumbGeneration;
    for (ImageView view : thumbTargets.values()) view.setImageDrawable(null);
    for (Bitmap cached : filmThumbs.values()) Bitmaps.release(cached);
    filmThumbs.clear();
    thumbTargets.clear();
    filmScrollRef = null;
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
    thumbnailIo.shutdown();
    bootStateIo.shutdownNow();
    libraryWriter.shutdown();
    super.onDestroy();
  }
}
