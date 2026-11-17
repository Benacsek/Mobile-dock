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

/**
 * Colors, type and small view factories, in the style of Apple's Human Interface Guidelines.
 * The whole UI is built in code, so it works on every Android version.
 *
 * The color names are semantic, like Apple's: BG is the grouped background, SURFACE a card on it,
 * SURFACE_HI a fill for buttons and tracks, LINE a hairline, TEXT, MUTED and FAINT the label levels.
 * loadTheme sets them for light or dark, and every page reads them when it is built.
 */
final class Ui {
    static int BG, SURFACE, SURFACE_HI, PRESSED, LINE, TEXT, MUTED, FAINT;
    /** System orange: things happening right now, such as recording or a running countdown. */
    static int SIGNAL;
    static int DANGER, GREEN;
    /** Floating glass for navigation: tint, bright rim and shadow. Content never uses it. */
    static int GLASS, GLASS_RIM, SELECTED;
    static boolean DARK = true;
    /** Set from the Appearance page. Everything reads it when it is built. */
    static int ACCENT;
    static float TILE_SCALE = 1f;
    static boolean LABELS = true;

    /** Apple's system colors, 2025 values from developer.apple.com: light, then dark. */
    static final int[][] ACCENTS = {
        {0xFF0088FF, 0xFF0091FF}, {0xFF34C759, 0xFF30D158}, {0xFF6155F5, 0xFF6D7CFF}, {0xFFFF8D28, 0xFFFF9230},
        {0xFFFF2D55, 0xFFFF375F},
    };
    static final String[] ACCENT_NAMES = {"Blue", "Green", "Indigo", "Orange", "Pink"};
    static final float[] TILE_SCALES = {0.85f, 1f, 1.2f};
    static final String[] TILE_SCALE_NAMES = {"Small", "Normal", "Large"};
    static final String PREF_THEME = "theme";  // 0 automatic, 1 light, 2 dark
    static final String[] THEME_NAMES = {"Automatic", "Light", "Dark"};

    private static Typeface regular, semibold, bold;

    /** Loads Inter once. Without the font files the system font is used, so nothing breaks. */
    static void init(Context c) {
        if (regular != null) return;
        try {
            regular = Typeface.createFromAsset(c.getAssets(), "fonts/Inter-Regular.ttf");
            semibold = Typeface.createFromAsset(c.getAssets(), "fonts/Inter-SemiBold.ttf");
            bold = Typeface.createFromAsset(c.getAssets(), "fonts/Inter-Bold.ttf");
        } catch (RuntimeException e) {
            regular = Typeface.DEFAULT;
            semibold = Typeface.DEFAULT_BOLD;
            bold = Typeface.DEFAULT_BOLD;
        }
    }

    static Typeface regular() {
        return regular != null ? regular : Typeface.DEFAULT;
    }

    static Typeface semibold() {
        return semibold != null ? semibold : Typeface.DEFAULT_BOLD;
    }

    static Typeface bold() {
        return bold != null ? bold : Typeface.DEFAULT_BOLD;
    }

