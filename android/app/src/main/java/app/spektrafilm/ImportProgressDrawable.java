// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.animation.LinearInterpolator;

/**
 * Precision Instrument (方案 1 · 光学朱红游标) progress drawable shared by the 54dp home import slot
 * and the 6dp compact export rail, adapting dynamically to Darkroom Black and Studio Silver themes.
 */
final class ImportProgressDrawable extends Drawable {
  private final boolean compactTrack;
  private final Paint background = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint ticks = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint wash = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint railGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint rail = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint cursor = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Matrix bandMatrix = new Matrix();
  private final RectF bounds = new RectF();
  private final RectF outlineBounds = new RectF();
  private final RectF fillBounds = new RectF();
  private final Path clip = new Path();
  private final float density;

  private Shader tickShader, washShader, railGlowShader, railShader, cursorShader, bandShader;
  private boolean cachedLight;
  private boolean themeInitialized;
  private float cachedVisible = -1f;
  private boolean cachedComplete;
  private int cachedWidth = -1, cachedHeight = -1;
  private int masterAlpha = 255;

  private ValueAnimator animator;
  private boolean animating;
  private float phase, fraction;

  ImportProgressDrawable(Context context) {
    this(context, false);
  }

  static ImportProgressDrawable compactTrack(Context context) {
    return new ImportProgressDrawable(context, true);
  }

  private ImportProgressDrawable(Context context, boolean compactTrack) {
    this.compactTrack = compactTrack;
    density = context.getResources().getDisplayMetrics().density;
    outline.setStyle(Paint.Style.STROKE);
    outline.setStrokeWidth(Math.max(1f, density * (compactTrack ? .75f : .9f)));
    syncTheme(true);
  }

  private void syncTheme(boolean force) {
    boolean light = AppStyle.isLight();
    if (!force && themeInitialized && light == cachedLight) return;
    themeInitialized = true;
    cachedLight = light;
    if (compactTrack) {
      background.setColor(light ? 0xffdfdfe5 : 0xff1b1b20);
      outline.setColor(light ? 0xffcbcbd4 : 0xff2c2c34);
    } else {
      background.setColor(light ? 0xffe3e3e8 : 0xff141417);
      outline.setColor(light ? 0xffceced6 : 0xff2b2b32);
    }
    background.setAlpha(masterAlpha);
    outline.setAlpha(masterAlpha);
    invalidateShaders();
  }

  private void invalidateShaders() {
    tickShader = null;
    washShader = null;
    railGlowShader = null;
    railShader = null;
    cursorShader = null;
    bandShader = null;
    cachedVisible = -1f;
    cachedWidth = -1;
    cachedHeight = -1;
  }

  void setFraction(float value) {
    fraction = Math.max(0f, Math.min(1f, value));
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
      phase = 0f;
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
    invalidateShaders();
  }

  private void ensureStaticShaders() {
    int width = Math.round(bounds.width());
    int height = Math.round(bounds.height());
    if (width <= 0 || height <= 0) return;
    if (cachedWidth == width && cachedHeight == height && bandShader != null) return;
    cachedWidth = width;
    cachedHeight = height;

    boolean light = cachedLight;
    if (compactTrack) {
      ticks.setShader(null);
      ticks.setColor(light ? 0x14111113 : 0x14ffffff);
    } else {
      tickShader =
          new LinearGradient(
              0f, bounds.top + bounds.height() * .56f, 0f, bounds.bottom,
              light ? 0x00111113 : 0x00ffffff,
              light ? 0x16111113 : 0x12ffffff,
              Shader.TileMode.CLAMP);
      ticks.setShader(tickShader);
    }

    if (!compactTrack) {
      float railHeight = density * 3.5f;
      float glowHeight = density * 5.5f;
      railGlowShader =
          light
              ? null
              : new LinearGradient(
                  0f, bounds.bottom - railHeight - glowHeight,
                  0f, bounds.bottom - railHeight,
                  0x00f0523d, 0x44f0523d, Shader.TileMode.CLAMP);

      float cursorTop = bounds.top + density * 8f;
      float cursorBottom = Math.max(cursorTop + 1f, bounds.bottom - railHeight);
      cursorShader =
          new LinearGradient(
              0f, cursorTop, 0f, cursorBottom,
              light
                  ? new int[] {0x00c83422, 0xa6c83422, 0xffc83422}
                  : new int[] {0x00f0523d, 0xbff0523d, 0xfff0523d},
              new float[] {0f, .55f, 1f},
              Shader.TileMode.CLAMP);
    }

    float bandWidth = Math.max(1f, density * (compactTrack ? 48f : 72f));
    int bandPeak = compactTrack ? (light ? 0x4cffffff : 0x48ffffff) : (light ? 0x3affffff : 0x22ffffff);
    bandShader =
        new LinearGradient(
            0f, 0f, bandWidth, 0f,
            new int[] {0x00ffffff, bandPeak, 0x00ffffff},
            new float[] {0f, .5f, 1f},
            Shader.TileMode.CLAMP);
  }

