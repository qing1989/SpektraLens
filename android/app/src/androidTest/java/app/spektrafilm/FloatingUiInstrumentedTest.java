// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.lang.reflect.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Exercise expanding panels and real text validation after shared dialog styling. */
public final class FloatingUiInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;

  public FloatingUiInstrumentedTest() {
    super(MainActivity.class);
  }

  protected void setUp() throws Exception {
    super.setUp();
    activity = getActivity();
    long end = SystemClock.elapsedRealtime() + 30000;
    while (!(Boolean) value("ready") && SystemClock.elapsedRealtime() < end) Thread.sleep(40);
    assertTrue((Boolean) value("ready"));
    main(() -> invoke("showEditor", new Class<?>[0]));
  }

  private Field field(String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private Object value(String name) throws Exception {
    return field(name).get(activity);
  }

  private void invoke(String name, Class<?>[] types, Object... arguments) {
    try {
      Method method = MainActivity.class.getDeclaredMethod(name, types);
      method.setAccessible(true);
      method.invoke(activity, arguments);
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
  }

  private void main(Runnable action) {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                action.run();
              } catch (Throwable error) {
                failure.set(error);
              }
            });
    getInstrumentation().waitForIdleSync();
    if (failure.get() instanceof AssertionError) throw (AssertionError) failure.get();
    if (failure.get() != null) throw new RuntimeException(failure.get());
  }

  private Rect bounds(View view) {
    int[] location = new int[2];
    view.getLocationOnScreen(location);
    return new Rect(
        location[0], location[1], location[0] + view.getWidth(), location[1] + view.getHeight());
  }

  /**
   * 面板贴底、永不压住照片，而且切工具时照片的矩形一动不动——面板高度现在跟着内容走
   * （装得下就收短，装不下就停在上限滚动），所以「照片不许跟着长」必须靠布局保证。
   */
  public void testPanelDocksBelowPhotoAndNeverMovesIt() throws Exception {
    String settings = value("settings").toString();
    Rect first = null;
    for (int tool : new int[] {0, 1, 2, 4, 5}) {
      main(
          () -> {
            try {
              field("selectedTab").setInt(activity, tool);
              invoke("buildPanel", new Class<?>[0]);
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      View panel = (View) value("panel"),
          photo = (View) value("photo"),
          navigation = (View) value("tabRow");
      assertEquals(
          "Four real tools remain available", 4, ((LinearLayout) navigation).getChildCount());
      assertTrue("Panel is inset from screen edge", bounds(panel).left > 0);
      assertFalse("Panel never covers the photo", Rect.intersects(bounds(photo), bounds(panel)));
      assertTrue("Dock stays below panel", bounds(navigation).top >= bounds(panel).bottom);
      assertTrue(
          "Preview is still usable",
          photo.getHeight() > 96 * activity.getResources().getDisplayMetrics().density);
      if (first == null) first = bounds(photo);
      else
        assertEquals(
            "切换工具不许改变照片的位置和大小（工具 " + tool + "）", first, bounds(photo));
    }
    assertEquals(
        "Changing panel size does not edit the photograph", settings, value("settings").toString());
  }

  public void testProgressFeedbackDoesNotMoveThePhotographAndErrorsStayVisible() throws Exception {
    View photo = (View) value("photo"), feedback = (View) value("statusRow");
    main(() -> invoke("setStatus", new Class<?>[] {String.class, boolean.class}, "已准备好", false));
    Rect idle = bounds(photo);
    main(() -> invoke("setStatus", new Class<?>[] {String.class, boolean.class}, "正在显影…", true));
    assertEquals(View.VISIBLE, feedback.getVisibility());
    assertEquals("Progress must not resize or shift the photograph", idle, bounds(photo));
    main(
        () ->
            invoke(
                "setStatus",
                new Class<?>[] {String.class, boolean.class, boolean.class},
                "导入超时 · 可重试",
                false,
                true));
    assertEquals("Error feedback remains available", View.VISIBLE, feedback.getVisibility());
    assertEquals(idle, bounds(photo));
    main(() -> invoke("setStatus", new Class<?>[] {String.class, boolean.class}, "已完成", false));
    assertEquals(View.GONE, feedback.getVisibility());
    assertEquals(idle, bounds(photo));
  }

  private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text, String className) {
    if (node == null) return null;
    if ((text == null || text.contentEquals(node.getText() == null ? "" : node.getText()))
        && (className == null || className.contentEquals(node.getClassName()))) return node;
    for (int i = 0; i < node.getChildCount(); i++) {
      AccessibilityNodeInfo match = find(node.getChild(i), text, className);
      if (match != null) return match;
    }
    return null;
  }

  public void testDialogPrimaryButtonKeepsTheSharedVisualStyle() throws Exception {
    String[][] labels = {
      {"完成", "处理诊断", "许可全文"},
      {"关闭", "切换显影模式", "复制诊断"},
      {"再编辑一张", "回到首页", null},
      {"应用", "取消", null},
      {"恢复", "取消", null},
      {"知道了", null, null}
    };
    for (String[] names : labels) {
      android.app.AlertDialog[] dialog = new android.app.AlertDialog[1];
      main(
          () -> {
            Haptics.DialogBuilder builder = new Haptics.DialogBuilder(activity);
            builder.setTitle("操作确认").setMessage("测试弹窗操作样式").setPositiveButton(names[0], null);
            if (names[1] != null) builder.setNegativeButton(names[1], null);
            if (names[2] != null) builder.setNeutralButton(names[2], null);
            dialog[0] = builder.show();
          });
      try {
        Button positive = dialog[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE);
        assertEquals(
            "Primary text contrasts with orange fill", AppStyle.BG, positive.getCurrentTextColor());
        assertTrue(
            "Primary action stays horizontal: " + names[0],
            positive.getWidth() >= positive.getHeight() * 2);
        Rect previous = null;
        for (int which :
            new int[] {
              android.app.AlertDialog.BUTTON_NEUTRAL,
              android.app.AlertDialog.BUTTON_NEGATIVE,
              android.app.AlertDialog.BUTTON_POSITIVE
            }) {
          Button button = dialog[0].getButton(which);
          if (button == null || button.getVisibility() != View.VISIBLE) continue;
          Rect visible = new Rect();
          assertTrue(
              "Every action is visible: " + button.getText(), button.getGlobalVisibleRect(visible));
          assertEquals("No clipped width", button.getWidth(), visible.width());
          assertEquals("No clipped height", button.getHeight(), visible.height());
          assertTrue(
              "Full label fits: " + button.getText(),
              button.getPaint().measureText(button.getText().toString())
                  <= button.getWidth() - button.getPaddingLeft() - button.getPaddingRight() + 1);
          if (previous != null)
            assertFalse("Actions never overlap", Rect.intersects(previous, visible));
          previous = visible;
        }
      } finally {
        main(() -> dialog[0].dismiss());
      }
    }
  }

  private AccessibilityNodeInfo ui(String text, String className) throws Exception {
    long until = SystemClock.elapsedRealtime() + 5000;
    while (SystemClock.elapsedRealtime() < until) {
      AccessibilityNodeInfo match =
          find(getInstrumentation().getUiAutomation().getRootInActiveWindow(), text, className);
      if (match != null) return match;
      Thread.sleep(40);
    }
    throw new AssertionError("Missing dialog control: " + text + " / " + className);
  }

  public void testStyledTextDialogKeepsValidationAndCancelListener() throws Exception {
    String previous = ((JSONObject) value("settings")).optString("edge_caption");
    main(
        () ->
            invoke(
                "editTextValue",
                new Class<?>[] {String.class, String.class, int.class, String.class},
                "edge_caption",
                "相纸题注",
                20,
                "仅支持英文字母与数字"));
    try {
      Bundle input = new Bundle();
      input.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "中文");
      assertTrue(
          ui(null, "android.widget.EditText")
              .performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, input));
      assertTrue(ui("应用", null).performAction(AccessibilityNodeInfo.ACTION_CLICK));
      getInstrumentation().waitForIdleSync();
      assertNotNull("Invalid input keeps the dialog open", ui("相纸题注", null));
      assertEquals(
          "Invalid text was not applied",
          previous,
          ((JSONObject) value("settings")).optString("edge_caption"));
    } finally {
      ui("取消", null).performAction(AccessibilityNodeInfo.ACTION_CLICK);
      getInstrumentation().waitForIdleSync();
    }
    assertEquals(previous, ((JSONObject) value("settings")).optString("edge_caption"));
  }
}
