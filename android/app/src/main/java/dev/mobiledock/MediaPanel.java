package dev.mobiledock;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Now playing and media keys. Works with any player that reacts to media keys or MPRIS. */
final class MediaPanel extends LinearLayout {
    private final DockClient client;
    private final TextView title;
    private final TextView artist;
    private final Button play;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            postDelayed(this, 3000);
        }
    };

    MediaPanel(Context c, DockClient client) {
        super(c);
        this.client = client;
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER);

        title = Ui.title(c, "Nothing playing");
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        addView(title, Ui.fillWidth(c, 4));
        artist = Ui.text(c, "", 15, Ui.MUTED);
        artist.setGravity(Gravity.CENTER);
        addView(artist, Ui.fillWidth(c, 4));

        LinearLayout transport = Ui.row(c);
        transport.setGravity(Gravity.CENTER);
        transport.addView(key(c, "Previous", "prev"), big(c));
        play = key(c, "Play / Pause", "play_pause");
        play.setBackground(Ui.pressable(c, Ui.ACCENT, 0xFF3A6FD0, 10));
        transport.addView(play, big(c));
        transport.addView(key(c, "Next", "next"), big(c));
        addView(transport, Ui.fillWidth(c, 12));

        LinearLayout volume = Ui.row(c);
        volume.setGravity(Gravity.CENTER);
        volume.addView(key(c, "Volume down", "vol_down"), big(c));
        volume.addView(key(c, "Mute", "mute"), big(c));
        volume.addView(key(c, "Volume up", "vol_up"), big(c));
        addView(volume, Ui.fillWidth(c, 12));
    }

    private LayoutParams big(Context c) {
        LayoutParams lp = new LayoutParams(Ui.dp(c, 150), Ui.dp(c, 64));
        int m = Ui.dp(c, 6);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private Button key(Context c, String label, final String action) {
        Button b = Ui.button(c, label);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                client.request("media", DockClient.args("action", action), new DockClient.Callback() {
                    @Override
                    public void done(JSONObject reply) {
                        show(reply);
                    }
                });
            }
        });
        return b;
    }

    private void show(JSONObject r) {
        if (!r.optBoolean("ok")) {
            android.widget.Toast.makeText(getContext(), r.optString("error", "Failed"), android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if (!r.has("title")) return;
        String t = r.optString("title");
        title.setText(t.length() == 0 ? "Nothing playing" : t);
        artist.setText(r.optString("artist"));
        play.setText(r.optBoolean("playing") ? "Pause" : "Play");
    }

    void refresh() {
        client.request("media.status", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject reply) {
                if (reply.optBoolean("ok")) show(reply);
            }
        });
    }

    void setActive(boolean active) {
        removeCallbacks(poll);
        if (active) post(poll);
    }
}
