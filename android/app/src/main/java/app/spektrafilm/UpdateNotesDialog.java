// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.TypefaceSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Content-sized update notice; only the body scrolls and the actions always remain visible. */
final class UpdateNotesDialog extends Dialog {
  private static final Pattern EMPHASIS = Pattern.compile("\\*\\*(.+?)\\*\\*|`([^`]+)`");
  private final Activity activity;
  private final BodyAndActions root;
  private boolean submitted;
  private final View.OnLayoutChangeListener windowChanged =
      (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) resize();
      };

  UpdateNotesDialog(Activity activity, String current, UpdateManifest manifest, Runnable download) {
    super(activity);
    this.activity = activity;
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    Haptics.install(getWindow());
    setCanceledOnTouchOutside(true);
    root = new BodyAndActions();
    root.setTag("update.dialog");
    AppStyle.surface(root, AppStyle.SURFACE, AppStyle.R_LARGE, true);

    ScrollView scroll = new ScrollView(getContext());
    scroll.setTag("update.scroll");
    scroll.setFillViewport(false);
    LinearLayout body = column();
    body.setPadding(dp(20), dp(20), dp(20), dp(16));
    LinearLayout topRow = new LinearLayout(getContext());
    topRow.setOrientation(LinearLayout.HORIZONTAL);
    topRow.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout titles = column();
    TextView title = text("发现新版本", AppStyle.TYPE_TITLE, AppStyle.INK);
    AppStyle.setTypeface(title, AppStyle.FONT_BOLD);
    title.setAccessibilityHeading(true);
    titles.addView(title);
    gap(titles, 2);
    TextView version = text("v" + manifest.versionName, 22, AppStyle.INK);
    AppStyle.setTypeface(version, AppStyle.FONT_BOLD);
    version.setFontFeatureSettings("tnum");
    version.setLetterSpacing(-.03f);
    titles.addView(version);
    topRow.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
    if (manifest.sizeBytes != UpdateManifest.MAX_APK_BYTES) {
      TextView sizePill =
          text(
              String.format(Locale.CHINA, "%.1f MiB", manifest.sizeBytes / 1048576.0),
              AppStyle.TYPE_MICRO,
              AppStyle.GOLD);
      AppStyle.setNumericTypeface(sizePill);
      AppStyle.setTypeface(sizePill, AppStyle.FONT_SEMIBOLD);
      sizePill.setPadding(dp(10), dp(4), dp(10), dp(4));
      android.graphics.drawable.GradientDrawable pillBg =
          AppStyle.shape(getContext(), AppStyle.ACCENT_SOFT, 7f, false);
      pillBg.setStroke(Math.max(1, dp(.85f)), AppStyle.ACCENT_BORDER);
      sizePill.setBackground(pillBg);
      topRow.addView(sizePill, new LinearLayout.LayoutParams(-2, -2));
    }
    body.addView(topRow, new LinearLayout.LayoutParams(-1, -2));
    gap(body, 6);
    TextView currentInfo =
        text("当前 v" + current + " · 校验 SHA-256 后由系统确认安装", AppStyle.TYPE_MICRO, AppStyle.MUTED);
    AppStyle.setNumericTypeface(currentInfo);
    body.addView(currentInfo);
    gap(body, 14);
    TextView notes = text("", AppStyle.TYPE_BODY, AppStyle.INK);
    notes.setTag("update.notes");
    notes.setLineSpacing(dp(5), 1);
    notes.setPadding(dp(12), dp(10), dp(12), dp(10));
    notes.setBackground(AppStyle.shape(getContext(), AppStyle.CONTROL, 9f, true));
    SpannableStringBuilder paragraphs = new SpannableStringBuilder();
    for (UpdateNotes.Line line : UpdateNotes.parse(manifest.releaseNotes)) {
      if (paragraphs.length() > 0) paragraphs.append("\n\n");
      int start = paragraphs.length();
      boolean bullet = line.marker.equals("•");
      if (!line.marker.isEmpty() && !bullet) paragraphs.append(line.marker).append(" ");
      paragraphs.append(emphasize(line.text));
      if (line.heading) {
        paragraphs.setSpan(new TypefaceSpan(AppStyle.typeface(activity, AppStyle.FONT_BOLD)),
            start, paragraphs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        paragraphs.setSpan(new ForegroundColorSpan(AppStyle.INK),
            start, paragraphs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
      if (bullet) {
        paragraphs.setSpan(new BulletSpan(dp(8), AppStyle.MUTED, dp(2)),
            start, paragraphs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      } else if (!line.marker.isEmpty()) {
        int indent = (int) Math.ceil(notes.getPaint().measureText(line.marker + " "));
        paragraphs.setSpan(new LeadingMarginSpan.Standard(0, indent),
            start, paragraphs.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
    // A single text layout stays bounded even for a large, valid 64 KiB release log.
    notes.setText(paragraphs);
    body.addView(notes, new LinearLayout.LayoutParams(-1, -2));
    scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    root.addView(AppStyle.wrapScrollFade(getContext(), scroll, AppStyle.SURFACE, 28, 28, true));

    LinearLayout footer = column();
    footer.setTag("update.actions");
    View divider = new View(getContext());
    divider.setBackgroundColor(AppStyle.DIVIDER);
    footer.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));
    LinearLayout actions = new LinearLayout(getContext());
    actions.setPadding(dp(18), dp(14), dp(18), dp(18));
    TextView later = action("稍后", "update.later", false, this::dismiss);
    TextView install = action("下载", "update.download", true, () -> {
      if (submitted) return;
      submitted = true;
      dismiss();
      download.run();
    });
    actions.addView(later, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout.LayoutParams primary = new LinearLayout.LayoutParams(0, -2, 1);
    primary.leftMargin = dp(12);
    actions.addView(install, primary);
    footer.addView(actions);
    root.addView(footer);
    setContentView(root);
  }

  private SpannableStringBuilder emphasize(String source) {
    SpannableStringBuilder result = new SpannableStringBuilder();
    Matcher match = EMPHASIS.matcher(source);
    int previous = 0;
    while (match.find()) {
      result.append(source, previous, match.start());
      int start = result.length();
      boolean bold = match.group(1) != null;
      result.append(bold ? match.group(1) : match.group(2));
      result.setSpan(
          bold
              ? new TypefaceSpan(AppStyle.typeface(activity, AppStyle.FONT_BOLD))
              : new TypefaceSpan(AppStyle.typeface(activity, AppStyle.FONT_MEDIUM)),
          start, result.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      previous = match.end();
    }
    result.append(source, previous, source.length());
    return result;
  }

  private TextView action(String label, String tag, boolean primary, Runnable click) {
    TextView button = text(label, AppStyle.TYPE_TITLE, AppStyle.INK);
    button.setTag(tag);
    button.setGravity(Gravity.CENTER);
    button.setSingleLine(true);
    button.setPadding(dp(12), dp(12), dp(12), dp(12));
    button.setMinHeight(dp(AppStyle.TOUCH_PRIMARY));
    AppStyle.pillAction(
        button,
        primary ? AppStyle.ON_PRIMARY : AppStyle.INK,
        primary ? AppStyle.PRIMARY : AppStyle.CONTROL);
    AppStyle.setTypeface(
        button, primary ? AppStyle.FONT_BOLD : AppStyle.FONT_SEMIBOLD);
    button.setOnClickListener(view -> click.run());
    button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override
      public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        info.setClassName("android.widget.Button");
      }
    });
    return button;
  }

  private TextView text(String label, int size, int color) {
    TextView view = new TextView(getContext());
    view.setText(label);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    AppStyle.setTypeface(view, AppStyle.FONT_REGULAR);
    return view;
  }

  private LinearLayout column() {
    LinearLayout layout = new LinearLayout(getContext());
    layout.setOrientation(LinearLayout.VERTICAL);
    return layout;
  }

  private int dp(float value) {
    return AppStyle.dp(getContext(), value);
  }

  private void gap(LinearLayout parent, int height) {
    parent.addView(new View(getContext()), new LinearLayout.LayoutParams(1, dp(height)));
  }

  @Override
  public void show() {
    super.show();
    Window window = getWindow();
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    window.setGravity(Gravity.CENTER);
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    window.setDimAmount(AppStyle.DIM_AMOUNT);
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
    root.maxHeight = Math.max(1, Math.round(height * .72f));
    root.requestLayout();
    int maxDp = FoldableLayout.updateDialogMaxDp(FoldableUi.windowWidthDp(activity));
    getWindow().setLayout(FoldableUi.constrainedWidthPx(getContext(), width, maxDp, 16), -2);
  }

  @Override
  protected void onStop() {
    activity.getWindow().getDecorView().removeOnLayoutChangeListener(windowChanged);
    super.onStop();
  }

  /** Measure actions first so long text can never push them out of the window. */
  @SuppressLint("ViewConstructor")
  private final class BodyAndActions extends ViewGroup {
    int maxHeight = Integer.MAX_VALUE;

    BodyAndActions() {
      super(UpdateNotesDialog.this.getContext());
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec);
      int ceiling = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
          ? maxHeight : Math.min(maxHeight, MeasureSpec.getSize(heightSpec));
      int exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
      View body = getChildAt(0), footer = getChildAt(1);
      footer.measure(exactWidth, MeasureSpec.makeMeasureSpec(ceiling, MeasureSpec.AT_MOST));
      int remaining = Math.max(0, ceiling - footer.getMeasuredHeight());
      body.measure(exactWidth, MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.AT_MOST));
      setMeasuredDimension(width, footer.getMeasuredHeight() + body.getMeasuredHeight());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      View body = getChildAt(0), footer = getChildAt(1);
      body.layout(0, 0, getWidth(), body.getMeasuredHeight());
      footer.layout(0, body.getMeasuredHeight(), getWidth(), getHeight());
    }
  }
}
