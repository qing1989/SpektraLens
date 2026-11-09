// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.test.InstrumentationTestCase;
import java.io.*;
import org.json.*;

/**
 * Exercises the actual private processes and bundled ARM CPU driver, including forced startup
 * stalls.
 */
public final class RendererRecoveryInstrumentedTest extends InstrumentationTestCase {
  private Context context;
  private File resources, output;
  private SpectralRenderer renderer;

  protected void setUp() throws Exception {
    super.setUp();
    context = getInstrumentation().getTargetContext();
    resources = new File(context.getFilesDir(), "recovery_engine_v023");
    if (!new File(resources, ".ready").exists()) {
      copy("engine", resources);
      new File(resources, ".ready").createNewFile();
    }
    output = new File(context.getFilesDir(), "renderer-recovery");
    output.mkdirs();
  }

  protected void tearDown() throws Exception {
    if (renderer != null) renderer.close();
    super.tearDown();
  }

  private void copy(String path, File dest) throws IOException {
    String[] children = context.getAssets().list(path);
    if (children != null && children.length > 0) {
      dest.mkdirs();
      for (String child : children) copy(path + "/" + child, new File(dest, child));
    } else
      try (InputStream in = context.getAssets().open(path);
          OutputStream out = new FileOutputStream(dest)) {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
      }
  }

  private String params() {
    return "{\"film_stock\":\"kodak_portra_400\",\"print_stock\":\"kodak_portra_endura\",\"input_color_space\":\"sRGB\",\"input_cctf_decoding\":true,\"output_color_space\":\"sRGB\",\"output_cctf_encoding\":true,\"auto_exposure\":false,\"grain_active\":false,\"halation_active\":false,\"glare_active\":false,\"dir_couplers_active\":false}";
  }

  private Bitmap chart() {
    Bitmap b = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
    for (int y = 0; y < 64; y++)
      for (int x = 0; x < 96; x++)
        b.setPixel(x, y, Color.rgb(25 + x * 2, 25 + y * 3, 30 + (x + y)));
    return b;
  }

  private double brightness(Bitmap b) {
    double v = 0;
    for (int y = 0; y < b.getHeight(); y++)
      for (int x = 0; x < b.getWidth(); x++) {
        int c = b.getPixel(x, y);
        v += Color.red(c) + Color.green(c) + Color.blue(c);
      }
    return v / (3 * b.getWidth() * b.getHeight());
  }

  public void testBundledCpuRendersMatchesReferenceAndWritesPng16() throws Exception {
    renderer = new SpectralRenderer(context);
    String capabilities = renderer.start(resources.getAbsolutePath(), "cpu");
    assertEquals("cpu", renderer.backend());
    assertTrue(capabilities, capabilities.contains("SwiftShader"));
    assertTrue(renderer.processId() != android.os.Process.myPid());
    Bitmap input = chart();
    renderer.open(input, params());
    Bitmap cpu = renderer.render(params(), "{}", true);
    assertEquals(96, cpu.getWidth());
    assertEquals(64, cpu.getHeight());
    assertTrue(brightness(cpu) > 1 && brightness(cpu) < 254);
    long reference = NativeEngine.create(resources.getAbsolutePath());
    try {
      NativeEngine.open(reference, input, params());
      Bitmap gpu = NativeEngine.render(reference, params(), true);
      int worst = 0;
      for (int y = 0; y < 64; y++)
        for (int x = 0; x < 96; x++) {
          int a = cpu.getPixel(x, y), b = gpu.getPixel(x, y);
          worst = Math.max(worst, Math.abs(Color.red(a) - Color.red(b)));
          worst = Math.max(worst, Math.abs(Color.green(a) - Color.green(b)));
          worst = Math.max(worst, Math.abs(Color.blue(a) - Color.blue(b)));
        }
      assertTrue(
          "Bundled CPU vs existing Vulkan reference <=2 counts, actual " + worst, worst <= 2);
      gpu.recycle();
    } finally {
      NativeEngine.destroy(reference);
    }
    File png = new File(output, "cpu-output.png");
    if (png.exists()) png.delete();
    JSONObject size =
        new JSONObject(
            renderer.exportImage(
                params(), "{\"geo_w\":0.5,\"geo_turns\":1}", png.getAbsolutePath(), "png"));
    assertEquals(64, size.getInt("width"));
    assertEquals(48, size.getInt("height"));
    try (InputStream in = new FileInputStream(png)) {
      byte[] header = new byte[26];
      assertEquals(26, in.read(header));
      assertEquals(16, header[24] & 255);
    }
    input.recycle();
    cpu.recycle();
  }

