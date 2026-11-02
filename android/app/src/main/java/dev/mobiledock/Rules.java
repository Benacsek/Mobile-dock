package dev.mobiledock;

/**
 * Small decisions with no Android code in them, so plain unit tests can check them.
 */
final class Rules {
    private Rules() {
    }

    /** Private network addresses only, the same ranges the PC accepts. Used for QR pairing links. */
    static boolean isLocalAddress(String host) {
        if (host == null || !host.matches("\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}")) return false;
        String[] p = host.split("\\.");
        for (String part : p) if (Integer.parseInt(part) > 255) return false;
        int a = Integer.parseInt(p[0]), b = Integer.parseInt(p[1]);
        return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)
                || (a == 169 && b == 254) || (a == 100 && b >= 64 && b <= 127);
    }

    /**
     * Columns for the app grid: as many as fit, then spread evenly over the rows needed,
     * so 8 apps make two rows of 4 instead of 6 and a ragged 2. Never fewer than 3.
     */
    static int balancedColumns(int width, int minTile, int apps) {
        int max = Math.max(3, width / Math.max(1, minTile));
        if (apps <= 0) return max;
        int rows = (apps + max - 1) / max;
        return Math.max(3, (apps + rows - 1) / rows);
    }

    /** A press counts as a tap when the finger stayed within the slop and lifted before a long press. */
    static boolean isTap(float dx, float dy, float slop, long heldMs, long longPressMs) {
        return Math.abs(dx) <= slop && Math.abs(dy) <= slop && heldMs < longPressMs;
    }
}
