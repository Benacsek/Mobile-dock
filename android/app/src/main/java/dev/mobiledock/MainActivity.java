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

/** Single screen app: a connect page, then the dock with a side rail and four panels. */
public final class MainActivity extends Activity implements DockClient.Listener {
    static final String EXTRA_WAKE = "wake";
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
    private StatsPanel stats;
    private FileSender sender;
    private Uri pendingShare;
    private String pendingMode;
    private static final int PICK_PICTURE = 11, PICK_FILE = 12;
    private MediaPanel media;
    private final List<LinearLayout> tabs = new ArrayList<LinearLayout>();
    private final List<View> panels = new ArrayList<View>();
    private LinearLayout recordButton;
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
        store = new Store(this);
        client = new DockClient(store, this);
        updater = new PhoneUpdater(this, client);
        sender = new FileSender(this, client);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
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
        String last = store.lastHost();
        if (last.length() > 0 && store.hasAnyPairing()) client.connect(last, store.lastPort());
        else scan();
        handleWake(getIntent());
        handleShare(getIntent());
        WatchService.sync(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleWake(intent);
        handleShare(intent);
    }

    /** Opened by the PC watcher: turn the screen on and connect to the PC that just started. */
    @SuppressWarnings("deprecation")
    private void handleWake(Intent intent) {
        if (intent == null || !intent.getBooleanExtra(EXTRA_WAKE, false)) return;
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        WatchService.dockVisible = true;
        immersive();
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

    /** Back returns to the Apps panel first, then leaves the app. */
    private void handleBack() {
        if (dockView != null && dockView.getVisibility() == View.VISIBLE && panels.size() > 0
                && panels.get(0).getVisibility() != View.VISIBLE) {
            select(0);
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

    private void showConnect() {
        if (connectView == null) connectView = buildConnect();
        if (connectView.getParent() == null) root.addView(connectView);
        connectView.setVisibility(View.VISIBLE);
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
        ImageView logo = new ImageView(this);
        logo.setImageDrawable(new Icon(Icon.Kind.APPS, Ui.ACCENT));
        brand.addView(logo, new LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)));
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
        left.addView(again, Ui.wrap(this, 4));
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

    /** Android 10 and newer need extra permissions before an app may open itself. */
    private void explainWakePermissions() {
        if (Build.VERSION.SDK_INT >= 33) WatchCompat.askNotifications(this);
        if (Build.VERSION.SDK_INT < 29 || WatchCompat.canDrawOverlays(this)) {
            if (Build.VERSION.SDK_INT >= 26) WatchCompat.askBatteryExemption(this);
            return;
        }
        new AlertDialog.Builder(this).setTitle("One more permission")
                .setMessage("To open by itself when your PC starts, Android needs you to allow Mobile Dock to "
                        + "display over other apps. Without it, you get a notification to tap instead.")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        WatchCompat.askOverlay(MainActivity.this);
                    }
                }).setNegativeButton("Use notification", null).show();
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

    private void showDock() {
        if (dockView == null) {
            dockView = buildDock();
            root.addView(dockView);
        }
        dockView.setVisibility(View.VISIBLE);
        if (connectView != null) connectView.setVisibility(View.GONE);
        dockBanner.setVisibility(View.GONE);
        apps.load();
    }

