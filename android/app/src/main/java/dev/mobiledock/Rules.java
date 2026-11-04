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

    /**
     * Whether a file another app hands us may be read and sent to the PC. It must never point into
     * Mobile Dock's own storage or its own provider, where the pairing keys live: a malicious app could
     * otherwise "share" those and have them copied to the PC. Old Android file pickers still return
     * file:// paths, so those are allowed when they lie outside our data folder.
     *
     * @param canonicalPath the file's canonical path for file://, or null
     * @param ourDataDir our app's canonical data folder, such as /data/data/dev.mobiledock
     */
    static boolean isSafeShare(String scheme, String authority, String canonicalPath, String ourPackage,
                               String ourDataDir) {
        if ("content".equals(scheme)) {
            return authority != null && authority.length() > 0
                    && !authority.equals(ourPackage) && !authority.startsWith(ourPackage + ".");
        }
        if ("file".equals(scheme)) {
            if (canonicalPath == null || ourDataDir == null || ourDataDir.length() == 0) return false;
            return !canonicalPath.equals(ourDataDir) && !canonicalPath.startsWith(ourDataDir + "/");
        }
        return false;
    }

    /** A press counts as a tap when the finger stayed within the slop and lifted before a long press. */
    static boolean isTap(float dx, float dy, float slop, long heldMs, long longPressMs) {
        return Math.abs(dx) <= slop && Math.abs(dy) <= slop && heldMs < longPressMs;
    }
}