  private void ensureProgressShaders(float visible, boolean complete) {
    float span = Math.max(1f, visible - bounds.left);
    if (railShader != null && Math.abs(visible - cachedVisible) < .5f && complete == cachedComplete)
      return;
    cachedVisible = visible;
    cachedComplete = complete;
    boolean light = cachedLight;

    if (compactTrack) {
      railShader =
          new LinearGradient(
              bounds.left, 0f, bounds.left + span, 0f,
              light
                  ? new int[] {0xffa32718, 0xffc83422, 0xffe34b38}
                  : new int[] {0xffc83a28, 0xfff0523d, 0xffff8472},
              new float[] {0f, .85f, 1f},
              Shader.TileMode.CLAMP);
      return;
    }

    if (complete) {
      washShader =
          new LinearGradient(
              bounds.left, 0f, bounds.left + span, 0f,
              light ? 0x1fc83422 : 0x2cf0523d,
              light ? 0x14c83422 : 0x1ef0523d,
              Shader.TileMode.CLAMP);
    } else {
      washShader =
          new LinearGradient(
              bounds.left, 0f, bounds.left + span, 0f,
              light
                  ? new int[] {0x21c83422, 0x14c83422, 0x00c83422}
                  : new int[] {0x30f0523d, 0x1ff0523d, 0x00f0523d},
              new float[] {0f, .82f, 1f},
              Shader.TileMode.CLAMP);
    }

    railShader =
        new LinearGradient(
            bounds.left, 0f, bounds.left + span, 0f,
            light
                ? new int[] {0xff9e2618, 0xffc83422, 0xffe84e3b}
                : new int[] {0xffb83525, 0xfff0523d, 0xffff8270},
            new float[] {0f, .86f, 1f},
            Shader.TileMode.CLAMP);
  }

  private float cornerRadius(float width, float height) {
    float maxRadius = Math.min(width, height) / 2f;
    return compactTrack ? maxRadius : Math.min(density * AppStyle.R_BUTTON, maxRadius);
  }

  @Override
  public void draw(Canvas canvas) {
    if (bounds.isEmpty()) return;
    syncTheme(false);
    ensureStaticShaders();

    float radius = cornerRadius(bounds.width(), bounds.height());
    clip.reset();
    clip.addRoundRect(bounds, radius, radius, Path.Direction.CW);
    int save = canvas.save();
    canvas.clipPath(clip);

    // 1. Recessed instrument track background
    canvas.drawRoundRect(bounds, radius, radius, background);

    // 2. Micro-etched vernier ticks
    float tickStep = density * (compactTrack ? 16f : 18f);
    float tickWidth = Math.max(1f, density * 1f);
    float tickTop = compactTrack ? bounds.top : bounds.top + bounds.height() * .56f;
    for (float x = bounds.left + tickStep; x < bounds.right - density * 4f; x += tickStep) {
      canvas.drawRect(x, tickTop, x + tickWidth, bounds.bottom, ticks);
    }

    // 3. Active progress fill
    float visible = bounds.left + bounds.width() * fraction;
    boolean complete = fraction >= .999f;
    if (fraction > 0f && visible > bounds.left) {
      ensureProgressShaders(visible, complete);
      if (compactTrack) {
        drawCompactFill(canvas, visible, radius, complete);
      } else {
        drawButtonFill(canvas, visible, complete);
      }
    }

    // 4. Crisp 1px engineering border
    float halfStroke = outline.getStrokeWidth() / 2f;
    outlineBounds.set(bounds);
    outlineBounds.inset(halfStroke, halfStroke);
    float strokeRadius = Math.max(0f, radius - halfStroke);
    canvas.drawRoundRect(outlineBounds, strokeRadius, strokeRadius, outline);
    canvas.restoreToCount(save);
  }

