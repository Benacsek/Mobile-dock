package dev.mobiledock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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
    private final Button padButton;
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
        header.addView(Ui.spacer(c));
        padButton = Ui.button(c, "Pad");
        Icon chevron = new Icon(Icon.Kind.CHEVRON, Ui.ACCENT);  // the pill opens a list
        chevron.setBounds(0, 0, Ui.dp(c, 12), Ui.dp(c, 12));
        padButton.setCompoundDrawables(null, null, chevron, null);
        padButton.setCompoundDrawablePadding(Ui.dp(c, 6));
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

    /** One list: follow the app in front, or stay on one pad. The current choice has a mark. */
    private void choosePad() {
        if (pads.length() == 0) return;
        final String[] names = new String[pads.length() + 1];
        String front = app.replace(".exe", "");
        names[0] = "Follow the app in front" + (front.length() > 0 ? " (" + front + ")" : "");
        int chosen = 0;
        for (int i = 0; i < pads.length(); i++) {
            names[i + 1] = pads.optJSONObject(i).optString("name");
            if (!following && pads.optJSONObject(i).optString("id").equals(padId)) chosen = i + 1;
        }
        new AlertDialog.Builder(getContext()).setTitle("Show pad")
                .setSingleChoiceItems(names, chosen, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        d.dismiss();
                        following = which == 0;  // a pad you pick stays until you pick Follow again
                        if (following) {
                            load();
                            return;
                        }
                        padId = pads.optJSONObject(which - 1).optString("id");
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
        padButton.setText((following ? "Auto: " : "") + pad.optString("name"));
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
            TextView tile = Ui.strong(c, tiles.optJSONObject(i).optString("label"), 15, Ui.TEXT);
            tile.setGravity(Gravity.CENTER);
            tile.setPadding(Ui.dp(c, 6), 0, Ui.dp(c, 6), 0);
            tile.setMaxLines(2);
            tile.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.PRESSED, 16));
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
