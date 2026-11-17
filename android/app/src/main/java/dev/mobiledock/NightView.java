package dev.mobiledock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Random;

/**
 * The idle screen, like a bedside clock: one big tile and one side tile in a color you pick, on black.
 * Big: clock, calendar or now playing. Side: date, calendar, now playing, battery or nothing.
 * The tiles move a few dp now and then, so no pixel stays lit in the same place for hours.
 */
final class NightView extends FrameLayout {
    static final String PREF_COLOR = "idle_color", PREF_MAIN = "idle_main", PREF_SIDE = "idle_side";
    /** Bright system colors that read well on black. Red is kind to the eyes in a dark room. */
    static final int[] COLORS = {0xFF70B8FF, 0xFFF2F2F7, 0xFFFF4245, 0xFFFF9230, 0xFF30D158, 0xFFFF375F};
    static final String[] COLOR_NAMES = {"Blue", "White", "Red", "Orange", "Green", "Pink"};
    static final String[] MAIN_NAMES = {"Clock", "Calendar", "Playing"};
    static final String[] SIDE_NAMES = {"Date", "Calendar", "Playing", "Battery", "None"};
    private static final int CLOCK = 0, CALENDAR = 1, PLAYING = 2;  // big tile
    private static final int DATE = 0, SIDE_CALENDAR = 1, SIDE_PLAYING = 2, BATTERY = 3, NONE = 4;  // side tile

