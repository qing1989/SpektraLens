// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

final class UpdateAvailabilityTest {
  private static int checks;
  private static final String HASH = "0".repeat(64);

  private static String manifest(int version, int minSdk) {
    return "{\"versionCode\":" + version + ",\"versionName\":\"test\",\"minSdk\":" + minSdk
        + ",\"apkUrl\":\"https://example.org/update.apk\",\"sha256\":\"" + HASH + "\"}";
  }

  private static void check(boolean result) {
    checks++;
    if (!result) throw new AssertionError("availability check " + checks);
  }

  static void run() {
    UpdateAvailability state = new UpdateAvailability(22, 35, null);
    check(state.available() == null && state.cachedJson() == null);
    String newer = manifest(23, 29);
    state.accept(newer);
    check(state.available().versionCode == 23);
    check(newer.equals(state.cachedJson()));

    UpdateAvailability reopened = new UpdateAvailability(22, 35, state.cachedJson());
    check(reopened.available().versionCode == 23);
    try {
      state.accept("{\"versionCode\":24}");
      throw new AssertionError("malformed response accepted");
    } catch (IllegalArgumentException expected) {
      check(state.available().versionCode == 23 && newer.equals(state.cachedJson()));
    }
    // Network failures do not call accept; the cached notice survives another app opening.
    check(new UpdateAvailability(22, 35, state.cachedJson()).available().versionCode == 23);
    state.accept(manifest(23, 36));
    check(state.available() == null && state.cachedJson() == null);
    state.accept(manifest(22, 29));
    check(state.available() == null);
    state.accept(manifest(21, 29));
    check(state.available() == null);
    check(new UpdateAvailability(23, 35, newer).cachedJson() == null);
    check(new UpdateAvailability(24, 35, newer).available() == null);
    check(new UpdateAvailability(22, 28, newer).available() == null);
    check(new UpdateAvailability(22, 35, "damaged").cachedJson() == null);
    check(new UpdateAvailability(22, 35, " ".repeat(65537)).available() == null);
    state.accept(newer);
    state.accept(manifest(22, 29)); // A withdrawn server release must not remain installable.
    check(state.available() == null && state.cachedJson() == null);
    System.out.println("UpdateAvailabilityTest: " + checks + " checks passed");
  }
}
