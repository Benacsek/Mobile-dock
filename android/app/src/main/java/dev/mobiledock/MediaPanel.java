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
import android.widget.Button;
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
    private final Button play, shuffle, repeat;
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
        int pad = Ui.dp(c, 16);
        setPadding(pad, Ui.dp(c, 8), pad, Ui.dp(c, 8));
        setBackground(Ui.rounded(c, Ui.SURFACE, 16));

        LinearLayout left = Ui.column(c);
        left.setGravity(Gravity.CENTER_HORIZONTAL);

        LinearLayout top = Ui.row(c);
        Button back = Ui.button(c, "Back");
        back.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                MediaPanel.this.onBack.run();
            }
        });
        top.addView(back);
        top.addView(Ui.spacer(c));
        source = Ui.text(c, "", 13, Ui.MUTED);
        top.addView(source);
        left.addView(top, Ui.fillWidth(c, 0));

        title = centered(c, "Nothing playing", 24, Ui.TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        left.addView(title, Ui.fillWidth(c, 2));
        artist = centered(c, "Start music on the PC", 16, Ui.TEXT);
        left.addView(artist, Ui.fillWidth(c, 0));
        album = centered(c, "", 15, Ui.ACCENT);
        left.addView(album, Ui.fillWidth(c, 0));

        LinearLayout transport = Ui.row(c);
        transport.setGravity(Gravity.CENTER);
        transport.addView(action(c, "Previous", "media", "prev"), size(c, 110, 56));
        play = action(c, "Play", "media", "play_pause");
        play.setBackground(Ui.pressable(c, Ui.ACCENT, 0xFF3A6FD0, 28));
        transport.addView(play, size(c, 120, 64));
        transport.addView(action(c, "Next", "media", "next"), size(c, 110, 56));
        left.addView(transport, Ui.fillWidth(c, 6));

        LinearLayout progress = Ui.row(c);
        elapsed = Ui.text(c, "0:00", 13, Ui.TEXT);
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
        remaining = Ui.text(c, "", 13, Ui.TEXT);
        progress.addView(remaining);
        left.addView(progress, Ui.fillWidth(c, 2));

        LinearLayout extras = Ui.row(c);
        extras.setGravity(Gravity.CENTER);
        shuffle = action(c, "Shuffle", "media.shuffle", null);
        extras.addView(shuffle, Ui.wrap(c, 3));
        repeat = action(c, "Repeat", "media.repeat", null);
        extras.addView(repeat, Ui.wrap(c, 3));
        extras.addView(action(c, "Vol -", "media", "vol_down"), Ui.wrap(c, 3));
        extras.addView(action(c, "Mute", "media", "mute"), Ui.wrap(c, 3));
        extras.addView(action(c, "Vol +", "media", "vol_up"), Ui.wrap(c, 3));
        left.addView(extras, Ui.fillWidth(c, 0));

        addView(left, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.5f));

        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.FIT_CENTER);
        art.setAdjustViewBounds(true);
        LayoutParams alp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        alp.leftMargin = pad;
        addView(art, alp);
    }

    private static TextView centered(Context c, String s, float sp, int color) {
        TextView t = Ui.text(c, s, sp, color);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        return t;
    }

    private static LayoutParams size(Context c, int w, int h) {
        LayoutParams lp = new LayoutParams(Ui.dp(c, w), Ui.dp(c, h));
        int m = Ui.dp(c, 6);
        lp.setMargins(m, 0, m, 0);
        return lp;
    }

    private Button action(Context c, String label, final String cmd, final String mediaAction) {
        Button b = Ui.button(c, label);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                send(cmd, mediaAction == null ? null : DockClient.args("action", mediaAction));
            }
        });
        return b;
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
        play.setText(playing ? "Pause" : "Play");
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
            repeat.setText("track".equals(mode) ? "Repeat one" : "list".equals(mode) ? "Repeat all" : "Repeat");
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
