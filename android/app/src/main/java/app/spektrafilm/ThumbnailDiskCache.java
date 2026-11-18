// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** Private, bounded lossless thumbnail files. Writers publish atomically and never touch originals. */
final class ThumbnailDiskCache {
  interface Writer {
    boolean write(OutputStream output) throws IOException;
  }

  private final File directory;
  private final long budget;
  private long stamp;

  ThumbnailDiskCache(File directory, long budget) {
    this.directory = directory;
    this.budget = budget;
  }

  static String key(String... parts) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (String part : parts) {
        byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
        digest.update((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
        digest.update(bytes);
      }
      byte[] bytes = digest.digest();
      char[] result = new char[bytes.length * 2];
      String hex = "0123456789abcdef";
      for (int i = 0; i < bytes.length; i++) {
        result[i * 2] = hex.charAt((bytes[i] & 255) >>> 4);
        result[i * 2 + 1] = hex.charAt(bytes[i] & 15);
      }
      return new String(result);
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private File file(String key) {
    if (!key.matches("[a-f0-9]{64}(-[a-f0-9]{64})?")) throw new IllegalArgumentException("Invalid cache key");
    return new File(directory, key + ".png");
  }

  synchronized File get(String key) {
    File file = file(key);
    if (!file.isFile() || file.length() == 0) return null;
    touch(file);
    return file;
  }

  synchronized void remove(String key) {
    file(key).delete();
  }

  synchronized void removePhoto(String prefix) {
    if (!prefix.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid photo prefix");
    File[] files = directory.listFiles((dir, name) -> name.startsWith(prefix + "-"));
    if (files != null) for (File file : files) file.delete();
  }

  synchronized boolean put(String key, Writer writer) throws IOException {
    File target = file(key);
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cache directory unavailable");
    File temp = File.createTempFile("thumb-", ".pending", directory);
    try {
      try (OutputStream output = new FileOutputStream(temp)) {
        if (!writer.write(output)) return false;
      }
      if (temp.length() == 0 || temp.length() > budget || !temp.renameTo(target)) return false;
      touch(target);
      File[] files = directory.listFiles((dir, name) -> name.endsWith(".png"));
      if (files != null) {
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long bytes = 0;
        for (File file : files) {
          bytes += file.length();
          if (bytes > budget) file.delete();
        }
      }
      return true;
    } finally {
      temp.delete();
    }
  }

  synchronized void sweepPending() {
    File[] files = directory.listFiles((dir, name) -> name.endsWith(".pending"));
    if (files != null) for (File file : files) file.delete();
  }

  private void touch(File file) {
    stamp = Math.max(System.currentTimeMillis(), Math.max(stamp, file.lastModified()) + 1);
    file.setLastModified(stamp);
  }
}
