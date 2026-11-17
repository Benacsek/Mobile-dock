package dev.mobiledock;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Settings on the phone, like iOS Settings: a short list of groups, and each group opens its own page
 * with "Settings" at the top to go back. Saved on the phone and applied at once.
 */
final class SettingsPanel extends android.widget.ScrollView {
    static final String PREF_VOLUME_KEYS = "volume_keys";
    static final String PREF_DIM = "dim_minutes";
    /** The pages. The big tile picker opens from the Idle screen page and goes back there. */
    static final int LIST = 0, DISPLAY = 1, IDLE = 2, BUTTONS = 3, UPDATES = 4, PICK_BIG = 5;
    private static final String[] PAGE_NAMES = {"Settings", "Display", "Idle screen", "Buttons and feedback",
        "Updates", "Big tile"};
    private static final int[] DIM_MINUTES = {0, 1, 5, 15};
    private static final String[] DIM_NAMES = {"Off", "1 min", "5 min", "15 min"};
    private final SharedPreferences prefs;
    private final Runnable apply, preview;
    private final UpdatesPanel updates;
    private final Kit.OnPick onPage;
    private final LinearLayout list;
    private int page;
    private String pcName, pcStatus;  // shown at the top of the list when the dock uses the icon rail
    private boolean pcConnected;

    /** onPage hears every page change, so the open page survives the rebuild after a change. */
    SettingsPanel(Context c, SharedPreferences prefs, Runnable apply, Runnable preview, UpdatesPanel updates,
            int page, Kit.OnPick onPage) {
        super(c);
        this.prefs = prefs;
        this.apply = apply;
        this.preview = preview;
        this.updates = updates;
        this.onPage = onPage;
        setVerticalScrollBarEnabled(false);
        list = Ui.column(c);
        list.setPadding(0, 0, Ui.dp(c, 6), Ui.dp(c, 16));
        addView(list);
        this.page = page;
        build();
    }

    /** Opens a page of Settings. */
    void show(int p) {
        page = p;
        onPage.picked(p);
        build();
        scrollTo(0, 0);
    }

    /** With the icon rail, the PC and its connection move from the sidebar header to the top of the list. */
    void setPc(String name, String status, boolean connected) {
        pcName = name;
        pcStatus = status;
        pcConnected = connected;
        if (page == LIST) build();
    }

    /** Back from a page: a tile picker returns to Idle screen, the rest to the list. False on the list itself. */
    boolean back() {
        if (page == LIST) return false;
        show(page >= PICK_BIG ? IDLE : LIST);
        return true;
    }

    private void build() {
        Context c = getContext();
        list.removeAllViews();
        if (page != LIST) {
            list.addView(Kit.backLink(c, page >= PICK_BIG ? "Idle screen" : "Settings", new Runnable() {
                @Override
                public void run() {
                    back();
                }
            }));
        }
        list.addView(Ui.title(c, PAGE_NAMES[page]));
        if (page == LIST) buildList(c);
        else if (page == DISPLAY) buildDisplay(c);
        else if (page == IDLE) buildIdle(c);
        else if (page == BUTTONS) buildButtons(c);
        else if (page == UPDATES) {
            if (updates.getParent() != null) ((android.view.ViewGroup) updates.getParent()).removeView(updates);
            list.addView(updates);
        } else {
            buildPicker(c, NightView.PREF_MAIN, 0, NightView.MAIN_NAMES, new int[] {0, 1, 2});
        }
    }

