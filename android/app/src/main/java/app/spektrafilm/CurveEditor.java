// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import java.util.*;
import org.json.*;

/**
 * Touch-editable monotone Hermite curve with Precision Instrument styling (clipped 4x4 grid, dashed
 * identity baseline, white-core ringed control points) and bidirectional tonal-anchor sync.
 */
final class CurveEditor extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final ArrayList<PointF> points = new ArrayList<>();
  private final RectF boxRect = new RectF();
  private final RectF plotRect = new RectF();
  private final Path boxClip = new Path();
  private final Path curvePath = new Path();
  private final float[] xsBuf = new float[CurveMath.MAX_POINTS];
  private final float[] ysBuf = new float[CurveMath.MAX_POINTS];
  private final float[] slopesBuf = new float[CurveMath.MAX_POINTS];
  private final float[] tangentsBuf = new float[CurveMath.MAX_POINTS];
  private int selected = -1;
  private boolean dirty;
  private final int color;
  private final Runnable onLiveChanged;
  private final Runnable onCommitted;
  private final GestureDetector gestures;
  private float lastDashDensity = -1f;

  CurveEditor(
      Context c, JSONArray json, int color, Runnable onLiveChanged, Runnable onCommitted) {
    super(c);
    this.color = color;
    this.onLiveChanged = onLiveChanged;
    this.onCommitted = onCommitted;
    setContentDescription("曲线编辑器：点击添加点，拖动调整，长按删除内部控制点");
    loadPoints(json);
    gestures =
        new GestureDetector(
            c,
            new GestureDetector.SimpleOnGestureListener() {
              public void onLongPress(MotionEvent e) {
                if (selected > 0 && selected < points.size() - 1) {
                  points.remove(selected);
                  selected = -1;
                  dirty = false;
                  if (Haptics.enabled) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                  }
                  invalidate();
                  notifyLive();
                  notifyCommitted();
                }
              }
            });
  }

  private void loadPoints(JSONArray json) {
    points.clear();
    if (json != null) {
      for (int i = 0; i < json.length() && points.size() < CurveMath.MAX_POINTS; i++) {
        JSONArray v = json.optJSONArray(i);
        if (v != null) {
          float px = Math.max(0f, Math.min(1f, (float) v.optDouble(0)));
          float py = Math.max(0f, Math.min(1f, (float) v.optDouble(1)));
          points.add(new PointF(px, py));
        }
      }
    }
    if (points.size() < 2) {
      points.clear();
      points.add(new PointF(0f, 0f));
      points.add(new PointF(1f, 1f));
    }
    points.sort(Comparator.comparingDouble(a -> a.x));
    for (int i = 1; i < points.size(); i++) {
      if (points.get(i).x <= points.get(i - 1).x + 0.001f) {
        points.get(i).x = Math.min(1f, points.get(i - 1).x + 0.001f);
      }
    }
  }

  void resetToIdentity() {
    points.clear();
    points.add(new PointF(0f, 0f));
    points.add(new PointF(1f, 1f));
    selected = -1;
    dirty = false;
    invalidate();
  }

  boolean isIdentity() {
    int n = copyPointsToBuffers();
    return CurveMath.isIdentity(xsBuf, ysBuf, n);
  }

  int readAnchorSlider(float anchorX) {
    int n = copyPointsToBuffers();
    return CurveMath.readAnchorSlider(xsBuf, ysBuf, n, anchorX);
  }

  void applyAnchorSlider(float anchorX, int sliderValue) {
    int n = copyPointsToBuffers();
    float[][] next = CurveMath.applyAnchorSlider(xsBuf, ysBuf, n, anchorX, sliderValue);
    points.clear();
    for (int i = 0; i < next[0].length; i++) {
      points.add(new PointF(next[0][i], next[1][i]));
    }
    invalidate();
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

  private int copyPointsToBuffers() {
    int n = Math.min(points.size(), CurveMath.MAX_POINTS);
    for (int i = 0; i < n; i++) {
      PointF q = points.get(i);
      xsBuf[i] = q.x;
      ysBuf[i] = q.y;
    }
    return n;
  }

  private void notifyLive() {
    if (onLiveChanged != null) onLiveChanged.run();
  }

  private void notifyCommitted() {
    if (onCommitted != null) onCommitted.run();
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    float d = getResources().getDisplayMetrics().density;
    float w = getWidth(), h = getHeight();
    if (w <= 0 || h <= 0) return;

    float strokeW = Math.max(1f, d);
    float half = strokeW * 0.5f;
    float corner = AppStyle.R_MEDIUM * d;
    float pad = 10f * d;

    boxRect.set(half, half, w - half, h - half);
    plotRect.set(
        boxRect.left + pad, boxRect.top + pad, boxRect.right - pad, boxRect.bottom - pad);
    if (plotRect.width() <= 1f || plotRect.height() <= 1f) return;

    boxClip.reset();
    boxClip.addRoundRect(boxRect, corner, corner, Path.Direction.CW);

    boolean light = AppStyle.isLight();
    int gridColor = light ? 0x38787886 : 0x29a0a0af;
    int diagColor = light ? 0x55787886 : 0x48a0a0af;

    int save = c.save();
    c.clipPath(boxClip);

    // 1. Instrument stage background
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(AppStyle.STAGE_BG);
    c.drawRect(boxRect, paint);

    // 2. Crisp 4x4 internal grid (3x3 inner lines aligned to 0.25 / 0.50 / 0.75 plot anchors)
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(Math.max(1f, d));
    paint.setColor(gridColor);
    for (int i = 1; i <= 3; i++) {
      float t = i / 4f;
      float gx = plotRect.left + t * plotRect.width();
      float gy = plotRect.top + t * plotRect.height();
      c.drawLine(gx, boxRect.top, gx, boxRect.bottom, paint);
      c.drawLine(boxRect.left, gy, boxRect.right, gy, paint);
    }

    // 3. Dashed diagonal identity reference line
    if (lastDashDensity != d) {
      lastDashDensity = d;
      dashPaint.setStyle(Paint.Style.STROKE);
      dashPaint.setPathEffect(new DashPathEffect(new float[] {4f * d, 4f * d}, 0f));
    }
    dashPaint.setStrokeWidth(Math.max(1f, d));
    dashPaint.setColor(diagColor);
    c.drawLine(plotRect.left, plotRect.bottom, plotRect.right, plotRect.top, dashPaint);

    // 4. Monotone Hermite curve
    int n = copyPointsToBuffers();
    CurveMath.computeTangents(xsBuf, ysBuf, n, slopesBuf, tangentsBuf);
    curvePath.reset();
    int k = 0;
    for (int i = 0; i <= 256; i++) {
      float x = i / 256f;
      while (k < n - 2 && x > xsBuf[k + 1]) k++;
      float y =
          CurveMath.evalSegment(
              xsBuf[k], ysBuf[k], tangentsBuf[k], xsBuf[k + 1], ysBuf[k + 1], tangentsBuf[k + 1],
              x);
      float px = plotRect.left + x * plotRect.width();
      float py = plotRect.bottom - y * plotRect.height();
      if (i == 0) curvePath.moveTo(px, py);
      else curvePath.lineTo(px, py);
    }
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeWidth(2.4f * d);
    paint.setColor(color);
    c.drawPath(curvePath, paint);

    // 5. Control points (white-core ringed dots; stage-core when curve itself is INK)
    int coreColor =
        color == AppStyle.INK ? AppStyle.STAGE_BG : (light ? 0xffffffff : 0xfff4f4f6);
    for (int i = 0; i < n; i++) {
      float cx = plotRect.left + xsBuf[i] * plotRect.width();
      float cy = plotRect.bottom - ysBuf[i] * plotRect.height();
      boolean endpoint = i == 0 || i == n - 1;
      boolean active = i == selected;
      if (active) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(AppStyle.withAlpha(color, light ? 0.18f : 0.24f));
        c.drawCircle(cx, cy, 11f * d, paint);
      }
      float r = (active ? 5.6f : (endpoint ? 3.8f : 4.8f)) * d;
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(coreColor);
      c.drawCircle(cx, cy, r, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth((endpoint && !active ? 1.8f : 2.1f) * d);
      paint.setColor(color);
      c.drawCircle(cx, cy, r, paint);
    }

    c.restoreToCount(save);

    // 6. Crisp 1dp rounded outer bezel
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(strokeW);
    paint.setColor(AppStyle.OUTLINE);
    c.drawRoundRect(boxRect, corner, corner, paint);
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    gestures.onTouchEvent(e);
    if (plotRect.width() <= 0 || plotRect.height() <= 0) return true;
    float x = Math.max(0f, Math.min(1f, (e.getX() - plotRect.left) / plotRect.width()));
    float y = Math.max(0f, Math.min(1f, (plotRect.bottom - e.getY()) / plotRect.height()));
    if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
      if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
      float best = 44f * getResources().getDisplayMetrics().density;
      selected = -1;
      dirty = false;
      for (int i = 0; i < points.size(); i++) {
        PointF q = points.get(i);
        float dist =
            (float) Math.hypot((q.x - x) * plotRect.width(), (q.y - y) * plotRect.height());
        if (dist < best) {
          best = dist;
          selected = i;
        }
      }
      if (selected < 0 && points.size() < CurveMath.MAX_POINTS && x > .02f && x < .98f) {
        int insertAt = 1;
        while (insertAt < points.size() && points.get(insertAt).x < x) insertAt++;
        float minX = points.get(insertAt - 1).x + .01f;
        float maxX = points.get(insertAt).x - .01f;
        if (minX <= maxX) {
          float clampedX = Math.max(minX, Math.min(maxX, x));
          points.add(insertAt, new PointF(clampedX, y));
          selected = insertAt;
          dirty = true;
          invalidate();
          notifyLive();
        }
      } else if (selected >= 0) {
        invalidate();
      }
    }
    if ((e.getActionMasked() == MotionEvent.ACTION_MOVE
            || e.getActionMasked() == MotionEvent.ACTION_DOWN)
        && selected >= 0) {
      PointF q = points.get(selected);
      float newY = y;
      float newX = q.x;
      if (selected > 0 && selected < points.size() - 1) {
        float minX = points.get(selected - 1).x + .01f;
        float maxX = points.get(selected + 1).x - .01f;
        if (minX <= maxX) newX = Math.max(minX, Math.min(maxX, x));
      }
      if (Math.abs(newX - q.x) > 1e-4f || Math.abs(newY - q.y) > 1e-4f) {
        q.x = newX;
        q.y = newY;
        dirty = true;
        notifyLive();
      }
      invalidate();
    }
    if (e.getActionMasked() == MotionEvent.ACTION_UP
        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
      if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
      boolean shouldCommit = dirty;
      dirty = false;
      selected = -1;
      invalidate();
      if (shouldCommit) notifyCommitted();
      performClick();
    }
    return true;
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }
}
