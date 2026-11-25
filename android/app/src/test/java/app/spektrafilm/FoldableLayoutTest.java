// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

/** Pure JVM policy tests for {@link FoldableLayout} universal 3-tier breakpoints. */
public final class FoldableLayoutTest {
  private static int passed;

  public static void main(String[] args) {
    testTiersAndBoundaries();
    testCompactStaysUntouched();
    testMediumNarrowFoldable();
    testExpandedLargeFoldable();
    System.out.println("FoldableLayoutTest passed: " + passed + " checks");
  }

  private static void check(boolean condition, String label) {
    if (!condition) throw new AssertionError("Failed: " + label);
    passed++;
  }

  private static void testTiersAndBoundaries() {
    check(FoldableLayout.tier(360) == FoldableLayout.Tier.COMPACT, "360dp compact");
    check(FoldableLayout.tier(390) == FoldableLayout.Tier.COMPACT, "390dp compact");
    check(FoldableLayout.tier(559) == FoldableLayout.Tier.COMPACT, "559dp compact");
    check(FoldableLayout.tier(560) == FoldableLayout.Tier.MEDIUM, "560dp medium");
    check(FoldableLayout.tier(690) == FoldableLayout.Tier.MEDIUM, "690dp medium");
    check(FoldableLayout.tier(719) == FoldableLayout.Tier.MEDIUM, "719dp medium");
    check(FoldableLayout.tier(720) == FoldableLayout.Tier.EXPANDED, "720dp expanded");
    check(FoldableLayout.tier(820) == FoldableLayout.Tier.EXPANDED, "820dp expanded");
    check(!FoldableLayout.isFoldable(559), "559dp not foldable");
    check(FoldableLayout.isFoldable(560), "560dp foldable");
  }

  private static void testCompactStaysUntouched() {
    int w = 390;
    check(FoldableLayout.homeColumns(w) == 2, "compact home 2 cols");
    check(FoldableLayout.homePaddingDp(w) == 18, "compact home 18dp pad");
    check(FoldableLayout.homeConstrainedMaxDp(w) == -1, "compact home unconstrained");
    check(!FoldableLayout.useSplitEditor(w, 844), "compact portrait stacked editor");
    check(FoldableLayout.useSplitEditor(640, 390), "landscape phone keeps split editor");
    check(FoldableLayout.editorPanelWidthDp(w) == -1, "compact editor panel full");
    check(FoldableLayout.editorFilmGridColumns(w) == 0, "compact film horizontal");
    check(FoldableLayout.editorBorderGridColumns(w) == 0, "compact border horizontal");
    check(FoldableLayout.editorStripColumns(w) == 5, "compact strip 5 cols");
    check(FoldableLayout.cropDeckMaxDp(w) == -1, "compact crop full");
    check(!FoldableLayout.lightTableDefaultLandscape(w), "compact lighttable portrait");
    check(!FoldableLayout.lightTableAddPopup(w), "compact keeps bottom sheet");
    check(FoldableLayout.lightTableContextBarMaxDp(w) == -1, "compact ctx bar default");
    check(FoldableLayout.readingPageMaxDp(w) == -1, "compact reading full");
    check(FoldableLayout.galleryColumns(w) == 3, "compact gallery 3 cols");
    check(FoldableLayout.actionSheetMaxDp(w) == -1, "compact action sheet full");
    check(FoldableLayout.lightTableExportSheetMaxDp(w) == -1, "compact lt export full");
    check(FoldableLayout.noticeMaxDp(w) == 320, "compact notice 320dp");
  }

