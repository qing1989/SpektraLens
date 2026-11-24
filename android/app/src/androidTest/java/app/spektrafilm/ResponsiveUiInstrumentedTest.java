// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.test.ActivityInstrumentationTestCase2;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** Real font measurement at narrow widths, large fonts and high density; no global settings. */
public final class ResponsiveUiInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  public ResponsiveUiInstrumentedTest() {
    super(MainActivity.class);
  }

  private void main(Runnable action) {
    Throwable[] failure = {null};
    getInstrumentation().runOnMainSync(
        () -> {
          try { action.run(); }
          catch (Throwable error) { failure[0] = error; }
        });
    if (failure[0] instanceof AssertionError) throw (AssertionError) failure[0];
    if (failure[0] != null) throw new RuntimeException(failure[0]);
  }

  private Context scaled(float fontScale) {
    Context base = getInstrumentation().getTargetContext();
    Configuration configuration = new Configuration(base.getResources().getConfiguration());
    configuration.densityDpi = 480;
    configuration.fontScale = fontScale;
    return base.createConfigurationContext(configuration);
  }

  private TextView action(Context context, String text, int size) {
    TextView label = new TextView(context);
    label.setText(text);
    label.setTextSize(size);
    label.setIncludeFontPadding(false);
    label.setPadding(AppStyle.dp(context, 14), AppStyle.dp(context, 8),
        AppStyle.dp(context, 14), AppStyle.dp(context, 8));
    AppStyle.setTypeface(label, AppStyle.FONT_BOLD);
    return label;
  }

  private void measure(View view, int width, int height) {
    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
    view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
  }

  private void fullText(TextView view) {
    assertNotNull(view.getLayout());
    for (int line = 0; line < view.getLineCount(); line++)
      assertEquals("No label is ellipsized", 0, view.getLayout().getEllipsisCount(line));
    assertEquals(view.getText().length(), view.getLayout().getLineEnd(view.getLineCount() - 1));
    assertTrue("All lines fit vertically", view.getHeight() >= view.getLayout().getHeight()
        + view.getPaddingTop() + view.getPaddingBottom());
  }

  public void testBatchActionsStackAndGrowWithoutShrinkingText() {
    for (float scale : new float[] {1.5f, 2f}) {
      Context context = scaled(scale);
      main(
          () -> {
            TextView cancel = action(context, "取消", AppStyle.TYPE_BODY);
            TextView confirm = action(context, "确认粘贴 · 20 张", AppStyle.TYPE_BODY);
            float originalSize = confirm.getTextSize();
            AdaptiveActionRow row = new AdaptiveActionRow(context, false, 12);
            row.addAction(cancel, cancel, 96);
            row.addAction(confirm, confirm, 0);
            measure(row, AppStyle.dp(context, 272), AppStyle.dp(context, 600));
            assertEquals(LinearLayout.VERTICAL, row.getOrientation());
            assertTrue(confirm.getTop() >= cancel.getBottom() + AppStyle.dp(context, 12));
            assertEquals(cancel.getWidth(), confirm.getWidth());
            assertEquals(originalSize, confirm.getTextSize(), .01f);
            fullText(cancel);
            fullText(confirm);
            // Widening the same row returns it to a single line of actions.
            measure(row, AppStyle.dp(context, 600), AppStyle.dp(context, 600));
            assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
            assertTrue(confirm.getLeft() >= cancel.getRight() + AppStyle.dp(context, 12));
            fullText(confirm);
          });
    }
  }

  public void testNarrowProgressKeepsFullCountsAndCentredText() {
    Context context = scaled(2f);
    main(
        () -> {
          TextView button = action(context, "开始导入照片", AppStyle.TYPE_TITLE);
          TextView cancel = action(context, "取消", AppStyle.TYPE_MICRO);
          cancel.setPadding(AppStyle.dp(context, 4), 0, AppStyle.dp(context, 4), 0);
          cancel.setMinHeight(AppStyle.dp(context, AppStyle.TOUCH_MIN));
          ImportButtonLayout slot = new ImportButtonLayout(context, button, cancel);
          button.setTextSize(AppStyle.TYPE_BODY);
          button.setBackground(new ImportProgressDrawable(context));
          slot.setLabel("正在导入 20 / 20", "20 / 20");
          measure(slot, AppStyle.dp(context, 280), AppStyle.dp(context, 300));
          assertEquals("20 / 20", button.getText().toString());
          assertEquals("正在导入 20 / 20", button.getContentDescription().toString());
          assertEquals(Gravity.CENTER, button.getGravity());
          assertEquals(button.getPaddingLeft(), button.getPaddingRight());
          assertTrue(cancel.getWidth() >= AppStyle.dp(context, AppStyle.TOUCH_MIN));
          assertTrue(cancel.getHeight() >= AppStyle.dp(context, AppStyle.TOUCH_MIN));
          fullText(button);
          fullText(cancel);
        });
  }

  public void testSwitchThumbKeepsTheSameGapAtBothEnds() {
    Context context = scaled(1f);
    main(
        () -> {
          int inset = AppStyle.dp(context, 4);
          for (int direction : new int[] {View.LAYOUT_DIRECTION_LTR, View.LAYOUT_DIRECTION_RTL}) {
            Switch control = new Switch(context);
            AppStyle.styleSwitch(control);
            control.setLayoutDirection(direction);
            measure(control, AppStyle.dp(context, 96), AppStyle.dp(context, 48));
            for (boolean checked : new boolean[] {false, true}) {
              control.setChecked(checked);
              control.jumpDrawablesToCurrentState();
              control.draw(new Canvas());
              Rect track = control.getTrackDrawable().getBounds();
              Rect thumb = control.getThumbDrawable().getBounds();
              boolean onRight = checked != (direction == View.LAYOUT_DIRECTION_RTL);
              assertEquals(inset, onRight ? track.right - thumb.right : thumb.left - track.left);
              assertEquals(AppStyle.dp(context, 20), thumb.width());
            }
          }
        });
  }

  public void testPhotoStagePreservesPreviewPixelsInBothThemes() {
    Context context = scaled(1f);
    main(
        () -> {
          Bitmap before = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
          Bitmap developed = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
          Bitmap transparent = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
          int originalColor = 0xff2555a1;
          int developedColor = 0xff441e12;
          before.eraseColor(originalColor);
          developed.eraseColor(developedColor);
          developed.setHasAlpha(false);
          transparent.eraseColor(Color.TRANSPARENT);
          PhotoView preview = new PhotoView(context);
          int size = AppStyle.dp(context, 144);
          measure(preview, size, size);
          Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
          Canvas canvas = new Canvas(output);
          boolean previousTheme = AppStyle.isLight();
          try {
            for (boolean light : new boolean[] {true, false}) {
              AppStyle.applyTheme(light);
              preview.compare(false);
              preview.images(before, null);
              preview.draw(canvas);
              assertEquals("Source preview", originalColor, output.getPixel(size / 4, size / 2));

              preview.images(before, developed);
              preview.draw(canvas);
              assertEquals("Developed preview", developedColor, output.getPixel(size / 4, size / 2));

              preview.compare(true);
              preview.draw(canvas);
              assertEquals("Comparison source", originalColor, output.getPixel(size / 4, size / 2));
              assertEquals("Comparison result", developedColor, output.getPixel(3 * size / 4, size / 2));

              preview.compare(false);
              preview.images(before, transparent);
              preview.draw(canvas);
              assertEquals("Transparent border backing", FrameStyle.BACKING_COLOR,
                  output.getPixel(size / 4, size / 2));
            }
          } finally {
            AppStyle.applyTheme(previousTheme);
            before.recycle();
            developed.recycle();
            transparent.recycle();
            output.recycle();
          }
        });
  }

  private void enlarge(View view, float scale) {
    if (view instanceof TextView text)
      text.setTextSize(TypedValue.COMPLEX_UNIT_PX, text.getTextSize() * scale);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) enlarge(group.getChildAt(i), scale);
  }

  public void testAboutActionsFitTheAvailableWidthAndKeepTheirBadges() {
    MainActivity activity = getActivity();
    main(
        () -> {
          Runnable noop = () -> {};
          AboutPage page = new AboutPage(activity, "test", noop, noop, noop, noop, noop);
          enlarge(page, 1.5f);
          page.measure(View.MeasureSpec.makeMeasureSpec(AppStyle.dp(activity, 340), View.MeasureSpec.EXACTLY),
              View.MeasureSpec.makeMeasureSpec(AppStyle.dp(activity, 1000), View.MeasureSpec.EXACTLY));
          page.layout(0, 0, page.getMeasuredWidth(), page.getMeasuredHeight());
          AdaptiveActionRow row = page.findViewWithTag("about.actions");
          assertEquals(LinearLayout.VERTICAL, row.getOrientation());
          ViewGroup badge = page.findViewWithTag("about.update");
          TextView update = (TextView) badge.getChildAt(0);
          TextView sponsor = page.findViewWithTag("about.sponsor");
          assertEquals(update.getWidth(), sponsor.getWidth());
          assertEquals(update.getHeight(), sponsor.getHeight());
          assertTrue(badge.getRight() <= row.getWidth());
          assertTrue(sponsor.getRight() <= row.getWidth());
          fullText(update);
          fullText(sponsor);
          int height = row.getHeight();
          page.setUpdateAvailable(true);
          page.measure(View.MeasureSpec.makeMeasureSpec(AppStyle.dp(activity, 340), View.MeasureSpec.EXACTLY),
              View.MeasureSpec.makeMeasureSpec(AppStyle.dp(activity, 1000), View.MeasureSpec.EXACTLY));
          page.layout(0, 0, page.getMeasuredWidth(), page.getMeasuredHeight());
          assertEquals("Showing the update badge does not move the actions", height, row.getHeight());
        });
  }

  public void testEqualWidthStackedActionsFillAvailableWidth() {
    Context context = scaled(1f);
    main(
        () -> {
          TextView cancel = action(context, "取消", AppStyle.TYPE_TITLE);
          TextView discard = action(context, "放弃修改", AppStyle.TYPE_TITLE);
          TextView save = action(context, "保存并返回", AppStyle.TYPE_TITLE);
          AdaptiveActionRow row = new AdaptiveActionRow(context, true, 12, true);
          row.addAction(cancel, cancel, 84);
          row.addAction(discard, discard, 84);
          row.addAction(save, save, 84);
          int narrow = AppStyle.dp(context, 296);
          measure(row, narrow, AppStyle.dp(context, 600));
          assertEquals(LinearLayout.VERTICAL, row.getOrientation());
          assertEquals(narrow, cancel.getWidth());
          assertEquals(narrow, discard.getWidth());
          assertEquals(narrow, save.getWidth());
          fullText(cancel);
          fullText(discard);
          fullText(save);

          int wide = AppStyle.dp(context, 440);
          measure(row, wide, AppStyle.dp(context, 600));
          assertEquals(LinearLayout.HORIZONTAL, row.getOrientation());
          assertEquals(cancel.getWidth(), discard.getWidth());
          assertEquals(discard.getWidth(), save.getWidth());
          int total = cancel.getWidth() * 3 + AppStyle.dp(context, 12) * 2;
          assertTrue("Horizontal equal-width fillRow should span available width",
              total >= wide - 2 && total <= wide);
        });
  }
}
