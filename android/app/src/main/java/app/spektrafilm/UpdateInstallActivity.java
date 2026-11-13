// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

/** Explicit, private PendingIntent target for PackageInstaller's mutable status callback. */
public final class UpdateInstallActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    Haptics.install(getWindow());
    Intent result = getIntent();
    if (!result.hasExtra(PackageInstaller.EXTRA_STATUS)) {
      // OEM launchers may recreate this Activity after replacing the package without restoring
      // the transient PackageInstaller extras. That is not an installation failure.
      Log.i("SpektraUpdate", "Ignoring install callback without a status");
      finish();
      return;
    }
    int status =
        result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      if (isAlreadyUpdated(result, getPackageManager())) {
        Log.i("SpektraUpdate", "Ignoring duplicate install confirmation after update");
        finish();
        return;
      }
      Intent confirmation = result.getParcelableExtra(Intent.EXTRA_INTENT);
      try {
        if (confirmation == null) throw new IllegalStateException("missing confirmation");
        startActivity(confirmation); finish(); return;
      } catch (Exception e) {
        Log.w("SpektraUpdate", "Cannot open installer", e);
        showFailure(result, status);
        return;
      }
    } else if (status == PackageInstaller.STATUS_SUCCESS) {
      finish();
      return;
    }
    if (!shouldShowFailureDialog(result, getPackageManager())) {
      // Some OEM installers deliver FAILURE_ABORTED or another terminal status after the package
      // replacement has already completed. The installed version is authoritative.
      Log.i(
          "SpektraUpdate",
          "Install already completed despite status "
              + status
              + ": "
              + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
      finish();
      return;
    }
    showFailure(result, status);
  }

  private void showFailure(Intent result, int status) {
    Log.w(
        "SpektraUpdate",
        "Install status "
            + status
            + ": "
            + result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    new Haptics.DialogBuilder(this).setTitle("安装未完成")
        .setMessage("安装已取消或未成功。覆盖安装需要相同签名，且设备需有足够空间。")
        .setPositiveButton("完成", (d, n) -> finish())
        .setNeutralButton("打开发布页", (d, n) -> { UpdateController.openReleases(this); finish(); })
        .setOnCancelListener(d -> finish()).show();
  }

  static boolean shouldShowFailureDialog(Intent result, PackageManager packages) {
    if (!result.hasExtra(PackageInstaller.EXTRA_STATUS)) return false;
    int status =
        result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION
        || status == PackageInstaller.STATUS_SUCCESS) return false;
    return !isAlreadyUpdated(result, packages);
  }

  static boolean isAlreadyUpdated(Intent result, PackageManager packages) {
    long starting =
        result.getLongExtra(UpdateController.EXTRA_STARTING_VERSION_CODE, -1);
    long target = result.getLongExtra(UpdateController.EXTRA_TARGET_VERSION_CODE, -1);
    if (starting < 0 || target <= starting) return false;
    try {
      String packageName = result.getPackage();
      if (packageName == null && result.getComponent() != null)
        packageName = result.getComponent().getPackageName();
      if (packageName == null) return false;
      long installed =
          (Build.VERSION.SDK_INT >= 33
                  ? packages.getPackageInfo(
                      packageName, PackageManager.PackageInfoFlags.of(0))
                  : packages.getPackageInfo(packageName, 0))
              .getLongVersionCode();
      return installed >= target;
    } catch (PackageManager.NameNotFoundException error) {
      return false;
    }
  }
}
