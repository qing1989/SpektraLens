// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

/**
 * Precision miniature vector preview for each {@link FrameStyle}, mirroring the real physical
 * film-base geometry, perforations, notches, bevels, and rebate markings rendered by
 * {@code film_border.cpp}. Pure Canvas drawing with zero bitmap allocations.
 */
final class FrameStyleGlyphDrawable extends Drawable {
  private static final float VW = 56f;
  private static final float VH = 38f;
  private static final float[] SPROCKET_X = {7f, 16.5f, 26f, 35.5f, 45f};

  private final FrameStyle style;
  private final boolean lightTheme;
  private final boolean active;

  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint winPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint hillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint orbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

  private final RectF tmpRect = new RectF();
  private final Path tmpPath = new Path();
  private final Path hillPath = new Path();
  private final Path clipPath = new Path();

  private float scale = 1f;
  private float ox = 0f;
  private float oy = 0f;
  private Shader baseShader;
  private Shader winShader;

  FrameStyleGlyphDrawable(FrameStyle style, boolean lightTheme, boolean active) {
    this.style = style;
    this.lightTheme = lightTheme;
    this.active = active;
    fillPaint.setStyle(Paint.Style.FILL);
    strokePaint.setStyle(Paint.Style.STROKE);
    strokePaint.setStrokeCap(Paint.Cap.ROUND);
    strokePaint.setStrokeJoin(Paint.Join.ROUND);
    shadowPaint.setStyle(Paint.Style.FILL);
    winPaint.setStyle(Paint.Style.FILL);
    hillPaint.setStyle(Paint.Style.FILL);
    orbPaint.setStyle(Paint.Style.FILL);
    markPaint.setStyle(Paint.Style.FILL);
  }

  @Override
  protected void onBoundsChange(Rect bounds) {
    super.onBoundsChange(bounds);
    float bw = Math.max(1, bounds.width());
    float bh = Math.max(1, bounds.height());
    scale = Math.min(bw / VW, bh / VH);
    ox = bounds.left + (bw - VW * scale) * .5f;
    oy = bounds.top + (bh - VH * scale) * .5f;
    rebuildShaders();
  }

  private void rebuildShaders() {
    baseShader = null;
    if (style == FrameStyle.BLACK_135) {
      baseShader =
          new LinearGradient(
              ox, sy(2.5f), ox, sy(35.5f), 0xff1a1817, 0xff0f0e0d, Shader.TileMode.CLAMP);
    } else if (style == FrameStyle.WARM_135) {
      baseShader =
          new LinearGradient(
              ox, sy(2.5f), ox, sy(35.5f), 0xff5b3722, 0xff3b2113, Shader.TileMode.CLAMP);
    } else if (style == FrameStyle.SILVER_135) {
      baseShader =
          new LinearGradient(
              ox, sy(2.5f), ox, sy(35.5f), 0xdb3f4a56, 0xdb2b333c, Shader.TileMode.CLAMP);
    } else if (style == FrameStyle.SLIDE) {
      baseShader =
          new LinearGradient(
              sx(9f), sy(2f), sx(47f), sy(36f), 0xffeeeae0, 0xffddd7ca, Shader.TileMode.CLAMP);
    }
    boolean bw = style == FrameStyle.SILVER_135;
    int[] winColors =
        bw ? new int[] {0xff2a2d31, 0xff696d73, 0xffa6abb2}
           : new int[] {0xff3e4956, 0xff627180, 0xff8a96a1};
    winShader =
        new LinearGradient(
            sx(6f),
            sy(5f),
            sx(50f),
            sy(33f),
            winColors,
            new float[] {0f, .52f, 1f},
            Shader.TileMode.CLAMP);
  }

  private float sx(float x) {
    return ox + x * scale;
  }

  private float sy(float y) {
    return oy + y * scale;
  }

  private float su(float u) {
    return u * scale;
  }

