// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;

/** Test APK only: delivers one byte, then stalls like a misbehaving photo provider. */
public final class SlowImportProvider extends ContentProvider {
  public boolean onCreate() {
    return true;
  }

  public String getType(Uri u) {
    return "image/jpeg";
  }

  public Cursor query(Uri u, String[] p, String s, String[] a, String sort) {
    MatrixCursor c =
        new MatrixCursor(new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
    c.addRow(new Object[] {"delayed-photo.jpg", 100000});
    return c;
  }

  public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
    try {
      ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
      Thread writer =
          new Thread(
              () -> {
                try (OutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                  out.write(255);
                  out.flush();
                  Thread.sleep(15000);
                } catch (Exception ignored) {
                }
              },
              "slow-photo-test");
      writer.setDaemon(true);
      writer.start();
      return pipe[0];
    } catch (IOException ex) {
      throw new FileNotFoundException(ex.toString());
    }
  }

  public Uri insert(Uri u, ContentValues v) {
    throw new UnsupportedOperationException();
  }

  public int delete(Uri u, String s, String[] a) {
    throw new UnsupportedOperationException();
  }

  public int update(Uri u, ContentValues v, String s, String[] a) {
    throw new UnsupportedOperationException();
  }
}
