// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs real queue/cache/publication code on the host JVM; timings are not Android GPU timings. */
public final class ThumbnailPerformanceTest {
  private static int checks;

  private static void check(boolean condition, String label) {
    checks++;
    if (!condition) throw new AssertionError(label);
  }

  private static void scheduling() throws Exception {
    ThumbnailWorkQueue queue = new ThumbnailWorkQueue();
    for (int i = 0; i < 20; i++) queue.offer("photo" + i, "recipe1");
    check(queue.pendingCount() == 20, "batch has twenty distinct photos");
    check(!queue.offer("photo0", "recipe1"), "duplicate pending work coalesces");
    ThumbnailWorkQueue.Job visible = queue.poll(id -> id.equals("photo18") ? 0 : 2);
    check(visible.id.equals("photo18"), "visible photo jumps ahead of offscreen FIFO");
    check(queue.poll(id -> 0) == null, "single renderer cannot run concurrent stocks");
    check(!queue.offer(visible.id, "recipe1"), "duplicate in-flight work coalesces");
    queue.offer(visible.id, "recipe2");
    check(!queue.isCurrent(visible), "new recipe revokes old render and delayed writes");
    queue.finish(visible);
    ThumbnailWorkQueue.Job newer = queue.poll(id -> id.equals("photo18") ? 0 : 2);
    check(newer.params.equals("recipe2"), "latest recipe is selected");
    queue.retry(newer);
    check(queue.isCurrent(newer), "pausing does not invalidate latest recipe");
    check(queue.poll(id -> id.equals("photo18") ? 0 : 2) == newer, "resume retries the same job");
    queue.finish(newer);
    check(queue.isCurrent(newer), "publication remains valid after rendering completes");
    queue.invalidate(newer.id);
    check(!queue.isCurrent(newer), "deletion invalidates delayed publication");
    ThumbnailWorkQueue.Job old = queue.poll(id -> 0);
    queue.offer(old.id, "newest");
    queue.retry(old);
    queue.finish(old);
    ThumbnailWorkQueue.Job latest = queue.poll(id -> id.equals(old.id) ? 0 : 2);
    check(latest.params.equals("newest"), "stale retry cannot replace a newer pending job");
    queue.clear();
    check(!queue.isCurrent(latest) && queue.pendingCount() == 0, "shutdown revokes every token");
    queue.finish(latest);

    // A deterministic executor simulation demonstrates where the main preview can take over.
    for (int i = 0; i < 20; i++) queue.offer("photo" + i, "r");
    var worker = Executors.newSingleThreadExecutor();
    List<String> events = new ArrayList<>();
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    ThumbnailWorkQueue.Job first = queue.poll(id -> 0);
    var firstResult = worker.submit(() -> {
      events.add(first.id);
      entered.countDown();
      release.await();
      return true;
    });
    check(entered.await(3, TimeUnit.SECONDS), "first render entered worker");
    var mainPreview = worker.submit(() -> events.add("main-preview"));
    release.countDown();
    firstResult.get(3, TimeUnit.SECONDS);
    queue.finish(first);
    ThumbnailWorkQueue.Job second = queue.poll(id -> id.equals("photo19") ? 0 : 2);
    worker.submit(() -> events.add(second.id)).get(3, TimeUnit.SECONDS);
    mainPreview.get(3, TimeUnit.SECONDS);
    worker.shutdown();
    check(events.equals(Arrays.asList("photo0", "main-preview", "photo19")),
        "main preview waits for at most the current photo, not nineteen more photos");
    System.out.println("SIMULATION 20 photos: " + events + "; remaining=" + queue.pendingCount());
  }

  private static void publication() throws Exception {
    ThumbnailWriteGate gate = new ThumbnailWriteGate();
    AtomicInteger published = new AtomicInteger();
    long first = gate.begin("photo");
    long second = gate.begin("photo");
    check(!gate.commit("photo", first, () -> true, () -> { published.incrementAndGet(); return true; }),
        "an older write cannot overwrite a newer one");
    gate.finish("photo", first);
    check(gate.isWriting("photo"), "old completion cannot release a new write slot");
    check(!gate.commit("photo", second, () -> false, () -> true), "revoked render cannot publish");
    check(gate.commit("photo", second, () -> true, () -> { published.incrementAndGet(); return true; }),
        "current result can publish");
    gate.finish("photo", second);
    check(!gate.isWriting("photo") && gate.isCurrent("photo", second), "display remains valid after save");
    gate.invalidate("photo");
    check(!gate.commit("photo", second, () -> true, () -> true), "delete prevents republishing");
    check(published.get() == 1, "exactly one result was published");

    // Invalidation must wait for an already-started atomic rename, then make every later write fail.
    long token = gate.begin("race");
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    var io = Executors.newFixedThreadPool(2);
    var commit = io.submit(() -> gate.commit("race", token, () -> true, () -> {
      entered.countDown();
      try { return release.await(3, TimeUnit.SECONDS); }
      catch (InterruptedException ex) { throw new AssertionError(ex); }
    }));
    check(entered.await(3, TimeUnit.SECONDS), "publication race entered");
    var invalidation = io.submit(() -> gate.invalidate("race"));
    release.countDown();
    check(commit.get(3, TimeUnit.SECONDS), "already-started publication completes under its lock");
    invalidation.get(3, TimeUnit.SECONDS);
    check(!gate.commit("race", token, () -> true, () -> true), "no resurrection after invalidation");
    gate.close();
    long closed = gate.begin("closed");
    check(!gate.commit("closed", closed, () -> true, () -> true), "shutdown drains work without publishing");
    io.shutdown();
  }