  @Override
  public void draw(Canvas canvas) {
    if (scale <= 0f) return;
    switch (style) {
      case NONE -> drawNone(canvas);
      case BLACK_135 ->
          drawStrip135(
              canvas, lightTheme ? 0xff32302e : 0xff565358, 0xfff2a652, false);
      case WARM_135 ->
          drawStrip135(
              canvas, lightTheme ? 0xff7a4c32 : 0xff966040, 0xfff7b866, false);
      case SILVER_135 ->
          drawStrip135(
              canvas, lightTheme ? 0xff616e7c : 0xff7b8a9b, 0xffdce6f2, true);
      case SLIDE -> drawSlideMount(canvas);
      case SHEET -> drawSheet4x5(canvas);
      case MEDIUM_120 -> drawMedium120(canvas);
      case WHITE -> drawCleanWhite(canvas);
      case INSTANT -> drawInstant(canvas);
      case FILM_CARD_WHITE -> drawFilmCardWhite(canvas);
      case FILM_CARD_BLACK -> drawFilmCardBlack(canvas);
    }
  }

  private void drawSoftShadow(Canvas canvas, float l, float t, float r, float b, float rx) {
    shadowPaint.setColor(lightTheme ? 0x16000000 : 0x42000000);
    tmpRect.set(sx(l - .3f), sy(t + .9f), sx(r + .3f), sy(b + 1.6f));
    canvas.drawRoundRect(tmpRect, su(rx + .4f), su(rx + .4f), shadowPaint);
    shadowPaint.setColor(lightTheme ? 0x14000000 : 0x36000000);
    tmpRect.set(sx(l), sy(t + .5f), sx(r), sy(b + 1.0f));
    canvas.drawRoundRect(tmpRect, su(rx), su(rx), shadowPaint);
  }

  private void drawOpticalWindow(
      Canvas canvas, float x, float y, float w, float h, float rx, boolean bw) {
    clipPath.reset();
    tmpRect.set(sx(x), sy(y), sx(x + w), sy(y + h));
    clipPath.addRoundRect(tmpRect, su(rx), su(rx), Path.Direction.CW);
    drawOpticalWindowClipped(canvas, x, y, w, h, bw);
    strokePaint.setShader(null);
    strokePaint.setColor(0x47000000);
    strokePaint.setStrokeWidth(Math.max(1f, su(.5f)));
    tmpRect.set(sx(x + .25f), sy(y + .25f), sx(x + w - .25f), sy(y + h - .25f));
    canvas.drawRoundRect(tmpRect, su(rx), su(rx), strokePaint);
  }

  private void drawOpticalWindowClipped(
      Canvas canvas, float x, float y, float w, float h, boolean bw) {
    int save = canvas.save();
    canvas.clipPath(clipPath);
    winPaint.setShader(winShader);
    tmpRect.set(sx(x - 1f), sy(y - 1f), sx(x + w + 1f), sy(y + h + 1f));
    canvas.drawRect(tmpRect, winPaint);
    orbPaint.setColor(bw ? 0x29ffffff : 0x2efff4e6);
    canvas.drawCircle(
        sx(x + w * .72f), sy(y + h * .30f), su(Math.min(w, h) * .16f), orbPaint);
    hillPaint.setColor(bw ? 0x75141619 : 0x6b1a212a);
    hillPath.reset();
    hillPath.moveTo(sx(x - 1f), sy(y + h + 1f));
    hillPath.lineTo(sx(x + w * .36f), sy(y + h * .56f));
    hillPath.lineTo(sx(x + w * .62f), sy(y + h * .74f));
    hillPath.lineTo(sx(x + w * .85f), sy(y + h * .62f));
    hillPath.lineTo(sx(x + w + 1f), sy(y + h + 1f));
    hillPath.close();
    canvas.drawPath(hillPath, hillPaint);
    canvas.restoreToCount(save);
  }

