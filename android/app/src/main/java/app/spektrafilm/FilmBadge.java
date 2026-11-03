// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import org.json.JSONObject;

/** Authorized labels and original matching vector labels, all in the same native canister. */
final class FilmBadge extends View {
  private final Paint p = new Paint(3);
  private final String id, name;
  private Bitmap custom;
  private boolean labelArtwork;
  private static JSONObject artworkManifest;
  private final RectF customBounds = new RectF(18, 0, 82, 65);
  private final Shader bodyGradient =
      new LinearGradient(
          29,
          0,
          73,
          0,
          new int[] {0xff101612, 0xff5a625a, 0xff161c17, 0xff424a42, 0xff0d110d},
          new float[] {0, .16f, .36f, .79f, 1},
          Shader.TileMode.CLAMP);
  private final Shader labelGloss =
      new LinearGradient(
          28,
          0,
          75,
          0,
          new int[] {0x44ffffff, 0x00ffffff, 0x33000000},
          new float[] {0, .3f, 1},
          Shader.TileMode.CLAMP);
  private final boolean selected;
  private static final HashMap<String, Bitmap> customArt = new HashMap<>();
  private static Boolean publicLabels;

  static boolean usesPublicLabels(Context context) {
    if (publicLabels == null) {
      try {
        publicLabels = context.getAssets().list("film_icons/grlife").length == 0;
      } catch (java.io.IOException error) {
        publicLabels = true;
      }
    }
    return publicLabels;
  }

  public FilmBadge(Context c) {
    this(c, "kodak_portra_400", "Kodak Portra 400", false);
  }

  FilmBadge(Context c, String id, String name, boolean selected) {
    super(c);
    this.id = id;
    this.name = name;
    this.selected = selected;
    if (usesPublicLabels(c)) {
      String key = "public:" + id;
      if (!customArt.containsKey(key)) customArt.put(key, FilmLabelArtwork.createPublic(id, name));
      custom = customArt.get(key);
      labelArtwork = true;
      return;
    }
    String asset = "film_icons/" + id + ".png";
    if (artworkManifest == null) {
      try (InputStream in = c.getAssets().open("film_icons/manifest.json");
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
        artworkManifest = new JSONObject(out.toString("UTF-8"));
      } catch (Exception ex) {
        artworkManifest = new JSONObject();
        android.util.Log.w("SpektraAndroid", "Film artwork manifest unavailable", ex);
      }
    }
    JSONObject stocks = artworkManifest.optJSONObject("stocks");
    JSONObject artworks = artworkManifest.optJSONObject("artworks");
    String artId = stocks == null ? "" : stocks.optString(id);
    JSONObject artwork = artworks == null ? null : artworks.optJSONObject(artId);
    if (artwork != null && "vector_label".equals(artwork.optString("mode"))) {
      String key = "vector:" + artId;
      if (!customArt.containsKey(key))
        customArt.put(key, FilmLabelArtwork.create(artwork.optJSONObject("drawing")));
      custom = customArt.get(key);
      labelArtwork = true;
      return;
    }
    if (artwork != null) {
      asset = "film_icons/" + artwork.optString("asset");
      labelArtwork = "label".equals(artwork.optString("mode"));
    }
    if (!customArt.containsKey(asset)) {
      Bitmap bitmap = null;
      try (InputStream in = c.getAssets().open(asset)) {
        bitmap = BitmapFactory.decodeStream(in);
      } catch (java.io.IOException ignored) {
      }
      customArt.put(asset, bitmap);
    }
    custom = customArt.get(asset);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    float scale = Math.min(getWidth() / 100f, getHeight() / 70f);
    canvas.save();
    canvas.translate(getWidth() / 2f - 50 * scale, getHeight() / 2f - 35 * scale);
    canvas.scale(scale, scale);
    int accent =
        id.contains("fuji")
            ? 0xffe4e7db
            : id.contains("gold") || id.contains("ultra")
                ? 0xffe5bd42
                : id.contains("vision")
                    ? 0xff4d626d
                    : id.contains("portra")
                        ? 0xffd8c9a4
                        : id.contains("ektar") ? 0xffa13f38 : 0xff5a7d9c;
    if (custom != null) {
      if (labelArtwork) drawLabelCanister(canvas);
      else canvas.drawBitmap(custom, null, customBounds, p);
      drawMarkers(canvas);
      canvas.restore();
      return;
    }
    p.setColor(0x55000000);
    canvas.drawOval(22, 59, 81, 67, p);
    p.setShader(bodyGradient);
    canvas.drawRoundRect(28, 8, 75, 60, 6, 6, p);
    p.setShader(null);
    p.setColor(0xff101612);
    canvas.drawOval(31, 3, 72, 14, p);
    p.setColor(0xff5b655c);
    canvas.drawOval(39, 3, 64, 8, p);
    p.setColor(0xff121813);
    canvas.drawRoundRect(44, 0, 59, 6, 3, 3, p);
    p.setColor(accent);
    canvas.drawRect(29, 17, 74, 53, p);
    int labelInk =
        id.contains("vision")
                || id.contains("ektar")
                || id.contains("ektachrome")
                || id.contains("provia")
            ? 0xfff0eee1
            : 0xff25241d;
    int band =
        id.contains("fuji")
            ? 0xff327251
            : id.contains("gold") ? 0xffb13e31 : id.contains("portra") ? 0xff817052 : 0xffb3a56b;
    p.setColor(band);
    canvas.drawRect(29, 17, 74, 20, p);
    canvas.drawRect(29, 49, 74, 53, p);
    p.setColor(labelInk);
    p.setTextAlign(Paint.Align.CENTER);
    p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    p.setTextSize(6.3f);
    canvas.drawText(id.startsWith("fuji") ? "FUJIFILM" : "KODAK", 51.5f, 28, p);
    String iso = "";
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("[0-9]+[a-zA-Z]?").matcher(name);
    while (matcher.find()) {
      String match = matcher.group();
      if (Integer.parseInt(match.replaceAll("[^0-9]", "")) >= 50) {
        iso = match.toUpperCase(java.util.Locale.ROOT);
        break;
      }
    }
    String family =
        id.contains("portra")
            ? "PORTRA"
            : id.contains("gold")
                ? "GOLD"
                : id.contains("vision")
                    ? "VISION3"
                    : id.contains("ektar")
                        ? "EKTAR"
                        : id.contains("ultramax")
                            ? "ULTRAMAX"
                            : id.contains("provia")
                                ? "PROVIA"
                                : id.contains("velvia")
                                    ? "VELVIA"
                                    : id.contains("kodachrome")
                                        ? "KODACHROME"
                                        : id.contains("ektachrome")
                                            ? "EKTACHROME"
                                            : id.contains("xtra")
                                                ? "X-TRA"
                                                : id.contains("pro_400")
                                                    ? "PRO"
                                                    : id.contains("c200") ? "COLOR" : "FILM";
    p.setTextSize(family.length() > 9 ? 4.2f : 5.8f);
    canvas.drawText(family, 51.5f, 35, p);
    p.setTypeface(Typeface.create("serif", Typeface.BOLD));
    p.setTextSize(13);
    canvas.drawText(iso.isEmpty() ? "FILM" : iso, 51.5f, 46, p);
    p.setShader(labelGloss);
    canvas.drawRect(29, 17, 74, 53, p);
    p.setShader(null);
    p.setColor(0xff737c70);
    canvas.drawOval(31, 55, 72, 63, p);
    p.setColor(0xff1b241d);
    canvas.drawOval(33, 56, 70, 61, p);
    drawMarkers(canvas);
    canvas.restore();
  }

