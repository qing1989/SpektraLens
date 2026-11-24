// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.SeekBar;
import java.util.Arrays;

/** Native seeking and accessibility with a full gradient track and a white capsule thumb. */
final class AdjustmentSeekBar extends SeekBar {
  private final Track track;
  private OnSeekBarChangeListener delegate;
  private int lastProgress, detent = Integer.MIN_VALUE;
  private long lastTick;
  private final int touchSlop, doubleTapSlop;
  private Runnable reset;
  private MotionEvent touchDown, pendingTap;
  private boolean dragging, secondTap;
  private final Runnable confirmTap = this::finishPendingTap;

  /** 拖动时每跨一格刻度震一下；过零刻度换成更明确的一下，方便盲拖回默认值。 */
  private final OnSeekBarChangeListener ticker =
      new OnSeekBarChangeListener() {
        public void onProgressChanged(SeekBar bar, int pos, boolean user) {
          if (user) tick(pos);
          else lastProgress = pos; // 程序设值只对齐基线，不震动
          if (delegate != null) delegate.onProgressChanged(bar, pos, user);
        }

        public void onStartTrackingTouch(SeekBar bar) {
          lastProgress = bar.getProgress();
          if (delegate != null) delegate.onStartTrackingTouch(bar);
        }

        public void onStopTrackingTouch(SeekBar bar) {
          if (delegate != null) delegate.onStopTrackingTouch(bar);
        }
      };

  public AdjustmentSeekBar(Context context) {
    this(context, new int[] {0xff67646a, 0xffac7e58, 0xffff7a1a});
  }

  AdjustmentSeekBar(Context context, int[] colors) {
    super(context);
    ViewConfiguration configuration = ViewConfiguration.get(context);
    touchSlop = configuration.getScaledTouchSlop();
    doubleTapSlop = configuration.getScaledDoubleTapSlop();
    float density = getResources().getDisplayMetrics().density;
    track = new Track(colors, Math.round(2f * density), density);
    VernierThumb thumb =
        new VernierThumb(Math.round(16 * density), Math.round(26 * density), density);
    setProgressTintList(null);
    setSecondaryProgressTintList(null);
    setProgressBackgroundTintList(null);
    setThumbTintList(null);
    setProgressDrawable(track);
    setThumb(thumb);
    setSplitTrack(false);
    setBackground(null);
    setMinWidth(0);
    setMinHeight(Math.round(14 * density));
    setMaxHeight(Math.round(14 * density));
    int half = thumb.getIntrinsicWidth() / 2;
    // Leave generous thumb travel clearance from both ends so 0% / 100% never hug the screen edge.
    int inset = Math.max(half + Math.round(6 * density), Math.round(14 * density));
    setThumbOffset(half);
    setPadding(inset, 0, inset, 0);
  }

  void setOnResetListener(Runnable listener) {
    reset = listener;
  }

