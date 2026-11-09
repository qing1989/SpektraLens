// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Bitmap;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import java.io.*;
import java.lang.reflect.*;
import java.util.concurrent.*;

/** A slow GPU queue must never prevent a local JPEG from being read and shown. */
public final class ImportPipelineInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;
  private CountDownLatch release;
  private Uri galleryPhoto;

  public ImportPipelineInstrumentedTest() {
    super(MainActivity.class);
  }

  private Object field(String name) throws Exception {
    Field f = MainActivity.class.getDeclaredField(name);
    f.setAccessible(true);
    return f.get(activity);
  }

  private void call(String name, Class<?>[] types, Object... args) throws Exception {
    Method m = MainActivity.class.getDeclaredMethod(name, types);
    m.setAccessible(true);
    m.invoke(activity, args);
  }

  protected void tearDown() throws Exception {
    if (release != null) release.countDown();
    if (galleryPhoto != null) activity.getContentResolver().delete(galleryPhoto, null, null);
    super.tearDown();
  }

  public void testSwitchingPhotoThenReturningHomeKeepsALivePreview() throws Exception {
    activity = getActivity();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!(Boolean) field("ready") && System.nanoTime() < end) Thread.sleep(50);
    assertTrue((Boolean) field("ready"));
    Bitmap previous = (Bitmap) field("source");
    assertNotNull(previous);
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                call("showEditor", new Class<?>[] {});
                call("loadDemoAction", new Class<?>[] {});
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
    end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while ((!previous.isRecycled() || field("source") == previous) && System.nanoTime() < end)
      Thread.sleep(50);
    assertTrue("Old preview has reached its normal release point", previous.isRecycled());
    Bitmap[] displayed = new Bitmap[1];
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                android.widget.ImageView hero = (android.widget.ImageView) field("homeHero");
                displayed[0] =
                    ((android.graphics.drawable.BitmapDrawable) hero.getDrawable()).getBitmap();
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
    assertFalse("Home preview still references a recycled photo", displayed[0].isRecycled());
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                android.widget.ImageView hero = (android.widget.ImageView) field("homeHero");
                call("showHome", new Class<?>[] {});
                Bitmap target = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
                hero.draw(new android.graphics.Canvas(target));
                target.recycle();
              } catch (Exception ex) {
                throw new RuntimeException(ex);
              }
            });
  }

  public void testLocalJpegImportDoesNotWaitForRenderQueue() throws Exception {
    activity = getActivity();
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
    while (!(Boolean) field("ready") && System.nanoTime() < end) Thread.sleep(50);
    assertTrue("Engine ready for queue-isolation test", (Boolean) field("ready"));
    File jpeg = new File(activity.getFilesDir(), "import-queue-regression.jpg");
    Bitmap image = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888);
    image.eraseColor(0xffa06942);
    try (OutputStream out = new FileOutputStream(jpeg)) {
      image.compress(Bitmap.CompressFormat.JPEG, 95, out);
    }
    image.recycle();
    android.content.ContentValues values = new android.content.ContentValues();
    values.put(
        android.provider.MediaStore.Images.Media.DISPLAY_NAME, "SpektraLens-queue-regression.jpg");
    values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
    values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SpektraLensTests");
    galleryPhoto =
        activity
            .getContentResolver()
            .insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
    assertNotNull(galleryPhoto);
    try (InputStream in = new FileInputStream(jpeg);
        OutputStream out = activity.getContentResolver().openOutputStream(galleryPhoto)) {
      byte[] data = new byte[4096];
      int n;
      while ((n = in.read(data)) != -1) out.write(data, 0, n);
    }
    File previous = (File) field("sourceFile");
    CountDownLatch started = new CountDownLatch(1);
    release = new CountDownLatch(1);
    ((ExecutorService) field("worker"))
        .execute(
            () -> {
              started.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    assertTrue(started.await(15, TimeUnit.SECONDS));
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                call("importUri", new Class<?>[] {Uri.class}, galleryPhoto);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    boolean imported = false;
    while (System.nanoTime() < end) {
      File source = (File) field("sourceFile");
      if (source != null
          && !source.equals(previous)
          && source.getParentFile().getName().equals("photos")
          && source.length() == jpeg.length()) {
        Bitmap b = (Bitmap) field("source");
        imported = b.getWidth() == 160 && b.getHeight() == 120;
        if (imported) break;
      }
      Thread.sleep(50);
    }
    assertTrue(
        "A blocked render queue left the local JPEG at '正在读取照片'; preview import must use"
            + " independent IO",
        imported);
  }

  private void waitForByte() throws Exception {
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < until) {
      PhotoImportJob j = (PhotoImportJob) field("activeImport");
      if (j != null && j.copied > 0) return;
      Thread.sleep(30);
    }
    fail("Slow provider did not reach the copy phase");
  }

  private void startSlowImport() throws Exception {
    activity = getActivity();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                call(
                    "importUri",
                    new Class<?>[] {Uri.class},
                    Uri.parse("content://app.spektrafilm.test.slow-import/photo"));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    waitForByte();
  }

  public void testSlowProviderCanBeCancelledAndLocalPhotoRetried() throws Exception {
    startSlowImport();
    PhotoImportJob job = (PhotoImportJob) field("activeImport");
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                call("cancelImport", new Class<?>[] {String.class}, "已取消导入");
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    assertNull(field("activeImport"));
    assertTrue(job.cancelled.get());
    File previous = (File) field("sourceFile"),
        jpeg = new File(activity.getFilesDir(), "retry-import.jpg");
    Bitmap b = Bitmap.createBitmap(167, 123, Bitmap.Config.ARGB_8888);
    b.eraseColor(0xff326aa3);
    try (OutputStream out = new FileOutputStream(jpeg)) {
      b.compress(Bitmap.CompressFormat.JPEG, 95, out);
    }
    b.recycle();
    getInstrumentation()
        .runOnMainSync(
            () -> {
              try {
                call("importUri", new Class<?>[] {Uri.class}, Uri.fromFile(jpeg));
              } catch (Exception e) {
                throw new RuntimeException(e);
              }
            });
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    boolean imported = false;
    while (System.nanoTime() < until) {
      File current = (File) field("sourceFile");
      if (current != null
          && !current.equals(previous)
          && ((Bitmap) field("source")).getWidth() == 167) {
        imported = true;
        break;
      }
      Thread.sleep(30);
    }
    assertTrue("Cancel must leave the reader usable for the next local photo", imported);
  }

  public void testStalledReadTimesOutAndRetainsPreviousPhoto() throws Exception {
    startSlowImport();
    File previous = (File) field("sourceFile");
    PhotoImportJob job = (PhotoImportJob) field("activeImport");
    job.lastProgress = android.os.SystemClock.elapsedRealtime() - 31000;
    long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
    while (field("activeImport") != null && System.nanoTime() < until) Thread.sleep(30);
    assertNull("Watchdog must terminate the stalled import", field("activeImport"));
    assertTrue(job.cancelled.get());
    assertEquals(
        "Timeout feedback stays visible above the tools",
        android.view.View.VISIBLE,
        ((android.view.View) field("statusRow")).getVisibility());
    assertTrue(((android.widget.TextView) field("status")).getText().toString().contains("超时"));
    assertEquals(previous, field("sourceFile"));
    String text;
    try (InputStream in =
            new FileInputStream(new File(activity.getFilesDir(), "last_import.json"));
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] data = new byte[4096];
      int n;
      while ((n = in.read(data)) != -1) out.write(data, 0, n);
      text = out.toString("UTF-8");
    }
    assertEquals("timeout", new org.json.JSONObject(text).getString("outcome"));
  }

  public void testRasterSignaturesUseContentRatherThanExtension() throws Exception {
    activity = getActivity();
    File file = new File(activity.getCacheDir(), "ordinary-photo.img");
    Bitmap b = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
    try (OutputStream out = new FileOutputStream(file)) {
      b.compress(Bitmap.CompressFormat.PNG, 100, out);
    }
    b.recycle();
    assertTrue(PhotoFormats.isRaster(file));
    try (OutputStream out = new FileOutputStream(file)) {
      out.write(new byte[] {'I', 'I', 42, 0, 8, 0, 0, 0});
    }
    assertFalse("DNG/TIFF must still go through the RAW probe", PhotoFormats.isRaster(file));
  }
}
