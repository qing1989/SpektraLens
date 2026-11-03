// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Dialog;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.*;

/**
 * Observes actual window-dispatched system feedback requests without requiring a physical motor.
 */
public final class HapticsInstrumentedTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public HapticsInstrumentedTest() {
    super(MainActivity.class);
  }

  private final class TouchView extends View {
    int calls;

    TouchView() {
      super(getActivity());
      setOnClickListener(v -> {});
    }

    public boolean performHapticFeedback(int constant) {
      assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, constant);
      calls++;
      return true;
    }
  }

  private void gesture(View view, boolean drag) {
    long until = SystemClock.uptimeMillis() + 3000;
    while ((view.getWidth() == 0 || !view.hasWindowFocus()) && SystemClock.uptimeMillis() < until) {
      getInstrumentation().waitForIdleSync();
      SystemClock.sleep(20);
    }
    assertTrue(
        "Target window is laid out before injecting touch",
        view.getWidth() > 0 && view.hasWindowFocus());
    int[] location = new int[2];
    view.getLocationOnScreen(location);
    float x = location[0] + view.getWidth() / 2f, y = location[1] + view.getHeight() / 2f;
    long now = SystemClock.uptimeMillis();
    send(MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0));
    SystemClock.sleep(20);
    if (drag)
      send(
          MotionEvent.obtain(
              now, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x + 70, y + 70, 0));
    send(
        MotionEvent.obtain(
            now,
            SystemClock.uptimeMillis(),
            MotionEvent.ACTION_UP,
            x + (drag ? 70 : 0),
            y + (drag ? 70 : 0),
            0));
    getInstrumentation().waitForIdleSync();
  }

  private void send(MotionEvent event) {
    getInstrumentation().sendPointerSync(event);
    event.recycle();
  }

  public void testMainAndModalClicksUseSystemFeedbackAndDraggingDoesNot() {
    MainActivity activity = getActivity();
    TouchView[] main = new TouchView[1], modal = new TouchView[1];
    Dialog[] dialog = new Dialog[1];
    getInstrumentation()
        .runOnMainSync(
            () -> {
              main[0] = new TouchView();
              activity.setContentView(main[0]);
            });
    getInstrumentation().waitForIdleSync();
    gesture(main[0], false);
    assertEquals(1, main[0].calls);
    gesture(main[0], true);
    assertEquals(1, main[0].calls);
    getInstrumentation()
        .runOnMainSync(
            () -> {
              dialog[0] = new Dialog(activity);
              modal[0] = new TouchView();
              dialog[0].setContentView(modal[0]);
              Haptics.install(dialog[0].getWindow());
              dialog[0].show();
              dialog[0].getWindow().setLayout(600, 500);
            });
    try {
      getInstrumentation().waitForIdleSync();
      assertTrue(
          java.lang.reflect.Proxy.isProxyClass(dialog[0].getWindow().getCallback().getClass()));
      gesture(modal[0], false);
      assertEquals(1, modal[0].calls);
    } finally {
      getInstrumentation().runOnMainSync(() -> dialog[0].dismiss());
    }
  }
}
