// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executor;

/**
 * Voluntary WeChat appreciation flow. The QR resource is injected only into builds that provide a
 * private asset; this page performs no payment, network, order or telemetry work.
 */
@SuppressLint("ViewConstructor")
final class SponsorPage extends FrameLayout {
  private final Activity activity;
  private final int qrResource;
  private final Executor worker;
  private final Runnable close;
  private boolean qrStage;
  private boolean saving;
  private boolean released;
  private Bitmap qrBitmap;
  private TextView saveButton;

  SponsorPage(Activity activity, int qrResource, Executor worker, Runnable close) {
    super(activity);
    this.activity = activity;
    this.qrResource = qrResource;
    this.worker = worker;
    this.close = close;
    setAccessibilityPaneTitle("赞助开发者");
    showIntro();
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    if (!qrStage) close.run();
    return true;
  }

  void navigateBack() {
    if (qrStage) showIntro();
    else close.run();
  }

  void release() {
    released = true;
    releaseQrBitmap();
    removeAllViews();
  }

  private void showIntro() {
    if (qrStage) releaseQrBitmap();
    qrStage = false;
    saveButton = null;
    removeAllViews();
    setBackgroundColor(0xb8000000);
    setAccessibilityPaneTitle("赞助开发者");
    setClickable(true);

    MaxHeightScrollView sheet = new MaxHeightScrollView(activity, dp(620));
    sheet.setFillViewport(false);
    sheet.setClipToOutline(true);
    sheet.setVerticalScrollBarEnabled(false);
    sheet.setOverScrollMode(View.OVER_SCROLL_NEVER);
    sheet.setBackground(
        AppStyle.shape(activity, AppStyle.SURFACE, AppStyle.R_LARGE, false));

    LinearLayout content = vertical();
    content.setPadding(dp(22), dp(18), dp(22), dp(28));
    sheet.addView(content, new ScrollView.LayoutParams(-1, -2));

    FrameLayout grabberBox = new FrameLayout(activity);
    View grabber = new View(activity);
    grabber.setBackground(AppStyle.shape(activity, AppStyle.GRIP, 2, false));
    grabberBox.addView(
        grabber, new FrameLayout.LayoutParams(dp(40), dp(4), Gravity.CENTER));
    content.addView(grabberBox, new LinearLayout.LayoutParams(-1, dp(4)));
    gap(content, 20);

    TextView title = text("赞助开发者", AppStyle.TYPE_HEADING, AppStyle.INK);
    AppStyle.setTypeface(title, AppStyle.FONT_BOLD);
    title.setAccessibilityHeading(true);
    content.addView(title, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 12);

    TextView lede =
        paragraph(
            "如果你喜欢这款应用，期待你自愿赞助后续开发，这对开发者来说是很大的帮助。");
    content.addView(lede, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 20);

    LinearLayout facts = vertical();
    facts.setPadding(dp(16), dp(3), dp(16), dp(3));
    AppStyle.surface(facts, AppStyle.CONTROL, AppStyle.R_MEDIUM, false);
    fact(facts, "完全自愿", "不影响功能，也不解锁内容");
    divider(facts);
    fact(facts, "隐私优先", "App 不读取、保存或上传付款信息");
    divider(facts);
    fact(facts, "微信赞赏", "由微信完成付款，单笔上限 ¥50");
    content.addView(facts, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 20);

    TextView signature =
        text(
            "请仅从 SpektraLens 官方签名的 Release 获取本应用。"
                + "付款过程完全离线，不依赖网页或 GitHub Pages；实际付款由微信处理。",
            AppStyle.TYPE_LABEL,
            AppStyle.VALUE);
    signature.setLineSpacing(dp(4), 1);
    content.addView(signature, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 20);

    TextView openQr =
        action("查看微信赞赏码", AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    AppStyle.setTypeface(openQr, AppStyle.FONT_BOLD);
    openQr.setTag("sponsor.open_qr");
    openQr.setOnClickListener(view -> showQr());
    content.addView(openQr, new LinearLayout.LayoutParams(-1, dp(50)));
    gap(content, 8);

    TextView later = action("以后再说", AppStyle.MUTED, Color.TRANSPARENT);
    later.setTag("sponsor.later");
    later.setOnClickListener(view -> close.run());
    content.addView(later, new LinearLayout.LayoutParams(-1, dp(44)));

    FrameLayout.LayoutParams sheetLayout =
        new FrameLayout.LayoutParams(
            Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(600)),
            -2,
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    sheetLayout.setMargins(dp(12), 0, dp(12), dp(10));
    addView(sheet, sheetLayout);
  }

  private void showQr() {
    qrStage = true;
    removeAllViews();
    setBackgroundColor(AppStyle.BG);
    setAccessibilityPaneTitle("微信赞赏");
    setClickable(false);

    LinearLayout page = vertical();
    page.setBackgroundColor(AppStyle.BG);
    addView(page, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout header = new LinearLayout(activity);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(dp(16), dp(8), dp(16), dp(8));
    ImageButton back =
        AppStyle.iconButton(activity, R.drawable.ic_ui_back, "返回赞助说明", Color.WHITE);
    back.setOnClickListener(view -> showIntro());
    header.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));

    page.addView(header, new LinearLayout.LayoutParams(-1, -2));

    ScrollView scroll = new ScrollView(activity);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setVerticalScrollBarEnabled(false);
    page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

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
    scroll.addView(centered, new ScrollView.LayoutParams(-1, -2));

    LinearLayout content = vertical();
    content.setGravity(Gravity.CENTER_HORIZONTAL);
    content.setPadding(dp(24), dp(16), dp(24), dp(34));
    centered.addView(
        content, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));

