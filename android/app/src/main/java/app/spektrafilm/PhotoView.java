// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Both the before/after canvas and saved file use encoded sRGB pixels. */
public final class PhotoView extends View {
  private final Paint paint = new Paint(3);
  private final RectF frame = new RectF();
  private Bitmap before, after;
  private boolean compare;
  private float split = .5f;
  private float zoom = 1, offsetX, offsetY, lastX, lastY;
  private final ScaleGestureDetector scale;
  private final GestureDetector taps;

  public PhotoView(Context c) {
    super(c);
    setContentDescription("照片预览；双指缩放，双击复位，开启对比后可左右拖动分界线");
    scale =
        new ScaleGestureDetector(
            c,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(ScaleGestureDetector detector) {
                zoom = Math.max(1, Math.min(6, zoom * detector.getScaleFactor()));
                if (zoom == 1) {
                  offsetX = offsetY = 0;
                }
                invalidate();
                return true;
              }
            });
    taps =
        new GestureDetector(
            c,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }

              @Override
              public boolean onDoubleTap(MotionEvent e) {
                if (zoom > 1) resetZoom();
                else {
                  zoom = 2;
                  invalidate();
                }
                return true;
              }
            });
  }

  public void resetZoom() {
    zoom = 1;
    offsetX = offsetY = 0;
    invalidate();
  }

  public void images(Bitmap a, Bitmap b) {
    before = a;
    after = b;
    invalidate();
  }

  public void compare(boolean value) {
    compare = value;
    invalidate();
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.drawColor(Color.BLACK);
    Bitmap image = after != null ? after : before;
    if (image == null) return;
    float pad = 12 * getResources().getDisplayMetrics().density;
    float scale =
        Math.min(
            (getWidth() - pad * 2) / image.getWidth(), (getHeight() - pad * 2) / image.getHeight());
    float w = image.getWidth() * scale * zoom, h = image.getHeight() * scale * zoom;
    offsetX =
        Math.max(
            -Math.max(0, (w - getWidth()) / 2),
            Math.min(Math.max(0, (w - getWidth()) / 2), offsetX));
    offsetY =
        Math.max(
            -Math.max(0, (h - getHeight()) / 2),
            Math.min(Math.max(0, (h - getHeight()) / 2), offsetY));
    frame.set(
        (getWidth() - w) / 2 + offsetX,
        (getHeight() - h) / 2 + offsetY,
        (getWidth() + w) / 2 + offsetX,
        (getHeight() + h) / 2 + offsetY);
    paint.setColor(Color.BLACK);
    c.drawRect(frame.left + 3, frame.top + 5, frame.right + 3, frame.bottom + 5, paint);
    c.drawBitmap(image, null, frame, paint);
    if (compare && before != null && after != null) {
      float x = frame.left + split * frame.width();
      c.save();
      c.clipRect(frame.left, frame.top, x, frame.bottom);
      c.drawBitmap(before, null, frame, paint);
      c.restore();
      paint.setColor(0xddffffff);
      paint.setStrokeWidth(2);
      c.drawLine(x, frame.top, x, frame.bottom, paint);
      c.drawCircle(x, frame.centerY(), 13 * getResources().getDisplayMetrics().density, paint);
      paint.setColor(0xff171717);
      paint.setTextSize(12 * getResources().getDisplayMetrics().density);
      paint.setTextAlign(Paint.Align.CENTER);
      c.drawText("↔", x, frame.centerY() + 4 * getResources().getDisplayMetrics().density, paint);
      label(c, "原图", frame.left + 8, frame.top + 8);
      label(c, "显影", frame.right - 48 * getResources().getDisplayMetrics().density, frame.top + 8);
    }
  }

  private void label(Canvas c, String s, float x, float y) {
    float d = getResources().getDisplayMetrics().density;
    paint.setColor(0xc0101010);
    c.drawRoundRect(x, y, x + 40 * d, y + 23 * d, 4 * d, 4 * d, paint);
    paint.setColor(Color.WHITE);
    paint.setTextAlign(Paint.Align.LEFT);
    paint.setTextSize(10 * d);
    c.drawText(s, x + 9 * d, y + 16 * d, paint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    scale.onTouchEvent(e);
    taps.onTouchEvent(e);
    if (e.getPointerCount() > 1) {
      getParent().requestDisallowInterceptTouchEvent(true);
      lastX = e.getX();
      lastY = e.getY();
      return true;
    }
    if (!compare) {
      if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
        lastX = e.getX();
        lastY = e.getY();
        return true;
      }
      if (e.getActionMasked() == MotionEvent.ACTION_MOVE && zoom > 1 && !scale.isInProgress()) {
        offsetX += e.getX() - lastX;
        offsetY += e.getY() - lastY;
        lastX = e.getX();
        lastY = e.getY();
        invalidate();
        return true;
      }
      if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
      return true;
    }
    if (e.getAction() == MotionEvent.ACTION_DOWN || e.getAction() == MotionEvent.ACTION_MOVE) {
      getParent().requestDisallowInterceptTouchEvent(true);
      split = Math.max(.03f, Math.min(.97f, (e.getX() - frame.left) / frame.width()));
      invalidate();
      return true;
    }
    if (e.getAction() == MotionEvent.ACTION_UP) {
      getParent().requestDisallowInterceptTouchEvent(false);
      performClick();
      return true;
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
