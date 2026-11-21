// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Rect;
import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Approved grouped action sheet: callback routing, disabled rows and constrained layout. */
public final class LibraryActionSheetInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private LibraryActionSheet sheet;
  private final List<Integer> actions = new ArrayList<>();

  public LibraryActionSheetInstrumentedTest() {
    super(MainActivity.class);
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

  private void open(boolean canPaste, boolean source) {
    MainActivity activity = getActivity();
    main(
        () -> {
          if (sheet != null) sheet.dismiss();
          List<LibraryActionSheet.ActionItem> items = new ArrayList<>();
          String[] labels = {
            "再次编辑", source ? "清除已复制编辑参数" : "复制编辑参数", "粘贴编辑参数",
            "批量粘贴编辑参数", "取消收藏", "导出照片", "删除照片"
          };
          for (int action = 0; action <= LibraryActionSheet.DELETE; action++) {
            boolean paste = action == LibraryActionSheet.PASTE || action == LibraryActionSheet.BATCH_PASTE;
            String note = switch (action) {
              case LibraryActionSheet.COPY -> "保存这张照片的全部调整";
              case LibraryActionSheet.PASTE -> "应用到当前照片";
              case LibraryActionSheet.BATCH_PASTE -> "一次最多选择 20 张照片";
              default -> "";
            };
            String state = paste ? (canPaste ? "可粘贴" : "未复制") : source && action == LibraryActionSheet.COPY ? "当前来源" : "";
            items.add(new LibraryActionSheet.ActionItem(
                action, labels[action], note, state, !paste || canPaste, action == LibraryActionSheet.DELETE));
          }
          sheet = new LibraryActionSheet(activity, items, actions::add);
          sheet.show();
        });
  }

  private View view(String suffix) {
    View found = sheet.getWindow().getDecorView().findViewWithTag("library.actions." + suffix);
    assertNotNull(suffix, found);
    return found;
  }

  private TextView label(int action) {
    ViewGroup row = (ViewGroup) view("row." + action);
    for (int i = 0; i < row.getChildCount(); i++) {
      View child = row.getChildAt(i);
      if (child instanceof ViewGroup) return (TextView) ((ViewGroup) child).getChildAt(0);
    }
    throw new AssertionError("row has no label");
  }

  private int dp(float value) {
    return AppStyle.dp(getActivity(), value);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      if (sheet != null) main(sheet::dismiss);
    } finally {
      super.tearDown();
    }
  }

  public void testGroupsPreserveAllSevenCallbacksAndOrder() {
    open(true, false);
    main(
        () -> {
          int[][] groups = {{0}, {1, 2, 3}, {4, 5, 6}};
          for (int[] ids : groups) {
            ViewGroup card = (ViewGroup) view("group." + ids[0]);
            assertEquals(ids.length * 2 - 1, card.getChildCount());
            for (int i = 0; i < ids.length; i++) {
              View row = view("row." + ids[i]);
              assertSame(row, card.getChildAt(i * 2));
              assertTrue(row.getMinimumHeight() >= dp(AppStyle.TOUCH_MIN));
              AccessibilityNodeInfo info = row.createAccessibilityNodeInfo();
              assertEquals("android.widget.Button", info.getClassName().toString());
              assertTrue(info.isClickable());
              if (i > 0) {
                ViewGroup.MarginLayoutParams divider =
                    (ViewGroup.MarginLayoutParams) card.getChildAt(i * 2 - 1).getLayoutParams();
                assertEquals(dp(16), divider.leftMargin);
                assertEquals(dp(16), divider.rightMargin);
              }
            }
          }
          assertEquals(AppStyle.GOLD, label(0).getCurrentTextColor());
          assertEquals(AppStyle.DANGER, label(6).getCurrentTextColor());
        });
    for (int action = 0; action <= LibraryActionSheet.DELETE; action++) {
      open(true, false);
      int id = action;
      main(() -> assertTrue(view("row." + id).performClick()));
      assertFalse(sheet.isShowing());
      assertEquals(action + 1, actions.size());
      assertEquals(action, actions.get(action).intValue());
    }
  }

  public void testDisabledPasteRowsCannotDispatchAndSourceIsAnnounced() {
    open(false, false);
    main(
        () -> {
          for (int id : new int[] {LibraryActionSheet.PASTE, LibraryActionSheet.BATCH_PASTE}) {
            View row = view("row." + id);
            assertFalse(row.isEnabled());
            assertFalse(row.isClickable());
            assertFalse(row.performClick());
            assertEquals(AppStyle.DISABLED, label(id).getCurrentTextColor());
            assertTrue(row.getContentDescription().toString().contains("未复制"));
          }
        });
    assertTrue(actions.isEmpty());
    open(true, true);
    main(
        () -> {
          assertEquals("清除已复制编辑参数", label(LibraryActionSheet.COPY).getText().toString());
          assertTrue(view("row.1").getContentDescription().toString().contains("当前来源"));
          assertTrue(view("row.2").isEnabled());
          assertTrue(view("row.3").isEnabled());
        });
  }

  private void enlarge(View view) {
    if (view instanceof TextView text)
      text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, text.getTextSize() * 2);
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) enlarge(group.getChildAt(i));
  }

  public void testNarrowLargeTextAndLandscapeKeepHeaderFixedAndBodyScrollable() {
    for (int[] size : new int[][] {{320, 400}, {640, 280}}) {
      open(true, true);
      main(
          () -> {
            ViewGroup inset = (ViewGroup) view("inset");
            enlarge(inset);
            inset.measure(
                View.MeasureSpec.makeMeasureSpec(dp(size[0]), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(size[1]), View.MeasureSpec.AT_MOST));
            inset.layout(0, 0, inset.getMeasuredWidth(), inset.getMeasuredHeight());
            View surface = view("surface"), header = view("header"), cancel = view("cancel");
            ScrollView scroll = (ScrollView) view("scroll");
            assertEquals(dp(10), surface.getLeft());
            assertEquals(dp(10), inset.getWidth() - surface.getRight());
            assertEquals(dp(10), inset.getHeight() - surface.getBottom());
            assertTrue(inset.getHeight() <= dp(size[1]));
            assertTrue(header.getHeight() >= dp(62));
            assertTrue(cancel.getWidth() >= dp(AppStyle.TOUCH_MIN));
            assertTrue(cancel.getHeight() >= dp(AppStyle.TOUCH_MIN));
            assertTrue(scroll.getHeight() > 0);
            int headerTop = header.getTop();
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
            assertTrue(scroll.getScrollY() > 0);
            assertEquals(headerTop, header.getTop());
            Rect lastRow = new Rect();
            view("row.6").getDrawingRect(lastRow);
            scroll.offsetDescendantRectToMyCoords(view("row.6"), lastRow);
            lastRow.offset(-scroll.getScrollX(), -scroll.getScrollY());
            assertTrue(lastRow.top >= 0);
            assertTrue(lastRow.bottom <= scroll.getHeight());
            // Wrapped copy grows the row; no line is hidden behind a fixed-height item.
            for (int id = 0; id <= LibraryActionSheet.DELETE; id++) {
              TextView label = label(id);
              assertTrue(label.getLayout().getHeight() <= label.getHeight());
            }
          });
    }
  }

  public void testCancelBackOutsideAndTransparentMarginDismissWithoutActions() {
    open(true, false);
    main(() -> view("cancel").performClick());
    assertFalse(sheet.isShowing());
    open(true, false);
    getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
    getInstrumentation().waitForIdleSync();
    assertFalse(sheet.isShowing());
    open(true, false);
    main(
        () -> {
          MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, -100, -100, 0);
          try { assertTrue(sheet.onTouchEvent(event)); }
          finally { event.recycle(); }
        });
    assertFalse(sheet.isShowing());
    open(true, false);
    main(
        () -> {
          View inset = view("inset");
          for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent event = MotionEvent.obtain(0, 0, action, dp(2), dp(2), 0);
            try { inset.dispatchTouchEvent(event); }
            finally { event.recycle(); }
          }
        });
    assertFalse(sheet.isShowing());
    assertTrue(actions.isEmpty());
  }
}