    TextView heading = text("感谢你支持 SpektraLens", AppStyle.TYPE_HEADING, AppStyle.INK);
    heading.setGravity(Gravity.CENTER);
    AppStyle.setTypeface(heading, AppStyle.FONT_BOLD);
    content.addView(heading, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 8);

    TextView sub =
        text(
            "赞赏完全自愿，不影响任何功能",
            AppStyle.TYPE_LABEL,
            AppStyle.MUTED);
    sub.setGravity(Gravity.CENTER);
    sub.setLineSpacing(dp(4), 1);
    content.addView(sub, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 22);

    FrameLayout qrCard = new FrameLayout(activity);
    qrCard.setClipToOutline(true);
    qrCard.setBackground(AppStyle.shape(activity, Color.WHITE, 24, false));
    int cardSize = Math.min(dp(340), getResources().getDisplayMetrics().widthPixels - dp(48));
    qrBitmap = decodeQr(qrResource);
    ImageView image = new ImageView(activity);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setContentDescription("微信赞赏码");
    image.setImageBitmap(qrBitmap);
    qrCard.addView(image, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
    content.addView(qrCard, new LinearLayout.LayoutParams(cardSize, cardSize));
    gap(content, 20);

    saveButton =
        action("保存赞赏码", AppStyle.ON_PRIMARY, AppStyle.PRIMARY);
    AppStyle.setTypeface(saveButton, AppStyle.FONT_BOLD);
    saveButton.setTag("sponsor.qr.save");
    saveButton.setOnClickListener(view -> saveQr());
    if (saving) {
      saveButton.setText("正在保存…");
      saveButton.setEnabled(false);
    }
    content.addView(saveButton, new LinearLayout.LayoutParams(-1, dp(50)));
    gap(content, 18);

    LinearLayout steps = vertical();
    steps.setPadding(dp(18), dp(16), dp(18), dp(16));
    AppStyle.surface(steps, AppStyle.SURFACE, AppStyle.R_MEDIUM, false);
    step(steps, "1", "保存到手机相册");
    step(steps, "2", "打开微信“扫一扫”，从相册选择");
    step(steps, "3", "核对收款信息并完成赞赏");
    content.addView(steps, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 14);

    TextView privacy =
        text(
            "SpektraLens 不会读取、保存或上传你的付款信息。\n"
                + "请仅使用 SpektraLens 官方签名的 Release。",
            AppStyle.TYPE_LABEL,
            AppStyle.MUTED);
    privacy.setGravity(Gravity.CENTER);
    privacy.setLineSpacing(dp(4), 1);
    content.addView(privacy, new LinearLayout.LayoutParams(-1, -2));
  }

  private void saveQr() {
    if (saving || released) return;
    saving = true;
    if (saveButton != null) {
      saveButton.setEnabled(false);
      saveButton.setText("正在保存…");
    }
    final int resource = qrResource;
    worker.execute(
        () -> {
          Uri uri = null;
          Exception failure = null;
          try {
            String name =
                "SpektraLens_WeChat_Sponsor_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                    + ".jpg";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            values.put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "Pictures/" + activity.getString(R.string.app_name));
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri =
                activity
                    .getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("无法创建相册条目");
            try (InputStream in = activity.getResources().openRawResource(resource);
                OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
              if (out == null) throw new IOException("无法写入相册");
              copy(in, out);
            }
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            if (activity.getContentResolver().update(uri, values, null, null) != 1) {
              throw new IOException("无法完成相册写入");
            }
          } catch (Exception error) {
            failure = error;
            if (uri != null) {
              try {
                activity.getContentResolver().delete(uri, null, null);
              } catch (RuntimeException ignored) {
                // Preserve the original error.
              }
            }
          }
          final Exception result = failure;
          activity.runOnUiThread(
              () -> {
                saving = false;
                if (!released && saveButton != null) {
                  saveButton.setText("保存赞赏码");
                  saveButton.setEnabled(true);
                }
                if (activity.isFinishing() || activity.isDestroyed()) return;
                AppNotice.showLong(
                    activity,
                    result == null
                        ? "已保存到 Pictures/SpektraLens"
                        : "保存失败：" + result.getMessage());
              });
        });
  }

