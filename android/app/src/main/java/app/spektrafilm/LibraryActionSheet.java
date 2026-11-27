// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Activity;
import android.app.Dialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** Bottom action sheet matching the approved recent-photo workflow prototype. */
final class LibraryActionSheet extends Dialog {
  interface Listener {
    void onAction(int action);
  }

  static final int EDIT = 0,
      COPY = 1,
      PASTE = 2,
      BATCH_PASTE = 3,
      FAVORITE = 4,
      EXPORT = 5,
      DELETE = 6;

  static final class ActionItem {
    final int action;
    final String label, note, state;
    final boolean enabled, destructive;

    ActionItem(
        int action,
        String label,
        String note,
        String state,
        boolean enabled,
        boolean destructive) {
      this.action = action;
      this.label = label;
      this.note = note;
      this.state = state;
      this.enabled = enabled;
      this.destructive = destructive;
    }
  }

  private final Activity activity;
  private final Listener listener;
  private final SheetLayout root;
  private final View.OnLayoutChangeListener windowChanged =
      (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
        if (isShowing()
            && (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop))
          resize();
      };

  LibraryActionSheet(Activity activity, List<ActionItem> items, Listener listener) {
    super(activity);
    this.activity = activity;
    this.listener = listener;
    requestWindowFeature(Window.FEATURE_NO_TITLE);
    Haptics.install(getWindow());
    setCanceledOnTouchOutside(true);
    root = new SheetLayout(getContext());
    build(new ArrayList<>(items));
    // Transparent window space keeps all four rounded corners clear of the screen edges.
    FrameLayout inset =
        new FrameLayout(getContext()) {
          private boolean outsideGesture;

          @Override
          public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN)
              outsideGesture =
                  event.getX() < root.getLeft()
                      || event.getX() >= root.getRight()
                      || event.getY() < root.getTop()
                      || event.getY() >= root.getBottom();
            if (!outsideGesture) return super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
              outsideGesture = false;
              if (action == MotionEvent.ACTION_UP) dismiss();
            }
            return true;
          }
        };
    inset.setTag("library.actions.inset");
    inset.setPadding(dp(10), dp(10), dp(10), dp(10));
    inset.addView(root, new FrameLayout.LayoutParams(-1, -2));
    setContentView(inset);
  }

  private int dp(float value) {
    return AppStyle.dp(getContext(), value);
  }

  private LinearLayout column() {
    LinearLayout view = new LinearLayout(getContext());
    view.setOrientation(LinearLayout.VERTICAL);
    return view;
  }

  private TextView text(String value, int size, int color) {
    TextView view = new TextView(getContext());
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    view.setIncludeFontPadding(false);
    AppStyle.setTypeface(view, AppStyle.FONT_REGULAR);
    return view;
  }

  private void build(List<ActionItem> items) {
    root.setTag("library.actions.surface");
    root.setAccessibilityPaneTitle("更多操作");
    root.setBackground(AppStyle.shape(getContext(), AppStyle.SURFACE, AppStyle.R_LARGE, true));
    root.setClipToOutline(true);

    LinearLayout header = new LinearLayout(getContext());
    header.setTag("library.actions.header");
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setMinimumHeight(dp(62));
    header.setPadding(dp(18), dp(10), dp(12), dp(8));
    TextView title = text("更多操作", AppStyle.TYPE_HEADING, AppStyle.INK);
    title.setGravity(Gravity.CENTER_VERTICAL);
    AppStyle.setTypeface(title, AppStyle.FONT_SEMIBOLD);
    title.setAccessibilityHeading(true);
    header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));

    TextView cancel = text("取消", AppStyle.TYPE_BODY, AppStyle.MUTED);
    cancel.setTag("library.actions.cancel");
    cancel.setGravity(Gravity.CENTER);
    cancel.setMinWidth(dp(54));
    cancel.setMinHeight(dp(AppStyle.TOUCH_MIN));
    cancel.setPadding(dp(10), dp(4), dp(10), dp(4));
    AppStyle.pillAction(cancel, AppStyle.MUTED, Color.TRANSPARENT);
    AppStyle.setTypeface(cancel, AppStyle.FONT_MEDIUM);
    buttonSemantics(cancel);
    cancel.setOnClickListener(view -> dismiss());
    LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-2, -2);
    cancelParams.leftMargin = dp(8);
    header.addView(cancel, cancelParams);
    root.addView(header, new ViewGroup.LayoutParams(-1, -2));

    ScrollView scroll = new ScrollView(getContext());
    scroll.setTag("library.actions.scroll");
    scroll.setFillViewport(false);
    scroll.setVerticalScrollBarEnabled(false);
    LinearLayout list = column();
    list.setPadding(dp(18), dp(6), dp(18), dp(20));
    addGroup(list, "编辑", items, EDIT, EDIT);
    addGroup(list, "编辑参数", items, COPY, BATCH_PASTE);
    addGroup(list, "照片管理", items, FAVORITE, DELETE);
    scroll.addView(list, new ScrollView.LayoutParams(-1, -2));
    root.addView(
        AppStyle.wrapScrollFade(getContext(), scroll, AppStyle.SURFACE, 26, 26, true),
        new ViewGroup.LayoutParams(-1, -2));
  }

  private void addGroup(
      LinearLayout list, String name, List<ActionItem> items, int first, int last) {
    LinearLayout group = column();
    TextView heading = text(name, AppStyle.TYPE_MICRO, AppStyle.MUTED);
    AppStyle.setTypeface(heading, AppStyle.FONT_MEDIUM);
    heading.setAccessibilityHeading(true);
    heading.setMinimumHeight(dp(16));
    heading.setGravity(Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(-1, -2);
    headingParams.leftMargin = headingParams.rightMargin = dp(4);
    headingParams.bottomMargin = dp(7);
    group.addView(heading, headingParams);

    LinearLayout card = column();
    card.setTag("library.actions.group." + first);
    card.setBackground(AppStyle.shape(getContext(), AppStyle.CONTROL, AppStyle.R_MEDIUM, true));
    card.setClipToOutline(true);
    for (ActionItem item : items) {
      if (item.action < first || item.action > last) continue;
      if (card.getChildCount() > 0) {
        View divider = new View(getContext());
        divider.setBackgroundColor(AppStyle.DIVIDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.leftMargin = params.rightMargin = dp(16);
        card.addView(divider, params);
      }
      card.addView(row(item), new LinearLayout.LayoutParams(-1, -2));
    }
    group.addView(card, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    if (list.getChildCount() > 0) params.topMargin = dp(12);
    list.addView(group, params);
  }

  private View row(ActionItem item) {
    LinearLayout content = new LinearLayout(getContext());
    content.setTag("library.actions.row." + item.action);
    content.setGravity(Gravity.CENTER_VERTICAL);
    content.setMinimumHeight(dp(item.note.isEmpty() ? 52 : 61));
    content.setPadding(dp(16), dp(9), dp(16), dp(9));
    content.setBackground(
        new RippleDrawable(
            ColorStateList.valueOf(AppStyle.withAlpha(AppStyle.INK, .14f)),
            null,
            AppStyle.shape(getContext(), Color.WHITE, 0, false)));
    LinearLayout copy = column();
    int labelColor =
        !item.enabled ? AppStyle.DISABLED
            : item.destructive ? AppStyle.DANGER
            : item.action == EDIT ? AppStyle.GOLD : AppStyle.INK;
    TextView label = text(item.label, AppStyle.TYPE_BODY, labelColor);
    AppStyle.setTypeface(
        label, item.action == EDIT ? AppStyle.FONT_SEMIBOLD : AppStyle.FONT_MEDIUM);
    copy.addView(label, new LinearLayout.LayoutParams(-1, -2));
    if (!item.note.isEmpty()) {
      TextView note =
          text(item.note, AppStyle.TYPE_MICRO, item.enabled ? AppStyle.MUTED : AppStyle.DISABLED);
      note.setLineSpacing(dp(2), 1);
      LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(-1, -2);
      noteParams.topMargin = dp(3);
      copy.addView(note, noteParams);
    }
    int icon = iconFor(item);
    if (icon != 0) {
      ImageView iconView = new ImageView(getContext());
      iconView.setImageResource(icon);
      iconView.setImageTintList(ColorStateList.valueOf(labelColor));
      iconView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(24), dp(24));
      iconParams.rightMargin = dp(12);
      content.addView(iconView, iconParams);
    }
    content.addView(copy, new LinearLayout.LayoutParams(0, -2, 1));
    if (!item.state.isEmpty()) {
      TextView state =
          text(item.state, AppStyle.TYPE_MICRO, item.enabled ? AppStyle.GOLD : AppStyle.DISABLED);
      state.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      AppStyle.setTypeface(state, AppStyle.FONT_MEDIUM);
      state.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
      LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(-2, -2);
      stateParams.leftMargin = dp(12);
      content.addView(state, stateParams);
    }
    // TalkBack reads the whole row once, including its availability and current-source state.
    copy.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    content.setContentDescription(
        item.label
            + (item.note.isEmpty() ? "" : "，" + item.note)
            + (item.state.isEmpty() ? "" : "，" + item.state)
            + (item.enabled ? "" : "，当前不可用"));
    buttonSemantics(content);
    // Installing an OnClickListener marks a View clickable; do it only for enabled rows.
    if (item.enabled)
      content.setOnClickListener(
          view -> {
            dismiss();
            listener.onAction(item.action);
          });
    content.setEnabled(item.enabled);
    content.setFocusable(item.enabled);
    return content;
  }

  private int iconFor(ActionItem item) {
    switch (item.action) {
      case EDIT:
        return R.drawable.ic_ui_reedit;
      case COPY:
        return R.drawable.ic_ui_copy_settings;
      case PASTE:
        return R.drawable.ic_ui_paste_settings;
      case BATCH_PASTE:
        return R.drawable.ic_ui_batch_paste_settings;
      case FAVORITE:
        return item.label.contains("取消")
            ? R.drawable.ic_ui_favorite_remove
            : R.drawable.ic_ui_favorite_add;
      case EXPORT:
        return R.drawable.ic_ui_export_photo;
      case DELETE:
        return R.drawable.ic_ui_delete_photo;
      default:
        return 0;
    }
  }

  private void buttonSemantics(View view) {
    view.setAccessibilityDelegate(
        new View.AccessibilityDelegate() {
          @Override
          public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(host, info);
            info.setClassName("android.widget.Button");
          }
        });
  }

  @Override
  public void show() {
    Window window = getWindow();
    if (window != null) window.setWindowAnimations(0);
    super.show();
    window = getWindow();
    window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
    window.setGravity(Gravity.BOTTOM);
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    window.setDimAmount(AppStyle.DIM_AMOUNT);
    FoldableUi.stabilizeSheetWindow(window, root);
    activity.getWindow().getDecorView().addOnLayoutChangeListener(windowChanged);
    resize();
  }

  @Override
  public void dismiss() {
    FoldableUi.stabilizeSheetWindow(getWindow(), root);
    super.dismiss();
  }

  private void resize() {
    if (!isShowing()) return;
    int width = FoldableUi.visibleWindowWidthPx(activity);
    Rect visible = new Rect();
    activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible);
    int height = visible.height();
    if (width <= 0 || height <= 0) {
      width = getContext().getResources().getDisplayMetrics().widthPixels;
      height = getContext().getResources().getDisplayMetrics().heightPixels - dp(48);
    }
    root.maxHeight = Math.max(1, height - dp(20));
    root.requestLayout();
    int maxDp = FoldableLayout.actionSheetMaxDp(FoldableUi.windowWidthDp(activity));
    // Add the 20dp transparent inset padding so the visible card matches the 480/520dp spec.
    int windowMaxDp = maxDp > 0 ? maxDp + 20 : maxDp;
    int sheetWidth = FoldableUi.constrainedWidthPx(getContext(), width, windowMaxDp, 0);
    getWindow().setLayout(sheetWidth, WindowManager.LayoutParams.WRAP_CONTENT);
  }

  @Override
  protected void onStop() {
    activity.getWindow().getDecorView().removeOnLayoutChangeListener(windowChanged);
    super.onStop();
  }

  /** Keeps the header fixed and caps the scrollable action list to the visible window. */
  private final class SheetLayout extends ViewGroup {
    int maxHeight = Integer.MAX_VALUE;

    SheetLayout(android.content.Context context) {
      super(context);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
      int width = MeasureSpec.getSize(widthSpec);
      int ceiling =
          MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
              ? maxHeight
              : Math.min(maxHeight, MeasureSpec.getSize(heightSpec));
      int exactWidth = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY);
      View header = getChildAt(0), body = getChildAt(1);
      header.measure(exactWidth, MeasureSpec.makeMeasureSpec(ceiling, MeasureSpec.AT_MOST));
      int remaining = Math.max(0, ceiling - header.getMeasuredHeight());
      body.measure(exactWidth, MeasureSpec.makeMeasureSpec(remaining, MeasureSpec.AT_MOST));
      setMeasuredDimension(width, header.getMeasuredHeight() + body.getMeasuredHeight());
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
      int width = right - left;
      View header = getChildAt(0), body = getChildAt(1);
      header.layout(0, 0, width, header.getMeasuredHeight());
      int topOfBody = header.getMeasuredHeight();
      body.layout(0, topOfBody, width, topOfBody + body.getMeasuredHeight());
    }
  }
}
