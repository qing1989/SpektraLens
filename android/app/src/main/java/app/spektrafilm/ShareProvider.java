// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Grants read-only access to one app-owned export, never arbitrary paths. */
public final class ShareProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  private File file(Uri uri) throws FileNotFoundException {
    String name = uri.getLastPathSegment();
    // Keep earlier read grants working after the product rename; exports use SpektraLens.
    if (name == null || !name.matches("(?:SpektraLens|Spektra)_[A-Za-z0-9_]+\\.(jpg|png|tiff)"))
      throw new FileNotFoundException();
    return new File(getContext().getCacheDir(), name);
  }

  public String getType(Uri uri) {
    return uri.toString().endsWith(".tiff")
        ? "image/tiff"
        : uri.toString().endsWith(".png") ? "image/png" : "image/jpeg";
  }

  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
    return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  public Cursor query(
      Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
    try {
      File f = file(uri);
      MatrixCursor c =
          new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
      c.addRow(new Object[] {f.getName(), f.length()});
      return c;
    } catch (FileNotFoundException ex) {
      return null;
    }
  }

  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri uri, ContentValues values, String where, String[] args) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri uri, String where, String[] args) {
    throw new UnsupportedOperationException();
  }
}
