// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.Manifest;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import java.io.File;
import java.util.*;

/**
 * Image-only metadata. Access is checked afresh; Android partial grants are never treated as full.
 *
 * <p>The picker never reads the XMP blob column while listing: on libraries with tens of thousands
 * of photos that single column turns a fast index scan into minutes of CursorWindow refills.
 * Motion / Live detection moved to {@link #moving}, which reads one row when the user picks it, and
 * to the byte scan performed while importing.
 */
final class GalleryPhotos {
  /** Rows per metadata query; keeps the first screen cheap on very large libraries. */
  static final int PAGE_SIZE = 400;

  enum Access {
    NONE,
    PARTIAL,
    FULL
  }

  private static final String[] PROJECTION = {
    MediaStore.Images.Media._ID,
    MediaStore.Images.Media.DISPLAY_NAME,
    MediaStore.Images.Media.MIME_TYPE,
    MediaStore.Images.Media.BUCKET_ID,
    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
    MediaStore.Images.Media.DATE_TAKEN,
    MediaStore.Images.Media.DATE_MODIFIED,
    MediaStore.Images.Media.DATE_ADDED
  };

  static final Comparator<Photo> NEWEST_FIRST =
      Comparator.comparingLong((Photo p) -> p.timestamp)
          .reversed()
          .thenComparing(Comparator.comparingLong((Photo p) -> p.mediaId).reversed());

