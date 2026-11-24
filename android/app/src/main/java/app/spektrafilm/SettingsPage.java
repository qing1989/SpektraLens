// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.Executor;

/**
 * Redesigned Precision Instrument settings page with live Darkroom Black / Studio Silver visual
 * theme switcher, grouped workbench/update/storage controls, and bottom SpektraLens brand plate.
 */
@SuppressLint("ViewConstructor")
final class SettingsPage extends LinearLayout {
  static final String PREF_SHOW_EDITOR_STRIP = "show_editor_strip";
  static final boolean DEFAULT_SHOW_EDITOR_STRIP = false;
  static final String PREF_SHAKE_ARRANGE = "shake_lighttable_arrange";
  static final boolean DEFAULT_SHAKE_ARRANGE = true;

  interface ThemeListener {
    void onThemeSelected(boolean light);
  }

  private final Activity activity;
  private final SharedPreferences prefs;
  private final Runnable close, about, editorStripChanged, checkUpdates, cacheCleared;
  private final Executor cacheIo;
  private final ThemeListener themeListener;
  private final TextView cacheValue;
  private final TextView aboutUpdateBadge;
  private int cacheJobId;
  private boolean clearingCache;

  SettingsPage(
      Activity activity,
      String version,
      Runnable close,
      Runnable about,
      Runnable editorStripChanged,
      Runnable checkUpdates,
      Runnable cacheCleared,
      Executor cacheIo,
      ThemeListener themeListener) {
    super(activity);
    this.activity = activity;
    this.close = close;
    this.about = about;
    this.editorStripChanged = editorStripChanged;
    this.checkUpdates = checkUpdates;
    this.cacheCleared = cacheCleared;
    this.cacheIo = cacheIo;
    this.themeListener = themeListener;
    this.prefs = activity.getSharedPreferences("darkroom", Activity.MODE_PRIVATE);
    setOrientation(VERTICAL);
    setBackgroundColor(AppStyle.BG);
    setAccessibilityPaneTitle("设置");
    setTag("settings.page");
    setClickable(true);

    // Strictly left-aligned top header + right mode badge
    LinearLayout header = new LinearLayout(activity);
    header.setOrientation(HORIZONTAL);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(20), dp(14), dp(20), dp(8));

    TextView title = text("设置", 22, AppStyle.INK);
    AppStyle.setTypeface(title, AppStyle.FONT_BOLD);
    title.setLetterSpacing(-.02f);
    title.setAccessibilityHeading(true);
    header.addView(title, new LayoutParams(0, -2, 1));

    TextView modeBadge =
        text(
            AppStyle.isLight() ? "STUDIO · 银室白" : "DARKROOM · 暗房黑",
            10,
            AppStyle.MUTED);
    AppStyle.setTypeface(modeBadge, AppStyle.FONT_SEMIBOLD);
    modeBadge.setSingleLine(true);
    modeBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
    modeBadge.setBackground(AppStyle.shape(activity, AppStyle.CONTROL, 6f, true));
    header.addView(modeBadge, new LayoutParams(-2, -2));
    addView(header, new LayoutParams(-1, -2));

    ScrollView scroll = new ScrollView(activity);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout content = new LinearLayout(activity);
    content.setOrientation(VERTICAL);
    content.setPadding(dp(18), dp(8), dp(18), dp(104));
    scroll.addView(content);
    addView(
        AppStyle.wrapScrollFade(activity, scroll, AppStyle.BG, 32, 0, false),
        new LayoutParams(-1, 0, 1));

