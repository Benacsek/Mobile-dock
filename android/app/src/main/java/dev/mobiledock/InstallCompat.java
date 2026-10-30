package dev.mobiledock;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

/** Installer calls for Android 7 and newer. Kept apart so older Androids never load them. */
@TargetApi(24)
final class InstallCompat {
    private InstallCompat() {
    }

    static boolean needsPermission(Activity a) {
        return Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls();
    }

    @TargetApi(26)
    static void askPermission(Activity a) {
        a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + a.getPackageName())));
    }

    static Intent installIntent(Activity a) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ApkProvider.URI, "application/vnd.android.package-archive");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
