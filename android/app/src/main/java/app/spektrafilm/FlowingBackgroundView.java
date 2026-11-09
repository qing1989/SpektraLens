// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.Choreographer;
import android.view.View;

/**
 * A black canvas with very slow, dim light fields; no image/video assets or per-frame allocation.
 */
public final class FlowingBackgroundView extends View implements Choreographer.FrameCallback {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Shader silver =
      new RadialGradient(
          0,
          0,
          1,
          new int[] {0xff202020, 0xc0121212, 0x00000000},
          new float[] {0, .45f, 1},
          Shader.TileMode.CLAMP);
  private final Shader warm =
      new RadialGradient(
          0,
          0,
          1,
          new int[] {0xdf211a10, 0xa013100b, 0x00000000},
          new float[] {0, .4f, 1},
          Shader.TileMode.CLAMP);
  private final Shader ink =
      new RadialGradient(
          0,
          0,
          1,
          new int[] {0xd0181818, 0x80101010, 0x00000000},
          new float[] {0, .5f, 1},
          Shader.TileMode.CLAMP);
  private boolean motionEnabled = true;
  private boolean hostResumed;
  private boolean scheduled;
  private long lastFrameNanos;
  private double seconds;
  private float fade = 1;

  public FlowingBackgroundView(Context context) {
    super(context);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    setClickable(false);
    setFocusable(false);
  }

  public void setMotionEnabled(boolean enabled) {
    if (motionEnabled != enabled) fade = enabled ? 0 : 1;
    motionEnabled = enabled;
    invalidate();
    updateScheduling();
  }

  public boolean isMotionEnabled() {
    return motionEnabled;
  }

  public void setHostResumed(boolean resumed) {
    hostResumed = resumed;
    updateScheduling();
  }

  private boolean shouldAnimate() {
    return motionEnabled
        && hostResumed
        && isAttachedToWindow()
        && getWindowVisibility() == VISIBLE
        && isShown()
        && hasWindowFocus()
        && ValueAnimator.areAnimatorsEnabled();
  }

  private void updateScheduling() {
    if (shouldAnimate()) {
      if (!scheduled) {
        lastFrameNanos = 0;
        scheduled = true;
        Choreographer.getInstance().postFrameCallback(this);
      }
    } else {
      if (scheduled) Choreographer.getInstance().removeFrameCallback(this);
      scheduled = false;
      lastFrameNanos = 0;
    }
  }

  @Override
  public void doFrame(long frameTimeNanos) {
    scheduled = false;
    if (!shouldAnimate()) {
      lastFrameNanos = 0;
      return;
    }
    double elapsed =
        lastFrameNanos == 0 ? 0 : Math.min(.1, (frameTimeNanos - lastFrameNanos) / 1e9);
    seconds += elapsed;
    fade = Math.min(1, fade + (float) elapsed * .45f);
    lastFrameNanos = frameTimeNanos;
    invalidate();
    // Slow shapes do not need a high refresh rate: approximately 20 updates/sec
    // on a 60 Hz display, and no callbacks while another window or app is active.
    scheduled = true;
    Choreographer.getInstance().postFrameCallbackDelayed(this, 40);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    canvas.drawColor(Color.BLACK);
    if (!motionEnabled || !ValueAnimator.areAnimatorsEnabled()) return;
    float w = getWidth(), h = getHeight();
    if (w == 0 || h == 0) return;
    float t = (float) seconds;
    paint.setAlpha(Math.round(255 * fade));
    field(
        canvas,
        silver,
        w * (.25f + .29f * (float) Math.sin(t * .075f)),
        h * (.20f + .13f * (float) Math.cos(t * .063f)),
        w * .95f,
        h * .61f);
    field(
        canvas,
        warm,
        w * (.72f + .25f * (float) Math.sin(t * .052f + 2)),
        h * (.67f + .20f * (float) Math.sin(t * .071f + .3f)),
        w * .88f,
        h * .49f);
    field(
        canvas,
        ink,
        w * (.46f + .29f * (float) Math.cos(t * .085f + 1.4f)),
        h * (.45f + .28f * (float) Math.sin(t * .058f + 1)),
        w * .70f,
        h * .43f);
    paint.setShader(null);
    paint.setAlpha(255);
  }

  private void field(Canvas canvas, Shader shader, float x, float y, float rx, float ry) {
    canvas.save();
    canvas.translate(x, y);
    canvas.scale(rx, ry);
    paint.setShader(shader);
    canvas.drawCircle(0, 0, 1, paint);
    canvas.restore();
  }

  @Override
  public boolean isOpaque() {
    return true;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    updateScheduling();
  }

  @Override
  protected void onDetachedFromWindow() {
    hostResumed = false;
    updateScheduling();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onWindowVisibilityChanged(int visibility) {
    super.onWindowVisibilityChanged(visibility);
    updateScheduling();
  }

  @Override
  protected void onVisibilityChanged(View changedView, int visibility) {
    super.onVisibilityChanged(changedView, visibility);
    updateScheduling();
  }

  @Override
  public void onWindowFocusChanged(boolean hasWindowFocus) {
    super.onWindowFocusChanged(hasWindowFocus);
    updateScheduling();
  }
}
