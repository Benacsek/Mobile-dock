package dev.mobiledock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Base64;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import dev.mobiledock.proto.Crypto;

/**
 * Gets new versions of this app from the PC, which downloads them from GitHub releases.
 * The file must match the SHA-256 the PC reports, and Android only installs it over
 * this app when it carries the same signature.
 */
final class PhoneUpdater {
    private final Activity activity;
    private final DockClient client;
    private boolean busy;

    PhoneUpdater(Activity activity, DockClient client) {
        this.activity = activity;
        this.client = client;
    }

    static File apkFile(Activity a) {
        return new File(new File(a.getFilesDir(), "updates"), "MobileDock.apk");
    }

    @SuppressWarnings("deprecation")
    private int installedCode() {
        try {
            return activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    void check() {
        if (busy) return;
        client.request("update.check", DockClient.args("code", installedCode()), new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (r.optBoolean("ok") && r.optBoolean("available")) download(r);
            }
        });
    }

    private void download(final JSONObject offer) {
        busy = true;
        final File file = apkFile(activity);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            busy = false;
            return;
        }
        final FileOutputStream out;
        try {
            out = new FileOutputStream(file);
        } catch (IOException e) {
            busy = false;
            return;
        }
        final long total = offer.optLong("size");
        final String version = offer.optString("version");
        final String sha = offer.optString("sha256");
        new Object() {
            long offset;

            void next() {
                client.request("update.chunk", DockClient.args("offset", offset), new DockClient.Callback() {
                    @Override
                    public void done(JSONObject r) {
                        byte[] data = null;
                        if (r.optBoolean("ok")) {
                            try {
                                data = Base64.decode(r.optString("data"), Base64.DEFAULT);
                            } catch (IllegalArgumentException ignored) {
                                data = null;
                            }
                        }
                        try {
                            if (data == null || data.length == 0) throw new IOException("download stopped");
                            out.write(data);
                            offset += data.length;
                            if (offset < total) {
                                next();
                                return;
                            }
                            out.close();
                            if (!sha.equalsIgnoreCase(sha256(file))) throw new IOException("checksum mismatch");
                            busy = false;
                            ask(version, file);
                        } catch (IOException e) {
                            IconCache.close(out);
                            if (!file.delete()) file.deleteOnExit();
                            busy = false;
                        }
                    }
                });
            }
        }.next();
    }

    static String sha256(File f) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            return Crypto.hex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("no SHA-256");
        } finally {
            in.close();
        }
    }

    private void ask(String version, final File file) {
        if (activity.isFinishing()) return;
        new AlertDialog.Builder(activity).setTitle("Update ready")
                .setMessage("Mobile Dock " + version + " is ready. Install it now? The dock reconnects after the update.")
                .setPositiveButton("Install", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        install(file);
                    }
                }).setNegativeButton("Later", null).show();
    }

    @SuppressWarnings("deprecation")
    void install(File file) {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                if (InstallCompat.needsPermission(activity)) {
                    Toast.makeText(activity, "Allow Mobile Dock to install updates, then tap Install again.",
                            Toast.LENGTH_LONG).show();
                    InstallCompat.askPermission(activity);
                    return;
                }
                activity.startActivity(InstallCompat.installIntent(activity));
                return;
            }
            // Android 6 and older: the installer reads a file on shared storage.
            File ext = activity.getExternalFilesDir(null);
            if (ext == null) throw new IOException("no shared storage");
            File copy = new File(ext, "MobileDock-update.apk");
            copyFile(file, copy);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(copy), "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
        } catch (IOException | RuntimeException e) {
            Toast.makeText(activity, "Could not start the installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static void copyFile(File from, File to) throws IOException {
        FileInputStream in = new FileInputStream(from);
        FileOutputStream out = new FileOutputStream(to);
        try {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            in.close();
            out.close();
        }
    }
}
