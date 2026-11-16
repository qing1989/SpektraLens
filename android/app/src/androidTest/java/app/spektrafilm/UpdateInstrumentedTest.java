// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.pm.PackageInstaller;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ScrollView;
import android.widget.TextView;
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
  private List<String> startupRequests;

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
    getInstrumentation().getTargetContext()
        .getSharedPreferences("update_notice", 0).edit().clear().commit();
    RESPONSES.put(UpdateController.MANIFEST, manifestResponse(1, 1, ""));
    activity = getActivity();
    Field updater = MainActivity.class.getDeclaredField("updates");
    updater.setAccessible(true);
    controller = (UpdateController) updater.get(activity);
    await(() -> !busy());
    startupRequests = new ArrayList<>(REQUESTS);
    REQUESTS.clear();
    RESPONSES.clear();
  }

  protected void tearDown() throws Exception {
    for (Response response : RESPONSES.values())
      if (response.releaseRead != null) response.releaseRead.countDown();
    if (controller != null) {
      main(
          () -> {
            controller.close();
            activity.getSharedPreferences("pending_update", 0).edit().clear().commit();
            activity.getSharedPreferences("update_notice", 0).edit().clear().commit();
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

  private static Response manifestResponse(int code, int minSdk, String notes) throws Exception {
    org.json.JSONObject json = new org.json.JSONObject();
    json.put("versionCode", code);
    json.put("versionName", "test");
    json.put("minSdk", minSdk);
    json.put("apkUrl", "https://updates.invalid/a.apk");
    json.put("sha256", BAD_HASH);
    json.put("sizeBytes", 123456);
    json.put("releaseNotes", notes);
    return new Response(json.toString().getBytes("UTF-8"));
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

  public void testStartupIsSilentAndOnlyRequestsTheFixedManifest() throws Exception {
    assertEquals(Collections.singletonList(UpdateController.MANIFEST), startupRequests);
    assertNull("Startup must not create a progress window", field("progress"));
    assertNull("Startup must not open a result window", field("notice"));
    assertFalse(screenContains("已是最新版本"));
    assertFalse(controller.hasAvailableUpdate());
  }

  public void testManualCheckOnlyRequestsTheFixedManifest() throws Exception {
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

  public void testBackgroundCheckUpdatesBothBadgesAndLaterDoesNotClearThem() throws Exception {
    RESPONSES.put(UpdateController.MANIFEST,
        manifestResponse(10000, 29, "- 第一项\n- 第二项"));
    main(() -> {
      View about = activity.getWindow().getDecorView().findViewWithTag("home.about");
      about.performClick();
      controller.checkInBackground();
    });
    await(() -> !busy());
    assertTrue(controller.hasAvailableUpdate());
    assertNull(field("progress"));
    assertNull(field("notice"));
    assertBadge("home.about", true);
    assertBadge("about.update", true);
    main(controller::check);
    await(() -> !busy());
    UpdateNotesDialog notice = (UpdateNotesDialog) field("notice");
    assertNotNull(notice);
    main(() -> notice.getWindow().getDecorView()
        .findViewWithTag("update.later").performClick());
    assertBadge("home.about", true);
    assertBadge("about.update", true);
    assertNotNull(activity.getSharedPreferences("update_notice", 0).getString("manifest", null));
    assertEquals(Arrays.asList(UpdateController.MANIFEST, UpdateController.MANIFEST), REQUESTS);
  }

  private void assertBadge(String tag, boolean expected) {
    boolean[] visible = {false};
    main(() -> {
      ViewGroup group = activity.getWindow().getDecorView().findViewWithTag(tag);
      visible[0] = group != null && group.getChildAt(1).getVisibility() == View.VISIBLE
          && group.getContentDescription().toString().contains("有新版本可更新");
    });
    assertEquals(tag, expected, visible[0]);
  }

  public void testManualTapJoinsStartupRequestWithoutDoubleFetching() throws Exception {
    Response response = manifestResponse(1, 1, "");
    response.readStarted = new CountDownLatch(1);
    response.releaseRead = new CountDownLatch(1);
    RESPONSES.put(UpdateController.MANIFEST, response);
    main(controller::checkInBackground);
    assertTrue(response.readStarted.await(3, TimeUnit.SECONDS));
    main(() -> {
      controller.checkInBackground();
      controller.check();
      controller.check();
    });
    assertEquals(Collections.singletonList(UpdateController.MANIFEST), REQUESTS);
    assertNotNull(field("progress"));
    response.releaseRead.countDown();
    await(() -> !busy());
    assertTrue(screenContains("已是最新版本"));
  }

  public void testBackgroundFailuresKeepConfirmedNoticeAndCacheSurvivesRecreation() throws Exception {
    RESPONSES.put(UpdateController.MANIFEST, manifestResponse(10000, 29, "更新说明"));
    main(controller::checkInBackground);
    await(() -> !busy());
    assertTrue(controller.hasAvailableUpdate());
    RESPONSES.clear();
    main(controller::checkInBackground);
    await(() -> !busy());
    assertTrue(controller.hasAvailableUpdate());
    assertNull(field("progress"));
    assertNull(field("notice"));
    assertFalse(screenContains("暂时无法检查更新"));
    UpdateController[] restored = new UpdateController[1];
    main(() -> restored[0] = new UpdateController(activity));
    assertTrue("Offline app opening restores the confirmed badge", restored[0].hasAvailableUpdate());
    main(restored[0]::close);
    main(() -> activity.getWindow().getDecorView().findViewWithTag("home.about").performClick());
    assertBadge("about.update", true);
  }

  public void testIncompatibleOrWithdrawnReleaseClearsBadges() throws Exception {
    RESPONSES.put(UpdateController.MANIFEST, manifestResponse(10000, 29, ""));
    main(controller::checkInBackground);
    await(() -> !busy());
    assertTrue(controller.hasAvailableUpdate());
    RESPONSES.put(UpdateController.MANIFEST, manifestResponse(10001, 10000, ""));
    main(controller::checkInBackground);
    await(() -> !busy());
    assertFalse(controller.hasAvailableUpdate());
    assertBadge("home.about", false);
    assertNull(activity.getSharedPreferences("update_notice", 0).getString("manifest", null));
    RESPONSES.put(UpdateController.MANIFEST, manifestResponse(1, 1, ""));
    main(controller::checkInBackground);
    await(() -> !busy());
    assertFalse(controller.hasAvailableUpdate());
  }

  public void testCloseDuringCheckDoesNotPublishAStaleResult() throws Exception {
    Response response = manifestResponse(10000, 29, "");
    response.readStarted = new CountDownLatch(1);
    response.releaseRead = new CountDownLatch(1);
    RESPONSES.put(UpdateController.MANIFEST, response);
    main(controller::checkInBackground);
    assertTrue(response.readStarted.await(3, TimeUnit.SECONDS));
    main(controller::close);
    response.releaseRead.countDown();
    ExecutorService worker = (ExecutorService) field("worker");
    assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
    main(() -> {});
    assertFalse(controller.hasAvailableUpdate());
    assertNull(field("notice"));
    assertNull(activity.getSharedPreferences("update_notice", 0).getString("manifest", null));
  }

  public void testPendingInstallSkipsBackgroundCheck() throws Exception {
    main(() -> {
      activity.getSharedPreferences("pending_update", 0).edit().putString("sha256", BAD_HASH).commit();
      controller.checkInBackground();
      activity.getSharedPreferences("pending_update", 0).edit().clear().commit();
    });
    assertFalse(busy());
    assertTrue(REQUESTS.isEmpty());
  }

  public void testCancelJoinedRequestKeepsNoticeAndAllowsSuccessfulRetry() throws Exception {
    RESPONSES.put(UpdateController.MANIFEST, manifestResponse(10000, 29, "已确认的版本"));
    main(controller::checkInBackground);
    await(() -> !busy());
    String cached = activity.getSharedPreferences("update_notice", 0).getString("manifest", null);

    Response response = manifestResponse(1, 1, "这次检查会取消");
    response.readStarted = new CountDownLatch(1);
    response.releaseRead = new CountDownLatch(1);
    RESPONSES.put(UpdateController.MANIFEST, response);
    main(controller::checkInBackground);
    assertTrue(response.readStarted.await(3, TimeUnit.SECONDS));
    main(controller::check);
    android.app.AlertDialog progress = (android.app.AlertDialog) field("progress");
    assertNotNull(progress);
    main(progress::cancel);
    await(() -> !busy());
    assertTrue("A cancelled response cannot clear the confirmed notice", controller.hasAvailableUpdate());
    assertEquals(cached,
        activity.getSharedPreferences("update_notice", 0).getString("manifest", null));
    assertNull(field("notice"));
    assertNull(field("progress"));
    assertFalse(screenContains("暂时无法检查更新"));

    RESPONSES.put(UpdateController.MANIFEST, manifestResponse(10001, 29, "- 重试成功"));
    main(controller::check);
    await(() -> !busy());
    assertNotNull("Cancellation must not suppress the next successful result", field("notice"));
    assertEquals(3, REQUESTS.size());
  }

  public void testLargeFontBadgesDoNotCoverLabelsOrMoveWhenToggled() {
    main(() -> {
      android.content.res.Configuration config =
          new android.content.res.Configuration(activity.getResources().getConfiguration());
      config.fontScale = 2f;
      android.content.Context context = activity.createConfigurationContext(config);
      for (String label : Arrays.asList("关于", "检查更新")) {
        boolean about = label.equals("关于");
        TextView action = new TextView(context);
        action.setText(label);
        action.setSingleLine(true);
        action.setGravity(android.view.Gravity.CENTER);
        action.setTextSize(about ? AppStyle.TYPE_LABEL : AppStyle.TYPE_TITLE);
        action.setMinHeight(AppStyle.dp(context, AppStyle.TOUCH_MIN));
        int horizontal = AppStyle.dp(context, about ? 0 : 24);
        int vertical = AppStyle.dp(context, about ? 0 : 14);
        action.setPadding(horizontal, vertical, horizontal, vertical);
        int[] clicks = {0};
        action.setOnClickListener(view -> clicks[0]++);
        UpdateBadge group = new UpdateBadge(action, about ? "new" : "可更新");
        int width = View.MeasureSpec.makeMeasureSpec(AppStyle.dp(context, 280), View.MeasureSpec.AT_MOST);
        int height = View.MeasureSpec.makeMeasureSpec(AppStyle.dp(context, 180), View.MeasureSpec.AT_MOST);
        group.measure(width, height);
        group.layout(0, 0, group.getMeasuredWidth(), group.getMeasuredHeight());
        int originalTop = action.getTop(), originalHeight = group.getHeight();
        group.setAvailable(true);
        group.measure(width, height);
        group.layout(0, 0, group.getMeasuredWidth(), group.getMeasuredHeight());
        View badge = group.getChildAt(1);
        assertTrue("Large badge remains above the action text",
            badge.getBottom() <= action.getTop() + action.getTotalPaddingTop());
        assertEquals(
            "A badge reserve must not pull an action off the wrapper's visual center",
            (group.getWidth() - action.getWidth()) / 2,
            action.getLeft());
        assertEquals(originalTop, action.getTop());
        assertEquals(originalHeight, group.getHeight());
        assertEquals(1, action.getLineCount());
        group.performClick();
        assertEquals("The whole badge still opens the original action", 1, clicks[0]);
        group.setEnabled(false);
        assertFalse(action.isEnabled());
        group.setEnabled(true);
        assertTrue(action.isEnabled());
      }
    });
  }

  public void testSmallWindowWithLargeTextKeepsUpdateActionsOutsideScroll() throws Exception {
    UpdateManifest manifest = UpdateManifest.parse(
        new String(manifestResponse(10000, 29, "- 长日志保留独立条目。\n".repeat(40)).bytes, "UTF-8"));
    main(() -> {
      UpdateNotesDialog dialog = new UpdateNotesDialog(activity, "test", manifest, () -> {});
      View root = dialog.getWindow().getDecorView().findViewWithTag("update.dialog");
      enlargeText(root);
      int width = AppStyle.dp(activity, 288); // A 320dp window with 16dp side margins.
      int ceiling = Math.round(AppStyle.dp(activity, 320) * .72f);
      root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(ceiling, View.MeasureSpec.AT_MOST));
      root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
      ScrollView scroll = root.findViewWithTag("update.scroll");
      View actions = root.findViewWithTag("update.actions");
      TextView download = root.findViewWithTag("update.download");
      TextView later = root.findViewWithTag("update.later");
      assertTrue(root.getHeight() <= ceiling);
      assertTrue("Even a short window retains some scrollable content", scroll.getHeight() > 0);
      assertTrue(scroll.getChildAt(0).getHeight() > scroll.getHeight());
      assertEquals(scroll.getBottom(), actions.getTop());
      assertEquals(root.getHeight(), actions.getBottom());
      assertEquals(1, download.getLineCount());
      assertEquals(1, later.getLineCount());
      assertTrue("The primary action label fits horizontally",
          download.getPaint().measureText("下载")
              <= download.getWidth() - download.getPaddingLeft() - download.getPaddingRight());
      int footerTop = actions.getTop();
      scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
      assertTrue(scroll.getScrollY() > 0);
      assertEquals(footerTop, actions.getTop());
    });
  }

  private void enlargeText(View view) {
    if (view instanceof TextView) {
      TextView text = (TextView) view;
      text.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, text.getTextSize() * 2);
    } else if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) enlargeText(group.getChildAt(i));
    }
  }

  public void testLongNotesScrollWhileActionsStayVisibleAndShortNotesShrink() throws Exception {
    UpdateManifest longer = UpdateManifest.parse(
        new String(manifestResponse(10000, 29, "## 更新内容\n" + "- 独立条目，按日志换行。\n".repeat(40)).bytes, "UTF-8"));
    UpdateManifest shorter = UpdateManifest.parse(
        new String(manifestResponse(10000, 29, "- 一项简短说明。").bytes, "UTF-8"));
    UpdateNotesDialog[] dialog = new UpdateNotesDialog[1];
    main(() -> {
      dialog[0] = new UpdateNotesDialog(activity, "test", longer, () -> {});
      dialog[0].show();
    });
    try {
      await(() -> dialog[0].getWindow().getDecorView()
          .findViewWithTag("update.dialog").getHeight() > 0);
      View decor = dialog[0].getWindow().getDecorView();
      ScrollView scroll = decor.findViewWithTag("update.scroll");
      View actions = decor.findViewWithTag("update.actions");
      View container = decor.findViewWithTag("update.dialog");
      int[] before = new int[2], after = new int[2];
      main(() -> actions.getLocationOnScreen(before));
      assertTrue(scroll.getChildAt(0).getHeight() > scroll.getHeight());
      int longHeight = container.getHeight();
      android.graphics.Rect visible = new android.graphics.Rect();
      main(() -> activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(visible));
      assertTrue("The modal is capped at 72% of the available window",
          longHeight <= Math.round(visible.height() * .72f));
      main(() -> scroll.scrollTo(0, scroll.getChildAt(0).getHeight()));
      main(() -> actions.getLocationOnScreen(after));
      assertTrue(scroll.getScrollY() > 0);
      assertEquals(before[1], after[1]);
      TextView download = decor.findViewWithTag("update.download");
      assertTrue(download.isShown());
      assertEquals(1, download.getLineCount());
      main(dialog[0]::dismiss);
      main(() -> {
        dialog[0] = new UpdateNotesDialog(activity, "test", shorter, () -> {});
        dialog[0].show();
      });
      await(() -> dialog[0].getWindow().getDecorView()
          .findViewWithTag("update.dialog").getHeight() > 0);
      View small = dialog[0].getWindow().getDecorView().findViewWithTag("update.dialog");
      assertTrue("Short notes must not fill the maximum height", small.getHeight() < longHeight);
    } finally {
      main(dialog[0]::dismiss);
    }
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