    // Section 1: 外观 · 机身主题 (Black / White Dual-Body Visual Switcher)
    section(content, "外观 · 机身主题", "暗房黑 / 银室白", 6);
    LinearLayout themeDeck = new LinearLayout(activity);
    themeDeck.setOrientation(HORIZONTAL);
    View darkCard = themeCard(false, !AppStyle.isLight());
    darkCard.setTag("settings.theme.dark");
    View lightCard = themeCard(true, AppStyle.isLight());
    lightCard.setTag("settings.theme.light");
    themeDeck.addView(darkCard, new LayoutParams(0, -2, 1));
    LayoutParams lightCardParams = new LayoutParams(0, -2, 1);
    lightCardParams.leftMargin = dp(10);
    themeDeck.addView(lightCard, lightCardParams);
    content.addView(themeDeck, new LayoutParams(-1, -2));

    // Section 2: 工作台与交互
    section(content, "工作台与交互", null, 18);
    LinearLayout editCard = card(content);
    editCard.addView(
        switchRow(
            R.drawable.ic_settings_strip,
            "显示照片缩略图条",
            "编辑页大图下方快速切换",
            PREF_SHOW_EDITOR_STRIP,
            DEFAULT_SHOW_EDITOR_STRIP,
            () -> editorStripChanged.run()),
        new LayoutParams(-1, -2));
    divider(editCard);
    editCard.addView(
        switchRow(
            R.drawable.ic_settings_vibrate,
            "操作震动反馈",
            "按钮、游标滑块与长按",
            "haptics_enabled",
            true,
            () -> Haptics.enabled = prefs.getBoolean("haptics_enabled", true)),
        new LayoutParams(-1, -2));
    divider(editCard);
    editCard.addView(
        switchRow(
            R.drawable.ic_settings_shake,
            "摇一摇",
            "观片台重新整理",
            PREF_SHAKE_ARRANGE,
            DEFAULT_SHAKE_ARRANGE,
            null),
        new LayoutParams(-1, -2));

    // Section 3: 更新与存储 (Unified Instrument Module)
    section(content, "更新与存储", null, 18);
    LinearLayout sysCard = card(content);
    sysCard.addView(
        switchRow(
            R.drawable.ic_settings_refresh,
            "自动检查更新",
            "启动时静默检查新版本",
            "auto_check_updates",
            true,
            () -> {
              if (prefs.getBoolean("auto_check_updates", true)) checkUpdates.run();
            }),
        new LayoutParams(-1, -2));
    divider(sysCard);
    sysCard.addView(
        switchRow(
            R.drawable.ic_settings_wifi,
            "仅 Wi-Fi 下载更新",
            "只限制安装包，检查不受影响",
            "wifi_only_updates",
            true,
            null),
        new LayoutParams(-1, -2));
    divider(sysCard);

    cacheValue = text("计算中...", AppStyle.TYPE_LABEL, AppStyle.INK);
    AppStyle.setNumericTypeface(cacheValue);
    AppStyle.setTypeface(cacheValue, AppStyle.FONT_SEMIBOLD);
    sysCard.addView(cacheRow(), new LayoutParams(-1, -2));

