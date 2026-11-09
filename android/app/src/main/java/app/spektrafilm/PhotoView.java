// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

/** Both the before/after canvas and saved file use encoded sRGB pixels. */
public final class PhotoView extends View {
  private final Paint paint = new Paint(3);
  private final RectF frame = new RectF();
  private final RectF visibleFrame = new RectF();
  private Bitmap before, after;
  private boolean compare;
  private float split = .5f;
  private float zoom = 1, offsetX, offsetY, lastX, lastY;
  private float lastFocusX, lastFocusY;
  private boolean scaleGesture;
  private boolean dragging, dragDivider;
  private float downX, downY;
  private final int touchSlop;
  private final ScaleGestureDetector scale;
  private final GestureDetector taps;

  public PhotoView(Context c) {
    super(c);
    touchSlop = ViewConfiguration.get(c).getScaledTouchSlop();
    setContentDescription("照片预览；双指缩放，双击放大或复位；对比时拖动分界线，放大后拖动其他位置平移照片");
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
      float x = dividerX();
      c.save();
      c.clipRect(frame.left, frame.top, x, frame.bottom);
      c.drawBitmap(before, null, frame, paint);
      c.restore();
      paint.setColor(0xddffffff);
      paint.setStrokeWidth(2);
      c.drawLine(x, visibleFrame.top, x, visibleFrame.bottom, paint);
      c.drawCircle(
          x, visibleFrame.centerY(), 13 * getResources().getDisplayMetrics().density, paint);
      paint.setColor(0xff171717);
      paint.setTextSize(12 * getResources().getDisplayMetrics().density);
      paint.setTextAlign(Paint.Align.CENTER);
      c.drawText(
          "↔", x, visibleFrame.centerY() + 4 * getResources().getDisplayMetrics().density, paint);
      label(c, "原图", visibleFrame.left + 8, visibleFrame.top + 8);
      label(
          c,
          "显影",
          visibleFrame.right - 48 * getResources().getDisplayMetrics().density,
          visibleFrame.top + 8);
    }
  }

  private void updateFrame() {
    Bitmap image = after != null ? after : before;
    if (image == null) {
      frame.setEmpty();
      visibleFrame.setEmpty();
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
    visibleFrame.set(frame);
    if (!visibleFrame.intersect(0, 0, getWidth(), getHeight())) visibleFrame.setEmpty();
  }

  private float dividerX() {
    return visibleFrame.left + split * visibleFrame.width();
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
    if (action == MotionEvent.ACTION_DOWN) {
      scaleGesture = false;
      dragging = false;
      downX = lastX = e.getX();
      downY = lastY = e.getY();
      updateFrame();
      dragDivider =
          compare
              && (zoom == 1
                  || Math.abs(e.getX() - dividerX())
                      <= 24 * getResources().getDisplayMetrics().density);
    }
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
    if (action == MotionEvent.ACTION_MOVE) {
      if (!dragging && Math.hypot(e.getX() - downX, e.getY() - downY) <= touchSlop) return true;
      dragging = true;
      intercept(true);
      updateFrame();
      if (visibleFrame.isEmpty()) return true;
      if (dragDivider) {
        split =
            Math.max(.03f, Math.min(.97f, (e.getX() - visibleFrame.left) / visibleFrame.width()));
      } else if (zoom > 1) {
        offsetX += e.getX() - lastX;
        offsetY += e.getY() - lastY;
      }
      lastX = e.getX();
      lastY = e.getY();
      invalidate();
      return true;
    }
    if (action == MotionEvent.ACTION_UP) {
      intercept(false);
      if (!dragging) performClick();
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
