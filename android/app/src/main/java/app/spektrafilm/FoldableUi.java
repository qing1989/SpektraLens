// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/** Android-side helpers bridging {@link FoldableLayout} breakpoints to View/Window measurements. */
final class FoldableUi {
  private FoldableUi() {}

  static int windowWidthDp(Context context) {
    if (context == null) return 390;
    DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    float density = Math.max(0.5f, metrics.density);
    if (context instanceof Activity activity && activity.getWindow() != null) {
      Rect visible = new Rect();
      activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
      if (visible.width() > 0) return Math.round(visible.width() / density);
    }
    int cfgW = context.getResources().getConfiguration().screenWidthDp;
    if (cfgW > 0) return cfgW;
    return Math.round(metrics.widthPixels / density);
  }

  static int windowHeightDp(Context context) {
    if (context == null) return 800;
    int cfgH = context.getResources().getConfiguration().screenHeightDp;
    if (cfgH > 0) return cfgH;
    DisplayMetrics metrics = context.getResources().getDisplayMetrics();
    return Math.round(metrics.heightPixels / Math.max(0.5f, metrics.density));
  }

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
            int widthDp =
                Math.round(avail / Math.max(0.5f, getResources().getDisplayMetrics().density));
            int maxDp = maxDpProvider.maxDp(widthDp > 0 ? widthDp : windowWidthDp(context));
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
            int target = maxDp > 0 ? Math.min(avail, AppStyle.dp(context, maxDp)) : -1;
            if (lp.width != target) {
              lp.width = target;
              lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
              child.setLayoutParams(lp);
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
