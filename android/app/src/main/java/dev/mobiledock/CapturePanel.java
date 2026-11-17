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

    CapturePanel(Context c, final DockClient client, final Actions actions, final Runnable back) {
        super(c);
        setVerticalScrollBarEnabled(false);
        LinearLayout page = Ui.column(c);
        page.setPadding(0, 0, Ui.dp(c, 6), Ui.dp(c, 12));
        addView(page);
        // Opened from Controls, so a back link sits above the title, like iOS.
        TextView up = Ui.text(c, "\u2039 Controls", 17, Ui.ACCENT);
        up.setPadding(Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 12), 0);
        up.setClickable(true);
        up.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                back.run();
            }
        });
        page.addView(up, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, Ui.dp(c, 32)));
        page.addView(Ui.title(c, "Capture"));
        LinearLayout box = Ui.row(c);
        box.setGravity(android.view.Gravity.TOP);
        page.addView(box);

        // Left: capture on the PC. Files save on the PC.
        LinearLayout left = Ui.column(c);
        left.addView(Ui.header(c, "On the PC"));
        LinearLayout pc = Ui.card(c);
        pc.addView(row(c, Icon.Kind.CAMERA, 0xFF8E8E93, "Screenshot", new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.screenshot();
            }
        }));
        pc.addView(Ui.hairline(c, 54));
        pc.addView(row(c, Icon.Kind.SCISSORS, 0xFF0088FF, "Snip a region", new OnClickListener() {
            @Override
            public void onClick(View v) {
                // Opens the snipping bar on Windows. The result goes to the PC clipboard.
                client.fire("input.key", DockClient.args("key", "s", "mods", new JSONArray().put("meta").put("shift")));
            }
        }));
        pc.addView(Ui.hairline(c, 54));
        recordTile = row(c, Icon.Kind.RECORD, 0xFFFF383C, "Record the screen", new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.toggleRecording();
            }
        });
        pc.addView(recordTile);
        left.addView(pc);
        TextView where = Ui.text(c, "Pictures and videos save in Pictures and Videos, in a Mobile Dock folder.", 13,
                Ui.MUTED);
        where.setPadding(Ui.dp(c, 16), Ui.dp(c, 6), Ui.dp(c, 16), 0);
        left.addView(where);
        box.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // Right: send from the phone, and clipboard text both ways.
        LinearLayout right = Ui.column(c);
        right.addView(Ui.header(c, "From this phone"));
        LinearLayout send = Ui.card(c);
        send.addView(row(c, Icon.Kind.PICTURE, 0xFF34C759, "Picture to clipboard", new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.pick(true);
            }
        }));
        send.addView(Ui.hairline(c, 54));
        send.addView(row(c, Icon.Kind.FILE, 0xFF0088FF, "File to Downloads", new OnClickListener() {
            @Override
            public void onClick(View v) {
                actions.pick(false);
            }
        }));
        right.addView(send);
        right.addView(Ui.header(c, "Clipboard text"));
        LinearLayout clip = Ui.card(c);
        clip.addView(row(c, Icon.Kind.CLIPBOARD, 0xFFFF8D28, "Phone to PC", new OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToPc(client);
            }
        }));
        clip.addView(Ui.hairline(c, 54));
        clip.addView(row(c, Icon.Kind.CLIPBOARD, 0xFFFF8D28, "PC to phone", new OnClickListener() {
            @Override
            public void onClick(View v) {
                copyFromPc(client);
            }
        }));
        right.addView(clip);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        rlp.leftMargin = Ui.dp(c, 14);
        box.addView(right, rlp);
    }

    /** A grouped list row. Child 0 is the icon tile and child 1 the label. */
    private static LinearLayout row(Context c, Icon.Kind kind, int color, String label, OnClickListener click) {
        LinearLayout r = Ui.listRow(c, kind, color, label, null);
        TextView l = (TextView) r.getChildAt(1);
        l.setMaxLines(2);  // a small phone wraps "Picture to clipboard" instead of cutting it
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        Ui.pressableRow(c, r, click);
        return r;
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
                new Icon(on ? Icon.Kind.STOP : Icon.Kind.RECORD, 0xFFFFFFFF, true));
        TextView label = (TextView) recordTile.getChildAt(1);
        label.setText(on ? "Stop recording" : "Record the screen");
        label.setTextColor(on ? Ui.DANGER : Ui.TEXT);
    }
}
