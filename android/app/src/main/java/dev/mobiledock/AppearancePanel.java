package dev.mobiledock;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Settings on the phone, as an iOS grouped list. Saved on the phone and applied at once. */
final class AppearancePanel extends LinearLayout {
    static final String PREF_VOLUME_KEYS = "volume_keys";
    static final String PREF_DIM = "dim_minutes";
    private static final int[] DIM_MINUTES = {0, 1, 5, 15};
    private static final String[] DIM_NAMES = {"Off", "1 min", "5 min", "15 min"};
    private final SharedPreferences prefs;
    private final Runnable apply;

    AppearancePanel(Context c, SharedPreferences prefs, Runnable apply, final Runnable preview) {
        super(c);
        this.prefs = prefs;
        this.apply = apply;
        setOrientation(VERTICAL);

        addView(Ui.header(c, "Appearance"));
        addView(Kit.segmented(c, Ui.THEME_NAMES, prefs.getInt(Ui.PREF_THEME, 0), new Kit.OnPick() {
            @Override
            public void picked(int index) {
                save(Ui.PREF_THEME, index);
            }
        }));

        addView(Ui.header(c, "Accent color"));
        LinearLayout card = Ui.card(c);
        LinearLayout swatches = Ui.row(c);
        swatches.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        int chosen = Math.max(0, Math.min(Ui.ACCENTS.length - 1, prefs.getInt("accent", 0)));
        for (int i = 0; i < Ui.ACCENTS.length; i++) {
            final int index = i;
            LinearLayout s = Ui.column(c);
            s.setGravity(Gravity.CENTER);
            s.setClickable(true);
            s.setContentDescription(Ui.ACCENT_NAMES[i]);
            View dot = new View(c);
            int color = Ui.ACCENTS[i][Ui.DARK ? 1 : 0];
            android.graphics.drawable.GradientDrawable d = Ui.rounded(c, color, 15);
            if (i == chosen) d.setStroke(Ui.dp(c, 3), Ui.SURFACE);
            View ring = new View(c);
            ring.setBackground(Ui.rounded(c, i == chosen ? color : 0x00000000, 18));
            android.widget.FrameLayout holder = new android.widget.FrameLayout(c);
            holder.addView(ring, new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            android.widget.FrameLayout.LayoutParams dlp = new android.widget.FrameLayout.LayoutParams(
                    Ui.dp(c, 30), Ui.dp(c, 30), Gravity.CENTER);
            dot.setBackground(d);
            holder.addView(dot, dlp);
            s.addView(holder, new LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            TextView name = Ui.text(c, Ui.ACCENT_NAMES[i], 12, i == chosen ? Ui.TEXT : Ui.MUTED);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 4);
            s.addView(name, lp);
            s.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    save("accent", index);
                }
            });
            swatches.addView(s, new LayoutParams(0, Ui.dp(c, 64), 1f));
        }
        card.addView(swatches);
        addView(card);

        addView(Ui.header(c, "App icon size"));
        int scale = 1;
        for (int i = 0; i < Ui.TILE_SCALES.length; i++) if (Ui.TILE_SCALE == Ui.TILE_SCALES[i]) scale = i;
        addView(Kit.segmented(c, Ui.TILE_SCALE_NAMES, scale, new Kit.OnPick() {
            @Override
            public void picked(int index) {
                save("tile_scale", index);
            }
        }));

        addIdleScreen(c, preview);

        addView(Ui.header(c, "Show the idle screen after"));
        int dim = prefs.getInt(PREF_DIM, 5), dimIndex = 0;
        for (int i = 0; i < DIM_MINUTES.length; i++) if (DIM_MINUTES[i] == dim) dimIndex = i;
        addView(Kit.segmented(c, DIM_NAMES, dimIndex, new Kit.OnPick() {
            @Override
            public void picked(int index) {
                save(PREF_DIM, DIM_MINUTES[index]);
            }
        }));

        addView(Ui.header(c, "Vibrate when you press a button"));
        int buzz = Haptics.length(prefs), buzzIndex = 0;
        for (int i = 0; i < Haptics.LEVELS_MS.length; i++) if (Haptics.LEVELS_MS[i] == buzz) buzzIndex = i;
        addView(Kit.segmented(c, Haptics.LEVEL_NAMES, buzzIndex, new Kit.OnPick() {
            @Override
            public void picked(int index) {
                Haptics.tick(getContext(), Haptics.LEVELS_MS[index]);  // feel it right away
                save(Haptics.PREF_MS, Haptics.LEVELS_MS[index]);
            }
        }));

        addView(Ui.header(c, "Phone"));
        LinearLayout phone = Ui.card(c);
        phone.addView(switchRow(c, Icon.Kind.APPS, 0xFFFF8D28, "Labels under app icons", "labels", Ui.LABELS));
        phone.addView(Ui.hairline(c, 54));
        phone.addView(switchRow(c, Icon.Kind.SPEAKER, 0xFFFF2D55, "Volume buttons control the PC", PREF_VOLUME_KEYS,
                prefs.getBoolean(PREF_VOLUME_KEYS, true)));
        addView(phone);
    }

    /** The idle screen: its color, the big tile, the side tile, and a Preview button. */
    private void addIdleScreen(Context c, final Runnable preview) {
        LinearLayout head = Ui.row(c);
        head.addView(Ui.header(c, "Idle screen"));
        head.addView(Ui.spacer(c));
        TextView show = Ui.strong(c, "Preview", 15, Ui.ACCENT);
        show.setPadding(Ui.dp(c, 12), Ui.dp(c, 10), Ui.dp(c, 12), Ui.dp(c, 4));
        show.setClickable(true);
        show.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                preview.run();
            }
        });
        head.addView(show);
        addView(head, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        LinearLayout card = Ui.card(c);
        LinearLayout swatches = Ui.row(c);
        swatches.setPadding(Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8), Ui.dp(c, 8));
        int chosen = Math.max(0, Math.min(NightView.COLORS.length - 1, prefs.getInt(NightView.PREF_COLOR, 0)));
        for (int i = 0; i < NightView.COLORS.length; i++) {
            final int index = i;
            LinearLayout s = Ui.column(c);
            s.setGravity(Gravity.CENTER);
            s.setClickable(true);
            s.setContentDescription(NightView.COLOR_NAMES[i] + " idle screen");
            // Shown on black, like the idle screen itself.
            View dot = new View(c);
            android.graphics.drawable.GradientDrawable d = Ui.rounded(c, NightView.COLORS[i], 15);
            d.setStroke(Ui.dp(c, i == chosen ? 3 : 0), 0xFF000000);
            dot.setBackground(d);
            android.widget.FrameLayout holder = new android.widget.FrameLayout(c);
            View ring = new View(c);
            ring.setBackground(Ui.rounded(c, i == chosen ? NightView.COLORS[i] : 0xFF000000, 18));
            holder.addView(ring, new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            holder.addView(dot, new android.widget.FrameLayout.LayoutParams(Ui.dp(c, 30), Ui.dp(c, 30), Gravity.CENTER));
            s.addView(holder, new LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            TextView name = Ui.text(c, NightView.COLOR_NAMES[i], 12, i == chosen ? Ui.TEXT : Ui.MUTED);
            LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 4);
            s.addView(name, lp);
            s.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    save(NightView.PREF_COLOR, index);
                }
            });
            swatches.addView(s, new LayoutParams(0, Ui.dp(c, 64), 1f));
        }
        card.addView(swatches);
        addView(card);

        addView(Ui.header(c, "Big tile"));
        addView(Kit.segmented(c, NightView.MAIN_NAMES, prefs.getInt(NightView.PREF_MAIN, 0), new Kit.OnPick() {
            @Override
            public void picked(int index) {
                save(NightView.PREF_MAIN, index);
            }
        }));
        addView(Ui.header(c, "Side tile"));
        addView(Kit.segmented(c, NightView.SIDE_NAMES, prefs.getInt(NightView.PREF_SIDE, 0), new Kit.OnPick() {
            @Override
            public void picked(int index) {
                save(NightView.PREF_SIDE, index);
            }
        }));
    }

    private LinearLayout switchRow(Context c, Icon.Kind kind, int color, String label, final String key, boolean on) {
        Kit.Switch sw = new Kit.Switch(c, on);
        sw.setContentDescription(label);
        sw.setOnFlip(new Kit.OnFlip() {
            @Override
            public void flipped(boolean value) {
                prefs.edit().putBoolean(key, value).commit();
                Ui.loadTheme(getContext(), prefs);
                // Let the knob finish sliding before the page is rebuilt.
                postDelayed(apply, 220);
            }
        });
        return Ui.listRow(c, kind, color, label, sw);
    }

    private void save(String key, int value) {
        prefs.edit().putInt(key, value).commit();
        Ui.loadTheme(getContext(), prefs);
        apply.run();
    }
}