  private void drawNone(Canvas canvas) {
    fillPaint.setShader(null);
    fillPaint.setColor(lightTheme ? 0xadffffff : 0x0cffffff);
    tmpRect.set(sx(6f), sy(4f), sx(50f), sy(34f));
    canvas.drawRoundRect(tmpRect, su(2.5f), su(2.5f), fillPaint);

    int brkColor =
        active ? (lightTheme ? 0xffc83422 : 0xfff0523d) : (lightTheme ? 0xff7a7a85 : 0xff8e8e98);
    strokePaint.setShader(null);
    strokePaint.setColor(brkColor);
    strokePaint.setStrokeWidth(Math.max(1.5f, su(1.5f)));

    // Four Leica-style viewfinder corner brackets
    drawCorner(canvas, 6f, 11f, 6f, 4f, 13f, 4f);
    drawCorner(canvas, 43f, 4f, 50f, 4f, 50f, 11f);
    drawCorner(canvas, 6f, 27f, 6f, 34f, 13f, 34f);
    drawCorner(canvas, 43f, 34f, 50f, 34f, 50f, 27f);

    // Subtle rangefinder center crosshair
    int dotColor = active ? brkColor : (lightTheme ? 0xffa5a5b0 : 0xff585862);
    strokePaint.setColor((dotColor & 0x00ffffff) | 0xa6000000);
    strokePaint.setStrokeWidth(Math.max(1f, su(.95f)));
    canvas.drawLine(sx(25.5f), sy(19f), sx(30.5f), sy(19f), strokePaint);
    canvas.drawLine(sx(28f), sy(16.5f), sx(28f), sy(21.5f), strokePaint);
  }

  private void drawCorner(
      Canvas canvas, float x0, float y0, float cx, float cy, float x1, float y1) {
    tmpPath.reset();
    tmpPath.moveTo(sx(x0), sy(y0));
    tmpPath.lineTo(sx(cx), sy(cy));
    tmpPath.lineTo(sx(x1), sy(y1));
    canvas.drawPath(tmpPath, strokePaint);
  }

  private void drawStrip135(Canvas canvas, int borderColor, int markColor, boolean bw) {
    drawSoftShadow(canvas, 2f, 2.5f, 54f, 35.5f, 2.4f);

    // EVEN_ODD path genuinely punches transparent sprocket perforations through the film strip
    tmpPath.reset();
    tmpPath.setFillType(Path.FillType.EVEN_ODD);
    tmpRect.set(sx(2f), sy(2.5f), sx(54f), sy(35.5f));
    tmpPath.addRoundRect(tmpRect, su(2.4f), su(2.4f), Path.Direction.CW);
    for (float hx : SPROCKET_X) {
      tmpRect.set(sx(hx), sy(4.8f), sx(hx + 4.2f), sy(7.9f));
      tmpPath.addRoundRect(tmpRect, su(.9f), su(.9f), Path.Direction.CW);
      tmpRect.set(sx(hx), sy(30.1f), sx(hx + 4.2f), sy(33.2f));
      tmpPath.addRoundRect(tmpRect, su(.9f), su(.9f), Path.Direction.CW);
    }
    fillPaint.setShader(baseShader);
    fillPaint.setColor(Color.WHITE);
    canvas.drawPath(tmpPath, fillPaint);

    // Outer rim stroke
    strokePaint.setShader(null);
    strokePaint.setColor(borderColor);
    strokePaint.setStrokeWidth(Math.max(1f, su(lightTheme ? .7f : .85f)));
    tmpRect.set(sx(2f), sy(2.5f), sx(54f), sy(35.5f));
    canvas.drawRoundRect(tmpRect, su(2.4f), su(2.4f), strokePaint);

    // Subtle bright bevel rim around every sprocket hole
    strokePaint.setColor(lightTheme ? 0x38ffffff : 0x5cffffff);
    strokePaint.setStrokeWidth(Math.max(1f, su(.5f)));
    for (float hx : SPROCKET_X) {
      tmpRect.set(sx(hx), sy(4.8f), sx(hx + 4.2f), sy(7.9f));
      canvas.drawRoundRect(tmpRect, su(.9f), su(.9f), strokePaint);
      tmpRect.set(sx(hx), sy(30.1f), sx(hx + 4.2f), sy(33.2f));
      canvas.drawRoundRect(tmpRect, su(.9f), su(.9f), strokePaint);
    }

    // Top DX / frame bars and bottom edge imprint
    drawBar(canvas, 7f, 9.1f, 5.5f, .85f, markColor, .88f);
    drawBar(canvas, 14f, 9.1f, 2.2f, .85f, markColor, .65f);
    drawBar(canvas, 33f, 9.1f, 4.5f, .85f, markColor, .85f);
    drawBar(canvas, 7f, 28.0f, 11f, .85f, markColor, .90f);
    drawBar(canvas, 44f, 28.0f, 5f, .85f, markColor, .85f);

    drawOpticalWindow(canvas, 6.5f, 10.9f, 43f, 16.2f, .7f, bw);
  }

