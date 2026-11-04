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
}
