package dev.mobiledock;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

/** Screenshots, region snips, screen recording, and sending pictures or files to the PC. */
final class CapturePanel extends ScrollView {
    interface Actions {
        void screenshot();

        void toggleRecording();

        void pick(boolean picture);
    }

    private final LinearLayout recordTile;

    CapturePanel(Context c, final DockClient client, final Actions actions) {
        super(c);
        LinearLayout box = Ui.column(c);
        addView(box);
        box.addView(Ui.title(c, "Capture"), Ui.fillWidth(c, 4));
        box.addView(Ui.text(c, "Screenshots and recordings are saved on the PC.", 13, Ui.MUTED), Ui.fillWidth(c, 4));

        LinearLayout row = Ui.row(c);
        LinearLayout shot = ControlsPanel.tile(c, Icon.Kind.CAMERA, "Screenshot");
        shot.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.screenshot();
            }
        });
        row.addView(shot, cell(c));
        LinearLayout snip = ControlsPanel.tile(c, Icon.Kind.SCISSORS, "Snip a region");
        snip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // Opens the snipping bar on Windows. The result goes to the PC clipboard.
                client.fire("input.key", DockClient.args("key", "s", "mods", new JSONArray().put("meta").put("shift")));
            }
        });
        row.addView(snip, cell(c));
        recordTile = ControlsPanel.tile(c, Icon.Kind.RECORD, "Record");
        recordTile.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.toggleRecording();
            }
        });
        row.addView(recordTile, cell(c));
        box.addView(row, Ui.fillWidth(c, 0));

        TextView send = Ui.text(c, "Send from this phone", 13, Ui.ACCENT);
        send.setPadding(Ui.dp(c, 6), Ui.dp(c, 12), 0, Ui.dp(c, 4));
        box.addView(send);
        LinearLayout row2 = Ui.row(c);
        LinearLayout pic = ControlsPanel.tile(c, Icon.Kind.PICTURE, "Picture to clipboard");
        pic.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.pick(true);
            }
        });
        row2.addView(pic, cell(c));
        LinearLayout file = ControlsPanel.tile(c, Icon.Kind.FILE, "File to Downloads");
        file.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.pick(false);
            }
        });
        row2.addView(file, cell(c));
        box.addView(row2, Ui.fillWidth(c, 0));
    }

    private static LinearLayout.LayoutParams cell(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 92 * Ui.TILE_SCALE), 1f);
        int m = Ui.dp(c, 5);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    void setRecording(boolean on) {
        ((ImageView) recordTile.getChildAt(0)).setImageDrawable(
                new Icon(on ? Icon.Kind.STOP : Icon.Kind.RECORD, on ? Ui.SIGNAL : Ui.TEXT));
        TextView label = (TextView) recordTile.getChildAt(1);
        label.setText(on ? "Stop recording" : "Record");
        label.setTextColor(on ? Ui.SIGNAL : Ui.TEXT);
    }
}
