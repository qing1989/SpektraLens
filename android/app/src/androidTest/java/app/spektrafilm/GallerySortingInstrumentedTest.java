// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.*;
import android.content.pm.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.test.InstrumentationTestCase;
import android.test.mock.MockContentResolver;
import java.util.*;

/** A vendor provider deliberately ignores SQL ordering and supplies oldest-first records. */
public final class GallerySortingInstrumentedTest extends InstrumentationTestCase {
  public void testCaptureDatesAreNewestFirstEvenWhenProviderIgnoresSort() {
    Context target = getInstrumentation().getTargetContext();
    MockContentResolver resolver = new MockContentResolver(target);
    ContentProvider provider =
        new ContentProvider() {
          public boolean onCreate() {
            return true;
          }

          public String getType(Uri uri) {
            return "image/jpeg";
          }

          public Uri insert(Uri uri, ContentValues values) {
            return null;
          }

          public int delete(Uri uri, String selection, String[] args) {
            return 0;
          }

          public int update(Uri uri, ContentValues values, String selection, String[] args) {
            return 0;
          }

          public Cursor query(
              Uri uri, String[] projection, String selection, String[] args, String order) {
            MatrixCursor cursor = new MatrixCursor(projection);
            // Added time is reversed relative to capture time; selecting by insertion is incorrect.
            Object[][] rows = {
              {1L, "old.jpg", "camera", "Camera", 1000L, 1L, 99L},
              {2L, "new.jpg", "camera", "Camera", 9000L, 9L, 1L},
              {3L, "no-exif.png", "screenshots", "Screenshots", 0L, 5L, 100L},
              {4L, "same-time-newer-id.jpg", "camera", "Camera", 9000L, 9L, 1L}
            };
            String[] columns = {
              "_id",
              "_display_name",
              "bucket_id",
              "bucket_display_name",
              "datetaken",
              "date_modified",
              "date_added"
            };
            for (Object[] row : rows) {
              Object[] projected = new Object[projection.length];
              for (int p = 0; p < projection.length; p++)
                for (int c = 0; c < columns.length; c++)
                  if (projection[p].equals(columns[c])) projected[p] = row[c];
              cursor.addRow(projected);
            }
            return cursor;
          }
        };
    ProviderInfo info = new ProviderInfo();
    info.authority = "media";
    provider.attachInfo(target, info);
    resolver.addProvider("media", provider);
    Context context =
        new ContextWrapper(target) {
          public int checkSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
          }

          public ContentResolver getContentResolver() {
            return resolver;
          }
        };
    List<GalleryPhotos.Photo> photos = GalleryPhotos.query(context, new CancellationSignal());
    assertEquals(4, photos.size());
    assertEquals("same-time-newer-id.jpg", photos.get(0).name);
    assertEquals("new.jpg", photos.get(1).name);
    assertEquals("no-exif.png", photos.get(2).name);
    assertEquals("old.jpg", photos.get(3).name);
  }
}
