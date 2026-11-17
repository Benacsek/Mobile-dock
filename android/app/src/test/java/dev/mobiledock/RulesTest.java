package dev.mobiledock;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RulesTest {
    @Test
    public void pairingLinksOnlyReachPrivateAddresses() {
        for (String ok : new String[] {"192.168.1.20", "10.0.0.5", "172.16.0.1", "172.31.9.9", "127.0.0.1",
            "169.254.3.3", "100.64.0.1"}) {
            assertTrue(ok, Rules.isLocalAddress(ok));
        }
        for (String bad : new String[] {"8.8.8.8", "172.32.0.1", "192.169.1.1", "192.168.1.300", "evil.example",
            "", null, "1.2.3", "192.168.1.1.5"}) {
            assertFalse(String.valueOf(bad), Rules.isLocalAddress(bad));
        }
    }

    @Test
    public void appsSpreadEvenlyOverRows() {
        // 640 dp wide with 112 dp tiles fits 5. 8 apps then make two rows of 4, not 5 and 3.
        assertEquals(4, Rules.balancedColumns(640, 112, 8));
        assertEquals(5, Rules.balancedColumns(640, 112, 5));
        assertEquals(3, Rules.balancedColumns(640, 112, 2));  // never fewer than 3
        assertEquals(5, Rules.balancedColumns(640, 112, 0));
        assertEquals(3, Rules.balancedColumns(100, 112, 9));  // tiny screens still get 3
    }

    @Test
    public void tabletTilesGrowToFillTheHeight() {
        assertEquals(2f, Rules.fillScale(1040, 680, 112, 124, 8, 2f), 0.001f);  // two big rows of 4
        assertEquals(1f, Rules.fillScale(560, 250, 112, 124, 8, 2f), 0.001f);  // a phone: no room to grow
        float s = Rules.fillScale(1040, 680, 112, 124, 20, 2f);
        int cols = Rules.balancedColumns(1040, Math.round(112 * s), 20);
        assertTrue(((20 + cols - 1) / cols) * 124 * s <= 680);  // many apps: as big as still fits
        assertTrue(s > 1f);
    }

    @Test
    public void onlyRealTapsBuzz() {
        assertTrue(Rules.isTap(2, 3, 8, 120, 500));
        assertFalse(Rules.isTap(20, 0, 8, 120, 500));  // a scroll that started on a button
        assertFalse(Rules.isTap(0, 0, 8, 600, 500));  // a long press, for example dragging an app
    }

    @Test
    public void magicPacketRepeatsTheAddress() {
        byte[] p = WakeOnLan.packet("aa:bb:cc:dd:ee:0f");
        assertEquals(102, p.length);
        for (int i = 0; i < 6; i++) assertEquals((byte) 0xFF, p[i]);
        byte[] mac = {(byte) 0xaa, (byte) 0xbb, (byte) 0xcc, (byte) 0xdd, (byte) 0xee, (byte) 0x0f};
        for (int r = 0; r < 16; r++) {
            byte[] copy = new byte[6];
            System.arraycopy(p, 6 + r * 6, copy, 0, 6);
            assertArrayEquals(mac, copy);
        }
    }

    @Test
    public void sharedFilesCannotReachOurOwnStorage() {
        String pkg = "dev.mobiledock", data = "/data/data/dev.mobiledock";
        assertTrue(Rules.isSafeShare("content", "com.android.providers.media.documents", null, pkg, data));
        assertTrue(Rules.isSafeShare("file", null, "/storage/emulated/0/DCIM/photo.jpg", pkg, data));
        // Our own provider and our own private files, where the pairing keys are.
        assertFalse(Rules.isSafeShare("content", "dev.mobiledock.apk", null, pkg, data));
        assertFalse(Rules.isSafeShare("content", "dev.mobiledock", null, pkg, data));
        assertFalse(Rules.isSafeShare("file", null, "/data/data/dev.mobiledock/shared_prefs/dock.xml", pkg, data));
        assertFalse(Rules.isSafeShare("file", null, "/data/data/dev.mobiledock", pkg, data));
        assertTrue(Rules.isSafeShare("file", null, "/data/data/dev.mobiledockother/x", pkg, data));  // not a prefix trick
        assertFalse(Rules.isSafeShare("file", null, null, pkg, data));
        assertFalse(Rules.isSafeShare("http", "example.com", null, pkg, data));
        assertFalse(Rules.isSafeShare("content", null, null, pkg, data));
    }

    @Test(expected = IllegalArgumentException.class)
    public void badMacIsRejected() {
        WakeOnLan.packet("not-a-mac");
    }

    @Test
    public void monthGridStartsOnTheRightWeekday() {
        // November 2026 starts on a Sunday and has 30 days.
        int[] mondayFirst = Rules.monthGrid(2026, 10, java.util.Calendar.MONDAY);
        assertEquals(0, mondayFirst[5]);
        assertEquals(1, mondayFirst[6]);  // Sunday is the last column when weeks start on Monday
        assertEquals(30, mondayFirst[35]);
        assertEquals(0, mondayFirst[36]);
        int[] sundayFirst = Rules.monthGrid(2026, 10, java.util.Calendar.SUNDAY);
        assertEquals(1, sundayFirst[0]);
        assertEquals(30, sundayFirst[29]);
        // February 2028 is a leap month: 29 days, starting on a Tuesday.
        int[] leap = Rules.monthGrid(2028, 1, java.util.Calendar.MONDAY);
        assertEquals(1, leap[1]);
        assertEquals(29, leap[29]);
        assertEquals(0, leap[30]);
    }

    @Test
    public void idleTilesFollowTheChecklist() {
        int[] order = {5, 0, 1, 2, 3};  // Clock, Date, Calendar, Playing, Battery
        int all = (1 << 5) | 1 | 2 | 4 | 8;
        assertArrayEquals(new int[] {5, 0, 1, 2}, Rules.checkedTiles(all, order, 4));  // at most 4
        assertArrayEquals(new int[] {2, 3}, Rules.checkedTiles(4 | 8, order, 4));
        assertArrayEquals(new int[0], Rules.checkedTiles(0, order, 4));
        // 2.2.0 kept two slots. Date and None become just Date, Playing and Battery stay both.
        assertEquals(1, Rules.legacyTileMask(0, 4, 4, 6));
        assertEquals(4 | 8, Rules.legacyTileMask(2, 3, 4, 6));
        assertEquals(0, Rules.legacyTileMask(4, 4, 4, 6));
        assertArrayEquals(new int[] {1, 1}, Rules.gridRows(2));
        assertArrayEquals(new int[] {1, 2}, Rules.gridRows(3));  // one wide tile over two
        assertArrayEquals(new int[] {2, 2}, Rules.gridRows(4));
    }

    @Test
    public void idleTilesKeepTheirOrder() {
        int[] defaults = {5, 0, 1, 2, 3};
        assertArrayEquals(defaults, Rules.tileOrder(null, defaults));
        assertArrayEquals(new int[] {2, 5, 0, 1, 3}, Rules.tileOrder("2,5", defaults));  // missing ones follow
        assertArrayEquals(new int[] {3, 5, 0, 1, 2}, Rules.tileOrder("3,9,3,x", defaults));  // bad parts dropped
        assertEquals("2,5,0,1,3", Rules.joinOrder(new int[] {2, 5, 0, 1, 3}));
        // Clock, Playing and Battery checked. Playing moves up past the unchecked Date and Calendar.
        int mask = (1 << 5) | 4 | 8;
        assertArrayEquals(new int[] {2, 0, 1, 5, 3}, Rules.moveTile(defaults, mask, 2, -1));
        assertArrayEquals(new int[] {5, 0, 1, 3, 2}, Rules.moveTile(defaults, mask, 2, 1));
        assertArrayEquals(defaults, Rules.moveTile(defaults, mask, 5, -1));  // already first
        assertArrayEquals(new int[] {2, 5, 3}, Rules.checkedTiles(mask, Rules.moveTile(defaults, mask, 2, -1), 4));
    }

    @Test
    public void phonesGetTheRailAndTabletsTheSidebar() {
        assertTrue(Rules.useRail(320));  // the small-phone check in CI
        assertTrue(Rules.useRail(360));  // the CI phone
        assertTrue(Rules.useRail(412));  // a Pixel 7 in landscape
        assertFalse(Rules.useRail(600));
        assertFalse(Rules.useRail(800));  // a 10 inch tablet
    }
}
