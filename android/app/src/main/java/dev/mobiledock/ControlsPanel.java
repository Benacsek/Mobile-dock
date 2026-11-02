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
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

/** PC controls: volume, lock, sleep, screen off, desktop, Task Manager, and leaving the dock. */
final class ControlsPanel extends ScrollView {
    private final DockClient client;

    ControlsPanel(Context c, DockClient client, final Runnable disconnect) {
        super(c);
        this.client = client;
        LinearLayout box = Ui.column(c);
        addView(box);

        box.addView(Ui.title(c, "Controls"), Ui.fillWidth(c, 4));
        box.addView(section(c, "Sound"));
        LinearLayout sound = Ui.row(c);
        sound.addView(control(c, Icon.Kind.VOL_DOWN, "Volume down", "media", "vol_down", null), cell(c));
        sound.addView(control(c, Icon.Kind.MUTE, "Mute", "media", "mute", null), cell(c));
        sound.addView(control(c, Icon.Kind.VOL_UP, "Volume up", "media", "vol_up", null), cell(c));
        box.addView(sound, Ui.fillWidth(c, 0));

        box.addView(section(c, "PC"));
        LinearLayout pc = Ui.row(c);
        pc.addView(control(c, Icon.Kind.LOCK, "Lock", "system", "lock", null), cell(c));
        pc.addView(control(c, Icon.Kind.MONITOR, "Screen off", "system", "display_off", null), cell(c));
        pc.addView(control(c, Icon.Kind.MOON, "Sleep", "system", "sleep", "Put the PC to sleep?"), cell(c));
        box.addView(pc, Ui.fillWidth(c, 0));
        LinearLayout pc2 = Ui.row(c);
        pc2.addView(control(c, Icon.Kind.APPS, "Show desktop", "system", "desktop", null), cell(c));
        pc2.addView(control(c, Icon.Kind.CHART, "Task Manager", "system", "task_manager", null), cell(c));
        LinearLayout leave = tile(c, Icon.Kind.POWER, "Disconnect");
        leave.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                disconnect.run();
            }
        });
        pc2.addView(leave, cell(c));
        box.addView(pc2, Ui.fillWidth(c, 0));
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
        l.setSingleLine(true);
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
