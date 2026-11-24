// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.test.ActivityInstrumentationTestCase2;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.util.*;

/** Permission boundaries and actual grid-to-photo identity, independently of renderer speed. */
public final class GalleryInstrumentedTest extends ActivityInstrumentationTestCase2<MainActivity> {
  public GalleryInstrumentedTest() {
    super(MainActivity.class);
  }

  private Context accessContext(boolean all, boolean partial) {
    return new ContextWrapper(getInstrumentation().getTargetContext()) {
      public int checkSelfPermission(String permission) {
        if (all
            && (permission.equals(Manifest.permission.READ_MEDIA_IMAGES)
                || permission.equals(Manifest.permission.READ_EXTERNAL_STORAGE)))
          return PackageManager.PERMISSION_GRANTED;
        if (partial && permission.equals("android.permission.READ_MEDIA_VISUAL_USER_SELECTED"))
          return PackageManager.PERMISSION_GRANTED;
        return PackageManager.PERMISSION_DENIED;
      }

      public ContentResolver getContentResolver() {
        throw new AssertionError("Must not query photos before permission");
      }
    };
  }

  public void testDeniedPermissionDoesNotQueryMedia() {
    Context denied = accessContext(false, false);
    assertEquals(GalleryPhotos.Access.NONE, GalleryPhotos.access(denied));
    assertTrue(GalleryPhotos.query(denied, new android.os.CancellationSignal()).isEmpty());
  }

  public void testFullAndPartialPhotoPermissionsStayDistinct() throws Exception {
    assertEquals(GalleryPhotos.Access.FULL, GalleryPhotos.access(accessContext(true, false)));
    assertEquals(
        Build.VERSION.SDK_INT >= 34 ? GalleryPhotos.Access.PARTIAL : GalleryPhotos.Access.NONE,
        GalleryPhotos.access(accessContext(false, true)));
    Context context = getInstrumentation().getTargetContext();
    String[] requested =
        context
            .getPackageManager()
            .getPackageInfo(context.getPackageName(), PackageManager.GET_PERMISSIONS)
            .requestedPermissions;
    assertFalse(Arrays.asList(requested).contains(Manifest.permission.READ_MEDIA_VIDEO));
    assertFalse(Arrays.asList(requested).contains(Manifest.permission.CAMERA));
  }

