package dev.mobiledock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
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
    private Store store;
    private DockClient client;
    private FrameLayout root;
    private View connectView, dockView;
    private TextView connectStatus, dockBanner;
    private LinearLayout hostList;
    private EditText addressField;
    private FrameLayout content;
    private AppsPanel apps;
    private MediaPanel media;
    private final List<Button> tabs = new ArrayList<Button>();
    private final List<View> panels = new ArrayList<View>();
    private Button recordButton;
    private boolean recording;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = new Store(this);
        client = new DockClient(store, this);
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        setContentView(root);
        showConnect();
        String last = store.lastHost();
        if (last.length() > 0 && store.hasAnyPairing()) client.connect(last, store.lastPort());
        else scan();
    }

    @Override
    protected void onResume() {
        super.onResume();
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

    @Override
    public void onBackPressed() {
        if (dockView != null && dockView.getVisibility() == View.VISIBLE && panels.size() > 0
                && panels.get(0).getVisibility() != View.VISIBLE) {
            select(0);
            return;
        }
        super.onBackPressed();
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
        int pad = Ui.dp(this, 24);
        page.setPadding(pad, pad, pad, pad);

        LinearLayout left = Ui.column(this);
        left.addView(Ui.title(this, "Mobile Dock"));
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
        page.addView(right, Ui.weight(1f));
        return page;
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
        rail.setBackground(Ui.rounded(this, Ui.SURFACE, 14));
        rail.setPadding(pad, pad, pad, pad);
        String[] names = {"Apps", "Media", "Touchpad", "Keys"};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            Button b = Ui.button(this, names[i]);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    select(index);
                }
            });
            tabs.add(b);
            rail.addView(b, Ui.fillWidth(this, 3));
        }
        rail.addView(Ui.spacer(this));
        Button shot = Ui.button(this, "Screenshot");
        shot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                screenshot();
            }
        });
        rail.addView(shot, Ui.fillWidth(this, 3));
        recordButton = Ui.button(this, "Record");
        recordButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleRecording();
            }
        });
        rail.addView(recordButton, Ui.fillWidth(this, 3));
        Button leave = Ui.button(this, "Disconnect");
        leave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.disconnect();
                showConnect();
                scan();
            }
        });
        rail.addView(leave, Ui.fillWidth(this, 3));
        dock.addView(rail, new LinearLayout.LayoutParams(Ui.dp(this, 132), LinearLayout.LayoutParams.MATCH_PARENT));

        LinearLayout main = Ui.column(this);
        dockBanner = Ui.text(this, "", 14, Ui.TEXT);
        dockBanner.setBackground(Ui.rounded(this, Ui.DANGER, 8));
        dockBanner.setGravity(Gravity.CENTER);
        dockBanner.setPadding(pad, pad, pad, pad);
        dockBanner.setVisibility(View.GONE);
        main.addView(dockBanner, Ui.fillWidth(this, 4));
        content = new FrameLayout(this);
        apps = new AppsPanel(this, client);
        media = new MediaPanel(this, client);
        panels.add(apps);
        panels.add(media);
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
            tabs.get(i).setSelected(i == index);
        }
        media.setActive(index == 1);
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
        recordButton.setText(on ? "Stop recording" : "Record");
        recordButton.setBackground(on ? Ui.pressable(this, Ui.DANGER, 0xFFB83A3E, 10)
                : Ui.pressable(this, Ui.SURFACE_HI, 0xFF343A46, 10));
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
    public void onPing(JSONObject reply) {
        if (reply.optBoolean("ok")) setRecording(reply.optBoolean("recording"));
    }
}