  static Access access(Context context) {
    String permission =
        Build.VERSION.SDK_INT >= 33
            ? Manifest.permission.READ_MEDIA_IMAGES
            : Manifest.permission.READ_EXTERNAL_STORAGE;
    if (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
      return Access.FULL;
    if (Build.VERSION.SDK_INT >= 34
        && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            == PackageManager.PERMISSION_GRANTED) return Access.PARTIAL;
    return Access.NONE;
  }

  static String[] permissions() {
    if (Build.VERSION.SDK_INT >= 34)
      return new String[] {
        Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
      };
    return new String[] {
      Build.VERSION.SDK_INT >= 33
          ? Manifest.permission.READ_MEDIA_IMAGES
          : Manifest.permission.READ_EXTERNAL_STORAGE
    };
  }

  static final class Photo {
    final Uri uri;
    final String frameId, name, albumId, albumName;
    final File thumbnail;
    final long timestamp, mediaId;

    Photo(Uri uri, String frameId, String name, String albumId, String albumName, File thumbnail) {
      this(uri, frameId, name, albumId, albumName, thumbnail, 0, 0);
    }

    Photo(
        Uri uri,
        String frameId,
        String name,
        String albumId,
        String albumName,
        File thumbnail,
        long timestamp,
        long mediaId) {
      this.uri = uri;
      this.frameId = frameId;
      this.name = name;
      this.albumId = albumId;
      this.albumName = albumName;
      this.thumbnail = thumbnail;
      this.timestamp = timestamp;
      this.mediaId = mediaId;
    }

    static Photo imported(String id, String name, File thumbnail) {
      return new Photo(null, id, name, "imported", "已导入", thumbnail);
    }

    String key() {
      return uri != null ? uri.toString() : "frame:" + frameId;
    }
  }

  /** One metadata page: parsed photos, raw provider rows and whether the library was exhausted. */
  static final class Page {
    final List<Photo> photos;
    final int rows;
    final boolean complete;

    Page(List<Photo> photos, int rows, boolean complete) {
      this.photos = photos;
      this.rows = rows;
      this.complete = complete;
    }
  }

  /** Reads at most {@code limit} rows starting at {@code offset}; {@code limit} 0 reads them all. */
  static Page page(Context context, CancellationSignal cancellation, int offset, int limit) {
    List<Photo> photos = new ArrayList<>();
    if (access(context) == Access.NONE) return new Page(photos, 0, true);
    Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
    Bundle args = new Bundle();
    // The Images collection already excludes video. Query every still and apply the shared
    // extension/MIME allow-list below so vendor providers with a generic MIME still expose valid DNG.
    args.putString(
        ContentResolver.QUERY_ARG_SQL_SELECTION, MediaStore.Images.Media.IS_PENDING + " = 0");
    args.putString(
        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
        MediaStore.Images.Media.DATE_ADDED + " DESC, " + MediaStore.Images.Media._ID + " DESC");
    boolean windowed = limit > 0 && Build.VERSION.SDK_INT >= 30;
    if (windowed) {
      args.putInt(ContentResolver.QUERY_ARG_LIMIT, limit);
      args.putInt(ContentResolver.QUERY_ARG_OFFSET, Math.max(0, offset));
    }
    Cursor cursor = null;
    try {
      cursor = context.getContentResolver().query(collection, PROJECTION, args, cancellation);
    } catch (RuntimeException error) {
      // A provider that rejects the paging arguments still gets one unpaged attempt.
      if (!windowed) throw error;
      args.remove(ContentResolver.QUERY_ARG_LIMIT);
      args.remove(ContentResolver.QUERY_ARG_OFFSET);
      windowed = false;
      cursor = context.getContentResolver().query(collection, PROJECTION, args, cancellation);
    }
    int rows = 0;
    try (Cursor read = cursor) {
      if (read == null) return new Page(photos, 0, true);
      while (read.moveToNext()) {
        cancellation.throwIfCanceled();
        rows++;
        String name = read.getString(1);
        String mime = read.getString(2);
        if (!PhotoFormatPolicy.supported(name, mime)) continue;
        Uri uri = ContentUris.withAppendedId(collection, read.getLong(0));
        String album = read.getString(3), label = read.getString(4);
        photos.add(
            new Photo(
                uri,
                null,
                name == null ? "照片" : name,
                album == null ? "" : album,
                label == null ? "其他照片" : label,
                null,
                photoTime(read.getLong(5), read.getLong(6), read.getLong(7)),
                read.getLong(0)));
      }
    }
    // Vendor providers can ignore the requested sort. Enforce order after normalizing ms/seconds.
    photos.sort(NEWEST_FIRST);
    return new Page(photos, rows, !windowed || rows < limit);
  }

  /** Whole-library read; used by tests and by the paging fallback. */
  static List<Photo> query(Context context, CancellationSignal cancellation) {
    return page(context, cancellation, 0, 0).photos;
  }

  /**
   * One-row XMP probe for the photo the user is about to include. Returns false when the provider
   * has no XMP column; the byte scan in {@link PhotoFormats#isMovingPhoto} still rejects moving
   * photos before they are saved.
   */
  static boolean moving(Context context, Photo photo, CancellationSignal cancellation) {
    if (photo == null || photo.uri == null || photo.mediaId <= 0) return false;
    Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
    String[] projection = {MediaStore.MediaColumns.XMP};
    String selection = MediaStore.Images.Media._ID + " = ?";
    String[] arguments = {Long.toString(photo.mediaId)};
    try (Cursor cursor =
        context
            .getContentResolver()
            .query(collection, projection, selection, arguments, null, cancellation)) {
      if (cursor == null || !cursor.moveToFirst()) return false;
      return PhotoFormatPolicy.movingPhotoMetadata(readXmp(cursor, 0));
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private static String readXmp(Cursor cursor, int column) {
    if (column < 0 || cursor.isNull(column)) return null;
    try {
      if (cursor.getType(column) == Cursor.FIELD_TYPE_BLOB) {
        byte[] bytes = cursor.getBlob(column);
        return bytes == null
            ? null
            : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
      }
      return cursor.getString(column);
    } catch (RuntimeException ignored) {
      return null;
    }
  }

  static long photoTime(long takenMs, long modifiedSeconds, long addedSeconds) {
    return takenMs > 0
        ? takenMs
        : Math.max(0, modifiedSeconds > 0 ? modifiedSeconds : addedSeconds) * 1000;
  }
}
