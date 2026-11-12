// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.util.Arrays;
import org.json.*;

/**
 * JPEG 通路的曝光保真不变量。
 *
 * <p>RAW 的解码把 1.0 钉在传感器饱和点，所以导入时必须补测光锚点；而 JPEG 的白已经是
 * 显示白，管线对它必须近似恒等——中间调不动，只保留胶片自身的趾部/肩部。这里把一张相机
 * 直出 JPEG 走真实管线导出，断言三件事：
 *
 * <ol>
 *   <li>默认参数下，输出的中位数亮度与输入一致（没有隐藏的整体增益）；
 *   <li>把输入压暗后再走管线，输出跟着同比变暗（不会「修正」JPEG 的曝光）；
 *   <li>对 JPEG 打开测光会明显压暗（防止有人把 RAW 的锚定逻辑误开到 JPEG 上）。
 * </ol>
 */
public final class JpegExposureInstrumentedTest extends InstrumentationTestCase {
  private static final String BASE =
      "{\"film_stock\":\"kodak_portra_400\",\"print_stock\":\"kodak_portra_endura\","
          + "\"exposure_compensation_ev\":0,\"m_filter_shift\":0,\"y_filter_shift\":0,"
          + "\"grain_amount\":1,\"halation_amount\":1,\"glare_amount\":1,"
          + "\"dir_couplers_amount\":1,\"halation_scatter_amount\":1,\"density_curve_gamma\":1,"
          + "\"film_format_mm\":36,\"print_exposure\":1.0,\"scan_film\":false,"
          + "\"grain_active\":true,\"halation_active\":true,\"glare_active\":true,"
          + "\"dir_couplers_active\":true,\"scene_latitude_active\":false,"
          + "\"contrast_mask_active\":false,\"overscan_active\":false,"
          + "\"date_imprint_active\":false,"
          + "\"input_color_space\":\"sRGB\",\"input_cctf_decoding\":true,"
          + "\"output_color_space\":\"sRGB\",\"output_cctf_encoding\":true}";

  private void copyAsset(String name, File target) throws IOException {
    try (InputStream in = getInstrumentation().getContext().getAssets().open(name);
        OutputStream out = new FileOutputStream(target)) {
      byte[] buffer = new byte[65536];
      int count;
      while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
    }
  }

  private void makeDimmed(File input, File target) throws IOException {
    Bitmap src = BitmapFactory.decodeFile(input.getAbsolutePath());
    int w = src.getWidth(), h = src.getHeight();
    int[] px = new int[w * h];
    src.getPixels(px, 0, w, 0, 0, w, h);
    src.recycle();
    for (int i = 0; i < px.length; i++) {
      int p = px[i];
      px[i] =
          Color.rgb(
              Math.round(Color.red(p) * .6f),
              Math.round(Color.green(p) * .6f),
              Math.round(Color.blue(p) * .6f));
    }
    Bitmap dim = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
    try (OutputStream out = new FileOutputStream(target)) {
      dim.compress(Bitmap.CompressFormat.JPEG, 95, out);
    }
    dim.recycle();
  }

  /** Rec.709 显示域亮度的中位数；inSampleSize=2 采样对中位数足够稳。 */
  private static double medianLuma(File image) throws IOException {
    BitmapFactory.Options opts = new BitmapFactory.Options();
    opts.inSampleSize = 2;
    Bitmap b = BitmapFactory.decodeFile(image.getAbsolutePath(), opts);
    assertNotNull("无法解码 " + image, b);
    int w = b.getWidth(), h = b.getHeight();
    int[] px = new int[w * h];
    b.getPixels(px, 0, w, 0, 0, w, h);
    b.recycle();
    double[] luma = new double[px.length];
    for (int i = 0; i < px.length; i++) {
      int p = px[i];
      luma[i] = (0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)) / 255.0;
    }
    Arrays.sort(luma);
    return luma[luma.length / 2];
  }

  public void testJpegPipelineKeepsMidtonesAndMeteringWouldDarkenThem() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    File dir = context.getExternalFilesDir(null);
    assertNotNull(dir);
    File resources = new File(context.getFilesDir(), "exposure_engine_v023");
    EngineResources.prepare(context, resources);
    File input = new File(dir, "exposure-input.jpg");
    copyAsset("exposure-probe.jpg", input);
    File dimmed = new File(dir, "exposure-input-dark.jpg");
    makeDimmed(input, dimmed);

    String edits = "{\"grade_enabled\":false}";
    File[] inputs = {input, input, dimmed};
    String[] names = {"exposure-out-default", "exposure-out-meter", "exposure-out-dark"};
    boolean[] meters = {false, true, false};
    try (SpectralRenderer renderer = new SpectralRenderer(context)) {
      renderer.start(resources.getAbsolutePath(), "gpu");
      for (int i = 0; i < inputs.length; i++) {
        JSONObject params = new JSONObject(BASE);
        params.put("auto_exposure", meters[i]);
        params.put("auto_exposure_method", "balanced");
        JSONObject open = new JSONObject(params.toString());
        open.put("product_defaults", true);
        renderer.openFile(inputs[i].getAbsolutePath(), open.toString());
        File out = new File(dir, names[i] + ".jpg");
        if (out.exists()) out.delete();
        assertEquals(
            names[i] + " 导出失败",
            1600,
            new JSONObject(
                    renderer.exportImage(params.toString(), edits, out.getAbsolutePath(), "jpeg"))
                .getInt("width"));
        renderer.closeImage();
      }
    }

    double midIn = medianLuma(input);
    double midDefault = medianLuma(new File(dir, names[0] + ".jpg"));
    double midMeter = medianLuma(new File(dir, names[1] + ".jpg"));
    double midDarkIn = medianLuma(dimmed);
    double midDarkOut = medianLuma(new File(dir, names[2] + ".jpg"));

    assertEquals(
        "默认参数下输出的中间调与原图不一致，管线出现了隐藏的整体增益（实测偏差应 <0.02）",
        midIn,
        midDefault,
        0.04);
    assertEquals(
        "压暗的输入被管线「修正」了亮度，JPEG 的曝光必须原样保留",
        midDarkIn,
        midDarkOut,
        0.04);
    assertTrue(
        "对 JPEG 打开测光没有压暗中间调——RAW 的锚定逻辑不能开到 JPEG 上（实测压暗 0.17）",
        midMeter < midDefault - 0.12);
  }
}
