// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** One import button with a centred progress label and an independently usable cancel action. */
@SuppressLint("ViewConstructor") // Requires the existing buttons; never XML-inflated.
final class ImportButtonLayout extends ViewGroup {
  private final TextView button, cancel;
  private final int minimumHeight;
  private String fullLabel = "开始导入照片", compactLabel = fullLabel;

  ImportButtonLayout(Context context, TextView button, TextView cancel) {
    super(context);
    this.button = button;
    this.cancel = cancel;
    minimumHeight = Math.max(AppStyle.dp(context, 54),
        (int) Math.ceil(button.getPaint().getFontMetrics().descent - button.getPaint().getFontMetrics().ascent)
            + button.getPaddingTop() + button.getPaddingBottom());
    button.setSingleLine(false);
    button.setEllipsize(null);
    addView(button, new LayoutParams(-1, -1));
    addView(cancel, new LayoutParams(-2, -2));
  }

  void setLabel(String full, String compact) {
    fullLabel = full;
    compactLabel = compact;
    updateLabel(getWidth() > 0 ? getWidth() : Integer.MAX_VALUE / 4);
    requestLayout();
  }

  private int cancelWidth() {
    return Math.max(AppStyle.dp(getContext(), 56),
        (int) Math.ceil(cancel.getPaint().measureText(cancel.getText().toString()))
            + cancel.getPaddingLeft() + cancel.getPaddingRight());
  }

  private void updateLabel(int width) {
    boolean cancelling = cancel.getVisibility() == VISIBLE;
    int inset = cancelling ? cancelWidth() + AppStyle.dp(getContext(), 12) : AppStyle.dp(getContext(), 14);
    button.setPadding(inset, AppStyle.dp(getContext(), 8), inset, AppStyle.dp(getContext(), 8));
    int textSpace = Math.max(1, width - inset * 2);
    String label = cancelling && button.getPaint().measureText(fullLabel) > textSpace
        ? compactLabel : fullLabel;
    if (!label.contentEquals(button.getText())) button.setText(label);
    button.setGravity(Gravity.CENTER);
    button.setContentDescription(button.getBackground() instanceof ImportProgressDrawable ? fullLabel : null);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    int width = MeasureSpec.getSize(widthSpec);
    updateLabel(width);
    int exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
    button.measure(exactWidth, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
    int height = Math.max(minimumHeight, button.getMeasuredHeight());
    if (cancel.getVisibility() != GONE) {
      cancel.measure(MeasureSpec.makeMeasureSpec(cancelWidth(), MeasureSpec.EXACTLY),
          MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
      height = Math.max(height, cancel.getMeasuredHeight() + AppStyle.dp(getContext(), 10));
    }
    height = resolveSize(height, heightSpec);
    button.measure(exactWidth, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    setMeasuredDimension(width, height);
  }

  @Override
  protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
    int width = right - left, height = bottom - top;
    button.layout(0, 0, width, height);
    if (cancel.getVisibility() != GONE) {
      int end = width - AppStyle.dp(getContext(), 7);
      int start = end - cancel.getMeasuredWidth();
      int y = (height - cancel.getMeasuredHeight()) / 2;
      cancel.layout(start, y, end, y + cancel.getMeasuredHeight());
    }
  }
}