  public void testBlockedGpuStartupFallsBackToActualCpuWithoutKillingEditor() throws Exception {
    int editor = android.os.Process.myPid();
    File keep = new File(output, "keep-photo-marker");
    try (OutputStream out = new FileOutputStream(keep)) {
      out.write(new byte[] {17, 23, 41});
    }
    renderer = new SpectralRenderer(context);
    String capabilities = renderer.start(resources.getAbsolutePath(), "gpu", 300, 30000);
    assertEquals("cpu", renderer.backend());
    assertTrue(renderer.fallbackReason(), renderer.fallbackReason().contains("超时"));
    assertTrue(capabilities.contains("SwiftShader"));
    assertEquals(editor, android.os.Process.myPid());
    assertEquals(3, keep.length());
    assertTrue(renderer.processId() != editor);
    Bitmap input = chart();
    renderer.open(input, params());
    Bitmap result = renderer.render(params(), "{}", true);
    assertTrue(brightness(result) > 1 && brightness(result) < 254);
    input.recycle();
    result.recycle();
  }

  public void testKilledCompatibilityProcessCanBeRecreated() throws Exception {
    renderer = new SpectralRenderer(context);
    renderer.start(resources.getAbsolutePath(), "cpu");
    int previous = renderer.processId();
    android.os.Process.killProcess(previous);
    boolean disconnected = false;
    try {
      renderer.render(params(), "{}", true);
    } catch (IllegalStateException expected) {
      disconnected = true;
    }
    assertTrue("Process death must return an error instead of blocking", disconnected);
    renderer.close();
    renderer = new SpectralRenderer(context);
    renderer.start(resources.getAbsolutePath(), "cpu");
    assertTrue(previous != renderer.processId());
    Bitmap input = chart();
    renderer.open(input, params());
    Bitmap result = renderer.render(params(), "{}", true);
    assertEquals(96, result.getWidth());
    input.recycle();
    result.recycle();
  }

  public void testProcessDeathDuringCreateRetriesFreshSession() throws Exception {
    renderer = new SpectralRenderer(context);
    java.util.concurrent.ExecutorService startup =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try {
      java.util.concurrent.Future<String> boot =
          startup.submit(() -> renderer.start(resources.getAbsolutePath(), "gpu", 15000, 700));
      long end = android.os.SystemClock.elapsedRealtime() + 8000;
      while (renderer.processId() <= 0 && android.os.SystemClock.elapsedRealtime() < end)
        Thread.sleep(10);
      int killed = renderer.processId();
      assertTrue("HELLO must identify the service before CREATE completes", killed > 0);
      android.os.Process.killProcess(killed);
      assertTrue(boot.get(30, java.util.concurrent.TimeUnit.SECONDS).contains("native"));
      assertEquals("gpu", renderer.backend());
      assertTrue(killed != renderer.processId());
      Bitmap input = chart();
      renderer.open(input, params());
      Bitmap result = renderer.render(params(), "{}", true);
      assertTrue(brightness(result) > 1);
      result.recycle();
      input.recycle();
    } finally {
      startup.shutdownNow();
    }
  }

  public void testRapidCompatibilityRestartGetsFreshProcess() throws Exception {
    int previous = 0;
    for (int i = 0; i < 3; i++) {
      renderer = new SpectralRenderer(context);
      renderer.start(resources.getAbsolutePath(), "cpu");
      assertTrue(previous != renderer.processId());
      previous = renderer.processId();
      Bitmap input = chart();
      renderer.open(input, params());
      Bitmap result = renderer.render(params(), "{}", true);
      assertTrue(brightness(result) > 1);
      input.recycle();
      result.recycle();
      renderer.close();
    }
  }

