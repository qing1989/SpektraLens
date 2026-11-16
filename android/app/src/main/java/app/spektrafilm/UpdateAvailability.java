// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

/** Last confirmed, installable update. Failed checks do not erase an existing notice. */
final class UpdateAvailability {
  private final long installedCode;
  private final int sdk;
  private UpdateManifest available;
  private String cachedJson;

  UpdateAvailability(long installedCode, int sdk, String cachedJson) {
    this.installedCode = installedCode;
    this.sdk = sdk;
    if (cachedJson != null) {
      try {
        accept(cachedJson);
      } catch (IllegalArgumentException ignored) {
        // A damaged cache is neither evidence of an update nor a reason to block startup.
      }
    }
  }

  UpdateManifest accept(String json) {
    // Parse before mutating: malformed responses must preserve the last confirmed result.
    UpdateManifest manifest = UpdateManifest.parse(json);
    accept(manifest, json);
    return manifest;
  }

  void accept(UpdateManifest manifest, String json) {
    boolean installable = manifest.isNewerThan(installedCode) && sdk >= manifest.minSdk;
    available = installable ? manifest : null;
    cachedJson = installable ? json : null;
  }

  UpdateManifest available() {
    return available;
  }

  String cachedJson() {
    return cachedJson;
  }
}