  private static void testMediumNarrowFoldable() {
    int w = 690;
    check(FoldableLayout.homeColumns(w) == 3, "medium home 3 cols");
    check(FoldableLayout.homePaddingDp(w) == 20, "medium home 20dp pad");
    check(FoldableLayout.homeConstrainedMaxDp(w) == 500, "medium home bar 500dp");
    check(FoldableLayout.useSplitEditor(w, 760), "medium split editor regardless of height");
    check(FoldableLayout.editorPanelWidthDp(w) == 296, "medium editor right 296dp");
    check(FoldableLayout.editorFilmGridColumns(w) == 3, "medium film 3 cols");
    check(FoldableLayout.editorBorderGridColumns(w) == 3, "medium border 3x3");
    check(FoldableLayout.editorStripColumns(w) == 5, "medium strip 5 cols");
    check(FoldableLayout.cropDeckMaxDp(w) == 480, "medium crop 480dp");
    check(FoldableLayout.lightTableDefaultLandscape(w), "medium lighttable landscape");
    check(FoldableLayout.lightTableAddPopup(w), "medium floating add popup");
    check(FoldableLayout.lightTableAddPopupWidthDp(w) == 232, "medium popup 232dp");
    check(FoldableLayout.lightTableContextBarMaxDp(w) == 440, "medium ctx bar 440dp");
    check(FoldableLayout.readingPageMaxDp(w) == 560, "medium reading 560dp");
    check(FoldableLayout.galleryColumns(w) == 4, "medium gallery 4 cols");
    check(FoldableLayout.gallerySheetMaxDp(w) == 580, "medium gallery sheet 580dp");
    check(FoldableLayout.lightTableExportSheetMaxDp(w) == 580, "medium lt export 580dp");
    check(FoldableLayout.exportSheetMaxDp(w) == 520, "medium export sheet 520dp");
    check(FoldableLayout.sponsorSheetMaxDp(w) == 520, "medium sponsor 520dp");
    check(FoldableLayout.actionSheetMaxDp(w) == 480, "medium action sheet 480dp");
    check(FoldableLayout.exitConfirmMaxDp(w) == 460, "medium exit confirm 460dp");
    check(FoldableLayout.updateDialogMaxDp(w) == 460, "medium update dialog 460dp");
    check(FoldableLayout.alertDialogMaxDp(w, false) == 420, "medium alert 420dp");
    check(FoldableLayout.alertDialogMaxDp(w, true) == 400, "medium single choice 400dp");
    check(FoldableLayout.noticeMaxDp(w) == 360, "medium notice 360dp");
  }

  private static void testExpandedLargeFoldable() {
    int w = 820;
    check(FoldableLayout.homeColumns(w) == 4, "expanded home 4 cols");
    check(FoldableLayout.homePaddingDp(w) == 24, "expanded home 24dp pad");
    check(FoldableLayout.homeConstrainedMaxDp(w) == 500, "expanded home bar 500dp");
    check(FoldableLayout.useSplitEditor(w, 740), "expanded split editor");
    check(FoldableLayout.editorPanelWidthDp(w) == 336, "expanded editor right 336dp");
    check(FoldableLayout.editorFilmGridColumns(w) == 3, "expanded film 3 cols");
    check(FoldableLayout.editorBorderGridColumns(w) == 3, "expanded border 3x3");
    check(FoldableLayout.editorStripColumns(w) == 6, "expanded strip 6 cols");
    check(FoldableLayout.cropDeckMaxDp(w) == 520, "expanded crop 520dp");
    check(FoldableLayout.lightTableDefaultLandscape(w), "expanded lighttable landscape");
    check(FoldableLayout.lightTableAddPopup(w), "expanded floating add popup");
    check(FoldableLayout.lightTableAddPopupWidthDp(w) == 232, "expanded popup 232dp");
    check(FoldableLayout.lightTableContextBarMaxDp(w) == 480, "expanded ctx bar 480dp");
    check(FoldableLayout.readingPageMaxDp(w) == 600, "expanded reading 600dp");
    check(FoldableLayout.galleryColumns(w) == 5, "expanded gallery 5 cols");
    check(FoldableLayout.gallerySheetMaxDp(w) == 640, "expanded gallery sheet 640dp");
    check(FoldableLayout.lightTableExportSheetMaxDp(w) == 640, "expanded lt export 640dp");
    check(FoldableLayout.exportSheetMaxDp(w) == 560, "expanded export sheet 560dp");
    check(FoldableLayout.sponsorSheetMaxDp(w) == 540, "expanded sponsor 540dp");
    check(FoldableLayout.actionSheetMaxDp(w) == 520, "expanded action sheet 520dp");
    check(FoldableLayout.exitConfirmMaxDp(w) == 480, "expanded exit confirm 480dp");
    check(FoldableLayout.updateDialogMaxDp(w) == 480, "expanded update dialog 480dp");
    check(FoldableLayout.alertDialogMaxDp(w, false) == 440, "expanded alert 440dp");
    check(FoldableLayout.alertDialogMaxDp(w, true) == 420, "expanded single choice 420dp");
    check(FoldableLayout.noticeMaxDp(w) == 360, "expanded notice 360dp");
  }
}
