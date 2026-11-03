// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.test.InstrumentationTestCase;
import java.io.*;
import org.json.JSONObject;

/** Executes the real JNI library and packaged compute shaders on the device. */
public final class EngineInstrumentedTest extends InstrumentationTestCase {
  private long engine;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    Context c = getInstrumentation().getTargetContext();
    File resources = new File(c.getFilesDir(), "test_engine_v023");
    if (!new File(resources, ".ready").exists()) {
      copy(c, "engine", resources);
      new File(resources, ".ready").createNewFile();
    }
    engine = NativeEngine.create(resources.getAbsolutePath());
  }

  @Override
  protected void tearDown() throws Exception {
    NativeEngine.destroy(engine);
    super.tearDown();
  }

  private void copy(Context c, String path, File dest) throws IOException {
    String[] children = c.getAssets().list(path);
    if (children.length > 0) {
      dest.mkdirs();
      for (String child : children) copy(c, path + "/" + child, new File(dest, child));
    } else
      try (InputStream in = c.getAssets().open(path);
          OutputStream out = new FileOutputStream(dest)) {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
      }
  }

  private String params(String extra) throws Exception {
    JSONObject j =
        new JSONObject(
            "{\"film_stock\":\"kodak_portra_400\",\"print_stock\":\"kodak_portra_endura\",\"input_color_space\":\"sRGB\",\"input_cctf_decoding\":true,\"output_color_space\":\"sRGB\",\"output_cctf_encoding\":true,\"auto_exposure\":false,\"grain_active\":false,\"halation_active\":false,\"glare_active\":false}");
    JSONObject patch = new JSONObject(extra);
    java.util.Iterator<String> keys = patch.keys();
    while (keys.hasNext()) {
      String k = keys.next();
      j.put(k, patch.get(k));
    }
    return j.toString();
  }

  private Bitmap chart() {
    Bitmap b = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
    for (int y = 0; y < 64; y++)
      for (int x = 0; x < 96; x++) {
        int p =
            x < 32
                ? Color.rgb(200, 30, 20)
                : x < 64 ? Color.rgb(110 + y, 110 + y, 110 + y) : Color.rgb(20, 40, 200);
        b.setPixel(x, y, p);
      }
    return b;
  }

  private double brightness(Bitmap b) {
    double sum = 0;
    for (int y = 0; y < b.getHeight(); y++)
      for (int x = 0; x < b.getWidth(); x++) {
        int c = b.getPixel(x, y);
        sum += .2126 * Color.red(c) + .7152 * Color.green(c) + .0722 * Color.blue(c);
      }
    return sum / (b.getWidth() * b.getHeight());
  }

  private int differences(Bitmap a, Bitmap b) {
    int n = 0;
    for (int y = 0; y < a.getHeight(); y++)
      for (int x = 0; x < a.getWidth(); x++) if (a.getPixel(x, y) != b.getPixel(x, y)) n++;
    return n;
  }

  public void testRealSpectralPipelineAndBrightness() throws Exception {
    String capabilities = NativeEngine.capabilities(engine);
    assertTrue(capabilities, capabilities.contains("native-vulkan"));
    Bitmap input = chart();
    int original = input.getPixel(20, 20);
    NativeEngine.open(engine, input, params("{}"));
    Bitmap a = NativeEngine.render(engine, params("{}"), true);
    assertEquals(96, a.getWidth());
    assertEquals(64, a.getHeight());
    assertEquals(original, input.getPixel(20, 20));
    assertEquals(255, Color.alpha(a.getPixel(30, 30)));
    int red = a.getPixel(10, 30), blue = a.getPixel(80, 30);
    assertTrue("RGB channel order or horizontal orientation", Color.red(red) > Color.blue(red));
    assertTrue("RGB channel order or horizontal orientation", Color.blue(blue) > Color.red(blue));
    assertTrue("Vertical gradient orientation", brightnessRow(a, 52) > brightnessRow(a, 8));
    Bitmap b = NativeEngine.render(engine, params("{\"print_exposure\":0.5}"), true);
    assertTrue(
        "Print brightness must rise when enlarger exposure decreases",
        brightness(b) > brightness(a) + 3);
    Bitmap again = NativeEngine.render(engine, params("{}"), true);
    // Params are deltas: explicitly restore print exposure for this check.
    Bitmap restored = NativeEngine.render(engine, params("{\"print_exposure\":1.0}"), true);
    assertEquals(
        "No random effects: cached/reprinted pixels remain stable", 0, differences(a, restored));
    assertTrue("The engine must transform the source", differences(input, a) > 500);
    input.recycle();
    a.recycle();
    b.recycle();
    again.recycle();
    restored.recycle();
  }

  private double brightnessRow(Bitmap b, int row) {
    double s = 0;
    for (int x = 36; x < 60; x++) {
      int c = b.getPixel(x, row);
      s += Color.red(c) + Color.green(c) + Color.blue(c);
    }
    return s / 72;
  }

  public void testStockSwitchAndGrainAreReal() throws Exception {
    Bitmap input = chart();
    NativeEngine.open(engine, input, params("{}"));
    Bitmap a = NativeEngine.render(engine, params("{}"), true);
    Bitmap stock = NativeEngine.render(engine, params("{\"film_stock\":\"kodak_gold_200\"}"), true);
    assertTrue("Selecting a film must change the spectral result", differences(a, stock) > 1000);
    Bitmap grain =
        NativeEngine.render(engine, params("{\"grain_active\":true,\"grain_amount\":2}"), true);
    assertTrue("Grain must change output", differences(a, grain) > 500);
    input.recycle();
    a.recycle();
    stock.recycle();
    grain.recycle();
  }

  public void testInvalidParameterDoesNotBreakSession() throws Exception {
    Bitmap input = chart();
    NativeEngine.open(engine, input, params("{}"));
    boolean rejected = false;
    try {
      NativeEngine.render(engine, "{\"not_a_real_parameter\":true}", true);
    } catch (IllegalStateException expected) {
      rejected = true;
    }
    assertTrue("Unknown controls must never be silently ignored", rejected);
    Bitmap valid = NativeEngine.render(engine, params("{}"), true);
    assertEquals(96, valid.getWidth());
    input.recycle();
    valid.recycle();
  }

  public void testEveryBundledFilmAndPaperCanRender() throws Exception {
    Context c = getInstrumentation().getTargetContext();
    String text;
    try (InputStream in = c.getAssets().open("catalog.json");
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[4096];
      int n;
      while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
      text = out.toString("UTF-8");
    }
    JSONObject catalog = new JSONObject(text);
    assertEquals(20, catalog.getJSONArray("films").length());
    assertEquals(8, catalog.getJSONArray("papers").length());
    Bitmap input = chart();
    NativeEngine.open(engine, input, params("{}"));
    for (int i = 0; i < 20; i++) {
      String id = catalog.getJSONArray("films").getJSONObject(i).getString("stock");
      Bitmap b = NativeEngine.render(engine, params("{\"film_stock\":\"" + id + "\"}"), true);
      assertTrue(id + " has nonempty output", brightness(b) > 1 && brightness(b) < 254);
      b.recycle();
    }
    for (int i = 0; i < 8; i++) {
      String id = catalog.getJSONArray("papers").getJSONObject(i).getString("stock");
      Bitmap b = NativeEngine.render(engine, params("{\"print_stock\":\"" + id + "\"}"), true);
      assertTrue(id + " has nonempty output", brightness(b) > 1 && brightness(b) < 254);
      b.recycle();
    }
    input.recycle();
  }
}
