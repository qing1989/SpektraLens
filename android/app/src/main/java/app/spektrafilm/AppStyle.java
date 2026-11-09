// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;

/** Shared C-direction surfaces and controls, drawn natively without blurring photographs. */
final class AppStyle {
  static final int BG = 0xff000000,
      SURFACE = 0xff222225,
      CONTROL = 0xff2d2d32,
      SELECTED = 0xff3b3b41,
      FIELD = 0xff1b1b1e,
      OUTLINE = 0xff39393f,
      INK = 0xfff4f4f6,
      MUTED = 0xffa1a1aa,
      ACCENT = 0xffff7a1a,
      GRIP = 0xff74747e,
      DISABLED = 0xff8a8a93,
      VALUE = 0xffd4d4d9,
      // 曲线通道色：只用于曲线图与图例，别拿去当正文色。
      CURVE_R = 0xfff28179,
      CURVE_G = 0xff99c786,
      CURVE_B = 0xff85baff;

  // 字号阶梯。新增界面只从这六档里选，别再插 12.5 这种中间值。
  // 2026-11-09 整体上调一档：小字 10→11、标签 11→12、正文 12→13、标题 14→15、板块 16→18、品牌 28→30。
  static final int TYPE_MICRO = 11, TYPE_LABEL = 12, TYPE_BODY = 13,
      TYPE_TITLE = 15, TYPE_HEADING = 18, TYPE_BRAND = 30;

  // 圆角阶梯：小控件 10、卡片 22、面板与弹层 26、胶囊容器 28。按钮一律走胶囊（半高）。
  static final float R_CONTROL = 10, R_CARD = 22, R_SHEET = 26, R_PILL = 28;

  // 触控基准：主操作 48、常规 44。低于 44 的只留给纯展示元素。
  static final int TOUCH_PRIMARY = 48, TOUCH_MIN = 44;

  // 传给 action() 表示「做成胶囊」；给具体数值就按该半径的圆角矩形画。
  // 注意和上面圆角阶梯里的 R_PILL = 28（胶囊容器的圆角）区分：那个是画形状用的数值。
  static final float R_CAPSULE = 999f;

  /** Same colour at a different opacity, for wells and overlays. */
  static int withAlpha(int color, float alpha) {
    return (color & 0x00ffffff) | (Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24);
  }