  private Bitmap decodeQr(int resource) {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
    try (InputStream input = activity.getResources().openRawResource(resource)) {
      Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
      if (bitmap == null) throw new IllegalStateException("赞赏码图片无法解码");
      return bitmap;
    } catch (IOException error) {
      throw new IllegalStateException("赞赏码图片读取失败", error);
    }
  }

  private void releaseQrBitmap() {
    Bitmap bitmap = qrBitmap;
    qrBitmap = null;
    if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
  }

  private void fact(LinearLayout parent, String label, String description) {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(14), 0, dp(14));
    TextView name = text(label, AppStyle.TYPE_BODY, AppStyle.INK);
    AppStyle.setTypeface(name, AppStyle.FONT_BOLD);
    row.addView(name, new LinearLayout.LayoutParams(dp(76), -2));
    TextView detail = text(description, AppStyle.TYPE_LABEL, AppStyle.MUTED);
    detail.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  private void step(LinearLayout parent, String number, String label) {
    LinearLayout row = new LinearLayout(activity);
    row.setGravity(Gravity.CENTER_VERTICAL);
    if (parent.getChildCount() > 0) row.setPadding(0, dp(10), 0, 0);
    TextView count = text(number, AppStyle.TYPE_MICRO, AppStyle.GOLD);
    AppStyle.setNumericTypeface(count);
    count.setGravity(Gravity.CENTER);
    count.setBackground(
        AppStyle.shape(activity, AppStyle.withAlpha(AppStyle.GOLD, .10f), 11, false));
    row.addView(count, new LinearLayout.LayoutParams(dp(22), dp(22)));
    TextView copy = text(label, AppStyle.TYPE_LABEL, AppStyle.VALUE);
    LinearLayout.LayoutParams copyLayout = new LinearLayout.LayoutParams(0, -2, 1);
    copyLayout.leftMargin = dp(10);
    row.addView(copy, copyLayout);
    parent.addView(row, new LinearLayout.LayoutParams(-1, -2));
  }

  private void divider(LinearLayout parent) {
    View line = new View(activity);
    line.setBackgroundColor(AppStyle.DIVIDER);
    parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  private LinearLayout vertical() {
    LinearLayout view = new LinearLayout(activity);
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  private TextView action(String value, int foreground, int fill) {
    TextView view = text(value, AppStyle.TYPE_TITLE, foreground);
    view.setGravity(Gravity.CENTER);
    view.setMinHeight(dp(44));
    AppStyle.pillAction(view, foreground, fill);
    view.setPadding(dp(16), dp(10), dp(16), dp(10));
    return view;
  }

  private TextView paragraph(String value) {
    TextView view = text(value, AppStyle.TYPE_TITLE, AppStyle.MUTED);
    view.setLineSpacing(dp(6), 1);
    return view;
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

  private void gap(LinearLayout parent, int height) {
    parent.addView(new View(activity), new LinearLayout.LayoutParams(1, dp(height)));
  }

  private int dp(float value) {
    return AppStyle.dp(activity, value);
  }

  private static void copy(InputStream input, OutputStream output) throws IOException {
    byte[] buffer = new byte[64 * 1024];
    int count;
    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
  }

  private final class MaxHeightScrollView extends ScrollView {
    private final int preferredMinimumHeight;

    MaxHeightScrollView(Activity context, int preferredMinimumHeight) {
      super(context);
      this.preferredMinimumHeight = preferredMinimumHeight;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int available =
          MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
              ? getResources().getDisplayMetrics().heightPixels
              : MeasureSpec.getSize(heightSpec);
      int viewport = getResources().getDisplayMetrics().heightPixels - dp(36);
      int maximum = Math.max(dp(120), Math.min(available, viewport));
      super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(maximum, MeasureSpec.AT_MOST));
      int minimum = Math.min(preferredMinimumHeight, maximum);
      if (getMeasuredHeight() < minimum) {
        setMeasuredDimension(getMeasuredWidth(), minimum);
      }
    }
  }
}
