// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.*;
import android.content.Context;
import android.graphics.Rect;
import android.view.*;
import android.widget.AdapterView;
import java.lang.reflect.*;

/** System-defined touch feedback: no custom waveform, amplitude, permission or setting override. */
final class Haptics implements InvocationHandler {
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
          && target.isShown()
          && target.isEnabled()
          && event.getEventTime() - down < ViewConfiguration.getLongPressTimeout())
        target.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
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
    return view.isClickable()
            || view instanceof AdapterView
            // SeekBar 不在此列：AdjustmentSeekBar 自带刻度震动，点按跳值已有一下，
            // 再叠全局点按反馈就是连续两震。
            || view instanceof PhotoView
            || view instanceof CurveEditor
            || view instanceof CropEditor
        ? view
        : null;
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