  private void drawSlideMount(Canvas canvas) {
    drawSoftShadow(canvas, 9f, 2f, 47f, 36f, 3.2f);

    fillPaint.setShader(baseShader);
    fillPaint.setColor(Color.WHITE);
    tmpRect.set(sx(9f), sy(2f), sx(47f), sy(36f));
    canvas.drawRoundRect(tmpRect, su(3.2f), su(3.2f), fillPaint);

    strokePaint.setShader(null);
    strokePaint.setColor(lightTheme ? 0xffc4bcb0 : 0xff968e82);
    strokePaint.setStrokeWidth(Math.max(1f, su(.75f)));
    canvas.drawRoundRect(tmpRect, su(3.2f), su(3.2f), strokePaint);

    // Molded outer lip groove
    strokePaint.setColor(0x386e6458);
    strokePaint.setStrokeWidth(Math.max(1f, su(.65f)));
    tmpRect.set(sx(11.5f), sy(4.5f), sx(44.5f), sy(33.5f));
    canvas.drawRoundRect(tmpRect, su(1.8f), su(1.8f), strokePaint);

    // Recessed window bevel (shadow top-left, bright catchlight bottom-right)
    fillPaint.setShader(null);
    fillPaint.setColor(0xffcfc8ba);
    tmpRect.set(sx(14.5f), sy(10.2f), sx(41.5f), sy(27.8f));
    canvas.drawRoundRect(tmpRect, su(1.3f), su(1.3f), fillPaint);

    strokePaint.setStrokeWidth(Math.max(1f, su(.8f)));
    strokePaint.setColor(0xb8ffffff);
    canvas.drawLine(sx(14.5f), sy(27.8f), sx(41.5f), sy(27.8f), strokePaint);
    canvas.drawLine(sx(41.5f), sy(27.8f), sx(41.5f), sy(10.2f), strokePaint);
    strokePaint.setColor(0x575a5246);
    canvas.drawLine(sx(14.5f), sy(27.8f), sx(14.5f), sy(10.2f), strokePaint);
    canvas.drawLine(sx(14.5f), sy(10.2f), sx(41.5f), sy(10.2f), strokePaint);

    drawOpticalWindow(canvas, 15.6f, 11.3f, 24.8f, 15.4f, .6f, false);

    // Archival stamp bar, frame number, signature red alignment dot, and E-6 deboss bar
    drawBar(canvas, 14.6f, 6.8f, 8.5f, 1.1f, 0xffa83b2b, .82f);
    drawBar(canvas, 37.2f, 6.8f, 4.2f, 1.1f, 0xff7d7468, .70f);
    markPaint.setColor(0xffb53827);
    canvas.drawCircle(sx(15.7f), sy(30.8f), su(1.45f), markPaint);
    drawBar(canvas, 31.5f, 30.3f, 9.8f, 1.1f, 0xff7d7468, .62f);
  }

