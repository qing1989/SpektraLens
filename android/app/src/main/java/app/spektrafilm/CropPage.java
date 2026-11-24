// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.Locale;

/**
 * Full-screen Precision Instrument crop page launched from the editor action bar.
 * Top bar strictly centers「裁切」on the 50% screen axis with「取消 / 完成」flanking it;
 * bottom deck combines 90° rotate, horizontal flip, reset, 6 aspect ratios, and straighten slider.
 */
@SuppressLint("ViewConstructor")
final class CropPage extends LinearLayout {
  interface Listener {
    void onApply(RectF crop, int turns, boolean flipH, float angleDeg);

    void onCancel();
  }

  private static final String[] RATIO_LABELS = {"自由", "原图", "1:1", "3:2", "4:5", "16:9"};

  private final Activity activity;
  private final CropEditor cropView;
  private final Listener listener;
  private final int fullWidth, fullHeight;
  private final float originalAspect;
  private final TextView dimPill, angleValue;
  private final TextView[] ratioChips = new TextView[RATIO_LABELS.length];
  private final AdjustmentSeekBar angleBar;
  private int selectedRatioIndex = 0;

  CropPage(
      Activity activity,
      Bitmap previewBitmap,
      int fullWidth,
      int fullHeight,
      RectF initialCrop,
      int initialTurns,
      boolean initialFlipH,
      float initialAngleDeg,
      Listener listener) {
    super(activity);
    this.activity = activity;
    this.listener = listener;
    this.fullWidth = Math.max(1, fullWidth);
    this.fullHeight = Math.max(1, fullHeight);
    this.originalAspect =
        previewBitmap != null && previewBitmap.getHeight() > 0
            ? previewBitmap.getWidth() / (float) previewBitmap.getHeight()
            : this.fullWidth / (float) this.fullHeight;

    setOrientation(VERTICAL);
    setBackgroundColor(AppStyle.BG);
    setTag("crop.page");
    setAccessibilityPaneTitle("裁切");
    setClickable(true);

    cropView =
        new CropEditor(
            activity, previewBitmap, initialCrop, initialTurns, initialFlipH, initialAngleDeg);
    cropView.setTag("crop.editor");

    // Strictly 50%-centered top bar: 取消 | 裁切 | 完成
    FrameLayout topBar = new FrameLayout(activity);
    topBar.setPadding(dp(12), dp(6), dp(12), dp(6));

    TextView cancel = text("取消", AppStyle.TYPE_BODY, AppStyle.MUTED);
    cancel.setTag("crop.cancel");
    cancel.setGravity(Gravity.CENTER);
    cancel.setPadding(dp(12), 0, dp(12), 0);
    AppStyle.pillAction(cancel, AppStyle.MUTED, Color.TRANSPARENT);
    AppStyle.setTypeface(cancel, AppStyle.FONT_MEDIUM);
    cancel.setOnClickListener(v -> listener.onCancel());
    topBar.addView(
        cancel, new FrameLayout.LayoutParams(-2, dp(42), Gravity.START | Gravity.CENTER_VERTICAL));

    TextView title = text("裁切", AppStyle.TYPE_TITLE, AppStyle.INK);
    title.setGravity(Gravity.CENTER);
    AppStyle.setTypeface(title, AppStyle.FONT_BOLD);
    title.setAccessibilityHeading(true);
    FrameLayout.LayoutParams titleParams =
        new FrameLayout.LayoutParams(-1, dp(42), Gravity.CENTER);
    titleParams.leftMargin = titleParams.rightMargin = dp(92);
    topBar.addView(title, titleParams);

    TextView done = text("完成", AppStyle.TYPE_BODY, AppStyle.ON_PRIMARY);
    done.setTag("crop.done");
    done.setGravity(Gravity.CENTER);
    done.setPadding(dp(14), 0, dp(14), 0);
    AppStyle.action(done, AppStyle.ON_PRIMARY, AppStyle.PRIMARY, 10f);
    AppStyle.setTypeface(done, AppStyle.FONT_BOLD);
    done.setOnClickListener(
        v ->
            listener.onApply(
                cropView.value(), cropView.turns(), cropView.flipH(), cropView.angleDeg()));
    topBar.addView(
        done, new FrameLayout.LayoutParams(-2, dp(34), Gravity.END | Gravity.CENTER_VERTICAL));

    addView(topBar, new LayoutParams(-1, dp(50)));

    // Center crop stage + live output dimensions pill
    FrameLayout stage = new FrameLayout(activity);
    stage.setBackgroundColor(AppStyle.STAGE_BG);
    stage.addView(cropView, new FrameLayout.LayoutParams(-1, -1));

    dimPill = text("", AppStyle.TYPE_MICRO, AppStyle.NOTICE_INK);
    dimPill.setTag("crop.dimensions");
    AppStyle.setNumericTypeface(dimPill);
    dimPill.setGravity(Gravity.CENTER);
    dimPill.setPadding(dp(12), dp(5), dp(12), dp(5));
    GradientDrawable pillBg = AppStyle.shape(activity, AppStyle.NOTICE_BG, 7f, false);
    pillBg.setStroke(Math.max(1, dp(.75f)), 0x26ffffff);
    dimPill.setBackground(pillBg);
    FrameLayout.LayoutParams pillParams =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    pillParams.bottomMargin = dp(10);
    stage.addView(dimPill, pillParams);

    addView(stage, new LayoutParams(-1, 0, 1));

    // Bottom Precision Instrument control deck
    LinearLayout deck = new LinearLayout(activity);
    deck.setOrientation(VERTICAL);
    deck.setBackgroundColor(AppStyle.SURFACE);
    deck.setPadding(dp(16), dp(12), dp(16), dp(18));

    View topHairline = new View(activity);
    topHairline.setBackgroundColor(AppStyle.OUTLINE);
    addView(topHairline, new LayoutParams(-1, Math.max(1, dp(.75f))));

    // Row 1: 旋转 90° | 水平翻转 | 重置构图
    LinearLayout actionsRow = new LinearLayout(activity);
    actionsRow.setOrientation(HORIZONTAL);
    actionsRow.setGravity(Gravity.CENTER_VERTICAL);
    View rotateBtn = actionChip(R.drawable.ic_crop_rotate, "旋转 90°", AppStyle.INK, cropView::rotate90);
    View flipBtn = actionChip(R.drawable.ic_crop_flip, "水平翻转", AppStyle.INK, cropView::toggleFlipH);
    View resetBtn =
        actionChip(
            0,
            "重置构图",
            AppStyle.MUTED,
            () -> {
              selectedRatioIndex = 0;
              updateRatioChips();
              cropView.resetAll();
              syncAngleSlider(0f);
              updateDimensions();
            });
    actionsRow.addView(rotateBtn, new LayoutParams(0, dp(38), 1));
    LayoutParams midP = new LayoutParams(0, dp(38), 1);
    midP.leftMargin = dp(8);
    actionsRow.addView(flipBtn, midP);
    LayoutParams endP = new LayoutParams(0, dp(38), 1);
    endP.leftMargin = dp(8);
    actionsRow.addView(resetBtn, endP);
    deck.addView(actionsRow, new LayoutParams(-1, -2));

    // Row 2: 6 Aspect ratio chips (自由 / 原图 / 1:1 / 3:2 / 4:5 / 16:9)
    LinearLayout ratioRow = new LinearLayout(activity);
    ratioRow.setOrientation(HORIZONTAL);
    ratioRow.setGravity(Gravity.CENTER_VERTICAL);
    LayoutParams ratioRowParams = new LayoutParams(-1, dp(34));
    ratioRowParams.topMargin = dp(10);
    for (int i = 0; i < RATIO_LABELS.length; i++) {
      final int idx = i;
      TextView chip = text(RATIO_LABELS[i], AppStyle.TYPE_MICRO, AppStyle.MUTED);
      chip.setGravity(Gravity.CENTER);
      chip.setSingleLine(true);
      chip.setClickable(true);
      chip.setFocusable(true);
      chip.setOnClickListener(
          v -> {
            selectedRatioIndex = idx;
            updateRatioChips();
            cropView.ratio(aspectForIndex(idx));
            updateDimensions();
          });
      ratioChips[i] = chip;
      LayoutParams cp = new LayoutParams(0, -1, 1);
      if (i > 0) cp.leftMargin = dp(5);
      ratioRow.addView(chip, cp);
    }
    deck.addView(ratioRow, ratioRowParams);

    // Row 3: Straighten vernier slider (-45° .. +45°, step 0.1°)
    LinearLayout angleHead = new LinearLayout(activity);
    angleHead.setOrientation(HORIZONTAL);
    angleHead.setGravity(Gravity.CENTER_VERTICAL);
    LayoutParams ahParams = new LayoutParams(-1, -2);
    ahParams.topMargin = dp(12);
    TextView angleLabel = text("拉直", AppStyle.TYPE_BODY, AppStyle.INK);
    AppStyle.setTypeface(angleLabel, AppStyle.FONT_MEDIUM);
    angleHead.addView(angleLabel, new LayoutParams(0, -2, 1));
    angleValue = text(formatAngle(initialAngleDeg), AppStyle.TYPE_LABEL, AppStyle.VALUE);
    AppStyle.setNumericTypeface(angleValue);
    angleHead.addView(angleValue, new LayoutParams(-2, -2));
    deck.addView(angleHead, ahParams);

    angleBar =
        new AdjustmentSeekBar(
            activity, new int[] {AppStyle.SLIDER_TRACK, AppStyle.SLIDER_TRACK});
    angleBar.setMax(900); // -45.0 .. +45.0 in 0.1° steps
    angleBar.setDetentProgress(450);
    angleBar.setContentDescription("拉直角度");
    syncAngleSlider(initialAngleDeg);
    angleBar.setOnResetListener(
        () -> {
          syncAngleSlider(0f);
          cropView.setAngle(0f);
          if (Haptics.enabled) {
            angleBar.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
          }
          updateDimensions();
        });
    angleBar.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          @Override
          public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (!fromUser) return;
            float deg = Math.round((progress - 450)) / 10f;
            angleValue.setText(formatAngle(deg));
            cropView.setAngle(deg);
          }

