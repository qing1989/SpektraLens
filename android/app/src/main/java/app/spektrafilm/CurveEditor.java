// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;
import org.json.*;

/** Touch-editable monotone Hermite curve. Points are persisted in the photo recipe. */
final class CurveEditor extends View {
  private final Paint p = new Paint(3);
  private final ArrayList<PointF> points = new ArrayList<>();
  private final RectF area = new RectF();
  private final float[] slopes = new float[12], m = new float[12];
  private final Path path = new Path();
  private int selected = -1;
  private final int color;
  private final Runnable changed;
  private final GestureDetector gestures;

  CurveEditor(Context c, JSONArray json, int color, Runnable changed) {
    super(c);
    this.color = color;
    this.changed = changed;
    setContentDescription("曲线编辑器：点击添加点，拖动调整，长按删除内部控制点");
    if (json != null)
      for (int i = 0; i < json.length(); i++) {
        JSONArray v = json.optJSONArray(i);
        if (v != null) points.add(new PointF((float) v.optDouble(0), (float) v.optDouble(1)));
      }
    if (points.size() < 2) {
      points.clear();
      points.add(new PointF(0, 0));
      points.add(new PointF(1, 1));
    }
    points.sort(Comparator.comparingDouble(a -> a.x));
    gestures =
        new GestureDetector(
            c,
            new GestureDetector.SimpleOnGestureListener() {
              public void onLongPress(MotionEvent e) {
                if (selected > 0 && selected < points.size() - 1) {
                  points.remove(selected);
                  selected = -1;
                  invalidate();
                  changed.run();
                }
              }
            });
  }

  JSONArray value() {
    JSONArray j = new JSONArray();
    try {
      for (PointF q : points) j.put(new JSONArray().put((double) q.x).put((double) q.y));
    } catch (JSONException ex) {
      throw new IllegalStateException(ex);
    }
    return j;
  }

  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float d = getResources().getDisplayMetrics().density;
    area.set(20 * d, 12 * d, getWidth() - 20 * d, getHeight() - 24 * d);
    p.setStyle(Paint.Style.FILL);
    p.setColor(AppStyle.FIELD);
    c.drawRoundRect(area, 14 * d, 14 * d, p);
    p.setStrokeWidth(d);
    p.setColor(AppStyle.OUTLINE);
    for (int i = 0; i <= 4; i++) {
      float t = i / 4f;
      c.drawLine(
          area.left + t * area.width(), area.top, area.left + t * area.width(), area.bottom, p);
      c.drawLine(
          area.left, area.top + t * area.height(), area.right, area.top + t * area.height(), p);
    }
    p.setColor(0xff555555);
    c.drawLine(area.left, area.bottom, area.right, area.top, p);
    int n = points.size();
    for (int i = 0; i < n - 1; i++)
      slopes[i] = (points.get(i + 1).y - points.get(i).y) / (points.get(i + 1).x - points.get(i).x);
    m[0] = slopes[0];
    m[n - 1] = slopes[n - 2];
    for (int i = 1; i < n - 1; i++)
      m[i] =
          slopes[i - 1] * slopes[i] <= 0
              ? 0
              : 2 * slopes[i - 1] * slopes[i] / (slopes[i - 1] + slopes[i]);
    path.reset();
    int k = 0;
    for (int i = 0; i <= 256; i++) {
      float x = i / 256f;
      while (k < n - 2 && x > points.get(k + 1).x) k++;
      PointF a = points.get(k), b = points.get(k + 1);
      float h = b.x - a.x, t = Math.max(0, Math.min(1, (x - a.x) / h)), t2 = t * t, t3 = t2 * t;
      float y =
          (2 * t3 - 3 * t2 + 1) * a.y
              + (t3 - 2 * t2 + t) * h * m[k]
              + (-2 * t3 + 3 * t2) * b.y
              + (t3 - t2) * h * m[k + 1];
      y = Math.max(0, Math.min(1, y));
      float px = area.left + x * area.width(), py = area.bottom - y * area.height();
      if (i == 0) path.moveTo(px, py);
      else path.lineTo(px, py);
    }
    p.setColor(color);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(2 * d);
    c.drawPath(path, p);
    p.setStyle(Paint.Style.FILL);
    for (PointF q : points) {
      p.setColor(color);
      c.drawCircle(area.left + q.x * area.width(), area.bottom - q.y * area.height(), 5 * d, p);
      p.setColor(AppStyle.FIELD);
      c.drawCircle(area.left + q.x * area.width(), area.bottom - q.y * area.height(), 2 * d, p);
    }
  }

  public boolean onTouchEvent(MotionEvent e) {
    gestures.onTouchEvent(e);
    if (area.width() <= 0) return true;
    float x = Math.max(0, Math.min(1, (e.getX() - area.left) / area.width())),
        y = Math.max(0, Math.min(1, (area.bottom - e.getY()) / area.height()));
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      getParent().requestDisallowInterceptTouchEvent(true);
      float best = 48 * getResources().getDisplayMetrics().density;
      selected = -1;
      for (int i = 0; i < points.size(); i++) {
        PointF q = points.get(i);
        float dist = (float) Math.hypot((q.x - x) * area.width(), (q.y - y) * area.height());
        if (dist < best) {
          best = dist;
          selected = i;
        }
      }
      if (selected < 0 && points.size() < 12 && x > .02 && x < .98) {
        points.add(new PointF(x, y));
        points.sort(Comparator.comparingDouble(a -> a.x));
        for (int i = 0; i < points.size(); i++) if (points.get(i).x == x) selected = i;
      }
    }
    if ((e.getActionMasked() == MotionEvent.ACTION_MOVE
            || e.getActionMasked() == MotionEvent.ACTION_DOWN)
        && selected >= 0) {
      PointF q = points.get(selected);
      q.y = y;
      if (selected > 0 && selected < points.size() - 1)
        q.x =
            Math.max(
                points.get(selected - 1).x + .01f, Math.min(points.get(selected + 1).x - .01f, x));
      invalidate();
    }
    if (e.getActionMasked() == MotionEvent.ACTION_UP
        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      getParent().requestDisallowInterceptTouchEvent(false);
      changed.run();
      performClick();
    }
    return true;
  }

  public boolean performClick() {
    super.performClick();
    return true;
  }
}