    // Bottom Brand Nameplate Card
    LinearLayout aboutPlate = new LinearLayout(activity);
    aboutPlate.setOrientation(HORIZONTAL);
    aboutPlate.setGravity(Gravity.CENTER_VERTICAL);
    aboutPlate.setPadding(dp(14), dp(12), dp(14), dp(12));
    aboutPlate.setTag("settings.about");
    aboutPlate.setClickable(true);
    aboutPlate.setFocusable(true);
    aboutPlate.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(AppStyle.RIPPLE),
            AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_MEDIUM, true),
            AppStyle.shape(activity, Color.WHITE, AppStyle.R_MEDIUM, false)));
    aboutPlate.setClipToOutline(true);

    FrameLayout logoWrap = new FrameLayout(activity);
    logoWrap.setBackground(AppStyle.shape(activity, AppStyle.CONTROL, 9f, true));
    logoWrap.setClipToOutline(true);
    ImageView logoImg = new ImageView(activity);
    logoImg.setImageResource(R.drawable.ic_spektra_film_s_logo);
    logoImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
    logoImg.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    logoWrap.addView(logoImg, new FrameLayout.LayoutParams(-1, -1));
    LayoutParams logoParams = new LayoutParams(dp(36), dp(36));
    logoParams.rightMargin = dp(11);
    aboutPlate.addView(logoWrap, logoParams);

    LinearLayout aboutTexts = new LinearLayout(activity);
    aboutTexts.setOrientation(VERTICAL);
    TextView aboutTitle =
        text("关于 " + activity.getString(R.string.app_name), AppStyle.TYPE_BODY, AppStyle.INK);
    AppStyle.setTypeface(aboutTitle, AppStyle.FONT_SEMIBOLD);
    aboutTexts.addView(aboutTitle, new LayoutParams(-1, -2));
    TextView aboutSub = text("v" + version + " · 开源许可与免责声明", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    aboutSub.setSingleLine(true);
    aboutSub.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LayoutParams subP = new LayoutParams(-1, -2);
    subP.topMargin = dp(2);
    aboutTexts.addView(aboutSub, subP);
    aboutPlate.addView(aboutTexts, new LayoutParams(0, -2, 1));

    aboutUpdateBadge = text("可更新", 10, AppStyle.GOLD);
    AppStyle.setTypeface(aboutUpdateBadge, AppStyle.FONT_BOLD);
    aboutUpdateBadge.setPadding(dp(7), dp(3), dp(7), dp(3));
    GradientDrawable updBg = AppStyle.shape(activity, AppStyle.ACCENT_SOFT, 6f, false);
    updBg.setStroke(Math.max(1, dp(.75f)), AppStyle.ACCENT_BORDER);
    aboutUpdateBadge.setBackground(updBg);
    aboutUpdateBadge.setVisibility(GONE);
    LayoutParams updP = new LayoutParams(-2, -2);
    updP.leftMargin = dp(8);
    aboutPlate.addView(aboutUpdateBadge, updP);

    ImageView chevron = new ImageView(activity);
    chevron.setImageResource(R.drawable.ic_material_chevron_right);
    chevron.setImageTintList(ColorStateList.valueOf(AppStyle.MUTED));
    chevron.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    LayoutParams chevP = new LayoutParams(dp(18), dp(18));
    chevP.leftMargin = dp(6);
    aboutPlate.addView(chevron, chevP);

    aboutPlate.setOnClickListener(
        v -> {
          if (this.about != null) this.about.run();
        });
    LayoutParams plateParams = new LayoutParams(-1, -2);
    plateParams.topMargin = dp(18);
    content.addView(aboutPlate, plateParams);
  }

  private View themeCard(boolean lightOption, boolean selected) {
    LinearLayout card = new LinearLayout(activity);
    card.setOrientation(VERTICAL);
    card.setPadding(dp(10), dp(10), dp(10), dp(10));
    card.setClickable(true);
    card.setFocusable(true);
    card.setContentDescription(
        (lightOption ? "银室白主题" : "暗房黑主题") + (selected ? "，当前已选" : ""));

    GradientDrawable bg = AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_MEDIUM, false);
    bg.setStroke(
        dp(selected ? 1.5f : .75f), selected ? AppStyle.GOLD : AppStyle.OUTLINE);
    card.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(AppStyle.RIPPLE),
            bg,
            AppStyle.shape(activity, Color.WHITE, AppStyle.R_MEDIUM, false)));

    // Miniature camera/darkroom UI wireframe preview
    LinearLayout mini = new LinearLayout(activity);
    mini.setOrientation(VERTICAL);
    mini.setPadding(dp(8), dp(7), dp(8), dp(7));
    int miniBg = lightOption ? 0xffececef : 0xff09090b;
    int miniBorder = lightOption ? 0xffd5d5dc : 0xff27272c;
    int miniCardFill = lightOption ? 0xffffffff : 0xff19191d;
    int miniBarFill = lightOption ? 0xff111113 : 0xffffffff;
    int miniTrack = lightOption ? 0xffc8c8d0 : 0xff2c2c34;
    GradientDrawable miniShape = new GradientDrawable();
    miniShape.setColor(miniBg);
    miniShape.setCornerRadius(dp(7));
    miniShape.setStroke(Math.max(1, dp(.75f)), miniBorder);
    mini.setBackground(miniShape);

    // Top row: title bar + vermilion dot
    LinearLayout miniTop = new LinearLayout(activity);
    miniTop.setGravity(Gravity.CENTER_VERTICAL);
    View bar = new View(activity);
    GradientDrawable barBg = new GradientDrawable();
    barBg.setColor(miniBarFill);
    barBg.setCornerRadius(dp(2));
    bar.setBackground(barBg);
    miniTop.addView(bar, new LayoutParams(dp(44), dp(4)));
    miniTop.addView(new View(activity), new LayoutParams(0, 1, 1));
    View dot = new View(activity);
    GradientDrawable dotBg = new GradientDrawable();
    dotBg.setShape(GradientDrawable.OVAL);
    dotBg.setColor(lightOption ? 0xffc83422 : 0xfff0523d);
    dot.setBackground(dotBg);
    miniTop.addView(dot, new LayoutParams(dp(6), dp(6)));
    mini.addView(miniTop, new LayoutParams(-1, -2));

    // Middle row: two miniature photo cards (1.3fr : 1fr)
    LinearLayout miniCards = new LinearLayout(activity);
    miniCards.setOrientation(HORIZONTAL);
    LayoutParams mcParams = new LayoutParams(-1, dp(24));
    mcParams.topMargin = dp(6);
    float[] cardWeights = {1.3f, 1f};
    for (int i = 0; i < 2; i++) {
      View c = new View(activity);
      GradientDrawable cBg = new GradientDrawable();
      cBg.setColor(miniCardFill);
      cBg.setCornerRadius(dp(4));
      cBg.setStroke(Math.max(1, dp(.5f)), miniBorder);
      c.setBackground(cBg);
      LayoutParams cp = new LayoutParams(0, -1, cardWeights[i]);
      if (i == 1) cp.leftMargin = dp(5);
      miniCards.addView(c, cp);
    }
    mini.addView(miniCards, mcParams);

    // Bottom row: miniature vernier slider positioned at 60% width
    FrameLayout miniSlider = new FrameLayout(activity);
    LayoutParams msParams = new LayoutParams(-1, dp(10));
    msParams.topMargin = dp(6);
    View rail = new View(activity);
    rail.setBackgroundColor(miniTrack);
    miniSlider.addView(rail, new FrameLayout.LayoutParams(-1, dp(2), Gravity.CENTER_VERTICAL));
    View thumb = new View(activity);
    GradientDrawable thBg = new GradientDrawable();
    thBg.setColor(lightOption ? 0xffc83422 : 0xfff0523d);
    thBg.setCornerRadius(dp(1.5f));
    thumb.setBackground(thBg);
    FrameLayout.LayoutParams thP =
        new FrameLayout.LayoutParams(dp(5), dp(9), Gravity.CENTER_VERTICAL | Gravity.START);
    miniSlider.addView(thumb, thP);
    miniSlider.addOnLayoutChangeListener(
        (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
          int w = right - left;
          if (w <= 0) return;
          int margin = Math.max(0, Math.round(w * .60f) - dp(2.5f));
          if (thP.leftMargin != margin) {
            thP.leftMargin = margin;
            thumb.setLayoutParams(thP);
          }
        });
    mini.addView(miniSlider, msParams);

    card.addView(mini, new LayoutParams(-1, -2));

    // Footer row: icon + title + subtitle + radio check
    LinearLayout foot = new LinearLayout(activity);
    foot.setOrientation(HORIZONTAL);
    foot.setGravity(Gravity.CENTER_VERTICAL);
    LayoutParams footP = new LayoutParams(-1, -2);
    footP.topMargin = dp(9);

    LinearLayout meta = new LinearLayout(activity);
    meta.setOrientation(VERTICAL);
    LinearLayout nameRow = new LinearLayout(activity);
    nameRow.setOrientation(HORIZONTAL);
    nameRow.setGravity(Gravity.CENTER_VERTICAL);
    ImageView icon = new ImageView(activity);
    icon.setImageResource(lightOption ? R.drawable.ic_settings_sun : R.drawable.ic_settings_moon);
    icon.setImageTintList(ColorStateList.valueOf(AppStyle.INK));
    icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    LayoutParams iconP = new LayoutParams(dp(13), dp(13));
    iconP.rightMargin = dp(4);
    nameRow.addView(icon, iconP);
    TextView name = text(lightOption ? "银室白" : "暗房黑", AppStyle.TYPE_BODY, AppStyle.INK);
    AppStyle.setTypeface(name, AppStyle.FONT_BOLD);
    nameRow.addView(name, new LayoutParams(-2, -2));
    meta.addView(nameRow, new LayoutParams(-1, -2));

    TextView sub =
        text(lightOption ? "阳极银 · 日间明室" : "哑光黑 · 零偏色修图", 10, AppStyle.MUTED);
    sub.setSingleLine(true);
    sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
    LayoutParams subP = new LayoutParams(-1, -2);
    subP.topMargin = dp(2);
    meta.addView(sub, subP);
    foot.addView(meta, new LayoutParams(0, -2, 1));

    // Chamfered square radio indicator
    FrameLayout radio = new FrameLayout(activity);
    GradientDrawable rBg = new GradientDrawable();
    rBg.setCornerRadius(dp(5));
    rBg.setColor(selected ? AppStyle.GOLD : Color.TRANSPARENT);
    rBg.setStroke(Math.max(1, dp(1.25f)), selected ? AppStyle.GOLD : AppStyle.OUTLINE);
    radio.setBackground(rBg);
    if (selected) {
      ImageView chk = new ImageView(activity);
      chk.setImageResource(R.drawable.ic_material_check);
      chk.setImageTintList(ColorStateList.valueOf(AppStyle.ON_ACCENT));
      chk.setPadding(dp(2), dp(2), dp(2), dp(2));
      radio.addView(chk, new FrameLayout.LayoutParams(-1, -1));
    }
    LayoutParams rP = new LayoutParams(dp(17), dp(17));
    rP.leftMargin = dp(6);
    foot.addView(radio, rP);
    card.addView(foot, footP);

    card.setOnClickListener(
        v -> {
          if (lightOption != AppStyle.isLight() && themeListener != null) {
            themeListener.onThemeSelected(lightOption);
          }
        });
    return card;
  }

  private View cacheRow() {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(14), dp(11), dp(14), dp(11));
    row.setMinimumHeight(dp(58));
    row.setClickable(true);
    row.setFocusable(true);
    row.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(AppStyle.RIPPLE),
            null,
            AppStyle.shape(activity, Color.WHITE, 0, false)));

    row.addView(leadIcon(R.drawable.ic_settings_storage), iconBoxParams());

    LinearLayout labels = new LinearLayout(activity);
    labels.setOrientation(VERTICAL);
    TextView name = text("本地缓存", AppStyle.TYPE_BODY, AppStyle.INK);
    AppStyle.setTypeface(name, AppStyle.FONT_SEMIBOLD);
    labels.addView(name, new LayoutParams(-1, -2));
    TextView note = text("缩略图与预览 · 不影响原片", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    LayoutParams noteP = new LayoutParams(-1, -2);
    noteP.topMargin = dp(2);
    labels.addView(note, noteP);
    row.addView(labels, new LayoutParams(0, -2, 1));

    row.addView(cacheValue, new LayoutParams(-2, -2));

    TextView clearTag = text("清理", AppStyle.TYPE_MICRO, AppStyle.INK);
    AppStyle.setTypeface(clearTag, AppStyle.FONT_SEMIBOLD);
    clearTag.setPadding(dp(9), dp(4), dp(9), dp(4));
    clearTag.setBackground(AppStyle.shape(activity, AppStyle.CONTROL, 6f, true));
    LayoutParams tagP = new LayoutParams(-2, -2);
    tagP.leftMargin = dp(8);
    row.addView(clearTag, tagP);

    row.setOnClickListener(v -> confirmClearCache());
    return row;
  }

  private FrameLayout leadIcon(int iconRes) {
    FrameLayout box = new FrameLayout(activity);
    box.setBackground(AppStyle.shape(activity, AppStyle.CONTROL, 8f, true));
    ImageView iv = new ImageView(activity);
    iv.setImageResource(iconRes);
    iv.setImageTintList(ColorStateList.valueOf(AppStyle.INK));
    iv.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    box.addView(iv, new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.CENTER));
    return box;
  }

  private LayoutParams iconBoxParams() {
    LayoutParams p = new LayoutParams(dp(28), dp(28));
    p.rightMargin = dp(11);
    return p;
  }

  void navigateBack() {
    close.run();
  }

  void setUpdateAvailable(boolean available) {
    if (aboutUpdateBadge != null) aboutUpdateBadge.setVisibility(available ? VISIBLE : GONE);
  }

  void refreshCacheSize() {
    if (cacheValue == null || clearingCache) return;
    final int job = ++cacheJobId;
    final File cacheDir = activity.getCacheDir();
    try {
      cacheIo.execute(
          () -> {
            long bytes = cacheBytes(cacheDir);
            post(
                () -> {
                  if (job != cacheJobId || clearingCache || cacheValue == null) return;
                  cacheValue.setText(formatSize(bytes));
                });
          });
    } catch (java.util.concurrent.RejectedExecutionException ignored) {
    }
  }

  private void confirmClearCache() {
    if (clearingCache) return;
    new Haptics.DialogBuilder(activity)
        .setTitle("清理缓存？")
        .setMessage("会删除缩略图与临时预览缓存，原照片、编辑参数及更新任务不受影响。")
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "清理",
            (d, n) -> {
              if (clearingCache) return;
              clearingCache = true;
              final int job = ++cacheJobId;
              if (cacheValue != null) cacheValue.setText("清理中...");
              final File cacheDir = activity.getCacheDir();
              try {
                cacheIo.execute(
                    () -> {
                      deleteCacheContents(cacheDir);
                      long remaining = cacheBytes(cacheDir);
                      post(
                          () -> {
                            clearingCache = false;
                            if (cacheCleared != null) cacheCleared.run();
                            if (job == cacheJobId && cacheValue != null) {
                              cacheValue.setText(formatSize(remaining));
                            }
                            if (!activity.isFinishing() && !activity.isDestroyed()) {
                              AppNotice.show(activity, "缓存已清理");
                            }
                          });
                    });
              } catch (java.util.concurrent.RejectedExecutionException ignored) {
                clearingCache = false;
              }
            })
        .show();
  }

  private View switchRow(
      int iconRes,
      String label,
      String detail,
      String key,
      boolean fallback,
      Runnable changed) {
    LinearLayout row = new LinearLayout(activity);
    row.setOrientation(HORIZONTAL);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(14), dp(10), dp(12), dp(10));
    row.addView(leadIcon(iconRes), iconBoxParams());

    LinearLayout labels = new LinearLayout(activity);
    labels.setOrientation(VERTICAL);
    TextView name = text(label, AppStyle.TYPE_BODY, AppStyle.INK);
    AppStyle.setTypeface(name, AppStyle.FONT_SEMIBOLD);
    labels.addView(name, new LayoutParams(-1, -2));
    TextView note = text(detail, AppStyle.TYPE_MICRO, AppStyle.MUTED);
    LayoutParams noteParams = new LayoutParams(-1, -2);
    noteParams.topMargin = dp(2);
    labels.addView(note, noteParams);
    row.addView(labels, new LayoutParams(0, -2, 1));

    Switch toggle = new Switch(activity);
    AppStyle.styleSwitch(toggle);
    toggle.setChecked(prefs.getBoolean(key, fallback));
    toggle.setOnCheckedChangeListener(
        (button, checked) -> {
          prefs.edit().putBoolean(key, checked).apply();
          if (changed != null) changed.run();
        });
    row.addView(toggle, new LayoutParams(-2, dp(44)));
    row.setFocusable(true);
    row.setOnClickListener(v -> toggle.toggle());
    return row;
  }

  private void section(LinearLayout content, String label, String meta, int topDp) {
    LinearLayout head = new LinearLayout(activity);
    head.setOrientation(HORIZONTAL);
    head.setGravity(Gravity.CENTER_VERTICAL);
    TextView title = text(label, AppStyle.TYPE_MICRO, AppStyle.MUTED);
    AppStyle.setTypeface(title, AppStyle.FONT_SEMIBOLD);
    head.addView(title, new LayoutParams(0, -2, 1));
    if (meta != null) {
      TextView right = text(meta, 10, AppStyle.MUTED);
      head.addView(right, new LayoutParams(-2, -2));
    }
    LayoutParams params = new LayoutParams(-1, -2);
    params.topMargin = dp(topDp);
    params.bottomMargin = dp(7);
    params.leftMargin = params.rightMargin = dp(2);
    content.addView(head, params);
  }

  private LinearLayout card(LinearLayout content) {
    LinearLayout card = new LinearLayout(activity);
    card.setOrientation(VERTICAL);
    card.setBackground(AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_MEDIUM, true));
    card.setClipToOutline(true);
    content.addView(card, new LayoutParams(-1, -2));
    return card;
  }

  private void divider(LinearLayout card) {
    View line = new View(activity);
    line.setBackgroundColor(AppStyle.DIVIDER);
    card.addView(line, new LayoutParams(-1, 1));
  }

  private static boolean isProtectedUpdateDir(File root, File child) {
    return child != null && root.equals(child.getParentFile()) && "updates".equals(child.getName());
  }

  private static long cacheBytes(File root) {
    if (root == null || !root.exists()) return 0;
    long total = 0;
    File[] children = root.listFiles();
    if (children == null) return 0;
    for (File child : children) {
      if (isProtectedUpdateDir(root, child)) continue;
      total += sizeOf(child);
    }
    return total;
  }

  private static long sizeOf(File file) {
    if (file == null || !file.exists()) return 0;
    if (file.isFile()) return file.length();
    long total = 0;
    File[] children = file.listFiles();
    if (children != null) for (File child : children) total += sizeOf(child);
    return total;
  }

  private static void deleteCacheContents(File root) {
    if (root == null || !root.exists()) return;
    File[] children = root.listFiles();
    if (children == null) return;
    for (File child : children) {
      if (isProtectedUpdateDir(root, child)) continue;
      if (child.isDirectory()) deleteContents(child);
      child.delete();
    }
  }

  private static void deleteContents(File file) {
    if (file == null || !file.exists()) return;
    File[] children = file.listFiles();
    if (children == null) return;
    for (File child : children) {
      if (child.isDirectory()) deleteContents(child);
      child.delete();
    }
  }

  private static String formatSize(long bytes) {
    if (bytes < 1024) return bytes + " B";
    double kb = bytes / 1024.0;
    if (kb < 1024) return String.format(java.util.Locale.ROOT, "%.0f KB", kb);
    double mb = kb / 1024.0;
    if (mb < 1024) return String.format(java.util.Locale.ROOT, "%.1f MB", mb);
    return String.format(java.util.Locale.ROOT, "%.1f GB", mb / 1024.0);
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

  private int dp(float value) {
    return AppStyle.dp(activity, value);
  }
}
