// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.*;
import android.net.Uri;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.MotionEvent;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Real photo/engine tests for gallery loading, invalidation and main-preview isolation. */
public final class FilmGalleryInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;

  public FilmGalleryInstrumentedTest() {
    super(MainActivity.class);
  }

  private Object field(String name) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(activity);
  }

  private void field(String name, Object value) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    f.set(activity, value);
  }

  private Object call(String name, Class<?>[] types, Object... values) throws Exception {
    Method m = MainActivity.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    return m.invoke(activity, values);
  }

  private void main(Runnable action) {
    Throwable[] error = {null};
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                action.run();
              } catch (Throwable e) {
                error[0] = e;
              }
            });
    getInstrumentation().waitForIdleSync();
    if (error[0] instanceof AssertionError) throw (AssertionError) error[0];
    if (error[0] != null) throw new RuntimeException(error[0]);
  }

  private <T> T read(Callable<T> action) {
    Object[] result = {null};
    main(
        () -> {
          try {
            result[0] = action.call();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return (T) result[0];
  }

  private void await(String description, Callable<Boolean> condition) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 40000;
    while (SystemClock.elapsedRealtime() < deadline) {
      if (read(condition)) return;
      Thread.sleep(60);
    }
    fail(description);
  }

  protected void setUp() throws Exception {
    super.setUp();
    activity = getActivity();
    await(
        "Engine and initial photo ready",
        () -> (Boolean) field("ready") && field("source") != null);
  }

  private Map<String, Bitmap> cache() throws Exception {
    return (Map<String, Bitmap>) field("filmThumbs");
  }

  private void idleGallery() throws Exception {
    await(
        "Visible thumbnails finish without a stuck queue",
        () -> {
          Set<?> queued = (Set<?>) field("thumbQueued");
          return field("activeImport") == null
              && !(Boolean) field("thumbBusy")
              && queued.isEmpty()
              && cache().size() >= 3
              && ((TextView) field("exportButton")).getText().toString().equals("导出照片");
        });
    assertTrue("No thumbnail engine errors", read(() -> ((Set<?>) field("thumbFailed")).isEmpty()));
  }

  private File fixture(boolean alternate) throws Exception {
    File f = new File(activity.getFilesDir(), "gallery-test-" + alternate + ".jpg");
    Bitmap b = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(b);
    Paint p = new Paint();
    c.drawColor(alternate ? 0xff224e9e : 0xffcb893a);
    p.setColor(Color.WHITE);
    c.drawRect(alternate ? 360 : 40, 30, alternate ? 560 : 200, 370, p);
    p.setColor(Color.BLACK);
    c.drawCircle(alternate ? 160 : 410, 190, 90, p);
    p.setColor(0xffd93c35);
    c.drawRect(230, 310, 350, 390, p);
    try (FileOutputStream out = new FileOutputStream(f)) {
      b.compress(Bitmap.CompressFormat.JPEG, 100, out);
    }
    b.recycle();
    return f;
  }

  private void importPhoto(boolean alternate) throws Exception {
    File f = fixture(alternate);
    main(
        () -> {
          try {
            call("importUri", new Class<?>[] {Uri.class}, Uri.fromFile(f));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    await(
        "Photo imported and main preview developed",
        () ->
            field("activeImport") == null
                && field("developed") != null
                && ((TextView) field("exportButton")).getText().toString().equals("导出照片"));
    main(
        () -> {
          try {
            field("selectedTab", 0);
            call("buildPanel", new Class<?>[] {});
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    idleGallery();
  }

  public void testSquareCardsSelectionCurrentPhotoAndLazyScroll() throws Exception {
    importPhoto(false);
    int initial = read(() -> cache().size());
    assertTrue("Only visible cards plus small buffer rendered", initial >= 3 && initial <= 6);
    main(
        () -> {
          try {
            LinearLayout cards = (LinearLayout) field("filmCards");
            assertTrue(cards.getChildCount() > initial);
            boolean selected = false;
            for (int i = 0; i < cards.getChildCount(); i++) {
              LinearLayout card = (LinearLayout) cards.getChildAt(i);
              FrameLayout frame = (FrameLayout) card.getChildAt(0);
              assertEquals(frame.getWidth(), frame.getHeight());
              ImageView image = (ImageView) frame.getChildAt(0);
              assertEquals(ImageView.ScaleType.CENTER_CROP, image.getScaleType());
              TextView label = (TextView) card.getChildAt(1);
              assertNull("Labels stay unfilled", label.getBackground());
              if (card.isSelected()) {
                selected = true;
                assertEquals(AppStyle.ACCENT, label.getCurrentTextColor());
                Bitmap pixels =
                    Bitmap.createBitmap(
                        frame.getWidth(), frame.getHeight(), Bitmap.Config.ARGB_8888);
                frame.draw(new Canvas(pixels));
                assertEquals(
                    "White selection stroke",
                    Color.WHITE,
                    pixels.getPixel(pixels.getWidth() / 2, 1));
                pixels.recycle();
              }
            }
            assertTrue(selected);
            SpectralRenderer renderer = (SpectralRenderer) field("renderer");
            Field last = SpectralRenderer.class.getDeclaredField("lastOpen");
            last.setAccessible(true);
            android.os.Bundle opened = (android.os.Bundle) last.get(renderer);
            assertEquals(((File) field("sourceFile")).getAbsolutePath(), opened.getString("path"));
            JSONObject open = new JSONObject(opened.getString("params"));
            assertEquals(800, open.getInt("preview_long_edge"));
            assertTrue(open.getBoolean("product_defaults"));
            assertNull("Thumbnails invalidate main engine state", field("openedSource"));
            Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
            try (FileOutputStream out =
                new FileOutputStream(
                    new File(activity.getExternalFilesDir(null), "film-gallery.png"))) {
              screenshot.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            screenshot.recycle();
            ((HorizontalScrollView) field("filmScrollRef")).fullScroll(View.FOCUS_RIGHT);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    await(
        "Scrolling lazily loads later stocks",
        () -> cache().size() > initial && !(Boolean) field("thumbBusy"));
  }

  public void testEditsKeepGalleryFixedAndNewPhotoInvalidates() throws Exception {
    importPhoto(false);
    Map<String, Bitmap> old = read(() -> new HashMap<>(cache()));
    String fingerprint = read(() -> (String) field("thumbPrint"));
    main(
        () -> {
          try {
            Map<String, SeekBar> sliders = (Map<String, SeekBar>) field("sliders");
            SeekBar exposure = sliders.get("exposure_compensation_ev");
            long now = SystemClock.uptimeMillis();
            float y = exposure.getHeight() / 2f;
            for (int i = 0; i < 3; i++) {
              float x = exposure.getWidth() * (i == 0 ? .5f : .7f);
              int action =
                  i == 0
                      ? MotionEvent.ACTION_DOWN
                      : i == 1 ? MotionEvent.ACTION_MOVE : MotionEvent.ACTION_UP;
              MotionEvent touch = MotionEvent.obtain(now, now + i * 16, action, x, y, 0);
              exposure.dispatchTouchEvent(touch);
              touch.recycle();
            }
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    idleGallery();
    assertEquals(
        "Editing does not change the gallery cache key",
        fingerprint,
        read(() -> (String) field("thumbPrint")));
    read(
        () -> {
          for (String stock : old.keySet())
            if (cache().containsKey(stock))
              assertSame(
                  "Exposure must keep every cached visible stock",
                  old.get(stock),
                  cache().get(stock));
          return null;
        });
    main(
        () -> {
          try {
            JSONObject settings = (JSONObject) field("settings");
            settings.put("grade_exposure", .7);
            settings.put("hsl_red_saturation", 25);
            settings.put("grain_amount", 1.8);
            settings.put("date_enabled", true);
            settings.put("date_text", "2001.02.03");
            call("scheduleRender", new Class<?>[] {});
            call("buildPanel", new Class<?>[] {});
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    idleGallery();
    read(
        () -> {
          assertEquals(
              "All secondary edits leave the gallery fixed", fingerprint, field("thumbPrint"));
          for (String stock : old.keySet())
            if (cache().containsKey(stock))
              assertSame(
                  "No secondary edits appear in gallery images",
                  old.get(stock),
                  cache().get(stock));
          return null;
        });
    int beforeScroll = read(() -> cache().size());
    main(() -> {
      try {
        ((HorizontalScrollView) field("filmScrollRef")).fullScroll(View.FOCUS_RIGHT);
      } catch (Exception e) { throw new RuntimeException(e); }
    });
    await("Uncached stocks render after editing", () -> cache().size() > beforeScroll && !(Boolean) field("thumbBusy"));
    read(() -> {
      SpectralRenderer renderer = (SpectralRenderer) field("renderer");
      Field last = SpectralRenderer.class.getDeclaredField("lastOpen");
      last.setAccessible(true);
      JSONObject neutral = new JSONObject(((android.os.Bundle) last.get(renderer)).getString("params"));
      assertEquals("New gallery entries ignore user exposure", 0.0, neutral.getDouble("exposure_compensation_ev"), 0.0001);
      assertEquals("New gallery entries retain default grain", 1.0, neutral.getDouble("grain_amount"), 0.0001);
      return null;
    });
    Map<String, Bitmap> firstPhoto = read(() -> new HashMap<>(cache()));
    importPhoto(true);
    read(
        () -> {
          for (String stock : firstPhoto.keySet())
            if (cache().containsKey(stock))
              assertNotSame(
                  "New photo cannot reuse another photo's image",
                  firstPhoto.get(stock),
                  cache().get(stock));
          return null;
        });
  }

  public void testMainPreviewReopensAfterThumbnailStockAndRemainsCorrect() throws Exception {
    importPhoto(false);
    main(
        () -> {
          try {
            JSONObject settings = (JSONObject) field("settings");
            settings.put("grain_amount", 0);
            settings.put("halation_amount", 0);
            call(
                "set",
                new Class<?>[] {String.class, Object.class},
                "film_stock",
                "kodak_ektar_100");
            call("scheduleRender", new Class<?>[] {});
            call("buildPanel", new Class<?>[] {});
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    idleGallery();
    assertEquals(
        "kodak_ektar_100", read(() -> ((JSONObject) field("settings")).getString("film_stock")));
    Bitmap displayed =
        read(() -> ((Bitmap) field("developed")).copy(Bitmap.Config.ARGB_8888, false));
    String params = read(() -> (String) call("params", new Class<?>[] {}));
    String edits = read(() -> (String) call("editParams", new Class<?>[] {}));
    Bitmap source = read(() -> (Bitmap) field("source"));
    ExecutorService worker = (ExecutorService) field("worker");
    Bitmap reference =
        worker
            .submit(
                () -> {
                  SpectralRenderer renderer = (SpectralRenderer) field("renderer");
                  try {
                    String open =
                        (String) call("openParams", new Class<?>[] {String.class}, params);
                    renderer.open(source, open);
                    return renderer.render(params, edits, false);
                  } finally {
                    field("openedSource", null);
                  }
                })
            .get(30, TimeUnit.SECONDS);
    assertEquals(reference.getWidth(), displayed.getWidth());
    assertEquals(reference.getHeight(), displayed.getHeight());
    long distance = 0;
    int samples = 0;
    for (int y = 0; y < reference.getHeight(); y += 8)
      for (int x = 0; x < reference.getWidth(); x += 8) {
        int a = reference.getPixel(x, y), b = displayed.getPixel(x, y);
        distance +=
            Math.abs(Color.red(a) - Color.red(b))
                + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
        samples += 3;
      }
    assertTrue(
        "Main preview matches a fresh open of its selected stock",
        distance / (double) samples < 1.0);
    reference.recycle();
    displayed.recycle();
  }

  public void testNotReadyDefersAndAutomaticallyRefills() throws Exception {
    importPhoto(false);
    main(
        () -> {
          try {
            call("restartRenderer", new Class<?>[] {String.class}, "auto");
            call("refillThumbs", new Class<?>[] {});
            assertEquals(0, cache().size());
            assertFalse((Boolean) field("thumbBusy"));
            assertFalse(
                "Restart defers thumbnails until the engine is ready", (Boolean) field("ready"));
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    idleGallery();
    assertTrue(read(() -> cache().size()) >= 3);
  }

  /**
   * 点击画廊卡片只应切换选中态。面板会整体重建，若不沿用上次滚动位置，整排会被重新定位到
   * 「选中项 − 一格」，用户会看到卡片横向滑一下，像点歪了。
   */
  public void testTappingACardKeepsTheGalleryWhereTheUserScrolled() throws Exception {
    importPhoto(false);
    idleGallery();
    main(
        () -> {
          try {
            ((HorizontalScrollView) field("filmScrollRef")).fullScroll(View.FOCUS_RIGHT);
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    int before = read(() -> galleryScrollX());
    assertTrue("Sanity: the gallery was actually scrolled away from the start", before > 0);
    main(
        () -> {
          try {
            LinearLayout cards = (LinearLayout) field("filmCards");
            View target = cards.getChildAt(cards.getChildCount() - 1);
            assertNotNull("A tappable card exists", target);
            target.performClick();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    await(
        "Rebuilt gallery must restore the previous scroll position instead of re-centering",
        () -> galleryScrollX() == before);
    int after = read(() -> galleryScrollX());
    assertEquals("Tapping a card must not shift the row", before, after);
  }

  /** 新照片一律从「全部」的第一张开始，而不是跳到选中胶片所属的分类。 */
  public void testNewPhotoStartsAtTheFirstCardOfAllFilms() throws Exception {
    importPhoto(false);
    idleGallery();
    assertEquals("全部", read(() -> (String) field("filmCategory")));
    int scroll = read(() -> galleryScrollX());
    assertEquals("First card, no scroll", 0, scroll);
  }

  /** 点中间那张不动；点右边缘那张、且右边还有胶片时，才滑一格。 */
  public void testOnlyTheEdgeCardScrollsTheGallery() throws Exception {
    importPhoto(false);
    idleGallery();
    int start = read(() -> galleryScrollX());
    main(
        () -> {
          try {
            LinearLayout cards = (LinearLayout) field("filmCards");
            cards.getChildAt(1).performClick();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    await("Interior tap keeps the row still", () -> galleryScrollX() == start);

    main(
        () -> {
          try {
            HorizontalScrollView scroll = (HorizontalScrollView) field("filmScrollRef");
            scroll.scrollTo(0, 0);
            LinearLayout cards = (LinearLayout) field("filmCards");
            View edge = null;
            for (int i = 0; i < cards.getChildCount(); i++) {
              View c = cards.getChildAt(i);
              if (c.getLeft() < scroll.getWidth() && c.getRight() > scroll.getWidth() - 1) {
                edge = c;
                break;
              }
            }
            assertNotNull("A card cut by the right edge exists", edge);
            edge.performClick();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    await("Right-edge tap advances exactly one card", () -> galleryScrollX() > start);
  }

  /** 每个分类各自记住停留位置：切走再切回回到原处，没动过的分类从第一张开始。 */
  public void testEachCategoryRemembersItsOwnPosition() throws Exception {
    importPhoto(false);
    idleGallery();
    int stride = read(() -> (Integer) field("thumbStridePx"));
    main(
        () -> {
          try {
            ((HorizontalScrollView) field("filmScrollRef")).scrollTo(stride * 2, 0);
            ((LinearLayout) field("filmCards")).getChildAt(3).performClick();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    await("Tapping a card records the position in its category", () -> galleryScrollX() == stride * 2);

    main(
        () -> {
          try {
            field("filmCategory", "反转片");
            field("pendingFilmScrollX", Integer.valueOf(0));
            call("buildPanel", new Class<?>[] {});
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    await("A category never touched starts at its first card", () -> galleryScrollX() == 0);

    main(
        () -> {
          try {
            Map<String, Integer> memo = (Map<String, Integer>) field("categoryScrollX");
            field("filmCategory", "全部");
            field("pendingFilmScrollX", memo.getOrDefault("全部", 0));
            call("buildPanel", new Class<?>[] {});
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    await("Returning restores the remembered position", () -> galleryScrollX() == stride * 2);
  }

  private int galleryScrollX() throws Exception {
    HorizontalScrollView scroll = (HorizontalScrollView) field("filmScrollRef");
    return scroll == null ? -1 : scroll.getScrollX();
  }
}
