package dev.mobiledock;

import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;

import dev.mobiledock.proto.Crypto;

/** Finds PCs on the same Wi-Fi with a UDP broadcast. Only names and ports are shared here. */
final class Discovery {
    static final int UDP_PORT = 47801;

    static final class Host {
        final String address;
        final int port;
        final String id;
        final String name;
        final String os;

        Host(String address, int port, String id, String name, String os) {
            this.address = address;
            this.port = port;
            this.id = id;
            this.name = name;
            this.os = os;
        }
    }

    private Discovery() {
    }

    @SuppressWarnings("deprecation")
    private static InetAddress subnetBroadcast(Context context) {
        try {
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo dhcp = wifi == null ? null : wifi.getDhcpInfo();
            if (dhcp == null || dhcp.ipAddress == 0) return null;
            int broadcast = (dhcp.ipAddress & dhcp.netmask) | ~dhcp.netmask;
            byte[] quads = new byte[4];
            for (int k = 0; k < 4; k++) quads[k] = (byte) (broadcast >> (k * 8));
            return InetAddress.getByAddress(quads);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Blocking. Call from a background thread. */
    static List<Host> scan(Context context) {
        List<Host> found = new ArrayList<Host>();
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket();
            socket.setBroadcast(true);
            socket.setSoTimeout(400);
            byte[] probe = Crypto.utf8("MDOCK1?");
            List<InetAddress> targets = new ArrayList<InetAddress>();
            targets.add(InetAddress.getByName("255.255.255.255"));
            InetAddress subnet = subnetBroadcast(context);
            if (subnet != null) targets.add(subnet);
            long end = System.currentTimeMillis() + 2000;
            int round = 0;
            byte[] buf = new byte[2048];
            while (System.currentTimeMillis() < end) {
                if (round++ % 3 == 0) {
                    for (InetAddress t : targets) {
                        try {
                            socket.send(new DatagramPacket(probe, probe.length, t, UDP_PORT));
                        } catch (IOException ignored) {
                        }
                    }
                }
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                try {
                    JSONObject o = new JSONObject(new String(packet.getData(), 0, packet.getLength(), Crypto.UTF8));
                    if (!"mdock".equals(o.optString("t"))) continue;
                    String addr = packet.getAddress().getHostAddress();
                    String id = o.optString("host_id");
                    boolean dup = false;
                    for (Host h : found) dup |= h.id.equals(id);
                    if (!dup) {
                        found.add(new Host(addr, o.optInt("port", 47800), id, o.optString("host_name", addr),
                                o.optString("os")));
                    }
                } catch (JSONException ignored) {
                }
            }
        } catch (IOException ignored) {
        } finally {
            if (socket != null) socket.close();
        }
        return found;
    }
}
