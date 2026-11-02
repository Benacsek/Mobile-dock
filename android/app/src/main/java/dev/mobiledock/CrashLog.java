package dev.mobiledock;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A short error log kept on the phone. Nothing is ever sent anywhere.
 * Copy diagnostics in Settings puts it on the clipboard, to paste when reporting a problem.
 */
final class CrashLog {
    private static final int MAX = 24 * 1024;

    private CrashLog() {
    }

    /** Writes crashes to the log, then lets Android handle them as usual. */
    static void install(final Context context) {
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                StringWriter trace = new StringWriter();
                e.printStackTrace(new PrintWriter(trace));
                note(app, "Crash in " + t.getName() + "\n" + trace);
                if (previous != null) previous.uncaughtException(t, e);
            }
        });
    }

    static synchronized void note(Context c, String text) {
        File f = file(c);
        String old = read(f);
        String entry = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + text + "\n";
        String all = old + entry;
        if (all.length() > MAX) all = all.substring(all.length() - MAX);
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(all.getBytes("UTF-8"));
        } catch (IOException ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    static String diagnostics(Context c) {
        StringBuilder sb = new StringBuilder("Mobile Dock diagnostics\n");
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            sb.append("App ").append(info.versionName).append(" (").append(info.versionCode).append(")\n");
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Phone ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("\n\n");
        String log = read(file(c));
        sb.append("Errors:\n").append(log.length() == 0 ? "none\n" : log);
        sb.append("\nWatcher:\n").append(WatchService.logText(c));
        return sb.toString();
    }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "errors.log");
    }

    private static String read(File f) {
        if (!f.isFile()) return "";
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) Math.min(f.length(), MAX)];
            int n = 0;
            while (n < buf.length) {
                int r = in.read(buf, n, buf.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(buf, 0, n, "UTF-8");
        } catch (IOException e) {
            return "";
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
