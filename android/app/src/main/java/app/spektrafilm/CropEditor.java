// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Interactive non-destructive crop canvas supporting 90° rotation, horizontal flip, straighten
 * angle preview, aspect-ratio locking, rule-of-thirds grid, and precision L-corner handles.
 * Internally stores display-space normalized crop and converts bijectively to source-space.
 */
final class CropEditor extends View {
  private final Bitmap image;
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
  private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bracketPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Matrix drawMatrix = new Matrix();
  private final RectF area = new RectF(), displayCrop = new RectF(), frame = new RectF();
  private float aspect, lastX, lastY;
  private int turns;
  private boolean flipH, flipV;
  private float angleDeg;
  private int handle = -1;
  private Runnable changeListener;

  CropEditor(Context context, Bitmap bitmap, RectF initialSourceCrop) {
    this(context, bitmap, initialSourceCrop, 0, false, false, 0f);
  }

  CropEditor(
      Context context,
      Bitmap bitmap,
      RectF initialSourceCrop,
      int initialTurns,
      boolean initialFlipH,
      float initialAngleDeg) {
    this(context, bitmap, initialSourceCrop, initialTurns, initialFlipH, false, initialAngleDeg);
  }

  CropEditor(
      Context context,
      Bitmap bitmap,
      RectF initialSourceCrop,
      int initialTurns,
      boolean initialFlipH,
      boolean initialFlipV,
      float initialAngleDeg) {
    super(context);
    this.image = bitmap;
    this.turns = ((initialTurns % 4) + 4) % 4;
    this.flipH = initialFlipH;
    this.flipV = initialFlipV;
    this.angleDeg = initialAngleDeg;
    sourceToDisplay(initialSourceCrop, this.turns, this.flipH, this.flipV, displayCrop);
    clamp();
    setContentDescription("裁切框：拖动四角改变范围，拖动中间移动");
  }

  void setOnChangeListener(Runnable listener) {
    this.changeListener = listener;
  }

  /** Normalized source-space crop rectangle matching native edit_pipeline.cpp Geometry. */
  RectF value() {
    RectF out = new RectF();
    displayToSource(displayCrop, turns, flipH, flipV, out);
    return out;
  }

  int turns() {
    return turns;
  }

  boolean flipH() {
    return flipH;
  }

  boolean flipV() {
    return flipV;
  }

  float angleDeg() {
    return angleDeg;
  }

  float displayAspect() {
    float dw = displaySourceWidth();
    float dh = displaySourceHeight();
    return (displayCrop.width() * dw) / Math.max(1f, displayCrop.height() * dh);
  }

  int outputWidth(int fullSourceW, int fullSourceH) {
    RectF src = value();
    int cw = Math.max(1, Math.round(src.width() * fullSourceW));
    int ch = Math.max(1, Math.round(src.height() * fullSourceH));
    return (turns % 2 == 1) ? ch : cw;
  }

  int outputHeight(int fullSourceW, int fullSourceH) {
    RectF src = value();
    int cw = Math.max(1, Math.round(src.width() * fullSourceW));
    int ch = Math.max(1, Math.round(src.height() * fullSourceH));
    return (turns % 2 == 1) ? cw : ch;
  }

  private float displaySourceWidth() {
    return (turns % 2 == 1) ? image.getHeight() : image.getWidth();
  }

  private float displaySourceHeight() {
    return (turns % 2 == 1) ? image.getWidth() : image.getHeight();
  }

  void rotate90() {
    RectF src = value();
    turns = (turns + 1) % 4;
    sourceToDisplay(src, turns, flipH, flipV, displayCrop);
    if (aspect > 0f) applyAspect(aspect);
    else clamp();
    invalidate();
    notifyChanged();
  }

  void toggleFlipH() {
    RectF src = value();
    flipH = !flipH;
    sourceToDisplay(src, turns, flipH, flipV, displayCrop);
    clamp();
    invalidate();
    notifyChanged();
  }

