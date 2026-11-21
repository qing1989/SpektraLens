// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import java.io.IOException;
import java.util.ArrayList;

/** Shared C-direction surfaces and controls, drawn natively without blurring photographs. */
final class AppStyle {
  static final int BG = 0xff000000,
      SURFACE = 0xff16141a,
      CONTROL = 0xff241f28,
      SELECTED = 0xff241f28,
      FIELD = 0xff16141a,
      OUTLINE = 0xff3a3340,
      DIVIDER = 0xff2a272e,
      INK = 0xfff6f3f4,
      MUTED = 0xffa399a0,
      PRIMARY = 0xfff7f3ea,
      ON_PRIMARY = 0xff16141a,
      GOLD = 0xffe0b47a,
      DANGER = 0xffe2a092,
      SWITCH_TRACK_ON = 0xff8f6a3e,
      SWITCH_TRACK_OFF = 0xff4a4a4e,
      SWITCH_THUMB_OFF = 0xffd0d0d2,
      GRIP = 0xff7a7278,
      DISABLED = 0xff8a8086,
      VALUE = 0xfff6f3f4,
      // 曲线通道色：只用于曲线图与图例，别拿去当正文色。
      CURVE_R = 0xfff28179,
      CURVE_G = 0xff99c786,
      CURVE_B = 0xff85baff;

  // 字号阶梯。新增界面只从这六档里选，别再插 12.5 这种中间值。
  // 2026-11-09 整体上调一档：小字 10→11、标签 11→12、正文 12→13、标题 14→15、板块 16→18、品牌 28→30。
  static final int TYPE_MICRO = 11, TYPE_LABEL = 12, TYPE_BODY = 13,
      TYPE_TITLE = 15, TYPE_HEADING = 18, TYPE_BRAND = 30;

  // 普通圆角矩形仍分三档；按钮、页签、数值框和底部导航使用独立的 pill() 胶囊轮廓，
  // 按实际短边的一半绘制，不靠某个固定半径近似，字体或窗口变大时也保持半圆端部。
  //   R_SMALL  10 —— 输入框、状态条
  //   R_MEDIUM 16 —— 卡片、胶片卡、材质卡、导出分段选项
  //   R_LARGE  26 —— 面板、弹层、对话框
  // 两条配套规则比档位更重要，凡是「套在一起」的形状都按它们来：
  //   ① 容器里的选中块用相同形状规则（底部导航与选中块都用 pill，各按内缩后的
  //      实际半高绘制），不要用小一档的固定半径，否则就是「方角塞进圆角」；
  //   ② 包住图形的环要和图形共用同一个矩形与半径（描边向内画），别另算一个数。
  //      必须留间隙时，外径要同时补上间隙和描边宽度——描边占的是内侧，只补间隙
  //      会让环的内弧比图形更圆，四角挤出一道楔形空隙，看着就是「两个圆角不一样」。
  static final float R_SMALL = 10, R_MEDIUM = 16, R_LARGE = 26;

  // 触控基准：主操作 48、常规 44。低于 44 的只留给纯展示元素。
  static final int TOUCH_PRIMARY = 48, TOUCH_MIN = 44;

  // Thumbnail selection is decorative; the whole photo remains the selection touch target.
  static final int SELECTION_BADGE = 22;

  static final int FONT_REGULAR = 400, FONT_MEDIUM = 500, FONT_SEMIBOLD = 600,
      FONT_BOLD = 700;
  private static final int[] FONT_WEIGHTS = {
    FONT_REGULAR, FONT_MEDIUM, FONT_SEMIBOLD, FONT_BOLD
  };
  private static final Typeface[] TYPEFACES = new Typeface[FONT_WEIGHTS.length];

  /** Same colour at a different opacity, for wells and overlays. */
  static int withAlpha(int color, float alpha) {
    return (color & 0x00ffffff) | (Math.round(Math.max(0, Math.min(1, alpha)) * 255) << 24);
  }

