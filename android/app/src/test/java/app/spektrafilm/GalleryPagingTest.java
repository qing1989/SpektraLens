// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

/** Host-JVM checks for the phone-gallery paging policy. */
public final class GalleryPagingTest {
  private static int checks;

  private static void check(boolean value, String message) {
    checks++;
    if (!value) throw new AssertionError(message);
  }

  public static void main(String[] args) {
    int pageSize = 400;

    GalleryPaging first = new GalleryPaging(pageSize);
    check(first.offset() == 0, "starts at the first row");
    check(
        first.accept(pageSize, pageSize, pageSize) == GalleryPaging.MORE,
        "a full page asks for more");
    check(first.offset() == pageSize, "offset advances by the rows read");

    GalleryPaging shortPage = new GalleryPaging(pageSize);
    check(
        shortPage.accept(pageSize - 1, pageSize - 1, pageSize - 1) == GalleryPaging.DONE,
        "short page ends");

    GalleryPaging emptyPage = new GalleryPaging(pageSize);
    check(emptyPage.accept(0, 0, 0) == GalleryPaging.DONE, "empty page ends");

    GalleryPaging unsupported = new GalleryPaging(pageSize);
    check(
        unsupported.accept(pageSize, 0, 0) == GalleryPaging.MORE,
        "a full page of unsupported files continues the scan");
    check(unsupported.offset() == pageSize, "unsupported rows still advance the offset");

    GalleryPaging ignoredLimit = new GalleryPaging(pageSize);
    check(
        ignoredLimit.accept(12000, 12000, 12000) == GalleryPaging.DONE,
        "provider that ignores the limit already returned everything");

    GalleryPaging ignoredOffset = new GalleryPaging(pageSize);
    ignoredOffset.accept(pageSize, pageSize, pageSize);
    check(
        ignoredOffset.accept(pageSize, pageSize, 0) == GalleryPaging.FULL_SCAN,
        "duplicate page means the provider ignores the offset");
    check(ignoredOffset.offset() == 0, "full scan restarts from the first row");
    check(ignoredOffset.accept(pageSize, pageSize, 0) == GalleryPaging.DONE, "full scan runs once");

    GalleryPaging duplicateFirstPage = new GalleryPaging(pageSize);
    check(
        duplicateFirstPage.accept(pageSize, pageSize, 0) == GalleryPaging.DONE,
        "a duplicated first page cannot loop forever");

    GalleryPaging walk = new GalleryPaging(pageSize);
    int remaining = 10000, queries = 0, loaded = 0;
    while (true) {
      int rows = Math.min(pageSize, remaining);
      remaining -= rows;
      queries++;
      loaded += rows;
      int next = walk.accept(rows, rows, rows);
      if (next == GalleryPaging.DONE) break;
      check(next == GalleryPaging.MORE, "walk stays on normal pages");
      check(queries < 40, "walk terminates");
    }
    check(loaded == 10000, "walk reads every photo");
    check(queries == 26, "10000 photos take 26 pages, including the empty last one");

    boolean rejected = false;
    try {
      new GalleryPaging(0);
    } catch (IllegalArgumentException expected) {
      rejected = true;
    }
    check(rejected, "page size must be positive");

    System.out.println("GalleryPaging: " + checks + " checks passed");
  }
}
