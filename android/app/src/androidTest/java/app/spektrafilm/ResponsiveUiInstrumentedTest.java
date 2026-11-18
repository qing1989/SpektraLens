// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.content.res.Configuration;
import android.test.ActivityInstrumentationTestCase2;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
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
}
