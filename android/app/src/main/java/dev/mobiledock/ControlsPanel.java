package dev.mobiledock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** iOS grouped lists: PC controls and Capture on the left, the volume and per-app mixer on the right. */
final class ControlsPanel extends ScrollView {
    private final DockClient client;
    private final LinearLayout mixerRows;

    ControlsPanel(Context c, DockClient client, final Runnable disconnect, final Runnable openCapture) {
        super(c);
        this.client = client;
        setVerticalScrollBarEnabled(false);
        LinearLayout page = Ui.column(c);
        page.setPadding(0, 0, Ui.dp(c, 6), Ui.dp(c, 12));
        addView(page);
        page.addView(Ui.title(c, "Controls"));
        LinearLayout columns = Ui.row(c);
        columns.setGravity(Gravity.TOP);
        page.addView(columns);

        LinearLayout left = Ui.column(c);
        left.addView(Ui.header(c, "This PC"));
        LinearLayout pc = Ui.card(c);
        pc.addView(control(c, Icon.Kind.LOCK, 0xFF8E8E93, "Lock", "system", "lock", null));
        pc.addView(Ui.hairline(c, 54));
        pc.addView(control(c, Icon.Kind.MONITOR, 0xFF6155F5, "Screen off", "system", "display_off", null));
        pc.addView(Ui.hairline(c, 54));
        pc.addView(control(c, Icon.Kind.MOON, 0xFF6155F5, "Sleep", "system", "sleep", "Put the PC to sleep?"));
        pc.addView(Ui.hairline(c, 54));
        pc.addView(control(c, Icon.Kind.APPS, 0xFF0088FF, "Show desktop", "system", "desktop", null));
        pc.addView(Ui.hairline(c, 54));
        pc.addView(control(c, Icon.Kind.CHART, 0xFF34C759, "Task Manager", "system", "task_manager", null));
        pc.addView(Ui.hairline(c, 54));
        LinearLayout capture = Ui.listRow(c, Icon.Kind.CAMERA, 0xFFFF8D28, "Capture", Ui.chevron(c));
        Ui.pressableRow(c, capture, new OnClickListener() {
            @Override
            public void onClick(View v) {
                openCapture.run();
            }
        });
        pc.addView(capture);
        left.addView(pc);
        TextView note = Ui.text(c, "There is no shut down button, on purpose.", 13, Ui.MUTED);
        note.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), 0);
        left.addView(note);
        LinearLayout leaveCard = Ui.card(c);
        LinearLayout leave = Ui.listRow(c, null, 0, "Disconnect", null);
        ((TextView) leave.getChildAt(0)).setTextColor(Ui.DANGER);
        Ui.pressableRow(c, leave, new OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnect.run();
            }
        });
        leaveCard.addView(leave);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.topMargin = Ui.dp(c, 18);
        left.addView(leaveCard, llp);
        columns.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = Ui.column(c);
        right.addView(Ui.header(c, "Sound"));
        // The PC volume and refresh sit on their own line, so they never run off a narrow column.
        LinearLayout head = Ui.row(c);
        head.setPadding(Ui.dp(c, 8), 0, 0, Ui.dp(c, 8));
        head.addView(master(c, Icon.Kind.VOL_DOWN, "Volume down", "vol_down"));
        head.addView(master(c, Icon.Kind.MUTE, "Mute", "mute"));
        head.addView(master(c, Icon.Kind.VOL_UP, "Volume up", "vol_up"));
        ImageView refresh = Ui.iconButton(c, Icon.Kind.REFRESH, 36, 0x00000000, Ui.SELECTED, Ui.ACCENT);
        refresh.setContentDescription("Refresh the mixer");
        refresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        head.addView(refresh);
        right.addView(head, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mixerRows = Ui.card(c);
        right.addView(mixerRows);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = Ui.dp(c, 14);
        columns.addView(right, rlp);
    }

    /** Master volume as small icon buttons in the Sound header. */
    private ImageView master(Context c, Icon.Kind kind, String label, final String action) {
        ImageView b = Ui.iconButton(c, kind, 36, Ui.SURFACE_HI, Ui.PRESSED, Ui.ACCENT);
        b.setContentDescription(label);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                client.fire("media", DockClient.args("action", action));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36));
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
            none.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14));
            mixerRows.addView(none);
            return;
        }
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a == null) continue;
            if (mixerRows.getChildCount() > 0) mixerRows.addView(Ui.hairline(c, 16));
            mixerRows.addView(mixerRow(c, a));
        }
    }

    private View mixerRow(Context c, JSONObject a) {
        final String id = a.optString("id");
        // Name and level on top, the slider and mute below: room for the slider on a narrow column.
        LinearLayout row = Ui.column(c);
        row.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 2));
        LinearLayout top = Ui.row(c);
        TextView name = Ui.text(c, a.optString("name"), 15, Ui.TEXT);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(top);
        LinearLayout controlsRow = Ui.row(c);
        row.addView(controlsRow);
        final TextView level = Ui.text(c, a.optInt("volume") + "%", 15, Ui.MUTED);
        level.setMinWidth(Ui.dp(c, 44));
        level.setGravity(Gravity.END);
        SeekBar bar = new SeekBar(c);
        Kit.slider(c, bar);
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
        controlsRow.addView(bar, new LinearLayout.LayoutParams(0, Ui.dp(c, 44), 1f));
        top.addView(level);
        final boolean[] muted = {a.optBoolean("muted")};
        final ImageView mute = Ui.iconButton(c, muted[0] ? Icon.Kind.MUTE : Icon.Kind.VOL_UP, 40, 0x00000000,
                Ui.SELECTED, Ui.ACCENT);
        mute.setContentDescription("Mute " + a.optString("name"));
        if (muted[0]) ((Icon) mute.getDrawable()).setColor(Ui.SIGNAL);
        mute.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                muted[0] = !muted[0];
                mute.setImageDrawable(new Icon(muted[0] ? Icon.Kind.MUTE : Icon.Kind.VOL_UP,
                        muted[0] ? Ui.SIGNAL : Ui.ACCENT));
                client.fire("mixer.set", DockClient.args("id", id, "muted", muted[0]));
            }
        });
        controlsRow.addView(mute);
        return row;
    }

    private LinearLayout control(final Context c, Icon.Kind kind, int color, String label, final String cmd,
                                 final String action, final String confirm) {
        LinearLayout t = Ui.listRow(c, kind, color, label, null);
        Ui.pressableRow(c, t, new OnClickListener() {
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