  static int dp(Context context, float value) {
    return Math.round(value * context.getResources().getDisplayMetrics().density);
  }

  /**
   * Inter is the Latin base and Noto Sans SC is the bundled CJK fallback. The explicit system
   * fallback covers uncommon symbols, while all ordinary UI text stays local and deterministic.
   */
  static Typeface typeface(Context context, int weight) {
    int index = 0;
    for (int i = 0; i < FONT_WEIGHTS.length; i++)
      if (weight >= FONT_WEIGHTS[i]) index = i;
    Typeface cached = TYPEFACES[index];
    if (cached != null) return cached;
    synchronized (AppStyle.class) {
      cached = TYPEFACES[index];
      if (cached != null) return cached;
      Context app = context.getApplicationContext();
      try {
        int selectedWeight = FONT_WEIGHTS[index];
        String variations = "'wght' " + selectedWeight;
        Font inter =
            new Font.Builder(app.getResources(), R.font.inter_variable)
                .setFontVariationSettings(variations)
                .setWeight(selectedWeight)
                .build();
        Font noto =
            new Font.Builder(app.getResources(), R.font.noto_sans_sc_variable)
                .setFontVariationSettings(variations)
                .setWeight(selectedWeight)
                .build();
        Typeface.CustomFallbackBuilder fallback =
            new Typeface.CustomFallbackBuilder(new FontFamily.Builder(inter).build());
        fallback.addCustomFallback(new FontFamily.Builder(noto).build());
        fallback.setStyle(new FontStyle(selectedWeight, FontStyle.FONT_SLANT_UPRIGHT));
        fallback.setSystemFallback("sans-serif");
        cached = fallback.build();
      } catch (IOException error) {
        throw new IllegalStateException("Bundled fonts could not be loaded", error);
      }
      TYPEFACES[index] = cached;
      return cached;
    }
  }

  static void setTypeface(TextView view, int weight) {
    view.setTypeface(typeface(view.getContext(), weight));
    view.setFontFeatureSettings("kern");
  }

  static void setNumericTypeface(TextView view) {
    view.setTypeface(typeface(view.getContext(), FONT_MEDIUM));
    view.setFontFeatureSettings("tnum");
  }

  static void styleSwitch(Switch view) {
    Context context = view.getContext();
    int inset = dp(context, 2), diameter = dp(context, 22);
    GradientDrawable thumb = new GradientDrawable();
    thumb.setShape(GradientDrawable.OVAL);
    thumb.setSize(diameter, diameter);
    thumb.setColor(switchColors(GOLD, SWITCH_THUMB_OFF));
    // Switch measures two thumb content widths. This padding represents the overlap at the
    // rounded ends, keeping a 44 × 26 dp rail and a circular 22 dp thumb with a 2 dp inset.
    thumb.setPadding(inset, 0, inset, 0);
    GradientDrawable track = pill(context, SWITCH_TRACK_OFF, false);
    track.setSize(diameter * 2, diameter + inset * 2);
    track.setPadding(inset * 2, inset, inset * 2, inset);
    track.setColor(switchColors(SWITCH_TRACK_ON, SWITCH_TRACK_OFF));
    // Replace the geometry as well as the colours; OEM tinting must not restyle these drawables.
    view.setThumbTintList(null);
    view.setTrackTintList(null);
    view.setThumbDrawable(new InsetDrawable(thumb, 0, inset, 0, inset));
    view.setTrackDrawable(track);
    view.setShowText(false);
    view.setSplitTrack(false);
    view.setSwitchMinWidth(diameter * 2);
    view.setSwitchPadding(dp(context, 12));
    view.setMinHeight(dp(context, TOUCH_MIN));
    view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    view.setIncludeFontPadding(false);
    view.getThumbDrawable().setState(view.getDrawableState());
    view.getTrackDrawable().setState(view.getDrawableState());
  }

