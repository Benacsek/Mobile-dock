package dev.mobiledock;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colors and small view factories. The whole UI is built in code, so it works on every Android version. */
final class Ui {
    static final int BG = 0xFF0F1115;
    static final int SURFACE = 0xFF1A1D23;
    static final int SURFACE_HI = 0xFF262A33;
    static final int ACCENT = 0xFF4F8CFF;
    static final int TEXT = 0xFFE8EAED;
    static final int MUTED = 0xFF9AA0A6;
    static final int DANGER = 0xFFE5484D;
    static final int[] TILE_COLORS = {
        0xFF3B6FD8, 0xFF8E4FD8, 0xFFD8504F, 0xFFD88A2F, 0xFF2FA57A, 0xFF2F96B5, 0xFFB5408C, 0xFF6C7A2F,
    };

    private Ui() {
    }

    static int dp(Context c, float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    static GradientDrawable rounded(Context c, int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static Drawable pressable(Context c, int color, int pressed, float radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_pressed}, rounded(c, pressed, radiusDp));
        s.addState(new int[] {android.R.attr.state_selected}, rounded(c, ACCENT, radiusDp));
        s.addState(new int[] {}, rounded(c, color, radiusDp));
        return s;
    }

    static TextView text(Context c, String s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        return t;
    }

    static TextView title(Context c, String s) {
        TextView t = text(c, s, 20, TEXT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextColor(TEXT);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setBackground(pressable(c, SURFACE_HI, 0xFF343A46, 10));
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        return b;
    }

    static Button accentButton(Context c, String label) {
        Button b = button(c, label);
        b.setBackground(pressable(c, ACCENT, 0xFF3A6FD0, 10));
        return b;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weight(float w) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, w);
    }

    static LinearLayout.LayoutParams wrap(Context c, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = dp(c, marginDp);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    static LinearLayout.LayoutParams fillWidth(Context c, int marginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        int m = dp(c, marginDp);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    static View spacer(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        return v;
    }

    static String initials(String name) {
        String[] words = name.trim().split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.length() > 0 && Character.isLetterOrDigit(w.charAt(0))) sb.append(Character.toUpperCase(w.charAt(0)));
            if (sb.length() == 2) break;
        }
        return sb.length() == 0 ? "?" : sb.toString();
    }

    static int colorFor(String key) {
        return TILE_COLORS[(key.hashCode() & 0x7FFFFFFF) % TILE_COLORS.length];
    }
}
