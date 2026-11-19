// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.*;
import android.content.pm.*;
import android.database.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.test.InstrumentationTestCase;
import android.test.mock.MockContentResolver;
import java.util.*;

/**
 * Regression for very large system albums (Xiaomi 15, ~10,000 photos): listing must page through
 * metadata and must never pull the XMP blob column, which vendors populate with multi-kilobyte
 * payloads and which stalled the picker on a spinner.
 */
public final class GalleryLargeLibraryInstrumentedTest extends InstrumentationTestCase {
  private static final int TOTAL = 12000;
  private FakeMediaProvider provider;
  private Context context;

  private final class FakeMediaProvider extends ContentProvider {
    int xmpQueries, queries, lastRows = -1;

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

    private void count(String[] projection) {
      queries++;
      for (String column : projection)
        if (MediaStore.MediaColumns.XMP.equals(column)) xmpQueries++;
    }

    private Cursor cursor(String[] projection, int start, int end) {
      MatrixCursor cursor = new MatrixCursor(projection);
      for (int index = Math.max(0, start); index < Math.min(TOTAL, end); index++) {
        Object[] row = new Object[projection.length];
        for (int column = 0; column < projection.length; column++) {
          String name = projection[column];
          if (MediaStore.Images.Media._ID.equals(name)) row[column] = (long) index + 1;
          else if (MediaStore.Images.Media.DISPLAY_NAME.equals(name))
            row[column] = "IMG_" + (index + 1) + ".jpg";
          else if (MediaStore.Images.Media.MIME_TYPE.equals(name)) row[column] = "image/jpeg";
          else if (MediaStore.Images.Media.BUCKET_ID.equals(name)) row[column] = "camera";
          else if (MediaStore.Images.Media.BUCKET_DISPLAY_NAME.equals(name)) row[column] = "相机";
          else if (MediaStore.Images.Media.DATE_TAKEN.equals(name))
            row[column] = 1_700_000_000L - index;
          else if (MediaStore.Images.Media.DATE_MODIFIED.equals(name)) row[column] = 1_700_000_000L;
          else if (MediaStore.Images.Media.DATE_ADDED.equals(name)) row[column] = 1_700_000_000L;
          else row[column] = null;
        }
        cursor.addRow(row);
      }
      lastRows = cursor.getCount();
      return cursor;
    }

    public Cursor query(
        Uri uri, String[] projection, Bundle args, CancellationSignal cancellation) {
      count(projection);
      int offset = args == null ? 0 : args.getInt(ContentResolver.QUERY_ARG_OFFSET, 0);
      int limit = args == null ? -1 : args.getInt(ContentResolver.QUERY_ARG_LIMIT, -1);
      return cursor(projection, offset, limit <= 0 ? TOTAL : offset + limit);
    }

    public Cursor query(
        Uri uri, String[] projection, String selection, String[] args, String sort) {
      count(projection);
      long id = 0;
      if (args != null && args.length == 1)
        try {
          id = Long.parseLong(args[0]);
        } catch (NumberFormatException ignored) {
        }
      if (id > 0) return cursor(projection, (int) (id - 1), (int) id);
      return cursor(projection, 0, TOTAL);
    }
  }

  protected void setUp() throws Exception {
    super.setUp();
    Context target = getInstrumentation().getTargetContext();
    provider = new FakeMediaProvider();
    ProviderInfo info = new ProviderInfo();
    info.authority = "media";
    provider.attachInfo(target, info);
    MockContentResolver resolver = new MockContentResolver(target);
    resolver.addProvider("media", provider);
    context =
        new ContextWrapper(target) {
          public int checkSelfPermission(String permission) {
            return PackageManager.PERMISSION_GRANTED;
          }

          public ContentResolver getContentResolver() {
            return resolver;
          }
        };
  }

  public void testListingNeverReadsXmpAndPagesForward() {
    CancellationSignal signal = new CancellationSignal();
    GalleryPhotos.Page first = GalleryPhotos.page(context, signal, 0, GalleryPhotos.PAGE_SIZE);
    assertEquals(GalleryPhotos.PAGE_SIZE, first.rows);
    assertEquals(GalleryPhotos.PAGE_SIZE, first.photos.size());
    assertFalse(first.complete);
    assertEquals(0, provider.xmpQueries);
    assertEquals("IMG_1.jpg", first.photos.get(0).name);

    GalleryPhotos.Page second =
        GalleryPhotos.page(context, signal, GalleryPhotos.PAGE_SIZE, GalleryPhotos.PAGE_SIZE);
    assertEquals(GalleryPhotos.PAGE_SIZE, second.rows);
    assertEquals(0, provider.xmpQueries);
    assertTrue(
        "second page must not repeat the first page",
        second.photos.get(0).mediaId != first.photos.get(0).mediaId);
  }

  public void testPagingPolicyWalksTheWholeLibrary() {
    CancellationSignal signal = new CancellationSignal();
    GalleryPaging paging = new GalleryPaging(GalleryPhotos.PAGE_SIZE);
    Set<String> seen = new HashSet<>();
    int queries = 0;
    while (true) {
      GalleryPhotos.Page page =
          GalleryPhotos.page(
              context, signal, paging.offset(), paging.pageSize());
      queries++;
      int added = 0;
      for (GalleryPhotos.Photo photo : page.photos) if (seen.add(photo.key())) added++;
      int next = paging.accept(page.rows, page.photos.size(), added);
      if (next == GalleryPaging.DONE) break;
      assertEquals(GalleryPaging.MORE, next);
      assertTrue("paging must terminate", queries < 64);
    }
    assertEquals(TOTAL, seen.size());
    assertEquals(0, provider.xmpQueries);
  }

}