          @Override
          public void onStartTrackingTouch(SeekBar bar) {}

          @Override
          public void onStopTrackingTouch(SeekBar bar) {}
        });
    LayoutParams barParams = new LayoutParams(-1, dp(AppStyle.TOUCH_MIN));
    deck.addView(angleBar, barParams);

    addView(deck, new LayoutParams(-1, -2));

    cropView.setOnChangeListener(this::updateDimensions);
    updateRatioChips();
    updateDimensions();
  }

  void navigateBack() {
    listener.onCancel();
  }

  private float aspectForIndex(int index) {
    switch (index) {
      case 1:
        return originalAspect;
      case 2:
        return 1f;
      case 3:
        return 1.5f;
      case 4:
        return 0.8f;
      case 5:
        return 16f / 9f;
      default:
        return 0f;
    }
  }

  private void syncAngleSlider(float deg) {
    int progress = Math.max(0, Math.min(900, Math.round(deg * 10f) + 450));
    angleBar.setProgress(progress);
    angleValue.setText(formatAngle(deg));
  }

  private void updateRatioChips() {
    for (int i = 0; i < ratioChips.length; i++) {
      boolean active = i == selectedRatioIndex;
      TextView chip = ratioChips[i];
      chip.setSelected(active);
      chip.setTextColor(active ? AppStyle.GOLD : AppStyle.MUTED);
      AppStyle.setTypeface(chip, active ? AppStyle.FONT_SEMIBOLD : AppStyle.FONT_MEDIUM);
      GradientDrawable bg =
          AppStyle.shape(
              activity, active ? AppStyle.ACCENT_SOFT : AppStyle.CONTROL, AppStyle.R_SMALL, false);
      if (active) bg.setStroke(Math.max(1, dp(.85f)), AppStyle.GOLD);
      chip.setBackground(
          new RippleDrawable(
              ColorStateList.valueOf(AppStyle.RIPPLE),
              bg,
              AppStyle.shape(activity, Color.WHITE, AppStyle.R_SMALL, false)));
    }
  }

  private void updateDimensions() {
    int outW = cropView.outputWidth(fullWidth, fullHeight);
    int outH = cropView.outputHeight(fullWidth, fullHeight);
    String prefix = RATIO_LABELS[selectedRatioIndex];
    dimPill.setText(prefix + " · " + outW + " × " + outH);
  }

  private static String formatAngle(float deg) {
    if (Math.abs(deg) < 0.05f) return "0.0°";
    return String.format(Locale.US, "%+.1f°", deg);
  }

  private View actionChip(int iconRes, String label, int color, Runnable action) {
    LinearLayout btn = new LinearLayout(activity);
    btn.setOrientation(HORIZONTAL);
    btn.setGravity(Gravity.CENTER);
    btn.setClickable(true);
    btn.setFocusable(true);
    btn.setPadding(dp(8), 0, dp(8), 0);
    GradientDrawable bg = AppStyle.shape(activity, AppStyle.CONTROL, 10f, true);
    btn.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(AppStyle.RIPPLE),
            bg,
            AppStyle.shape(activity, Color.WHITE, 10f, false)));
    if (iconRes != 0) {
      ImageView icon = new ImageView(activity);
      icon.setImageResource(iconRes);
      icon.setImageTintList(ColorStateList.valueOf(color));
      icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
      LayoutParams ip = new LayoutParams(dp(15), dp(15));
      ip.rightMargin = dp(5);
      btn.addView(icon, ip);
    }
    TextView tv = text(label, AppStyle.TYPE_MICRO, color);
    AppStyle.setTypeface(tv, AppStyle.FONT_SEMIBOLD);
    tv.setSingleLine(true);
    btn.addView(tv, new LayoutParams(-2, -2));
    btn.setOnClickListener(v -> action.run());
    return btn;
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    AppStyle.setTypeface(view, AppStyle.FONT_REGULAR);
    return view;
  }

  private int dp(float value) {
    return AppStyle.dp(activity, value);
  }
}
