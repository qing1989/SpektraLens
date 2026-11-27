// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * Keeps a 1:1 box even when the parent is narrower than the requested cell width.
 *
 * <p>The foldable film grid asks for a fixed square cell, but a weighted row column can resolve
 * narrower; a plain FrameLayout then keeps the fixed height and squeezes the width, so the
 * thumbnail and its selection ring render as a tall rectangle. Measuring the height from the
 * resolved width keeps the box square at every width tier.
 */
final class SquareFrameLayout extends FrameLayout {
  SquareFrameLayout(Context context) {
    super(context);
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    int width = MeasureSpec.getSize(widthMeasureSpec);
    int side =
        MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED
            ? MeasureSpec.getSize(heightMeasureSpec)
            : width;
    if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.AT_MOST) {
      side = Math.min(side, MeasureSpec.getSize(heightMeasureSpec));
    }
    int exact = MeasureSpec.makeMeasureSpec(Math.max(0, side), MeasureSpec.EXACTLY);
    super.onMeasure(exact, exact);
  }
}

