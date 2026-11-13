// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ExportSheet extends Dialog {
  interface Listener {
    void export(ExportOptions options);
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
  private volatile boolean closed;
  private final View.OnLayoutChangeListener windowChanged =
      (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (!closed && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop))
          resize();
      };
  private LinearLayout qualityPanel;
  private TextView lossless, description, size, sizeNote, share;
  private int format = ExportOptions.JPEG, quality = ExportOptions.RECOMMENDED;
  private boolean estimationFinished, submitted;

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
    AppStyle.action(button, color, fill, AppStyle.R_MEDIUM);
    return button;
  }

  private void gap(LinearLayout parent, int height) {
    parent.addView(new View(getContext()), new LinearLayout.LayoutParams(1, dp(height)));
  }

  private void build(String dimensions) {
    LinearLayout root = column();
    root.setPadding(dp(20), dp(12), dp(20), dp(12));
    AppStyle.surface(root, AppStyle.SURFACE, AppStyle.R_LARGE, false);

    LinearLayout header = new LinearLayout(getContext());
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = text("导出照片", AppStyle.TYPE_HEADING, AppStyle.INK);
    title.setTypeface(null, Typeface.BOLD);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    header.addView(
        action("取消", "export.cancel", AppStyle.MUTED, Color.TRANSPARENT, this::dismiss),
        new LinearLayout.LayoutParams(-2, -2));
    root.addView(header);
    TextView dimensionsText =
        text("原尺寸 · " + dimensions, AppStyle.TYPE_LABEL, AppStyle.MUTED);
    dimensionsText.setTag("export.dimensions");
    root.addView(dimensionsText);
    gap(root, 18);

    ScrollView scroll = new ScrollView(getContext());
    scroll.setClipToPadding(false);
    LinearLayout content = column();
    scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
    root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    content.addView(text("文件格式", AppStyle.TYPE_TITLE, AppStyle.INK));
    gap(content, 10);
    String[] formats = {"JPEG\n8 位", "PNG\n16 位", "TIFF\n16 位"};
    LinearLayout formatRail = rail(content);
    for (int index = 0; index < formats.length; index++) {
      int selected = index;
      TextView button =
          action(formats[index], "export.format." + index, AppStyle.INK, Color.TRANSPARENT,
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
    qualityPanel.addView(text("JPEG 画质", AppStyle.TYPE_TITLE, AppStyle.INK));
    gap(qualityPanel, 10);
    LinearLayout qualityRail = rail(qualityPanel);
    for (int index = 0; index < qualities.length; index++) {
      int selected = qualities[index];
      TextView button =
          action(ExportOptions.qualityLabel(selected), "export.quality." + selected,
              AppStyle.INK, Color.TRANSPARENT,
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
    gap(content, 22);
    content.addView(text("预计大小", AppStyle.TYPE_LABEL, AppStyle.MUTED));
    gap(content, 8);
    size = text("", AppStyle.TYPE_HEADING, AppStyle.VALUE);
    size.setTag("export.estimate");
    content.addView(size);
    gap(content, 8);
    sizeNote = text("", AppStyle.TYPE_LABEL, AppStyle.MUTED);
    sizeNote.setLineSpacing(dp(3), 1);
    content.addView(sizeNote);
    gap(content, 12);
    gap(root, 12);
    TextView save =
        action("保存到相册", "export.save", AppStyle.BG, AppStyle.ACCENT, () -> submit(false));
    save.setMinHeight(dp(AppStyle.TOUCH_PRIMARY));
    root.addView(save, new LinearLayout.LayoutParams(-1, -2));
    share =
        action("分享 JPEG", "export.share", AppStyle.MUTED, Color.TRANSPARENT, () -> submit(true));
    root.addView(share, new LinearLayout.LayoutParams(-1, -2));
    setContentView(root);
    refresh();
  }

  private LinearLayout rail(LinearLayout parent) {
    LinearLayout rail = new LinearLayout(getContext());
    rail.setPadding(dp(4), dp(4), dp(4), dp(4));
    AppStyle.surface(rail, AppStyle.CONTROL, AppStyle.R_MEDIUM, false);
    parent.addView(rail, new LinearLayout.LayoutParams(-1, -2));
    return rail;
  }

  private void choice(LinearLayout rail, TextView button) {
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
    for (int index = 0; index < qualities.length; index++)
      if (qualities[index] == quality) range = estimates[index];
    if (!jpeg) {
      size.setText("以实际导出为准");
      sizeNote.setText("无损输出，文件通常较大");
    } else if (range == null) {
      size.setText(estimationFinished ? "以实际导出为准" : "估算中…");
      sizeNote.setText("可以直接保存，无需等待估算");
    } else {
      double unit = range[1] < 1e6 ? 1e3 : 1e6;
      size.setText(
          String.format(
              Locale.CHINA, "约 %.1f–%.1f %s", range[0] / unit, range[1] / unit,
              unit == 1e3 ? "KB" : "MB"));
      sizeNote.setText("根据当前预览粗略估算；颗粒、纹理会影响体积，实际以导出为准");
    }
  }

  private void selected(TextView button, boolean selected) {
    if (button.isSelected() == selected) return;
    button.setSelected(selected);
    AppStyle.action(
        button, selected ? AppStyle.ACCENT : AppStyle.INK,
        selected ? AppStyle.SELECTED : Color.TRANSPARENT, AppStyle.R_MEDIUM);
  }

  private void submit(boolean sharing) {
    if (closed || submitted || sharing && format != ExportOptions.JPEG) return;
    submitted = true;
    ExportOptions options =
        new ExportOptions(sharing ? ExportOptions.SHARE_JPEG : format, quality);
    dismiss();
    listener.export(options);
  }

  private void estimate(Bitmap preview, int outputWidth, int outputHeight) {
    Bitmap snapshot = null;
    try {
      if (preview != null && !preview.isRecycled() && outputWidth > 0 && outputHeight > 0) {
        double scale = Math.min(1, 768.0 / Math.max(preview.getWidth(), preview.getHeight()));
        int width = Math.max(1, (int) Math.round(preview.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(preview.getHeight() * scale));
        snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(snapshot);
        canvas.drawColor(Color.BLACK);
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
    Bitmap owned = snapshot;
    estimator.execute(
        () -> {
          long[][] results = new long[qualities.length][];
          try {
            for (int index = 0; index < qualities.length && !closed; index++) {
              ByteCounter counter = new ByteCounter();
              if (owned.compress(Bitmap.CompressFormat.JPEG, qualities[index], counter))
                results[index] =
                    ExportOptions.estimateJpegBytes(
                        counter.count, owned.getWidth(), owned.getHeight(), outputWidth, outputHeight);
            }
          } catch (RuntimeException | OutOfMemoryError error) {
            android.util.Log.w("SpektraLensExport", "Size estimate unavailable", error);
          } finally {
            Bitmaps.release(owned);
          }
          if (closed) return;
          main.post(
              () -> {
                if (closed) return;
                System.arraycopy(results, 0, estimates, 0, results.length);
                estimationFinished = true;
                refresh();
              });
        });
    estimator.shutdown();
  }

  @Override
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
    getWindow().setLayout(
        Math.min(dp(560), Math.max(dp(200), width - dp(24))),
        Math.min(Math.max(dp(240), height - dp(20)), dp(format == ExportOptions.JPEG ? 560 : 460)));
  }

  @Override
  protected void onStop() {
    closed = true;
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
