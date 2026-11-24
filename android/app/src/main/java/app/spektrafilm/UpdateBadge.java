// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.annotation.SuppressLint;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Reserves space for the corner notice so it never clips or moves the action when shown. */
@SuppressLint("ViewConstructor")
final class UpdateBadge extends FrameLayout {
  private final TextView action;
  private final TextView badge;
  private final CharSequence description;

  UpdateBadge(TextView action, String label) {
    this(action, label, true);
  }

  UpdateBadge(TextView action, String label, boolean reserveHorizontalSpace) {
    super(action.getContext());
    this.action = action;
    description = action.getContentDescription() == null
        ? action.getText() : action.getContentDescription();
    LayoutParams actionParams = new LayoutParams(-2, -2);
    actionParams.topMargin = dp(10);
    actionParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
    int horizontalInset = reserveHorizontalSpace ? dp(14) : 0;
    actionParams.setMarginStart(horizontalInset);
    actionParams.setMarginEnd(horizontalInset);
    addView(action, actionParams);
    action.setClickable(false);
    action.setFocusable(false);
    action.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    action.setDuplicateParentStateEnabled(true);
    setFocusable(true);
    setOnClickListener(view -> action.performClick());
    setAccessibilityDelegate(new View.AccessibilityDelegate() {
      @Override
      public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(host, info);
        info.setClassName("android.widget.Button");
      }
    });

    badge = new TextView(getContext());
    badge.setText(label);
    badge.setTextSize(AppStyle.TYPE_MICRO);
    badge.setTextColor(AppStyle.ON_ACCENT);
    AppStyle.setTypeface(badge, AppStyle.FONT_SEMIBOLD);
    badge.setIncludeFontPadding(false);
    badge.setSingleLine(true);
    badge.setGravity(Gravity.CENTER);
    badge.setPadding(dp(7), dp(3), dp(7), dp(3));
    GradientDrawable fill =
        AppStyle.shape(getContext(), AppStyle.GOLD, 6f, false);
    fill.setStroke(dp(2), AppStyle.BG);
    badge.setBackground(fill);
    badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    addView(badge, new LayoutParams(-2, -2, Gravity.TOP | Gravity.END));
    setAvailable(false);
  }

  @Override
  protected void onMeasure(int widthSpec, int heightSpec) {
    super.onMeasure(widthSpec, heightSpec);
    LayoutParams params = (LayoutParams) action.getLayoutParams();
    // INVISIBLE still measures, so showing a notice never moves the action. Account for the
    // real font metrics: at large system font sizes a fixed 10dp inset can cover the label.
    int top = Math.max(dp(10), badge.getMeasuredHeight() + dp(2) - action.getTotalPaddingTop());
    if (params.topMargin != top) {
      params.topMargin = top;
      super.onMeasure(widthSpec, heightSpec);
    }
  }

  @Override
  public void setEnabled(boolean enabled) {
    super.setEnabled(enabled);
    if (action != null) action.setEnabled(enabled);
  }

  void setAvailable(boolean available) {
    badge.setVisibility(available ? VISIBLE : INVISIBLE);
    setContentDescription(description + (available ? "，有新版本可更新" : ""));
  }

  private int dp(float value) {
    return AppStyle.dp(getContext(), value);
  }
}
