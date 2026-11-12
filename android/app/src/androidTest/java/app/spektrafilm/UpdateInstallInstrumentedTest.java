// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.pm.PackageInstaller;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Run separately: changing REQUEST_INSTALL_PACKAGES back can kill the instrumented process. */
public final class UpdateInstallInstrumentedTest
    extends ActivityInstrumentationTestCase2<MainActivity> {
  private MainActivity activity;
  private UpdateController controller;

  public UpdateInstallInstrumentedTest() {
    super(MainActivity.class);
  }

  protected void setUp() throws Exception {
    super.setUp();
    activity = getActivity();
    main(() -> controller = new UpdateController(activity));
  }

  protected void tearDown() throws Exception {
    main(controller::close);
    assertTrue(((ExecutorService) field("worker")).awaitTermination(8, TimeUnit.SECONDS));
    super.tearDown();
  }

  private void main(Runnable action) {
    getInstrumentation().runOnMainSync(action);
    getInstrumentation().waitForIdleSync();
  }

  private Object field(String name) throws Exception {
    Field field = UpdateController.class.getDeclaredField(name);
    field.setAccessible(true);
    return field.get(controller);
  }

  private Object call(String name, Class<?>[] types, Object... args) throws Exception {
    Method method = UpdateController.class.getDeclaredMethod(name, types);
    method.setAccessible(true);
    return method.invoke(controller, args);
  }

  private static String hash(File file) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] buffer = new byte[32768];
      int n;
      while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
    }
    StringBuilder out = new StringBuilder();
    for (byte value : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", value & 255));
    return out.toString();
  }

  private boolean screenContains(String text) {
    AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
    if (root == null) return false;
    try {
      List<AccessibilityNodeInfo> matches = root.findAccessibilityNodeInfosByText(text);
      boolean found = !matches.isEmpty();
      for (AccessibilityNodeInfo match : matches) match.recycle();
      return found;
    } finally {
      root.recycle();
    }
  }

  public void testVerifiedSessionOpensSystemConfirmationAndCanBeCancelled() throws Exception {
    boolean allowed = activity.getPackageManager().canRequestPackageInstalls();
    PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
    Set<Integer> previous = new HashSet<>();
    for (PackageInstaller.SessionInfo session : installer.getMySessions())
      previous.add(session.getSessionId());
    long version =
        activity
            .getPackageManager()
            .getPackageInfo(activity.getPackageName(), 0)
            .getLongVersionCode();
    assertTrue(
        "Run via scripts/device_tests.py --test-update-install to grant permission", allowed);
    try {
      File installed = new File(activity.getApplicationInfo().sourceDir);
      File apk = (File) field("updateFile");
      apk.getParentFile().mkdirs();
      try (InputStream in = new FileInputStream(installed);
          OutputStream out = new FileOutputStream(apk)) {
        byte[] buffer = new byte[32768];
        int n;
        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
      }
      String expected = hash(apk);
      main(
          () -> {
            try {
              call("install", new Class<?>[] {String.class}, expected);
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      long workerEnd = SystemClock.elapsedRealtime() + 8000;
      while ((Boolean) field("busy") && SystemClock.elapsedRealtime() < workerEnd) Thread.sleep(25);
      assertFalse((Boolean) field("busy"));
      long end = SystemClock.elapsedRealtime() + 8000;
      String foreground = "";
      while (SystemClock.elapsedRealtime() < end) {
        AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
        if (root != null) {
          foreground = String.valueOf(root.getPackageName());
          root.recycle();
        }
        if (foreground.contains("packageinstaller")) break;
        Thread.sleep(50);
      }
      assertTrue(
          "PackageInstaller must present confirmation, foreground=" + foreground,
          foreground.contains("packageinstaller"));
      assertEquals(
          "No installation occurs without confirmation",
          version,
          activity
              .getPackageManager()
              .getPackageInfo(activity.getPackageName(), 0)
              .getLongVersionCode());
      assertTrue(
          "Installer presents an explicit update or install choice",
          screenContains("Update")
              || screenContains("Install")
              || screenContains("更新")
              || screenContains("安装"));
      android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
      if (screenshot != null) {
        try (OutputStream out =
            new FileOutputStream(
                new File(activity.getFilesDir(), "update-system-confirmation.png"))) {
          screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        }
        screenshot.recycle();
      }
      getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      getInstrumentation().waitForIdleSync();
    } finally {
      for (PackageInstaller.SessionInfo session : installer.getMySessions())
        if (!previous.contains(session.getSessionId()))
          installer.abandonSession(session.getSessionId());
    }
  }
}
