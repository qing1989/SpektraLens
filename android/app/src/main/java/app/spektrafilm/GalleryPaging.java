// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

/**
 * Paging state for the phone gallery. Decides whether another metadata page is worth asking for and
 * detects providers that ignore {@code QUERY_ARG_OFFSET}, so the picker never silently stops at the
 * first page or loops forever on duplicated rows.
 */
final class GalleryPaging {
  static final int MORE = 0, DONE = 1, FULL_SCAN = 2;

  private final int pageSize;
  private int offset;
  private boolean fullScanUsed;

  GalleryPaging(int pageSize) {
    if (pageSize <= 0) throw new IllegalArgumentException("pageSize");
    this.pageSize = pageSize;
  }

  int offset() {
    return offset;
  }

  int pageSize() {
    return pageSize;
  }

  /**
   * @param rows provider rows in the accepted page
   * @param supported rows that passed the format allow-list
   * @param added photos that were not already listed
   */
  int accept(int rows, int supported, int added) {
    // Nothing left, or the provider ignored QUERY_ARG_LIMIT and already returned everything.
    if (rows <= 0 || rows > pageSize) return DONE;
    if (rows < pageSize) return DONE;
    if (supported <= 0) {
      // A whole page of unsupported files (GIF, WebP, vendor formats) is not the end of the album.
      offset += rows;
      return MORE;
    }
    if (added <= 0) {
      // A full page of already-listed rows means this provider ignores QUERY_ARG_OFFSET.
      if (offset == 0 || fullScanUsed) return DONE;
      fullScanUsed = true;
      offset = 0;
      return FULL_SCAN;
    }
    offset += rows;
    return MORE;
  }
}
