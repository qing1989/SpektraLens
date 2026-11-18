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
 */
final class GalleryPhotos {
  enum Access {
    NONE,
    PARTIAL,
    FULL
  }

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

  static List<Photo> query(Context context, CancellationSignal cancellation) {
    List<Photo> photos = new ArrayList<>();
    if (access(context) == Access.NONE) return photos;
    Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
    String[] baseProjection = {
      MediaStore.Images.Media._ID,
      MediaStore.Images.Media.DISPLAY_NAME,
      MediaStore.Images.Media.MIME_TYPE,
      MediaStore.Images.Media.BUCKET_ID,
      MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
      MediaStore.Images.Media.DATE_TAKEN,
      MediaStore.Images.Media.DATE_MODIFIED,
      MediaStore.Images.Media.DATE_ADDED
    };
    // The Images collection already excludes video. Query every still and apply the shared
    // extension/MIME allow-list below so vendor providers with a generic MIME still expose valid DNG.
    String selection = MediaStore.Images.Media.IS_PENDING + " = 0";
    Bundle args = new Bundle();
    args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
    args.putString(
        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
        MediaStore.Images.Media.DATE_ADDED + " DESC, " + MediaStore.Images.Media._ID + " DESC");
    String[] projection = baseProjection;
    boolean hasXmp = Build.VERSION.SDK_INT >= 30;
    if (hasXmp) {
      projection = Arrays.copyOf(baseProjection, baseProjection.length + 1);
      projection[baseProjection.length] = MediaStore.MediaColumns.XMP;
    }
    Cursor cursor = null;
    try {
      cursor = context.getContentResolver().query(collection, projection, args, cancellation);
    } catch (RuntimeException ignored) {
      // A vendor MediaProvider can omit XMP. Keep the base query and reject motion media again
      // while copying the selected files, before anything is saved to the private library.
    }
    if (cursor == null && hasXmp) {
      projection = baseProjection;
      hasXmp = false;
      cursor = context.getContentResolver().query(collection, projection, args, cancellation);
    }
    try (Cursor rows = cursor) {
      Cursor cursorRows = rows;
      if (cursorRows == null) return photos;
      int xmpColumn = hasXmp ? cursorRows.getColumnIndex(MediaStore.MediaColumns.XMP) : -1;
      while (cursorRows.moveToNext()) {
        cancellation.throwIfCanceled();
        String name = cursorRows.getString(1);
        String mime = cursorRows.getString(2);
        String xmp = xmpColumn < 0 ? null : readXmp(cursorRows, xmpColumn);
        if (!PhotoFormatPolicy.supported(name, mime)
            || PhotoFormatPolicy.movingPhotoMetadata(xmp)) continue;
        Uri uri = ContentUris.withAppendedId(collection, cursorRows.getLong(0));
        String album = cursorRows.getString(3), label = cursorRows.getString(4);
        photos.add(
            new Photo(
                uri,
                null,
                name == null ? "照片" : name,
                album == null ? "" : album,
                label == null ? "其他照片" : label,
                null,
                photoTime(
                    cursorRows.getLong(5), cursorRows.getLong(6), cursorRows.getLong(7)),
                cursorRows.getLong(0)));
      }
    }
    // Vendor providers can ignore the requested sort. Enforce order after normalizing ms/seconds.
    photos.sort(
        Comparator.comparingLong((Photo p) -> p.timestamp)
            .reversed()
            .thenComparing(Comparator.comparingLong((Photo p) -> p.mediaId).reversed()));
    return photos;
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