  public void testNewConnectionReplacesStaleSessionSafely() throws Exception {
    SpectralRenderer old = new SpectralRenderer(context);
    try {
      old.start(resources.getAbsolutePath(), "cpu");
      int previous = old.processId();
      renderer = new SpectralRenderer(context);
      renderer.start(resources.getAbsolutePath(), "cpu");
      assertTrue(previous != renderer.processId());
      old.close();
      assertTrue(renderer.isAlive());
      Bitmap input = chart();
      renderer.open(input, params());
      Bitmap image = renderer.render(params(), "{}", true);
      assertTrue(brightness(image) > 1);
      input.recycle();
      image.recycle();
    } finally {
      old.close();
    }
  }

  public void testAutomaticModeAttemptsGpuAndCpuRemainsSelectable() {
    assertEquals("gpu", SpectralRenderer.preferredBackend("auto"));
    assertEquals("gpu", SpectralRenderer.preferredBackend("gpu"));
    assertEquals("cpu", SpectralRenderer.preferredBackend("cpu"));
  }

  public void testGpuDeathReopensBitmapOnCpuAndRetainsPixels() throws Exception {
    renderer = new SpectralRenderer(context);
    renderer.start(resources.getAbsolutePath(), "gpu");
    assertEquals("gpu", renderer.backend());
    Bitmap input = chart();
    renderer.open(input, params());
    Bitmap expected = renderer.render(params(), "{}", true);
    android.os.Process.killProcess(renderer.processId());
    Bitmap actual = renderer.render(params(), "{}", true);
    assertEquals("cpu", renderer.backend());
    assertTrue(renderer.fallbackReason().length() > 0);
    assertTrue(expected.sameAs(actual));
    input.recycle();
    expected.recycle();
    actual.recycle();
  }

  public void testGpuRangeFailureRetriesOnCpuWithoutLosingEdits() throws Exception {
    renderer = new SpectralRenderer(context);
    renderer.start(resources.getAbsolutePath(), "gpu", 15000, 0, 65536);
    assertEquals("gpu", renderer.backend());
    Bitmap input = chart();
    renderer.open(input, params());
    String edits = "{\"geo_w\":0.5,\"geo_turns\":1}";
    Bitmap result = renderer.render(params(), edits, true);
    assertEquals("cpu", renderer.backend());
    assertTrue(
        renderer.fallbackReason(), renderer.fallbackReason().contains("maxStorageBufferRange"));
    assertEquals(64, result.getWidth());
    assertEquals(48, result.getHeight());
    assertTrue(brightness(result) > 1 && brightness(result) < 254);
    input.recycle();
    result.recycle();
  }

  public void testGpuDeathReopensOriginalFileForExport() throws Exception {
    File source = new File(output, "original.png");
    Bitmap input = Bitmap.createBitmap(577, 389, Bitmap.Config.ARGB_8888);
    new Canvas(input).drawColor(Color.rgb(85, 120, 170));
    try (OutputStream out = new FileOutputStream(source)) {
      assertTrue(input.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    input.recycle();
    renderer = new SpectralRenderer(context);
    renderer.start(resources.getAbsolutePath(), "gpu");
    assertEquals("gpu", renderer.backend());
    renderer.openFile(source.getAbsolutePath(), params());
    android.os.Process.killProcess(renderer.processId());
    File png = new File(output, "recovered-original.png");
    if (png.exists()) assertTrue(png.delete());
    JSONObject size =
        new JSONObject(renderer.exportImage(params(), "{}", png.getAbsolutePath(), "png"));
    assertEquals("cpu", renderer.backend());
    assertEquals(577, size.getInt("width"));
    assertEquals(389, size.getInt("height"));
    assertTrue(source.isFile());
    Bitmap actual = BitmapFactory.decodeFile(png.getAbsolutePath());
    assertEquals(577, actual.getWidth());
    assertEquals(389, actual.getHeight());
    assertTrue(brightness(actual) > 1 && brightness(actual) < 254);
    actual.recycle();
  }
}
