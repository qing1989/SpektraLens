// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.test.InstrumentationTestCase;

/** Guards against false “installation incomplete” dialogs after a successful replacement. */
public final class UpdateInstallResultInstrumentedTest extends InstrumentationTestCase {
  private long installedVersion() throws Exception {
    Context context = getInstrumentation().getTargetContext();
    PackageManager packages = context.getPackageManager();
    return (Build.VERSION.SDK_INT >= 33
            ? packages.getPackageInfo(
                context.getPackageName(), PackageManager.PackageInfoFlags.of(0))
            : packages.getPackageInfo(context.getPackageName(), 0))
        .getLongVersionCode();
  }

  private Intent callback(long starting, long target) {
    Intent intent =
        new Intent(
            getInstrumentation().getTargetContext(), UpdateInstallActivity.class);
    intent.putExtra(UpdateController.EXTRA_STARTING_VERSION_CODE, starting);
    intent.putExtra(UpdateController.EXTRA_TARGET_VERSION_CODE, target);
    return intent;
  }

  private android.app.Activity launch(Intent result) {
    result.setClass(
        getInstrumentation().getTargetContext(), UpdateInstallActivity.class);
    result.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    android.app.Activity activity = getInstrumentation().startActivitySync(result);
    getInstrumentation().waitForIdleSync();
    return activity;
  }

  public void testMissingStatusDoesNotShowFailure() {
    Intent result = callback(installedVersionUnchecked() - 1, installedVersionUnchecked());
    assertFalse(
        UpdateInstallActivity.shouldShowFailureDialog(
            result, getInstrumentation().getTargetContext().getPackageManager()));
    android.app.Activity activity = launch(new Intent());
    assertTrue(activity.isFinishing());
  }

  public void testLateFailureAfterReplacementFinishesSilently() throws Exception {
    long installed = installedVersion();
    Intent result = callback(installed - 1, installed);
    result.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED);
    android.app.Activity activity = launch(result);
    assertTrue(activity.isFinishing());
  }

  public void testDuplicateConfirmationAfterReplacementFinishesSilently() throws Exception {
    long installed = installedVersion();
    Intent result = callback(installed - 1, installed);
    result.putExtra(
        PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION);
    android.app.Activity activity = launch(result);
    assertTrue(activity.isFinishing());
  }

  public void testPendingAndSuccessDoNotShowFailure() {
    Intent result = callback(installedVersionUnchecked(), installedVersionUnchecked() + 1);
    result.putExtra(
        PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION);
    assertFalse(
        UpdateInstallActivity.shouldShowFailureDialog(
            result, getInstrumentation().getTargetContext().getPackageManager()));
    result.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_SUCCESS);
    assertFalse(
        UpdateInstallActivity.shouldShowFailureDialog(
            result, getInstrumentation().getTargetContext().getPackageManager()));
  }

  public void testCompletedReplacementSuppressesLateFailureStatus() throws Exception {
    long installed = installedVersion();
    Intent result = callback(installed - 1, installed);
    result.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED);
    assertFalse(
        UpdateInstallActivity.shouldShowFailureDialog(
            result, getInstrumentation().getTargetContext().getPackageManager()));
  }

  public void testUnchangedPackageDoesNotMasqueradeAsSuccessfulUpdate() throws Exception {
    long installed = installedVersion();
    Intent result = callback(installed, installed + 1);
    result.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED);
    assertTrue(
        UpdateInstallActivity.shouldShowFailureDialog(
            result, getInstrumentation().getTargetContext().getPackageManager()));
  }

  public void testOrdinaryFailureStillReportsAsFailure() throws Exception {
    long installed = installedVersion();
    Intent result = callback(installed, installed + 1);
    result.putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    assertTrue(
        UpdateInstallActivity.shouldShowFailureDialog(
            result, getInstrumentation().getTargetContext().getPackageManager()));
  }

  private long installedVersionUnchecked() {
    try {
      return installedVersion();
    } catch (Exception error) {
      throw new AssertionError(error);
    }
  }
}
