// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises real Bitmap ownership, early display and invalidation while a save is paused. */
public final class ThumbnailPersistenceInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private HomePhotoImages images;
  private FrameLayout decor;
  private ScrollView scroll;
  private ImageView image;
  private File original, developed;
  private String id;
  private ExecutorService rendererWorker;
  private final CountDownLatch release = new CountDownLatch(1);

  public ThumbnailPersistenceInstrumentedTest() { super(MainActivity.class); }

  private void main(Runnable action) {
    getInstrumentation().runOnMainSync(action);
    getInstrumentation().waitForIdleSync();
  }

  @Override protected void setUp() throws Exception {
    super.setUp();
    MainActivity activity = getActivity();
    id = "persistence-test-" + System.nanoTime();
    original = new File(activity.getCacheDir(), id + ".jpg");
    developed = new File(new File(activity.getFilesDir(), "developed"), id + ".jpg");
    Bitmap blue = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
    blue.eraseColor(Color.BLUE);
    try (FileOutputStream out = new FileOutputStream(original)) {
      blue.compress(Bitmap.CompressFormat.JPEG, 90, out);
    }
    blue.recycle();
    rendererWorker = Executors.newSingleThreadExecutor();
    main(() -> {
      decor = activity.findViewById(android.R.id.content);
      scroll = new ScrollView(activity);
      image = new ImageView(activity);
      image.setLayoutParams(new ScrollView.LayoutParams(180, 180));
      image.setMinimumHeight(180);
      scroll.addView(image);
      FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(180, 180, Gravity.TOP | Gravity.LEFT);
      decor.addView(scroll, params);
      images = new HomePhotoImages(activity, new Handler(Looper.getMainLooper()), scroll, rendererWorker);
      images.bind(image, original, developed, null, false);
    });
    long deadline = SystemClock.elapsedRealtime() + 5000;
    while (image.getWidth() == 0 && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
    assertTrue(image.getWidth() > 0);
  }

  public void testVisibleCropDisplaysBeforeJpegPersistence() throws Exception {
    CountDownLatch beforeSave = new CountDownLatch(1);
    AtomicInteger calls = new AtomicInteger();
    Bitmap red = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
    red.setHasAlpha(false);
    red.eraseColor(Color.RED);
    main(() -> images.storeDevelopedOwned(id, red, () -> {
      if (Looper.myLooper() != Looper.getMainLooper() && calls.incrementAndGet() == 2) {
        beforeSave.countDown();
        try { return release.await(5, TimeUnit.SECONDS); }
        catch (InterruptedException ex) { return false; }
      }
      return true;
    }));
    assertTrue("IO reached persistence after posting visible crop", beforeSave.await(5, TimeUnit.SECONDS));
    getInstrumentation().waitForIdleSync();
    main(() -> {
      assertTrue(image.getDrawable() instanceof BitmapDrawable);
      Bitmap shown = ((BitmapDrawable) image.getDrawable()).getBitmap();
      assertEquals("Direct rendered pixels displayed", Color.RED, shown.getPixel(20, 20));
      assertFalse("Display did not wait for JPEG encoding or decoding", developed.exists());
    });
    release.countDown();
    awaitSaved(red, true);
  }

  public void testInvalidatedSaveCannotResurrectDeletedLook() throws Exception {
    CountDownLatch beforeSave = new CountDownLatch(1);
    Bitmap red = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
    red.setHasAlpha(false);
    red.eraseColor(Color.RED);
    main(() -> {
      scroll.setVisibility(View.GONE);
      images.storeDevelopedOwned(id, red, () -> {
        if (Looper.myLooper() != Looper.getMainLooper()) {
          beforeSave.countDown();
          try { return release.await(5, TimeUnit.SECONDS); }
          catch (InterruptedException ex) { return false; }
        }
        return true;
      });
    });
    assertTrue(beforeSave.await(5, TimeUnit.SECONDS));
    main(() -> { images.removeDeveloped(id); developed.delete(); });
    release.countDown();
    awaitSaved(red, false);
  }

  public void testClosingReleasesUnreceivedHandlerBitmaps() {
    Bitmap owned = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
    AtomicInteger received = new AtomicInteger();
    main(() -> {
      PendingBitmaps deliveries = new PendingBitmaps();
      deliveries.post(new Handler(Looper.getMainLooper()), owned, received::incrementAndGet);
      deliveries.close();
      assertTrue(owned.isRecycled());
    });
    assertEquals("Closed owner cancels receipt", 0, received.get());
  }

  public void testTransparentFilmLookKeepsAlphaOnDiskAndUsesMatteAtHome() throws Exception {
    Bitmap film = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
    film.eraseColor(0x80334455);
    film.setPixel(64, 64, Color.TRANSPARENT);
    CountDownLatch saved = new CountDownLatch(1);
    main(() -> {
      images.setDevelopedListener(ready -> {
        if (id.equals(ready)) saved.countDown();
      });
      images.storeDevelopedOwned(id, film, () -> true);
    });
    assertTrue("Film preview was published", saved.await(5, TimeUnit.SECONDS));
    File alpha = HomePhotoImages.alphaLook(developed);
    assertEquals(alpha, HomePhotoImages.preferredLook(developed));
    assertFalse("A film preview needs only one file", developed.exists());
    Bitmap read = BitmapFactory.decodeFile(alpha.getAbsolutePath());
    assertNotNull(read);
    assertEquals(0, Color.alpha(read.getPixel(64, 64)));
    assertEquals(128, Color.alpha(read.getPixel(15, 15)));
    read.recycle();
    main(() -> {
      assertTrue(image.getDrawable() instanceof BitmapDrawable);
      Bitmap shown = ((BitmapDrawable) image.getDrawable()).getBitmap();
      assertEquals(255, Color.alpha(shown.getPixel(20, 20)));
      images.removeDeveloped(id);
    });
    assertFalse("Removing the photo clears its alpha look", alpha.exists());
  }

  private void awaitSaved(Bitmap owned, boolean exists) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + 5000;
    while ((!owned.isRecycled() || images.pendingWrites() != 0)
        && SystemClock.elapsedRealtime() < deadline) Thread.sleep(20);
    assertTrue("Transferred bitmap is released", owned.isRecycled());
    assertEquals("Derived file publication", exists, developed.isFile());
  }

  @Override protected void tearDown() throws Exception {
    release.countDown();
    main(() -> {
      if (images != null) images.close();
      if (decor != null && scroll != null) decor.removeView(scroll);
    });
    if (rendererWorker != null) rendererWorker.shutdownNow();
    if (original != null) original.delete();
    if (developed != null) {
      developed.delete();
      HomePhotoImages.alphaLook(developed).delete();
    }
    super.tearDown();
  }
}
