// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

/** Pure-JVM verification for {@link CurveMath} spline evaluation and 3-slider sync. */
public final class CurveMathTest {
  private static int passed;

  public static void main(String[] args) {
    testIdentityAndEvaluation();
    testThreeAnchorRoundTrip();
    testResetCollapsesBackToIdentity();
    testPreservesCustomControlPoints();
    testReusesNearbyAnchorPoint();
    testClampingAndMaxPointsSafety();
    testFormatting();
    System.out.println("CurveMathTest: " + passed + " checks passed");
  }

  private static void testIdentityAndEvaluation() {
    float[] xs = {0f, 1f};
    float[] ys = {0f, 1f};
    check(CurveMath.isIdentity(xs, ys, 2), "identity endpoints must be identity");
    checkEq(CurveMath.readAnchorSlider(xs, ys, 2, CurveMath.ANCHOR_SHADOWS), 0, "shadows=0");
    checkEq(CurveMath.readAnchorSlider(xs, ys, 2, CurveMath.ANCHOR_MIDTONES), 0, "midtones=0");
    checkEq(CurveMath.readAnchorSlider(xs, ys, 2, CurveMath.ANCHOR_HIGHLIGHTS), 0, "highlights=0");
    checkNear(CurveMath.evaluate(xs, ys, 2, 0.37f), 0.37f, 1e-4f, "linear eval");
  }

  private static void testThreeAnchorRoundTrip() {
    float[] xs = {0f, 1f};
    float[] ys = {0f, 1f};
    float[][] s1 = CurveMath.applyAnchorSlider(xs, ys, 2, CurveMath.ANCHOR_SHADOWS, -8);
    float[][] s2 =
        CurveMath.applyAnchorSlider(s1[0], s1[1], s1[0].length, CurveMath.ANCHOR_MIDTONES, 6);
    float[][] s3 =
        CurveMath.applyAnchorSlider(s2[0], s2[1], s2[0].length, CurveMath.ANCHOR_HIGHLIGHTS, 12);

    int n = s3[0].length;
    checkEq(n, 5, "3 anchor sliders create 5 total points");
    check(!CurveMath.isIdentity(s3[0], s3[1], n), "bent S-curve is not identity");
    checkEq(CurveMath.readAnchorSlider(s3[0], s3[1], n, CurveMath.ANCHOR_SHADOWS), -8, "read -8");
    checkEq(CurveMath.readAnchorSlider(s3[0], s3[1], n, CurveMath.ANCHOR_MIDTONES), 6, "read +6");
    checkEq(
        CurveMath.readAnchorSlider(s3[0], s3[1], n, CurveMath.ANCHOR_HIGHLIGHTS), 12, "read +12");

    // Strictly increasing X and bounded Y everywhere
    for (int i = 1; i < n; i++) {
      check(s3[0][i] > s3[0][i - 1], "strictly increasing x");
    }
    for (int i = 0; i <= 20; i++) {
      float y = CurveMath.evaluate(s3[0], s3[1], n, i / 20f);
      check(y >= 0f && y <= 1f, "eval y bounded in [0,1]");
    }
  }

  private static void testResetCollapsesBackToIdentity() {
    float[] xs = {0f, 1f};
    float[] ys = {0f, 1f};
    float[][] s1 = CurveMath.applyAnchorSlider(xs, ys, 2, CurveMath.ANCHOR_MIDTONES, 18);
    checkEq(s1[0].length, 3, "midtone point inserted");
    float[][] s2 =
        CurveMath.applyAnchorSlider(s1[0], s1[1], s1[0].length, CurveMath.ANCHOR_MIDTONES, 0);
    checkEq(s2[0].length, 2, "resetting sole anchor back to 0 collapses to 2 endpoints");
    check(CurveMath.isIdentity(s2[0], s2[1], 2), "collapsed curve is identity");

    // Neutral slider 0 on identity does not insert a spurious point
    float[][] s3 = CurveMath.applyAnchorSlider(xs, ys, 2, CurveMath.ANCHOR_SHADOWS, 0);
    checkEq(s3[0].length, 2, "0 on identity inserts nothing");
  }

