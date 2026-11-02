package dev.mobiledock;

import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import dev.mobiledock.proto.Connection;
import dev.mobiledock.proto.DockException;

/**
 * Keeps one connection to a PC alive. Network work runs on a background thread.
 * Listener and callbacks always run on the main thread.
 */
final class DockClient {
    static final int CONNECTING = 0, CONNECTED = 1, DISCONNECTED = 2, NEED_PIN = 3, FAILED = 4;

    interface Listener {
        void onState(int state, String message);

        void onPing(JSONObject reply);

        /** A message the PC sent without being asked, for example {"t":"event","e":"apps"}. */
        void onEvent(JSONObject event);
    }

    interface Callback {
        void done(JSONObject reply);
    }

    private final Store store;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler net;
    private final Map<Integer, Callback> pending = new HashMap<Integer, Callback>();
    private int nextId = 1;
    private volatile Connection conn;
    private volatile boolean wanted;
    private int generation;
    String host = "";
    int port = Connection.PORT;
    String hostName = "";
    String hostOs = "";

    private float moveX, moveY;
    private boolean moveQueued;

    DockClient(Store store, Listener listener) {
        this.store = store;
        this.listener = listener;
        HandlerThread thread = new HandlerThread("dock-net");
        thread.start();
        net = new Handler(thread.getLooper());
    }

    boolean isConnected() {
        return conn != null || Demo.on;
    }

