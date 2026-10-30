package dev.mobiledock.proto;

import java.math.BigInteger;

/** SPAKE2 over the RFC 3526 2048-bit MODP group. The phone is always the client. */
public final class Spake2 {
    public static final BigInteger P = new BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD1"
            + "29024E088A67CC74020BBEA63B139B22514A08798E3404DD"
            + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245"
            + "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
            + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3D"
            + "C2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
            + "83655D23DCA3AD961C62F356208552BB9ED529077096966D"
            + "670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
            + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9"
            + "DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
            + "15728E5A8AACAA68FFFFFFFFFFFFFFFF", 16);
    public static final BigInteger Q = P.subtract(BigInteger.ONE).shiftRight(1);
    private static final BigInteger G = BigInteger.valueOf(2);
    private static final int ELEMENT_LEN = 256;
    private static final BigInteger M = hashToGroup("MobileDock SPAKE2 M");
    private static final BigInteger N = hashToGroup("MobileDock SPAKE2 N");

    private final BigInteger w;
    private final BigInteger x;
    public final BigInteger publicValue;

    public Spake2(String pin) {
        w = new BigInteger(1, Crypto.sha256(Crypto.utf8("MobileDock PIN v1"), Crypto.utf8(pin))).mod(Q);
        BigInteger r;
        do {
            r = new BigInteger(1, Crypto.random(ELEMENT_LEN)).mod(Q);
        } while (r.compareTo(BigInteger.ONE) <= 0);
        x = r;
        publicValue = G.modPow(x, P).multiply(M.modPow(w, P)).mod(P);
    }

    private static BigInteger hashToGroup(String label) {
        byte[][] blocks = new byte[9][];
        for (int i = 0; i < 9; i++) blocks[i] = Crypto.sha256(Crypto.utf8(label), new byte[] {(byte) i});
        BigInteger v = new BigInteger(1, Crypto.concat(blocks)).mod(P);
        return v.modPow(BigInteger.valueOf(2), P);
    }

    static boolean validElement(BigInteger v) {
        return v.compareTo(BigInteger.ONE) > 0
                && v.compareTo(P.subtract(BigInteger.ONE)) < 0
                && v.modPow(Q, P).equals(BigInteger.ONE);
    }

    static byte[] fixed(BigInteger v) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[ELEMENT_LEN];
        int copy = Math.min(raw.length, ELEMENT_LEN);
        System.arraycopy(raw, raw.length - copy, out, ELEMENT_LEN - copy, copy);
        return out;
    }

    static byte[] lp(byte[] data) {
        int n = data.length;
        return Crypto.concat(new byte[] {(byte) (n >>> 24), (byte) (n >>> 16), (byte) (n >>> 8), (byte) n}, data);
    }

    /** Returns [clientConfirm, serverConfirm, sessionMaterial]. */
    public byte[][] finish(BigInteger serverPublic) throws DockException {
        if (!validElement(serverPublic)) throw new DockException("protocol", "Invalid key from PC");
        BigInteger blind = N.modPow(w, P).modInverse(P);
        BigInteger z = serverPublic.multiply(blind).mod(P).modPow(x, P);
        byte[] transcript = Crypto.concat(
                lp(Crypto.utf8("MobileDock-SPAKE2-v1")),
                lp(fixed(publicValue)), lp(fixed(serverPublic)), lp(fixed(z)), lp(fixed(w)));
        byte[] k = Crypto.sha256(transcript);
        byte[] material = Crypto.hkdf(k, new byte[0], Crypto.utf8("mdock pair confirm v1"), 64);
        return new byte[][] {
            Crypto.hmac(Crypto.slice(material, 0, 32), Crypto.utf8("client")),
            Crypto.hmac(Crypto.slice(material, 32, 64), Crypto.utf8("server")),
            Crypto.hkdf(k, new byte[0], Crypto.utf8("mdock session v1"), 128),
        };
    }
}
