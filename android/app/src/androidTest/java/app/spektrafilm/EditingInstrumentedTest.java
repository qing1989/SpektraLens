// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.test.InstrumentationTestCase;
import java.io.*;
import org.json.*;

/** Assertions exercise the APK's native pixels, real RAW decoder and actual encoded files. */
public final class EditingInstrumentedTest extends InstrumentationTestCase {
  private long engine;
  private Context context;
  private File output;

  protected void setUp() throws Exception {
    super.setUp();
    context = getInstrumentation().getTargetContext();
    File resources = new File(context.getFilesDir(), "editing_test_engine_v023");
    if (!new File(resources, ".ready").exists()) {
      copy(context, "engine", resources);
      new File(resources, ".ready").createNewFile();
    }
    engine = NativeEngine.create(resources.getAbsolutePath());
    output = new File(context.getFilesDir(), "test-v0.2/" + getName());
    output.mkdirs();
    File[] previous = output.listFiles();
    if (previous != null)
      for (File f : previous)
        if (f.isFile() && !f.delete())
          throw new IOException("Cannot clear prior test output: " + f);
  }

  protected void tearDown() throws Exception {
    NativeEngine.destroy(engine);
    super.tearDown();
  }

  private void copy(Context c, String path, File dest) throws IOException {
    String[] names = c.getAssets().list(path);
    if (names != null && names.length > 0) {
      dest.mkdirs();
      for (String name : names) copy(c, path + "/" + name, new File(dest, name));
    } else
      try (InputStream in = c.getAssets().open(path);
          OutputStream out = new FileOutputStream(dest)) {
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n);
      }
  }

  private String params(String patch) throws Exception {
    JSONObject p =
        new JSONObject(
            "{\"film_stock\":\"kodak_portra_400\",\"print_stock\":\"kodak_portra_endura\",\"input_color_space\":\"sRGB\",\"input_cctf_decoding\":true,\"output_color_space\":\"sRGB\",\"output_cctf_encoding\":true,\"auto_exposure\":false,\"grain_active\":false,\"halation_active\":false,\"glare_active\":false}");
    JSONObject x = new JSONObject(patch);
    for (java.util.Iterator<String> i = x.keys(); i.hasNext(); ) {
      String key = i.next();
      p.put(key, x.get(key));
    }
    return p.toString();
  }

  private String product(String patch) throws Exception {
    JSONObject p = new JSONObject(params(patch));
    p.put("product_defaults", true);
    return p.toString();
  }

  private Bitmap chart(int w, int h) {
    Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
    int[] row = new int[w];
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++)
        row[x] = Color.rgb(20 + x * 210 / w, 20 + y * 210 / h, 20 + (x + y) * 210 / (w + h));
      b.setPixels(row, 0, w, 0, y, w, 1);
    }
    return b;
  }

  private int difference(Bitmap a, Bitmap b) {
    int n = 0;
    assertEquals(a.getWidth(), b.getWidth());
    assertEquals(a.getHeight(), b.getHeight());
    for (int y = 0; y < a.getHeight(); y++)
      for (int x = 0; x < a.getWidth(); x++) if (a.getPixel(x, y) != b.getPixel(x, y)) n++;
    return n;
  }

  private double light(Bitmap b) {
    double sum = 0;
    for (int y = 0; y < b.getHeight(); y++)
      for (int x = 0; x < b.getWidth(); x++) {
        int c = b.getPixel(x, y);
        sum += Color.red(c) * .2126 + Color.green(c) * .7152 + Color.blue(c) * .0722;
      }
    return sum / (b.getWidth() * b.getHeight());
  }

  private byte[] bytes(File f) throws IOException {
    try (InputStream in = new FileInputStream(f);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return out.toByteArray();
    }
  }

  public void testNonDestructiveGeometryAndIdentity() throws Exception {
    Bitmap source = chart(96, 64), identity = NativeEngine.editBitmap(source, "{}");
    assertEquals(0, difference(source, identity));
    String edits = "{\"geo_x\":0.25,\"geo_y\":0.25,\"geo_w\":0.5,\"geo_h\":0.5,\"geo_turns\":1}";
    Bitmap turned = NativeEngine.editBitmap(source, edits);
    assertEquals(32, turned.getWidth());
    assertEquals(48, turned.getHeight());
    assertEquals(source.getPixel(24, 47), turned.getPixel(0, 0));
    assertEquals(source.getPixel(71, 16), turned.getPixel(31, 47));
    Bitmap flipped = NativeEngine.editBitmap(source, "{\"geo_flip_h\":true}");
    assertEquals(source.getPixel(95, 5), flipped.getPixel(0, 5));
    Bitmap straight = NativeEngine.editBitmap(source, "{\"geo_angle\":17}");
    assertTrue(difference(source, straight) > 1000);
    assertEquals(source.getPixel(0, 0), Color.rgb(20, 20, 20));
    source.recycle();
    identity.recycle();
    turned.recycle();
    flipped.recycle();
    straight.recycle();
  }

  public void testToneColorAndCurveControlsChangePixels() throws Exception {
    Bitmap source = chart(96, 64);
    String[] patches = {
      "{\"grade_temperature\":80}",
      "{\"grade_tint\":60}",
      "{\"grade_exposure\":1}",
      "{\"grade_contrast\":30}",
      "{\"grade_brightness\":30}",
      "{\"grade_shadows\":70}",
      "{\"grade_highlights\":-80}",
      "{\"grade_black\":10}",
      "{\"grade_white\":10}",
      "{\"grade_saturation\":-70}",
      "{\"balance_midtones_hue\":210,\"balance_midtones_saturation\":60}",
      "{\"hsl_red_hue\":80,\"hsl_red_saturation\":-50}",
      "{\"grade_vignette\":-60}",
      "{\"curve_rgb\":[[0,0],[0.5,0.7],[1,1]]}",
      "{\"curve_luma\":[[0,0],[0.5,0.3],[1,1]]}",
      "{\"curve_r\":[[0,0],[1,0.6]]}",
      "{\"curve_g\":[[0,0],[1,0.6]]}",
      "{\"curve_b\":[[0,0],[1,0.6]]}"
    };
    for (String patch : patches) {
      Bitmap b = NativeEngine.editBitmap(source, patch);
      assertTrue(patch, difference(source, b) > 40);
      b.recycle();
    }
    Bitmap disabled =
        NativeEngine.editBitmap(
            source, "{\"grade_enabled\":false,\"grade_exposure\":4,\"curve_r\":[[0,0],[1,0.2]]}");
    assertEquals(0, difference(source, disabled));
    source.recycle();
    disabled.recycle();
  }

  public void testLumaCurveCanLiftBlackEndpoint() throws Exception {
    Bitmap black = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
    black.eraseColor(Color.BLACK);
    Bitmap lifted = NativeEngine.editBitmap(black, "{\"curve_luma\":[[0,0.2],[1,1]]}");
    assertTrue(Color.red(lifted.getPixel(8, 8)) > 45);
    black.recycle();
    lifted.recycle();
  }

  public void testBordersDatesAndDimensionsAreExportable() throws Exception {
    Bitmap source = chart(640, 480);
    String crop = "\"geo_w\":0.5,\"geo_h\":0.5,\"geo_turns\":1,";
    String plain =
        "{"
            + crop
            + "\"edge_style\":\"135_black\",\"edge_amount\":6,\"film_label\":\"PORTRA 400\"}";
    Bitmap before = NativeEngine.editBitmap(source, plain);
    JSONObject dims = new JSONObject(NativeEngine.dimensions(640, 480, plain));
    assertEquals(dims.getInt("width"), before.getWidth());
    assertEquals(dims.getInt("height"), before.getHeight());
    String date =
        plain.substring(0, plain.length() - 1)
            + ",\"date_enabled\":true,\"date_text\":\"2026.11.01\",\"date_size\":2}";
    Bitmap after = NativeEngine.editBitmap(source, date);
    assertTrue(difference(before, after) > 100);
    String[] styles = {"135_warm", "clean_white", "instant"};
    for (String style : styles) {
      Bitmap b =
          NativeEngine.editBitmap(
              source, "{\"edge_style\":\"" + style + "\",\"edge_caption\":\"TEST FRAME\"}");
      assertTrue(b.getWidth() > source.getWidth());
      assertTrue(b.getHeight() > source.getHeight());
      b.recycle();
    }
    source.recycle();
    before.recycle();
    after.recycle();
  }

  public void testNative16BitFilesMatchEditedPreview() throws Exception {
    Bitmap source = chart(192, 128);
    String p = params("{}");
    NativeEngine.open(engine, source, product("{}"));
    String edits =
        "{\"geo_w\":0.75,\"geo_turns\":1,\"grade_temperature\":20,\"curve_rgb\":[[0,0],[0.5,0.6],[1,1]],\"edge_style\":\"instant\",\"date_enabled\":true,\"date_text\":\"2026.11.01\"}";
    Bitmap preview = NativeEngine.renderEdited(engine, p, edits, true);
    for (String format : new String[] {"png", "tiff", "jpeg"}) {
      File f = new File(output, "edited." + format);
      JSONObject dims =
          new JSONObject(NativeEngine.exportImage(engine, p, edits, f.getAbsolutePath(), format));
      assertEquals(preview.getWidth(), dims.getInt("width"));
      assertEquals(preview.getHeight(), dims.getInt("height"));
      byte[] data = bytes(f);
      assertTrue(data.length > 500);
      if (format.equals("png")) {
        assertEquals(16, data[24] & 255);
        assertEquals(2, data[25] & 255);
        Bitmap decoded = BitmapFactory.decodeFile(f.getAbsolutePath());
        assertNotNull(decoded);
        assertEquals(preview.getWidth(), decoded.getWidth());
        int worst = 0;
        for (int y = 0; y < preview.getHeight(); y++)
          for (int x = 0; x < preview.getWidth(); x++) {
            int a = preview.getPixel(x, y), b = decoded.getPixel(x, y);
            worst = Math.max(worst, Math.abs(Color.red(a) - Color.red(b)));
            worst = Math.max(worst, Math.abs(Color.green(a) - Color.green(b)));
            worst = Math.max(worst, Math.abs(Color.blue(a) - Color.blue(b)));
          }
        assertTrue("PNG16 preview parity <= 1 count: " + worst, worst <= 1);
        decoded.recycle();
      } else if (format.equals("tiff")) {
        assertEquals('I', data[0]);
        assertEquals('I', data[1]);
        assertEquals(42, data[2]);
      } else {
        assertEquals(255, data[0] & 255);
        assertEquals(216, data[1] & 255);
      }
    }
    preview.recycle();
    source.recycle();
  }

  public void testProductExposureAndPositiveDirectScan() throws Exception {
    Bitmap source = chart(96, 64);
    NativeEngine.open(engine, source, product("{}"));
    Bitmap a = NativeEngine.render(engine, params("{}"), true);
    Bitmap b = NativeEngine.render(engine, params("{\"exposure_compensation_ev\":1}"), true);
    assertTrue("Product exposure must affect final print", Math.abs(light(a) - light(b)) > 2);
    String reversal =
        params(
            "{\"film_stock\":\"fujifilm_provia_100f\",\"scan_film\":true,\"exposure_compensation_ev\":0}");
    Bitmap positive = NativeEngine.render(engine, reversal, true);
    assertTrue(light(positive) > 1 && light(positive) < 254);
    JSONObject changed = new JSONObject(reversal);
    changed.put("print_stock", "kodak_2383");
    changed.put("print_exposure", .5);
    Bitmap noPaper = NativeEngine.render(engine, changed.toString(), true);
    assertEquals("Paper must not change a direct film scan", 0, difference(positive, noPaper));
    source.recycle();
    a.recycle();
    b.recycle();
    positive.recycle();
    noPaper.recycle();
  }

  public void testDngSensorDataWithoutEmbeddedPreview() throws Exception {
    File fixture = new File(output, "bayer-no-preview.dng");
    try (InputStream in =
            getInstrumentation().getContext().getAssets().open("bayer-no-preview.dng");
        OutputStream out = new FileOutputStream(fixture)) {
      byte[] b = new byte[16384];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
    }
    JSONObject info = new JSONObject(NativeEngine.inspectRaw(fixture.getAbsolutePath()));
    assertTrue(info.toString(), info.getBoolean("raw"));
    assertEquals(256, info.getInt("width"));
    Bitmap small = NativeEngine.rawPreview(engine, fixture.getAbsolutePath(), 128);
    assertEquals(128, small.getWidth());
    assertEquals(96, small.getHeight());
    String p = params("{\"input_color_space\":\"ProPhoto RGB\",\"input_cctf_decoding\":false}");
    NativeEngine.openRaw(engine, fixture.getAbsolutePath(), product("{}"), 0);
    File f = new File(output, "raw-full.png");
    JSONObject dims =
        new JSONObject(NativeEngine.exportImage(engine, p, "{}", f.getAbsolutePath(), "png"));
    assertEquals(256, dims.getInt("width"));
    assertEquals(192, dims.getInt("height"));
    Bitmap decoded = BitmapFactory.decodeFile(f.getAbsolutePath());
    assertTrue(light(decoded) > 1 && light(decoded) < 254);
    small.recycle();
    decoded.recycle();
  }

  public void testRealCameraNefDecode() throws Exception {
    File fixture = new File(context.getFilesDir(), "test-fixtures/iss030e122639.NEF");
    assertTrue("Install the documented local-only rawpy fixture", fixture.isFile());
    JSONObject info = new JSONObject(NativeEngine.inspectRaw(fixture.getAbsolutePath()));
    assertTrue(info.toString(), info.getBoolean("raw"));
    assertTrue(
        info.toString(), info.getString("make").toUpperCase(java.util.Locale.US).contains("NIKON"));
    assertTrue(info.getInt("width") > 2560);
    Bitmap small = NativeEngine.rawPreview(engine, fixture.getAbsolutePath(), 384);
    assertEquals(384, Math.max(small.getWidth(), small.getHeight()));
    String p = params("{\"input_color_space\":\"ProPhoto RGB\",\"input_cctf_decoding\":false}");
    NativeEngine.openRaw(engine, fixture.getAbsolutePath(), product("{}"), 384);
    Bitmap developed = NativeEngine.renderEdited(engine, p, "{}", true);
    assertTrue(light(developed) > 1 && light(developed) < 254);
    assertTrue(difference(small, developed) > 1000);
    android.util.Log.i("SpektraLensV02Test", "RAW actual camera: " + info);
    small.recycle();
    developed.recycle();
  }

  public void testRealCameraNefOriginalSizeExport() throws Exception {
    File fixture = new File(context.getFilesDir(), "test-fixtures/iss030e122639.NEF");
    JSONObject info = new JSONObject(NativeEngine.inspectRaw(fixture.getAbsolutePath()));
    String p =
        params(
            "{\"input_color_space\":\"ProPhoto"
                + " RGB\",\"input_cctf_decoding\":false,\"dir_couplers_active\":false}");
    NativeEngine.openRaw(
        engine, fixture.getAbsolutePath(), product("{\"dir_couplers_active\":false}"), 0);
    File f = new File(output, "camera-original.jpeg");
    JSONObject dims =
        new JSONObject(NativeEngine.exportImage(engine, p, "{}", f.getAbsolutePath(), "jpeg"));
    assertEquals(info.getInt("width"), dims.getInt("width"));
    assertEquals(info.getInt("height"), dims.getInt("height"));
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeFile(f.getAbsolutePath(), options);
    assertEquals(dims.getInt("width"), options.outWidth);
    assertEquals(dims.getInt("height"), options.outHeight);
  }

  public void testOriginalSizeBeyondOld2560Limit() throws Exception {
    Bitmap source = chart(3072, 2048);
    String p = params("{\"dir_couplers_active\":false}");
    NativeEngine.open(engine, source, product("{\"dir_couplers_active\":false}"));
    File f = new File(output, "original-3072.png");
    JSONObject dims =
        new JSONObject(NativeEngine.exportImage(engine, p, "{}", f.getAbsolutePath(), "png"));
    assertEquals(3072, dims.getInt("width"));
    assertEquals(2048, dims.getInt("height"));
    assertEquals(16, bytes(f)[24] & 255);
    source.recycle();
  }
}
