// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.*;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.MotionEvent;

/** Check the pixels under real touch events, rather than inspecting zoom fields. */
public final class PhotoPreviewInstrumentedTest extends InstrumentationTestCase {
  private PhotoView view;
  private Bitmap original, developed;
  private long time;

  protected void setUp() throws Exception {
    super.setUp();
    time = SystemClock.uptimeMillis();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              view = new PhotoView(getInstrumentation().getTargetContext());
              new android.widget.FrameLayout(view.getContext()).addView(view);
              view.layout(0, 0, 800, 800);
              original = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888);
              for (int y = 0; y < 400; y++)
                for (int x = 0; x < 400; x++)
                  original.setPixel(x, y, Color.rgb(x * 255 / 399, y * 255 / 399, 64));
              developed = original.copy(Bitmap.Config.ARGB_8888, true);
              view.images(original, developed);
            });
  }

  protected void tearDown() throws Exception {
    getInstrumentation()
        .runOnMainSync(
            () -> {
              view.images(null, null);
              original.recycle();
              developed.recycle();
            });
    super.tearDown();
  }

  private int pixel(int x, int y) {
    Bitmap screen = Bitmap.createBitmap(800, 800, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(screen));
    int color = screen.getPixel(x, y);
    screen.recycle();
    return color;
  }

  private void event(int action, float... points) {
    int count = points.length / 2;
    MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
    MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
    for (int i = 0; i < count; i++) {
      properties[i] = new MotionEvent.PointerProperties();
      properties[i].id = i;
      properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
      coordinates[i] = new MotionEvent.PointerCoords();
      coordinates[i].x = points[i * 2];
      coordinates[i].y = points[i * 2 + 1];
      coordinates[i].pressure = coordinates[i].size = 1;
    }
    time += 60;
    MotionEvent event =
        MotionEvent.obtain(
            time - 500,
            time,
            action,
            count,
            properties,
            coordinates,
            0,
            0,
            1,
            1,
            0,
            0,
            android.view.InputDevice.SOURCE_TOUCHSCREEN,
            0);
    view.onTouchEvent(event);
    event.recycle();
  }

  private void near(int expected, int actual) {
    assertTrue(
        "Red at focus: " + Color.red(expected) + " / " + Color.red(actual),
        Math.abs(Color.red(expected) - Color.red(actual)) <= 2);
    assertTrue(
        "Green at focus: " + Color.green(expected) + " / " + Color.green(actual),
        Math.abs(Color.green(expected) - Color.green(actual)) <= 2);
  }

  private void main(Runnable action) {
    java.util.concurrent.atomic.AtomicReference<Throwable> error =
        new java.util.concurrent.atomic.AtomicReference<>();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                action.run();
              } catch (Throwable failure) {
                error.set(failure);
              }
            });
    if (error.get() instanceof AssertionError) throw (AssertionError) error.get();
    if (error.get() != null) throw new RuntimeException(error.get());
  }

  private void pinch(float x, float y) {
    event(MotionEvent.ACTION_DOWN, x - 220, y);
    event(
        MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
        x - 220,
        y,
        x + 220,
        y);
    event(MotionEvent.ACTION_MOVE, x - 300, y, x + 300, y);
    event(MotionEvent.ACTION_MOVE, x - 420, y, x + 420, y);
  }

  public void testDoubleTapKeepsTappedDetailInPlaceAndSecondDoubleTapResets() {
    main(
        () -> {
          int focus = pixel(550, 300), other = pixel(630, 300);
          for (int i = 0; i < 2; i++) {
            event(MotionEvent.ACTION_DOWN, 550, 300);
            event(MotionEvent.ACTION_UP, 550, 300);
          }
          near(focus, pixel(550, 300));
          assertTrue(
              "The picture actually enlarged",
              Math.abs(Color.red(other) - Color.red(pixel(630, 300))) > 5);
          time += 500;
          for (int i = 0; i < 2; i++) {
            event(MotionEvent.ACTION_DOWN, 550, 300);
            event(MotionEvent.ACTION_UP, 550, 300);
          }
          near(other, pixel(630, 300));
        });
  }

  public void testPinchKeepsDetailUnderFingers() {
    main(
        () -> {
          int focus = pixel(550, 300), other = pixel(630, 300);
          pinch(550, 300);
          near(focus, pixel(550, 300));
          assertTrue(
              "Pinch actually enlarged the picture",
              Math.abs(Color.red(other) - Color.red(pixel(630, 300))) > 3);
          event(
              MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
              130,
              300,
              970,
              300);
          event(MotionEvent.ACTION_UP, 130, 300);
        });
  }

  public void testLiftingFingerAfterPinchDoesNotMoveComparisonDivider() {
    main(
        () -> {
          original.eraseColor(Color.RED);
          developed.eraseColor(Color.BLUE);
          view.compare(true);
          pixel(400, 400);
          pinch(400, 400);
          event(
              MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
              -20,
              400,
              820,
              400);
          event(MotionEvent.ACTION_MOVE, 650, 400);
          assertEquals("Pinch must not turn into a divider drag", Color.BLUE, pixel(550, 400));
          event(MotionEvent.ACTION_UP, 650, 400);
        });
  }

  public void testStartingComparisonPinchDoesNotMoveDivider() {
    main(
        () -> {
          original.eraseColor(Color.RED);
          developed.eraseColor(Color.BLUE);
          view.compare(true);
          assertEquals(Color.RED, pixel(320, 300));
          event(MotionEvent.ACTION_DOWN, 180, 400);
          assertEquals(
              "The first finger of a pinch must not move the divider", Color.RED, pixel(320, 300));
          event(
              MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
              180,
              400,
              620,
              400);
          event(MotionEvent.ACTION_MOVE, 100, 400, 700, 400);
          assertEquals("Pinch keeps the original side in place", Color.RED, pixel(320, 300));
          event(MotionEvent.ACTION_CANCEL, 100, 400);
        });
  }

  public void testZoomedComparisonCanPanAwayFromDivider() {
    main(
        () -> {
          for (int i = 0; i < 2; i++) {
            event(MotionEvent.ACTION_DOWN, 400, 400);
            event(MotionEvent.ACTION_UP, 400, 400);
          }
          time += 500;
          view.compare(true);
          int detail = pixel(180, 300);
          event(MotionEvent.ACTION_DOWN, 180, 300);
          event(MotionEvent.ACTION_MOVE, 280, 350);
          near(detail, pixel(280, 350));
          assertTrue(
              "Divider stays at the viewport center while the photo pans",
              Color.red(pixel(400, 650)) > 210);
          event(MotionEvent.ACTION_UP, 280, 350);
        });
  }

  public void testZoomedComparisonDividerCanStillBeDragged() {
    main(
        () -> {
          original.eraseColor(Color.RED);
          developed.eraseColor(Color.BLUE);
          for (int i = 0; i < 2; i++) {
            event(MotionEvent.ACTION_DOWN, 600, 400);
            event(MotionEvent.ACTION_UP, 600, 400);
          }
          time += 500;
          view.compare(true);
          assertEquals(
              "Zoom must keep the divider in the visible photo", Color.RED, pixel(320, 300));
          event(MotionEvent.ACTION_DOWN, 400, 400);
          event(MotionEvent.ACTION_MOVE, 600, 400);
          event(MotionEvent.ACTION_UP, 600, 400);
          assertEquals("The handle remains draggable after zooming", Color.RED, pixel(550, 300));
          assertEquals(Color.BLUE, pixel(700, 300));
        });
  }
}
