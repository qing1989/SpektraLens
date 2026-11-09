// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors.
package app.spektrafilm;

import android.graphics.*;
import java.util.Locale;
import org.json.JSONObject;

/**
 * Flat cartridge label: stock colour on top, speed code as the hero, a maker line or push tag
 * below. Drawn in a 100 × 80 design space.
 *
 * <p>Replaces the earlier 3D canister illustration. The card in the film list is only about 34 dp
 * tall, and the eight layers of gradient plus a sprocketed leader left the speed code unreadable at
 * that size. Everything here is flat fill and a hairline frame, so it survives downscaling without
 * any image assets.
 */
final class FilmCanisterArtwork {
  static final int WIDTH = 100, HEIGHT = 80;

  private static final float RADIUS = 13f;
  private static final float DIVIDER = 55f;
  private static final int BLACK = 0xff000000, WHITE = 0xffffffff;
  private static final int DARK_INK = 0xff0d1010, LIGHT_INK = 0xfffaf6ec;

  private final Canvas c;
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF rect = new RectF();

  static Bitmap render(String id, JSONObject stock) {
    Bitmap bitmap = Bitmap.createBitmap(WIDTH * 3, HEIGHT * 3, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.scale(3, 3);
    new FilmCanisterArtwork(canvas).draw(id, stock);
    return bitmap;
  }

  private FilmCanisterArtwork(Canvas canvas) {
    c = canvas;
  }

  /** Maker line, taken from the stock id prefix: fujifilm_c200 → FUJIFILM. */
  private static String maker(String id) {
    int cut = id.indexOf('_');
    String prefix = cut > 0 ? id.substring(0, cut) : id;
    return prefix.toUpperCase(Locale.ROOT);
  }

  private static int mix(int a, int b, float t) {
    return Color.rgb(
        Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
        Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
        Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
  }

  private static int withAlpha(int color, float alpha) {
    return (color & 0x00ffffff) | (Math.round(alpha * 255) << 24);
  }

  private static int inkOf(int color) {
    return Color.luminance(color) > 0.26f ? DARK_INK : LIGHT_INK;
  }

  private void fill(int color) {
    p.setShader(null);
    p.setColor(color);
  }

  private void draw(String id, JSONObject stock) {
    int coat = Color.parseColor(stock.optString("coat", "#ABADA8"));
    int accent = Color.parseColor(stock.optString("accent", "#577B78"));
    int ink = inkOf(coat);

    Path label = new Path();
    rect.set(0, 0, WIDTH, HEIGHT);
    label.addRoundRect(rect, RADIUS, RADIUS, Path.Direction.CW);

    c.save();
    c.clipPath(label);
    fill(mix(coat, BLACK, .42f));
    c.drawRect(0, DIVIDER, WIDTH, HEIGHT, p);
    fill(coat);
    c.drawRect(0, 0, WIDTH, DIVIDER, p);
    // Paper edge catching the light, then the accent rule that ties the family names together.
    fill(withAlpha(mix(coat, WHITE, .26f), .5f));
    c.drawRect(0, DIVIDER - .8f, WIDTH, DIVIDER + .85f, p);
    fill(accent);
    rect.set(18, 57.6f, 82, 60.2f);
    c.drawRoundRect(rect, 1.3f, 1.3f, p);

    int push = stock.optInt("push");
    p.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    if (push > 0) {
      fill(accent);
      rect.set(66, 59, 90, 72);
      c.drawRoundRect(rect, 6.5f, 6.5f, p);
      fill(inkOf(accent));
      centered("+" + push, 78, 68.8f, 8.6f, 22);
    } else {
      fill(withAlpha(ink, .38f));
      tracked(maker(id), 50, 68.4f, 6.4f, 2f, 60);
    }
    c.restore();

    // Frame: a hairline that keeps the tile from bleeding into the card behind it.
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(1);
    fill(withAlpha(WHITE, .09f));
    rect.set(.5f, .5f, WIDTH - .5f, HEIGHT - .5f);
    c.drawRoundRect(rect, RADIUS - .4f, RADIUS - .4f, p);
    p.setStyle(Paint.Style.FILL);

    fill(withAlpha(ink, .58f));
    tracked(stock.optString("family", "FILM"), 50, 24, 7.6f, 2.4f, 74);

    fill(ink);
    p.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    centered(stock.optString("mark", "35"), 50, 49, 27, 78);
  }

  /** Shrinks horizontally rather than wrapping, then centres on {@code centerX}. */
  private void centered(String value, float centerX, float baseline, float size, float maxWidth) {
    p.setTextSize(size);
    p.setTextScaleX(1);
    float width = p.measureText(value);
    if (width > maxWidth) p.setTextScaleX(maxWidth / width);
    p.setTextAlign(Paint.Align.CENTER);
    c.drawText(value, centerX, baseline, p);
    p.setTextScaleX(1);
  }

  /** Same, with per-character tracking so short family names keep their wide-set label look. */
  private void tracked(
      String value, float centerX, float baseline, float size, float tracking, float maxWidth) {
    p.setTextSize(size);
    p.setTextScaleX(1);
    int n = value.length();
    if (n == 0) return;
    float[] advance = new float[n];
    float total = 0;
    for (int i = 0; i < n; i++) {
      advance[i] = p.measureText(value, i, i + 1);
      total += advance[i];
    }
    total += tracking * (n - 1);
    float scale = total > maxWidth ? maxWidth / total : 1;
    p.setTextScaleX(scale);
    p.setTextAlign(Paint.Align.LEFT);
    float x = centerX - total * scale / 2;
    for (int i = 0; i < n; i++) {
      c.drawText(value, i, i + 1, x, baseline, p);
      x += (advance[i] + tracking) * scale;
    }
    p.setTextScaleX(1);
  }
}
