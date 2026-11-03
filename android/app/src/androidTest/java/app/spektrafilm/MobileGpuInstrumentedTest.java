// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.test.InstrumentationTestCase;
import java.io.*;

/** Actual kernels, odd layouts, buffer limits and mapped-output ownership. */
public final class MobileGpuInstrumentedTest extends InstrumentationTestCase {
  private String resources;

  protected void setUp() throws Exception {
    super.setUp();
    Context context = getInstrumentation().getTargetContext();
    File directory = new File(context.getFilesDir(), "mobile_gpu_checks_v023");
    if (!new File(directory, ".ready").exists()) {
      copy(context, "engine", directory);
      new File(directory, ".ready").createNewFile();
    }
    resources = directory.getAbsolutePath();
  }

  private void copy(Context c, String asset, File to) throws IOException {
    String[] children = c.getAssets().list(asset);
    if (children != null && children.length > 0) {
      to.mkdirs();
      for (String child : children) copy(c, asset + "/" + child, new File(to, child));
    } else
      try (InputStream in = c.getAssets().open(asset);
          OutputStream out = new FileOutputStream(to)) {
        byte[] data = new byte[65536];
        int n;
        while ((n = in.read(data)) != -1) out.write(data, 0, n);
      }
  }

  public void testOddWindowsHalosLimitsAndPadding() throws Exception {
    assertTrue(NativeEngine.gpuCheck(resources, "windows").contains("true"));
  }

  public void testGrainAndSpectrumStayIdenticalAcrossBands() throws Exception {
    assertTrue(NativeEngine.gpuCheck(resources, "grain").contains("true"));
  }

  public void testMappedResultOutlivesItsEngine() throws Exception {
    assertTrue(NativeEngine.gpuCheck(resources, "lifetime").contains("true"));
  }

  public void testSplitMathAndBoundedTransfers() throws Exception {
    assertTrue(NativeEngine.gpuCheck(resources, "transfers").contains("true"));
  }
}
