// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.*;
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
    for (int tab = 0; tab <= 5; tab++) {
      for (String section :
          tab == 2 ? new String[] {"基础", "曲线", "色彩平衡", "HSL", "暗角"} : new String[] {"基础"}) {
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

  public void testHomePreviewUsesLandscapePortraitAndSquareAspectRatios() throws Exception {
    activity = getActivity();
    ImageView hero = (ImageView) field("homeHero").get(activity);
    for (int[] dimensions : new int[][] {{300, 100}, {100, 300}, {120, 120}}) {
      Bitmap bitmap = Bitmap.createBitmap(dimensions[0], dimensions[1], Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(Color.MAGENTA);
      main(
          () -> {
            try {
              call("showHome");
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
            hero.setImageBitmap(bitmap);
          });
      assertEquals(
          "Whole photo aspect ratio",
          hero.getWidth() * (double) dimensions[1] / dimensions[0],
          (double) hero.getHeight(),
          1.0);
      assertEquals(ImageView.ScaleType.FIT_CENTER, hero.getScaleType());
      main(() -> hero.setImageBitmap(null));
      bitmap.recycle();
    }
    main(
        () -> {
          try {
            hero.setImageBitmap((Bitmap) field("source").get(activity));
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
  }
}
