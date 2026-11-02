package dev.mobiledock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** PC controls on the left, master volume and the per-app volume mixer on the right. */
final class ControlsPanel extends LinearLayout {
    private final DockClient client;
    private final LinearLayout mixerRows;

    ControlsPanel(Context c, DockClient client, final Runnable disconnect) {
        super(c);
        this.client = client;
        setOrientation(HORIZONTAL);

        // Left: PC controls, two rows of three. They always fit, so this side never scrolls.
        LinearLayout left = Ui.column(c);
        left.addView(section(c, "PC"));
        LinearLayout pc = Ui.row(c);
        pc.addView(control(c, Icon.Kind.LOCK, "Lock", "system", "lock", null), cell(c));
        pc.addView(control(c, Icon.Kind.MONITOR, "Screen off", "system", "display_off", null), cell(c));
        pc.addView(control(c, Icon.Kind.MOON, "Sleep", "system", "sleep", "Put the PC to sleep?"), cell(c));
        left.addView(pc, Ui.fillWidth(c, 0));
        LinearLayout pc2 = Ui.row(c);
        pc2.addView(control(c, Icon.Kind.APPS, "Desktop", "system", "desktop", null), cell(c));
        pc2.addView(control(c, Icon.Kind.CHART, "Task Manager", "system", "task_manager", null), cell(c));
        LinearLayout leave = tile(c, Icon.Kind.POWER, "Disconnect");
        leave.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnect.run();
            }
        });
        pc2.addView(leave, cell(c));
        left.addView(pc2, Ui.fillWidth(c, 0));
        addView(left, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));

        // Right: master volume, then one slider per app, like the Windows volume mixer. This side scrolls.
        LinearLayout right = Ui.column(c);
        LinearLayout head = Ui.row(c);
        head.addView(section(c, "Sound"));
        head.addView(Ui.spacer(c));
        head.addView(master(c, Icon.Kind.VOL_DOWN, "Volume down", "vol_down"));
        head.addView(master(c, Icon.Kind.MUTE, "Mute", "mute"));
        head.addView(master(c, Icon.Kind.VOL_UP, "Volume up", "vol_up"));
        ImageView refresh = Ui.flatIcon(c, Icon.Kind.REFRESH, 40);
        refresh.setContentDescription("Refresh the mixer");
        refresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        head.addView(refresh);
        right.addView(head, Ui.fillWidth(c, 0));
        ScrollView scroll = new ScrollView(c);
        mixerRows = Ui.column(c);
        scroll.addView(mixerRows);
        right.addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        LayoutParams rlp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        rlp.leftMargin = Ui.dp(c, 12);
        addView(right, rlp);
    }

    /** Master volume as small icon buttons in the Sound header. */
    private ImageView master(Context c, Icon.Kind kind, String label, final String action) {
        ImageView b = Ui.iconButton(c, kind, 40, Ui.SURFACE, Ui.SURFACE_HI, Ui.TEXT);
        b.setContentDescription(label);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                client.fire("media", DockClient.args("action", action));
            }
        });
        LayoutParams lp = new LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40));
        lp.rightMargin = Ui.dp(c, 6);
        b.setLayoutParams(lp);
        return b;
    }

    /** Reloads the apps that play sound. Called when the Controls page opens. */
    void load() {
        client.request("mixer.list", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                showMixer(r);
            }
        });
    }

    private void showMixer(JSONObject r) {
        Context c = getContext();
        mixerRows.removeAllViews();
        org.json.JSONArray apps = r.optJSONArray("apps");
        if (!r.optBoolean("ok") || apps == null || apps.length() == 0) {
            TextView none = Ui.text(c, r.optBoolean("ok") ? "No app is playing sound right now."
                    : r.optString("error", "The mixer is not available."), 13, Ui.MUTED);
            none.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), 0, Ui.dp(c, 8));
            mixerRows.addView(none);
            return;
        }
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a != null) mixerRows.addView(mixerRow(c, a), Ui.fillWidth(c, 3));
        }
    }

    private View mixerRow(Context c, JSONObject a) {
        final String id = a.optString("id");
        LinearLayout row = Ui.row(c);
        row.setBackground(Ui.rounded(c, Ui.SURFACE, 14));
        row.setPadding(Ui.dp(c, 14), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4));
        TextView name = Ui.text(c, a.optString("name"), 14, Ui.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView level = Ui.text(c, a.optInt("volume") + "%", 13, Ui.MUTED);
        level.setMinWidth(Ui.dp(c, 44));
        level.setGravity(Gravity.END);
        SeekBar bar = new SeekBar(c);
        bar.setMax(100);
        bar.setProgress(a.optInt("volume"));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            private long sentAt;

            @Override
            public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                level.setText(value + "%");
                long now = android.os.SystemClock.uptimeMillis();
                if (fromUser && now - sentAt > 150) {  // live, but not a flood of messages
                    sentAt = now;
                    client.fire("mixer.set", DockClient.args("id", id, "volume", value));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                client.fire("mixer.set", DockClient.args("id", id, "volume", s.getProgress()));
            }
        });
        row.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 2f));
        row.addView(level);
        final boolean[] muted = {a.optBoolean("muted")};
        final ImageView mute = Ui.flatIcon(c, muted[0] ? Icon.Kind.MUTE : Icon.Kind.VOL_UP, 40);
        mute.setContentDescription("Mute " + a.optString("name"));
        if (muted[0]) ((Icon) mute.getDrawable()).setColor(Ui.SIGNAL);
        mute.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                muted[0] = !muted[0];
                mute.setImageDrawable(new Icon(muted[0] ? Icon.Kind.MUTE : Icon.Kind.VOL_UP,
                        muted[0] ? Ui.SIGNAL : Ui.TEXT));
                client.fire("mixer.set", DockClient.args("id", id, "muted", muted[0]));
            }
        });
        row.addView(mute);
        return row;
    }

    private static TextView section(Context c, String label) {
        TextView t = Ui.text(c, label, 13, Ui.ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(Ui.dp(c, 6), Ui.dp(c, 12), 0, Ui.dp(c, 4));
        return t;
    }

    private static LinearLayout.LayoutParams cell(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 84 * Ui.TILE_SCALE), 1f);
        int m = Ui.dp(c, 5);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    static LinearLayout tile(Context c, Icon.Kind kind, String label) {
        LinearLayout t = Ui.column(c);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 18));
        t.setClickable(true);
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(new Icon(kind, Ui.TEXT));
        int s = Ui.dp(c, 28 * Ui.TILE_SCALE);
        t.addView(icon, new LinearLayout.LayoutParams(s, s));
        TextView l = Ui.text(c, label, 12, Ui.TEXT);
        l.setGravity(Gravity.CENTER);
        Ui.fitLabel(l, 12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 6);
        t.addView(l, lp);
        return t;
    }

    private LinearLayout control(final Context c, Icon.Kind kind, String label, final String cmd, final String action,
                                 final String confirm) {
        LinearLayout t = tile(c, kind, label);
        t.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (confirm == null) {
                    send(cmd, action);
                    return;
                }
                new AlertDialog.Builder(c).setTitle(confirm)
                        .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                send(cmd, action);
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        return t;
    }

    private void send(String cmd, String action) {
        client.request(cmd, DockClient.args("action", action), new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (!r.optBoolean("ok")) {
                    Toast.makeText(getContext(), r.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