    /** The front page: one row per group, with what is set now on the right. */
    private void buildList(Context c) {
        list.addView(new View(c), new LinearLayout.LayoutParams(1, Ui.dp(c, 12)));
        LinearLayout pc = Ui.card(c);
        if (pcName != null) {
            LinearLayout row = Ui.listRow(c, Icon.Kind.MONITOR, pcConnected ? Ui.GREEN : Ui.SIGNAL, pcName,
                    Ui.value(c, pcStatus));
            row.setContentDescription(pcName + ", " + pcStatus);
            pc.addView(row);
            pc.addView(Ui.hairline(c, 54));
        }
        pc.addView(startWithPcRow(c));
        list.addView(pc);
        list.addView(new View(c), new LinearLayout.LayoutParams(1, Ui.dp(c, 20)));
        LinearLayout card = Ui.card(c);
        card.addView(groupRow(c, Icon.Kind.SUN, 0xFF0088FF, DISPLAY,
                Ui.THEME_NAMES[Math.max(0, Math.min(2, prefs.getInt(Ui.PREF_THEME, 0)))]));
        card.addView(Ui.hairline(c, 54));
        card.addView(groupRow(c, Icon.Kind.MOON, 0xFF6155F5, IDLE, idleSummary()));
        card.addView(Ui.hairline(c, 54));
        card.addView(groupRow(c, Icon.Kind.SPEAKER, 0xFFFF2D55, BUTTONS,
                "Vibrate: " + Haptics.LEVEL_NAMES[levelIndex(Haptics.length(prefs))]));
        card.addView(Ui.hairline(c, 54));
        card.addView(groupRow(c, Icon.Kind.REFRESH, 0xFF8E8E93, UPDATES, UpdatesPanel.phoneVersion(c)));
        list.addView(card);
    }

    private LinearLayout groupRow(Context c, Icon.Kind kind, int color, final int target, String value) {
        return Kit.navRow(c, kind, color, PAGE_NAMES[target], value, new OnClickListener() {
            @Override
            public void onClick(View v) {
                show(target);
            }
        });
    }

    /** "Clock, Date and Battery". */
    private String idleSummary() {
        String s = NightView.MAIN_NAMES[pick(NightView.PREF_MAIN, 0, NightView.MAIN_NAMES.length)];
        int[] sides = NightView.sideTiles(prefs);
        for (int i = 0; i < sides.length; i++) {
            s += (i == sides.length - 1 ? " and " : ", ") + NightView.SIDE_NAMES[sides[i]];
        }
        return s;
    }

    private int pick(String key, int fallback, int count) {
        return Math.max(0, Math.min(count - 1, prefs.getInt(key, fallback)));
    }

    private static int levelIndex(int ms) {
        for (int i = 0; i < Haptics.LEVELS_MS.length; i++) if (Haptics.LEVELS_MS[i] == ms) return i;
        return 0;
    }

