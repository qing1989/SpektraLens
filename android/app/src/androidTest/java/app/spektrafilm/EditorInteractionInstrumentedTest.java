// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.animation.ValueAnimator;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Real touch streams: reset is one edit, scrolling is not an edit, panel toggles are layout only. */
public final class EditorInteractionInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;
  private long time, downTime;
  private String originalSettings, originalId;
  private int originalOrientation;

  public EditorInteractionInstrumentedTest() {
    super(MainActivity.class);
  }

  protected void setUp() throws Exception {
    super.setUp();
    activity = getActivity();
    originalOrientation = activity.getRequestedOrientation();
    long deadline = SystemClock.elapsedRealtime() + 30000;
    while (!(Boolean) get("ready") && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
    assertTrue("Engine startup finished", (Boolean) get("ready"));
    main(
        () -> {
          originalSettings = settings().toString();
          originalId = (String) get("activeId");
          // These tests exercise settings/history/layout; native rendering has separate coverage.
          field("ready").setBoolean(activity, false);
          field("activeId").set(activity, "demo");
          field("settings").set(activity, new JSONObject().put("film_stock", "kodak_portra_400"));
          call("defaults");
          field("committed").set(activity, settings().toString());
          history("undoStack").clear();
          history("redoStack").clear();
          call("showEditor");
          activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        });
    waitForLandscape(false);
    time = SystemClock.uptimeMillis();
    page(2, "基础");
  }

  protected void tearDown() throws Exception {
    try {
      if (activity != null && originalSettings != null)
        main(
            () -> {
              call("updateEditorLayout");
              for (SeekBar bar : bars().values()) bar.setEnabled(false);
              field("settings").set(activity, new JSONObject(originalSettings));
              field("activeId").set(activity, originalId);
              activity.getSharedPreferences("darkroom", 0).edit()
                  .putString("settings", originalSettings).apply();
              activity.setRequestedOrientation(originalOrientation);
            });
    } finally {
      super.tearDown();
    }
  }

  private Field field(String name) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f;
  }

  private Object get(String name) throws Exception {
    return field(name).get(activity);
  }

  private void call(String name) throws Exception {
    Method m = MainActivity.class.getDeclaredMethod(name);
    m.setAccessible(true);
    m.invoke(activity);
  }

  private void historyStep(boolean redo) throws Exception {
    Method m = MainActivity.class.getDeclaredMethod("historyStep", boolean.class);
    m.setAccessible(true);
    m.invoke(activity, redo);
  }

  @SuppressWarnings("unchecked")
  private Map<String, SeekBar> bars() throws Exception {
    return (Map<String, SeekBar>) get("sliders");
  }

  @SuppressWarnings("unchecked")
  private ArrayDeque<String> history(String name) throws Exception {
    return (ArrayDeque<String>) get(name);
  }

  private JSONObject settings() throws Exception {
    return (JSONObject) get("settings");
  }

  private interface CheckedAction {
    void run() throws Exception;
  }

  private void main(CheckedAction action) throws Exception {
    AtomicReference<Throwable> failure = new AtomicReference<>();
    getInstrumentation().runOnMainSync(
        () -> {
          try {
            action.run();
          } catch (Throwable error) {
            failure.set(error);
          }
        });
    getInstrumentation().waitForIdleSync();
    if (failure.get() != null) throw new AssertionError(failure.get());
  }

  private void page(int tool, String section) throws Exception {
    main(
        () -> {
          field("panelCollapsed").setBoolean(activity, false);
          field("selectedTab").setInt(activity, tool);
          field("gradeSection").set(activity, section);
          if (tool == 4) settings().put("edge_style", "135_black");
          call("buildPanel");
        });
    main(() -> {});
  }

  private AdjustmentSeekBar bar(String key) throws Exception {
    return (AdjustmentSeekBar) bars().get(key);
  }

  private void event(AdjustmentSeekBar bar, int action, float x, float y) {
    time += 40;
    if (action == MotionEvent.ACTION_DOWN) downTime = time;
    MotionEvent e = MotionEvent.obtain(downTime, time, action, x, y, 0);
    bar.onTouchEvent(e);
    e.recycle();
  }

  private void tap(AdjustmentSeekBar bar, float x) {
    event(bar, MotionEvent.ACTION_DOWN, x, bar.getHeight() / 2f);
    event(bar, MotionEvent.ACTION_UP, x, bar.getHeight() / 2f);
  }

  private void doubleTap(AdjustmentSeekBar bar, float x) {
    time += ViewConfiguration.getDoubleTapTimeout() + 50;
    tap(bar, x);
    tap(bar, x);
  }

  private void change(AdjustmentSeekBar bar) {
    time += ViewConfiguration.getDoubleTapTimeout() + 50;
    float x =
        bar.getProgress() == bar.getMax()
            ? bar.getPaddingLeft()
            : bar.getWidth() - bar.getPaddingRight();
    tap(bar, x);
    bar.finishPendingTap();
  }

  private void setting(String key, Object value) throws Exception {
    Method setter = MainActivity.class.getDeclaredMethod("set", String.class, Object.class);
    setter.setAccessible(true);
    setter.invoke(activity, key, value);
  }

  private JSONObject renderParameters() throws Exception {
    Method parameters = MainActivity.class.getDeclaredMethod("params");
    parameters.setAccessible(true);
    return new JSONObject((String) parameters.invoke(activity));
  }

  private View tagged(String tag) throws Exception {
    return tagged((View) get("editor"), tag);
  }

  private View tagged(View view, String tag) {
    if (tag.equals(view.getTag())) return view;
    if (!(view instanceof android.view.ViewGroup)) return null;
    android.view.ViewGroup group = (android.view.ViewGroup) view;
    for (int i = 0; i < group.getChildCount(); i++) {
      View found = tagged(group.getChildAt(i), tag);
      if (found != null) return found;
    }
    return null;
  }

  public void testLightLeakStrengthShuffleAndUndo() throws Exception {
    String amount = "light_leak_amount", seed = "light_leak_seed";
    page(5, "基础");
    main(
        () -> {
          AdjustmentSeekBar strength = bar(amount);
          assertEquals(0, strength.getProgress());
          assertFalse(settings().has(seed));
          String initial = settings().toString();
          float y = strength.getHeight() / 2f;
          float left = strength.getPaddingLeft();
          float right = strength.getWidth() - strength.getPaddingRight();
          event(strength, MotionEvent.ACTION_DOWN, left, y);
          event(strength, MotionEvent.ACTION_MOVE, right, y);
          assertEquals(100, strength.getProgress());
          event(strength, MotionEvent.ACTION_MOVE, left, y);
          event(strength, MotionEvent.ACTION_UP, left, y);
          assertEquals(initial, settings().toString());
          assertTrue(history("undoStack").isEmpty());
          change(strength);
          assertEquals(100, settings().getInt(amount));
          assertTrue(settings().getInt(seed) > 0);
          assertEquals(1, history("undoStack").size());
          historyStep(false);
          assertEquals(0, bar(amount).getProgress());
          assertFalse(settings().has(seed));
        });
    main(
        () -> {
          View shuffle = tagged("editor.light_leak.shuffle");
          assertNotNull(shuffle);
          LinearLayout strengthRow = (LinearLayout) bar(amount).getParent();
          View strengthHeader = strengthRow.getChildAt(0);
          assertEquals(strengthRow.getLeft() + strengthHeader.getLeft()
              + strengthHeader.getPaddingLeft(), shuffle.getLeft());
          assertEquals(strengthRow.getLeft() + strengthHeader.getRight()
              - strengthHeader.getPaddingRight(), shuffle.getRight());
          assertTrue(shuffle.performClick());
          int first = settings().getInt(seed);
          assertEquals(50, bar(amount).getProgress());
          assertEquals(50, settings().getInt(amount));
          assertEquals(1, history("undoStack").size());
          change(bar(amount));
          assertEquals(100, settings().getInt(amount));
          assertEquals(first, settings().getInt(seed));
          assertTrue(shuffle.performClick());
          int second = settings().getInt(seed);
          assertTrue(first != second);
          assertEquals(100, settings().getInt(amount));
          assertEquals(3, history("undoStack").size());
          assertFalse(renderParameters().has(seed));
          historyStep(false);
          assertEquals(first, settings().getInt(seed));
          historyStep(true);
          assertEquals(second, settings().getInt(seed));
          assertEquals(100, bar(amount).getProgress());
        });
    main(
        () -> {
          int current = settings().getInt(seed);
          doubleTap(bar(amount), bar(amount).getWidth() / 2f);
          assertEquals(0, settings().getInt(amount));
          assertEquals(current, settings().getInt(seed));
        });
  }

  public void testLightLeakModePreservesStrengthAndCanBeUndone() throws Exception {
    page(5, "基础");
    main(
        () -> {
          assertTrue(tagged("editor.light_leak.color").isSelected());
          assertTrue(tagged("editor.light_leak.first_roll").performClick());
          assertEquals(0, bar("light_leak_amount").getProgress());
          assertTrue(tagged("editor.light_leak.shuffle").performClick());
          assertEquals(50, bar("light_leak_amount").getProgress());
          assertEquals("first_roll", settings().getString("light_leak_mode"));
          int seed = settings().getInt("light_leak_seed");
          assertTrue(tagged("editor.light_leak.color").performClick());
          assertEquals(50, bar("light_leak_amount").getProgress());
          assertEquals(seed, settings().getInt("light_leak_seed"));
          historyStep(false);
          assertTrue(tagged("editor.light_leak.first_roll").isSelected());
          assertEquals(50, bar("light_leak_amount").getProgress());
          assertEquals(seed, settings().getInt("light_leak_seed"));
        });
  }

  public void testMalformedCaptureDateDoesNotDropFocalLengths() throws Exception {
    java.io.File file = java.io.File.createTempFile("focal-exif-", ".jpg", activity.getCacheDir());
    Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
    try {
      try (java.io.FileOutputStream out = new java.io.FileOutputStream(file)) {
        assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out));
      }
      androidx.exifinterface.media.ExifInterface exif =
          new androidx.exifinterface.media.ExifInterface(file);
      exif.setAttribute("DateTimeOriginal", "not a capture date");
      exif.setAttribute("FocalLength", "92/10");
      exif.setAttribute("FocalLengthIn35mmFilm", "70");
      exif.saveAttributes();
      Method reader = MainActivity.class.getDeclaredMethod("readRasterMetadata",
          java.io.File.class, JSONObject.class);
      reader.setAccessible(true);
      JSONObject metadata = new JSONObject();
      reader.invoke(activity, file, metadata);
      assertFalse(metadata.has("timestamp"));
      assertEquals(9.2, metadata.getDouble("focal_mm"), .0001);
      assertEquals(70, metadata.getInt("focal_35mm"));
    } finally {
      bitmap.recycle();
      file.delete();
    }
  }

  public void testBlackWhitePaperPairingAndUndoRestoreColorEdits() throws Exception {
    main(
        () -> {
          setting("print_stock", "kodak_2383");
          setting("m_filter_shift", .12);
          setting("y_filter_shift", -.08);
          call("scheduleRender");
          history("undoStack").clear();
          history("redoStack").clear();
          String colorSettings = settings().toString();
          setting("film_stock", "kodak_tri_x_400");
          call("scheduleRender");
          assertEquals(1, history("undoStack").size());
          assertEquals("黑白", get("filmCategory"));
          assertEquals(FilmStockPolicy.SILVER_PAPER, settings().getString("print_stock"));
          JSONObject wire = renderParameters();
          assertEquals(68, wire.getDouble("y_filter_neutral"), .00001);
          assertEquals(0, wire.getDouble("m_filter_shift"), .00001);
          assertEquals(0, wire.getDouble("y_filter_shift"), .00001);
          assertEquals(.12, settings().getDouble("m_filter_shift"), .00001);
          assertEquals(-.08, settings().getDouble("y_filter_shift"), .00001);
          setting("print_stock", "kodak_2393");
          assertEquals(FilmStockPolicy.SILVER_PAPER, settings().getString("print_stock"));
          historyStep(false);
          assertEquals(colorSettings, settings().toString());
          wire = renderParameters();
          assertFalse(wire.has("y_filter_neutral"));
          assertFalse("Color keeps the 0.4.6 grain-layer default", wire.has("grain_sublayers_active"));
          assertEquals(.12, wire.getDouble("m_filter_shift"), .00001);
          historyStep(true);
          assertEquals(FilmStockPolicy.SILVER_PAPER, settings().getString("print_stock"));
          setting("film_stock", "fujifilm_c200");
          assertFalse(FilmStockPolicy.SILVER_PAPER.equals(settings().getString("print_stock")));
          assertFalse(renderParameters().has("y_filter_neutral"));
        });
  }

  public void testBlackWhiteGrainResetKeepsStockCalibrationAndColorSettings() throws Exception {
    main(
        () -> {
          setting("film_stock", "kodak_tri_x_400");
          setting("grain_amount", 2);
          call("scheduleRender");
        });
    page(5, "基础");
    main(
        () -> {
          assertEquals(2, renderParameters().getDouble("grain_amount"), .00001);
          int undo = history("undoStack").size();
          doubleTap(bar("grain_amount"), bar("grain_amount").getWidth() / 2f);
          assertEquals(1, settings().getDouble("grain_amount"), .00001);
          assertEquals(1.70, renderParameters().getDouble("grain_amount"), .00001);
          assertEquals(undo + 1, history("undoStack").size());
          historyStep(false);
          assertEquals(2, settings().getDouble("grain_amount"), .00001);
          assertEquals(2, renderParameters().getDouble("grain_amount"), .00001);
          setting("film_stock", "kodak_tmax_100");
          assertEquals(2, settings().getDouble("grain_amount"), .00001);
          assertEquals(1.48, renderParameters().getDouble("grain_amount"), .00001);
          setting("film_stock", "kodak_portra_400");
          assertEquals(2, renderParameters().getDouble("grain_amount"), .00001);
        });
  }

  public void testBlackWhitePaperPanelAndRawWire() throws Exception {
    main(() -> setting("film_stock", "ilford_hp5_plus_400"));
    page(1, "基础");
    main(
        () -> {
          assertTrue(bars().containsKey("print_brightness"));
          assertFalse(bars().containsKey("m_filter_shift"));
          assertFalse(bars().containsKey("y_filter_shift"));
          assertEquals("Multigrade IV RC", ((TextView) get("paperTitle")).getText().toString());
          boolean previousRaw = (Boolean) get("sourceRaw");
          try {
            for (boolean raw : new boolean[] {false, true}) {
              field("sourceRaw").setBoolean(activity, raw);
              JSONObject wire = renderParameters();
              assertEquals(raw ? "ProPhoto RGB" : "sRGB", wire.getString("input_color_space"));
              assertEquals(!raw, wire.getBoolean("input_cctf_decoding"));
              assertFalse(wire.getBoolean("extended_dynamic_range"));
              assertFalse(wire.getBoolean("scan_film"));
              assertFalse(wire.getBoolean("grain_sublayers_active"));
              assertEquals(1.49, wire.getDouble("grain_amount"), .00001);
            }
          } finally {
            field("sourceRaw").setBoolean(activity, previousRaw);
          }
          setting("film_stock", "fujifilm_c200");
          call("buildPanel");
          assertTrue(bars().containsKey("m_filter_shift"));
          assertTrue(bars().containsKey("y_filter_shift"));
        });
  }

  public void testBlackWhiteFilterAndPaperGradeStayInsideTheirWorkflow() throws Exception {
    page(0, "基础");
    main(() -> assertNull("Color films do not expose the monochrome filter",
        tagged("black_white_filter")));
    main(
        () -> {
          setting("film_stock", "kodak_tri_x_400");
          setting("camera_filter", "w25");
          setting("paper_grade", "5");
          call("buildPanel");
          View filter = tagged("black_white_filter");
          assertNotNull("Monochrome film exposes its lens filter", filter);
          assertEquals("红色", ((TextView) ((android.view.ViewGroup) filter).getChildAt(1))
              .getText().toString());
          JSONObject wire = renderParameters();
          assertEquals("w25", wire.getString("camera_filter"));
          assertEquals(151.7, wire.getDouble("m_filter_neutral"), .00001);
          assertEquals(0, wire.getDouble("y_filter_neutral"), .00001);
          assertEquals(1.3507, wire.getDouble("print_exposure"), .00001);
        });
    page(1, "基础");
    main(
        () -> {
          assertNotNull("Silver paper exposes contrast grades", tagged("paper_grade_scroll"));
          assertEquals(
              "最硬朗，保留黑白摄影的极端风格",
              ((TextView) tagged("paper_grade_description")).getText().toString());
          setting("film_stock", "kodak_portra_400");
          setting("camera_filter", "w25");
          setting("paper_grade", "5");
          call("buildPanel");
          assertNull("Color paper does not expose silver grades", tagged("paper_grade_scroll"));
          JSONObject wire = renderParameters();
          assertFalse(wire.has("camera_filter"));
          assertEquals(1, wire.getDouble("print_exposure"), .00001);
        });
  }

  public void testCineStillHeadroomVisibilityDefaultPersistenceAndUndo() throws Exception {
    main(
        () -> {
          setting("film_stock", FilmStockPolicy.CINESTILL_800T);
          assertEquals("电影胶片", get("filmCategory"));
        });
    page(5, "基础");
    main(
        () -> {
          AdjustmentSeekBar headroom = bar("halation_headroom_ev");
          assertNotNull("CineStill exposes highlight headroom", headroom);
          assertEquals(40, headroom.getProgress());
          assertEquals(4, renderParameters().getDouble("halation_headroom_ev"), .00001);
          setting("halation_headroom_ev", 6.5);
          call("scheduleRender");
          assertEquals(6.5, renderParameters().getDouble("halation_headroom_ev"), .00001);
          history("undoStack").clear();
          history("redoStack").clear();
          setting("halation_headroom_ev", 7.5);
          call("scheduleRender");
          assertEquals(1, history("undoStack").size());
          historyStep(false);
          assertEquals(6.5, settings().getDouble("halation_headroom_ev"), .00001);
          historyStep(true);
          assertEquals(7.5, settings().getDouble("halation_headroom_ev"), .00001);
          setting("film_stock", "kodak_portra_400");
          call("buildPanel");
          assertFalse("Headroom is specific to CineStill", bars().containsKey("halation_headroom_ev"));
          assertFalse(renderParameters().has("halation_headroom_ev"));
        });
  }

  public void testAllSliderDefaultsAndNoOpReset() throws Exception {
    Set<String> checked = new HashSet<>();
    for (int tool : new int[] {0, 1, 2, 4, 5}) {
      for (String section :
          tool == 2 ? new String[] {"基础", "颜色分级", "颜色混合", "暗角"} : new String[] {"基础"}) {
        page(tool, section);
        main(
            () -> {
              for (Map.Entry<String, SeekBar> entry : bars().entrySet()) {
                String key = entry.getKey();
                AdjustmentSeekBar bar = (AdjustmentSeekBar) entry.getValue();
                double expected = settings().optDouble(key, 0);
                int progress = bar.getProgress();
                change(bar);
                assertTrue(key + " changed by native seeking",
                    Math.abs(settings().optDouble(key) - expected) > .00001);
                int undo = history("undoStack").size(), revision = (Integer) get("revision");
                doubleTap(bar, bar.getWidth() / 2f);
                assertEquals(key + " reset", expected, settings().optDouble(key), .00001);
                assertEquals(key + " thumb", progress, bar.getProgress());
                assertEquals(key + " one render", revision + 1, (int) (Integer) get("revision"));
                assertEquals(key + " one undo step",
                    Math.min(30, undo + 1), history("undoStack").size());
                int resetRevision = (Integer) get("revision");
                String resetHistory = history("undoStack").toString();
                doubleTap(bar, bar.getPaddingLeft());
                assertEquals(key + " no-op render", resetRevision, (int) (Integer) get("revision"));
                assertEquals(key + " no-op undo", resetHistory, history("undoStack").toString());
                checked.add(key);
              }
            });
      }
    }
    assertTrue("All visible slider families covered", checked.size() >= 30);
    assertTrue(checked.contains("grain_amount"));
    assertTrue(checked.contains("grade_vignette_midpoint"));
    assertTrue(checked.contains("date_size"));
    assertTrue(checked.contains("edge_frame_number"));
  }

  public void testResetCanUndoAndRedoWithoutAnIntermediateTapEdit() throws Exception {
    main(
        () -> {
          AdjustmentSeekBar bar = bar("grade_exposure");
          change(bar);
          double edited = settings().optDouble("grade_exposure");
          String prior = settings().toString();
          int undo = history("undoStack").size();
          time += 1000;
          tap(bar, bar.getPaddingLeft());
          assertEquals("First tap is buffered", prior, settings().toString());
          tap(bar, bar.getPaddingLeft());
          assertEquals(0, settings().optDouble("grade_exposure"), .00001);
          assertEquals(undo + 1, history("undoStack").size());
          historyStep(false);
          assertEquals(edited, settings().optDouble("grade_exposure"), .00001);
          historyStep(true);
          assertEquals(0, settings().optDouble("grade_exposure"), .00001);
        });
  }

  public void testDragThenTapAndSecondTapDragDoNotReset() throws Exception {
    main(
        () -> {
          AdjustmentSeekBar bar = bar("grade_temperature");
          int[] resets = {0};
          bar.setOnResetListener(() -> resets[0]++);
          float left = bar.getPaddingLeft(), right = bar.getWidth() - bar.getPaddingRight();
          float y = bar.getHeight() / 2f;
          event(bar, MotionEvent.ACTION_DOWN, left, y);
          event(bar, MotionEvent.ACTION_MOVE, right, y);
          event(bar, MotionEvent.ACTION_UP, right, y);
          tap(bar, right);
          bar.finishPendingTap();
          assertEquals("Drag + tap is not double tap", 0, resets[0]);
          time += 1000;
          tap(bar, left);
          event(bar, MotionEvent.ACTION_DOWN, left, y);
          event(bar, MotionEvent.ACTION_MOVE, right, y);
          event(bar, MotionEvent.ACTION_UP, right, y);
          assertEquals("Second contact drag must seek", bar.getMax(), bar.getProgress());
          assertEquals(0, resets[0]);
        });
  }

  public void testVerticalScrollCancelLongHoldAndDisableDoNotReset() throws Exception {
    main(
        () -> {
          AdjustmentSeekBar bar = bar("grade_temperature");
          int[] resets = {0};
          bar.setOnResetListener(() -> resets[0]++);
          int initial = bar.getProgress();
          float x = bar.getWidth() / 2f, y = bar.getHeight() / 2f;
          event(bar, MotionEvent.ACTION_DOWN, x, y);
          event(bar, MotionEvent.ACTION_MOVE, x, y + bar.getHeight() * 3);
          event(bar, MotionEvent.ACTION_UP, x, y);
          bar.finishPendingTap();
          assertEquals("Vertical scroll leaves value unchanged", initial, bar.getProgress());
          time += 1000;
          tap(bar, x);
          event(bar, MotionEvent.ACTION_DOWN, x, y);
          event(bar, MotionEvent.ACTION_CANCEL, x, y);
          bar.finishPendingTap();
          time += 1000;
          tap(bar, x);
          event(bar, MotionEvent.ACTION_DOWN, x, y);
          time += ViewConfiguration.getLongPressTimeout();
          event(bar, MotionEvent.ACTION_UP, x, y);
          time += 1000;
          tap(bar, bar.getPaddingLeft());
          bar.setEnabled(false);
          bar.setEnabled(true);
          bar.finishPendingTap();
          assertEquals(initial, bar.getProgress());
          assertEquals(0, resets[0]);
        });
  }

  public void testAccessibilityResetUsesTheSameDefaultAndUndo() throws Exception {
    page(5, "基础");
    main(
        () -> {
          AdjustmentSeekBar bar = bar("grain_amount");
          change(bar);
          double edited = settings().optDouble("grain_amount");
          assertTrue(bar.performAccessibilityAction(R.id.reset_adjustment, null));
          assertEquals(1, settings().optDouble("grain_amount"), .00001);
          historyStep(false);
          assertEquals(edited, settings().optDouble("grain_amount"), .00001);
        });
  }

  public void testSingleTapConfirmsOnceAfterDoubleTapWindow() throws Exception {
    int[] revision = new int[1];
    main(
        () -> {
          AdjustmentSeekBar bar = bar("grade_temperature");
          revision[0] = (Integer) get("revision");
          tap(bar, bar.getWidth() - bar.getPaddingRight());
          assertEquals("Don't seek before deciding single/double tap",
              0, settings().optDouble("grade_temperature", 0), .00001);
        });
    Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 100);
    main(
        () -> {
          assertEquals(100, settings().optDouble("grade_temperature"), .00001);
          assertEquals(revision[0] + 1, (int) (Integer) get("revision"));
        });
  }

  public void testPendingTapCannotEditAfterItsPanelIsReplaced() throws Exception {
    main(
        () -> {
          AdjustmentSeekBar old = bar("grade_temperature");
          tap(old, old.getPaddingLeft());
          call("buildPanel");
          old.finishPendingTap();
          assertEquals(0, settings().optDouble("grade_temperature", 0), .00001);
        });
  }

  public void testHslAndBalanceResetOnlyTheChosenBandOrZone() throws Exception {
    for (String kind : new String[] {"hsl", "balance"}) {
      String[] groups =
          kind.equals("hsl")
              ? new String[] {"red", "orange", "yellow", "green", "cyan", "blue", "purple", "magenta"}
              : new String[] {"master", "shadows", "midtones", "highlights"};
      for (String group : groups) {
        main(
            () -> {
              field(kind.equals("hsl") ? "hslBand" : "balanceZone").set(activity, group);
              settings().put(kind + "_" + group + "_saturation", 25);
              settings().put(kind + "_" + group + "_luminance", 17);
            });
        page(2, kind.equals("hsl") ? "颜色混合" : "颜色分级");
        main(
            () -> {
              String key = kind + "_" + group + "_saturation";
              AdjustmentSeekBar selected = bar(key);
              doubleTap(selected, selected.getWidth() / 2f);
              assertEquals(key, 0, settings().optDouble(key), .00001);
              assertEquals("Other parameter preserved",
                  17, settings().optDouble(kind + "_" + group + "_luminance"), .00001);
            });
      }
    }
  }

  private void tool(int index) throws Exception {
    main(() -> ((LinearLayout) get("tabRow")).getChildAt(index).performClick());
    waitForEditorMotion();
  }

  private void previewTap() throws Exception {
    main(
        () -> {
          PhotoView photo = (PhotoView) get("photo");
          long now = SystemClock.uptimeMillis();
          MotionEvent down =
              MotionEvent.obtain(
                  now, now, MotionEvent.ACTION_DOWN, photo.getWidth() / 2f,
                  photo.getHeight() / 2f, 0);
          MotionEvent up =
              MotionEvent.obtain(
                  now, now + 40, MotionEvent.ACTION_UP, photo.getWidth() / 2f,
                  photo.getHeight() / 2f, 0);
          photo.onTouchEvent(down);
          photo.onTouchEvent(up);
          down.recycle();
          up.recycle();
        });
    Thread.sleep(ViewConfiguration.getDoubleTapTimeout() + 100);
    getInstrumentation().waitForIdleSync();
    waitForEditorMotion();
  }

  private void waitForEditorMotion() throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 10000;
    boolean[] settled = {false};
    while (SystemClock.elapsedRealtime() < deadline) {
      main(
          () -> {
            settled[0] = get("panelAnimator") == null
                && !((View) get("editorBody")).isLayoutRequested();
            LinearLayout navigation = (LinearLayout) get("tabRow");
            for (int i = 0; i < navigation.getChildCount(); i++) {
              View tab = navigation.getChildAt(i);
              settled[0] &= tab.getAlpha() == 1f
                  && tab.getScaleX() == 1f && tab.getScaleY() == 1f;
            }
          });
      if (settled[0]) return;
      Thread.sleep(16);
    }
    fail("Editor transition did not settle");
  }

  /** Deterministically inspect a middle frame without changing the device animation settings. */
  private boolean holdPanelMotion() throws Exception {
    ValueAnimator animation = (ValueAnimator) get("panelAnimator");
    if (animation == null) {
      assertFalse("Portrait should animate when enabled", ValueAnimator.areAnimatorsEnabled());
      return false;
    }
    animation.pause();
    animation.setCurrentFraction(.4f);
    return true;
  }

  private void assertRestingTransforms() throws Exception {
    assertNull(get("panelAnimator"));
    View panel = (View) get("panel");
    assertEquals(1f, panel.getAlpha(), 0f);
    assertEquals(0f, panel.getTranslationY(), 0f);
  }

  private void assertNavigationVisible() throws Exception {
    View navigation = (View) get("tabRow"), body = (View) get("editorBody");
    assertTrue(navigation.isShown());
    int[] nav = new int[2], bounds = new int[2];
    navigation.getLocationOnScreen(nav);
    body.getLocationOnScreen(bounds);
    assertTrue("Navigation remains inside screen",
        nav[1] + navigation.getHeight() <= bounds[1] + body.getHeight());
  }

  public void testCollapseExpandsPreviewAndPreservesPanelAndPhotoState() throws Exception {
    AtomicReference<View> content = new AtomicReference<>();
    int[] height = new int[1], revision = new int[1], scrollY = new int[1];
    int[] generation = new int[1];
    String[] params = new String[1], undo = new String[1], redo = new String[1];
    Object[] pending = new Object[1], developed = new Object[1];
    main(
        () -> {
          ScrollView scroll = (ScrollView) get("panelScroll");
          scroll.scrollTo(0, 100);
          scrollY[0] = scroll.getScrollY();
          content.set(scroll);
          height[0] = ((View) get("photoStage")).getHeight();
          revision[0] = (Integer) get("revision");
          params[0] = settings().toString();
          undo[0] = history("undoStack").toString();
          redo[0] = history("redoStack").toString();
          pending[0] = get("pending");
          developed[0] = get("developed");
          generation[0] = (Integer) get("thumbGeneration");
        });
    tool(1);
    main(
        () -> {
          assertEquals(View.GONE, ((View) get("panel")).getVisibility());
          assertTrue(((View) get("photoStage")).getHeight() > height[0]);
          assertSame("Panel content is retained", content.get(), get("panelScroll"));
          assertNavigationVisible();
          LinearLayout navigation = (LinearLayout) get("tabRow");
          for (int i = 0; i < navigation.getChildCount(); i++)
            assertFalse("Hidden tools aren't marked open", navigation.getChildAt(i).isSelected());
          assertEquals(params[0], settings().toString());
          assertEquals(revision[0], (int) (Integer) get("revision"));
        });
    tool(1);
    main(
        () -> {
          assertEquals(View.VISIBLE, ((View) get("panel")).getVisibility());
          assertEquals(height[0], ((View) get("photoStage")).getHeight());
          assertSame(content.get(), get("panelScroll"));
          assertEquals(scrollY[0], ((ScrollView) get("panelScroll")).getScrollY());
          assertEquals(revision[0], (int) (Integer) get("revision"));
          assertEquals(undo[0], history("undoStack").toString());
          assertEquals(redo[0], history("redoStack").toString());
          assertSame("No new render request", pending[0], get("pending"));
          assertSame("Preview pixels reused", developed[0], get("developed"));
          assertEquals("Gallery generation preserved",
              generation[0], (int) (Integer) get("thumbGeneration"));
          assertRestingTransforms();
        });
    tool(1);
    tool(3);
    main(
        () -> {
          assertEquals(5, (int) (Integer) get("selectedTab"));
          assertEquals(View.VISIBLE, ((View) get("panel")).getVisibility());
          assertEquals(height[0], ((View) get("photoStage")).getHeight());
        });
  }

  public void testMotionKeepsNavigationAndScrollViewportStillAndCanReverse() throws Exception {
    int[] originalHeight = new int[1], scrollHeight = new int[1], scrollY = new int[1];
    int[] navigationY = new int[2];
    boolean[] animated = new boolean[1];
    AtomicReference<View> content = new AtomicReference<>();
    main(
        () -> {
          ScrollView scroll = (ScrollView) get("panelScroll");
          scroll.scrollTo(0, 100);
          content.set(scroll);
          scrollY[0] = scroll.getScrollY();
          scrollHeight[0] = scroll.getHeight();
          originalHeight[0] = ((View) get("photoStage")).getHeight();
          ((View) get("tabRow")).getLocationOnScreen(navigationY);
          ((LinearLayout) get("tabRow")).getChildAt(1).performClick();
          animated[0] = holdPanelMotion();
        });
    main(
        () -> {
          assertNavigationVisible();
          int[] location = new int[2];
          ((View) get("tabRow")).getLocationOnScreen(location);
          assertEquals("Navigation must not move during collapse", navigationY[1], location[1]);
          assertTrue(((View) get("photoStage")).getHeight() > originalHeight[0]);
          if (animated[0]) {
            assertEquals("Scroll viewport stays full size during animation",
                scrollHeight[0], ((ScrollView) get("panelScroll")).getHeight());
            assertEquals(scrollY[0], ((ScrollView) get("panelScroll")).getScrollY());
            View panel = (View) get("panel");
            int height = panel.getLayoutParams().height;
            float alpha = panel.getAlpha(), shift = panel.getTranslationY();
            ((LinearLayout) get("tabRow")).getChildAt(1).performClick();
            assertEquals("Reverse from current height", height, panel.getLayoutParams().height);
            assertEquals("Reverse from current opacity", alpha, panel.getAlpha(), .00001f);
            assertEquals("Reverse from current position", shift, panel.getTranslationY(), .00001f);
          } else {
            ((LinearLayout) get("tabRow")).getChildAt(1).performClick();
          }
        });
    waitForEditorMotion();
    main(
        () -> {
          assertFalse((Boolean) get("panelCollapsed"));
          assertEquals(originalHeight[0], ((View) get("photoStage")).getHeight());
          assertSame(content.get(), get("panelScroll"));
          assertEquals(scrollY[0], ((ScrollView) get("panelScroll")).getScrollY());
          assertRestingTransforms();
        });
  }

  public void testSwitchingToolDuringMotionKeepsLatestIntentAndNoEdit() throws Exception {
    int[] revision = new int[1], height = new int[1];
    String[] params = new String[1];
    main(
        () -> {
          revision[0] = (Integer) get("revision");
          params[0] = settings().toString();
          height[0] = ((View) get("photoStage")).getHeight();
          ((LinearLayout) get("tabRow")).getChildAt(1).performClick();
          boolean animated = holdPanelMotion();
          View panel = (View) get("panel");
          int intermediate = panel.getLayoutParams().height;
          ((LinearLayout) get("tabRow")).getChildAt(3).performClick();
          if (animated)
            assertEquals("Switching tool must not snap open",
                intermediate, panel.getLayoutParams().height);
          // Reverse again before the previous transition has settled.
          ((LinearLayout) get("tabRow")).getChildAt(3).performClick();
          ((LinearLayout) get("tabRow")).getChildAt(2).performClick();
        });
    waitForEditorMotion();
    main(
        () -> {
          assertEquals(4, (int) (Integer) get("selectedTab"));
          assertFalse((Boolean) get("panelCollapsed"));
          assertEquals(View.VISIBLE, ((View) get("panel")).getVisibility());
          assertEquals(height[0], ((View) get("photoStage")).getHeight());
          assertEquals(revision[0], (int) (Integer) get("revision"));
          assertEquals(params[0], settings().toString());
          assertRestingTransforms();
          assertNavigationVisible();
        });
  }

  public void testMotionRetainsZoomPanAndComparison() throws Exception {
    PhotoView photo = (PhotoView) get("photo");
    String[] names = {"before", "after", "zoom", "offsetX", "offsetY", "compare", "split"};
    Field[] fields = new Field[names.length];
    Object[] previous = new Object[names.length];
    for (int i = 0; i < names.length; i++) {
      fields[i] = PhotoView.class.getDeclaredField(names[i]);
      fields[i].setAccessible(true);
    }
    Bitmap fixture = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888);
    try {
      main(
          () -> {
            for (int i = 0; i < fields.length; i++) previous[i] = fields[i].get(photo);
            photo.images(fixture, fixture);
            fields[2].setFloat(photo, 6f);
            // Small offsets remain inside this square image in both preview sizes.
            fields[3].setFloat(photo, 5f);
            fields[4].setFloat(photo, 7f);
            photo.compare(true);
            fields[6].setFloat(photo, .37f);
          });
      tool(1);
      tool(1);
      main(
          () -> {
            assertEquals(6f, fields[2].getFloat(photo), 0f);
            assertEquals(5f, fields[3].getFloat(photo), 0f);
            assertEquals(7f, fields[4].getFloat(photo), 0f);
            assertTrue(fields[5].getBoolean(photo));
            assertEquals(.37f, fields[6].getFloat(photo), 0f);
            assertSame(fixture, fields[0].get(photo));
            assertSame(fixture, fields[1].get(photo));
          });
    } finally {
      main(
          () -> {
            for (int i = 0; i < fields.length; i++) fields[i].set(photo, previous[i]);
            photo.invalidate();
          });
      fixture.recycle();
    }
  }

  public void testLeavingEditorAndRotatingFinishInFlightMotion() throws Exception {
    main(
        () -> {
          ((LinearLayout) get("tabRow")).getChildAt(1).performClick();
          holdPanelMotion();
          call("showHome");
          assertRestingTransforms();
          call("showEditor");
        });
    waitForEditorMotion();
    main(
        () -> {
          assertEquals(View.GONE, ((View) get("panel")).getVisibility());
          ((LinearLayout) get("tabRow")).getChildAt(1).performClick();
          holdPanelMotion();
          activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        });
    waitForLandscape(true);
    waitForEditorMotion();
    main(
        () -> {
          assertFalse((Boolean) get("panelCollapsed"));
          assertEquals(View.VISIBLE, ((View) get("panel")).getVisibility());
          assertRestingTransforms();
          assertNavigationVisible();
        });
  }

  public void testPaperSubtabIsRetainedWhenFilmToolClosesAndReopens() throws Exception {
    page(1, "基础");
    tool(0);
    main(() -> assertTrue((Boolean) get("panelCollapsed")));
    tool(0);
    main(
        () -> {
          assertFalse((Boolean) get("panelCollapsed"));
          assertEquals("Keep the paper subtab", 1, (int) (Integer) get("selectedTab"));
        });
  }

  public void testPreviewTapTogglesPanelAndRestoresLastPanel() throws Exception {
    page(2, "颜色混合");
    AtomicReference<View> content = new AtomicReference<>();
    int[] height = new int[1], scrollY = new int[1], revision = new int[1];
    int[] generation = new int[1];
    String[] params = new String[1], undo = new String[1], redo = new String[1];
    Object[] pending = new Object[1], developed = new Object[1];
    main(
        () -> {
          ScrollView scroll = (ScrollView) get("panelScroll");
          scroll.scrollTo(0, 80);
          scrollY[0] = scroll.getScrollY();
          content.set(scroll);
          height[0] = ((View) get("photoStage")).getHeight();
          revision[0] = (Integer) get("revision");
          params[0] = settings().toString();
          undo[0] = history("undoStack").toString();
          redo[0] = history("redoStack").toString();
          pending[0] = get("pending");
          developed[0] = get("developed");
          generation[0] = (Integer) get("thumbGeneration");
        });
    previewTap();
    main(
        () -> {
          assertTrue((Boolean) get("panelCollapsed"));
          assertEquals(View.GONE, ((View) get("panel")).getVisibility());
          assertTrue(((View) get("photoStage")).getHeight() > height[0]);
          assertEquals(2, (int) (Integer) get("selectedTab"));
          assertEquals("颜色混合", get("gradeSection"));
          assertSame(content.get(), get("panelScroll"));
          assertEquals(params[0], settings().toString());
          assertEquals(revision[0], (int) (Integer) get("revision"));
          assertEquals(undo[0], history("undoStack").toString());
          assertEquals(redo[0], history("redoStack").toString());
          assertSame(pending[0], get("pending"));
          assertSame(developed[0], get("developed"));
          assertEquals(generation[0], (int) (Integer) get("thumbGeneration"));
        });
    previewTap();
    main(
        () -> {
          assertFalse((Boolean) get("panelCollapsed"));
          assertEquals(View.VISIBLE, ((View) get("panel")).getVisibility());
          assertEquals(height[0], ((View) get("photoStage")).getHeight());
          assertEquals(2, (int) (Integer) get("selectedTab"));
          assertEquals("颜色混合", get("gradeSection"));
          assertSame(content.get(), get("panelScroll"));
          assertEquals(scrollY[0], ((ScrollView) get("panelScroll")).getScrollY());
          assertRestingTransforms();
        });
  }

  public void testPendingSingleTapCommitsBeforePanelCollapses() throws Exception {
    main(
        () -> {
          AdjustmentSeekBar bar = bar("grade_temperature");
          tap(bar, bar.getWidth() - bar.getPaddingRight());
          ((LinearLayout) get("tabRow")).getChildAt(1).performClick();
          assertTrue((Boolean) get("panelCollapsed"));
          assertEquals(100, settings().optDouble("grade_temperature"), .00001);
          assertEquals(1, history("undoStack").size());
        });
  }

  public void testLandscapeCollapseReleasesToolColumnAndSurvivesRotation() throws Exception {
    int requested = activity.getRequestedOrientation();
    try {
      main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
      waitForLandscape(true);
      int width = ((View) get("photoStage")).getWidth();
      tool(1);
      main(
          () -> {
            assertEquals(View.GONE, ((View) get("panel")).getVisibility());
            assertTrue("Photo uses space formerly occupied by right tools",
                ((View) get("photoStage")).getWidth() > width);
            assertNavigationVisible();
          });
      main(() -> activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
      waitForLandscape(false);
      main(
          () -> {
            assertTrue((Boolean) get("panelCollapsed"));
            assertEquals(View.GONE, ((View) get("panel")).getVisibility());
            assertNavigationVisible();
          });
      tool(1);
      main(() -> assertEquals(View.VISIBLE, ((View) get("panel")).getVisibility()));
    } finally {
      main(() -> activity.setRequestedOrientation(requested));
    }
  }

  private void waitForLandscape(boolean landscape) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 5000;
    while (SystemClock.elapsedRealtime() < deadline) {
      boolean current =
          activity.getResources().getConfiguration().orientation
              == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
      if (current == landscape) {
        getInstrumentation().waitForIdleSync();
        main(() -> {});
        return;
      }
      Thread.sleep(50);
    }
    fail("Orientation did not change");
  }
}