  /**
   * 暂存单击，确认不是双击后才交给原生 SeekBar；拖动一旦越过系统阈值立即交接。
   * 这样双击不会先跳到点击位置、写入一次历史，再复位。双击也必须在第二次抬手时才生效，
   * 避免两次快速拖动、长按、竖直滚动或多指触摸误复位。
   */
  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (reset == null) return super.onTouchEvent(event);
    if (!isEnabled()) {
      cancelTouch();
      return false;
    }
    int action = event.getActionMasked();
    if (action == MotionEvent.ACTION_CANCEL || event.getPointerCount() > 1) {
      cancelTouch();
      return true;
    }
    if (action == MotionEvent.ACTION_DOWN) {
      long interval = pendingTap == null ? -1 : event.getEventTime() - pendingTap.getEventTime();
      secondTap =
          pendingTap != null
              && interval >= 0
              && interval <= ViewConfiguration.getDoubleTapTimeout()
              && distanceSquared(event, pendingTap) <= doubleTapSlop * doubleTapSlop;
      if (secondTap) discardPendingTap();
      else finishPendingTap();
      releaseDown();
      touchDown = MotionEvent.obtain(event);
      dragging = false;
      setPressed(true);
      return true;
    }
    if (touchDown == null) return true;
    if (action == MotionEvent.ACTION_MOVE) {
      if (!dragging && distanceSquared(event, touchDown) > touchSlop * touchSlop) {
        if (Math.abs(event.getY() - touchDown.getY())
            > Math.abs(event.getX() - touchDown.getX())) {
          // 让面板的 ScrollView 接管竖直滚动，不改变参数。
          cancelTouch();
          return true;
        }
        dragging = true;
        secondTap = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        super.onTouchEvent(touchDown);
      }
      if (dragging) super.onTouchEvent(event);
      return true;
    }
    if (action == MotionEvent.ACTION_UP) {
      if (dragging) {
        super.onTouchEvent(event);
      } else if (distanceSquared(event, touchDown) <= touchSlop * touchSlop) {
        boolean shortTap =
            event.getEventTime() - touchDown.getEventTime()
                < ViewConfiguration.getLongPressTimeout();
        if (secondTap && shortTap) {
          reset.run();
          performClick();
        } else if (shortTap) {
          pendingTap = MotionEvent.obtain(event);
          postDelayed(confirmTap, ViewConfiguration.getDoubleTapTimeout());
        } else {
          seekTap(event);
        }
      }
      releaseDown();
      dragging = secondTap = false;
      setPressed(false);
      if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }
    return true;
  }

  /** 切换/收起工具前提交已经结束的单击，不把它带到下一张照片或另一页。 */
  void finishPendingTap() {
    removeCallbacks(confirmTap);
    MotionEvent tap = pendingTap;
    pendingTap = null;
    if (tap == null) return;
    if (isEnabled() && isShown()) seekTap(tap);
    tap.recycle();
  }

  void finishPendingTapOutside(float rawX, float rawY) {
    if (pendingTap == null) return;
    int[] position = new int[2];
    getLocationOnScreen(position);
    if (rawX < position[0] || rawX >= position[0] + getWidth()
        || rawY < position[1] || rawY >= position[1] + getHeight()) finishPendingTap();
  }

  private void seekTap(MotionEvent tap) {
    MotionEvent down = MotionEvent.obtain(tap);
    down.setAction(MotionEvent.ACTION_DOWN);
    super.onTouchEvent(down);
    super.onTouchEvent(tap);
    down.recycle();
    performClick();
  }

  private static float distanceSquared(MotionEvent a, MotionEvent b) {
    float dx = a.getX() - b.getX(), dy = a.getY() - b.getY();
    return dx * dx + dy * dy;
  }

  private void releaseDown() {
    if (touchDown != null) touchDown.recycle();
    touchDown = null;
  }

  private void discardPendingTap() {
    removeCallbacks(confirmTap);
    if (pendingTap != null) pendingTap.recycle();
    pendingTap = null;
  }

  private void cancelTouch() {
    discardPendingTap();
    if (dragging && touchDown != null) {
      MotionEvent cancel = MotionEvent.obtain(touchDown);
      cancel.setAction(MotionEvent.ACTION_CANCEL);
      super.onTouchEvent(cancel);
      cancel.recycle();
    }
    releaseDown();
    dragging = secondTap = false;
    setPressed(false);
  }

  @Override
  protected void onDetachedFromWindow() {
    cancelTouch();
    super.onDetachedFromWindow();
  }

  @Override
  public void setEnabled(boolean enabled) {
    if (!enabled && reset != null) cancelTouch();
    super.setEnabled(enabled);
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    if (reset != null && isEnabled())
      info.addAction(
          new AccessibilityNodeInfo.AccessibilityAction(
              R.id.reset_adjustment, "恢复默认值"));
  }

  @Override
  public boolean performAccessibilityAction(int action, Bundle arguments) {
    if (action == R.id.reset_adjustment && reset != null && isEnabled()) {
      cancelTouch();
      reset.run();
      return true;
    }
    return super.performAccessibilityAction(action, arguments);
  }

  @Override
  public void setOnSeekBarChangeListener(OnSeekBarChangeListener listener) {
    delegate = listener;
    super.setOnSeekBarChangeListener(listener == null ? null : ticker);
  }

  /** 双极参数（-x..+y）把 0（默认值）所在格设为刻度，拖过或落上时换成更重的一下。 */
  void setDetentProgress(int progress) {
    detent = progress;
  }

  private void tick(int pos) {
    int from = lastProgress;
    lastProgress = pos;
    if (pos == from) return; // 顶在端点或格内微动，不震
    boolean zero =
        detent != Integer.MIN_VALUE
            && ((from < detent && pos >= detent) || (from > detent && pos <= detent));
    long now = SystemClock.uptimeMillis();
    // 刻度限 25 次/秒，快速甩动是连续的嗒嗒声而不是一片嗡嗡；过零不受限频。
    if (zero || now - lastTick >= 40) {
      lastTick = now;
      if (Haptics.enabled) {
        performHapticFeedback(
            zero ? HapticFeedbackConstants.CONTEXT_CLICK : HapticFeedbackConstants.CLOCK_TICK);
      }
    }
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
    private final float density;
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    Track(int[] colors, int height, float density) {
      this.colors = colors.clone();
      this.height = Math.max(2, height);
      this.density = density;
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
      return Math.round(12 * density);
    }

    public void draw(Canvas canvas) {
      Rect bounds = getBounds();
      float radius = height / 2f, y = bounds.exactCenterY();
      // Precision engineering tick marks (9 ticks with emphasized center index).
      float span = Math.max(1f, bounds.width() - 4 * density);
      float startX = bounds.left + 2 * density;
      tickPaint.setStrokeWidth(Math.max(1f, density));
      int baseAlpha = enabled ? alpha : alpha * 2 / 5;
      for (int i = 0; i <= 8; i++) {
        boolean center = i == 4;
        float tx = startX + span * (i / 8f);
        float halfH = (center ? 4.5f : 2.5f) * density;
        tickPaint.setColor(AppStyle.MUTED);
        tickPaint.setAlpha(Math.round(baseAlpha * (center ? .68f : .32f)));
        canvas.drawLine(tx, y - halfH, tx, y + halfH, tickPaint);
      }
      applyAlpha();
      canvas.drawRoundRect(
          bounds.left, y - radius, bounds.right, y + radius, radius, radius, paint);
    }
  }

  /** Precision Instrument vertical vernier index thumb with vermilion center hairline. */
  private static final class VernierThumb extends StyledDrawable {
    private final int width, height;
    private final float density;
    private final RectF box = new RectF();

    VernierThumb(int width, int height, float density) {
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
      box.set(getBounds());
      float radius = 4.5f * density;
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(AppStyle.SLIDER_THUMB_BG);
      applyAlpha();
      canvas.drawRoundRect(box, radius, radius, paint);

      // Center vernier optical index line in vermilion (clean solid thumb with no perimeter outline).
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(AppStyle.SLIDER_THUMB_MARK);
      applyAlpha();
      float markW = Math.max(2f * density, 2.5f);
      float markH = box.height() * .54f;
      float cx = box.centerX(), cy = box.centerY();
      canvas.drawRoundRect(
          cx - markW / 2f,
          cy - markH / 2f,
          cx + markW / 2f,
          cy + markH / 2f,
          markW / 2f,
          markW / 2f,
          paint);
    }
  }
}