  private void drawCompactFill(Canvas canvas, float visible, float radius, boolean complete) {
    rail.setShader(railShader);
    fillBounds.set(bounds.left, bounds.top, visible, bounds.bottom);
    canvas.drawRoundRect(fillBounds, radius, radius, rail);

    if (animating && !complete && bandShader != null) {
      int layer = canvas.save();
      canvas.clipRect(bounds.left, bounds.top, visible, bounds.bottom);
      drawShimmerBand(canvas, visible, density * 48f);
      canvas.restoreToCount(layer);
    }
  }

  private void drawButtonFill(Canvas canvas, float visible, boolean complete) {
    // Subtle translucent vermilion wash across active region so centered INK text stays AA+ legible
    wash.setShader(washShader);
    canvas.drawRect(bounds.left, bounds.top, visible, bounds.bottom, wash);

    if (animating && !complete && bandShader != null) {
      int layer = canvas.save();
      canvas.clipRect(bounds.left, bounds.top, visible, bounds.bottom);
      drawShimmerBand(canvas, visible, density * 72f);
      canvas.restoreToCount(layer);
    }

    // Bottom 3.5dp precision vermilion rail + soft darkroom glow
    float railHeight = density * 3.5f;
    float railTop = bounds.bottom - railHeight;
    if (railGlowShader != null) {
      railGlow.setShader(railGlowShader);
      canvas.drawRect(bounds.left, railTop - density * 5.5f, visible, railTop, railGlow);
    }
    rail.setShader(railShader);
    canvas.drawRect(bounds.left, railTop, visible, bounds.bottom, rail);

    // Leading vernier hairline at active progress front (hidden near 0% and 100%)
    if (fraction > .02f && fraction < .99f && cursorShader != null) {
      float cursorWidth = Math.max(1f, density * 1.5f);
      float cursorLeft = Math.max(bounds.left, visible - cursorWidth / 2f);
      float cursorTop = bounds.top + density * 8f;
      cursor.setShader(cursorShader);
      canvas.drawRect(cursorLeft, cursorTop, cursorLeft + cursorWidth, railTop, cursor);
    }
  }

  private void drawShimmerBand(Canvas canvas, float visible, float bandWidth) {
    float travel = (visible - bounds.left) + bandWidth;
    float x = bounds.left - bandWidth + phase * travel;
    bandMatrix.setTranslate(x, 0f);
    bandShader.setLocalMatrix(bandMatrix);
    band.setShader(bandShader);
    canvas.drawRect(x, bounds.top, x + bandWidth, bounds.bottom, band);
  }

  @Override
  public void getOutline(Outline result) {
    Rect b = getBounds();
    float radius = cornerRadius(b.width(), b.height());
    result.setRoundRect(b, radius);
  }

  @Override
  public void setAlpha(int alpha) {
    masterAlpha = alpha;
    background.setAlpha(alpha);
    ticks.setAlpha(alpha);
    wash.setAlpha(alpha);
    railGlow.setAlpha(alpha);
    rail.setAlpha(alpha);
    cursor.setAlpha(alpha);
    band.setAlpha(alpha);
    outline.setAlpha(alpha);
    invalidateSelf();
  }

  @Override
  public void setColorFilter(ColorFilter filter) {
    for (Paint paint : new Paint[] {background, ticks, wash, railGlow, rail, cursor, band, outline}) {
      paint.setColorFilter(filter);
    }
    invalidateSelf();
  }

  @Override
  public int getOpacity() {
    return PixelFormat.TRANSLUCENT;
  }
}
