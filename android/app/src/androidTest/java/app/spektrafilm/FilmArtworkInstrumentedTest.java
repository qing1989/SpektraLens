// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.test.InstrumentationTestCase;
import java.io.*;
import java.util.ArrayList;
import org.json.*;

/** Review and export the very same stock badges used in the editor. */
public final class FilmArtworkInstrumentedTest extends InstrumentationTestCase {
  private Bitmap render(String id, String name, boolean selected) {
    Bitmap bitmap = Bitmap.createBitmap(300, 240, Bitmap.Config.ARGB_8888);
    getInstrumentation()
        .runOnMainSync(
            () -> {
              FilmBadge view =
                  new FilmBadge(getInstrumentation().getTargetContext(), id, name, selected);
              view.layout(0, 0, 300, 240);
              view.draw(new Canvas(bitmap));
            });
    return bitmap;
  }

  private static void png(Bitmap bitmap, File path) throws IOException {
    try (FileOutputStream out = new FileOutputStream(path)) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    }
  }

  public void testEveryCatalogStockHasDistinctProjectArtwork() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    assertEquals(0, context.getAssets().list("film_icons/grlife").length);
    JSONArray films;
    try (InputStream in = context.getAssets().open("catalog.json");
        java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A")) {
      films = new JSONObject(scanner.next()).getJSONArray("films");
    }
    File dir = new File(context.getFilesDir(), "film-canisters-01");
    assertTrue(dir.isDirectory() || dir.mkdirs());
    Bitmap sheet = Bitmap.createBitmap(1200, 1160, Bitmap.Config.ARGB_8888);
    Canvas c = new Canvas(sheet);
    c.drawColor(0xff191a1b);
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    p.setColor(0xfff2efe7);
    p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    p.setTextSize(38);
    c.drawText("SpektraLens", 46, 67, p);
    p.setColor(0xffb8b7b2);
    p.setTextSize(20);
    c.drawText("胶卷图标  /  20 款", 47, 105, p);
    p.setColor(0xff414242);
    c.drawRect(44, 127, 1156, 128, p);
    ArrayList<Bitmap> seen = new ArrayList<>();
    for (int i = 0; i < films.length(); i++) {
      JSONObject f = films.getJSONObject(i);
      String id = f.getString("stock"), name = f.getString("name");
      Bitmap b = render(id, name, false);
      assertEquals("Transparent outside the canister", 0, b.getPixel(0, 0));
      assertEquals("Opaque wrap", 255, Color.alpha(b.getPixel(137, 134)));
      for (Bitmap other : seen) assertFalse("Duplicate stock art: " + id, b.sameAs(other));
      seen.add(b);
      png(b, new File(dir, id + ".png"));
      int x = 40 + (i % 5) * 224, y = 153 + (i / 5) * 248;
      p.setColor(Color.WHITE);
      c.drawBitmap(b, null, new Rect(x + 8, y, x + 216, y + 166), p);
      p.setTextAlign(Paint.Align.CENTER);
      p.setColor(0xffeceae5);
      p.setTextSize(20);
      String title =
          name.replaceFirst("^(Kodak|Fujifilm) ", "").replace(" (Push ", "  +").replace(")", "");
      c.drawText(title, x + 112, y + 200, p);
      p.setTextSize(13);
      p.setColor(0xff9c9d9d);
      c.drawText(name.startsWith("Kodak") ? "KODAK" : "FUJIFILM", x + 112, y + 222, p);
    }
    assertEquals(20, seen.size());
    png(sheet, new File(dir, "overview.png"));
    seen.forEach(Bitmap::recycle);
    sheet.recycle();
  }

  public void testPushAndSelectionDoNotReplaceTheBaseArtwork() {
    Bitmap base = render("kodak_portra_800", "Portra 800", false);
    Bitmap one = render("kodak_portra_800_push1", "Push 1", false);
    Bitmap two = render("kodak_portra_800_push2", "Push 2", false);
    Bitmap selected = render("kodak_portra_800", "Portra 800", true);
    int pushInk = 0, differentPushSteps = 0, selectedPixels = 0;
    for (int y = 0; y < base.getHeight(); y++)
      for (int x = 0; x < base.getWidth(); x++) {
        if (one.getPixel(x, y) != base.getPixel(x, y)) {
          assertTrue("Push marking stays in the dark lower label band", y >= 164);
          pushInk++;
        }
        if (one.getPixel(x, y) != two.getPixel(x, y)) differentPushSteps++;
        if (selected.getPixel(x, y) != base.getPixel(x, y)) selectedPixels++;
      }
    assertTrue(pushInk > 200);
    assertTrue(differentPushSteps > 20);
    assertTrue(selectedPixels > 100);
    base.recycle();
    one.recycle();
    two.recycle();
    selected.recycle();
  }
}
