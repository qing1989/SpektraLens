// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.test.InstrumentationTestCase;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import org.json.*;

/** Checks the actual Android decoder and visible selection/push cues with bundled artwork. */
public final class FilmArtworkInstrumentedTest extends InstrumentationTestCase {
  private Bitmap render(String stock, String name, boolean selected) {
    Bitmap result = Bitmap.createBitmap(200, 140, Bitmap.Config.ARGB_8888);
    getInstrumentation()
        .runOnMainSync(
            () -> {
              FilmBadge view =
                  new FilmBadge(getInstrumentation().getTargetContext(), stock, name, selected);
              view.layout(0, 0, 200, 140);
              view.draw(new Canvas(result));
            });
    return result;
  }

  public void testAuthorizedLabelsDecodeOnAndroid() throws Exception {
    Context c = getInstrumentation().getTargetContext();
    String[] names = c.getAssets().list("film_icons/grlife");
    assertEquals(FilmBadge.usesPublicLabels(c) ? 0 : 11, names.length);
    for (String name : names) {
      try (InputStream in = c.getAssets().open("film_icons/grlife/" + name)) {
        Bitmap b = BitmapFactory.decodeStream(in);
        assertNotNull(name, b);
        assertEquals(120, b.getWidth());
        assertEquals(120, b.getHeight());
        assertTrue("Transparent PNG padding is preserved: " + name, b.hasAlpha());
        b.recycle();
      }
    }
    writeArtworkReview(c);
  }

  private JSONObject assetJson(Context c, String name) throws Exception {
    try (InputStream in = c.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[4096];
      int n;
      while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
      return new JSONObject(out.toString("UTF-8"));
    }
  }

  private void writeArtworkReview(Context c) throws Exception {
    JSONObject manifest = assetJson(c, "film_icons/manifest.json");
    JSONArray films = assetJson(c, "catalog.json").getJSONArray("films");
    assertEquals(20, films.length());
    File output = new File(c.getFilesDir(), "film-artwork-v024");
    output.mkdirs();
    Bitmap sheet = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(sheet);
    canvas.drawColor(Color.rgb(14, 15, 15));
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    paint.setTextAlign(Paint.Align.CENTER);
    java.util.ArrayList<Bitmap> originals = new java.util.ArrayList<>();
    for (int i = 0; i < films.length(); i++) {
      JSONObject film = films.getJSONObject(i);
      String id = film.getString("stock"), name = film.getString("name");
      JSONObject artwork =
          manifest
              .getJSONObject("artworks")
              .getJSONObject(manifest.getJSONObject("stocks").getString(id));
      if (artwork.getString("mode").equals("vector_label")) {
        Bitmap label = FilmLabelArtwork.create(artwork.getJSONObject("drawing"));
        assertTrue(label.hasAlpha());
        for (Bitmap other : originals)
          assertFalse("Each new stock has distinct lettering", label.sameAs(other));
        originals.add(label);
        try (FileOutputStream out = new FileOutputStream(new File(output, id + ".png"))) {
          assertTrue(label.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
      }
      Bitmap badge = render(id, name, false);
      int x = (i % 5) * 240, y = (i / 5) * 200;
      canvas.drawBitmap(badge, null, new Rect(x + 20, y + 8, x + 220, y + 148), paint);
      badge.recycle();
      paint.setTextSize(16);
      paint.setColor(Color.rgb(235, 229, 216));
      String caption = name.replace("Kodak ", "").replace("Fujifilm ", "");
      paint.setTextScaleX(Math.min(1, 220 / paint.measureText(caption)));
      canvas.drawText(caption, x + 120, y + 173, paint);
      paint.setTextScaleX(1);
      paint.setTextSize(11);
      paint.setColor(Color.rgb(150, 152, 149));
      canvas.drawText(
          FilmBadge.usesPublicLabels(c)
              ? "PROJECT LABEL"
              : artwork.getString("mode").equals("vector_label") ? "PROJECT VECTOR" : "GRLIFE",
          x + 120,
          y + 192,
          paint);
    }
    assertEquals(7, originals.size());
    for (Bitmap b : originals) b.recycle();
    try (FileOutputStream out = new FileOutputStream(new File(output, "film-icons-review.png"))) {
      assertTrue(sheet.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
    sheet.recycle();
  }

  public void testSelectedCustomLabelKeepsVisibleCheckMarker() {
    Bitmap off = render("kodak_portra_400", "Kodak Portra 400", false);
    Bitmap on = render("kodak_portra_400", "Kodak Portra 400", true);
    int offGold = 0, onGold = 0;
    // The upper-right selection cue sits outside the cartridge label.
    for (int y = 6; y < 34; y++)
      for (int x = 156; x < 184; x++) {
        if (off.getPixel(x, y) == MainActivity.ACCENT) offGold++;
        if (on.getPixel(x, y) == MainActivity.ACCENT) onGold++;
      }
    assertEquals(0, offGold);
    assertTrue("Selected bitmap artwork must still show its gold check cue", onGold > 100);
    off.recycle();
    on.recycle();
  }

  public void testPushVariantsAndUnmatchedStocksRemainDistinct() {
    Bitmap base = render("kodak_portra_800", "Kodak Portra 800", false);
    Bitmap push1 = render("kodak_portra_800_push1", "Kodak Portra 800 (Push 1)", false);
    Bitmap push2 = render("kodak_portra_800_push2", "Kodak Portra 800 (Push 2)", false);
    int baseVsPush = 0, pushVsPush = 0;
    for (int y = 0; y < 140; y++)
      for (int x = 0; x < 200; x++) {
        if (base.getPixel(x, y) != push1.getPixel(x, y)) baseVsPush++;
        if (push1.getPixel(x, y) != push2.getPixel(x, y)) pushVsPush++;
      }
    assertTrue("Push processing has a visible badge", baseVsPush > 100);
    assertTrue("+1 and +2 must be distinguishable", pushVsPush > 10);
    Bitmap fallback = render("fujifilm_c200", "Fujifilm C200", false);
    int opaque = 0;
    for (int y = 0; y < 140; y++)
      for (int x = 0; x < 200; x++) if ((fallback.getPixel(x, y) >>> 24) > 0) opaque++;
    assertTrue("An unmatched stock still has a complete native icon", opaque > 1000);
    base.recycle();
    push1.recycle();
    push2.recycle();
    fallback.recycle();
  }
}