  static int dp(Context context, float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  static GradientDrawable shape(Context context, int color, float radius, boolean outline) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(context, radius));
    if (outline) drawable.setStroke(Math.max(1, dp(context, .5f)), OUTLINE);
    return drawable;
  }

  static void surface(View view, int color, float radius) {
    view.setBackground(shape(view.getContext(), color, radius, true));
    view.setClipToOutline(true);
    view.setElevation(dp(view.getContext(), 2));
  }

  static void action(TextView view, int color, int fill, float radius) {
    Context context = view.getContext();
    // 传 R_PILL 才是胶囊；给具体半径就是圆角矩形（导出这类大按钮用 16dp 更利落）。
    boolean capsule = radius >= 100f;
    StateListDrawable states = new StateListDrawable();
    states.addState(
        new int[] {-android.R.attr.state_enabled},
        actionShape(
            context, fill == Color.TRANSPARENT ? Color.TRANSPARENT : CONTROL, radius, capsule));
    states.addState(new int[] {}, actionShape(context, fill, radius, capsule));
    view.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x24ffffff),
            states,
            actionShape(context, Color.WHITE, radius, capsule)));
    view.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {DISABLED, color}));    view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    view.setIncludeFontPadding(false);
    // A capsule needs breathing room. With WRAP_CONTENT width and no padding the pill comes out
    // exactly as wide as its text, so the glyphs sit on the curved ends and read as spilling out of
    // the shape — that was visible on the「全部 8 款」chip. Auto-width actions therefore always keep
    // at least half their height on each side.
    view.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          ViewGroup.LayoutParams params = v.getLayoutParams();
          if (params == null || params.width != ViewGroup.LayoutParams.WRAP_CONTENT) return;
          int minimum = Math.round(v.getHeight() / 2f);
          if (v.getPaddingLeft() >= minimum && v.getPaddingRight() >= minimum) return;
          int horizontal = Math.max(minimum, Math.max(v.getPaddingLeft(), v.getPaddingRight()));
          v.setPadding(horizontal, v.getPaddingTop(), horizontal, v.getPaddingBottom());
        });
    view.setFocusable(true);
    view.setClickable(true);
  }

  private static GradientDrawable actionShape(
      Context context, int color, float radius, boolean capsule) {
    if (!capsule) return shape(context, color, radius, false);
    GradientDrawable drawable =
        new GradientDrawable() {
          @Override
          protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            // Match the export button's semicircular ends at every actual button height.
            setCornerRadius(Math.min(bounds.width(), bounds.height()) / 2f);
          }
        };
    drawable.setColor(color);
    return drawable;
  }

  static void field(EditText input) {
    input.setTextColor(INK);
    input.setHintTextColor(MUTED);
    input.setTextSize(16);
    input.setPadding(dp(input.getContext(), 14), 0, dp(input.getContext(), 14), 0);
    input.setBackground(shape(input.getContext(), FIELD, 14, true));
    input.setBackgroundTintList(null);
  }

  static void dialog(AlertDialog dialog) {
    Window window = dialog.getWindow();
    if (window == null) return;
    window.setBackgroundDrawable(shape(dialog.getContext(), SURFACE, 26, true));
    window.setDimAmount(.58f);
    View decor = window.getDecorView();
    // Style before the first frame without replacing the caller's validation listener.
    decor
        .getViewTreeObserver()
        .addOnPreDrawListener(
            new ViewTreeObserver.OnPreDrawListener() {
              public boolean onPreDraw() {
                if (!dialog.isShowing()) return true;
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                int width =
                    Math.min(
                        dp(dialog.getContext(), 480),
                        dialog.getContext().getResources().getDisplayMetrics().widthPixels
                            - dp(dialog.getContext(), 32));
                window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
                TextView message = dialog.findViewById(android.R.id.message);
                if (message != null) {
                  message.setTextColor(INK);
                  message.setTextSize(15);
                  message.setLineSpacing(dp(dialog.getContext(), 4), 1);
                }
                for (int which :
                    new int[] {
                      AlertDialog.BUTTON_POSITIVE,
                      AlertDialog.BUTTON_NEGATIVE,
                      AlertDialog.BUTTON_NEUTRAL
                    }) {
                  Button button = dialog.getButton(which);
                  if (button == null) continue;
                  boolean primary = which == AlertDialog.BUTTON_POSITIVE;
                  action(button, primary ? BG : INK, primary ? ACCENT : Color.TRANSPARENT, 20);
                  button.setAllCaps(false);
                  button.setTextSize(14);
                  button.setMinHeight(dp(dialog.getContext(), 44));
                  button.setMinWidth(primary ? 2 * dp(dialog.getContext(), 44) : 0);
                  button.setMinimumWidth(primary ? 2 * dp(dialog.getContext(), 44) : 0);
                  int padding = dp(dialog.getContext(), primary ? 12 : 8);
                  button.setPadding(padding, 0, padding, 0);
                }
                layoutDialogActions(dialog, width);
                decor.requestLayout();
                return false;
              }
            });
  }

  private static void layoutDialogActions(AlertDialog dialog, int width) {
    Button primary = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
    if (primary == null
        || primary.getVisibility() != View.VISIBLE
        || !(primary.getParent() instanceof ViewGroup)) return;
    ViewGroup original = (ViewGroup) primary.getParent();
    if (!(original.getParent() instanceof ViewGroup)) return;
    ViewGroup slot = (ViewGroup) original.getParent();
    ArrayList<Button> buttons = new ArrayList<>();
    for (int which :
        new int[] {
          AlertDialog.BUTTON_NEUTRAL, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_POSITIVE
        }) {
      Button button = dialog.getButton(which);
      if (button != null && button.getVisibility() == View.VISIBLE) {
        if (button.getParent() != original) return;
        buttons.add(button);
      }
    }
    Context context = dialog.getContext();
    int height = dp(context, 44), gap = dp(context, 8);
    int available = Math.max(height * 2, width - dp(context, 48));
    LinearLayout footer = new LinearLayout(context);
    footer.setId(original.getId());
    footer.setOrientation(LinearLayout.VERTICAL);
    footer.setPadding(dp(context, 12), gap, dp(context, 12), gap);
    LinearLayout row = null;
    int used = 0, rows = 0;
    for (Button button : buttons) {
      int desired =
          Math.max(
              button == primary ? height * 2 : height,
              (int) Math.ceil(button.getPaint().measureText(button.getText().toString()))
                  + button.getPaddingLeft()
                  + button.getPaddingRight());
      desired = Math.min(available, desired);
      if (row == null || used + gap + desired > available) {
        row = new LinearLayout(context);
        row.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, height);
        if (rows > 0) line.topMargin = gap;
        footer.addView(row, line);
        rows++;
        used = 0;
      }
      original.removeView(button);
      LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(desired, height);
      if (used > 0) action.leftMargin = gap;
      row.addView(button, action);
      used += desired + (used > 0 ? gap : 0);
    }
    // AlertDialog reserves the button panel's minimum height before sizing its scrollable body.
    footer.setMinimumHeight(rows * height + (rows - 1) * gap + gap * 2);
    int index = slot.indexOfChild(original);
    ViewGroup.LayoutParams layout = original.getLayoutParams();
    layout.height = ViewGroup.LayoutParams.WRAP_CONTENT;
    slot.removeView(original);
    slot.addView(footer, index, layout);
  }
}