  private void drawSheet4x5(Canvas canvas) {
    drawSoftShadow(canvas, 3.5f, 2.2f, 52.5f, 35.8f, 2.0f);

    // Outer sheet contour with iconic top-right V-U-V code notches + EVEN_ODD top-left pinhole
    tmpPath.reset();
    tmpPath.setFillType(Path.FillType.EVEN_ODD);
    tmpPath.moveTo(sx(5.5f), sy(2.2f));
    tmpPath.lineTo(sx(37.1f), sy(2.2f));
    // Left U-notch
    tmpPath.lineTo(sx(37.1f), sy(4.3f));
    tmpPath.quadTo(sx(38.4f), sy(5.6f), sx(39.7f), sy(4.3f));
    tmpPath.lineTo(sx(39.7f), sy(2.2f));
    // Center V-notch
    tmpPath.lineTo(sx(41.4f), sy(2.2f));
    tmpPath.lineTo(sx(43.0f), sy(5.3f));
    tmpPath.lineTo(sx(44.6f), sy(2.2f));
    // Right U-notch
    tmpPath.lineTo(sx(46.2f), sy(2.2f));
    tmpPath.lineTo(sx(46.2f), sy(4.3f));
    tmpPath.quadTo(sx(47.4f), sy(5.6f), sx(48.6f), sy(4.3f));
    tmpPath.lineTo(sx(48.6f), sy(2.2f));
    tmpPath.lineTo(sx(50.5f), sy(2.2f));
    tmpPath.quadTo(sx(52.5f), sy(2.2f), sx(52.5f), sy(4.2f));
    tmpPath.lineTo(sx(52.5f), sy(33.8f));
    tmpPath.quadTo(sx(52.5f), sy(35.8f), sx(50.5f), sy(35.8f));
    tmpPath.lineTo(sx(5.5f), sy(35.8f));
    tmpPath.quadTo(sx(3.5f), sy(35.8f), sx(3.5f), sy(33.8f));
    tmpPath.lineTo(sx(3.5f), sy(4.2f));
    tmpPath.quadTo(sx(3.5f), sy(2.2f), sx(5.5f), sy(2.2f));
    tmpPath.close();

    // Registration pinhole punched out cleanly at top-left
    tmpPath.addCircle(sx(7.4f), sy(5.0f), su(1.15f), Path.Direction.CW);

    fillPaint.setShader(null);
    fillPaint.setColor(0xff131315);
    canvas.drawPath(tmpPath, fillPaint);

    // Crisp rim outline around notched outer contour & pinhole
    strokePaint.setShader(null);
    strokePaint.setColor(lightTheme ? 0xff323238 : 0xff585862);
    strokePaint.setStrokeWidth(Math.max(1f, su(lightTheme ? .7f : .85f)));
    canvas.drawPath(tmpPath, strokePaint);

    strokePaint.setColor(lightTheme ? 0x52ffffff : 0x73ffffff);
    strokePaint.setStrokeWidth(Math.max(1f, su(.5f)));
    canvas.drawCircle(sx(7.4f), sy(5.0f), su(1.15f), strokePaint);

    // Wide 4x5 inner gate + cool silver bottom rebate imprint
    drawOpticalWindow(canvas, 7.2f, 7.6f, 41.6f, 23.4f, .7f, false);
    drawBar(canvas, 7.8f, 33.0f, 14.5f, .95f, 0xffd9e3f0, .78f);
    drawBar(canvas, 44.2f, 33.0f, 4.2f, .95f, 0xffd9e3f0, .65f);
  }

