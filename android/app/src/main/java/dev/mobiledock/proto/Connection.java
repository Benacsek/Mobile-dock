package dev.mobiledock.proto;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;

import org.json.JSONException;
import org.json.JSONObject;

/** One authenticated, encrypted connection to a PC. Blocking. Use from a background thread. */
public final class Connection {
    public static final int PORT = 47800;
    public static final int VERSION = 1;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int HANDSHAKE_TIMEOUT_MS = 15000;
    public static final int READ_TIMEOUT_MS = 45000;

    /** Finds the stored key for a PC. Return null when this phone is not paired with it. */
    public interface Keys {
        Credentials find(String hostId);
    }

    public static final class Credentials {
        public final String deviceId;
        public final byte[] secret;

        public Credentials(String deviceId, byte[] secret) {
            this.deviceId = deviceId;
            this.secret = secret;
        }
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private SecureChannel channel;
    public String hostId = "";
    public String hostName = "";
    public String hostOs = "";
    /** Set only after a successful PIN pairing. The caller must store it. */
    public Credentials newCredentials;

    private Connection(String host, int port) throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
        socket.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
        out = new BufferedOutputStream(socket.getOutputStream());
    }

    private void sendPlain(JSONObject obj) throws IOException {
        Frames.write(out, Crypto.utf8(obj.toString()));
    }

    private JSONObject recvPlain(String expected) throws IOException, DockException {
        JSONObject obj = parse(new String(Frames.read(in), Crypto.UTF8));
        String t = obj.optString("t");
        if ("error".equals(t)) throw new DockException(obj.optString("code"), obj.optString("message", "Error"));
        if (!expected.equals(t)) throw new DockException("protocol", "Unexpected reply from PC");
        return obj;
    }

    private static JSONObject parse(String s) throws DockException {
        try {
            return new JSONObject(s);
        } catch (JSONException e) {
            throw new DockException("protocol", "Bad message");
        }
    }

    private void identity(JSONObject obj) {
        hostId = obj.optString("host_id");
        hostName = obj.optString("host_name", "PC");
        hostOs = obj.optString("os");
    }

    /** Pairs using the PIN shown on the PC. */
    public static Connection pair(String host, int port, String pin, String deviceName)
            throws IOException, DockException {
        Connection c = new Connection(host, port);
        try {
            Spake2 spake = new Spake2(pin);
            JSONObject hello = new JSONObject();
            hello.put("v", VERSION).put("t", "pair").put("name", deviceName).put("X", spake.publicValue.toString(16));
            c.sendPlain(hello);
            JSONObject reply = c.recvPlain("pair_r");
            byte[][] keys = spake.finish(new BigInteger(reply.getString("Y"), 16));
            c.sendPlain(new JSONObject().put("t", "pair_c").put("c", Crypto.hex(keys[0])));
            JSONObject ok = c.recvPlain("pair_ok");
            if (!Crypto.equal(Crypto.unhex(ok.getString("c")), keys[1])) {
                throw new DockException("bad_pin", "The PC could not prove it knows the PIN");
            }
            c.channel = new SecureChannel(c.in, c.out, keys[2]);
            JSONObject paired = c.recv();
            if (!"paired".equals(paired.optString("t"))) throw new DockException("protocol", "Pairing failed");
            c.identity(paired);
            byte[] secret = Crypto.unhex(paired.getString("secret"));
            if (secret.length != 32) throw new DockException("protocol", "Bad key from PC");
            c.newCredentials = new Credentials(paired.getString("device_id"), secret);
            c.socket.setSoTimeout(READ_TIMEOUT_MS);
            return c;
        } catch (JSONException e) {
            c.close();
            throw new DockException("protocol", "Bad message");
        } catch (IOException | DockException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    /** Reconnects to a PC this phone already paired with. */
    public static Connection connect(String host, int port, Keys keys) throws IOException, DockException {
        Connection c = new Connection(host, port);
        try {
            byte[] nc = Crypto.random(32);
            c.sendPlain(new JSONObject().put("v", VERSION).put("t", "hello").put("nc", Crypto.hex(nc)));
            JSONObject challenge = c.recvPlain("challenge");
            c.identity(challenge);
            Credentials cred = keys.find(c.hostId);
            if (cred == null) throw new DockException("not_paired", "Not paired with " + c.hostName);
            byte[] ns = Crypto.unhex(challenge.getString("ns"));
            byte[] salt = Crypto.concat(nc, ns);
            byte[] auth = Crypto.hkdf(cred.secret, salt, Crypto.utf8("mdock auth v1|" + cred.deviceId), 64);
            byte[] session = Crypto.hkdf(cred.secret, salt, Crypto.utf8("mdock session v1|" + cred.deviceId), 128);
            byte[] clientMac = Crypto.hmac(Crypto.slice(auth, 0, 32), Crypto.utf8("client"));
            byte[] serverMac = Crypto.hmac(Crypto.slice(auth, 32, 64), Crypto.utf8("server"));
            c.sendPlain(new JSONObject().put("t", "auth").put("device_id", cred.deviceId)
                    .put("mac", Crypto.hex(clientMac)));
            JSONObject ok = c.recvPlain("auth_ok");
            if (!Crypto.equal(Crypto.unhex(ok.getString("mac")), serverMac)) {
                throw new DockException("auth_failed", "PC failed to prove its identity");
            }
            c.channel = new SecureChannel(c.in, c.out, session);
            c.socket.setSoTimeout(READ_TIMEOUT_MS);
            return c;
        } catch (JSONException e) {
            c.close();
            throw new DockException("protocol", "Bad message");
        } catch (IOException | DockException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    public void send(JSONObject obj) throws IOException {
        channel.send(obj.toString());
    }

    public JSONObject recv() throws IOException, DockException {
        return parse(channel.recv());
    }

    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}
