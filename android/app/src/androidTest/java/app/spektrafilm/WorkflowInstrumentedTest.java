// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
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
    Throwable[] failure = {null};
    getInstrumentation().runOnMainSync(() -> {
      try { run.run(); }
      catch (Throwable error) { failure[0] = error; }
    });
    getInstrumentation().waitForIdleSync();
    if (failure[0] instanceof AssertionError) throw (AssertionError) failure[0];
    if (failure[0] != null) throw new RuntimeException(failure[0]);
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

  public void testOpeningLastFrameAfterRestartRendersItsSavedEdits() throws Exception {
    importPhoto(image(), null);
    main(
        () -> {
          try {
            ((JSONObject) field("settings")).put("grade_exposure", .45);
            call("scheduleRender", new Class<?>[] {});
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    awaitPreview();
    String saved = settings().toString();
    String id = (String) field("activeId");
    main(activity::finish);
    setActivity(null);
    activity = getActivity();
    long end = android.os.SystemClock.elapsedRealtime() + 30000;
    while (!(Boolean) field("ready") && android.os.SystemClock.elapsedRealtime() < end)
      Thread.sleep(40);
    assertTrue("Engine restored after restarting the activity", (Boolean) field("ready"));
    assertEquals(id, field("activeId"));
    assertNull("Home defers developing the restored photograph", field("developed"));
    main(
        () -> {
          try {
            LinearLayout history = (LinearLayout) field("historyList");
            ((ViewGroup) history.getChildAt(0)).getChildAt(0).performClick();
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    awaitPreview();
    assertEquals(View.VISIBLE, ((View) field("editorScreen")).getVisibility());
    assertEquals("Reopening preserves the saved adjustments", saved, settings().toString());
  }

  private void awaitPreview() throws Exception {
    long end = android.os.SystemClock.elapsedRealtime() + 15000;
    while (android.os.SystemClock.elapsedRealtime() < end) {
      boolean[] complete = {false};
      main(
          () -> {
            try {
              TextView export = (TextView) field("exportButton");
              complete[0] =
                  field("developed") != null
                      && export.isEnabled()
                      && "导出照片".contentEquals(export.getText());
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      if (complete[0]) return;
      Thread.sleep(40);
    }
    fail("Opening the saved photo must produce a developed preview and enable export");
  }

  private Uri image() throws Exception {
    ContentValues values = new ContentValues();
    values.put(MediaStore.Images.Media.DISPLAY_NAME, "SpektraLens-workflow.JPG");
    values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SpektraLensTests");
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
    assertTrue("New exports carry the new project name", name.startsWith("SpektraLens_"));
    Uri shared = Uri.parse("content://app.spektrafilm.files/" + name);
    try (InputStream in = activity.getContentResolver().openInputStream(shared)) {
      assertEquals("Renamed JPEG remains readable through the sharing provider", 0xff, in.read());
      assertEquals(0xd8, in.read());
    }
    try (android.database.Cursor cursor =
        activity
            .getContentResolver()
            .query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                new String[] {MediaStore.Images.Media._ID, MediaStore.Images.Media.RELATIVE_PATH},
                MediaStore.Images.Media.DISPLAY_NAME + " = ?",
                new String[] {name},
                null)) {
      assertNotNull(cursor);
      assertTrue("The renamed export is saved to the phone gallery", cursor.moveToFirst());
      created.add(
          ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)));
      assertEquals("Pictures/SpektraLens/", cursor.getString(1));
    }
  }

  public void testEarlierSharedFileNamesRemainReadableAfterRename() throws Exception {
    File prior = File.createTempFile("Spektra_legacy_", ".jpg", activity.getCacheDir());
    try {
      try (FileOutputStream out = new FileOutputStream(prior)) {
        out.write(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xd9});
      }
      Uri uri = Uri.parse("content://app.spektrafilm.files/" + prior.getName());
      try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
        assertEquals(0xff, in.read());
        assertEquals(0xd8, in.read());
      }
      Uri invalid =
          Uri.parse("content://app.spektrafilm.files/")
              .buildUpon()
              .appendPath("../SpektraLens_legacy_rename_test.jpg")
              .build();
      try (android.database.Cursor result =
          activity.getContentResolver().query(invalid, null, null, null, null)) {
        assertNull("Reject path traversal even when querying only file metadata", result);
      }
    } finally {
      prior.delete();
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
    // 默认胶片是目录第一张（Fujifilm C200），与「导入后停在全部第一张」保持一致。
    assertEquals("fujifilm_c200", state.getString("film_stock"));
    assertEquals(0., state.optDouble("grade_exposure", 0));
    assertEquals(0., state.optDouble("geo_x", 0));
    assertEquals(1., state.getDouble("geo_w"));
    assertEquals("none", state.getString("edge_style"));
    assertFalse(state.optBoolean("date_enabled"));
    assertFalse(state.getBoolean("auto_exposure"));
    JSONObject engineParams = new JSONObject((String) call("params", new Class<?>[] {}));
    assertFalse(
        "The engine must receive the default, not only the switch",
        engineParams.getBoolean("auto_exposure"));
    assertEquals("balanced", state.getString("auto_exposure_method"));
    assertTrue(((ArrayDeque<?>) field("undoStack")).isEmpty());
    assertTrue(((ArrayDeque<?>) field("redoStack")).isEmpty());
    assertFalse(
        "Original comparison is not active on a new photo",
        ((TextView) field("compareButton")).isSelected());
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
            state.put("auto_exposure", true);
            state.put("auto_exposure_method", "balanced");
            ((TextView) field("compareButton")).performClick();
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

  public void testAutoExposureDefaultsOffAndCanBeEnabledManually() throws Exception {
    Uri uri = image();
    importPhoto(uri, null);
    main(
        () -> {
          try {
            call("showEditor", new Class<?>[] {});
            field("selectedTab", 5);
            call("buildPanel", new Class<?>[] {});
            View screen = (View) field("editorScreen");
            assertTrue(find(screen, "关闭", false).isSelected());
            String[][] modes = {
              {"均衡", "balanced"}, {"中央主体", "center"},
              {"保护高光", "protect_highlights"}, {"保护阴影", "protect_shadows"}
            };
            for (String[] mode : modes) {
              View chip = find((View) field("editorScreen"), mode[0], false);
              assertNotNull(mode[0], chip);
              chip.performClick();
              JSONObject request = new JSONObject((String) call("params", new Class<?>[] {}));
              assertTrue(request.getBoolean("auto_exposure"));
              assertEquals(mode[1], request.getString("auto_exposure_method"));
              assertTrue(find((View) field("editorScreen"), mode[0], false).isSelected());
            }
            find((View) field("editorScreen"), "关闭", false).performClick();
            assertFalse(new JSONObject((String) call("params", new Class<?>[] {}))
                .getBoolean("auto_exposure"));
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    importPhoto(uri, null);
    defaults(settings());
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

  public void testFilmCardsKeepTheCategoryAndPaperHintIsVisible() throws Exception {
    String[] selections = {"Fujifilm Provia 100F", "Kodak Vision3 250D", "Kodak Portra 400"};
    for (String selection : selections) {
      main(
          () -> {
            try {
              call("showEditor", new Class<?>[] {});
              field("selectedTab", 0);
              field("filmCategory", "全部");
              call("buildPanel", new Class<?>[] {});
              View card = find((View) field("editorScreen"), "胶片 " + selection, true);
              assertNotNull(selection, card);
              card.performClick();
              // 点卡片只负责选胶片，分类交给上方的 chips：否则在「全部」下点一张卡，
              // 列表会突然缩到某个分类，停留位置也跟着乱。
              assertEquals("全部", field("filmCategory"));
              assertTrue(find((View) field("editorScreen"), "全部", false).isSelected());
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
    File file = new File(activity.getCacheDir(), "SpektraLens-label-test.JPG");
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
