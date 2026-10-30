package dev.mobiledock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/**
 * Now playing: album art, title, progress with seeking, transport, shuffle, repeat and volume.
 * Data comes from the PC media session (Windows) or MPRIS (Linux), so no Spotify account is needed.
 */
final class MediaPanel extends LinearLayout {
    private final DockClient client;
    private final Runnable onBack;
    private final TextView title, artist, album, source, elapsed, remaining;
    private final ImageView play, shuffle, repeat;
    private final SeekBar seek;
    private final ImageView art;
    private boolean active, playing, dragging;
    private double position, duration;
    private long positionAt;
    private String artId = "";

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            postDelayed(this, 2000);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            drawProgress();
            postDelayed(this, 500);
        }
    };

    MediaPanel(Context c, DockClient client, Runnable onBack) {
        super(c);
        this.client = client;
        this.onBack = onBack;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(c, 18);
        setPadding(pad, Ui.dp(c, 10), pad, Ui.dp(c, 10));
        setBackground(Ui.rounded(c, Ui.SURFACE, 18));

        LinearLayout left = Ui.column(c);
        left.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout top = Ui.row(c);
        ImageView back = Ui.flatIcon(c, Icon.Kind.BACK, 40);
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                MediaPanel.this.onBack.run();
            }
        });
        top.addView(back);
        TextView heading = Ui.text(c, "Now playing", 14, Ui.MUTED);
        top.addView(heading);
        top.addView(Ui.spacer(c));
        source = Ui.text(c, "", 13, Ui.ACCENT);
        top.addView(source);
        left.addView(top, Ui.fillWidth(c, 0));

        title = centered(c, "Nothing playing", 22, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title, Ui.fillWidth(c, 2));
        artist = centered(c, "Start music on the PC", 15, Ui.TEXT);
        left.addView(artist, Ui.fillWidth(c, 0));
        album = centered(c, "", 14, Ui.ACCENT);
        left.addView(album, Ui.fillWidth(c, 0));

        // Fixed, compact sizes: 52 + 68 + 52 dp plus gaps always fits next to the cover.
        LinearLayout transport = Ui.row(c);
        transport.setGravity(Gravity.CENTER);
        transport.addView(control(c, Icon.Kind.PREV, 52, "media", "prev", false));
        play = control(c, Icon.Kind.PLAY, 68, "media", "play_pause", true);
        transport.addView(play);
        transport.addView(control(c, Icon.Kind.NEXT, 52, "media", "next", false));
        left.addView(transport, Ui.fillWidth(c, 6));

        LinearLayout progress = Ui.row(c);
        elapsed = Ui.text(c, "", 12, Ui.TEXT);
        progress.addView(elapsed);
        seek = new SeekBar(c);
        seek.setMax(1000);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
                if (fromUser && duration > 0) elapsed.setText(time(duration * value / 1000.0));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                dragging = false;
                if (duration <= 0) return;
                double target = duration * bar.getProgress() / 1000.0;
                position = target;
                positionAt = System.currentTimeMillis();
                send("media.seek", DockClient.args("position", target));
            }
        });
        progress.addView(seek, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        remaining = Ui.text(c, "", 12, Ui.TEXT);
        progress.addView(remaining);
        left.addView(progress, Ui.fillWidth(c, 2));

        LinearLayout extras = Ui.row(c);
        extras.setGravity(Gravity.CENTER);
        shuffle = control(c, Icon.Kind.SHUFFLE, 42, "media.shuffle", null, false);
        extras.addView(shuffle);
        repeat = control(c, Icon.Kind.REPEAT, 42, "media.repeat", null, false);
        extras.addView(repeat);
        extras.addView(control(c, Icon.Kind.VOL_DOWN, 42, "media", "vol_down", false));
        extras.addView(control(c, Icon.Kind.MUTE, 42, "media", "mute", false));
        extras.addView(control(c, Icon.Kind.VOL_UP, 42, "media", "vol_up", false));
        left.addView(extras, Ui.fillWidth(c, 0));

        addView(left, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.rounded(c, Ui.SURFACE_HI, 14));
        LayoutParams alp = new LayoutParams(0, 0);
        alp.leftMargin = pad;
        addView(art, alp);
    }

    /** The cover is a square that fills the height but always leaves room for the controls. */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int controls = Ui.dp(getContext(), 300);
        int side = Math.min(h - getPaddingTop() - getPaddingBottom(),
                w - getPaddingLeft() - getPaddingRight() - controls - Ui.dp(getContext(), 18));
        final int size = Math.max(0, side);
        post(new Runnable() {
            @Override
            public void run() {
                LayoutParams lp = (LayoutParams) art.getLayoutParams();
                lp.width = size;
                lp.height = size;
                art.setVisibility(size < Ui.dp(getContext(), 80) ? GONE : VISIBLE);
                art.setLayoutParams(lp);
            }
        });
    }

    private ImageView control(Context c, Icon.Kind kind, int size, final String cmd, final String action,
                              boolean primary) {
        ImageView b = primary
                ? Ui.iconButton(c, kind, size, Ui.ACCENT, 0xFF3A6FD0, 0xFFFFFFFF)
                : Ui.iconButton(c, kind, size, 0x00000000, 0x33FFFFFF, Ui.TEXT);
        LayoutParams lp = (LayoutParams) b.getLayoutParams();
        int m = Ui.dp(c, primary ? 12 : 4);
        lp.setMargins(m, 0, m, 0);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                send(cmd, action == null ? null : DockClient.args("action", action));
            }
        });
        return b;
    }

    private static TextView centered(Context c, String s, float sp, int color) {
        TextView t = Ui.text(c, s, sp, color);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private void send(String cmd, JSONObject args) {
        client.request(cmd, args, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (!r.optBoolean("ok")) {
                    Toast.makeText(getContext(), r.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                    return;
                }
                show(r);
            }
        });
    }

    static String time(double seconds) {
        int s = (int) Math.max(0, Math.round(seconds));
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private void show(JSONObject r) {
        if (!r.has("title") && !r.has("playing")) return;
        String t = r.optString("title");
        title.setText(t.length() == 0 ? "Nothing playing" : t);
        artist.setText(t.length() == 0 ? "Start music on the PC" : r.optString("artist"));
        album.setText(r.optString("album"));
        source.setText(appLabel(r.optString("app")));
        playing = r.optBoolean("playing");
        play.setImageDrawable(new Icon(playing ? Icon.Kind.PAUSE : Icon.Kind.PLAY, 0xFFFFFFFF));
        if (r.has("shuffle")) {
            shuffle.setVisibility(VISIBLE);
            shuffle.setSelected(r.optBoolean("shuffle"));
        } else {
            shuffle.setVisibility(GONE);
        }
        if (r.has("repeat")) {
            String mode = r.optString("repeat");
            repeat.setVisibility(VISIBLE);
            repeat.setSelected(!"none".equals(mode));
            repeat.setImageDrawable(new Icon("track".equals(mode) ? Icon.Kind.REPEAT_ONE : Icon.Kind.REPEAT, Ui.TEXT));
        } else {
            repeat.setVisibility(GONE);
        }
        duration = r.optDouble("duration", 0);
        if (!dragging && r.has("position")) {
            position = r.optDouble("position", 0);
            positionAt = System.currentTimeMillis();
        }
        seek.setEnabled(duration > 0);
        drawProgress();
        String id = r.optString("art_id");
        if (!id.equals(artId)) {
            artId = id;
            if (id.length() == 0) setArt(null);
            else loadArt(id);
        }
    }

    private static String appLabel(String app) {
        String a = app.toLowerCase();
        if (a.contains("spotify")) return "Spotify";
        if (a.length() == 0) return "";
        int dot = app.lastIndexOf('.');
        return dot >= 0 && dot < app.length() - 1 ? app.substring(dot + 1) : app;
    }

    private void drawProgress() {
        if (duration <= 0) {
            elapsed.setText("");
            remaining.setText("");
            seek.setProgress(0);
            return;
        }
        double now = position + (playing ? (System.currentTimeMillis() - positionAt) / 1000.0 : 0);
        now = Math.min(now, duration);
        if (!dragging) {
            seek.setProgress((int) (1000 * now / duration));
            elapsed.setText(time(now));
        }
        remaining.setText("-" + time(duration - now));
    }

    private void loadArt(final String id) {
        client.request("media.art", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (!id.equals(artId)) return;
                String data = r.optString("art");
                Bitmap bmp = null;
                if (data.length() > 0) {
                    try {
                        byte[] raw = Base64.decode(data, Base64.DEFAULT);
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inSampleSize = 1;
                        bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
                    } catch (IllegalArgumentException | OutOfMemoryError e) {
                        bmp = null;
                    }
                }
                setArt(bmp);
            }
        });
    }

    /** Shows the cover and tints the panel with its average color, like car dashboards do. */
    private void setArt(Bitmap bmp) {
        art.setImageBitmap(bmp);
        int base = Ui.SURFACE;
        if (bmp != null) {
            int avg = Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0);
            base = Color.rgb(Color.red(avg) / 2, Color.green(avg) / 2, Color.blue(avg) / 2);
        }
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {base, Ui.BG});
        g.setCornerRadius(Ui.dp(getContext(), 16));
        setBackground(g);
    }

    void refresh() {
        client.request("media.status", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject reply) {
                if (reply.optBoolean("ok")) show(reply);
            }
        });
    }

    void setActive(boolean on) {
        active = on;
        removeCallbacks(poll);
        removeCallbacks(tick);
        if (on) {
            post(poll);
            post(tick);
        }
    }

    boolean isActive() {
        return active;
    }
}
