// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.*;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.widget.*;
import java.lang.reflect.*;
import java.util.*;

/** Pixel/control bounds, including endpoints and changing source aspect ratios. */
public final class EditorLayoutInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;

  public EditorLayoutInstrumentedTest() {
    super(MainActivity.class);
  }

  protected void setUp() throws Exception {
    super.setUp();
    activity = getActivity();
    long until = android.os.SystemClock.elapsedRealtime() + 30000;
    while (!(Boolean) field("ready").get(activity)
        && android.os.SystemClock.elapsedRealtime() < until) Thread.sleep(50);
    assertTrue(
        "Renderer initialized before layout interaction", (Boolean) field("ready").get(activity));
  }

  private Field field(String name) throws Exception {
    Field field = MainActivity.class.getDeclaredField(name);
    field.setAccessible(true);
    return field;
  }

  private void call(String name) throws Exception {
    Method method = MainActivity.class.getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(activity);
  }

  /** 面板的屏幕上限（dp）：面板高度就是 min(PANEL_MAX_DP, 这个值)。 */
  private int panelLimit() throws Exception {
    Method method = MainActivity.class.getDeclaredMethod("panelLimit");
    method.setAccessible(true);
    return (Integer) method.invoke(activity);
  }

  private void main(Runnable action) {
    getInstrumentation().runOnMainSync(action);
    getInstrumentation().waitForIdleSync();
  }

  public void testSliderThumbsFitAtBothEndpointsInEveryTool() throws Exception {
    activity = getActivity();
    Set<String> checked = new HashSet<>();
    for (int tab : new int[] {0, 1, 2, 4, 5}) {
      for (String section :
          tab == 2 ? new String[] {"基础", "曲线", "色彩平衡", "HSL 分色", "暗角"} : new String[] {"基础"}) {
        final int tool = tab;
        main(
            () -> {
              try {
                call("showEditor");
                field("selectedTab").setInt(activity, tool);
                field("gradeSection").set(activity, section);
                call("buildPanel");
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
        main(
            () -> {
              try {
                Map<String, SeekBar> bars = (Map<String, SeekBar>) field("sliders").get(activity);
                Map<String, TextView> numbers =
                    (Map<String, TextView>) field("values").get(activity);
                for (Map.Entry<String, SeekBar> item : bars.entrySet()) {
                  SeekBar bar = item.getValue();
                  assertTrue("Measured " + item.getKey(), bar.getWidth() > 0);
                  Bitmap pixels =
                      Bitmap.createBitmap(bar.getWidth(), bar.getHeight(), Bitmap.Config.ARGB_8888);
                  int previous = bar.getProgress();
                  for (int endpoint : new int[] {0, bar.getMax()}) {
                    bar.setProgress(endpoint);
                    bar.draw(new Canvas(pixels));
                    Rect thumb = bar.getThumb().getBounds();
                    int translatedLeft = bar.getPaddingLeft() - bar.getThumbOffset();
                    assertTrue(
                        item.getKey() + " left thumb is clipped", thumb.left + translatedLeft >= 1);
                    assertTrue(
                        item.getKey() + " right thumb is clipped",
                        thumb.right + translatedLeft <= bar.getWidth() - 1);
                  }
                  bar.setProgress(previous);
                  pixels.recycle();
                  TextView number = numbers.get(item.getKey());
                  assertTrue(
                      "Full value and unit fit: " + item.getKey(),
                      number.getPaint().measureText(number.getText().toString())
                          <= number.getWidth()
                              - number.getPaddingLeft()
                              - number.getPaddingRight()
                              + 1);
                  checked.add(item.getKey());
                }
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
      }
    }
    assertTrue("Every tool's actual sliders were checked", checked.size() >= 30);
  }

  /**
   * 编辑区的一条硬规则，九页（胶片 / 相纸 / 调整五段 / 边框 / 质感）逐页验：
   * <b>预览区高度与面板高度都必须一模一样</b>——面板固定占满 {@link
   * MainActivity#PANEL_MAX_DP}（或屏幕上限），不随内容收缩、不随内容跳动，内容多了就在
   * 面板里滚动。照片因此永远不动。
   *
   * <p>同时守住底部渐隐：它必须与「还能不能滚」一致——长页滚到底要灭，短页从头到尾不亮。
   */
  public void testPreviewAndPanelHeightsAreIdenticalOnEveryTool() throws Exception {
    activity = getActivity();
    final float density = activity.getResources().getDisplayMetrics().density;
    List<Integer> previews = new ArrayList<>();
    List<Integer> panels = new ArrayList<>();
    List<String> pages = new ArrayList<>();
    for (int tab : new int[] {0, 1, 2, 4, 5}) {
      for (String section :
          tab == 2
              ? new String[] {"基础", "曲线", "色彩平衡", "HSL 分色", "暗角"}
              : new String[] {"基础"}) {
        final int tool = tab;
        main(
            () -> {
              try {
                call("showEditor");
                field("selectedTab").setInt(activity, tool);
                field("gradeSection").set(activity, section);
                call("buildPanel");
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
        main(
            () -> {
              try {
                LinearLayout panel = (LinearLayout) field("panel").get(activity);
                View stage = (View) field("photoStage").get(activity);
                ScrollView scroll = (ScrollView) field("panelScroll").get(activity);
                View fade = (View) field("panelFade").get(activity);
                LinearLayout editor = (LinearLayout) field("editor").get(activity);
                String page = "工具 " + tool + " / " + section;
                assertTrue(page + " 没有完成布局", panel.getHeight() > 0 && stage.getHeight() > 0);
                previews.add(stage.getHeight());
                panels.add(panel.getHeight());
                pages.add(page);
                // 横屏平板是左右分栏，面板按权重分，不适用这套规则。
                if (((LinearLayout.LayoutParams) panel.getLayoutParams()).weight > 0) return;
                // fillViewport 会把子 View 撑满可视区，所以要按内容自己量一遍才是真实正文高。
                int width =
                    scroll.getWidth() - scroll.getPaddingLeft() - scroll.getPaddingRight();
                editor.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                int body = Math.round(editor.getMeasuredHeight() / density);
                boolean scrolls = scroll.canScrollVertically(1);
                android.util.Log.i(
                    "PanelAudit",
                    page
                        + " 预览 "
                        + Math.round(stage.getHeight() / density)
                        + "dp 正文 "
                        + body
                        + "dp 面板 "
                        + Math.round(panel.getHeight() / density)
                        + "dp 能滚 "
                        + (scrolls ? "是" : "否"));
                // 面板高度只跟屏幕有关：等于 min(PANEL_MAX_DP, 屏幕上限)。
                int expected =
                    Math.round(Math.min(MainActivity.PANEL_MAX_DP, panelLimit()) * density);
                assertTrue(
                    page + " 面板必须固定为 " + Math.round(expected / density) + "dp，实际 "
                        + Math.round(panel.getHeight() / density) + "dp",
                    Math.abs(panel.getHeight() - expected) <= 2);
                // 底部渐隐是「还能往下滚」的唯一提示，必须与可滚性一致。
                assertEquals(page + " 底部渐隐要和可滚性一致", scrolls ? 1f : 0f, fade.getAlpha());
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
      }
    }
    for (int i = 1; i < previews.size(); i++) {
      assertEquals(
          "预览区高度必须固定：" + pages.get(i) + " 与 " + pages.get(0) + " 不一致",
          previews.get(0).intValue(),
          previews.get(i).intValue());
      assertEquals(
          "面板高度必须固定：" + pages.get(i) + " 与 " + pages.get(0) + " 不一致",
          panels.get(0).intValue(),
          panels.get(i).intValue());
    }
  }

  public void testHomeBannerKeepsFixedHeightAndCentersLandscapePortraitAndSquare()
      throws Exception {
    activity = getActivity();
    ImageView hero = (ImageView) field("homeHero").get(activity);
    main(
        () -> {
          try {
            call("showHome");
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    for (int[] dimensions : new int[][] {{300, 100}, {100, 300}, {120, 120}}) {
      Bitmap bitmap = Bitmap.createBitmap(dimensions[0], dimensions[1], Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(Color.MAGENTA);
      bitmap.setDensity(Bitmap.DENSITY_NONE);
      RectF image = new RectF(0, 0, dimensions[0], dimensions[1]);
      float[] center = {image.centerX(), image.centerY()};
      main(
          () -> {
            Drawable original = hero.getDrawable();
            hero.setImageDrawable(new BitmapDrawable(activity.getResources(), bitmap));
            hero.getImageMatrix().mapPoints(center);
            hero.getImageMatrix().mapRect(image);
            hero.setImageDrawable(original);
          });
      assertEquals(
          "Photo aspect ratio must not change the banner height",
          Math.round(240 * activity.getResources().getDisplayMetrics().density),
          hero.getHeight());
      assertEquals(ImageView.ScaleType.CENTER_CROP, hero.getScaleType());
      assertEquals("Photo center stays on banner center X", hero.getWidth() / 2., center[0], 1.);
      assertEquals("Photo center stays on banner center Y", hero.getHeight() / 2., center[1], 1.);
      assertTrue(
          "Banner is filled horizontally", image.left <= 1 && image.right >= hero.getWidth() - 1);
      assertTrue(
          "Banner is filled vertically", image.top <= 1 && image.bottom >= hero.getHeight() - 1);
      bitmap.recycle();
    }
  }
}
