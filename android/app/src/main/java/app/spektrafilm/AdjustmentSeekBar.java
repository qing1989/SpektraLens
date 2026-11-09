// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.widget.SeekBar;
import java.util.Arrays;

/** Native seeking and accessibility with a full gradient track and a white capsule thumb. */
final class AdjustmentSeekBar extends SeekBar {
  private final Track track;

  public AdjustmentSeekBar(Context context) {
    this(context, new int[] {0xff67646a, 0xffac7e58, 0xffff7a1a});
  }

  AdjustmentSeekBar(Context context, int[] colors) {
    super(context);
    float density = getResources().getDisplayMetrics().density;
    track = new Track(colors, Math.round(3 * density));
    Capsule thumb = new Capsule(Math.round(30 * density), Math.round(20 * density), density);
    setProgressTintList(null);
    setSecondaryProgressTintList(null);
    setProgressBackgroundTintList(null);
    setThumbTintList(null);
    setProgressDrawable(track);
    setThumb(thumb);
    setSplitTrack(false);
    setBackground(null);
    setMinWidth(0);
    setMinHeight(Math.round(3 * density));
    setMaxHeight(Math.round(3 * density));
    int half = thumb.getIntrinsicWidth() / 2;
    int inset = half + Math.max(2, Math.round(2 * density));
    setThumbOffset(half);
    setPadding(inset, 0, inset, 0);
  }

  void setTrackColors(int[] colors) {
    track.colors(colors);
  }

  private abstract static class StyledDrawable extends Drawable {
    final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    int alpha = 255;
    boolean enabled = true, active;

    public boolean isStateful() {
      return true;
    }

    protected boolean onStateChange(int[] states) {
      boolean nextEnabled = false, nextActive = false;
      for (int state : states) {
        if (state == android.R.attr.state_enabled) nextEnabled = true;
        if (state == android.R.attr.state_pressed || state == android.R.attr.state_focused)
          nextActive = true;
      }
      if (enabled == nextEnabled && active == nextActive) return false;
      enabled = nextEnabled;
      active = nextActive;
      invalidateSelf();
      return true;
    }

    public void setAlpha(int value) {
      alpha = value;
      invalidateSelf();
    }

    public void setColorFilter(ColorFilter filter) {
      paint.setColorFilter(filter);
      invalidateSelf();
    }

    public int getOpacity() {
      return PixelFormat.TRANSLUCENT;
    }

    void applyAlpha() {
      paint.setAlpha(enabled ? alpha : alpha * 2 / 5);
    }
  }

  private static final class Track extends StyledDrawable {
    private int[] colors;
    private final int height;

    Track(int[] colors, int height) {
      this.colors = colors.clone();
      this.height = Math.max(2, height);
    }

    void colors(int[] value) {
      if (Arrays.equals(colors, value)) return;
      colors = value.clone();
      onBoundsChange(getBounds());
      invalidateSelf();
    }

    protected void onBoundsChange(Rect bounds) {
      paint.setShader(
          new LinearGradient(
              bounds.left,
              0,
              Math.max(bounds.left + 1, bounds.right),
              0,
              colors,
              null,
              Shader.TileMode.CLAMP));
    }

    public int getIntrinsicHeight() {
      return height;
    }

    public void draw(Canvas canvas) {
      Rect bounds = getBounds();
      float radius = height / 2f, y = bounds.exactCenterY();
      applyAlpha();
      canvas.drawRoundRect(
          bounds.left, y - radius, bounds.right, y + radius, radius, radius, paint);
    }
  }

  private static final class Capsule extends StyledDrawable {
    private final int width, height;
    private final float density;
    private final RectF pill = new RectF();

    Capsule(int width, int height, float density) {
      this.width = width;
      this.height = height;
      this.density = density;
    }

    public int getIntrinsicWidth() {
      return width;
    }

    public int getIntrinsicHeight() {
      return height;
    }

    public void draw(Canvas canvas) {
      pill.set(getBounds());
      float radius = pill.height() / 2;
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(active ? Color.WHITE : 0xfff5f5f7);
      applyAlpha();
      canvas.drawRoundRect(pill, radius, radius, paint);
      float stroke = Math.max(1, density * .65f);
      pill.inset(stroke / 2, stroke / 2);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(stroke);
      paint.setColor(active ? 0xffff8c42 : 0xffd7d7dc);
      applyAlpha();
      canvas.drawRoundRect(pill, radius, radius, paint);
      paint.setStyle(Paint.Style.FILL);
    }
  }
}
