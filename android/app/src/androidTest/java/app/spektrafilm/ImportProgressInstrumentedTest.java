// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.test.ActivityInstrumentationTestCase2;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Import progress is a visual state of the original button, with centred copy and cancellation. */
public final class ImportProgressInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;

  public ImportProgressInstrumentedTest() {
    super(MainActivity.class);
  }

  private Object field(String name) throws Exception {
    Field value = MainActivity.class.getDeclaredField(name);
    value.setAccessible(true);
    return value.get(activity);
  }

  private void running(boolean value) throws Exception {
    Field running = MainActivity.class.getDeclaredField("batchImportRunning");
    running.setAccessible(true);
    running.setBoolean(activity, value);
  }

  private void call(String name, Class<?>[] types, Object... args) throws Exception {
    Method method = MainActivity.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    method.invoke(activity, args);
  }

  private void main(Runnable action) {
    Throwable[] failure = {null};
    getInstrumentation().runOnMainSync(
        () -> {
          try { action.run(); }
          catch (Throwable error) { failure[0] = error; }
        });
    getInstrumentation().waitForIdleSync();
    if (failure[0] instanceof AssertionError) throw (AssertionError) failure[0];
    if (failure[0] != null) throw new RuntimeException(failure[0]);
  }

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    activity = getActivity();
    getInstrumentation().waitForIdleSync();
  }

  public void testProgressUsesTheSameButtonAndCancelRestoresIt() throws Exception {
    TextView button = (TextView) field("homeImportButton");
    int width = button.getWidth(), height = button.getHeight();
    Object parent = button.getParent();
    main(
        () -> {
          try {
            running(true);
            call("startHomeImportProgress", new Class<?>[] {int.class}, 20);
            call("setHomeImportProgress", new Class<?>[] {int.class, int.class, float.class}, 5, 20, .5f);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    main(
        () -> {
          try {
            assertSame(button, field("homeImportButton"));
            assertSame(parent, button.getParent());
            assertEquals(width, button.getWidth());
            assertEquals(height, button.getHeight());
            assertEquals(View.VISIBLE, button.getVisibility());
            assertEquals(Gravity.CENTER, button.getGravity());
            assertEquals(button.getPaddingLeft(), button.getPaddingRight());
            assertEquals("正在导入 6 / 20", button.getContentDescription().toString());
            assertTrue(button.getText().toString().endsWith("6 / 20"));
            assertFalse(button.isEnabled());
            assertSame(field("homeImportProgress"), button.getBackground());
            Bitmap rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            try {
              button.draw(new Canvas(rendered));
            } finally {
              rendered.recycle();
            }
            Outline outline = new Outline();
            button.getBackground().getOutline(outline);
            float expectedRadius =
                Math.min(AppStyle.dp(activity, AppStyle.R_BUTTON), Math.min(width, height) / 2f);
            assertEquals(expectedRadius, outline.getRadius(), .5f);
            TextView cancel = (TextView) field("homeImportCancel");
            assertEquals(View.VISIBLE, cancel.getVisibility());
            assertTrue(cancel.getHeight() >= AppStyle.dp(activity, AppStyle.TOUCH_MIN));
            assertTrue(cancel.performClick());
            assertFalse((Boolean) field("batchImportRunning"));
            assertEquals("开始导入照片", button.getText().toString());
            assertEquals(View.GONE, cancel.getVisibility());
            assertNotSame(field("homeImportProgress"), button.getBackground());
            assertNull(((ImportProgressDrawable) field("homeImportProgress")).getCallback());
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
  }

  public void testCompletionRestoresTheOriginalButtonAndCapsuleCornersStayClear() throws Exception {
    TextView button = (TextView) field("homeImportButton");
    main(
        () -> {
          try {
            running(true);
            call("startHomeImportProgress", new Class<?>[] {int.class}, 20);
            running(false);
            call("completeHomeImportProgress", new Class<?>[] {int.class, int.class}, 20, 0);
            assertEquals("已导入 20 张", button.getText().toString());
            Bitmap bitmap = Bitmap.createBitmap(button.getWidth(), button.getHeight(), Bitmap.Config.ARGB_8888);
            try {
              button.draw(new Canvas(bitmap));
              assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)));
              assertTrue(Color.alpha(bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2)) > 0);
            } finally {
              bitmap.recycle();
            }
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    Thread.sleep(700);
    main(
        () -> {
          try {
            assertSame(button, field("homeImportButton"));
            assertEquals("开始导入照片", button.getText().toString());
            assertTrue(button.isEnabled());
            assertEquals(button.getPaddingLeft(), button.getPaddingRight());
            assertFalse(button.getBackground() instanceof ImportProgressDrawable);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      main(
          () -> {
            try {
              call("cancelBatchImport", new Class<?>[] {});
              call("resetHomeImportButton", new Class<?>[] {});
              AppNotice.clear(activity.getWindow());
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
    } finally {
      super.tearDown();
    }
  }
}
