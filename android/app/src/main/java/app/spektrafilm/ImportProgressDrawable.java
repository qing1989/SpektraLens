// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

/** Iridescent glass progress inside the existing import button; no hard marker at the edge. */
final class ImportProgressDrawable extends Drawable {
  private static final int FILL_ALPHA = 245;
  private static final int TRACK = AppStyle.CONTROL;
  private static final int[] GLASS = {
    0xff55e6ff, 0xff7a5cff, 0xffff4fd8, 0xffffb347
  };
  private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint progress = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint gloss = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint shade = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint edgeFade = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Matrix bandMatrix = new Matrix();
  private final RectF bounds = new RectF();
  private final RectF outlineBounds = new RectF();
  private final Path clip = new Path();
  private final float density;
  private Shader spectrum, glossShader, shadeShader, bandShader, edgeShader;
  private ValueAnimator animator;
  private boolean animating;
  private float phase, fraction, edgeFraction = -1;
  private int edgeWidth = -1;

  ImportProgressDrawable(Context context) {
    density = context.getResources().getDisplayMetrics().density;
    background.setColor(TRACK);
    progress.setAlpha(FILL_ALPHA);
    outline.setStyle(Paint.Style.STROKE);
    outline.setStrokeWidth(Math.max(1, density * .6f));
    outline.setColor(AppStyle.OUTLINE);
  }

  void setFraction(float value) {
    fraction = Math.max(0, Math.min(1, value));
    invalidateSelf();
  }

  void setAnimating(boolean running) {
    animating = running;
    updateAnimation();
  }

  private void updateAnimation() {
    boolean motion = animating && isVisible() && ValueAnimator.areAnimatorsEnabled();
    if (motion && animator == null) {
      animator = ValueAnimator.ofFloat(0f, 1f);
      animator.setDuration(7000);
      animator.setRepeatCount(ValueAnimator.INFINITE);
      animator.setInterpolator(new LinearInterpolator());
      animator.addUpdateListener(
          value -> {
            phase = (float) value.getAnimatedValue();
            invalidateSelf();
          });
      animator.start();
    } else if (!motion && animator != null) {
      animator.cancel();
      animator = null;
      phase = 0;
      invalidateSelf();
    }
  }

  @Override
  public boolean setVisible(boolean visible, boolean restart) {
    boolean changed = super.setVisible(visible, restart);
    updateAnimation();
    return changed;
  }

  @Override
  protected void onBoundsChange(Rect rect) {
    bounds.set(rect);
    spectrum =
        new LinearGradient(
            rect.left, rect.top, rect.right, rect.bottom,
            GLASS, null, Shader.TileMode.CLAMP);
    glossShader =
        new LinearGradient(
            0, rect.top, 0, rect.top + rect.height() * .55f,
            0x38ffffff, 0x00ffffff, Shader.TileMode.CLAMP);
    shadeShader =
        new LinearGradient(
            0, rect.bottom, 0, rect.bottom - rect.height() * .42f,
            0x40000000, 0x00000000, Shader.TileMode.CLAMP);
    float bandWidth = Math.max(1, density * 70);
    bandShader =
        new LinearGradient(
            0, 0, bandWidth, 0,
            new int[] {0x00ffffff, 0x5cffffff, 0x00ffffff},
            new float[] {0, .5f, 1}, Shader.TileMode.CLAMP);
    edgeShader = null;
    edgeFraction = -1;
    edgeWidth = -1;
  }

  @Override
  public void draw(Canvas canvas) {
    if (bounds.isEmpty()) return;
    float radius = Math.min(bounds.width(), bounds.height()) / 2f;
    clip.reset();
    clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
    int save = canvas.save();
    canvas.clipPath(clip);
    canvas.drawRoundRect(bounds, radius, radius, background);
    float visible = bounds.left + bounds.width() * fraction;
    if (fraction > 0) {
      progress.setShader(spectrum);
      progress.setAlpha(FILL_ALPHA);
      canvas.drawRect(bounds.left, bounds.top, visible, bounds.bottom, progress);

      int layer = canvas.save();
      canvas.clipRect(bounds.left, bounds.top, visible, bounds.bottom);
      gloss.setShader(glossShader);
      canvas.drawRect(bounds.left, bounds.top, visible, bounds.bottom, gloss);
      shade.setShader(shadeShader);
      canvas.drawRect(bounds.left, bounds.top, visible, bounds.bottom, shade);
      // A slow, soft light sweep is the only moving highlight; no vertical marker line.
      if (animating && fraction < .999f) {
        float bandWidth = Math.max(1, density * 70);
        float travel = visible - bounds.left + bandWidth;
        float x = bounds.left - bandWidth + phase * travel;
        bandMatrix.setTranslate(x, 0);
        bandShader.setLocalMatrix(bandMatrix);
        band.setShader(bandShader);
        canvas.drawRect(x, bounds.top, x + bandWidth, bounds.bottom, band);
      }
      canvas.restoreToCount(layer);

      if (fraction < .999f) {
        float fade = Math.min(visible - bounds.left, density * 18);
        if (fade > 0) {
          if (edgeShader == null || edgeFraction != fraction || edgeWidth != bounds.width()) {
            edgeShader =
                new LinearGradient(
                    visible - fade, 0, visible, 0,
                    0x00241f28, 0xff241f28, Shader.TileMode.CLAMP);
            edgeFraction = fraction;
            edgeWidth = (int) bounds.width();
          }
          edgeFade.setShader(edgeShader);
          canvas.drawRect(visible - fade, bounds.top, visible, bounds.bottom, edgeFade);
        }
      }
    }
    float halfStroke = outline.getStrokeWidth() / 2;
    outlineBounds.set(bounds);
    outlineBounds.inset(halfStroke, halfStroke);
    canvas.drawRoundRect(
        outlineBounds, Math.max(0, radius - halfStroke), Math.max(0, radius - halfStroke), outline);
    canvas.restoreToCount(save);
  }

  @Override
  public void getOutline(Outline result) {
    result.setRoundRect(getBounds(), Math.min(bounds.width(), bounds.height()) / 2f);
  }

  @Override
  public void setAlpha(int alpha) {
    background.setAlpha(alpha);
    progress.setAlpha(Math.round(FILL_ALPHA * alpha / 255f));
    // Shader colors already carry their own alpha; paint alpha must not multiply it again.
    gloss.setAlpha(alpha);
    shade.setAlpha(alpha);
    band.setAlpha(alpha);
    edgeFade.setAlpha(alpha);
    outline.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    for (Paint paint : new Paint[] {background, progress, gloss, shade, band, edgeFade, outline})
      paint.setColorFilter(filter);
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
