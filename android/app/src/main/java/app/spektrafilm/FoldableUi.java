// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Android-side helpers bridging {@link FoldableLayout} breakpoints to View/Window measurements. */
final class FoldableUi {
  private FoldableUi() {}

  static Activity unwrapActivity(Context context) {
    Context cur = context;
    while (cur instanceof ContextWrapper) {
      if (cur instanceof Activity activity) return activity;
      cur = ((ContextWrapper) cur).getBaseContext();
    }
    return cur instanceof Activity activity ? activity : null;
  }

  static int visibleWindowWidthPx(Context context) {
    if (context == null) return 0;
    Activity activity = unwrapActivity(context);
    if (activity != null) {
      if (Build.VERSION.SDK_INT >= 30 && activity.getWindowManager() != null) {
        Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
        if (bounds.width() > 0) return bounds.width();
      }
      if (activity.getWindow() != null) {
        Rect visible = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
        if (visible.width() > 0) return visible.width();
      }
    }
    return context.getResources().getDisplayMetrics().widthPixels;
  }

  static int windowWidthDp(Context context) {
    if (context == null) return 390;
    DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    float density = Math.max(0.5f, metrics.density);
    Activity activity = unwrapActivity(context);
    if (activity != null && Build.VERSION.SDK_INT >= 30 && activity.getWindowManager() != null) {
      Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
      if (bounds.width() > 0) return Math.round(bounds.width() / density);
    }
    int cfgW = context.getResources().getConfiguration().screenWidthDp;
    if (cfgW > 0) return cfgW;
    if (activity != null && activity.getWindow() != null) {
      Rect visible = new Rect();
      activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
      if (visible.width() > 0) return Math.round(visible.width() / density);
    }
    return Math.round(metrics.widthPixels / density);
  }

  static int windowHeightDp(Context context) {
    if (context == null) return 800;
    DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    float density = Math.max(0.5f, metrics.density);
    Activity activity = unwrapActivity(context);
    if (activity != null && Build.VERSION.SDK_INT >= 30 && activity.getWindowManager() != null) {
      Rect bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
      if (bounds.height() > 0) return Math.round(bounds.height() / density);
    }
    int cfgH = context.getResources().getConfiguration().screenHeightDp;
    if (cfgH > 0) return cfgH;
    return Math.round(metrics.heightPixels / density);
  }

  /** Disables system window alpha transitions and resets window/decor/root alpha to 1. */
  static void stabilizeSheetWindow(Window window, View root) {
    if (window != null) {
      window.setWindowAnimations(0);
      WindowManager.LayoutParams lp = window.getAttributes();
      if (lp != null && lp.alpha != 1f) {
        lp.alpha = 1f;
        window.setAttributes(lp);
      }
      View decor = window.getDecorView();
      if (decor != null) {
        decor.animate().cancel();
        decor.setAlpha(1f);
      }
    }
    if (root != null) {
      root.animate().cancel();
      root.setAlpha(1f);
    }
  }

  static final int FLOATING_SHEET_MARGIN_DP = 10;

  /**
   * Computes a constrained sheet/dialog width in px. On COMPACT where {@code maxDp <= 0}, returns
   * {@code availablePx} untouched so ordinary phone layout stays 100% identical.
   */
  static int constrainedWidthPx(Context context, int availablePx, int maxDp, int marginDp) {
    if (maxDp <= 0) return availablePx;
    int maxPx = AppStyle.dp(context, maxDp);
    int inset = marginDp > 0 ? AppStyle.dp(context, marginDp) * 2 : 0;
    int ceiling = Math.max(AppStyle.dp(context, 240), availablePx - inset);
    return Math.min(maxPx, ceiling);
  }

  /**
   * Computes a floating bottom-sheet window width in px with symmetric horizontal margins across
   * all width classes (including COMPACT) so all four rounded corners stay clear of screen edges.
   */
  static int floatingSheetWidthPx(Context context, int availablePx, int maxDp) {
    int inset = AppStyle.dp(context, FLOATING_SHEET_MARGIN_DP) * 2;
    int ceiling = Math.max(AppStyle.dp(context, 240), availablePx - inset);
    if (maxDp <= 0) return ceiling;
    return Math.min(AppStyle.dp(context, maxDp), ceiling);
  }

  /**
   * Wraps a single child in a centered container that caps its width to {@code maxDp} only when
   * {@code maxDp > 0} (Medium/Expanded), keeping COMPACT full-width with zero overhead.
   */
  static FrameLayout wrapCenteredMaxWidth(
      Context context, View child, MaxWidthProvider maxDpProvider) {
    FrameLayout host =
        new FrameLayout(context) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int avail = MeasureSpec.getSize(widthSpec);
            // Classify breakpoints by true window width (not child avail after parent padding).
            int widthDp = windowWidthDp(context);
            int maxDp = maxDpProvider.maxDp(widthDp);
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
            int target = maxDp > 0 ? Math.min(avail, AppStyle.dp(context, maxDp)) : -1;
            if (lp.width != target || lp.gravity != (Gravity.TOP | Gravity.CENTER_HORIZONTAL)) {
              lp.width = target;
              lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            }
            super.onMeasure(widthSpec, heightSpec);
          }
        };
    host.addView(child, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    return host;
  }

  interface MaxWidthProvider {
    int maxDp(int widthDp);
  }
}