    private void buildDisplay(Context c) {
        list.addView(Ui.header(c, "Appearance"));
        list.addView(Kit.segmented(c, Ui.THEME_NAMES, prefs.getInt(Ui.PREF_THEME, 0), new Kit.OnPick() {
            @Override
            public void picked(int index) {
                save(Ui.PREF_THEME, index);
            }
        }));

        list.addView(Ui.header(c, "Accent color"));
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
            s.addView(holder, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            TextView name = Ui.text(c, Ui.ACCENT_NAMES[i], 12, i == chosen ? Ui.TEXT : Ui.MUTED);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 4);
            s.addView(name, lp);
            s.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    save("accent", index);
                }
            });
            swatches.addView(s, new LinearLayout.LayoutParams(0, Ui.dp(c, 64), 1f));
        }
        card.addView(swatches);
        list.addView(card);

        list.addView(Ui.header(c, "App icon size"));
        int scale = 1;
        for (int i = 0; i < Ui.TILE_SCALES.length; i++) if (Ui.TILE_SCALE == Ui.TILE_SCALES[i]) scale = i;
        list.addView(Kit.segmented(c, Ui.TILE_SCALE_NAMES, scale, new Kit.OnPick() {
            @Override
            public void picked(int index) {
                save("tile_scale", index);
            }
        }));

        list.addView(Ui.header(c, "Apps"));
        LinearLayout apps = Ui.card(c);
        apps.addView(switchRow(c, Icon.Kind.APPS, 0xFFFF8D28, "Labels under app icons", "labels", Ui.LABELS));
        list.addView(apps);
    }

    /** The idle screen: its color, the big tile, two side tiles, when it shows, and a Preview button. */
    private void buildIdle(Context c) {
        list.addView(Ui.header(c, "Color"));
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
            s.addView(holder, new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36)));
            TextView name = Ui.text(c, NightView.COLOR_NAMES[i], 12, i == chosen ? Ui.TEXT : Ui.MUTED);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(c, 4);
            s.addView(name, lp);
            s.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveIdle(NightView.PREF_COLOR, index);
                }
            });
            swatches.addView(s, new LinearLayout.LayoutParams(0, Ui.dp(c, 64), 1f));
        }
        card.addView(swatches);
        list.addView(card);

        list.addView(Ui.header(c, "Tiles"));
        LinearLayout tiles = Ui.card(c);
        tiles.addView(pickRow(c, PICK_BIG,
                NightView.MAIN_NAMES[pick(NightView.PREF_MAIN, 0, NightView.MAIN_NAMES.length)]));
        list.addView(tiles);

        // The side tiles as a checklist. Checked tiles come first, in the order they show, with arrows to
        // move them. When 4 are checked, the rest wait until one is unchecked.
        list.addView(Ui.header(c, "Side tiles"));
        final int mask = NightView.tileMask(prefs);
        final int[] order = NightView.tileOrder(prefs);
        final int[] shown = NightView.sideTiles(prefs);
        int[] rows = new int[order.length];
        int n = 0;
        for (int tile : shown) rows[n++] = tile;
        for (int tile : order) if (indexOf(shown, tile) < 0) rows[n++] = tile;
        LinearLayout sides = Ui.card(c);
        for (int i = 0; i < rows.length; i++) {
            final int tile = rows[i];
            int at = indexOf(shown, tile);
            boolean on = at >= 0;
            boolean open = on || shown.length < NightView.MAX_SIDE;
            LinearLayout end = null;
            if (on) {
                end = Ui.row(c);
                if (at > 0) end.addView(arrow(c, tile, -1, mask, order));
                if (at < shown.length - 1) end.addView(arrow(c, tile, 1, mask, order));
                end.addView(checkMark(c));
            }
            LinearLayout row = Ui.listRow(c, null, 0, NightView.SIDE_NAMES[tile], end);
            row.setContentDescription(NightView.SIDE_NAMES[tile] + (on ? ", shown" : ", hidden"));
            if (open) {
                Ui.pressableRow(c, row, new OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        saveIdle(NightView.PREF_TILES, mask ^ (1 << tile));
                    }
                });
            } else {
                row.setAlpha(0.4f);
            }
            if (i > 0) sides.addView(Ui.hairline(c, 16));
            sides.addView(row);
        }
        list.addView(sides);
        TextView note = Ui.text(c, "Check up to " + NightView.MAX_SIDE + ". The arrows set the order. One or two "
                + "sit one above the other. With three, the first is wide on top. Four share a grid.", 13, Ui.MUTED);
        note.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), 0);
        list.addView(note);

        list.addView(Ui.header(c, "Show the idle screen after"));
        int dim = prefs.getInt(PREF_DIM, 5), dimIndex = 0;
        for (int i = 0; i < DIM_MINUTES.length; i++) if (DIM_MINUTES[i] == dim) dimIndex = i;
        list.addView(Kit.segmented(c, DIM_NAMES, dimIndex, new Kit.OnPick() {
            @Override
            public void picked(int index) {
                saveIdle(PREF_DIM, DIM_MINUTES[index]);
            }
        }));

        android.widget.Button show = Ui.accentButton(c, "Preview");
        show.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                preview.run();  // a touch brings the dock back
            }
        });
        LinearLayout actions = Ui.row(c);
        actions.setPadding(0, Ui.dp(c, 12), 0, 0);
        actions.addView(show, Ui.wrap(c, 4));
        list.addView(actions);
    }

    private LinearLayout pickRow(Context c, final int target, String value) {
        return groupRow(c, null, 0, target, value);
    }

    /** One tile choice per row, a checkmark on the chosen one. Picking saves and returns to Idle screen. */
    private void buildPicker(Context c, final String key, int fallback, String[] names, int[] order) {
        list.addView(new View(c), new LinearLayout.LayoutParams(1, Ui.dp(c, 12)));
        int chosen = pick(key, fallback, names.length);
        LinearLayout card = Ui.card(c);
        for (int i = 0; i < order.length; i++) {
            final int value = order[i];
            LinearLayout row = Ui.listRow(c, null, 0, names[value], value == chosen ? checkMark(c) : null);
            row.setContentDescription(names[value] + (value == chosen ? ", chosen" : ""));
            Ui.pressableRow(c, row, new OnClickListener() {
                @Override
                public void onClick(View v) {
                    page = IDLE;
                    onPage.picked(IDLE);
                    save(key, value);
                }
            });
            if (i > 0) card.addView(Ui.hairline(c, 16));
            card.addView(row);
        }
        list.addView(card);
    }

    /** An up or down arrow that moves a checked side tile one place. */
    private View arrow(Context c, final int tile, final int dir, final int mask, final int[] order) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(new Icon(Icon.Kind.CHEVRON, Ui.ACCENT));
        v.setRotation(dir < 0 ? -90 : 90);
        int p = Ui.dp(c, 9);
        v.setPadding(p, p, p, p);
        v.setContentDescription(NightView.SIDE_NAMES[tile] + (dir < 0 ? " up" : " down"));
        v.setClickable(true);
        v.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View view) {
                prefs.edit().putString(NightView.PREF_ORDER, Rules.joinOrder(Rules.moveTile(order, mask, tile, dir)))
                        .commit();
                saveIdle(NightView.PREF_TILES, mask);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
        lp.rightMargin = Ui.dp(c, 4);
        v.setLayoutParams(lp);
        return v;
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }

    private static View checkMark(Context c) {
        ImageView mark = new ImageView(c);
        mark.setImageDrawable(new Icon(Icon.Kind.CHECK, Ui.ACCENT));
        mark.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 22), Ui.dp(c, 22)));
        return mark;
    }

    private void buildButtons(Context c) {
        list.addView(Ui.header(c, "Vibrate when you press a button"));
        list.addView(Kit.segmented(c, Haptics.LEVEL_NAMES, levelIndex(Haptics.length(prefs)), new Kit.OnPick() {
            @Override
            public void picked(int index) {
                Haptics.tick(getContext(), Haptics.LEVELS_MS[index]);  // feel it right away
                save(Haptics.PREF_MS, Haptics.LEVELS_MS[index]);
            }
        }));

        list.addView(Ui.header(c, "Phone buttons"));
        LinearLayout phone = Ui.card(c);
        phone.addView(switchRow(c, Icon.Kind.SPEAKER, 0xFFFF2D55, "Volume buttons control the PC", PREF_VOLUME_KEYS,
                prefs.getBoolean(PREF_VOLUME_KEYS, true)));
        list.addView(phone);
    }

    /** The same switch as on the connect page, here too, so you can turn it off while the PC is on. */
    private LinearLayout startWithPcRow(final Context c) {
        Kit.Switch sw = new Kit.Switch(c, WatchService.enabled(c));
        sw.setContentDescription("Start with your PC");
        sw.setOnFlip(new Kit.OnFlip() {
            @Override
            public void flipped(boolean value) {
                prefs.edit().putBoolean(WatchService.PREF_ENABLED, value).commit();
                WatchService.sync(c);
                if (value && c instanceof MainActivity) ((MainActivity) c).explainWakePermissions();
            }
        });
        return Ui.listRow(c, Icon.Kind.POWER, 0xFF34C759, "Start with your PC", sw);
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

    /** Idle screen choices change nothing in the dock, so only this page redraws and keeps its scroll. */
    private void saveIdle(String key, int value) {
        prefs.edit().putInt(key, value).commit();
        final int y = getScrollY();
        build();
        post(new Runnable() {
            @Override
            public void run() {
                scrollTo(0, y);
            }
        });
    }

    private void save(String key, int value) {
        prefs.edit().putInt(key, value).commit();
        Ui.loadTheme(getContext(), prefs);
        apply.run();
    }
}
