package dev.mobiledock.proto;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Hashing, HMAC, HKDF and randomness. Mirrors host/mobiledock_host/protocol.py. */
public final class Crypto {
    public static final Charset UTF8 = Charset.forName("UTF-8");
    private static final SecureRandom FALLBACK = new SecureRandom();

    private Crypto() {
    }

    /**
     * Reads the kernel CSPRNG directly. Android 4.1 to 4.3 shipped a badly seeded
     * SecureRandom, so we do not depend on it when /dev/urandom is readable.
     */
    public static byte[] random(int n) {
        byte[] out = new byte[n];
        FileInputStream in = null;
        try {
            in = new FileInputStream("/dev/urandom");
            int off = 0;
            while (off < n) {
                int r = in.read(out, off, n - off);
                if (r < 0) throw new IOException("eof");
                off += r;
            }
            return out;
        } catch (IOException e) {
            FALLBACK.nextBytes(out);
            return out;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    public static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (byte[] p : parts) md.update(p);
            return md.digest();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hmac(byte[] key, byte[]... parts) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            for (byte[] p : parts) mac.update(p);
            return mac.doFinal();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static byte[] hkdf(byte[] ikm, byte[] salt, byte[] info, int length) {
        byte[] prk = hmac(salt.length == 0 ? new byte[32] : salt, ikm);
        byte[] out = new byte[length];
        byte[] block = new byte[0];
        int pos = 0;
        for (int counter = 1; pos < length; counter++) {
            block = hmac(prk, block, info, new byte[] {(byte) counter});
            int take = Math.min(block.length, length - pos);
            System.arraycopy(block, 0, out, pos, take);
            pos += take;
        }
        return out;
    }

    /** Constant-time comparison. */
    public static boolean equal(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    public static byte[] utf8(String s) {
        return s.getBytes(UTF8);
    }

    public static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) len += p.length;
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    public static byte[] slice(byte[] src, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(src, from, out, 0, out.length);
        return out;
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    public static String hex(byte[] data) {
        char[] out = new char[data.length * 2];
        for (int i = 0; i < data.length; i++) {
            out[i * 2] = HEX[(data[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[data[i] & 0xF];
        }
        return new String(out);
    }

    public static byte[] unhex(String s) {
        if (s == null || s.length() % 2 != 0) throw new IllegalArgumentException("bad hex");
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) throw new IllegalArgumentException("bad hex");
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }
}
