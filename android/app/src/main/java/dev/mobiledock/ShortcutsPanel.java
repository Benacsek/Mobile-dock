package dev.mobiledock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Shortcut tiles for the app in front on the PC. With Follow on, the page switches pads by
 * itself when you switch apps. Edit the pads in the PC window.
 */
final class ShortcutsPanel extends LinearLayout {
    private final DockClient client;
    private final TextView appLabel;
    private final Button padButton;
    private final ImageView follow;
    private final LinearLayout grid;
    private JSONArray pads = new JSONArray();
    private String padId = "";
    private String app = "";
    private boolean following = true;
    private int columns = 4;

    ShortcutsPanel(Context c, DockClient client) {
        super(c);
        this.client = client;
        setOrientation(VERTICAL);

        LinearLayout header = Ui.row(c);
        TextView title = Ui.title(c, "Shortcuts");
        header.addView(title);
        appLabel = Ui.text(c, "", 13, Ui.MUTED);
        appLabel.setPadding(Ui.dp(c, 12), 0, 0, 0);
        appLabel.setSingleLine(true);
        header.addView(appLabel, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        follow = Ui.iconButton(c, Icon.Kind.FOLLOW, 40, 0x00000000, 0x33FFFFFF, Ui.TEXT);
        follow.setSelected(true);
        follow.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                following = !following;
                follow.setSelected(following);
                Toast.makeText(getContext(), following ? "Follows the app in front" : "Stays on this pad",
                        Toast.LENGTH_SHORT).show();
                if (following) load();
            }
        });
        header.addView(follow);
        padButton = Ui.button(c, "Pad");
        padButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                choosePad();
            }
        });
        header.addView(padButton, Ui.wrap(c, 4));
        addView(header, Ui.fillWidth(c, 4));

        ScrollView scroll = new ScrollView(c);
        grid = Ui.column(c);
        scroll.addView(grid);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int cols = Math.max(3, w / Ui.dp(getContext(), 124));
        if (cols != columns) {
            columns = cols;
            post(new Runnable() {
                @Override
                public void run() {
                    rebuild();
                }
            });
        }
    }

    void load() {
        client.request("pads.list", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (!r.optBoolean("ok")) return;
                JSONArray list = r.optJSONArray("pads");
                pads = list == null ? new JSONArray() : list;
                JSONObject ctx = r.optJSONObject("context");
                if (ctx != null) onContext(ctx);
                else rebuild();
            }
        });
    }

    /** Called when the PC reports a new app in front. */
    void onContext(JSONObject ctx) {
        app = ctx.optString("app");
        appLabel.setText(app.length() == 0 ? "" : "In front: " + app.replace(".exe", ""));
        if (following || padId.length() == 0) padId = ctx.optString("pad", padId);
        rebuild();
    }

    private JSONObject currentPad() {
        for (int i = 0; i < pads.length(); i++) {
            JSONObject p = pads.optJSONObject(i);
            if (p != null && p.optString("id").equals(padId)) return p;
        }
        return pads.length() > 0 ? pads.optJSONObject(0) : null;
    }

    private void choosePad() {
        if (pads.length() == 0) return;
        final String[] names = new String[pads.length()];
        for (int i = 0; i < pads.length(); i++) names[i] = pads.optJSONObject(i).optString("name");
        new AlertDialog.Builder(getContext()).setTitle("Show pad")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        padId = pads.optJSONObject(which).optString("id");
                        following = false;  // you picked one, so stay on it
                        follow.setSelected(false);
                        rebuild();
                    }
                }).show();
    }

    private void rebuild() {
        Context c = getContext();
        grid.removeAllViews();
        final JSONObject pad = currentPad();
        if (pad == null) {
            padButton.setText("Pad");
            return;
        }
        padId = pad.optString("id");
        padButton.setText(pad.optString("name"));
        JSONArray tiles = pad.optJSONArray("tiles");
        if (tiles == null || tiles.length() == 0) {
            TextView none = Ui.text(c, "This pad has no tiles yet. Add some in the PC window under Shortcut pads.", 14, Ui.MUTED);
            none.setGravity(Gravity.CENTER);
            none.setPadding(0, Ui.dp(c, 40), 0, 0);
            grid.addView(none, Ui.fillWidth(c, 0));
            return;
        }
        LinearLayout row = null;
        for (int i = 0; i < tiles.length(); i++) {
            if (i % columns == 0) {
                row = Ui.row(c);
                grid.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 64)));
            }
            final int index = i;
            TextView tile = Ui.text(c, tiles.optJSONObject(i).optString("label"), 13, Ui.TEXT);
            tile.setTypeface(Typeface.DEFAULT_BOLD);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
            tile.setMaxLines(2);
            tile.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 14));
            tile.setClickable(true);
            tile.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    run(pad.optString("id"), index);
                }
            });
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            int m = Ui.dp(c, 4);
            lp.setMargins(m, m, m, m);
            row.addView(tile, lp);
        }
        for (int k = tiles.length() % columns; k != 0 && k < columns; k++) {
            LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            row.addView(new View(c), lp);
        }
    }

    private void run(String pad, int index) {
        client.request("pad.run", DockClient.args("pad", pad, "index", index), new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (!r.optBoolean("ok")) {
                    Toast.makeText(getContext(), r.optString("error", "Failed"), Toast.LENGTH_SHORT).show();
                    load();
                }
            }
        });
    }
}