  private static ColorStateList switchColors(int checked, int unchecked) {
    return new ColorStateList(
        new int[][] {
          {-android.R.attr.state_enabled, android.R.attr.state_checked},
          {-android.R.attr.state_enabled},
          {android.R.attr.state_checked},
          {}
        },
        new int[] {withAlpha(checked, .45f), withAlpha(unchecked, .45f), checked, unchecked});
  }

  static GradientDrawable shape(Context context, int color, float radius, boolean outline) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    drawable.setCornerRadius(dp(context, radius));
    if (outline) drawable.setStroke(Math.max(1, dp(context, .5f)), OUTLINE);
    return drawable;
  }

  static GradientDrawable pill(Context context, int color, boolean outline) {
    GradientDrawable drawable = new PillDrawable();
    drawable.setColor(color);
    if (outline) drawable.setStroke(Math.max(1, dp(context, .5f)), OUTLINE);
    return drawable;
  }

  /** Derive the outline from the final drawable bounds, including any navigation insets. */
  private static final class PillDrawable extends GradientDrawable {
    @Override
    protected void onBoundsChange(Rect bounds) {
      super.onBoundsChange(bounds);
      setCornerRadius(Math.min(bounds.width(), bounds.height()) / 2f);
    }
  }

  static ImageButton iconButton(Context context, int drawable, String description, int tint) {
    ImageButton view = new ImageButton(context);
    view.setImageResource(drawable);
    view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
    view.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
    view.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x24ffffff), null, pill(context, Color.WHITE, false)));
    view.setImageTintList(ColorStateList.valueOf(tint));
    view.setContentDescription(description);
    view.setMinimumWidth(0);
    view.setMinimumHeight(0);
    return view;
  }

  static void surface(View view, int color, float radius) {
    surface(view, color, radius, true);
  }

  /** 底部导航这类贴着屏幕边的容器不要描边：那道线看起来像多余的外框。 */
  static void surface(View view, int color, float radius, boolean outline) {
    view.setBackground(shape(view.getContext(), color, radius, outline));
    view.setClipToOutline(true);
    view.setElevation(dp(view.getContext(), 2));
  }

  static void action(TextView view, int color, int fill, float radius) {
    action(view, color, fill, radius, false);
  }

  static void pillAction(TextView view, int color, int fill) {
    action(view, color, fill, 0, true);
  }

  private static void action(TextView view, int color, int fill, float radius, boolean pill) {
    Context context = view.getContext();
    StateListDrawable states = new StateListDrawable();
    states.addState(
        new int[] {-android.R.attr.state_enabled},
        actionShape(
            context, fill == Color.TRANSPARENT ? Color.TRANSPARENT : CONTROL, radius, pill));
    states.addState(new int[] {}, actionShape(context, fill, radius, pill));
    view.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(0x24ffffff),
            states,
            actionShape(context, Color.WHITE, radius, pill)));
    view.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {DISABLED, color}));
    setTypeface(view, FONT_REGULAR);
    view.setIncludeFontPadding(false);
    // A capsule needs breathing room. With WRAP_CONTENT width and no padding the pill comes out
    // exactly as wide as its text, so the glyphs sit on the curved ends and read as spilling out of
    // the shape — that was visible on the「全部 8 款」chip. Auto-width actions therefore always keep
    // at least half their height on each side.
    view.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          ViewGroup.LayoutParams params = v.getLayoutParams();
          if (params == null || params.width != ViewGroup.LayoutParams.WRAP_CONTENT) return;
          int minimum = Math.round(v.getHeight() * .34f);
          int current = Math.max(v.getPaddingLeft(), v.getPaddingRight());
          if (current >= minimum) return;
          // 内边距只能在「文字之外的空余」里加。按高度加是不安全的：把文字挤窄就会换行，
          // 换行又抬高高度，高度再抬高这里的 minimum，越挤越窄——弹窗里的「取消」因此被
          // 压成竖排两行、只剩半个字宽。所以先算真实剩余空间，加不下就不加。
          CharSequence label = view.getText();
          int text =
              label == null
                  ? 0
                  : (int) Math.ceil(view.getPaint().measureText(label.toString()));
          int spare = Math.max(0, (v.getWidth() - text) / 2 - 1);
          int horizontal = Math.min(minimum, Math.max(current, spare));
          if (horizontal <= current) return;
          v.setPadding(horizontal, v.getPaddingTop(), horizontal, v.getPaddingBottom());
        });
    view.setFocusable(true);
    view.setClickable(true);
  }

  private static GradientDrawable actionShape(
      Context context, int color, float radius, boolean pill) {
    return pill ? pill(context, color, false) : shape(context, color, radius, false);
  }

  static void field(EditText input) {
    input.setTextColor(INK);
    input.setHintTextColor(MUTED);
    input.setTextSize(16);
    input.setPadding(dp(input.getContext(), 14), 0, dp(input.getContext(), 14), 0);
    input.setBackground(shape(input.getContext(), FIELD, R_SMALL, true));
    input.setBackgroundTintList(null);
    setTypeface(input, FONT_REGULAR);
  }

  @android.annotation.SuppressLint("DiscouragedApi") // AlertDialog exposes no public title-id API.
  static void dialog(AlertDialog dialog) {
    Window window = dialog.getWindow();
    if (window == null) return;
    window.setBackgroundDrawable(shape(dialog.getContext(), SURFACE, R_LARGE, true));
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
                final int titleId =
                    dialog
                        .getContext()
                        .getResources()
                        .getIdentifier("alertTitle", "id", "android");
                styleDialogTree(decor, titleId);
                // 列表只保留可见行；新绑定或复用的行在加入 ViewGroup 时重新套用字号，
                // 不监听全局布局，避免 setTextSize / setLineSpacing 反过来触发布局循环。
                ListView list = dialog.getListView();
                if (list != null) {
                  list.setOnHierarchyChangeListener(
                      new ViewGroup.OnHierarchyChangeListener() {
                        public void onChildViewAdded(View parent, View child) {
                          styleDialogTree(child, titleId);
                        }

                        public void onChildViewRemoved(View parent, View child) {
                          // Nothing to release. Keeping the callback only styles newly bound rows.
                        }
                      });
                }
                TextView message = dialog.findViewById(android.R.id.message);
                if (message != null) {
                  message.setTextColor(INK);
                  message.setTextSize(15);
                  message.setLineSpacing(dp(dialog.getContext(), 4), 1);
                  setTypeface(message, FONT_REGULAR);
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
                  pillAction(
                      button,
                      primary ? ON_PRIMARY : INK,
                      primary ? PRIMARY : Color.TRANSPARENT);
                  setTypeface(button, primary ? FONT_BOLD : FONT_REGULAR);
                  button.setAllCaps(false);
                  button.setTextSize(14);
                  // 与主界面按钮同一规则：操作按钮不折行。否则「取消」这种两字标签在窄槽里
                  // 会一列排开，看起来像字被转了 90 度。
                  button.setSingleLine(true);
                  button.setEllipsize(android.text.TextUtils.TruncateAt.END);
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

  private static void styleDialogTree(View view, int titleId) {
    if (view instanceof TextView) {
      TextView text = (TextView) view;
      boolean title = text.getId() == titleId;
      setTypeface(text, title ? FONT_SEMIBOLD : FONT_REGULAR);
      // 沿用页面字号阶梯，而不是系统默认的 22 / 16sp：标题 18，选项 13。
      // 选项缩到正文档后长说明能保持单行；窄屏折行时补 2dp 行距，不再挤在一起。
      if (title) {
        text.setTextSize(TYPE_HEADING);
      } else if (text.getId() == android.R.id.text1 && !(text instanceof Button)) {
        text.setTextSize(TYPE_BODY);
        text.setLineSpacing(dp(text.getContext(), 2), 1);
      }
    }
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) styleDialogTree(group.getChildAt(i), titleId);
    }
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
