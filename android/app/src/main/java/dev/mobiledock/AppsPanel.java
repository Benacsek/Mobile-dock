package dev.mobiledock;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Pinned apps grid. Tap to launch. Long press and drag to reorder, or drop on Remove to unpin. */
final class AppsPanel extends LinearLayout {
    private static final class App {
        final String id;
        final String name;

        App(String id, String name) {
            this.id = id;
            this.name = name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private final DockClient client;
    private final List<App> apps = new ArrayList<App>();
    private final LinearLayout grid;
    private final LinearLayout header;
    private final TextView removeZone;
    private final TextView empty;
    private int columns = 5;
    private final IconCache icons;
    private final LaunchListener onLaunch;

    /** Lets the dock react to launches, for example by opening Now playing for music apps. */
    interface LaunchListener {
        void launched(String appName);
    }

    AppsPanel(Context c, DockClient client, LaunchListener onLaunch) {
        super(c);
        this.client = client;
        this.onLaunch = onLaunch;
        icons = new IconCache(c, client);
        setOrientation(VERTICAL);

        FrameLayout top = new FrameLayout(c);
        header = Ui.row(c);
        header.addView(Ui.title(c, "Pinned apps"));
        TextView hint = Ui.text(c, "   Long press to move", 13, Ui.MUTED);
        header.addView(hint);
        header.addView(Ui.spacer(c));
        Button refresh = Ui.button(c, "Refresh");
        refresh.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                load();
            }
        });
        header.addView(refresh, Ui.wrap(c, 4));
        Button add = Ui.accentButton(c, "+ Add app");
        add.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showPicker();
            }
        });
        header.addView(add, Ui.wrap(c, 4));
        top.addView(header);

        removeZone = Ui.text(c, "Drop here to unpin", 16, Ui.TEXT);
        removeZone.setGravity(Gravity.CENTER);
        removeZone.setBackground(Ui.rounded(c, Ui.DANGER, 10));
        removeZone.setVisibility(GONE);
        removeZone.setOnDragListener(new OnDragListener() {
            @Override
            public boolean onDrag(View v, DragEvent e) {
                switch (e.getAction()) {
                    case DragEvent.ACTION_DRAG_ENTERED:
                        v.setAlpha(1f);
                        return true;
                    case DragEvent.ACTION_DRAG_EXITED:
                        v.setAlpha(0.7f);
                        return true;
                    case DragEvent.ACTION_DROP:
                        unpin((App) e.getLocalState());
                        return true;
                    default:
                        return true;
                }
            }
        });
        top.addView(removeZone, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 56)));
        addView(top, Ui.fillWidth(c, 4));

        empty = Ui.text(c, "No pinned apps yet. Tap + Add app.", 15, Ui.MUTED);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, Ui.dp(c, 40), 0, 0);
        addView(empty, Ui.fillWidth(c, 0));

        ScrollView scroll = new ScrollView(c);
        grid = Ui.column(c);
        scroll.addView(grid);
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int cols = Math.max(3, w / Ui.dp(getContext(), 112));
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
        client.request("apps.pinned", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject reply) {
                apply(reply);
            }
        });
    }

    private void apply(JSONObject reply) {
        if (!reply.optBoolean("ok")) {
            toast(reply.optString("error", "Failed"));
            return;
        }
        JSONArray arr = reply.optJSONArray("apps");
        if (arr == null) return;
        apps.clear();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject a = arr.optJSONObject(i);
            if (a != null) apps.add(new App(a.optString("id"), a.optString("name")));
        }
        rebuild();
    }

    private void toast(String s) {
        Toast.makeText(getContext(), s, Toast.LENGTH_SHORT).show();
    }

    private void rebuild() {
        Context c = getContext();
        grid.removeAllViews();
        empty.setVisibility(apps.isEmpty() ? VISIBLE : GONE);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % columns == 0) {
                row = Ui.row(c);
                grid.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 104)));
            }
            row.addView(tile(apps.get(i), i), cell(c));
        }
        if (row != null) {
            for (int k = apps.size() % columns; k != 0 && k < columns; k++) row.addView(new View(c), cell(c));
        }
    }

    private static LayoutParams cell(Context c) {
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        int m = Ui.dp(c, 5);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private View tile(final App app, final int index) {
        final Context c = getContext();
        LinearLayout t = Ui.column(c);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 14));
        t.setPadding(Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6));

        FrameLayout iconBox = new FrameLayout(c);
        TextView letters = Ui.text(c, Ui.initials(app.name), 20, Ui.TEXT);
        letters.setGravity(Gravity.CENTER);
        letters.setBackground(Ui.rounded(c, Ui.colorFor(app.id), 12));
        iconBox.addView(letters, new FrameLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Ui.SURFACE);
        image.setVisibility(GONE);
        iconBox.addView(image, new FrameLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));
        icons.load(app.id, image);
        t.addView(iconBox, new LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)));

        TextView label = Ui.text(c, app.name, 12, Ui.TEXT);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 6);
        t.addView(label, lp);

        t.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                client.request("apps.launch", DockClient.args("app", app.id), new DockClient.Callback() {
                    @Override
                    public void done(JSONObject reply) {
                        if (!reply.optBoolean("ok")) toast(reply.optString("error", "Launch failed"));
                    }
                });
                toast("Opening " + app.name);
                if (onLaunch != null) onLaunch.launched(app.name);
            }
        });
        t.setOnLongClickListener(new OnLongClickListener() {
            @Override
            @SuppressWarnings("deprecation")
            public boolean onLongClick(View v) {
                header.setVisibility(INVISIBLE);
                removeZone.setAlpha(0.7f);
                removeZone.setVisibility(VISIBLE);
                v.setAlpha(0.35f);
                v.startDrag(ClipData.newPlainText("app", app.id), new DragShadowBuilder(v), app, 0);
                return true;
            }
        });
        t.setOnDragListener(new OnDragListener() {
            @Override
            public boolean onDrag(View v, DragEvent e) {
                Object dragged = e.getLocalState();
                switch (e.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                        return dragged instanceof App;
                    case DragEvent.ACTION_DRAG_ENTERED:
                        if (dragged != app) v.setBackground(Ui.rounded(c, Ui.ACCENT, 14));
                        return true;
                    case DragEvent.ACTION_DRAG_EXITED:
                        v.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 14));
                        return true;
                    case DragEvent.ACTION_DROP:
                        move((App) dragged, index);
                        return true;
                    case DragEvent.ACTION_DRAG_ENDED:
                        v.setAlpha(1f);
                        v.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 14));
                        dragEnded();
                        return true;
                    default:
                        return true;
                }
            }
        });
        return t;
    }

    private void dragEnded() {
        removeZone.setVisibility(GONE);
        header.setVisibility(VISIBLE);
    }

    private void move(final App app, final int target) {
        int from = apps.indexOf(app);
        if (from < 0 || from == target) return;
        apps.remove(from);
        apps.add(Math.min(target, apps.size()), app);
        JSONArray ids = new JSONArray();
        for (App a : apps) ids.put(a.id);
        post(new Runnable() {
            @Override
            public void run() {
                rebuild();
            }
        });
        client.request("apps.reorder", DockClient.args("ids", ids), new DockClient.Callback() {
            @Override
            public void done(JSONObject reply) {
                if (!reply.optBoolean("ok")) load();
            }
        });
    }

    private void unpin(App app) {
        apps.remove(app);
        post(new Runnable() {
            @Override
            public void run() {
                rebuild();
            }
        });
        client.request("apps.unpin", DockClient.args("app", app.id), new DockClient.Callback() {
            @Override
            public void done(JSONObject reply) {
                apply(reply);
            }
        });
    }

    private void showPicker() {
        final Context c = getContext();
        final Toast wait = Toast.makeText(c, "Loading apps from the PC", Toast.LENGTH_SHORT);
        wait.show();
        client.request("apps.available", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject reply) {
                wait.cancel();
                JSONArray arr = reply.optJSONArray("apps");
                if (!reply.optBoolean("ok") || arr == null) {
                    toast(reply.optString("error", "Could not list apps"));
                    return;
                }
                final List<App> all = new ArrayList<App>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject a = arr.optJSONObject(i);
                    if (a != null) all.add(new App(a.optString("id"), a.optString("name")));
                }
                openPicker(all);
            }
        });
    }

    private void openPicker(final List<App> all) {
        final Context c = getContext();
        LinearLayout box = Ui.column(c);
        box.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), 0);
        final EditText search = new EditText(c);
        search.setHint("Search");
        search.setSingleLine(true);
        box.addView(search, Ui.fillWidth(c, 0));
        final ListView list = new ListView(c);
        final ArrayAdapter<App> adapter = new ArrayAdapter<App>(c, android.R.layout.simple_list_item_1,
                new ArrayList<App>(all));
        list.setAdapter(adapter);
        box.addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 260)));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                String q = s.toString().toLowerCase();
                adapter.clear();
                for (App a : all) {
                    if (a.name.toLowerCase().contains(q)) adapter.add(a);
                }
            }
        });
        final AlertDialog dialog = new AlertDialog.Builder(c).setTitle("Pin an app").setView(box)
                .setNegativeButton("Close", null).create();
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                App a = adapter.getItem(position);
                client.request("apps.pin", DockClient.args("app", a.id), new DockClient.Callback() {
                    @Override
                    public void done(JSONObject reply) {
                        apply(reply);
                    }
                });
                toast("Pinned " + a.name);
            }
        });
        dialog.show();
    }
}
