package dev.mobiledock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import dev.mobiledock.proto.Crypto;

/** App icons from the PC, kept in memory and on disk so tiles draw instantly next time. */
final class IconCache {
    private final DockClient client;
    private final File dir;
    private final LruCache<String, Bitmap> memory = new LruCache<String, Bitmap>(120);
    private final Set<String> missing = new HashSet<String>();
    private final Set<String> loading = new HashSet<String>();

    IconCache(Context context, DockClient client) {
        this.client = client;
        dir = new File(context.getFilesDir(), "icons");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            android.util.Log.w("MobileDock", "cannot create icon cache");
        }
    }

    private File file(String appId) {
        return new File(dir, Crypto.hex(Crypto.sha256(Crypto.utf8(appId))).substring(0, 24) + ".png");
    }

    /** Shows the icon in view when it exists. Leaves the view hidden otherwise. */
    void load(final String appId, final ImageView view) {
        view.setTag(appId);
        Bitmap cached = memory.get(appId);
        if (cached == null) cached = fromDisk(appId);
        if (cached != null) {
            show(view, cached);
            return;
        }
        if (missing.contains(appId) || loading.contains(appId)) return;
        loading.add(appId);
        client.request("apps.icon", DockClient.args("app", appId), new DockClient.Callback() {
            @Override
            public void done(JSONObject reply) {
                loading.remove(appId);
                String png = reply.optString("png");
                if (!reply.optBoolean("ok")) return;  // not connected or similar, try again later
                if (png.length() == 0) {
                    missing.add(appId);
                    return;
                }
                byte[] data;
                try {
                    data = Base64.decode(png, Base64.DEFAULT);
                } catch (IllegalArgumentException e) {
                    return;
                }
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp == null) return;
                bmp = shape(bmp);
                memory.put(appId, bmp);
                save(appId, data);
                if (appId.equals(view.getTag())) show(view, bmp);
            }
        });
    }

    /**
     * Gives every icon the same rounded-square outline, like a phone launcher.
     * Square icons are clipped to it. Round or odd-shaped icons sit on a plate of that shape.
     */
    static Bitmap shape(Bitmap src) {
        int size = Math.max(32, Math.min(192, Math.max(src.getWidth(), src.getHeight())));
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(out);
        android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG | android.graphics.Paint.FILTER_BITMAP_FLAG);
        android.graphics.RectF box = new android.graphics.RectF(0, 0, size, size);
        float radius = size * 0.22f;
        if (fullBleed(src)) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setRectToRect(new android.graphics.RectF(0, 0, src.getWidth(), src.getHeight()), box,
                    android.graphics.Matrix.ScaleToFit.FILL);
            android.graphics.BitmapShader shader = new android.graphics.BitmapShader(src,
                    android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            shader.setLocalMatrix(m);
            paint.setShader(shader);
            canvas.drawRoundRect(box, radius, radius, paint);
        } else {
            paint.setColor(Ui.SURFACE_HI);
            canvas.drawRoundRect(box, radius, radius, paint);
            float inset = size * 0.12f;
            android.graphics.RectF inner = new android.graphics.RectF(inset, inset, size - inset, size - inset);
            paint.setColor(0xFFFFFFFF);
            canvas.drawBitmap(src, null, inner, paint);
        }
        return out;
    }

    /** True when all four corners are opaque, so the icon fills its square edge to edge. */
    private static boolean fullBleed(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight(), dx = Math.max(1, w / 20), dy = Math.max(1, h / 20);
        int[][] pts = {{dx, dy}, {w - 1 - dx, dy}, {dx, h - 1 - dy}, {w - 1 - dx, h - 1 - dy}};
        for (int[] p : pts) {
            if (android.graphics.Color.alpha(b.getPixel(p[0], p[1])) < 200) return false;
        }
        return true;
    }

    private static void show(ImageView view, Bitmap bmp) {
        view.setImageBitmap(bmp);
        view.setVisibility(android.view.View.VISIBLE);
    }

    private Bitmap fromDisk(String appId) {
        File f = file(appId);
        if (!f.isFile()) return null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp != null) {
                bmp = shape(bmp);
                memory.put(appId, bmp);
            }
            return bmp;
        } catch (IOException e) {
            return null;
        } finally {
            close(in);
        }
    }

    private void save(String appId, byte[] data) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file(appId));
            out.write(data);
        } catch (IOException ignored) {
        } finally {
            close(out);
        }
    }

    static void close(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
