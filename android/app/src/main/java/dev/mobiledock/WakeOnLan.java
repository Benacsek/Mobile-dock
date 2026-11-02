package dev.mobiledock;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

/** Sends the Wake-on-LAN "magic packet" that turns on a sleeping or switched-off PC. */
final class WakeOnLan {
    private WakeOnLan() {
    }

    /** 6 bytes of 0xFF, then the MAC address 16 times. */
    static byte[] packet(String mac) {
        String[] parts = mac.split("[:-]");
        if (parts.length != 6) throw new IllegalArgumentException("Bad MAC address");
        byte[] addr = new byte[6];
        for (int i = 0; i < 6; i++) addr[i] = (byte) Integer.parseInt(parts[i], 16);
        byte[] out = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) out[i] = (byte) 0xFF;
        for (int i = 6; i < out.length; i++) out[i] = addr[(i - 6) % 6];
        return out;
    }

    /** Broadcasts to the whole network and to the PC's own /24 subnet, on the two usual ports. */
    static void send(String mac, String lastHost) throws java.io.IOException {
        byte[] data = packet(mac);
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setBroadcast(true);
            String[] targets = {"255.255.255.255", subnetBroadcast(lastHost)};
            for (String target : targets) {
                if (target == null) continue;
                InetAddress to = InetAddress.getByName(target);
                for (int port : new int[] {9, 7}) socket.send(new DatagramPacket(data, data.length, to, port));
            }
        } finally {
            socket.close();
        }
    }

    private static String subnetBroadcast(String host) {
        if (host == null || !host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}") || host.startsWith("127.")) {
            return null;
        }
        return host.substring(0, host.lastIndexOf('.')) + ".255";
    }
}
