package dev.mobiledock.proto;

/** An error with a machine readable code, for example bad_pin or auth_failed. */
public class DockException extends Exception {
    public final String code;

    public DockException(String code, String message) {
        super(message);
        this.code = code;
    }
}
