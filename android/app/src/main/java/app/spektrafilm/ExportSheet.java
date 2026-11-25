// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;

final class ExportSheet extends Dialog {
  interface Listener {
    boolean export(ExportOptions options);
  }

  private final Activity activity;
  private final Listener listener;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService estimator = Executors.newSingleThreadExecutor();
  private final TextView[] formatButtons = new TextView[3], qualityButtons = new TextView[3];
  private final int[] qualities = {
    ExportOptions.HIGH, ExportOptions.RECOMMENDED, ExportOptions.COMPACT
  };
  private final long[][] estimates = new long[3][];
  private final long[] jpegSampleBytes = new long[3];
  private long[] pngEstimate;
  private long tiffBytes;
  private int sampleWidth, sampleHeight, outputWidth, outputHeight;
  private double pngRatio;
  private volatile boolean closed;
  private final View.OnLayoutChangeListener windowChanged =
      (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (!closed && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop))
          resize();
      };
  private LinearLayout root, header, qualityPanel, idleFooter, progressFooter;
  private LinearLayout content;
  private TextView dimensionsText;
  private TextView lossless, description, size, sizeNote, share, cancelButton, progressLabel;
  private TextView progressMeta;
  private View progressBar, progressDot;
  private ImportProgressDrawable progressDrawable;
  private ValueAnimator progressAnimator, dotAnimator;
  private float progressFraction;
  private int format = ExportOptions.JPEG, quality = ExportOptions.RECOMMENDED;
  private boolean estimationFinished, submitted, exporting;

  ExportSheet(
      Activity activity, String dimensions, int outputWidth, int outputHeight,
      Bitmap preview, Listener listener) {
    super(activity);
    this.activity = activity;
    this.listener = listener;
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    Haptics.install(getWindow());
    setCanceledOnTouchOutside(true);
    build(dimensions);
    estimate(preview, outputWidth, outputHeight);
  }

  private int dp(float value) {
    return AppStyle.dp(getContext(), value);
  }

  private LinearLayout column() {
    LinearLayout column = new LinearLayout(getContext());
    column.setOrientation(LinearLayout.VERTICAL);
    return column;
  }

  private TextView text(String label, int type, int color) {
    TextView text = new TextView(getContext());
    text.setText(label);
    text.setTextSize(type);
    text.setTextColor(color);
    text.setIncludeFontPadding(false);
    AppStyle.setTypeface(text, AppStyle.FONT_REGULAR);
    return text;
  }

  private TextView action(String label, String tag, int color, int fill, Runnable action) {
    TextView button = text(label, AppStyle.TYPE_TITLE, color);
    button.setTag(tag);
    button.setSingleLine(true);
    button.setGravity(Gravity.CENTER);
    button.setMinHeight(dp(AppStyle.TOUCH_MIN));
    button.setPadding(dp(12), dp(12), dp(12), dp(12));
    button.setFocusable(true);
    button.setOnClickListener(view -> action.run());
    AppStyle.pillAction(button, color, fill);
    return button;
  }

  private void gap(LinearLayout parent, int height) {
    parent.addView(new View(getContext()), new LinearLayout.LayoutParams(1, dp(height)));
  }

  private void build(String dimensions) {
    root = column();
    root.setPadding(dp(18), dp(16), dp(18), dp(18));
    root.setBackground(AppStyle.topRounded(getContext(), AppStyle.SURFACE, AppStyle.R_LARGE, true));
    root.setClipToOutline(true);

    header = new LinearLayout(getContext());
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = text("导出照片", AppStyle.TYPE_HEADING, AppStyle.INK);
    AppStyle.setTypeface(title, AppStyle.FONT_BOLD);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    cancelButton =
        action("取消", "export.cancel", AppStyle.MUTED, Color.TRANSPARENT, this::dismiss);
    cancelButton.setTextSize(AppStyle.TYPE_BODY);
    AppStyle.setTypeface(cancelButton, AppStyle.FONT_SEMIBOLD);
    header.addView(cancelButton, new LinearLayout.LayoutParams(-2, -2));
    root.addView(header);
    dimensionsText = text("原尺寸 · " + dimensions, AppStyle.TYPE_MICRO, AppStyle.MUTED);
    AppStyle.setNumericTypeface(dimensionsText);
    dimensionsText.setTag("export.dimensions");
    root.addView(dimensionsText);

    progressFooter = column();
    progressFooter.setTag("export.progress");
    progressFooter.setVisibility(View.GONE);
    progressFooter.setPadding(0, dp(12), 0, 0);
    LinearLayout progressStatusRow = new LinearLayout(getContext());
    progressStatusRow.setOrientation(LinearLayout.HORIZONTAL);
    progressStatusRow.setGravity(Gravity.CENTER_VERTICAL);
    progressDot = new View(getContext());
    progressDot.setTag("export.progress.dot");
    progressDot.setBackground(buildProgressDotDrawable());
    LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(12), dp(12));
    dotParams.rightMargin = dp(5);
    progressStatusRow.addView(progressDot, dotParams);
    progressLabel = text("正在导出照片…", AppStyle.TYPE_BODY, AppStyle.INK);
    AppStyle.setTypeface(progressLabel, AppStyle.FONT_MEDIUM);
    progressLabel.setTag("export.progress.label");
    progressStatusRow.addView(progressLabel, new LinearLayout.LayoutParams(0, -2, 1));
    progressMeta = text("", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    AppStyle.setTypeface(progressMeta, AppStyle.FONT_MEDIUM);
    AppStyle.setNumericTypeface(progressMeta);
    progressMeta.setTag("export.progress.meta");
    progressStatusRow.addView(progressMeta, new LinearLayout.LayoutParams(-2, -2));
    progressFooter.addView(progressStatusRow, new LinearLayout.LayoutParams(-1, -2));
    gap(progressFooter, 8);
    progressBar = new View(getContext());
    progressBar.setTag("export.progress.bar");
    progressDrawable = ImportProgressDrawable.compactTrack(getContext());
    progressBar.setBackground(progressDrawable);
    progressBar.setContentDescription("导出进度");
    progressFooter.addView(progressBar, new LinearLayout.LayoutParams(-1, dp(6)));
    root.addView(progressFooter);

    gap(root, 6);

    ScrollView scroll = new ScrollView(getContext());
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    content = column();
    content.setPadding(0, dp(12), 0, dp(12));
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
    root.addView(
        AppStyle.wrapScrollFade(getContext(), scroll, AppStyle.SURFACE, 26, 26, false),
        new LinearLayout.LayoutParams(-1, 0, 1));
    content.addView(heading("文件格式"));
    gap(content, 10);
    String[] formats = {"JPEG\n8 位", "PNG\n16 位", "TIFF\n16 位"};
    LinearLayout formatRail = rail(content);
    for (int index = 0; index < formats.length; index++) {
      int selected = index;
      TextView button =
          action(formats[index], "export.format." + index, AppStyle.MUTED, Color.TRANSPARENT,
              () -> {
                format = selected;
                refresh();
                resize();
              });
      button.setSingleLine(false);
      button.setGravity(Gravity.CENTER);
      button.setContentDescription(formats[index].replace('\n', ' '));
      formatButtons[index] = button;
      choice(formatRail, button);
    }
    adaptiveChoices(formatRail, formatButtons);
    gap(content, 20);

    qualityPanel = column();
    qualityPanel.setTag("export.quality");
    qualityPanel.addView(heading("JPEG 画质"));
    gap(qualityPanel, 10);
    LinearLayout qualityRail = rail(qualityPanel);
    for (int index = 0; index < qualities.length; index++) {
      int selected = qualities[index];
      TextView button =
          action(ExportOptions.qualityLabel(selected), "export.quality." + selected,
              AppStyle.MUTED, Color.TRANSPARENT,
              () -> {
                quality = selected;
                refresh();
              });
      button.setTextSize(AppStyle.TYPE_BODY);
      qualityButtons[index] = button;
      choice(qualityRail, button);
    }
    adaptiveChoices(qualityRail, qualityButtons);
    gap(qualityPanel, 10);
    description = text("", AppStyle.TYPE_BODY, AppStyle.MUTED);
    qualityPanel.addView(description);
    gap(qualityPanel, 8);
    qualityPanel.addView(
        text("仅调整编码画质，像素尺寸保持不变", AppStyle.TYPE_LABEL, AppStyle.MUTED));
    content.addView(qualityPanel);

    lossless = text("无损输出\n保留 16 位色深，适合后续编辑", AppStyle.TYPE_BODY, AppStyle.MUTED);
    lossless.setTag("export.lossless");
    lossless.setLineSpacing(dp(6), 1);
    content.addView(lossless);
    gap(content, 16);
    LinearLayout estBox = column();
    estBox.setPadding(dp(14), dp(12), dp(14), dp(12));
    estBox.setBackground(AppStyle.shape(getContext(), AppStyle.CONTROL, AppStyle.R_MEDIUM, true));
    estBox.addView(text("预计大小", AppStyle.TYPE_MICRO, AppStyle.MUTED));
    gap(estBox, 4);
    size = text("", 20, AppStyle.VALUE);
    AppStyle.setNumericTypeface(size);
    AppStyle.setTypeface(size, AppStyle.FONT_BOLD);
    size.setTag("export.estimate");
    estBox.addView(size);
    gap(estBox, 4);
    sizeNote = text("", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    AppStyle.setNumericTypeface(sizeNote);
    sizeNote.setLineSpacing(dp(3), 1);
    estBox.addView(sizeNote);
    content.addView(estBox, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 10);

    idleFooter = column();
    idleFooter.setPadding(0, dp(12), 0, 0);
    idleFooter.setTag("export.actions");
    TextView save =
        action(
            "保存到相册",
            "export.save",
            AppStyle.ON_PRIMARY,
            AppStyle.PRIMARY,
            () -> submit(false));
    AppStyle.setTypeface(save, AppStyle.FONT_BOLD);
    save.setMinHeight(dp(AppStyle.TOUCH_PRIMARY));
    idleFooter.addView(save, new LinearLayout.LayoutParams(-1, -2));
    share =
        action("分享 JPEG", "export.share", AppStyle.MUTED, Color.TRANSPARENT, () -> submit(true));
    idleFooter.addView(share, new LinearLayout.LayoutParams(-1, -2));
    root.addView(idleFooter);

    setContentView(root);
    refresh();
  }

  private LinearLayout rail(LinearLayout parent) {
    LinearLayout rail = new LinearLayout(getContext());
    rail.setPadding(dp(4), dp(4), dp(4), dp(4));
    AppStyle.surface(rail, AppStyle.CONTROL, AppStyle.R_MEDIUM, true);
    parent.addView(rail, new LinearLayout.LayoutParams(-1, -2));
    return rail;
  }

  private TextView heading(String value) {
    TextView view = text(value, AppStyle.TYPE_MICRO, AppStyle.MUTED);
    AppStyle.setTypeface(view, AppStyle.FONT_SEMIBOLD);
    return view;
  }

  private void choice(LinearLayout rail, TextView button) {
    // These nested segments are rounded rectangles in the mock, unlike the standalone CTAs.
    AppStyle.action(button, AppStyle.MUTED, Color.TRANSPARENT, AppStyle.R_MEDIUM);
    button.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.RadioButton");
            info.setCheckable(true);
            info.setChecked(host.isSelected());
          }
        });
    rail.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
  }

  private void adaptiveChoices(LinearLayout rail, TextView[] buttons) {
    rail.addOnLayoutChangeListener(
        (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          float widest = 0;
          for (TextView button : buttons)
            for (String line : button.getText().toString().split("\n"))
              widest = Math.max(widest, button.getPaint().measureText(line) + dp(24));
          int available = right - left - rail.getPaddingLeft() - rail.getPaddingRight();
          int orientation =
              widest * buttons.length > available ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL;
          if (available <= 0 || rail.getOrientation() == orientation) return;
          rail.setOrientation(orientation);
          for (TextView button : buttons)
            button.setLayoutParams(
                orientation == LinearLayout.HORIZONTAL
                    ? new LinearLayout.LayoutParams(0, -2, 1)
                    : new LinearLayout.LayoutParams(-1, -2));
        });
  }

  private void refresh() {
    for (int index = 0; index < formatButtons.length; index++)
      selected(formatButtons[index], index == format);
    for (int index = 0; index < qualityButtons.length; index++)
      selected(qualityButtons[index], qualities[index] == quality);
    boolean jpeg = format == ExportOptions.JPEG;
    qualityPanel.setVisibility(jpeg ? View.VISIBLE : View.GONE);
    lossless.setVisibility(jpeg ? View.GONE : View.VISIBLE);
    share.setVisibility(jpeg ? View.VISIBLE : View.GONE);
    description.setText(ExportOptions.qualityDescription(quality));
    long[] range = null;
    String note;
    if (format == ExportOptions.JPEG) {
      for (int index = 0; index < qualities.length; index++)
        if (qualities[index] == quality) range = estimates[index];
      note = "根据当前预览估算；颗粒、纹理会让实际大小有约 ±20% 波动";
    } else if (format == ExportOptions.PNG) {
      range = pngEstimate;
      note = "压缩率随画面内容变化，实际可能相差约 ±20%";
    } else {
      range = tiffBytes > 0 ? new long[] {tiffBytes, tiffBytes} : null;
      note = "未压缩，文件大小基本固定";
    }
    if (range == null) {
      size.setText(estimationFinished ? "以实际导出为准" : "估算中…");
      sizeNote.setText("可以直接保存，无需等待估算");
    } else {
      size.setText(formatRange(range));
      sizeNote.setText(note);
    }
  }

  private String formatRange(long[] range) {
    long low = range[0], high = range[1];
    long unit = Math.max(low, high) < 1_000_000 ? 1_000 : 1_000_000;
    String suffix = unit == 1_000 ? "KB" : "MB";
    if (high <= low * 1.02)
      return String.format(Locale.CHINA, "约 %.1f %s", (low + high) / 2.0 / unit, suffix);
    return String.format(
        Locale.CHINA, "约 %.1f–%.1f %s", low / (double) unit, high / (double) unit, suffix);
  }

  private void selected(TextView button, boolean selected) {
    button.setSelected(selected);
    if (selected) {
      android.graphics.drawable.GradientDrawable bg =
          AppStyle.shape(getContext(), AppStyle.SELECTED, AppStyle.R_SMALL, false);
      bg.setStroke(Math.max(1, dp(.95f)), AppStyle.GOLD);
      button.setBackground(
          new android.graphics.drawable.RippleDrawable(
              android.content.res.ColorStateList.valueOf(AppStyle.RIPPLE),
              bg,
              AppStyle.shape(getContext(), Color.WHITE, AppStyle.R_SMALL, false)));
      button.setTextColor(AppStyle.INK);
    } else {
      AppStyle.action(button, AppStyle.MUTED, Color.TRANSPARENT, AppStyle.R_SMALL);
    }
    AppStyle.setTypeface(
        button, selected ? AppStyle.FONT_SEMIBOLD : AppStyle.FONT_MEDIUM);
    String raw = button.getText().toString();
    int newline = raw.indexOf('\n');
    if (newline > 0) {
      android.text.SpannableStringBuilder styled = new android.text.SpannableStringBuilder(raw);
      styled.setSpan(
          new android.text.style.ForegroundColorSpan(selected ? AppStyle.INK : AppStyle.MUTED),
          0,
          newline,
          android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      styled.setSpan(
          new android.text.style.ForegroundColorSpan(selected ? AppStyle.GOLD : AppStyle.MUTED),
          newline + 1,
          raw.length(),
          android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      styled.setSpan(
          new android.text.style.RelativeSizeSpan(.82f),
          newline + 1,
          raw.length(),
          android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      button.setText(styled);
      button.setFontFeatureSettings("tnum");
    }
  }

  private void submit(boolean sharing) {
    if (closed || submitted || sharing && format != ExportOptions.JPEG) return;
    submitted = true;
    ExportOptions options =
        new ExportOptions(sharing ? ExportOptions.SHARE_JPEG : format, quality);
    beginExport(sharing);
    if (!listener.export(options)) dismiss();
  }

  private android.graphics.drawable.Drawable buildProgressDotDrawable() {
    boolean light = AppStyle.isLight();
    android.graphics.drawable.GradientDrawable halo = new android.graphics.drawable.GradientDrawable();
    halo.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    halo.setColor(AppStyle.withAlpha(AppStyle.GOLD, light ? .15f : .22f));
    android.graphics.drawable.GradientDrawable core = new android.graphics.drawable.GradientDrawable();
    core.setShape(android.graphics.drawable.GradientDrawable.OVAL);
    core.setColor(AppStyle.GOLD);
    android.graphics.drawable.LayerDrawable layers =
        new android.graphics.drawable.LayerDrawable(
            new android.graphics.drawable.Drawable[] {halo, core});
    int inset = dp(3);
    layers.setLayerInset(1, inset, inset, inset, inset);
    return layers;
  }

  private String activeSpecSummary() {
    if (format == ExportOptions.PNG) return "PNG · 16 位";
    if (format == ExportOptions.TIFF) return "TIFF · 16 位";
    return "JPEG · " + ExportOptions.qualityLabel(quality);
  }

  private void startDotPulse() {
    stopDotPulse();
    if (progressDot == null) return;
    progressDot.setVisibility(View.VISIBLE);
    progressDot.setAlpha(1f);
    if (!ValueAnimator.areAnimatorsEnabled()) return;
    dotAnimator = ValueAnimator.ofFloat(1f, .42f);
    dotAnimator.setDuration(800);
    dotAnimator.setRepeatMode(ValueAnimator.REVERSE);
    dotAnimator.setRepeatCount(ValueAnimator.INFINITE);
    dotAnimator.addUpdateListener(a -> progressDot.setAlpha((float) a.getAnimatedValue()));
    dotAnimator.start();
  }

  private void stopDotPulse() {
    if (dotAnimator != null) {
      dotAnimator.cancel();
      dotAnimator = null;
    }
    if (progressDot != null) progressDot.setAlpha(1f);
  }

  private void beginExport(boolean sharing) {
    exporting = true;
    setCancelable(false);
    setCanceledOnTouchOutside(false);
    cancelButton.setText("请稍候");
    cancelButton.setTextSize(AppStyle.TYPE_LABEL);
    cancelButton.setTextColor(AppStyle.MUTED);
    cancelButton.setOnClickListener(null);
    cancelButton.setClickable(false);
    cancelButton.setFocusable(false);
    for (TextView button : formatButtons) button.setEnabled(false);
    for (TextView button : qualityButtons) button.setEnabled(false);
    content.setAlpha(.45f);
    idleFooter.setVisibility(View.GONE);
    progressFooter.setVisibility(View.VISIBLE);
    progressLabel.setText(sharing ? "正在准备分享…" : "正在导出照片…");
    progressMeta.setText(activeSpecSummary());
    startDotPulse();
    resize();
    progressFraction = 0f;
    progressDrawable.setFraction(0f);
    progressDrawable.setAnimating(true);
    setProgress(0.04f, 350);
  }

  /** Re-enter the exporting state when the user accepts a reduced-size retry. */
  void retryExport(boolean sharing) {
    if (closed) return;
    submitted = true;
    beginExport(sharing);
  }

  void setProgress(float fraction) {
    setProgress(fraction, 0);
  }

  void setProgress(float fraction, long durationMs) {
    if (closed || !exporting) return;
    float target = Math.max(0f, Math.min(1f, fraction));
    if (progressAnimator != null) {
      progressAnimator.cancel();
      progressAnimator = null;
    }
    if (durationMs <= 0) {
      progressFraction = target;
      progressDrawable.setFraction(target);
      return;
    }
    progressAnimator = ValueAnimator.ofFloat(progressFraction, target);
    progressAnimator.setDuration(durationMs);
    progressAnimator.setInterpolator(new LinearInterpolator());
    progressAnimator.addUpdateListener(
        animation -> {
          progressFraction = (float) animation.getAnimatedValue();
          progressDrawable.setFraction(progressFraction);
        });
    progressAnimator.start();
  }

  void finishExport() {
    if (closed) return;
    if (progressAnimator != null) {
      progressAnimator.cancel();
      progressAnimator = null;
    }
    stopDotPulse();
    if (progressDot != null) progressDot.setVisibility(View.GONE);
    progressLabel.setText("导出完成");
    progressFraction = 1f;
    progressDrawable.setFraction(1f);
    progressDrawable.setAnimating(false);
    dismiss();
  }

  void failExport() {
    if (closed || !exporting) return;
    exporting = false;
    if (progressAnimator != null) {
      progressAnimator.cancel();
      progressAnimator = null;
    }
    stopDotPulse();
    progressDrawable.setAnimating(false);
    progressFraction = 0f;
    progressDrawable.setFraction(0f);
    progressFooter.setVisibility(View.GONE);
    idleFooter.setVisibility(View.VISIBLE);
    content.setAlpha(1f);
    cancelButton.setText("取消");
    cancelButton.setTextSize(AppStyle.TYPE_TITLE);
    cancelButton.setTextColor(AppStyle.MUTED);
    cancelButton.setOnClickListener(view -> dismiss());
    cancelButton.setClickable(true);
    cancelButton.setFocusable(true);
    for (TextView button : formatButtons) button.setEnabled(true);
    for (TextView button : qualityButtons) button.setEnabled(true);
    setCancelable(true);
    setCanceledOnTouchOutside(true);
    submitted = false;
    resize();
  }

  private void estimate(Bitmap preview, int outputWidth, int outputHeight) {
    this.outputWidth = outputWidth;
    this.outputHeight = outputHeight;
    rebuildEstimates();
    Bitmap snapshot = null;
    try {
      if (preview != null && !preview.isRecycled() && outputWidth > 0 && outputHeight > 0) {
        double scale = Math.min(1, 1024.0 / Math.max(preview.getWidth(), preview.getHeight()));
        int width = Math.max(1, (int) Math.round(preview.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(preview.getHeight() * scale));
        snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(snapshot);
        canvas.drawColor(preview.hasAlpha() ? FrameStyle.BACKING_COLOR : Color.BLACK);
        canvas.drawBitmap(
            preview, null, new Rect(0, 0, width, height),
            new Paint(Paint.FILTER_BITMAP_FLAG));
      }
    } catch (RuntimeException | OutOfMemoryError error) {
      Bitmaps.release(snapshot);
      snapshot = null;
    }
    if (snapshot == null) {
      estimationFinished = true;
      refresh();
      estimator.shutdown();
      return;
    }
    final int snapshotWidth = snapshot.getWidth(), snapshotHeight = snapshot.getHeight();
    Bitmap owned = snapshot;
    estimator.execute(
        () -> {
          long[] samples = new long[qualities.length];
          double png = 0;
          try {
            for (int index = 0; index < qualities.length && !closed; index++) {
              ByteCounter counter = new ByteCounter();
              if (owned.compress(Bitmap.CompressFormat.JPEG, qualities[index], counter))
                samples[index] = counter.count;
            }
            if (!closed) png = estimatePngRatio(owned);
          } catch (RuntimeException | OutOfMemoryError error) {
            android.util.Log.w("SpektraLensExport", "Size estimate unavailable", error);
          } finally {
            Bitmaps.release(owned);
          }
          if (closed) return;
          final long[] sampleResult = samples;
          final double pngResult = png;
          main.post(
              () -> {
                if (closed) return;
                System.arraycopy(sampleResult, 0, jpegSampleBytes, 0, sampleResult.length);
                sampleWidth = snapshotWidth;
                sampleHeight = snapshotHeight;
                pngRatio = pngResult;
                rebuildEstimates();
                estimationFinished = true;
                refresh();
              });
        });
    estimator.shutdown();
  }

  private void rebuildEstimates() {
    tiffBytes = ExportOptions.estimateTiffBytes(outputWidth, outputHeight);
    for (int index = 0; index < qualities.length; index++) {
      estimates[index] =
          jpegSampleBytes[index] > 0 && sampleWidth > 0 && sampleHeight > 0
              ? ExportOptions.estimateJpegBytes(
                  jpegSampleBytes[index], sampleWidth, sampleHeight, outputWidth, outputHeight)
              : null;
    }
    pngEstimate =
        pngRatio > 0
            ? ExportOptions.estimatePngBytes(pngRatio, outputWidth, outputHeight)
            : null;
  }

  void setOutputSize(String label, int width, int height) {
    if (closed) return;
    if (dimensionsText != null) dimensionsText.setText(label);
    outputWidth = width;
    outputHeight = height;
    rebuildEstimates();
    refresh();
  }

  /** Mirrors the native PNG16 encoder: filter byte 0, RGB 16-bit, zlib level 3. */
  private double estimatePngRatio(Bitmap source) {
    Bitmap sample = null;
    Deflater deflater = null;
    try {
      double scale = Math.min(1, 512.0 / Math.max(source.getWidth(), source.getHeight()));
      int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
      int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
      sample = Bitmap.createScaledBitmap(source, width, height, true);
      int[] pixels = new int[width * height];
      sample.getPixels(pixels, 0, width, 0, 0, width, height);
      int rowBytes = width * 6 + 1;
      byte[] raw = new byte[rowBytes * height];
      for (int y = 0; y < height; y++) {
        int row = y * rowBytes;
        raw[row] = 0;
        for (int x = 0; x < width; x++) {
          int color = pixels[y * width + x];
          int p = row + 1 + x * 6;
          int r = (color >> 16) & 255, g = (color >> 8) & 255, b = color & 255;
          raw[p] = (byte) r;
          raw[p + 1] = (byte) r;
          raw[p + 2] = (byte) g;
          raw[p + 3] = (byte) g;
          raw[p + 4] = (byte) b;
          raw[p + 5] = (byte) b;
        }
      }
      deflater = new Deflater(3);
      deflater.setInput(raw);
      deflater.finish();
      byte[] buffer = new byte[32 * 1024];
      long compressed = 0;
      while (!deflater.finished()) {
        int count = deflater.deflate(buffer);
        if (count <= 0) break;
        compressed += count;
      }
      return compressed > 0 ? compressed / (double) raw.length : 0;
    } catch (RuntimeException | OutOfMemoryError error) {
      return 0;
    } finally {
      if (deflater != null) deflater.end();
      if (sample != null && sample != source) Bitmaps.release(sample);
    }
  }

  @Override
  public void show() {
    super.show();
    Window window = getWindow();
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    window.setGravity(Gravity.BOTTOM);
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    WindowManager.LayoutParams attributes = window.getAttributes();
    attributes.dimAmount = AppStyle.DIM_AMOUNT;
    attributes.y = 0;
    window.setAttributes(attributes);
    activity.getWindow().getDecorView().addOnLayoutChangeListener(windowChanged);
    resize();
  }

  private void resize() {
    if (!isShowing()) return;
    Rect visible = new Rect();
    activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
    int width = visible.width(), height = visible.height();
    if (width <= 0 || height <= 0) {
      width = getContext().getResources().getDisplayMetrics().widthPixels;
      height = getContext().getResources().getDisplayMetrics().heightPixels - dp(48);
    }
    int exportMaxDp = FoldableLayout.exportSheetMaxDp(FoldableUi.windowWidthDp(activity));
    int windowWidth =
        FoldableUi.constrainedWidthPx(getContext(), Math.max(dp(200), width), exportMaxDp, 0);
    int maxHeight =
        Math.min(Math.max(dp(240), height - dp(20)), dp(format == ExportOptions.JPEG ? 560 : 460));
    int windowHeight = maxHeight;
    if (exporting
        && header != null
        && dimensionsText != null
        && progressFooter != null
        && content != null) {
      int contentWidth = Math.max(1, windowWidth - dp(40));
      int exactWidth = View.MeasureSpec.makeMeasureSpec(contentWidth, View.MeasureSpec.EXACTLY);
      int unspecifiedHeight = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
      header.measure(exactWidth, unspecifiedHeight);
      dimensionsText.measure(exactWidth, unspecifiedHeight);
      progressFooter.measure(exactWidth, unspecifiedHeight);
      content.measure(exactWidth, unspecifiedHeight);
      int natural =
          dp(12)
              + header.getMeasuredHeight()
              + dimensionsText.getMeasuredHeight()
              + progressFooter.getMeasuredHeight()
              + dp(18)
              + content.getMeasuredHeight()
              + dp(12);
      windowHeight = Math.max(dp(240), Math.min(maxHeight, natural));
    }
    getWindow().setLayout(windowWidth, windowHeight);
  }

  @Override
  protected void onStop() {
    closed = true;
    if (progressAnimator != null) {
      progressAnimator.cancel();
      progressAnimator = null;
    }
    stopDotPulse();
    if (progressDrawable != null) progressDrawable.setAnimating(false);
    estimator.shutdown();
    main.removeCallbacksAndMessages(null);
    activity.getWindow().getDecorView().removeOnLayoutChangeListener(windowChanged);
    super.onStop();
  }

  private static final class ByteCounter extends OutputStream {
    long count;

    @Override
    public void write(int value) {
      count++;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) {
      count += length;
    }
  }
}
