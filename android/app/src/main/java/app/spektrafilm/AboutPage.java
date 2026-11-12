// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Local About and statement pages. External links open only after an explicit tap. */
@SuppressLint(
    "ViewConstructor") // Programmatic page with required host actions; never XML-inflated.
final class AboutPage extends LinearLayout {
  static final String PROJECT_URL = "https://github.com/qing1989/SpektraLens";
  static final String ENGINE_URL = "https://github.com/JamesQiu2005/SpektraLab";
  static final String AUTHOR_URL = "https://xhslink.cn/o/AIvtVWQhXyF";

  private final Activity activity;
  private final Runnable close, licenses;
  private final TextView title;
  private final ScrollView overview, statement;
  private boolean showingStatement;

  AboutPage(
      Activity activity,
      String version,
      Runnable close,
      Runnable checkUpdates,
      Runnable licenses,
      Runnable diagnostics) {
    super(activity);
    this.activity = activity;
    this.close = close;
    this.licenses = licenses;
    setOrientation(VERTICAL);
    setBackgroundColor(AppStyle.BG);
    setAccessibilityPaneTitle("关于");
    setClickable(true);

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(16), dp(8), dp(16), dp(8));
    TextView back = action("返回", AppStyle.INK, AppStyle.CONTROL);
    back.setSingleLine(true);
    back.setPadding(dp(12), dp(10), dp(12), dp(10));
    int backWidth = Math.max(dp(64), (int) Math.ceil(back.getPaint().measureText("返回")) + dp(24));
    back.setContentDescription("返回上一页");
    back.setOnClickListener(v -> navigateBack());
    header.addView(back, new LayoutParams(backWidth, -2));
    title = text("关于", AppStyle.TYPE_HEADING, AppStyle.INK);
    title.setTypeface(null, Typeface.BOLD);
    title.setGravity(Gravity.CENTER);
    title.setAccessibilityHeading(true);
    header.addView(title, new LayoutParams(0, -2, 1));
    header.addView(new View(activity), new LayoutParams(backWidth, 1));
    addView(header, new LayoutParams(-1, -2));

