package ru.forum.adbfastboottool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbWifiEndpointMatchTest {
    @Test fun connectionServiceMustBelongToPairedGuid() {
        assertTrue(AdbWifiEndpointMatch.isPairMate("adb-A2B45C-Xp7d", "A2B45C"))
        assertTrue(AdbWifiEndpointMatch.isPairMate("adb-A2B45C-Xp7d", "adb-A2B45C"))
        assertTrue(AdbWifiEndpointMatch.isPairMate("adb-A2B45C-Xp7d", "a2b45c"))
        assertFalse(AdbWifiEndpointMatch.isPairMate("adb-A2B45C-Xp7d", "A2B45"))
        assertFalse(AdbWifiEndpointMatch.isPairMate("adb-DIFFERENT-Ab9c", "A2B45C"))
        assertFalse(AdbWifiEndpointMatch.isPairMate("adb-A2B45C-Xp7d", ""))
    }

    @Test fun advertisedPairingAndConnectionEndpointsKeepTheirKind() {
        val pairing = AdbWifiDiscovery.Endpoint(
            "adb-A2B45C-Kd2x", "192.168.1.30", 37415, AdbWifiDiscovery.Kind.PAIR
        )
        val connection = AdbWifiDiscovery.Endpoint(
            "adb-A2B45C-Qm4p", "192.168.1.30", 40211, AdbWifiDiscovery.Kind.CONNECT
        )
        assertFalse(pairing.key == connection.key)
        assertTrue(AdbWifiEndpointMatch.isPairMate(connection.name, "A2B45C"))
        assertTrue(AdbWifiDiscovery.Kind.PAIR.serviceType == "_adb-tls-pairing._tcp.")
        assertTrue(AdbWifiDiscovery.Kind.CONNECT.serviceType == "_adb-tls-connect._tcp.")
    }
}
