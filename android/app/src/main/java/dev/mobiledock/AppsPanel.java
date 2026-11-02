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
    private TextView hint;
    private boolean arrange;
    private final TextView empty;
    private int columns = 5;
    private final IconCache icons;
    private final LaunchListener onLaunch;

    /** Lets the dock react to launches, for example by opening Now playing for music apps. */
    interface LaunchListener {
        void launched(String appName);
    }

    private final TextView pcName;
    private final View pcDot;
    private ImageView arrangeButton;
    private int width;

    AppsPanel(Context c, DockClient client, LaunchListener onLaunch) {
        super(c);
        this.client = client;
        this.onLaunch = onLaunch;
        icons = new IconCache(c, client);
        setOrientation(VERTICAL);

        FrameLayout top = new FrameLayout(c);
        header = Ui.row(c);
        header.addView(Ui.title(c, "Pinned apps"));
        // Which PC this phone is driving, with the same amber dot as the PC window.
        LinearLayout pc = Ui.row(c);
        pc.setBackground(Ui.rounded(c, Ui.SURFACE, 12));
        pc.setPadding(Ui.dp(c, 10), Ui.dp(c, 4), Ui.dp(c, 12), Ui.dp(c, 4));
        pcDot = new View(c);
        pcDot.setBackground(Ui.rounded(c, Ui.SIGNAL, 4));
        pc.addView(pcDot, new LayoutParams(Ui.dp(c, 8), Ui.dp(c, 8)));
        pcName = Ui.text(c, "", 13, Ui.SIGNAL);
        pcName.setSingleLine(true);
        pcName.setEllipsize(android.text.TextUtils.TruncateAt.END);
        pcName.setMaxWidth(Ui.dp(c, 180));
        pcName.setPadding(Ui.dp(c, 8), 0, 0, 0);
        pc.addView(pcName);
        LayoutParams pclp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        pclp.leftMargin = Ui.dp(c, 12);
        header.addView(pc, pclp);
        hint = Ui.text(c, "", 13, Ui.MUTED);
        hint.setSingleLine(true);
        hint.setEllipsize(android.text.TextUtils.TruncateAt.END);
        hint.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0);
        header.addView(hint, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        ImageView add = Ui.iconButton(c, Icon.Kind.PLUS, 44, 0x00000000, Ui.SURFACE_HI, Ui.ACCENT);
        add.setContentDescription("Add app");
        add.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                showPicker();
            }
        });
        header.addView(add);
        arrangeButton = Ui.iconButton(c, Icon.Kind.MOVE, 44, 0x00000000, Ui.SURFACE_HI, Ui.TEXT);
        arrangeButton.setContentDescription("Arrange");
        arrangeButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                setArrange(!arrange);
            }
        });
        header.addView(arrangeButton);
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
        top.addView(removeZone, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(c, 56 * Ui.TILE_SCALE)));
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
        width = w;
        int cols = balancedColumns();
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

    /**
     * As many columns as fit, then spread evenly over the rows needed,
     * so 8 apps make two rows of 4 instead of 6 and a ragged 2.
     */
    private int balancedColumns() {
        if (width <= 0) return columns;
        int max = Math.max(3, width / Ui.dp(getContext(), 112 * Ui.TILE_SCALE));
        int n = apps.size();
        if (n == 0) return max;
        int rows = (n + max - 1) / max;
        return Math.max(3, (n + rows - 1) / rows);
    }

    private void rebuild() {
        Context c = getContext();
        columns = balancedColumns();
        if (hint != null) updateHint();
        grid.removeAllViews();
        empty.setVisibility(apps.isEmpty() ? VISIBLE : GONE);
        LinearLayout row = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % columns == 0) {
                row = Ui.row(c);
                grid.addView(row, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 124 * Ui.TILE_SCALE)));
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
        t.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 18));
        if (arrange) t.setRotation(index % 2 == 0 ? -1.5f : 1.5f);
        t.setPadding(Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6));

        FrameLayout iconBox = new FrameLayout(c);
        TextView letters = Ui.text(c, Ui.initials(app.name), 20, Ui.TEXT);
        letters.setGravity(Gravity.CENTER);
        letters.setBackground(Ui.rounded(c, Ui.colorFor(app.id), 12 * Ui.TILE_SCALE));
        iconBox.addView(letters, new FrameLayout.LayoutParams(Ui.dp(c, 56 * Ui.TILE_SCALE), Ui.dp(c, 56 * Ui.TILE_SCALE)));
        ImageView image = new ImageView(c);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(GONE);
        iconBox.addView(image, new FrameLayout.LayoutParams(Ui.dp(c, 56 * Ui.TILE_SCALE), Ui.dp(c, 56 * Ui.TILE_SCALE)));
        icons.load(app.id, image);
        t.addView(iconBox, new LayoutParams(Ui.dp(c, 56 * Ui.TILE_SCALE), Ui.dp(c, 56 * Ui.TILE_SCALE)));

        TextView label = Ui.text(c, app.name, 12, Ui.TEXT);
        label.setGravity(Gravity.CENTER);
        label.setMaxLines(2);  // two lines, like a launcher, so "Minecraft Launcher" fits
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(c, 6);
        t.addView(label, lp);

        t.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (arrange) {
                    startMove(v, app);
                    return;
                }
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
            public boolean onLongClick(View v) {
                startMove(v, app);
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
                        if (dragged != app) v.setBackground(Ui.rounded(c, Ui.ACCENT, 18));
                        return true;
                    case DragEvent.ACTION_DRAG_EXITED:
                        v.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 18));
                        return true;
                    case DragEvent.ACTION_DROP:
                        move((App) dragged, index);
                        return true;
                    case DragEvent.ACTION_DRAG_ENDED:
                        v.setAlpha(1f);
                        v.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.SURFACE_HI, 18));
                        dragEnded();
                        return true;
                    default:
                        return true;
                }
            }
        });
        return t;
    }

    @SuppressWarnings("deprecation")
    private void startMove(View v, App app) {
        header.setVisibility(INVISIBLE);
        removeZone.setAlpha(0.7f);
        removeZone.setVisibility(VISIBLE);
        v.setAlpha(0.35f);
        v.startDrag(ClipData.newPlainText("app", app.id), new DragShadowBuilder(v), app, 0);
    }

    /** Arrange mode: a tap picks a tile up instead of opening the app. */
    void setArrange(boolean on) {
        arrange = on;
        ((Icon) arrangeButton.getDrawable()).setColor(on ? Ui.onAccent() : Ui.TEXT);  // blue fill while on
        arrangeButton.setSelected(on);
        updateHint();
        rebuild();
    }

    /** The tip shows until you have moved an app once. */
    private void updateHint() {
        boolean learned = WatchService.prefs(getContext()).getBoolean("moved_once", false);
        hint.setText(arrange ? "Tap a tile, then drop it on its new place" : learned ? "" : "Long press a tile to move it");
        hint.setTextColor(arrange ? Ui.ACCENT : Ui.MUTED);
    }

    /** Shows which PC this phone is connected to. */
    void setPc(String name) {
        pcName.setText(name == null || name.length() == 0 ? "Your PC" : name);
    }

    boolean arranging() {
        return arrange;
    }

    private void dragEnded() {
        removeZone.setVisibility(GONE);
        header.setVisibility(VISIBLE);
    }

    private void move(final App app, final int target) {
        int from = apps.indexOf(app);
        if (from < 0 || from == target) return;
        WatchService.prefs(getContext()).edit().putBoolean("moved_once", true).commit();
        updateHint();
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
                new ArrayList<App>(all)) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row = convertView instanceof LinearLayout ? (LinearLayout) convertView : null;
                if (row == null) {
                    row = Ui.row(c);
                    int p = Ui.dp(c, 8);
                    row.setPadding(p, p, p, p);
                    ImageView img = new ImageView(c);
                    row.addView(img, new LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32)));
                    TextView name = new TextView(c);
                    name.setTextSize(16);
                    name.setPadding(Ui.dp(c, 12), 0, 0, 0);
                    name.setSingleLine(true);
                    row.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
                }
                App a = getItem(position);
                ImageView img = (ImageView) row.getChildAt(0);
                img.setImageDrawable(null);
                img.setVisibility(INVISIBLE);
                icons.load(a.id, img);
                ((TextView) row.getChildAt(1)).setText(a.name);
                return row;
            }
        };
        list.setAdapter(adapter);
        TextView none = new TextView(c);
        none.setText("No apps match your search.");
        none.setGravity(Gravity.CENTER);
        none.setPadding(0, Ui.dp(c, 24), 0, Ui.dp(c, 24));
        box.addView(none, Ui.fillWidth(c, 0));
        list.setEmptyView(none);
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
