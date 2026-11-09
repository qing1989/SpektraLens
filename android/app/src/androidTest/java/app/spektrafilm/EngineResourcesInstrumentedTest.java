// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.nio.file.Files;

/** Upgrade and corruption checks use the same APK assets and preparation path as the editor. */
public final class EngineResourcesInstrumentedTest extends InstrumentationTestCase {
  private Context context;
  private File directory;
  private static final String SHADER = "vulkan/spk_cctf_decode.spv";

  protected void setUp() throws Exception {
    super.setUp();
    context = getInstrumentation().getTargetContext();
    directory = new File(context.getCacheDir(), "resource-test-" + getName());
    remove(directory);
    assertTrue(directory.mkdirs());
  }

  protected void tearDown() throws Exception {
    remove(directory);
    super.tearDown();
  }

  private void remove(File file) throws IOException {
    if (!file.exists()) return;
    if (file.isDirectory()) for (File child : file.listFiles()) remove(child);
    if (!file.delete()) throw new IOException("Cannot remove test cache");
  }

  private void assertApkFile(String relative) throws Exception {
    try (InputStream in = context.getAssets().open("engine/" + relative);
        ByteArrayOutputStream expected = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = in.read(buffer)) != -1) expected.write(buffer, 0, count);
      assertTrue(
          "Runtime file matches the installed APK: " + relative,
          java.util.Arrays.equals(
              expected.toByteArray(), Files.readAllBytes(new File(directory, relative).toPath())));
    }
  }

  public void testOldReadyMarkerCannotKeepEarlierInstallationResources() throws Exception {
    assertTrue(new File(directory, "vulkan").mkdirs());
    Files.write(new File(directory, ".ready").toPath(), new byte[0]);
    Files.write(new File(directory, SHADER).toPath(), new byte[] {1, 2, 3});
    File retired = new File(directory, "retired-profile.json");
    Files.write(retired.toPath(), new byte[] {4});
    long start = SystemClock.elapsedRealtime();
    EngineResources.prepare(context, directory);
    android.util.Log.i(
        "SpektraLensResourceTest", "Migration ms: " + (SystemClock.elapsedRealtime() - start));
    assertApkFile(SHADER);
    assertApkFile("profiles/kodak_portra_400.json");
    assertFalse("Obsolete files are removed", retired.exists());
    assertEquals(32, new File(directory, ".ready").length());
  }

  public void testUnchangedBundleIsReusedAndDamagedCacheIsRepaired() throws Exception {
    EngineResources.prepare(context, directory);
    File shader = new File(directory, SHADER);
    long sentinel = 1000000000000L;
    assertTrue(shader.setLastModified(sentinel));
    long start = SystemClock.elapsedRealtime();
    EngineResources.prepare(context, directory);
    android.util.Log.i(
        "SpektraLensResourceTest", "Verified reuse ms: " + (SystemClock.elapsedRealtime() - start));
    assertEquals("Valid files are reused, not recopied", sentinel, shader.lastModified());
    try (RandomAccessFile damaged = new RandomAccessFile(shader, "rw")) {
      int old = damaged.read();
      damaged.seek(0);
      damaged.write(old ^ 0xff);
    }
    EngineResources.prepare(context, directory);
    assertApkFile(SHADER);
    assertApkFile("profiles/kodak_portra_400.json");
  }
}
