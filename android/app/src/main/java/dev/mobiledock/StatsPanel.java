package dev.mobiledock;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/** Live PC performance: CPU, GPU, memory, network, disk and the busiest apps. */
final class StatsPanel extends LinearLayout {
    private static final int CPU = 0xFF4F8CFF, GPU = 0xFFE5484D, RAM = 0xFFB57BFF, DOWN = 0xFF3DDC84,
            UP = 0xFFFFB020, READ = 0xFF2FB5D8, WRITE = 0xFFFF7A59;

    private final DockClient client;
    private final Card cpu, gpu, ram, net, disk;
    private final LinearLayout procs;
    private boolean active;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            postDelayed(this, 1000);
        }
    };

    /** Title, big value, detail line and a graph. */
    private static final class Card extends LinearLayout {
        final TextView value, detail;
        final GraphView graph;

        Card(Context c, String title, int color, GraphView graph) {
            super(c);
            setOrientation(VERTICAL);
            setBackground(Ui.rounded(c, Ui.SURFACE, 16));
            int p = Ui.dp(c, 10);
            setPadding(p, p, p, p);
            LinearLayout head = Ui.row(c);
            TextView t = Ui.text(c, title, 13, color);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(t);
            head.addView(Ui.spacer(c));
            value = Ui.text(c, "", 18, Ui.TEXT);
            value.setTypeface(Typeface.DEFAULT_BOLD);
            head.addView(value);
            addView(head);
            detail = Ui.text(c, "", 11, Ui.MUTED);
            detail.setSingleLine(true);
            detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
            addView(detail);
            this.graph = graph;
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
            lp.topMargin = Ui.dp(c, 6);
            addView(graph, lp);
        }
    }

    StatsPanel(Context c, DockClient client) {
        super(c);
        this.client = client;
        setOrientation(VERTICAL);
        cpu = new Card(c, "CPU", CPU, new GraphView(c, 100, CPU));
        gpu = new Card(c, "GPU", GPU, new GraphView(c, 100, GPU));
        ram = new Card(c, "Memory", RAM, new GraphView(c, 100, RAM));
        net = new Card(c, "Network", DOWN, new GraphView(c, 0, DOWN, UP));
        disk = new Card(c, "Disk", READ, new GraphView(c, 0, READ, WRITE));

        LinearLayout top = Ui.row(c);
        top.addView(cpu, cell(c));
        top.addView(gpu, cell(c));
        top.addView(ram, cell(c));
        addView(top, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bottom = Ui.row(c);
        bottom.addView(net, cell(c));
        bottom.addView(disk, cell(c));
        procs = Ui.column(c);
        procs.setBackground(Ui.rounded(c, Ui.SURFACE, 16));
        int p = Ui.dp(c, 10);
        procs.setPadding(p, p, p, p);
        bottom.addView(procs, cell(c));
        addView(bottom, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
    }

    private static LayoutParams cell(Context c) {
        LayoutParams lp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        int m = Ui.dp(c, 4);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    static String bytes(double b) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int u = 0;
        while (b >= 1024 && u < units.length - 1) {
            b /= 1024;
            u++;
        }
        return String.format(Locale.US, b >= 100 || u == 0 ? "%.0f %s" : "%.1f %s", b, units[u]);
    }

    private void show(JSONObject r) {
        double c = r.optDouble("cpu", 0);
        cpu.value.setText(Math.round(c) + "%");
        String mhz = r.has("cpu_mhz") ? String.format(Locale.US, "   %.2f GHz", r.optInt("cpu_mhz") / 1000.0) : "";
        JSONArray cores = r.optJSONArray("cores");
        cpu.detail.setText(r.optString("cpu_name") + (cores != null ? "   " + cores.length() + " threads" : "") + mhz);
        cpu.graph.add((float) c);

        if (r.has("gpu")) {
            gpu.setVisibility(VISIBLE);
            double g = r.optDouble("gpu", 0);
            gpu.value.setText(Math.round(g) + "%");
            String vram = r.has("vram_used")
                    ? "   " + bytes(r.optLong("vram_used")) + (r.has("vram_total") ? " / " + bytes(r.optLong("vram_total")) : "")
                    : "";
            String temp = r.has("gpu_temp") ? String.format(Locale.US, "   %.0f °C", r.optDouble("gpu_temp")) : "";
            gpu.detail.setText(r.optString("gpu_name", "GPU") + vram + temp);
            gpu.graph.add((float) g);
        } else {
            gpu.value.setText("");
            gpu.detail.setText("No GPU counters on this PC");
        }

        long used = r.optLong("ram_used"), total = Math.max(1, r.optLong("ram_total", 1));
        ram.value.setText(Math.round(100.0 * used / total) + "%");
        ram.detail.setText(bytes(used) + " of " + bytes(total) + " in use");
        ram.graph.add(100f * used / total);

        long down = r.optLong("net_down"), up = r.optLong("net_up");
        net.value.setText(bytes(down) + "/s");
        net.detail.setText("Down " + bytes(down) + "/s   Up " + bytes(up) + "/s");
        net.graph.add(down, up);

        long rd = r.optLong("disk_read"), wr = r.optLong("disk_write");
        disk.value.setText(bytes(rd + wr) + "/s");
        disk.detail.setText("Read " + bytes(rd) + "/s   Write " + bytes(wr) + "/s");
        disk.graph.add(rd, wr);

        JSONArray list = r.optJSONArray("procs");
        procs.removeAllViews();
        TextView head = Ui.text(getContext(), "Busiest apps", 13, Ui.ACCENT);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        procs.addView(head);
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject p = list.optJSONObject(i);
            if (p == null) continue;
            LinearLayout row = Ui.row(getContext());
            TextView name = Ui.text(getContext(), p.optString("name"), 12, Ui.TEXT);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.addView(name, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            TextView v = Ui.text(getContext(), String.format(Locale.US, "%.0f%%  %s", p.optDouble("cpu"),
                    bytes(p.optLong("mem"))), 12, Ui.MUTED);
            v.setGravity(Gravity.END);
            row.addView(v);
            LayoutParams lp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            lp.topMargin = Ui.dp(getContext(), 3);
            procs.addView(row, lp);
        }
    }

    void refresh() {
        client.request("perf", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject reply) {
                if (active && reply.optBoolean("ok") && reply.has("cpu")) show(reply);
            }
        });
    }

    void setActive(boolean on) {
        active = on;
        removeCallbacks(poll);
        if (on) post(poll);
    }
}
