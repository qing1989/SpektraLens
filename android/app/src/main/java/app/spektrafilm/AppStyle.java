// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.graphics.fonts.FontStyle;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.IOException;
import java.util.ArrayList;

/**
 * Precision Instrument (方案 02 · 精密光学) semantic design system with live Darkroom Black /
 * Studio Silver theme switching across every screen, sheet, and dialog.
 */
final class AppStyle {
  static final String PREF_THEME = "ui_theme";
  static final String THEME_DARK = "dark";
  static final String THEME_LIGHT = "light";

  // Active semantic palette (mutated by applyTheme; defaults to Darkroom Black).
  static int BG = 0xff0a0a0b,
      SURFACE = 0xff121214,
      CONTROL = 0xff1c1c1f,
      SELECTED = 0xff232328,
      SELECTED_BORDER = 0xff35353c,
      EDITOR_TABS_BG = 0xff121214,
      FIELD = 0xff0f0f11,
      OUTLINE = 0xff27272c,
      DIVIDER = 0xff1f1f24,
      INK = 0xfff4f4f6,
      MUTED = 0xff8e8e95,
      PRIMARY = 0xffffffff,
      ON_PRIMARY = 0xff0a0a0b,
      GOLD = 0xfff0523d,
      ACCENT_SOFT = 0x24f0523d,
      ACCENT_BORDER = 0x7af0523d,
      ON_ACCENT = 0xffffffff,
      DANGER = 0xffff5b4d,
      SWITCH_TRACK_ON = 0xffd94430,
      SWITCH_TRACK_OFF = 0xff34343b,
      SWITCH_THUMB_ON = 0xffffffff,
      SWITCH_THUMB_OFF = 0xffc8c8ce,
      GRIP = 0xff52525b,
      DISABLED = 0xff4e4e55,
      VALUE = 0xfff4f4f6,
      NOTICE_BG = 0xf01a1a1e,
      NOTICE_INK = 0xfff4f4f6,
      RIPPLE = 0x24ffffff,
      STAGE_BG = 0xff050506,
      SLIDER_TRACK = 0xff34343b,
      SLIDER_THUMB_BG = 0xfff4f4f6,
      SLIDER_THUMB_BORDER = 0x59000000,
      SLIDER_THUMB_MARK = 0xfff0523d,
      // 曲线通道色：只用于曲线图与图例，别拿去当正文色。
      CURVE_R = 0xfff28179,
      CURVE_G = 0xff6bbd78,
      CURVE_B = 0xff5c9bf5;

  static float DIM_AMOUNT = .68f;
  private static boolean lightTheme;

  // 字号阶梯：小字 11、标签 12、正文 13、标题 15、板块 18、品牌 30。
  static final int TYPE_MICRO = 11, TYPE_LABEL = 12, TYPE_BODY = 13,
      TYPE_TITLE = 15, TYPE_HEADING = 18, TYPE_BRAND = 30;

  // 精密光学收敛圆角体系：
  //   R_SMALL  8  —— 芯片、输入框、微胶囊、轻提示
  //   R_MEDIUM 12 —— 卡片、胶片缩略图、设置卡、主/次按钮、导出选项轨
  //   R_PANEL  20 —— 编辑面板、浮动坞
  //   R_LARGE  22 —— 底部浮层、系统弹窗、悬浮菜单
  static final float R_SMALL = 8,
      R_MEDIUM = 12,
      R_BUTTON = 12,
      R_DOCK = 18,
      R_DOCK_INNER = 14,
      R_PANEL = 20,
      R_LARGE = 22;

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

  static boolean isLight() {
    return lightTheme;
  }

  static void loadTheme(Context context) {
    SharedPreferences prefs =
        context.getApplicationContext().getSharedPreferences("darkroom", Context.MODE_PRIVATE);
    applyTheme(THEME_LIGHT.equals(prefs.getString(PREF_THEME, THEME_DARK)));
  }

  static void saveTheme(Context context, boolean light) {
    context
        .getApplicationContext()
        .getSharedPreferences("darkroom", Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_THEME, light ? THEME_LIGHT : THEME_DARK)
        .apply();
    applyTheme(light);
  }

