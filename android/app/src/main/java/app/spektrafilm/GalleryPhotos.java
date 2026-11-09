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
    String[] projection = {
      MediaStore.Images.Media._ID,
      MediaStore.Images.Media.DISPLAY_NAME,
      MediaStore.Images.Media.BUCKET_ID,
      MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
      MediaStore.Images.Media.DATE_TAKEN,
      MediaStore.Images.Media.DATE_MODIFIED,
      MediaStore.Images.Media.DATE_ADDED
    };
    // Exclude pending/deleted media and unsupported animations; RAW remains available via Files.
    String selection =
        MediaStore.Images.Media.IS_PENDING
            + " = 0 AND "
            + MediaStore.Images.Media.MIME_TYPE
            + " IN (?,?,?,?,?)";
    String[] types = {"image/jpeg", "image/png", "image/heic", "image/heif", "image/x-adobe-dng"};
    Bundle args = new Bundle();
    args.putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection);
    args.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, types);
    args.putString(
        ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
        MediaStore.Images.Media.DATE_ADDED + " DESC, " + MediaStore.Images.Media._ID + " DESC");
    try (Cursor cursor =
        context.getContentResolver().query(collection, projection, args, cancellation)) {
      if (cursor != null)
        while (cursor.moveToNext()) {
          cancellation.throwIfCanceled();
          Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(0));
          String name = cursor.getString(1),
              album = cursor.getString(2),
              label = cursor.getString(3);
          photos.add(
              new Photo(
                  uri,
                  null,
                  name == null ? "照片" : name,
                  album == null ? "" : album,
                  label == null ? "其他照片" : label,
                  null,
                  photoTime(cursor.getLong(4), cursor.getLong(5), cursor.getLong(6)),
                  cursor.getLong(0)));
        }
    }
    // Vendor providers can ignore the requested sort. Enforce order after normalizing ms/seconds.
    photos.sort(
        Comparator.comparingLong((Photo p) -> p.timestamp)
            .reversed()
            .thenComparing(Comparator.comparingLong((Photo p) -> p.mediaId).reversed()));
    return photos;
  }

  static long photoTime(long takenMs, long modifiedSeconds, long addedSeconds) {
    return takenMs > 0
        ? takenMs
        : Math.max(0, modifiedSeconds > 0 ? modifiedSeconds : addedSeconds) * 1000;
  }
}
