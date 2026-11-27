// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

/**
 * Pure-Java monotone Hermite curve math and 3-zone (shadows/midtones/highlights) bidirectional
 * slider synchronization, shared by {@link CurveEditor}, {@link MainActivity}, and JVM unit tests.
 */
final class CurveMath {
  static final int MAX_POINTS = 12;
  static final float ANCHOR_SHADOWS = 0.25f;
  static final float ANCHOR_MIDTONES = 0.50f;
  static final float ANCHOR_HIGHLIGHTS = 0.75f;
  static final int SLIDER_MIN = -50;
  static final int SLIDER_MAX = 50;
  static final float SLIDER_SCALE = 200f; // +/-50 maps to +/-0.25 normalized output offset
  static final float ANCHOR_MATCH_RADIUS = 0.06f;
  private static final float MIN_X_GAP = 0.01f;
  private static final float IDENTITY_EPS = 0.002f;

  private CurveMath() {}

  /** Evaluates a monotone Hermite spline at normalized input x in [0, 1]. */
  static float evaluate(float[] xs, float[] ys, int count, float x) {
    if (count < 2) return clamp01(x);
    float cx = clamp01(x);
    if (cx <= xs[0]) return clamp01(ys[0]);
    if (cx >= xs[count - 1]) return clamp01(ys[count - 1]);

    float[] slopes = new float[count - 1];
    float[] tangents = new float[count];
    computeTangents(xs, ys, count, slopes, tangents);

    int k = 0;
    while (k < count - 2 && cx > xs[k + 1]) k++;
    return evalSegment(xs[k], ys[k], tangents[k], xs[k + 1], ys[k + 1], tangents[k + 1], cx);
  }

  /** Precomputes Fritsch-Carlson monotone Hermite tangents into caller-supplied buffers. */
  static void computeTangents(
      float[] xs, float[] ys, int count, float[] slopesOut, float[] tangentsOut) {
    if (count < 2) return;
    for (int i = 0; i < count - 1; i++) {
      float dx = Math.max(1e-5f, xs[i + 1] - xs[i]);
      slopesOut[i] = (ys[i + 1] - ys[i]) / dx;
    }
    tangentsOut[0] = slopesOut[0];
    tangentsOut[count - 1] = slopesOut[count - 2];
    for (int i = 1; i < count - 1; i++) {
      float s0 = slopesOut[i - 1], s1 = slopesOut[i];
      tangentsOut[i] = s0 * s1 <= 0f ? 0f : 2f * s0 * s1 / (s0 + s1);
    }
  }

  static float evalSegment(
      float x0, float y0, float m0, float x1, float y1, float m1, float x) {
    float h = Math.max(1e-5f, x1 - x0);
    float t = clamp01((x - x0) / h);
    float t2 = t * t;
    float t3 = t2 * t;
    float y =
        (2f * t3 - 3f * t2 + 1f) * y0
            + (t3 - 2f * t2 + t) * h * m0
            + (-2f * t3 + 3f * t2) * y1
            + (t3 - t2) * h * m1;
    return clamp01(y);
  }

  /** Reads the current slider integer [-50, +50] for an anchor x (0.25 / 0.50 / 0.75). */
  static int readAnchorSlider(float[] xs, float[] ys, int count, float anchorX) {
    float y = evaluate(xs, ys, count, anchorX);
    int v = Math.round((y - anchorX) * SLIDER_SCALE);
    return Math.max(SLIDER_MIN, Math.min(SLIDER_MAX, v));
  }

