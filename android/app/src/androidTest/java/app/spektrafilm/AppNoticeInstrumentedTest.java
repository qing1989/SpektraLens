// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Dialog;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Feedback stays text-only in its owning window, replaces older text and cleans up on close. */
public final class AppNoticeInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;
  private Dialog dialog;

  public AppNoticeInstrumentedTest() {
    super(MainActivity.class);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    activity = getActivity();
    main(() -> AppNotice.clear(activity.getWindow()));
  }

  private void main(Runnable run) {
    Throwable[] failure = {null};
    getInstrumentation().runOnMainSync(
        () -> {
          try { run.run(); }
          catch (Throwable error) { failure[0] = error; }
        });
    getInstrumentation().waitForIdleSync();
    if (failure[0] instanceof AssertionError) throw (AssertionError) failure[0];
    if (failure[0] != null) throw new RuntimeException(failure[0]);
  }

  private TextView notice(View root, String expected) {
    View found = root.findViewWithTag(AppNotice.TAG);
    assertTrue(found instanceof TextView);
    TextView text = (TextView) found;
    assertEquals(expected, text.getText().toString());
    assertFalse(text.isClickable());
    assertFalse(text.isFocusable());
    assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE, text.getAccessibilityLiveRegion());
    for (android.graphics.drawable.Drawable drawable : text.getCompoundDrawables()) assertNull(drawable);
    for (android.graphics.drawable.Drawable drawable : text.getCompoundDrawablesRelative()) assertNull(drawable);
    return text;
  }

  public void testActivityNoticesReplaceOldTextAndStayAboveBatchActions() {
    main(
        () -> {
          AppNotice.show(activity, "first");
          View root = activity.getWindow().getDecorView();
          TextView first = notice(root, "first");
          ViewGroup host = (ViewGroup) first.getParent();
          int children = host.getChildCount();
          AppNotice.show(activity, "second", 104, false);
          TextView second = notice(root, "second");
          assertNotSame(first, second);
          assertFalse(first.isAttachedToWindow());
          assertEquals("", first.getText().toString());
          assertEquals(children, host.getChildCount());
          FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) second.getLayoutParams();
          assertEquals(AppStyle.dp(activity, 104), params.bottomMargin);
          AppNotice.clear(activity.getWindow());
          assertNull(root.findViewWithTag(AppNotice.TAG));
        });
  }

  public void testDialogOwnsItsNoticeAndClosingDoesNotLeaveStaleText() {
    main(
        () -> {
          dialog = new Dialog(activity);
          dialog.setContentView(new TextView(activity));
          dialog.show();
          AppNotice.show(dialog, "最多选择 20 张照片");
          TextView label = notice(dialog.getWindow().getDecorView(), "最多选择 20 张照片");
          assertNull(activity.getWindow().getDecorView().findViewWithTag(AppNotice.TAG));
          dialog.dismiss();
          assertFalse(label.isAttachedToWindow());
          assertEquals(View.GONE, label.getVisibility());
          assertEquals("", label.getText().toString());
          AppNotice.show(dialog, "stale");
          dialog.show();
          assertEquals("", label.getText().toString());
          AppNotice.show(dialog, "new");
          notice(dialog.getWindow().getDecorView(), "new");
        });
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      main(
          () -> {
            if (dialog != null) dialog.dismiss();
            AppNotice.clear(activity.getWindow());
          });
    } finally {
      super.tearDown();
    }
  }
}
