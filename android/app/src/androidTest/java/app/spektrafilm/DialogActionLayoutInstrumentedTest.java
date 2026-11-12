// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.util.Log;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicReference;
import android.widget.Button;

/**
 * 弹窗操作按钮的排版守卫。
 *
 * <p>只有次按钮（典型是「取消」）的弹窗走不到 {@code layoutDialogActions}，靠平台给按钮
 * wrap_content 宽度。如果共享样式再按高度往上加内边距，文字会被挤窄换行、换行又撑高高度，
 * 于是「取消」被压成一列两行（2026-11-12 真机截图）。这里断言两个形态都能单行放得下。
 */
public final class DialogActionLayoutInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private static final String TAG = "SpektraDialogLayout";
  private MainActivity activity;

  public DialogActionLayoutInstrumentedTest() {
    super(MainActivity.class);
  }

  protected void setUp() throws Exception {
    super.setUp();
    activity = getActivity();
    long end = SystemClock.elapsedRealtime() + 30000;
    while (!(Boolean) field("ready").get(activity) && SystemClock.elapsedRealtime() < end)
      Thread.sleep(40);
  }

  private java.lang.reflect.Field field(String name) throws Exception {
    java.lang.reflect.Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
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
    if (failure.get() != null) throw new RuntimeException(failure.get());
  }

  private void snapshot(String name) {
    try {
      Bitmap shot = getInstrumentation().getUiAutomation().takeScreenshot();
      if (shot == null) return;
      File target = new File(activity.getFilesDir(), name);
      try (FileOutputStream out = new FileOutputStream(target)) {
        shot.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      Log.i(TAG, "screenshot " + target.getAbsolutePath());
      shot.recycle();
    } catch (Exception error) {
      Log.w(TAG, "screenshot failed", error);
    }
  }

  private void assertLabelFits(String label, Button button) {
    String text = button.getText().toString();
    int content = button.getWidth() - button.getPaddingLeft() - button.getPaddingRight();
    int measured = (int) Math.ceil(button.getPaint().measureText(text));
    Log.i(
        TAG,
        label
            + " text="
            + text
            + " size="
            + button.getWidth()
            + "x"
            + button.getHeight()
            + " pad="
            + button.getPaddingLeft()
            + "/"
            + button.getPaddingRight()
            + " lines="
            + button.getLineCount()
            + " measure="
            + measured
            + " content="
            + content);
    assertEquals("操作按钮不折行：" + text, 1, button.getLineCount());
    assertTrue(
        "操作按钮的可用宽度要放得下整行文字：" + text + "（可用 " + content + "，需要 " + measured + "）",
        content + 1 >= measured);
    assertTrue(
        "操作按钮不低于 44 dp：" + text,
        button.getHeight() >= AppStyle.dp(activity, 44) - 1);
    assertTrue(
        "操作按钮水平方向不吃掉文字：" + text,
        button.getWidth() >= measured + AppStyle.dp(activity, 8));
  }

  /** 截图里那一类：setItems + 只有「取消」，没有任何主按钮。 */
  public void testCancelOnlyDialogStaysOnOneLine() throws Exception {
    AlertDialog[] dialog = new AlertDialog[1];
    main(
        () ->
            dialog[0] =
                new Haptics.DialogBuilder(activity)
                    .setTitle("原尺寸导出 · 5472 × 3648")
                    .setItems(
                        new String[] {
                          "保存 JPEG · 8 位", "保存 PNG · 16 位", "保存 TIFF · 16 位", "分享 JPEG · 8 位"
                        },
                        null)
                    .setNegativeButton("取消", null)
                    .show());
    try {
      SystemClock.sleep(500);
      assertLabelFits("items/取消", dialog[0].getButton(AlertDialog.BUTTON_NEGATIVE));
    } catch (AssertionError error) {
      snapshot("dialog-cancel-only.png");
      throw error;
    } finally {
      main(() -> dialog[0].dismiss());
    }
  }

  /** 对照：有主按钮的弹窗，走 layoutDialogActions 那条显式宽度的路。 */
  public void testConfirmDialogActionsStayOnOneLine() throws Exception {
    String[][] labels = {{"应用", "取消"}, {"打开发布页", "稍后"}, {"知道了", null}};
    for (String[] names : labels) {
      AlertDialog[] dialog = new AlertDialog[1];
      main(
          () -> {
            Haptics.DialogBuilder builder = new Haptics.DialogBuilder(activity);
            builder.setTitle("操作确认").setMessage("测试弹窗操作按钮").setPositiveButton(names[0], null);
            if (names[1] != null) builder.setNegativeButton(names[1], null);
            dialog[0] = builder.show();
          });
      try {
        SystemClock.sleep(400);
        assertLabelFits("confirm/" + names[0], dialog[0].getButton(AlertDialog.BUTTON_POSITIVE));
        Button negative = dialog[0].getButton(AlertDialog.BUTTON_NEGATIVE);
        if (names[1] != null && negative.getVisibility() == View.VISIBLE)
          assertLabelFits("confirm/" + names[1], negative);
      } catch (AssertionError error) {
        snapshot("dialog-confirm.png");
        throw error;
      } finally {
        main(() -> dialog[0].dismiss());
      }
    }
  }
}
