package dev.mobiledock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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
 * Now playing: album art, title, progress with seeking, transport, shuffle, repeat and the PC volume.
 * Data comes from the PC media session (Windows) or MPRIS (Linux), so no Spotify account is needed.
 */
final class MediaPanel extends LinearLayout {
    private final DockClient client;
    private final Runnable onBack;
    private final TextView title, artist, album, source, elapsed, remaining;
    private final ImageView play, shuffle, repeat;
    private final SeekBar seek;
    private final VolumeRow volume;
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
        setPadding(Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));

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
        back.setVisibility(GONE);  // the sidebar is the way back now
        top.addView(back);
        TextView heading = Ui.strong(c, "Now Playing", 17, Ui.TEXT);
        top.addView(heading);
        top.addView(Ui.spacer(c));
        source = Ui.strong(c, "", 15, Ui.ACCENT);
        top.addView(source);
        left.addView(top, Ui.fillWidth(c, 0));

        title = centered(c, "Nothing playing", 24, Ui.TEXT);
        title.setTypeface(Ui.bold());
        left.addView(title, Ui.fillWidth(c, 2));
        artist = centered(c, "Start music on the PC", 19, Ui.TEXT);
        left.addView(artist, Ui.fillWidth(c, 0));
        album = centered(c, "", 13, Ui.MUTED);
        left.addView(album, Ui.fillWidth(c, 0));

        // Fixed, compact sizes: 38 + 42 + 54 + 42 + 38 dp plus gaps fits next to the cover.
        // -5 and +5 move the video or song. The arrows still skip whole tracks, which Spotify needs.
        LinearLayout transport = Ui.row(c);
        transport.setGravity(Gravity.CENTER);
        transport.addView(jump(c, Icon.Kind.BACK5, -5));
        transport.addView(control(c, Icon.Kind.PREV, 42, "media", "prev", false));
        play = control(c, Icon.Kind.PLAY, 54, "media", "play_pause", true);
        transport.addView(play);
        transport.addView(control(c, Icon.Kind.NEXT, 42, "media", "next", false));
        transport.addView(jump(c, Icon.Kind.FORWARD5, 5));
        left.addView(transport, Ui.fillWidth(c, 6));

        LinearLayout progress = Ui.row(c);
        elapsed = Ui.text(c, "", 12, Ui.MUTED);
        progress.addView(elapsed);
        seek = new SeekBar(c);
        Kit.slider(c, seek);
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
        remaining = Ui.text(c, "", 12, Ui.MUTED);
        progress.addView(remaining);
        left.addView(progress, Ui.fillWidth(c, 2));

        LinearLayout extras = Ui.row(c);
        extras.setGravity(Gravity.CENTER);
        shuffle = control(c, Icon.Kind.SHUFFLE, 40, "media.shuffle", null, false);
        extras.addView(shuffle);
        repeat = control(c, Icon.Kind.REPEAT, 40, "media.repeat", null, false);
        extras.addView(repeat);
        // The PC's own volume, the same level as the taskbar. Hidden until the PC answers volume.get.
        volume = new VolumeRow(c, null, new VolumeRow.Sink() {
            @Override
            public void send(JSONObject change) {
                MediaPanel.this.client.fire("volume.set", change);
            }
        });
        volume.setVisibility(GONE);
        extras.addView(volume, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        left.addView(extras, Ui.fillWidth(c, 0));

        // Like Apple Music in landscape: the cover on the left, the song and controls on the right.
        art = new ImageView(c);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(Ui.rounded(c, Ui.SURFACE_HI, 14));
        LayoutParams alp = new LayoutParams(0, 0);
        alp.rightMargin = pad;
        addView(art, alp);
        addView(left, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
    }

    /** The cover is a square that fills the height but always leaves room for the controls. */
    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int controls = Ui.dp(getContext(), 236);  // the song and the compact controls need this much width
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
                ? Ui.iconButton(c, kind, size, 0x00000000, Ui.SELECTED, Ui.TEXT)  // Apple Music: plain glyphs
                : Ui.iconButton(c, kind, size, 0x00000000, Ui.SELECTED, Ui.TEXT);
        LayoutParams lp = (LayoutParams) b.getLayoutParams();
        int m = Ui.dp(c, primary ? 6 : 2);
        lp.setMargins(m, 0, m, 0);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                send(cmd, action == null ? null : DockClient.args("action", action));
            }
        });
        return b;
    }

    /** -5 or +5 seconds. Works with the player in the background, on any monitor. */
    private ImageView jump(Context c, Icon.Kind kind, final int seconds) {
        ImageView b = Ui.iconButton(c, kind, 38, 0x00000000, Ui.SELECTED, Ui.TEXT);
        LayoutParams lp = (LayoutParams) b.getLayoutParams();
        int m = Ui.dp(c, 2);
        lp.setMargins(m, 0, m, 0);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                send("media.jump", DockClient.args("seconds", seconds));
            }
        });
        return b;
    }

    /** On, like Apple Music: the icon turns the accent color on a soft gray circle. No solid fill. */
    private void mark(ImageView b, boolean on) {
        ((Icon) b.getDrawable()).setColor(on ? Ui.ACCENT : Ui.TEXT);
        b.setBackground(Ui.pressable(getContext(), on ? Ui.SELECTED : 0x00000000, Ui.SELECTED, 20));
    }

    private static TextView centered(Context c, String s, float sp, int color) {
        TextView t = Ui.text(c, s, sp, color);
        t.setGravity(Gravity.START);
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
        play.setImageDrawable(new Icon(playing ? Icon.Kind.PAUSE : Icon.Kind.PLAY, Ui.TEXT));
        play.setContentDescription(playing ? "Pause" : "Play");
        if (r.has("shuffle")) {
            shuffle.setVisibility(VISIBLE);
            mark(shuffle, r.optBoolean("shuffle"));
        } else {
            shuffle.setVisibility(GONE);
        }
        if (r.has("repeat")) {
            String mode = r.optString("repeat");
            repeat.setVisibility(VISIBLE);
            repeat.setImageDrawable(new Icon("track".equals(mode) ? Icon.Kind.REPEAT_ONE : Icon.Kind.REPEAT, Ui.TEXT));
            mark(repeat, !"none".equals(mode));
            repeat.setContentDescription("track".equals(mode) ? "Repeat one" : "Repeat all");
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

    /** Called when the cover changes, with a background made from its colors. */
    interface Backdrop {
        void changed(android.graphics.drawable.Drawable background);
    }

    private Backdrop backdropListener;
    private android.graphics.drawable.Drawable backdrop;

    void setBackdropListener(Backdrop l) {
        backdropListener = l;
    }

    /** The page background made from the cover, or null when nothing plays. */
    android.graphics.drawable.Drawable backdrop() {
        return backdrop;
    }

    /**
     * Shows the cover. Its colors fill the whole page and run under the sidebar, like Apple Music
     * (Apple calls this a background extension). Mixed with the page color, so text stays readable.
     */
    private void setArt(Bitmap bmp) {
        art.setImageBitmap(bmp);
        backdrop = null;
        if (bmp != null) {
            Bitmap two = Bitmap.createScaledBitmap(bmp, 1, 2, true);
            float mix = Ui.DARK ? 0.42f : 0.38f;
            int top = Kit.blend(Ui.BG, two.getPixel(0, 0), mix), bottom = Kit.blend(Ui.BG, two.getPixel(0, 1), mix);
            backdrop = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        }
        if (backdropListener != null) backdropListener.changed(backdrop);
    }

    void refresh() {
        client.request("media.status", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject reply) {
                if (reply.optBoolean("ok")) show(reply);
            }
        });
        client.request("volume.get", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject reply) {
                boolean ok = reply.optBoolean("ok");  // an older PC app, or no sound output
                volume.setVisibility(ok ? VISIBLE : GONE);
                if (ok) volume.show(reply.optInt("volume"), reply.optBoolean("muted"));
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
