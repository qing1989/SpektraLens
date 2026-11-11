// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.nio.file.*;

/** Dependency-free JVM tests; run with Gradle :app:testUpdatePolicy or scripts/test_updates.py. */
public final class UpdateManifestTest {
  private static int checks;
  private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
  private static final String BASE = "{\"versionCode\":19,\"versionName\":\"0.1\",\"apkUrl\":\"https://example.org/app.apk\",\"sha256\":\"" + HASH + "\"}";
  private static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
  private static void reject(String json) {
    checks++;
    try { UpdateManifest.parse(json); } catch (IllegalArgumentException e) { return; }
    throw new AssertionError("accepted invalid manifest: " + json);
  }
  public static void main(String[] args) throws Exception {
    UpdateManifest m = UpdateManifest.parse(BASE);
    check(m.isNewerThan(18)); check(!m.isNewerThan(19)); check(!m.isNewerThan(20));
    check(m.versionName.equals("0.1")); // Name is deliberately older; only code decides.
    check(m.minSdk == 1 && m.releaseNotes.isEmpty() && m.sizeBytes == UpdateManifest.MAX_APK_BYTES);
    m = UpdateManifest.parse(BASE.replace("}", ",\"minSdk\":35,\"sizeBytes\":123,\"releaseNotes\":\"更新\\n说明\",\"apkUrlMirrors\":[\"http://bad.org/a\",42,\"https://mirror.org/a\"],\"future\":{\"array\":[true,null,1.5]}}"));
    check(m.minSdk == 35 && m.sizeBytes == 123 && m.urls.size() == 2);
    check(m.releaseNotes.equals("更新\n说明"));
    reject("{}"); reject(BASE + "x"); reject(BASE.replace("19", "19.5"));
    reject(BASE.replace("19", "2147483648")); reject(BASE.replace("19", "-1"));
    reject(BASE.replace("19", "\"19\"")); reject(BASE.replace("https:", "http:"));
    reject(BASE.replace(HASH, HASH.toUpperCase())); reject(BASE.replace(HASH, "abc"));
    reject(BASE.replace("}", ",\"sizeBytes\":0}"));
    reject(BASE.replace("}", ",\"sizeBytes\":536870913}"));
    reject(BASE.replace("}", ",\"versionCode\":20}"));
    reject(BASE.replace("}", ",\"minSdk\":null}"));
    reject(BASE.replace("}", ",}")); reject(BASE.replace("0.1", "bad\nstring"));
    reject(" ".repeat(65537));
    Path file = Files.createTempFile("update-hash-", ".apk");
    try {
      Files.writeString(file, "abc"); check(UpdateManifest.verify(file.toFile(), HASH));
      Files.writeString(file, "abcd"); check(!UpdateManifest.verify(file.toFile(), HASH));
      Files.write(file, new byte[0]); check(!UpdateManifest.verify(file.toFile(), HASH));
    } finally { Files.delete(file); }
    System.out.println("UpdateManifestTest: " + checks + " checks passed");
  }
}
