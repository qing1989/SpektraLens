// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.*;
import java.util.*;

/** The installed APK, not an earlier installation's .ready file, owns the engine resources. */
final class EngineResources {
  static synchronized File prepare(Context context, File directory) throws IOException {
    AssetManager assets = context.getAssets();
    Map<String, byte[]> expected = new TreeMap<>();
    collect(assets, "", expected);
    if (expected.isEmpty()) throw new IOException("安装包缺少显影资源");
    MessageDigest bundle = sha256();
    for (Map.Entry<String, byte[]> file : expected.entrySet()) {
      bundle.update(file.getKey().getBytes(StandardCharsets.UTF_8));
      bundle.update((byte) 0);
      bundle.update(file.getValue());
    }
    byte[] fingerprint = bundle.digest();
    File marker = new File(directory, ".ready");
    if (matches(directory, fingerprint, expected)) return directory;

    // Invalidate first, publish the marker last. An interrupted copy is retried on next startup.
    remove(directory);
    if (!directory.mkdirs()) throw new IOException("无法准备显影资源目录");
    for (Map.Entry<String, byte[]> file : expected.entrySet()) {
      File destination = new File(directory, file.getKey());
      File parent = destination.getParentFile();
      if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法准备显影资源目录");
      MessageDigest copied = sha256();
      try (InputStream in = assets.open("engine/" + file.getKey());
          OutputStream out = new FileOutputStream(destination)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = in.read(buffer)) != -1) {
          copied.update(buffer, 0, count);
          out.write(buffer, 0, count);
        }
      }
      if (!MessageDigest.isEqual(file.getValue(), copied.digest()))
        throw new IOException("显影资源校验失败：" + file.getKey());
    }
    Files.write(marker.toPath(), fingerprint);
    return directory;
  }

  private static void collect(AssetManager assets, String path, Map<String, byte[]> files)
      throws IOException {
    String asset = path.isEmpty() ? "engine" : "engine/" + path;
    String[] children = assets.list(asset);
    if (children != null && children.length > 0) {
      for (String child : children)
        collect(assets, path.isEmpty() ? child : path + "/" + child, files);
    } else if (!path.isEmpty()) files.put(path, hash(assets.open(asset)));
  }

  private static boolean matches(File directory, byte[] fingerprint, Map<String, byte[]> files) {
    try {
      File marker = new File(directory, ".ready");
      if (!marker.isFile()
          || !MessageDigest.isEqual(fingerprint, Files.readAllBytes(marker.toPath()))) return false;
      for (Map.Entry<String, byte[]> file : files.entrySet())
        if (!MessageDigest.isEqual(
            file.getValue(), hash(new FileInputStream(new File(directory, file.getKey())))))
          return false;
      return countFiles(directory) == files.size() + 1;
    } catch (IOException ex) {
      return false;
    }
  }

  private static int countFiles(File directory) throws IOException {
    File[] files = directory.listFiles();
    if (files == null) throw new IOException("无法读取显影资源目录");
    int count = 0;
    for (File file : files) count += file.isDirectory() ? countFiles(file) : 1;
    return count;
  }

  private static byte[] hash(InputStream stream) throws IOException {
    try (InputStream in = stream) {
      MessageDigest digest = sha256();
      byte[] buffer = new byte[65536];
      int count;
      while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
      return digest.digest();
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  private static void remove(File file) throws IOException {
    if (!file.exists()) return;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children == null) throw new IOException("无法读取旧显影资源目录");
      for (File child : children) remove(child);
    }
    if (!file.delete()) throw new IOException("无法替换旧显影资源：" + file.getName());
  }
}
