package dev.mobiledock;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

import dev.mobiledock.proto.Crypto;

/**
 * Optional background watcher. When a paired PC comes online, it wakes the screen and opens
 * the dock, which then connects on its own. It only sends the same small discovery probe the
 * connect page uses, and only to your network.
 *
 * It checks every 5 seconds while the phone charges and every 60 seconds on battery.
 */
public final class WatchService extends Service {
    static final String PREF_ENABLED = "wake_on_pc";
    static final String PREF_LOCKSCREEN = "show_on_lockscreen";
    private static final int NOTIFICATION_ID = 7;

    /** Set while the dock is on screen, so the watcher stays quiet. */
    static volatile boolean dockVisible;
    /** Set when you tap Disconnect. The watcher waits until the PC goes off and on again. */
    static volatile boolean quietUntilOffline;

    /** What the watcher saw last, for the status panel on the connect page. */
    static volatile String status = "Not running";
    static volatile long lastCheck;

    private volatile boolean running;
    private Thread thread;
    private PowerManager.WakeLock cpuLock;
    private WifiManager.WifiLock wifiLock;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("dock", Context.MODE_PRIVATE);
    }

    static boolean enabled(Context c) {
        return prefs(c).getBoolean(PREF_ENABLED, false);
    }

    static void sync(Context c) {
        Intent i = new Intent(c, WatchService.class);
        if (!enabled(c)) {
            c.stopService(i);
        } else if (Build.VERSION.SDK_INT >= 26) {
            WatchCompat.startForegroundService(c, i);
        } else {
            c.startService(i);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = notification("Waiting for your PC", "Mobile Dock opens when your PC starts.", false);
        if (Build.VERSION.SDK_INT >= 29) {
            WatchCompat.startForeground(this, NOTIFICATION_ID, n);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        if (!running) {
            running = true;
            thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "dock-watch");
            thread.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        status = "Not running";
        log(this, "Watcher stopped");
        if (thread != null) thread.interrupt();
        holdLocks(false);
        super.onDestroy();
    }

    @SuppressWarnings("deprecation")
    Notification notification(String title, String text, boolean tapToOpen) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? WatchCompat.builder(this, tapToOpen) : new Notification.Builder(this);
        b.setSmallIcon(getApplicationInfo().icon).setContentTitle(title).setContentText(text).setOngoing(!tapToOpen);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(MainActivity.EXTRA_WAKE, true);
        b.setContentIntent(Build.VERSION.SDK_INT >= 23
                ? WatchCompat.openIntent(this, tapToOpen ? 1 : 0, open)
                : PendingIntent.getActivity(this, tapToOpen ? 1 : 0, open, PendingIntent.FLAG_UPDATE_CURRENT));
        if (tapToOpen) {
            b.setAutoCancel(true);
            b.setPriority(Notification.PRIORITY_HIGH);
        } else {
            b.setPriority(Notification.PRIORITY_MIN);
        }
        return b.build();
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // only below Android 6, sticky read
    private boolean charging() {
        if (Build.VERSION.SDK_INT >= 23) return WatchCompat.charging(this);
        Intent battery = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    /** CPU and Wi-Fi stay awake only while charging, so the watcher costs no battery otherwise. */
    @SuppressWarnings("deprecation")
    private synchronized void holdLocks(boolean hold) {
        try {
            if (hold && cpuLock == null) {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                cpuLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MobileDock:watch");
                cpuLock.setReferenceCounted(false);
                cpuLock.acquire(10 * 60 * 1000L);
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL, "MobileDock:watch");
                    wifiLock.setReferenceCounted(false);
                    wifiLock.acquire();
                }
            } else if (hold) {
                cpuLock.acquire(10 * 60 * 1000L);  // renew the timeout
            } else {
                if (cpuLock != null && cpuLock.isHeld()) cpuLock.release();
                if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
                cpuLock = null;
                wifiLock = null;
            }
        } catch (RuntimeException ignored) {
            // A missing lock only makes the watcher slower, never broken.
        }
    }

    /** Keeps the last 30 watcher events with the time, so you can see what happened. */
    static synchronized void log(Context c, String message) {
        SharedPreferences p = prefs(c);
        String line = new java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date()) + "  " + message;
        String old = p.getString("watch_log", "");
        String[] lines = old.length() == 0 ? new String[0] : old.split("\n");
        StringBuilder sb = new StringBuilder(line);
        for (int i = 0; i < Math.min(29, lines.length); i++) sb.append('\n').append(lines[i]);
        p.edit().putString("watch_log", sb.toString()).commit();
    }

    static String logText(Context c) {
        String t = prefs(c).getString("watch_log", "");
        return t.length() == 0 ? "Nothing logged yet." : t;
    }

    private void loop() {
        Store store = new Store(this);
        boolean online = false;
        String lastBoot = null;
        int misses = 0;
        boolean wasPlugged = !charging();
        log(this, "Watcher started");
        while (running) {
            boolean plugged = charging();
            if (plugged != wasPlugged) {
                log(this, plugged ? "Charging: checking every 5 seconds" : "On battery: checking every 60 seconds");
                wasPlugged = plugged;
            }
            holdLocks(plugged);
            String boot = probe(store);
            lastCheck = System.currentTimeMillis();
            if (boot != null) {
                misses = 0;
                // A new boot id means the PC app started again: after a PC restart, or when you
                // closed and reopened the app. Both should bring the dock up and connect.
                boolean restarted = !online || !boot.equals(lastBoot);
                if (restarted) {
                    quietUntilOffline = false;
                    log(this, "PC app is running (start " + boot + ")");
                    status("PC online");
                    wake(!dockVisible);
                }
                online = true;
                lastBoot = boot;
            } else if (++misses >= 3 && online) {
                online = false;
                quietUntilOffline = false;
                log(this, "PC went offline");
                status("Waiting for your PC");
            } else if (!online) {
                status = "Waiting for your PC";
            }
            try {
                Thread.sleep(plugged ? 5000 : 60000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** The boot id of a paired PC that answers the discovery probe, or null when none answers. */
    private String probe(Store store) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(1500);
            byte[] msg = Crypto.utf8("MDOCK1?");
            String last = store.lastHost();
            if (last.length() > 0 && !last.startsWith("127.")) {
                socket.send(new DatagramPacket(msg, msg.length, InetAddress.getByName(last), Discovery.UDP_PORT));
            }
            socket.send(new DatagramPacket(msg, msg.length, InetAddress.getByName("255.255.255.255"),
                    Discovery.UDP_PORT));
            byte[] buf = new byte[2048];
            long end = System.currentTimeMillis() + 1500;
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                socket.receive(p);
                JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), Crypto.UTF8));
                if ("mdock".equals(o.optString("t")) && store.isPaired(o.optString("host_id"))) {
                    store.setLast(p.getAddress().getHostAddress(), o.optInt("port", 47800));
                    return o.optString("boot", "unknown");
                }
            }
        } catch (SocketTimeoutException e) {
            return null;
        } catch (IOException | JSONException e) {
            return null;
        } finally {
            if (socket != null) socket.close();
        }
        return null;
    }

    private void status(String text) {
        status = text;
        Notification n = notification(text, "Mobile Dock opens when your PC app starts.", false);
        if (Build.VERSION.SDK_INT >= 26) {
            WatchCompat.notify(this, NOTIFICATION_ID, n);
        } else {
            ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, n);
        }
    }

    private void fallbackNotification(String why) {
        if (Build.VERSION.SDK_INT >= 33 && !WatchCompat.notificationsAllowed(this)) {
            log(this, why + " Notifications are off too, so nothing could be shown. Open Mobile Dock and tap Fix.");
            return;
        }
        log(this, why + " Sent a notification to tap.");
        if (Build.VERSION.SDK_INT >= 26) {
            WatchCompat.notify(this, 8, notification("Your PC is on", "Tap to open Mobile Dock", true));
        } else {
            ((android.app.NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE))
                    .notify(8, notification("Your PC is on", "Tap to open Mobile Dock", true));
        }
    }

    @SuppressWarnings("deprecation")
    private void wake(boolean turnScreenOn) {
        if (quietUntilOffline) {
            log(this, "Not opening: you tapped Disconnect");
            return;
        }
        if (turnScreenOn) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock screen = pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "MobileDock:wake");
                screen.acquire(10000);
            } catch (RuntimeException ignored) {
                // The dock still opens, the screen just stays as it is.
            }
        }
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_WAKE, true);
        // Android 10 and newer only lets an app open itself from the background when it may
        // draw over other apps. Without that, a notification asks you to tap.
        if (Build.VERSION.SDK_INT >= 29 && !dockVisible && !WatchCompat.canDrawOverlays(this)) {
            fallbackNotification("Could not open the dock: \"Display over other apps\" is off.");
            return;
        }
        try {
            startActivity(open);
            log(this, dockVisible ? "Reconnecting the open dock" : "Woke the phone and opened the dock");
        } catch (RuntimeException e) {
            fallbackNotification("Android blocked opening the dock (" + e.getClass().getSimpleName() + ").");
        }
    }
}
