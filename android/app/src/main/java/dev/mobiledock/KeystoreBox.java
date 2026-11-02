package dev.mobiledock;

import android.annotation.TargetApi;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import dev.mobiledock.proto.Crypto;

/**
 * Encrypts pairing keys with an AES key that lives in the Android Keystore (Android 6 and newer).
 * The Keystore key never leaves the phone's secure hardware, so a copy of the app data is useless.
 */
@TargetApi(23)
final class KeystoreBox {
    static final String PREFIX = "ks1:";
    private static final String ALIAS = "mobiledock_pairing";

    private KeystoreBox() {
    }

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }

    /** Returns the encrypted text, or null if the Keystore is not usable on this phone. */
    static String seal(String plain) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] out = Crypto.concat(c.getIV(), c.doFinal(Crypto.utf8(plain)));
            return PREFIX + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns the plain text, or null when it cannot be decrypted (for example after a phone reset). */
    static String open(String sealed) {
        try {
            byte[] raw = Base64.decode(sealed.substring(PREFIX.length()), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, raw, 0, 12));
            return new String(c.doFinal(raw, 12, raw.length - 12), Crypto.UTF8);
        } catch (Exception e) {
            return null;
        }
    }
}
