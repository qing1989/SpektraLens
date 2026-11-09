// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors.
package app.spektrafilm;

import android.graphics.*;
import org.json.JSONObject;

/** Original cartridge illustration. Coordinates are in a 100 × 80 design space. */
final class FilmCanisterArtwork {
  static final int WIDTH = 100, HEIGHT = 80;
  private final Canvas c;
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
  private static final int WHITE = Color.WHITE, BLACK = Color.BLACK;

  static Bitmap render(JSONObject stock) {
    Bitmap bitmap = Bitmap.createBitmap(WIDTH * 3, HEIGHT * 3, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.scale(3, 3);
    new FilmCanisterArtwork(canvas).draw(stock);
    return bitmap;
  }

  private FilmCanisterArtwork(Canvas canvas) {
    c = canvas;
  }

  private void solid(int color) {
    p.setShader(null);
    p.setColor(color);
  }

  private void across(float start, float end, int[] colors, float[] stops) {
    p.setColor(WHITE);
    p.setShader(new LinearGradient(start, 0, end, 0, colors, stops, Shader.TileMode.CLAMP));
  }

  private static int mix(int a, int b, float t) {
    return Color.rgb(
        Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
        Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
        Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
  }

  // A band around the cylinder. Its front edge dips towards the viewer.
  private static Path band(float left, float right, float top, float bottom, float bow) {
    float mid = (left + right) / 2;
    Path path = new Path();
    path.moveTo(left, top);
    path.quadTo(mid, top + 2 * bow, right, top);
    path.lineTo(right, bottom);
    path.quadTo(mid, bottom + 2 * bow, left, bottom);
    path.close();
    return path;
  }

  private void metal() {
    across(
        19,
        74,
        new int[] {0xff303539, 0xff778181, 0xff42494a, 0xff242a2c, 0xff545a58, 0xff242729},
        new float[] {0, .17f, .38f, .74f, .92f, 1});
  }

  private void leader() {
    Path tail = new Path();
    tail.moveTo(64, 32);
    tail.cubicTo(74, 34, 79, 32, 86, 29);
    tail.quadTo(89, 28, 89, 31);
    tail.lineTo(89, 39);
    tail.quadTo(89, 41, 87, 42);
    tail.quadTo(83, 43, 83, 47);
    tail.lineTo(83, 58);
    tail.quadTo(77, 63, 64, 63);
    tail.close();
    tail.setFillType(Path.FillType.EVEN_ODD);
    for (int i = 0; i < 2; i++) {
      float x = 75 + 6 * i, y = 35 - 2 * i;
      tail.addRoundRect(x, y, x + 3.6f, y + 3.2f, .65f, .65f, Path.Direction.CW);
    }
    tail.addRoundRect(75, 55, 78.6f, 58.2f, .65f, .65f, Path.Direction.CW);
    across(64, 90, new int[] {0xff32291f, 0xff76543a, 0xffa57d51}, null);
    c.drawPath(tail, p);
  }

  private void draw(JSONObject stock) {
    int coat = Color.parseColor(stock.optString("coat", "#ABADA8"));
    int accent = Color.parseColor(stock.optString("accent", "#577B78"));
    solid(0x40000000);
    c.drawOval(14, 67, 84, 79, p);
    solid(0x5c000000);
    c.drawOval(21, 69, 76, 76, p);
    leader();

    metal();
    c.drawPath(band(19, 73, 15, 67, 5), p);
    Path wrap = band(20, 72, 18, 64, 4.5f);
    c.save();
    c.clipPath(wrap);
    // Matte stock paper, glancing light at left and a soft falloff round the far side.
    across(
        20,
        72,
        new int[] {
          mix(coat, BLACK, .31f),
          mix(coat, WHITE, .13f),
          coat,
          mix(coat, BLACK, .08f),
          mix(coat, BLACK, .37f)
        },
        new float[] {0, .17f, .44f, .72f, 1});
    c.drawPath(wrap, p);
    solid(accent);
    c.drawPath(band(19, 73, 59, 65, 4.5f), p);
    solid(mix(accent, WHITE, .48f));
    c.drawPath(band(19, 73, 59, 59.7f, 4.5f), p);
    String series = stock.optString("series");
    if ("reversal".equals(series)) {
      solid(accent);
      c.drawPath(band(19, 73, 56.8f, 57.8f, 4.5f), p);
    } else if ("cine".equals(series)) {
      solid(mix(accent, BLACK, .56f));
      for (int i = 0; i < 4; i++) c.drawRoundRect(29 + i * 9, 64, 34 + i * 9, 66, .6f, .6f, p);
    }

    // One family name and one large speed code, readable without a manufacturer logo.
    int ink = Color.luminance(coat) >= .18 ? 0xff101412 : 0xfffff9ec;
    solid(ink);
    p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    p.setTextAlign(Paint.Align.CENTER);
    text(stock.optString("family", "FILM"), 45, 34, 7.8f, 45);
    text(stock.optString("mark", "35"), 45, 56.7f, 23, 46);
    c.restore();

    // Independent rolled hems, graphite lid, and a recessed winding spindle.
    metal();
    c.drawPath(band(18, 74, 65, 69, 4.8f), p);
    c.drawPath(band(18, 74, 13, 18, 4.8f), p);
    solid(0x86c1c5bb);
    c.drawPath(band(19, 73, 65.4f, 66.2f, 4.6f), p);
    c.drawPath(band(19, 73, 16.2f, 17, 4.6f), p);
    solid(0xff7c8380);
    c.drawOval(18, 8.5f, 74, 18.5f, p);
    solid(0xff343a39);
    c.drawOval(19, 9, 73, 17.6f, p);
    solid(0xff59605d);
    c.drawOval(28, 10.5f, 63, 16.3f, p);
    across(35, 55, new int[] {0xff303536, 0xff778080, 0xff42494a, 0xff242827}, null);
    c.drawPath(band(35, 55, 4.8f, 12, 2.1f), p);
    solid(0xff808884);
    c.drawOval(35, 2.7f, 55, 7, p);
    solid(0xff202423);
    c.drawOval(39.2f, 3.7f, 51, 6.1f, p);
    int push = stock.optInt("push");
    if (push > 0) {
      solid(0xffe7cda9);
      c.drawRoundRect(68, 44, 93, 59, 3, 3, p);
      solid(0xff342b28);
      text("+" + push, 80.5f, 55.5f, 12, 22);
    }
  }

  private void text(String value, float x, float baseline, float size, float maxWidth) {
    p.setTextSize(size);
    p.setTextScaleX(1);
    p.setTextScaleX(Math.min(1, maxWidth / Math.max(1, p.measureText(value))));
    c.drawText(value, x, baseline, p);
    p.setTextScaleX(1);
  }
}
