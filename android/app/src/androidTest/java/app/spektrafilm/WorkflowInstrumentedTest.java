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
  private String pasteSourceId;

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
            ViewGroup card =
                (ViewGroup) ((ViewGroup) history.getChildAt(0)).getChildAt(0);
            assertFalse("Metadata no longer opens the editor", card.isClickable());
            card.getChildAt(0).performClick();
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    awaitPreview();
    assertEquals(View.VISIBLE, ((View) field("editorScreen")).getVisibility());
    assertEquals("Reopening preserves the saved adjustments", saved, settings().toString());
  }

  public void testRecentPhotoLongPressShowsTheCompleteActionMenu() throws Exception {
    importPhoto(image(), null);
    main(
        () -> {
          try {
            call("showHome", new Class<?>[] {});
            LinearLayout history = (LinearLayout) field("historyList");
            ViewGroup card = (ViewGroup) ((ViewGroup) history.getChildAt(0)).getChildAt(0);
            assertTrue(((ViewGroup) card.getChildAt(0)).performLongClick());
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    main(
        () -> {
          try {
            android.app.Dialog sheet = (android.app.Dialog) field("frameActions");
            String[] labels = {
              "开始编辑", "复制编辑参数", "粘贴编辑参数", "批量粘贴编辑参数",
              "添加收藏", "导出照片", "删除照片"
            };
            for (int action = 0; action < labels.length; action++) {
              View row = sheet.getWindow().getDecorView().findViewWithTag("library.actions.row." + action);
              assertNotNull(labels[action], row);
              assertTrue(labels[action], row.getContentDescription().toString().startsWith(labels[action]));
            }
            sheet.dismiss();
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
  }

  public void testBatchFooterKeepsButtonsVisibleAndSelectionCompact() throws Exception {
    importPhoto(image(), null);
    android.content.SharedPreferences prefs = (android.content.SharedPreferences) field("prefs");
    String oldClipboard = prefs.getString("edit_clipboard", null);
    try {
      main(
          () -> {
            try {
              call("showHome", new Class<?>[] {});
              call("saveActiveFrame", new Class<?>[] {});
              Object frame = ((java.util.List<?>) field("library")).get(0);
              assertTrue((Boolean) call("saveEditClipboard", new Class<?>[] {frame.getClass()}, frame));
              call("beginBatchPaste", new Class<?>[] {});
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      main(
          () -> {
            try {
              TextView confirm = (TextView) field("batchPasteBar");
              LinearLayout actions = (LinearLayout) confirm.getParent();
              TextView cancel = (TextView) actions.getChildAt(0);
              assertEquals("取消", cancel.getText().toString());
              assertEquals("确认粘贴 · 0 张", confirm.getText().toString());
              assertFalse(confirm.isEnabled());
              for (TextView button : new TextView[] {cancel, confirm}) {
                assertTrue(button.getHeight() >= AppStyle.dp(activity, AppStyle.TOUCH_PRIMARY));
                assertTrue("Button label must fit vertically at the device density",
                    button.getHeight() >= button.getLayout().getHeight()
                        + button.getPaddingTop() + button.getPaddingBottom());
                assertEquals("Button label must fit without ellipsis", 0,
                    button.getLayout().getEllipsisCount(0));
              }
              LinearLayout history = (LinearLayout) field("historyList");
              ViewGroup card = (ViewGroup) ((ViewGroup) history.getChildAt(0)).getChildAt(0);
              ViewGroup photo = (ViewGroup) card.getChildAt(0);
              View check = photo.getChildAt(photo.getChildCount() - 1);
              assertEquals(View.VISIBLE, check.getVisibility());
              assertEquals(AppStyle.dp(activity, AppStyle.SELECTION_BADGE), check.getWidth());
              assertEquals(AppStyle.dp(activity, AppStyle.SELECTION_BADGE), check.getHeight());
              assertTrue(photo.performClick());
              assertTrue(confirm.isEnabled());
              assertEquals("确认粘贴 · 1 张", confirm.getText().toString());
              LinearLayout.LayoutParams cancelParams =
                  (LinearLayout.LayoutParams) cancel.getLayoutParams();
              LinearLayout.LayoutParams confirmParams =
                  (LinearLayout.LayoutParams) confirm.getLayoutParams();
              assertEquals(1f, cancelParams.weight, 0f);
              assertEquals(1f, confirmParams.weight, 0f);
              assertEquals(cancelParams.width, confirmParams.width);
              assertEquals(AppStyle.dp(activity, AppStyle.TOUCH_PRIMARY), cancelParams.height);
              View shell = (View) field("batchPasteShell");
              shell.measure(View.MeasureSpec.makeMeasureSpec(AppStyle.dp(activity, 360), View.MeasureSpec.EXACTLY),
                  View.MeasureSpec.makeMeasureSpec(AppStyle.dp(activity, 600), View.MeasureSpec.AT_MOST));
              shell.layout(shell.getLeft(), shell.getTop(), shell.getLeft() + shell.getMeasuredWidth(),
                  shell.getTop() + shell.getMeasuredHeight());
              assertTrue(((View) field("homeContent")).getPaddingBottom()
                  >= shell.getHeight() + AppStyle.dp(activity, 18));
              call("showNotice", new Class<?>[] {String.class}, "batch feedback");
              View notice = activity.getWindow().getDecorView().findViewWithTag(AppNotice.TAG);
              assertTrue(((FrameLayout.LayoutParams) notice.getLayoutParams()).bottomMargin
                  >= shell.getHeight() + AppStyle.dp(activity, 10));
              assertTrue(cancel.performClick());
              assertFalse((Boolean) field("batchPasteMode"));
              assertTrue(((java.util.Set<?>) field("batchSelection")).isEmpty());
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
    } finally {
      main(
          () -> {
            try {
              call("exitBatchPaste", new Class<?>[] {});
              android.content.SharedPreferences.Editor restore = prefs.edit();
              if (oldClipboard == null) restore.remove("edit_clipboard");
              else restore.putString("edit_clipboard", oldClipboard);
              assertTrue(restore.commit());
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
    }
  }

  public void testPhotoManageModeDeletesAllAndRestoresDemoBanner() throws Exception {
    importPhoto(image(), null);
    main(
        () -> {
          try {
            TextView title = (TextView) field("historyTitle");
            assertNotNull(title);
            String label = title.getText().toString();
            assertTrue(label.startsWith("最近照片"));
            assertTrue(label.contains("1 张"));
            TextView manage = (TextView) field("homeManage");
            assertEquals(View.VISIBLE, manage.getVisibility());
            assertEquals("照片管理", manage.getText().toString());
            assertTrue(manage.performClick());
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    assertTrue((Boolean) field("manageMode"));
    main(
        () -> {
          try {
            TextView delete = (TextView) field("manageDeleteBar");
            assertEquals("删除 · 0 张", delete.getText().toString());
            assertFalse(delete.isEnabled());
            assertEquals(View.VISIBLE, ((View) field("manageActionRow")).getVisibility());
            assertEquals(
                View.GONE,
                ((View) ((View) field("batchPasteBar")).getParent()).getVisibility());
            TextView manage = (TextView) field("homeManage");
            assertEquals("管理期间不重复展示入口", View.GONE, manage.getVisibility());
            View headerActions = (View) field("manageHeaderActions");
            assertEquals(View.VISIBLE, headerActions.getVisibility());
            TextView selectAll = (TextView) field("homeSelectAll");
            TextView clear = (TextView) field("homeClearSelection");
            assertTrue(selectAll.isEnabled());
            assertFalse(clear.isEnabled());
            ((TextView) field("homeImportButton")).performClick();
            assertNull("照片管理期间不能打开导入", field("gallerySheet"));
            ((ImageView) field("homeHero")).performClick();
            assertEquals(View.GONE, ((View) field("editorScreen")).getVisibility());
            assertTrue(selectAll.performClick());
            assertTrue(clear.isEnabled());
            assertEquals("删除 · 1 张", delete.getText().toString());
            assertEquals(AppStyle.ON_PRIMARY, delete.getCurrentTextColor());
            LinearLayout manageRow = (LinearLayout) field("manageActionRow");
            TextView manageCancel = (TextView) manageRow.getChildAt(0);
            LinearLayout.LayoutParams cancelParams =
                (LinearLayout.LayoutParams) manageCancel.getLayoutParams();
            LinearLayout.LayoutParams deleteParams =
                (LinearLayout.LayoutParams) delete.getLayoutParams();
            assertEquals(1f, cancelParams.weight, 0f);
            assertEquals(1f, deleteParams.weight, 0f);
            assertEquals(cancelParams.width, deleteParams.width);
            assertEquals(AppStyle.dp(activity, AppStyle.TOUCH_PRIMARY), cancelParams.height);
            assertTrue(clear.performClick());
            assertFalse(clear.isEnabled());
            assertEquals("删除 · 0 张", delete.getText().toString());
            ViewGroup history = (ViewGroup) field("historyList");
            ViewGroup card = (ViewGroup) ((ViewGroup) history.getChildAt(0)).getChildAt(0);
            ViewGroup photo = (ViewGroup) card.getChildAt(0);
            assertEquals(View.VISIBLE, photo.getChildAt(photo.getChildCount() - 1).getVisibility());
            assertTrue(photo.performClick());
            assertTrue(delete.isEnabled());
            assertEquals("删除 · 1 张", delete.getText().toString());
            assertTrue(photo.performClick());
            assertFalse(delete.isEnabled());
            assertEquals("删除 · 0 张", delete.getText().toString());
            assertTrue(photo.performClick());
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    main(
        () -> {
          try {
            ViewGroup actions = (ViewGroup) field("manageActionRow");
            TextView cancel = (TextView) actions.getChildAt(0);
            assertEquals("取消", cancel.getText().toString());
            assertTrue(cancel.performClick());
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    assertFalse((Boolean) field("manageMode"));
    assertTrue(((java.util.Set<?>) field("manageSelection")).isEmpty());

    main(
        () -> {
          try {
            ((TextView) field("homeManage")).performClick();
            ViewGroup history = (ViewGroup) field("historyList");
            ((ViewGroup) ((ViewGroup) history.getChildAt(0)).getChildAt(0))
                .getChildAt(0)
                .performClick();
            call("removeSelectedPhotos", new Class<?>[] {});
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    long end = android.os.SystemClock.elapsedRealtime() + 20000;
    boolean[] done = {false};
    while (android.os.SystemClock.elapsedRealtime() < end) {
      main(
          () -> {
            try {
              boolean empty = ((java.util.List<?>) field("library")).isEmpty();
              boolean demo = "demo".equals(field("activeId"));
              File source = (File) field("sourceFile");
              ImageView hero = (ImageView) field("homeHero");
              done[0] =
                  empty
                      && demo
                      && !(Boolean) field("manageMode")
                      && source != null
                      && "demo.jpg".equals(source.getName())
                      && hero.getDrawable() != null;
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      if (done[0]) break;
      Thread.sleep(60);
    }
    assertTrue("删除全部照片后回到示例图与首页", done[0]);
    main(
        () -> {
          try {
            String label = ((TextView) field("historyTitle")).getText().toString();
            assertTrue(label.contains("0 张"));
            assertEquals(View.GONE, ((TextView) field("homeManage")).getVisibility());
            TextView hint = (TextView) field("homeImportHint");
            assertEquals(View.GONE, hint.getVisibility());
            assertEquals("", hint.getText().toString());
            assertFalse((Boolean) field("batchPasteMode"));
            assertFalse(new File(activity.getFilesDir(), "source.img").exists());
            assertTrue(((ImageView) field("homeHero")).getDrawable() != null);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
  }

  public void testUneditedPhotoEntryUsesC200AndKeepsLaterPanelState() throws Exception {
    importPhoto(image(), null);
    main(
        () -> {
          try {
            // Simulate a photo imported before the C200 default was pinned.
            Object frame = ((java.util.List<?>) field("library")).get(0);
            Field edited = frame.getClass().getDeclaredField("edited");
            edited.setAccessible(true);
            edited.setBoolean(frame, false);
            ((JSONObject) field("settings")).put("film_stock", "cinestill_800t");
            field("committed", field("settings").toString());
            field("editorPhotoId", "");
            field("selectedTab", 5);
            field("filmCategory", "电影胶片");
            call("showHome", new Class<?>[] {});
            call("openFrame", new Class<?>[] {frame.getClass()}, frame);
            assertEquals("fujifilm_c200", ((JSONObject) field("settings")).optString("film_stock"));
            assertEquals("全部", field("filmCategory"));
            assertEquals(0, field("selectedTab"));
            assertFalse("Applying the entry default is not a user edit", edited.getBoolean(frame));
            assertTrue(((java.util.Deque<?>) field("undoStack")).isEmpty());

            field("selectedTab", 5);
            call("buildPanel", new Class<?>[] {});
            call("showHome", new Class<?>[] {});
            call("openFrame", new Class<?>[] {frame.getClass()}, frame);
            assertEquals("The same photo retains its tool after the initial entry", 5, field("selectedTab"));

            // Existing edits and clipboard-applied recipes must never be defaulted back to C200.
            edited.setBoolean(frame, true);
            ((JSONObject) field("settings")).put("film_stock", "kodak_ektar_100");
            field("committed", field("settings").toString());
            field("editorPhotoId", "");
            call("openFrame", new Class<?>[] {frame.getClass()}, frame);
            assertEquals("kodak_ektar_100", ((JSONObject) field("settings")).optString("film_stock"));
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
  }

  public void testEveryHomeEditingEntryOpensTheFilmTool() throws Exception {
    importPhoto(image(), null);
    awaitPreview();
    for (String entry : new String[] {"menu", "thumbnail", "hero"}) {
      for (int previousTool : new int[] {1, 2, 4, 5, 0}) {
        main(
          () -> {
            try {
              Object frame = ((java.util.List<?>) field("library")).get(0);
              Field edited = frame.getClass().getDeclaredField("edited");
              edited.setAccessible(true);
              edited.setBoolean(frame, true);
              String recipe = field("settings").toString();
              int undoCount = ((java.util.Deque<?>) field("undoStack")).size();
              call("showHome", new Class<?>[] {});
              field("selectedTab", previousTool);
              field("panelCollapsed", true);
              if (entry.equals("menu")) {
                call("showFrameActions", new Class<?>[] {frame.getClass()}, frame);
                android.app.Dialog sheet = (android.app.Dialog) field("frameActions");
                View edit = sheet.getWindow().getDecorView().findViewWithTag("library.actions.row.0");
                assertTrue(edit.getContentDescription().toString().startsWith("再次编辑"));
                assertTrue(edit.performClick());
                assertFalse(sheet.isShowing());
              } else if (entry.equals("hero")) {
                assertTrue(((View) field("homeHero")).performClick());
              } else {
                ViewGroup history = (ViewGroup) field("historyList");
                ViewGroup card = (ViewGroup) ((ViewGroup) history.getChildAt(0)).getChildAt(0);
                assertTrue(card.getChildAt(0).performClick());
              }
              assertEquals(0, field("selectedTab"));
              assertFalse((Boolean) field("panelCollapsed"));
              assertEquals(View.VISIBLE, ((View) field("editorScreen")).getVisibility());
              assertNotNull(field("filmScrollRef"));
              assertEquals("Opening the film tool preserves all edits", recipe, field("settings").toString());
              assertEquals(undoCount, ((java.util.Deque<?>) field("undoStack")).size());
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      }
    }
  }

  public void testHomeExportOpensExportSheetWithoutEnteringEditor() throws Exception {
    importPhoto(image(), null);
    main(
        () -> {
          try {
            call("showHome", new Class<?>[] {});
            Object frame =
                ((java.util.List<?>) field("library")).get(0);
            Class<?> frameType =
                Class.forName("app.spektrafilm.MainActivity$Frame");
            call("exportFrame", new Class<?>[] {frameType}, frame);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    awaitExportSheet();
    main(
        () -> {
          try {
            assertEquals(View.GONE, ((View) field("editorScreen")).getVisibility());
            assertTrue(((android.app.Dialog) field("exportSheet")).isShowing());
            ((android.app.Dialog) field("exportSheet")).dismiss();
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
  }

  public void testPasteUpdatesTheActiveFrameAndItsPreview() throws Exception {
    importPhoto(image(), null);
    main(
        () -> {
          try {
            ((JSONObject) field("settings")).put("grade_exposure", .35);
            call("saveActiveFrame", new Class<?>[] {});
            Object source =
                ((java.util.List<?>) field("library")).get(0);
            Field id = source.getClass().getDeclaredField("id");
            id.setAccessible(true);
            pasteSourceId = (String) id.get(source);
            Class<?> frameType =
                Class.forName("app.spektrafilm.MainActivity$Frame");
            assertTrue(
                (Boolean)
                    call(
                        "saveEditClipboard",
                        new Class<?>[] {frameType},
                        source));
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    importPhoto(image(), null);
    main(
        () -> {
          try {
            Object target = null;
            for (Object candidate : (java.util.List<?>) field("library")) {
              Field id = candidate.getClass().getDeclaredField("id");
              id.setAccessible(true);
              if (!pasteSourceId.equals(id.get(candidate))) {
                target = candidate;
                break;
              }
            }
            assertNotNull("A second imported photo is required for paste", target);
            Class<?> frameType =
                Class.forName("app.spektrafilm.MainActivity$Frame");
            call("pasteToFrame", new Class<?>[] {frameType}, target);
            assertEquals(
                .35,
                ((JSONObject) field("settings")).getDouble("grade_exposure"),
                .0001);
            assertEquals(
                .35,
                new JSONObject((String) call("params", new Class<?>[] {}))
                    .getDouble("grade_exposure"),
                .0001);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    awaitPreview();
  }

  public void testPasteRefreshesNonActiveHomeThumbnail() throws Exception {
    importPhoto(image(), null);
    final String[] sourceId = new String[1];
    main(
        () ->
            {
              try {
                Object source = ((java.util.List<?>) field("library")).get(0);
                Field id = source.getClass().getDeclaredField("id");
                id.setAccessible(true);
                sourceId[0] = (String) id.get(source);
                ((JSONObject) field("settings")).put("grade_exposure", .35);
                call("saveActiveFrame", new Class<?>[] {});
                Class<?> frameType =
                    Class.forName("app.spektrafilm.MainActivity$Frame");
                assertTrue(
                    (Boolean)
                        call(
                            "saveEditClipboard",
                            new Class<?>[] {frameType},
                            source));
              } catch (Exception error) {
                throw new RuntimeException(error);
              }
            });

    importPhoto(image(), null);
    main(
        () -> {
          try {
            call("showHome", new Class<?>[] {});
            Object target =
                call("findFrame", new Class<?>[] {String.class}, sourceId[0]);
            assertNotNull(target);
            Class<?> frameType =
                Class.forName("app.spektrafilm.MainActivity$Frame");
            call("pasteToFrame", new Class<?>[] {frameType}, target);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });

    File developed = new File(activity.getFilesDir(), "developed/" + sourceId[0] + ".jpg");
    long end = android.os.SystemClock.elapsedRealtime() + 30000;
    while (android.os.SystemClock.elapsedRealtime() < end
        && (!developed.isFile() || developed.length() == 0))
      Thread.sleep(50);
    assertTrue("Pasting must refresh a non-active home thumbnail", developed.isFile());
    assertTrue(developed.length() > 0);
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

  private void awaitExportSheet() throws Exception {
    long end = android.os.SystemClock.elapsedRealtime() + 15000;
    while (android.os.SystemClock.elapsedRealtime() < end) {
      boolean[] visible = {false};
      main(
          () -> {
            try {
              android.app.Dialog sheet =
                  (android.app.Dialog) field("exportSheet");
              visible[0] = sheet != null && sheet.isShowing();
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      if (visible[0]) return;
      Thread.sleep(40);
    }
    fail("Home export must open the existing export sheet without entering the editor");
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
    Uri shared = Uri.parse("content://" + activity.getPackageName() + ".files/" + name);
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
      Uri uri =
          Uri.parse("content://" + activity.getPackageName() + ".files/" + prior.getName());
      try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
        assertEquals(0xff, in.read());
        assertEquals(0xd8, in.read());
      }
      Uri invalid =
          Uri.parse("content://" + activity.getPackageName() + ".files/")
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
    main(
        () -> {
          View confirm = findPrefix(sheet.getWindow().getDecorView(), "确认导入");
          assertNotNull(confirm);
          confirm.performClick();
        });
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
    // 默认明确为 C200；当前按名称排序时 CineStill 在前，不能跟随目录第一张。
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
        ((View) field("compareButton")).isSelected());
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
            ((View) field("compareButton")).performClick();
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

  private View findPrefix(View root, String prefix) {
    CharSequence value = root instanceof TextView ? ((TextView) root).getText() : null;
    if (value != null && value.toString().startsWith(prefix)) return root;
    if (root instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
        View result = findPrefix(((ViewGroup) root).getChildAt(i), prefix);
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