  static void applyTheme(boolean light) {
    lightTheme = light;
    if (light) {
      BG = 0xffececef;
      SURFACE = 0xfff9f9fa;
      CONTROL = 0xffe6e6eb;
      SELECTED = 0xffffffff;
      SELECTED_BORDER = 0xffcfcfd6;
      EDITOR_TABS_BG = 0xffefeff3;
      FIELD = 0xffe4e4e8;
      OUTLINE = 0xffd5d5dc;
      DIVIDER = 0xffe5e5ea;
      INK = 0xff111113;
      MUTED = 0xff676770;
      PRIMARY = 0xff111113;
      ON_PRIMARY = 0xffffffff;
      GOLD = 0xffc83422;
      ACCENT_SOFT = 0x1ac83422;
      ACCENT_BORDER = 0x6bc83422;
      ON_ACCENT = 0xffffffff;
      DANGER = 0xffc83422;
      SWITCH_TRACK_ON = 0xffc83422;
      SWITCH_TRACK_OFF = 0xffceced6;
      SWITCH_THUMB_ON = 0xffffffff;
      SWITCH_THUMB_OFF = 0xffffffff;
      GRIP = 0xffb2b2bc;
      DISABLED = 0xffaeaeb7;
      VALUE = 0xff111113;
      NOTICE_BG = 0xf018181c;
      NOTICE_INK = 0xfff4f4f6;
      RIPPLE = 0x1a000000;
      STAGE_BG = 0xffe2e2e6;
      SLIDER_TRACK = 0xffceced6;
      SLIDER_THUMB_BG = 0xff111113;
      SLIDER_THUMB_BORDER = 0x38ffffff;
      SLIDER_THUMB_MARK = 0xfff0523d;
      CURVE_R = 0xffd9483b;
      CURVE_G = 0xff2d8f4e;
      CURVE_B = 0xff2b6fd6;
      DIM_AMOUNT = .40f;
    } else {
      BG = 0xff0a0a0b;
      SURFACE = 0xff121214;
      CONTROL = 0xff1c1c1f;
      SELECTED = 0xff232328;
      SELECTED_BORDER = 0xff35353c;
      EDITOR_TABS_BG = 0xff121214;
      FIELD = 0xff0f0f11;
      OUTLINE = 0xff27272c;
      DIVIDER = 0xff1f1f24;
      INK = 0xfff4f4f6;
      MUTED = 0xff8e8e95;
      PRIMARY = 0xffffffff;
      ON_PRIMARY = 0xff0a0a0b;
      GOLD = 0xfff0523d;
      ACCENT_SOFT = 0x24f0523d;
      ACCENT_BORDER = 0x7af0523d;
      ON_ACCENT = 0xffffffff;
      DANGER = 0xffff5b4d;
      SWITCH_TRACK_ON = 0xffd94430;
      SWITCH_TRACK_OFF = 0xff34343b;
      SWITCH_THUMB_ON = 0xffffffff;
      SWITCH_THUMB_OFF = 0xffc8c8ce;
      GRIP = 0xff52525b;
      DISABLED = 0xff4e4e55;
      VALUE = 0xfff4f4f6;
      NOTICE_BG = 0xf01a1a1e;
      NOTICE_INK = 0xfff4f4f6;
      RIPPLE = 0x24ffffff;
      STAGE_BG = 0xff050506;
      SLIDER_TRACK = 0xff34343b;
      SLIDER_THUMB_BG = 0xfff4f4f6;
      SLIDER_THUMB_BORDER = 0x59000000;
      SLIDER_THUMB_MARK = 0xfff0523d;
      CURVE_R = 0xfff28179;
      CURVE_G = 0xff6bbd78;
      CURVE_B = 0xff5c9bf5;
      DIM_AMOUNT = .68f;
    }
  }

