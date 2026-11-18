// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.test.InstrumentationTestCase;
import java.io.*;
import org.json.*;

/** Cross-backend reference, captured from upstream Metal rather than another Vulkan run. */
public final class FilmParityInstrumentedTest extends InstrumentationTestCase {
  private byte[] read(InputStream input) throws IOException {
    try (InputStream in = input;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int n;
      while ((n = in.read(buffer)) >= 0) out.write(buffer, 0, n);
      return out.toByteArray();
    }
  }

  private void copy(Context context, String asset, File file) throws IOException {
    String[] children = context.getAssets().list(asset);
    if (children != null && children.length > 0) {
      file.mkdirs();
      for (String name : children) copy(context, asset + "/" + name, new File(file, name));
    } else {
      try (OutputStream out = new FileOutputStream(file)) {
        out.write(read(context.getAssets().open(asset)));
      }
    }
  }

  public void testEveryFilmMatchesIndependentUpstreamMetalReference() throws Exception {
    Context app = getInstrumentation().getTargetContext(),
        tests = getInstrumentation().getContext();
    File resources = new File(app.getCacheDir(), "film-parity-engine");
    // Always copy the APK's shaders. A previous run's .ready stamp can otherwise grade stale code.
    copy(app, "engine", resources);
    File output = new File(app.getFilesDir(), "film-reference-observed");
    output.mkdirs();
    JSONObject manifest =
        new JSONObject(
            new String(read(tests.getAssets().open("film-reference/manifest.json")), "UTF-8"));
    int width = manifest.getInt("width"), height = manifest.getInt("height");
    byte[] source = read(tests.getAssets().open("film-reference/chart.rgb8"));
    Bitmap input = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    for (int y = 0; y < height; y++)
      for (int x = 0; x < width; x++) {
        int i = (y * width + x) * 3;
        input.setPixel(x, y, Color.rgb(source[i] & 255, source[i + 1] & 255, source[i + 2] & 255));
      }
    JSONArray results = new JSONArray(), failed = new JSONArray();
    long engine = NativeEngine.create(resources.getAbsolutePath());
    try {
      JSONArray cases = manifest.getJSONArray("cases");
      JSONObject catalog =
          new JSONObject(new String(read(app.getAssets().open("catalog.json")), "UTF-8"));
      assertEquals(catalog.getJSONArray("films").length() + 5, cases.length());
      for (int n = 0; n < cases.length(); n++) {
        JSONObject item = cases.getJSONObject(n), params = item.getJSONObject("params");
        String name = item.getString("name");
        JSONObject open = new JSONObject(params.toString());
        open.put("product_defaults", true);
        NativeEngine.open(engine, input, open.toString());
        Bitmap actual = NativeEngine.render(engine, params.toString(), true);
        byte[] expected = read(tests.getAssets().open("film-reference/" + name + ".rgb8"));
        byte[] pixels = new byte[expected.length];
        int worst = 0;
        long total = 0;
        for (int y = 0; y < height; y++)
          for (int x = 0; x < width; x++) {
            int color = actual.getPixel(x, y), i = (y * width + x) * 3;
            int[] channels = {Color.red(color), Color.green(color), Color.blue(color)};
            for (int k = 0; k < 3; k++) {
              pixels[i + k] = (byte) channels[k];
              int difference = Math.abs(channels[k] - (expected[i + k] & 255));
              worst = Math.max(worst, difference);
              total += difference;
            }
          }
        try (OutputStream out = new FileOutputStream(new File(output, name + ".rgb8"))) {
          out.write(pixels);
        }
        File exported = new File(output, name + ".png");
        if (exported.exists()) assertTrue("Remove this test's previous output", exported.delete());
        NativeEngine.exportImage(
            engine, params.toString(), "{}", exported.getAbsolutePath(), "png");
        if (FilmStockPolicy.monochrome(params.getString("film_stock"))) {
          Bitmap live = NativeEngine.render(engine, params.toString(), false);
          assertTrue(name + " equal-size live/full parity", actual.sameAs(live));
          live.recycle();
          assertExportMatches(actual, exported);
          JSONObject defaults = new JSONObject(params.toString());
          for (java.util.Map.Entry<String, Object> override :
              FilmStockPolicy.renderOverrides(
                      params.getString("film_stock"), "", FilmStockPolicy.SILVER_PAPER, 1)
                  .entrySet())
            defaults.put(override.getKey(), override.getValue());
          defaults.put("halation_active", true);
          defaults.put("glare_active", true);
          Bitmap textured = NativeEngine.render(engine, defaults.toString(), true);
          try {
            for (int y = 0; y < textured.getHeight(); y++)
              for (int x = 0; x < textured.getWidth(); x++) {
                int pixel = textured.getPixel(x, y);
                assertTrue(name + " neutral default effects",
                    Math.abs(Color.red(pixel) - Color.green(pixel)) <= 2
                        && Math.abs(Color.blue(pixel) - Color.green(pixel)) <= 2);
              }
            File texturedExport = new File(output, name + "-default.png");
            if (texturedExport.exists()) assertTrue(texturedExport.delete());
            NativeEngine.exportImage(
                engine, defaults.toString(), "{}", texturedExport.getAbsolutePath(), "png");
            assertExportMatches(textured, texturedExport);
          } finally {
            textured.recycle();
          }
          Bitmap restored = NativeEngine.render(engine, params.toString(), true);
          assertTrue(name + " reset grain restores quiet pixels", actual.sameAs(restored));
          restored.recycle();
        }
        results.put(
            new JSONObject()
                .put("name", name)
                .put("max_rgb8_difference", worst)
                .put("mean_rgb8_difference", total / (double) pixels.length));
        if (worst > 1) failed.put(name + ": max RGB8 difference " + worst);
        actual.recycle();
      }
    } finally {
      NativeEngine.destroy(engine);
      input.recycle();
      try (OutputStream out = new FileOutputStream(new File(output, "results.json"))) {
        out.write(results.toString(2).getBytes("UTF-8"));
      }
    }
    assertEquals(failed.toString(), 0, failed.length());
  }

  private void assertExportMatches(Bitmap preview, File file) {
    Bitmap exported = BitmapFactory.decodeFile(file.getAbsolutePath());
    assertNotNull("PNG export decodes", exported);
    try {
      assertEquals(preview.getWidth(), exported.getWidth());
      assertEquals(preview.getHeight(), exported.getHeight());
      for (int y = 0; y < preview.getHeight(); y++)
        for (int x = 0; x < preview.getWidth(); x++) {
          int before = preview.getPixel(x, y), after = exported.getPixel(x, y);
          assertTrue("PNG16 and preview agree after 8-bit decoding",
              Math.abs(Color.red(before) - Color.red(after)) <= 1
                  && Math.abs(Color.green(before) - Color.green(after)) <= 1
                  && Math.abs(Color.blue(before) - Color.blue(after)) <= 1);
        }
    } finally {
      exported.recycle();
    }
  }
}