  private final RectF labelBounds = new RectF(20, 7, 80, 67);
  private final RectF pushBounds = new RectF(74, 41, 98, 56);

  private void drawLabelCanister(Canvas canvas) {
    // Keep the original square PNG and its transparent padding; it is a label,
    // so the spool, metallic rims and film leader are native vector elements.
    p.setColor(0x66000000);
    canvas.drawOval(23, 64, 83, 70, p);
    p.setColor(0xff6d5d47);
    canvas.drawRoundRect(70, 26, selected ? 91 : 85, 57, 2, 2, p);
    p.setColor(0xff222923);
    canvas.drawRect(selected ? 84 : 78, 30, selected ? 88 : 82, 35, p);
    canvas.drawRect(selected ? 84 : 78, 48, selected ? 88 : 82, 53, p);
    p.setShader(bodyGradient);
    canvas.drawRoundRect(27, 10, 73, 65, 3, 3, p);
    p.setShader(null);
    p.setColor(Color.WHITE);
    canvas.drawBitmap(custom, null, labelBounds, p);
    p.setShader(bodyGradient);
    canvas.drawRoundRect(25, 8, 75, 13, 2, 2, p);
    canvas.drawRoundRect(25, 63, 75, 68, 2, 2, p);
    canvas.drawRoundRect(42, 1, 58, 9, 2, 2, p);
    p.setShader(null);
    p.setColor(0xff737c70);
    canvas.drawRect(28, 9, 72, 10, p);
    canvas.drawRect(28, 64, 72, 65, p);
  }

  private void drawMarkers(Canvas canvas) {
    p.setTextAlign(Paint.Align.CENTER);
    p.setTypeface(Typeface.DEFAULT_BOLD);
    if (id.endsWith("_push1") || id.endsWith("_push2")) {
      p.setColor(MainActivity.ACCENT);
      canvas.drawRoundRect(pushBounds, 3, 3, p);
      p.setColor(0xff182017);
      p.setTextSize(10);
      canvas.drawText(id.endsWith("_push1") ? "+1" : "+2", 86, 52, p);
    }
    if (selected) {
      p.setColor(MainActivity.ACCENT);
      canvas.drawCircle(85, 10, 7, p);
      p.setColor(0xff182017);
      p.setTextSize(9);
      p.setTypeface(Typeface.DEFAULT_BOLD);
      canvas.drawText("✓", 85, 13.5f, p);
    }
  }
}
