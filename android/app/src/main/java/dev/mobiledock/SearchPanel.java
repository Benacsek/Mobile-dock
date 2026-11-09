package dev.mobiledock;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.InputType;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Search on the PC: type once, and a preset sends it to YouTube, Google, the app in front or Windows.
 * Auto lets the PC decide: a keyword like "yt lofi", a site name, the app in front, then the default.
 * Presets are made on the PC. This page only sends the text and the chosen preset's id.
 */
final class SearchPanel extends LinearLayout {
    private static final String PREF_RECENT = "search_recent";
    private static final int MAX_RECENT = 10;

    private final DockClient client;
    private final SharedPreferences prefs;
    private final EditText field;
    private final LinearLayout chips, recent;
    private final TextView clearAll;
    private final List<View> chipViews = new ArrayList<View>();
    private final Map<String, Bitmap> icons = new HashMap<String, Bitmap>();
    private String chosen = "";  // "" is Auto

    SearchPanel(Context c, DockClient client) {
        super(c);
        this.client = client;
        prefs = WatchService.prefs(c);
        setOrientation(VERTICAL);

        LinearLayout top = Ui.row(c);
        field = new EditText(c);
        field.setHint("Search on the PC, or yt lofi, youtube.com");
        field.setSingleLine(true);
        field.setTextColor(Ui.TEXT);
        field.setHintTextColor(Ui.MUTED);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setBackground(Ui.rounded(c, Ui.SURFACE, 14));
        int p = Ui.dp(c, 14);
        field.setPadding(p, 0, p, 0);
        field.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int action, KeyEvent e) {
                run(field.getText().toString(), chosen);
                return true;
            }
        });
        top.addView(field, new LayoutParams(0, Ui.dp(c, 52), 1f));
        Button go = Ui.accentButton(c, "Search");
        go.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                run(field.getText().toString(), chosen);
            }
        });
        LayoutParams glp = new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 52));
        glp.leftMargin = Ui.dp(c, 8);
        top.addView(go, glp);
        addView(top, Ui.fillWidth(c, 4));

        HorizontalScrollView scroll = new HorizontalScrollView(c);
        scroll.setHorizontalScrollBarEnabled(false);
        chips = Ui.row(c);
        scroll.addView(chips);
        addView(scroll, Ui.fillWidth(c, 4));

        LinearLayout headRow = Ui.row(c);
        headRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView head = Ui.text(c, "Recent", 13, Ui.ACCENT);
        head.setPadding(Ui.dp(c, 6), Ui.dp(c, 8), 0, Ui.dp(c, 2));
        headRow.addView(head);
        headRow.addView(Ui.spacer(c));
        clearAll = Ui.text(c, "Clear all", 13, Ui.MUTED);
        clearAll.setGravity(Gravity.CENTER);
        clearAll.setClickable(true);
        clearAll.setBackground(Ui.pressable(c, Ui.BG, Ui.SURFACE_HI, 12));
        clearAll.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        clearAll.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                save(new ArrayList<String>());
                toast("Recent searches cleared");
            }
        });
        headRow.addView(clearAll, new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 40)));
        addView(headRow, Ui.fillWidth(c, 0));
        ScrollView rs = new ScrollView(c);
        recent = Ui.column(c);
        rs.addView(recent);
        addView(rs, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        showRecent();
    }

    /** Reloads the presets from the PC. Called when the page opens and when the PC's presets change. */
    void load() {
        client.request("search.presets", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (r.optBoolean("ok")) showChips(r.optJSONArray("presets"));
            }
        });
    }

    private void showChips(JSONArray presets) {
        Context c = getContext();
        chips.removeAllViews();
        chipViews.clear();
        chips.addView(chip(c, "", "Auto", "", null));
        for (int i = 0; presets != null && i < presets.length(); i++) {
            JSONObject p = presets.optJSONObject(i);
            if (p != null) {
                chips.addView(chip(c, p.optString("id"), p.optString("name"), p.optString("keyword"),
                        p.optString("icon_rev")));
            }
        }
        select(chosen);
    }

    /** Icon and name. Long press puts the keyword in the box, as a reminder of the shortcut. */
    private View chip(final Context c, final String id, String name, final String keyword, String rev) {
        LinearLayout chip = Ui.row(c);
        chip.setTag(id);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setClickable(true);
        chip.setPadding(Ui.dp(c, 8), 0, Ui.dp(c, 14), 0);
        chip.setContentDescription(name);
        FrameLayout box = new FrameLayout(c);
        int s = Ui.dp(c, 30);
        TextView letters = Ui.text(c, id.length() == 0 ? "" : Ui.initials(name), 12, Ui.TEXT);
        letters.setGravity(Gravity.CENTER);
        if (id.length() > 0) letters.setBackground(Ui.rounded(c, Ui.colorFor(id), 8));
        box.addView(letters, new FrameLayout.LayoutParams(s, s));
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (id.length() == 0) image.setImageDrawable(new Icon(Icon.Kind.SEARCH, Ui.TEXT));
        else image.setVisibility(GONE);
        box.addView(image, new FrameLayout.LayoutParams(s, s));
        chip.addView(box, new LayoutParams(s, s));
        TextView label = Ui.text(c, name, 14, Ui.TEXT);
        label.setSingleLine(true);
        label.setPadding(Ui.dp(c, 8), 0, 0, 0);
        chip.addView(label);
        chip.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                select(id);
            }
        });
        chip.setOnLongClickListener(new OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (keyword.length() > 0) {
                    field.setText(keyword + " ");
                    field.setSelection(field.getText().length());
                }
                return true;
            }
        });
        if (id.length() > 0) loadIcon(id, rev, image);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, Ui.dp(c, 46));
        lp.rightMargin = Ui.dp(c, 8);
        chip.setLayoutParams(lp);
        chipViews.add(chip);
        return chip;
    }

    private void select(String id) {
        chosen = id;
        for (View v : chipViews) {
            boolean on = id.equals(v.getTag());
            v.setBackground(Ui.rounded(getContext(), on ? (Ui.ACCENT & 0x00FFFFFF) | 0x38000000 : Ui.SURFACE, 23));
        }
    }

    /** Icons come from the PC: the site's own icon, the app's icon, your picture, or a line icon. */
    private void loadIcon(final String id, String rev, final ImageView view) {
        final String key = id + "/" + rev;
        Bitmap cached = icons.get(key);
        if (cached != null) {
            show(view, cached);
            return;
        }
        client.request("search.icon", DockClient.args("id", id), new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (!r.optBoolean("ok")) return;
                String png = r.optString("png", "");
                if (png.length() > 0) {
                    try {
                        byte[] data = Base64.decode(png, Base64.DEFAULT);
                        Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                        if (bmp != null) {
                            bmp = IconCache.shape(bmp);
                            icons.put(key, bmp);
                            show(view, bmp);
                        }
                    } catch (IllegalArgumentException ignored) {
                        // keep the letters
                    }
                    return;
                }
                Icon.Kind kind = builtin(r.optString("builtin", ""));
                if (kind != null) {
                    view.setImageDrawable(new Icon(kind, Ui.TEXT));
                    view.setPadding(Ui.dp(getContext(), 3), Ui.dp(getContext(), 3), Ui.dp(getContext(), 3),
                            Ui.dp(getContext(), 3));
                    view.setBackground(Ui.rounded(getContext(), Ui.SURFACE_HI, 8));
                    view.setVisibility(VISIBLE);
                }
            }
        });
    }

    private static Icon.Kind builtin(String name) {
        if ("search".equals(name)) return Icon.Kind.SEARCH;
        if ("globe".equals(name)) return Icon.Kind.GLOBE;
        if ("pin".equals(name)) return Icon.Kind.PIN;
        if ("windows".equals(name)) return Icon.Kind.APPS;
        return null;
    }

    private static void show(ImageView view, Bitmap bmp) {
        view.setImageBitmap(bmp);
        view.setVisibility(VISIBLE);
    }

    private void run(final String text, String preset) {
        if (text.trim().length() == 0) {
            toast("Type something to search for");
            return;
        }
        client.request("search.run", DockClient.args("text", text.trim(), "preset", preset), new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (!r.optBoolean("ok")) {
                    toast(r.optString("error", "Search failed"));
                    return;
                }
                toast(r.optString("did", "Done"));
                remember(text.trim());
                field.setText("");
            }
        });
    }

    private void remember(String text) {
        List<String> list = recentList();
        list.remove(text);
        list.add(0, text);
        while (list.size() > MAX_RECENT) list.remove(list.size() - 1);
        save(list);
    }

    private void forget(String text) {
        List<String> list = recentList();
        list.remove(text);
        save(list);
    }

    private void save(List<String> list) {
        prefs.edit().putString(PREF_RECENT, new JSONArray(list).toString()).commit();
        showRecent();
    }

    private List<String> recentList() {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(prefs.getString(PREF_RECENT, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.optString(i));
        } catch (org.json.JSONException ignored) {
            // start fresh
        }
        return out;
    }

    /** Recent searches. Tap one to search it again with the chosen preset. */
    private void showRecent() {
        Context c = getContext();
        recent.removeAllViews();
        List<String> list = recentList();
        clearAll.setVisibility(list.isEmpty() ? GONE : VISIBLE);
        if (list.isEmpty()) {
            TextView none = Ui.text(c, "Your searches show here, so you can repeat them with one tap. "
                    + "Tap the X to remove one. Long press a preset to see its keyword.", 13, Ui.MUTED);
            none.setPadding(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), 0);
            recent.addView(none);
            return;
        }
        for (final String text : list) {
            LinearLayout row = Ui.row(c);
            row.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 12));
            row.setClickable(true);
            row.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 2), 0);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(c);
            icon.setImageDrawable(new Icon(Icon.Kind.SEARCH, Ui.MUTED));
            int s = Ui.dp(c, 18);
            row.addView(icon, new LayoutParams(s, s));
            TextView t = Ui.text(c, text, 15, Ui.TEXT);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(Ui.dp(c, 10), 0, 0, 0);
            row.addView(t, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            row.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    run(text, chosen);
                }
            });
            ImageView remove = new ImageView(c);
            remove.setImageDrawable(new Icon(Icon.Kind.CLOSE, Ui.MUTED));
            int rp = Ui.dp(c, 12);
            remove.setPadding(rp, rp, rp, rp);
            remove.setClickable(true);
            remove.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 12));
            remove.setContentDescription("Remove " + text);
            remove.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    forget(text);
                }
            });
            int rs = Ui.dp(c, 42);
            row.addView(remove, new LayoutParams(rs, rs));
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 44));
            lp.setMargins(Ui.dp(c, 4), Ui.dp(c, 3), Ui.dp(c, 4), Ui.dp(c, 3));
            recent.addView(row, lp);
        }
    }

    private void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }
}
