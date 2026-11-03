// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.*;
import org.json.JSONObject;

/** Original vector labels matching the flat, vertical type layout of the authorized label set. */
final class FilmLabelArtwork {
  private FilmLabelArtwork() {}

  /** Independent typographic labels for public builds; no reserved PNG pixels are used. */
  static Bitmap createPublic(String id, String name) {
    Bitmap image = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(image);
    canvas.scale(4, 4);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    boolean fuji = id.startsWith("fujifilm");
    paint.setColor(fuji ? 0xffdce7de : 0xffe8dfcb);
    canvas.drawRoundRect(new RectF(15, 6, 105, 114), 8, 8, paint);
    paint.setColor(fuji ? 0xff275341 : 0xffba5b20);
    canvas.drawRoundRect(new RectF(20, 11, 100, 32), 3, 3, paint);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    paint.setTextSize(10);
    paint.setColor(Color.WHITE);
    canvas.drawText(fuji ? "FUJIFILM" : "KODAK", 60, 25, paint);
    String model = name.replaceFirst("^(Kodak|Fujifilm)\\s+", "").replaceFirst("\\s*\\(Push.*", "");
    java.util.regex.Matcher numbers =
        java.util.regex.Pattern.compile("[0-9]+[a-zA-Z]?").matcher(model);
    String speed = "";
    while (numbers.find())
      if (Integer.parseInt(numbers.group().replaceAll("[^0-9]", "")) >= 50) speed = numbers.group();
    String family = model.replace(speed, "").trim().toUpperCase(java.util.Locale.ROOT);
    paint.setColor(0xff202027);
    paint.setTextSize(12);
    paint.setTextScaleX(Math.min(1, 78 / Math.max(1, paint.measureText(family))));
    canvas.drawText(family, 60, 56, paint);
    paint.setTextScaleX(1);
    paint.setTextSize(32);
    canvas.drawText(speed.toUpperCase(java.util.Locale.ROOT), 60, 91, paint);
    paint.setTextSize(7);
    canvas.drawText("SPEKTRA / FILM", 60, 106, paint);
    return image;
  }

  static Bitmap create(JSONObject drawing) {
    // Logical 120px labels share the same placement as the original PNGs.
    // Render at 4x so the new type stays sharp on high-density phone displays.
    Bitmap image = Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(image);
    canvas.scale(4, 4);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    int background = Color.parseColor(drawing.optString("background", "#F4F0E4"));
    int ink = Color.parseColor(drawing.optString("ink", "#171C18"));
    int spine = Color.parseColor(drawing.optString("spine", "#285C45"));
    int separator = Color.parseColor(drawing.optString("separator", "#D9BF77"));
    int railInk = Color.parseColor(drawing.optString("rail_ink", "#FFFFFF"));
    Path outline = new Path();
    outline.addRoundRect(new RectF(15, 6, 105, 114), 8, 8, Path.Direction.CW);
    canvas.save();
    canvas.clipPath(outline);
    paint.setColor(background);
    canvas.drawRect(15, 6, 105, 114, paint);
    paint.setColor(spine);
    canvas.drawRect(15, 6, 39, 114, paint);
    paint.setColor(separator);
    canvas.drawRect(39, 6, 45, 114, paint);
    paint.setColor(railInk);
    canvas.drawRoundRect(24, 15, 33, 29, 1, 1, paint);
    canvas.drawRoundRect(24, 95, 33, 107, 1, 1, paint);
    paint.setTypeface(Typeface.create("sans-serif-condensed", Typeface.BOLD));
    paint.setTextAlign(Paint.Align.CENTER);
    verticalText(canvas, paint, drawing.optString("family", "FILM"), 28, 62, 9, 56);
    paint.setColor(ink);
    verticalText(canvas, paint, drawing.optString("display", "FILM"), 75, 60, 58, 90);
    canvas.restore();
    return image;
  }

  private static void verticalText(
      Canvas canvas,
      Paint paint,
      String text,
      float centerX,
      float centerY,
      float size,
      float maxLength) {
    paint.setTextScaleX(1);
    paint.setTextSize(size);
    float width = paint.measureText(text);
    if (width > maxLength) paint.setTextScaleX(maxLength / width);
    Paint.FontMetrics metrics = paint.getFontMetrics();
    canvas.save();
    canvas.translate(centerX, centerY);
    canvas.rotate(-90);
    canvas.drawText(text, 0, -(metrics.ascent + metrics.descent) / 2, paint);
    canvas.restore();
    paint.setTextScaleX(1);
  }
}
