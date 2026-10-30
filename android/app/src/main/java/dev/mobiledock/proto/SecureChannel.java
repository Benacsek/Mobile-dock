package dev.mobiledock.proto;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.security.GeneralSecurityException;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-CBC with HMAC-SHA256 (encrypt then MAC) and implicit sequence numbers.
 * Every primitive is available on every Android version without extra libraries.
 */
public final class SecureChannel {
    private final DataInputStream in;
    private final OutputStream out;
    private final byte[] txEnc, txMac, rxEnc, rxMac;
    private long txSeq, rxSeq;

    public SecureChannel(DataInputStream in, OutputStream out, byte[] material) {
        this.in = in;
        this.out = out;
        txEnc = Crypto.slice(material, 0, 32);
        txMac = Crypto.slice(material, 32, 64);
        rxEnc = Crypto.slice(material, 64, 96);
        rxMac = Crypto.slice(material, 96, 128);
    }

    private static byte[] seq(long n) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) n;
            n >>>= 8;
        }
        return b;
    }

    public synchronized void send(String json) throws IOException {
        try {
            byte[] iv = Crypto.random(16);
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(txEnc, "AES"), new IvParameterSpec(iv));
            byte[] ct = c.doFinal(Crypto.utf8(json));
            byte[] tag = Crypto.hmac(txMac, seq(txSeq), iv, ct);
            txSeq++;
            Frames.write(out, Crypto.concat(iv, ct, tag));
        } catch (GeneralSecurityException e) {
            throw new IOException("encrypt failed: " + e);
        }
    }

    public String recv() throws IOException, DockException {
        byte[] frame = Frames.read(in);
        if (frame.length < 64 || (frame.length - 48) % 16 != 0) throw new DockException("protocol", "Bad frame");
        byte[] iv = Crypto.slice(frame, 0, 16);
        byte[] ct = Crypto.slice(frame, 16, frame.length - 32);
        byte[] tag = Crypto.slice(frame, frame.length - 32, frame.length);
        if (!Crypto.equal(tag, Crypto.hmac(rxMac, seq(rxSeq), iv, ct))) {
            throw new DockException("protocol", "Message failed integrity check");
        }
        rxSeq++;
        try {
            Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(rxEnc, "AES"), new IvParameterSpec(iv));
            return new String(c.doFinal(ct), Crypto.UTF8);
        } catch (GeneralSecurityException e) {
            throw new DockException("protocol", "Decrypt failed");
        }
    }
}
