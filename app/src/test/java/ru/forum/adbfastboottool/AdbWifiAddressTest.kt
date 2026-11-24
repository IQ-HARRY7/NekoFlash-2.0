package ru.forum.adbfastboottool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbWifiAddressTest {
    @Test fun parsesIpv4AndHostnameEndpoints() {
        assertEquals(AdbWifiAddress("192.168.1.22", 37149), AdbWifiAddress.parse(" 192.168.1.22:37149 \n"))
        assertEquals(AdbWifiAddress("device.local", 5555), AdbWifiAddress.parse("device.local:5555"))
        assertEquals(AdbWifiAddress("device.local"), AdbWifiAddress.parse("device.local"))
    }

    @Test fun bracketedIpv6SupportsPortsAndBareIpv6KeepsTheAddress() {
        assertEquals(AdbWifiAddress("fd12::1234", 42421), AdbWifiAddress.parse("[fd12::1234]:42421"))
        assertEquals(AdbWifiAddress("fe80::1234%wlan0", 42421), AdbWifiAddress.parse("[fe80::1234%wlan0]:42421"))
        assertEquals(AdbWifiAddress("fd12::1234"), AdbWifiAddress.parse("[fd12::1234]"))
        assertEquals(AdbWifiAddress("fd12::1234"), AdbWifiAddress.parse("fd12::1234"))
    }

    @Test fun rejectsIncompleteEndpointsAndOutOfRangePorts() {
        for (input in listOf("", ":5555", "host:", "host:0", "host:65536", "[::1", "[::1]5555", "[]:5555")) {
            assertNull(input, AdbWifiAddress.parse(input))
        }
        assertEquals(1, AdbWifiAddress.parsePort("1"))
        assertEquals(65535, AdbWifiAddress.parsePort("65535"))
    }
}
