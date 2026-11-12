// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 SpektraLens contributors. See NOTICE for upstream attribution.
package app.spektrafilm;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Bundle;
import android.util.Log;

/** Explicit, private PendingIntent target for PackageInstaller's mutable status callback. */
public final class UpdateInstallActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    Haptics.install(getWindow());
    int status = getIntent().getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      Intent confirmation = getIntent().getParcelableExtra(Intent.EXTRA_INTENT);
      try {
        if (confirmation == null) throw new IllegalStateException("missing confirmation");
        startActivity(confirmation); finish(); return;
      } catch (Exception e) { Log.w("SpektraUpdate", "Cannot open installer", e); }
    } else if (status == PackageInstaller.STATUS_SUCCESS) { finish(); return; }
    Log.w("SpektraUpdate", "Install status " + status + ": " + getIntent().getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    new Haptics.DialogBuilder(this).setTitle("安装未完成")
        .setMessage("安装已取消或未成功。覆盖安装需要相同签名，且设备需有足够空间。")
        .setPositiveButton("完成", (d, n) -> finish())
        .setNeutralButton("打开发布页", (d, n) -> { UpdateController.openReleases(this); finish(); })
        .setOnCancelListener(d -> finish()).show();
  }
}
