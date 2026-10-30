package dev.mobiledock;

import android.annotation.TargetApi;
import android.app.Activity;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** Back gesture handling for Android 13 and newer. Kept apart so older Androids never load these classes. */
@TargetApi(33)
final class BackCompat {
    private BackCompat() {
    }

    static void register(Activity activity, final Runnable onBack) {
        activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, new OnBackInvokedCallback() {
                    @Override
                    public void onBackInvoked() {
                        onBack.run();
                    }
                });
    }
}
