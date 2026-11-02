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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Colors and small view factories. The whole UI is built in code, so it works on every Android version. */
final class Ui {
    // Same three colors as the PC window: graphite surfaces, a blue accent, amber for live state.
    static final int BG = 0xFF16181C;
    static final int SURFACE = 0xFF1E2126;
    static final int SURFACE_HI = 0xFF272B31;
    static final int LINE = 0xFF33383F;
    /** Amber: things happening right now, such as recording or a running countdown. */
    static final int SIGNAL = 0xFFF2B33D;
    /** Set from the Appearance page. Everything reads it when it is built. */
    static int ACCENT = 0xFF4C8DF6;
    static float TILE_SCALE = 1f;
    static boolean LABELS = true;
    static boolean GLOW = true;

    static final int[] ACCENTS = {0xFF4C8DF6, 0xFF34D399, 0xFFA78BFA, 0xFFF2B33D};
    static final String[] ACCENT_NAMES = {"Blue", "Green", "Purple", "Amber"};
    static final float[] TILE_SCALES = {0.85f, 1f, 1.2f};
    static final String[] TILE_SCALE_NAMES = {"Compact", "Comfortable", "Large"};

    static void loadTheme(android.content.SharedPreferences p) {
        ACCENT = ACCENTS[Math.max(0, Math.min(ACCENTS.length - 1, p.getInt("accent", 0)))];
        TILE_SCALE = TILE_SCALES[Math.max(0, Math.min(TILE_SCALES.length - 1, p.getInt("tile_scale", 1)))];
        LABELS = p.getBoolean("labels", true);
        GLOW = p.getBoolean("glow", false);
    }

    /** The accent, a little darker, for the pressed state. */
    static int accentPressed() {
        return android.graphics.Color.rgb(android.graphics.Color.red(ACCENT) * 4 / 5,
                android.graphics.Color.green(ACCENT) * 4 / 5, android.graphics.Color.blue(ACCENT) * 4 / 5);
    }

    /** Text color that stays readable on the accent. */
    static int onAccent() {
        int r = android.graphics.Color.red(ACCENT), g = android.graphics.Color.green(ACCENT),
                b = android.graphics.Color.blue(ACCENT);
        return (r * 299 + g * 587 + b * 114) / 1000 > 150 ? 0xFF0B1220 : 0xFFFFFFFF;
    }

    /** Flat graphite like the PC window. The optional glow adds the accent and amber at the bottom. */
    static Drawable background(Context c) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(BG);
        if (!GLOW) return base;
        int second = ACCENT == SIGNAL ? ACCENTS[0] : SIGNAL;
        return new android.graphics.drawable.LayerDrawable(new Drawable[] {
            base, glow(c, ACCENT, 0.12f, 1.0f), glow(c, second, 0.88f, 1.0f),
        });
    }

    private static Drawable glow(Context c, int color, float cx, float cy) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {(color & 0x00FFFFFF) | 0x55000000, color & 0x00FFFFFF});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(dp(c, 260));
        return g;
    }

    /** Rounded-square dock tile: line icon over a short label. */
    static LinearLayout dockTile(Context c, Icon.Kind kind, String label) {
        int size = dp(c, (LABELS ? 76 : 60) * TILE_SCALE);
        LinearLayout tile = column(c);
        tile.setGravity(Gravity.CENTER);
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_selected}, rounded(c, (ACCENT & 0x00FFFFFF) | 0x2E000000, 14));
        bg.addState(new int[] {android.R.attr.state_pressed}, rounded(c, SURFACE_HI, 14));
        bg.addState(new int[] {}, rounded(c, 0x00000000, 14));
        tile.setBackground(bg);
        tile.setClickable(true);
        tile.setMinimumWidth(size);
        int p = dp(c, 6);
        tile.setPadding(p, p, p, p);
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(new Icon(kind, MUTED));
        int iconSize = dp(c, 26 * TILE_SCALE);
        tile.addView(icon, new LinearLayout.LayoutParams(iconSize, iconSize));
        TextView t = text(c, label, 11, MUTED);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setVisibility(LABELS ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 6);
        tile.addView(t, lp);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(size, size);
        int m = dp(c, 4);
        tlp.setMargins(m, 0, m, 0);
        tile.setLayoutParams(tlp);
        return tile;
    }
    static final int TEXT = 0xFFE8EAED;
    static final int MUTED = 0xFF8B919A;
    static final int FAINT = 0xFF5C626B;
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
        b.setBackground(pressable(c, SURFACE_HI, 0x38FFFFFF, 14));
        b.setMinHeight(dp(c, 48));
        b.setMinimumHeight(dp(c, 48));
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        return b;
    }

    static Button accentButton(Context c, String label) {
        Button b = button(c, label);
        b.setBackground(pressable(c, ACCENT, accentPressed(), 14));
        b.setTextColor(onAccent());
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

    /** Round or rounded icon button. Use a radius of half the size for a circle. */
    static ImageView iconButton(Context c, Icon.Kind kind, int sizeDp, int bg, int pressed, int iconColor) {
        ImageView b = new ImageView(c);
        b.setImageDrawable(new Icon(kind, iconColor));
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = dp(c, sizeDp * 0.22f);
        b.setPadding(pad, pad, pad, pad);
        b.setBackground(pressable(c, bg, pressed, sizeDp / 2f));
        b.setClickable(true);
        b.setFocusable(true);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return b;
    }

    /** Plain icon button with no fill until pressed. */
    static ImageView flatIcon(Context c, Icon.Kind kind, int sizeDp) {
        return iconButton(c, kind, sizeDp, 0x00000000, 0x33FFFFFF, TEXT);
    }

    /** Like the PC tabs: the selected tile gets a blue tint and a blue icon, the rest stay muted. */
    static void setTileSelected(LinearLayout tile, boolean selected) {
        tile.setSelected(selected);
        ((Icon) ((ImageView) tile.getChildAt(0)).getDrawable()).setColor(selected ? ACCENT : MUTED);
        ((TextView) tile.getChildAt(1)).setTextColor(selected ? TEXT : MUTED);
    }

    /** Four small squares, three blue and one amber. The same mark as the PC window. */
    static View logo(Context c, int sizeDp) {
        final int size = dp(c, sizeDp);
        View v = new View(c) {
            private final android.graphics.Paint paint = new android.graphics.Paint();
            private final float cell = size * 11f / 26f, gap = size * 2f / 26f;

            @Override
            protected void onDraw(android.graphics.Canvas canvas) {
                for (int i = 0; i < 4; i++) {
                    float x = gap + (i % 2) * (gap + cell), y = gap + (i / 2) * (gap + cell);
                    paint.setColor(i == 3 ? SIGNAL : ACCENT);
                    canvas.drawRect(x, y, x + cell, y + cell, paint);
                }
            }
        };
        v.setLayoutParams(new LinearLayout.LayoutParams(size, size));
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
