// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import org.json.JSONObject;

/** Displays the same project-drawn cartridges in every build. */
final class FilmBadge extends View {
  private static final HashMap<String, Bitmap> cache = new HashMap<>();
  private static JSONObject recipes;
  private final Bitmap artwork;
  private final boolean selected;
  private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final RectF bounds =
      new RectF(0, 0, FilmCanisterArtwork.WIDTH, FilmCanisterArtwork.HEIGHT);

  public FilmBadge(Context context) {
    this(context, "kodak_portra_400", "Kodak Portra 400", false);
  }

  FilmBadge(Context context, String id, String name, boolean selected) {
    super(context);
    this.selected = selected;
    if (recipes == null) {
      try (InputStream in = context.getAssets().open("film_icons/manifest.json");
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        recipes =
            new JSONObject(out.toString(StandardCharsets.UTF_8.name())).getJSONObject("stocks");
      } catch (java.io.IOException | org.json.JSONException error) {
        throw new IllegalStateException("Cannot read the bundled film icon definitions", error);
      }
    }
    JSONObject stock = recipes.optJSONObject(id);
    if (stock == null)
      throw new IllegalArgumentException("No film icon for " + id + " (" + name + ")");
    artwork = cache.computeIfAbsent(id, ignored -> FilmCanisterArtwork.render(id, stock));
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float scale = Math.min(getWidth() / bounds.width(), getHeight() / bounds.height());
    canvas.save();
    canvas.translate(
        (getWidth() - bounds.width() * scale) / 2, (getHeight() - bounds.height() * scale) / 2);
    canvas.scale(scale, scale);
    p.setColor(Color.WHITE);
    canvas.drawBitmap(artwork, null, bounds, p);
    if (selected) {
      p.setColor(MainActivity.ACCENT);
      canvas.drawCircle(85, 10, 7, p);
      p.setColor(0xff182017);
      p.setTextAlign(Paint.Align.CENTER);
      p.setTypeface(Typeface.DEFAULT_BOLD);
      p.setTextSize(9);
      canvas.drawText("✓", 85, 13.5f, p);
    }
    canvas.restore();
  }
}