  /**
   * Applies a tonal anchor slider value [-50, +50] onto a sorted control-point list.
   *
   * <p>Reuses any existing internal control point within {@link #ANCHOR_MATCH_RADIUS} of {@code
   * anchorX}, or inserts a new point at {@code anchorX} when space remains. Returning a slider to 0
   * removes a redundant anchor point when the remaining spline already passes through neutral.
   *
   * @return the resulting point list as {@code float[2][newCount]} where {@code [0]} is xs and
   *     {@code [1]} is ys.
   */
  static float[][] applyAnchorSlider(
      float[] xs, float[] ys, int count, float anchorX, int sliderValue) {
    int clampedVal = Math.max(SLIDER_MIN, Math.min(SLIDER_MAX, sliderValue));
    float targetY = clamp01(anchorX + clampedVal / SLIDER_SCALE);

    float[] curX = new float[count];
    float[] curY = new float[count];
    System.arraycopy(xs, 0, curX, 0, count);
    System.arraycopy(ys, 0, curY, 0, count);

    int match = findNearestInternal(curX, count, anchorX, ANCHOR_MATCH_RADIUS);
    if (match > 0) {
      if (clampedVal == 0 && Math.abs(curX[match] - anchorX) <= 0.015f) {
        // Check if removing this anchor leaves the curve neutral at anchorX.
        float[] remX = new float[count - 1];
        float[] remY = new float[count - 1];
        for (int i = 0, j = 0; i < count; i++) {
          if (i == match) continue;
          remX[j] = curX[i];
          remY[j] = curY[i];
          j++;
        }
        if (Math.abs(evaluate(remX, remY, count - 1, anchorX) - anchorX) < 0.003f) {
          return new float[][] {remX, remY};
        }
      }
      curY[match] = clamp01(curX[match] + clampedVal / SLIDER_SCALE);
      return new float[][] {curX, curY};
    }

    // No nearby internal point: if slider is 0 and curve is already neutral here, don't add noise.
    if (clampedVal == 0 && Math.abs(evaluate(curX, curY, count, anchorX) - anchorX) < 0.003f) {
      return new float[][] {curX, curY};
    }

    if (count < MAX_POINTS) {
      int insertAt = 1;
      while (insertAt < count && curX[insertAt] < anchorX) insertAt++;
      float minX = curX[insertAt - 1] + MIN_X_GAP;
      float maxX = curX[insertAt] - MIN_X_GAP;
      if (minX <= maxX) {
        float px = Math.max(minX, Math.min(maxX, anchorX));
        float[] nextX = new float[count + 1];
        float[] nextY = new float[count + 1];
        System.arraycopy(curX, 0, nextX, 0, insertAt);
        System.arraycopy(curY, 0, nextY, 0, insertAt);
        nextX[insertAt] = px;
        nextY[insertAt] = targetY;
        System.arraycopy(curX, insertAt, nextX, insertAt + 1, count - insertAt);
        System.arraycopy(curY, insertAt, nextY, insertAt + 1, count - insertAt);
        return new float[][] {nextX, nextY};
      }
    }

    // Fallback when curve is full (12 points) or slot is blocked: nudge nearest internal point.
    int fallback = findNearestInternal(curX, count, anchorX, 1f);
    if (fallback > 0) curY[fallback] = targetY;
    return new float[][] {curX, curY};
  }

  /** Returns true when all control points sit on the identity diagonal y = x (0,0 .. 1,1). */
  static boolean isIdentity(float[] xs, float[] ys, int count) {
    if (count < 2) return true;
    if (Math.abs(xs[0]) > IDENTITY_EPS || Math.abs(ys[0]) > IDENTITY_EPS) return false;
    if (Math.abs(xs[count - 1] - 1f) > IDENTITY_EPS
        || Math.abs(ys[count - 1] - 1f) > IDENTITY_EPS) return false;
    for (int i = 0; i < count; i++) {
      if (Math.abs(ys[i] - xs[i]) > IDENTITY_EPS) return false;
    }
    return true;
  }

  /** Formats a signed anchor slider value as "+12", "-8", or "0". */
  static String formatSliderValue(int value) {
    if (value > 0) return "+" + value;
    return Integer.toString(value);
  }

  private static int findNearestInternal(float[] xs, int count, float targetX, float maxDist) {
    int bestIdx = -1;
    float bestDist = maxDist;
    for (int i = 1; i < count - 1; i++) {
      float dist = Math.abs(xs[i] - targetX);
      if (dist <= bestDist) {
        bestDist = dist;
        bestIdx = i;
      }
    }
    return bestIdx;
  }

  private static float clamp01(float v) {
    return Math.max(0f, Math.min(1f, v));
  }
}
