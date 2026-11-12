// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.pm.PackageInstaller;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Exercise the real updater with deterministic network fixtures; no public release is needed. */
public final class UpdateInstrumentedTest extends ActivityInstrumentationTestCase2<MainActivity> {
  private static final Map<String, Response> RESPONSES = new ConcurrentHashMap<>();
  private static final List<String> REQUESTS = Collections.synchronizedList(new ArrayList<>());
  private static boolean factoryInstalled;
  private static final String BAD_HASH =
      "0000000000000000000000000000000000000000000000000000000000000000";
  private MainActivity activity;
  private UpdateController controller;

  public UpdateInstrumentedTest() {
    super(MainActivity.class);
  }

  private static final class Response {
    int status = 200;
    String location;
    long length = -1;
    byte[] bytes = new byte[0];
    CountDownLatch readStarted, releaseRead;
    File file;

    Response(byte[] bytes) {
      this.bytes = bytes;
    }

    HttpURLConnection open(URL url) {
      return new HttpURLConnection(url) {
        public void connect() {}

        public boolean usingProxy() {
          return false;
        }

        public void disconnect() {
          if (releaseRead != null) releaseRead.countDown();
        }

        public int getResponseCode() {
          return status;
        }

        public long getContentLengthLong() {
          return length;
        }

        public String getHeaderField(String name) {
          return "Location".equals(name) ? location : null;
        }

        public InputStream getInputStream() throws IOException {
          if (file != null) return new FileInputStream(file);
          return new ByteArrayInputStream(bytes) {
            @Override
            public synchronized int read(byte[] b, int off, int count) {
              if (readStarted != null) {
                readStarted.countDown();
                try {
                  releaseRead.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
              return super.read(b, off, count);
            }
          };
        }
      };
    }
  }

  protected void setUp() throws Exception {
    super.setUp();
    synchronized (UpdateInstrumentedTest.class) {
      if (!factoryInstalled) {
        URL.setURLStreamHandlerFactory(
            protocol ->
                "https".equals(protocol) || "http".equals(protocol)
                    ? new URLStreamHandler() {
                      protected URLConnection openConnection(URL url) throws IOException {
                        REQUESTS.add(url.toString());
                        Response response = RESPONSES.get(url.toString());
                        if (response == null) throw new IOException("Unexpected test URL " + url);
                        return response.open(url);
                      }
                    }
                    : null);
        factoryInstalled = true;
      }
    }
    RESPONSES.clear();
    REQUESTS.clear();
    activity = getActivity();
    main(() -> controller = new UpdateController(activity));
  }

  protected void tearDown() throws Exception {
    for (Response response : RESPONSES.values())
      if (response.releaseRead != null) response.releaseRead.countDown();
    if (controller != null) {
      main(
          () -> {
            controller.close();
            activity.getSharedPreferences("pending_update", 0).edit().clear().commit();
          });
      ExecutorService worker = (ExecutorService) field("worker");
      assertTrue(
          "Update worker finishes and cleans up", worker.awaitTermination(8, TimeUnit.SECONDS));
    }
    super.tearDown();
  }

  private void main(Runnable run) {
    getInstrumentation().runOnMainSync(run);
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
    try {
      return method.invoke(controller, args);
    } catch (InvocationTargetException error) {
      if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
      throw error;
    }
  }

  private void await(BooleanSupplier condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + 8_000;
    while (SystemClock.elapsedRealtime() < end) {
      boolean[] done = {false};
      main(() -> done[0] = condition.getAsBoolean());
      if (done[0]) return;
      Thread.sleep(25);
    }
    fail("Updater did not reach the expected state");
  }

  private boolean busy() {
    try {
      return (Boolean) field("busy");
    } catch (Exception error) {
      throw new RuntimeException(error);
    }
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

  private String transfer(String url, long limit) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Class<?> progress = Class.forName("app.spektrafilm.UpdateController$Progress");
    call(
        "transfer",
        new Class<?>[] {String.class, OutputStream.class, long.class, progress},
        url,
        out,
        limit,
        null);
    return out.toString("UTF-8");
  }

  private void rejectTransfer(String url, long limit) throws Exception {
    try {
      transfer(url, limit);
      fail("Unsafe or invalid response accepted");
    } catch (IOException | IllegalArgumentException expected) {
    }
  }

  public void testTransferAllowsHttpsRedirectButRejectsHttpAndDowngrades() throws Exception {
    String start = "https://updates.invalid/start", end = "https://cdn.invalid/app";
    Response redirect = new Response(new byte[0]);
    redirect.status = 302;
    redirect.location = end;
    RESPONSES.put(start, redirect);
    RESPONSES.put(end, new Response("valid".getBytes("UTF-8")));
    assertEquals("valid", transfer(start, 10));
    REQUESTS.clear();
    redirect.location = "http://cdn.invalid/app";
    rejectTransfer(start, 10);
    assertEquals(Collections.singletonList(start), REQUESTS);
    REQUESTS.clear();
    rejectTransfer("http://updates.invalid/start", 10);
    assertTrue("HTTP must be rejected before opening a connection", REQUESTS.isEmpty());
  }

  public void testTransferBoundsBytesTruncationAndRedirectCount() throws Exception {
    String url = "https://updates.invalid/file";
    Response response = new Response(new byte[11]);
    RESPONSES.put(url, response);
    rejectTransfer(url, 10);
    response.bytes = new byte[4];
    response.length = 7;
    rejectTransfer(url, 10);
    response.status = 302;
    response.location = url;
    REQUESTS.clear();
    rejectTransfer(url, 10);
    assertEquals("At most five redirects plus the initial response", 6, REQUESTS.size());
  }

  public void testCheckIsManualAndOnlyRequestsTheFixedManifest() throws Exception {
    Thread.sleep(150);
    assertTrue("Opening the activity does not contact the update server", REQUESTS.isEmpty());
    RESPONSES.put(
        UpdateController.MANIFEST,
        new Response(
            ("{\"versionCode\":1,\"versionName\":\"test\",\"apkUrl\":\"https://updates.invalid/a.apk\","
                 + "\"sha256\":\""
                    + BAD_HASH
                    + "\"}")
                .getBytes("UTF-8")));
    main(controller::check);
    await(() -> !busy());
    assertEquals(Collections.singletonList(UpdateController.MANIFEST), REQUESTS);
    long end = SystemClock.elapsedRealtime() + 3000;
    while (!screenContains("已是最新版本") && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
    assertTrue("An older manifest is reported as up to date", screenContains("已是最新版本"));
  }

  public void testUnreachableManifestAsksToRetryInsteadOfClaimingUpToDate() throws Exception {
    main(controller::check);
    await(() -> !busy());
    long end = SystemClock.elapsedRealtime() + 3000;
    while (!screenContains("暂时无法检查更新") && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
    assertTrue(
        "A manifest that cannot be fetched must not read as an up-to-date install",
        screenContains("暂时无法检查更新"));
    assertFalse("Up-to-date copy is reserved for a real manifest", screenContains("已是最新版本"));
  }

  public void testCancelUnwindsWorkerAndAllowsAnotherCheck() throws Exception {
    Response response = new Response("{}".getBytes("UTF-8"));
    response.readStarted = new CountDownLatch(1);
    response.releaseRead = new CountDownLatch(1);
    RESPONSES.put(UpdateController.MANIFEST, response);
    main(controller::check);
    assertTrue(response.readStarted.await(3, TimeUnit.SECONDS));
    main(
        () -> {
          try {
            call("cancel", new Class<?>[] {});
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    await(() -> !busy());
    assertFalse(((File) field("updateFile")).exists());
    RESPONSES.put(UpdateController.MANIFEST, new Response(new byte[0]));
    main(controller::check);
    await(() -> !busy());
    assertEquals(2, REQUESTS.size());
  }

  public void testCorruptDownloadIsRemovedAndNeverCreatesAnInstallSession() throws Exception {
    PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
    int sessions = installer.getMySessions().size();
    String url = "https://updates.invalid/corrupt.apk";
    RESPONSES.put(url, new Response("corrupt".getBytes("UTF-8")));
    UpdateManifest manifest =
        UpdateManifest.parse(
            "{\"versionCode\":19,\"versionName\":\"test\",\"apkUrl\":\""
                + url
                + "\",\"sha256\":\""
                + BAD_HASH
                + "\"}");
    main(
        () -> {
          try {
            call("download", new Class<?>[] {UpdateManifest.class}, manifest);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    await(() -> !busy());
    assertFalse("Rejected APK was removed", ((File) field("updateFile")).exists());
    assertEquals(
        "A failed hash must never reach PackageInstaller",
        sessions,
        installer.getMySessions().size());
  }

  public void testInstallRechecksHashAfterPermissionReturn() throws Exception {
    PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
    int sessions = installer.getMySessions().size();
    File file = (File) field("updateFile");
    file.getParentFile().mkdirs();
    try (FileOutputStream out = new FileOutputStream(file)) {
      out.write("changed".getBytes("UTF-8"));
    }
    main(
        () -> {
          try {
            call("install", new Class<?>[] {String.class}, BAD_HASH);
          } catch (Exception error) {
            throw new RuntimeException(error);
          }
        });
    await(() -> !busy());
    assertFalse(file.exists());
    assertEquals(sessions, installer.getMySessions().size());
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

  public void testMirrorFallbackKeepsOnlyTheVerifiedApkAndAsksForPermission() throws Exception {
    boolean allowed = activity.getPackageManager().canRequestPackageInstalls();
    assertFalse(
        "Run with unknown-source permission denied; device_tests.py --test-update-install"
            + " configures it",
        allowed);
    {
      File installed = new File(activity.getApplicationInfo().sourceDir);
      String expected = hash(installed);
      String first = "https://updates.invalid/corrupt.apk";
      String second = "https://mirror.invalid/verified.apk";
      RESPONSES.put(first, new Response("bad".getBytes("UTF-8")));
      Response good = new Response(new byte[0]);
      good.file = installed;
      good.length = installed.length();
      RESPONSES.put(second, good);
      UpdateManifest manifest =
          UpdateManifest.parse(
              "{\"versionCode\":19,\"versionName\":\"test\",\"sizeBytes\":"
                  + installed.length()
                  + ",\"apkUrl\":\""
                  + first
                  + "\",\"apkUrlMirrors\":[\""
                  + second
                  + "\"],\"sha256\":\""
                  + expected
                  + "\"}");
      main(
          () -> {
            try {
              call("download", new Class<?>[] {UpdateManifest.class}, manifest);
            } catch (Exception error) {
              throw new RuntimeException(error);
            }
          });
      await(() -> !busy());
      assertEquals(Arrays.asList(first, second), REQUESTS);
      assertTrue(UpdateManifest.verify((File) field("updateFile"), expected));
      long end = SystemClock.elapsedRealtime() + 3000;
      while (!screenContains("允许安装更新") && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
      assertTrue(
          "A verified file still requires unknown-source permission", screenContains("允许安装更新"));
    }
  }

  public void testResumeAfterPermissionReturnReusesPackageAndOffersRetry() throws Exception {
    assertFalse(
        "device_tests.py --test-update-install denies this permission",
        activity.getPackageManager().canRequestPackageInstalls());
    PackageInstaller installer = activity.getPackageManager().getPackageInstaller();
    int sessions = installer.getMySessions().size();
    File file = (File) field("updateFile");
    file.getParentFile().mkdirs();
    try (OutputStream out = new FileOutputStream(file)) {
      out.write("verified".getBytes("UTF-8"));
    }
    android.content.SharedPreferences pending =
        activity.getSharedPreferences("pending_update", 0);
    // Writing and consuming the pending state in one main-thread step keeps the activity's own
    // controller from claiming it on resume first.
    main(
        () -> {
          pending.edit().putString("sha256", BAD_HASH).putString("file", file.getName()).commit();
          controller.resume();
        });
    assertTrue("Resume reuses the verified package instead of downloading it again", file.exists());
    assertNull("Pending state survives exactly one resume", pending.getString("sha256", null));
    assertEquals(
        "Resume must not reach PackageInstaller without the permission",
        sessions,
        installer.getMySessions().size());
    long end = SystemClock.elapsedRealtime() + 3000;
    while (!screenContains("尚未允许安装") && SystemClock.elapsedRealtime() < end) Thread.sleep(50);
    assertTrue(
        "Resume explains the still-missing permission and offers a retry",
        screenContains("尚未允许安装") && screenContains("重试"));
    file.delete();
  }
}
