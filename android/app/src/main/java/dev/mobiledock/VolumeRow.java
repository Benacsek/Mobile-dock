package dev.mobiledock;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import org.json.JSONObject;

/**
 * One volume on one line: a slider and a mute button. With a name it reads "Name, slider, 50%, mute",
 * as in the Controls mixer. Without one it is "mute, slider", as on Now Playing.
 */
final class VolumeRow extends LinearLayout {
    /** Sends {"volume": n} or {"muted": b}. The caller adds the command and, for an app, its id. */
    interface Sink {
        void send(JSONObject change);
    }

    private final Sink sink;
    private final SeekBar bar;
    private final ImageView mute;
    private final TextView level;
    private boolean muted, dragging;
    private long sentAt;

    VolumeRow(Context c, String name, final Sink sink) {
        super(c);
        this.sink = sink;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);
        mute = Ui.iconButton(c, Icon.Kind.VOL_UP, 40, 0x00000000, Ui.SELECTED, Ui.ACCENT);
        mute.setContentDescription("Mute " + (name == null ? "the PC" : name));
        mute.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showMuted(!muted);
                sink.send(DockClient.args("muted", muted));
            }
        });
        bar = new SeekBar(c);
        Kit.slider(c, bar);
        bar.setMax(100);
        bar.setContentDescription((name == null ? "PC" : name) + " volume");
        level = name == null ? null : Ui.text(c, "", 15, Ui.MUTED);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (level != null) level.setText(value + "%");
                long now = android.os.SystemClock.uptimeMillis();
                if (fromUser && now - sentAt > 150) {  // live, but not a flood of messages
                    sentAt = now;
                    sink.send(DockClient.args("volume", value));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                dragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                sink.send(DockClient.args("volume", s.getProgress()));
            }
        });
        LayoutParams blp = new LayoutParams(0, Ui.dp(c, 44), 1f);
        if (name == null) {
            addView(mute);
            addView(bar, blp);
            return;
        }
        setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 4), 0);
        TextView label = Ui.text(c, name, 15, Ui.TEXT);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        // At most 30 percent of the row, so the slider keeps its room. Long names end in "…".
        addView(label, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 0.43f));
        addView(bar, blp);
        level.setMinWidth(Ui.dp(c, 44));
        level.setGravity(Gravity.END);
        addView(level);
        addView(mute);
    }

    /** Shows what the PC reports, unless your finger is on the slider. */
    void show(int volume, boolean isMuted) {
        if (dragging) return;
        bar.setProgress(volume);
        if (level != null) level.setText(volume + "%");
        showMuted(isMuted);
    }

    private void showMuted(boolean on) {
        muted = on;
        mute.setImageDrawable(new Icon(on ? Icon.Kind.MUTE : Icon.Kind.VOL_UP, on ? Ui.SIGNAL : Ui.ACCENT));
    }
}