  private void drawMedium120(Canvas canvas) {
    drawSoftShadow(canvas, 3.5f, 2.2f, 52.5f, 35.8f, 2.0f);

    fillPaint.setShader(null);
    fillPaint.setColor(0xff1b191b);
    tmpRect.set(sx(3.5f), sy(2.2f), sx(52.5f), sy(35.8f));
    canvas.drawRoundRect(tmpRect, su(2.0f), su(2.0f), fillPaint);

    strokePaint.setShader(null);
    strokePaint.setColor(lightTheme ? 0xff3b373a : 0xff5b565c);
    strokePaint.setStrokeWidth(Math.max(1f, su(lightTheme ? .7f : .85f)));
    canvas.drawRoundRect(tmpRect, su(2.0f), su(2.0f), strokePaint);

    // Olive-gold 120 top & bottom edge markings (smooth rails, NO sprocket holes)
    int olive = 0xffc7ad78;
    drawBar(canvas, 8.5f, 5.1f, 4.2f, 1.0f, olive, .90f);
    drawBar(canvas, 21f, 5.2f, 2.2f, .8f, olive, .55f);
    drawBar(canvas, 25f, 5.2f, 1.1f, .8f, olive, .55f);
    drawBar(canvas, 28f, 5.2f, 2.2f, .8f, olive, .55f);
    drawBar(canvas, 32f, 5.2f, 1.1f, .8f, olive, .55f);
    drawBar(canvas, 42.5f, 5.1f, 5.0f, 1.0f, olive, .85f);
    drawBar(canvas, 8.5f, 32.0f, 13.5f, 1.0f, olive, .85f);
    drawBar(canvas, 42.5f, 32.0f, 5.0f, 1.0f, olive, .82f);

    // Inner photo gate with twin Hasselblad triangular V-notches on the left edge
    clipPath.reset();
    clipPath.moveTo(sx(8.5f), sy(8.8f));
    clipPath.lineTo(sx(47.5f), sy(8.8f));
    clipPath.lineTo(sx(47.5f), sy(29.2f));
    clipPath.lineTo(sx(8.5f), sy(29.2f));
    clipPath.lineTo(sx(8.5f), sy(16.0f));
    clipPath.lineTo(sx(6.3f), sy(14.9f));
    clipPath.lineTo(sx(8.5f), sy(13.8f));
    clipPath.lineTo(sx(8.5f), sy(12.6f));
    clipPath.lineTo(sx(6.3f), sy(11.5f));
    clipPath.lineTo(sx(8.5f), sy(10.4f));
    clipPath.close();
    drawOpticalWindowClipped(canvas, 6.3f, 8.8f, 41.2f, 20.4f, false);

    strokePaint.setColor(0x59000000);
    strokePaint.setStrokeWidth(Math.max(1f, su(.5f)));
    canvas.drawPath(clipPath, strokePaint);
  }

  private void drawCleanWhite(Canvas canvas) {
    drawSoftShadow(canvas, 4f, 2.5f, 52f, 35.5f, 2.0f);
    fillPaint.setShader(null);
    fillPaint.setColor(0xfff8f6f1);
    tmpRect.set(sx(4f), sy(2.5f), sx(52f), sy(35.5f));
    canvas.drawRoundRect(tmpRect, su(2.0f), su(2.0f), fillPaint);

    strokePaint.setShader(null);
    strokePaint.setColor(lightTheme ? 0xffccc7be : 0xff5a5854);
    strokePaint.setStrokeWidth(Math.max(1f, su(.75f)));
    canvas.drawRoundRect(tmpRect, su(2.0f), su(2.0f), strokePaint);

    drawOpticalWindow(canvas, 9f, 7.2f, 38f, 23.6f, .5f, false);
  }

  private void drawInstant(Canvas canvas) {
    drawSoftShadow(canvas, 13f, 1.6f, 43f, 36.4f, 2.0f);
    fillPaint.setShader(null);
    fillPaint.setColor(0xfff7f4ed);
    tmpRect.set(sx(13f), sy(1.6f), sx(43f), sy(36.4f));
    canvas.drawRoundRect(tmpRect, su(2.0f), su(2.0f), fillPaint);

    strokePaint.setShader(null);
    strokePaint.setColor(lightTheme ? 0xffccc6bc : 0xff66635e);
    strokePaint.setStrokeWidth(Math.max(1f, su(.75f)));
    canvas.drawRoundRect(tmpRect, su(2.0f), su(2.0f), strokePaint);

    drawOpticalWindow(canvas, 16.2f, 4.8f, 23.6f, 21.8f, .5f, false);
    drawBar(canvas, 21.5f, 31.0f, 13f, 1.15f, 0xffb0a89c, .65f);
  }

