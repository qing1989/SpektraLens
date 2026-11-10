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
   * 面板高度只跟屏幕有关，和当前页内容无关：九个编辑页（胶片 / 相纸 / 调整的五段 / 边框 /
   * 质感）挨个切一遍，高度必须一模一样。切 tab 时面板—连带照片区—跟着缩放，比面板底部
   * 那几十 dp 空白难看得多。
   *
   * <p>顺带守住三条不变量：
   *
   * <ul>
   *   <li>把手那一行永远占 26dp：胶囊调暗时不能把整页内容往上挪；
   *   <li>正文装不下才让把手可用，装得下就禁掉（别给一根点了没反应的把手）；
   *   <li>曲线页正文必须一次看全——它就是为「下端被裁」修的。
   * </ul>
   */
  public void testPanelHeightIsStableAcrossEveryTool() throws Exception {
    activity = getActivity();
    final float density = activity.getResources().getDisplayMetrics().density;
    final int handleRowPx = Math.round(MainActivity.HANDLE_ROW_DP * density);
    List<Integer> heights = new ArrayList<>();
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
                field("panelExpanded").setBoolean(activity, false);
                call("buildPanel");
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
        main(
            () -> {
              try {
                LinearLayout panel = (LinearLayout) field("panel").get(activity);
                View handle = (View) field("panelHandle").get(activity);
                int body = field("panelBodyHeight").getInt(activity);
                String page = "工具 " + tool + " / " + section;
                assertTrue(page + " 没有完成布局", panel.getHeight() > 0);
                heights.add(panel.getHeight());
                pages.add(page);
                // 横屏平板把面板当侧栏用（weight），把手不参与这套规则。
                if (panel.getLayoutParams().height == 0) return;
                assertEquals(page + " 把手行必须常驻", handleRowPx, handle.getHeight());
                int viewport = Math.round(panel.getHeight() / density) - MainActivity.HANDLE_ROW_DP;
                android.util.Log.i(
                    "PanelAudit",
                    page
                        + " 面板 "
                        + Math.round(panel.getHeight() / density)
                        + "dp 正文 "
                        + body
                        + "dp 可视 "
                        + viewport
                        + "dp 把手 "
                        + (handle.isEnabled() ? "可用" : "禁用")
                        + " 余量 "
                        + (viewport - body)
                        + "dp");
                if (body > viewport + 12) {
                  assertTrue(page + " 正文 " + body + "dp 装不下，把手必须可用", handle.isEnabled());
                } else {
                  assertFalse(
                      page + " 正文 " + body + "dp 装得下，把手不该可用", handle.isEnabled());
                }
                if (tool == 2 && "曲线".equals(section)) {
                  assertTrue(
                      page + " 正文 " + body + "dp 必须一次看全（可视区 " + viewport + "dp）",
                      body <= viewport);
                }
                // 底部渐隐是「还能往下滚」的唯一提示：只在下面确实还有内容时出现，
                // 短页（曲线 / 暗角 / 胶片）必须完全消失，否则看着像被裁了一道。
                ScrollView scroll = (ScrollView) field("panelScroll").get(activity);
                View fade = (View) field("panelFade").get(activity);
                assertEquals(
                    page + " 底部渐隐要和可滚性一致",
                    scroll.canScrollVertically(1) ? 1f : 0f,
                    fade.getAlpha());
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
      }
    }
    for (int i = 1; i < heights.size(); i++) {
      assertEquals(
          "面板高度必须只跟屏幕有关：" + pages.get(i) + " 与 " + pages.get(0) + " 不一致",
          heights.get(0).intValue(),
          heights.get(i).intValue());
    }
  }

  /**
   * 从基础页（十个滑杆，装不下）展开着切到曲线页（装得下）：必须收回收起态，
   * 且把手随即禁掉。反过来如果展开态被带过去，曲线底下会多出一大块空白。
   */
  public void testExpandedPanelCollapsesWhenSwitchingToAPageThatFits() throws Exception {
    activity = getActivity();
    final int[] height = new int[2];
    main(
        () -> {
          try {
            call("showEditor");
            field("selectedTab").setInt(activity, 2);
            field("gradeSection").set(activity, "基础");
            field("panelExpanded").setBoolean(activity, false);
            call("buildPanel");
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    main(
        () -> {
          try {
            height[0] = ((View) field("panel").get(activity)).getHeight();
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    // 矮屏或横屏根本没有展开档，这条用例不适用。
    if (!((View) field("panelHandle").get(activity)).isEnabled()) return;
    main(
        () -> {
          try {
            ((View) field("panelHandle").get(activity)).performClick();
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    main(
        () -> {
          try {
            height[1] = ((View) field("panel").get(activity)).getHeight();
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    assertTrue("基础页展开后应该变高", height[1] > height[0]);
    main(
        () -> {
          try {
            field("gradeSection").set(activity, "曲线");
            call("buildPanel");
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
    main(
        () -> {
          try {
            assertEquals(
                "切到装得下的页必须收回收起态",
                height[0],
                ((View) field("panel").get(activity)).getHeight());
            assertFalse("装得下的页不该留在展开态", (Boolean) field("panelExpanded").get(activity));
            assertFalse(
                "装得下的页把手必须禁掉", ((View) field("panelHandle").get(activity)).isEnabled());
          } catch (Exception ex) {
            throw new RuntimeException(ex);
          }
        });
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
