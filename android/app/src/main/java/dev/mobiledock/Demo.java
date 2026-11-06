package dev.mobiledock;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Random;

/**
 * Sample answers that stand in for a PC, so CI can take screenshots of every page.
 * Only debuggable builds can turn it on. The release app never answers from here.
 */
final class Demo {
    static volatile boolean on;
    private static final Random RANDOM = new Random(7);
    private static double cpu = 22, gpu = 35;

    private Demo() {
    }

    static JSONObject reply(String cmd, JSONObject args) {
        try {
            JSONObject r = answer(cmd, args == null ? new JSONObject() : args);
            r.put("ok", true);
            return r;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    private static JSONObject answer(String cmd, JSONObject args) throws JSONException {
        JSONObject r = new JSONObject();
        if ("apps.pinned".equals(cmd)) {
            String[] names = {"Spotify", "Minecraft Launcher", "CurseForge", "Teamfight Tactics", "Prime Video",
                "Discord", "Figma", "Task Manager"};
            JSONArray apps = new JSONArray();
            for (int i = 0; i < names.length; i++) apps.put(new JSONObject().put("id", "demo" + i).put("name", names[i]));
            r.put("apps", apps);
        } else if ("apps.icon".equals(cmd)) {
            r.put("png", icon(args.optString("app")));
        } else if ("media.status".equals(cmd)) {
            r.put("title", "Midnight City").put("artist", "M83").put("album", "Hurry Up, We're Dreaming")
                    .put("playing", true).put("position", 94).put("duration", 243).put("shuffle", false)
                    .put("repeat", "all").put("art_id", "demo-art").put("app", "Spotify");
        } else if ("media.art".equals(cmd)) {
            r.put("art", art());
        } else if ("pads.list".equals(cmd)) {
            String[] tiles = {"Move", "Frame", "Rectangle", "Ellipse", "Pen", "Text", "Group", "Ungroup",
                "Auto layout", "Component", "Duplicate", "Zoom to fit", "Undo", "Redo", "Export"};
            JSONArray list = new JSONArray();
            for (String t : tiles) list.put(new JSONObject().put("label", t));
            r.put("pads", new JSONArray().put(new JSONObject().put("id", "figma").put("name", "Figma").put("tiles", list)));
            r.put("context", new JSONObject().put("app", "Figma.exe").put("pad", "figma"));
        } else if ("perf".equals(cmd)) {
            cpu = Math.max(3, Math.min(97, cpu + RANDOM.nextGaussian() * 9));
            gpu = Math.max(3, Math.min(99, gpu + RANDOM.nextGaussian() * 12));
            JSONArray cores = new JSONArray();
            for (int i = 0; i < 16; i++) cores.put((int) Math.max(0, Math.min(100, cpu + RANDOM.nextGaussian() * 15)));
            r.put("cpu", cpu).put("cores", cores).put("cpu_name", "AMD Ryzen 7 7800X3D").put("cpu_mhz", 4850)
                    .put("cpu_temp", 61.4).put("gpu", gpu).put("gpu_name", "AMD Radeon RX 7800 XT")
                    .put("vram_used", 5_400_000_000L).put("vram_total", 17_179_869_184L).put("gpu_temp", 55)
                    .put("ram_used", 14_800_000_000L).put("ram_total", 34_359_738_368L)
                    .put("net_down", (long) (2_400_000 + RANDOM.nextInt(900_000))).put("net_up", 180_000L)
                    .put("disk_read", (long) RANDOM.nextInt(40_000_000)).put("disk_write", 3_200_000L)
                    .put("fans", new JSONArray().put(new JSONObject().put("name", "CPU").put("rpm", 1180))
                            .put(new JSONObject().put("name", "Case").put("rpm", 860)));
            String[] procs = {"Minecraft", "Discord", "Spotify", "Figma", "Chrome", "Mobile Dock", "Explorer"};
            JSONArray list = new JSONArray();
            for (int i = 0; i < procs.length; i++) {
                list.put(new JSONObject().put("name", procs[i]).put("cpu", 18.0 / (i + 1)).put("mem", 900_000_000L / (i + 1)));
            }
            r.put("procs", list);
        } else if ("mixer.list".equals(cmd)) {
            r.put("apps", new JSONArray()
                    .put(mixer("discord.exe", "Discord", 70, false))
                    .put(mixer("javaw.exe", "Minecraft", 45, false))
                    .put(mixer("spotify.exe", "Spotify", 30, false))
                    .put(mixer("system", "System sounds", 20, true)));
        } else if ("update.status".equals(cmd) || "update.refresh".equals(cmd)) {
            r.put("pc_version", "1.2.32").put("status", "Up to date (1.2.32)").put("phone_ready", "1.2.32");
        } else if ("update.check".equals(cmd)) {
            r.put("available", false);
        } else if ("host.info".equals(cmd)) {
            r.put("mac", "");
        } else if ("search.presets".equals(cmd)) {
            String[][] list = {{"google", "Google", "g"}, {"youtube", "YouTube", "yt"}, {"maps", "Maps", "map"},
                {"browser", "In browser", ""}, {"vscode", "In VS Code", ""}, {"windows", "Windows", "win"}};
            JSONArray presets = new JSONArray();
            for (String[] p : list) {
                presets.put(new JSONObject().put("id", p[0]).put("name", p[1]).put("keyword", p[2]).put("icon_rev", "1"));
            }
            r.put("presets", presets);
        } else if ("search.icon".equals(cmd)) {
            String id = args.optString("id");
            if ("maps".equals(id)) r.put("builtin", "pin");
            else if ("windows".equals(id)) r.put("builtin", "windows");
            else r.put("png", icon("demo" + ("google".equals(id) ? 7 : "youtube".equals(id) ? 2 : "browser".equals(id) ? 0 : 4)));
        } else if ("search.run".equals(cmd)) {
            r.put("did", "YouTube: " + args.optString("text"));
        } else if ("ping".equals(cmd)) {
            r.put("recording", false);
        }
        return r;
    }

    private static JSONObject mixer(String id, String name, int volume, boolean muted) throws JSONException {
        return new JSONObject().put("id", id).put("name", name).put("volume", volume).put("muted", muted);
    }

    /** Half the icons fill their square and half are round, so both icon shapes show up. */
    private static String icon(String id) {
        int n = id.length() > 4 ? id.charAt(4) - '0' : 0;
        int[] colors = {0xFF1DB954, 0xFF6B8E23, 0xFFF16436, 0xFFE8B730, 0xFF00A8E1, 0xFF5865F2, 0xFFA259FF, 0xFF0078D4};
        Bitmap b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(colors[Math.abs(n) % colors.length]);
        if (n % 2 == 0) c.drawCircle(48, 48, 44, p);
        else c.drawRect(0, 0, 96, 96, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(48, 48, 16, p);
        return png(b);
    }

    private static String art() {
        Bitmap b = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setShader(new LinearGradient(0, 0, 300, 300, 0xFF4C8DF6, 0xFFF2B33D, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 300, 300, p);
        return png(b);
    }

    private static String png(Bitmap b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }
}
