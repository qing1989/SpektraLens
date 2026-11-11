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
  private static final float FRAME_PAD_DP = 12;
  private static final float FRAME_SHADOW_DP = 4;
  private static final float DIVIDER_STROKE_DP = 2;
  private static final float DIVIDER_GRAB_DP = 24;
  private static final float KNOB_RADIUS_DP = 13;
  private static final float KNOB_TEXT_DP = 12;
  private static final float KNOB_BASELINE_DP = 4;
  private static final float LABEL_WIDTH_DP = 40;
  private static final float LABEL_HEIGHT_DP = 23;
  private static final float LABEL_TEXT_DP = 10;
  private static final float LABEL_INSET_DP = 8;
  private static final float LABEL_RIGHT_OFFSET_DP = 48;
  private static final float LABEL_BASELINE_DP = 16;
  private static final String LABEL_ORIGINAL = "原图";
  private static final String LABEL_DEVELOPED = "显影";

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

  private float density() {
    return getResources().getDisplayMetrics().density;
  }

  private void zoomAt(
      float requested, float focusX, float focusY, float previousX, float previousY) {
    float next = Math.max(1, Math.min(6, requested));
    float factor = next / zoom;
    offsetX = focusX - getWidth() / 2f - (previousX - getWidth() / 2f - offsetX) * factor;
    offsetY = focusY - getHeight() / 2f - (previousY - getHeight() / 2f - offsetY) * factor;
    zoom = next;
    if (zoom == 1) offsetX = offsetY = 0;
    clampOffsets();
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
  protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
    super.onSizeChanged(w, h, oldWidth, oldHeight);
    clampOffsets();
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.drawColor(Color.BLACK);
    Bitmap image = after != null ? after : before;
    if (image == null) return;
    updateFrame();
    if (frame.isEmpty()) return;
    float density = density();
    float shadow = FRAME_SHADOW_DP * density;
    paint.setColor(Color.BLACK);
    c.drawRect(
        frame.left + shadow * .75f,
        frame.top + shadow,
        frame.right + shadow * .75f,
        frame.bottom + shadow,
        paint);
    c.drawBitmap(image, null, frame, paint);
    if (compare && before != null && after != null) {
      float x = dividerX();
      c.save();
      c.clipRect(frame.left, frame.top, x, frame.bottom);
      c.drawBitmap(before, null, frame, paint);
      c.restore();
      paint.setColor(0xddffffff);
      paint.setStrokeWidth(DIVIDER_STROKE_DP * density);
      c.drawLine(x, visibleFrame.top, x, visibleFrame.bottom, paint);
      c.drawCircle(x, visibleFrame.centerY(), KNOB_RADIUS_DP * density, paint);
      paint.setColor(0xff171717);
      paint.setTextSize(KNOB_TEXT_DP * density);
      paint.setTextAlign(Paint.Align.CENTER);
      c.drawText("↔", x, visibleFrame.centerY() + KNOB_BASELINE_DP * density, paint);
      label(
          c,
          LABEL_ORIGINAL,
          visibleFrame.left + LABEL_INSET_DP * density,
          visibleFrame.top + LABEL_INSET_DP * density);
      label(
          c,
          LABEL_DEVELOPED,
          visibleFrame.right - LABEL_RIGHT_OFFSET_DP * density,
          visibleFrame.top + LABEL_INSET_DP * density);
    }
  }

  /**
   * Keeps panning inside the image. Called from touch and size changes, never from onDraw, so
   * drawing no longer mutates state.
   */
  private void clampOffsets() {
    Bitmap image = after != null ? after : before;
    if (image == null || getWidth() == 0 || getHeight() == 0) return;
    float pad = FRAME_PAD_DP * density();
    float scale =
        Math.max(
            0,
            Math.min(
                (getWidth() - pad * 2) / image.getWidth(),
                (getHeight() - pad * 2) / image.getHeight()));
    float w = image.getWidth() * scale * zoom, h = image.getHeight() * scale * zoom;
    float limitX = Math.max(0, (w - getWidth()) / 2), limitY = Math.max(0, (h - getHeight()) / 2);
    offsetX = Math.max(-limitX, Math.min(limitX, offsetX));
    offsetY = Math.max(-limitY, Math.min(limitY, offsetY));
  }

  private void updateFrame() {
    Bitmap image = after != null ? after : before;
    if (image == null) {
      frame.setEmpty();
      visibleFrame.setEmpty();
      return;
    }
    float pad = FRAME_PAD_DP * density();
    float scale =
        Math.max(
            0,
            Math.min(
                (getWidth() - pad * 2) / image.getWidth(),
                (getHeight() - pad * 2) / image.getHeight()));
    float w = image.getWidth() * scale * zoom, h = image.getHeight() * scale * zoom;
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
    float d = density();
    paint.setColor(0xc0101010);
    c.drawRoundRect(x, y, x + LABEL_WIDTH_DP * d, y + LABEL_HEIGHT_DP * d, 4 * d, 4 * d, paint);
    paint.setColor(Color.WHITE);
    paint.setTextAlign(Paint.Align.LEFT);
    paint.setTextSize(LABEL_TEXT_DP * d);
    c.drawText(s, x + LABEL_INSET_DP * d + d, y + LABEL_BASELINE_DP * d, paint);
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
              && (zoom == 1 || Math.abs(e.getX() - dividerX()) <= DIVIDER_GRAB_DP * density());
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
        clampOffsets();
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