  private static boolean put(ThumbnailDiskCache cache, String key, byte[] bytes) throws IOException {
    return cache.put(key, out -> { out.write(bytes); return true; });
  }

  private static void disk() throws Exception {
    File root = Files.createTempDirectory("spektra-thumbs-").toFile();
    try {
      File directory = new File(root, "cache");
      File original = new File(root, "original.jpg");
      Files.write(original.toPath(), new byte[] {42});
      ThumbnailDiskCache cache = new ThumbnailDiskCache(directory, 12);
      String photo = ThumbnailDiskCache.key(original.getAbsolutePath());
      String a = photo + "-" + ThumbnailDiskCache.key("a");
      String b = photo + "-" + ThumbnailDiskCache.key("b");
      String c = ThumbnailDiskCache.key("other-photo") + "-" + ThumbnailDiskCache.key("c");
      check(!ThumbnailDiskCache.key("ab", "c").equals(ThumbnailDiskCache.key("a", "bc")),
          "length-delimited keys prevent ambiguous input tuples");
      check(ThumbnailDiskCache.key("原片", "v1").equals(ThumbnailDiskCache.key("原片", "v1")),
          "UTF-8 cache keys remain stable across sessions");
      String[] tuple = {"source", "length", "mtime", "engine", "backend", "neutral-recipe", "size"};
      for (int i = 0; i < tuple.length; i++) {
        String[] altered = tuple.clone();
        altered[i] += "changed";
        check(!ThumbnailDiskCache.key(tuple).equals(ThumbnailDiskCache.key(altered)),
            "identity change at field " + i + " invalidates cache");
      }
      check(put(cache, a, new byte[] {1, 2, 3, 4}), "lossless bytes published");
      check(Arrays.equals(Files.readAllBytes(cache.get(a).toPath()), new byte[] {1, 2, 3, 4}),
          "cache read preserves exact rendered bytes");
      check(!cache.put(a, out -> { out.write(99); return false; }), "failed encoder rejected");
      boolean threw = false;
      try { cache.put(a, out -> { throw new IOException("expected encoder error"); }); }
      catch (IOException expected) { threw = true; }
      check(threw, "encoder exception propagated and temporary file cleaned up");
      check(Files.readAllBytes(cache.get(a).toPath())[0] == 1, "failed replacement preserves old entry");
      check(!put(cache, b, new byte[13]), "oversized result rejected before eviction");
      check(cache.get(a) != null, "oversized result preserves useful cache");
      put(cache, b, new byte[4]);
      put(cache, c, new byte[4]);
      cache.get(a); // a becomes the most recently used entry.
      String d = ThumbnailDiskCache.key("d");
      put(cache, d, new byte[4]);
      check(cache.get(a) != null && cache.get(b) == null && cache.get(c) != null,
          "LRU keeps recently viewed entries within the byte budget");
      ThumbnailDiskCache reopened = new ThumbnailDiskCache(directory, 12);
      check(reopened.get(a) != null, "new cache instance reuses completed files");
      Files.write(new File(directory, "interrupted.pending").toPath(), new byte[] {0});
      reopened.sweepPending();
      check(!new File(directory, "interrupted.pending").exists(), "interrupted writes are swept");
      reopened.removePhoto(photo);
      check(reopened.get(a) == null && reopened.get(c) != null, "delete only removes requested photo");
      check(Files.readAllBytes(original.toPath())[0] == 42, "cache operations never modify original");
      boolean invalid = false;
      try { cache.get("../../original.jpg"); }
      catch (IllegalArgumentException expected) { invalid = true; }
      check(invalid, "cache keys cannot escape the private directory");
    } finally {
      try (var paths = Files.walk(root.toPath())) {
        paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      }
    }
  }

  public static void main(String[] args) throws Exception {
    scheduling();
    publication();
    disk();
    System.out.println("PASS thumbnail performance policy: " + checks + " checks");
  }
}
