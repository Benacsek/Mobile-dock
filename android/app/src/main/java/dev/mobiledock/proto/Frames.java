package dev.mobiledock.proto;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;

/** 4-byte big-endian length prefix, then payload. */
public final class Frames {
    public static final int MAX_FRAME = 1 << 20;

    private Frames() {
    }

    public static byte[] read(DataInputStream in) throws IOException, DockException {
        int len = in.readInt();
        if (len <= 0 || len > MAX_FRAME) throw new DockException("protocol", "Bad frame length");
        byte[] data = new byte[len];
        in.readFully(data);
        return data;
    }

    public static void write(OutputStream out, byte[] payload) throws IOException {
        int n = payload.length;
        byte[] buf = new byte[4 + n];
        buf[0] = (byte) (n >>> 24);
        buf[1] = (byte) (n >>> 16);
        buf[2] = (byte) (n >>> 8);
        buf[3] = (byte) n;
        System.arraycopy(payload, 0, buf, 4, n);
        out.write(buf);
        out.flush();
    }
}
