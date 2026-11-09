// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
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

  public void testThreeColumnImportedGridKeepsExactPhotoIdentity() throws Exception {
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
                      photos,
                      "unique-frame-0",
                      new GallerySheet.Listener() {
                        public void picked(GalleryPhotos.Photo photo) {
                          selected[0] = photo.frameId;
                        }

                        public void files() {
                          fail("Unexpected files action");
                        }

                        public void systemPicker() {
                          fail("Unexpected system picker");
                        }

                        public void permission() {
                          fail(
                              "Permission must only follow a user action in this direct sheet"
                                  + " test");
                        }
                      });
              sheet[0].show();
              find(sheet[0].getWindow().getDecorView(), "已导入").performClick();
            });
    try {
      getInstrumentation().waitForIdleSync();
      GridView grid = grid(sheet[0].getWindow().getDecorView());
      assertNotNull(grid);
      assertEquals(3, grid.getNumColumns());
      assertEquals(3, grid.getAdapter().getCount());
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
      assertEquals("unique-frame-1", selected[0]);
      assertFalse(sheet[0].isShowing());
    } finally {
      getInstrumentation().runOnMainSync(() -> sheet[0].dismiss());
    }
  }
}