    FrameLayout body = new FrameLayout(activity);
    addView(body, new LayoutParams(-1, 0, 1));
    overview = scroll();
    statement = scroll();
    body.addView(overview, new FrameLayout.LayoutParams(-1, -1));
    body.addView(statement, new FrameLayout.LayoutParams(-1, -1));
    statement.setVisibility(GONE);
    buildOverview(version, checkUpdates, diagnostics);
    buildStatement();
  }

  void navigateBack() {
    if (showingStatement) {
      showingStatement = false;
      statement.setVisibility(GONE);
      overview.setVisibility(VISIBLE);
      announcePage("关于");
    } else close.run();
  }

  private void openStatement() {
    showingStatement = true;
    overview.setVisibility(GONE);
    statement.setVisibility(VISIBLE);
    statement.scrollTo(0, 0);
    announcePage("免责声明");
  }

  private void announcePage(String name) {
    title.setText(name);
    setAccessibilityPaneTitle(name);
  }

  private void buildOverview(String version, Runnable checkUpdates, Runnable diagnostics) {
    LinearLayout content = content(overview);
    LinearLayout identity = column();
    identity.setGravity(Gravity.CENTER_HORIZONTAL);
    identity.setPadding(dp(12), dp(24), dp(12), dp(12));

    ImageView icon = new ImageView(activity);
    // 自适应图标当普通图片绘制时不会套遮罩，会露出整块 108 dp 出血并让图形缩到 72/108。
    // 这里改用带圆角、已裁掉出血的 logo 资源，观感与桌面图标一致。
    icon.setImageResource(R.drawable.ic_spektra_film_s_logo);
    icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
    icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    identity.addView(icon, new LayoutParams(dp(88), dp(88)));
    gap(identity, 18);
    TextView name = text(activity.getString(R.string.app_name), AppStyle.TYPE_BRAND, AppStyle.INK);
    name.setTypeface(null, Typeface.BOLD);
    name.setGravity(Gravity.CENTER);
    identity.addView(name, new LayoutParams(-1, -2));
    gap(identity, 12);

    LinearLayout badges = new LinearLayout(activity);
    badges.setGravity(Gravity.CENTER);
    TextView versionBadge = badge("v" + version, AppStyle.ACCENT, AppStyle.CONTROL);
    badges.addView(versionBadge, new LayoutParams(-2, -2));
    TextView licenseBadge = badge("GPL-3.0+", AppStyle.MUTED, AppStyle.SURFACE);
    AppStyle.action(licenseBadge, AppStyle.MUTED, AppStyle.SURFACE, AppStyle.R_MEDIUM);
    licenseBadge.setContentDescription("GPL-3.0-or-later，查看许可全文");
    licenseBadge.setOnClickListener(v -> licenses.run());
    LayoutParams licenseParams = new LayoutParams(-2, -2);
    licenseParams.leftMargin = dp(8);
    badges.addView(licenseBadge, licenseParams);
    identity.addView(badges, new LayoutParams(-1, -2));
    gap(identity, 18);

    TextView summary = text("随身的光谱胶片实验室\n本地显影 · 无账号 · 开源无广告", AppStyle.TYPE_TITLE, AppStyle.MUTED);
    summary.setGravity(Gravity.CENTER);
    summary.setLineSpacing(dp(6), 1);
    identity.addView(summary, new LayoutParams(-1, -2));
    gap(identity, 22);
    TextView update = action("检查更新", AppStyle.BG, AppStyle.ACCENT);
    update.setPadding(dp(24), dp(14), dp(24), dp(14));
    update.setOnClickListener(v -> checkUpdates.run());
    identity.addView(update, new LayoutParams(-2, -2));
    content.addView(identity, new LayoutParams(-1, -2));

    sectionLabel(content, "更多");
    LinearLayout links = card();
    linkRow(links, "项目地址", "github.com/qing1989/SpektraLens", () -> open(PROJECT_URL));
    divider(links);
    linkRow(links, "内核地址", "github.com/JamesQiu2005/SpektraLab", () -> open(ENGINE_URL));
    divider(links);
    linkRow(links, "作者小红书", "在小红书查看作者主页", () -> open(AUTHOR_URL));
    divider(links);
    linkRow(links, "免责声明", "使用说明、隐私与开源致谢", this::openStatement);
    content.addView(links, new LayoutParams(-1, -2));

    // There are no other direct contributors recorded in this repository. Upstream credits
    // remain in the statement and bundled licenses instead of inventing contributor entries.
    gap(content, 20);
    LinearLayout support = new LinearLayout(activity);
    support.setGravity(Gravity.CENTER);
    TextView licenseLink = action("许可全文", AppStyle.MUTED, android.graphics.Color.TRANSPARENT);
    licenseLink.setOnClickListener(v -> licenses.run());
    support.addView(licenseLink, new LayoutParams(0, dp(48), 1));
    TextView diagnosticLink = action("处理诊断", AppStyle.MUTED, android.graphics.Color.TRANSPARENT);
    diagnosticLink.setOnClickListener(v -> diagnostics.run());
    support.addView(diagnosticLink, new LayoutParams(0, dp(48), 1));
    content.addView(support, new LayoutParams(-1, -2));
    gap(content, 4);
    TextView network =
        text("仅在手动检查更新及下载更新包时联网\n外部链接由系统浏览器或对应应用打开", AppStyle.TYPE_LABEL, AppStyle.MUTED);
    network.setGravity(Gravity.CENTER);
    network.setLineSpacing(dp(4), 1);
    content.addView(network, new LayoutParams(-1, -2));
  }

  private void buildStatement() {
    LinearLayout content = content(statement);
    gap(content, 16);
    LinearLayout introduction = card();
    introduction.setPadding(dp(20), dp(20), dp(20), dp(20));
    TextView intro =
        paragraph("本页说明 SpektraLens 的用途、数据处理方式、开源来源与能力边界。" + "各组件的完整授权条件以「许可全文」中的原始文本为准。");
    introduction.addView(intro, new LayoutParams(-1, -2));
    content.addView(introduction, new LayoutParams(-1, -2));

    statementSection(
        content,
        1,
        "软件性质",
        "SpektraLens 是独立的 Android 本地照片编辑与光谱胶片模拟工具，基于 spektrafilm 模型和 SpektraLab 原生引擎，"
            + "使用 380–780 nm、5 nm 间隔的 81 波长数据进行计算。\n\n"
            + "本项目并非上游项目或胶片、相纸厂商的官方应用。胶片与相纸名称用于标识所模拟的材料，"
            + "不表示与相关品牌存在隶属、合作或背书关系。");
    statementSection(
        content,
        2,
        "本地处理与权限",
        "本软件无需账号，不上传照片，不收集设备标识或遥测数据。照片读取与显影在手机本地完成；"
            + "相册访问范围由你的系统授权决定，编辑记录保存在本机，导出的照片写入 Pictures / SpektraLens。\n\n"
            + "删除应用或清除应用数据可能移除本机编辑记录与导入副本，请自行保留原始照片和重要导出文件。");
    statementSection(
        content,
        3,
        "更新与外部链接",
        "仅在你手动检查更新及下载更新包时联网。更新清单来自本项目的固定 GitHub Release 地址，"
            + "APK 通过 HTTPS 下载并在安装前校验 SHA-256；安装仍需系统授权和你的确认，不会静默安装。\n\n"
            + "项目地址、内核地址和作者主页通过系统浏览器或对应应用打开，外部服务的数据处理方式以其自身说明为准。");
    statementSection(
        content,
        4,
        "模拟效果与设备限制",
        "显影结果是基于模型的数字模拟，不保证与某一卷实体胶片、冲洗工艺、扫描仪或显示设备完全一致。"
            + "颗粒、光晕、片边与日期水印属于模拟或呈现效果，日期文字不作为拍摄时间的证明。\n\n"
            + "RAW 使用 LibRaw 解码；编辑预览最长边为 1024 像素，导出重新读取原文件，支持 sRGB JPEG、16 位 PNG 与 16 位 TIFF。"
            + "极大图和高强度效果仍受设备内存、GPU 与系统限制，无法保证所有设备的处理速度与稳定性。"
            + "当前未开放裁切、批量处理和专业输出色域，场景纬度映射未移植。");
    statementSection(
        content,
        5,
        "照片与使用责任",
        "你导入照片的权利不会因使用本软件而转让给本项目。请确认你有权编辑、使用和发布相关照片，"
            + "并尊重原作者署名、肖像、隐私及其他相关权利。\n\n"
            + "内置示例照片与第三方素材保留各自许可证；本软件的开源许可不替代这些素材的使用条件。"
            + "发布模拟结果前，建议核对画面、颜色、日期及导出内容，并备份重要文件。");
    statementSection(
        content,
        6,
        "开源来源与致谢",
        "原始模型与彩色配置：Andrea Volpato / spektrafilm。\n"
            + "原生引擎：Hanze Qiu 与 SpektraLab 贡献者。\n"
            + "移动 GPU 参考：SAINTTaiYi / SpektraLab-Android v1.3.3j。\n"
            + "Android 客户端与 JNI：本项目移植。\n\n"
            + "程序使用 GPL-3.0-or-later；胶片、相纸配置使用 CC BY-SA 4.0，数据未经修改。"
            + "LibRaw、SwiftShader、Vulkan 及其他依赖保留各自许可证和原作者声明。\n\n"
            + "内置示例：Fronalpstock big.jpg，Hannes Röst，CC BY-SA 3.0。"
            + "胶卷图标由 SpektraLens 根据各款型号独立绘制，不包含历史第三方 PNG 图标。"
            + "完整版权、署名与授权文本可在下方「许可全文」中查看。");
    statementSection(
        content,
        7,
        "担保与责任范围",
        "本软件依据适用的开源许可证按现状提供，不承诺其功能、模拟结果或兼容性适合某一特定用途。"
            + "在适用法律允许的范围内，担保与责任限制以相关许可证为准。"
            + "本说明不限制开源许可证授予的权利，也不排除法律规定不得排除的责任。");
    gap(content, 24);
    TextView fullLicense = action("许可全文", AppStyle.INK, AppStyle.CONTROL);
    fullLicense.setPadding(dp(24), dp(14), dp(24), dp(14));
    fullLicense.setOnClickListener(v -> licenses.run());
    content.addView(fullLicense, new LayoutParams(-1, -2));
  }

  private void statementSection(LinearLayout parent, int number, String heading, String body) {
    gap(parent, 28);
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    TextView count = text(String.valueOf(number), AppStyle.TYPE_TITLE, AppStyle.MUTED);
    count.setGravity(Gravity.CENTER);
    count.setBackground(AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_MEDIUM, false));
    row.addView(count, new LayoutParams(dp(32), dp(32)));
    TextView label = text(heading, AppStyle.TYPE_HEADING, AppStyle.INK);
    label.setTypeface(null, Typeface.BOLD);
    label.setAccessibilityHeading(true);
    LayoutParams labelParams = new LayoutParams(0, -2, 1);
    labelParams.leftMargin = dp(12);
    row.addView(label, labelParams);
    parent.addView(row, new LayoutParams(-1, -2));
    gap(parent, 14);
    parent.addView(paragraph(body), new LayoutParams(-1, -2));
  }

  private void open(String url) {
    try {
      activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (android.content.ActivityNotFoundException error) {
      Toast.makeText(activity, "没有可打开此链接的应用", Toast.LENGTH_LONG).show();
    }
  }

  private ScrollView scroll() {
    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    return scroll;
  }

  private LinearLayout content(ScrollView scroll) {
    // A centered column keeps both small phones and landscape/tablet text readable.
    FrameLayout centered =
        new FrameLayout(activity) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            View child = getChildAt(0);
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) child.getLayoutParams();
            params.width = Math.min(MeasureSpec.getSize(widthSpec), dp(600));
            super.onMeasure(widthSpec, heightSpec);
          }
        };
    LinearLayout content = column();
    content.setPadding(dp(20), dp(8), dp(20), dp(32));
    centered.addView(
        content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    scroll.addView(centered, new ScrollView.LayoutParams(-1, -2));
    return content;
  }

  private LinearLayout column() {
    LinearLayout layout = new LinearLayout(activity);
    layout.setOrientation(VERTICAL);
    return layout;
  }

  private LinearLayout card() {
    LinearLayout card = column();
    card.setBackground(AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_LARGE, false));
    return card;
  }

  private void sectionLabel(LinearLayout parent, String name) {
    gap(parent, 22);
    TextView label = text(name, AppStyle.TYPE_HEADING, AppStyle.MUTED);
    label.setTypeface(null, Typeface.BOLD);
    label.setAccessibilityHeading(true);
    label.setPadding(dp(4), 0, dp(4), dp(12));
    parent.addView(label, new LayoutParams(-1, -2));
  }

  private void linkRow(LinearLayout parent, String name, String detail, Runnable click) {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(dp(20), dp(17), dp(20), dp(17));
    row.setMinimumHeight(dp(76));
    row.setBackground(
        new android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x24ffffff),
            null,
            AppStyle.shape(activity, android.graphics.Color.WHITE, AppStyle.R_LARGE, false)));
    row.setFocusable(true);
    row.setOnClickListener(v -> click.run());
    LinearLayout copy = column();
    row.addView(copy, new LayoutParams(0, -2, 1));
    TextView label = text(name, AppStyle.TYPE_TITLE, AppStyle.INK);
    label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    copy.addView(label, new LayoutParams(-1, -2));
    gap(copy, 5);
    TextView subtitle = text(detail, AppStyle.TYPE_LABEL, AppStyle.MUTED);
    subtitle.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    copy.addView(subtitle, new LayoutParams(-1, -2));
    ImageView arrow = new ImageView(activity);
    arrow.setImageResource(R.drawable.about_chevron_right);
    arrow.setImageTintList(android.content.res.ColorStateList.valueOf(AppStyle.MUTED));
    arrow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    LayoutParams arrowParams = new LayoutParams(dp(20), dp(20));
    arrowParams.setMarginStart(dp(16));
    row.addView(arrow, arrowParams);
    row.setContentDescription(name + "，" + detail);
    parent.addView(row, new LayoutParams(-1, -2));
  }

  private void divider(LinearLayout parent) {
    View line = new View(activity);
    line.setBackgroundColor(AppStyle.OUTLINE);
    LayoutParams params = new LayoutParams(-1, dp(1));
    params.leftMargin = params.rightMargin = dp(20);
    parent.addView(line, params);
  }

  private TextView badge(String label, int color, int fill) {
    TextView view = text(label, AppStyle.TYPE_LABEL, color);
    view.setPadding(dp(14), dp(9), dp(14), dp(9));
    view.setBackground(AppStyle.shape(activity, fill, AppStyle.R_MEDIUM, false));
    return view;
  }

  private TextView action(String label, int color, int fill) {
    TextView view = text(label, AppStyle.TYPE_TITLE, color);
    view.setGravity(Gravity.CENTER);
    view.setMinHeight(dp(44));
    AppStyle.action(view, color, fill, AppStyle.R_MEDIUM);
    view.setPadding(dp(16), dp(10), dp(16), dp(10));
    return view;
  }

  private TextView paragraph(String value) {
    TextView view = text(value, AppStyle.TYPE_TITLE, AppStyle.MUTED);
    view.setLineSpacing(dp(7), 1);
    return view;
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(activity);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    return view;
  }

  private void gap(LinearLayout parent, int height) {
    parent.addView(new View(activity), new LayoutParams(1, dp(height)));
  }

  private int dp(int value) {
    return AppStyle.dp(activity, value);
  }
}