  @SuppressWarnings("deprecation")
  static void applyWindowTheme(Activity activity) {
    Window window = activity.getWindow();
    if (window == null) return;
    window.setBackgroundDrawable(new ColorDrawable(BG));
    window.setStatusBarColor(BG);
    window.setNavigationBarColor(BG);
    // Ensure DecorView is installed before touching WindowInsetsController: on Android 11–15,
    // PhoneWindow.getInsetsController() dereferences mDecor directly and throws NPE if called
    // before setContentView() / getDecorView().
    View decor = window.getDecorView();
    if (decor == null) return;
    if (Build.VERSION.SDK_INT >= 30) {
      final boolean light = lightTheme;
      Runnable applyBars =
          () -> {
            if (activity.isDestroyed() || activity.isFinishing()) return;
            WindowInsetsController controller = decor.getWindowInsetsController();
            if (controller == null) controller = window.getInsetsController();
            if (controller != null) {
              int mask =
                  WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                      | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
              controller.setSystemBarsAppearance(light ? mask : 0, mask);
            }
          };
      if (decor.isAttachedToWindow()) applyBars.run();
      else decor.post(applyBars);
    } else {
      int flags = decor.getSystemUiVisibility();
      int lightMask =
          View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
      decor.setSystemUiVisibility(lightTheme ? (flags | lightMask) : (flags & ~lightMask));
    }
  }

  static ColorStateList iconTint() {
    return new ColorStateList(
        new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
        new int[] {DISABLED, INK});
  }

  static ColorStateList accentToggleTint() {
    return new ColorStateList(
        new int[][] {
          new int[] {-android.R.attr.state_enabled},
          new int[] {android.R.attr.state_selected},
          new int[] {}
        },
        new int[] {DISABLED, GOLD, INK});
  }

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

  /** Precision Instrument rectangular-chamfered switch (R7 rail, R5 thumb, vermilion ON state). */
  static void styleSwitch(Switch view) {
    Context context = view.getContext();
    int thumbSize = dp(context, 20);
    int horizontalInset = dp(context, 4);
    int verticalInset = dp(context, 3);
    int trackWidth = thumbSize * 2 + horizontalInset * 2;
    GradientDrawable thumb = new GradientDrawable();
    thumb.setShape(GradientDrawable.RECTANGLE);
    thumb.setCornerRadius(dp(context, 5));
    thumb.setSize(thumbSize, thumbSize);
    thumb.setColor(switchColors(SWITCH_THUMB_ON, SWITCH_THUMB_OFF));
    GradientDrawable track = new GradientDrawable();
    track.setShape(GradientDrawable.RECTANGLE);
    track.setCornerRadius(dp(context, 7));
    track.setSize(trackWidth, thumbSize + verticalInset * 2);
    // Switch extends the thumb bounds by any thumb padding. Use track padding for travel limits.
    track.setPadding(horizontalInset, verticalInset, horizontalInset, verticalInset);
    track.setColor(switchColors(SWITCH_TRACK_ON, SWITCH_TRACK_OFF));
    // Replace the geometry as well as the colours; OEM tinting must not restyle these drawables.
    view.setThumbTintList(null);
    view.setTrackTintList(null);
    view.setThumbDrawable(new InsetDrawable(thumb, 0, verticalInset, 0, verticalInset));
    view.setTrackDrawable(track);
    view.setShowText(false);
    view.setSplitTrack(false);
    view.setSwitchMinWidth(trackWidth);
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
    if (outline) drawable.setStroke(Math.max(1, dp(context, .75f)), OUTLINE);
    return drawable;
  }

