package dev.mobiledock;

import android.content.Context;
import android.content.SharedPreferences;

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
                    .put("secret", Crypto.hex(cred.secret)).put("name", hostName));
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString("pairings", all.toString()).commit();
    }

    @Override
    public synchronized Connection.Credentials find(String hostId) {
        JSONObject entry = pairings().optJSONObject(hostId);
        if (entry == null) return null;
        try {
            return new Connection.Credentials(entry.getString("device_id"), Crypto.unhex(entry.getString("secret")));
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
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
