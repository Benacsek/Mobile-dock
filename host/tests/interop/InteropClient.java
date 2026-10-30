import dev.mobiledock.proto.Connection;

import org.json.JSONObject;

/** Drives the Android protocol code against a real Python host. Used by test_interop.py. */
public class InteropClient {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        String pin = args[1];
        Connection c = Connection.pair("127.0.0.1", port, pin, "Interop phone");
        final Connection.Credentials cred = c.newCredentials;
        c.send(new JSONObject().put("id", 1).put("t", "apps.pin").put("app", "code.desktop"));
        c.send(new JSONObject().put("id", 2).put("t", "ping"));
        System.out.println("pin:" + c.recv().getBoolean("ok"));
        System.out.println("ping:" + c.recv().getBoolean("ok"));
        c.close();

        Connection again = Connection.connect("127.0.0.1", port, new Connection.Keys() {
            @Override
            public Connection.Credentials find(String hostId) {
                return cred;
            }
        });
        again.send(new JSONObject().put("id", 3).put("t", "apps.pinned"));
        JSONObject r = again.recv();
        System.out.println("pinned:" + r.getJSONArray("apps").getJSONObject(0).getString("id"));
        System.out.println("host:" + again.hostName);
        again.close();
    }
}
