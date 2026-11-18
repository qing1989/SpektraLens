// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Plain-text feedback owned by the current app window; no system toast or app icon. */
final class AppNotice {
  static final String TAG = "app.notice";
  private static final Handler MAIN = new Handler(Looper.getMainLooper());

  private AppNotice() {}

  static void show(Activity activity, String message) {
    show(activity, message, 44, false);
  }

  static void showLong(Activity activity, String message) {
    show(activity, message, 44, true);
  }

  static void show(Activity activity, String message, int bottomDp, boolean longer) {
    activity.runOnUiThread(
        () -> {
          if (!activity.isFinishing() && !activity.isDestroyed())
            display(activity.getWindow(), message, bottomDp, longer);
        });
  }

  static void show(Dialog dialog, String message) {
    onMain(
        () -> {
          if (dialog.isShowing()) display(dialog.getWindow(), message, 88, false);
        });
  }

  static void clear(Window window) {
    onMain(
        () -> {
          if (window == null) return;
          View old = window.getDecorView().findViewWithTag(TAG);
          if (old instanceof NoticeView notice) notice.dismiss();
        });
  }

  static void setBottomMargin(Window window, int bottomDp) {
    onMain(
        () -> {
          if (window == null) return;
          View notice = window.getDecorView().findViewWithTag(TAG);
          if (!(notice instanceof NoticeView)) return;
          FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) notice.getLayoutParams();
          int margin = AppStyle.dp(notice.getContext(), bottomDp);
          if (params.bottomMargin != margin) {
            params.bottomMargin = margin;
            notice.setLayoutParams(params);
          }
        });
  }

  private static void onMain(Runnable run) {
    if (Looper.myLooper() == Looper.getMainLooper()) run.run();
    else MAIN.post(run);
  }

  private static void display(Window window, String message, int bottomDp, boolean longer) {
    if (window == null || message == null || message.isEmpty()) return;
    View content = window.getDecorView().findViewById(android.R.id.content);
    if (!(content instanceof FrameLayout host)) return;
    clear(window);
    NoticeView notice = new NoticeView(host.getContext(), message, longer);
    FrameLayout.LayoutParams params =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    params.leftMargin = params.rightMargin = AppStyle.dp(host.getContext(), 24);
    params.bottomMargin = AppStyle.dp(host.getContext(), bottomDp);
    host.addView(notice, params);
    host.bringChildToFront(notice);
  }

  private static final class NoticeView extends TextView {
    private final int durationMs;
    private boolean closed;
    private final Runnable hide =
        () -> {
          if (closed) return;
          animate()
              .alpha(0f)
              .translationY(AppStyle.dp(getContext(), 4))
              .setDuration(140)
              .withEndAction(this::dismiss)
              .start();
        };

    NoticeView(Context context, String message, boolean longer) {
      super(context);
      setTag(TAG);
      setText(message);
      setTextSize(AppStyle.TYPE_BODY);
      setTextColor(AppStyle.INK);
      AppStyle.setTypeface(this, AppStyle.FONT_REGULAR);
      setIncludeFontPadding(false);
      setGravity(Gravity.CENTER);
      setMaxWidth(AppStyle.dp(context, 320));
      setMaxLines(4);
      setEllipsize(TextUtils.TruncateAt.END);
      int horizontal = AppStyle.dp(context, 18), vertical = AppStyle.dp(context, 11);
      setPadding(horizontal, vertical, horizontal, vertical);
      setBackground(
          AppStyle.pill(context, AppStyle.withAlpha(AppStyle.CONTROL, .95f), true));
      setElevation(AppStyle.dp(context, 18));
      setClickable(false);
      setFocusable(false);
      setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
      int duration = longer ? 3500 : 1500;
      AccessibilityManager accessibility = context.getSystemService(AccessibilityManager.class);
      durationMs =
          accessibility == null
              ? duration
              : accessibility.getRecommendedTimeoutMillis(
                  duration, AccessibilityManager.FLAG_CONTENT_TEXT);
    }

    @Override
    protected void onAttachedToWindow() {
      super.onAttachedToWindow();
      if (closed) return;
      setAlpha(0f);
      setTranslationY(AppStyle.dp(getContext(), 8));
      animate().alpha(1f).translationY(0f).setDuration(140).start();
      postDelayed(hide, durationMs);
    }

    void dismiss() {
      stop();
      if (getParent() instanceof ViewGroup parent) parent.removeView(this);
    }

    private void stop() {
      closed = true;
      removeCallbacks(hide);
      animate().withEndAction(null).cancel();
      setVisibility(View.GONE);
      setText("");
    }

    @Override
    protected void onDetachedFromWindow() {
      stop();
      super.onDetachedFromWindow();
    }
  }
}
