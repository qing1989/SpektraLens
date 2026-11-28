// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * Renders one live border-styled sample floating inside a darkroom tray well.
 *
 * <p>Preserves each border's true physical aspect ratio (e.g. 1:1 slide mount, wide landscape
 * film card, 135 strip) centered in the well with subtle elevation shadow, warm lightbox backing
 * behind transparent sprocket perforations ({@link FrameStyle#BACKING_COLOR}), and a crisp
 * hairline rim so dark borders never melt into dark trays and white mattes never vanish on light
 * trays.
 */
final class FrameSampleView extends View {
  private static final long FADE_IN_MS = 120L;

  private final FrameStyle style;
  private final Paint bitmapPaint =
      new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
  private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF drawRect = new RectF();
  private final RectF shadowRect = new RectF();
  private final Path clipPath = new Path();
  private FrameStyleGlyphDrawable placeholder;
  private Bitmap bitmap;
  private long fadeStartMs;
  private boolean hasShownSample;

  FrameSampleView(Context context, FrameStyle style) {
    super(context);
    this.style = style;
    rimPaint.setStyle(Paint.Style.STROKE);
    fillPaint.setStyle(Paint.Style.FILL);
    shadowPaint.setStyle(Paint.Style.FILL);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  void setSampleBitmap(Bitmap next, boolean animateFirstReveal) {
    if (next == bitmap) return;
    boolean first = !hasShownSample && next != null && animateFirstReveal;
    bitmap = next;
    if (next != null) {
      hasShownSample = true;
      fadeStartMs = first ? SystemClock.uptimeMillis() : 0L;
    } else {
      hasShownSample = false;
      fadeStartMs = 0L;
    }
    invalidate();
  }

  void clearSample() {
    bitmap = null;
    hasShownSample = false;
    fadeStartMs = 0L;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    int w = getWidth();
    int h = getHeight();
    if (w <= 0 || h <= 0) return;

    float d = getResources().getDisplayMetrics().density;
    boolean light = AppStyle.isLight();
    Bitmap live = bitmap != null && !bitmap.isRecycled() ? bitmap : null;

    if (live == null) {
      drawPlaceholder(canvas, w, h, light);
      return;
    }

    int bw = live.getWidth();
    int bh = live.getHeight();
    if (bw <= 0 || bh <= 0) return;

    float scale = Math.min((float) w / bw, (float) h / bh);
    float dw = bw * scale;
    float dh = bh * scale;
    float left = (w - dw) * .5f;
    float top = (h - dh) * .5f;
    drawRect.set(left, top, left + dw, top + dh);

    float alpha = 1f;
    if (fadeStartMs > 0L) {
      long elapsed = SystemClock.uptimeMillis() - fadeStartMs;
      if (elapsed < FADE_IN_MS) {
        alpha = Math.max(0f, Math.min(1f, (float) elapsed / FADE_IN_MS));
        postInvalidateOnAnimation();
      } else {
        fadeStartMs = 0L;
      }
    }
    int alphaInt = Math.round(alpha * 255f);
    float corner = (style == FrameStyle.NONE ? 4f : 1.25f) * d;

    // Two-tier soft elevation shadow (hardware-accelerated without software layer overhead).
    drawSoftShadow(canvas, drawRect, corner, d, light, alpha);

    // Lightbox warm backing behind transparent sprocket / notch perforations (matches PhotoView).
    int save = canvas.save();
    clipPath.reset();
    clipPath.addRoundRect(drawRect, corner, corner, Path.Direction.CW);
    canvas.clipPath(clipPath);
    if (live.hasAlpha()) {
      fillPaint.setColor(FrameStyle.BACKING_COLOR);
      fillPaint.setAlpha(alphaInt);
      canvas.drawRect(drawRect, fillPaint);
    }
    bitmapPaint.setAlpha(alphaInt);
    canvas.drawBitmap(live, null, drawRect, bitmapPaint);
    canvas.restoreToCount(save);

    // Subtle hairline rim: keeps black film cards legible on dark trays and white mattes on light.
    float stroke = Math.max(1f, .55f * d);
    rimPaint.setStrokeWidth(stroke);
    rimPaint.setColor(light ? Color.argb(Math.round(28 * alpha), 17, 17, 19)
        : Color.argb(Math.round(24 * alpha), 255, 255, 255));
    float half = stroke * .5f;
    shadowRect.set(
        drawRect.left + half, drawRect.top + half, drawRect.right - half, drawRect.bottom - half);
    canvas.drawRoundRect(shadowRect, corner, corner, rimPaint);
  }

  private void drawSoftShadow(
      Canvas canvas, RectF rect, float corner, float d, boolean light, float alpha) {
    int outerA = Math.round((light ? 16 : 64) * alpha);
    shadowPaint.setColor(Color.argb(outerA, 0, 0, 0));
    shadowRect.set(rect.left - 1f * d, rect.top + 1.2f * d, rect.right + 1f * d, rect.bottom + 3.2f * d);
    canvas.drawRoundRect(shadowRect, corner + 1.5f * d, corner + 1.5f * d, shadowPaint);

    int innerA = Math.round((light ? 24 : 96) * alpha);
    shadowPaint.setColor(Color.argb(innerA, 0, 0, 0));
    shadowRect.set(rect.left, rect.top + .6f * d, rect.right, rect.bottom + 1.6f * d);
    canvas.drawRoundRect(shadowRect, corner + .6f * d, corner + .6f * d, shadowPaint);
  }

  private void drawPlaceholder(Canvas canvas, int w, int h, boolean light) {
    if (placeholder == null) {
      placeholder = new FrameStyleGlyphDrawable(style, light, false);
    }
    // Keep a calm, low-contrast silhouette while the live sample renders for the first time.
    float targetRatio = style == FrameStyle.SLIDE ? 1f : 1.38f;
    float boxW, boxH;
    if ((float) w / h > targetRatio) {
      boxH = h * .72f;
      boxW = boxH * targetRatio;
    } else {
      boxW = w * .76f;
      boxH = boxW / targetRatio;
    }
    int left = Math.round((w - boxW) * .5f);
    int top = Math.round((h - boxH) * .5f);
    placeholder.setBounds(left, top, Math.round(left + boxW), Math.round(top + boxH));
    placeholder.setAlpha(light ? 115 : 105);
    placeholder.draw(canvas);
  }
}
