package dev.mobiledock;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Accent color, tile size, background glow and labels. Saved on the phone and applied at once. */
final class AppearancePanel extends ScrollView {
    static final String PREF_VOLUME_KEYS = "volume_keys";
    static final String PREF_DIM = "dim_minutes";
    private static final int[] DIM_MINUTES = {0, 1, 5, 15};
    private static final String[] DIM_NAMES = {"Off", "1 min", "5 min", "15 min"};
    private final SharedPreferences prefs;
    private final Runnable apply;

    AppearancePanel(Context c, SharedPreferences prefs, Runnable apply) {
        super(c);
        this.prefs = prefs;
        this.apply = apply;
        LinearLayout box = Ui.column(c);
        addView(box);
        box.addView(Ui.title(c, "Appearance"), Ui.fillWidth(c, 4));

        box.addView(section(c, "Accent"));
        LinearLayout swatches = Ui.row(c);
        for (int i = 0; i < Ui.ACCENTS.length; i++) {
            final int index = i;
            boolean on = Ui.ACCENT == Ui.ACCENTS[i];
            LinearLayout s = Ui.column(c);
            s.setGravity(Gravity.CENTER);
            s.setBackground(Ui.pressable(c, on ? Ui.SURFACE_HI : Ui.SURFACE, Ui.SURFACE_HI, 18));
            s.setClickable(true);
            View dot = new View(c);
            dot.setBackground(Ui.rounded(c, Ui.ACCENTS[i], 14));
            int d = Ui.dp(c, 28);
            s.addView(dot, new LinearLayout.LayoutParams(d, d));
            TextView name = Ui.text(c, Ui.ACCENT_NAMES[i], 12, on ? Ui.TEXT : Ui.MUTED);
            LinearLayout.LayoutParams lp = Ui.wrap(c, 0);
            lp.topMargin = Ui.dp(c, 6);
            s.addView(name, lp);
            s.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    save("accent", index);
                }
            });
            swatches.addView(s, cell(c));
        }
        box.addView(swatches, Ui.fillWidth(c, 0));

        box.addView(section(c, "Tile size"));
        LinearLayout sizes = Ui.row(c);
        for (int i = 0; i < Ui.TILE_SCALES.length; i++) {
            final int index = i;
            Button b = Ui.button(c, Ui.TILE_SCALE_NAMES[i]);
            if (Ui.TILE_SCALE == Ui.TILE_SCALES[i]) {
                b.setBackground(Ui.pressable(c, Ui.ACCENT, Ui.accentPressed(), 14));
                b.setTextColor(Ui.onAccent());
            }
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    save("tile_scale", index);
                }
            });
            sizes.addView(b, new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f));
            if (i < Ui.TILE_SCALES.length - 1) sizes.addView(new View(c), new LinearLayout.LayoutParams(Ui.dp(c, 8), 1));
        }
        box.addView(sizes, Ui.fillWidth(c, 4));

        box.addView(section(c, "Dock"));
        box.addView(toggle(c, "Background glow", "glow", Ui.GLOW), Ui.fillWidth(c, 4));
        box.addView(toggle(c, "Labels under dock icons", "labels", Ui.LABELS), Ui.fillWidth(c, 4));

        box.addView(section(c, "Dim to a clock when idle"));
        LinearLayout dims = Ui.row(c);
        int dim = prefs.getInt(PREF_DIM, 5);
        for (int i = 0; i < DIM_MINUTES.length; i++) {
            final int minutes = DIM_MINUTES[i];
            Button b = Ui.button(c, DIM_NAMES[i]);
            if (dim == minutes) {
                b.setBackground(Ui.pressable(c, Ui.ACCENT, Ui.accentPressed(), 14));
                b.setTextColor(Ui.onAccent());
            }
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    save(PREF_DIM, minutes);
                }
            });
            dims.addView(b, new LinearLayout.LayoutParams(0, Ui.dp(c, 48), 1f));
            if (i < DIM_MINUTES.length - 1) dims.addView(new View(c), new LinearLayout.LayoutParams(Ui.dp(c, 8), 1));
        }
        box.addView(dims, Ui.fillWidth(c, 4));

        box.addView(section(c, "Buttons"));
        box.addView(toggle(c, "Vibrate when you press a button", Haptics.PREF,
                prefs.getBoolean(Haptics.PREF, true)), Ui.fillWidth(c, 4));
        box.addView(toggle(c, "Phone volume buttons change the PC volume", PREF_VOLUME_KEYS,
                prefs.getBoolean(PREF_VOLUME_KEYS, true)), Ui.fillWidth(c, 4));
    }

    private static TextView section(Context c, String label) {
        TextView t = Ui.text(c, label, 13, Ui.ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(Ui.dp(c, 6), Ui.dp(c, 12), 0, Ui.dp(c, 4));
        return t;
    }

    private static LinearLayout.LayoutParams cell(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 76), 1f);
        int m = Ui.dp(c, 5);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private LinearLayout toggle(Context c, String label, final String key, final boolean on) {
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 14));
        row.setClickable(true);
        int p = Ui.dp(c, 14);
        row.setPadding(p, p, p, p);
        row.addView(Ui.text(c, label, 15, Ui.TEXT));
        row.addView(Ui.spacer(c));
        TextView state = Ui.text(c, on ? "On" : "Off", 14, on ? Ui.onAccent() : Ui.MUTED);
        state.setGravity(Gravity.CENTER);
        state.setBackground(Ui.rounded(c, on ? Ui.ACCENT : Ui.SURFACE_HI, 12));
        state.setPadding(Ui.dp(c, 14), Ui.dp(c, 4), Ui.dp(c, 14), Ui.dp(c, 4));
        row.addView(state);
        row.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.edit().putBoolean(key, !on).commit();
                Ui.loadTheme(prefs);
                apply.run();
            }
        });
        return row;
    }

    private void save(String key, int value) {
        prefs.edit().putInt(key, value).commit();
        Ui.loadTheme(prefs);
        apply.run();
    }
}