    private void state(final int s, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onState(s, message);
            }
        });
    }

    static String deviceName() {
        String model = Build.MODEL == null ? "Android" : Build.MODEL;
        String maker = Build.MANUFACTURER == null ? "" : Build.MANUFACTURER;
        return model.toLowerCase().startsWith(maker.toLowerCase()) ? model : maker + " " + model;
    }

    /** Connects with stored keys. Reports NEED_PIN when this PC is unknown. */
    void connect(String host, int port) {
        start(host, port, null);
    }

    /** Pairs with the PIN the PC shows, then stays connected. */
    void pair(String host, int port, String pin) {
        start(host, port, pin);
    }

    private void start(final String h, final int p, final String pin) {
        disconnect();
        wanted = true;
        host = h;
        port = p;
        final int gen = ++generation;
        state(CONNECTING, "Connecting to " + h);
        net.post(new Runnable() {
            @Override
            public void run() {
                open(gen, pin);
            }
        });
    }

    private void open(final int gen, String pin) {
        if (!wanted || gen != generation) return;
        try {
            Connection c;
            if (pin != null) {
                c = Connection.pair(host, port, pin, deviceName());
                store.savePairing(c.hostId, c.hostName, c.newCredentials);
            } else {
                c = Connection.connect(host, port, store);
            }
            if (!wanted || gen != generation) {
                c.close();
                return;
            }
            conn = c;
            hostName = c.hostName;
            hostOs = c.hostOs;
            store.setLast(host, port);
            state(CONNECTED, hostName);
            startReader(gen, c);
            schedulePing(gen);
        } catch (DockException e) {
            if ("not_paired".equals(e.code) || "auth_failed".equals(e.code)) {
                wanted = false;
                state(NEED_PIN, e.getMessage());
            } else if ("throttled".equals(e.code) || "bad_pin".equals(e.code) || "not_pairing".equals(e.code)
                    || "version".equals(e.code) || "busy".equals(e.code) || pin != null) {
                wanted = false;
                state(FAILED, e.getMessage());
            } else {
                retry(gen, e.getMessage());
            }
        } catch (IOException e) {
            if (pin != null) {
                wanted = false;
                state(FAILED, "Could not reach the PC. " + e.getMessage());
                return;
            }
            retry(gen, "PC not reachable");
        }
    }

    private void retry(final int gen, String why) {
        if (!wanted || gen != generation) return;
        state(DISCONNECTED, why + ". Retrying");
        net.postDelayed(new Runnable() {
            @Override
            public void run() {
                open(gen, null);
            }
        }, 3000);
    }

    private void startReader(final int gen, final Connection c) {
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    while (true) {
                        final JSONObject msg = c.recv();
                        if (!msg.has("re")) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    listener.onEvent(msg);
                                }
                            });
                            continue;
                        }
                        final Callback cb;
                        synchronized (pending) {
                            cb = pending.remove(msg.optInt("re"));
                        }
                        if (cb != null) {
                            main.post(new Runnable() {
                                @Override
                                public void run() {
                                    cb.done(msg);
                                }
                            });
                        }
                    }
                } catch (IOException | DockException e) {
                    lost(gen, c);
                }
            }
        }, "dock-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void lost(final int gen, Connection c) {
        c.close();
        if (conn == c) conn = null;
        failPending();
        if (wanted && gen == generation) {
            net.post(new Runnable() {
                @Override
                public void run() {
                    retry(gen, "Connection lost");
                }
            });
        }
    }

    private void failPending() {
        final Map<Integer, Callback> copy;
        synchronized (pending) {
            copy = new HashMap<Integer, Callback>(pending);
            pending.clear();
        }
        for (final Callback cb : copy.values()) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    cb.done(error("Not connected"));
                }
            });
        }
    }

    private void schedulePing(final int gen) {
        net.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (gen != generation || conn == null) return;
                request("ping", null, new Callback() {
                    @Override
                    public void done(JSONObject reply) {
                        listener.onPing(reply);
                    }
                });
                schedulePing(gen);
            }
        }, 15000);
    }

    void disconnect() {
        wanted = false;
        generation++;
        final Connection c = conn;
        conn = null;
        if (c != null) {
            net.post(new Runnable() {
                @Override
                public void run() {
                    c.close();
                }
            });
        }
        failPending();
    }

    static JSONObject error(String message) {
        JSONObject o = new JSONObject();
        try {
            o.put("ok", false).put("error", message);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** Sends a command. The callback gets the reply, or ok=false on failure. Callback may be null. */
    void request(final String cmd, final JSONObject args, final Callback cb) {
        if (Demo.on) {
            if (cb != null) {
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.done(Demo.reply(cmd, args));
                    }
                });
            }
            return;
        }
        final JSONObject msg;
        try {
            msg = args == null ? new JSONObject() : new JSONObject(args.toString());
            msg.put("t", cmd);
            if (cb != null) {
                synchronized (pending) {
                    int id = nextId++;
                    msg.put("id", id);
                    pending.put(id, cb);
                }
            }
        } catch (JSONException e) {
            return;
        }
        net.post(new Runnable() {
            @Override
            public void run() {
                Connection c = conn;
                if (c == null) {
                    if (cb != null) {
                        synchronized (pending) {
                            pending.remove(msg.optInt("id"));
                        }
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                cb.done(error("Not connected"));
                            }
                        });
                    }
                    return;
                }
                try {
                    c.send(msg);
                } catch (IOException e) {
                    c.close();
                }
            }
        });
    }

    void fire(String cmd, JSONObject args) {
        request(cmd, args, null);
    }

    static JSONObject args(Object... kv) {
        JSONObject o = new JSONObject();
        try {
            for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        } catch (JSONException ignored) {
        }
        return o;
    }

    /** Mouse moves are merged and sent at most every 12 ms to keep the link responsive. */
    void moveBy(float dx, float dy) {
        synchronized (this) {
            moveX += dx;
            moveY += dy;
            if (moveQueued) return;
            moveQueued = true;
        }
        net.postDelayed(new Runnable() {
            @Override
            public void run() {
                int x, y;
                synchronized (DockClient.this) {
                    x = (int) moveX;
                    y = (int) moveY;
                    moveX -= x;
                    moveY -= y;
                    moveQueued = false;
                }
                if (x != 0 || y != 0) fire("mouse.move", args("dx", x, "dy", y));
            }
        }, 12);
    }
}
