package dev.mobiledock;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import dev.mobiledock.proto.Connection;

/** Single screen app: a connect page, then the dock with its pages and a bottom bar of tiles. */
public final class MainActivity extends Activity implements DockClient.Listener {
    static final String EXTRA_WAKE = "wake";
    /** Random per app process. Other apps cannot know it, so they cannot trigger a wake. */
    static final String WAKE_TOKEN = dev.mobiledock.proto.Crypto.hex(dev.mobiledock.proto.Crypto.random(16));
    private Store store;
    private DockClient client;
    private FrameLayout root;
    private View connectView, dockView;
    private TextView connectStatus, dockBanner;
    private LinearLayout hostList;
    private EditText addressField;
    private FrameLayout content;
    private AppsPanel apps;
    private ShortcutsPanel shortcuts;
    private TextView watchStatus;
    private Button wakeButton;
    private StatsPanel stats;
    private FileSender sender;
    private Uri pendingShare;
    private String pendingMode;
    private static final int PICK_PICTURE = 11, PICK_FILE = 12;
    private MediaPanel media;
    private final List<LinearLayout> tabs = new ArrayList<LinearLayout>();
    private final List<View> panels = new ArrayList<View>();
    private boolean recording;
    private PhoneUpdater updater;
    private static final java.util.regex.Pattern MUSIC_APPS = java.util.regex.Pattern.compile(
            "(?i)spotify|music|tidal|deezer|vlc|foobar|winamp|musicbee|aimp|youtube music|soundcloud|"
            + "amazon music|rhythmbox|strawberry|clementine|audacious|lollypop|amberol|elisa|cider|plexamp");

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        CrashLog.install(this);
        store = new Store(this);
        client = new DockClient(store, this);
        updater = new PhoneUpdater(this, client);
        sender = new FileSender(this, client);
        Ui.loadTheme(WatchService.prefs(this));
        root = new FrameLayout(this);
        root.setBackground(Ui.background(this));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 33) {
            BackCompat.register(this, new Runnable() {
                @Override
                public void run() {
                    handleBack();
                }
            });
        }
        showConnect();
        if (startDemo(getIntent())) return;
        root.post(new Runnable() {
            @Override
            public void run() {
                whatsNew();
            }
        });
        String last = store.lastHost();
        if (last.length() > 0 && store.hasAnyPairing()) client.connect(last, store.lastPort());
        else scan();
        handleWake(getIntent());
        handleShare(getIntent());
        handlePairLink(getIntent());
        WatchService.sync(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleWake(intent);
        handleShare(intent);
        handlePairLink(intent);
        startDemo(intent);
    }

    /**
     * Screenshot mode for CI: sample data instead of a PC, and the page given in the "page" extra
     * (-1 for the connect page). Only works in debuggable builds, never in the release app.
     */
    private boolean startDemo(Intent intent) {
        boolean debuggable = (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        if (!debuggable || intent == null || !intent.getBooleanExtra("demo", false)) return false;
        Demo.on = true;
        client.hostName = "Desk-PC";
        int page = intent.getIntExtra("page", APPS);
        if (page < 0) {
            showConnect();
            return true;
        }
        if (dockView == null || dockView.getVisibility() != View.VISIBLE) onState(DockClient.CONNECTED, "Desk-PC");
        if (dimmed) wake();
        if (page == 99) {  // the dim night clock
            dim();
            return true;
        }
        select(page);
        return true;
    }

    /**
     * The QR code on the PC holds mobiledock://pair?h=address&p=port&pin=123456&n=name.
     * Any app or web page could open such a link, so it must point at a local address and you confirm it.
     */
    private void handlePairLink(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return;
        Uri uri = intent.getData();
        setIntent(new Intent());
        if (!"mobiledock".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) return;
        final String host = uri.getQueryParameter("h");
        final String pin = uri.getQueryParameter("pin");
        String name = uri.getQueryParameter("n");
        int port;
        try {
            port = Integer.parseInt(uri.getQueryParameter("p"));
        } catch (RuntimeException e) {
            port = -1;
        }
        if (host == null || !Rules.isLocalAddress(host) || pin == null || !pin.matches("\\d{6}") || port < 1 || port > 65535) {
            toast("This pairing code is not valid");
            return;
        }
        final int pairPort = port;
        String label = name == null || name.trim().length() == 0 ? host : name.trim() + " (" + host + ")";
        new AlertDialog.Builder(this).setTitle("Pair with this PC?")
                .setMessage(label + "\n\nOnly continue if you just scanned the code on your own PC.")
                .setPositiveButton("Pair", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        client.pair(host, pairPort, pin);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    /** Shows the notes for this version once after an update. A fresh install stays quiet. */
    @SuppressWarnings("deprecation")
    private void whatsNew() {
        android.content.pm.PackageInfo info;
        try {
            info = getPackageManager().getPackageInfo(getPackageName(), 0);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            return;
        }
        android.content.SharedPreferences p = WatchService.prefs(this);
        int seen = p.getInt("seen_code", 0);
        if (seen == info.versionCode) return;
        p.edit().putInt("seen_code", info.versionCode).commit();
        if (seen == 0 && !store.hasAnyPairing()) return;  // a new install, not an update
        String notes = asset("whats_new.txt");
        if (notes.length() == 0) return;
        new AlertDialog.Builder(this).setTitle("What's new in " + info.versionName)
                .setMessage(notes).setPositiveButton("OK", null).show();
    }

    private String asset(String name) {
        java.io.InputStream in = null;
        try {
            in = getAssets().open(name);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
            return out.toString("UTF-8").trim();
        } catch (java.io.IOException e) {
            return "";  // builds outside CI have no notes
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (java.io.IOException ignored) {
                }
            }
        }
    }

    // Night dimming and tap feedback ----------------------------------------------

    /** Every touch restarts the idle timer. While dimmed, the first touch only wakes the dock. */
    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            if (dimmed) {
                wake();
                swallowGesture = true;
                return true;
            }
            swallowGesture = false;
            // Remember a press on a button. It only buzzes on lift, if it turned out to be a tap.
            tapPending = isDocked() && Haptics.length(WatchService.prefs(this)) > 0
                    && Haptics.onPressable(getWindow().getDecorView(), ev.getX(), ev.getY());
            tapX = ev.getX();
            tapY = ev.getY();
            tapAt = ev.getEventTime();
            resetIdle();
        } else if (tapPending && action == android.view.MotionEvent.ACTION_MOVE) {
            float slop = android.view.ViewConfiguration.get(this).getScaledTouchSlop();
            if (!Rules.isTap(ev.getX() - tapX, ev.getY() - tapY, slop, 0, 1)) tapPending = false;  // a scroll
        } else if (tapPending && action == android.view.MotionEvent.ACTION_UP) {
            tapPending = false;
            if (Rules.isTap(0, 0, 0, ev.getEventTime() - tapAt, android.view.ViewConfiguration.getLongPressTimeout())) {
                Haptics.tick(this, Haptics.length(WatchService.prefs(this)));
            }
        } else if (action == android.view.MotionEvent.ACTION_CANCEL) {
            tapPending = false;
        }
        if (swallowGesture) {
            if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL) {
                swallowGesture = false;
            }
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    private boolean isDocked() {
        return dockView != null && dockView.getVisibility() == View.VISIBLE;
    }

    private final Runnable dimTask = new Runnable() {
        @Override
        public void run() {
            if (isDocked() && !dimmed) dim();
        }
    };

    /** Starts the countdown to the dim clock, or stops it when dimming is off or the dock is closed. */
    private void resetIdle() {
        if (root == null) return;
        root.removeCallbacks(dimTask);
        int minutes = WatchService.prefs(this).getInt(AppearancePanel.PREF_DIM, 5);
        if (minutes > 0 && isDocked()) root.postDelayed(dimTask, minutes * 60_000L);
    }

    private void dim() {
        if (night == null) {
            night = new NightView(this, client);
            root.addView(night);
        }
        night.setVisibility(View.VISIBLE);
        night.bringToFront();
        night.start();
        setBrightness(0.02f);
        dimmed = true;
    }

    private void wake() {
        if (night != null) {
            night.stop();
            night.setVisibility(View.GONE);
        }
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        dimmed = false;
        resetIdle();
    }

    private void setBrightness(float value) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = value;
        getWindow().setAttributes(lp);
    }

    /** Phone volume buttons turn the PC volume up and down while the dock is open. */
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {
        if (dimmed) wake();
        else resetIdle();
        if (volumeKeysForPc() && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            client.fire("media", DockClient.args("action",
                    keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ? "vol_up" : "vol_down"));
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, android.view.KeyEvent event) {
        if (volumeKeysForPc() && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            return true;  // no phone volume popup or click sound
        }
        return super.onKeyUp(keyCode, event);
    }

    private boolean volumeKeysForPc() {
        return client.isConnected() && dockView != null && dockView.getVisibility() == View.VISIBLE
                && WatchService.prefs(this).getBoolean(AppearancePanel.PREF_VOLUME_KEYS, true);
    }

    /** Opened by the PC watcher: turn the screen on and connect to the PC that just started. */
    @SuppressWarnings("deprecation")
    private void handleWake(Intent intent) {
        if (intent == null || !WAKE_TOKEN.equals(intent.getStringExtra(EXTRA_WAKE))) return;
        boolean overLock = WatchService.prefs(this).getBoolean(WatchService.PREF_LOCKSCREEN, false);
        if (Build.VERSION.SDK_INT >= 27) {
            WatchCompat.wakeActivity(this, overLock);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | (overLock ? WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED : 0));
        }
        String last = store.lastHost();
        if (!client.isConnected() && last.length() > 0 && store.hasAnyPairing()) {
            client.connect(last, store.lastPort());
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        WatchService.dockVisible = false;
        if (root != null) root.removeCallbacks(dimTask);
    }

    @Override
    protected void onResume() {
        super.onResume();
        WatchService.dockVisible = true;
        if (root != null) {
            root.removeCallbacks(statusTick);
            root.post(statusTick);
        }
        immersive();
        if (!dimmed) resetIdle();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @SuppressWarnings("deprecation")
    private void immersive() {
        int flags = View.SYSTEM_UI_FLAG_LOW_PROFILE;
        if (Build.VERSION.SDK_INT >= 19) {
            // LAYOUT_STABLE | LAYOUT_HIDE_NAVIGATION | LAYOUT_FULLSCREEN | HIDE_NAVIGATION | FULLSCREEN | IMMERSIVE_STICKY
            flags |= 0x100 | 0x200 | 0x400 | 0x2 | 0x4 | 0x1000;
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    @Override
    protected void onDestroy() {
        client.disconnect();
        super.onDestroy();
    }

    /** Back leaves fullscreen or arrange mode, then returns to Apps, then leaves the app. */
    private void handleBack() {
        if (dimmed) {
            wake();
            return;
        }
        boolean docked = dockView != null && dockView.getVisibility() == View.VISIBLE;
        if (docked && fullscreen) {
            setFullscreen(false);
            return;
        }
        if (docked && apps.arranging()) {
            select(APPS);
            apps.setArrange(false);
            return;
        }
        if (docked && current != APPS) {
            select(APPS);
            return;
        }
        finish();
    }

    @Override
    @SuppressWarnings("deprecation")
    @SuppressLint("GestureBackNavigation") // Android 13+ uses BackCompat instead.
    public void onBackPressed() {
        handleBack();
    }

    // Connect page -----------------------------------------------------------

    /** Wake-on-LAN: turns the PC on from sleep or shutdown, if its network card allows it. */
    private void wakePc() {
        final String host = store.lastHost();
        final String mac = store.mac(host);
        if (mac.length() == 0) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String result;
                try {
                    WakeOnLan.send(mac, host);
                    result = "Wake signal sent. The PC app connects once Windows has started.";
                } catch (Exception e) {
                    result = "Could not send the wake signal. Is Wi-Fi on?";
                }
                final String text = result;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        toast(text);
                    }
                });
            }
        }, "wake").start();
    }

    private void showConnect() {
        if (connectView == null) connectView = buildConnect();
        wakeButton.setVisibility(store.mac(store.lastHost()).length() > 0 ? View.VISIBLE : View.GONE);
        if (connectView.getParent() == null) root.addView(connectView);
        if (dimmed) wake();
        root.removeCallbacks(dimTask);
        connectView.setVisibility(View.VISIBLE);
        root.removeCallbacks(statusTick);
        root.post(statusTick);
        if (dockView != null) dockView.setVisibility(View.GONE);
    }

    private View buildConnect() {
        LinearLayout page = Ui.row(this);
        page.setGravity(Gravity.TOP);
        int pad = Ui.dp(this, 16);
        page.setPadding(pad, pad, pad, pad);

        int cardPad = Ui.dp(this, 18);
        LinearLayout left = Ui.column(this);
        left.setBackground(Ui.rounded(this, Ui.SURFACE, 18));
        left.setPadding(cardPad, cardPad, cardPad, cardPad);
        LinearLayout brand = Ui.row(this);
        brand.addView(Ui.logo(this, 28));
        TextView name = Ui.title(this, "  Mobile Dock");
        brand.addView(name);
        left.addView(brand);
        connectStatus = Ui.text(this, "Looking for your PC", 15, Ui.MUTED);
        left.addView(connectStatus, Ui.fillWidth(this, 4));
        left.addView(Ui.text(this, "PCs on this network", 14, Ui.TEXT), Ui.fillWidth(this, 4));
        ScrollView scroll = new ScrollView(this);
        hostList = Ui.column(this);
        scroll.addView(hostList);
        left.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        Button again = Ui.button(this, "Search again");
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.disconnect();
                scan();
            }
        });
        LinearLayout buttons = Ui.row(this);
        buttons.addView(again, Ui.wrap(this, 4));
        wakeButton = Ui.button(this, "Wake PC");
        wakeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wakePc();
            }
        });
        buttons.addView(wakeButton, Ui.wrap(this, 4));
        left.addView(buttons);
        page.addView(left, Ui.weight(1f));

        View gap = new View(this);
        page.addView(gap, new LinearLayout.LayoutParams(pad, 1));

        LinearLayout right = Ui.column(this);
        right.setBackground(Ui.rounded(this, Ui.SURFACE, 18));
        right.setPadding(cardPad, cardPad, cardPad, cardPad);
        right.addView(Ui.text(this, "Connect by address", 14, Ui.TEXT), Ui.fillWidth(this, 4));
        LinearLayout addrRow = Ui.row(this);
        addressField = new EditText(this);
        addressField.setHint("192.168.1.20");
        addressField.setSingleLine(true);
        addressField.setTextColor(Ui.TEXT);
        addressField.setHintTextColor(Ui.MUTED);
        addressField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setText(store.lastHost().equals("127.0.0.1") ? "" : store.lastHost());
        addrRow.addView(addressField, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button go = Ui.accentButton(this, "Connect");
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectTyped();
            }
        });
        addrRow.addView(go, Ui.wrap(this, 4));
        right.addView(addrRow, Ui.fillWidth(this, 0));

        right.addView(Ui.text(this, "USB cable", 14, Ui.TEXT), Ui.fillWidth(this, 4));
        right.addView(Ui.text(this, "Turn on USB debugging, plug in, then click Enable USB on the PC. Needs Android 5.0 or newer. On older phones, use USB tethering and connect by address.", 13, Ui.MUTED), Ui.fillWidth(this, 4));
        Button usb = Ui.button(this, "Connect over USB");
        usb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.connect("127.0.0.1", Connection.PORT);
            }
        });
        right.addView(usb, Ui.wrap(this, 4));

        right.addView(Ui.text(this, "Start with your PC", 14, Ui.TEXT), Ui.fillWidth(this, 4));
        final CheckBox wakeBox = new CheckBox(this);
        wakeBox.setText("Wake up and connect when my PC turns on");
        wakeBox.setTextColor(Ui.TEXT);
        wakeBox.setChecked(WatchService.enabled(this));
        wakeBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                WatchService.prefs(MainActivity.this).edit().putBoolean(WatchService.PREF_ENABLED, on).commit();
                WatchService.sync(MainActivity.this);
                if (on) explainWakePermissions();
            }
        });
        right.addView(wakeBox, Ui.fillWidth(this, 2));
        final CheckBox lockBox = new CheckBox(this);
        lockBox.setText("Show the dock over the lock screen");
        lockBox.setTextColor(Ui.TEXT);
        lockBox.setChecked(WatchService.prefs(this).getBoolean(WatchService.PREF_LOCKSCREEN, false));
        lockBox.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean on) {
                WatchService.prefs(MainActivity.this).edit().putBoolean(WatchService.PREF_LOCKSCREEN, on).commit();
            }
        });
        right.addView(lockBox, Ui.fillWidth(this, 2));
        right.addView(Ui.text(this, "Works best while the phone charges. Over the lock screen, anyone near the phone can control your PC.", 12, Ui.MUTED), Ui.fillWidth(this, 4));
        watchStatus = Ui.text(this, "", 13, Ui.TEXT);
        watchStatus.setBackground(Ui.rounded(this, Ui.SURFACE_HI, 10));
        int sp = Ui.dp(this, 10);
        watchStatus.setPadding(sp, sp, sp, sp);
        right.addView(watchStatus, Ui.fillWidth(this, 4));
        LinearLayout watchButtons = Ui.row(this);
        Button fix = Ui.accentButton(this, "Fix permissions");
        fix.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                fixNextPermission(true);
            }
        });
        watchButtons.addView(fix, Ui.wrap(this, 4));
        Button showLog = Ui.button(this, "Show log");
        showLog.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                TextView t = Ui.text(MainActivity.this, WatchService.logText(MainActivity.this), 12, Ui.TEXT);
                int p = Ui.dp(MainActivity.this, 16);
                t.setPadding(p, p, p, p);
                t.setTextIsSelectable(true);
                ScrollView sv = new ScrollView(MainActivity.this);
                sv.addView(t);
                new AlertDialog.Builder(MainActivity.this).setTitle("Watcher log").setView(sv)
                        .setPositiveButton("Close", null).show();
            }
        });
        watchButtons.addView(showLog, Ui.wrap(this, 4));
        right.addView(watchButtons, Ui.fillWidth(this, 0));

        Button forget = Ui.button(this, "Forget paired PCs");
        forget.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this).setTitle("Forget paired PCs?")
                        .setMessage("Paired: " + store.pairedNames() + "\nYou will need a new PIN to connect again.")
                        .setPositiveButton("Forget", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                client.disconnect();
                                store.forgetAll();
                                scan();
                            }
                        }).setNegativeButton("Cancel", null).show();
            }
        });
        right.addView(forget, Ui.wrap(this, 4));
        ScrollView rightScroll = new ScrollView(this);
        rightScroll.setFillViewport(true);
        rightScroll.addView(right);
        page.addView(rightScroll, Ui.weight(1f));
        return page;
    }

    private void explainWakePermissions() {
        fixNextPermission(false);
    }

    /** Asks for the first missing permission. Tap Fix again for the next one. */
    private void fixNextPermission(boolean sayWhenDone) {
        if (Build.VERSION.SDK_INT >= 33 && !WatchCompat.notificationsAllowed(this)) {
            WatchCompat.askNotifications(this);
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && !WatchCompat.canDrawOverlays(this)) {
            new AlertDialog.Builder(this).setTitle("Allow opening by itself")
                    .setMessage("Android only lets Mobile Dock open itself when your PC starts if you allow "
                            + "\"Display over other apps\". In the next screen, find Mobile Dock and turn it on.")
                    .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            WatchCompat.askOverlay(MainActivity.this);
                        }
                    }).setNegativeButton("Later", null).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !WatchCompat.batteryExempt(this)) {
            WatchCompat.askBatteryExemption(this);
            return;
        }
        if (sayWhenDone) toast("All permissions are set. Keep the phone charging for instant wake.");
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            updateWatchStatus();
            if (connectView != null && connectView.getVisibility() == View.VISIBLE) {
                root.postDelayed(this, 2000);
            }
        }
    };

    @SuppressWarnings("deprecation")
    private void updateWatchStatus() {
        if (watchStatus == null) return;
        StringBuilder sb = new StringBuilder();
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        sb.append("App version ").append(version).append('\n');
        if (!WatchService.enabled(this)) {
            sb.append("Wake on PC start is off.");
            watchStatus.setText(sb.toString());
            return;
        }
        sb.append("Watcher: ").append(WatchService.status);
        if (WatchService.lastCheck > 0) {
            sb.append(", checked ").append(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(WatchService.lastCheck)));
        }
        sb.append('\n');
        if (Build.VERSION.SDK_INT >= 29) {
            sb.append("Display over other apps: ").append(WatchCompat.canDrawOverlays(this) ? "allowed" : "NEEDED").append('\n');
        }
        if (Build.VERSION.SDK_INT >= 33) {
            sb.append("Notifications: ").append(WatchCompat.notificationsAllowed(this) ? "allowed" : "NEEDED").append('\n');
        }
        if (Build.VERSION.SDK_INT >= 23) {
            sb.append("Battery limits: ").append(WatchCompat.batteryExempt(this) ? "off" : "ON, may stop the watcher").append('\n');
        }
        android.content.Intent battery = registerReceiverForBattery();
        boolean plugged = battery != null && battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
        sb.append("Charging: ").append(plugged ? "yes" : "no, checks only every 60 seconds");
        watchStatus.setText(sb.toString());
    }

    @android.annotation.SuppressLint("UnspecifiedRegisterReceiverFlag") // sticky read, no receiver
    private Intent registerReceiverForBattery() {
        return registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    private void connectTyped() {
        String text = addressField.getText().toString().trim();
        if (text.length() == 0) return;
        int port = Connection.PORT;
        int colon = text.lastIndexOf(':');
        if (colon > 0 && text.indexOf(':') == colon) {
            try {
                port = Integer.parseInt(text.substring(colon + 1));
                text = text.substring(0, colon);
            } catch (NumberFormatException e) {
                toast("Bad port");
                return;
            }
        }
        client.connect(text, port);
    }

    private void scan() {
        connectStatus.setText("Looking for your PC");
        hostList.removeAllViews();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Discovery.Host> found = Discovery.scan(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        showHosts(found);
                    }
                });
            }
        }, "discovery").start();
    }

    private void showHosts(List<Discovery.Host> found) {
        hostList.removeAllViews();
        if (found.isEmpty()) {
            connectStatus.setText("No PC found. Is the Mobile Dock host running on the same Wi-Fi?");
            return;
        }
        connectStatus.setText("Tap your PC to connect");
        for (final Discovery.Host h : found) {
            String label = h.name + "   " + h.address + (store.isPaired(h.id) ? "   paired" : "");
            Button b = Ui.button(this, label);
            b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (store.isPaired(h.id)) client.connect(h.address, h.port);
                    else askPin(h.address, h.port, null);
                }
            });
            hostList.addView(b, Ui.fillWidth(this, 4));
        }
    }

    private void askPin(final String host, final int port, String why) {
        final EditText pin = new EditText(this);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("6 digit PIN");
        pin.setGravity(Gravity.CENTER);
        FrameLayout box = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, 0, p, 0);
        box.addView(pin);
        new AlertDialog.Builder(this).setTitle("Pair with this PC")
                .setMessage((why == null ? "" : why + "\n\n") + "On the PC, click Pair new phone and type the PIN it shows.")
                .setView(box)
                .setPositiveButton("Pair", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        String digits = pin.getText().toString().replaceAll("\\D", "");
                        if (digits.length() != 6) {
                            askPin(host, port, "The PIN has 6 digits.");
                            return;
                        }
                        client.pair(host, port, digits);
                    }
                }).setNegativeButton("Cancel", null).show();
    }

    // Dock -------------------------------------------------------------------

    private static final int APPS = 0, PLAYING = 1, SHORTCUTS = 2, CONTROLS = 3, CAPTURE = 4, TOUCH = 5,
            STATS = 6, SETTINGS = 7;
    private int current;
    private boolean fullscreen;
    private LinearLayout dockBar;
    private View exitFull;
    private CapturePanel capture;
    private NightView night;
    private boolean dimmed, swallowGesture, tapPending;
    private float tapX, tapY;
    private long tapAt;
    private ControlsPanel controls;
    private UpdatesPanel updates;

    private void showDock() {
        if (dockView == null) {
            dockView = buildDock();
            root.addView(dockView);
        }
        dockView.setVisibility(View.VISIBLE);
        if (connectView != null) connectView.setVisibility(View.GONE);
        dockBanner.setVisibility(View.GONE);
        apps.load();
        resetIdle();
    }

    /** Rebuilds the dock after an Appearance change. The connection stays up. */
    private void rebuildDock() {
        int keep = current;
        root.setBackground(Ui.background(this));
        if (dockView != null) root.removeView(dockView);
        if (connectView != null) root.removeView(connectView);
        connectView = null;
        dockView = null;
        panels.clear();
        tabs.clear();
        fullscreen = false;
        showDock();
        select(keep);
    }

    private View buildDock() {
        FrameLayout frame = new FrameLayout(this);
        LinearLayout dock = Ui.column(this);
        int pad = Ui.dp(this, 10);
        dock.setPadding(pad, pad, pad, Ui.dp(this, 6));

        dockBanner = Ui.text(this, "", 14, Ui.TEXT);
        dockBanner.setBackground(Ui.rounded(this, Ui.DANGER, 12));
        dockBanner.setGravity(Gravity.CENTER);
        dockBanner.setPadding(pad, pad, pad, pad);
        dockBanner.setVisibility(View.GONE);
        dock.addView(dockBanner, Ui.fillWidth(this, 2));

        content = new FrameLayout(this);
        apps = new AppsPanel(this, client, new AppsPanel.LaunchListener() {
            @Override
            public void launched(String appName) {
                // Opening a music app jumps to Now playing, like a car dashboard.
                if (MUSIC_APPS.matcher(appName).find()) select(PLAYING);
            }
        });
        media = new MediaPanel(this, client, new Runnable() {
            @Override
            public void run() {
                select(APPS);
            }
        });
        shortcuts = new ShortcutsPanel(this, client);
        stats = new StatsPanel(this, client);
        capture = new CapturePanel(this, client, new CapturePanel.Actions() {
            @Override
            public void screenshot() {
                MainActivity.this.screenshot();
            }

            @Override
            public void toggleRecording() {
                MainActivity.this.toggleRecording();
            }

            @Override
            public void pick(boolean picture) {
                pickToSend(picture);
            }
        });
        updates = new UpdatesPanel(this, client, updater);
        panels.add(apps);
        panels.add(media);
        panels.add(shortcuts);
        controls = new ControlsPanel(this, client, new Runnable() {
            @Override
            public void run() {
                leaveDock();
            }
        });
        panels.add(controls);
        panels.add(capture);
        panels.add(new TouchPanel(this, client, new SignPanel.Sender() {
            @Override
            public void sendPng(byte[] png) {
                sender.sendBytes("signature.png", png, "clipboard");
            }
        }));
        panels.add(stats);
        // Settings: Appearance on the left, Updates on the right.
        LinearLayout settings = Ui.row(this);
        settings.setGravity(Gravity.TOP);
        settings.addView(new AppearancePanel(this, WatchService.prefs(this), new Runnable() {
            @Override
            public void run() {
                rebuildDock();
            }
        }), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        ulp.leftMargin = Ui.dp(this, 16);
        settings.addView(updates, ulp);
        panels.add(settings);
        for (View p : panels) content.addView(p);
        capture.setRecording(recording);
        dock.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // The dock bar: eight tiles sharing the width, so all of them are always on screen.
        dockBar = Ui.row(this);
        int bp = Ui.dp(this, 6);
        dockBar.setPadding(bp, bp, bp, bp);
        dockBar.setBackground(Ui.rounded(this, Ui.SURFACE, 18));
        String[] names = {"Apps", "Playing", "Shortcuts", "Controls", "Capture", "Touch", "Stats", "Settings"};
        Icon.Kind[] kinds = {Icon.Kind.APPS, Icon.Kind.MUSIC, Icon.Kind.SHORTCUTS, Icon.Kind.SLIDERS,
            Icon.Kind.CAMERA, Icon.Kind.PEN, Icon.Kind.CHART, Icon.Kind.SUN};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout tile = Ui.dockTile(this, kinds[i], names[i]);
            tile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(index);
                }
            });
            tabs.add(tile);
            dockBar.addView(tile);
        }
        // Fullscreen sits at the end of the bar, so it is there on every page.
        ImageView full = Ui.iconButton(this, Icon.Kind.EXPAND, 44, 0x00000000, Ui.SURFACE_HI, Ui.MUTED);
        full.setContentDescription("Fullscreen");
        full.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFullscreen(true);
            }
        });
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
        flp.setMargins(Ui.dp(this, 4), 0, Ui.dp(this, 2), 0);
        dockBar.addView(full, flp);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.topMargin = Ui.dp(this, 8);
        dock.addView(dockBar, blp);
        frame.addView(dock);

        ImageView exit = Ui.iconButton(this, Icon.Kind.SHRINK, 44, Ui.SURFACE, Ui.SURFACE_HI, Ui.TEXT);
        exit.setContentDescription("Exit fullscreen");
        exit.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setFullscreen(false);
            }
        });
        FrameLayout.LayoutParams elp = new FrameLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44),
                Gravity.BOTTOM | Gravity.END);
        elp.setMargins(0, 0, Ui.dp(this, 12), Ui.dp(this, 12));
        exit.setVisibility(View.GONE);
        frame.addView(exit, elp);
        exitFull = exit;
        select(APPS);
        return frame;
    }

    /** Fullscreen hides the dock bar so the open page gets the whole screen. */
    private void setFullscreen(boolean on) {
        fullscreen = on;
        dockBar.setVisibility(on ? View.GONE : View.VISIBLE);
        exitFull.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    private void leaveDock() {
        WatchService.quietUntilOffline = true;  // do not reopen until the PC restarts
        client.disconnect();
        showConnect();
        scan();
    }

    private void select(int index) {
        current = index;
        for (int i = 0; i < panels.size(); i++) {
            panels.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
            Ui.setTileSelected(tabs.get(i), i == index);
        }
        if (index != APPS && apps.arranging()) apps.setArrange(false);
        media.setActive(index == PLAYING);
        stats.setActive(index == STATS);
        if (index == SHORTCUTS) shortcuts.load();
        if (index == CONTROLS) controls.load();
        if (index == SETTINGS) updates.refresh(false);
    }

    private void screenshot() {
        toast("Taking screenshot");
        client.request("screenshot", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                toast(r.optBoolean("ok") ? "Saved on PC: " + r.optString("path") : r.optString("error", "Failed"));
            }
        });
    }

    private void toggleRecording() {
        client.request(recording ? "record.stop" : "record.start", null, new DockClient.Callback() {
            @Override
            public void done(JSONObject r) {
                if (!r.optBoolean("ok")) {
                    toast(r.optString("error", "Failed"));
                    return;
                }
                setRecording(r.optBoolean("recording"));
                if (r.has("path")) toast("Saved on PC: " + r.optString("path"));
            }
        });
    }

    private void setRecording(boolean on) {
        recording = on;
        if (capture != null) capture.setRecording(on);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // Client events ------------------------------------------------------------

    @Override
    public void onState(int state, String message) {
        boolean docked = dockView != null && dockView.getVisibility() == View.VISIBLE;
        switch (state) {
            case DockClient.CONNECTING:
                if (docked) showBanner(message);
                else connectStatus.setText(message);
                break;
            case DockClient.CONNECTED:
                showDock();
                apps.setPc(client.hostName);
                final String connectedHost = client.host;
                client.request("host.info", null, new DockClient.Callback() {
                    @Override
                    public void done(JSONObject r) {
                        String mac = r.optString("mac", "");
                        if (r.optBoolean("ok") && mac.matches("[0-9a-f]{2}(:[0-9a-f]{2}){5}")) {
                            store.setMac(connectedHost, mac);
                        }
                    }
                });
                updater.check();
                shortcuts.load();
                if (pendingShare != null) {
                    sender.send(pendingShare, pendingMode);
                    pendingShare = null;
                }
                break;
            case DockClient.DISCONNECTED:
                if (docked) showBanner(message);
                else connectStatus.setText(message + ". Tap Search again to stop.");
                break;
            case DockClient.NEED_PIN:
                showConnect();
                connectStatus.setText(message);
                askPin(client.host, client.port, message);
                break;
            case DockClient.FAILED:
            default:
                CrashLog.note(this, "Connection failed: " + message);
                showConnect();
                connectStatus.setText(message);
                toast(message);
                break;
        }
    }

    private void showBanner(String message) {
        dockBanner.setText(message);
        dockBanner.setVisibility(View.VISIBLE);
    }

    @Override
    public void onEvent(JSONObject event) {
        String e = event.optString("e");
        if ("apps".equals(e) && apps != null) apps.load();
        if ("pads".equals(e) && shortcuts != null) shortcuts.load();
        if ("context".equals(e) && shortcuts != null) shortcuts.onContext(event);
    }

    // Sending to the PC ----------------------------------------------------------

    private void pickToSend(boolean picture) {
        Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType(picture ? "image/*" : "*/*");
        try {
            startActivityForResult(Intent.createChooser(pick, "Choose"), picture ? PICK_PICTURE : PICK_FILE);
        } catch (RuntimeException ex) {
            toast("No file picker on this phone");
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        if (request == PICK_PICTURE || request == PICK_FILE) {
            sender.send(data.getData(), request == PICK_PICTURE ? "clipboard" : "downloads");
        }
    }

    /** Something shared to Mobile Dock from another app, for example a photo from the gallery. */
    @SuppressWarnings("deprecation")
    private void handleShare(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        final Uri uri = (Uri) intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) {
            toast("Only files and pictures can be sent");
            return;
        }
        String type = intent.getType() == null ? "" : intent.getType();
        final boolean picture = type.startsWith("image/");
        // Always ask. Without this, any app on the phone could push files to your PC unseen.
        setIntent(new Intent());
        new AlertDialog.Builder(this).setTitle("Send to your PC?")
                .setMessage(picture ? "Put this picture on the PC clipboard, or save it in Downloads?"
                        : "Save this file in Downloads/Mobile Dock on your PC?")
                .setPositiveButton(picture ? "Clipboard" : "Send", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        sendShared(uri, picture ? "clipboard" : "downloads");
                    }
                })
                .setNeutralButton(picture ? "Downloads" : null, picture ? new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        sendShared(uri, "downloads");
                    }
                } : null)
                .setNegativeButton("Cancel", null).show();
    }

    private void sendShared(Uri uri, String mode) {
        if (client.isConnected()) {
            sender.send(uri, mode);
        } else {
            pendingShare = uri;
            pendingMode = mode;
            toast("Connecting to your PC to send it");
            String last = store.lastHost();
            if (last.length() > 0) client.connect(last, store.lastPort());
        }
    }

    @Override
    public void onPing(JSONObject reply) {
        if (reply.optBoolean("ok")) setRecording(reply.optBoolean("recording"));
    }
}
