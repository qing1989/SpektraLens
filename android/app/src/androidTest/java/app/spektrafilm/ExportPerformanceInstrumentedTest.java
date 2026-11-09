// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.security.MessageDigest;
import org.json.*;

/** Real full-size decoding, spectral development, grading and file encoding; no preview reuse. */
public final class ExportPerformanceInstrumentedTest extends InstrumentationTestCase {
  private void copy(Context c, String path, File target) throws IOException {
    String[] children = c.getAssets().list(path);
    if (children.length > 0) {
      target.mkdirs();
      for (String child : children) copy(c, path + "/" + child, new File(target, child));
    } else
      try (InputStream in = c.getAssets().open(path);
          OutputStream out = new FileOutputStream(target)) {
        byte[] buffer = new byte[65536];
        int count;
        while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
      }
  }

  private String hash(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = in.read(buffer)) != -1) digest.update(buffer, 0, count);
    }
    StringBuilder result = new StringBuilder();
    for (byte b : digest.digest())
      result.append(String.format(java.util.Locale.US, "%02x", b & 255));
    return result.toString();
  }

  private File fixture(File directory, int width, int height) throws IOException {
    File file = new File(directory, "noise-" + width + "x" + height + ".jpg");
    if (!file.exists()) {
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      int state = 314159, row[] = new int[width];
      for (int y = 0; y < height; y++) {
        for (int x = 0; x < width; x++) {
          state ^= state << 13;
          state ^= state >>> 17;
          state ^= state << 5;
          row[x] = 0xff000000 | (state & 0xffffff);
        }
        bitmap.setPixels(row, 0, width, 0, y, width, 1);
      }
      try (OutputStream out = new FileOutputStream(file)) {
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out))
          throw new IOException("JPEG fixture failed");
      }
      bitmap.recycle();
    }
    return file;
  }

  public void testFullSizeExportTimingsAndDeterministicBytes() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    File directory = new File(context.getFilesDir(), "export-performance");
    directory.mkdirs();
    File resources = new File(context.getFilesDir(), "performance_engine_v023");
    if (!new File(resources, ".ready").exists()) {
      copy(context, "engine", resources);
      new File(resources, ".ready").createNewFile();
    }
    JSONArray measurements = new JSONArray();
    try (SpectralRenderer renderer = new SpectralRenderer(context)) {
      renderer.start(resources.getAbsolutePath(), "gpu");
      for (boolean effects : new boolean[] {false, true}) {
        int width = effects ? 1600 : 4000, height = effects ? 1200 : 3000;
        File input = fixture(directory, width, height);
        JSONObject params =
            new JSONObject(
                "{\"film_stock\":\"kodak_portra_400\",\"print_stock\":\"kodak_portra_endura\",\"input_color_space\":\"sRGB\",\"input_cctf_decoding\":true,\"output_color_space\":\"sRGB\",\"output_cctf_encoding\":true,\"auto_exposure\":false,\"product_defaults\":true}");
        params.put("grain_active", effects);
        params.put("halation_active", effects);
        params.put("glare_active", effects);
        String openParams = params.toString();
        params.remove("product_defaults");
        String edits =
            "{\"grade_enabled\":true,\"grade_brightness\":8,\"grade_contrast\":12,\"grade_vignette\":-12}";
        String previous = null;
        for (int run = 0; run < 2; run++) {
          long start = SystemClock.elapsedRealtime();
          renderer.openFile(input.getAbsolutePath(), openParams);
          long opened = SystemClock.elapsedRealtime();
          File output = new File(directory, (effects ? "effects-" : "12mp-") + run + ".jpg");
          if (output.exists() && !output.delete())
            throw new IOException("Cannot clear previous benchmark output");
          JSONObject result =
              new JSONObject(
                  renderer.exportImage(params.toString(), edits, output.getAbsolutePath(), "jpeg"));
          long finished = SystemClock.elapsedRealtime();
          renderer.closeImage();
          assertEquals(width, result.getInt("width"));
          assertEquals(height, result.getInt("height"));
          String sha = hash(output);
          if (previous != null)
            assertEquals("Repeated full exports must preserve pixels", previous, sha);
          previous = sha;
          result.put("run", run);
          result.put("effects", effects);
          result.put("input_bytes", input.length());
          result.put("input_sha256", hash(input));
          result.put("output_sha256", sha);
          result.put("open_ms", opened - start);
          result.put("total_ms", finished - start);
          result.put("backend", renderer.backend());
          result.put("fallback_reason", renderer.fallbackReason());
          measurements.put(result);
          android.util.Log.i("SpektraLensBenchmark", result.toString());
          try (OutputStream out = new FileOutputStream(new File(directory, "timings.json"))) {
            out.write(measurements.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
        }
      }
    }
  }
}
