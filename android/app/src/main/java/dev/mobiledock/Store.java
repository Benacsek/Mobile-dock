package dev.mobiledock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;

import dev.mobiledock.proto.Connection;
import dev.mobiledock.proto.Crypto;

/** Pairing keys and last used address. App private storage, excluded from backups. */
final class Store implements Connection.Keys {
    private final SharedPreferences prefs;

    Store(Context context) {
        prefs = context.getSharedPreferences("dock", Context.MODE_PRIVATE);
    }

    private JSONObject pairings() {
        try {
            return new JSONObject(prefs.getString("pairings", "{}"));
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    synchronized void savePairing(String hostId, String hostName, Connection.Credentials cred) {
        JSONObject all = pairings();
        try {
            all.put(hostId, new JSONObject().put("device_id", cred.deviceId)
                    .put("secret", seal(Crypto.hex(cred.secret))).put("name", hostName));
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString("pairings", all.toString()).commit();
    }

    @Override
    public synchronized Connection.Credentials find(String hostId) {
        JSONObject all = pairings();
        JSONObject entry = all.optJSONObject(hostId);
        if (entry == null) return null;
        try {
            String stored = entry.getString("secret");
            String hex = open(stored);
            if (hex == null) return null;
            if (!stored.startsWith(KeystoreBox.PREFIX) && Build.VERSION.SDK_INT >= 23) {
                // Saved by an older version in plain text: encrypt it now.
                String sealed = seal(hex);
                if (!sealed.equals(stored)) {
                    entry.put("secret", sealed);
                    prefs.edit().putString("pairings", all.toString()).commit();
                }
            }
            return new Connection.Credentials(entry.getString("device_id"), Crypto.unhex(hex));
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    /** Encrypts with the Android Keystore on Android 6 and newer. Older phones rely on app-private storage. */
    private static String seal(String hex) {
        if (Build.VERSION.SDK_INT < 23) return hex;
        String sealed = KeystoreBox.seal(hex);
        return sealed == null ? hex : sealed;
    }

    private static String open(String stored) {
        if (!stored.startsWith(KeystoreBox.PREFIX)) return stored;
        return Build.VERSION.SDK_INT >= 23 ? KeystoreBox.open(stored) : null;
    }

    synchronized boolean isPaired(String hostId) {
        return pairings().has(hostId);
    }

    synchronized boolean hasAnyPairing() {
        return pairings().length() > 0;
    }

    synchronized void forget(String hostId) {
        JSONObject all = pairings();
        all.remove(hostId);
        prefs.edit().putString("pairings", all.toString()).commit();
    }

    synchronized void forgetAll() {
        prefs.edit().remove("pairings").remove("last_host").commit();
    }

    @SuppressWarnings("unchecked")
    synchronized String pairedNames() {
        StringBuilder sb = new StringBuilder();
        JSONObject all = pairings();
        Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            JSONObject e = all.optJSONObject(keys.next());
            if (e == null) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.optString("name", "PC"));
        }
        return sb.toString();
    }

    /** MAC address of the PC at this address, for Wake-on-LAN. Learned from the PC while connected. */
    void setMac(String host, String mac) {
        prefs.edit().putString("mac_" + host, mac).commit();
    }

    String mac(String host) {
        return prefs.getString("mac_" + host, "");
    }

    void setLast(String host, int port) {
        prefs.edit().putString("last_host", host).putInt("last_port", port).commit();
    }

    String lastHost() {
        return prefs.getString("last_host", "");
    }

    int lastPort() {
        return prefs.getInt("last_port", Connection.PORT);
    }
}
