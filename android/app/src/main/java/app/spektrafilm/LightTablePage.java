// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local portrait (2:3) optical light table (Darkroom Optical Bench / Theme A). Photos sit directly
 * on a machined titanium-anodised aluminium backlit diffuser with an etched top/left millimetre
 * L-ruler, two-tier physical shadows, acetate sheen, transparent 135 sprocket perforations, and a
 * bevelled optical glass 3x loupe. Nothing leaves the device; export is explicit.
 */
@SuppressLint("ViewConstructor")
final class LightTablePage extends LinearLayout {
  static final int MAX_PHOTOS = 12;
  private static final int DECODE_MAX_EDGE = 1280;
  private static final int THUMB_MAX_EDGE = 320;
  private static final int THUMB_CACHE_BYTES = 8 * 1024 * 1024;
  private static final int PREVIEW_POSTER_EDGE = 630;
  private static final int EXPORT_POSTER_EDGE = 2400;
  private static final String PREF_EXPORT_THEME = "lighttable_export_theme";
  private static final String PREF_EXPORT_FOOTER = "lighttable_export_footer";
  private static final String PREF_FOLDABLE_LANDSCAPE = "lighttable_foldable_landscape";
  private static final String POSTER_SLOGAN = "暗房里没有过去，只有正在发生的影像";

  enum PosterTheme {
    LIGHT,
    DARK
  }

  /**
   * Formats Option 1 (contact-sheet archive stamp) top line from film labels present on the table.
   * Picks the most frequent stock (breaking ties by order on the table) and appends " · +N" when
   * multiple distinct stocks are present.
   */
  static String formatFilmSummary(List<String> labels) {
    if (labels == null || labels.isEmpty()) return "CONTACT SHEET";
    LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
    for (String raw : labels) {
      if (raw == null) continue;
      String trimmed = raw.trim();
      if (trimmed.isEmpty()) continue;
      String normalized = trimmed.toUpperCase(Locale.ROOT);
      Integer prev = counts.get(normalized);
      counts.put(normalized, prev == null ? 1 : prev + 1);
    }
    if (counts.isEmpty()) return "CONTACT SHEET";
    String best = null;
    int bestCount = -1;
    for (Map.Entry<String, Integer> entry : counts.entrySet()) {
      if (entry.getValue() > bestCount) {
        best = entry.getKey();
        bestCount = entry.getValue();
      }
    }
    int extra = counts.size() - 1;
    return extra > 0 ? best + " · +" + extra : best;
  }

  /** Formats Option 1 contact-sheet bottom line: "YYYY.MM.DD · NN FRAMES". */
  static String formatFrameSummary(String dateText, int frameCount) {
    int count = Math.max(0, frameCount);
    return String.format(
        Locale.ROOT, "%s · %02d %s", dateText, count, count == 1 ? "FRAME" : "FRAMES");
  }

  /**
   * Self-contained optical bench palette. Currently only DARKROOM (Theme A) is instantiated; kept
   * cleanly separated so a future user-selectable bright studio theme can plug in directly.
   */
  private static final class TablePalette {
    final int pageBg;
    final int hintText;
    final int placeholderFill;
    final int selectionStroke;
    final int selectionGlow;
    final int selectionInnerHairline;
    final int fallbackFrameOuter;
    final int fallbackFrameInner;
    final int fallbackDiffuser;

    final boolean lightMode;

    TablePalette(
        boolean lightMode,
        int pageBg,
        int hintText,
        int placeholderFill,
        int selectionStroke,
        int selectionGlow,
        int selectionInnerHairline,
        int fallbackFrameOuter,
        int fallbackFrameInner,
        int fallbackDiffuser) {
      this.lightMode = lightMode;
      this.pageBg = pageBg;
      this.hintText = hintText;
      this.placeholderFill = placeholderFill;
      this.selectionStroke = selectionStroke;
      this.selectionGlow = selectionGlow;
      this.selectionInnerHairline = selectionInnerHairline;
      this.fallbackFrameOuter = fallbackFrameOuter;
      this.fallbackFrameInner = fallbackFrameInner;
      this.fallbackDiffuser = fallbackDiffuser;
    }

    static TablePalette current() {
      boolean light = AppStyle.isLight();
      return new TablePalette(
          light,
          AppStyle.BG,
          0xff7c766b,
          0xffddd6c7,
          AppStyle.GOLD,
          AppStyle.withAlpha(AppStyle.GOLD, light ? .34f : .45f),
          0x6bfffaf0,
          0xffdcdfe5,
          0xffc2c6ce,
          0xfffdfbf5);
    }
  }

  static final class SourcePhoto {
    final String id, name, path;
    final File thumbnail, developed;
    final long bytes;
    final FrameStyle style;
    final float edgeAmount;
    final String filmLabel;

    SourcePhoto(String id, String name, String path, File thumbnail, File developed, long bytes) {
      this(id, name, path, thumbnail, developed, bytes, null, "");
    }

    SourcePhoto(
        String id,
        String name,
        String path,
        File thumbnail,
        File developed,
        long bytes,
        String paramsJson) {
      this(id, name, path, thumbnail, developed, bytes, paramsJson, "");
    }

    SourcePhoto(
        String id,
        String name,
        String path,
        File thumbnail,
        File developed,
        long bytes,
        String paramsJson,
        String filmLabel) {
      this.id = id;
      this.name = name;
      this.path = path;
      this.thumbnail = thumbnail;
      this.developed = developed;
      this.bytes = bytes;
      this.filmLabel = filmLabel == null ? "" : filmLabel.trim();
      String style = "none";
      float amount = 6f;
      if (paramsJson != null && !paramsJson.isBlank()) {
        try {
          JSONObject json = new JSONObject(paramsJson);
          style = json.optString("edge_style", "none");
          amount = (float) json.optDouble("edge_amount", 6.0);
        } catch (JSONException ignored) {
        }
      }
      this.style = FrameStyle.fromId(style);
      this.edgeAmount = amount;
    }

    File preferredLook() {
      if (developed == null) return null;
      File alpha = HomePhotoImages.alphaLook(developed);
      if (style.film()) {
        if (alpha.isFile()) return alpha;
        return style.legacyStrip() && developed.isFile() ? developed : null;
      }
      return developed.isFile() ? developed : null;
    }
  }

  static final class TablePhoto {
    final SourcePhoto source;
    DecodedPiece decoded;
    Bitmap thumbPlaceholder;
    float x = .5f, y = .5f, scale = 1f, rotation = 0f;

    TablePhoto(SourcePhoto source) {
      this.source = source;
    }

    Bitmap displayBitmap() {
      return decoded == null ? thumbPlaceholder : decoded.bitmap;
    }
  }

  private static final class RenderPiece {
    final SourcePhoto source;
    final DecodedPiece decoded;
    final Bitmap placeholder;
    final float x, y, scale, rotation;

    RenderPiece(TablePhoto photo) {
      this(photo.source, photo.decoded, photo.thumbPlaceholder,
          photo.x, photo.y, photo.scale, photo.rotation);
    }

    RenderPiece(SourcePhoto source, DecodedPiece decoded, Bitmap placeholder,
                float x, float y, float scale, float rotation) {
      this.source = source;
      this.decoded = decoded;
      this.placeholder = placeholder;
      this.x = x;
      this.y = y;
      this.scale = scale;
      this.rotation = rotation;
    }

    Bitmap bitmap() {
      return decoded == null ? placeholder : decoded.bitmap;
    }
  }

  /** Owned by the stage or a poster snapshot after decode; treated as read-only after publication. */
  private static final class DecodedPiece {
    final Bitmap bitmap;
    final String lookKey;
    final boolean acetate;

    DecodedPiece(Bitmap bitmap, String lookKey, boolean acetate) {
      this.bitmap = bitmap;
      this.lookKey = lookKey;
      this.acetate = acetate;
    }
  }

  interface ContextBarListener {
    void onContextBarVisible(boolean visible);
  }

  private static final class ToolSlot {
    final LinearLayout container;
    final ImageView icon;
    final TextView label;

    ToolSlot(LinearLayout container, ImageView icon, TextView label) {
      this.container = container;
      this.icon = icon;
      this.label = label;
    }
  }

