// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.provider.MediaStore;
import android.test.ActivityInstrumentationTestCase2;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.json.*;

/** New-photo defaults, explicit carryover, saved-photo isolation and the visible controls. */
public final class WorkflowInstrumentedTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;
  private final List<Uri> created = new ArrayList<>();

  public WorkflowInstrumentedTest() {
    super(MainActivity.class);
  }

  private Object field(String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(activity);
  }

  private void field(String name, Object value) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(activity, value);
  }

  private Object call(String name, Class<?>[] types, Object... args) throws Exception {
    Method method = MainActivity.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(activity, args);
  }

  private void main(Runnable run) {
    getInstrumentation().runOnMainSync(run);
    getInstrumentation().waitForIdleSync();
  }

  protected void setUp() throws Exception {
    super.setUp();
    activity = getActivity();
    long end = android.os.SystemClock.elapsedRealtime() + 30000;
    while (!(Boolean) field("ready") && android.os.SystemClock.elapsedRealtime() < end)
      Thread.sleep(40);
    assertTrue((Boolean) field("ready"));
  }

  protected void tearDown() throws Exception {
    for (Uri uri : created) activity.getContentResolver().delete(uri, null, null);
    super.tearDown();
  }

  private Uri image() throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.Images.Media.DISPLAY_NAME, "Spektra-workflow.JPG");
    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SpektraTests");
    Uri uri =
        activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    created.add(uri);
    Bitmap bitmap = Bitmap.createBitmap(96, 72, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(0xffc49a62);
    try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out));
    }
    bitmap.recycle();
    return uri;
  }

  private void importPhoto(Uri uri, String preset) throws Exception {
    File previous = (File) field("sourceFile");
    main(
        () -> {
          try {
            call("importUri", new Class<?>[] {Uri.class, String.class}, uri, preset);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    long end = android.os.SystemClock.elapsedRealtime() + 10000;
    while (android.os.SystemClock.elapsedRealtime() < end) {
      boolean[] done = new boolean[1];
      main(
          () -> {
            try {
              done[0] = field("activeImport") == null && !previous.equals(field("sourceFile"));
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
          });
      if (done[0]) return;
      Thread.sleep(40);
    }
    fail("Import did not commit its new-photo state");
  }

  private JSONObject settings() throws Exception {
    return new JSONObject(field("settings").toString());
  }

  private AccessibilityNodeInfo ui(String label) throws Exception {
    long end = android.os.SystemClock.elapsedRealtime() + 15000;
    while (android.os.SystemClock.elapsedRealtime() < end) {
      AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
      if (root != null) {
        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(label))
          if (label.contentEquals(node.getText())) return node;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("Missing visible control: " + label);
  }

  private GridView grid(View root) {
    if (root instanceof GridView) return (GridView) root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        GridView found = grid(((ViewGroup) root).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private void exported() throws Exception {
    main(
        () -> {
          try {
            call("export", new Class<?>[] {int.class}, 0);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    ui("已保存到相册");
    // Clean up only this test's exported media, identified by the actual export record.
    File record = new File(activity.getFilesDir(), "last_export.json");
    String name =
        new JSONObject(
                new String(
                    java.nio.file.Files.readAllBytes(record.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8))
            .getString("export");
    try (android.database.Cursor cursor =
        activity
            .getContentResolver()
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.Images.Media._ID},
                MediaStore.Images.Media.DISPLAY_NAME + " = ?",
                new String[] {name},
                null)) {
      if (cursor != null && cursor.moveToFirst())
        created.add(
            ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)));
    }
  }

  public void testExportSuccessButtonsCarrySnapshotThroughPhoneGalleryAndHomeResets()
      throws Exception {
    Uri uri = image();
    importPhoto(uri, null);
    main(
        () -> {
          try {
            call("showEditor", new Class<?>[] {});
            JSONObject state = (JSONObject) field("settings");
            state.put("grade_exposure", .75);
            state.put("grain_amount", 1.4);
            state.put("geo_w", .8);
            state.put("date_enabled", true);
            state.put("date_text", "2001.02.03");
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    JSONObject preset = settings();
    exported();
    ui("回到首页");
    assertTrue(ui("再编辑一张").performAction(AccessibilityNodeInfo.ACTION_CLICK));
    getInstrumentation().waitForIdleSync();
    GallerySheet sheet = (GallerySheet) field("gallerySheet");
    assertNotNull(sheet);
    assertTrue(sheet.isShowing());
    GridView grid = grid(sheet.getWindow().getDecorView());
    long end = android.os.SystemClock.elapsedRealtime() + 8000;
    int chosen = -1;
    while (chosen < 0 && android.os.SystemClock.elapsedRealtime() < end) {
      for (int i = 0; i < grid.getAdapter().getCount(); i++)
        if (uri.equals(((GalleryPhotos.Photo) grid.getAdapter().getItem(i)).uri)) {
          chosen = i;
          break;
        }
      if (chosen < 0) Thread.sleep(40);
    }
    assertTrue("Phone gallery contains this test's photo", chosen >= 0);
    File previous = (File) field("sourceFile");
    final int position = chosen;
    main(() -> grid.performItemClick(null, position, grid.getAdapter().getItemId(position)));
    end = android.os.SystemClock.elapsedRealtime() + 10000;
    while (previous.equals(field("sourceFile")) && android.os.SystemClock.elapsedRealtime() < end)
      Thread.sleep(40);
    assertFalse(previous.equals(field("sourceFile")));
    JSONObject carried = settings();
    assertEquals(preset.length(), carried.length());
    for (Iterator<String> keys = preset.keys(); keys.hasNext(); ) {
      String key = keys.next();
      assertEquals(key, preset.get(key).toString(), carried.get(key).toString());
    }
    exported();
    assertTrue(ui("回到首页").performAction(AccessibilityNodeInfo.ACTION_CLICK));
    getInstrumentation().waitForIdleSync();
    assertEquals(View.VISIBLE, ((View) field("homeScreen")).getVisibility());
    main(
        () -> {
          try {
            call("importPhoto", new Class<?>[] {String.class}, preset.toString());
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    main(
        () -> {
          try {
            ((GallerySheet) field("gallerySheet")).dismiss();
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    importPhoto(uri, null);
    defaults(settings());
  }

  private void defaults(JSONObject state) throws Exception {
    assertEquals("kodak_portra_400", state.getString("film_stock"));
    assertEquals(0., state.optDouble("grade_exposure", 0));
    assertEquals(0., state.optDouble("geo_x", 0));
    assertEquals(1., state.getDouble("geo_w"));
    assertEquals("none", state.getString("edge_style"));
    assertFalse(state.optBoolean("date_enabled"));
    assertTrue(state.getBoolean("auto_exposure"));
    assertEquals("center_weighted", state.getString("auto_exposure_method"));
    assertTrue(((ArrayDeque<?>) field("undoStack")).isEmpty());
    assertTrue(((ArrayDeque<?>) field("redoStack")).isEmpty());
  }

  public void testOrdinaryImportsResetAndExplicitCarryDoesNotLeakToNextImport() throws Exception {
    Uri uri = image();
    importPhoto(uri, null);
    defaults(settings());
    String oldId = field("activeId").toString();
    main(
        () -> {
          try {
            JSONObject state = (JSONObject) field("settings");
            state.put("grade_exposure", 1.8);
            state.put("geo_x", .2);
            state.put("geo_w", .6);
            state.put("edge_style", "instant");
            state.put("date_enabled", true);
            state.put("date_text", "2001.02.03");
            state.put("grain_amount", 1.7);
            state.put("auto_exposure", false);
            state.put("auto_exposure_method", "median");
            call("saveActiveFrame", new Class<?>[] {});
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    JSONObject preset = settings();
    importPhoto(uri, null);
    defaults(settings());
    Object frame = call("findFrame", new Class<?>[] {String.class}, oldId);
    Field params = frame.getClass().getDeclaredField("params");
    params.setAccessible(true);
    assertEquals(preset.toString(), params.get(frame));
    importPhoto(uri, preset.toString());
    JSONObject carried = settings();
    assertEquals(preset.length(), carried.length());
    for (Iterator<String> keys = preset.keys(); keys.hasNext(); ) {
      String key = keys.next();
      assertEquals(key, preset.get(key).toString(), carried.get(key).toString());
    }
    importPhoto(uri, null);
    defaults(settings());
    main(
        () -> {
          try {
            field("pickerSettings", preset.toString());
            call(
                "onActivityResult",
                new Class<?>[] {int.class, int.class, Intent.class},
                10,
                android.app.Activity.RESULT_CANCELED,
                null);
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    assertNull(field("pickerSettings"));
  }

  private View find(View root, String text, boolean description) {
    CharSequence value =
        description
            ? root.getContentDescription()
            : root instanceof TextView ? ((TextView) root).getText() : null;
    if (value != null
        && (description ? value.toString().startsWith(text) : value.toString().equals(text)))
      return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View result = find(((ViewGroup) root).getChildAt(i), text, description);
        if (result != null) return result;
      }
    return null;
  }

  public void testFilmCardsSynchronizeCategoryAndPaperHintIsVisible() throws Exception {
    String[][] selections = {
      {"Fujifilm Provia 100F", "反转片"}, {"Kodak Vision3 250D", "电影胶片"}, {"Kodak Portra 400", "彩色负片"}
    };
    for (String[] selection : selections) {
      main(
          () -> {
            try {
              call("showEditor", new Class<?>[] {});
              field("selectedTab", 0);
              field("filmCategory", "全部");
              call("buildPanel", new Class<?>[] {});
              View card = find((View) field("editorScreen"), "胶片 " + selection[0], true);
              assertNotNull(selection[0], card);
              card.performClick();
              assertEquals(selection[1], field("filmCategory"));
              assertTrue(find((View) field("editorScreen"), selection[1], false).isSelected());
            } catch (Exception ex) {
              throw new RuntimeException(ex);
            }
          });
    }
    main(
        () -> {
          try {
            field("selectedTab", 1);
            call("buildPanel", new Class<?>[] {});
            assertNotNull(find((View) field("editorScreen"), "相纸与已选择的胶片联动，默认无需选择", false));
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
  }

  public void testFileLabelsUseActualFormatAndLeaveOriginalNameUntouched() throws Exception {
    File file = new File(activity.getCacheDir(), "Spektra-label-test.JPG");
    Bitmap bitmap = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
    try (OutputStream out = new FileOutputStream(file)) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    bitmap.recycle();
    assertEquals("PNG", PhotoLabels.format(file, "wrong.JPG"));
    assertEquals("photo.with.dots", PhotoLabels.title("photo.with.dots.JPG"));
    assertTrue(file.length() > 0);
    file.delete();
  }
}
