// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Bitmap;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ExportSheetInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private ExportSheet sheet;
  private Bitmap preview;
  private final AtomicReference<ExportOptions> selected = new AtomicReference<>();
  private final AtomicInteger submissions = new AtomicInteger();

  public ExportSheetInstrumentedTest() {
    super(MainActivity.class);
  }

  private void main(Runnable action) {
    getInstrumentation().runOnMainSync(action);
    getInstrumentation().waitForIdleSync();
  }

  private View view(String tag) {
    View found = sheet.getWindow().getDecorView().findViewWithTag(tag);
    assertNotNull(tag, found);
    return found;
  }

  private void open() {
    MainActivity activity = getActivity();
    main(
        () -> {
          preview = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
          preview.eraseColor(0xff7990aa);
          sheet =
              new ExportSheet(
                  activity, "6000 × 4000", 6000, 4000, preview,
                  options -> {
                    selected.set(options);
                    submissions.incrementAndGet();
                  });
          sheet.show();
        });
  }

  @Override
  protected void tearDown() throws Exception {
    main(
        () -> {
          if (sheet != null) sheet.dismiss();
          Bitmaps.release(preview);
        });
    super.tearDown();
  }

  public void testDefaultSaveAndOneSubmission() {
    open();
    assertTrue(view("export.quality.90").isSelected());
    assertTrue(view("export.format.0").isSelected());
    assertEquals("原尺寸 · 6000 × 4000", ((TextView) view("export.dimensions")).getText().toString());
    main(
        () -> {
          view("export.save").performClick();
          view("export.save").performClick();
        });
    assertEquals(1, submissions.get());
    assertEquals(ExportOptions.JPEG, selected.get().mode);
    assertEquals(90, selected.get().jpegQuality);
    assertFalse(sheet.isShowing());
    assertFalse("The sheet must not recycle the editor preview", preview.isRecycled());
  }

  public void testLosslessHidesQualityAndSharingWithoutLosingJpegSelection() {
    open();
    main(() -> view("export.quality.85").performClick());
    for (int format : new int[] {1, 2}) {
      main(() -> view("export.format." + format).performClick());
      assertEquals(View.GONE, view("export.quality").getVisibility());
      assertEquals(View.GONE, view("export.share").getVisibility());
      assertEquals(View.VISIBLE, view("export.lossless").getVisibility());
      main(() -> view("export.share").performClick());
      assertEquals(0, submissions.get());
    }
    main(() -> view("export.format.0").performClick());
    assertTrue(view("export.quality.85").isSelected());
    assertEquals(View.VISIBLE, view("export.share").getVisibility());
    main(() -> view("export.share").performClick());
    assertEquals(ExportOptions.SHARE_JPEG, selected.get().mode);
    assertEquals(85, selected.get().jpegQuality);
  }

  public void testCancelDoesNotExportAndReopeningDefaultsToRecommended() {
    open();
    main(
        () -> {
          view("export.quality.95").performClick();
          view("export.cancel").performClick();
        });
    assertEquals(0, submissions.get());
    assertNull(selected.get());
    main(() -> Bitmaps.release(preview));
    open();
    assertTrue(view("export.quality.90").isSelected());
  }

  public void testSavePngAndTiffKeepSelectedFormats() {
    for (int format : new int[] {1, 2}) {
      open();
      main(
          () -> {
            view("export.format." + format).performClick();
            view("export.save").performClick();
          });
      assertEquals(format, selected.get().mode);
      assertFalse(selected.get().isJpeg());
      main(() -> Bitmaps.release(preview));
    }
  }

  private void enlarge(View view) {
    if (view instanceof TextView text) {
      float pixels = text.getTextSize();
      text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, pixels * 2);
    }
    if (view instanceof ViewGroup group)
      for (int index = 0; index < group.getChildCount(); index++) enlarge(group.getChildAt(index));
  }

  public void testActionLabelsFitWithLargeText() {
    open();
    main(() -> enlarge(sheet.getWindow().getDecorView()));
    main(() -> sheet.getWindow().getDecorView().requestLayout());
    for (String tag : new String[] {
      "export.cancel", "export.save", "export.share",
      "export.quality.95", "export.quality.90", "export.quality.85"
    }) {
      TextView button = (TextView) view(tag);
      assertEquals(tag, 1, button.getLineCount());
      int space = button.getWidth() - button.getPaddingLeft() - button.getPaddingRight();
      assertTrue(tag, space + 1 >= button.getPaint().measureText(button.getText().toString()));
    }
  }
}
