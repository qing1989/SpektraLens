// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Spektra contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.view.*;

/** Normalised source-space crop; the native export uses the identical rectangle. */
final class CropEditor extends View {
  private final Bitmap image;
  private final Paint p = new Paint(3);
  private final RectF area = new RectF(), crop, frame = new RectF();
  private float aspect, lastX, lastY;
  private int handle = -1;

  CropEditor(Context c, Bitmap b, RectF initial) {
    super(c);
    image = b;
    crop = new RectF(initial);
    setContentDescription("裁切框：拖动四角改变范围，拖动中间移动");
  }

  RectF value() {
    return new RectF(crop);
  }

  void ratio(float r) {
    aspect = r;
    if (r == 0) {
      invalidate();
      return;
    }
    float physical = image.getWidth() / (float) image.getHeight(), normalized = r / physical;
    float w = crop.width(), h = w / normalized;
    if (h > 1) {
      h = 1;
      w = normalized;
    }
    float cx = crop.centerX(), cy = crop.centerY();
    crop.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2);
    clamp();
    invalidate();
  }

  void reset() {
    crop.set(0, 0, 1, 1);
    ratio(aspect);
  }

  private void clamp() {
    if (crop.left < 0) crop.offset(-crop.left, 0);
    if (crop.right > 1) crop.offset(1 - crop.right, 0);
    if (crop.top < 0) crop.offset(0, -crop.top);
    if (crop.bottom > 1) crop.offset(0, 1 - crop.bottom);
  }

  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float d = getResources().getDisplayMetrics().density,
        s =
            Math.min(
                (getWidth() - 24 * d) / image.getWidth(),
                (getHeight() - 24 * d) / image.getHeight());
    float w = image.getWidth() * s, h = image.getHeight() * s;
    area.set(
        (getWidth() - w) / 2, (getHeight() - h) / 2, (getWidth() + w) / 2, (getHeight() + h) / 2);
    c.drawColor(Color.BLACK);
    c.drawBitmap(image, null, area, p);
    RectF r = frame;
    r.set(
        area.left + crop.left * w,
        area.top + crop.top * h,
        area.left + crop.right * w,
        area.top + crop.bottom * h);
    p.setColor(0xb9000000);
    c.drawRect(area.left, area.top, area.right, r.top, p);
    c.drawRect(area.left, r.bottom, area.right, area.bottom, p);
    c.drawRect(area.left, r.top, r.left, r.bottom, p);
    c.drawRect(r.right, r.top, area.right, r.bottom, p);
    p.setColor(0xffd9b77a);
    p.setStrokeWidth(2 * d);
    p.setStyle(Paint.Style.STROKE);
    c.drawRect(r, p);
    p.setStrokeWidth(d);
    p.setColor(0x99ffffff);
    for (int i = 1; i < 3; i++) {
      c.drawLine(r.left + i * r.width() / 3, r.top, r.left + i * r.width() / 3, r.bottom, p);
      c.drawLine(r.left, r.top + i * r.height() / 3, r.right, r.top + i * r.height() / 3, p);
    }
    p.setStyle(Paint.Style.FILL);
    p.setColor(0xffd9b77a);
    c.drawCircle(r.left, r.top, 6 * d, p);
    c.drawCircle(r.right, r.top, 6 * d, p);
    c.drawCircle(r.left, r.bottom, 6 * d, p);
    c.drawCircle(r.right, r.bottom, 6 * d, p);
  }

  public boolean onTouchEvent(MotionEvent e) {
    if (area.width() <= 0) return true;
    float x = (e.getX() - area.left) / area.width(), y = (e.getY() - area.top) / area.height();
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      lastX = x;
      lastY = y;
      float best = 48 * getResources().getDisplayMetrics().density;
      handle = 4;
      for (int i = 0; i < 4; i++) {
        float a = (i % 2 == 0 ? crop.left : crop.right), b = (i < 2 ? crop.top : crop.bottom);
        float distance = (float) Math.hypot((a - x) * area.width(), (b - y) * area.height());
        if (distance < best) {
          best = distance;
          handle = i;
        }
      }
      getParent().requestDisallowInterceptTouchEvent(true);
    } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
      if (handle == 4) {
        crop.offset(x - lastX, y - lastY);
        clamp();
      } else if (handle >= 0) {
        boolean left = handle % 2 == 0, top = handle < 2;
        float ax = left ? crop.right : crop.left, ay = top ? crop.bottom : crop.top;
        float w = Math.max(.04f, Math.min(left ? ax : 1 - ax, Math.abs(x - ax))),
            h = Math.max(.04f, Math.min(top ? ay : 1 - ay, Math.abs(y - ay)));
        if (aspect > 0) {
          float norm = aspect * image.getHeight() / image.getWidth();
          h = w / norm;
          float maxH = top ? ay : 1 - ay;
          if (h > maxH) {
            h = maxH;
            w = h * norm;
          }
        }
        crop.set(left ? ax - w : ax, top ? ay - h : ay, left ? ax : ax + w, top ? ay : ay + h);
      }
      lastX = x;
      lastY = y;
      invalidate();
    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
      getParent().requestDisallowInterceptTouchEvent(false);
      performClick();
    }
    return true;
  }

  public boolean performClick() {
    super.performClick();
    return true;
  }
}