  private View find(View view, String label) {
    if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = find(((ViewGroup) view).getChildAt(i), label);
        if (found != null) return found;
      }
    return null;
  }

  private GridView grid(View view) {
    if (view instanceof GridView) return (GridView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        GridView found = grid(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private ListView list(View view) {
    if (view instanceof ListView) return (ListView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        ListView found = list(((ViewGroup) view).getChildAt(i));
        if (found != null) return found;
      }
    return null;
  }

  private View findByDescription(View view, String description) {
    if (description.contentEquals(view.getContentDescription())) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = findByDescription(((ViewGroup) view).getChildAt(i), description);
        if (found != null) return found;
      }
    return null;
  }

  public void testThreeColumnGridKeepsExactPhotoIdentityAndConfirmsSelection() throws Exception {
    MainActivity activity = getActivity();
    List<GalleryPhotos.Photo> photos = new ArrayList<>();
    for (int i = 0; i < 3; i++)
      photos.add(
          GalleryPhotos.Photo.imported(
              "unique-frame-" + i,
              "image-" + i + ".jpg",
              new File(activity.getCacheDir(), "demo.jpg")));
    GallerySheet[] sheet = new GallerySheet[1];
    String[] selected = new String[1];
    getInstrumentation()
        .runOnMainSync(
            () -> {
              sheet[0] =
                  new GallerySheet(
                      activity,
                      0,
                      new GallerySheet.Listener() {
                        public void importPhotos(List<GalleryPhotos.Photo> chosen) {
                          selected[0] = chosen.get(0).frameId;
                        }

                        public void permission() {
                          fail(
                              "Permission must only follow a user action in this direct sheet"
                                  + " test");
                        }
                      });
              sheet[0].show();
              sheet[0].setPhotosForTest(photos);
            });
    try {
      getInstrumentation().waitForIdleSync();
      GridView grid = grid(sheet[0].getWindow().getDecorView());
      assertNotNull(grid);
      assertEquals(3, grid.getNumColumns());
      assertEquals(3, grid.getAdapter().getCount());
      for (String action : new String[] {"取消", "确认导入"}) {
        TextView button = (TextView) find(sheet[0].getWindow().getDecorView(), action);
        assertNotNull(action, button);
        assertEquals(AppStyle.dp(activity, AppStyle.TOUCH_PRIMARY), button.getHeight());
        assertTrue(action + " has room for its text and vertical padding",
            button.getHeight() >= button.getLayout().getHeight()
                + button.getPaddingTop() + button.getPaddingBottom());
      }
      for (int i = 0; i < 3; i++) {
        View tile = grid.getChildAt(i);
        assertNotNull(tile);
        assertEquals(tile.getWidth(), tile.getHeight());
        assertEquals(
            "unique-frame-" + i, ((GalleryPhotos.Photo) grid.getAdapter().getItem(i)).frameId);
      }
      getInstrumentation()
          .runOnMainSync(
              () -> grid.performItemClick(grid.getChildAt(1), 1, grid.getAdapter().getItemId(1)));
      getInstrumentation().waitForIdleSync();
      FrameLayout selectedTile = (FrameLayout) grid.getChildAt(1);
      View badge = selectedTile.getChildAt(2);
      assertEquals(View.VISIBLE, badge.getVisibility());
      assertEquals(AppStyle.dp(activity, AppStyle.SELECTION_BADGE), badge.getWidth());
      assertEquals(AppStyle.dp(activity, AppStyle.SELECTION_BADGE), badge.getHeight());
      assertTrue("The photo keeps the full selection target", selectedTile.getWidth() > badge.getWidth());
      assertTrue(sheet[0].isShowing());
      getInstrumentation()
          .runOnMainSync(
              () ->
                  findPrefix(
                          sheet[0].getWindow().getDecorView(), "确认导入")
                      .performClick());
      assertEquals("unique-frame-1", selected[0]);
      assertFalse(sheet[0].isShowing());
    } finally {
      getInstrumentation().runOnMainSync(() -> sheet[0].dismiss());
    }
  }

  private View findPrefix(View view, String prefix) {
    if (view instanceof TextView
        && ((TextView) view).getText().toString().startsWith(prefix)) return view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        View found = findPrefix(((ViewGroup) view).getChildAt(i), prefix);
        if (found != null) return found;
      }
    return null;
  }

  public void testSelectionStopsAtTwentyWithoutDiscardingPreviousChoices() throws Exception {
    MainActivity activity = getActivity();
    List<GalleryPhotos.Photo> photos = new ArrayList<>();
    for (int i = 0; i < 21; i++)
      photos.add(
          GalleryPhotos.Photo.imported(
              "limited-frame-" + i,
              "limited-" + i + ".jpg",
              new File(activity.getCacheDir(), "demo.jpg")));
    GallerySheet[] sheet = new GallerySheet[1];
    getInstrumentation()
        .runOnMainSync(
            () -> {
              sheet[0] =
                  new GallerySheet(
                      activity,
                      20,
                      new GallerySheet.Listener() {
                        public void importPhotos(List<GalleryPhotos.Photo> chosen) {}

                        public void permission() {}
                      });
              sheet[0].show();
              sheet[0].setPhotosForTest(photos);
            });
    try {
      getInstrumentation().waitForIdleSync();
      GridView grid = grid(sheet[0].getWindow().getDecorView());
      assertNotNull(grid);
      getInstrumentation()
          .runOnMainSync(
              () -> {
                for (int i = 0; i < 21; i++)
                  grid.performItemClick(null, i, grid.getAdapter().getItemId(i));
              });
      getInstrumentation().waitForIdleSync();
      View selectedTwenty =
          find(sheet[0].getWindow().getDecorView(), "已选 20 张");
      assertNotNull(selectedTwenty);
      View feedback = sheet[0].getWindow().getDecorView().findViewWithTag(AppNotice.TAG);
      assertTrue("Selection-limit feedback is text in the gallery's own window", feedback instanceof TextView);
      assertEquals("最多选择 20 张照片", ((TextView) feedback).getText().toString());
      for (android.graphics.drawable.Drawable icon : ((TextView) feedback).getCompoundDrawables())
        assertNull("Selection-limit feedback contains no app icon", icon);
      TextView confirm =
          (TextView) findPrefix(sheet[0].getWindow().getDecorView(), "确认导入");
      assertNotNull(confirm);
      assertTrue(confirm.isEnabled());

      // At the limit the newest tap is rejected before the existing 20 are preserved.
      getInstrumentation()
          .runOnMainSync(
              () -> grid.performItemClick(null, 0, grid.getAdapter().getItemId(0)));
      assertNotNull(find(sheet[0].getWindow().getDecorView(), "已选 19 张"));
      getInstrumentation()
          .runOnMainSync(
              () -> grid.performItemClick(null, 20, grid.getAdapter().getItemId(20)));
      assertNotNull(find(sheet[0].getWindow().getDecorView(), "已选 20 张"));
    } finally {
      getInstrumentation().runOnMainSync(() -> sheet[0].dismiss());
    }
  }

  public void testAlbumPickerExpandsInsideTheSameGallerySheet() throws Exception {
    MainActivity activity = getActivity();
    List<GalleryPhotos.Photo> photos = new ArrayList<>();
    photos.add(
        GalleryPhotos.Photo.imported(
            "album-frame", "album.jpg", new File(activity.getCacheDir(), "demo.jpg")));
    GallerySheet[] sheet = new GallerySheet[1];
    getInstrumentation()
        .runOnMainSync(
            () -> {
              sheet[0] =
                  new GallerySheet(
                      activity,
                      0,
                      new GallerySheet.Listener() {
                        public void importPhotos(List<GalleryPhotos.Photo> chosen) {}

                        public void permission() {}
                      });
              sheet[0].show();
              sheet[0].setPhotosForTest(photos);
            });
    try {
      getInstrumentation().waitForIdleSync();
      View selector =
          findByDescription(
              sheet[0].getWindow().getDecorView(), "从相册选择");
      assertNotNull(selector);
      getInstrumentation().runOnMainSync(selector::performClick);
      getInstrumentation().waitForIdleSync();
      ListView albums = list(sheet[0].getWindow().getDecorView());
      assertNotNull(albums);
      assertTrue(albums.isShown());
      assertEquals(1, albums.getAdapter().getCount());
      GridView gridView = grid(sheet[0].getWindow().getDecorView());
      assertNotNull(gridView);
      View photosPanel = (View) gridView.getParent();
      View empty = gridView.getEmptyView();
      assertSame(photosPanel, empty.getParent());
      assertEquals("相册列表直接切换网格区域", View.GONE, photosPanel.getVisibility());
      View panel = (View) albums.getParent();
      assertEquals(AppStyle.SURFACE, ((ColorDrawable) panel.getBackground()).getColor());
      assertEquals(1f, panel.getAlpha(), 0f);
      assertEquals(0f, panel.getElevation(), 0f);
      FrameLayout.LayoutParams panelParams = (FrameLayout.LayoutParams) panel.getLayoutParams();
      assertEquals(0, panelParams.leftMargin);
      assertEquals(0, panelParams.rightMargin);
      assertEquals(0, panelParams.topMargin);
      getInstrumentation().runOnMainSync(
          () -> {
            // Drive the same filter and adapter notification used by a metadata refresh.
            sheet[0].setPhotosForTest(Collections.emptyList());
            assertFalse(empty.isShown());
            sheet[0].setPhotosForTest(photos);
            assertFalse(gridView.isShown());
            assertTrue(albums.isShown());
          });
      getInstrumentation().waitForIdleSync();
      getInstrumentation()
          .runOnMainSync(
              () ->
                  albums.performItemClick(
                      albums.getChildAt(0), 0, albums.getAdapter().getItemId(0)));
      getInstrumentation().waitForIdleSync();
      assertFalse(albums.isShown());
      assertTrue(gridView.isShown());
      assertNotNull(find(sheet[0].getWindow().getDecorView(), "已导入"));
    } finally {
      getInstrumentation().runOnMainSync(() -> sheet[0].dismiss());
    }
  }
}