  private final Activity activity;
  private final Runnable close;
  private final ContextBarListener contextBarListener;
  private final SharedPreferences prefs;
  private final List<SourcePhoto> sources;
  private final ArrayList<TablePhoto> table = new ArrayList<>();
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final ExecutorService thumbExecutor = Executors.newFixedThreadPool(3);
  private final ExecutorService bakeExecutor = Executors.newSingleThreadExecutor();
  private final LruCache<String, Bitmap> thumbCache =
      new LruCache<String, Bitmap>(THUMB_CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
          return value == null ? 0 : value.getAllocationByteCount();
        }
      };
  private final AtomicInteger exportPreviewGen = new AtomicInteger();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final TablePalette palette = TablePalette.current();
  private final ToolSlot[] contextSlots = new ToolSlot[3];
  private static final int MIN_BRIGHTNESS = 15;
  private static final int MAX_BRIGHTNESS = 100;
  private static final int DETENT_STEP = 8;
  private static final long LAMP_TRANSITION_MS = 180L;

  private LightTableView canvas;
  private FrameLayout canvasHost;
  private FrameLayout addPopupHost;
  private View addPopupView;
  private final AtomicInteger addPopupGen = new AtomicInteger();
  private LinearLayout orientToggle;
  private TextView orientLandBtn, orientPortBtn;
  private boolean landscapeMode;
  private TextView countLabel, exportButton;
  private ImageButton headerLoupeButton, headerArrangeButton, headerAddButton;
  private LinearLayout contextBar, selectionPill, loupePill;
  private LinearLayout dimmerPill;
  private PowerSwitchView dimmerPowerButton;
  private DimmerScaleView dimmerScale;
  private TextView dimmerValueLabel;
  private LightTableSound lampSound;
  private ValueAnimator lampAnimator;
  private boolean lampOn = true;
  private int lampBrightness = MAX_BRIGHTNESS;
  private int lastDimmerDetent = Math.round(MAX_BRIGHTNESS / (float) DETENT_STEP);
  private Dialog addSheet;
  private Dialog exportSheet;
  private Runnable refreshExportPreview;
  private TextView addConfirm;
  private static final float SHAKE_G_THRESHOLD = 2.25f;
  private static final long SHAKE_MIN_PEAK_GAP_MS = 70L;
  private static final long SHAKE_WINDOW_MS = 500L;
  private static final long SHAKE_COOLDOWN_MS = 1000L;
  private static final int SHAKE_REQUIRED_PEAKS = 2;

  private TablePhoto selected;
  private boolean magnifier;
  private boolean contextBarVisible;
  private volatile boolean released;
  private SensorManager sensorManager;
  private Sensor accelerometer;
  private boolean shakeListening;
  private long lastShakePeakMs;
  private long lastShakeTriggerMs;
  private int shakePeakCount;
  private final SensorEventListener shakeListener =
      new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
          if (released || event == null || event.values == null || event.values.length < 3) return;
          float gx = event.values[0] / SensorManager.GRAVITY_EARTH;
          float gy = event.values[1] / SensorManager.GRAVITY_EARTH;
          float gz = event.values[2] / SensorManager.GRAVITY_EARTH;
          float gForce = (float) Math.sqrt(gx * gx + gy * gy + gz * gz);
          if (gForce < SHAKE_G_THRESHOLD) return;
          long now = SystemClock.elapsedRealtime();
          if (now - lastShakeTriggerMs < SHAKE_COOLDOWN_MS) return;
          if (lastShakePeakMs > 0 && now - lastShakePeakMs < SHAKE_MIN_PEAK_GAP_MS) return;
          if (now - lastShakePeakMs > SHAKE_WINDOW_MS) shakePeakCount = 0;
          lastShakePeakMs = now;
          shakePeakCount++;
          if (shakePeakCount >= SHAKE_REQUIRED_PEAKS) {
            shakePeakCount = 0;
            lastShakePeakMs = 0;
            lastShakeTriggerMs = now;
            onShakeDetected();
          }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
      };

  LightTablePage(Activity activity, Runnable close, List<SourcePhoto> sources) {
    this(activity, close, sources, null);
  }

  LightTablePage(
      Activity activity,
      Runnable close,
      List<SourcePhoto> sources,
      ContextBarListener contextBarListener) {
    super(activity);
    this.activity = activity;
    this.close = close;
    this.contextBarListener = contextBarListener;
    this.sources = new ArrayList<>();
    if (sources != null) this.sources.addAll(sources);
    this.prefs = activity.getSharedPreferences("darkroom", Activity.MODE_PRIVATE);
    int initWidthDp = FoldableUi.windowWidthDp(activity);
    this.landscapeMode =
        FoldableLayout.isFoldable(initWidthDp)
            && prefs.getBoolean(
                PREF_FOLDABLE_LANDSCAPE, FoldableLayout.lightTableDefaultLandscape(initWidthDp));
    setOrientation(VERTICAL);
    setBackgroundColor(palette.pageBg);
    setAccessibilityPaneTitle("观片台");
    setTag("lighttable.page");
    setClickable(true);

    addView(header(), new LayoutParams(-1, -2));

    canvasHost = new FrameLayout(activity);
    canvasHost.setClipChildren(false);
    canvas = new LightTableView(this);
    canvasHost.addView(canvas, new FrameLayout.LayoutParams(-1, -1));

    lampSound = new LightTableSound(activity);

    dimmerPill = buildDimmerPill();
    FrameLayout.LayoutParams dimmerParams =
        new FrameLayout.LayoutParams(-2, dp(38), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    dimmerParams.bottomMargin = dp(84);
    canvasHost.addView(dimmerPill, dimmerParams);

    contextBar = buildContextBar();
    canvasHost.addView(contextBar, contextBarLayoutParams(initWidthDp));

    addPopupHost = new FrameLayout(activity);
    addPopupHost.setClipChildren(false);
    addPopupHost.setVisibility(GONE);
    addPopupHost.setClickable(true);
    addPopupHost.setBackgroundColor(Color.TRANSPARENT);
    addPopupHost.setOnClickListener(v -> closeAddPopup());
    canvasHost.addView(addPopupHost, new FrameLayout.LayoutParams(-1, -1));

    addView(canvasHost, new LayoutParams(-1, 0, 1));

    syncOrientationUi(initWidthDp);
    loadState();
    updateCount();
    updateTools();
    syncDimmerUi();
  }

  private FrameLayout.LayoutParams contextBarLayoutParams(int widthDp) {
    int maxDp = FoldableLayout.lightTableContextBarMaxDp(widthDp);
    if (maxDp <= 0) {
      return new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    }
    int availPx = FoldableUi.visibleWindowWidthPx(activity);
    if (availPx <= 0) availPx = activity.getResources().getDisplayMetrics().widthPixels;
    // Add 32dp for row horizontal padding (16dp * 2) so the visible capsule matches 440/480dp.
    int targetW = FoldableUi.constrainedWidthPx(activity, availPx, maxDp + 32, 0);
    return new FrameLayout.LayoutParams(targetW, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
  }

  private void syncOrientationUi(int widthDp) {
    boolean foldable = FoldableLayout.isFoldable(widthDp);
    if (orientToggle != null) {
      orientToggle.setVisibility(foldable ? VISIBLE : GONE);
    }
    if (orientLandBtn != null && orientPortBtn != null) {
      orientLandBtn.setBackground(landscapeMode ? AppStyle.selectedShape(activity, 6f) : null);
      orientLandBtn.setTextColor(landscapeMode ? AppStyle.INK : AppStyle.MUTED);
      AppStyle.setTypeface(
          orientLandBtn, landscapeMode ? AppStyle.FONT_SEMIBOLD : AppStyle.FONT_MEDIUM);
      orientPortBtn.setBackground(!landscapeMode ? AppStyle.selectedShape(activity, 6f) : null);
      orientPortBtn.setTextColor(!landscapeMode ? AppStyle.INK : AppStyle.MUTED);
      AppStyle.setTypeface(
          orientPortBtn, !landscapeMode ? AppStyle.FONT_SEMIBOLD : AppStyle.FONT_MEDIUM);
    }
  }

  private void setLandscapeMode(boolean landscape, boolean persistFoldablePref) {
    int widthDp = FoldableUi.windowWidthDp(activity);
    boolean allowed = FoldableLayout.isFoldable(widthDp) && landscape;
    if (persistFoldablePref && FoldableLayout.isFoldable(widthDp)) {
      prefs.edit().putBoolean(PREF_FOLDABLE_LANDSCAPE, landscape).apply();
    }
    boolean changed = landscapeMode != allowed;
    landscapeMode = allowed;
    syncOrientationUi(widthDp);
    if (changed && canvas != null) {
      canvas.recomputeAndRebake();
    }
  }

  void onWindowConfigChanged() {
    if (released) return;
    int widthDp = FoldableUi.windowWidthDp(activity);
    boolean foldable = FoldableLayout.isFoldable(widthDp);
    boolean wantLandscape =
        foldable
            && prefs.getBoolean(
                PREF_FOLDABLE_LANDSCAPE, FoldableLayout.lightTableDefaultLandscape(widthDp));
    setLandscapeMode(wantLandscape, false);
    if (contextBar != null) {
      contextBar.setLayoutParams(contextBarLayoutParams(widthDp));
    }
    if (addPopupView != null) {
      if (!FoldableLayout.lightTableAddPopup(widthDp)) {
        closeAddPopup();
      } else {
        attachAddPopupForWidth(widthDp);
      }
    } else if (addSheet != null && addSheet.isShowing() && foldable) {
      addSheet.dismiss();
    }
  }

  private View header() {
    LinearLayout header = new LinearLayout(activity);
    header.setOrientation(HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(20), dp(12), dp(16), dp(8));

    LinearLayout titleBlock = new LinearLayout(activity);
    titleBlock.setOrientation(HORIZONTAL);
    titleBlock.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = text("观片台", 20, AppStyle.INK);
    AppStyle.setTypeface(title, AppStyle.FONT_BOLD);
    title.setLetterSpacing(-.02f);
    title.setSingleLine(true);
    title.setEllipsize(TextUtils.TruncateAt.END);
    title.setAccessibilityHeading(true);
    titleBlock.addView(title, new LayoutParams(-2, -2));

    countLabel = text("0 / " + MAX_PHOTOS, AppStyle.TYPE_MICRO, AppStyle.MUTED);
    AppStyle.setNumericTypeface(countLabel);
    countLabel.setGravity(Gravity.CENTER);
    countLabel.setSingleLine(true);
    countLabel.setPadding(dp(8), dp(2), dp(8), dp(2));
    countLabel.setBackground(AppStyle.shape(activity, AppStyle.CONTROL, 6f, true));
    LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-2, -2);
    countParams.leftMargin = dp(8);
    titleBlock.addView(countLabel, countParams);
    header.addView(titleBlock, new LayoutParams(0, -2, 1));

    orientToggle = new LinearLayout(activity);
    orientToggle.setOrientation(HORIZONTAL);
    orientToggle.setGravity(Gravity.CENTER_VERTICAL);
    orientToggle.setPadding(dp(2), dp(2), dp(2), dp(2));
    orientToggle.setBackground(AppStyle.shape(activity, AppStyle.CONTROL, AppStyle.R_SMALL, true));
    orientToggle.setVisibility(GONE);
    orientLandBtn = text("横 3:2", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    orientPortBtn = text("竖 2:3", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    for (TextView opt : new TextView[] {orientLandBtn, orientPortBtn}) {
      opt.setGravity(Gravity.CENTER);
      opt.setSingleLine(true);
      opt.setMinHeight(dp(28));
      opt.setPadding(dp(9), dp(2), dp(9), dp(2));
      opt.setClickable(true);
      opt.setFocusable(true);
    }
    orientLandBtn.setOnClickListener(
        v -> {
          closeAddPopup();
          setLandscapeMode(true, true);
        });
    orientPortBtn.setOnClickListener(
        v -> {
          closeAddPopup();
          setLandscapeMode(false, true);
        });
    orientToggle.addView(orientLandBtn, new LayoutParams(-2, -2));
    orientToggle.addView(orientPortBtn, new LayoutParams(-2, -2));
    LayoutParams orientParams = new LayoutParams(-2, dp(34));
    orientParams.rightMargin = dp(8);
    header.addView(orientToggle, orientParams);

    headerLoupeButton =
        topActionButton(
            R.drawable.ic_ui_lt_loupe,
            "3× 放大镜",
            () -> {
              closeAddPopup();
              toggleMagnifier();
            });
    header.addView(headerLoupeButton, new LayoutParams(dp(36), dp(36)));

    headerArrangeButton =
        topActionButton(
            R.drawable.ic_ui_lt_arrange,
            "整理排版",
            () -> {
              closeAddPopup();
              arrange();
            });
    LayoutParams arrangeParams = new LayoutParams(dp(36), dp(36));
    arrangeParams.leftMargin = dp(6);
    header.addView(headerArrangeButton, arrangeParams);

    headerAddButton =
        topActionButton(R.drawable.ic_ui_add, "添加照片", this::showAddPhotos);
    LayoutParams addParams = new LayoutParams(dp(36), dp(36));
    addParams.leftMargin = dp(6);
    header.addView(headerAddButton, addParams);

    exportButton = button("导出", AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    exportButton.setTextSize(AppStyle.TYPE_BODY);
    AppStyle.setTypeface(exportButton, AppStyle.FONT_BOLD);
    exportButton.setMinHeight(0);
    exportButton.setMinWidth(dp(64));
    exportButton.setPadding(dp(14), 0, dp(14), 0);
    exportButton.setOnClickListener(
        v -> {
          closeAddPopup();
          exportTable();
        });
    LayoutParams exportParams = new LayoutParams(-2, dp(36));
    exportParams.leftMargin = dp(8);
    header.addView(exportButton, exportParams);
    return header;
  }

  private ImageButton topActionButton(int iconRes, String description, Runnable action) {
    ImageButton btn = new ImageButton(activity);
    btn.setImageResource(iconRes);
    btn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    btn.setPadding(dp(9), dp(9), dp(9), dp(9));
    btn.setContentDescription(description);
    btn.setImageTintList(ColorStateList.valueOf(AppStyle.INK));
    GradientDrawable bg = AppStyle.shape(activity, AppStyle.SURFACE, 9f, true);
    btn.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(AppStyle.RIPPLE),
            bg,
            AppStyle.shape(activity, Color.WHITE, 9f, false)));
    btn.setClipToOutline(true);
    btn.setOnClickListener(v -> action.run());
    return btn;
  }

  /**
   * Minimal floating Precision Instrument dimmer capsule (Option B): left power button, center
   * precision tick scale with vermilion index needle, and right tabular percentage/OFF readout.
   */
  private LinearLayout buildDimmerPill() {
    LinearLayout pill = new LinearLayout(activity);
    pill.setOrientation(HORIZONTAL);
    pill.setGravity(Gravity.CENTER_VERTICAL);
    pill.setPadding(dp(5), dp(4), dp(13), dp(4));
    pill.setTag("lighttable.dimmer");
    GradientDrawable bg =
        AppStyle.shape(activity, AppStyle.withAlpha(AppStyle.SURFACE, .96f), 13f, true);
    pill.setBackground(bg);
    pill.setElevation(0f);
    pill.setClipToOutline(true);
    pill.setClickable(true);

    dimmerPowerButton = new PowerSwitchView(activity);
    dimmerPowerButton.setTag("lighttable.dimmer.power");
    dimmerPowerButton.setTag(R.id.haptic_on_click, Boolean.TRUE);
    dimmerPowerButton.setOnClickListener(v -> toggleLampPower());
    pill.addView(dimmerPowerButton, new LayoutParams(dp(28), dp(28)));

    dimmerScale = new DimmerScaleView(activity);
    dimmerScale.setTag("lighttable.dimmer.scale");
    LayoutParams scaleParams = new LayoutParams(dp(146), dp(30));
    scaleParams.leftMargin = dp(11);
    scaleParams.rightMargin = dp(10);
    pill.addView(dimmerScale, scaleParams);

    dimmerValueLabel = text("100%", 11, AppStyle.INK);
    dimmerValueLabel.setTextSize(11.5f);
    AppStyle.setNumericTypeface(dimmerValueLabel);
    AppStyle.setTypeface(dimmerValueLabel, AppStyle.FONT_BOLD);
    dimmerValueLabel.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    dimmerValueLabel.setSingleLine(true);
    dimmerValueLabel.setMinWidth(dp(34));
    dimmerValueLabel.setTag("lighttable.dimmer.value");
    pill.addView(dimmerValueLabel, new LayoutParams(-2, -2));

    return pill;
  }

  private void positionDimmerPill(float boxBottomPx, int hostHeightPx) {
    if (dimmerPill == null || hostHeightPx <= 0) return;
    int pillH = dp(38);
    int dockBand = dp(82);
    int gapBelowBox = dp(11);
    int minBottom = dockBand;
    int idealBottom = Math.round(hostHeightPx - boxBottomPx - gapBelowBox - pillH);
    int clampedBottom = Math.max(minBottom, idealBottom);
    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dimmerPill.getLayoutParams();
    if (lp != null && lp.bottomMargin != clampedBottom) {
      lp.bottomMargin = clampedBottom;
      dimmerPill.setLayoutParams(lp);
    }
  }

  private void syncDimmerUi() {
    if (dimmerPowerButton != null) {
      dimmerPowerButton.setOnState(lampOn);
      dimmerPowerButton.setContentDescription(lampOn ? "关闭观片台灯光" : "打开观片台灯光");
    }
    if (dimmerScale != null) {
      dimmerScale.setState(lampOn, lampBrightness);
    }
    if (dimmerValueLabel != null) {
      dimmerValueLabel.setText(lampOn ? (lampBrightness + "%") : "OFF");
      dimmerValueLabel.setTextColor(lampOn ? AppStyle.INK : AppStyle.MUTED);
    }
  }

  private void toggleLampPower() {
    lampOn = !lampOn;
    vibrateSwitch();
    if (lampSound != null) lampSound.playSwitch(lampOn);
    syncDimmerUi();
    animateLampTo(lampOn ? (lampBrightness / 100f) : 0f);
  }

  private void onDimmerScrub(int targetBrightness) {
    int clamped = Math.max(MIN_BRIGHTNESS, Math.min(MAX_BRIGHTNESS, targetBrightness));
    boolean wokeUp = false;
    if (!lampOn) {
      lampOn = true;
      wokeUp = true;
      vibrateSwitch();
      if (lampSound != null) lampSound.playSwitch(true);
    }
    boolean changed = clamped != lampBrightness;
    lampBrightness = clamped;
    int detent = Math.round(clamped / (float) DETENT_STEP);
    if (!wokeUp && changed && detent != lastDimmerDetent) {
      lastDimmerDetent = detent;
      vibrateDetent();
      if (lampSound != null) lampSound.playDetent();
    } else if (wokeUp) {
      lastDimmerDetent = detent;
    }
    cancelLampAnimator();
    if (canvas != null) canvas.setLightLevel(lampBrightness / 100f);
    syncDimmerUi();
  }

  private void animateLampTo(float targetLevel) {
    cancelLampAnimator();
    if (canvas == null) return;
    float from = canvas.lightLevel();
    if (Math.abs(from - targetLevel) < .002f || !ValueAnimator.areAnimatorsEnabled()) {
      canvas.setLightLevel(targetLevel);
      return;
    }
    ValueAnimator anim = ValueAnimator.ofFloat(from, targetLevel);
    lampAnimator = anim;
    anim.setDuration(LAMP_TRANSITION_MS);
    anim.setInterpolator(new DecelerateInterpolator(1.6f));
    anim.addUpdateListener(
        a -> {
          if (released || lampAnimator != anim) return;
          if (canvas != null) canvas.setLightLevel((Float) a.getAnimatedValue());
        });
    anim.addListener(
        new AnimatorListenerAdapter() {
          @Override
          public void onAnimationEnd(Animator animation) {
            if (lampAnimator == anim) lampAnimator = null;
          }
        });
    anim.start();
  }

  private void cancelLampAnimator() {
    if (lampAnimator != null) {
      ValueAnimator prev = lampAnimator;
      lampAnimator = null;
      prev.removeAllListeners();
      prev.removeAllUpdateListeners();
      prev.cancel();
    }
  }

  /**
   * Contextual single-row dock occupying the exact bottom band as the host 3-tab capsule. Shown
   * only while a photo is selected or the 3x loupe is active, replacing the host tab bar so two
   * bottom bars never stack.
   */
  private LinearLayout buildContextBar() {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(16), dp(8), dp(16), dp(14));
    row.setVisibility(GONE);
    row.setTag("lighttable.context");

    selectionPill = new LinearLayout(activity);
    selectionPill.setOrientation(HORIZONTAL);
    selectionPill.setGravity(Gravity.CENTER_VERTICAL);
    selectionPill.setPadding(dp(4), dp(4), dp(4), dp(4));
    GradientDrawable selBg =
        AppStyle.shape(activity, AppStyle.withAlpha(AppStyle.SURFACE, .96f), 14f, false);
    selBg.setStroke(Math.max(1, dp(.85f)), AppStyle.ACCENT_BORDER);
    selectionPill.setBackground(selBg);
    selectionPill.setElevation(dp(8));
    selectionPill.setClipToOutline(true);

    String[] labels = {"3× 放大镜", "旋转", "移除"};
    int[] icons = {
      R.drawable.ic_ui_lt_loupe,
      R.drawable.ic_ui_lt_rotate,
      R.drawable.ic_ui_lt_remove
    };
    for (int i = 0; i < labels.length; i++) {
      final int tool = i;
      LinearLayout item = new LinearLayout(activity);
      item.setOrientation(VERTICAL);
      item.setGravity(Gravity.CENTER);
      item.setClickable(true);
      item.setFocusable(true);
      item.setContentDescription(labels[i]);
      item.setBackground(
          new RippleDrawable(
              ColorStateList.valueOf(AppStyle.RIPPLE),
              null,
              AppStyle.shape(activity, Color.WHITE, 10f, false)));

      ImageView icon = new ImageView(activity);
      icon.setImageResource(icons[i]);
      icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
      icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      item.addView(icon, new LayoutParams(dp(18), dp(18)));

      TextView label = text(labels[i], AppStyle.TYPE_MICRO, AppStyle.MUTED);
      label.setGravity(Gravity.CENTER);
      label.setSingleLine(true);
      label.setEllipsize(TextUtils.TruncateAt.END);
      label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LayoutParams labelParams = new LayoutParams(-2, -2);
      labelParams.topMargin = dp(2);
      item.addView(label, labelParams);

      item.setOnClickListener(
          v -> {
            if (tool == 0) toggleMagnifier();
            else if (tool == 1) rotateSelected();
            else removeSelected();
          });
      contextSlots[i] = new ToolSlot(item, icon, label);
      selectionPill.addView(item, new LayoutParams(0, dp(48), 1));
    }
    row.addView(selectionPill, new LayoutParams(0, dp(56), 1));

    loupePill = new LinearLayout(activity);
    loupePill.setOrientation(HORIZONTAL);
    loupePill.setGravity(Gravity.CENTER);
    loupePill.setPadding(dp(16), 0, dp(16), 0);
    GradientDrawable loupeBg =
        AppStyle.shape(activity, AppStyle.withAlpha(AppStyle.SURFACE, .96f), 16f, false);
    loupeBg.setStroke(Math.max(1, dp(.85f)), AppStyle.ACCENT_BORDER);
    loupePill.setBackground(loupeBg);
    loupePill.setElevation(dp(8));
    loupePill.setVisibility(GONE);

    View dot = new View(activity);
    GradientDrawable dotBg = new GradientDrawable();
    dotBg.setShape(GradientDrawable.OVAL);
    dotBg.setColor(AppStyle.GOLD);
    dot.setBackground(dotBg);
    LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(6), dp(6));
    dotParams.rightMargin = dp(8);
    loupePill.addView(dot, dotParams);

    TextView loupeLabel = text("3× 放大镜 · 拖动查看", AppStyle.TYPE_BODY, AppStyle.INK);
    AppStyle.setTypeface(loupeLabel, AppStyle.FONT_MEDIUM);
    loupeLabel.setSingleLine(true);
    loupeLabel.setEllipsize(TextUtils.TruncateAt.END);
    loupePill.addView(loupeLabel, new LayoutParams(-2, -2));
    row.addView(loupePill, new LayoutParams(0, dp(56), 1));

    LinearLayout donePill = new LinearLayout(activity);
    donePill.setOrientation(HORIZONTAL);
    donePill.setGravity(Gravity.CENTER);
    donePill.setClickable(true);
    donePill.setFocusable(true);
    donePill.setContentDescription("完成操作");
    donePill.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(AppStyle.RIPPLE),
            AppStyle.shape(activity, AppStyle.PRIMARY, 14f, false),
            AppStyle.shape(activity, Color.WHITE, 14f, false)));
    donePill.setElevation(dp(8));
    donePill.setClipToOutline(true);

    ImageView doneIcon = new ImageView(activity);
    doneIcon.setImageResource(R.drawable.ic_material_check);
    doneIcon.setImageTintList(ColorStateList.valueOf(AppStyle.ON_PRIMARY));
    doneIcon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    doneIcon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    donePill.addView(doneIcon, new LayoutParams(dp(18), dp(18)));

    TextView doneLabel = text("完成", AppStyle.TYPE_TITLE, AppStyle.ON_PRIMARY);
    AppStyle.setTypeface(doneLabel, AppStyle.FONT_BOLD);
    doneLabel.setSingleLine(true);
    doneLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LayoutParams doneLabelParams = new LayoutParams(-2, -2);
    doneLabelParams.leftMargin = dp(5);
    donePill.addView(doneLabel, doneLabelParams);

    donePill.setOnClickListener(v -> finishContextMode());
    LayoutParams doneParams = new LayoutParams(dp(96), dp(56));
    doneParams.leftMargin = dp(10);
    row.addView(donePill, doneParams);

    return row;
  }

  private void finishContextMode() {
    if (magnifier) {
      magnifier = false;
      if (canvas != null) canvas.hideLens();
    }
    if (selected != null) selected = null;
    updateTools();
    if (canvas != null) canvas.invalidate();
  }

  private void toggleMagnifier() {
    if (table.isEmpty()) return;
    magnifier = !magnifier;
    if (magnifier) canvas.showLensAtSelected();
    else canvas.hideLens();
    updateTools();
  }

  private void updateTools() {
    boolean hasPhotos = !table.isEmpty();
    boolean hasSelected = selected != null;
    if (!hasPhotos && magnifier) {
      magnifier = false;
      if (canvas != null) canvas.hideLens();
    }
    if (exportButton != null) {
      exportButton.setEnabled(hasPhotos);
      exportButton.setAlpha(hasPhotos ? 1f : .42f);
    }
    if (headerLoupeButton != null) {
      headerLoupeButton.setEnabled(hasPhotos);
      int tint = !hasPhotos ? AppStyle.DISABLED : (magnifier ? AppStyle.GOLD : AppStyle.INK);
      headerLoupeButton.setImageTintList(ColorStateList.valueOf(tint));
      GradientDrawable bg =
          AppStyle.shape(
              activity, magnifier ? AppStyle.ACCENT_SOFT : AppStyle.SURFACE, 9f, false);
      bg.setStroke(
          Math.max(1, dp(.85f)), magnifier ? AppStyle.GOLD : AppStyle.OUTLINE);
      headerLoupeButton.setBackground(
          new RippleDrawable(
              ColorStateList.valueOf(AppStyle.RIPPLE),
              bg,
              AppStyle.shape(activity, Color.WHITE, 9f, false)));
    }
    if (headerArrangeButton != null) {
      headerArrangeButton.setEnabled(hasPhotos);
      headerArrangeButton.setImageTintList(
          ColorStateList.valueOf(hasPhotos ? AppStyle.INK : AppStyle.DISABLED));
    }

    for (int i = 0; i < contextSlots.length; i++) {
      ToolSlot slot = contextSlots[i];
      if (slot == null) continue;
      boolean enabled = (i == 0) ? hasPhotos : hasSelected;
      boolean activeLoupe = (i == 0) && magnifier && hasPhotos;
      int color =
          !enabled ? AppStyle.DISABLED
              : activeLoupe ? AppStyle.GOLD
              : (i == 2 ? AppStyle.DANGER : AppStyle.MUTED);
      slot.container.setEnabled(enabled);
      slot.container.setBackground(
          new RippleDrawable(
              ColorStateList.valueOf(AppStyle.RIPPLE),
              activeLoupe ? AppStyle.shape(activity, AppStyle.ACCENT_SOFT, 10f, false) : null,
              AppStyle.shape(activity, Color.WHITE, 10f, false)));
      slot.icon.setImageTintList(ColorStateList.valueOf(color));
      slot.label.setTextColor(color);
      AppStyle.setTypeface(
          slot.label, activeLoupe ? AppStyle.FONT_SEMIBOLD : AppStyle.FONT_MEDIUM);
    }

    boolean showContext = magnifier || hasSelected;
    if (contextBar != null) {
      contextBar.setVisibility(showContext ? VISIBLE : GONE);
      if (selectionPill != null)
        selectionPill.setVisibility(showContext && hasSelected ? VISIBLE : GONE);
      if (loupePill != null) loupePill.setVisibility(magnifier && !hasSelected ? VISIBLE : GONE);
    }
    if (contextBarVisible != showContext) {
      contextBarVisible = showContext;
      if (contextBarListener != null) contextBarListener.onContextBarVisible(showContext);
    }
  }

  private void updateCount() {
    if (countLabel == null) return;
    countLabel.setText(String.format(Locale.ROOT, "%d / %d", table.size(), MAX_PHOTOS));
    countLabel.setContentDescription("已添加 " + table.size() + " 张，最多 " + MAX_PHOTOS + " 张");
    updateTools();
  }

  private void select(TablePhoto photo) {
    selected = photo;
    if (photo != null) {
      table.remove(photo);
      table.add(photo);
    }
    updateTools();
    canvas.invalidate();
  }

  private void scaleSelected(float factor) {
    if (selected == null) return;
    canvas.finishSnapBackImmediately();
    float maxScale = canvas.maxSafeScaleForPhoto(selected);
    selected.scale = clamp(selected.scale * factor, .45f, Math.min(2.4f, maxScale));
    canvas.clampPhotoInPlace(selected);
    canvas.invalidate();
  }

  private void rotateSelected() {
    if (selected == null) {
      AppNotice.show(activity, "先选一张照片");
      return;
    }
    canvas.finishSnapBackImmediately();
    selected.rotation = (selected.rotation + 90f) % 360f;
    canvas.clampPhotoInPlace(selected);
    saveState();
    canvas.invalidate();
  }

  private void removeSelected() {
    if (selected == null) {
      AppNotice.show(activity, "先选一张照片");
      return;
    }
    canvas.finishSnapBackImmediately();
    TablePhoto removed = selected;
    table.remove(removed);
    selected = null;
    removed.decoded = null;
    removed.thumbPlaceholder = null;
    saveState();
    updateCount();
    canvas.invalidate();
  }

  private void arrange() {
    canvas.finishSnapBackImmediately();
    int n = table.size();
    if (n == 0) {
      saveState();
      updateCount();
      canvas.invalidate();
      return;
    }
    if (n == 1) {
      place(table.get(0), .5f, .5f, -2.5f);
    } else if (landscapeMode) {
      if (n == 2) {
        place(table.get(0), .32f, .50f, -3.5f);
        place(table.get(1), .68f, .50f, 3f);
      } else if (n == 3) {
        place(table.get(0), .22f, .48f, -4.5f);
        place(table.get(1), .50f, .52f, 2.5f);
        place(table.get(2), .78f, .48f, -2f);
      } else {
        int columns = n <= 6 ? 3 : 4;
        int rows = (n + columns - 1) / columns;
        for (int i = 0; i < n; i++) {
          int col = i % columns, row = i / columns;
          float x =
              columns == 3
                  ? (.20f + .30f * col)
                  : (.16f + .68f * col / (columns - 1f));
          float y =
              rows == 2 ? (.30f + .40f * row) : (.22f + .56f * row / Math.max(1f, rows - 1f));
          place(table.get(i), x, y, (i % 2 == 0 ? -3.2f : 3.2f) + (i % 3 - 1) * 1.4f);
          if (n > 8) table.get(i).scale = .80f;
          else if (n > 4) table.get(i).scale = .88f;
        }
      }
    } else if (n == 2) {
      place(table.get(0), .48f, .33f, -3.5f);
      place(table.get(1), .52f, .68f, 3f);
    } else if (n == 3) {
      place(table.get(0), .43f, .27f, -5f);
      place(table.get(1), .61f, .50f, 4f);
      place(table.get(2), .44f, .76f, 2f);
    } else {
      int columns = 2;
      int rows = (n + columns - 1) / columns;
      for (int i = 0; i < n; i++) {
        int col = i % columns, row = i / columns;
        float x = .28f + .44f * col;
        float y = rows == 2 ? (.30f + .40f * row) : (.15f + .70f * row / (rows - 1f));
        place(table.get(i), x, y, (i % 2 == 0 ? -3.5f : 3.5f) + (i % 3 - 1) * 1.5f);
        if (n > 6) table.get(i).scale = .86f;
      }
    }
    canvas.clampAllInPlace();
    saveState();
    updateCount();
    canvas.invalidate();
    if (magnifier) canvas.showLensAtSelected();
  }

  private void place(TablePhoto photo, float x, float y, float rotation) {
    photo.x = x;
    photo.y = y;
    photo.scale = 1f;
    photo.rotation = rotation;
  }

  private void addPhotos(List<SourcePhoto> chosen) {
    if (chosen == null || chosen.isEmpty()) return;
    HashSet<String> existing = new HashSet<>();
    for (TablePhoto item : table) existing.add(item.source.id);
    ArrayList<SourcePhoto> fresh = new ArrayList<>();
    for (SourcePhoto candidate : chosen) {
      if (candidate != null && existing.add(candidate.id)) fresh.add(candidate);
    }
    if (fresh.isEmpty()) return;
    int room = MAX_PHOTOS - table.size();
    if (room <= 0) {
      AppNotice.show(activity, "观片台最多 " + MAX_PHOTOS + " 张");
      return;
    }
    for (int i = 0; i < fresh.size() && i < room; i++) {
      TablePhoto photo = new TablePhoto(fresh.get(i));
      table.add(photo);
      loadBitmap(photo);
    }
    if (fresh.size() > room)
      AppNotice.show(activity, "已添加前 " + room + " 张 · 最多 " + MAX_PHOTOS + " 张");
    selected = null;
    arrange();
  }

  private void loadBitmap(TablePhoto photo) {
    // Stage a cached thumbnail while the full developed piece decodes off-thread.
    String key = thumbCacheKey(photo.source);
    Bitmap cachedThumb = thumbCache.get(key);
    if (cachedThumb != null) {
      photo.thumbPlaceholder = cachedThumb;
    } else {
      thumbExecutor.execute(
          () -> {
            if (released) return;
            Bitmap staged = cachedThumb(photo.source, key);
            if (staged == null) return;
            main.post(
                () -> {
                  if (released || !table.contains(photo) || photo.decoded != null) return;
                  photo.thumbPlaceholder = staged;
                  canvas.invalidate();
                });
          });
    }
    requestPiece(photo);
  }

  void developedLookChanged(String photoId) {
    for (TablePhoto photo : table)
      if (photo.source.id.equals(photoId)) requestPiece(photo);
  }

  private void requestPiece(TablePhoto photo) {
    io.execute(
        () -> {
          if (released) return;
          DecodedPiece piece = decode(photo.source);
          main.post(
              () -> {
                if (released || !table.contains(photo)) {
                  if (piece != null) Bitmaps.release(piece.bitmap);
                  return;
                }
                if (piece != null) {
                  photo.decoded = piece;
                  photo.thumbPlaceholder = null;
                  if (canvas != null && !canvas.isDraggingPhoto(photo)) {
                    canvas.clampPhotoInPlace(photo);
                  }
                  canvas.invalidate();
                  if (refreshExportPreview != null) refreshExportPreview.run();
                }
              });
        });
  }

  private static String fileKey(File file) {
    if (file == null || !file.isFile()) return null;
    return file.getAbsolutePath() + ":" + file.lastModified() + ":" + file.length();
  }

  private String thumbCacheKey(SourcePhoto source) {
    String key = fileKey(source.preferredLook());
    if (key == null) key = fileKey(source.thumbnail);
    return source.id + ":" + key;
  }

  private Bitmap cachedThumb(SourcePhoto source, String key) {
    Bitmap cached = thumbCache.get(key);
    if (cached != null) return cached;
    Bitmap thumb = decodeDerived(source.preferredLook(), THUMB_MAX_EDGE, false);
    if (thumb == null) thumb = decodeDerived(source.thumbnail, THUMB_MAX_EDGE, false);
    if (thumb == null && source.path != null) thumb = decodeFile(new File(source.path), THUMB_MAX_EDGE);
    if (thumb != null && !released && key.equals(thumbCacheKey(source))) thumbCache.put(key, thumb);
    return thumb;
  }

  /** Developed images are already oriented. Preserve PNG alpha and keep JPEG thumbnails cheap. */
  private static Bitmap decodeDerived(File file, int maxEdge, boolean full) {
    if (file == null || !file.isFile()) return null;
    try {
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
      int longest = Math.max(bounds.outWidth, bounds.outHeight);
      if (longest <= 0) return null;
      int sample = 1;
      while (longest / (sample * 2) >= maxEdge) sample *= 2;
      BitmapFactory.Options opts = new BitmapFactory.Options();
      opts.inSampleSize = sample;
      opts.inPreferredConfig =
          full || file.getName().endsWith(".png") ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
      opts.inMutable = full;
      return BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  private DecodedPiece decode(SourcePhoto source) {
    File file = source.preferredLook();
    String key = fileKey(file);
    Bitmap developed = key == null ? null : decodeDerived(file, DECODE_MAX_EDGE, true);
    if (developed != null) {
      if (file.equals(source.developed) && source.style.legacyStrip())
        prepareLegacyFilmStrip(developed, source.edgeAmount);
      if (developed.hasAlpha() && source.style.acetate()) applyAcetateSheen(developed);
      return new DecodedPiece(developed, key, source.style.acetate());
    }
    Bitmap bitmap = source.path == null ? null : decodeFile(new File(source.path), DECODE_MAX_EDGE);
    if (bitmap == null) bitmap = decodeDerived(source.thumbnail, DECODE_MAX_EDGE, true);
    return bitmap == null ? null : new DecodedPiece(bitmap, null, false);
  }

  /** Compatibility for cached JPEGs from before the renderer saved native film alpha. */
  private void prepareLegacyFilmStrip(Bitmap src, float edgeAmount) {
    int w = src.getWidth(), h = src.getHeight();
    if (w < 64 || h < 64) return;
    FilmHoleSpec spec = detectFilmHoleSpec(src, w, h, clamp(edgeAmount, 2f, 15f) / 100f);
    if (spec == null) return;
    src.setHasAlpha(true);
    Canvas c = new Canvas(src);
    Paint punch = new Paint(Paint.ANTI_ALIAS_FLAG);
    punch.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    rim.setStyle(Paint.Style.STROKE);
    rim.setColor(0x38fff4dc);
    rim.setStrokeWidth(Math.max(1f, spec.rail * .024f));

    for (int side = 0; side < 2; side++) {
      float v = side == 0 ? spec.rail * .42f : spec.across + spec.rail * 1.58f;
      for (int i = 0; i < 8; i++) {
        float u = spec.end + (i + .5f) * spec.pitch;
        float cx = spec.rotate ? (w - v) : u;
        float cy = spec.rotate ? u : v;
        float hw = (spec.rotate ? spec.holeH : spec.holeW) * .5f;
        float hh = (spec.rotate ? spec.holeW : spec.holeH) * .5f;
        float r = Math.min(hw, hh) * .34f;
        c.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, r, r, punch);
        c.drawRoundRect(cx - hw, cy - hh, cx + hw, cy + hh, r, r, rim);
      }
    }
  }

  /** Bake gloss once and keep the exact alpha, including rounded corners and thin silver base. */
  private static void applyAcetateSheen(Bitmap bitmap) {
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    LinearGradient sheen = pieceSheen(true);
    Matrix matrix = new Matrix();
    matrix.setScale(bitmap.getWidth(), bitmap.getHeight());
    matrix.postTranslate(bitmap.getWidth() * .5f, bitmap.getHeight() * .5f);
    sheen.setLocalMatrix(matrix);
    paint.setShader(sheen);
    paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
    canvas.drawPaint(paint);
  }

  private static final class FilmHoleSpec {
    final boolean rotate;
    final float rail, end, across, pitch, holeW, holeH;

    FilmHoleSpec(
        boolean rotate,
        float rail,
        float end,
        float across,
        float pitch,
        float holeW,
        float holeH) {
      this.rotate = rotate;
      this.rail = rail;
      this.end = end;
      this.across = across;
      this.pitch = pitch;
      this.holeW = holeW;
      this.holeH = holeH;
    }
  }

  private FilmHoleSpec detectFilmHoleSpec(Bitmap bmp, int w, int h, float amount) {
    FilmHoleSpec horiz = buildAndValidateFilmSpec(bmp, w, h, amount, false);
    if (horiz != null) return horiz;
    return buildAndValidateFilmSpec(bmp, w, h, amount, true);
  }

  private FilmHoleSpec buildAndValidateFilmSpec(
      Bitmap bmp, int w, int h, float amount, boolean rotate) {
    float denom = 1f + 3.64f * amount;
    float across = (rotate ? w : h) / denom;
    float rail = across * 1.82f * amount;
    float end = across * .56f * amount;
    float length = (rotate ? h : w) - 2f * end;
    if (length < across * .94f || rail < 4f) return null;

    float pitch = length / 8f;
    float holeW = Math.min(.47f * pitch, .83f * rail);
    float holeH = .55f * rail;
    if (holeW < 2f || holeH < 2f) return null;

    float holeLumaSum = 0f;
    for (int side = 0; side < 2; side++) {
      float v = side == 0 ? rail * .42f : across + rail * 1.58f;
      for (int i = 0; i < 8; i++) {
        float u = end + (i + .5f) * pitch;
        int sx = Math.round(clamp(rotate ? (w - v) : u, 0, w - 1));
        int sy = Math.round(clamp(rotate ? u : v, 0, h - 1));
        float lum = pixelLuma(bmp.getPixel(sx, sy));
        if (lum < .68f) return null;
        holeLumaSum += lum;
      }
    }

    int darkRebateCount = 0;
    float rebateLumaSum = 0f;
    for (int side = 0; side < 2; side++) {
      float v = side == 0 ? rail * .42f : across + rail * 1.58f;
      for (int i = 0; i < 7; i++) {
        float u = end + (i + 1f) * pitch;
        int sx = Math.round(clamp(rotate ? (w - v) : u, 0, w - 1));
        int sy = Math.round(clamp(rotate ? u : v, 0, h - 1));
        float lum = pixelLuma(bmp.getPixel(sx, sy));
        rebateLumaSum += lum;
        if (lum <= .55f) darkRebateCount++;
      }
    }
    float avgHole = holeLumaSum / 16f;
    float avgRebate = rebateLumaSum / 14f;
    if (darkRebateCount < 11 || avgHole - avgRebate < .26f) return null;
    return new FilmHoleSpec(rotate, rail, end, across, pitch, holeW, holeH);
  }

  private static float pixelLuma(int argb) {
    int r = (argb >> 16) & 0xff;
    int g = (argb >> 8) & 0xff;
    int b = argb & 0xff;
    return (0.299f * r + 0.587f * g + 0.114f * b) / 255f;
  }

  private Bitmap decodeFile(File file, int maxEdge) {
    if (file == null || !file.isFile()) return null;
    try {
      return RasterDecoder.decode(file, maxEdge).bitmap;
    } catch (Exception ignored) {
      return null;
    }
  }

  private void loadState() {
    table.clear();
    selected = null;
    try {
      String saved = prefs.getString("lighttable_state", "");
      if (saved == null || saved.isBlank()) return;
      JSONObject state = new JSONObject(saved);
      JSONArray savedPhotos = state.optJSONArray("photos");
      if (savedPhotos == null) return;
      for (int i = 0; i < savedPhotos.length() && table.size() < MAX_PHOTOS; i++) {
        JSONObject entry = savedPhotos.optJSONObject(i);
        if (entry == null) continue;
        String id = entry.optString("id", "");
        SourcePhoto source = findSource(id);
        if (source == null) continue;
        TablePhoto photo = new TablePhoto(source);
        photo.x = (float) entry.optDouble("x", .5);
        photo.y = (float) entry.optDouble("y", .5);
        photo.scale = (float) entry.optDouble("scale", 1);
        photo.rotation = (float) entry.optDouble("rotation", 0);
        if (canvas != null) canvas.clampPhotoInPlace(photo);
        table.add(photo);
        loadBitmap(photo);
      }
      // Open in browse mode so the shared bottom 3-tab capsule stays visible until a photo is tapped.
      selected = null;
    } catch (JSONException ignored) {
      table.clear();
    }
  }

  private SourcePhoto findSource(String id) {
    for (SourcePhoto source : sources) if (source.id.equals(id)) return source;
    return null;
  }

  private void saveState() {
    try {
      JSONObject state = new JSONObject();
      state.put("orientation", 1);
      JSONArray saved = new JSONArray();
      for (TablePhoto photo : table) {
        JSONObject entry = new JSONObject();
        entry.put("id", photo.source.id);
        float[] safe = canvas != null ? canvas.settledCoordinatesFor(photo) : null;
        entry.put("x", safe != null ? safe[0] : photo.x);
        entry.put("y", safe != null ? safe[1] : photo.y);
        entry.put("scale", safe != null ? safe[2] : photo.scale);
        entry.put("rotation", photo.rotation);
        saved.put(entry);
      }
      state.put("photos", saved);
      prefs.edit().putString("lighttable_state", state.toString()).apply();
    } catch (JSONException ignored) {
    }
  }

  private void showAddPhotos() {
    int widthDp = FoldableUi.windowWidthDp(activity);
    if (FoldableLayout.lightTableAddPopup(widthDp)) {
      if (addPopupView != null) {
        closeAddPopup();
      } else {
        openAddPopup(widthDp);
      }
      return;
    }
    showAddPhotosDialog();
  }

  private void closeAddPopup() {
    addPopupGen.incrementAndGet();
    addPopupView = null;
    if (addPopupHost != null) {
      addPopupHost.removeAllViews();
      addPopupHost.setVisibility(GONE);
    }
    if (headerAddButton != null) {
      headerAddButton.setImageTintList(ColorStateList.valueOf(AppStyle.INK));
      GradientDrawable bg = AppStyle.shape(activity, AppStyle.SURFACE, 9f, true);
      headerAddButton.setBackground(
          new RippleDrawable(
              ColorStateList.valueOf(AppStyle.RIPPLE),
              bg,
              AppStyle.shape(activity, Color.WHITE, 9f, false)));
    }
  }

  private void attachAddPopupForWidth(int widthDp) {
    if (addPopupView == null) return;
    if (addPopupView.getParent() instanceof ViewGroup parent) {
      parent.removeView(addPopupView);
    }
    if (!FoldableLayout.lightTableAddPopup(widthDp) || addPopupHost == null) {
      closeAddPopup();
      return;
    }
    int popupW = dp(Math.max(200, FoldableLayout.lightTableAddPopupWidthDp(widthDp)));
    FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(popupW, -1, Gravity.END);
    params.topMargin = dp(6);
    params.rightMargin = dp(18);
    params.bottomMargin = dp(84);
    addPopupHost.removeAllViews();
    addPopupHost.addView(addPopupView, params);
    addPopupHost.setVisibility(VISIBLE);
    addPopupHost.bringToFront();
  }

  private void openAddPopup(int widthDp) {
    closeAddPopup();
    final int gen = addPopupGen.incrementAndGet();

    LinearLayout panel = new LinearLayout(activity);
    panel.setOrientation(VERTICAL);
    panel.setBackground(AppStyle.shape(activity, AppStyle.SURFACE, 18f, true));
    panel.setClipToOutline(true);
    panel.setElevation(dp(18));
    panel.setClickable(true);
    panel.setPadding(dp(12), dp(12), dp(12), dp(12));
    panel.setTag("lighttable.add.popup");

    LinearLayout popupHeader = new LinearLayout(activity);
    popupHeader.setOrientation(HORIZONTAL);
    popupHeader.setGravity(Gravity.CENTER_VERTICAL);
    popupHeader.setPadding(dp(2), 0, 0, dp(10));
    LinearLayout titleCol = new LinearLayout(activity);
    titleCol.setOrientation(VERTICAL);
    TextView heading = text("添加照片", AppStyle.TYPE_TITLE, AppStyle.INK);
    AppStyle.setTypeface(heading, AppStyle.FONT_BOLD);
    titleCol.addView(heading, new LayoutParams(-2, -2));
    TextView sub =
        text("已添加 " + table.size() + " / " + MAX_PHOTOS + " 张", AppStyle.TYPE_MICRO, AppStyle.GOLD);
    AppStyle.setNumericTypeface(sub);
    AppStyle.setTypeface(sub, AppStyle.FONT_SEMIBOLD);
    LayoutParams subLp = new LayoutParams(-2, -2);
    subLp.topMargin = dp(2);
    titleCol.addView(sub, subLp);
    popupHeader.addView(titleCol, new LayoutParams(0, -2, 1));

    TextView closeBtn = text("×", 18, AppStyle.MUTED);
    closeBtn.setGravity(Gravity.CENTER);
    closeBtn.setContentDescription("关闭添加照片面板");
    closeBtn.setClickable(true);
    closeBtn.setFocusable(true);
    closeBtn.setOnClickListener(v -> closeAddPopup());
    popupHeader.addView(closeBtn, new LayoutParams(dp(34), dp(34)));
    panel.addView(popupHeader, new LayoutParams(-1, -2));

    ScrollView scroll = new ScrollView(activity);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout content = new LinearLayout(activity);
    content.setOrientation(VERTICAL);
    content.setPadding(0, dp(4), 0, dp(10));
    scroll.addView(content);
    FrameLayout scrollWrap =
        AppStyle.wrapScrollFade(activity, scroll, AppStyle.SURFACE, 20, 20, false);

    final Set<String> tableIds = new HashSet<>();
    for (TablePhoto photo : table) tableIds.add(photo.source.id);
    final Set<String> chosen = new HashSet<>();
    final List<SourcePhoto> visible = sources.size() > 30 ? sources.subList(0, 30) : sources;
    final List<Runnable> pendingThumbLoads = new ArrayList<>();

    final TextView confirm = button("添加", AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    confirm.setTextSize(AppStyle.TYPE_LABEL);
    AppStyle.setTypeface(confirm, AppStyle.FONT_BOLD);
    confirm.setMinHeight(dp(42));
    confirm.setEnabled(false);

    if (visible.isEmpty()) {
      TextView empty = text("还没有导入照片", AppStyle.TYPE_BODY, AppStyle.MUTED);
      empty.setGravity(Gravity.CENTER);
      content.addView(empty, new LayoutParams(-1, dp(160)));
    } else {
      LinearLayout row = null;
      for (int i = 0; i < visible.size(); i++) {
        if (i % 2 == 0) {
          row = new LinearLayout(activity);
          row.setGravity(Gravity.TOP);
          row.setWeightSum(2f);
          content.addView(row, new LayoutParams(-1, -2));
        }
        SourcePhoto source = visible.get(i);
        boolean already = tableIds.contains(source.id);
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(VERTICAL);
        cell.setBackground(AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_SMALL, false));
        GradientDrawable cardBorder = new GradientDrawable();
        cardBorder.setShape(GradientDrawable.RECTANGLE);
        cardBorder.setColor(Color.TRANSPARENT);
        cardBorder.setCornerRadius(dp(AppStyle.R_SMALL));
        cardBorder.setStroke(Math.max(1, dp(.75f)), AppStyle.OUTLINE);
        cell.setForeground(cardBorder);
        cell.setClipToOutline(true);

        FrameLayout frame = aspectFrame(.78f);
        frame.setBackgroundColor(AppStyle.CONTROL);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(AppStyle.CONTROL);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        final String thumbKey = thumbCacheKey(source);
        image.setTag(thumbKey);
        Bitmap cachedThumb = thumbCache.get(thumbKey);
        if (cachedThumb != null && !cachedThumb.isRecycled()) {
          image.setImageBitmap(cachedThumb);
        } else {
          pendingThumbLoads.add(() -> loadThumbForPopup(source, thumbKey, image, gen));
        }

        final ImageView check;
        if (already) {
          check = null;
          TextView badge = text("已添加", 9, AppStyle.NOTICE_INK);
          AppStyle.setTypeface(badge, AppStyle.FONT_SEMIBOLD);
          badge.setGravity(Gravity.CENTER);
          badge.setPadding(dp(5), dp(2), dp(5), dp(2));
          badge.setBackground(AppStyle.shape(activity, AppStyle.NOTICE_BG, 5f, false));
          FrameLayout.LayoutParams badgeLp =
              new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
          badgeLp.topMargin = dp(5);
          badgeLp.rightMargin = dp(5);
          frame.addView(badge, badgeLp);
        } else {
          check = new ImageView(activity);
          check.setImageResource(R.drawable.ic_material_check);
          check.setImageTintList(ColorStateList.valueOf(AppStyle.ON_ACCENT));
          check.setPadding(dp(3), dp(3), dp(3), dp(3));
          styleAddPickBadge(check, false);
          FrameLayout.LayoutParams checkLp =
              new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.TOP | Gravity.END);
          checkLp.topMargin = dp(5);
          checkLp.rightMargin = dp(5);
          frame.addView(check, checkLp);
        }
        cell.addView(frame, new LayoutParams(-1, -2));

        TextView name = text(PhotoLabels.title(source.name), AppStyle.TYPE_MICRO, AppStyle.INK);
        AppStyle.setTypeface(name, AppStyle.FONT_MEDIUM);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(6), dp(5), dp(6), dp(6));
        cell.addView(name, new LayoutParams(-1, -2));

        cell.setAlpha(already ? .55f : 1f);
        cell.setContentDescription(PhotoLabels.title(source.name) + (already ? "，已添加" : ""));
        if (!already) {
          cell.setOnClickListener(
              v -> {
                boolean picked;
                if (chosen.remove(source.id)) {
                  picked = false;
                } else {
                  chosen.add(source.id);
                  picked = true;
                }
                cardBorder.setStroke(
                    Math.max(1, dp(picked ? 2f : .75f)),
                    picked ? AppStyle.GOLD : AppStyle.OUTLINE);
                if (check != null) styleAddPickBadge(check, picked);
                confirm.setText(chosen.isEmpty() ? "添加" : "添加 · " + chosen.size() + " 张");
                confirm.setEnabled(!chosen.isEmpty());
              });
        } else {
          cell.setClickable(false);
        }
        LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, -2, 1);
        cellParams.bottomMargin = dp(8);
        if (i % 2 == 0) cellParams.rightMargin = dp(4);
        else cellParams.leftMargin = dp(4);
        row.addView(cell, cellParams);
      }
      if (visible.size() % 2 == 1 && row != null) {
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 1, 1);
        spacerParams.leftMargin = dp(4);
        row.addView(new View(activity), spacerParams);
      }
    }
    panel.addView(scrollWrap, new LayoutParams(-1, 0, 1));

    LinearLayout actions = new LinearLayout(activity);
    actions.setOrientation(HORIZONTAL);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    actions.setPadding(0, dp(8), 0, 0);
    TextView cancel = button("取消", AppStyle.INK, AppStyle.CONTROL);
    cancel.setTextSize(AppStyle.TYPE_LABEL);
    cancel.setMinHeight(dp(42));
    cancel.setOnClickListener(v -> closeAddPopup());
    confirm.setOnClickListener(
        v -> {
          List<SourcePhoto> picked = new ArrayList<>();
          for (SourcePhoto source : visible) {
            if (chosen.contains(source.id)) picked.add(source);
          }
          addPhotos(picked);
          closeAddPopup();
        });
    actions.addView(cancel, new LayoutParams(0, dp(42), 1));
    LayoutParams confirmLp = new LayoutParams(0, dp(42), 1);
    confirmLp.leftMargin = dp(8);
    actions.addView(confirm, confirmLp);
    panel.addView(actions, new LayoutParams(-1, -2));

    addPopupView = panel;
    attachAddPopupForWidth(widthDp);
    if (headerAddButton != null) {
      headerAddButton.setImageTintList(ColorStateList.valueOf(AppStyle.GOLD));
      GradientDrawable activeBg = AppStyle.shape(activity, AppStyle.ACCENT_SOFT, 9f, false);
      activeBg.setStroke(Math.max(1, dp(.85f)), AppStyle.GOLD);
      headerAddButton.setBackground(
          new RippleDrawable(
              ColorStateList.valueOf(AppStyle.RIPPLE),
              activeBg,
              AppStyle.shape(activity, Color.WHITE, 9f, false)));
    }
    for (Runnable task : pendingThumbLoads) task.run();
  }

  private void loadThumbForPopup(
      SourcePhoto source, String key, ImageView image, int expectedGen) {
    thumbExecutor.execute(
        () -> {
          if (released || addPopupGen.get() != expectedGen) return;
          Bitmap result = cachedThumb(source, key);
          if (result == null) return;
          main.post(
              () -> {
                if (released || addPopupGen.get() != expectedGen) return;
                if (!key.equals(image.getTag())) return;
                if (!result.isRecycled()) image.setImageBitmap(result);
              });
        });
  }

  private void showAddPhotosDialog() {
    if (addSheet != null && addSheet.isShowing()) return;
    Dialog dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    LinearLayout sheet = new LinearLayout(activity);
    sheet.setOrientation(VERTICAL);
    sheet.setBackground(AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_LARGE, true));
    sheet.setClipToOutline(true);
    sheet.setPadding(dp(20), dp(16), dp(20), dp(14));

    LinearLayout title = new LinearLayout(activity);
    title.setGravity(Gravity.CENTER_VERTICAL);
    title.setPadding(0, dp(4), 0, dp(14));
    TextView heading = text("添加照片", AppStyle.TYPE_HEADING, AppStyle.INK);
    AppStyle.setTypeface(heading, AppStyle.FONT_BOLD);
    title.addView(heading, new LayoutParams(0, -2, 1));
    TextView added =
        text("已添加 " + table.size() + " / " + MAX_PHOTOS + " 张", AppStyle.TYPE_LABEL, AppStyle.GOLD);
    AppStyle.setNumericTypeface(added);
    AppStyle.setTypeface(added, AppStyle.FONT_BOLD);
    title.addView(added, new LayoutParams(-2, -2));
    sheet.addView(title, new LayoutParams(-1, -2));

    ScrollView scroll = new ScrollView(activity);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout content = new LinearLayout(activity);
    content.setOrientation(VERTICAL);
    content.setPadding(0, dp(8), 0, dp(14));
    scroll.addView(content);
    FrameLayout scrollWrap = AppStyle.wrapScrollFade(activity, scroll, AppStyle.SURFACE, 28, 26, false);

    final Set<String> tableIds = new HashSet<>();
    for (TablePhoto photo : table) tableIds.add(photo.source.id);
    final Set<String> chosen = new HashSet<>();
    final List<SourcePhoto> visible = sources.size() > 30 ? sources.subList(0, 30) : sources;
    final List<Runnable> pendingThumbLoads = new ArrayList<>();

    if (visible.isEmpty()) {
      TextView empty = text("还没有导入照片", AppStyle.TYPE_BODY, AppStyle.MUTED);
      empty.setGravity(Gravity.CENTER);
      content.addView(empty, new LayoutParams(-1, dp(180)));
    } else {
      LinearLayout row = null;
      for (int i = 0; i < visible.size(); i++) {
        if (i % 2 == 0) {
          row = new LinearLayout(activity);
          row.setGravity(Gravity.TOP);
          row.setWeightSum(2f);
          content.addView(row, new LayoutParams(-1, -2));
        }
        SourcePhoto source = visible.get(i);
        boolean already = tableIds.contains(source.id);
        LinearLayout cell = new LinearLayout(activity);
        cell.setOrientation(VERTICAL);
        cell.setBackground(AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_MEDIUM, false));
        // Draw card/selection stroke on foreground so it wraps cleanly over BOTH photo and label.
        GradientDrawable cardBorder = new GradientDrawable();
        cardBorder.setShape(GradientDrawable.RECTANGLE);
        cardBorder.setColor(Color.TRANSPARENT);
        cardBorder.setCornerRadius(dp(AppStyle.R_MEDIUM));
        cardBorder.setStroke(Math.max(1, dp(.75f)), AppStyle.OUTLINE);
        cell.setForeground(cardBorder);
        cell.setClipToOutline(true);
        FrameLayout frame = aspectFrame(.62f);
        frame.setBackgroundColor(AppStyle.CONTROL);
        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(AppStyle.CONTROL);
        frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
        final String thumbKey = thumbCacheKey(source);
        image.setTag(thumbKey);
        Bitmap cachedThumb = thumbCache.get(thumbKey);
        if (cachedThumb != null && !cachedThumb.isRecycled()) {
          image.setImageBitmap(cachedThumb);
        } else {
          pendingThumbLoads.add(() -> loadThumbForDialog(source, thumbKey, image, dialog));
        }
        final ImageView check;
        if (already) {
          check = null;
          TextView badge = text("已添加", AppStyle.TYPE_MICRO, AppStyle.NOTICE_INK);
          AppStyle.setTypeface(badge, AppStyle.FONT_SEMIBOLD);
          badge.setGravity(Gravity.CENTER);
          badge.setPadding(dp(7), dp(3), dp(7), dp(3));
          badge.setBackground(AppStyle.shape(activity, AppStyle.NOTICE_BG, 6f, false));
          FrameLayout.LayoutParams params =
              new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
          params.topMargin = dp(7);
          params.rightMargin = dp(7);
          frame.addView(badge, params);
        } else {
          check = new ImageView(activity);
          check.setImageResource(R.drawable.ic_material_check);
          check.setImageTintList(ColorStateList.valueOf(AppStyle.ON_ACCENT));
          check.setPadding(dp(4), dp(4), dp(4), dp(4));
          styleAddPickBadge(check, false);
          FrameLayout.LayoutParams params =
              new FrameLayout.LayoutParams(
                  dp(AppStyle.SELECTION_BADGE), dp(AppStyle.SELECTION_BADGE),
                  Gravity.TOP | Gravity.END);
          params.topMargin = dp(7);
          params.rightMargin = dp(7);
          frame.addView(check, params);
        }
        cell.addView(frame, new LayoutParams(-1, -2));
        LinearLayout labels = new LinearLayout(activity);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        labels.setPadding(dp(9), dp(6), dp(9), dp(7));
        TextView name = text(PhotoLabels.title(source.name), AppStyle.TYPE_LABEL, AppStyle.INK);
        AppStyle.setTypeface(name, AppStyle.FONT_MEDIUM);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name, new LayoutParams(0, -2, 1));
        TextView size = text(mb(source.bytes), AppStyle.TYPE_MICRO, AppStyle.MUTED);
        labels.addView(size, new LayoutParams(-2, -2));
        cell.addView(labels, new LayoutParams(-1, -2));
        cell.setAlpha(already ? .55f : 1f);
        cell.setContentDescription(PhotoLabels.title(source.name) + (already ? "，已添加" : ""));
        if (!already) {
          cell.setOnClickListener(
              v -> {
                boolean picked;
                if (chosen.remove(source.id)) {
                  picked = false;
                } else {
                  chosen.add(source.id);
                  picked = true;
                }
                cardBorder.setStroke(
                    Math.max(1, dp(picked ? 2f : .75f)),
                    picked ? AppStyle.GOLD : AppStyle.OUTLINE);
                if (check != null) styleAddPickBadge(check, picked);
                updateAddAction(chosen);
              });
        } else {
          cell.setClickable(false);
        }
        LinearLayout.LayoutParams cellParams = new LinearLayout.LayoutParams(0, -2, 1);
        cellParams.bottomMargin = dp(10);
        if (i % 2 == 0) cellParams.rightMargin = dp(5);
        else cellParams.leftMargin = dp(5);
        row.addView(cell, cellParams);
      }
      if (visible.size() % 2 == 1 && row != null) {
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(0, 1, 1);
        spacerParams.leftMargin = dp(5);
        row.addView(new View(activity), spacerParams);
      }
    }
    sheet.addView(scrollWrap, new LayoutParams(-1, 0, 1));

    LinearLayout actions = new LinearLayout(activity);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    actions.setPadding(0, dp(6), 0, 0);
    TextView cancel = button("取消", AppStyle.INK, AppStyle.CONTROL);
    cancel.setTextSize(AppStyle.TYPE_BODY);
    cancel.setOnClickListener(v -> dialog.dismiss());
    TextView confirm = button("添加", AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    addConfirm = confirm;
    confirm.setTextSize(AppStyle.TYPE_BODY);
    AppStyle.setTypeface(confirm, AppStyle.FONT_BOLD);
    confirm.setEnabled(false);
    confirm.setOnClickListener(
        v -> {
          List<SourcePhoto> picked = new ArrayList<>();
          for (SourcePhoto source : visible)
            if (chosen.contains(source.id)) picked.add(source);
          addPhotos(picked);
          dialog.dismiss();
        });
    actions.addView(cancel, new LinearLayout.LayoutParams(0, dp(48), 1));
    LinearLayout.LayoutParams confirmParams = new LinearLayout.LayoutParams(0, dp(48), 1);
    confirmParams.leftMargin = dp(10);
    actions.addView(confirm, confirmParams);
    sheet.addView(actions, new LayoutParams(-1, -2));

    dialog.setContentView(sheet);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.setWindowAnimations(0);
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(AppStyle.DIM_AMOUNT);
      window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
      WindowManager.LayoutParams lp = window.getAttributes();
      lp.y = dp(FoldableUi.FLOATING_SHEET_MARGIN_DP);
      window.setAttributes(lp);
    }
    addSheet = dialog;
    dialog.setOnDismissListener(
        d -> {
          if (addSheet == dialog) {
            addSheet = null;
            addConfirm = null;
          }
        });
    dialog.show();
    FoldableUi.stabilizeSheetWindow(window, sheet);
    if (window != null) {
      int liveW = FoldableUi.visibleWindowWidthPx(activity);
      if (liveW <= 0) liveW = activity.getResources().getDisplayMetrics().widthPixels;
      int sheetW = FoldableUi.floatingSheetWidthPx(activity, liveW, -1);
      int height =
          Math.min(
              (int) (activity.getResources().getDisplayMetrics().heightPixels * .72f), dp(640));
      window.setLayout(sheetW, height);
    }
    Haptics.install(dialog.getWindow());
    updateAddAction(chosen);
    // Enqueue thumbnail decoding strictly after dialog.show() so callbacks never drop on detach.
    for (Runnable task : pendingThumbLoads) task.run();
  }

  private void loadThumbForDialog(
      SourcePhoto source, String key, ImageView image, Dialog dialog) {
    thumbExecutor.execute(
        () -> {
          if (released || addSheet != dialog) return;
          Bitmap result = cachedThumb(source, key);
          if (result == null) return;
          main.post(
              () -> {
                if (released || addSheet != dialog) return;
                if (!key.equals(image.getTag())) return;
                if (!result.isRecycled()) image.setImageBitmap(result);
              });
        });
  }

  private void styleAddPickBadge(ImageView badge, boolean picked) {
    GradientDrawable bg = new GradientDrawable();
    bg.setShape(GradientDrawable.RECTANGLE);
    bg.setCornerRadius(dp(7));
    bg.setColor(picked ? AppStyle.GOLD : 0x6b0a0a0b);
    bg.setStroke(Math.max(1, dp(1.5f)), picked ? AppStyle.GOLD : 0xd9ffffff);
    badge.setBackground(bg);
    badge.setImageAlpha(picked ? 255 : 0);
  }

  private void updateAddAction(Set<String> chosen) {
    if (addConfirm == null) return;
    addConfirm.setText(chosen.isEmpty() ? "添加" : "添加 · " + chosen.size() + " 张");
    addConfirm.setEnabled(!chosen.isEmpty());
  }

  private List<RenderPiece> snapshotPieces() {
    ArrayList<RenderPiece> pieces = new ArrayList<>(table.size());
    for (TablePhoto photo : table) pieces.add(new RenderPiece(photo));
    return pieces;
  }

  private void exportTable() {
    if (table.isEmpty()) {
      AppNotice.show(activity, "先添加照片");
      return;
    }
    if (canvas != null) canvas.finishSnapBackImmediately();
    showExportSheet();
  }

  private void showExportSheet() {
    if (exportSheet != null && exportSheet.isShowing()) return;
    final Dialog dialog = new Dialog(activity);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

    final PosterTheme[] themeState = {
      "dark".equals(prefs.getString(PREF_EXPORT_THEME, "light"))
          ? PosterTheme.DARK
          : PosterTheme.LIGHT
    };
    final boolean[] footerState = {prefs.getBoolean(PREF_EXPORT_FOOTER, true)};
    final boolean exportLandscape = landscapeMode;
    final Bitmap[] previewHolder = new Bitmap[1];
    final boolean[] exporting = {false};
    final int screenW = activity.getResources().getDisplayMetrics().widthPixels;
    final int exportWidthDp = FoldableUi.windowWidthDp(activity);
    final int exportMaxDp = FoldableLayout.lightTableExportSheetMaxDp(exportWidthDp);
    final int targetSheetW = FoldableUi.floatingSheetWidthPx(activity, screenW, exportMaxDp);

    LinearLayout sheet = new LinearLayout(activity);
    sheet.setOrientation(VERTICAL);
    sheet.setBackground(AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_LARGE, true));
    sheet.setClipToOutline(true);
    sheet.setPadding(dp(20), dp(18), dp(20), dp(14));

    LinearLayout headerRow = new LinearLayout(activity);
    headerRow.setOrientation(HORIZONTAL);
    headerRow.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading = text("导出观片台", AppStyle.TYPE_TITLE, AppStyle.INK);
    AppStyle.setTypeface(heading, AppStyle.FONT_BOLD);
    headerRow.addView(heading, new LayoutParams(0, -2, 1));
    final TextView metaLabel = text("", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    AppStyle.setNumericTypeface(metaLabel);
    headerRow.addView(metaLabel, new LayoutParams(-2, -2));
    sheet.addView(headerRow, new LayoutParams(-1, -2));

    ScrollView bodyScroll = new ScrollView(activity);
    bodyScroll.setVerticalScrollBarEnabled(false);
    LinearLayout body = new LinearLayout(activity);
    body.setOrientation(VERTICAL);
    body.setPadding(0, dp(14), 0, dp(12));
    bodyScroll.addView(body, new LayoutParams(-1, -2));

    // Preview well showing live 420×630 poster thumbnail with generous vertical breathing room
    // so card elevation shadow never clips against the top/bottom edges.
    FrameLayout previewWell = new FrameLayout(activity);
    previewWell.setBackground(AppStyle.shape(activity, AppStyle.CONTROL, AppStyle.R_MEDIUM, true));
    previewWell.setPadding(dp(16), dp(22), dp(16), dp(22));
    previewWell.setClipToPadding(false);
    previewWell.setClipChildren(false);

    int screenH = activity.getResources().getDisplayMetrics().heightPixels;
    int previewH;
    int previewW;
    if (exportLandscape) {
      int sheetApproxW = targetSheetW > 0 ? targetSheetW : screenW;
      int maxPreviewW = Math.min(dp(360), Math.max(dp(220), sheetApproxW - dp(72)));
      previewH = Math.min(dp(220), Math.max(dp(146), Math.round(maxPreviewW * (2f / 3f))));
      previewW = Math.round(previewH * 1.5f);
    } else {
      previewH = Math.min(dp(276), Math.max(dp(176), Math.round(screenH * .32f)));
      previewW = Math.round(previewH * (2f / 3f));
    }
    final ImageView previewImage = new ImageView(activity);
    previewImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
    previewImage.setContentDescription("观片台导出预览");
    GradientDrawable previewClip = AppStyle.shape(activity, AppStyle.CONTROL, 8f, false);
    previewImage.setBackground(previewClip);
    previewImage.setClipToOutline(true);
    previewImage.setElevation(dp(4));
    previewWell.addView(
        previewImage, new FrameLayout.LayoutParams(previewW, previewH, Gravity.CENTER));
    body.addView(previewWell, new LayoutParams(-1, -2));

    // Options card: poster background + Option 1 bottom nameplate switch.
    LinearLayout optsCard = new LinearLayout(activity);
    optsCard.setOrientation(VERTICAL);
    optsCard.setBackground(AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_MEDIUM, true));
    optsCard.setClipToOutline(true);
    LayoutParams optsParams = new LayoutParams(-1, -2);
    optsParams.topMargin = dp(14);
    body.addView(optsCard, optsParams);

    LinearLayout bgRow = new LinearLayout(activity);
    bgRow.setOrientation(HORIZONTAL);
    bgRow.setGravity(Gravity.CENTER_VERTICAL);
    bgRow.setMinimumHeight(dp(50));
    bgRow.setPadding(dp(14), dp(10), dp(14), dp(10));
    TextView bgLabel = text("海报背景", AppStyle.TYPE_BODY, AppStyle.INK);
    AppStyle.setTypeface(bgLabel, AppStyle.FONT_SEMIBOLD);
    bgRow.addView(bgLabel, new LayoutParams(0, -2, 1));

    LinearLayout seg = new LinearLayout(activity);
    seg.setOrientation(HORIZONTAL);
    seg.setGravity(Gravity.CENTER_VERTICAL);
    seg.setPadding(dp(2), dp(2), dp(2), dp(2));
    seg.setBackground(AppStyle.shape(activity, AppStyle.CONTROL, AppStyle.R_SMALL, true));
    final TextView lightOption = text("明室白", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    final TextView darkOption = text("暗房黑", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    for (TextView opt : new TextView[] {lightOption, darkOption}) {
      opt.setGravity(Gravity.CENTER);
      opt.setSingleLine(true);
      opt.setMinHeight(dp(26));
      opt.setPadding(dp(10), dp(3), dp(10), dp(3));
      opt.setClickable(true);
      opt.setFocusable(true);
    }
    seg.addView(lightOption, new LayoutParams(-2, -2));
    seg.addView(darkOption, new LayoutParams(-2, -2));
    bgRow.addView(seg, new LayoutParams(-2, -2));
    optsCard.addView(bgRow, new LayoutParams(-1, -2));

    View divider = new View(activity);
    divider.setBackgroundColor(AppStyle.DIVIDER);
    optsCard.addView(divider, new LayoutParams(-1, Math.max(1, dp(1))));

    LinearLayout footerRow = new LinearLayout(activity);
    footerRow.setOrientation(HORIZONTAL);
    footerRow.setGravity(Gravity.CENTER_VERTICAL);
    footerRow.setMinimumHeight(dp(56));
    footerRow.setPadding(dp(14), dp(10), dp(14), dp(10));
    footerRow.setClickable(true);
    footerRow.setFocusable(true);

    LinearLayout footerTexts = new LinearLayout(activity);
    footerTexts.setOrientation(VERTICAL);
    TextView footerTitle = text("底部铭牌", AppStyle.TYPE_BODY, AppStyle.INK);
    AppStyle.setTypeface(footerTitle, AppStyle.FONT_MEDIUM);
    footerTexts.addView(footerTitle, new LayoutParams(-2, -2));
    TextView footerSub = text("应用图标、宣言与印样信息", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    LayoutParams subParams = new LayoutParams(-2, -2);
    subParams.topMargin = dp(2);
    footerTexts.addView(footerSub, subParams);
    footerRow.addView(footerTexts, new LayoutParams(0, -2, 1));

    final Switch footerSwitch = new Switch(activity);
    AppStyle.styleSwitch(footerSwitch);
    footerSwitch.setChecked(footerState[0]);
    footerSwitch.setContentDescription("底部铭牌");
    footerRow.addView(footerSwitch, new LayoutParams(-2, -2));
    optsCard.addView(footerRow, new LayoutParams(-1, -2));

    LayoutParams scrollParams = new LayoutParams(-1, 0, 1);
    sheet.addView(
        AppStyle.wrapScrollFade(activity, bodyScroll, AppStyle.SURFACE, 26, 26, false),
        scrollParams);

    LinearLayout actions = new LinearLayout(activity);
    actions.setOrientation(HORIZONTAL);
    actions.setGravity(Gravity.CENTER_VERTICAL);
    actions.setPadding(0, dp(14), 0, 0);
    final TextView cancel = button("取消", AppStyle.INK, AppStyle.CONTROL);
    cancel.setTextSize(AppStyle.TYPE_BODY);
    cancel.setOnClickListener(v -> dialog.dismiss());
    final TextView saveBtn = button("保存到相册", AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    saveBtn.setTextSize(AppStyle.TYPE_BODY);
    AppStyle.setTypeface(saveBtn, AppStyle.FONT_BOLD);
    actions.addView(cancel, new LayoutParams(0, dp(AppStyle.TOUCH_PRIMARY), 1));
    LayoutParams saveParams = new LayoutParams(0, dp(AppStyle.TOUCH_PRIMARY), 1);
    saveParams.leftMargin = dp(12);
    actions.addView(saveBtn, saveParams);
    sheet.addView(actions, new LayoutParams(-1, -2));

    final Runnable refreshPreview =
        () -> {
          if (exporting[0]) return;
          boolean isLight = themeState[0] == PosterTheme.LIGHT;
          lightOption.setBackground(
              isLight ? AppStyle.selectedShape(activity, 6f) : null);
          lightOption.setTextColor(isLight ? AppStyle.INK : AppStyle.MUTED);
          AppStyle.setTypeface(lightOption, isLight ? AppStyle.FONT_SEMIBOLD : AppStyle.FONT_MEDIUM);
          darkOption.setBackground(
              !isLight ? AppStyle.selectedShape(activity, 6f) : null);
          darkOption.setTextColor(!isLight ? AppStyle.INK : AppStyle.MUTED);
          AppStyle.setTypeface(darkOption, !isLight ? AppStyle.FONT_SEMIBOLD : AppStyle.FONT_MEDIUM);

          String dimLabel = exportLandscape ? "2400 × 1600" : "1600 × 2400";
          metaLabel.setText(footerState[0] ? (dimLabel + " · JPEG") : (dimLabel + " · 仅灯箱"));

          final int gen = exportPreviewGen.incrementAndGet();
          final PosterTheme selectedTheme = themeState[0];
          final boolean selectedFooter = footerState[0];
          final List<RenderPiece> snapshot = snapshotPieces();
          bakeExecutor.execute(
              () -> {
                if (released || exportPreviewGen.get() != gen) return;
                Bitmap preview =
                    canvas.renderPosterBitmap(
                        PREVIEW_POSTER_EDGE,
                        exportLandscape,
                        selectedTheme,
                        selectedFooter,
                        snapshot,
                        gen);
                if (preview == null) return;
                main.post(
                    () -> {
                      if (released || exportSheet != dialog || exportPreviewGen.get() != gen) {
                        preview.recycle();
                        return;
                      }
                      Bitmap previous = previewHolder[0];
                      previewHolder[0] = preview;
                      previewImage.setImageBitmap(preview);
                      if (previous != null && previous != preview && !previous.isRecycled()) {
                        previous.recycle();
                      }
                    });
              });
        };

    lightOption.setOnClickListener(
        v -> {
          if (themeState[0] == PosterTheme.LIGHT || exporting[0]) return;
          themeState[0] = PosterTheme.LIGHT;
          prefs.edit().putString(PREF_EXPORT_THEME, "light").apply();
          refreshPreview.run();
        });
    darkOption.setOnClickListener(
        v -> {
          if (themeState[0] == PosterTheme.DARK || exporting[0]) return;
          themeState[0] = PosterTheme.DARK;
          prefs.edit().putString(PREF_EXPORT_THEME, "dark").apply();
          refreshPreview.run();
        });
    footerRow.setOnClickListener(
        v -> {
          if (!exporting[0]) footerSwitch.setChecked(!footerSwitch.isChecked());
        });
    footerSwitch.setOnCheckedChangeListener(
        (btn, checked) -> {
          if (exporting[0]) return;
          footerState[0] = checked;
          prefs.edit().putBoolean(PREF_EXPORT_FOOTER, checked).apply();
          refreshPreview.run();
        });
    saveBtn.setOnClickListener(
        v -> {
          if (exporting[0]) return;
          exporting[0] = true;
          dialog.setCancelable(false);
          dialog.setCanceledOnTouchOutside(false);
          saveBtn.setEnabled(false);
          saveBtn.setText("正在导出…");
          cancel.setEnabled(false);
          lightOption.setEnabled(false);
          darkOption.setEnabled(false);
          footerSwitch.setEnabled(false);
          saveExportedPoster(
              exportLandscape,
              themeState[0],
              footerState[0],
              snapshotPieces(),
              () -> {
                if (dialog.isShowing()) dialog.dismiss();
              });
        });

    dialog.setContentView(sheet);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.setWindowAnimations(0);
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      window.setDimAmount(AppStyle.DIM_AMOUNT);
      window.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
      WindowManager.LayoutParams lp = window.getAttributes();
      lp.y = dp(FoldableUi.FLOATING_SHEET_MARGIN_DP);
      window.setAttributes(lp);
    }
    final Runnable applyExportLayout =
        () -> {
          if (!dialog.isShowing() || window == null) return;
          int liveW = FoldableUi.visibleWindowWidthPx(activity);
          if (liveW <= 0) liveW = activity.getResources().getDisplayMetrics().widthPixels;
          int liveH = activity.getResources().getDisplayMetrics().heightPixels;
          int widthDp = FoldableUi.windowWidthDp(activity);
          int maxDp = FoldableLayout.lightTableExportSheetMaxDp(widthDp);
          int sheetW = FoldableUi.floatingSheetWidthPx(activity, liveW, maxDp);
          int maxHeight = Math.round(liveH * .85f);
          window.setLayout(sheetW, ViewGroup.LayoutParams.WRAP_CONTENT);
          sheet.post(
              () -> {
                if (dialog.isShowing() && sheet.getHeight() > maxHeight) {
                  window.setLayout(sheetW, maxHeight);
                }
              });
        };
    final View hostDecor = activity.getWindow().getDecorView();
    final View.OnLayoutChangeListener exportResizeListener =
        (v, l, t, r, b, ol, ot, or, ob) -> {
          if (dialog.isShowing() && (r - l != or - ol || b - t != ob - ot)) {
            applyExportLayout.run();
          }
        };
    exportSheet = dialog;
    refreshExportPreview = refreshPreview;
    dialog.setOnDismissListener(
        d -> {
          hostDecor.removeOnLayoutChangeListener(exportResizeListener);
          exportPreviewGen.incrementAndGet();
          previewImage.setImageDrawable(null);
          if (previewHolder[0] != null && !previewHolder[0].isRecycled()) {
            previewHolder[0].recycle();
            previewHolder[0] = null;
          }
          if (exportSheet == dialog) {
            exportSheet = null;
            refreshExportPreview = null;
          }
        });
    dialog.show();
    FoldableUi.stabilizeSheetWindow(window, sheet);
    hostDecor.addOnLayoutChangeListener(exportResizeListener);
    applyExportLayout.run();
    Haptics.install(dialog.getWindow());
    refreshPreview.run();
  }

  private void saveExportedPoster(
      boolean exportLandscape,
      PosterTheme theme,
      boolean showFooter,
      List<RenderPiece> pieces,
      Runnable onFinished) {
    final long stamp = System.currentTimeMillis();
    final File file = new File(activity.getCacheDir(), "Spektra_lighttable_" + stamp + ".jpg");
    io.execute(
        () -> {
          ArrayList<RenderPiece> exportPieces = new ArrayList<>(pieces.size());
          ArrayList<Bitmap> tempBitmaps = new ArrayList<>();
          Bitmap bitmap = null;
          try {
            try {
              for (RenderPiece piece : pieces) {
                String latestLook = fileKey(piece.source.preferredLook());
                DecodedPiece decoded = piece.decoded;
                if (decoded == null || !Objects.equals(decoded.lookKey, latestLook)) {
                  DecodedPiece fresh = decode(piece.source);
                  if (fresh != null) {
                    tempBitmaps.add(fresh.bitmap);
                    decoded = fresh;
                  }
                }
                if (piece.source.style.film() && (decoded == null || decoded.lookKey == null)) {
                  main.post(() -> {
                    if (onFinished != null) onFinished.run();
                    if (!released) AppNotice.show(activity, "片边尚未载入，请稍后再导出");
                  });
                  return;
                }
                exportPieces.add(new RenderPiece(
                    piece.source, decoded, piece.placeholder,
                    piece.x, piece.y, piece.scale, piece.rotation));
              }
              bitmap =
                  canvas.renderPosterBitmap(
                      EXPORT_POSTER_EDGE, exportLandscape, theme, showFooter, exportPieces, -1);
            } finally {
              for (Bitmap temp : tempBitmaps) Bitmaps.release(temp);
            }
          } catch (Throwable error) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            main.post(
                () -> {
                  if (onFinished != null) onFinished.run();
                  if (!released) AppNotice.show(activity, "观片台导出失败，内存不足或发生错误");
                });
            return;
          }
          if (bitmap == null) {
            main.post(
                () -> {
                  if (onFinished != null) onFinished.run();
                  if (!released) AppNotice.show(activity, "观片台生成失败");
                });
            return;
          }
          final int width = bitmap.getWidth(), height = bitmap.getHeight();
          boolean cacheSaved = false;
          boolean gallerySaved = false;
          File[] staleExports =
              activity
                  .getCacheDir()
                  .listFiles(
                      (dir, name) ->
                          name.startsWith("Spektra_lighttable_")
                              && name.endsWith(".jpg")
                              && !name.equals(file.getName()));
          if (staleExports != null) {
            for (File oldExport : staleExports) oldExport.delete();
          }
          try (FileOutputStream out = new FileOutputStream(file)) {
            cacheSaved = bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
            out.getFD().sync();
          } catch (Exception ignored) {
          }
          Uri target = null;
          try {
            ContentValues values = new ContentValues();
            values.put(
                MediaStore.Images.Media.DISPLAY_NAME,
                "Spektra_Lighttable_" + stamp + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/" + activity.getString(R.string.app_name));
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            target =
                activity
                    .getContentResolver()
                    .insert(
                        MediaStore.Images.Media.getContentUri(
                            MediaStore.VOLUME_EXTERNAL_PRIMARY),
                        values);
            if (target != null) {
              try (java.io.OutputStream out =
                  activity.getContentResolver().openOutputStream(target)) {
                if (out != null) {
                  if (cacheSaved && file.isFile() && file.length() > 0) {
                    try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
                      byte[] buffer = new byte[32768];
                      int read;
                      while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                      out.flush();
                      gallerySaved = true;
                    }
                  } else {
                    gallerySaved = bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out);
                  }
                }
              }
              if (gallerySaved) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                activity.getContentResolver().update(target, done, null, null);
              } else {
                activity.getContentResolver().delete(target, null, null);
              }
            }
          } catch (Exception ignored) {
            gallerySaved = false;
            if (target != null) {
              try {
                activity.getContentResolver().delete(target, null, null);
              } catch (Exception ignoredCleanup) {
              }
            }
          }
          bitmap.recycle();
          final boolean cacheOk = cacheSaved && file.isFile() && file.length() > 0;
          final boolean galleryOk = gallerySaved;
          main.post(
              () -> {
                if (onFinished != null) onFinished.run();
                if (released) return;
                if (!cacheOk && !galleryOk) {
                  AppNotice.show(activity, "观片台导出失败");
                  return;
                }
                String detail =
                    (galleryOk
                            ? "已保存到 Pictures / " + activity.getString(R.string.app_name)
                            : "观片台图片已生成")
                        + "\n"
                        + width
                        + " × "
                        + height
                        + " · JPEG";
                new Haptics.DialogBuilder(activity)
                    .setTitle("已导出观片台")
                    .setMessage(detail)
                    .setNegativeButton("完成", null)
                    .setPositiveButton(
                        "分享",
                        (d, n) -> {
                          if (cacheOk) shareTable(file);
                          else AppNotice.show(activity, "图片已保存到相册");
                        })
                    .show();
              });
        });
  }

  private void shareTable(File file) {
    Uri uri =
        Uri.parse("content://" + activity.getPackageName() + ".files/" + file.getName());
    Intent share = new Intent(Intent.ACTION_SEND);
    share.setType("image/jpeg");
    share.putExtra(Intent.EXTRA_STREAM, uri);
    share.setClipData(android.content.ClipData.newRawUri("观片台", uri));
    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    try {
      activity.startActivity(Intent.createChooser(share, "分享观片台"));
    } catch (Exception error) {
      AppNotice.show(activity, "没有可用的分享应用");
    }
  }

  void navigateBack() {
    if (exportSheet != null && exportSheet.isShowing()) {
      exportSheet.dismiss();
      return;
    }
    if (addPopupView != null) {
      closeAddPopup();
      return;
    }
    if (addSheet != null && addSheet.isShowing()) {
      addSheet.dismiss();
      return;
    }
    if (magnifier || selected != null) {
      finishContextMode();
      return;
    }
    close.run();
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    updateShakeListening(true);
  }

  @Override
  protected void onDetachedFromWindow() {
    updateShakeListening(false);
    super.onDetachedFromWindow();
  }

  void onHostResume() {
    updateShakeListening(true);
  }

  void onHostPause() {
    updateShakeListening(false);
  }

  private void updateShakeListening(boolean active) {
    boolean enabled =
        prefs.getBoolean(SettingsPage.PREF_SHAKE_ARRANGE, SettingsPage.DEFAULT_SHAKE_ARRANGE);
    boolean want = active && !released && isAttachedToWindow() && enabled;
    if (want == shakeListening) return;
    if (want) {
      if (sensorManager == null) {
        sensorManager = (SensorManager) activity.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null)
          accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
      }
      if (sensorManager == null || accelerometer == null) return;
      shakePeakCount = 0;
      lastShakePeakMs = 0;
      shakeListening =
          sensorManager.registerListener(
              shakeListener, accelerometer, SensorManager.SENSOR_DELAY_UI);
    } else {
      if (sensorManager != null) sensorManager.unregisterListener(shakeListener);
      shakeListening = false;
      shakePeakCount = 0;
      lastShakePeakMs = 0;
    }
  }

  private void onShakeDetected() {
    if (Looper.myLooper() != Looper.getMainLooper()) {
      main.post(this::onShakeDetected);
      return;
    }
    if (released || !isShown()) return;
    if (!prefs.getBoolean(SettingsPage.PREF_SHAKE_ARRANGE, SettingsPage.DEFAULT_SHAKE_ARRANGE))
      return;
    if (addPopupView != null
        || (addSheet != null && addSheet.isShowing())
        || (exportSheet != null && exportSheet.isShowing()))
      return;
    if (table.isEmpty()) return;
    vibrateForShake();
    arrange();
  }

  @SuppressWarnings("deprecation")
  private Vibrator systemVibrator() {
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        VibratorManager manager =
            (VibratorManager) activity.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
        return manager == null ? null : manager.getDefaultVibrator();
      }
      return (Vibrator) activity.getSystemService(Context.VIBRATOR_SERVICE);
    } catch (Exception ignored) {
      return null;
    }
  }

  private void vibrateForShake() {
    if (!Haptics.enabled) return;
    boolean vibrated = false;
    try {
      Vibrator vibrator = systemVibrator();
      if (vibrator != null && vibrator.hasVibrator()) {
        long[] timings = {0L, 140L, 45L, 95L};
        VibrationEffect effect =
            vibrator.hasAmplitudeControl()
                ? VibrationEffect.createWaveform(timings, new int[] {0, 220, 0, 180}, -1)
                : VibrationEffect.createWaveform(timings, -1);
        vibrator.vibrate(effect);
        vibrated = true;
      }
    } catch (Exception ignored) {
    }
    if (!vibrated) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
  }

  private void vibrateBoundaryHit() {
    if (!Haptics.enabled) return;
    boolean vibrated = false;
    try {
      Vibrator vibrator = systemVibrator();
      if (vibrator != null && vibrator.hasVibrator()) {
        int amp = vibrator.hasAmplitudeControl() ? 200 : VibrationEffect.DEFAULT_AMPLITUDE;
        vibrator.vibrate(VibrationEffect.createOneShot(38L, amp));
        vibrated = true;
      }
    } catch (Exception ignored) {
    }
    if (!vibrated) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
  }

  private void vibrateSwitch() {
    if (!Haptics.enabled) return;
    boolean vibrated = false;
    try {
      Vibrator vibrator = systemVibrator();
      if (vibrator != null && vibrator.hasVibrator()) {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK));
        vibrated = true;
      }
    } catch (Exception ignored) {
    }
    if (!vibrated) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
  }

  private void vibrateDetent() {
    if (!Haptics.enabled) return;
    boolean vibrated = false;
    try {
      Vibrator vibrator = systemVibrator();
      if (vibrator != null && vibrator.hasVibrator()) {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK));
        vibrated = true;
      }
    } catch (Exception ignored) {
    }
    if (!vibrated) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
  }

  void release() {
    released = true;
    main.removeCallbacksAndMessages(null);
    cancelLampAnimator();
    if (lampSound != null) {
      lampSound.release();
      lampSound = null;
    }
    updateShakeListening(false);
    exportPreviewGen.incrementAndGet();
    closeAddPopup();
    io.shutdownNow();
    thumbExecutor.shutdownNow();
    bakeExecutor.shutdownNow();
    if (exportSheet != null) {
      exportSheet.dismiss();
      exportSheet = null;
    }
    if (addSheet != null) {
      addSheet.dismiss();
      addSheet = null;
    }
    thumbCache.evictAll();
    if (canvas != null) canvas.release();
    for (TablePhoto photo : table) {
      photo.decoded = null;
      photo.thumbPlaceholder = null;
    }
  }

  private FrameLayout aspectFrame(final float heightOverWidth) {
    return new FrameLayout(activity) {
      @Override
      protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = Math.max(1, Math.round(width * heightOverWidth));
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(Math.max(0, width), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
      }
    };
  }

  private float clamp(float value, float lo, float hi) {
    return Math.max(lo, Math.min(hi, value));
  }

  private String mb(long bytes) {
    return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    AppStyle.setTypeface(view, AppStyle.FONT_REGULAR);
    view.setIncludeFontPadding(false);
    return view;
  }

  private TextView button(String value, int color, int fill) {
    TextView view = text(value, AppStyle.TYPE_BODY, color);
    view.setGravity(Gravity.CENTER);
    view.setSingleLine(true);
    view.setEllipsize(TextUtils.TruncateAt.END);
    AppStyle.pillAction(view, color, fill);
    view.setMinHeight(dp(44));
    return view;
  }

  private int dp(float value) {
    return AppStyle.dp(activity, value);
  }

  private static LinearGradient pieceSheen(boolean acetate) {
    return new LinearGradient(
        -.52f, -.48f, .48f, .52f,
        acetate
            ? new int[] {0x00fffcf5, 0x11fffcf5, 0x33fffdf8, 0x0cfffcf5, 0x00fffcf5}
            : new int[] {0x00fffcf5, 0x08fffcf5, 0x18fffdf8, 0x06fffcf5, 0x00fffcf5},
        new float[] {0f, .28f, .41f, .49f, .66f},
        Shader.TileMode.CLAMP);
  }

  /**
   * Portrait 2:3 Darkroom Optical Bench view. Pre-bakes the machined brushed-aluminium frame,
   * backlit diffuser with edge falloff/grain, top/left etched L-ruler, and 3D bevelled optical
   * glass loupe overlays off the UI thread so dragging and 3x inspection stay at 60–120fps.
   */
  final class LightTableView extends View {
    // Physical lightbox geometry in millimetres (portrait 2:3 = 140×210mm; foldable landscape 3:2 = 210×140mm).
    private static final float HALF_SHORT_MM = 70.0f;
    private static final float HALF_LONG_MM = 105.0f;
    private static final float FRAME_W_MM = 4.4f;
    private static final float INNER_R_MM = 4.2f;
    private static final float OUTER_R_MM = INNER_R_MM + FRAME_W_MM;
    private static final float SPILL_MM = 14.0f;
    private static final float LOUPE_RIM_ZOOM = 1.80f;
    private static final float LOUPE_CORE_ZOOM = 2.85f;

    private float halfWMm(boolean landscape) {
      return landscape ? HALF_LONG_MM : HALF_SHORT_MM;
    }

    private float halfHMm(boolean landscape) {
      return landscape ? HALF_SHORT_MM : HALF_LONG_MM;
    }

    private float outWMm(boolean landscape) {
      return halfWMm(landscape) + FRAME_W_MM;
    }

    private float outHMm(boolean landscape) {
      return halfHMm(landscape) + FRAME_W_MM;
    }

    // Normalized Gaussian spread radii for hardware/software-identical layered soft drop shadows.
    private static final float[] SOFT_SHADOW_SPREAD = {
      1.00f, .89f, .78f, .67f, .56f, .45f, .34f, .23f, .12f, .01f, -.10f, -.20f
    };
    private static final float[] CONTACT_SHADOW_SPREAD = {1.00f, .80f, .60f, .40f, .20f, -.05f};

    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint pieceBitmapPaint =
        new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final PiecePainter piecePainter = new PiecePainter(pieceBitmapPaint);
    private final Paint diffuserDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ColorMatrix lightColorMatrix = new ColorMatrix();
    private final ColorMatrix lightSatMatrix = new ColorMatrix();
    private float lightLevel = 1f;
    private float cachedFilterLevel = 1f;
    private final Paint hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionInnerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fallbackDiffuserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lensFillBgPaint = new Paint();

    private final Path loupeOuterPath = new Path();
    private final Path loupeInnerPath = new Path();
    private final RectF boxOuterRect = new RectF();
    private final RectF diffuserRect = new RectF();
    private final RectF backdropDestRect = new RectF();
    private final RectF tmpRect = new RectF();
    private final float[] tmpSize = new float[2];
    private final float[] tmpBounds = new float[4];
    private final float[] tmpDims = new float[2];

    private final ScaleGestureDetector scaleDetector;
    private final AtomicInteger bakeGeneration = new AtomicInteger();
    private final Typeface rulerFont;

    private Bitmap backdropBitmap;
    private Bitmap loupeShadowBitmap;
    private Bitmap loupeTintBitmap;
    private Bitmap loupeSpecBitmap;
    private float loupeLensR, loupeRimW, loupeTotalR, loupeBmpHalf;
    private float boxCx, boxCy, ppm;
    private boolean showLens, dragging, draggingLens;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private float lensX, lensY, lastX, lastY;
    private float lensDragOffsetX, lensDragOffsetY;
    private float dragRawX, dragRawY;
    private boolean dragOutOfBounds;
    private long lastBoundaryHapticMs;
    private ValueAnimator snapBackAnimator;
    private TablePhoto snapBackTarget;
    private float snapTargetX, snapTargetY;

    LightTableView(LightTablePage page) {
      super(page.activity);
      setBackgroundColor(palette.pageBg);
      rulerFont = AppStyle.typeface(page.activity, AppStyle.FONT_MEDIUM);

      hintPaint.setColor(palette.hintText);
      hintPaint.setTextSize(dp(13));
      hintPaint.setTextAlign(Paint.Align.CENTER);
      hintPaint.setTypeface(AppStyle.typeface(page.activity, AppStyle.FONT_REGULAR));

      selectionStrokePaint.setColor(palette.selectionStroke);
      selectionStrokePaint.setStyle(Paint.Style.STROKE);
      selectionStrokePaint.setStrokeWidth(dp(1.6f));

      selectionGlowPaint.setColor(palette.selectionGlow);
      selectionGlowPaint.setStyle(Paint.Style.STROKE);

      selectionInnerPaint.setColor(palette.selectionInnerHairline);
      selectionInnerPaint.setStyle(Paint.Style.STROKE);
      selectionInnerPaint.setStrokeWidth(Math.max(1f, dp(.5f)));

      fallbackFramePaint.setColor(palette.fallbackFrameOuter);
      fallbackStrokePaint.setColor(palette.fallbackFrameInner);
      fallbackStrokePaint.setStyle(Paint.Style.STROKE);
      fallbackStrokePaint.setStrokeWidth(Math.max(1f, dp(1f)));
      fallbackDiffuserPaint.setColor(palette.fallbackDiffuser);

      lensFillBgPaint.setColor(palette.pageBg);

      scaleDetector =
          new ScaleGestureDetector(
              page.activity,
              new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScaleBegin(ScaleGestureDetector detector) {
                  dragging = false;
                  activePointerId = MotionEvent.INVALID_POINTER_ID;
                  return true;
                }

                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                  if (!magnifier) scaleSelected(detector.getScaleFactor());
                  return true;
                }

                @Override
                public void onScaleEnd(ScaleGestureDetector detector) {
                  dragging = false;
                  activePointerId = MotionEvent.INVALID_POINTER_ID;
                  if (!magnifier) saveState();
                }
              });
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
      super.onSizeChanged(w, h, oldw, oldh);
      if (w <= 0 || h <= 0) return;
      computeGeometry(w, h);
      clampAllInPlace();
      schedulePrebake();
      if (magnifier && showLens) showLensAtSelected();
    }

    void recomputeAndRebake() {
      int w = getWidth(), h = getHeight();
      if (w <= 0 || h <= 0) {
        invalidate();
        return;
      }
      finishSnapBackImmediately();
      computeGeometry(w, h);
      clampAllInPlace();
      schedulePrebake();
      if (magnifier && showLens) showLensAtSelected();
      invalidate();
    }

    float lightLevel() {
      return lightLevel;
    }

    void setLightLevel(float level) {
      float clamped = clamp(level, 0f, 1f);
      if (Math.abs(clamped - lightLevel) < .001f) return;
      lightLevel = clamped;
      updateLightFilter();
      invalidate();
    }

    private void updateLightFilter() {
      if (lightLevel >= .999f) {
        if (cachedFilterLevel < .999f) {
          pieceBitmapPaint.setColorFilter(null);
          cachedFilterLevel = 1f;
        }
        return;
      }
      if (Math.abs(lightLevel - cachedFilterLevel) < .002f) return;
      cachedFilterLevel = lightLevel;
      float gain, sat, warmAmt;
      if (lightLevel >= .15f) {
        float t = (lightLevel - .15f) / .85f;
        gain = .66f + .34f * t;
        sat = .90f + .10f * t;
        warmAmt = (1f - t) * .065f;
      } else {
        float onFrac = lightLevel / .15f;
        gain = .42f + .24f * onFrac;
        sat = .74f + .16f * onFrac;
        warmAmt = onFrac * .065f;
      }
      lightColorMatrix.reset();
      lightSatMatrix.setSaturation(sat);
      lightColorMatrix.postConcat(lightSatMatrix);
      float rScale = gain;
      float gScale = gain * (1f - warmAmt * .38f);
      float bScale = gain * (1f - warmAmt);
      lightSatMatrix.setScale(rScale, gScale, bScale, 1f);
      lightColorMatrix.postConcat(lightSatMatrix);
      pieceBitmapPaint.setColorFilter(new ColorMatrixColorFilter(lightColorMatrix));
    }

    private void computeGeometry(int w, int h) {
      boolean land = landscapeMode;
      float halfW = halfWMm(land);
      float halfH = halfHMm(land);
      float outW = outWMm(land);
      float outH = outHMm(land);
      float topPad = dp(8);
      float bottomReserve = dp(134);
      float availW = Math.max(1f, w - dp(28));
      float availH = Math.max(1f, h - topPad - bottomReserve);
      float outerWmm = outW * 2f;
      float outerHmm = outH * 2f;
      ppm = Math.min(availW / outerWmm, availH / outerHmm);

      float outerW = outerWmm * ppm;
      float outerH = outerHmm * ppm;
      boxCx = w * .5f;
      boxCy = topPad + availH * .5f;

      boxOuterRect.set(
          boxCx - outerW * .5f,
          boxCy - outerH * .5f,
          boxCx + outerW * .5f,
          boxCy + outerH * .5f);
      float litW = halfW * 2f * ppm;
      float litH = halfH * 2f * ppm;
      diffuserRect.set(
          boxCx - litW * .5f,
          boxCy - litH * .5f,
          boxCx + litW * .5f,
          boxCy + litH * .5f);

      float spillPx = SPILL_MM * ppm;
      backdropDestRect.set(
          boxOuterRect.left - spillPx,
          boxOuterRect.top - spillPx,
          boxOuterRect.right + spillPx,
          boxOuterRect.bottom + spillPx);

      float shortOuter = Math.min(outerW, outerH);
      loupeLensR = clamp(shortOuter * .175f, dp(52), dp(66));
      loupeRimW = Math.max(dp(6), loupeLensR * .13f);
      loupeTotalR = loupeLensR + loupeRimW;
      loupeBmpHalf = (float) Math.ceil(loupeTotalR + dp(22));
      positionDimmerPill(boxOuterRect.bottom, h);
    }

    private void schedulePrebake() {
      if (ppm <= 0f || released) return;
      final int gen = bakeGeneration.incrementAndGet();
      final boolean bakeLandscape = landscapeMode;
      final int bmpW = Math.max(1, Math.round(backdropDestRect.width()));
      final int bmpH = Math.max(1, Math.round(backdropDestRect.height()));
      final float localCx = bmpW * .5f;
      final float localCy = bmpH * .5f;
      final float bakePpm = ppm;
      final float lensR = loupeLensR;
      final float rimW = loupeRimW;
      final int loupeHalf = Math.max(1, Math.round(loupeBmpHalf));
      final float shadowDx = dp(1.8f);
      final float shadowDy = dp(7.5f);
      final float shadowInner = dp(8f);
      final float shadowOuter = dp(13f);
      final float seamPx = Math.max(1.3f, dp(.95f));

      bakeExecutor.execute(
          () -> {
            if (released || bakeGeneration.get() != gen) return;
            Bitmap bakedBackdrop =
                bakeLightboxBackdrop(
                    bmpW,
                    bmpH,
                    localCx,
                    localCy,
                    bakePpm,
                    SPILL_MM,
                    bakeLandscape,
                    true,
                    rulerFont,
                    gen);
            if (bakedBackdrop == null || released || bakeGeneration.get() != gen) {
              if (bakedBackdrop != null) bakedBackdrop.recycle();
              return;
            }
            Bitmap[] bakedLoupe =
                bakeOpticalLoupeLayers(
                    loupeHalf,
                    lensR,
                    rimW,
                    shadowDx,
                    shadowDy,
                    shadowInner,
                    shadowOuter,
                    seamPx,
                    gen);
            if (bakedLoupe == null || released || bakeGeneration.get() != gen) {
              bakedBackdrop.recycle();
              if (bakedLoupe != null) {
                for (Bitmap b : bakedLoupe) if (b != null) b.recycle();
              }
              return;
            }
            main.post(
                () -> {
                  if (released || bakeGeneration.get() != gen) {
                    bakedBackdrop.recycle();
                    for (Bitmap b : bakedLoupe) if (b != null) b.recycle();
                    return;
                  }
                  recyclePrebaked();
                  backdropBitmap = bakedBackdrop;
                  loupeShadowBitmap = bakedLoupe[0];
                  loupeTintBitmap = bakedLoupe[1];
                  loupeSpecBitmap = bakedLoupe[2];
                  invalidate();
                });
          });
    }

    @Override
    protected void onDraw(Canvas c) {
      super.onDraw(c);
      if (ppm <= 0f) return;
      drawScene(
          c, true, false, boxOuterRect, diffuserRect, backdropDestRect, backdropBitmap, 1f,
          0f, 0f, -1f);
      drawLens(c);
    }

    private void drawScene(
        Canvas c,
        boolean withSelection,
        boolean forLoupe,
        RectF outerRect,
        RectF litRect,
        RectF backdropRect,
        Bitmap bakedBackdrop,
        float scaleFactor,
        float clipCx,
        float clipCy,
        float clipR) {
      float outerR = OUTER_R_MM * ppm * scaleFactor;
      float innerR = INNER_R_MM * ppm * scaleFactor;
      if (bakedBackdrop != null && !bakedBackdrop.isRecycled()) {
        c.drawBitmap(bakedBackdrop, null, backdropRect, bitmapPaint);
      } else {
        c.drawRoundRect(outerRect, outerR, outerR, fallbackFramePaint);
        c.drawRoundRect(outerRect, outerR, outerR, fallbackStrokePaint);
        c.drawRoundRect(litRect, innerR, innerR, fallbackDiffuserPaint);
      }

      // Real-time optical dimming & power-off attenuation without re-baking CPU backdrop.
      if (lightLevel < .999f) {
        int dimRgb;
        float dimAlpha;
        if (lightLevel >= .15f) {
          float t = (lightLevel - .15f) / .85f;
          dimRgb = palette.lightMode ? 0x9c968c : 0x1d1a15;
          dimAlpha = (1f - (float) Math.pow(t, .85f)) * (palette.lightMode ? .42f : .60f);
        } else {
          float onFrac = lightLevel / .15f;
          int offRgb = palette.lightMode ? 0xc4c4ca : 0x232327;
          int lowRgb = palette.lightMode ? 0x9c968c : 0x1d1a15;
          int rr = Math.round(mix((offRgb >> 16) & 0xff, (lowRgb >> 16) & 0xff, onFrac));
          int gg = Math.round(mix((offRgb >> 8) & 0xff, (lowRgb >> 8) & 0xff, onFrac));
          int bb = Math.round(mix(offRgb & 0xff, lowRgb & 0xff, onFrac));
          dimRgb = (rr << 16) | (gg << 8) | bb;
          dimAlpha = mix(palette.lightMode ? .82f : .87f, palette.lightMode ? .42f : .60f, onFrac);
        }
        diffuserDimPaint.setColor((Math.round(dimAlpha * 255f) << 24) | dimRgb);
        c.drawRoundRect(litRect, innerR, innerR, diffuserDimPaint);
      }

      if (table.isEmpty()) {
        if (!forLoupe) {
          hintPaint.setAlpha(lightLevel < .15f ? 110 : 255);
          c.drawText(
              "添加照片开始布置",
              litRect.centerX(),
              litRect.centerY() + dp(4) * scaleFactor,
              hintPaint);
        }
        return;
      }

      float pad = dp(8) * scaleFactor;
      float cw = Math.max(1f, litRect.width() - pad * 2f);
      float ch = Math.max(1f, litRect.height() - pad * 2f);
      for (TablePhoto photo : table) {
        sizeOf(photo, cw, ch, tmpSize);
        float cx = litRect.left + pad + photo.x * cw;
        float cy = litRect.top + pad + photo.y * ch;
        boolean lifted = withSelection && dragging && photo == selected;
        float liftScale = lifted ? 1.024f : 1f;
        float dw = tmpSize[0] * photo.scale * liftScale;
        float dh = tmpSize[1] * photo.scale * liftScale;
        if (clipR > 0f) {
          float pieceR = .5f * (float) Math.hypot(dw, dh) + dp(16) * scaleFactor;
          float dx = cx - clipCx;
          float dy = cy - clipCy;
          float limit = pieceR + clipR;
          if (dx * dx + dy * dy > limit * limit) continue;
        }
        drawPieceOnLightbox(
            c,
            photo,
            cx,
            cy,
            dw,
            dh,
            lifted,
            withSelection && photo == selected,
            scaleFactor);
      }
    }

    private void drawPieceOnLightbox(
        Canvas c,
        TablePhoto photo,
        float cx,
        float cy,
        float dw,
        float dh,
        boolean lifted,
        boolean isSelected,
        float scaleFactor) {
      float halfW = dw * .5f;
      float halfH = dh * .5f;
      piecePainter.draw(
          c, photo.displayBitmap(), photo.decoded != null && photo.decoded.acetate,
          cx, cy, dw, dh, photo.rotation, scaleFactor, lifted, getWidth() > dp(560));
      if (isSelected) {
        c.save();
        c.translate(cx, cy);
        c.rotate(photo.rotation);
        float outerPad = dp(1.3f) * scaleFactor;
        float outerCorner = dp(2.0f) * scaleFactor;
        tmpRect.set(-halfW - outerPad, -halfH - outerPad, halfW + outerPad, halfH + outerPad);
        int baseGlowAlpha = Color.alpha(palette.selectionGlow);
        for (int g = 0; g < 3; g++) {
          float spread = dp(2.2f + g * 2.2f) * scaleFactor;
          selectionGlowPaint.setStrokeWidth(spread);
          selectionGlowPaint.setAlpha(Math.round(baseGlowAlpha * (.26f / (g + 1))));
          c.drawRoundRect(tmpRect, outerCorner + spread * .25f, outerCorner + spread * .25f, selectionGlowPaint);
        }
        selectionStrokePaint.setStrokeWidth(dp(1.6f) * scaleFactor);
        c.drawRoundRect(tmpRect, outerCorner, outerCorner, selectionStrokePaint);

        float innerPad = dp(.4f) * scaleFactor;
        float innerCorner = dp(1.25f) * scaleFactor;
        tmpRect.set(-halfW - innerPad, -halfH - innerPad, halfW + innerPad, halfH + innerPad);
        selectionInnerPaint.setStrokeWidth(Math.max(1f, dp(.5f) * scaleFactor));
        c.drawRoundRect(tmpRect, innerCorner, innerCorner, selectionInnerPaint);
        c.restore();
      }
    }

    /** Shares composition between the stage, loupe and poster. Each renderer owns its scratch. */
    private final class PiecePainter {
      final Paint bitmapPaint;
      final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
      final Paint placeholder = new Paint(Paint.ANTI_ALIAS_FLAG);
      final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
      final LinearGradient acetateSheen = pieceSheen(true), printSheen = pieceSheen(false);
      final Matrix matrix = new Matrix();
      final RectF rect = new RectF();

      PiecePainter(Paint bitmapPaint) {
        this.bitmapPaint = bitmapPaint;
        placeholder.setColor(palette.placeholderFill);
      }

      void draw(Canvas c, Bitmap bitmap, boolean acetate, float cx, float cy, float width,
                float height, float rotation, float scale, boolean lifted, boolean wideTable) {
        Bitmap safeBitmap = bitmap != null && !bitmap.isRecycled() ? bitmap : null;
        float hw = width * .5f, hh = height * .5f;
        boolean transmits = safeBitmap != null && safeBitmap.hasAlpha();
        // The larger foldable table needs a tighter, lighter shadow than the Compact table.
        float shadowScale = wideTable ? .78f : 1f;
        float shadowOpacity = wideTable ? .45f : 1f;
        shadow(c, cx, cy, rotation, hw, hh, transmits,
            dp(lifted ? 3.25f : 1.1f) * scale * shadowScale,
            dp(lifted ? 7f : 2.4f) * scale * shadowScale,
            dp(lifted ? 20f : 12f) * scale * shadowScale,
            lifted ? 0x20160c : 0x1c140c,
            (lifted ? .19f : .14f) * shadowOpacity, SOFT_SHADOW_SPREAD);
        shadow(c, cx, cy, rotation, hw, hh, transmits, dp(.2f) * scale, dp(.5f) * scale,
            dp(lifted ? 4.2f : 3.2f) * scale * shadowScale,
            lifted ? 0x1a120a : 0x18110a,
            (lifted ? .12f : .13f) * shadowOpacity, CONTACT_SHADOW_SPREAD);

        c.save();
        c.translate(cx, cy);
        c.rotate(rotation);
        rect.set(-hw, -hh, hw, hh);
        if (safeBitmap != null) c.drawBitmap(safeBitmap, null, rect, bitmapPaint);
        else c.drawRect(rect, placeholder);
        // Pieces with baked transmission already carry any film gloss on their opaque pixels.
        if (!transmits) {
          LinearGradient gradient = acetate ? acetateSheen : printSheen;
          matrix.setScale(width, height);
          gradient.setLocalMatrix(matrix);
          sheen.setShader(gradient);
          c.drawRect(rect, sheen);
        }
        c.restore();
      }

      private void shadow(Canvas c, float cx, float cy, float rotation, float hw, float hh,
                          boolean transmits, float dx, float dy, float blur, int rgb,
                          float opacity, float[] spreadSteps) {
        c.save();
        c.translate(cx, cy);
        if (transmits) {
          // The lamp and underlying pieces should show straight through the alpha bitmap.
          // Keep this approximate box shadow outside its footprint, including when rotated.
          c.rotate(rotation);
          c.clipOutRect(-hw, -hh, hw, hh);
          c.rotate(-rotation);
        }
        c.translate(dx, dy);
        c.rotate(rotation);
        drawLayeredShadow(c, hw, hh, blur, rgb, opacity, spreadSteps, shadow, rect);
        c.restore();
      }
    }

    private static void drawLayeredShadow(Canvas c, float hw, float hh, float blur, int rgb,
                                          float opacity, float[] spreadSteps, Paint paint, RectF rect) {
      int n = spreadSteps.length;
      if (n == 0 || opacity <= 0f) return;
      // Accumulate exact target transmittance with error diffusion across integer 8-bit alpha
      // layers so total opacity matches `opacity` even at low foldable shadow alphas.
      float remain = 1f;
      for (int i = 0; i < n; i++) {
        float step = spreadSteps[i];
        float expand = step * blur;
        if (hw + expand <= 0 || hh + expand <= 0) continue;
        float targetRemain = (float) Math.pow(1f - opacity, (i + 1) / (double) n);
        float needed = remain > 1e-4f ? Math.max(0f, 1f - targetRemain / remain) : 0f;
        int a = Math.min(255, Math.round(needed * 255f));
        if (a <= 0) continue;
        remain *= (1f - a / 255f);
        paint.setColor((a << 24) | rgb);
        float corner = Math.max(0f, expand * .85f);
        rect.set(-hw - expand, -hh - expand, hw + expand, hh + expand);
        c.drawRoundRect(rect, corner, corner, paint);
      }
    }

    private void sizeOf(TablePhoto photo, float cw, float ch, float[] out) {
      sizeOfBitmap(photo.displayBitmap(), cw, ch, out);
    }

    private void sizeOfBitmap(Bitmap bmp, float cw, float ch, float[] out) {
      boolean wide = cw > ch;
      float width = cw * (wide ? .36f : .52f);
      float ratio =
          bmp == null || bmp.isRecycled() || bmp.getWidth() == 0
              ? .75f
              : bmp.getHeight() / (float) bmp.getWidth();
      float height = width * ratio;
      float maxHeight = ch * (wide ? .52f : .40f);
      if (height > maxHeight) {
        height = maxHeight;
        width = height / ratio;
      }
      out[0] = width;
      out[1] = height;
    }

    private void drawLens(Canvas c) {
      if (!magnifier || !showLens || loupeTotalR <= 0f) return;
      float cx = lensX, cy = lensY;
      float left = cx - loupeBmpHalf;
      float top = cy - loupeBmpHalf;

      if (loupeShadowBitmap != null && !loupeShadowBitmap.isRecycled()) {
        c.drawBitmap(loupeShadowBitmap, left, top, bitmapPaint);
      }

      loupeOuterPath.reset();
      loupeOuterPath.addCircle(cx, cy, Math.max(1f, loupeTotalR - .5f), Path.Direction.CW);
      loupeInnerPath.reset();
      loupeInnerPath.addCircle(cx, cy, loupeLensR, Path.Direction.CW);

      // Refracted crystal glass bevel rim (1.45x refractive shift around the outer ring).
      c.save();
      c.clipPath(loupeOuterPath);
      c.drawRect(
          cx - loupeTotalR - 2f,
          cy - loupeTotalR - 2f,
          cx + loupeTotalR + 2f,
          cy + loupeTotalR + 2f,
          lensFillBgPaint);
      c.translate(cx, cy);
      c.scale(LOUPE_RIM_ZOOM, LOUPE_RIM_ZOOM);
      c.translate(-cx, -cy);
      drawScene(
          c, false, true, boxOuterRect, diffuserRect, backdropDestRect, backdropBitmap, 1f,
          cx, cy, loupeTotalR / LOUPE_RIM_ZOOM);
      c.restore();

      // Core 3x optical magnification sampled directly from full decoded piece bitmaps (no crosshair).
      c.save();
      c.clipPath(loupeInnerPath);
      c.drawRect(
          cx - loupeLensR - 2f,
          cy - loupeLensR - 2f,
          cx + loupeLensR + 2f,
          cy + loupeLensR + 2f,
          lensFillBgPaint);
      c.translate(cx, cy);
      c.scale(LOUPE_CORE_ZOOM, LOUPE_CORE_ZOOM);
      c.translate(-cx, -cy);
      drawScene(
          c, false, true, boxOuterRect, diffuserRect, backdropDestRect, backdropBitmap, 1f,
          cx, cy, loupeLensR / LOUPE_CORE_ZOOM);
      c.restore();

      if (loupeTintBitmap != null && !loupeTintBitmap.isRecycled()) {
        c.drawBitmap(loupeTintBitmap, left, top, bitmapPaint);
      }
      if (loupeSpecBitmap != null && !loupeSpecBitmap.isRecycled()) {
        c.drawBitmap(loupeSpecBitmap, left, top, bitmapPaint);
      }
    }

    void showLensAtSelected() {
      if (diffuserRect.width() <= 0f || diffuserRect.height() <= 0f) {
        showLens = true;
        return;
      }
      if (selected != null) {
        float pad = dp(8);
        float cw = Math.max(1f, diffuserRect.width() - pad * 2f);
        float ch = Math.max(1f, diffuserRect.height() - pad * 2f);
        lensX = clamp(diffuserRect.left + pad + selected.x * cw, diffuserRect.left, diffuserRect.right);
        lensY = clamp(diffuserRect.top + pad + selected.y * ch, diffuserRect.top, diffuserRect.bottom);
      } else {
        lensX = diffuserRect.centerX();
        lensY = diffuserRect.centerY();
      }
      showLens = true;
      invalidate();
    }

    void hideLens() {
      showLens = false;
      draggingLens = false;
      invalidate();
    }

    @Override
    public boolean performClick() {
      return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
      scaleDetector.onTouchEvent(event);
      if (scaleDetector.isInProgress()) return true;
      if (diffuserRect.width() <= 0f || diffuserRect.height() <= 0f) return true;

      if (magnifier) {
        int action = event.getActionMasked();
        float ex = event.getX(), ey = event.getY();
        if (action == MotionEvent.ACTION_DOWN) {
          float dist = (float) Math.hypot(ex - lensX, ey - lensY);
          if (showLens && dist <= loupeTotalR * 1.45f) {
            // Grab near the loupe to slide it 1:1 to any millimeter on the lightbox without jump.
            lensDragOffsetX = lensX - ex;
            lensDragOffsetY = lensY - ey;
          } else {
            // Tap elsewhere: place the glass cleanly above the fingertip so it stays unobstructed.
            float liftY = loupeTotalR * .85f + dp(18);
            float targetY = ey - liftY;
            if (targetY < diffuserRect.top + dp(10)) targetY = ey + liftY;
            lensX = clamp(ex, diffuserRect.left, diffuserRect.right);
            lensY = clamp(targetY, diffuserRect.top, diffuserRect.bottom);
            lensDragOffsetX = lensX - ex;
            lensDragOffsetY = lensY - ey;
          }
          showLens = true;
          draggingLens = true;
          invalidate();
        } else if (action == MotionEvent.ACTION_MOVE && draggingLens) {
          lensX = clamp(ex + lensDragOffsetX, diffuserRect.left, diffuserRect.right);
          lensY = clamp(ey + lensDragOffsetY, diffuserRect.top, diffuserRect.bottom);
          invalidate();
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
          draggingLens = false;
        }
        return true;
      }

      float pad = dp(8);
      float cw = Math.max(1f, diffuserRect.width() - pad * 2f);
      float ch = Math.max(1f, diffuserRect.height() - pad * 2f);
      int action = event.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN) {
        finishSnapBackImmediately();
        activePointerId = event.getPointerId(0);
        TablePhoto hit = hitTest(event.getX(), event.getY(), pad, cw, ch);
        select(hit);
        dragging = hit != null;
        lastX = event.getX();
        lastY = event.getY();
        if (hit != null) {
          dragRawX = hit.x;
          dragRawY = hit.y;
          dragOutOfBounds = false;
        }
        if (dragging) invalidate();
        return true;
      }
      if (action == MotionEvent.ACTION_POINTER_DOWN) {
        // Multi-touch begins: cancel single-finger drag so pinch zoom never jumps on finger lift.
        dragging = false;
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        return true;
      }
      if (action == MotionEvent.ACTION_POINTER_UP) {
        int liftedIndex = event.getActionIndex();
        if (event.getPointerId(liftedIndex) == activePointerId) {
          dragging = false;
          activePointerId = MotionEvent.INVALID_POINTER_ID;
        }
        return true;
      }
      if (action == MotionEvent.ACTION_MOVE && dragging && selected != null) {
        int ptrIdx = event.findPointerIndex(activePointerId);
        if (ptrIdx < 0) {
          dragging = false;
          activePointerId = MotionEvent.INVALID_POINTER_ID;
          return true;
        }
        float curX = event.getX(ptrIdx);
        float curY = event.getY(ptrIdx);
        dragRawX += (curX - lastX) / cw;
        dragRawY += (curY - lastY) / ch;
        lastX = curX;
        lastY = curY;
        computeSafeBounds(
            selected.displayBitmap(), selected.scale, selected.rotation, cw, ch, tmpBounds);
        float minX = tmpBounds[0], maxX = tmpBounds[1];
        float minY = tmpBounds[2], maxY = tmpBounds[3];
        boolean out =
            dragRawX < minX - 1e-4f
                || dragRawX > maxX + 1e-4f
                || dragRawY < minY - 1e-4f
                || dragRawY > maxY + 1e-4f;
        selected.x = rubberBand(dragRawX, minX, maxX, .10f);
        selected.y = rubberBand(dragRawY, minY, maxY, .07f);
        if (out && !dragOutOfBounds) {
          long now = SystemClock.uptimeMillis();
          if (now - lastBoundaryHapticMs >= 180L) {
            lastBoundaryHapticMs = now;
            vibrateBoundaryHit();
          }
        }
        dragOutOfBounds = out;
        invalidate();
        return true;
      }
      if (event.getActionMasked() == MotionEvent.ACTION_UP
          || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        if (dragging) {
          dragging = false;
          TablePhoto releasedPhoto = selected;
          if (releasedPhoto != null) {
            computeSafeBounds(
                releasedPhoto.displayBitmap(),
                releasedPhoto.scale,
                releasedPhoto.rotation,
                cw,
                ch,
                tmpBounds);
            float targetX = clamp(releasedPhoto.x, tmpBounds[0], tmpBounds[1]);
            float targetY = clamp(releasedPhoto.y, tmpBounds[2], tmpBounds[3]);
            if (Math.abs(releasedPhoto.x - targetX) > 1e-4f
                || Math.abs(releasedPhoto.y - targetY) > 1e-4f) {
              if (!dragOutOfBounds) {
                long now = SystemClock.uptimeMillis();
                if (now - lastBoundaryHapticMs >= 180L) {
                  lastBoundaryHapticMs = now;
                  vibrateBoundaryHit();
                }
              }
              startSnapBack(releasedPhoto, targetX, targetY);
            } else {
              saveState();
              invalidate();
            }
          } else {
            saveState();
            invalidate();
          }
          dragOutOfBounds = false;
        }
        return true;
      }
      return true;
    }

    private void referenceBoundsSize(float[] outCwCh) {
      if (diffuserRect.width() > 0f && diffuserRect.height() > 0f) {
        float pad = dp(8);
        outCwCh[0] = Math.max(1f, diffuserRect.width() - pad * 2f);
        outCwCh[1] = Math.max(1f, diffuserRect.height() - pad * 2f);
      } else {
        outCwCh[0] = halfWMm(landscapeMode) * 2f;
        outCwCh[1] = halfHMm(landscapeMode) * 2f;
      }
    }

    private float maxSafeScaleForBitmap(Bitmap bmp, float rotationDeg, float cw, float ch) {
      float[] base = new float[2];
      sizeOfBitmap(bmp, cw, ch, base);
      double rad = Math.toRadians(rotationDeg);
      float absCos = (float) Math.abs(Math.cos(rad));
      float absSin = (float) Math.abs(Math.sin(rad));
      float aabbW = Math.max(1e-3f, base[0] * absCos + base[1] * absSin);
      float aabbH = Math.max(1e-3f, base[0] * absSin + base[1] * absCos);
      return Math.max(.45f, Math.min(cw / aabbW, ch / aabbH));
    }

    float maxSafeScaleForPhoto(TablePhoto photo) {
      referenceBoundsSize(tmpDims);
      return maxSafeScaleForBitmap(photo.displayBitmap(), photo.rotation, tmpDims[0], tmpDims[1]);
    }

    private void computeSafeBounds(
        Bitmap bmp, float scale, float rotationDeg, float cw, float ch, float[] outMinMaxXY) {
      float[] base = new float[2];
      sizeOfBitmap(bmp, cw, ch, base);
      float dw = base[0] * scale;
      float dh = base[1] * scale;
      double rad = Math.toRadians(rotationDeg);
      float absCos = (float) Math.abs(Math.cos(rad));
      float absSin = (float) Math.abs(Math.sin(rad));
      float normHalfW = ((dw * absCos + dh * absSin) * .5f) / Math.max(1f, cw);
      float normHalfH = ((dw * absSin + dh * absCos) * .5f) / Math.max(1f, ch);
      outMinMaxXY[0] = Math.min(.5f, normHalfW);
      outMinMaxXY[1] = Math.max(.5f, 1f - normHalfW);
      outMinMaxXY[2] = Math.min(.5f, normHalfH);
      outMinMaxXY[3] = Math.max(.5f, 1f - normHalfH);
    }

    void clampPhotoInPlace(TablePhoto photo) {
      if (photo == null) return;
      referenceBoundsSize(tmpDims);
      float maxScale = maxSafeScaleForBitmap(photo.displayBitmap(), photo.rotation, tmpDims[0], tmpDims[1]);
      photo.scale = clamp(photo.scale, .45f, Math.min(2.4f, maxScale));
      computeSafeBounds(photo.displayBitmap(), photo.scale, photo.rotation, tmpDims[0], tmpDims[1], tmpBounds);
      photo.x = clamp(photo.x, tmpBounds[0], tmpBounds[1]);
      photo.y = clamp(photo.y, tmpBounds[2], tmpBounds[3]);
    }

    void clampAllInPlace() {
      for (TablePhoto photo : table) clampPhotoInPlace(photo);
    }

    boolean isDraggingPhoto(TablePhoto photo) {
      return dragging && photo != null && photo == selected;
    }

    float[] settledCoordinatesFor(TablePhoto photo) {
      if (photo == null) return null;
      referenceBoundsSize(tmpDims);
      float maxScale = maxSafeScaleForBitmap(photo.displayBitmap(), photo.rotation, tmpDims[0], tmpDims[1]);
      float safeScale = clamp(photo.scale, .45f, Math.min(2.4f, maxScale));
      computeSafeBounds(photo.displayBitmap(), safeScale, photo.rotation, tmpDims[0], tmpDims[1], tmpBounds);
      float rawX = (photo == snapBackTarget && snapBackAnimator != null) ? snapTargetX : photo.x;
      float rawY = (photo == snapBackTarget && snapBackAnimator != null) ? snapTargetY : photo.y;
      return new float[] {
        clamp(rawX, tmpBounds[0], tmpBounds[1]),
        clamp(rawY, tmpBounds[2], tmpBounds[3]),
        safeScale
      };
    }

    private float rubberBand(float raw, float lo, float hi, float maxOver) {
      if (raw < lo) {
        float d = lo - raw;
        return lo - maxOver * (1f - 1f / (1f + d * 4.2f / maxOver));
      }
      if (raw > hi) {
        float d = raw - hi;
        return hi + maxOver * (1f - 1f / (1f + d * 4.2f / maxOver));
      }
      return raw;
    }

    void finishSnapBackImmediately() {
      ValueAnimator active = snapBackAnimator;
      TablePhoto target = snapBackTarget;
      float tx = snapTargetX, ty = snapTargetY;
      snapBackAnimator = null;
      snapBackTarget = null;
      if (active != null) {
        active.removeAllListeners();
        active.removeAllUpdateListeners();
        active.cancel();
      }
      if (target != null && table.contains(target)) {
        target.x = tx;
        target.y = ty;
        invalidate();
      }
    }

    private void startSnapBack(TablePhoto photo, float targetX, float targetY) {
      finishSnapBackImmediately();
      if (!ValueAnimator.areAnimatorsEnabled()) {
        photo.x = targetX;
        photo.y = targetY;
        saveState();
        invalidate();
        return;
      }
      final float fromX = photo.x;
      final float fromY = photo.y;
      snapBackTarget = photo;
      snapTargetX = targetX;
      snapTargetY = targetY;
      ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
      snapBackAnimator = anim;
      anim.setDuration(280L);
      anim.setInterpolator(new OvershootInterpolator(1.1f));
      anim.addUpdateListener(
          v -> {
            if (snapBackAnimator != anim || !table.contains(photo)) return;
            float t = (Float) v.getAnimatedValue();
            photo.x = fromX + (targetX - fromX) * t;
            photo.y = fromY + (targetY - fromY) * t;
            invalidate();
          });
      anim.addListener(
          new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
              if (snapBackAnimator != anim) return;
              snapBackAnimator = null;
              snapBackTarget = null;
              if (table.contains(photo)) {
                photo.x = targetX;
                photo.y = targetY;
                saveState();
                invalidate();
              }
            }
          });
      anim.start();
    }

    private TablePhoto hitTest(float x, float y, float pad, float cw, float ch) {
      for (int i = table.size() - 1; i >= 0; i--) {
        TablePhoto photo = table.get(i);
        sizeOf(photo, cw, ch, tmpSize);
        float cx = diffuserRect.left + pad + photo.x * cw;
        float cy = diffuserRect.top + pad + photo.y * ch;
        double angle = Math.toRadians(photo.rotation);
        float dx = x - cx, dy = y - cy;
        float lx = (float) (dx * Math.cos(angle) + dy * Math.sin(angle));
        float ly = (float) (-dx * Math.sin(angle) + dy * Math.cos(angle));
        float halfW = tmpSize[0] * photo.scale * .5f + dp(8);
        float halfH = tmpSize[1] * photo.scale * .5f + dp(8);
        if (Math.abs(lx) <= halfW && Math.abs(ly) <= halfH) return photo;
      }
      return null;
    }

    Bitmap renderToBitmap(int maxEdge) {
      return renderPosterBitmap(maxEdge, landscapeMode, PosterTheme.DARK, false, snapshotPieces(), -1);
    }

    /**
     * Thread-safe 2:3 / 3:2 brand poster & pure-lightbox renderer. Runs cleanly on background
     * threads for both confirmation previews and full 1600×2400 / 2400×1600 JPEG exports without
     * mutating UI-thread draw scratch buffers.
     */
    Bitmap renderPosterBitmap(
        int longEdge,
        boolean landscape,
        PosterTheme theme,
        boolean showFooter,
        List<RenderPiece> pieces,
        int expectedPreviewGen) {
      if (longEdge <= 0) return null;
      int outW = landscape ? longEdge : Math.max(1, Math.round(longEdge * (2f / 3f)));
      int outH = landscape ? Math.max(1, Math.round(longEdge * (2f / 3f))) : longEdge;
      float halfW = halfWMm(landscape);
      float halfH = halfHMm(landscape);
      float outWMmVal = outWMm(landscape);
      float outHMmVal = outHMm(landscape);
      boolean isDark = theme == PosterTheme.DARK;

      float baseDim = Math.min(outW, outH);
      float marginX = baseDim * .056f;
      float marginTop = baseDim * .056f;
      float footerBandH = showFooter ? outH * (landscape ? .145f : .122f) : baseDim * .056f;
      float availW = outW - marginX * 2f;
      float availH = outH - marginTop - footerBandH;
      float exportPpm = Math.min(availW / (2f * outWMmVal), availH / (2f * outHMmVal));
      if (exportPpm <= 0f) return null;

      float boxOuterW = 2f * outWMmVal * exportPpm;
      float boxOuterH = 2f * outHMmVal * exportPpm;
      float exportCx = outW * .5f;
      float exportCy =
          showFooter
              ? (marginTop + boxOuterH * .5f + (availH - boxOuterH) * .42f)
              : (outH * .5f);

      int bakeScaleDiv = longEdge >= 1200 ? 2 : 1;
      int bakeW = Math.max(1, outW / bakeScaleDiv);
      int bakeH = Math.max(1, outH / bakeScaleDiv);
      float invDiv = 1f / bakeScaleDiv;
      Bitmap exportBackdrop =
          bakePosterBackdrop(
              bakeW,
              bakeH,
              exportCx * invDiv,
              exportCy * invDiv,
              exportPpm * invDiv,
              landscape,
              isDark,
              expectedPreviewGen);
      if (exportBackdrop == null) return null;

      Bitmap bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
      Canvas c = new Canvas(bitmap);
      Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
      c.drawBitmap(exportBackdrop, null, new RectF(0, 0, outW, outH), bmpPaint);
      exportBackdrop.recycle();

      RectF exportLit =
          new RectF(
              exportCx - halfW * exportPpm,
              exportCy - halfH * exportPpm,
              exportCx + halfW * exportPpm,
              exportCy + halfH * exportPpm);
      drawEtchedRuler(
          c,
          exportLit.left,
          exportLit.top,
          exportLit.width(),
          exportLit.height(),
          INNER_R_MM * exportPpm,
          exportPpm,
          halfW,
          halfH,
          rulerFont);

      // Deterministic physical scale independent of live view size / device density.
      float scaleFactor = exportPpm / (dp(1) * 2.57f);
      float pad = dp(8) * scaleFactor;
      float cw = Math.max(1f, exportLit.width() - pad * 2f);
      float ch = Math.max(1f, exportLit.height() - pad * 2f);

      PiecePainter posterPainter = new PiecePainter(bmpPaint);
      float[] localSize = new float[2];
      float[] localBounds = new float[4];

      if (pieces != null) {
        for (RenderPiece piece : pieces) {
          if (expectedPreviewGen >= 0 && (released || exportPreviewGen.get() != expectedPreviewGen)) {
            bitmap.recycle();
            return null;
          }
          Bitmap pieceBitmap = piece.bitmap();
          float maxScale = maxSafeScaleForBitmap(pieceBitmap, piece.rotation, cw, ch);
          float safeScale = clamp(piece.scale, .45f, Math.min(2.4f, maxScale));
          computeSafeBounds(pieceBitmap, safeScale, piece.rotation, cw, ch, localBounds);
          float safeX = clamp(piece.x, localBounds[0], localBounds[1]);
          float safeY = clamp(piece.y, localBounds[2], localBounds[3]);
          sizeOfBitmap(pieceBitmap, cw, ch, localSize);
          float cx = exportLit.left + pad + safeX * cw;
          float cy = exportLit.top + pad + safeY * ch;
          posterPainter.draw(c, pieceBitmap, piece.decoded != null && piece.decoded.acetate,
              cx, cy, localSize[0] * safeScale, localSize[1] * safeScale,
              piece.rotation, scaleFactor, false, landscape);
        }
      }

      if (showFooter) {
        drawPosterFooter(
            c,
            outW,
            outH,
            exportCx - boxOuterW * .5f,
            exportCx + boxOuterW * .5f,
            exportCy + boxOuterH * .5f,
            isDark,
            pieces,
            bmpPaint,
            posterPainter.shadow,
            posterPainter.rect);
      }
      return bitmap;
    }

    /**
     * Draws the Option 1 brand & contact-sheet archive nameplate below the lightbox:
     * Left: app icon (with physical lift shadow), brand name, darkroom slogan.
     * Right: primary film stock (+N for multi-stock tables) & date / frame count in tabular figures.
     */
    private void drawPosterFooter(
        Canvas c,
        int w,
        int h,
        float boxLeft,
        float boxRight,
        float boxBottom,
        boolean isDark,
        List<RenderPiece> pieces,
        Paint bmpPaint,
        Paint localShadowPaint,
        RectF localRect) {
      float footCenterY = boxBottom + (h - boxBottom) * .50f;
      float s = Math.min(w, h);
      float iconSize = Math.round(s * .088f);
      float iconX = boxLeft;
      float iconY = Math.round(footCenterY - iconSize * .5f);
      float iconRadius = iconSize * .22f;

      // Soft physical lift shadow under app icon.
      c.save();
      c.translate(iconX + iconSize * .5f, iconY + iconSize * .5f + iconSize * .08f);
      drawLayeredShadow(
          c,
          iconSize * .46f,
          iconSize * .46f,
          iconSize * .16f,
          isDark ? 0x00000000 : 0x0014141a,
          isDark ? .55f : .18f,
          SOFT_SHADOW_SPREAD,
          localShadowPaint,
          localRect);
      c.restore();

      Bitmap logo =
          BitmapFactory.decodeResource(activity.getResources(), R.drawable.ic_spektra_film_s_logo);
      localRect.set(iconX, iconY, iconX + iconSize, iconY + iconSize);
      if (logo != null) {
        c.save();
        Path iconClip = new Path();
        iconClip.addRoundRect(localRect, iconRadius, iconRadius, Path.Direction.CW);
        c.clipPath(iconClip);
        c.drawBitmap(logo, null, localRect, bmpPaint);
        c.restore();
        logo.recycle();
      }
      if (isDark) {
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setStyle(Paint.Style.STROKE);
        border.setColor(0x24ffffff);
        border.setStrokeWidth(Math.max(1f, s * .0014f));
        c.drawRoundRect(localRect, iconRadius, iconRadius, border);
      }

      float textX = iconX + iconSize + s * .026f;
      Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      titlePaint.setTypeface(AppStyle.typeface(activity, AppStyle.FONT_BOLD));
      titlePaint.setTextSize(s * .036f);
      titlePaint.setColor(isDark ? 0xfff5f2ee : 0xff18181b);
      titlePaint.setTextAlign(Paint.Align.LEFT);
      String brandName = "SpektraLens";
      c.drawText(brandName, textX, footCenterY - s * .005f, titlePaint);

      Paint subPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      subPaint.setTypeface(AppStyle.typeface(activity, AppStyle.FONT_REGULAR));
      subPaint.setTextSize(s * .0205f);
      subPaint.setColor(isDark ? 0xff98909a : 0xff6a6a72);
      subPaint.setTextAlign(Paint.Align.LEFT);
      c.drawText(POSTER_SLOGAN, textX, footCenterY + s * .026f, subPaint);

      float leftBlockRight =
          textX + Math.max(titlePaint.measureText(brandName), subPaint.measureText(POSTER_SLOGAN));

      // Right: Option 1 contact-sheet film metadata stamp.
      ArrayList<String> filmLabels = new ArrayList<>();
      int frameCount = pieces == null ? 0 : pieces.size();
      if (pieces != null) {
        for (RenderPiece piece : pieces) filmLabels.add(piece.source.filmLabel);
      }
      String topMeta = formatFilmSummary(filmLabels);
      String dateText = new SimpleDateFormat("yyyy.MM.dd", Locale.ROOT).format(new Date());
      String botMeta = formatFrameSummary(dateText, frameCount);

      float maxRightWidth = Math.max(s * .22f, boxRight - leftBlockRight - s * .03f);

      Paint metaTopPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      metaTopPaint.setTypeface(AppStyle.typeface(activity, AppStyle.FONT_SEMIBOLD));
      metaTopPaint.setFontFeatureSettings("tnum");
      metaTopPaint.setTextSize(s * .0225f);
      // Keep dark-poster vermilion independent of the current Light/Dark app UI theme.
      metaTopPaint.setColor(isDark ? 0xfff0523d : 0xff26262b);
      metaTopPaint.setTextAlign(Paint.Align.RIGHT);
      float topMeasured = metaTopPaint.measureText(topMeta);
      if (topMeasured > maxRightWidth && topMeasured > 0f) {
        metaTopPaint.setTextSize(metaTopPaint.getTextSize() * (maxRightWidth / topMeasured));
      }
      c.drawText(topMeta, boxRight, footCenterY - s * .005f, metaTopPaint);

      Paint metaBotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      metaBotPaint.setTypeface(AppStyle.typeface(activity, AppStyle.FONT_MEDIUM));
      metaBotPaint.setFontFeatureSettings("tnum");
      metaBotPaint.setTextSize(s * .0185f);
      metaBotPaint.setColor(isDark ? 0xff8b838e : 0xff7a7a84);
      metaBotPaint.setTextAlign(Paint.Align.RIGHT);
      float botMeasured = metaBotPaint.measureText(botMeta);
      if (botMeasured > maxRightWidth && botMeasured > 0f) {
        metaBotPaint.setTextSize(metaBotPaint.getTextSize() * (maxRightWidth / botMeasured));
      }
      c.drawText(botMeta, boxRight, footCenterY + s * .025f, metaBotPaint);
    }

    private Bitmap bakePosterBackdrop(
        int width,
        int height,
        float cx,
        float cy,
        float scalePpm,
        boolean landscape,
        boolean isDark,
        int expectedPreviewGen) {
      float halfW = halfWMm(landscape);
      float halfH = halfHMm(landscape);
      float outW = outWMm(landscape);
      float outH = outHMm(landscape);
      int[] pixels = new int[width * height];
      float invPpm = 1f / scalePpm;
      float pxMm = invPpm;
      float lx = -.34f, ly = -.94f;
      float invH = 1f / Math.max(1, height);

      for (int y = 0; y < height; y++) {
        if (expectedPreviewGen >= 0 && (released || exportPreviewGen.get() != expectedPreviewGen)) {
          return null;
        }
        float ty = y * invH;
        float deskR, deskG, deskB;
        if (isDark) {
          deskR = mix(.036f, .018f, ty);
          deskG = mix(.034f, .018f, ty);
          deskB = mix(.042f, .022f, ty);
        } else {
          deskR = mix(.935f, .908f, ty);
          deskG = mix(.934f, .907f, ty);
          deskB = mix(.940f, .914f, ty);
        }
        float py = (y + .5f - cy) * invPpm;
        float absPy = Math.abs(py);
        int rowOffset = y * width;
        for (int x = 0; x < width; x++) {
          float px = (x + .5f - cx) * invPpm;
          float absPx = Math.abs(px);
          float dIn = sdRoundBoxAbs(absPx, absPy, halfW, halfH, INNER_R_MM);

          float r, g, b;
          if (dIn < -pxMm) {
            float inset = -dIn;
            float fall = smoothstep(0f, 34f, inset);
            float lipShadow = inset < 8f ? (1f - .105f * (float) Math.exp(-inset / .72f)) : 1f;
            float edgeFall = mix(.928f, 1.0f, (float) Math.pow(fall, .68f));
            float nx = px / halfW, ny = py / halfH;
            float centerDome = 1.0f - .026f * (nx * nx + ny * ny);
            float lum = edgeFall * lipShadow * centerDome;
            float grain = (vnoise(px * 4.5f, py * 4.5f) - .5f) * .015f;
            r = clamp(mix(.992f, 1.000f, fall) * lum + grain, 0f, 1f);
            g = clamp(mix(.964f, .996f, fall) * lum + grain, 0f, 1f);
            b = clamp(mix(.926f, .988f, fall) * lum + grain, 0f, 1f);
          } else {
            float dOut = sdRoundBoxAbs(absPx, absPy, outW, outH, OUTER_R_MM);
            r = deskR;
            g = deskG;
            b = deskB;

            // Uniform light anodised aluminium bezel (no outer halo/shadow, no directional light gradient).
            if (dOut < pxMm && dIn > -pxMm) {
              float u = clamp(-dOut / FRAME_W_MM, 0f, 1f);
              float qx = absPx - outW + OUTER_R_MM;
              float qy = absPy - outH + OUTER_R_MM;
              float gx, gy;
              if (qx > 0f || qy > 0f) {
                float mx = (qx > 0f ? qx : 0f) + 1e-5f;
                float my = (qy > 0f ? qy : 0f) + 1e-5f;
                float invLen = 1f / (float) Math.hypot(mx, my);
                gx = mx * invLen;
                gy = my * invLen;
              } else if (qx > qy) {
                gx = 1f;
                gy = 0f;
              } else {
                gx = 0f;
                gy = 1f;
              }
              float nx = px >= 0f ? gx : -gx;
              float ny = py >= 0f ? gy : -gy;
              float along = px * (-ny) + py * nx;
              float across = px * nx + py * ny;
              float brush =
                  (vnoise(along * .16f, across * 28.0f) - .5f) * .70f
                      + (vnoise(along * .55f, across * 64.0f) - .5f) * .30f;

              float shade = .885f;
              shade -= .045f * (1f - smoothstep(0f, .18f, u));
              float crestD = (u - .24f) / .090f;
              float crest = (float) Math.exp(-crestD * crestD);
              shade += .055f * crest + brush * .028f;
              shade -= .075f * smoothstep(.76f, .90f, u) * (1f - smoothstep(.94f, 1.0f, u));
              shade = clamp(shade, .76f, .955f);

              float fr = shade * .985f;
              float fg = shade * .992f;
              float fb = shade;
              float bounce = .26f * smoothstep(.90f, 1.0f, u);
              fr = mix(fr, 1.000f, bounce);
              fg = mix(fg, .986f, bounce);
              fb = mix(fb, .960f, bounce);

              float frameCov = 1f - smoothstep(-pxMm, pxMm, dOut);
              r = mix(r, fr, frameCov);
              g = mix(g, fg, frameCov);
              b = mix(b, fb, frameCov);
            }

            if (dIn < pxMm) {
              float inset = Math.max(-dIn, 0f);
              float fall = smoothstep(0f, 34f, inset);
              float lipShadow = 1f - .105f * (float) Math.exp(-inset / .72f);
              float edgeFall = mix(.928f, 1.0f, (float) Math.pow(fall, .68f));
              float nx = px / halfW, ny = py / halfH;
              float centerDome = 1.0f - .026f * (nx * nx + ny * ny);
              float lum = edgeFall * lipShadow * centerDome;
              float grain = (vnoise(px * 4.5f, py * 4.5f) - .5f) * .015f;
              float lr = clamp(mix(.992f, 1.000f, fall) * lum + grain, 0f, 1f);
              float lg = clamp(mix(.964f, .996f, fall) * lum + grain, 0f, 1f);
              float lb = clamp(mix(.926f, .988f, fall) * lum + grain, 0f, 1f);

              float litCov = 1f - smoothstep(-pxMm, pxMm, dIn);
              r = mix(r, lr, litCov);
              g = mix(g, lg, litCov);
              b = mix(b, lb, litCov);
            }
          }

          int ir = Math.round(clamp(r, 0f, 1f) * 255f);
          int ig = Math.round(clamp(g, 0f, 1f) * 255f);
          int ib = Math.round(clamp(b, 0f, 1f) * 255f);
          pixels[rowOffset + x] = 0xff000000 | (ir << 16) | (ig << 8) | ib;
        }
      }

      Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      bmp.setPixels(pixels, 0, width, 0, 0, width, height);
      return bmp;
    }

    private void recyclePrebaked() {
      if (backdropBitmap != null) {
        backdropBitmap.recycle();
        backdropBitmap = null;
      }
      if (loupeShadowBitmap != null) {
        loupeShadowBitmap.recycle();
        loupeShadowBitmap = null;
      }
      if (loupeTintBitmap != null) {
        loupeTintBitmap.recycle();
        loupeTintBitmap = null;
      }
      if (loupeSpecBitmap != null) {
        loupeSpecBitmap.recycle();
        loupeSpecBitmap = null;
      }
    }

    void release() {
      finishSnapBackImmediately();
      bakeGeneration.incrementAndGet();
      recyclePrebaked();
    }

    // ---- Pre-baked optical physics kernels (ported from SpektraLab Lightpad GLSL shaders) ----

    private Bitmap bakeLightboxBackdrop(
        int width,
        int height,
        float cx,
        float cy,
        float scalePpm,
        float spillMm,
        boolean landscape,
        boolean includeRuler,
        Typeface font,
        int expectedGen) {
      float halfW = halfWMm(landscape);
      float halfH = halfHMm(landscape);
      float outW = outWMm(landscape);
      float outH = outHMm(landscape);
      int[] pixels = new int[width * height];
      float invPpm = 1f / scalePpm;
      float pxMm = invPpm;
      float lx = -.34f, ly = -.94f;
      float fadeStart = spillMm * .55f;

      for (int y = 0; y < height; y++) {
        if (expectedGen >= 0 && (released || bakeGeneration.get() != expectedGen)) return null;
        float py = (y + .5f - cy) * invPpm;
        float absPy = Math.abs(py);
        int rowOffset = y * width;
        for (int x = 0; x < width; x++) {
          float px = (x + .5f - cx) * invPpm;
          float absPx = Math.abs(px);
          float dIn = sdRoundBoxAbs(absPx, absPy, halfW, halfH, INNER_R_MM);

          float r, g, b;
          if (dIn < -pxMm) {
            // Fast path inside powered-on luminous acrylic diffuser (covers ~85% of pixels).
            float inset = -dIn;
            float fall = smoothstep(0f, 34f, inset);
            float lipShadow = inset < 8f ? (1f - .105f * (float) Math.exp(-inset / .72f)) : 1f;
            float edgeFall = mix(.928f, 1.0f, (float) Math.pow(fall, .68f));
            float nx = px / halfW, ny = py / halfH;
            float centerDome = 1.0f - .026f * (nx * nx + ny * ny);
            float lum = edgeFall * lipShadow * centerDome;
            float grain =
                (vnoise(px * 4.5f, py * 4.5f) - .5f) * .015f + (hash2(x, y) - .5f) * .006f;
            r = clamp(mix(.992f, 1.000f, fall) * lum + grain, 0f, 1f);
            g = clamp(mix(.964f, .996f, fall) * lum + grain, 0f, 1f);
            b = clamp(mix(.926f, .988f, fall) * lum + grain, 0f, 1f);
          } else {
            float dOut = sdRoundBoxAbs(absPx, absPy, outW, outH, OUTER_R_MM);
            float baseR = Color.red(palette.pageBg) / 255f;
            float baseG = Color.green(palette.pageBg) / 255f;
            float baseB = Color.blue(palette.pageBg) / 255f;
            r = baseR;
            g = baseG;
            b = baseB;

            // Clean borderless edge outside chassis: keep pure pageBg without outer halo or shadow.

            // Uniform light anodised aluminium chassis (no directional light gradient, dark & light unified).
            if (dOut < pxMm && dIn > -pxMm) {
              float u = clamp(-dOut / FRAME_W_MM, 0f, 1f);
              float qx = absPx - outW + OUTER_R_MM;
              float qy = absPy - outH + OUTER_R_MM;
              float gx, gy;
              if (qx > 0f || qy > 0f) {
                float mx = (qx > 0f ? qx : 0f) + 1e-5f;
                float my = (qy > 0f ? qy : 0f) + 1e-5f;
                float invLen = 1f / (float) Math.hypot(mx, my);
                gx = mx * invLen;
                gy = my * invLen;
              } else if (qx > qy) {
                gx = 1f;
                gy = 0f;
              } else {
                gx = 0f;
                gy = 1f;
              }
              float nx = px >= 0f ? gx : -gx;
              float ny = py >= 0f ? gy : -gy;
              float tx = -ny, ty = nx;
              float along = px * tx + py * ty;
              float across = px * nx + py * ny;

              float streak1 = vnoise(along * .16f, across * 28.0f) - .5f;
              float streak2 = vnoise(along * .55f, across * 64.0f) - .5f;
              float brush = streak1 * .70f + streak2 * .30f;

              float shade = .885f;
              shade -= .045f * (1f - smoothstep(0f, .18f, u));
              float crestD = (u - .24f) / .090f;
              float crest = (float) Math.exp(-crestD * crestD);
              shade += .055f * crest + brush * .028f;
              shade -= .075f * smoothstep(.76f, .90f, u) * (1f - smoothstep(.94f, 1.0f, u));
              shade = clamp(shade, .76f, .955f);

              float fr = shade * .985f;
              float fg = shade * .992f;
              float fb = shade;
              float bounce = .26f * smoothstep(.90f, 1.0f, u);
              fr = mix(fr, 1.000f, bounce);
              fg = mix(fg, .986f, bounce);
              fb = mix(fb, .960f, bounce);

              float frameCov = 1f - smoothstep(-pxMm, pxMm, dOut);
              r = mix(r, fr, frameCov);
              g = mix(g, fg, frameCov);
              b = mix(b, fb, frameCov);
            }

            // 3. Anti-aliased inner diffuser boundary.
            if (dIn < pxMm) {
              float inset = Math.max(-dIn, 0f);
              float fall = smoothstep(0f, 34f, inset);
              float lipShadow = 1f - .105f * (float) Math.exp(-inset / .72f);
              float edgeFall = mix(.928f, 1.0f, (float) Math.pow(fall, .68f));
              float nx = px / halfW, ny = py / halfH;
              float centerDome = 1.0f - .026f * (nx * nx + ny * ny);
              float lum = edgeFall * lipShadow * centerDome;
              float grain =
                  (vnoise(px * 4.5f, py * 4.5f) - .5f) * .015f + (hash2(x, y) - .5f) * .006f;
              float lr = clamp(mix(.992f, 1.000f, fall) * lum + grain, 0f, 1f);
              float lg = clamp(mix(.964f, .996f, fall) * lum + grain, 0f, 1f);
              float lb = clamp(mix(.926f, .988f, fall) * lum + grain, 0f, 1f);

              float litCov = 1f - smoothstep(-pxMm, pxMm, dIn);
              r = mix(r, lr, litCov);
              g = mix(g, lg, litCov);
              b = mix(b, lb, litCov);
            }
          }

          int ir = Math.round(clamp(r, 0f, 1f) * 255f);
          int ig = Math.round(clamp(g, 0f, 1f) * 255f);
          int ib = Math.round(clamp(b, 0f, 1f) * 255f);
          pixels[rowOffset + x] = 0xff000000 | (ir << 16) | (ig << 8) | ib;
        }
      }

      Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      bmp.setPixels(pixels, 0, width, 0, 0, width, height);

      if (includeRuler) {
        Canvas c = new Canvas(bmp);
        float litLeft = cx - halfW * scalePpm;
        float litTop = cy - halfH * scalePpm;
        drawEtchedRuler(
            c,
            litLeft,
            litTop,
            halfW * 2f * scalePpm,
            halfH * 2f * scalePpm,
            INNER_R_MM * scalePpm,
            scalePpm,
            halfW,
            halfH,
            font);
      }
      return bmp;
    }

    private void drawEtchedRuler(
        Canvas c,
        float litLeft,
        float litTop,
        float litW,
        float litH,
        float litR,
        float scalePpm,
        float halfW,
        float halfH,
        Typeface font) {
      c.save();
      Path clip = new Path();
      clip.addRoundRect(litLeft, litTop, litLeft + litW, litTop + litH, litR, litR, Path.Direction.CW);
      c.clipPath(clip);

      PorterDuffXfermode multiply = new PorterDuffXfermode(PorterDuff.Mode.MULTIPLY);
      Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
      tick.setStyle(Paint.Style.STROKE);
      tick.setStrokeCap(Paint.Cap.BUTT);
      tick.setXfermode(multiply);

      int totalMmX = Math.round(halfW * 2f);
      int totalMmY = Math.round(halfH * 2f);

      // Top edge 1 / 5 / 10 mm etched ticks.
      for (int mm = 5; mm <= totalMmX - 5; mm++) {
        boolean isCm = (mm % 10 == 0);
        boolean isHalf = (mm % 5 == 0);
        float lenMm = isCm ? 4.4f : (isHalf ? 2.7f : 1.55f);
        tick.setColor(isCm ? 0x9e383a40 : (isHalf ? 0x7044464c : 0x4754565c));
        tick.setStrokeWidth(Math.max(.65f, (isCm ? .28f : (isHalf ? .19f : .13f)) * scalePpm));
        float x = litLeft + mm * scalePpm;
        c.drawLine(x, litTop, x, litTop + lenMm * scalePpm, tick);
      }

      // Left edge 1 / 5 / 10 mm etched ticks.
      for (int mm = 5; mm <= totalMmY - 5; mm++) {
        boolean isCm = (mm % 10 == 0);
        boolean isHalf = (mm % 5 == 0);
        float lenMm = isCm ? 4.4f : (isHalf ? 2.7f : 1.55f);
        tick.setColor(isCm ? 0x9e383a40 : (isHalf ? 0x7044464c : 0x4754565c));
        tick.setStrokeWidth(Math.max(.65f, (isCm ? .28f : (isHalf ? .19f : .13f)) * scalePpm));
        float y = litTop + mm * scalePpm;
        c.drawLine(litLeft, y, litLeft + lenMm * scalePpm, y, tick);
      }

      // Tabular etched centimetre numerals.
      Paint numPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
      numPaint.setTypeface(font);
      numPaint.setFontFeatureSettings("tnum");
      numPaint.setTextSize(Math.max(8f, 2.7f * scalePpm));
      numPaint.setColor(0x8f42444a);
      numPaint.setXfermode(multiply);

      Paint.FontMetrics fm = numPaint.getFontMetrics();
      float topBaseline = litTop + 5.2f * scalePpm - fm.ascent;
      numPaint.setTextAlign(Paint.Align.CENTER);
      for (int cm = 1; cm * 10 <= totalMmX - 5; cm++) {
        c.drawText(String.valueOf(cm), litLeft + cm * 10f * scalePpm, topBaseline, numPaint);
      }

      float midOffset = -(fm.ascent + fm.descent) * .5f;
      numPaint.setTextAlign(Paint.Align.LEFT);
      for (int cm = 1; cm * 10 <= totalMmY - 5; cm++) {
        c.drawText(
            String.valueOf(cm),
            litLeft + 5.4f * scalePpm,
            litTop + cm * 10f * scalePpm + .2f * scalePpm + midOffset,
            numPaint);
      }

      c.restore();
    }

    /**
     * Pre-bakes the three static optical layers of the bevelled crystal loupe:
     * [0] directional floating shadow,
     * [1] glass thickness shading + inner/outer crystal seams + frosted bevel tint,
     * [2] top-left crescent dome reflection, lower-right warm caustic, bevel specular ridge & subtle
     *     prism dispersion.
     */
    private Bitmap[] bakeOpticalLoupeLayers(
        int half,
        float lensR,
        float rimW,
        float shadowDx,
        float shadowDy,
        float shadowInner,
        float shadowOuter,
        float seamWidth,
        int expectedGen) {
      int size = half * 2;
      int[] shadowPx = new int[size * size];
      int[] tintPx = new int[size * size];
      int[] specPx = new int[size * size];

      float totalR = lensR + rimW;
      float lx = -.5f, ly = -.866f;

      for (int y = 0; y < size; y++) {
        if (expectedGen >= 0 && (released || bakeGeneration.get() != expectedGen)) return null;
        float dy = y + .5f - half;
        int row = y * size;
        for (int x = 0; x < size; x++) {
          float dx = x + .5f - half;
          float d = (float) Math.hypot(dx, dy);

          // 1. Directional floating glass shadow (down-right offset).
          float sd = (float) Math.hypot(dx - shadowDx, dy - shadowDy) - totalR;
          float sh = .24f * (1f - smoothstep(-shadowInner, shadowOuter, sd));
          if (sh > .002f) {
            shadowPx[row + x] = (Math.round(clamp(sh, 0f, 1f) * 255f) << 24);
          }

          float disc = 1f - smoothstep(totalR - 1.2f, totalR + 1.2f, d);
          if (disc <= .001f) continue;

          float invD = 1f / Math.max(d, 1e-4f);
          float dirX = dx * invD, dirY = dy * invD;
          float r = d / lensR;
          float nx = dx / lensR, ny = dy / lensR;
          float dirDotL = dirX * lx + dirY * ly;

          // Optical edge vignette (deeper cool thickness along bottom-right curvature).
          float vig = 1f - .16f * smoothstep(.68f, 1.0f, r) * (.72f - .28f * dirDotL);

          // Inner & outer precision crystal seams.
          float innerSeam = 1f - .30f * (1f - smoothstep(0f, seamWidth, Math.abs(d - lensR)));
          float outerSeam = 1f - .54f * (1f - smoothstep(0f, seamWidth, Math.abs(d - totalR)));
          float seam = innerSeam * outerSeam;

          float rimBlend = d > lensR - 1.5f ? smoothstep(lensR - 1.2f, lensR + 1.2f, d) : 0f;
          float tr = clamp((d - lensR) / rimW, 0f, 1f);
          float facing = dirDotL * .5f + .5f;

          // Layer [1]: Multiplicative glass shading + frosted crystal bevel tint (SRC_OVER exact).
          float keepL = vig * seam;
          float whiteMix = .28f + .46f * facing;
          float rimTint = .64f + .36f * facing;
          float keepR = (1f - whiteMix) * rimTint * seam;
          float addRR = .98f * whiteMix * rimTint * .97f * seam;
          float addGR = .99f * whiteMix * rimTint * .99f * seam;
          float addBR = 1.00f * whiteMix * rimTint * 1.02f * seam;

          float keep = mix(keepL, keepR, rimBlend);
          float addR = addRR * rimBlend;
          float addG = addGR * rimBlend;
          float addB = addBR * rimBlend;

          float tintA = clamp(1f - keep, 0f, 1f);
          float exR = 0f, exG = 0f, exB = 0f;
          if (tintA > 1e-4f) {
            float cr = addR / tintA;
            float cg = addG / tintA;
            float cb = addB / tintA;
            if (cr > 1f) {
              exR = (cr - 1f) * tintA;
              cr = 1f;
            }
            if (cg > 1f) {
              exG = (cg - 1f) * tintA;
              cg = 1f;
            }
            if (cb > 1f) {
              exB = (cb - 1f) * tintA;
              cb = 1f;
            }
            tintPx[row + x] = packArgb(tintA * disc, cr, cg, cb);
          } else {
            exR = addR;
            exG = addG;
            exB = addB;
          }

          // Layer [2]: Top-left crescent specular, dome sheen, lower-right warm caustic, prism
          // micro-dispersion and bevel crystal highlight ridge.
          float crescent = .17f * smoothstep(.56f, .96f, r) * smoothstep(.24f, .94f, dirDotL);
          float dome =
              .042f * (1f - smoothstep(0f, .72f, (float) Math.hypot(nx - lx * .44f, ny - ly * .44f)));
          float caustic = .075f * smoothstep(.76f, .97f, r) * smoothstep(.35f, .95f, -dirDotL);
          float edgeDisp = smoothstep(.84f, .99f, r) * (1f - smoothstep(.99f, 1.03f, r));
          float warmEdge = edgeDisp * smoothstep(.1f, .9f, dirDotL) * .038f;
          float coolEdge = edgeDisp * smoothstep(.1f, .9f, -dirDotL) * .042f;

          float lensSpecR = (crescent + dome + caustic * 1.05f + warmEdge) * seam;
          float lensSpecG = (crescent + dome + caustic * .98f + (warmEdge + coolEdge) * .35f) * seam;
          float lensSpecB = (crescent + dome + caustic * .88f + coolEdge) * seam;

          float ridgeD = (tr - .28f) / .14f;
          float ridge = .40f * (float) Math.exp(-ridgeD * ridgeD) * smoothstep(.38f, 1.0f, facing);
          float lowD = (tr - .65f) / .16f;
          float lowGlint =
              .14f * (float) Math.exp(-lowD * lowD) * smoothstep(.45f, 1.0f, 1f - facing);
          float rimSpecR = (ridge + lowGlint * .95f) * seam;
          float rimSpecG = (ridge + lowGlint * .97f) * seam;
          float rimSpecB = (ridge + lowGlint * 1.02f) * seam;

          float sR = mix(lensSpecR, rimSpecR, rimBlend) + exR;
          float sG = mix(lensSpecG, rimSpecG, rimBlend) + exG;
          float sB = mix(lensSpecB, rimSpecB, rimBlend) + exB;
          float sMax = Math.max(sR, Math.max(sG, sB));
          if (sMax > .002f) {
            float sA = clamp(sMax, 0f, 1f);
            specPx[row + x] = packArgb(sA * disc, sR / sMax, sG / sMax, sB / sMax);
          }
        }
      }

      Bitmap shadowBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
      shadowBmp.setPixels(shadowPx, 0, size, 0, 0, size, size);
      Bitmap tintBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
      tintBmp.setPixels(tintPx, 0, size, 0, 0, size, size);
      Bitmap specBmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
      specBmp.setPixels(specPx, 0, size, 0, 0, size, size);
      return new Bitmap[] {shadowBmp, tintBmp, specBmp};
    }
  }

  private static int packArgb(float a, float r, float g, float b) {
    int ia = Math.round(Math.max(0f, Math.min(1f, a)) * 255f);
    int ir = Math.round(Math.max(0f, Math.min(1f, r)) * 255f);
    int ig = Math.round(Math.max(0f, Math.min(1f, g)) * 255f);
    int ib = Math.round(Math.max(0f, Math.min(1f, b)) * 255f);
    return (ia << 24) | (ir << 16) | (ig << 8) | ib;
  }

  private static float mix(float a, float b, float t) {
    return a + (b - a) * t;
  }

  private static float smoothstep(float e0, float e1, float x) {
    float t = (x - e0) / (e1 - e0);
    if (t <= 0f) return 0f;
    if (t >= 1f) return 1f;
    return t * t * (3f - 2f * t);
  }

  private static float sdRoundBoxAbs(float absPx, float absPy, float bx, float by, float r) {
    float qx = absPx - bx + r;
    float qy = absPy - by + r;
    if (qx <= 0f && qy <= 0f) return Math.max(qx, qy) - r;
    float mx = qx > 0f ? qx : 0f;
    float my = qy > 0f ? qy : 0f;
    return (float) Math.hypot(mx, my) - r;
  }

  private static float hash2(int ix, int iy) {
    int h = ix * 0x27d4eb2d ^ iy * 0x165667b1;
    h = (h ^ (h >>> 15)) * 0x85ebca6b;
    h = (h ^ (h >>> 13)) * 0xc2b2ae35;
    h ^= h >>> 16;
    return (h & 0x00ffffff) * (1f / 16777216f);
  }

  private static float vnoise(float x, float y) {
    int ix = (int) Math.floor(x);
    int iy = (int) Math.floor(y);
    float fx = x - ix;
    float fy = y - iy;
    float ux = fx * fx * (3f - 2f * fx);
    float uy = fy * fy * (3f - 2f * fy);
    float a = hash2(ix, iy);
    float b = hash2(ix + 1, iy);
    float c = hash2(ix, iy + 1);
    float d = hash2(ix + 1, iy + 1);
    return mix(mix(a, b, ux), mix(c, d, ux), uy);
  }

  /** Precision optical power icon button for Option B dimmer capsule. */
  private final class PowerSwitchView extends View {
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();
    private boolean on = true;

    PowerSwitchView(Context context) {
      super(context);
      setClickable(true);
      setFocusable(true);
      setClipToOutline(true);
      strokePaint.setStyle(Paint.Style.STROKE);
      strokePaint.setStrokeCap(Paint.Cap.ROUND);
      strokePaint.setStrokeWidth(dp(1.85f));
      applyBackground();
    }

    void setOnState(boolean on) {
      if (this.on == on && getBackground() != null) return;
      this.on = on;
      applyBackground();
      invalidate();
    }

    private void applyBackground() {
      GradientDrawable bg =
          AppStyle.shape(
              getContext(), on ? AppStyle.ACCENT_SOFT : AppStyle.CONTROL, 9f, false);
      bg.setStroke(
          Math.max(1, dp(on ? .85f : .75f)), on ? AppStyle.ACCENT_BORDER : AppStyle.OUTLINE);
      setBackground(
          new RippleDrawable(
              ColorStateList.valueOf(AppStyle.RIPPLE),
              bg,
              AppStyle.shape(getContext(), Color.WHITE, 9f, false)));
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float cx = getWidth() * .5f;
      float cy = getHeight() * .5f;
      float r = dp(5.4f);
      strokePaint.setColor(on ? AppStyle.GOLD : AppStyle.MUTED);
      arcRect.set(cx - r, cy - r + dp(.6f), cx + r, cy + r + dp(.6f));
      canvas.drawArc(arcRect, -54f, 288f, false, strokePaint);
      canvas.drawLine(cx, cy - r - dp(1.2f), cx, cy + dp(.2f), strokePaint);
    }
  }

  /**
   * Precision optical tick scale & vermilion needle slider matching Option B design specification.
   */
  private final class DimmerScaleView extends View {
    private static final int TICK_COUNT = 15;
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumbStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF thumbRect = new RectF();
    private final RectF needleRect = new RectF();
    private boolean on = true;
    private int brightness = MAX_BRIGHTNESS;

    DimmerScaleView(Context context) {
      super(context);
      setFocusable(true);
      setContentDescription("观片台亮度调节");
      linePaint.setStrokeCap(Paint.Cap.BUTT);
      thumbFillPaint.setStyle(Paint.Style.FILL);
      thumbStrokePaint.setStyle(Paint.Style.STROKE);
      thumbStrokePaint.setStrokeWidth(Math.max(1f, dp(.75f)));
      needlePaint.setStyle(Paint.Style.FILL);
    }

    void setState(boolean on, int brightness) {
      if (this.on == on && this.brightness == brightness) return;
      this.on = on;
      this.brightness = brightness;
      setContentDescription(on ? ("观片台亮度 " + brightness + "%") : "观片台灯光已关闭");
      invalidate();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouchEvent(MotionEvent event) {
      int action = event.getActionMasked();
      if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        float padX = dp(7f);
        float span = Math.max(1f, getWidth() - padX * 2f);
        float frac = clamp((event.getX() - padX) / span, 0f, 1f);
        int target = Math.round(MIN_BRIGHTNESS + frac * (MAX_BRIGHTNESS - MIN_BRIGHTNESS));
        onDimmerScrub(target);
        return true;
      }
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      }
      return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      float w = getWidth();
      float h = getHeight();
      if (w <= 0f || h <= 0f) return;

      float padX = dp(7f);
      float startX = padX;
      float endX = w - padX;
      float midY = h * .5f;
      float tickHalf = dp(5f);

      // Subtle horizontal center baseline
      linePaint.setColor(AppStyle.withAlpha(AppStyle.MUTED, .22f));
      linePaint.setStrokeWidth(Math.max(1f, dp(.8f)));
      canvas.drawLine(startX, midY, endX, midY, linePaint);

      // Evenly spaced precision vertical ticks
      linePaint.setColor(AppStyle.withAlpha(AppStyle.MUTED, .44f));
      linePaint.setStrokeWidth(Math.max(1f, dp(.9f)));
      for (int i = 0; i < TICK_COUNT; i++) {
        float x = startX + (endX - startX) * (i / (float) (TICK_COUNT - 1));
        canvas.drawLine(x, midY - tickHalf, x, midY + tickHalf, linePaint);
      }

      // Precision thumb with central vermilion optical needle
      float frac = clamp((brightness - MIN_BRIGHTNESS) / (float) (MAX_BRIGHTNESS - MIN_BRIGHTNESS), 0f, 1f);
      float thumbCx = startX + (endX - startX) * frac;
      float thumbW = dp(14f);
      float thumbH = dp(18f);
      float thumbR = dp(4f);
      float alphaScale = on ? 1f : .42f;

      thumbRect.set(thumbCx - thumbW * .5f, midY - thumbH * .5f, thumbCx + thumbW * .5f, midY + thumbH * .5f);
      thumbFillPaint.setColor(AppStyle.SLIDER_THUMB_BG);
      thumbFillPaint.setAlpha(Math.round(Color.alpha(AppStyle.SLIDER_THUMB_BG) * alphaScale));
      canvas.drawRoundRect(thumbRect, thumbR, thumbR, thumbFillPaint);

      thumbStrokePaint.setColor(AppStyle.SLIDER_THUMB_BORDER);
      thumbStrokePaint.setAlpha(Math.round(Color.alpha(AppStyle.SLIDER_THUMB_BORDER) * alphaScale));
      canvas.drawRoundRect(thumbRect, thumbR, thumbR, thumbStrokePaint);

      float needleW = dp(2f);
      float needleInsetY = dp(3.2f);
      needleRect.set(
          thumbCx - needleW * .5f,
          thumbRect.top + needleInsetY,
          thumbCx + needleW * .5f,
          thumbRect.bottom - needleInsetY);
      needlePaint.setColor(AppStyle.SLIDER_THUMB_MARK);
      needlePaint.setAlpha(Math.round(255f * alphaScale));
      canvas.drawRoundRect(needleRect, dp(1f), dp(1f), needlePaint);
    }
  }
}
