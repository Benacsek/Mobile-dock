package dev.mobiledock;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

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
        LinearLayout box = Ui.row(c);
        box.setGravity(android.view.Gravity.TOP);
        addView(box);

        // Left: capture on the PC. Files save on the PC.
        LinearLayout left = Ui.column(c);
        left.addView(section(c, "Capture on the PC"));
        left.addView(wide(c, Icon.Kind.CAMERA, "Screenshot", new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.screenshot();
            }
        }), cell(c));
        left.addView(wide(c, Icon.Kind.SCISSORS, "Snip a region", new OnClickListener() {
            @Override
            public void onClick(View v) {
                // Opens the snipping bar on Windows. The result goes to the PC clipboard.
                client.fire("input.key", DockClient.args("key", "s", "mods", new JSONArray().put("meta").put("shift")));
            }
        }), cell(c));
        recordTile = wide(c, Icon.Kind.RECORD, "Record", new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.toggleRecording();
            }
        });
        left.addView(recordTile, cell(c));
        box.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Right: send from the phone, two by two.
        LinearLayout right = Ui.column(c);
        right.addView(section(c, "Send from this phone"));
        LinearLayout row1 = Ui.row(c);
        row1.addView(wide(c, Icon.Kind.PICTURE, "Picture to clipboard", new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.pick(true);
            }
        }), half(c));
        row1.addView(wide(c, Icon.Kind.FILE, "File to Downloads", new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.pick(false);
            }
        }), half(c));
        right.addView(row1);
        right.addView(section(c, "Clipboard text"));
        LinearLayout row2 = Ui.row(c);
        row2.addView(wide(c, Icon.Kind.CLIPBOARD, "Phone to PC", new OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToPc(client);
            }
        }), half(c));
        row2.addView(wide(c, Icon.Kind.CLIPBOARD, "PC to phone", new OnClickListener() {
            @Override
            public void onClick(View v) {
                copyFromPc(client);
            }
        }), half(c));
        right.addView(row2);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f);
        rlp.leftMargin = Ui.dp(c, 12);
        box.addView(right, rlp);
    }

    private static TextView section(Context c, String label) {
        TextView t = Ui.text(c, label, 13, Ui.ACCENT);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(Ui.dp(c, 6), Ui.dp(c, 8), 0, Ui.dp(c, 4));
        return t;
    }

    /** A wide tile: icon on the left, label beside it. Child 0 is the icon and child 1 the label. */
    private static LinearLayout wide(Context c, Icon.Kind kind, String label, OnClickListener click) {
        LinearLayout t = Ui.row(c);
        t.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 14));
        t.setClickable(true);
        t.setPadding(Ui.dp(c, 14), 0, Ui.dp(c, 10), 0);
        ImageView icon = new ImageView(c);
        icon.setImageDrawable(new Icon(kind, Ui.TEXT));
        int s = Ui.dp(c, 24);
        t.addView(icon, new LinearLayout.LayoutParams(s, s));
        TextView l = Ui.text(c, label, 14, Ui.TEXT);
        l.setSingleLine(true);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        l.setPadding(Ui.dp(c, 12), 0, 0, 0);
        t.addView(l);
        t.setOnClickListener(click);
        return t;
    }

    private static LinearLayout.LayoutParams cell(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(c, 52 * Ui.TILE_SCALE));
        int m = Ui.dp(c, 4);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private static LinearLayout.LayoutParams half(Context c) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 52 * Ui.TILE_SCALE), 1f);
        int m = Ui.dp(c, 4);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    @SuppressWarnings("deprecation")
    private android.content.ClipboardManager clipboard() {
        return (android.content.ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
    }

    /** Copies the phone's clipboard text onto the PC clipboard. */
    private void copyToPc(DockClient client) {
        android.content.ClipData data = clipboard().getPrimaryClip();
        CharSequence text = data == null || data.getItemCount() == 0 ? null
                : data.getItemAt(0).coerceToText(getContext());
        if (text == null || text.length() == 0) {
            toast("The phone clipboard has no text");
            return;
        }
        client.request("clipboard.set_text", DockClient.args("text", text.toString()), new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                toast(r.optBoolean("ok") ? "Copied to the PC. Paste with Ctrl+V." : r.optString("error", "Failed"));
            }
        });
    }

    /** Puts the PC's clipboard text on the phone clipboard. */
    private void copyFromPc(DockClient client) {
        client.request("clipboard.get_text", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (!r.optBoolean("ok")) {
                    toast(r.optString("error", "Failed"));
                    return;
                }
                String text = r.optString("text", "");
                if (text.length() == 0) {
                    toast("The PC clipboard has no text");
                    return;
                }
                clipboard().setPrimaryClip(android.content.ClipData.newPlainText("From PC", text));
                toast("Copied from the PC");
            }
        });
    }

    private void toast(String s) {
        android.widget.Toast.makeText(getContext(), s, android.widget.Toast.LENGTH_SHORT).show();
    }

    void setRecording(boolean on) {
        ((ImageView) recordTile.getChildAt(0)).setImageDrawable(
                new Icon(on ? Icon.Kind.STOP : Icon.Kind.RECORD, on ? Ui.SIGNAL : Ui.TEXT));
        TextView label = (TextView) recordTile.getChildAt(1);
        label.setText(on ? "Stop recording" : "Record");
        label.setTextColor(on ? Ui.SIGNAL : Ui.TEXT);
    }
}
