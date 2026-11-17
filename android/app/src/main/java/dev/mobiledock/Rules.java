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

    /**
     * A month as 6 rows of 7 days for the calendar tile. 0 means an empty cell.
     * month is 0 for January. firstDay is the locale's first weekday: 1 Sunday, 2 Monday, as in Calendar.
     */
    static int[] monthGrid(int year, int month, int firstDay) {
        java.util.Calendar c = new java.util.GregorianCalendar(year, month, 1);
        int offset = (c.get(java.util.Calendar.DAY_OF_WEEK) - firstDay + 7) % 7;
        int days = c.getActualMaximum(java.util.Calendar.DAY_OF_MONTH);
        int[] cells = new int[42];
        for (int d = 1; d <= days; d++) cells[offset + d - 1] = d;
        return cells;
    }

    /** The idle side tiles that are checked in mask (bit 1 << tile), in the order given, at most max of them. */
    static int[] checkedTiles(int mask, int[] order, int max) {
        int[] out = new int[Math.min(max, Integer.bitCount(mask))];
        int n = 0;
        for (int tile : order) if (n < out.length && (mask & (1 << tile)) != 0) out[n++] = tile;
        return n == out.length ? out : java.util.Arrays.copyOf(out, n);
    }

    /** Turns the two side tiles of version 2.2.0 into a checklist mask, so nobody loses their choice. */
    static int legacyTileMask(int side, int side2, int none, int count) {
        int mask = 0;
        for (int s : new int[] {side, side2}) if (s >= 0 && s < count && s != none) mask |= 1 << s;
        return mask;
    }

    /** Phones get the narrow icon rail, tablets keep the sidebar with labels. Uses the screen's short side in dp. */
    static boolean useRail(int shortSideDp) {
        return shortSideDp < 600;
    }

    /** How many side tiles sit in each row: one or two stack, three put one wide tile over two, four make 2 x 2. */
    static int[] gridRows(int n) {
        if (n <= 0) return new int[0];
        if (n <= 2) return n == 1 ? new int[] {1} : new int[] {1, 1};
        return n == 3 ? new int[] {1, 2} : new int[] {2, 2};
    }

    /** The saved tile order, such as "3,5,0,1,2". Unknown or repeated tiles are dropped, missing ones added. */
    static int[] tileOrder(String saved, int[] defaults) {
        int[] out = new int[defaults.length];
        int n = 0;
        for (String part : (saved == null ? "" : saved).split(",")) {
            try {
                int tile = Integer.parseInt(part.trim());
                if (indexOf(defaults, tile) >= 0 && indexOf(java.util.Arrays.copyOf(out, n), tile) < 0) out[n++] = tile;
            } catch (NumberFormatException ignored) {
                // skip it
            }
        }
        for (int tile : defaults) if (indexOf(java.util.Arrays.copyOf(out, n), tile) < 0) out[n++] = tile;
        return out;
    }

    /** Moves a checked tile one place up (-1) or down (+1) among the checked tiles. */
    static int[] moveTile(int[] order, int mask, int tile, int dir) {
        int[] out = order.clone();
        int at = indexOf(out, tile);
        for (int i = at + dir; at >= 0 && i >= 0 && i < out.length; i += dir) {
            if ((mask & (1 << out[i])) == 0) continue;
            out[at] = out[i];
            out[i] = tile;
            break;
        }
        return out;
    }

    static String joinOrder(int[] order) {
        StringBuilder sb = new StringBuilder();
        for (int tile : order) sb.append(sb.length() > 0 ? "," : "").append(tile);
        return sb.toString();
    }

    private static int indexOf(int[] a, int v) {
        for (int i = 0; i < a.length; i++) if (a[i] == v) return i;
        return -1;
    }
}