  void setAngle(float deg) {
    float clamped = Math.max(-45f, Math.min(45f, deg));
    if (Math.abs(clamped - angleDeg) < 0.001f) return;
    angleDeg = clamped;
    invalidate();
    notifyChanged();
  }

  void ratio(float r) {
    aspect = r;
    if (r <= 0f) {
      invalidate();
      notifyChanged();
      return;
    }
    applyAspect(r);
    invalidate();
    notifyChanged();
  }

  void resetAll() {
    turns = 0;
    flipH = false;
    flipV = false;
    angleDeg = 0f;
    aspect = 0f;
    displayCrop.set(0f, 0f, 1f, 1f);
    invalidate();
    notifyChanged();
  }

  void reset() {
    displayCrop.set(0f, 0f, 1f, 1f);
    ratio(aspect);
  }

  private void applyAspect(float targetAspect) {
    float physical = displaySourceWidth() / Math.max(1f, displaySourceHeight());
    float normalized = targetAspect / physical;
    float w = displayCrop.width();
    float h = w / normalized;
    if (h > 1f) {
      h = 1f;
      w = Math.min(1f, normalized);
    }
    if (w > 1f) {
      w = 1f;
      h = Math.min(1f, 1f / normalized);
    }
    float cx = displayCrop.centerX(), cy = displayCrop.centerY();
    displayCrop.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
    clamp();
  }

  private void clamp() {
    float w = Math.min(1f, displayCrop.width());
    float h = Math.min(1f, displayCrop.height());
    if (aspect > 0f) {
      float norm = aspect * displaySourceHeight() / Math.max(1f, displaySourceWidth());
      float minW = Math.min(1f, Math.max(.08f, .08f * norm));
      float maxW = Math.min(1f, norm);
      w = Math.max(Math.min(minW, maxW), Math.min(maxW, w));
      h = w / norm;
      if (h > 1f) {
        h = 1f;
        w = Math.min(1f, h * norm);
      }
    } else {
      w = Math.max(.08f, w);
      h = Math.max(.08f, h);
    }
    float left = Math.max(0f, Math.min(1f - w, displayCrop.left));
    float top = Math.max(0f, Math.min(1f - h, displayCrop.top));
    displayCrop.set(left, top, left + w, top + h);
  }

  private void notifyChanged() {
    if (changeListener != null) changeListener.run();
  }

  private static void sourceToDisplay(
      RectF src, int turns, boolean flipH, boolean flipV, RectF out) {
    float[] xs = {src.left, src.right};
    float[] ys = {src.top, src.bottom};
    float minU = 1f, maxU = 0f, minV = 1f, maxV = 0f;
    for (float a : xs) {
      for (float b : ys) {
        float u1, v1;
        switch (turns) {
          case 1:
            u1 = 1f - b;
            v1 = a;
            break;
          case 2:
            u1 = 1f - a;
            v1 = 1f - b;
            break;
          case 3:
            u1 = b;
            v1 = 1f - a;
            break;
          default:
            u1 = a;
            v1 = b;
            break;
        }
        float u = flipH ? (1f - u1) : u1;
        float v = flipV ? (1f - v1) : v1;
        if (u < minU) minU = u;
        if (u > maxU) maxU = u;
        if (v < minV) minV = v;
        if (v > maxV) maxV = v;
      }
    }
    out.set(minU, minV, maxU, maxV);
  }

