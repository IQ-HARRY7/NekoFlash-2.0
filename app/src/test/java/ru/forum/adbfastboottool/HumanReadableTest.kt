package ru.forum.adbfastboottool

import org.junit.Assert.assertEquals
import org.junit.Test

class HumanReadableTest {
    @Test fun bytesUseBinaryUnitsWithTwoDecimals() {
        assertEquals("0 B", HumanReadable.bytes(0))
        assertEquals("1023 B", HumanReadable.bytes(1023))
        assertEquals("1.00 KB", HumanReadable.bytes(1024))
        assertEquals("1.50 KB", HumanReadable.bytes(1536))
        assertEquals("3.42 MB", HumanReadable.bytes(3_586_949))
        assertEquals("2.00 GB", HumanReadable.bytes(2L * 1024 * 1024 * 1024))
        assertEquals("0 B", HumanReadable.bytes(-5))
    }

    @Test fun durationReadsLikeAPerson() {
        assertEquals("0 ms", HumanReadable.duration(0))
        assertEquals("850 ms", HumanReadable.duration(850))
        assertEquals("42s", HumanReadable.duration(42_000))
        assertEquals("1m 5s", HumanReadable.duration(65_000))
        assertEquals("1h 2m 5s", HumanReadable.duration(3_725_000))
        assertEquals("unknown", HumanReadable.duration(-1))
    }

    @Test fun rateAppendsPerSecond() {
        assertEquals("1.00 MB/s", HumanReadable.rate(1024.0 * 1024.0))
        assertEquals("0 B/s", HumanReadable.rate(-1.0))
    }
}
