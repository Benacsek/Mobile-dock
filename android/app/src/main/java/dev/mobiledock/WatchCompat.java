package dev.mobiledock;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/** Calls for newer Android versions, kept apart so Android 4 never loads them. */
@TargetApi(26)
final class WatchCompat {
    private static final String CHANNEL_QUIET = "watch";
    private static final String CHANNEL_ALERT = "pc_online";

    private WatchCompat() {
    }

    static void startForegroundService(Context c, Intent i) {
        c.startForegroundService(i);
    }

    @TargetApi(29)
    static void startForeground(android.app.Service s, int id, Notification n) {
        s.startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    static Notification.Builder builder(Context c, boolean alert) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (alert) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERT, "PC came online",
                    NotificationManager.IMPORTANCE_HIGH));
            return new Notification.Builder(c, CHANNEL_ALERT);
        }
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_QUIET, "Waiting for your PC",
                NotificationManager.IMPORTANCE_MIN));
        return new Notification.Builder(c, CHANNEL_QUIET);
    }

    static void notify(Context c, int id, Notification n) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, n);
    }

    @TargetApi(23)
    static android.app.PendingIntent openIntent(Context c, int request, Intent i) {
        return android.app.PendingIntent.getActivity(c, request, i,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    }

    /** Charging state without registering a receiver (Android 6 and newer). */
    @TargetApi(23)
    static boolean charging(Context c) {
        android.os.BatteryManager bm = (android.os.BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        return bm != null && bm.isCharging();
    }

    static boolean canDrawOverlays(Context c) {
        return Settings.canDrawOverlays(c);
    }

    static void askOverlay(Activity a) {
        a.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + a.getPackageName())));
    }

    static void askNotifications(Activity a) {
        if (Build.VERSION.SDK_INT >= 33) a.requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 3);
    }

    @SuppressWarnings("BatteryLife")
    static void askBatteryExemption(Activity a) {
        PowerManager pm = (PowerManager) a.getSystemService(Context.POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(a.getPackageName())) return;
        a.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + a.getPackageName())));
    }

    /** Shows the dock over the lock screen and turns the screen on, on Android 8.1 and newer. */
    @TargetApi(27)
    static void wakeActivity(Activity a, boolean overLockScreen) {
        a.setTurnScreenOn(true);
        a.setShowWhenLocked(overLockScreen);
    }
}
