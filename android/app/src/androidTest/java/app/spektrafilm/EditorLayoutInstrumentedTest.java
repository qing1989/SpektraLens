// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.test.ActivityInstrumentationTestCase2;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Pixel/control bounds, including endpoints and changing source aspect ratios. */
public final class EditorLayoutInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;

  public EditorLayoutInstrumentedTest() {
    super(MainActivity.class);
  }

  protected void setUp() throws Exception {
    super.setUp();
    activity = getActivity();
    long until = android.os.SystemClock.elapsedRealtime() + 30000;
    while (!(Boolean) field("ready").get(activity)
        && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50);
    assertTrue(
        "Renderer initialized before layout interaction", (Boolean) field("ready").get(activity));
  }

  private Field field(String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private void call(String name) throws Exception {
    Method method = MainActivity.class.getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(activity);
  }

  private void main(Runnable action) {
    getInstrumentation().runOnMainSync(action);
    getInstrumentation().waitForIdleSync();
  }

  public void testSliderThumbsFitAtBothEndpointsInEveryTool() throws Exception {
    activity = getActivity();
    Set<String> checked = new HashSet<>();
    for (int tab : new int[] {0, 1, 2, 4, 5}) {
      for (String section :
          tab == 2 ? new String[] {"基础", "曲线", "色彩平衡", "HSL 分色", "暗角"} : new String[] {"基础"}) {
        final int tool = tab;
        main(
            () -> {
              try {
                call("showEditor");
                field("selectedTab").setInt(activity, tool);
                field("gradeSection").set(activity, section);
                call("buildPanel");
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
        main(
            () -> {
              try {
                Map<String, SeekBar> bars = (Map<String, SeekBar>) field("sliders").get(activity);
                Map<String, TextView> numbers =
                    (Map<String, TextView>) field("values").get(activity);
                for (Map.Entry<String, SeekBar> item : bars.entrySet()) {
                  SeekBar bar = item.getValue();
                  assertTrue("Measured " + item.getKey(), bar.getWidth() > 0);
                  Bitmap pixels =
                      Bitmap.createBitmap(bar.getWidth(), bar.getHeight(), Bitmap.Config.ARGB_8888);
                  int previous = bar.getProgress();
                  for (int endpoint : new int[] {0, bar.getMax()}) {
                    bar.setProgress(endpoint);
                    bar.draw(new Canvas(pixels));
                    Rect thumb = bar.getThumb().getBounds();
                    int translatedLeft = bar.getPaddingLeft() - bar.getThumbOffset();
                    assertTrue(
                        item.getKey() + " left thumb is clipped", thumb.left + translatedLeft >= 1);
                    assertTrue(
                        item.getKey() + " right thumb is clipped",
                        thumb.right + translatedLeft <= bar.getWidth() - 1);
                  }
                  bar.setProgress(previous);
                  pixels.recycle();
                  TextView number = numbers.get(item.getKey());
                  assertTrue(
                      "Full value and unit fit: " + item.getKey(),
                      number.getPaint().measureText(number.getText().toString())
                          <= number.getWidth()
                              - number.getPaddingLeft()
                              - number.getPaddingRight()
                              + 1);
                  checked.add(item.getKey());
                }
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
      }
    }
    assertTrue("Every tool's actual sliders were checked", checked.size() >= 30);
  }

  public void testHomeBannerKeepsFixedHeightAndCentersLandscapePortraitAndSquare()
      throws Exception {
    activity = getActivity();
    ImageView hero = (ImageView) field("homeHero").get(activity);
    main(
        () -> {
          try {
            call("showHome");
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    for (int[] dimensions : new int[][] {{300, 100}, {100, 300}, {120, 120}}) {
      Bitmap bitmap = Bitmap.createBitmap(dimensions[0], dimensions[1], Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(Color.MAGENTA);
      bitmap.setDensity(Bitmap.DENSITY_NONE);
      RectF image = new RectF(0, 0, dimensions[0], dimensions[1]);
      float[] center = {image.centerX(), image.centerY()};
      main(
          () -> {
            Drawable original = hero.getDrawable();
            hero.setImageDrawable(new BitmapDrawable(activity.getResources(), bitmap));
            hero.getImageMatrix().mapPoints(center);
            hero.getImageMatrix().mapRect(image);
            hero.setImageDrawable(original);
          });
      assertEquals(
          "Photo aspect ratio must not change the banner height",
          Math.round(240 * activity.getResources().getDisplayMetrics().density),
          hero.getHeight());
      assertEquals(ImageView.ScaleType.CENTER_CROP, hero.getScaleType());
      assertEquals("Photo center stays on banner center X", hero.getWidth() / 2., center[0], 1.);
      assertEquals("Photo center stays on banner center Y", hero.getHeight() / 2., center[1], 1.);
      assertTrue(
          "Banner is filled horizontally", image.left <= 1 && image.right >= hero.getWidth() - 1);
      assertTrue(
          "Banner is filled vertically", image.top <= 1 && image.bottom >= hero.getHeight() - 1);
      bitmap.recycle();
    }
  }
}
