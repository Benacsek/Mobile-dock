package dev.mobiledock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** PC actions and Capture as tiles on the left. The PC volume and one line per app on the right. */
final class ControlsPanel extends ScrollView {
    private final DockClient client;
    private final VolumeRow master;
    private final View masterLine;
    private final LinearLayout mixerRows;
    private final java.util.Map<String, VolumeRow> rows = new java.util.HashMap<String, VolumeRow>();
    private String appIds = "";

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            load();
            postDelayed(this, 5000);  // apps that start or stop playing show up without a refresh button
        }
    };

    ControlsPanel(Context c, DockClient client, final Runnable openCapture) {
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

        // Six tiles in two rows fit a landscape phone without scrolling. Six list rows did not.
        LinearLayout left = Ui.column(c);
        left.addView(Ui.header(c, "This PC"));
        LinearLayout pc = Ui.card(c);
        pc.setPadding(Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6), Ui.dp(c, 6));
        View[] tiles = {
            control(c, Icon.Kind.LOCK, 0xFF8E8E93, "Lock", "system", "lock", null),
            control(c, Icon.Kind.MONITOR, 0xFF6155F5, "Screen off", "system", "display_off", null),
            control(c, Icon.Kind.MOON, 0xFF6155F5, "Sleep", "system", "sleep", "Put the PC to sleep?"),
            control(c, Icon.Kind.APPS, 0xFF0088FF, "Show desktop", "system", "desktop", null),
            control(c, Icon.Kind.CHART, 0xFF34C759, "Task Manager", "system", "task_manager", null),
            tile(c, Icon.Kind.CAMERA, 0xFFFF8D28, "Capture", new OnClickListener() {
                @Override
                public void onClick(View v) {
                    openCapture.run();
                }
            }),
        };
        for (int r = 0; r < 2; r++) {
            LinearLayout line = Ui.row(c);
            for (int i = 0; i < 3; i++) {
                line.addView(tiles[r * 3 + i], new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            }
            pc.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        left.addView(pc);
        TextView note = Ui.text(c, "There is no shut down button, on purpose.", 13, Ui.MUTED);
        note.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), 0);
        left.addView(note);
        columns.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // The PC volume on top, then one line per app. The page reloads them while it is open.
        LinearLayout right = Ui.column(c);
        right.addView(Ui.header(c, "Sound"));
        LinearLayout sound = Ui.card(c);
        master = new VolumeRow(c, "PC volume", new VolumeRow.Sink() {
            @Override
            public void send(JSONObject change) {
                ControlsPanel.this.client.fire("volume.set", change);
            }
        });
        master.setVisibility(GONE);  // until the PC answers volume.get: older PC apps do not
        sound.addView(master);
        masterLine = Ui.hairline(c, 16);
        masterLine.setVisibility(GONE);
        sound.addView(masterLine);
        mixerRows = Ui.column(c);
        sound.addView(mixerRows);
        right.addView(sound);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = Ui.dp(c, 14);
        columns.addView(right, rlp);
    }

    /** Starts or stops reloading the volumes. On when the Controls page shows. */
    void setActive(boolean on) {
        removeCallbacks(poll);
        if (on) post(poll);
    }

    private void load() {
        client.request("volume.get", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                boolean ok = r.optBoolean("ok");
                master.setVisibility(ok ? VISIBLE : GONE);
                masterLine.setVisibility(ok ? VISIBLE : GONE);
                if (ok) master.show(r.optInt("volume"), r.optBoolean("muted"));
            }
        });
        client.request("mixer.list", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                showMixer(r);
            }
        });
    }

    /** Rebuilds the rows only when the apps change, so a reload never jumps a slider under your finger. */
    private void showMixer(JSONObject r) {
        Context c = getContext();
        org.json.JSONArray apps = r.optJSONArray("apps");
        if (!r.optBoolean("ok") || apps == null || apps.length() == 0) {
            appIds = "";
            rows.clear();
            mixerRows.removeAllViews();
            TextView none = Ui.text(c, r.optBoolean("ok") ? "No app is playing sound right now."
                    : r.optString("error", "The mixer is not available."), 13, Ui.MUTED);
            none.setPadding(Ui.dp(c, 16), Ui.dp(c, 14), Ui.dp(c, 16), Ui.dp(c, 14));
            mixerRows.addView(none);
            return;
        }
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            if (a != null) ids.append(a.optString("id")).append('\n');
        }
        if (!ids.toString().equals(appIds)) {
            appIds = ids.toString();
            rows.clear();
            mixerRows.removeAllViews();
            for (int i = 0; i < apps.length(); i++) {
                JSONObject a = apps.optJSONObject(i);
                if (a == null) continue;
                if (mixerRows.getChildCount() > 0) mixerRows.addView(Ui.hairline(c, 16));
                final String id = a.optString("id");
                VolumeRow row = new VolumeRow(c, a.optString("name"), new VolumeRow.Sink() {
                    @Override
                    public void send(JSONObject change) {
                        try {
                            change.put("id", id);
                        } catch (org.json.JSONException e) {
                            return;
                        }
                        client.fire("mixer.set", change);
                    }
                });
                rows.put(id, row);
                mixerRows.addView(row);
            }
        }
        for (int i = 0; i < apps.length(); i++) {
            JSONObject a = apps.optJSONObject(i);
            VolumeRow row = a == null ? null : rows.get(a.optString("id"));
            if (row != null) row.show(a.optInt("volume"), a.optBoolean("muted"));
        }
    }

    /** A square tile: a colored icon over its name, like Control Center. A few ask before they act. */
    private LinearLayout control(final Context c, Icon.Kind kind, int color, String label, final String cmd,
                                 final String action, final String confirm) {
        return tile(c, kind, color, label, new OnClickListener() {
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
    }

    private static LinearLayout tile(Context c, Icon.Kind kind, int color, String label, OnClickListener click) {
        LinearLayout t = Ui.column(c);
        t.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP);
        t.setMinimumHeight(Ui.dp(c, 84));
        t.setPadding(Ui.dp(c, 4), Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 8));
        t.setBackground(Ui.pressable(c, 0x00000000, Ui.PRESSED, 14));
        t.setClickable(true);
        t.setOnClickListener(click);
        t.setContentDescription(label);
        View icon = Ui.iconTile(c, kind, color);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(Ui.dp(c, 36), Ui.dp(c, 36));
        ilp.bottomMargin = Ui.dp(c, 6);
        t.addView(icon, ilp);
        TextView name = Ui.text(c, label, 13, Ui.TEXT);
        Ui.fitLabel(name, 13);
        name.setSingleLine(false);
        name.setMaxLines(2);  // "Show desktop" and "Task Manager" wrap instead of ending in "…"
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setGravity(Gravity.CENTER);
        t.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
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