    /** Dark on Android 9 and older, where the phone has no dark mode setting to follow. */
    static boolean systemDark(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 29) return true;
        int night = c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night != android.content.res.Configuration.UI_MODE_NIGHT_NO;
    }

    static void loadTheme(Context c, android.content.SharedPreferences p) {
        init(c);
        int mode = p.getInt(PREF_THEME, 0);
        DARK = mode == 2 || (mode == 0 && systemDark(c));
        if (DARK) {
            BG = 0xFF000000; SURFACE = 0xFF1C1C1E; SURFACE_HI = 0xFF2C2C2E; PRESSED = 0xFF3A3A3C;
            LINE = 0xFF38383A; TEXT = 0xFFFFFFFF; MUTED = 0xFF98989F; FAINT = 0xFF636366;
            SIGNAL = 0xFFFF9230; DANGER = 0xFFFF4245; GREEN = 0xFF30D158;
            GLASS = 0xD82C2C30; GLASS_RIM = 0x24FFFFFF; SELECTED = 0x22FFFFFF;
        } else {
            BG = 0xFFF2F2F7; SURFACE = 0xFFFFFFFF; SURFACE_HI = 0xFFE9E9EE; PRESSED = 0xFFD1D1D6;
            LINE = 0xFFC6C6C8; TEXT = 0xFF000000; MUTED = 0xFF6E6E73; FAINT = 0xFFAEAEB2;
            SIGNAL = 0xFFFF8D28; DANGER = 0xFFFF383C; GREEN = 0xFF34C759;
            GLASS = 0xD8FAFAFC; GLASS_RIM = 0x99FFFFFF; SELECTED = 0x16000000;
        }
        int a = Math.max(0, Math.min(ACCENTS.length - 1, p.getInt("accent", 0)));
        ACCENT = ACCENTS[a][DARK ? 1 : 0];
        TILE_SCALE = TILE_SCALES[Math.max(0, Math.min(TILE_SCALES.length - 1, p.getInt("tile_scale", 1)))];
        LABELS = p.getBoolean("labels", true);
    }

    /** The accent, a little darker, for the pressed state. */
    static int accentPressed() {
        return android.graphics.Color.rgb(android.graphics.Color.red(ACCENT) * 4 / 5,
                android.graphics.Color.green(ACCENT) * 4 / 5, android.graphics.Color.blue(ACCENT) * 4 / 5);
    }

    /** Text on the accent. Apple uses white on every system color. */
    static int onAccent() {
        return 0xFFFFFFFF;
    }

    /** The grouped background. Flat, like iOS: depth comes from cards and the floating sidebar. */
    static Drawable background(Context c) {
        GradientDrawable base = new GradientDrawable();
        base.setColor(BG);
        return base;
    }

    /** Apple's system colors (light values) for placeholder icons, picked from the name. */
    static final int[] TILE_COLORS = {
        0xFF0088FF, 0xFFCB30E0, 0xFFFF383C, 0xFFFF8D28, 0xFF34C759, 0xFF00C0E8, 0xFFFF2D55, 0xFF6155F5,
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
        t.setTypeface(regular());
        return t;
    }

    /** Text in Inter SemiBold, for headlines, buttons and the selected row. */
    static TextView strong(Context c, String s, float sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(semibold());
        return t;
    }

    /**
     * Labels inside fixed-size tiles grow with the system text size up to 15 percent, then end in "…".
     * Without the cap, large text would cut words in half.
     */
    static void fitLabel(TextView t, float sp) {
        android.util.DisplayMetrics m = t.getResources().getDisplayMetrics();
        float scale = Math.min(t.getResources().getConfiguration().fontScale, 1.15f);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * m.density * scale);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
    }

    /** Apple's Large Title: 34, bold, left aligned. Large text makes it at most 15 percent bigger. */
    static TextView title(Context c, String s) {
        TextView t = text(c, s, 34, TEXT);
        t.setTypeface(bold());
        fitLabel(t, 34);
        t.setPadding(dp(c, 4), 0, 0, dp(c, 2));
        return t;
    }

    /** Section header above a grouped card, in sentence case: "Volume mixer". */
    static TextView header(Context c, String s) {
        TextView t = strong(c, s, 15, TEXT);
        t.setPadding(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 6));
        return t;
    }

    /** A grouped list card: rows inside are separated by hairlines that start after the icon. */
    static LinearLayout card(Context c) {
        LinearLayout l = column(c);
        l.setBackground(rounded(c, SURFACE, 20));
        return l;
    }

    /** A hairline between rows of a card, inset from the left like iOS. */
    static View hairline(Context c, int insetDp) {
        View v = new View(c);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        lp.leftMargin = dp(c, insetDp);
        v.setLayoutParams(lp);
        return v;
    }

    /** A colored rounded square with a white line icon, like the icons in iOS Settings. */
    static View iconTile(Context c, Icon.Kind kind, int color) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(new Icon(kind, 0xFFFFFFFF));
        int p = dp(c, 6);
        v.setPadding(p, p, p, p);
        v.setBackground(rounded(c, color, 8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30));
        lp.rightMargin = dp(c, 12);
        v.setLayoutParams(lp);
        return v;
    }

    /**
     * One row of a grouped card: optional icon tile, label, then a value, a chevron or any view at the end.
     * Rows are at least 44 dp tall, Apple's minimum touch target.
     */
    static LinearLayout listRow(Context c, Icon.Kind kind, int tileColor, String label, View end) {
        LinearLayout row = row(c);
        row.setMinimumHeight(dp(c, 48));
        row.setPadding(dp(c, 12), dp(c, 4), dp(c, 14), dp(c, 4));
        if (kind != null) row.addView(iconTile(c, kind, tileColor));
        else row.setPadding(dp(c, 16), dp(c, 4), dp(c, 14), dp(c, 4));
        TextView t = text(c, label, 17, TEXT);
        row.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        if (end != null) row.addView(end);
        return row;
    }

    /** Gray value text at the end of a row, such as "Automatic". */
    static TextView value(Context c, String s) {
        TextView t = text(c, s, 17, MUTED);
        t.setPadding(dp(c, 8), 0, 0, 0);
        return t;
    }

    /** A chevron at the end of a row that opens something. */
    static View chevron(Context c) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(new Icon(Icon.Kind.CHEVRON, FAINT));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 14), dp(c, 14));
        lp.leftMargin = dp(c, 8);
        v.setLayoutParams(lp);
        return v;
    }

    /** Makes a whole row pressable, with the iOS gray highlight. */
    static void pressableRow(Context c, View row, View.OnClickListener click) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[] {android.R.attr.state_pressed}, new android.graphics.drawable.ColorDrawable(PRESSED));
        s.addState(new int[] {}, new android.graphics.drawable.ColorDrawable(0x00000000));
        row.setBackground(s);
        row.setClickable(true);
        row.setOnClickListener(click);
    }

    /** iOS 26 buttons are capsules: gray fill with accent text, or the accent fill for the main action. */
    static Button button(Context c, String label) {
        Button b = new Button(c);
        b.setText(label);
        b.setTextColor(ACCENT);
        b.setAllCaps(false);
        b.setTypeface(semibold());
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        b.setBackground(pressable(c, SURFACE_HI, PRESSED, 24));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setPadding(dp(c, 18), 0, dp(c, 18), 0);
        return b;
    }

    /** A key on the touchpad or keyboard pages: smaller text on one line, so rows of keys fit. */
    static Button keyButton(Context c, String label) {
        Button b = button(c, label);
        // White on the accent while a modifier such as Ctrl is held down.
        b.setTextColor(new android.content.res.ColorStateList(
                new int[][] {{android.R.attr.state_selected}, {}}, new int[] {0xFFFFFFFF, TEXT}));
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setSingleLine(true);
        b.setEllipsize(android.text.TextUtils.TruncateAt.END);
        b.setBackground(pressable(c, SURFACE, PRESSED, 12));
        b.setPadding(dp(c, 6), 0, dp(c, 6), 0);
        return b;
    }

    static Button accentButton(Context c, String label) {
        Button b = button(c, label);
        b.setBackground(pressable(c, ACCENT, accentPressed(), 24));
        b.setTextColor(onAccent());
        return b;
    }

    /** Apple's search field: a gray capsule with a magnifier and gray placeholder text. */
    static void searchField(Context c, android.widget.EditText field, String hint) {
        field.setHint(hint);
        field.setSingleLine(true);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setTypeface(regular());
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        field.setBackground(rounded(c, SURFACE_HI, 22));
        Icon glass = new Icon(Icon.Kind.SEARCH, MUTED);
        int s = dp(c, 17);
        glass.setBounds(0, 0, s, s);
        field.setCompoundDrawables(glass, null, null, null);
        field.setCompoundDrawablePadding(dp(c, 8));
        field.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        field.setMinHeight(dp(c, 44));
    }

    /** Fills a selected button of a choice with the accent, like a picked option. */
    static void markChosen(Button b) {
        b.setBackground(pressable(b.getContext(), ACCENT, accentPressed(), 24));
        b.setTextColor(onAccent());
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
        b.setContentDescription(spoken(kind));  // screen readers read this, since the button has no text
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return b;
    }

    /** What a screen reader says for an icon-only button. Callers can set a more specific text. */
    static String spoken(Icon.Kind kind) {
        switch (kind) {
            case PLAY: return "Play";
            case PAUSE: return "Pause";
            case PREV: return "Previous track";
            case NEXT: return "Next track";
            case SHUFFLE: return "Shuffle";
            case REPEAT: return "Repeat all";
            case REPEAT_ONE: return "Repeat one";
            case VOL_DOWN: return "Volume down";
            case VOL_UP: return "Volume up";
            case MUTE: return "Mute";
            case BACK: return "Back";
            case PLUS: return "Add";
            case REFRESH: return "Refresh";
            case FOLLOW: return "Follow the app in front";
            case EXPAND: return "Fullscreen";
            case SHRINK: return "Exit fullscreen";
            case GRIP: return "Arrange apps";
            case BACK5: return "Back 5 seconds";
            case SEARCH: return "Search";
            case FORWARD5: return "Forward 5 seconds";
            default: return kind.name().toLowerCase(java.util.Locale.US).replace('_', ' ');
        }
    }

    /** Plain icon button with no fill until pressed. */
    static ImageView flatIcon(Context c, Icon.Kind kind, int sizeDp) {
        return iconButton(c, kind, sizeDp, 0x00000000, SELECTED, TEXT);
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