  private void drawMiniCanister(
      Canvas canvas, float x, float y, float w, float h, int topRgb, int botRgb, int capRgb) {
    float nubW = w * .42f;
    float nubH = h * .12f;
    float capH = h * .15f;
    float bodyY0 = y + nubH + capH * .75f;
    float bodyY1 = y + h - capH * .75f;
    float midY = bodyY0 + (bodyY1 - bodyY0) * .56f;

    fillPaint.setShader(null);
    // Top spool nub
    fillPaint.setColor(0xff000000 | capRgb);
    tmpRect.set(sx(x + (w - nubW) * .5f), sy(y), sx(x + (w + nubW) * .5f), sy(y + nubH + capH * .5f));
    canvas.drawRoundRect(tmpRect, su(.35f), su(.35f), fillPaint);

    // Cylindrical two-tone label body
    fillPaint.setColor(0xff000000 | topRgb);
    tmpRect.set(sx(x + .25f), sy(bodyY0), sx(x + w - .25f), sy(midY));
    canvas.drawRect(tmpRect, fillPaint);
    fillPaint.setColor(0xff000000 | botRgb);
    tmpRect.set(sx(x + .25f), sy(midY), sx(x + w - .25f), sy(bodyY1));
    canvas.drawRect(tmpRect, fillPaint);

    // Subtle cylindrical highlight & far-edge shading
    fillPaint.setColor(0x4effffff);
    tmpRect.set(sx(x + w * .22f), sy(bodyY0), sx(x + w * .36f), sy(bodyY1));
    canvas.drawRect(tmpRect, fillPaint);
    fillPaint.setColor(0x2e000000);
    tmpRect.set(sx(x + w * .78f), sy(bodyY0), sx(x + w - .25f), sy(bodyY1));
    canvas.drawRect(tmpRect, fillPaint);

    // Top & bottom metallic rim caps
    fillPaint.setColor(0xff000000 | capRgb);
    tmpRect.set(sx(x), sy(y + nubH * .85f), sx(x + w), sy(y + nubH * .85f + capH));
    canvas.drawRoundRect(tmpRect, su(.45f), su(.45f), fillPaint);
    tmpRect.set(sx(x), sy(y + h - capH), sx(x + w), sy(y + h));
    canvas.drawRoundRect(tmpRect, su(.45f), su(.45f), fillPaint);

    // Crisp rim catchlight
    strokePaint.setShader(null);
    strokePaint.setColor(0x48ffffff);
    strokePaint.setStrokeWidth(Math.max(1f, su(.35f)));
    canvas.drawLine(
        sx(x + .4f), sy(y + nubH * .85f + .25f), sx(x + w - .4f), sy(y + nubH * .85f + .25f),
        strokePaint);
  }

  private void drawFilmCardWhite(Canvas canvas) {
    drawSoftShadow(canvas, 4f, 2.5f, 52f, 35.5f, 2.0f);
    fillPaint.setShader(null);
    fillPaint.setColor(0xfff8f7f4);
    tmpRect.set(sx(4f), sy(2.5f), sx(52f), sy(35.5f));
    canvas.drawRoundRect(tmpRect, su(2.0f), su(2.0f), fillPaint);

    strokePaint.setShader(null);
    strokePaint.setColor(lightTheme ? 0xffccc7be : 0xff5a5854);
    strokePaint.setStrokeWidth(Math.max(1f, su(.75f)));
    canvas.drawRoundRect(tmpRect, su(2.0f), su(2.0f), strokePaint);

    drawOpticalWindow(canvas, 7.2f, 5.2f, 41.6f, 20.8f, .5f, false);
    drawMiniCanister(canvas, 7.4f, 27.6f, 4.3f, 5.8f, 0x2c9e5e, 0xeeebe4, 0x1e1f24);
    drawBar(canvas, 13.3f, 28.6f, 13.8f, 1.45f, 0x202024, .88f);
    drawBar(canvas, 13.3f, 31.3f, 9.6f, .95f, 0x76767e, .72f);
    drawBar(canvas, 41.2f, 28.8f, 7.6f, 1.15f, 0x323238, .78f);
    drawBar(canvas, 43.2f, 31.4f, 5.6f, .80f, 0x989690, .65f);
  }

