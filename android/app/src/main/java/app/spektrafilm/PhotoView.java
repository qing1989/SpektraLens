// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
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
  private float lastFocusX, lastFocusY;
  private boolean scaleGesture;
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
              public boolean onScaleBegin(ScaleGestureDetector detector) {
                lastFocusX = detector.getFocusX();
                lastFocusY = detector.getFocusY();
                return true;
              }

              @Override
              public boolean onScale(ScaleGestureDetector detector) {
                zoomAt(
                    zoom * detector.getScaleFactor(),
                    detector.getFocusX(),
                    detector.getFocusY(),
                    lastFocusX,
                    lastFocusY);
                lastFocusX = detector.getFocusX();
                lastFocusY = detector.getFocusY();
                return true;
              }
            });
    scale.setQuickScaleEnabled(false);
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
                else zoomAt(2, e.getX(), e.getY(), e.getX(), e.getY());
                return true;
              }
            });
  }

  public void resetZoom() {
    zoom = 1;
    offsetX = offsetY = 0;
    invalidate();
  }

  private void zoomAt(
      float requested, float focusX, float focusY, float previousX, float previousY) {
    float next = Math.max(1, Math.min(6, requested));
    float factor = next / zoom;
    offsetX = focusX - getWidth() / 2f - (previousX - getWidth() / 2f - offsetX) * factor;
    offsetY = focusY - getHeight() / 2f - (previousY - getHeight() / 2f - offsetY) * factor;
    zoom = next;
    if (zoom == 1) offsetX = offsetY = 0;
    updateFrame();
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
    updateFrame();
    if (frame.isEmpty()) return;
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

  private void updateFrame() {
    Bitmap image = after != null ? after : before;
    if (image == null) {
      frame.setEmpty();
      return;
    }
    float pad = 12 * getResources().getDisplayMetrics().density;
    float scale =
        Math.max(
            0,
            Math.min(
                (getWidth() - pad * 2) / image.getWidth(),
                (getHeight() - pad * 2) / image.getHeight()));
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
    int action = e.getActionMasked();
    if (action == MotionEvent.ACTION_DOWN) scaleGesture = false;
    scale.onTouchEvent(e);
    taps.onTouchEvent(e);
    if (e.getPointerCount() > 1) {
      scaleGesture = true;
      intercept(true);
      return true;
    }
    // Consume the rest of a pinch, so lifting a finger cannot grab the comparison divider.
    if (scaleGesture) {
      if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
        scaleGesture = false;
        intercept(false);
      }
      return true;
    }
    if (action == MotionEvent.ACTION_CANCEL) {
      intercept(false);
      return true;
    }
    if (!compare) {
      if (action == MotionEvent.ACTION_DOWN) {
        lastX = e.getX();
        lastY = e.getY();
        return true;
      }
      if (action == MotionEvent.ACTION_MOVE && zoom > 1 && !scale.isInProgress()) {
        intercept(true);
        offsetX += e.getX() - lastX;
        offsetY += e.getY() - lastY;
        lastX = e.getX();
        lastY = e.getY();
        invalidate();
        return true;
      }
      if (action == MotionEvent.ACTION_UP) {
        intercept(false);
        performClick();
      }
      return true;
    }
    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
      intercept(true);
      updateFrame();
      if (frame.isEmpty()) return true;
      split = Math.max(.03f, Math.min(.97f, (e.getX() - frame.left) / frame.width()));
      invalidate();
      return true;
    }
    if (action == MotionEvent.ACTION_UP) {
      intercept(false);
      performClick();
      return true;
    }
    return true;
  }

  private void intercept(boolean disallow) {
    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(disallow);
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