    private final DockClient client;
    private final Random random = new Random();
    private final LinearLayout box;
    private final int color, main, side;
    private boolean running;
    private TextView clock;
    private final java.util.List<Runnable> updaters = new java.util.ArrayList<Runnable>();
    private TextView songTitle, songArtist, sideTitle, sideArtist;
    private ImageView art, sideArt;
    private String artId = "";

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            if (running) postDelayed(this, 20_000);
        }
    };

    NightView(Context c, DockClient client, SharedPreferences prefs) {
        super(c);
        this.client = client;
        color = COLORS[clamp(prefs.getInt(PREF_COLOR, 0), COLORS.length)];
        main = clamp(prefs.getInt(PREF_MAIN, CLOCK), MAIN_NAMES.length);
        side = clamp(prefs.getInt(PREF_SIDE, DATE), SIDE_NAMES.length);
        setBackgroundColor(0xFF000000);
        setClickable(true);  // the first touch only wakes the dock
        box = Ui.row(c);
        int p = Ui.dp(c, 28);
        box.setPadding(p, p, p, p);
        // The clock gets more room than a calendar or a song, so its digits can fill the height.
        float big = side == NONE ? 1f : main == CLOCK ? 0.7f : 0.64f;
        box.addView(bigTile(c), new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, big));
        if (side != NONE) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f - big);
            lp.leftMargin = Ui.dp(c, 24);
            box.addView(sideTile(c), lp);
        }
        addView(box, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    private static int clamp(int v, int n) {
        return Math.max(0, Math.min(n - 1, v));
    }

    private View bigTile(Context c) {
        if (main == CALENDAR) return new MonthView(c, color, true);
        if (main == PLAYING) {
            LinearLayout col = Ui.row(c);
            col.setGravity(Gravity.CENTER_VERTICAL);
            art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int s = Ui.dp(c, 150);
            col.addView(art, new LinearLayout.LayoutParams(s, s));
            LinearLayout words = Ui.column(c);
            words.setPadding(Ui.dp(c, 20), 0, 0, 0);
            songTitle = big(c, "Nothing playing", 30, 0xFFFFFFFF);
            songTitle.setTypeface(Ui.bold());
            songTitle.setSingleLine(false);
            songTitle.setMaxLines(2);  // long titles get a second line before they are cut
            songArtist = big(c, "", 22, color);
            words.addView(songTitle);
            words.addView(songArtist);
            col.addView(words, new LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            return col;
        }
        // The clock fills its tile: the digits grow to the height, then shrink to fit the width.
        clock = new TextView(c) {
            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                fitClock(w, h);
            }
        };
        clock.setTypeface(Ui.bold());
        clock.setTextColor(color);
        clock.setGravity(Gravity.CENTER);
        clock.setSingleLine(true);
        clock.setIncludeFontPadding(false);
        return clock;
    }

    /** Sizes the digits to the time on screen: as tall as the tile allows, then narrowed to its width. */
    private void fitClock(int w, int h) {
        if (w <= 0 || h <= 0) return;
        String now = clock.getText().length() > 0 ? clock.getText().toString() : "00:00";
        Paint paint = new Paint();
        paint.setTypeface(Ui.bold());
        float size = h * 0.95f;  // digits are about three quarters of the font size, so this fills the height
        paint.setTextSize(size);
        float text = paint.measureText(now);
        if (text > w * 0.96f) size *= w * 0.96f / text;
        clock.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    private View sideTile(Context c) {
        if (side == SIDE_CALENDAR) return new MonthView(c, color, false);
        LinearLayout col = Ui.column(c);
        col.setGravity(Gravity.CENTER_VERTICAL);
        if (side == DATE) {
            final TextView day = big(c, "", 34, color);
            day.setTypeface(Ui.bold());
            final TextView month = big(c, "", 22, 0xFFFFFFFF);
            final TextView year = big(c, "", 17, 0x99FFFFFF);
            col.addView(day);
            col.addView(month);
            col.addView(year);
            updaters.add(new Runnable() {
                @Override
                public void run() {
                    Date now = new Date();
                    Locale l = Locale.getDefault();
                    day.setText(new SimpleDateFormat("EEE", l).format(now).toUpperCase(l) + " "
                            + new SimpleDateFormat("d", l).format(now));
                    month.setText(new SimpleDateFormat("MMMM", l).format(now));
                    year.setText(new SimpleDateFormat("yyyy", l).format(now));
                }
            });
        } else if (side == SIDE_PLAYING) {
            sideArt = new ImageView(c);
            sideArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            int s = Ui.dp(c, 84);
            col.addView(sideArt, new LinearLayout.LayoutParams(s, s));
            sideTitle = big(c, "Nothing playing", 20, 0xFFFFFFFF);
            sideTitle.setTypeface(Ui.bold());
            sideTitle.setPadding(0, Ui.dp(c, 10), 0, 0);
            sideArtist = big(c, "", 17, color);
            col.addView(sideTitle);
            col.addView(sideArtist);
        } else if (side == BATTERY) {
            final BatteryView battery = new BatteryView(c, color);
            col.addView(battery, new LinearLayout.LayoutParams(Ui.dp(c, 96), Ui.dp(c, 44)));
            final TextView level = big(c, "", 34, color);
            level.setTypeface(Ui.bold());
            level.setPadding(0, Ui.dp(c, 10), 0, 0);
            final TextView state = big(c, "", 17, 0x99FFFFFF);
            col.addView(level);
            col.addView(state);
            updaters.add(new Runnable() {
                @Override
                public void run() {
                    Intent b = getContext().registerReceiver(null,
                            new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                    if (b == null) return;
                    int pct = Math.round(100f * b.getIntExtra("level", 0) / Math.max(1, b.getIntExtra("scale", 100)));
                    int plugged = b.getIntExtra("plugged", 0);
                    battery.set(pct);
                    level.setText(pct + "%");
                    state.setText(plugged != 0 ? (pct >= 100 ? "Charged" : "Charging") : "Phone battery");
                }
            });
        }
        return col;
    }

    private static TextView big(Context c, String s, float sp, int color) {
        TextView t = Ui.text(c, s, sp, color);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    void start() {
        running = true;
        removeCallbacks(tick);
        post(tick);
    }

    void stop() {
        running = false;
        removeCallbacks(tick);
    }

    private void update() {
        if (clock != null) {
            boolean h24 = android.text.format.DateFormat.is24HourFormat(getContext());
            clock.setText(new SimpleDateFormat(h24 ? "H:mm" : "h:mm", Locale.getDefault()).format(new Date()));
            fitClock(clock.getWidth(), clock.getHeight());  // "8:31" can be bigger than "12:31"
        }
        for (Runnable r : updaters) r.run();
        // A small shift now and then, so the screen does not burn in. Small enough that the layout holds.
        int shift = Ui.dp(getContext(), 8);
        box.setTranslationX(random.nextInt(2 * shift + 1) - shift);
        box.setTranslationY(random.nextInt(2 * shift + 1) - shift);
        for (int i = 0; i < box.getChildCount(); i++) box.getChildAt(i).invalidate();
        if (songTitle != null || sideTitle != null) loadSong();
    }

    private void loadSong() {
        client.request("media.status", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                boolean on = r.optBoolean("ok") && r.optString("title").length() > 0;
                String title = on ? r.optString("title") : "Nothing playing";
                String artist = on ? r.optString("artist") : "";
                if (songTitle != null) {
                    songTitle.setText(title);
                    songArtist.setText(artist);
                }
                if (sideTitle != null) {
                    sideTitle.setText(title);
                    sideArtist.setText(artist);
                }
                String id = on ? r.optString("art_id", "") : "";
                if (!id.equals(artId)) {
                    artId = id;
                    if (id.length() == 0) showArt(null);
                    else loadArt();
                }
            }
        });
    }

    private void loadArt() {
        client.request("media.art", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                Bitmap bmp = null;
                String data = r.optString("art", "");
                if (r.optBoolean("ok") && data.length() > 0) {
                    try {
                        byte[] raw = Base64.decode(data, Base64.DEFAULT);
                        bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                    } catch (IllegalArgumentException ignored) {
                        // no cover then
                    }
                }
                showArt(bmp);
            }
        });
    }

    private void showArt(Bitmap bmp) {
        for (ImageView v : new ImageView[] {art, sideArt}) {
            if (v == null) continue;
            v.setImageBitmap(bmp != null ? rounded(bmp) : null);
            v.setVisibility(bmp != null ? VISIBLE : GONE);
        }
    }

    /** The cover with rounded corners, like the rest of the screen. */
    private static Bitmap rounded(Bitmap src) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new android.graphics.BitmapShader(src, android.graphics.Shader.TileMode.CLAMP,
                android.graphics.Shader.TileMode.CLAMP));
        float r = Math.min(src.getWidth(), src.getHeight()) * 0.12f;
        c.drawRoundRect(new RectF(0, 0, src.getWidth(), src.getHeight()), r, r, p);
        return out;
    }

    /** A month: its name in the color, weekday letters, and today in a filled circle. */
    static final class MonthView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private final boolean large;

        MonthView(Context c, int color, boolean large) {
            super(c);
            this.color = color;
            this.large = large;
            paint.setTypeface(Ui.semibold());
        }

        @Override
        protected void onDraw(Canvas c) {
            Calendar now = Calendar.getInstance();
            int first = now.getFirstDayOfWeek();
            int[] cells = Rules.monthGrid(now.get(Calendar.YEAR), now.get(Calendar.MONTH), first);
            int rows = cells[35] == 0 ? 5 : 6;
            float w = getWidth(), h = getHeight();
            float head = h * (large ? 0.16f : 0.15f);
            float cellW = w / 7f, cellH = (h - head * 1.8f) / rows;
            float text = Math.min(cellW, cellH) * 0.46f;
            paint.setTextAlign(Paint.Align.LEFT);
            paint.setColor(color);
            paint.setTextSize(head * 0.72f);
            paint.setTypeface(Ui.bold());
            Locale l = Locale.getDefault();
            String title = new SimpleDateFormat("MMMM", l).format(now.getTime()).toUpperCase(l);
            c.drawText(title, cellW * 0.5f - text * 0.3f, head * 0.75f, paint);
            paint.setTypeface(Ui.semibold());
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(text * 0.85f);
            paint.setColor(0x80FFFFFF);
            Calendar wd = Calendar.getInstance();
            for (int i = 0; i < 7; i++) {
                wd.set(Calendar.DAY_OF_WEEK, (first - 1 + i) % 7 + 1);
                String letter = new SimpleDateFormat("EEEEE", l).format(wd.getTime());
                c.drawText(letter, cellW * (i + 0.5f), head * 1.55f, paint);
            }
            int today = now.get(Calendar.DAY_OF_MONTH);
            paint.setTextSize(text);
            float top = head * 1.8f;
            for (int i = 0; i < rows * 7; i++) {
                if (cells[i] == 0) continue;
                float cx = cellW * (i % 7 + 0.5f), cy = top + cellH * (i / 7 + 0.5f);
                boolean isToday = cells[i] == today;
                if (isToday) {
                    paint.setColor(color);
                    c.drawCircle(cx, cy, Math.min(cellW, cellH) * 0.42f, paint);
                }
                paint.setColor(isToday ? 0xFF000000 : 0xE6FFFFFF);
                c.drawText(String.valueOf(cells[i]), cx, cy + text * 0.36f, paint);
            }
        }
    }

    /** A battery outline, filled to the level in the color. */
    static final class BatteryView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int color;
        private int level;

        BatteryView(Context c, int color) {
            super(c);
            this.color = color;
        }

        void set(int pct) {
            level = Math.max(0, Math.min(100, pct));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), nub = w * 0.06f, stroke = h * 0.07f;
            RectF body = new RectF(stroke, stroke, w - nub - stroke, h - stroke);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setColor(0x80FFFFFF);
            c.drawRoundRect(body, h * 0.22f, h * 0.22f, paint);
            paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(new RectF(w - nub, h * 0.32f, w, h * 0.68f), nub / 2, nub / 2, paint);
            float in = stroke * 2.2f;
            RectF fill = new RectF(body.left + in, body.top + in,
                    body.left + in + (body.width() - 2 * in) * level / 100f, body.bottom - in);
            paint.setColor(level <= 20 ? 0xFFFF4245 : color);
            c.drawRoundRect(fill, h * 0.12f, h * 0.12f, paint);
        }
    }
}
