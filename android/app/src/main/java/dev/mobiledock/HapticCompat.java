package dev.mobiledock;

import android.annotation.TargetApi;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** VibrationEffect exists from Android 8. Kept apart so older Android never loads it. */
@TargetApi(26)
final class HapticCompat {
    private HapticCompat() {
    }

    static void tick(Vibrator v, int ms) {
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