  private static void displayToSource(
      RectF disp, int turns, boolean flipH, boolean flipV, RectF out) {
    float[] us = {disp.left, disp.right};
    float[] vs = {disp.top, disp.bottom};
    float minA = 1f, maxA = 0f, minB = 1f, maxB = 0f;
    for (float u : us) {
      for (float v : vs) {
        float u1 = flipH ? (1f - u) : u;
        float v1 = flipV ? (1f - v) : v;
        float a, b;
        switch (turns) {
          case 1:
            a = v1;
            b = 1f - u1;
            break;
          case 2:
            a = 1f - u1;
            b = 1f - v1;
            break;
          case 3:
            a = 1f - v1;
            b = u1;
            break;
          default:
            a = u1;
            b = v1;
            break;
        }
        if (a < minA) minA = a;
        if (a > maxA) maxA = a;
        if (b < minB) minB = b;
        if (b > maxB) maxB = b;
      }
    }
    out.set(
        Math.max(0f, Math.min(0.999f, minA)),
        Math.max(0f, Math.min(0.999f, minB)),
        Math.max(0.001f, Math.min(1f, maxA)),
        Math.max(0.001f, Math.min(1f, maxB)));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float d = getResources().getDisplayMetrics().density;
    canvas.drawColor(AppStyle.STAGE_BG);
    if (image == null || image.isRecycled()) return;

    float pad = 20f * d;
    float dw = displaySourceWidth();
    float dh = displaySourceHeight();
    float scale = Math.min((getWidth() - pad * 2f) / dw, (getHeight() - pad * 2f) / dh);
    float w = dw * scale;
    float h = dh * scale;
    area.set(
        (getWidth() - w) / 2f,
        (getHeight() - h) / 2f,
        (getWidth() + w) / 2f,
        (getHeight() + h) / 2f);

    RectF r = frame;
    r.set(
        area.left + displayCrop.left * w,
        area.top + displayCrop.top * h,
        area.left + displayCrop.right * w,
        area.top + displayCrop.bottom * h);

    // Match native edit_pipeline.cpp Geometry::sample forward transform exactly:
    // rotate (+angleDeg) & scale (fit) around the source crop center, then 90° turns, then flips.
    RectF srcCrop = value();
    float scx = srcCrop.centerX() * image.getWidth();
    float scy = srcCrop.centerY() * image.getHeight();
    canvas.save();
    canvas.clipRect(area);
    drawMatrix.reset();
    drawMatrix.postTranslate(-scx, -scy);
    if (Math.abs(angleDeg) > 0.01f) {
      double rad = Math.toRadians(angleDeg);
      float cw = Math.max(1f, srcCrop.width() * image.getWidth());
      float ch = Math.max(1f, srcCrop.height() * image.getHeight());
      float fit =
          (float)
              Math.max(
                  Math.abs(Math.cos(rad)) + Math.abs(Math.sin(rad)) * (ch / cw),
                  Math.abs(Math.cos(rad)) + Math.abs(Math.sin(rad)) * (cw / ch));
      drawMatrix.postRotate(angleDeg);
      drawMatrix.postScale(fit, fit);
    }
    if (turns != 0) drawMatrix.postRotate(turns * 90f);
    if (flipH || flipV) drawMatrix.postScale(flipH ? -1f : 1f, flipV ? -1f : 1f);
    drawMatrix.postScale(scale, scale);
    drawMatrix.postTranslate(r.centerX(), r.centerY());
    paint.setStyle(Paint.Style.FILL);
    paint.setAlpha(255);
    canvas.drawBitmap(image, drawMatrix, paint);
    canvas.restore();

    // Dimmed outer crop mask.
    paint.setColor(0xa6000000);
    canvas.drawRect(area.left, area.top, area.right, r.top, paint);
    canvas.drawRect(area.left, r.bottom, area.right, area.bottom, paint);
    canvas.drawRect(area.left, r.top, r.left, r.bottom, paint);
    canvas.drawRect(r.right, r.top, area.right, r.bottom, paint);

    // Crisp outer frame hairline + rule-of-thirds grid.
    gridPaint.setStyle(Paint.Style.STROKE);
    gridPaint.setStrokeWidth(Math.max(1f, d));
    gridPaint.setColor(0x66ffffff);
    for (int i = 1; i < 3; i++) {
      float vx = r.left + i * r.width() / 3f;
      float hy = r.top + i * r.height() / 3f;
      canvas.drawLine(vx, r.top, vx, r.bottom, gridPaint);
      canvas.drawLine(r.left, hy, r.right, hy, gridPaint);
    }
    gridPaint.setStrokeWidth(Math.max(1.25f * d, 1.5f));
    gridPaint.setColor(0xeeffffff);
    canvas.drawRect(r, gridPaint);

    // Precision Instrument vermilion L-corner brackets.
    float arm = Math.min(18f * d, Math.min(r.width(), r.height()) * 0.28f);
    bracketPaint.setStyle(Paint.Style.STROKE);
    bracketPaint.setStrokeCap(Paint.Cap.SQUARE);
    bracketPaint.setStrokeWidth(3f * d);
    bracketPaint.setColor(AppStyle.GOLD);
    // Top-left
    canvas.drawLine(r.left, r.top + arm, r.left, r.top, bracketPaint);
    canvas.drawLine(r.left, r.top, r.left + arm, r.top, bracketPaint);
    // Top-right
    canvas.drawLine(r.right - arm, r.top, r.right, r.top, bracketPaint);
    canvas.drawLine(r.right, r.top, r.right, r.top + arm, bracketPaint);
    // Bottom-left
    canvas.drawLine(r.left, r.bottom - arm, r.left, r.bottom, bracketPaint);
    canvas.drawLine(r.left, r.bottom, r.left + arm, r.bottom, bracketPaint);
    // Bottom-right
    canvas.drawLine(r.right - arm, r.bottom, r.right, r.bottom, bracketPaint);
    canvas.drawLine(r.right, r.bottom, r.right, r.bottom - arm, bracketPaint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (area.width() <= 0f) return true;
    float x = (e.getX() - area.left) / area.width();
    float y = (e.getY() - area.top) / area.height();
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      lastX = x;
      lastY = y;
      float best = 48f * getResources().getDisplayMetrics().density;
      handle = 4;
      for (int i = 0; i < 4; i++) {
        float a = (i % 2 == 0 ? displayCrop.left : displayCrop.right);
        float b = (i < 2 ? displayCrop.top : displayCrop.bottom);
        float distance = (float) Math.hypot((a - x) * area.width(), (b - y) * area.height());
        if (distance < best) {
          best = distance;
          handle = i;
        }
      }
      if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
    } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
      if (handle == 4) {
        displayCrop.offset(x - lastX, y - lastY);
        clamp();
      } else if (handle >= 0) {
        boolean left = handle % 2 == 0, top = handle < 2;
        float ax = left ? displayCrop.right : displayCrop.left;
        float ay = top ? displayCrop.bottom : displayCrop.top;
        float maxW = left ? ax : 1f - ax;
        float maxH = top ? ay : 1f - ay;
        float rawW = left ? (ax - x) : (x - ax);
        float rawH = top ? (ay - y) : (y - ay);
        float w = Math.max(.08f, Math.min(maxW, rawW));
        float h = Math.max(.08f, Math.min(maxH, rawH));
        if (aspect > 0f) {
          float norm = aspect * displaySourceHeight() / Math.max(1f, displaySourceWidth());
          float aspectMaxW = Math.min(maxW, maxH * norm);
          float aspectMinW = Math.min(aspectMaxW, Math.max(.08f, .08f * norm));
          w = Math.max(aspectMinW, Math.min(aspectMaxW, rawW));
          h = w / norm;
          if (h > maxH) {
            h = maxH;
            w = h * norm;
          }
        }
        displayCrop.set(
            left ? ax - w : ax, top ? ay - h : ay, left ? ax : ax + w, top ? ay : ay + h);
      }
      lastX = x;
      lastY = y;
      invalidate();
      notifyChanged();
    } else if (e.getActionMasked() == MotionEvent.ACTION_UP
        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
      if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
