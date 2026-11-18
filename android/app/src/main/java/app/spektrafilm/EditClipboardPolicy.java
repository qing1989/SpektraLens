// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import java.util.List;

/** Dependency-free rules shared by the recent-photo menu and batch paste flow. */
final class EditClipboardPolicy {
  static final int MAX_BATCH = 20;

  private EditClipboardPolicy() {}

  static String editMenuLabel(boolean alreadyEdited) {
    return alreadyEdited ? "再次编辑" : "开始编辑";
  }

  static boolean canPaste(boolean hasClipboard) {
    return hasClipboard;
  }

  static boolean canBatchPaste(boolean hasClipboard, int selectedCount) {
    return hasClipboard && selectedCount > 0 && selectedCount <= MAX_BATCH;
  }

  static boolean acceptsAnotherSelection(int selectedCount) {
    return selectedCount < MAX_BATCH;
  }

  static String batchButtonLabel(int selectedCount) {
    int safe = Math.max(0, Math.min(MAX_BATCH, selectedCount));
    return "确认粘贴 · " + safe + " 张";
  }

  /**
   * Finish validation before the caller mutates any frame. The same predicate is used by the UI
   * so an over-limit or empty selection can never become a partially applied batch.
   */
  static boolean validBatch(
      String copiedParams, List<?> targets, List<String> targetIds, String copiedSourceId) {
    if (copiedParams == null || copiedParams.isBlank() || targets == null) return false;
    if (targets.isEmpty() || targets.size() > MAX_BATCH) return false;
    if (targetIds == null || targetIds.size() != targets.size()) return false;
    for (String id : targetIds) if (id == null || id.isBlank()) return false;
    return true;
  }
}