    private View buildDock() {
        LinearLayout dock = Ui.row(this);
        dock.setGravity(Gravity.TOP);
        int pad = Ui.dp(this, 8);
        dock.setPadding(pad, pad, pad, pad);

        LinearLayout rail = Ui.column(this);
        rail.setBackground(Ui.rounded(this, Ui.SURFACE, 18));
        rail.setPadding(pad, pad, pad, pad);
        String[] names = {"Apps", "Playing", "Shortcuts", "Stats", "Touchpad", "Keys"};
        Icon.Kind[] kinds = {Icon.Kind.APPS, Icon.Kind.MUSIC, Icon.Kind.SHORTCUTS, Icon.Kind.CHART,
            Icon.Kind.TOUCHPAD, Icon.Kind.KEYBOARD};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            LinearLayout item = Ui.railItem(this, kinds[i], names[i]);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(index);
                }
            });
            tabs.add(item);
            rail.addView(item, Ui.fillWidth(this, 2));
        }
        rail.addView(Ui.spacer(this));
        LinearLayout send = Ui.railItem(this, Icon.Kind.SEND, "Send");
        send.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                chooseSend();
            }
        });
        rail.addView(send, Ui.fillWidth(this, 2));
        LinearLayout shot = Ui.railItem(this, Icon.Kind.CAMERA, "Screenshot");
        shot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                screenshot();
            }
        });
        rail.addView(shot, Ui.fillWidth(this, 2));
        recordButton = Ui.railItem(this, Icon.Kind.RECORD, "Record");
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRecording();
            }
        });
        rail.addView(recordButton, Ui.fillWidth(this, 2));
        LinearLayout leave = Ui.railItem(this, Icon.Kind.POWER, "Disconnect");
        leave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                WatchService.quietUntilOffline = true;  // do not reopen until the PC restarts
                client.disconnect();
                showConnect();
                scan();
            }
        });
        rail.addView(leave, Ui.fillWidth(this, 2));
        // The rail scrolls on short screens so its bottom items never get cut off.
        android.widget.ScrollView railScroll = new android.widget.ScrollView(this);
        railScroll.setFillViewport(true);
        railScroll.setVerticalScrollBarEnabled(false);
        railScroll.addView(rail, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT));
        dock.addView(railScroll, new LinearLayout.LayoutParams(Ui.dp(this, 92), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout main = Ui.column(this);
        dockBanner = Ui.text(this, "", 14, Ui.TEXT);
        dockBanner.setBackground(Ui.rounded(this, Ui.DANGER, 8));
        dockBanner.setGravity(Gravity.CENTER);
        dockBanner.setPadding(pad, pad, pad, pad);
        dockBanner.setVisibility(View.GONE);
        main.addView(dockBanner, Ui.fillWidth(this, 4));
        content = new FrameLayout(this);
        apps = new AppsPanel(this, client, new AppsPanel.LaunchListener() {
            @Override
            public void launched(String appName) {
                // Opening a music app jumps to Now playing, like a car dashboard.
                if (MUSIC_APPS.matcher(appName).find()) select(1);
            }
        });
        media = new MediaPanel(this, client, new Runnable() {
            @Override
            public void run() {
                select(0);
            }
        });
        shortcuts = new ShortcutsPanel(this, client);
        stats = new StatsPanel(this, client);
        panels.add(apps);
        panels.add(media);
        panels.add(shortcuts);
        panels.add(stats);
        panels.add(new PadPanel(this, client));
        panels.add(new KeysPanel(this, client));
        for (View p : panels) content.addView(p);
        main.addView(content, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout.LayoutParams mlp = Ui.weight(1f);
        mlp.leftMargin = pad;
        dock.addView(main, mlp);
        select(0);
        return dock;
    }

    private void select(int index) {
        for (int i = 0; i < panels.size(); i++) {
            panels.get(i).setVisibility(i == index ? View.VISIBLE : View.GONE);
            Ui.setRailSelected(tabs.get(i), i == index);
        }
        media.setActive(index == 1);
        stats.setActive(index == 3);
        if (index == 2) shortcuts.load();
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
        if (recordButton == null) return;
        ImageView icon = (ImageView) recordButton.getChildAt(0);
        icon.setImageDrawable(new Icon(on ? Icon.Kind.STOP : Icon.Kind.RECORD, on ? Ui.DANGER : Ui.MUTED));
        ((TextView) recordButton.getChildAt(1)).setText(on ? "Stop" : "Record");
        ((TextView) recordButton.getChildAt(1)).setTextColor(on ? Ui.DANGER : Ui.MUTED);
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

    private void chooseSend() {
        new AlertDialog.Builder(this).setTitle("Send to PC")
                .setItems(new String[] {"Picture to the clipboard (paste with Ctrl+V)", "File to Downloads"},
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                                pick.addCategory(Intent.CATEGORY_OPENABLE);
                                pick.setType(which == 0 ? "image/*" : "*/*");
                                try {
                                    startActivityForResult(Intent.createChooser(pick, "Choose"),
                                            which == 0 ? PICK_PICTURE : PICK_FILE);
                                } catch (RuntimeException ex) {
                                    toast("No file picker on this phone");
                                }
                            }
                        }).show();
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
        final String mode = type.startsWith("image/") ? "clipboard" : "downloads";
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
