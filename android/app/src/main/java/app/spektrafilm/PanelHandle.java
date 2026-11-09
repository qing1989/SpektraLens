// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.widget.FrameLayout;

/** A drag handle also exposes a real click action to accessibility services and keyboards. */
final class PanelHandle extends FrameLayout {
  public PanelHandle(Context context) {
    super(context);
    setFocusable(true);
    setClickable(true);
  }

  @Override
  public boolean performClick() {
    super.performClick();
    return true;
  }

  @Override
  public CharSequence getAccessibilityClassName() {
    return android.widget.Button.class.getName();
  }
}