  private void drawFilmCardBlack(Canvas canvas) {
    drawSoftShadow(canvas, 3.5f, 2.5f, 52.5f, 35.5f, 2.0f);
    fillPaint.setShader(null);
    fillPaint.setColor(0xff131317);
    tmpRect.set(sx(3.5f), sy(2.5f), sx(52.5f), sy(35.5f));
    canvas.drawRoundRect(tmpRect, su(2.0f), su(2.0f), fillPaint);

    strokePaint.setShader(null);
    strokePaint.setColor(lightTheme ? 0xff383842 : 0xff5e5e6a);
    strokePaint.setStrokeWidth(Math.max(1f, su(.8f)));
    canvas.drawRoundRect(tmpRect, su(2.0f), su(2.0f), strokePaint);

    drawOpticalWindow(canvas, 6.2f, 5.2f, 32.0f, 27.6f, .55f, false);

    // Fine vertical divider separating photo and right archive sidebar
    strokePaint.setColor(0xff3a3a44);
    strokePaint.setStrokeWidth(Math.max(1f, su(.55f)));
    canvas.drawLine(sx(40.1f), sy(5.2f), sx(40.1f), sy(32.8f), strokePaint);

    // Top archive vermilion dot + label bar
    markPaint.setColor(0xfff0523d);
    canvas.drawCircle(sx(42.75f), sy(6.45f), su(.75f), markPaint);
    drawBar(canvas, 44.4f, 6.0f, 5.2f, .85f, 0x9c9ea8, .72f);

    // Centered miniature canister & title bar
    drawMiniCanister(canvas, 43.6f, 9.2f, 5.0f, 6.8f, 0xe2643b, 0xeeebe4, 0x2a2b32);
    drawBar(canvas, 42.3f, 17.8f, 7.6f, 1.20f, 0xeeeef2, .90f);

    // Spec divider & 3-row technical spec micro-table
    strokePaint.setColor(0xff3a3a44);
    strokePaint.setStrokeWidth(Math.max(1f, su(.45f)));
    canvas.drawLine(sx(42.0f), sy(20.6f), sx(50.2f), sy(20.6f), strokePaint);
    drawBar(canvas, 42.0f, 22.2f, 2.6f, .80f, 0x767884, .78f);
    drawBar(canvas, 46.6f, 22.2f, 3.6f, .85f, 0xd8dae2, .84f);
    drawBar(canvas, 42.0f, 24.5f, 2.6f, .80f, 0x767884, .78f);
    drawBar(canvas, 46.6f, 24.5f, 3.6f, .85f, 0xd8dae2, .84f);
    drawBar(canvas, 42.0f, 26.8f, 2.6f, .80f, 0x767884, .78f);
    drawBar(canvas, 46.6f, 26.8f, 3.6f, .85f, 0xd8dae2, .84f);

    // Bottom pinned date bar + vermilion tail mark
    drawBar(canvas, 42.0f, 31.2f, 5.6f, 1.00f, 0xd8dae2, .82f);
    drawBar(canvas, 48.7f, 31.2f, 1.5f, 1.00f, 0xf0523d, .92f);
  }

  private void drawBar(
      Canvas canvas, float x, float y, float w, float h, int rgb, float alpha) {
    int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
    markPaint.setColor((rgb & 0x00ffffff) | (a << 24));
    tmpRect.set(sx(x), sy(y), sx(x + w), sy(y + h));
    float r = su(h * .5f);
    canvas.drawRoundRect(tmpRect, r, r, markPaint);
  }

  @Override
  public void setAlpha(int alpha) {}

  @Override
  public void setColorFilter(ColorFilter colorFilter) {}

  @Override
  @SuppressWarnings("deprecation")
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
