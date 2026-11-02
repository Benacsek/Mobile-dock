package dev.mobiledock;

import android.content.Context;
import android.os.Build;
import android.os.Vibrator;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;

/** A short buzz when a button or tile is pressed, so the dock can be used without looking at it. */
final class Haptics {
    static final String PREF = "haptic";
    private static final int MS = 14;

    private Haptics() {
    }

    /** True when the finger at (x, y) in window coordinates is on something you can press. */
    static boolean onPressable(View root, float x, float y) {
        View v = deepest(root, x, y);
        for (; v != null; v = v.getParent() instanceof View ? (View) v.getParent() : null) {
            if (v instanceof SeekBar) return false;  // sliders would buzz on every drag
            if (v.isClickable() && v.isEnabled()) return true;
        }
        return false;
    }

    private static View deepest(View v, float x, float y) {
        if (v.getVisibility() != View.VISIBLE) return null;
        int[] at = new int[2];
        v.getLocationInWindow(at);
        if (x < at[0] || y < at[1] || x >= at[0] + v.getWidth() || y >= at[1] + v.getHeight()) return null;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = g.getChildCount() - 1; i >= 0; i--) {  // the last child is drawn on top
                View hit = deepest(g.getChildAt(i), x, y);
                if (hit != null) return hit;
            }
        }
        return v;
    }

    @SuppressWarnings("deprecation")
    static void tick(Context c) {
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        if (Build.VERSION.SDK_INT >= 26) HapticCompat.tick(v, MS);
        else v.vibrate(MS);
    }
}
