// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

/**
 * Universal three-tier foldable window-width policy (no device model allowlists).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link Tier#COMPACT} ({@code < 560dp}): cover screens and slab phones stay 100% identical
 *       to shipped v0.6.0 behavior and layout.</li>
 *   <li>{@link Tier#MEDIUM} ({@code 560..719dp}): narrow foldables and 1:1 split-screen.</li>
 *   <li>{@link Tier#EXPANDED} ({@code >= 720dp}): large foldables unfolded and tablets.</li>
 * </ul>
 */
final class FoldableLayout {
  enum Tier {
    COMPACT,
    MEDIUM,
    EXPANDED
  }

  static final int MEDIUM_MIN_DP = 560;
  static final int EXPANDED_MIN_DP = 720;

  private FoldableLayout() {}

  static Tier tier(int widthDp) {
    if (widthDp >= EXPANDED_MIN_DP) return Tier.EXPANDED;
    if (widthDp >= MEDIUM_MIN_DP) return Tier.MEDIUM;
    return Tier.COMPACT;
  }

  static boolean isFoldable(int widthDp) {
    return widthDp >= MEDIUM_MIN_DP;
  }

  static int homeColumns(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 4;
      case MEDIUM -> 3;
      case COMPACT -> 2;
    };
  }

  static int homePaddingDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 24;
      case MEDIUM -> 20;
      case COMPACT -> 18;
    };
  }

  /** Returns {@code -1} on COMPACT to keep ordinary full-width behavior untouched. */
  static int homeConstrainedMaxDp(int widthDp) {
    return isFoldable(widthDp) ? 500 : -1;
  }

  static boolean useSplitEditor(int widthDp, int heightDp) {
    if (isFoldable(widthDp)) return true;
    // Preserve legacy landscape phone split-editor rule unchanged.
    return widthDp >= 600 && heightDp < 500;
  }

  /** Right-hand editor panel width in dp for Medium/Expanded; {@code -1} on COMPACT. */
  static int editorPanelWidthDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 336;
      case MEDIUM -> 296;
      case COMPACT -> -1;
    };
  }

  /** Film gallery grid columns in split editor; {@code 0} means ordinary horizontal scroll row. */
  static int editorFilmGridColumns(int widthDp) {
    return isFoldable(widthDp) ? 3 : 0;
  }

  /** Border style grid columns in split editor; {@code 0} means ordinary horizontal scroll row. */
  static int editorBorderGridColumns(int widthDp) {
    return isFoldable(widthDp) ? 3 : 0;
  }

  static int editorStripColumns(int widthDp) {
    return tier(widthDp) == Tier.EXPANDED ? 6 : 5;
  }

  static int cropDeckMaxDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 520;
      case MEDIUM -> 480;
      case COMPACT -> -1;
    };
  }

  static boolean lightTableDefaultLandscape(int widthDp) {
    return isFoldable(widthDp);
  }

  /** Unfolded widths show the add-photo picker as a floating card over the table. */
  static boolean lightTableAddPopup(int widthDp) {
    return isFoldable(widthDp);
  }

  static int lightTableAddPopupWidthDp(int widthDp) {
    return isFoldable(widthDp) ? 232 : -1;
  }

  static int lightTableContextBarMaxDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 480;
      case MEDIUM -> 440;
      case COMPACT -> -1;
    };
  }

  static int readingPageMaxDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 600;
      case MEDIUM -> 560;
      case COMPACT -> -1;
    };
  }

  static int galleryColumns(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 5;
      case MEDIUM -> 4;
      case COMPACT -> 3;
    };
  }

  static int gallerySheetMaxDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 640;
      case MEDIUM -> 580;
      case COMPACT -> 720;
    };
  }

  static int lightTableExportSheetMaxDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 640;
      case MEDIUM -> 580;
      case COMPACT -> -1;
    };
  }

  static int exportSheetMaxDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 560;
      case MEDIUM -> 520;
      case COMPACT -> 560;
    };
  }

  static int sponsorSheetMaxDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 540;
      case MEDIUM -> 520;
      case COMPACT -> 600;
    };
  }

  static int actionSheetMaxDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 520;
      case MEDIUM -> 480;
      case COMPACT -> -1;
    };
  }

  static int exitConfirmMaxDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 480;
      case MEDIUM -> 460;
      case COMPACT -> 480;
    };
  }

  static int updateDialogMaxDp(int widthDp) {
    return switch (tier(widthDp)) {
      case EXPANDED -> 480;
      case MEDIUM -> 460;
      case COMPACT -> 480;
    };
  }

  static int alertDialogMaxDp(int widthDp, boolean singleChoice) {
    return switch (tier(widthDp)) {
      case EXPANDED -> singleChoice ? 420 : 440;
      case MEDIUM -> singleChoice ? 400 : 420;
      case COMPACT -> 480;
    };
  }

  static int noticeMaxDp(int widthDp) {
    return isFoldable(widthDp) ? 360 : 320;
  }
}
