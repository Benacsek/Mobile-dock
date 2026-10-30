package dev.mobiledock;

import android.content.Context;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/** Keyboard. What you type in the box goes straight to the PC. Modifier keys stick for one key press. */
final class KeysPanel extends LinearLayout {
    private final DockClient client;
    private final EditText input;
    private final List<Button> modButtons = new ArrayList<Button>();
    private final List<String> mods = new ArrayList<String>();
    private boolean ignore;

    KeysPanel(Context c, DockClient client) {
        super(c);
        this.client = client;
        setOrientation(VERTICAL);

        LinearLayout top = Ui.row(c);
        input = new EditText(c);
        input.setHint("Type here. Text is sent as you type.");
        input.setTextColor(Ui.TEXT);
        input.setHintTextColor(Ui.MUTED);
        input.setSingleLine(true);
        // Visible password type turns off autocorrect, so each key press maps to one PC key.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        input.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN
                | EditorInfo.IME_ACTION_SEND);
        input.setBackground(Ui.rounded(c, Ui.SURFACE, 10));
        input.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (ignore) return;
                for (int i = 0; i < before; i++) key("backspace");
                if (count > 0) typed(s.subSequence(start, start + count).toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!ignore && s.length() > 200) clearBox();
            }
        });
        input.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                key("enter");
                clearBox();
                return true;
            }
        });
        input.setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, KeyEvent event) {
                // Backspace on an empty box still deletes on the PC.
                if (keyCode == KeyEvent.KEYCODE_DEL && event.getAction() == KeyEvent.ACTION_DOWN
                        && input.getText().length() == 0) {
                    key("backspace");
                    return true;
                }
                return false;
            }
        });
        top.addView(input, new LayoutParams(0, Ui.dp(c, 52), 1f));
        Button show = Ui.accentButton(c, "Keyboard");
        show.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                input.requestFocus();
                InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            }
        });
        top.addView(show, Ui.wrap(c, 4));
        addView(top, Ui.fillWidth(c, 4));

        addView(keyRow(c, new String[][] {
            {"Esc", "esc"}, {"Tab", "tab"}, {"Enter", "enter"}, {"Backspace", "backspace"}, {"Delete", "delete"},
            {"Home", "home"}, {"End", "end"}, {"Page up", "pgup"}, {"Page down", "pgdn"},
        }));
        LinearLayout modsRow = Ui.row(c);
        for (String[] m : new String[][] {{"Ctrl", "ctrl"}, {"Alt", "alt"}, {"Shift", "shift"}, {"Win / Super", "meta"}}) {
            modsRow.addView(modButton(c, m[0], m[1]), Ui.wrap(c, 3));
        }
        modsRow.addView(Ui.spacer(c));
        for (String[] a : new String[][] {{"Left", "left"}, {"Up", "up"}, {"Down", "down"}, {"Right", "right"}}) {
            modsRow.addView(plainKey(c, a[0], a[1]), Ui.wrap(c, 3));
        }
        addView(modsRow, Ui.fillWidth(c, 2));
        addView(shortcutRow(c));
        String[][] fkeys = new String[12][];
        for (int i = 0; i < 12; i++) fkeys[i] = new String[] {"F" + (i + 1), "f" + (i + 1)};
        addView(keyRow(c, fkeys));
    }

    private void clearBox() {
        ignore = true;
        input.setText("");
        ignore = false;
    }

    private void typed(String text) {
        if (mods.isEmpty()) {
            client.fire("input.text", DockClient.args("text", text));
            return;
        }
        // With a modifier held, letters and digits become shortcuts such as Ctrl+C.
        for (int i = 0; i < text.length(); i++) {
            char ch = Character.toLowerCase(text.charAt(i));
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) key(String.valueOf(ch));
            else if (ch == ' ') key("space");
        }
    }

    private void key(String name) {
        JSONArray m = new JSONArray();
        for (String s : mods) m.put(s);
        client.fire("input.key", DockClient.args("key", name, "mods", m));
        if (!mods.isEmpty()) {
            mods.clear();
            for (Button b : modButtons) b.setSelected(false);
        }
    }

    private Button plainKey(Context c, String label, final String name) {
        Button b = Ui.button(c, label);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                key(name);
            }
        });
        return b;
    }

    private Button modButton(Context c, String label, final String name) {
        final Button b = Ui.button(c, label);
        b.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mods.contains(name)) mods.remove(name);
                else mods.add(name);
                b.setSelected(mods.contains(name));
            }
        });
        modButtons.add(b);
        return b;
    }

    private View keyRow(Context c, String[][] keys) {
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        LinearLayout row = Ui.row(c);
        for (String[] k : keys) row.addView(plainKey(c, k[0], k[1]), Ui.wrap(c, 3));
        scroll.addView(row);
        scroll.setLayoutParams(Ui.fillWidth(c, 2));
        return scroll;
    }

    private View shortcutRow(Context c) {
        HorizontalScrollView scroll = new HorizontalScrollView(c);
        LinearLayout row = Ui.row(c);
        String[][] shortcuts = {
            {"Copy", "ctrl", "c"}, {"Paste", "ctrl", "v"}, {"Cut", "ctrl", "x"}, {"Undo", "ctrl", "z"},
            {"Select all", "ctrl", "a"}, {"Save", "ctrl", "s"}, {"Close tab", "ctrl", "w"},
            {"Switch app", "alt", "tab"}, {"Close app", "alt", "f4"},
        };
        for (final String[] s : shortcuts) {
            Button b = Ui.button(c, s[0]);
            b.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    client.fire("input.key", DockClient.args("key", s[2], "mods", new JSONArray().put(s[1])));
                }
            });
            row.addView(b, Ui.wrap(c, 3));
        }
        scroll.addView(row);
        scroll.setLayoutParams(Ui.fillWidth(c, 2));
        return scroll;
    }
}
