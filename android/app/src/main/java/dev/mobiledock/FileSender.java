package dev.mobiledock;

import android.app.Activity;
import android.app.AlertDialog;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import dev.mobiledock.proto.Crypto;

/**
 * Sends a photo or file from the phone to the PC over the encrypted link.
 * "clipboard" puts a picture on the PC clipboard, ready to paste into Figma.
 * "downloads" saves the file in Downloads/Mobile Dock on the PC.
 */
final class FileSender {
    private static final int CHUNK = 192 * 1024;

    private final Activity activity;
    private final DockClient client;
    private final Handler io;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    FileSender(Activity activity, DockClient client) {
        this.activity = activity;
        this.client = client;
        HandlerThread t = new HandlerThread("send-io");
        t.start();
        io = new Handler(t.getLooper());
    }

    boolean busy() {
        return busy;
    }

    /** Sends bytes made in the app, such as a signature, through a private cache file. */
    void sendBytes(String name, byte[] data, String mode) {
        java.io.File dir = new java.io.File(activity.getCacheDir(), "outgoing");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            toast("Could not prepare the file");
            return;
        }
        java.io.File f = new java.io.File(dir, name);
        java.io.FileOutputStream out = null;
        try {
            out = new java.io.FileOutputStream(f);
            out.write(data);
        } catch (java.io.IOException e) {
            toast("Could not prepare the file");
            return;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
        send(Uri.fromFile(f), mode);
    }

    void send(final Uri uri, final String mode) {
        if (busy) {
            toast("Already sending a file");
            return;
        }
        busy = true;
        final TextView text = Ui.text(activity, "Preparing", 15, Ui.TEXT);
        int p = Ui.dp(activity, 20);
        text.setPadding(p, p, p, p);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("clipboard".equals(mode) ? "Sending picture to the PC clipboard" : "Sending file to the PC")
                .setView(text).setCancelable(false).setNegativeButton("Cancel", null).show();
        final Job job = new Job(uri, mode, text, dialog);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                job.cancelled = true;
                job.finish("Cancelled");
            }
        });
        io.post(new Runnable() {
            @Override
            public void run() {
                job.open();
            }
        });
    }

    private void toast(final String s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(activity, s, Toast.LENGTH_LONG).show();
            }
        });
    }

    private final class Job {
        final Uri uri;
        final String mode;
        final TextView text;
        final AlertDialog dialog;
        InputStream in;
        File temp;
        long size, sent;
        String name = "file";
        MessageDigest sha;
        volatile boolean cancelled;

        Job(Uri uri, String mode, TextView text, AlertDialog dialog) {
            this.uri = uri;
            this.mode = mode;
            this.text = text;
            this.dialog = dialog;
        }

        void open() {
            try {
                sha = MessageDigest.getInstance("SHA-256");
                if ("file".equals(uri.getScheme()) && uri.getLastPathSegment() != null) {
                    name = uri.getLastPathSegment();  // only our own cache files use this scheme
                }
                Cursor c = "file".equals(uri.getScheme()) ? null
                        : activity.getContentResolver().query(uri, null, null, null, null);
                if (c != null) {
                    try {
                        if (c.moveToFirst()) {
                            int n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            int s = c.getColumnIndex(OpenableColumns.SIZE);
                            if (n >= 0 && !c.isNull(n)) name = c.getString(n);
                            if (s >= 0 && !c.isNull(s)) size = c.getLong(s);
                        }
                    } finally {
                        c.close();
                    }
                }
                in = activity.getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("cannot open the file");
                if (size <= 0) {
                    // Size unknown: copy first, so the PC knows how much is coming.
                    temp = new File(activity.getCacheDir(), "outgoing.bin");
                    FileOutputStream out = new FileOutputStream(temp);
                    try {
                        byte[] buf = new byte[65536];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    } finally {
                        out.close();
                        in.close();
                    }
                    size = temp.length();
                    in = new FileInputStream(temp);
                }
            } catch (IOException | SecurityException | NoSuchAlgorithmException e) {
                finish("Could not read the file: " + e.getMessage());
                return;
            }
            main.post(new Runnable() {
                @Override
                public void run() {
                    client.request("file.begin", DockClient.args("name", name, "size", size, "mode", mode),
                            new DockClient.Callback() {
                                @Override
                                public void done(JSONObject r) {
                                    if (!r.optBoolean("ok")) {
                                        finish(r.optString("error", "The PC refused the file"));
                                        return;
                                    }
                                    next();
                                }
                            });
                }
            });
        }

        void next() {
            if (cancelled) return;
            io.post(new Runnable() {
                @Override
                public void run() {
                    final byte[] buf = new byte[CHUNK];
                    int n = 0;
                    try {
                        int r;
                        while (n < CHUNK && (r = in.read(buf, n, CHUNK - n)) > 0) n += r;
                    } catch (IOException e) {
                        finish("Reading failed: " + e.getMessage());
                        return;
                    }
                    if (n <= 0) {
                        end();
                        return;
                    }
                    sha.update(buf, 0, n);
                    sent += n;
                    final String data = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            text.setText("Sending " + name + "\n" + (100 * sent / Math.max(1, size)) + "%   "
                                    + StatsPanel.bytes(sent) + " of " + StatsPanel.bytes(size));
                            client.request("file.chunk", DockClient.args("data", data), new DockClient.Callback() {
                                @Override
                                public void done(JSONObject r) {
                                    if (r.optBoolean("ok")) next();
                                    else finish(r.optString("error", "Sending stopped"));
                                }
                            });
                        }
                    });
                }
            });
        }

        void end() {
            final String digest = Crypto.hex(sha.digest());
            main.post(new Runnable() {
                @Override
                public void run() {
                    client.request("file.end", DockClient.args("sha256", digest), new DockClient.Callback() {
                        @Override
                        public void done(JSONObject r) {
                            if (!r.optBoolean("ok")) {
                                finish(r.optString("error", "Sending failed"));
                            } else if ("clipboard".equals(r.optString("where"))) {
                                finish("On the PC clipboard. Press Ctrl+V to paste.");
                            } else {
                                finish("Saved on the PC: " + r.optString("where"));
                            }
                        }
                    });
                }
            });
        }

        void finish(final String message) {
            IconCache.close(in);
            if (temp != null && !temp.delete()) temp.deleteOnExit();
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (dialog.isShowing()) dialog.dismiss();
                    busy = false;
                    Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
