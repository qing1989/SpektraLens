// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.*;
import android.content.Context;
import android.graphics.Rect;
import android.view.*;
import android.widget.AdapterView;
import android.widget.SeekBar;
import java.lang.reflect.*;

/** System-defined touch feedback: no custom waveform, amplitude, permission or setting override. */
final class Haptics implements InvocationHandler {
  /** App-level switch in Settings; platform-defined feedback stays otherwise untouched. */
  static boolean enabled = true;

  private final Window window;
  private final Window.Callback delegate;
  private final int slop;
  private float x, y;
  private long down;
  private View target;

  private Haptics(Window window, Window.Callback delegate) {
    this.window = window;
    this.delegate = delegate;
    slop = ViewConfiguration.get(window.getContext()).getScaledTouchSlop();
  }

  static void install(Window window) {
    if (window == null || window.getCallback() == null) return;
    Window.Callback callback = window.getCallback();
    if (Proxy.isProxyClass(callback.getClass())
        && Proxy.getInvocationHandler(callback) instanceof Haptics) return;
    window.setCallback(
        (Window.Callback)
            Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(),
                new Class<?>[] {Window.Callback.class},
                new Haptics(window, callback)));
  }

  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (method.getName().equals("dispatchTouchEvent") && args != null) touch((MotionEvent) args[0]);
    try {
      return method.invoke(delegate, args);
    } catch (InvocationTargetException error) {
      throw error.getCause();
    }
  }

  private void touch(MotionEvent event) {
    if (!enabled) return;
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      x = event.getRawX();
      y = event.getRawY();
      down = event.getEventTime();
      target = hit(window.getDecorView(), Math.round(x), Math.round(y));
    } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
      if (Math.abs(event.getRawX() - x) > slop || Math.abs(event.getRawY() - y) > slop)
        target = null;
    } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
      if (target != null
          && target.getTag(R.id.haptic_on_click) == null
          && target.isShown()
          && target.isEnabled()
          && event.getEventTime() - down < ViewConfiguration.getLongPressTimeout())
        click(target);
      target = null;
    } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || event.getPointerCount() > 1)
      target = null;
  }

  private View hit(View view, int x, int y) {
    Rect bounds = new Rect();
    if (!view.isShown() || !view.isEnabled() || !view.getGlobalVisibleRect(bounds)) return null;
    int[] origin = new int[2];
    view.getLocationOnScreen(origin);
    bounds.set(origin[0], origin[1], origin[0] + view.getWidth(), origin[1] + view.getHeight());
    if (!bounds.contains(x, y)) return null;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = group.getChildCount() - 1; i >= 0; i--) {
        View child = hit(group.getChildAt(i), x, y);
        if (child != null) return child;
      }
    }
    // 滑块自己负责拖动刻度与双击复位反馈，即便平台样式将它标记为 clickable 也不重复震动。
    if (view instanceof SeekBar) return null;
    return view.isClickable()
            || view instanceof AdapterView
            || view instanceof PhotoView
            || view instanceof CurveEditor
            || view instanceof CropEditor
        ? view
        : null;
  }

  static void click(View view) {
    if (enabled) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
  }

  static void longPress(View view) {
    if (enabled && view != null) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
  }

  static final class DialogBuilder extends AlertDialog.Builder {
    DialogBuilder(Context context) {
      super(context);
    }

    public AlertDialog create() {
      AlertDialog dialog = super.create();
      install(dialog.getWindow());
      AppStyle.dialog(dialog);
      return dialog;
    }
  }
}