  private static void testPreservesCustomControlPoints() {
    float[] xs = {0f, 0.38f, 1f};
    float[] ys = {0f, 0.52f, 1f};
    float[][] next = CurveMath.applyAnchorSlider(xs, ys, 3, CurveMath.ANCHOR_HIGHLIGHTS, -14);
    checkEq(next[0].length, 4, "adds highlight anchor alongside custom point");
    checkNear(next[0][1], 0.38f, 1e-4f, "custom x preserved");
    checkNear(next[1][1], 0.52f, 1e-4f, "custom y preserved");
    checkEq(
        CurveMath.readAnchorSlider(next[0], next[1], 4, CurveMath.ANCHOR_HIGHLIGHTS),
        -14,
        "highlight reads -14");
  }

  private static void testReusesNearbyAnchorPoint() {
    float[] xs = {0f, 0.23f, 1f};
    float[] ys = {0f, 0.20f, 1f};
    float[][] next = CurveMath.applyAnchorSlider(xs, ys, 3, CurveMath.ANCHOR_SHADOWS, 10);
    checkEq(next[0].length, 3, "nearby 0.23 point reused instead of duplicating");
    checkNear(next[0][1], 0.23f, 1e-4f, "reused point keeps x");
    checkNear(next[1][1], 0.28f, 1e-4f, "reused point applies +0.05 delta above diagonal");
  }

  private static void testClampingAndMaxPointsSafety() {
    float[] baseX = {0f, 1f};
    float[] baseY = {0f, 1f};
    float[][] hi = CurveMath.applyAnchorSlider(baseX, baseY, 2, CurveMath.ANCHOR_MIDTONES, 200);
    checkEq(
        CurveMath.readAnchorSlider(hi[0], hi[1], hi[0].length, CurveMath.ANCHOR_MIDTONES),
        CurveMath.SLIDER_MAX,
        "clamped to +50");
    float[][] lo = CurveMath.applyAnchorSlider(baseX, baseY, 2, CurveMath.ANCHOR_SHADOWS, -200);
    checkEq(
        CurveMath.readAnchorSlider(lo[0], lo[1], lo[0].length, CurveMath.ANCHOR_SHADOWS),
        CurveMath.SLIDER_MIN,
        "clamped to -50");

    float[] xs = new float[CurveMath.MAX_POINTS];
    float[] ys = new float[CurveMath.MAX_POINTS];
    for (int i = 0; i < CurveMath.MAX_POINTS; i++) {
      xs[i] = i / (float) (CurveMath.MAX_POINTS - 1);
      ys[i] = xs[i];
    }
    float[][] next =
        CurveMath.applyAnchorSlider(xs, ys, CurveMath.MAX_POINTS, CurveMath.ANCHOR_MIDTONES, 200);
    checkEq(next[0].length, CurveMath.MAX_POINTS, "never exceeds MAX_POINTS");
    check(
        CurveMath.readAnchorSlider(next[0], next[1], next[0].length, CurveMath.ANCHOR_MIDTONES) > 0,
        "full curve still responds positively to +slider");
  }

  private static void testFormatting() {
    check("+12".equals(CurveMath.formatSliderValue(12)), "+12 format");
    check("-8".equals(CurveMath.formatSliderValue(-8)), "-8 format");
    check("0".equals(CurveMath.formatSliderValue(0)), "0 format");
  }

  private static void check(boolean cond, String msg) {
    if (!cond) throw new AssertionError(msg);
    passed++;
  }

  private static void checkEq(int actual, int expected, String msg) {
    if (actual != expected) {
      throw new AssertionError(msg + " (expected " + expected + ", got " + actual + ")");
    }
    passed++;
  }

  private static void checkNear(float actual, float expected, float eps, String msg) {
    if (Math.abs(actual - expected) > eps) {
      throw new AssertionError(msg + " (expected " + expected + ", got " + actual + ")");
    }
    passed++;
  }
}
