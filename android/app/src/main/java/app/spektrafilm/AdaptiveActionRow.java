// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;

/** Keep full-size action labels readable; stack the actions when their measured widths need it. */
@SuppressLint("ViewConstructor") // Composes existing actions programmatically; never XML-inflated.
final class AdaptiveActionRow extends LinearLayout {
  private static final class Action {
    final View view;
    final TextView label;
    final int minimumWidth;

    Action(View view, TextView label, int minimumWidth) {
      this.view = view;
      this.label = label;
      this.minimumWidth = minimumWidth;
    }
  }

  private final ArrayList<Action> actions = new ArrayList<>();
  private final boolean equalWidths;
  private final int gap;
  private int[] widths = new int[0];

  AdaptiveActionRow(Context context, boolean equalWidths, int gapDp) {
    super(context);
    this.equalWidths = equalWidths;
    gap = AppStyle.dp(context, gapDp);
    setBaselineAligned(false);
    setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
  }

  void addAction(View view, TextView label, int minimumWidthDp) {
    label.setSingleLine(false);
    label.setEllipsize(null);
    actions.add(new Action(view, label, AppStyle.dp(getContext(), minimumWidthDp)));
    widths = new int[actions.size()];
    addView(view, new LayoutParams(-2, -2));
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    if (actions.isEmpty()) {
      super.onMeasure(widthSpec, heightSpec);
      return;
    }
    int available =
        MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
            ? Integer.MAX_VALUE / 4
            : Math.max(1, MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight());
    int widest = 0, required = gap * (actions.size() - 1);
    for (int i = 0; i < actions.size(); i++) {
      Action action = actions.get(i);
      widths[i] = Math.max(action.minimumWidth,
          (int) Math.ceil(action.label.getPaint().measureText(action.label.getText().toString()))
              + action.label.getPaddingLeft() + action.label.getPaddingRight());
      widest = Math.max(widest, widths[i]);
      required += widths[i];
    }
    if (equalWidths) required = widest * actions.size() + gap * (actions.size() - 1);
    boolean stacked = required > available;
    setOrientation(stacked ? VERTICAL : HORIZONTAL);
    int height = AppStyle.dp(getContext(), AppStyle.TOUCH_PRIMARY);
    for (int i = 0; i < actions.size(); i++) {
      int width;
      if (equalWidths) width = Math.min(widest, available);
      else if (stacked) width = available;
      else {
        width = widths[i];
        if (i == actions.size() - 1 && MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED)
          width += available - required;
      }
      widths[i] = width;
      TextView label = actions.get(i).label;
      label.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      height = Math.max(height, label.getMeasuredHeight());
    }
    for (int i = 0; i < actions.size(); i++) {
      Action action = actions.get(i);
      LayoutParams params = (LayoutParams) action.view.getLayoutParams();
      params.width = widths[i];
      params.height = action.view == action.label ? height : ViewGroup.LayoutParams.WRAP_CONTENT;
      params.weight = 0;
      params.leftMargin = !stacked && i > 0 ? gap : 0;
      params.topMargin = stacked && i > 0 ? gap : 0;
      if (action.view != action.label) {
        ViewGroup.LayoutParams labelParams = action.label.getLayoutParams();
        labelParams.width = widths[i];
        labelParams.height = height;
      }
    }
    super.onMeasure(widthSpec, heightSpec);
  }
}