  static GradientDrawable selectedShape(Context context, float radius) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(SELECTED);
    drawable.setCornerRadius(dp(context, radius));
    drawable.setStroke(Math.max(1, dp(context, .85f)), SELECTED_BORDER);
    return drawable;
  }

  static GradientDrawable topRounded(Context context, int color, float radius, boolean outline) {
    GradientDrawable drawable = new GradientDrawable();
    drawable.setColor(color);
    float r = dp(context, radius);
    drawable.setCornerRadii(new float[] {r, r, r, r, 0, 0, 0, 0});
    if (outline) drawable.setStroke(Math.max(1, dp(context, .75f)), OUTLINE);
    return drawable;
  }

  static GradientDrawable pill(Context context, int color, boolean outline) {
    GradientDrawable drawable = new PillDrawable();
    drawable.setColor(color);
    if (outline) drawable.setStroke(Math.max(1, dp(context, .75f)), OUTLINE);
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
            ColorStateList.valueOf(RIPPLE), null, shape(context, Color.WHITE, R_SMALL, false)));
    view.setImageTintList(ColorStateList.valueOf(tint));
    view.setContentDescription(description);
    view.setMinimumWidth(0);
    view.setMinimumHeight(0);
    return view;
  }

  static void surface(View view, int color, float radius) {
    surface(view, color, radius, true);
  }

  static void surface(View view, int color, float radius, boolean outline) {
    view.setBackground(shape(view.getContext(), color, radius, outline));
    view.setClipToOutline(true);
    view.setElevation(dp(view.getContext(), lightTheme ? 1 : 2));
  }

  // 9-stop C1-continuous perceptual scrim curve: solid at the clip edge to hide hard cuts,
  // decaying rapidly through the middle and tapering asymptotically to 0 to eliminate Mach bands.
  private static final float[] SCRIM_STOPS = {
    0.00f, 0.08f, 0.20f, 0.34f, 0.50f, 0.66f, 0.80f, 0.92f, 1.00f
  };
  private static final float[] SCRIM_ALPHAS = {
    1.00f, 0.95f, 0.78f, 0.54f, 0.31f, 0.145f, 0.052f, 0.012f, 0.00f
  };

  static Drawable easedScrim(int color, boolean fromTop) {
    return easedScrim(color, fromTop, 1f);
  }

  static Drawable easedScrim(int color, boolean fromTop, float maxAlpha) {
    return new EasedScrimDrawable(color, fromTop, maxAlpha);
  }

  private static final class EasedScrimDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.DITHER_FLAG);
    private final int[] colors = new int[SCRIM_ALPHAS.length];
    private final boolean fromTop;
    private int drawableAlpha = 255;

    EasedScrimDrawable(int color, boolean fromTop, float maxAlpha) {
      this.fromTop = fromTop;
      float scale = Math.max(0f, Math.min(1f, maxAlpha));
      for (int i = 0; i < SCRIM_ALPHAS.length; i++) {
        colors[i] = withAlpha(color, SCRIM_ALPHAS[i] * scale);
      }
      paint.setDither(true);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
      super.onBoundsChange(bounds);
      int h = bounds.height();
      if (h <= 0) {
        paint.setShader(null);
        return;
      }
      float y0 = fromTop ? bounds.top : bounds.bottom;
      float y1 = fromTop ? bounds.bottom : bounds.top;
      paint.setShader(
          new LinearGradient(0f, y0, 0f, y1, colors, SCRIM_STOPS, Shader.TileMode.CLAMP));
    }

    @Override
    public void draw(Canvas canvas) {
      if (paint.getShader() == null) return;
      paint.setAlpha(drawableAlpha);
      canvas.drawRect(getBounds(), paint);
    }

    @Override
    public void setAlpha(int alpha) {
      drawableAlpha = alpha;
      invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
      paint.setColorFilter(colorFilter);
      invalidateSelf();
    }

    @Override
    @SuppressWarnings("deprecation")
    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }
  }

  private static float smoothstep(float distancePx, float rampPx) {
    if (distancePx <= 0f) return 0f;
    float t = Math.min(1f, distancePx / rampPx);
    return t * t * (3f - 2f * t);
  }

  /** Suppresses native & OEM transient scrollbars (including ColorOS auto-awakened edge bars). */
  static void hideScrollBars(View view) {
    if (view == null) return;
    view.setVerticalScrollBarEnabled(false);
    view.setHorizontalScrollBarEnabled(false);
    view.setScrollbarFadingEnabled(false);
    view.setScrollBarSize(0);
    view.setVerticalScrollbarThumbDrawable(new ColorDrawable(Color.TRANSPARENT));
    view.setVerticalScrollbarTrackDrawable(null);
    view.setHorizontalScrollbarThumbDrawable(new ColorDrawable(Color.TRANSPARENT));
    view.setHorizontalScrollbarTrackDrawable(null);
  }

  /**
   * Attaches non-interactive eased gradient scrims to the top and/or bottom of a scrollable
   * viewport so scrolling content melts naturally under fixed headers and footers without visible
   * gradient edge lines. Tracks pixel scroll distance on both ScrollView and AbsListView.
   */
  static void attachScrollFade(
      FrameLayout host, View scroller, int fadeColor, int topDp, int bottomDp) {
    hideScrollBars(scroller);
    Context context = host.getContext();
    final View topFade;
    if (topDp > 0) {
      topFade = new View(context);
      topFade.setBackground(easedScrim(fadeColor, true));
      topFade.setClickable(false);
      topFade.setFocusable(false);
      topFade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      topFade.setAlpha(0f);
      host.addView(topFade, new FrameLayout.LayoutParams(-1, dp(context, topDp), Gravity.TOP));
    } else {
      topFade = null;
    }
    final View bottomFade;
    if (bottomDp > 0) {
      bottomFade = new View(context);
      bottomFade.setBackground(easedScrim(fadeColor, false));
      bottomFade.setClickable(false);
      bottomFade.setFocusable(false);
      bottomFade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      bottomFade.setAlpha(0f);
      host.addView(
          bottomFade, new FrameLayout.LayoutParams(-1, dp(context, bottomDp), Gravity.BOTTOM));
    } else {
      bottomFade = null;
    }
    if (topFade == null && bottomFade == null) return;
    final float rampPx = Math.max(1f, dp(context, 36));
    final Runnable update =
        () -> {
          if (topFade != null) {
            float target;
            if (scroller instanceof ScrollView) {
              target = smoothstep(scroller.getScrollY(), rampPx);
            } else if (scroller instanceof AbsListView) {
              AbsListView list = (AbsListView) scroller;
              if (list.getChildCount() == 0) {
                target = 0f;
              } else if (list.getFirstVisiblePosition() > 0) {
                target = 1f;
              } else {
                int scrolled = list.getPaddingTop() - list.getChildAt(0).getTop();
                target = smoothstep(scrolled, rampPx);
              }
            } else {
              target = scroller.canScrollVertically(-1) ? 1f : 0f;
            }
            if (Math.abs(topFade.getAlpha() - target) > .008f) topFade.setAlpha(target);
          }
          if (bottomFade != null) {
            float target;
            if (scroller instanceof ScrollView && ((ViewGroup) scroller).getChildCount() > 0) {
              View child = ((ViewGroup) scroller).getChildAt(0);
              int rem =
                  child.getHeight()
                      + scroller.getPaddingTop()
                      + scroller.getPaddingBottom()
                      - scroller.getHeight()
                      - scroller.getScrollY();
              target = smoothstep(rem, rampPx);
            } else if (scroller instanceof AbsListView) {
              AbsListView list = (AbsListView) scroller;
              int count = list.getChildCount();
              if (count == 0) {
                target = 0f;
              } else if (list.getLastVisiblePosition() < list.getCount() - 1) {
                target = 1f;
              } else {
                View last = list.getChildAt(count - 1);
                int rem = last.getBottom() - (list.getHeight() - list.getPaddingBottom());
                target = smoothstep(rem, rampPx);
              }
            } else {
              target = scroller.canScrollVertically(1) ? 1f : 0f;
            }
            if (Math.abs(bottomFade.getAlpha() - target) > .008f) bottomFade.setAlpha(target);
          }
        };
    final ViewTreeObserver.OnScrollChangedListener scrollListener = update::run;
    scroller.addOnAttachStateChangeListener(
        new View.OnAttachStateChangeListener() {
          @Override
          public void onViewAttachedToWindow(View v) {
            v.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
            v.post(update);
          }

          @Override
          public void onViewDetachedFromWindow(View v) {
            v.getViewTreeObserver().removeOnScrollChangedListener(scrollListener);
          }
        });
    if (scroller.isAttachedToWindow()) {
      scroller.getViewTreeObserver().addOnScrollChangedListener(scrollListener);
    }
    scroller.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.post(update));
    if (scroller instanceof ScrollView && ((ViewGroup) scroller).getChildCount() > 0) {
      ((ViewGroup) scroller)
          .getChildAt(0)
          .addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> scroller.post(update));
    }
    scroller.post(update);
  }

  static FrameLayout wrapScrollFade(
      Context context,
      View scroller,
      int fadeColor,
      int topDp,
      int bottomDp,
      boolean wrapContent) {
    FrameLayout wrap = new FrameLayout(context);
    wrap.addView(scroller, new FrameLayout.LayoutParams(-1, wrapContent ? -2 : -1));
    attachScrollFade(wrap, scroller, fadeColor, topDp, bottomDp);
    return wrap;
  }

  static void action(TextView view, int color, int fill, float radius) {
    action(view, color, fill, radius, false);
  }

  /** Precision Instrument button action: crisp chamfered R12 geometry with hairline outline on secondary fills. */
  static void pillAction(TextView view, int color, int fill) {
    action(view, color, fill, R_BUTTON, false);
  }

  private static void action(TextView view, int color, int fill, float radius, boolean pill) {
    Context context = view.getContext();
    boolean secondaryOutline =
        fill != Color.TRANSPARENT && fill != PRIMARY && fill != GOLD && fill != DANGER;
    StateListDrawable states = new StateListDrawable();
    states.addState(
        new int[] {-android.R.attr.state_enabled},
        actionShape(
            context,
            fill == Color.TRANSPARENT ? Color.TRANSPARENT : CONTROL,
            radius,
            pill,
            false));
    states.addState(new int[] {}, actionShape(context, fill, radius, pill, secondaryOutline));
    view.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(RIPPLE),
            states,
            actionShape(context, Color.WHITE, radius, pill, false)));
    view.setTextColor(
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {DISABLED, color}));
    setTypeface(view, FONT_REGULAR);
    view.setIncludeFontPadding(false);
    view.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          ViewGroup.LayoutParams params = v.getLayoutParams();
          if (params == null || params.width != ViewGroup.LayoutParams.WRAP_CONTENT) return;
          int minimum = Math.round(v.getHeight() * .34f);
          int current = Math.max(v.getPaddingLeft(), v.getPaddingRight());
          if (current >= minimum) return;
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
      Context context, int color, float radius, boolean pill, boolean outline) {
    return pill ? pill(context, color, outline) : shape(context, color, radius, outline);
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
    window.setDimAmount(DIM_AMOUNT);
    View decor = window.getDecorView();
    decor
        .getViewTreeObserver()
        .addOnPreDrawListener(
            new ViewTreeObserver.OnPreDrawListener() {
              public boolean onPreDraw() {
                if (!dialog.isShowing()) return true;
                decor.getViewTreeObserver().removeOnPreDrawListener(this);
                Context dialogCtx = dialog.getContext();
                int dialogMaxDp =
                    FoldableLayout.alertDialogMaxDp(
                        FoldableUi.windowWidthDp(dialogCtx), dialog.getListView() != null);
                int width =
                    Math.min(
                        dp(dialogCtx, dialogMaxDp),
                        dialogCtx.getResources().getDisplayMetrics().widthPixels
                            - dp(dialogCtx, 32));
                window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
                final int titleId =
                    dialog
                        .getContext()
                        .getResources()
                        .getIdentifier("alertTitle", "id", "android");
                styleDialogTree(decor, titleId);
                ListView list = dialog.getListView();
                if (list != null) {
                  list.setDivider(new ColorDrawable(DIVIDER));
                  list.setDividerHeight(1);
                  list.setOnHierarchyChangeListener(
                      new ViewGroup.OnHierarchyChangeListener() {
                        public void onChildViewAdded(View parent, View child) {
                          styleDialogTree(child, titleId);
                        }

                        public void onChildViewRemoved(View parent, View child) {
                          // Nothing to release.
                        }
                      });
                }
                TextView message = dialog.findViewById(android.R.id.message);
                if (message != null) {
                  message.setTextColor(MUTED);
                  message.setTextSize(14);
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
                  setTypeface(button, primary ? FONT_BOLD : FONT_MEDIUM);
                  button.setAllCaps(false);
                  button.setTextSize(14);
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
      if (title) {
        text.setTextSize(TYPE_HEADING);
        text.setTextColor(INK);
      } else if (text.getId() == android.R.id.text1 && !(text instanceof Button)) {
        text.setTextSize(TYPE_BODY);
        text.setTextColor(INK);
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
    footer.setMinimumHeight(rows * height + (rows - 1) * gap + gap * 2);
    int index = slot.indexOfChild(original);
    ViewGroup.LayoutParams layout = original.getLayoutParams();
    layout.height = ViewGroup.LayoutParams.WRAP_CONTENT;
    slot.removeView(original);
    slot.addView(footer, index, layout);
  }
}
